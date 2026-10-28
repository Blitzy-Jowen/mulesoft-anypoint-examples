/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.xml.transform.Source;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.client.SoapFaultClientException;
import org.springframework.ws.transport.WebServiceConnection;
import org.springframework.ws.transport.WebServiceMessageSender;
import org.w3c.dom.Document;
import org.xmlunit.builder.Input;
import org.xmlunit.xpath.JAXPXPathEngine;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.config.WorkdayProperties;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.exception.UpstreamRateLimitException;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.exception.UpstreamUnavailableException;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.CustomerObjectIDType;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.CustomerWWSDataType;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.ObjectFactory;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.PutCustomerRequestType;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.PutCustomerResponseType;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.support.WorkdayTestRequests;

import jakarta.xml.bind.JAXBElement;

/**
 * Offline test of {@link WorkdayRevenueManagementClient} against a WireMock stand-in for the Workday
 * {@code Revenue_Management} v25.0 SOAP endpoint.
 *
 * <p>The client replaces the Mule Workday connector of {@code add_a_new_customer.xml}
 * ({@code wd-connector:config} at line 5, {@code wd-connector:invoke type="Revenue_Management||Put_Customer"} at line
 * 42) and serves the MUnit suite's {@code Get_Customers} lookup ({@code integration-test.xml} lines 64-80) through
 * {@link WorkdayRevenueManagementClient#sendRaw}. Every case drives a real client behind a real
 * {@link UpstreamCallExecutor} with the real {@link WorkdayFailureClassifier}, so the approved connector failure
 * handling is proved end to end on the wire: what each request carries (SOAP 1.1, no SOAP action, a
 * {@code PasswordText} UsernameToken named {@code user@tenant} without nonce or creation time), one re-authentication
 * and one retry after HTTP 401 or a {@code wsse:FailedAuthentication} fault, no retry after HTTP 429, other faults
 * rethrown unchanged, and no retry, ever, after a read timeout or a refused connection. Every failure case counts its
 * attempts exactly: the requests WireMock received and, for the read timeout and the refused connection, the
 * connections the client's transport opened, one per send attempt. A refused connection never reaches WireMock, so
 * that transport count is its only attempt count.
 *
 * <p>The configuration checks of {@link WorkdayProperties#requireConfigured()} are proved on both operations: each
 * missing or invalid {@code wday.*} value fails the call with the message naming its key, before any connection is
 * opened and without re-authentication, while an explicit {@code wday.endpoint} needs no {@code wday.hostname}.
 *
 * <p>Each case builds a fresh client, wrapped in a Mockito spy so the number of {@code reauthenticate()} calls can be
 * verified; the executor's re-authentication step is the spy's own method reference. Every client connects and reads
 * with the 10 s production default timeouts, except that the read-timeout case reads with {@link #SHORT_READ_TIMEOUT}.
 * Every value is fake, every port is ephemeral and only {@code localhost} is contacted. No Spring context is started.
 */
class WorkdayRevenueManagementClientTest {

    /** Revenue_Management v25.0 path of the fake tenant {@link #TENANT}. */
    static final String PATH = "/ccx/service/test_pt1/Revenue_Management/v25.0";

    /** What the default {@code wday.endpoint} of {@code application.yml} derives to when only the host is empty. */
    private static final String HOSTLESS_ENDPOINT = "https://" + PATH;

    /** What the default {@code wday.endpoint} of {@code application.yml} derives to when every value is empty. */
    private static final String UNCONFIGURED_ENDPOINT = "https:///ccx/service//Revenue_Management/v25.0";

    private static final String INVALID_ENDPOINT_MESSAGE = "wday.endpoint is not a valid Workday service URL: it must"
            + " be an absolute http or https URL with a host";

    private static final String SOAPENV_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    private static final String WD_NS = WorkdayTestRequests.WD_NS;

    private static final String WSSE_NS =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";

