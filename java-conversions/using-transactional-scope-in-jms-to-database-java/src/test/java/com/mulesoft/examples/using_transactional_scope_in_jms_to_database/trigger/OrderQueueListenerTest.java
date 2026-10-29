/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.using_transactional_scope_in_jms_to_database.trigger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLSyntaxErrorException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jakarta.jms.BytesMessage;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.client.OrderRepository;
import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.mapper.OrderXmlMapper;
import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.model.Order;
import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.service.OrderTransactionService;
import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.service.TestComponent;

/**
 * Trigger test of {@link OrderQueueListener}, the replacement of the {@code jms:inbound-endpoint queue="in"} of flow
 * {@code transactionsFlow1} ({@code transactions.xml}), which has no HTTP entry point.
 *
 * <p>The broker cases send to queue {@code in} of the embedded Artemis broker and observe the live listener through
 * its log lines, the {@code orders} table of the H2 database, queue {@code out} and the two spied collaborators:
 * <ul>
 *   <li>the original path: every delivery inserts, fails in the {@code TestComponent} and rolls back, so the
 *       connector's {@code maxRedelivery="2"} lets deliveries 1 to 3 run and the 4th ends in the README's
 *       redelivery-exceeded ERROR, leaving no row ({@code [{count(*)=0}]} in the original test) and nothing on
 *       {@code out};</li>
 *   <li>two failures before the component (an unreadable document, a failing insert), rolled back the same way;</li>
 *   <li>a succeeding component, which shows the send to {@code out} ({@code JOIN_IF_POSSIBLE}) committing with the
 *       insert and the receive.</li>
 * </ul>
 * The broker-free cases call a listener built around a mocked {@link OrderTransactionService} with mocked JMS
 * messages, covering the redelivery guard's boundary, the message types, and the bytes body's length bound, empty
 * body and incomplete read.
 *
 * <p>The class-level configuration and the spy set are those of {@code service/OrderTransactionServiceTest}, so the
 * two classes share one cached Spring context. Every output assertion reads only what was written after
 * {@link #setUp}, because the captured output also holds earlier tests and other threads.
 */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class OrderQueueListenerTest {

    private static final String IN = "in";

    private static final String OUT = "out";

    /** {@code Order.toString()} of the committed {@code message.xml}. */
    private static final String ORDER_TEXT = "Order [item_id=1, item_units=2, customer_id=1]";

    /** The flow's INFO logger {@code "XXXXXXX order: #[payload]"} for {@code message.xml}. */
    private static final String INFO_LINE = "XXXXXXX order: Order [item_id=1, item_units=2, customer_id=1]";

    /** The README's redelivery-exceeded ERROR for a text message, without the broker-assigned message ID. */
    private static final String EXCEEDED_FRAGMENT = "has been redelivered 3 times on endpoint \"jms://in\", which "
            + "exceeds the maxRedelivery setting of 2 on the connector \"Active_MQ\". Message payload is of type: "
            + "ActiveMQTextMessage";

    /** Counted to prove the redelivery-exceeded ERROR is written exactly once per message. */
    private static final String EXCEEDED_MARKER = "which exceeds the maxRedelivery setting of 2";

    /** The whole redelivery-exceeded line at ERROR, with the {@code ID:} message ID Artemis assigns. */
    private static final Pattern EXCEEDED_ERROR_LINE = Pattern.compile(
            "ERROR[^\\r\\n]*Message with id \"ID:[^\"]+\" " + Pattern.quote(EXCEEDED_FRAGMENT));

    /** The flow logger's line at its declared level, INFO. */
    private static final Pattern INFO_LOG_LINE = Pattern.compile("INFO[^\\r\\n]*" + Pattern.quote(INFO_LINE));

    /** Prefix of the line the listener container's error handler writes for each rolled-back delivery. */
    private static final String ROLLBACK_LOG = "JMS listener failed: ";

    /** An {@code order} document whose root is never closed. */
    private static final String MALFORMED_XML = "<order><itemId>1</itemId>";

    /** A rejected value that must never reach the log. */
    private static final String SENTINEL = "SENTINEL_7f3a";

    /** A well-formed {@code order} document whose {@code customerId} holds {@link #SENTINEL}. */
    private static final String SENTINEL_ORDER_XML = "<order><itemId>1</itemId><itemUnits>2</itemUnits><customerId>"
            + SENTINEL + "</customerId></order>";

    private static final String PROCESSED = "processed";

    private static final String DELIVERY_COUNT = "JMSXDeliveryCount";

    /** The listener's private {@code MAX_BODY_BYTES}: the largest bytes-message body it accepts, 1 MiB. */
    private static final int MAX_BODY_BYTES = 1_048_576;

    private static final Duration MAX_WAIT = Duration.ofSeconds(30);

    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    @SpyBean
    private TestComponent testComponent;

    @SpyBean
    private OrderRepository orderRepository;

    /** The application template; only sends to {@code in}, its settings are never changed. */
    @Autowired
    private JmsTemplate jmsTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConnectionFactory connectionFactory;

    private CapturedOutput output;

    private int baseline;

    @BeforeEach
    void setUp(CapturedOutput output) {
        jdbcTemplate.update("delete from orders");
        JmsTemplate drain = drainTemplate(200);
        while (drain.receive(OUT) != null) {
            // Discards whatever an earlier test left on queue out.
        }
        clearInvocations(testComponent, orderRepository);
        this.output = output;
        this.baseline = output.getAll().length();
    }

    @Test
    void originalPathRollsBackInsertAndRedeliversUntilLimit() throws Exception {
        jmsTemplate.convertAndSend(IN, messageXml());

        awaitRedeliveryLimit();

        verify(testComponent, times(3)).onCall(argThat(order -> ORDER_TEXT.equals(String.valueOf(order))));
        verify(orderRepository, times(3)).insert(any());
        String flowOutput = outputSinceBaseline();
        assertEquals(3, occurrences(flowOutput, INFO_LINE));
        assertEquals(3, matches(INFO_LOG_LINE, flowOutput));
        assertEquals(3, occurrences(flowOutput, ROLLBACK_LOG + "MyException: exception customized at "),
                "each processed delivery must roll back on the component's MyException");
        assertTrue(flowOutput.contains(EXCEEDED_FRAGMENT));
        assertEquals(1, matches(EXCEEDED_ERROR_LINE, flowOutput));
        assertEquals(0, rowCount());
        assertNull(drainTemplate(1000).receive(OUT));
        assertEquals(1, occurrences(outputSinceBaseline(), EXCEEDED_MARKER));
    }

    /**
     * A document the mapper rejects fails before the flow's logger and the database. Each rolled-back delivery logs
     * the mapper's own message, which names a rejected element but never quotes the document or the rejected text.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedDocuments")
    void rejectedDocumentIsRolledBackWithoutTouchingDatabase(String document, String expectedLog, String forbidden)
            throws Exception {
        jmsTemplate.convertAndSend(IN, document);

        awaitRedeliveryLimit();

        verify(orderRepository, never()).insert(any());
        verify(testComponent, never()).onCall(any());
        assertEquals(0, rowCount());
        String flowOutput = outputSinceBaseline();
        assertFalse(flowOutput.contains("XXXXXXX order:"), "the document fails before the flow's logger");
        assertEquals(3, occurrences(flowOutput, ROLLBACK_LOG));
        assertEquals(3, occurrences(flowOutput, expectedLog));
        assertFalse(output.getAll().contains(forbidden), "the log must never carry " + forbidden);
        assertTrue(flowOutput.contains(EXCEEDED_FRAGMENT));
        assertNull(drainTemplate(1000).receive(OUT));
        assertEquals(1, occurrences(outputSinceBaseline(), EXCEEDED_MARKER));
    }

    static Stream<Arguments> rejectedDocuments() {
        return Stream.of(
                Arguments.of(Named.of("malformed XML", MALFORMED_XML),
                        ROLLBACK_LOG + "IllegalArgumentException: Failed to convert XML to Order: ", MALFORMED_XML),
                Arguments.of(Named.of("well-formed order whose customerId is not an integer", SENTINEL_ORDER_XML),
                        ROLLBACK_LOG + "IllegalArgumentException: Failed to convert XML to Order: element customerId "
                                + "does not hold an integer at ",
                        SENTINEL));
    }

    /**
     * A failing insert rolls back before the component runs. Each rolled-back delivery logs the type and message of
     * the failure below the Spring wrappers, escaped onto one line and cut at 256 characters.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("insertFailures")
    void insertFailureRollsBackBeforeTestComponent(RuntimeException failure, String expectedLog, String forbidden)
            throws Exception {
        doThrow(failure).when(orderRepository).insert(any());

        jmsTemplate.convertAndSend(IN, messageXml());

        awaitRedeliveryLimit();

        verify(testComponent, never()).onCall(any());
        verify(orderRepository, times(3)).insert(any());
        assertEquals(0, rowCount());
        String flowOutput = outputSinceBaseline();
        assertEquals(3, occurrences(flowOutput, INFO_LINE));
        assertEquals(3, occurrences(flowOutput, expectedLog));
        assertFalse(flowOutput.contains(forbidden), "the log must never carry " + forbidden);
        assertTrue(flowOutput.contains(EXCEEDED_FRAGMENT));
        assertNull(drainTemplate(1000).receive(OUT));
        assertEquals(1, occurrences(outputSinceBaseline(), EXCEEDED_MARKER));
    }

    static Stream<Arguments> insertFailures() {
        String longMessage = "long message " + "0123456789".repeat(30);
        DataIntegrityViolationException cyclic = new DataIntegrityViolationException("outer");
        cyclic.initCause(new RecoverableDataAccessException("inner", cyclic));
        return Stream.of(
                Arguments.of(Named.of("Spring exception without cause", new DataIntegrityViolationException("boom")),
                        ROLLBACK_LOG + "DataIntegrityViolationException: boom at ",
                        "ListenerExecutionFailedException"),
                Arguments.of(Named.of("Spring wrapper around the driver's exception",
                        new BadSqlGrammarException("PreparedStatementCallback",
                                "insert into orders(item_id, item_units, customer_id) values (?, ?, ?)",
                                new SQLSyntaxErrorException("Table 'company.orders' doesn't exist"))),
                        ROLLBACK_LOG + "SQLSyntaxErrorException: Table 'company.orders' doesn't exist at ",
                        "bad SQL grammar"),
                Arguments.of(Named.of("message with CR and LF",
                        new DataIntegrityViolationException("first\r\nERROR forged line")),
                        ROLLBACK_LOG + "DataIntegrityViolationException: first\\u000D\\u000AERROR forged line at ",
                        "\nERROR forged line"),
                Arguments.of(Named.of("message longer than 256 characters",
                        new DataIntegrityViolationException(longMessage)),
                        ROLLBACK_LOG + "DataIntegrityViolationException: " + longMessage.substring(0, 256) + "... at ",
                        longMessage),
                Arguments.of(Named.of("cause chain of Spring wrappers looping back on itself", cyclic),
                        ROLLBACK_LOG + "RecoverableDataAccessException: inner at ",
                        "DataIntegrityViolationException: outer"));
    }

    /**
     * With the component succeeding, the row and the message on {@code out} must both be committed, once: the
     * template's send joins the listener's transacted session, which commits when the listener returns.
     */
    @Test
    void outboundSendJoinsListenerSessionAndCommitsWithInsert() throws Exception {
        doReturn(PROCESSED).when(testComponent).onCall(any());

        jmsTemplate.convertAndSend(IN, messageXml());

        await().atMost(MAX_WAIT).pollInterval(POLL_INTERVAL).until(() -> rowCount() == 1);
        Map<String, Object> row = jdbcTemplate.queryForMap("select item_id, item_units, customer_id from orders");
        assertEquals(1, ((Number) row.get("item_id")).intValue());
        assertEquals(2, ((Number) row.get("item_units")).intValue());
        assertEquals(1, ((Number) row.get("customer_id")).intValue());

        Message sent = drainTemplate(5000).receive(OUT);
        TextMessage text = assertInstanceOf(TextMessage.class, sent);
        assertEquals(PROCESSED, text.getText());
        assertNull(drainTemplate(1000).receive(OUT), "exactly one message must reach out");

        verify(testComponent, times(1)).onCall(any());
        verify(orderRepository, times(1)).insert(any());
        assertEquals(1, rowCount());
        String flowOutput = outputSinceBaseline();
        assertEquals(1, occurrences(flowOutput, INFO_LINE));
        assertEquals(0, occurrences(flowOutput, ROLLBACK_LOG));
        assertEquals(0, occurrences(flowOutput, EXCEEDED_MARKER), "a committed delivery is never redelivered");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("deliveriesWithinRedeliveryLimit")
    void deliveryWithinRedeliveryLimitIsConvertedAndProcessed(Message message) throws Exception {
        OrderTransactionService service = mock(OrderTransactionService.class);

        standaloneListener(service).onMessage(message);

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(service, times(1)).process(captor.capture());
        assertEquals(ORDER_TEXT, captor.getValue().toString());
    }

    static Stream<Named<Message>> deliveriesWithinRedeliveryLimit() throws Exception {
        String xml = messageXml();
        int xmlLength = xml.getBytes(StandardCharsets.UTF_8).length;
        byte[] largestBody = (xml + " ".repeat(MAX_BODY_BYTES - xmlLength)).getBytes(StandardCharsets.UTF_8);
        return Stream.of(
                Named.of("text message, delivery count 3 (redelivery count 2, the limit)", textMessage(3, xml)),
                Named.of("text message without JMSXDeliveryCount (first delivery)", textMessage(null, xml)),
                Named.of("bytes message holding message.xml as UTF-8",
                        bytesMessage(1, xml.getBytes(StandardCharsets.UTF_8))),
                Named.of("bytes message of exactly 1048576 bytes (message.xml padded with trailing spaces)",
                        bytesMessage(1, largestBody)));
    }

    @Test
    void deliveryCountFourExceedsLimitAndIsConsumed() throws Exception {
        OrderTransactionService service = mock(OrderTransactionService.class);
        TextMessage message = textMessage(4, messageXml());
        OrderQueueListener listener = standaloneListener(service);

        assertDoesNotThrow(() -> listener.onMessage(message));

        verify(service, never()).process(any());
        verify(message, never()).getText();
        assertEquals("Active_MQ", OrderQueueListener.CONNECTOR_NAME);
        assertTrue(outputSinceBaseline().contains("Message with id \"null\" has been redelivered 3 times on endpoint "
                + "\"jms://in\", which exceeds the maxRedelivery setting of 2 on the connector \"Active_MQ\". "
                + "Message payload is of type: "));
    }

    @Test
    void nonTextNonBytesMessageIsRejected() throws Exception {
        OrderTransactionService service = mock(OrderTransactionService.class);
        Message message = mock(Message.class);
        stubDeliveryCount(message, 1);
        OrderQueueListener listener = standaloneListener(service);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> listener.onMessage(message));

        assertTrue(failure.getMessage().startsWith("Unsupported JMS message type: "));
        verify(service, never()).process(any());
    }

    /**
     * A declared body length outside 0 to {@code MAX_BODY_BYTES} is rejected before a buffer is allocated or a byte
     * is read: -1 (negative), the bound plus one, 2^31 (wrapped negative by an {@code int} cast), 2^32 + 90 (truncated
     * by that cast to the 90 bytes of {@code message.xml}, so only a prefix would be read) and {@code Long.MAX_VALUE}.
     */
    @ParameterizedTest(name = "declared length {0}")
    @ValueSource(longs = {-1L, MAX_BODY_BYTES + 1L, Integer.MAX_VALUE + 1L, (1L << 32) + 90L, Long.MAX_VALUE})
    void bytesBodyLengthOutsideBoundIsRejectedUnread(long declaredLength) throws Exception {
        OrderTransactionService service = mock(OrderTransactionService.class);
        BytesMessage message = bytesMessage(1, declaredLength, messageXml().getBytes(StandardCharsets.UTF_8), 0);
        OrderQueueListener listener = standaloneListener(service);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> listener.onMessage(message));

        assertEquals("Unsupported JMS BytesMessage body length: " + declaredLength + " bytes (supported: 0 to "
                + MAX_BODY_BYTES + ")", failure.getMessage());
        verify(message, never()).readBytes(any(byte[].class));
        verify(service, never()).process(any());
    }

    /**
     * A read returning fewer bytes than the body declares fails the delivery instead of decoding the bytes that
     * arrived; -1 is the provider's end-of-body answer and is reported as 0 bytes read.
     */
    @ParameterizedTest(name = "readBytes answers {0}")
    @CsvSource({"-1, 0", "0, 0", "40, 40", "89, 89"})
    void incompleteBytesBodyReadIsRejected(int readResult, int bytesRead) throws Exception {
        OrderTransactionService service = mock(OrderTransactionService.class);
        byte[] xml = messageXml().getBytes(StandardCharsets.UTF_8);
        BytesMessage message = bytesMessage(1, xml.length, Arrays.copyOf(xml, bytesRead), readResult);
        OrderQueueListener listener = standaloneListener(service);

        JMSException failure = assertThrows(JMSException.class, () -> listener.onMessage(message));

        assertEquals("Incomplete JMS BytesMessage body: read " + bytesRead + " of " + xml.length + " declared bytes",
                failure.getMessage());
        verify(message, times(1)).readBytes(any(byte[].class));
        verify(service, never()).process(any());
    }

    /** An empty bytes body is never read, since a provider answers -1, and the mapper rejects the empty text. */
    @Test
    void emptyBytesBodyIsRejectedByMapperUnread() throws Exception {
        OrderTransactionService service = mock(OrderTransactionService.class);
        BytesMessage message = bytesMessage(1, 0L, new byte[0], -1);
        OrderQueueListener listener = standaloneListener(service);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> listener.onMessage(message));

        assertEquals("Failed to convert XML to Order: input is empty", failure.getMessage());
        verify(message, never()).readBytes(any(byte[].class));
        verify(service, never()).process(any());
    }

    /** The listener as the context builds it from {@code application.yml}: {@code maxRedelivery} 2, queue in. */
    private static OrderQueueListener standaloneListener(OrderTransactionService service) {
        return new OrderQueueListener(new OrderXmlMapper(), service, 2, IN);
    }

    private static TextMessage textMessage(Integer deliveryCount, String text) throws JMSException {
        TextMessage message = mock(TextMessage.class);
        if (deliveryCount != null) {
            stubDeliveryCount(message, deliveryCount);
        }
        when(message.getText()).thenReturn(text);
        return message;
    }

    private static BytesMessage bytesMessage(int deliveryCount, byte[] body) throws JMSException {
        return bytesMessage(deliveryCount, body.length, body, body.length);
    }

    /**
     * A bytes message declaring {@code declaredLength} bytes whose {@code readBytes} copies as much of {@code body}
     * as the buffer holds and answers {@code readResult}, as a provider reporting a short or empty read would.
     */
    private static BytesMessage bytesMessage(int deliveryCount, long declaredLength, byte[] body, int readResult)
            throws JMSException {
        BytesMessage message = mock(BytesMessage.class);
        stubDeliveryCount(message, deliveryCount);
        when(message.getBodyLength()).thenReturn(declaredLength);
        doAnswer(invocation -> {
            byte[] target = invocation.getArgument(0);
            System.arraycopy(body, 0, target, 0, Math.min(body.length, target.length));
            return readResult;
        }).when(message).readBytes(any(byte[].class));
        return message;
    }

    private static void stubDeliveryCount(Message message, int deliveryCount) throws JMSException {
        when(message.propertyExists(DELIVERY_COUNT)).thenReturn(true);
        when(message.getIntProperty(DELIVERY_COUNT)).thenReturn(deliveryCount);
    }

    /** The committed {@code message.xml}, sent unchanged as the original test did. */
    private static String messageXml() throws IOException {
        return new ClassPathResource("message.xml").getContentAsString(StandardCharsets.UTF_8);
    }

    /** Waits for the redelivery-exceeded ERROR, which is written on the 4th and last delivery of a message. */
    private void awaitRedeliveryLimit() {
        await().atMost(MAX_WAIT).pollInterval(POLL_INTERVAL)
                .until(() -> occurrences(outputSinceBaseline(), EXCEEDED_MARKER) >= 1);
    }

    private String outputSinceBaseline() {
        return output.getAll().substring(baseline);
    }

    /** A non-transacted template of its own, so receives never touch the application template's settings. */
    private JmsTemplate drainTemplate(long timeoutMs) {
        JmsTemplate template = new JmsTemplate(connectionFactory);
        template.setReceiveTimeout(timeoutMs);
        return template;
    }

    private int rowCount() {
        return jdbcTemplate.queryForObject("select count(*) from orders", Integer.class);
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int from = haystack.indexOf(needle); from >= 0; from = haystack.indexOf(needle, from + needle.length())) {
            count++;
        }
        return count;
    }

    private static int matches(Pattern pattern, String text) {
        int count = 0;
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
