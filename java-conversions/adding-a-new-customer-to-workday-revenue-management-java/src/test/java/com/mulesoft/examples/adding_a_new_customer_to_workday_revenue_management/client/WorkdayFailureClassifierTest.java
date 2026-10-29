/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Named.named;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.xml.namespace.QName;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.oxm.MarshallingFailureException;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.soap.SoapBody;
import org.springframework.ws.soap.SoapFault;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.soap.client.SoapFaultClientException;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;

import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.client.UpstreamFailureKind.Category;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.config.WorkdayProperties;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.exception.UpstreamRateLimitException;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.exception.UpstreamUnavailableException;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.CustomerWWSDataType;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.ObjectFactory;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.PutCustomerRequestType;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.PutCustomerResponseType;

import jakarta.xml.bind.JAXBElement;
import jakarta.xml.soap.MessageFactory;
import jakarta.xml.soap.SOAPConstants;
import jakarta.xml.soap.SOAPException;

/**
 * Unit test of {@link WorkdayFailureClassifier}, the Workday classification of the shared connector failure handling
 * (SC-9).
 *
 * <p>The failures are the types {@link WorkdayRevenueManagementClient} lets escape: its
 * {@link WorkdayRevenueManagementClient.HttpStatusException}, Spring WS's {@link SoapFaultClientException} parsed
 * from a SOAP 1.1 fault envelope, and {@link WebServiceIOException} carrying the socket failure. Where a
 * classification decides whether a {@code Put_Customer} is repeated, the decision is proved through a real
 * {@link UpstreamCallExecutor}: first with a scripted call that counts its attempts, then end to end with the real
 * client against a WireMock Workday that counts the requests it receives. Netty is not on this project's classpath,
 * so its {@code ReadTimeoutException} is a class of that name generated with Byte Buddy; the classifier recognises it
 * by name. Every value is fake and every port is ephemeral.
 */
class WorkdayFailureClassifierTest {

    private static final String SOAP_11_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    private static final String WSSE_NS =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";

    private static final String WD_NS = "urn:com.workday/bsvc";

    private static final String WSSE_DECLARATION = "xmlns:wsse=\"" + WSSE_NS + "\"";

    private static final String FAILED_AUTHENTICATION_STRING =
            "The security token could not be authenticated or authorized";

    private static final String INVALID_SECURITY_STRING =
            "An error was discovered processing the <wsse:Security> header";

    private static final String VALIDATION_FAULT_STRING =
            "Validation error occurred. Invalid ID value. 'NOPE' is not a valid ID value for type = 'Customer_ID'";

    private static final String PATH = "/ccx/service/test_pt1/Revenue_Management/v25.0";

    private static final String CUSTOMER_NAME = "John Classifier";

    private static final String ADDED = "Added customer";

    private static final Duration READ_TIMEOUT = Duration.ofMillis(800);

    private static final String PUT_CUSTOMER_RESPONSE = "<env:Envelope xmlns:env=\"" + SOAP_11_NS + "\">"
            + "<env:Body>"
            + "<wd:Put_Customer_Response xmlns:wd=\"" + WD_NS + "\" wd:version=\"v25.0\">"
            + "<wd:Customer_Reference wd:Descriptor=\"" + CUSTOMER_NAME + "\">"
            + "<wd:ID wd:type=\"WID\">0123456789abcdef</wd:ID>"
            + "</wd:Customer_Reference>"
            + "</wd:Put_Customer_Response>"
            + "</env:Body>"
            + "</env:Envelope>";

    /** SOAP 1.1 message factory the fault envelopes are parsed with, as the client's template parses them. */
    private static final SaajSoapMessageFactory MESSAGE_FACTORY = soapMessageFactory();

