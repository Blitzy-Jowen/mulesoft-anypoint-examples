/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.ObjectMessage;
import jakarta.jms.TemporaryQueue;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.stubbing.Answer;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.SessionCallback;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;

import com.mulesoft.examples.service_orchestration_and_choice_routing.ServiceOrchestrationAndChoiceRoutingApplication;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.OrderItem;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.PurchaseReceipt;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.Status;
import com.mulesoft.examples.service_orchestration_and_choice_routing.service.InHouseOrderService;
import com.mulesoft.examples.service_orchestration_and_choice_routing.service.OrderOrchestrationService;
import com.mulesoft.examples.service_orchestration_and_choice_routing.service.PriceCacheService;
import com.mulesoft.examples.service_orchestration_and_choice_routing.support.PortInUseRetryingContextLoader;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Trigger test of {@link InHouseOrderListener}, the {@code jms:inbound-endpoint queue="inhouseOrder"} with
 * {@code xa-transaction action="ALWAYS_BEGIN"} of the {@code inhouseOrder} flow ({@code fulfillment.xml:98-129}). The
 * original has no test of this flow, so this class stands in for its endpoint test: the accepted path, the
 * {@code rollback-exception-strategy maxRedeliveryAttempts="3"} with its redeliveries, the rollback of an insert that
 * already ran, the {@code on-redelivery-attempts-exceeded} branch, a body that is not an order item or cannot be read,
 * each logged with its control characters escaped, and the one broker connection the idle listener keeps open.
 *
 * <p>The whole application runs, so every request travels the production path: the embedded Artemis broker, the
 * JTA-transacted {@code inHouseListenerContainerFactory} (Narayana over the XA Artemis session and the XA Derby
 * {@code DataSource}), the transactional {@link InHouseOrderService} and the reply on the request's
 * {@code JMSReplyTo}. Requests are sent the way {@code OrderOrchestrationService.orderInHouse} sends them: an
 * {@link ObjectMessage} of the {@link OrderItem} with the String property {@value InHouseOrderService#TOTAL_VALUE_PROPERTY}
 * {@code "0"}, the order's initial {@code totalValue} ({@code fulfillment.xml:31}), and a temporary reply queue, on
 * a non-transacted session of the {@code nonXaJmsConnectionFactory}.
 *
 * <p>{@link PriceCacheService} is mocked to answer {@code "159"}, the constant of the original Jersey
 * {@code ProductPrice} resource. The real price GET targets the POST-only listener of port 9999
 * ({@code fulfillment.xml:145}), a Tier 2A verification point, so the accepted path is reachable only with the price
 * lookup replaced. The broker returns a rolled-back request after 1000 ms ({@code config/XaTransactionConfig}), so a
 * request that keeps failing is consumed on its fifth delivery, about four seconds after it was sent.
 *
 * <p>The five original ports are replaced by free ports, drawn for each load attempt of the context: a drawn port is
 * free when drawn, not when bound, so when another process takes one in between,
 * {@link PortInUseRetryingContextLoader} loads the context again on newly drawn ports. A fresh in-memory Derby
 * database and a non-persistent embedded broker with a server id of its own give the class its own state, so no other
 * test class's context can receive or redeliver these requests.
 */
@SpringBootTest(classes = ServiceOrchestrationAndChoiceRoutingApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ContextConfiguration(loader = PortInUseRetryingContextLoader.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InHouseOrderListenerTest {

    /** Above the small server IDs Spring Boot's per-JVM counter hands out to embedded brokers. */
    private static final int MIN_ARTEMIS_SERVER_ID = 1_000_000;

    /** Five deliveries 1000 ms apart fit several times over, which leaves room for a loaded build host. */
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(30);

    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    /**
     * How long the queue must stay empty before a test ends: longer than the broker's 1000 ms redelivery delay, so a
     * request between a rollback and its redelivery is never mistaken for a consumed one.
     */
    private static final Duration DRAINED_QUIET_PERIOD = Duration.ofMillis(1_500);

    /** Wait for a reply that is expected: covers deliveries spread over several redelivery delays. */
    private static final long EXPECTED_REPLY_TIMEOUT_MS = 15_000;

    /** {@code ProductPrice.getProductPrice} of the original answers this constant for every product. */
    private static final String ORIGINAL_PRICE = "159";

    /** {@code set-session-variable value="0" variableName="totalValue"} (fulfillment.xml:31): a string. */
    private static final String INITIAL_TOTAL_VALUE = "0";

    /** {@code JMSXDeliveryCount} of the delivery on which redelivery is exhausted: 4 redeliveries exceed 3. */
    private static final int EXHAUSTED_DELIVERY = 5;

    /** Producer text that would start a forged log entry if it were logged unescaped. */
    private static final String FORGED_LINE = "bad\r\nINFO forged-order-audit";

    /** {@link #FORGED_LINE} as it is logged: CR and LF each written as a six-character escape. */
    private static final String ESCAPED_FORGED_LINE = "bad\\u000d\\u000aINFO forged-order-audit";

    /** A String property a producer may add to a request; the flow never reads it. */
    private static final String NOTE_PROPERTY = "note";

    /** Idle time spanning three receives of the listener container, whose receive timeout is Spring's 1000 ms. */
    private static final Duration IDLE_RECEIVES = Duration.ofMillis(3_500);

    @MockBean
    private PriceCacheService priceCacheService;

    /** The XA-wrapped Artemis factory of {@code inHouseListenerContainerFactory}; Narayana recovery never uses it. */
    @SpyBean(name = "jmsConnectionFactory")
    private ConnectionFactory xaConnectionFactory;

    @SpyBean
    private OrderOrchestrationService orderOrchestrationService;

    @SpyBean
    private InHouseOrderService inHouseOrderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier("nonXaJmsConnectionFactory")
    private ConnectionFactory nonXaConnectionFactory;

    @Autowired
    private EmbeddedActiveMQ embeddedActiveMQ;

    @Value("${orders.inhouse.queue}")
    private String inHouseQueue;

    /** {@code JMSXDeliveryCount} of every delivery that reached {@link InHouseOrderService#receive}, in order. */
    private final List<Integer> deliveryCounts = new CopyOnWriteArrayList<>();

    /** Non-transacted requester over the non-XA factory, as the orchestration's request-reply template is. */
    private JmsTemplate nonXaJmsTemplate;

    /**
     * Binds every listener to a free port drawn for this load attempt and gives the class its own Derby database and
     * Artemis broker.
     *
     * <p>{@code application.yml} sets each additional listener's port through a placeholder ({@code ajax.port},
     * {@code ports.populate}, {@code ports.samsung}, {@code ports.prices}), so only those keys are overridden:
     * Spring Boot binds the {@code listeners.additional} list from one property source, and overriding an entry's
     * port directly would drop its name and routes. {@code orders.samsung.url} and {@code orders.price.url} follow
     * {@code ports.samsung} and {@code ports.prices}; {@code orders.soap.url} names port 1080 literally.
     */
    @DynamicPropertySource
    static void isolatedListenersDatabaseAndBroker(DynamicPropertyRegistry registry) throws IOException {
        int[] ports = freePorts(5);
        registry.add("server.port", () -> ports[0]);
        registry.add("ajax.port", () -> ports[1]);
        registry.add("ports.populate", () -> ports[2]);
        registry.add("ports.samsung", () -> ports[3]);
        registry.add("ports.prices", () -> ports[4]);
        registry.add("orders.soap.url", () -> "http://localhost:" + ports[0] + "/orders");

        // createDatabase=create stays as application.yml sets it, so this name is a new in-memory database.
        String databaseName = "memory:inHouseOrderListenerTest_" + UUID.randomUUID().toString().replace('-', '_');
        registry.add("spring.datasource.xa.properties.databaseName", () -> databaseName);

        int artemisServerId = ThreadLocalRandom.current().nextInt(MIN_ARTEMIS_SERVER_ID, Integer.MAX_VALUE);
        registry.add("spring.artemis.embedded.server-id", () -> artemisServerId);
    }

    @BeforeEach
    void stubPriceRecordDeliveriesAndCreateTables() {
        deliveryCounts.clear();
        when(priceCacheService.price(anyString())).thenReturn(ORIGINAL_PRICE);
        doAnswer(recordingDelivery()).when(inHouseServiceSpy()).receive(any(), any(), anyInt());
        assertThat(orderOrchestrationService.populateDatabase()).isIn("db populated", "table already populated");
        nonXaJmsTemplate = new JmsTemplate(nonXaConnectionFactory);
    }

    /**
     * Lets a rolled-back request run out its deliveries before the next test. JUnit runs this method before Spring
     * resets the mocks, so the failing stubs still apply while the remaining redeliveries are consumed.
     */
    @AfterEach
    void drainInHouseQueue() {
        await().atMost(DELIVERY_TIMEOUT).pollInterval(POLL_INTERVAL).during(DRAINED_QUIET_PERIOD)
                .until(() -> queueDepth() == 0);
    }

    @Test
    void acceptedOrderRepliesWithReceiptTotalValueAndCorrelation() {
        String correlationId = correlationId();

        Reply reply = sendAndReceive(item("AX02", "p-1", "Philips", 2), correlationId, () -> {
        }, EXPECTED_REPLY_TIMEOUT_MS);

        assertThat(reply).as("reply to an accepted in-house item").isNotNull();
        assertThat(reply.receipt().getStatus()).isEqualTo(Status.ACCEPTED);
        assertThat(reply.receipt().getTotalPrice()).isEqualTo(318.0f);
        // The flow's Groovy transformer sets only status and totalPrice (fulfillment.xml:116-122).
        assertThat(reply.receipt().getId()).isNull();
        // MEL #[totalValue + totalPrice] on the String session variable concatenates (fulfillment.xml:115).
        assertThat(reply.totalValue()).isEqualTo("0318");
        assertThat(reply.correlationId()).isEqualTo(correlationId);
        assertThat(deliveryCounts).containsExactly(1);
        verify(priceCacheService).price("AX02");
        verify(orderOrchestrationService, never()).logError(any());

        // The reply and the row commit in one two-phase transaction; the Derby branch may become visible just after.
        await().atMost(DELIVERY_TIMEOUT).pollInterval(POLL_INTERVAL).until(() -> ordersRows("AX02") == 1);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select product_id, name, manufacturer, quantity, price from orders where product_id = ?", "AX02");
        assertThat(row.get("product_id")).isEqualTo("AX02");
        assertThat(row.get("name")).isEqualTo("p-1");
        assertThat(row.get("manufacturer")).isEqualTo("Philips");
        assertThat(row.get("quantity")).isEqualTo(2);
        assertThat(row.get("price")).isEqualTo(159);
    }

    @Test
    void failingDeliveryIsRolledBackAndRedelivered() {
        // The delivery during which each failing price lookup ran: deliveries are serial (one consumer), and each one
        // records its count before the service asks for the price.
        List<Integer> pricedDeliveries = new CopyOnWriteArrayList<>();
        when(priceCacheService.price("FAIL-ROLLBACK")).thenAnswer(invocation -> {
            pricedDeliveries.add(currentDelivery());
            throw new IllegalStateException("price lookup failed");
        });

        Reply reply = sendAndReceive(item("FAIL-ROLLBACK", "f-1", "Sony", 1), correlationId(),
                () -> await().atMost(DELIVERY_TIMEOUT).pollInterval(POLL_INTERVAL)
                        .until(() -> pricedDeliveries.size() >= 3),
                1_000);

        assertThat(reply).as("reply to a rolled-back delivery").isNull();
        List<Integer> deliveries = List.copyOf(deliveryCounts);
        assertThat(deliveries).startsWith(1, 2, 3);
        assertThat(deliveries).as("JMSXDeliveryCount rises by one per redelivery")
                .isEqualTo(oneTo(deliveries.size()));
        List<Integer> priced = List.copyOf(pricedDeliveries);
        assertThat(priced).as("one price lookup in each failed delivery").isEqualTo(oneTo(priced.size()));
        assertThat(ordersRows("FAIL-ROLLBACK")).isZero();
    }

    @Test
    void insertOfAFailedDeliveryIsRolledBackAndTheRedeliveryIsAccepted() {
        // Deliveries 1 and 2 fail after the service has inserted the row and built the reply, as a failure in the
        // flow's last processor would; delivery 3 succeeds. Only an insert enlisted in the listener's JTA transaction
        // leaves a single row.
        doAnswer(invocation -> {
            int deliveryCount = invocation.getArgument(2);
            deliveryCounts.add(deliveryCount);
            Object result = invocation.callRealMethod();
            if (deliveryCount < 3) {
                throw new IllegalStateException("failure after the insert of delivery " + deliveryCount);
            }
            return result;
        }).when(inHouseServiceSpy()).receive(argThat(hasProductId("RETRY-INSERT")), any(), anyInt());

        Reply reply = sendAndReceive(item("RETRY-INSERT", "r-1", "Sony", 3), correlationId(), () -> {
        }, EXPECTED_REPLY_TIMEOUT_MS);

        assertThat(reply).as("reply to the third delivery").isNotNull();
        assertThat(reply.receipt().getStatus()).isEqualTo(Status.ACCEPTED);
        assertThat(reply.receipt().getTotalPrice()).isEqualTo(477.0f);
        assertThat(reply.totalValue()).isEqualTo("0477");
        assertThat(deliveryCounts).containsExactly(1, 2, 3);
        verify(priceCacheService, times(3)).price("RETRY-INSERT");
        verify(orderOrchestrationService, never()).logError(any());
        await().atMost(DELIVERY_TIMEOUT).pollInterval(POLL_INTERVAL)
                .untilAsserted(() -> assertThat(ordersRows("RETRY-INSERT")).isEqualTo(1));
    }

    @Test
    void exhaustedRedeliveryLogsErrorConsumesAndSendsNoReply() {
        when(priceCacheService.price("FAIL-EXHAUST")).thenThrow(new IllegalStateException("price lookup failed"));

        Reply reply = sendAndReceive(item("FAIL-EXHAUST", "f-2", "Sony", 1), correlationId(), () -> {
            await().atMost(DELIVERY_TIMEOUT).pollInterval(POLL_INTERVAL).untilAsserted(
                    () -> verify(orderOrchestrationService).logError(argThat(isOrderItem("FAIL-EXHAUST"))));
            await().atMost(DELIVERY_TIMEOUT).pollInterval(POLL_INTERVAL).until(() -> queueDepth() == 0);
        }, 1_500);

        assertThat(reply).as("reply after redelivery is exhausted").isNull();
        assertThat(deliveryCounts).isEqualTo(oneTo(EXHAUSTED_DELIVERY));
        // The flow body ran on deliveries 1-4 only; the fifth went straight to on-redelivery-attempts-exceeded.
        verify(priceCacheService, times(EXHAUSTED_DELIVERY - 1)).price("FAIL-EXHAUST");
        verify(orderOrchestrationService, times(1)).logError(argThat(isOrderItem("FAIL-EXHAUST")));
        assertThat(ordersRows("FAIL-EXHAUST")).isZero();
    }

    /**
     * A text body fails the flow at {@code payload.productId}: deliveries 1-4 log the body at INFO and roll back, and
     * the fifth runs {@code defaultErrorHandler} and is consumed without a reply. Each control character of the logged
     * body is written as an escape, so a producer cannot forge log lines through it.
     */
    @ParameterizedTest
    @MethodSource("nonOrderItemBodies")
    void nonOrderItemBodyIsLoggedAfterRedeliveryExhaustion(String body, String expectedLogged) {
        int rowsBefore = ordersRows();

        try (LogCapture listenerLog = new LogCapture(InHouseOrderListener.class);
                LogCapture errorHandlerLog = new LogCapture(OrderOrchestrationService.class)) {
            Reply reply = sendAndReceive(body, correlationId(), () -> {
                await().atMost(DELIVERY_TIMEOUT).pollInterval(POLL_INTERVAL)
                        .untilAsserted(() -> verify(orderOrchestrationService).logError(any()));
                await().atMost(DELIVERY_TIMEOUT).pollInterval(POLL_INTERVAL).until(() -> queueDepth() == 0);
            }, 1_500);

            assertThat(reply).as("reply to a body that is not an order item").isNull();
            verify(orderOrchestrationService, times(1)).logError(body);
            assertThat(redeliveryChecks()).as("JMSXDeliveryCount of each delivery")
                    .isEqualTo(oneTo(EXHAUSTED_DELIVERY));
            assertThat(listenerLog.events()).as("rollback log of deliveries 1-4")
                    .extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                    .containsExactlyElementsOf(Collections.nCopies(EXHAUSTED_DELIVERY - 1,
                            tuple(Level.INFO, expectedLogged)));
            assertThat(errorHandlerLog.events()).as("defaultErrorHandler log of the exhausted delivery")
                    .extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                    .containsExactly(tuple(Level.INFO, "Error occurred: " + expectedLogged));
        }
        assertThat(deliveryCounts).as("deliveries that reached the flow body").isEmpty();
        verify(priceCacheService, never()).price(anyString());
        assertThat(ordersRows()).isEqualTo(rowsBefore);
    }

    /**
     * A body that cannot be deserialized fails before the flow starts: deliveries 1-4 log the message, which stands in
     * for the payload, and roll back, and the fifth hands the message to {@code defaultErrorHandler} and is consumed
     * without a reply. The message's text carries the producer's properties, so CR and LF in a property are written
     * as escapes in both log lines.
     */
    @Test
    void unreadableBodyIsLoggedEscapedAndHandedToDefaultErrorHandlerOnceExhausted() {
        int rowsBefore = ordersRows();

        try (LogCapture listenerLog = new LogCapture(InHouseOrderListener.class);
                LogCapture errorHandlerLog = new LogCapture(OrderOrchestrationService.class)) {
            Reply reply = sendAndReceive(new UnreadableBody(), Map.of(NOTE_PROPERTY, FORGED_LINE), correlationId(),
                    () -> {
                        await().atMost(DELIVERY_TIMEOUT).pollInterval(POLL_INTERVAL).untilAsserted(
                                () -> verify(orderOrchestrationService).logError(any(Message.class)));
                        await().atMost(DELIVERY_TIMEOUT).pollInterval(POLL_INTERVAL).until(() -> queueDepth() == 0);
                    }, 1_500);

            assertThat(reply).as("reply to an unreadable body").isNull();
            verify(orderOrchestrationService, times(1)).logError(any(Message.class));
            assertThat(redeliveryChecks()).as("JMSXDeliveryCount of each delivery")
                    .isEqualTo(oneTo(EXHAUSTED_DELIVERY));
            assertThat(listenerLog.events()).as("rollback log of deliveries 1-4").hasSize(EXHAUSTED_DELIVERY - 1)
                    .allSatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.INFO);
                        assertOneLineWithEscapedForgedLine(event.getFormattedMessage());
                    });
            assertThat(errorHandlerLog.events()).as("defaultErrorHandler log of the exhausted delivery")
                    .singleElement().satisfies(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.INFO);
                        assertThat(event.getFormattedMessage()).startsWith("Error occurred: ");
                        assertOneLineWithEscapedForgedLine(event.getFormattedMessage());
                    });
        }
        assertThat(deliveryCounts).as("deliveries that reached the flow body").isEmpty();
        verify(priceCacheService, never()).price(anyString());
        assertThat(ordersRows()).isEqualTo(rowsBefore);
    }

    @Test
    void idleListenerReceivesOnTheOneConnectionItKeepsOpen() {
        long connectionsBefore = connectionsCreated();

        await().pollDelay(IDLE_RECEIVES).until(() -> true);

        assertThat(connectionsCreated()).as("connections the idle listener opened over three receives")
                .isEqualTo(connectionsBefore);
    }

    static Stream<Arguments> nonOrderItemBodies() {
        return Stream.of(
                Arguments.of(Named.of("plain text", "not an order item"), "not an order item"),
                Arguments.of(Named.of("CR LF before a forged entry", FORGED_LINE), ESCAPED_FORGED_LINE));
    }

    /**
     * The {@code JMSXDeliveryCount} of every delivery the listener checked against the redelivery limit, in order:
     * one check per delivery of a body that never reaches the flow body.
     */
    private List<Integer> redeliveryChecks() {
        ArgumentCaptor<Integer> checked = ArgumentCaptor.forClass(Integer.class);
        verify(inHouseServiceSpy(), atLeastOnce()).isRedeliveryExhausted(checked.capture());
        return checked.getAllValues();
    }

    /** {@code logged} is one line that carries {@link #FORGED_LINE} only in its escaped form. */
    private static void assertOneLineWithEscapedForgedLine(String logged) {
        assertThat(logged).contains(ESCAPED_FORGED_LINE)
                .doesNotContain(FORGED_LINE)
                .doesNotContain("\r", "\n", "\u0085", "\u2028", "\u2029");
    }

    /** How many connections {@link #xaConnectionFactory} has created since the spy was last reset. */
    private long connectionsCreated() {
        return mockingDetails(xaConnectionFactory).getInvocations().stream()
                .filter(invocation -> "createConnection".equals(invocation.getMethod().getName()))
                .count();
    }

    /** Records the delivery's {@code JMSXDeliveryCount}, then runs the real {@link InHouseOrderService#receive}. */
    private Answer<Object> recordingDelivery() {
        return invocation -> {
            deliveryCounts.add(invocation.getArgument(2));
            return invocation.callRealMethod();
        };
    }

    /**
     * The Mockito spy behind the transactional proxy of {@link InHouseOrderService}. The service is
     * {@code @Transactional(propagation = MANDATORY)}, and a stubbing call made on the proxy would pass its transaction
     * interceptor outside any transaction and fail; stubbing the spy keeps the proxy, and the listener's transaction,
     * in front of every delivery's call.
     */
    private InHouseOrderService inHouseServiceSpy() {
        return AopTestUtils.getUltimateTargetObject(inHouseOrderService);
    }

    /** The {@code JMSXDeliveryCount} of the delivery being processed on the single listener thread. */
    private int currentDelivery() {
        return deliveryCounts.get(deliveryCounts.size() - 1);
    }

    /**
     * Sends one request to the in-house queue and waits for its reply on a temporary queue, as the orchestration's
     * request-response dispatch does.
     *
     * @param body             an {@link OrderItem}, sent as an {@link ObjectMessage}, or a {@link String}, sent as a
     *                         text message
     * @param correlationId    the request's {@code JMSCorrelationID}
     * @param beforeReceive    waits that must run while the temporary reply queue still exists
     * @param receiveTimeoutMs how long to wait for the reply once {@code beforeReceive} has returned
     * @return the reply, or {@code null} when none arrived in time
     */
    private Reply sendAndReceive(Object body, String correlationId, Runnable beforeReceive, long receiveTimeoutMs) {
        return sendAndReceive(body, Map.of(), correlationId, beforeReceive, receiveTimeoutMs);
    }

    /**
     * {@link #sendAndReceive(Object, String, Runnable, long)} with further String properties on the request.
     *
     * @param body             an {@link OrderItem} or another {@link Serializable}, sent as an {@link ObjectMessage},
     *                         or a {@link String}, sent as a text message
     * @param properties       String properties set on the request besides
     *                         {@value InHouseOrderService#TOTAL_VALUE_PROPERTY}
     * @param correlationId    the request's {@code JMSCorrelationID}
     * @param beforeReceive    waits that must run while the temporary reply queue still exists
     * @param receiveTimeoutMs how long to wait for the reply once {@code beforeReceive} has returned
     * @return the reply, or {@code null} when none arrived in time
     */
    private Reply sendAndReceive(Object body, Map<String, String> properties, String correlationId,
            Runnable beforeReceive, long receiveTimeoutMs) {
        return nonXaJmsTemplate.execute((SessionCallback<Reply>) session -> {
            TemporaryQueue replyQueue = session.createTemporaryQueue();
            MessageConsumer consumer = session.createConsumer(replyQueue);
            MessageProducer producer = session.createProducer(session.createQueue(inHouseQueue));
            try {
                Message request;
                if (body instanceof OrderItem item) {
                    request = session.createObjectMessage(item);
                } else if (body instanceof String text) {
                    request = session.createTextMessage(text);
                } else if (body instanceof Serializable object) {
                    request = session.createObjectMessage(object);
                } else {
                    throw new IllegalArgumentException("Unsupported request body " + body);
                }
                request.setStringProperty(InHouseOrderService.TOTAL_VALUE_PROPERTY, INITIAL_TOTAL_VALUE);
                for (Map.Entry<String, String> property : properties.entrySet()) {
                    request.setStringProperty(property.getKey(), property.getValue());
                }
                request.setJMSReplyTo(replyQueue);
                request.setJMSCorrelationID(correlationId);
                producer.send(request);

                beforeReceive.run();

                Message reply = consumer.receive(receiveTimeoutMs);
                if (reply == null) {
                    return null;
                }
                assertThat(reply).isInstanceOf(ObjectMessage.class);
                Object receipt = ((ObjectMessage) reply).getObject();
                assertThat(receipt).isInstanceOf(PurchaseReceipt.class);
                return new Reply((PurchaseReceipt) receipt,
                        reply.getStringProperty(InHouseOrderService.TOTAL_VALUE_PROPERTY), reply.getJMSCorrelationID());
            } finally {
                producer.close();
                consumer.close();
            }
        }, true);
    }

    /**
     * Messages on the in-house queue, including those scheduled for redelivery and those being delivered.
     */
    private long queueDepth() {
        Queue queue = embeddedActiveMQ.getActiveMQServer().locateQueue(SimpleString.toSimpleString(inHouseQueue));
        assertThat(queue).as("embedded queue %s", inHouseQueue).isNotNull();
        return queue.getMessageCount();
    }

    private int ordersRows(String productId) {
        return jdbcTemplate.queryForObject("select count(*) from orders where product_id = ?", Integer.class,
                productId);
    }

    private int ordersRows() {
        return jdbcTemplate.queryForObject("select count(*) from orders", Integer.class);
    }

    private static OrderItem item(String productId, String name, String manufacturer, int quantity) {
        OrderItem item = new OrderItem();
        item.setProductId(productId);
        item.setName(name);
        item.setManufacturer(manufacturer);
        item.setQuantity(quantity);
        return item;
    }

    private static ArgumentMatcher<OrderItem> hasProductId(String productId) {
        return item -> item != null && productId.equals(item.getProductId());
    }

    private static ArgumentMatcher<Object> isOrderItem(String productId) {
        return payload -> payload instanceof OrderItem item && productId.equals(item.getProductId());
    }

    private static String correlationId() {
        return "in-house-" + UUID.randomUUID();
    }

    /** {@code [1, 2, ..., count]}. */
    private static List<Integer> oneTo(int count) {
        return IntStream.rangeClosed(1, count).boxed().toList();
    }

    /** Distinct free local ports: every socket stays open until all are chosen, and all are closed before use. */
    private static int[] freePorts(int count) throws IOException {
        ServerSocket[] sockets = new ServerSocket[count];
        try {
            int[] ports = new int[count];
            for (int i = 0; i < count; i++) {
                sockets[i] = new ServerSocket(0);
                sockets[i].setReuseAddress(true);
                ports[i] = sockets[i].getLocalPort();
            }
            return ports;
        } finally {
            for (ServerSocket socket : sockets) {
                if (socket != null) {
                    socket.close();
                }
            }
        }
    }

    /** The parts of an in-house reply the requester reads. */
    private record Reply(PurchaseReceipt receipt, String totalValue, String correlationId) {
    }

    /**
     * A body that is written but cannot be read back: the listener's {@code getObject()} fails with a
     * {@link jakarta.jms.JMSException}, as it does for a class the application cannot deserialize.
     */
    private static final class UnreadableBody implements Serializable {

        private static final long serialVersionUID = 1L;

        private void readObject(ObjectInputStream in) throws IOException {
            throw new InvalidObjectException("unreadable in-house request body");
        }
    }

    /** Captures one logger's events at INFO and restores its level and appenders on {@link #close()}. */
    private static final class LogCapture implements AutoCloseable {

        private final Logger logger;

        private final Level previousLevel;

        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        LogCapture(Class<?> loggerClass) {
            logger = (Logger) LoggerFactory.getLogger(loggerClass);
            previousLevel = logger.getLevel();
            logger.setLevel(Level.INFO);
            appender.start();
            logger.addAppender(appender);
        }

        /** The events so far, copied under the lock the listener thread appends under. */
        List<ILoggingEvent> events() {
            synchronized (appender) {
                return List.copyOf(appender.list);
            }
        }

        @Override
        public void close() {
            try {
                logger.detachAppender(appender);
                appender.stop();
            } finally {
                logger.setLevel(previousLevel);
            }
        }
    }
}
