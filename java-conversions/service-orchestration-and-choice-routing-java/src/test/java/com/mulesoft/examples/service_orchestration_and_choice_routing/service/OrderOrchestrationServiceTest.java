/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.ObjectMessage;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.UncategorizedJmsException;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessageCreator;

import com.mulesoft.examples.service_orchestration_and_choice_routing.config.XaTransactionConfig;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.Order;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.OrderItem;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.OrderPerson;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.PurchaseReceipt;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.Status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Tests of {@link OrderOrchestrationService}, the {@code orderService} flow of {@code fulfillment.xml} (lines 28-48),
 * its {@code defaultErrorHandler} sub-flow (lines 156-158) and the {@code databaseInitialisation} flow (lines
 * 159-172).
 *
 * <p>The service is built directly over Mockito mocks: the {@code samsungOrder} flow, the request-reply template of
 * the {@code inhouseOrder} dispatch with a mocked JMS session and messages, the audit flow and the DDL template. The
 * session variable {@code totalValue} is a string that every leg extends by concatenation, as MEL's {@code +} did, so
 * the value the audit receives records each leg in order. The service's logger is captured with a Logback
 * {@link ListAppender} at INFO, detached and restored after each test.
 *
 * <p>The receive timeout of the request-reply template bounds the service's wait for the in-house reply, so the
 * template's bean method {@link XaTransactionConfig#inHouseRequestJmsTemplate} is also called here directly, over a
 * mocked connection factory and without a Spring context.
 */
@ExtendWith(MockitoExtension.class)
class OrderOrchestrationServiceTest {

    private static final String QUEUE = "inhouseOrder";

    /** {@code Create orders Table} (fulfillment.xml:162), verbatim. */
    private static final String ORDERS_DDL = "CREATE TABLE orders (i int generated always as identity, "
            + "product_id varchar(256), name varchar(256), manufacturer varchar(256), quantity integer, price integer)";

    /** {@code Create order_audits Table} (fulfillment.xml:166), verbatim. */
    private static final String ORDER_AUDITS_DDL = "CREATE TABLE order_audits (i int generated always as identity, "
            + "order_id varchar(256), total_value integer)";

    /** The Samsung price of the committed reply (reply.xml), which the Samsung leg appends to {@code totalValue}. */
    private static final String SAMSUNG_PRICE = "2550";

    /** Stands for caller data quoted in an exception message; no log line may carry it. */
    private static final String SENSITIVE_TEXT = "order 12 for John Doe, Main Street 123";

    @Mock
    private SamsungOrderService samsung;

    @Mock
    private AuditService audit;

    @Mock
    private JmsTemplate inHouseTemplate;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private Session session;

    @Mock
    private ObjectMessage request;

    @Mock
    private ObjectMessage reply;

    private OrderOrchestrationService service;

    private LogCapture log;

    @BeforeEach
    void setUp() {
        service = new OrderOrchestrationService(samsung, inHouseTemplate, audit, jdbcTemplate, QUEUE);
        log = new LogCapture(OrderOrchestrationService.class);
    }

    @AfterEach
    void releaseLog() {
        log.close();
    }

    @Test
    void samsungItemGetsReceiptAndAuditSeesConcatenatedTotal() {
        OrderItem item = samsungItem();
        Order order = order(item);
        PurchaseReceipt accepted = receipt(Status.ACCEPTED, 2550);
        when(samsung.order(same(item), any())).thenAnswer(appendingTotal(SAMSUNG_PRICE, accepted));

        Order result = service.processOrder(order);

        assertThat(result).isSameAs(order);
        assertThat(item.getPurchaseReceipt()).isSameAs(accepted);
        verify(audit).audit(order, "02550");
        verifyNoInteractions(inHouseTemplate);
        assertThat(log.events()).isEmpty();
    }