    private static final String WSU_NS =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd";

    private static final String PASSWORD_TEXT =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordText";

    private static final Map<String, String> NAMESPACES =
            Map.of("soapenv", SOAPENV_NS, "wd", WD_NS, "wsse", WSSE_NS, "wsu", WSU_NS);

    private static final String USERNAME_TOKEN = "/soapenv:Envelope/soapenv:Header/wsse:Security/wsse:UsernameToken";

    private static final String USER = "testuser";

    private static final String TENANT = "test_pt1";

    private static final String PASSWORD = "test-password";

    private static final String CUSTOMER_NAME = "John1";

    /**
     * Connect timeout of every client and read timeout of every client expecting an answer: the 10 s production
     * default, because on a loaded host WireMock's first answer can take seconds and a request the client gave up on
     * lands in the next case's journal.
     */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /** Read timeout of the read-timeout case only, far below the delayed stub's {@link #SLOW_RESPONSE_DELAY_MILLIS}. */
    private static final Duration SHORT_READ_TIMEOUT = Duration.ofSeconds(1);

    /** Longer than {@link #SHORT_READ_TIMEOUT}, so the client's read times out while WireMock holds the response. */
    private static final int SLOW_RESPONSE_DELAY_MILLIS = 3000;

    private static final String TEXT_XML = "text/xml;charset=UTF-8";

    private static final String PUT_CUSTOMER_RESPONSE = """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">\
            <soapenv:Body>\
            <wd:Put_Customer_Response xmlns:wd="urn:com.workday/bsvc" wd:version="v25.0">\
            <wd:Customer_Reference wd:Descriptor="John1">\
            <wd:ID wd:type="WID">3bc6c1b9f4a0100156c2a0f8e9b10000</wd:ID>\
            <wd:ID wd:type="Customer_Reference_ID">John1</wd:ID>\
            </wd:Customer_Reference>\
            </wd:Put_Customer_Response>\
            </soapenv:Body>\
            </soapenv:Envelope>""";

    private static final String FAILED_AUTHENTICATION_FAULT = """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">\
            <soapenv:Body>\
            <soapenv:Fault \
            xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">\
            <faultcode>wsse:FailedAuthentication</faultcode>\
            <faultstring>invalid username or password</faultstring>\
            </soapenv:Fault>\
            </soapenv:Body>\
            </soapenv:Envelope>""";

    private static final String VALIDATION_FAULT_STRING =
            "Invalid ID value. 'BAD' is not a valid ID value for type = 'Customer_Category_ID'";

    private static final String VALIDATION_FAULT = """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">\
            <soapenv:Body>\
            <soapenv:Fault xmlns:wd="urn:com.workday/bsvc">\
            <faultcode>wd:Validation_Error</faultcode>\
            <faultstring>%s</faultstring>\
            </soapenv:Fault>\
            </soapenv:Body>\
            </soapenv:Envelope>""".formatted(VALIDATION_FAULT_STRING);

