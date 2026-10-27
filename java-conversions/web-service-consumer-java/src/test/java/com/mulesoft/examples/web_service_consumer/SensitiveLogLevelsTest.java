/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.web_service_consumer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingXPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.mulesoft.examples.web_service_consumer.client.TshirtServiceClient;
import com.mulesoft.examples.web_service_consumer.support.HttpTestClient;

/**
 * Tests the logger levels {@code application.yml} pins, which keep the T-shirt service API key and the customer's
 * order out of the log under a root {@code TRACE} level and under Spring Boot's {@code trace} switch:
 * {@code tshirt-service-consumer.xml} has no logger.
 *
 * <p>The whole application runs on a random port in front of a WireMock {@code TshirtService}, once under
 * {@code logging.level.root=TRACE} and once under Spring Boot's {@code trace} switch (the {@code --trace} command-line
 * flag), with the API key set to a sentinel. Each exchange, sent through ST-1 {@link HttpTestClient}, is asserted on
 * the wire and at the upstream (the {@code AuthenticationHeader} carried the key), then the captured console output
 * is asserted to hold neither the key nor the order's personal data: the accepted order, the inventory, a SOAP fault,
 * an HTTP error without a fault, a connection reset and an order the mapper cannot read, whose 500 body repeats the
 * caller's token. A third run under root {@code TRACE} without a key covers the order rejected before any call.
 *
 * <p>Each exchange also asserts that the levels are really low and the capture sees them: the
 * {@code DispatcherServlet} wrote its {@code TRACE} request line, the {@code WebServiceTemplate} its {@code DEBUG}
 * connection line where the service was called, and {@code GlobalExceptionHandler} still wrote its {@code ERROR} line
 * for every failure. Each run restores the logger levels it found and closes its context, so later test classes do
 * not run at {@code TRACE}.
 */
class SensitiveLogLevelsTest {

    /** The API key the runs with a key configure; supplied as a dynamic property, so no context cache key names it. */
    static final String API_KEY = "SENTINEL_TSHIRT_API_KEY";

    /** An e-mail address sent unquoted, which the JSON parser names in its message and so in the 500 body. */
    static final String UNQUOTED_EMAIL_TOKEN = "SENTINEL_UNQUOTED_EMAIL";

    /** Personal data of {@code message.json}, the original test's order. */
    static final List<String> ORDER_DATA = List.of(
            "customerID@gmail.com", "Corrientes 316", "MuleSoft Argentina", "C1043AAQ");

    /** Every value that must stay out of the log. */
    static final List<String> SENTINELS = Stream.concat(Stream.of(API_KEY, UNQUOTED_EMAIL_TOKEN), ORDER_DATA.stream())
            .toList();

    private static final String ORDER_ACTION = "\"" + TshirtServiceClient.ORDER_TSHIRT_ACTION + "\"";

    private static final String INVENTORY_ACTION = "\"" + TshirtServiceClient.LIST_INVENTORY_ACTION + "\"";

    private static final String JSON = "application/json";

    private static final String API_KEY_XPATH =
            "//*[local-name()='AuthenticationHeader']/*[local-name()='apiKey']/text()";

    private static final String ERROR_LINE_PREFIX = "Unhandled exception processing POST /orders: ";

    /** The application under {@code logging.level.root=TRACE} with a configured API key. */
    @Nested
    @TestPropertySource(properties = "logging.level.root=TRACE")
    class ConfiguredKeyUnderRootTrace extends ConfiguredKeyExchanges {
    }

    /** The application under Spring Boot's {@code trace} switch, which sets {@code org.springframework} to TRACE. */
    @Nested
    @TestPropertySource(properties = "trace=true")
    class ConfiguredKeyUnderTraceSwitch extends ConfiguredKeyExchanges {
    }

    /** The application under {@code logging.level.root=TRACE} without an API key. */
    @Nested
    @TestPropertySource(properties = {"logging.level.root=TRACE", "tshirt.api-key="})
    class MissingKeyUnderRootTrace extends LoggedExchanges {

        // The order is mapped before the client rejects the missing key, so the order data is in memory but not logged.
        @Test
        void orderRejectedForTheMissingKeyLogsNoOrderData(CapturedOutput output) {
            assertExchange(new Exchange("POST /orders without a key", null, null, "/orders", JSON, message(),
                    "HTTP/1.1 500 Internal Server Error",
                    "{ \"message\": \"T-shirt service API key (tshirt.api-key) is not configured\" }",
                    Upstream.NOT_CALLED, "java.lang.IllegalStateException"), output);
        }
    }

    /** The exchanges of the runs with a configured API key. */
    abstract static class ConfiguredKeyExchanges extends LoggedExchanges {

        @DynamicPropertySource
        static void apiKey(DynamicPropertyRegistry registry) {
            registry.add("tshirt.api-key", () -> API_KEY);
        }