    /** MEL's {@code payload.manufacturer == 'Samsung'} is exact and case-sensitive; everything else goes in-house. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"samsung", "SAMSUNG", "Samsung ", " Samsung", "Philips"})
    void manufacturerMatchIsExact(String manufacturer) {
        OrderItem item = samsungItem();
        item.setManufacturer(manufacturer);
        Order order = order(item);
        when(inHouseTemplate.sendAndReceive(eq(QUEUE), any(MessageCreator.class))).thenReturn(null);

        service.processOrder(order);

        verify(inHouseTemplate).sendAndReceive(eq(QUEUE), any(MessageCreator.class));
        verifyNoInteractions(samsung);
        assertThat(item.getPurchaseReceipt()).isNull();
        verify(audit).audit(order, "0");
    }

    @Test
    void inHouseItemSendsObjectMessageWithTotalValue() throws JMSException {
        OrderItem item = philipsItem();
        when(inHouseTemplate.sendAndReceive(eq(QUEUE), any(MessageCreator.class))).thenReturn(null);
        service.processOrder(order(item));

        ArgumentCaptor<MessageCreator> creator = ArgumentCaptor.forClass(MessageCreator.class);
        verify(inHouseTemplate).sendAndReceive(eq(QUEUE), creator.capture());
        when(session.createObjectMessage(item)).thenReturn(request);

        Message sent = creator.getValue().createMessage(session);

        assertThat(sent).isSameAs(request);
        verify(session).createObjectMessage(item);
        verify(request).setStringProperty(InHouseOrderService.TOTAL_VALUE_PROPERTY, "0");
        verifyNoMoreInteractions(request);
    }

    @Test
    void inHouseReplyUpdatesReceiptAndTotalValue() throws JMSException {
        OrderItem item = philipsItem();
        Order order = order(item);
        PurchaseReceipt accepted = receipt(Status.ACCEPTED, 318);
        stubReply(accepted, "0318");

        service.processOrder(order);

        assertThat(item.getPurchaseReceipt()).isSameAs(accepted);
        verify(audit).audit(order, "0318");
        verifyNoInteractions(samsung);
    }

    @Test
    void inHouseTimeoutLeavesItemAndTotalUnchanged() {
        OrderItem item = philipsItem();
        PurchaseReceipt previous = receipt(Status.REJECTED, 0);
        item.setPurchaseReceipt(previous);
        Order order = order(item);
        when(inHouseTemplate.sendAndReceive(eq(QUEUE), any(MessageCreator.class))).thenReturn(null);

        Order result = service.processOrder(order);

        assertThat(result).isSameAs(order);
        assertThat(item.getPurchaseReceipt()).isSameAs(previous);
        verify(audit).audit(order, "0");
        assertThat(log.events()).isEmpty();
    }

    /** Mule's {@code foreach} over a missing or empty collection splits nothing, so only the audit runs. */
    @ParameterizedTest
    @NullAndEmptySource
    void orderWithoutItemsIsOnlyAudited(List<OrderItem> items) {
        Order order = order();
        order.setOrderItems(items);

        assertThat(service.processOrder(order)).isSameAs(order);

        verify(audit).audit(order, "0");
        verifyNoInteractions(samsung, inHouseTemplate);
    }

    @Test
    void mixedItemsProcessedInOrder() throws JMSException {
        OrderItem first = samsungItem();
        OrderItem philips = philipsItem();
        OrderItem third = samsungItem();
        Order order = order(first, philips, third);
        PurchaseReceipt firstReceipt = receipt(Status.ACCEPTED, 2550);
        PurchaseReceipt inHouseReceipt = receipt(Status.ACCEPTED, 318);
        PurchaseReceipt thirdReceipt = receipt(Status.ACCEPTED, 2550);
        when(samsung.order(any(OrderItem.class), any()))
                .thenAnswer(appendingTotal(SAMSUNG_PRICE, firstReceipt))
                .thenAnswer(appendingTotal(SAMSUNG_PRICE, thirdReceipt));
        // The request is built while the template sends, so it carries the totalValue of that moment.
        when(inHouseTemplate.sendAndReceive(eq(QUEUE), any(MessageCreator.class))).thenAnswer(invocation -> {
            invocation.<MessageCreator>getArgument(1).createMessage(session);
            return reply;
        });
        when(session.createObjectMessage(philips)).thenReturn(request);
        when(reply.getObject()).thenReturn(inHouseReceipt);
        when(reply.getStringProperty(InHouseOrderService.TOTAL_VALUE_PROPERTY)).thenReturn("02550318");

        service.processOrder(order);

        InOrder inOrder = inOrder(samsung, inHouseTemplate, audit);
        inOrder.verify(samsung).order(same(first), any());
        inOrder.verify(inHouseTemplate).sendAndReceive(eq(QUEUE), any(MessageCreator.class));
        inOrder.verify(samsung).order(same(third), any());
        inOrder.verify(audit).audit(order, "025503182550");
        inOrder.verifyNoMoreInteractions();
        verify(request).setStringProperty(InHouseOrderService.TOTAL_VALUE_PROPERTY, "02550");
        assertThat(order.getOrderItems()).extracting(OrderItem::getPurchaseReceipt)
                .containsExactly(firstReceipt, inHouseReceipt, thirdReceipt);
    }