    private static final String GET_CUSTOMERS_RESPONSE = """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">\
            <soapenv:Body>\
            <wd:Get_Customers_Response xmlns:wd="urn:com.workday/bsvc" wd:version="v25.0">\
            <wd:Request_Criteria><wd:Customer_Reference_ID>John1</wd:Customer_Reference_ID></wd:Request_Criteria>\
            <wd:Response_Group><wd:Include_Reference>1</wd:Include_Reference>\
            <wd:Include_Customer_Data>1</wd:Include_Customer_Data></wd:Response_Group>\
            <wd:Response_Results>\
            <wd:Total_Results>1</wd:Total_Results>\
            <wd:Total_Pages>1</wd:Total_Pages>\
            <wd:Page_Results>1</wd:Page_Results>\
            <wd:Page>1</wd:Page>\
            </wd:Response_Results>\
            <wd:Response_Data>\
            <wd:Customer>\
            <wd:Customer_Reference wd:Descriptor="John1">\
            <wd:ID wd:type="WID">3bc6c1b9f4a0100156c2a0f8e9b10000</wd:ID>\
            <wd:ID wd:type="Customer_Reference_ID">John1</wd:ID>\
            </wd:Customer_Reference>\
            <wd:Customer_Data>\
            <wd:Customer_ID>John1</wd:Customer_ID>\
            <wd:Customer_Name>John1</wd:Customer_Name>\
            <wd:Customer_Category_Reference wd:Descriptor="Corporate">\
            <wd:ID wd:type="WID">5c1b0b8a6d8b10000a1c5e2f3d4a0001</wd:ID>\
            <wd:ID wd:type="Customer_Category_ID">CUSTOMER_CATEGORY-5</wd:ID>\
            </wd:Customer_Category_Reference>\
            </wd:Customer_Data>\
            </wd:Customer>\
            </wd:Response_Data>\
            </wd:Get_Customers_Response>\
            </soapenv:Body>\
            </soapenv:Envelope>""";

    private static final String AUTH_SCENARIO = "workday-authentication";

    private static final String RETRIED = "retried";

    private static final WireMockServer wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    private WorkdayRevenueManagementClient client;

    @BeforeAll
    static void startWorkday() {
        wm.start();
    }

    @AfterAll
    static void stopWorkday() {
        wm.stop();
    }

    @BeforeEach
    void resetWorkdayAndBuildClient() {
        wm.resetAll();
        client = newClient(wm.baseUrl() + PATH);
    }

    @Test
    void putCustomerSendsUsernameTokenAndReturnsDescriptor() throws Exception {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(soap(200, PUT_CUSTOMER_RESPONSE)));

        PutCustomerResponseType response = client.putCustomer(putCustomerRequest(CUSTOMER_NAME));

        assertThat(response.getCustomerReference().getDescriptor()).isEqualTo(CUSTOMER_NAME);
        assertThat(response.getCustomerReference().getID())
                .extracting(CustomerObjectIDType::getType, CustomerObjectIDType::getValue)
                .containsExactly(tuple("WID", "3bc6c1b9f4a0100156c2a0f8e9b10000"),
                        tuple("Customer_Reference_ID", CUSTOMER_NAME));

        List<LoggedRequest> requests = wm.findAll(postRequestedFor(urlEqualTo(PATH)));
        assertThat(requests).hasSize(1);
        LoggedRequest request = requests.get(0);
        assertThat(request.getHeader("Content-Type")).startsWith("text/xml");
        // The v25.0 binding declares no soapAction: the header is absent or the SOAP 1.1 empty action.
        if (request.containsHeader("SOAPAction")) {
            assertThat(request.getHeader("SOAPAction")).isIn("", "\"\"");
        }

        String body = request.getBodyAsString();
        assertThat(xpath(body, "count(/soapenv:Envelope/soapenv:Body/wd:Put_Customer_Request)")).isEqualTo("1");
        assertThat(xpath(body, "//wd:Customer_Data/wd:Customer_Name")).isEqualTo(CUSTOMER_NAME);
        assertThat(xpath(body, "//wd:Customer_Data/wd:Customer_ID")).isEqualTo(CUSTOMER_NAME);
        assertThat(xpath(body, "//wd:Customer_Data/wd:Customer_Reference_ID")).isEqualTo(CUSTOMER_NAME);
        assertCarriesPasswordTextUsernameToken(body);