    /**
     * A stand-in for Netty's {@code ReadTimeoutException}: a {@link RuntimeException} subclass carrying Netty's
     * fully qualified class name, defined in a child-first class loader of its own.
     */
    private static final Class<? extends RuntimeException> NETTY_READ_TIMEOUT = new ByteBuddy()
            .subclass(RuntimeException.class)
            .name(UpstreamFailureClassifier.NETTY_READ_TIMEOUT_EXCEPTION)
            .make()
            .load(WorkdayFailureClassifierTest.class.getClassLoader(), ClassLoadingStrategy.Default.CHILD_FIRST)
            .getLoaded();

    @RegisterExtension
    static final WireMockExtension WORKDAY = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    /** This case's own {@code wday.*} settings, built before each case; the client reads its endpoint at call time. */
    private WorkdayProperties properties;

    /**
     * This case's own client over {@link #properties}, built before each case, so neither an endpoint nor a
     * re-authenticated security interceptor carries over from another case.
     */
    private WorkdayRevenueManagementClient client;

    private final WorkdayFailureClassifier classifier = new WorkdayFailureClassifier();

    private final UpstreamCallExecutor executor = new UpstreamCallExecutor(List.of(new WorkdayFailureClassifier()));

    private final AtomicInteger calls = new AtomicInteger();

    private final AtomicInteger reauthentications = new AtomicInteger();

    @BeforeEach
    void createClient() {
        properties = new WorkdayProperties();
        properties.setHostname("localhost");
        properties.setTenant("test_pt1");
        properties.setUser("testuser");
        properties.setPassword("test-password");
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setReadTimeout(READ_TIMEOUT);
        client = new WorkdayRevenueManagementClient(properties,
                new UpstreamCallExecutor(List.of(new WorkdayFailureClassifier())));
    }

    // system() and null

    @Test
    void servesWorkday() {
        assertThat(classifier.system()).isEqualTo(UpstreamSystem.WORKDAY);
        assertThat(classifier.system().displayName()).isEqualTo("Workday");
    }

    @Test
    void nullIsOther() {
        assertThat(classifier.classify(null)).isEqualTo(UpstreamFailureKind.OTHER);
    }

    // HTTP 401 and WS-Security faults → authentication