    @Test
    void configuredQueueReceivesTheInHouseRequest() {
        OrderOrchestrationService configured =
                new OrderOrchestrationService(samsung, inHouseTemplate, audit, jdbcTemplate, "inhouseOrder.dev");
        when(inHouseTemplate.sendAndReceive(eq("inhouseOrder.dev"), any(MessageCreator.class))).thenReturn(null);

        configured.processOrder(order(philipsItem()));

        verify(inHouseTemplate).sendAndReceive(eq("inhouseOrder.dev"), any(MessageCreator.class));
    }

    /**
     * The {@code catch-exception-strategy} runs {@code defaultErrorHandler} and the platform default then answers, so
     * the failure is logged by type only, the order is logged at INFO, and the same exception propagates.
     */
    @ParameterizedTest
    @EnumSource(FailurePoint.class)
    void collaboratorFailureLogsAndRethrows(FailurePoint point) {
        RuntimeException failure = new IllegalStateException("boom");
        Order order = arrangeFailure(point, failure);

        assertThatThrownBy(() -> service.processOrder(order)).isSameAs(failure);

        assertThat(log.events()).extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                .containsExactly(
                        tuple(Level.ERROR, "Order processing failed: java.lang.IllegalStateException"),
                        tuple(Level.INFO, "Error occurred: " + order));
        verify(audit, times(point == FailurePoint.AUDIT ? 1 : 0)).audit(any(), any());
    }

