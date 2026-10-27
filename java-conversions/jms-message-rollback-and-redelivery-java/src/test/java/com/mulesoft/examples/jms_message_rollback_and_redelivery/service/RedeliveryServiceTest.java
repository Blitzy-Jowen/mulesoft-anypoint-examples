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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.List;

import jakarta.jms.MessageFormatException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
 * mocked {@link TopicPublisher}, and the INFO loggers of the branches are read from the service's own logger.
 * The payload is the one the original {@code JmsMessageRollbackAndRedeliveryIT} sends to queue {@code in}.
 */
class RedeliveryServiceTest {

    private static final String MESSAGE = "Message123";

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
    }

    @Test
    @DisplayName("catch branch: a failure not caused by MyException is logged and the transaction commits")
    void publishFailureWithoutMyExceptionTakesCatchBranch() {
        doThrow(new RuntimeException("boom")).when(publisher).publish(MESSAGE);

        assertDoesNotThrow(() -> service.process(MESSAGE, 5));

        verify(publisher).publish(MESSAGE);
        assertEquals(List.of(RedeliveryService.SUCCESS_MESSAGE, RedeliveryService.CATCH_MESSAGE), infoMessages());
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
    }

    @Test
    @DisplayName("handleFailure, catch branch: an inbound read failure not caused by MyException logs and returns")
    void handleFailureWithoutMyExceptionTakesCatchBranch() {
        MessageFormatException failure = new MessageFormatException("Body not assignable to class java.lang.Object");

        assertDoesNotThrow(() -> service.handleFailure(failure));

        verifyNoInteractions(publisher);
        assertEquals(List.of(RedeliveryService.CATCH_MESSAGE), infoMessages());
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
    }
}
