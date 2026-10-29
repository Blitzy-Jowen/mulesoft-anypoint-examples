/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.jms_message_rollback_and_redelivery.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import jakarta.jms.MessageFormatException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import com.mulesoft.examples.jms_message_rollback_and_redelivery.client.TopicPublisher;
import com.mulesoft.examples.jms_message_rollback_and_redelivery.exception.MyException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Unit tests for {@link RedeliveryService}, the body of flow {@code JMSRedeliver} and its
 * {@code choice-exception-strategy} ({@code jms-redelivery.xml:26-47}). Each reachable branch is driven with a
 * mocked {@link TopicPublisher}, and the INFO loggers of the branches, with the catch branch's ERROR line, are
 * read from the service's own logger. The payload is the one the original
 * {@code JmsMessageRollbackAndRedeliveryIT} sends to queue {@code in}.
 */
class RedeliveryServiceTest {

    private static final String MESSAGE = "Message123";

    /** {@code doc:name} of the catch branch ({@code jms-redelivery.xml:40}), which starts its ERROR line. */
    private static final String CATCH_BRANCH = "Catch Exception Strategy";

    /** Text carried by every exception message, cause message and suppressed message; no log line may hold it. */
    private static final String SENTINEL = "pw-" + "SENTINEL-" + UUID.randomUUID();

    private TopicPublisher publisher;

    private RedeliveryService service;

    private Logger logger;

    private ListAppender<ILoggingEvent> appender;

    private Level originalLevel;