        static Stream<Exchange> exchanges() {
            return Stream.of(
                    new Exchange("POST /orders accepted", ORDER_ACTION,
                            soapAnswer(200, "<ns2:OrderTshirtResponse xmlns:ns2=\"http://mulesoft.org/tshirt-service\">"
                                    + "<orderId>1</orderId></ns2:OrderTshirtResponse>"),
                            "/orders", JSON, message(), "HTTP/1.1 200 OK", "\"orderId\": \"1\"",
                            Upstream.CALLED_WITH_KEY, null),
                    new Exchange("POST /inventory listed", INVENTORY_ACTION,
                            soapAnswer(200, "<ns2:ListInventoryResponse"
                                    + " xmlns:ns2=\"http://mulesoft.org/tshirt-service\">"
                                    + "<inventory><productCode>1412</productCode><size>M</size>"
                                    + "<description>Prueba2</description><count>5</count></inventory>"
                                    + "</ns2:ListInventoryResponse>"),
                            "/inventory", null, null, "HTTP/1.1 200 OK", "\"productCode\": \"1412\"",
                            Upstream.CALLED, null),
                    new Exchange("POST /orders answered with a SOAP fault", ORDER_ACTION,
                            soapAnswer(500, "<soap:Fault><faultcode>soap:Client</faultcode>"
                                    + "<faultstring>Invalid API key</faultstring></soap:Fault>"),
                            "/orders", JSON, message(), "HTTP/1.1 500 Internal Server Error",
                            "{ \"message\": \"Invalid API key\" }",
                            Upstream.CALLED_WITH_KEY, "org.springframework.ws.soap.client.SoapFaultClientException"),
                    new Exchange("POST /orders answered with HTTP 500 without a fault", ORDER_ACTION,
                            aResponse().withStatus(500).withHeader("Content-Type", "text/html")
                                    .withBody("<html><body>Internal Server Error</body></html>"),
                            "/orders", JSON, message(), "HTTP/1.1 500 Internal Server Error",
                            "{ \"message\": \"Server Error [500]\" }",
                            Upstream.CALLED_WITH_KEY, "org.springframework.ws.client.WebServiceTransportException"),
                    new Exchange("POST /orders with the connection reset", ORDER_ACTION,
                            aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER),
                            "/orders", JSON, message(), "HTTP/1.1 500 Internal Server Error",
                            "{ \"message\": \"I/O error: Connection reset",
                            Upstream.CALLED_WITH_KEY, "org.springframework.ws.client.WebServiceIOException"),
                    new Exchange("POST /orders with an unreadable order", null, null,
                            "/orders", JSON,
                            ("{\"email\":" + UNQUOTED_EMAIL_TOKEN + "@gmail.com}").getBytes(StandardCharsets.UTF_8),
                            "HTTP/1.1 500 Internal Server Error",
                            "{ \"message\": \"Unrecognized token '" + UNQUOTED_EMAIL_TOKEN + "'",
                            Upstream.NOT_CALLED, "java.lang.IllegalArgumentException"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("exchanges")
        void exchangeRunsAsConfiguredWhileTheLogHoldsNoKeyOrOrderData(Exchange exchange, CapturedOutput output) {
            assertExchange(exchange, output);
        }
    }

    /** The application context, upstream, level bookkeeping and assertions every run shares. */
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @DirtiesContext
    @ExtendWith(OutputCaptureExtension.class)
    abstract static class LoggedExchanges {

        /** How long the output must stay free of sentinels after the exchange, for lines written after the reply. */
        private static final Duration SETTLE = Duration.ofMillis(300);

        /** Most offending lines a failure reports; the capture holds the whole run, so it is never dumped. */
        private static final int REPORTED_LINES = 20;

        /** The logger levels set before this run's context started, by logger name. */
        private static final Map<String, Level> LEVELS_BEFORE = new HashMap<>();

        @RegisterExtension
        static WireMockExtension upstream = WireMockExtension.newInstance()
                .options(wireMockConfig().dynamicPort())
                .build();

        @LocalServerPort
        int port;

        @DynamicPropertySource
        static void serviceAddress(DynamicPropertyRegistry registry) {
            registry.add("tshirt.service-address", upstream::baseUrl);
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

        /**
         * Sends the exchange's request with its upstream answer stubbed, then asserts the response, the upstream
         * calls, the low levels seen in the exchange's own log events and the sentinel-free console output.
         */
        void assertExchange(Exchange exchange, CapturedOutput output) {
            if (exchange.soapAction() != null) {
                upstream.stubFor(post(anyUrl())
                        .withHeader("SOAPAction", equalTo(exchange.soapAction()))
                        .willReturn(exchange.answer()));
            }
            Map<String, String> headers =
                    exchange.contentType() == null ? null : Map.of("Content-Type", exchange.contentType());

            Logger root = loggerContext().getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            ListAppender<ILoggingEvent> events = new ListAppender<>();
            events.setContext(loggerContext());
            events.start();
            root.addAppender(events);
            HttpTestClient.Response response;
            try {
                response = HttpTestClient.localhost(port).send("POST", exchange.path(), headers, exchange.body());
            } finally {
                root.detachAppender(events);
                events.stop();
            }
            // The appender appends under its own lock, so reading under it sees every event the request thread wrote.
            List<ILoggingEvent> logged;
            synchronized (events) {
                logged = List.copyOf(events.list);
            }

            assertThat(response.statusLine()).as(response.toString()).isEqualTo(exchange.statusLine());
            assertThat(response.header("Content-Type")).startsWith(JSON);
            assertThat(response.bodyAsString()).contains(exchange.bodyFragment());
            switch (exchange.upstream()) {
                case NOT_CALLED -> upstream.verify(0, postRequestedFor(anyUrl()));
                case CALLED -> upstream.verify(1, postRequestedFor(anyUrl())
                        .withHeader("SOAPAction", equalTo(exchange.soapAction())));
                case CALLED_WITH_KEY -> upstream.verify(1, postRequestedFor(anyUrl())
                        .withHeader("SOAPAction", equalTo(exchange.soapAction()))
                        .withRequestBody(matchingXPath(API_KEY_XPATH, equalTo(API_KEY))));
            }

            String requestLine = "POST \"" + exchange.path() + "\"";
            ILoggingEvent traceLine = logged.stream()
                    .filter(event -> event.getLevel() == Level.TRACE
                            && "org.springframework.web.servlet.DispatcherServlet".equals(event.getLoggerName())
                            && event.getFormattedMessage().startsWith(requestLine))
                    .findFirst()
                    .orElse(null);
            assertThat(traceLine).as("the unpinned DispatcherServlet TRACE request line of this exchange").isNotNull();
            if (exchange.upstream() != Upstream.NOT_CALLED) {
                assertThat(logged).as("the unpinned WebServiceTemplate DEBUG connection line of this exchange")
                        .anyMatch(event -> event.getLevel() == Level.DEBUG
                                && "org.springframework.ws.client.core.WebServiceTemplate".equals(event.getLoggerName())
                                && event.getFormattedMessage().startsWith("Opening [")
                                && event.getFormattedMessage().endsWith("] to [" + upstream.baseUrl() + "]"));
            }
            if (exchange.error() != null) {
                assertThat(logged).as("the GlobalExceptionHandler ERROR line of this exchange")
                        .anyMatch(event -> event.getLevel() == Level.ERROR
                                && event.getFormattedMessage().startsWith(ERROR_LINE_PREFIX + exchange.error()));
            }
            assertThat(output.getAll().contains(traceLine.getFormattedMessage()))
                    .as("the console capture holds the TRACE request line of this exchange").isTrue();
            await().during(SETTLE).atMost(SETTLE.multipliedBy(4))
                    .untilAsserted(() -> assertThat(linesHoldingASentinel(output.getAll()))
                            .as("console lines holding the API key or order data").isEmpty());
        }

        /** The distinct lines of {@code output} holding a sentinel, at most {@link #REPORTED_LINES} of them. */
        private static List<String> linesHoldingASentinel(String output) {
            return output.lines()
                    .filter(line -> SENTINELS.stream().anyMatch(line::contains))
                    .distinct()
                    .limit(REPORTED_LINES)
                    .toList();
        }

        private static LoggerContext loggerContext() {
            return (LoggerContext) LoggerFactory.getILoggerFactory();
        }
    }

    /** What the upstream must have received. */
    enum Upstream {
        NOT_CALLED,
        CALLED,
        CALLED_WITH_KEY
    }

    /**
     * One request and what it must produce.
     *
     * @param name         the case's display name
     * @param soapAction   the quoted SOAPAction the upstream answer is stubbed for, or {@code null} for no stub
     * @param answer       the upstream answer, or {@code null} for no stub
     * @param path         the listener path posted to
     * @param contentType  the request {@code Content-Type}, or {@code null} for none
     * @param body         the request body, or {@code null} for none
     * @param statusLine   the expected raw status line
     * @param bodyFragment text the response body must contain
     * @param upstream     what the upstream must have received
     * @param error        the exception class the {@code ERROR} line must name, or {@code null} for a success
     */
    record Exchange(String name, String soapAction, ResponseDefinitionBuilder answer, String path, String contentType,
                    byte[] body, String statusLine, String bodyFragment, Upstream upstream, String error) {

        @Override
        public String toString() {
            return name;
        }
    }

    /** A SOAP 1.1 envelope around {@code bodyContent}, as the T-shirt service answers. */
    static ResponseDefinitionBuilder soapAnswer(int status, String bodyContent) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "text/xml; charset=utf-8")
                .withBody("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
                        + bodyContent + "</soap:Body></soap:Envelope>");
    }

    /** The original test's order, {@code src/test/resources/message.json}, holding every {@link #ORDER_DATA} value. */
    static byte[] message() {
        try (InputStream in = Objects.requireNonNull(SensitiveLogLevelsTest.class.getResourceAsStream("/message.json"),
                "classpath resource /message.json")) {
            byte[] message = in.readAllBytes();
            assertThat(new String(message, StandardCharsets.UTF_8)).contains(ORDER_DATA);
            return message;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