    /**
     * A failing item ends the {@code foreach} where it fails: the item completed before it keeps its receipt, no later
     * item is ordered, the same exception propagates and no audit is dispatched.
     */
    @Test
    void laterItemFailureKeepsEarlierReceiptAndSkipsAudit() {
        OrderItem first = samsungItem();
        OrderItem failing = philipsItem();
        OrderItem after = samsungItem();
        Order order = order(first, failing, after);
        PurchaseReceipt firstReceipt = receipt(Status.ACCEPTED, 2550);
        RuntimeException failure = new IllegalStateException("boom");
        when(samsung.order(same(first), any())).thenAnswer(appendingTotal(SAMSUNG_PRICE, firstReceipt));
        when(inHouseTemplate.sendAndReceive(eq(QUEUE), any(MessageCreator.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.processOrder(order)).isSameAs(failure);

        InOrder inOrder = inOrder(samsung, inHouseTemplate, audit);
        inOrder.verify(samsung).order(same(first), any());
        inOrder.verify(inHouseTemplate).sendAndReceive(eq(QUEUE), any(MessageCreator.class));
        inOrder.verifyNoMoreInteractions();
        verifyNoInteractions(audit);
        assertThat(order.getOrderItems()).extracting(OrderItem::getPurchaseReceipt)
                .containsExactly(firstReceipt, null, null);
    }

    @ParameterizedTest
    @MethodSource("failureChains")
    void failureLogNamesOnlyTheExceptionTypes(RuntimeException failure, String expectedChain) {
        Order order = order(samsungItem());
        when(samsung.order(any(OrderItem.class), any())).thenThrow(failure);

        assertThatThrownBy(() -> service.processOrder(order)).isSameAs(failure);

        assertThat(log.events(Level.ERROR)).singleElement().extracting(ILoggingEvent::getFormattedMessage)
                .isEqualTo("Order processing failed: " + expectedChain);
        assertThat(log.events()).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(SENSITIVE_TEXT);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "payload-x")
    void logErrorWritesInfoLine(String payload) {
        service.logError(payload);

        assertThat(log.events()).extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                .containsExactly(tuple(Level.INFO, "Error occurred: " + payload));
    }

    @Test
    void populateDatabaseCreatesBothTables() {
        assertThat(service.populateDatabase()).isEqualTo("db populated");

        ArgumentCaptor<String> statements = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).execute(statements.capture());
        assertThat(statements.getAllValues()).containsExactly(ORDERS_DDL, ORDER_AUDITS_DDL);
        assertThat(log.events()).isEmpty();
    }

    /** Either statement failing, the second included after the first created its table, answers the catch payload. */
    @ParameterizedTest
    @MethodSource("failingDdl")
    void populateDatabaseReportsAlreadyPopulated(String failingStatement, List<String> expectedExecuted) {
        doAnswer(invocation -> {
            if (failingStatement.equals(invocation.getArgument(0))) {
                throw new BadSqlGrammarException("ddl", failingStatement, new SQLException("exists"));
            }
            return null;
        }).when(jdbcTemplate).execute(anyString());

        assertThat(service.populateDatabase()).isEqualTo("table already populated");

        ArgumentCaptor<String> statements = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).execute(statements.capture());
        assertThat(statements.getAllValues()).isEqualTo(expectedExecuted);
        assertThat(log.events()).extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                .containsExactly(tuple(Level.ERROR, "Database initialisation failed: "
                        + "org.springframework.jdbc.BadSqlGrammarException <- java.sql.SQLException"));
    }

    @ParameterizedTest
    @CsvSource(value = {"0318, 0318", "NULL, 0"}, nullValues = "NULL")
    void orderInHouseReturnsReplyReceipt(String replyTotalValue, String expectedTotalValue) throws JMSException {
        PurchaseReceipt accepted = receipt(Status.ACCEPTED, 318);
        stubReply(accepted, replyTotalValue);
        AtomicReference<String> totalValue = new AtomicReference<>("0");

        assertThat(service.orderInHouse(philipsItem(), totalValue)).isSameAs(accepted);

        assertThat(totalValue).hasValue(expectedTotalValue);
    }

    @Test
    void orderInHouseTimeoutReturnsNullAndKeepsTotal() {
        when(inHouseTemplate.sendAndReceive(eq(QUEUE), any(MessageCreator.class))).thenReturn(null);
        AtomicReference<String> totalValue = new AtomicReference<>("0");

        assertThat(service.orderInHouse(philipsItem(), totalValue)).isNull();

        assertThat(totalValue).hasValue("0");
    }

    @ParameterizedTest
    @MethodSource("unexpectedReplies")
    void orderInHouseRejectsUnexpectedReply(Message unexpected, String expectedMessage) {
        when(inHouseTemplate.sendAndReceive(eq(QUEUE), any(MessageCreator.class))).thenReturn(unexpected);
        AtomicReference<String> totalValue = new AtomicReference<>("0");

        assertThatThrownBy(() -> service.orderInHouse(philipsItem(), totalValue))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(expectedMessage);
        assertThat(totalValue).hasValue("0");
    }

    @Test
    void orderInHouseConvertsJmsFailureReadingReply() throws JMSException {
        JMSException unreadable = new JMSException("reply unreadable");
        when(inHouseTemplate.sendAndReceive(eq(QUEUE), any(MessageCreator.class))).thenReturn(reply);
        when(reply.getObject()).thenThrow(unreadable);

        assertThatThrownBy(() -> service.orderInHouse(philipsItem(), new AtomicReference<>("0")))
                .isInstanceOf(UncategorizedJmsException.class)
                .hasCauseReference(unreadable);
    }