        verify(client, never()).reauthenticate();
    }

    @Test
    void retriesOnceAfterHttp401() throws Exception {
        wm.stubFor(post(urlEqualTo(PATH)).inScenario(AUTH_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(plainText(401, "Unauthorized"))
                .willSetStateTo(RETRIED));
        wm.stubFor(post(urlEqualTo(PATH)).inScenario(AUTH_SCENARIO)
                .whenScenarioStateIs(RETRIED)
                .willReturn(soap(200, PUT_CUSTOMER_RESPONSE)));

        PutCustomerResponseType response = client.putCustomer(putCustomerRequest(CUSTOMER_NAME));

        assertThat(response.getCustomerReference().getDescriptor()).isEqualTo(CUSTOMER_NAME);
        List<LoggedRequest> requests = wm.findAll(postRequestedFor(urlEqualTo(PATH)));
        assertThat(requests).hasSize(2);
        // The retry goes out with the UsernameToken that reauthenticate() rebuilt from the current credentials.
        assertCarriesPasswordTextUsernameToken(requests.get(1).getBodyAsString());
        verify(client, times(1)).reauthenticate();
    }

    @Test
    void failsAfterSecondHttp401() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(plainText(401, "Unauthorized")));

        Throwable failure = catchThrowable(() -> client.putCustomer(putCustomerRequest(CUSTOMER_NAME)));

        assertThat(failure).isInstanceOf(UpstreamAuthenticationException.class);
        assertThat(((UpstreamAuthenticationException) failure).getSystem()).isEqualTo(UpstreamSystem.WORKDAY);
        assertThat(failure.getCause()).isInstanceOfSatisfying(WorkdayRevenueManagementClient.HttpStatusException.class,
                cause -> assertThat(cause.statusCode()).isEqualTo(401));
        wm.verify(2, postRequestedFor(urlEqualTo(PATH)));
        verify(client, times(1)).reauthenticate();
    }

    @Test
    void rateLimitIsNotRetried() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(plainText(429, "Too Many Requests")
                .withHeader("Retry-After", "30")));

        Throwable failure = catchThrowable(() -> client.putCustomer(putCustomerRequest(CUSTOMER_NAME)));

        assertThat(failure).isInstanceOfSatisfying(UpstreamRateLimitException.class, rateLimit -> {
            assertThat(rateLimit.getRetryAfter()).isEqualTo("30");
            assertThat(rateLimit.getSystem()).isEqualTo(UpstreamSystem.WORKDAY);
        });
        assertThat(failure.getCause()).isInstanceOfSatisfying(WorkdayRevenueManagementClient.HttpStatusException.class,
                cause -> assertThat(cause.statusCode()).isEqualTo(429));
        wm.verify(1, postRequestedFor(urlEqualTo(PATH)));
        verify(client, never()).reauthenticate();
    }

    @Test
    void failedAuthenticationFaultIsRetriedOnce() throws Exception {
        wm.stubFor(post(urlEqualTo(PATH)).inScenario(AUTH_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(soap(500, FAILED_AUTHENTICATION_FAULT))
                .willSetStateTo(RETRIED));
        wm.stubFor(post(urlEqualTo(PATH)).inScenario(AUTH_SCENARIO)
                .whenScenarioStateIs(RETRIED)
                .willReturn(soap(200, PUT_CUSTOMER_RESPONSE)));

        PutCustomerResponseType response = client.putCustomer(putCustomerRequest(CUSTOMER_NAME));

        assertThat(response.getCustomerReference().getDescriptor()).isEqualTo(CUSTOMER_NAME);
        wm.verify(2, postRequestedFor(urlEqualTo(PATH)));
        verify(client, times(1)).reauthenticate();
    }

    @Test
    void genericFaultIsRethrownUnchanged() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(soap(500, VALIDATION_FAULT)));

        Throwable failure = catchThrowable(() -> client.putCustomer(putCustomerRequest(CUSTOMER_NAME)));

        // Exactly Spring WS's own fault type: neither wrapped in an upstream exception nor retried.
        assertThat(failure).isExactlyInstanceOf(SoapFaultClientException.class)
                .hasMessageContaining("Invalid ID value");
        SoapFaultClientException fault = (SoapFaultClientException) failure;
        assertThat(fault.getFaultCode().getLocalPart()).isEqualTo("Validation_Error");
        assertThat(fault.getFaultStringOrReason()).isEqualTo(VALIDATION_FAULT_STRING);
        wm.verify(1, postRequestedFor(urlEqualTo(PATH)));
        verify(client, never()).reauthenticate();
    }

    @Test
    void timeoutIsNotRetried() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(soap(200, PUT_CUSTOMER_RESPONSE)
                .withFixedDelay(SLOW_RESPONSE_DELAY_MILLIS)));
        // With the 10 s default read timeout this delayed answer would succeed, so only this client reads with less.
        WorkdayRevenueManagementClient impatient = newClient(wm.baseUrl() + PATH, SHORT_READ_TIMEOUT);
        CountingMessageSender transport = countTransportAttempts(impatient);

        Throwable failure = catchThrowable(() -> impatient.putCustomer(putCustomerRequest(CUSTOMER_NAME)));

        assertThat(failure).isInstanceOfSatisfying(UpstreamUnavailableException.class,
                unavailable -> assertThat(unavailable.getSystem()).isEqualTo(UpstreamSystem.WORKDAY));
        assertThat(failure.getCause()).isInstanceOf(WebServiceIOException.class);
        assertThat(causeChain(failure)).hasAtLeastOneElementOfType(SocketTimeoutException.class);
        assertThat(transport.connections()).isEqualTo(1);
        // The timed-out request reaches WireMock's journal, and no second one arrives while its delayed response is
        // pending or after the delay has run out.
        await().atMost(Duration.ofSeconds(5)).until(this::putCustomerRequestsReceived, received -> received == 1);
        await().during(Duration.ofMillis(SLOW_RESPONSE_DELAY_MILLIS + 500))
                .atMost(Duration.ofMillis(SLOW_RESPONSE_DELAY_MILLIS + 2000))
                .until(this::putCustomerRequestsReceived, received -> received == 1);
        verify(impatient, never()).reauthenticate();
    }

    @Test
    void refusedConnectionIsNotRetried() throws IOException {
        try (Socket reservation = refusingLoopbackPort()) {
            WorkdayRevenueManagementClient unreachable = newClient(refusingBaseUrl(reservation) + PATH);
            CountingMessageSender transport = countTransportAttempts(unreachable);

            Throwable failure = catchThrowable(() -> unreachable.putCustomer(putCustomerRequest(CUSTOMER_NAME)));

            assertThat(failure).isInstanceOfSatisfying(UpstreamUnavailableException.class,
                    unavailable -> assertThat(unavailable.getSystem()).isEqualTo(UpstreamSystem.WORKDAY));
            assertThat(causeChain(failure)).hasAtLeastOneElementOfType(ConnectException.class);
            // WireMock never sees a refused connection: the one connection opened proves the call was not retried.
            assertThat(transport.connections()).isEqualTo(1);
            verify(unreachable, never()).reauthenticate();
        }
    }

    @Test
    void sendRawGetCustomersReturnsDocument() throws Exception {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(soap(200, GET_CUSTOMERS_RESPONSE)));

        Source request = WorkdayTestRequests.getCustomersRequest(CUSTOMER_NAME);
        Document document = client.sendRaw(request);

        assertThat(document.getDocumentElement().getNamespaceURI()).isEqualTo(WD_NS);
        assertThat(document.getDocumentElement().getLocalName()).isEqualTo("Get_Customers_Response");
        assertThat(WorkdayTestRequests.totalResults(document)).isEqualTo("1");

        List<LoggedRequest> requests = wm.findAll(postRequestedFor(urlEqualTo(PATH)));
        assertThat(requests).hasSize(1);
        String body = requests.get(0).getBodyAsString();
        assertThat(xpath(body, "count(/soapenv:Envelope/soapenv:Body/wd:Get_Customers_Request)")).isEqualTo("1");
        assertThat(xpath(body, "//wd:Request_Criteria/wd:Customer_Reference_ID")).isEqualTo(CUSTOMER_NAME);
        assertCarriesPasswordTextUsernameToken(body);
        verify(client, never()).reauthenticate();
    }

    /**
     * A missing or invalid {@code wday.*} value fails either operation with the message naming the key, before the
     * transport opens a connection and without re-authentication. WireMock answers every call successfully, so a call
     * that got past the check would return instead of failing.
     */
    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("misconfigurations")
    void missingOrInvalidSettingFailsBeforeAnyRequest(Operation operation, String setting,
            Consumer<WorkdayProperties> misconfiguration, String expectedMessage) {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(soap(200, PUT_CUSTOMER_RESPONSE)));
        WorkdayRevenueManagementClient misconfigured = newClient(wm.baseUrl() + PATH, DEFAULT_TIMEOUT,
                misconfiguration);
        CountingMessageSender transport = countTransportAttempts(misconfigured);

        Throwable failure = catchThrowable(() -> operation.invoke(misconfigured));

        assertThat(failure).isExactlyInstanceOf(IllegalStateException.class).hasMessage(expectedMessage);
        assertThat(transport.connections()).isZero();
        wm.verify(0, anyRequestedFor(anyUrl()));
        verify(misconfigured, never()).reauthenticate();
    }

    /**
     * Each operation crossed with every way a setting can be unusable: each required key {@code null}, empty or
     * blank, every value empty as {@code application.yml} leaves them, and endpoints that are not absolute
     * {@code http}/{@code https} URLs with a host. The host is reported missing only while the endpoint names none.
     */
    static Stream<Arguments> misconfigurations() {
        List<Arguments> cases = new ArrayList<>();
        for (Operation operation : Operation.values()) {
            for (String missing : new String[] {null, "", "   "}) {
                String shown = missing == null ? "null" : "'" + missing + "'";
                cases.add(arguments(operation, "wday.hostname " + shown + " with a host-less endpoint",
                        settings(properties -> {
                            properties.setHostname(missing);
                            properties.setEndpoint(HOSTLESS_ENDPOINT);
                        }), "wday.hostname is not configured"));
                cases.add(arguments(operation, "wday.endpoint " + shown,
                        settings(properties -> properties.setEndpoint(missing)), "wday.endpoint is not configured"));
                cases.add(arguments(operation, "wday.tenant " + shown,
                        settings(properties -> properties.setTenant(missing)), "wday.tenant is not configured"));
                cases.add(arguments(operation, "wday.user " + shown,
                        settings(properties -> properties.setUser(missing)), "wday.user is not configured"));
                cases.add(arguments(operation, "wday.password " + shown,
                        settings(properties -> properties.setPassword(missing)), "wday.password is not configured"));
            }
            cases.add(arguments(operation, "every value empty", settings(properties -> {
                properties.setHostname("");
                properties.setTenant("");
                properties.setUser("");
                properties.setPassword("");
                properties.setEndpoint(UNCONFIGURED_ENDPOINT);
            }), "wday.hostname is not configured"));
            for (String invalid : new String[] {"ftp://localhost" + PATH, "http://local host" + PATH, PATH,
                    " https://localhost" + PATH}) {
                cases.add(arguments(operation, "wday.endpoint '" + invalid + "'",
                        settings(properties -> properties.setEndpoint(invalid)), INVALID_ENDPOINT_MESSAGE));
            }
        }
        return cases.stream();
    }

    /**
     * An explicit {@code wday.endpoint} with a host is used as it is, so the call is sent without
     * {@code wday.hostname}, as for a tenant whose service URL is not the derived one.
     */
    @ParameterizedTest(name = "wday.hostname [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void explicitEndpointIsSentWithoutHostname(String hostname) throws Exception {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(soap(200, PUT_CUSTOMER_RESPONSE)));
        WorkdayRevenueManagementClient overridden = newClient(wm.baseUrl() + PATH, DEFAULT_TIMEOUT,
                properties -> properties.setHostname(hostname));

        PutCustomerResponseType response = overridden.putCustomer(putCustomerRequest(CUSTOMER_NAME));

        assertThat(response.getCustomerReference().getDescriptor()).isEqualTo(CUSTOMER_NAME);
        List<LoggedRequest> requests = wm.findAll(postRequestedFor(urlEqualTo(PATH)));
        assertThat(requests).hasSize(1);
        assertCarriesPasswordTextUsernameToken(requests.get(0).getBodyAsString());
        verify(overridden, never()).reauthenticate();
    }

    /** {@link #newClient(String, Duration)} reading with {@link #DEFAULT_TIMEOUT}: every case awaiting an answer. */
    private static WorkdayRevenueManagementClient newClient(String endpoint) {
        return newClient(endpoint, DEFAULT_TIMEOUT);
    }

    /**
     * A spied client for {@code endpoint} with the fake test credentials, the {@link #DEFAULT_TIMEOUT} connect timeout
     * and the given read timeout, behind a real executor and the real Workday classifier.
     */
    private static WorkdayRevenueManagementClient newClient(String endpoint, Duration readTimeout) {
        return newClient(endpoint, readTimeout, properties -> { });
    }

    /**
     * {@link #newClient(String, Duration)} with {@code adjustment} applied to its settings before the client is built.
     */
    private static WorkdayRevenueManagementClient newClient(String endpoint, Duration readTimeout,
            Consumer<WorkdayProperties> adjustment) {
        WorkdayProperties properties = new WorkdayProperties();
        properties.setHostname("localhost");
        properties.setTenant(TENANT);
        properties.setUser(USER);
        properties.setPassword(PASSWORD);
        properties.setEndpoint(endpoint);
        properties.setConnectTimeout(DEFAULT_TIMEOUT);
        properties.setReadTimeout(readTimeout);
        adjustment.accept(properties);
        return spy(new WorkdayRevenueManagementClient(properties,
                new UpstreamCallExecutor(List.of(new WorkdayFailureClassifier()))));
    }

    /** Types a settings change for {@link Arguments}, whose varargs would otherwise leave a lambda untyped. */
    private static Consumer<WorkdayProperties> settings(Consumer<WorkdayProperties> change) {
        return change;
    }

    /**
     * Wraps the one message sender of {@code client}'s template in a {@link CountingMessageSender} and returns it, so
     * every send attempt, a retry included, is counted while the real transport still carries it.
     */
    private static CountingMessageSender countTransportAttempts(WorkdayRevenueManagementClient client) {
        WebServiceTemplate template = (WebServiceTemplate) ReflectionTestUtils.getField(client, "template");
        assertThat(template).isNotNull();
        WebServiceMessageSender[] senders = template.getMessageSenders();
        assertThat(senders).hasSize(1);
        CountingMessageSender counting = new CountingMessageSender(senders[0]);
        template.setMessageSender(counting);
        return counting;
    }

    /**
     * Reserves a free TCP port on 127.0.0.1 that refuses every connection: the socket is bound with address reuse
     * disabled and never listens. While it stays open no other socket can bind or listen on that port, so close it
     * only after the refused call and its assertions have run.
     */
    private static Socket refusingLoopbackPort() throws IOException {
        Socket reservation = new Socket();
        try {
            reservation.setReuseAddress(false);
            reservation.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0));
            return reservation;
        } catch (IOException | RuntimeException e) {
            reservation.close();
            throw e;
        }
    }

    /** The {@code http://127.0.0.1:<port>} base URL of a reservation made by {@link #refusingLoopbackPort()}. */
    private static String refusingBaseUrl(Socket reservation) {
        return "http://" + reservation.getLocalAddress().getHostAddress() + ":" + reservation.getLocalPort();
    }

    /** The {@code Put_Customer} POSTs WireMock has journaled since the last reset. */
    private int putCustomerRequestsReceived() {
        return wm.findAll(postRequestedFor(urlEqualTo(PATH))).size();
    }

    /** The original mapping script's {@code Put_Customer_Request}: ID, reference ID and name all {@code name}. */
    private static JAXBElement<PutCustomerRequestType> putCustomerRequest(String name) {
        ObjectFactory factory = new ObjectFactory();
        CustomerWWSDataType customerData = factory.createCustomerWWSDataType();
        customerData.setCustomerID(name);
        customerData.setCustomerReferenceID(name);
        customerData.setCustomerName(name);
        PutCustomerRequestType request = factory.createPutCustomerRequestType();
        request.setCustomerData(customerData);
        return factory.createPutCustomerRequest(request);
    }

    private static ResponseDefinitionBuilder soap(int status, String envelope) {
        return aResponse().withStatus(status).withHeader("Content-Type", TEXT_XML).withBody(envelope);
    }

    private static ResponseDefinitionBuilder plainText(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "text/plain").withBody(body);
    }

    /** Asserts the request's {@code wsse:UsernameToken}: {@code user@tenant}, clear-text password, no nonce or time. */
    private static void assertCarriesPasswordTextUsernameToken(String envelope) {
        assertThat(xpath(envelope, "count(" + USERNAME_TOKEN + ")")).isEqualTo("1");
        assertThat(xpath(envelope, USERNAME_TOKEN + "/wsse:Username")).isEqualTo(USER + "@" + TENANT);
        assertThat(xpath(envelope, USERNAME_TOKEN + "/wsse:Password/@Type")).isEqualTo(PASSWORD_TEXT);
        assertThat(xpath(envelope, USERNAME_TOKEN + "/wsse:Password")).isEqualTo(PASSWORD);
        assertThat(xpath(envelope, "count(//wsse:Nonce)")).isEqualTo("0");
        assertThat(xpath(envelope, "count(//wsu:Created)")).isEqualTo("0");
    }

    private static String xpath(String xml, String expression) {
        JAXPXPathEngine engine = new JAXPXPathEngine();
        engine.setNamespaceContext(NAMESPACES);
        return engine.evaluate(expression, Input.fromString(xml).build());
    }

    private static List<Throwable> causeChain(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable current = failure; current != null && !chain.contains(current); current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }

    /** The two client operations, each of which checks the {@code wday.*} values before it sends anything. */
    enum Operation {

        PUT_CUSTOMER {
            @Override
            Object invoke(WorkdayRevenueManagementClient client) throws Exception {
                return client.putCustomer(putCustomerRequest(CUSTOMER_NAME));
            }
        },

        SEND_RAW {
            @Override
            Object invoke(WorkdayRevenueManagementClient client) throws Exception {
                return client.sendRaw(WorkdayTestRequests.getCustomersRequest(CUSTOMER_NAME));
            }
        };

        /** Calls the operation on {@code client} with the request this test class sends for it. */
        abstract Object invoke(WorkdayRevenueManagementClient client) throws Exception;
    }

    /**
     * Counts the connections the template asks for, one per send attempt, and passes both calls to the client's own
     * sender unchanged, so the real transport and its refusal and timeout failures stay in place.
     */
    private static final class CountingMessageSender implements WebServiceMessageSender {

        private final WebServiceMessageSender delegate;

        private final AtomicInteger connections = new AtomicInteger();

        CountingMessageSender(WebServiceMessageSender delegate) {
            this.delegate = delegate;
        }

        @Override
        public WebServiceConnection createConnection(URI uri) throws IOException {
            connections.incrementAndGet();
            return delegate.createConnection(uri);
        }

        @Override
        public boolean supports(URI uri, UriSource uriSource) {
            return delegate.supports(uri, uriSource);
        }

        /** @return the connections created so far */
        int connections() {
            return connections.get();
        }
    }
}
