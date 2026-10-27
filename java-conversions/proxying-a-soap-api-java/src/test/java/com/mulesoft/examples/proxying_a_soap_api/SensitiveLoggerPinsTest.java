/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_soap_api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.NestedTestConfiguration;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import com.mulesoft.examples.proxying_a_soap_api.controller.SoapProxyController;
import com.mulesoft.examples.proxying_a_soap_api.support.HttpTestClient;

/**
 * The logger pins of {@code src/main/resources/application.yml} hold under {@code logging.level.root=TRACE}, Spring
 * Boot's {@code trace} mode and {@code spring.mvc.log-request-details}: the proxy forwards the caller's
 * {@code Authorization}, token header, cookie and WS-Security envelope to the upstream unchanged and hands the
 * upstream's answer back, while no credential, token, query, envelope or upstream content reaches the log. Below INFO
 * the pinned loggers would write all of them: httpclient5 its request and response headers and wire bytes,
 * {@code DispatcherServlet} the request line with its query, and the exception resolution and response-writing
 * loggers an exception message that can quote upstream content.
 *
 * <p>The application boots with its own {@code application.yml} in front of a WireMock stand-in for the predic8
 * ShopService. The upstream WSDL is read from that stand-in at start-up under configured user information, which
 * httpclient5 sends as {@code Basic} credentials, so the start-up read is covered as well; the output capture opened
 * before the context starts holds those lines. Every exchange also requires a line below INFO that an unpinned logger
 * wrote for it, so no case can pass because nothing was logged. The logger levels this class's contexts set are put
 * back afterwards, and both contexts are closed, so no later test runs at TRACE.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "logging.level.root=TRACE",
                "trace=true",
                "spring.mvc.log-request-details=true"})
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class SensitiveLoggerPinsTest {

    private static final String LOOPBACK = "127.0.0.1";

    /** The {@code http:request path} on the upstream. */
    private static final String UPSTREAM_PATH = "/shop/ShopService";

    private static final String WSDL_PATH = "/ShopService.wsdl";

    /** The schema the stand-in WSDL imports by the relative location {@code ShopService.xsd}. */
    private static final String XSD_PATH = "/ShopService.xsd";

    /**
     * User information of {@code proxy.wsdl-location}. Its Base64 holds no {@code +} or {@code /}, so the URL-safe
     * form httpclient5 is handed and the standard form WireMock's {@code Basic} check expects are the same.
     */
    private static final String WSDL_USER = "SENTINEL_WSDL_USER";

    private static final String WSDL_PASSWORD = "SENTINEL_WSDL_PASSWORD";

    private static final String BASIC_CREDENTIALS = "SENTINEL_BASIC_USER:SENTINEL_BASIC_PASSWORD";

    private static final String AUTHORIZATION =
            "Basic " + Base64.getEncoder().encodeToString(BASIC_CREDENTIALS.getBytes(UTF_8));

    private static final String API_TOKEN = "SENTINEL_API_TOKEN";

    private static final String COOKIE = "sid=SENTINEL_COOKIE";

    private static final String QUERY = "access_token=SENTINEL_QUERY";

    /** A {@code create} envelope carrying a WS-Security UsernameToken. */
    private static final byte[] ENVELOPE = ("<soapenv:Envelope"
            + " xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\""
            + " xmlns:ns=\"http://predic8.com/wsdl/shop/1/\"><soapenv:Header><wsse:Security xmlns:wsse="
            + "\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\">"
            + "<wsse:UsernameToken><wsse:Username>SENTINEL_WSSE_USER</wsse:Username>"
            + "<wsse:Password>SENTINEL_WSSE_PASSWORD</wsse:Password></wsse:UsernameToken></wsse:Security>"
            + "</soapenv:Header><soapenv:Body><ns:create/></soapenv:Body></soapenv:Envelope>").getBytes(UTF_8);

    /** The upstream's answer to {@code create}. */
    private static final byte[] CREATE_RESPONSE = ("<soapenv:Envelope xmlns:soapenv="
            + "\"http://schemas.xmlsoap.org/soap/envelope/\"><soapenv:Body><ns:createResponse"
            + " xmlns:ns=\"http://predic8.com/wsdl/shop/1/\"><cartId>SENTINEL_CART_ID</cartId></ns:createResponse>"
            + "</soapenv:Body></soapenv:Envelope>").getBytes(UTF_8);

    private static final String SET_COOKIE = "cart=SENTINEL_SET_COOKIE";

    /**
     * Every value the caller, the configuration or the upstream supplies; none may reach the log. The {@code Basic}
     * credentials are listed in their Base64 forms too, the form in which a header line would carry them.
     */
    private static final List<String> SENTINELS = List.of(
            "SENTINEL_BASIC_USER", "SENTINEL_BASIC_PASSWORD", AUTHORIZATION.substring("Basic ".length()),
            API_TOKEN, "SENTINEL_COOKIE", "SENTINEL_QUERY", "SENTINEL_WSSE_USER", "SENTINEL_WSSE_PASSWORD",
            "SENTINEL_CART_ID", "SENTINEL_SET_COOKIE", "SENTINEL_RESPONSE_HEADER", "SENTINEL_UPSTREAM_FAULT",
            "SENTINEL_LOCATION", WSDL_USER, WSDL_PASSWORD,
            Base64.getEncoder().encodeToString((WSDL_USER + ":" + WSDL_PASSWORD).getBytes(UTF_8)));

    /** Written by the unpinned handler mapping at TRACE when a request is dispatched to the proxy. */
    private static final String MAPPED_TO_PROXY = "Mapped to " + SoapProxyController.class.getName() + "#proxy(";


    /** Written at ERROR by the generic 500 advice for a failed proxied {@code POST}. */
    private static final String UNHANDLED_POST = "Unhandled exception processing POST /: ";

    /** How long the output must stay free of sentinels after an exchange, for lines written after the reply. */
    private static final Duration SETTLE = Duration.ofMillis(300);

    /** The logger levels set before this class's contexts started, by logger name. */
    private static final Map<String, Level> LEVELS_BEFORE = new HashMap<>();

    @RegisterExtension
    static WireMockExtension upstream = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().bindAddress(LOOPBACK).gzipDisabled(true))
            .build();

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void upstreamLocations(DynamicPropertyRegistry registry) {
        registry.add("proxy.upstream-url", () -> "http://" + LOOPBACK + ":" + upstream.getPort() + UPSTREAM_PATH);
        registry.add("proxy.wsdl-location", () -> "http://" + WSDL_USER + ":" + WSDL_PASSWORD + "@" + LOOPBACK + ":"
                + upstream.getPort() + WSDL_PATH);
    }

    @BeforeAll
    static void recordLevels() {
        LEVELS_BEFORE.clear();
        for (Logger logger : loggerContext().getLoggerList()) {
            if (logger.getLevel() != null) {
                LEVELS_BEFORE.put(logger.getName(), logger.getLevel());
            }
        }
    }

    /**
     * The WSDL and its schema, served only to the configured credentials, so a context that starts proves the
     * start-up read carried them. Stubbed before the context starts, which reads them once; WireMock drops these
     * stubs before each test.
     */
    @BeforeAll
    static void stubUpstreamWsdl() throws IOException {
        upstream.stubFor(get(urlEqualTo(WSDL_PATH)).withBasicAuth(WSDL_USER, WSDL_PASSWORD)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/xml;charset=UTF-8")
                        .withBody(new ClassPathResource("upstream/ShopService.wsdl").getContentAsByteArray())));
        upstream.stubFor(get(urlEqualTo(XSD_PATH)).withBasicAuth(WSDL_USER, WSDL_PASSWORD)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/xml;charset=UTF-8")
                        .withBody(new ClassPathResource("upstream/ShopService.xsd").getContentAsByteArray())));
    }

    @AfterAll
    static void restoreLevels() {
        for (Logger logger : loggerContext().getLoggerList()) {
            Level before = LEVELS_BEFORE.get(logger.getName());
            // The root logger always has a level and cannot be given none.
            if (before != null || !org.slf4j.Logger.ROOT_LOGGER_NAME.equals(logger.getName())) {
                logger.setLevel(before);
            }
        }
    }

    @BeforeEach
    void checkRootTrace() {
        assertThat(LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).isTraceEnabled())
                .as("root TRACE").isTrue();
    }

    /**
     * Under the root TRACE each pinned logger writes at its pinned level and above only: INFO for most, WARN for
     * {@code DispatcherServlet}, whose INFO stays off so that a root WARN also silences its servlet start-up lines.
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "org.apache.hc.client5.http.headers, INFO",
            "org.apache.hc.client5.http.wire, INFO",
            "org.apache.hc.client5.http.impl.classic.MainClientExec, INFO",
            "org.springframework.web.servlet.DispatcherServlet, WARN",
            "org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver, INFO",
            "org.springframework.web.method.HandlerMethod, INFO",
            "org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor, INFO"})
    void pinnedLoggerStaysAtItsLevelUnderRootTrace(String name, String pinned) {
        Logger logger = loggerContext().getLogger(name);
        Level pinnedLevel = Level.toLevel(pinned, null);

        assertThat(logger.getEffectiveLevel()).as("%s effective level", name).isEqualTo(pinnedLevel);
        for (Level level : List.of(Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR)) {
            assertThat(logger.isEnabledFor(level)).as("%s %s", name, level)
                    .isEqualTo(level.isGreaterOrEqual(pinnedLevel));
        }
    }

    @Test
    void credentialsTokenAndEnvelopeReachUpstreamUnchangedWithoutBeingLogged(CapturedOutput output) {
        upstream.stubFor(post(urlEqualTo(UPSTREAM_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/xml;charset=UTF-8")
                .withHeader("Set-Cookie", SET_COOKIE)
                .withHeader("X-Upstream-Token", "SENTINEL_RESPONSE_HEADER")
                .withBody(CREATE_RESPONSE)));
        long mappedBefore = lines(output, "TRACE", MAPPED_TO_PROXY);

        HttpTestClient.Response response = client().send("POST", "/?" + QUERY, callerHeaders(), ENVELOPE);

        assertThat(response.statusLine()).as("%s", response).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.body()).isEqualTo(CREATE_RESPONSE);
        assertThat(response.bodyAsString()).contains("<cartId>").contains("</cartId>");
        assertThat(response.header("Set-Cookie")).isEqualTo(SET_COOKIE);
        assertThat(response.header("X-Upstream-Token")).isEqualTo("SENTINEL_RESPONSE_HEADER");
        upstream.verify(1, postRequestedFor(urlEqualTo(UPSTREAM_PATH))
                .withHeader("Authorization", equalTo(AUTHORIZATION))
                .withHeader("X-Api-Token", equalTo(API_TOKEN))
                .withHeader("Cookie", equalTo(COOKIE))
                .withRequestBody(binaryEqualTo(ENVELOPE)));
        assertLogHoldsNoSentinel(output);
        assertLoggedSince(output, "TRACE", MAPPED_TO_PROXY, mappedBefore);
    }

    /** Case name, the upstream's answer, then the start of the 500 body the caller receives. */
    private static Stream<Arguments> upstreamFailures() {
        return Stream.of(
                Arguments.of("upstream 500 fault",
                        aResponse().withStatus(500)
                                .withHeader("Content-Type", "text/xml;charset=UTF-8")
                                .withHeader("X-Upstream-Token", "SENTINEL_RESPONSE_HEADER")
                                .withBody("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                                        + "<soap:Body><soap:Fault><faultcode>soap:Server</faultcode><faultstring>"
                                        + "SENTINEL_UPSTREAM_FAULT</faultstring></soap:Fault></soap:Body>"
                                        + "</soap:Envelope>"),
                        "{ \"message\": \"Response code 500 mapped as failure. Message payload is of type: "
                                + "BufferInputStream\" }"),
                // The I/O failure's message quotes the Location, so the 500 body and the exception carry upstream
                // content.
                Arguments.of("unparseable redirect Location",
                        aResponse().withStatus(302).withHeader("Location", "SENTINEL_LOCATION bad target"),
                        "{ \"message\": \"I/O error: Invalid redirect URI: SENTINEL_LOCATION bad target\" }"),
                Arguments.of("connection reset after the request was sent",
                        aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER),
                        "{ \"message\": \"I/O error"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("upstreamFailures")
    void upstreamFailureIsAnswered500WithoutLoggingRequestOrUpstreamContent(String name,
                                                                            ResponseDefinitionBuilder answer,
                                                                            String bodyStart,
                                                                            CapturedOutput output) {
        upstream.stubFor(post(urlEqualTo(UPSTREAM_PATH)).willReturn(answer));
        long mappedBefore = lines(output, "TRACE", MAPPED_TO_PROXY);
        long unhandledBefore = lines(output, "ERROR", UNHANDLED_POST);

        HttpTestClient.Response response = client().send("POST", "/?" + QUERY, callerHeaders(), ENVELOPE);

        assertThat(response.statusLine()).as("%s", response).isEqualTo("HTTP/1.1 500 Internal Server Error");
        assertThat(response.bodyAsString()).startsWith(bodyStart);
        upstream.verify(1, postRequestedFor(urlEqualTo(UPSTREAM_PATH))
                .withHeader("Authorization", equalTo(AUTHORIZATION))
                .withHeader("X-Api-Token", equalTo(API_TOKEN))
                .withRequestBody(binaryEqualTo(ENVELOPE)));
        assertLogHoldsNoSentinel(output);
        assertLoggedSince(output, "TRACE", MAPPED_TO_PROXY, mappedBefore);
        // The pins stop below INFO, or below WARN for DispatcherServlet: the 500 advice's ERROR line still reaches
        // the log.
        assertLoggedSince(output, "ERROR", UNHANDLED_POST, unhandledBefore);
    }

    @Test
    void wsdlReadWithConfiguredCredentialsAndServedToCallerWithoutLoggingEither(CapturedOutput output) {
        // The WSDL in its diagnostic form, without the user information it was read with.
        String wsdl = "URL [http://" + LOOPBACK + ":" + upstream.getPort() + WSDL_PATH + "]";
        // Written by the unpinned WsdlQueryPublisher at DEBUG when it answers ?wsdl.
        String serving = "Serving WSDL " + wsdl + " for ";
        long servingBefore = lines(output, "DEBUG", serving);

        HttpTestClient.Response response = client().send("GET", "/?wsdl", callerHeaders(), null);

        assertThat(response.statusLine()).as("%s", response).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.bodyAsString())
                .contains("<wsdl:service name=\"ShopService\">")
                .contains("soap:address location=\"http://" + LOOPBACK + ":" + port + "/\"")
                .doesNotContain("SENTINEL_");
        assertThat(upstream.findAll(anyRequestedFor(anyUrl()))).as("upstream calls while answering").isEmpty();
        assertLogHoldsNoSentinel(output);
        assertLoggedSince(output, "DEBUG", serving, servingBefore);
        assertThat(lines(output, "INFO", "Publishing " + wsdl + " for ")).as("the start-up read's INFO line").isOne();
    }

    /** The upstream address points at a closed local port, so every post fails to connect. */
    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                    "logging.level.root=TRACE",
                    "trace=true",
                    "spring.mvc.log-request-details=true",
                    "proxy.wsdl-location=classpath:upstream/ShopService.wsdl"})
    @DirtiesContext
    class UnreachableUpstream {

        private static final int CLOSED_PORT = closedPort();

        @LocalServerPort
        int proxyPort;

        @DynamicPropertySource
        static void unreachableUpstream(DynamicPropertyRegistry registry) {
            registry.add("proxy.upstream-url", () -> "http://" + LOOPBACK + ":" + CLOSED_PORT + UPSTREAM_PATH);
        }

        @Test
        void connectFailureIsAnswered500WithoutLoggingRequestContent(CapturedOutput output) {
            long mappedBefore = lines(output, "TRACE", MAPPED_TO_PROXY);
            long unhandledBefore = lines(output, "ERROR", UNHANDLED_POST);

            HttpTestClient.Response response = new HttpTestClient(LOOPBACK, proxyPort)
                    .send("POST", "/?" + QUERY, callerHeaders(), ENVELOPE);

            assertThat(response.statusLine()).as("%s", response).isEqualTo("HTTP/1.1 500 Internal Server Error");
            assertThat(response.bodyAsString()).isEqualTo("{ \"message\": \"I/O error: Connection refused\" }");
            assertLogHoldsNoSentinel(output);
            assertLoggedSince(output, "TRACE", MAPPED_TO_PROXY, mappedBefore);
            assertLoggedSince(output, "ERROR", UNHANDLED_POST, unhandledBefore);
        }
    }

    private HttpTestClient client() {
        return new HttpTestClient(LOOPBACK, port);
    }

    private static Map<String, String> callerHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "text/xml; charset=UTF-8");
        headers.put("Authorization", AUTHORIZATION);
        headers.put("X-Api-Token", API_TOKEN);
        headers.put("Cookie", COOKIE);
        return headers;
    }

    /** The output stays free of every sentinel for {@link #SETTLE} after the exchange. */
    private static void assertLogHoldsNoSentinel(CapturedOutput output) {
        await().during(SETTLE).atMost(SETTLE.multipliedBy(4)).untilAsserted(() -> assertThat(output.getAll().lines()
                .filter(line -> SENTINELS.stream().anyMatch(line::contains)))
                .as("log lines carrying a sentinel")
                .isEmpty());
    }

    /**
     * An unpinned logger wrote a line at {@code level} holding {@code text} since {@code before} such lines were
     * counted, so the exchange was logged at that level: the positive control of every case.
     */
    private static void assertLoggedSince(CapturedOutput output, String level, String text, long before) {
        assertThat(lines(output, level, text)).as("%s lines holding '%s'", level, text).isGreaterThan(before);
    }

    /** The captured lines at {@code level} holding {@code text}. */
    private static long lines(CapturedOutput output, String level, String text) {
        String levelColumn = " " + level + " ";
        return output.getAll().lines().filter(line -> line.contains(levelColumn) && line.contains(text)).count();
    }

    private static LoggerContext loggerContext() {
        return (LoggerContext) LoggerFactory.getILoggerFactory();
    }

    /** A local port that was free a moment ago and is closed now. */
    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