    @ParameterizedTest
    @ValueSource(strings = {"samsungOrderService", "inHouseRequestJmsTemplate", "auditService", "jdbcTemplate"})
    void constructorRejectsMissingCollaborator(String missing) {
        assertThatThrownBy(() -> new OrderOrchestrationService(
                "samsungOrderService".equals(missing) ? null : samsung,
                "inHouseRequestJmsTemplate".equals(missing) ? null : inHouseTemplate,
                "auditService".equals(missing) ? null : audit,
                "jdbcTemplate".equals(missing) ? null : jdbcTemplate,
                QUEUE))
                .isInstanceOf(NullPointerException.class)
                .hasMessage(missing);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void constructorRejectsBlankQueue(String queue) {
        assertThatThrownBy(() -> new OrderOrchestrationService(samsung, inHouseTemplate, audit, jdbcTemplate, queue))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("orders.inhouse.queue must not be blank");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void inHouseRequestTemplateRejectsNonPositiveReplyTimeout(long replyTimeoutMs) {
        // JmsTemplate would wait for the in-house reply for ever on 0 and not at all on a negative value.
        assertThatThrownBy(() -> new XaTransactionConfig()
                .inHouseRequestJmsTemplate(mock(ConnectionFactory.class), replyTimeoutMs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("orders.inhouse.reply-timeout-ms must be between 1 and " + Long.MAX_VALUE + ", was "
                        + replyTimeoutMs);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 10000, Long.MAX_VALUE})
    void inHouseRequestTemplateWaitsReplyTimeoutOnNonTransactedSession(long replyTimeoutMs) {
        ConnectionFactory nonXaConnectionFactory = mock(ConnectionFactory.class);

        JmsTemplate template = new XaTransactionConfig()
                .inHouseRequestJmsTemplate(nonXaConnectionFactory, replyTimeoutMs);

        assertThat(template.getReceiveTimeout()).isEqualTo(replyTimeoutMs);
        assertThat(template.isSessionTransacted()).isFalse();
        assertThat(template.getConnectionFactory()).isSameAs(nonXaConnectionFactory);
        verifyNoInteractions(nonXaConnectionFactory);
    }

    /** The collaborator whose failure ends the order flow. */
    enum FailurePoint {
        SAMSUNG_ORDER, IN_HOUSE_SEND, AUDIT
    }

    private Order arrangeFailure(FailurePoint point, RuntimeException failure) {
        return switch (point) {
            case SAMSUNG_ORDER -> {
                when(samsung.order(any(OrderItem.class), any())).thenThrow(failure);
                yield order(samsungItem());
            }
            case IN_HOUSE_SEND -> {
                when(inHouseTemplate.sendAndReceive(eq(QUEUE), any(MessageCreator.class))).thenThrow(failure);
                yield order(philipsItem());
            }
            case AUDIT -> {
                Order order = order(samsungItem());
                when(samsung.order(any(OrderItem.class), any()))
                        .thenAnswer(appendingTotal(SAMSUNG_PRICE, receipt(Status.ACCEPTED, 2550)));
                doThrow(failure).when(audit).audit(order, "02550");
                yield order;
            }
        };
    }

    static Stream<Arguments> failureChains() {
        RuntimeException chained = new IllegalStateException(SENSITIVE_TEXT, new SQLException(SENSITIVE_TEXT));

        IllegalArgumentException inner = new IllegalArgumentException(SENSITIVE_TEXT);
        RuntimeException circular = new IllegalStateException(SENSITIVE_TEXT, inner);
        inner.initCause(circular);

        // Eleven causes: the first ten are named, the eleventh is cut short.
        Throwable cause = new ArithmeticException(SENSITIVE_TEXT);
        StringBuilder deepChain = new StringBuilder("java.lang.IllegalStateException");
        for (int i = 0; i < 10; i++) {
            cause = new RuntimeException(SENSITIVE_TEXT, cause);
            deepChain.append(" <- java.lang.RuntimeException");
        }
        RuntimeException deep = new IllegalStateException(SENSITIVE_TEXT, cause);

        return Stream.of(
                Arguments.of(Named.of("cause chain", chained),
                        "java.lang.IllegalStateException <- java.sql.SQLException"),
                Arguments.of(Named.of("circular causes", circular),
                        "java.lang.IllegalStateException <- java.lang.IllegalArgumentException <- [circular]"),
                Arguments.of(Named.of("eleven causes", deep), deepChain.append(" <- ...").toString()));
    }

    static Stream<Arguments> failingDdl() {
        return Stream.of(
                Arguments.of(Named.of("orders exists", ORDERS_DDL), List.of(ORDERS_DDL)),
                Arguments.of(Named.of("order_audits exists", ORDER_AUDITS_DDL), List.of(ORDERS_DDL, ORDER_AUDITS_DDL)));
    }

    static Stream<Arguments> unexpectedReplies() throws JMSException {
        TextMessage text = mock(TextMessage.class);
        ObjectMessage stringBody = mock(ObjectMessage.class);
        when(stringBody.getObject()).thenReturn("ACCEPTED");
        ObjectMessage noBody = mock(ObjectMessage.class);
        return Stream.of(
                Arguments.of(Named.of("text message", text),
                        "Unexpected in-house reply: " + text.getClass().getName()),
                Arguments.of(Named.of("string body", stringBody), "Unexpected in-house reply: java.lang.String"),
                Arguments.of(Named.of("no body", noBody), "Unexpected in-house reply: null"));
    }

    private void stubReply(PurchaseReceipt receipt, String totalValue) throws JMSException {
        when(inHouseTemplate.sendAndReceive(eq(QUEUE), any(MessageCreator.class))).thenReturn(reply);
        when(reply.getObject()).thenReturn(receipt);
        when(reply.getStringProperty(InHouseOrderService.TOTAL_VALUE_PROPERTY)).thenReturn(totalValue);
    }

    /** The Samsung leg as {@code SamsungOrderService} behaves on a 200 reply: the price is appended to the total. */
    private static Answer<PurchaseReceipt> appendingTotal(String price, PurchaseReceipt receipt) {
        return invocation -> {
            AtomicReference<String> totalValue = invocation.getArgument(1);
            totalValue.accumulateAndGet(price, String::concat);
            return receipt;
        };
    }

    /** The item of the committed request (message.xml). */
    private static OrderItem samsungItem() {
        return item("Samsung", "s-1", "AX02", 1);
    }

    private static OrderItem philipsItem() {
        return item("Philips", "P3123", "23", 2);
    }

    private static OrderItem item(String manufacturer, String name, String productId, int quantity) {
        OrderItem item = new OrderItem();
        item.setManufacturer(manufacturer);
        item.setName(name);
        item.setProductId(productId);
        item.setQuantity(quantity);
        return item;
    }

    /** An order shaped like the committed request (message.xml): order 12 for John Doe. */
    private static Order order(OrderItem... items) {
        OrderPerson customer = new OrderPerson();
        customer.setFirstName("John");
        customer.setLastName("Doe");
        customer.setAddress("Main Street 123");
        Order order = new Order();
        order.setOrderId("12");
        order.setCustomer(customer);
        order.setOrderItems(new ArrayList<>(Arrays.asList(items)));
        return order;
    }

    private static PurchaseReceipt receipt(Status status, float totalPrice) {
        PurchaseReceipt receipt = new PurchaseReceipt();
        receipt.setStatus(status);
        receipt.setTotalPrice(totalPrice);
        return receipt;
    }

    /** Captures one logger's events at INFO and restores its level and appenders on {@link #close()}. */
    private static final class LogCapture {

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

        List<ILoggingEvent> events() {
            return List.copyOf(appender.list);
        }

        List<ILoggingEvent> events(Level level) {
            return events().stream().filter(event -> event.getLevel() == level).toList();
        }

        void close() {
            try {
                logger.detachAppender(appender);
                appender.stop();
            } finally {
                logger.setLevel(previousLevel);
            }
        }
    }
}
