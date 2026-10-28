/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.controller;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URLEncoder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.filter.OrderedRequestContextFilter;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.server.endpoint.adapter.DefaultMethodEndpointAdapter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.mulesoft.examples.service_orchestration_and_choice_routing.ServiceOrchestrationAndChoiceRoutingApplication;
import com.mulesoft.examples.service_orchestration_and_choice_routing.service.OrderOrchestrationService;
import com.mulesoft.examples.service_orchestration_and_choice_routing.support.HttpTestClient;

/**
 * Wire-level test of {@link OrderProcessingEndpoint}, the converted {@code orderService} flow of the original
 * {@code fulfillment.xml} (lines 28-48): the {@code http:listener allowedMethods="POST" path="orders"} on 1080 and the
 * {@code cxf:jaxws-service} answering {@code IProcessOrder.processOrder}.
 *
 * <p>The whole application runs, so every request passes the listener's real chain: SC-10's port routing,
 * SC-11's listener boundary, fault resolver and envelope styler, the listener-path guard, and
 * {@link OrderOrchestrationService} with its Samsung leg, which calls this application's own Samsung listener over
 * HTTP. Every request goes through the raw-socket {@link HttpTestClient}, so status, headers and body bytes are
 * asserted as Undertow sent them. The only test double is a spy on {@link OrderOrchestrationService}; Mockito resets
 * it after every test, so only {@link #serviceFailureIsServerFault()} changes its behaviour.
 *
 * <p>The ports are allocated here rather than left random: each additional listener must be bound to a concrete port,
 * because SC-10 identifies a listener by the local port that received the request, and the Samsung and SOAP client
 * URLs must name their listener's port before the context starts. The context gets its own in-memory Derby database
 * and its own embedded Artemis server id, so it never shares state with another context of the same test JVM. The
 * nested {@link UnderRootTrace} run covers the logger levels {@code application.yml} pins, in a context of its own.
 */