    static Stream<Named<Supplier<Throwable>>> authenticationRejections() {
        return Stream.of(
                named("HTTP 401", () -> httpStatus(401, null)),
                named("RuntimeException caused by HTTP 401",
                        () -> new RuntimeException("wrapped", httpStatus(401, null))),
                named("wsse:FailedAuthentication fault", () -> soapFault(WSSE_DECLARATION,
                        "wsse:FailedAuthentication", FAILED_AUTHENTICATION_STRING)),
                named("wsse:InvalidSecurity fault", () -> soapFault(WSSE_DECLARATION,
                        "wsse:InvalidSecurity", INVALID_SECURITY_STRING)),
                named("wsse:FailedAuthentication fault with the wsse prefix undeclared",
                        () -> soapFault("", "wsse:FailedAuthentication", FAILED_AUTHENTICATION_STRING)),
                named("RuntimeException caused by a wsse:InvalidSecurity fault", () -> new RuntimeException("wrapped",
                        soapFault(WSSE_DECLARATION, "wsse:InvalidSecurity", INVALID_SECURITY_STRING))),
                named("cyclic chain holding HTTP 401",
                        () -> cyclic(new RuntimeException("wrapper"), httpStatus(401, null))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("authenticationRejections")
    void authenticationRejectionsAreAuthentication(Supplier<Throwable> failure) {
        assertThat(classify(failure.get())).isEqualTo(UpstreamFailureKind.AUTHENTICATION);
    }

    /** The parsed fault envelopes carry the WS-Security code the classifier matches on, not a lookalike. */
    @Test
    void parsedSecurityFaultsCarryTheirWsseFaultCode() {
        assertThat(soapFault(WSSE_DECLARATION, "wsse:FailedAuthentication", FAILED_AUTHENTICATION_STRING)
                .getFaultCode()).isEqualTo(new QName(WSSE_NS, "FailedAuthentication"));
        assertThat(soapFault(WSSE_DECLARATION, "wsse:InvalidSecurity", INVALID_SECURITY_STRING)
                .getFaultCode()).isEqualTo(new QName(WSSE_NS, "InvalidSecurity"));
        assertThat(soapFault("", "wsse:FailedAuthentication", FAILED_AUTHENTICATION_STRING)
                .getFaultCode().getLocalPart()).isEqualTo("FailedAuthentication");
    }

    // HTTP 429 → rate limit with Workday's Retry-After

    static Stream<Arguments> rateLimits() {
        return Stream.of(
                Arguments.of(named("HTTP 429 with Retry-After 30", httpStatus(429, "30")), "30"),
                Arguments.of(named("HTTP 429 without Retry-After", httpStatus(429, null)), null),
                Arguments.of(named("RuntimeException caused by HTTP 429 with Retry-After 120",
                        new RuntimeException("wrapped", httpStatus(429, "120"))), "120"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rateLimits")
    void http429IsARateLimitCarryingRetryAfter(Throwable failure, String retryAfter) {
        UpstreamFailureKind kind = classify(failure);

        assertThat(kind.category()).isEqualTo(Category.RATE_LIMIT);
        assertThat(kind.retryAfter()).isEqualTo(retryAfter);
        assertThat(kind.retryAfterValue()).isEqualTo(Optional.ofNullable(retryAfter));
    }

    // Timeouts and connectivity failures → unavailable

    static Stream<Named<Supplier<Throwable>>> unavailableFailures() {
        List<Named<Supplier<IOException>>> socketCauses = List.of(
                named("SocketTimeoutException", () -> new SocketTimeoutException("Read timed out")),
                named("HttpTimeoutException", () -> new HttpTimeoutException("request timed out")),
                named("ConnectException", () -> new ConnectException("Connection refused")),
                named("UnknownHostException", () -> new UnknownHostException("wd2-impl-services1.workday.com")),
                named("NoRouteToHostException", () -> new NoRouteToHostException("No route to host")),
                named("SocketException (connection reset)", () -> new SocketException("Connection reset")));
        // Spring WS wraps the transport's IOException as the cause of a WebServiceIOException.
        Stream<Named<Supplier<Throwable>>> wrapped = socketCauses.stream().map(cause -> named(
                "WebServiceIOException caused by " + cause.getName(),
                () -> new WebServiceIOException("I/O error: " + cause.getName(), cause.getPayload().get())));
        Stream<Named<Supplier<Throwable>>> netty = Stream.of(
                named("Netty ReadTimeoutException", WorkdayFailureClassifierTest::nettyReadTimeout),
                named("RuntimeException caused by Netty ReadTimeoutException",
                        () -> new RuntimeException("wrapped", nettyReadTimeout())));
        Stream<Named<Supplier<Throwable>>> nested = Stream.of(
                named("RuntimeException caused by WebServiceIOException caused by SocketTimeoutException",
                        () -> new RuntimeException("wrapped", new WebServiceIOException("I/O error: Read timed out",
                                new SocketTimeoutException("Read timed out")))),
                named("WebServiceIOException caused by IOException caused by Netty ReadTimeoutException",
                        () -> new WebServiceIOException("I/O error", new IOException(nettyReadTimeout()))));
        Stream<Named<Supplier<Throwable>>> mixed = Stream.of(
                named("HTTP 401 caused by SocketTimeoutException",
                        () -> causedBy(httpStatus(401, null), new SocketTimeoutException("Read timed out"))),
                named("HTTP 429 caused by ConnectException",
                        () -> causedBy(httpStatus(429, "30"), new ConnectException("Connection refused"))),
                named("wsse:FailedAuthentication fault caused by SocketException", () -> causedBy(
                        soapFault(WSSE_DECLARATION, "wsse:FailedAuthentication", FAILED_AUTHENTICATION_STRING),
                        new SocketException("Connection reset"))),
                named("cyclic chain holding a SocketTimeoutException",
                        () -> cyclic(new RuntimeException("wrapper"), new SocketTimeoutException("Read timed out"))));
        return Stream.of(wrapped, netty, nested, mixed).flatMap(shapes -> shapes);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unavailableFailures")
    void timeoutsAndConnectivityFailuresAreUnavailable(Supplier<Throwable> failure) {
        assertThat(classify(failure.get())).isEqualTo(UpstreamFailureKind.UNAVAILABLE);
    }

    /**
     * Every unavailable failure, an authentication signal in the same chain included, ends after one attempt and no
     * re-authentication, so a {@code Put_Customer} that may already have been processed is never sent again.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unavailableFailures")
    void timeoutsAndConnectivityFailuresAreNeverRetried(Supplier<Throwable> failure) {
        Exception unavailable = (Exception) failure.get();

        Throwable thrown = catchThrowable(() -> callFailingWith(unavailable));

        assertThat(thrown).isInstanceOfSatisfying(UpstreamUnavailableException.class, exception -> {
            assertThat(exception.getSystem()).isEqualTo(UpstreamSystem.WORKDAY);
            assertThat(exception.getCause()).isSameAs(unavailable);
        });
        assertThat(calls).hasValue(1);
        assertThat(reauthentications).hasValue(0);
    }

    // Everything else → other, rethrown unchanged

    static Stream<Named<Supplier<Throwable>>> otherFailures() {
        return Stream.of(
                named("HTTP 400", () -> httpStatus(400, null)),
                named("HTTP 403", () -> httpStatus(403, null)),
                named("HTTP 404", () -> httpStatus(404, null)),
                named("HTTP 500 without a fault", () -> httpStatus(500, null)),
                named("HTTP 502", () -> httpStatus(502, null)),
                named("HTTP 503 with Retry-After", () -> httpStatus(503, "30")),
                named("SOAP-ENV:Client fault", () -> soapFault("", "SOAP-ENV:Client", VALIDATION_FAULT_STRING)),
                named("Workday Validation_Fault (SOAP-ENV:Client.validationError)",
                        () -> soapFault("", "SOAP-ENV:Client.validationError", VALIDATION_FAULT_STRING)),
                named("Workday Processing_Fault (SOAP-ENV:Server.processingError)",
                        () -> soapFault("", "SOAP-ENV:Server.processingError", "Processing error occurred.")),
                named("another WS-Security fault code (wsse:UnsupportedSecurityToken)", () -> soapFault(
                        WSSE_DECLARATION, "wsse:UnsupportedSecurityToken", "An unsupported token was provided")),
                named("SOAP fault whose code cannot be read", WorkdayFailureClassifierTest::unreadableFault),
                named("SOAP message without a Fault element", WorkdayFailureClassifierTest::faultWithoutCode),
                named("MarshallingFailureException", () -> new MarshallingFailureException("cannot marshal")),
                named("IllegalStateException", () -> new IllegalStateException("Unexpected Workday response")),
                named("WebServiceIOException caused by an IOException that is no socket failure",
                        () -> new WebServiceIOException("I/O error", new IOException("stream closed"))),
                named("cyclic chain without a Workday signal",
                        () -> cyclic(new RuntimeException("wrapper"), new IllegalStateException("inner"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("otherFailures")
    void everythingElseIsOther(Supplier<Throwable> failure) {
        assertThat(classify(failure.get())).isEqualTo(UpstreamFailureKind.OTHER);
    }

    @Test
    void upstreamMessageIsTheFaultStringOrTheHttpReasonPhrase() {
        assertThat(classifier.upstreamMessage(
                soapFault(WSSE_DECLARATION, "wsse:FailedAuthentication", FAILED_AUTHENTICATION_STRING)))
                .isEqualTo(FAILED_AUTHENTICATION_STRING);
        assertThat(classifier.upstreamMessage(httpStatus(429, "30"))).isEqualTo("Too Many Requests [429]");
    }

    // Executor runs with a scripted call

    @Test
    void http401IsReauthenticatedAndRetriedOnce() throws Exception {
        String result = callFailingWith(httpStatus(401, null));

        assertThat(result).isEqualTo(ADDED);
        assertThat(calls).hasValue(2);
        assertThat(reauthentications).hasValue(1);
    }

    static Stream<Arguments> rejectedOnBothAttempts() {
        return Stream.of(
                Arguments.of(named("HTTP 401, then HTTP 401", httpStatus(401, null)), httpStatus(401, null)),
                Arguments.of(named("wsse:InvalidSecurity fault, then wsse:FailedAuthentication fault",
                                soapFault(WSSE_DECLARATION, "wsse:InvalidSecurity", INVALID_SECURITY_STRING)),
                        soapFault(WSSE_DECLARATION, "wsse:FailedAuthentication", FAILED_AUTHENTICATION_STRING)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedOnBothAttempts")
    void anAuthenticationRejectionOnBothAttemptsEndsWithAnAuthenticationFailure(Exception firstFailure,
            Exception retryFailure) {
        Throwable thrown = catchThrowable(() -> callFailingWith(firstFailure, retryFailure));

        assertThat(thrown).isInstanceOf(UpstreamAuthenticationException.class)
                .hasMessage(retryFailure.getMessage())
                .hasCause(retryFailure);
        assertThat(((UpstreamAuthenticationException) thrown).getSystem()).isEqualTo(UpstreamSystem.WORKDAY);
        assertThat(calls).hasValue(2);
        assertThat(reauthentications).hasValue(1);
    }

    @Test
    void http429IsNeverRetried() {
        Throwable thrown = catchThrowable(() -> callFailingWith(httpStatus(429, "30")));

        assertThat(thrown).isInstanceOf(UpstreamRateLimitException.class);
        assertThat(((UpstreamRateLimitException) thrown).getRetryAfter()).isEqualTo("30");
        assertThat(calls).hasValue(1);
        assertThat(reauthentications).hasValue(0);
    }

    @Test
    void aValidationFaultIsRethrownUnchanged() {
        SoapFaultClientException fault = soapFault("", "SOAP-ENV:Client.validationError", VALIDATION_FAULT_STRING);

        Throwable thrown = catchThrowable(() -> callFailingWith(fault));

        assertThat(thrown).isSameAs(fault);
        assertThat(calls).hasValue(1);
        assertThat(reauthentications).hasValue(0);
    }

    // End to end: the real client against a WireMock Workday

    @Test
    void putCustomerRejectedWithHttp401IsSentTwiceAndSucceeds() throws Exception {
        WORKDAY.stubFor(post(urlEqualTo(PATH)).inScenario("reauthentication").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(401).withHeader("Content-Type", "text/plain")
                        .withBody("Unauthorized"))
                .willSetStateTo("re-authenticated"));
        WORKDAY.stubFor(post(urlEqualTo(PATH)).inScenario("reauthentication").whenScenarioStateIs("re-authenticated")
                .willReturn(soapResponse(200, PUT_CUSTOMER_RESPONSE)));
        properties.setEndpoint(WORKDAY.baseUrl() + PATH);

        PutCustomerResponseType response = client.putCustomer(putCustomerRequest());

        assertThat(response.getCustomerReference().getDescriptor()).isEqualTo(CUSTOMER_NAME);
        assertThat(WORKDAY.findAll(postRequestedFor(urlEqualTo(PATH)))).hasSize(2);
    }

    @Test
    void putCustomerRejectedByWsSecurityIsSentTwiceThenFails() {
        WORKDAY.stubFor(post(urlEqualTo(PATH)).willReturn(soapResponse(500,
                faultEnvelope(WSSE_DECLARATION, "wsse:FailedAuthentication", FAILED_AUTHENTICATION_STRING))));
        properties.setEndpoint(WORKDAY.baseUrl() + PATH);

        Throwable thrown = catchThrowable(() -> client.putCustomer(putCustomerRequest()));

        assertThat(thrown).isInstanceOf(UpstreamAuthenticationException.class)
                .hasMessage(FAILED_AUTHENTICATION_STRING)
                .hasCauseInstanceOf(SoapFaultClientException.class);
        assertThat(WORKDAY.findAll(postRequestedFor(urlEqualTo(PATH)))).hasSize(2);
    }

    @Test
    void putCustomerThrottledWithHttp429IsSentOnce() {
        WORKDAY.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(429)
                .withHeader("Content-Type", "text/plain").withHeader("Retry-After", "30")
                .withBody("Too Many Requests")));
        properties.setEndpoint(WORKDAY.baseUrl() + PATH);

        Throwable thrown = catchThrowable(() -> client.putCustomer(putCustomerRequest()));

        assertThat(thrown).isInstanceOf(UpstreamRateLimitException.class);
        assertThat(((UpstreamRateLimitException) thrown).getRetryAfter()).isEqualTo("30");
        assertThat(WORKDAY.findAll(postRequestedFor(urlEqualTo(PATH)))).hasSize(1);
    }

    @Test
    void putCustomerThatTimesOutIsSentOnce() {
        WORKDAY.stubFor(post(urlEqualTo(PATH)).willReturn(soapResponse(200, PUT_CUSTOMER_RESPONSE)
                .withFixedDelay((int) READ_TIMEOUT.multipliedBy(3).toMillis())));
        properties.setEndpoint(WORKDAY.baseUrl() + PATH);

        Throwable thrown = catchThrowable(() -> client.putCustomer(putCustomerRequest()));

        assertThat(thrown).isInstanceOf(UpstreamUnavailableException.class)
                .hasCauseInstanceOf(WebServiceIOException.class)
                .hasRootCauseInstanceOf(SocketTimeoutException.class);
        assertThat(WORKDAY.findAll(postRequestedFor(urlEqualTo(PATH)))).hasSize(1);
    }

    @Test
    void putCustomerRejectedWithAValidationFaultIsSentOnceAndRethrown() {
        WORKDAY.stubFor(post(urlEqualTo(PATH)).willReturn(soapResponse(500,
                faultEnvelope("", "SOAP-ENV:Client.validationError", VALIDATION_FAULT_STRING))));
        properties.setEndpoint(WORKDAY.baseUrl() + PATH);

        Throwable thrown = catchThrowable(() -> client.putCustomer(putCustomerRequest()));

        assertThat(thrown).isInstanceOf(SoapFaultClientException.class).hasMessage(VALIDATION_FAULT_STRING);
        assertThat(WORKDAY.findAll(postRequestedFor(urlEqualTo(PATH)))).hasSize(1);
    }

    // Helpers

    /** Classifies {@code failure}, failing the test instead of hanging should a cause cycle never end. */
    private UpstreamFailureKind classify(Throwable failure) {
        return assertTimeoutPreemptively(Duration.ofSeconds(5), () -> classifier.classify(failure));
    }

    /**
     * Runs a call through the executor that raises {@code failures} on its first attempts, in order, and returns
     * {@link #ADDED} on the attempt after them, counting attempts and re-authentications.
     */
    private String callFailingWith(Exception... failures) throws Exception {
        return executor.call(UpstreamSystem.WORKDAY, () -> {
            int attempt = calls.getAndIncrement();
            if (attempt < failures.length) {
                throw failures[attempt];
            }
            return ADDED;
        }, reauthentications::incrementAndGet);
    }

    /** The exception the client's template raises for an HTTP error without a SOAP fault. */
    private static WorkdayRevenueManagementClient.HttpStatusException httpStatus(int status, String retryAfter) {
        String reason = switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            default -> throw new IllegalArgumentException("no reason phrase for HTTP " + status);
        };
        return new WorkdayRevenueManagementClient.HttpStatusException(reason + " [" + status + "]", status, retryAfter);
    }

    /**
     * Parses a SOAP 1.1 fault envelope with the client's message factory and wraps it as Spring WS does.
     *
     * @param faultNamespaces namespace declarations written on the {@code Fault} element, or empty
     * @param faultCode       the {@code faultcode} text, prefix included
     * @param faultString     the {@code faultstring} text
     */
    private static SoapFaultClientException soapFault(String faultNamespaces, String faultCode, String faultString) {
        byte[] envelope = faultEnvelope(faultNamespaces, faultCode, faultString).getBytes(StandardCharsets.UTF_8);
        try {
            return new SoapFaultClientException(
                    MESSAGE_FACTORY.createWebServiceMessage(new ByteArrayInputStream(envelope)));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot parse the SOAP fault envelope", e);
        }
    }

    private static String faultEnvelope(String faultNamespaces, String faultCode, String faultString) {
        return "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"" + SOAP_11_NS + "\">"
                + "<SOAP-ENV:Body>"
                + "<SOAP-ENV:Fault" + (faultNamespaces.isEmpty() ? "" : " " + faultNamespaces) + ">"
                + "<faultcode>" + faultCode + "</faultcode>"
                + "<faultstring>" + faultString.replace("<", "&lt;").replace(">", "&gt;") + "</faultstring>"
                + "</SOAP-ENV:Fault>"
                + "</SOAP-ENV:Body>"
                + "</SOAP-ENV:Envelope>";
    }

    /** A SOAP fault whose code accessor fails, as a SAAJ fault element it cannot read would. */
    private static SoapFaultClientException unreadableFault() {
        SoapMessage message = mock(SoapMessage.class);
        SoapBody body = mock(SoapBody.class);
        SoapFault fault = mock(SoapFault.class);
        when(message.getFaultReason()).thenReturn("unreadable fault");
        when(message.getSoapBody()).thenReturn(body);
        when(body.getFault()).thenReturn(fault);
        when(fault.getFaultCode()).thenThrow(new IllegalArgumentException("unbound fault code prefix"));
        return new SoapFaultClientException(message);
    }

    /** A {@link SoapFaultClientException} over an envelope whose Body holds no fault, so its fault code is null. */
    private static SoapFaultClientException faultWithoutCode() {
        SoapFaultClientException exception = new SoapFaultClientException(MESSAGE_FACTORY.createWebServiceMessage());
        assertThat(exception.getFaultCode()).isNull();
        return exception;
    }

    private static ResponseDefinitionBuilder soapResponse(int status, String envelope) {
        return aResponse().withStatus(status).withHeader("Content-Type", "text/xml;charset=UTF-8").withBody(envelope);
    }

    /** A {@code Put_Customer_Request} built from the generated v25.0 classes. */
    private static JAXBElement<PutCustomerRequestType> putCustomerRequest() {
        ObjectFactory factory = new ObjectFactory();
        CustomerWWSDataType customerData = factory.createCustomerWWSDataType();
        customerData.setCustomerName(CUSTOMER_NAME);
        PutCustomerRequestType request = factory.createPutCustomerRequestType();
        request.setCustomerData(customerData);
        return factory.createPutCustomerRequest(request);
    }

    /** Sets {@code cause} as the cause of {@code throwable}, which must have none yet, and returns it. */
    private static <T extends Throwable> T causedBy(T throwable, Throwable cause) {
        throwable.initCause(cause);
        return throwable;
    }

    /** Links {@code outer} and {@code inner}, neither with a cause yet, into a two-element cause cycle. */
    private static <T extends Throwable> T cyclic(T outer, Throwable inner) {
        outer.initCause(inner);
        inner.initCause(outer);
        return outer;
    }

    /** A new instance of the Netty {@code ReadTimeoutException} stand-in, without message or cause. */
    private static RuntimeException nettyReadTimeout() {
        try {
            return NETTY_READ_TIMEOUT.getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot instantiate the Netty ReadTimeoutException stand-in", e);
        }
    }

    private static SaajSoapMessageFactory soapMessageFactory() {
        try {
            SaajSoapMessageFactory factory =
                    new SaajSoapMessageFactory(MessageFactory.newInstance(SOAPConstants.SOAP_1_1_PROTOCOL));
            factory.afterPropertiesSet();
            return factory;
        } catch (SOAPException e) {
            throw new IllegalStateException("Cannot create the SOAP 1.1 message factory", e);
        }
    }
}