    @BeforeEach
    void setUp() {
        publisher = mock(TopicPublisher.class);
        service = new RedeliveryService(publisher);

        logger = (Logger) LoggerFactory.getLogger(RedeliveryService.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(originalLevel);
    }

    /** The formatted messages of the INFO events the service logged, in the order it logged them. */
    private List<String> infoMessages() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** The formatted messages of the ERROR events the service logged, in the order it logged them. */
    private List<String> errorMessages() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** Every event the service logged, as its level, a space and its formatted message, in the order logged. */
    private List<String> events() {
        return appender.list.stream()
                .map(event -> event.getLevel() + " " + event.getFormattedMessage())
                .toList();
    }

    /** No event carries {@link #SENTINEL}, and none has a throwable attached. */
    private void assertNoExceptionTextLogged() {
        for (ILoggingEvent event : appender.list) {
            String logged = event.getFormattedMessage();
            assertFalse(logged.contains(SENTINEL), () -> "exception text logged: " + logged);
            assertNull(event.getThrowableProxy(), () -> "throwable logged with: " + logged);
        }
    }

    /** Replaces the stack trace of {@code failure} with {@code frames}, none meaning an empty trace. */
    private static <T extends Throwable> T withTrace(T failure, StackTraceElement... frames) {
        failure.setStackTrace(frames);
        return failure;
    }

    @Test
    @DisplayName("logger texts of the when, catch and rollback branches match jms-redelivery.xml:28,41,45")
    void branchMessagesMatchOriginalLoggers() {
        assertEquals("Transaction without errors", RedeliveryService.SUCCESS_MESSAGE);
        // The original's "commited" misspelling is part of the logged text.
        assertEquals("Entered catch exception strategy. The transaction is commited.",
                RedeliveryService.CATCH_MESSAGE);
        assertEquals("Entered rollback exception strategy. The message rolls back to its original state for "
                + "reprocessing.", RedeliveryService.ROLLBACK_MESSAGE);
    }

    @Test
    @DisplayName("when JMSXDeliveryCount == 5: logs success and publishes the payload to topic1")
    void fifthDeliveryLogsSuccessAndPublishes() {
        assertDoesNotThrow(() -> service.process(MESSAGE, 5));

        verify(publisher).publish(MESSAGE);
        verifyNoMoreInteractions(publisher);
        assertEquals(List.of(RedeliveryService.SUCCESS_MESSAGE), infoMessages());
    }

    @ParameterizedTest(name = "JMSXDeliveryCount = {0}")
    @ValueSource(ints = {1, 2, 3, 4, 6})
    @DisplayName("otherwise: test component throws MyException, rollback branch logs and rethrows it")
    void otherDeliveryRollsBackWithMyException(int deliveryCount) {
        MyException thrown = assertThrows(MyException.class, () -> service.process(MESSAGE, deliveryCount));

        assertEquals("test", thrown.getError());
        verifyNoInteractions(publisher);
        assertEquals(List.of(RedeliveryService.ROLLBACK_MESSAGE), infoMessages());
        assertEquals(List.of(), errorMessages());
    }

    @Test
    @DisplayName("catch branch: a failure not caused by MyException is logged at ERROR by type, then at INFO, "
            + "and the transaction commits")
    void publishFailureWithoutMyExceptionTakesCatchBranch() {
        RuntimeException failure = withTrace(
                new RuntimeException("publish failed " + SENTINEL, new IllegalStateException("broker " + SENTINEL)),
                new StackTraceElement("org.example.Publisher", "send", "Publisher.java", 12));
        failure.addSuppressed(new IllegalArgumentException("suppressed " + SENTINEL));
        // doAnswer, not doThrow, which would refill the stack trace the failure carries.
        doAnswer(invocation -> {
            throw failure;
        }).when(publisher).publish(MESSAGE);

        assertDoesNotThrow(() -> service.process(MESSAGE, 5));

        verify(publisher).publish(MESSAGE);
        assertEquals(List.of(
                "INFO " + RedeliveryService.SUCCESS_MESSAGE,
                "ERROR " + CATCH_BRANCH + ": java.lang.RuntimeException at org.example.Publisher.send(Publisher.java:12)"
                        + " <- java.lang.IllegalStateException",
                "INFO " + RedeliveryService.CATCH_MESSAGE), events());
        assertNoExceptionTextLogged();
    }

    @Test
    @DisplayName("rollback branch: a failure caused by MyException rethrows that MyException from the cause chain")
    void publishFailureCausedByMyExceptionTakesRollbackBranch() {
        MyException inner = new MyException("x");
        doThrow(new RuntimeException(inner)).when(publisher).publish(MESSAGE);

        MyException thrown = assertThrows(MyException.class, () -> service.process(MESSAGE, 5));

        assertSame(inner, thrown);
        assertEquals("x", thrown.getError());
        assertEquals(List.of(RedeliveryService.SUCCESS_MESSAGE, RedeliveryService.ROLLBACK_MESSAGE), infoMessages());
        assertEquals(List.of(), errorMessages());
    }

    /**
     * A failure not caused by {@link MyException} yields exactly one ERROR line, ahead of the branch's INFO line:
     * the branch name, the failure's class and throw-site frame, then each cause's class. An empty stack trace adds
     * no frame, a native or file-less frame is written as {@link StackTraceElement} writes it, a repeated cause ends
     * the chain with {@code [circular]}, more than ten causes end it with {@code ...}, and names are escaped and
     * bounded. No message, cause message or suppressed message is written and no throwable is attached.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("catchBranchFailures")
    @DisplayName("handleFailure, catch branch: logs the failure's type identifiers at ERROR, then INFO, and returns")
    void handleFailureWithoutMyExceptionTakesCatchBranch(String description, Exception failure, String described) {
        assertDoesNotThrow(() -> service.handleFailure(failure));

        verifyNoInteractions(publisher);
        assertEquals(List.of("ERROR " + CATCH_BRANCH + ": " + described, "INFO " + RedeliveryService.CATCH_MESSAGE),
                events());
        assertNoExceptionTextLogged();
    }

    /** Description, the failure {@code handleFailure} receives, then the expected text after the branch name. */
    static Stream<Arguments> catchBranchFailures() {
        MessageFormatException unreadable = withTrace(new MessageFormatException("unreadable body " + SENTINEL),
                new StackTraceElement("org.example.Reader", "read", "Reader.java", 21));
        unreadable.initCause(new ClassNotFoundException("qa.Foo " + SENTINEL));
        unreadable.addSuppressed(new IllegalStateException("suppressed " + SENTINEL));

        IllegalStateException circular = withTrace(new IllegalStateException(SENTINEL));
        RuntimeException middle = withTrace(new RuntimeException("middle " + SENTINEL, circular));
        circular.initCause(middle);

        IllegalStateException tenCauses = withTrace(new IllegalStateException("ten " + SENTINEL));
        Throwable tail = tenCauses;
        for (int i = 0; i < 10; i++) {
            RuntimeException next = withTrace(new RuntimeException("cause " + i + " " + SENTINEL));
            tail.initCause(next);
            tail = next;
        }
        IllegalStateException elevenCauses = withTrace(new IllegalStateException("eleven " + SENTINEL));
        tail = elevenCauses;
        for (int i = 0; i < 11; i++) {
            RuntimeException next = withTrace(new RuntimeException("cause " + i + " " + SENTINEL));
            tail.initCause(next);
            tail = next;
        }
        String tenNames = " <- java.lang.RuntimeException".repeat(10);

        return Stream.of(
                Arguments.of("inbound read failure with cause and suppressed exception", unreadable,
                        "jakarta.jms.MessageFormatException at org.example.Reader.read(Reader.java:21)"
                                + " <- java.lang.ClassNotFoundException"),
                Arguments.of("empty stack trace", withTrace(new IllegalStateException(SENTINEL)),
                        "java.lang.IllegalStateException"),
                Arguments.of("native frame", withTrace(new IllegalStateException(SENTINEL),
                                new StackTraceElement("org.example.Native", "call", "Native.java", -2)),
                        "java.lang.IllegalStateException at org.example.Native.call(Native Method)"),
                Arguments.of("frame without file", withTrace(new IllegalStateException(SENTINEL),
                                new StackTraceElement("org.example.Generated", "run", null, 7)),
                        "java.lang.IllegalStateException at org.example.Generated.run(Unknown Source)"),
                Arguments.of("frame without line", withTrace(new IllegalStateException(SENTINEL),
                                new StackTraceElement("org.example.Lined", "run", "Lined.java", -1)),
                        "java.lang.IllegalStateException at org.example.Lined.run(Lined.java)"),
                Arguments.of("circular causes", circular,
                        "java.lang.IllegalStateException <- java.lang.RuntimeException <- [circular]"),
                Arguments.of("ten causes", tenCauses, "java.lang.IllegalStateException" + tenNames),
                Arguments.of("eleven causes", elevenCauses, "java.lang.IllegalStateException" + tenNames + " <- ..."),
                Arguments.of("escaped names", withTrace(new IllegalStateException(SENTINEL),
                                new StackTraceElement("a\r\nb\\c\u202Ed\uD800", "m\r\u2028", "F\u0000.java", 1)),
                        "java.lang.IllegalStateException"
                                + " at a\\u000d\\u000ab\\u005cc\\u202ed\\ud800.m\\u000d\\u2028(F\\u0000.java:1)"),
                Arguments.of("overlong name", withTrace(new IllegalStateException(SENTINEL),
                                new StackTraceElement("org.example.Long", "m".repeat(300), "Long.java", 3)),
                        "java.lang.IllegalStateException at org.example.Long." + "m".repeat(256)
                                + "...[truncated](Long.java:3)"));
    }

    @Test
    @DisplayName("handleFailure, rollback branch: a failure caused by MyException rethrows that MyException")
    void handleFailureCausedByMyExceptionTakesRollbackBranch() {
        MyException inner = new MyException("y");

        MyException thrown = assertThrows(MyException.class,
                () -> service.handleFailure(new IllegalStateException(new RuntimeException(inner))));

        assertSame(inner, thrown);
        verifyNoInteractions(publisher);
        assertEquals(List.of(RedeliveryService.ROLLBACK_MESSAGE), infoMessages());
        assertEquals(List.of(), errorMessages());
    }
}