@SpringBootTest(classes = ServiceOrchestrationAndChoiceRoutingApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderProcessingEndpointTest {

    private static final String ORDERS_PATH = "/orders";

    private static final String SOAP11_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    /** The request headers of every SOAP post; no {@code SOAPAction}, as the original test sent none. */
    private static final Map<String, String> SOAP_HEADERS = Map.of("Content-Type", "text/xml; charset=UTF-8");

    /** The original test's request, {@code src/test/resources/message.xml}: one Samsung item. */
    private static final byte[] MESSAGE = resource("/message.xml");

    /** The original test's expected reply, {@code src/test/resources/reply.xml}, compared exactly. */
    private static final String REPLY = new String(resource("/reply.xml"), UTF_8);

    /** A {@code SamsungService.purchase} request: a valid operation of this application, served on another path. */
    private static final String PURCHASE_ENVELOPE = "<soapenv:Envelope"
            + " xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\""
            + " xmlns:sam=\"http://samsung.se.mulesoft.com/\">"
            + "<soapenv:Header/><soapenv:Body><sam:purchase><orderRequest><name>s-1</name>"
            + "<quantity>1</quantity></orderRequest></sam:purchase></soapenv:Body></soapenv:Envelope>";

    /** CXF's text for a message part outside the service's WSDL, two spaces before the parenthesis included. */
    private static final String UNKNOWN_PURCHASE_FAULT = "Message part {http://samsung.se.mulesoft.com/}purchase"
            + " was not recognized.  (Does it exist in service WSDL?)";

    private static final int MAIN_PORT;
    private static final int AJAX_PORT;
    private static final int POPULATE_PORT;
    private static final int SAMSUNG_PORT;
    private static final int PRICES_PORT;

    /** Embedded Artemis server id of this context: the in-VM acceptor is registered per id in the JVM. */
    private static final int ARTEMIS_SERVER_ID;

    /** In-memory Derby database of this context; the suffix holds letters and digits only. */
    private static final String DERBY_DATABASE;

    static {
        int[] ports = distinctFreePorts(5);
        MAIN_PORT = ports[0];
        AJAX_PORT = ports[1];
        POPULATE_PORT = ports[2];
        SAMSUNG_PORT = ports[3];
        PRICES_PORT = ports[4];
        ARTEMIS_SERVER_ID = ThreadLocalRandom.current().nextInt(10_000, Integer.MAX_VALUE);
        DERBY_DATABASE = "memory:OrderProcessingEndpointTest_" + UUID.randomUUID().toString().replace("-", "");
    }

    @SpyBean
    OrderOrchestrationService orchestration;

    /**
     * Moves every original port onto an allocated one. The additional listeners' ports in {@code application.yml}
     * are placeholders over {@code ajax.port} and {@code ports.*}, so those keys are overridden and the
     * {@code listeners.additional} list, which Spring Boot binds from one property source only, stays as declared.
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> MAIN_PORT);
        registry.add("ajax.port", () -> AJAX_PORT);
        registry.add("ports.populate", () -> POPULATE_PORT);
        registry.add("ports.samsung", () -> SAMSUNG_PORT);
        registry.add("ports.prices", () -> PRICES_PORT);
        registry.add("orders.samsung.url", () -> "http://localhost:" + SAMSUNG_PORT + "/samsung/orders");
        registry.add("orders.price.url", () -> "http://localhost:" + PRICES_PORT + "/api/prices/{productId}");
        registry.add("orders.soap.url", () -> "http://localhost:" + MAIN_PORT + ORDERS_PATH);
        registry.add("spring.datasource.xa.properties.databaseName", () -> DERBY_DATABASE);
        registry.add("spring.datasource.xa.properties.createDatabase", () -> "create");
        registry.add("spring.artemis.mode", () -> "embedded");
        registry.add("spring.artemis.embedded.persistent", () -> "false");
        registry.add("spring.artemis.embedded.server-id", () -> ARTEMIS_SERVER_ID);
    }

    /** The original {@code orderTest}: status 200 and the payload exactly equal to {@code reply.xml}. */
    @Test
    void postMessageReturnsExactReply() {
        HttpTestClient.Response response = postOrders(MAIN_PORT, MESSAGE);

        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        assertThat(response.header("Content-Type")).startsWith("text/xml");
        assertNoRequestHeaders(response);
        assertThat(response.bodyAsString()).isEqualTo(REPLY);
    }

    @Test
    void getIsMethodNotAllowed() {
        HttpTestClient.Response response = HttpTestClient.localhost(MAIN_PORT).get(ORDERS_PATH);

        assertThat(response.status()).as(response.toString()).isEqualTo(405);
        assertThat(response.header("Content-Type")).isEqualTo("text/plain");
        assertThat(response.bodyAsString()).isEqualTo("Method not allowed for endpoint: " + ORDERS_PATH);
    }

    static Stream<Arguments> unreadableEnvelopes() {
        return Stream.of(
                Arguments.of(Named.of("malformed XML", ("<soap:Envelope xmlns:soap=\"" + SOAP11_NS
                        + "\"><soap:Body><unclosed>").getBytes(UTF_8))),
                Arguments.of(Named.of("empty body", new byte[0])),
                Arguments.of(Named.of("non-envelope root", "<foo/>".getBytes(UTF_8))));
    }

    /** A body that cannot be read as a SOAP 1.1 envelope is answered at the listener boundary. */
    @ParameterizedTest(name = "{0} is a soap:Client fault")
    @MethodSource("unreadableEnvelopes")
    void unreadableEnvelopeIsClientFault(byte[] body) throws Exception {
        HttpTestClient.Response response = postOrders(MAIN_PORT, body);

        assertThat(response.status()).as(response.toString()).isEqualTo(500);
        assertThat(response.header("Content-Type")).startsWith("text/xml");
        assertNoRequestHeaders(response);
        Fault fault = fault(response.bodyAsString());
        assertThat(fault.code()).isEqualTo("soap:Client");
        assertThat(fault.string()).isNotBlank();
    }

    /** One dispatcher servlet serves both SOAP listeners; the Samsung operation is still unknown on /orders. */
    @Test
    void samsungOperationOnOrdersListenerIsClientFault() throws Exception {
        HttpTestClient.Response response = postOrders(MAIN_PORT, PURCHASE_ENVELOPE.getBytes(UTF_8));

        assertThat(response.status()).as(response.toString()).isEqualTo(500);
        assertThat(response.header("Content-Type")).startsWith("text/xml");
        Fault fault = fault(response.bodyAsString());
        assertThat(fault.code()).isEqualTo("soap:Client");
        assertThat(fault.string()).isEqualTo(UNKNOWN_PURCHASE_FAULT);
    }

    /** The order listener exists on the main port only; another listener's port has no {@code /orders}. */
    @Test
    void ordersPathOnPopulatePortIsNoListener() {
        HttpTestClient.Response response = postOrders(POPULATE_PORT, MESSAGE);

        assertThat(response.status()).as(response.toString()).isEqualTo(404);
        assertThat(response.header("Content-Type")).startsWith("text/plain");
        assertThat(response.bodyAsString()).isEqualTo("No listener for endpoint: " + ORDERS_PATH);
    }

    /** An exception escaping the flow becomes a {@code soap:Server} fault carrying its message. */
    @Test
    void serviceFailureIsServerFault() throws Exception {
        doThrow(new IllegalStateException("boom")).when(orchestration).processOrder(any());

        HttpTestClient.Response response = postOrders(MAIN_PORT, MESSAGE);

        assertThat(response.status()).as(response.toString()).isEqualTo(500);
        assertThat(response.header("Content-Type")).startsWith("text/xml");
        assertNoRequestHeaders(response);
        Fault fault = fault(response.bodyAsString());
        assertThat(fault.code()).isEqualTo("soap:Server");
        assertThat(fault.string()).isEqualTo("boom");
    }

    /** The orders of {@link LoggedOrders} under {@code logging.level.root=TRACE}. */
    @Nested
    @TestPropertySource(properties = "logging.level.root=TRACE")
    class UnderRootTrace extends LoggedOrders {
    }

    /**
     * Tests the logger levels {@code application.yml} pins, which keep the order's customer out of the log under a
     * root {@code TRACE} level: {@code fulfillment.xml} logs only {@code defaultErrorHandler}'s
     * {@code Error occurred: <payload>}.
     *
     * <p>The whole application runs unmocked in a context of its own: listener ports drawn apart from the enclosing
     * class's, which stays open in the context cache, its own Derby database and Artemis server id, and an in-house
     * reply timeout of {@link #IN_HOUSE_REPLY_TIMEOUT_MS} ms. The tables are never populated, so every audit insert
     * fails into {@code defaultErrorHandler} and an in-house item is rolled back until its redelivery is exhausted, as
     * in the original before {@code /populate}; every total stays within the audit's rollback threshold. Three orders
     * are sent: the original test's Samsung order, an in-house order and an order published on the Ajax channel
     * {@code /orders/request} with a {@code GET}, whose reply a held {@code /meta/connect} receives. Each is asserted
     * on the wire, then its own log events are asserted to hold a {@code TRACE} line of an unpinned logger, which
     * proves the capture sees that level, and the original {@code Error occurred:} line, and to hold no line of the
     * embedded broker's {@code INFO} audit loggers, and the console output is asserted to hold none of the customers'
     * data. The run restores the logger levels it found and closes its context, so later test classes do not run at
     * {@code TRACE}.
     */
    @SpringBootTest(classes = ServiceOrchestrationAndChoiceRoutingApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @DirtiesContext
    @ExtendWith(OutputCaptureExtension.class)
    abstract static class LoggedOrders {

        /** The customer of {@code message.xml}, in the forms its envelope and summary carry it. */
        static final List<String> ORIGINAL_CUSTOMER =
                List.of("Main Street 123", "<firstName>John</firstName>", "<lastName>Doe</lastName>");

        /** The customer of the in-house and Ajax orders. */
        static final String FIRST_NAME = "Ophelia";
        static final String LAST_NAME = "Brackenbury";
        static final String ADDRESS = "9 Tidewater Lane";

        /** Every customer value that must stay out of the log. */
        static final List<String> CUSTOMER_DATA = Stream.concat(ORIGINAL_CUSTOMER.stream(),
                Stream.of(FIRST_NAME, LAST_NAME, ADDRESS)).toList();

        /** The in-house reply wait of this context; the item's redelivery ends long before it. */
        static final int IN_HOUSE_REPLY_TIMEOUT_MS = 3000;

        /** Most {@code /meta/connect} requests an Ajax order waits through for its reply. */
        private static final int MAX_CONNECTS = 3;

        /** The original {@code defaultErrorHandler} line of {@code OrderOrchestrationService.logError}. */
        private static final String ERROR_OCCURRED = "Error occurred: ";

        /** The parent of the embedded broker's audit loggers ({@code .base}, {@code .resource}, {@code .message}). */
        private static final String BROKER_AUDIT_LOGGER = "org.apache.activemq.audit";

        /** How long lines written after an exchange get to arrive before the output is scanned for customer data. */
        private static final Duration SETTLE = Duration.ofMillis(300);

        /** Most offending lines a failure reports; the capture holds the whole run, so it is never dumped. */
        private static final int REPORTED_LINES = 20;

        /** The logger levels set before this run's context started, by logger name. */
        private static final Map<String, Level> LEVELS_BEFORE = new HashMap<>();

        private final ObjectMapper mapper = new ObjectMapper();

        @Value("${server.port}")
        int mainPort;

        @Value("${ajax.port}")
        int ajaxPort;

        @Value("${orders.samsung.url}")
        String samsungUrl;

        @DynamicPropertySource
        static void unmockedProperties(DynamicPropertyRegistry registry) {
            Set<Integer> ports = new LinkedHashSet<>(Set.of(MAIN_PORT, AJAX_PORT, POPULATE_PORT, SAMSUNG_PORT,
                    PRICES_PORT));
            int enclosingPorts = ports.size();
            while (ports.size() < enclosingPorts + 5) {
                ports.add(freePort());
            }
            int[] own = ports.stream().skip(enclosingPorts).mapToInt(Integer::intValue).toArray();
            int artemisServerId = ThreadLocalRandom.current().nextInt(10_000, Integer.MAX_VALUE);
            String derbyDatabase = "memory:OrderProcessingEndpointTest_logged_"
                    + UUID.randomUUID().toString().replace("-", "");
            registry.add("server.port", () -> own[0]);
            registry.add("ajax.port", () -> own[1]);
            registry.add("ports.populate", () -> own[2]);
            registry.add("ports.samsung", () -> own[3]);
            registry.add("ports.prices", () -> own[4]);
            registry.add("orders.samsung.url", () -> "http://localhost:" + own[3] + "/samsung/orders");
            registry.add("orders.price.url", () -> "http://localhost:" + own[4] + "/api/prices/{productId}");
            registry.add("orders.soap.url", () -> "http://localhost:" + own[0] + ORDERS_PATH);
            registry.add("orders.inhouse.reply-timeout-ms", () -> IN_HOUSE_REPLY_TIMEOUT_MS);
            registry.add("spring.datasource.xa.properties.databaseName", () -> derbyDatabase);
            registry.add("spring.datasource.xa.properties.createDatabase", () -> "create");
            registry.add("spring.artemis.mode", () -> "embedded");
            registry.add("spring.artemis.embedded.persistent", () -> "false");
            registry.add("spring.artemis.embedded.server-id", () -> artemisServerId);
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

        /** The original {@code orderTest}'s order: one Samsung item, bought over this application's Samsung SOAP. */
        @Test
        void samsungOrderAnswersTheOriginalReplyWhileTheLogHoldsNoCustomer(CapturedOutput output) throws Exception {
            assertThat(new String(MESSAGE, UTF_8)).contains(ORIGINAL_CUSTOMER);

            Logged<HttpTestClient.Response> exchange = logged(() -> postOrders(mainPort, MESSAGE));

            assertThat(exchange.result().status()).as(exchange.result().toString()).isEqualTo(200);
            assertThat(exchange.result().bodyAsString()).isEqualTo(REPLY);
            assertEndpointInvoked(exchange.events(), output);
            assertThat(exchange.events()).as("the unpinned WebServiceTemplate DEBUG connection line to Samsung")
                    .anyMatch(event -> event.getLevel() == Level.DEBUG
                            && WebServiceTemplate.class.getName().equals(event.getLoggerName())
                            && event.getFormattedMessage().startsWith("Opening [")
                            && event.getFormattedMessage().endsWith("] to [" + samsungUrl + "]"));
            assertNoCustomerDataLogged(output);
        }

        /** An item of another manufacturer, sent to the in-house JMS flow, whose insert finds no table. */
        @Test
        void inHouseOrderAnswersWhileTheLogHoldsNoCustomer(CapturedOutput output) throws Exception {
            byte[] order = withCustomer(new String(MESSAGE, UTF_8)
                    .replace("<manufacturer>Samsung</manufacturer>", "<manufacturer>Sony</manufacturer>"))
                    .getBytes(UTF_8);

            Logged<HttpTestClient.Response> exchange = logged(() -> postOrders(mainPort, order));

            assertThat(exchange.result().status()).as(exchange.result().toString()).isEqualTo(200);
            assertThat(exchange.result().bodyAsString())
                    .contains("<address>" + ADDRESS + "</address>", "<firstName>" + FIRST_NAME + "</firstName>",
                            "<lastName>" + LAST_NAME + "</lastName>", "<manufacturer>Sony</manufacturer>")
                    .doesNotContain("<purchaseReceipt>");
            assertEndpointInvoked(exchange.events(), output);
            assertNoCustomerDataLogged(output);
        }

        /**
         * An order published on {@code /orders/request} the way {@code mule.rpc} does, the publication sent as a
         * {@code GET} with its {@code message} query parameter: the Ajax flow posts it to {@code /orders} and the
         * summary reaches the {@code replyTo} channel the held {@code /meta/connect} receives.
         */
        @Test
        void ajaxOrderAnswersWhileTheLogHoldsNoCustomer(CapturedOutput output) throws Exception {
            String order = mapper.writeValueAsString(Map.of(
                    "orderId", "77",
                    "customer", Map.of("firstName", FIRST_NAME, "lastName", LAST_NAME, "address", ADDRESS),
                    "orderItems", List.of(Map.of("item", Map.of("manufacturer", "Samsung", "name", "Galaxy",
                            "productId", "i9000", "quantity", "1")))));
            HttpTestClient ajax = HttpTestClient.localhost(ajaxPort);

            Logged<JsonNode> exchange = logged(() -> {
                String clientId = bayeux(ajax, "POST", List.of(Map.of("channel", "/meta/handshake",
                        "version", "1.0", "supportedConnectionTypes", List.of("long-polling"), "id", "0")))
                        .get(0).path("clientId").asText();
                String replyTo = "/orders/request#" + clientId;
                bayeux(ajax, "POST", List.of(Map.of("channel", "/meta/subscribe", "clientId", clientId,
                        "subscription", replyTo, "id", "1")));
                bayeux(ajax, "GET", List.of(Map.of("channel", "/orders/request", "clientId", clientId,
                        "data", Map.of("data", order, "replyTo", replyTo), "id", "2")));
                // The first connect after a handshake is answered at once; a later one is held until the reply.
                for (int connect = 0; connect < MAX_CONNECTS; connect++) {
                    for (JsonNode message : bayeux(ajax, "POST", List.of(Map.of("channel", "/meta/connect",
                            "clientId", clientId, "connectionType", "long-polling", "id", "c" + connect)))) {
                        if (replyTo.equals(message.path("channel").asText())) {
                            return message;
                        }
                    }
                }
                return null;
            });

            assertThat(exchange.result()).as("the reply on the replyTo channel").isNotNull();
            assertThat(exchange.result().path("data").asText()).contains(FIRST_NAME, LAST_NAME, ADDRESS);
            ILoggingEvent bound = exchange.events().stream()
                    .filter(event -> event.getLevel() == Level.TRACE
                            && OrderedRequestContextFilter.class.getName().equals(event.getLoggerName())
                            && event.getFormattedMessage().startsWith("Bound request context to thread: ")
                            && event.getFormattedMessage().contains(" GET " + BayeuxLongPollingController.COMETD_PATH))
                    .findFirst()
                    .orElse(null);
            assertThat(bound).as("the unpinned OrderedRequestContextFilter TRACE line of the GET publication")
                    .isNotNull();
            assertThat(output.getAll()).as("the console capture holds the TRACE line of the GET publication")
                    .contains(bound.getFormattedMessage());
            assertEndpointInvoked(exchange.events(), output);
            assertNoCustomerDataLogged(output);
        }

        /**
         * Asserts the {@code TRACE} invocation line of {@link OrderProcessingEndpoint} among {@code events} and on the
         * console, the original {@code Error occurred:} line among {@code events}, and no broker audit event among
         * them.
         */
        private static void assertEndpointInvoked(List<ILoggingEvent> events, CapturedOutput output) {
            ILoggingEvent invocation = events.stream()
                    .filter(event -> event.getLevel() == Level.TRACE
                            && DefaultMethodEndpointAdapter.class.getName().equals(event.getLoggerName())
                            && event.getFormattedMessage().startsWith("Invoking [")
                            && event.getFormattedMessage().contains(OrderProcessingEndpoint.class.getName()))
                    .findFirst()
                    .orElse(null);
            assertThat(invocation).as("the unpinned DefaultMethodEndpointAdapter TRACE line of the order").isNotNull();
            assertThat(output.getAll()).as("the console capture holds the TRACE line of the order")
                    .contains(invocation.getFormattedMessage());
            assertThat(events).as("the original defaultErrorHandler INFO line of the order")
                    .anyMatch(event -> event.getLevel() == Level.INFO
                            && OrderOrchestrationService.class.getName().equals(event.getLoggerName())
                            && event.getFormattedMessage().startsWith(ERROR_OCCURRED));
            // Each receive of the in-house listener, one a second, opens a broker session and consumer, and an in-house
            // order crosses the broker, so a window spanning either holds audit events unless they are pinned.
            assertThat(events).as("events of the embedded broker's audit loggers")
                    .noneMatch(event -> event.getLoggerName().startsWith(BROKER_AUDIT_LOGGER));
        }

        /**
         * Asserts that the console output holds no customer value once lines written after the exchange have had
         * {@link #SETTLE} to arrive; the capture only grows, so one scan at the end covers the whole wait.
         */
        private static void assertNoCustomerDataLogged(CapturedOutput output) {
            await().pollDelay(SETTLE).until(() -> true);
            assertThat(linesHoldingCustomerData(output.getAll()))
                    .as("console lines holding a customer's names or address").isEmpty();
        }

        /**
         * Runs {@code exchange} with a list appender on the root logger and returns its result and the log events
         * written until the original {@code Error occurred:} line, which the asynchronous audit writes after the reply
         * because the tables are never populated.
         */
        private static <T> Logged<T> logged(ThrowingSupplier<T> exchange) throws Exception {
            Logger root = loggerContext().getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            ListAppender<ILoggingEvent> events = new ListAppender<>();
            events.setContext(loggerContext());
            events.start();
            root.addAppender(events);
            T result;
            try {
                result = exchange.get();
                await().atMost(Duration.ofSeconds(10)).until(() -> {
                    synchronized (events) {
                        return events.list.stream()
                                .anyMatch(event -> event.getFormattedMessage().startsWith(ERROR_OCCURRED));
                    }
                });
            } finally {
                root.detachAppender(events);
                events.stop();
            }
            // The appender appends under its own lock, so reading under it sees every event the exchange wrote.
            synchronized (events) {
                return new Logged<>(result, List.copyOf(events.list));
            }
        }

        /** Sends one Bayeux batch, as the copied client does, and returns the reply's messages. */
        private List<JsonNode> bayeux(HttpTestClient ajax, String method, List<Map<String, Object>> batch)
                throws IOException {
            String message = "message=" + URLEncoder.encode(mapper.writeValueAsString(batch), UTF_8);
            HttpTestClient.Response response = "GET".equals(method)
                    ? ajax.get(BayeuxLongPollingController.COMETD_PATH + "?" + message)
                    : ajax.send("POST", BayeuxLongPollingController.COMETD_PATH,
                            Map.of("Content-Type", "application/x-www-form-urlencoded"), message.getBytes(UTF_8));
            assertThat(response.status()).as(response.toString()).isEqualTo(200);
            List<JsonNode> messages = new ArrayList<>();
            mapper.readTree(response.body()).forEach(messages::add);
            return messages;
        }

        /** {@code envelope} with the customer of {@code message.xml} replaced by this class's customer. */
        private static String withCustomer(String envelope) {
            return envelope.replace("<address>Main Street 123</address>", "<address>" + ADDRESS + "</address>")
                    .replace("<firstName>John</firstName>", "<firstName>" + FIRST_NAME + "</firstName>")
                    .replace("<lastName>Doe</lastName>", "<lastName>" + LAST_NAME + "</lastName>");
        }

        /** The distinct lines of {@code output} holding a customer value, at most {@link #REPORTED_LINES}. */
        private static List<String> linesHoldingCustomerData(String output) {
            // The capture of a TRACE run is large, so it is split into lines only when it holds a value at all.
            if (CUSTOMER_DATA.stream().noneMatch(output::contains)) {
                return List.of();
            }
            return output.lines()
                    .filter(line -> CUSTOMER_DATA.stream().anyMatch(line::contains))
                    .distinct()
                    .limit(REPORTED_LINES)
                    .toList();
        }

        private static LoggerContext loggerContext() {
            return (LoggerContext) LoggerFactory.getILoggerFactory();
        }

        /** An exchange that may throw. */
        @FunctionalInterface
        interface ThrowingSupplier<T> {
            T get() throws Exception;
        }

        /** What an exchange returned and the log events written while it ran. */
        record Logged<T>(T result, List<ILoggingEvent> events) {
        }
    }

    private static HttpTestClient.Response postOrders(int port, byte[] body) {
        return HttpTestClient.localhost(port).send("POST", ORDERS_PATH, SOAP_HEADERS, body);
    }

    /** SAAJ keeps {@code Accept} and {@code SOAPAction} in every message's MIME headers; CXF sent neither back. */
    private static void assertNoRequestHeaders(HttpTestClient.Response response) {
        assertThat(response.header("Accept")).as("Accept of %s", response).isNull();
        assertThat(response.header("SOAPAction")).as("SOAPAction of %s", response).isNull();
    }

    /** The {@code faultcode} and {@code faultstring} texts of a SOAP 1.1 fault. */
    private record Fault(String code, String string) {
    }

    /**
     * Reads the single SOAP 1.1 {@code Fault} of {@code body} and its unqualified {@code faultcode} and
     * {@code faultstring} children; a child that is absent reads as {@code null}.
     */
    private static Fault fault(String body) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body.getBytes(UTF_8)));
        NodeList faults = document.getElementsByTagNameNS(SOAP11_NS, "Fault");
        assertThat(faults.getLength()).as("SOAP 1.1 Fault elements in %s", body).isEqualTo(1);
        Element fault = (Element) faults.item(0);
        return new Fault(childText(fault, "faultcode"), childText(fault, "faultstring"));
    }

    /** The text of {@code parent}'s first child element named {@code localName} in no namespace, or {@code null}. */
    private static String childText(Element parent, String localName) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE && child.getNamespaceURI() == null
                    && localName.equals(child.getLocalName())) {
                return child.getTextContent();
            }
        }
        return null;
    }

    /** The bytes of a classpath resource, failing the class's initialisation when it is missing. */
    private static byte[] resource(String path) {
        try (InputStream in = OrderProcessingEndpointTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Test resource " + path + " is missing");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read test resource " + path, e);
        }
    }

    /** {@code count} distinct ports that were free when probed, in probing order. */
    private static int[] distinctFreePorts(int count) {
        Set<Integer> ports = new LinkedHashSet<>();
        while (ports.size() < count) {
            ports.add(freePort());
        }
        return ports.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot allocate a free port", e);
        }
    }
}
