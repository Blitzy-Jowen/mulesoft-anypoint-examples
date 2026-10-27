/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.jms_message_rollback_and_redelivery.trigger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.Stream;

import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageEOFException;
import jakarta.jms.MessageFormatException;
import jakarta.jms.ObjectMessage;
import jakarta.jms.StreamMessage;
import jakarta.jms.TextMessage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.mulesoft.examples.jms_message_rollback_and_redelivery.exception.MyException;
import com.mulesoft.examples.jms_message_rollback_and_redelivery.service.RedeliveryService;

/**
 * Unit tests for {@link InQueueListener}, the transacted inbound endpoint of flow {@code JMSRedeliver}
 * ({@code jms-redelivery.xml:21-47}, AAP 0.7.3). The listener hands each delivery's body and
 * {@code JMSXDeliveryCount} to {@link RedeliveryService}; when the flow takes its rollback branch it pauses through
 * {@link RedeliveryDelay} and rethrows the very same {@link MyException}, so the session-transacted container rolls
 * back with the original {@code exception.causedBy(org.exceptions.MyException)} cause intact.
 *
 * <p>Both collaborators are Mockito mocks: no Spring context, broker or real pause is involved. The broker side,
 * including the measured redelivery gaps, is covered by {@code JmsMessageRollbackAndRedeliveryTest}.
 */
class InQueueListenerTest {

    /** The message the original {@code JmsMessageRollbackAndRedeliveryIT} dispatches to queue {@code in}. */
    private static final String PAYLOAD = "Message123";

    private RedeliveryService service;

    private RedeliveryDelay delay;

    private InQueueListener listener;

    @BeforeEach
    void setUp() {
        service = mock(RedeliveryService.class);
        delay = mock(RedeliveryDelay.class);
        listener = new InQueueListener(service, delay);
    }

    private static TextMessage textMessage(int deliveryCount) throws JMSException {
        TextMessage message = mock(TextMessage.class);
        when(message.getBody(Object.class)).thenReturn(PAYLOAD);
        when(message.getIntProperty("JMSXDeliveryCount")).thenReturn(deliveryCount);
        return message;
    }

    @Test
    @DisplayName("the fifth delivery is handed to the flow and returns without a pause, so the session commits")
    void fifthDeliveryIsProcessedWithoutPause() throws Exception {
        TextMessage message = textMessage(5);

        assertDoesNotThrow(() -> listener.onMessage(message));

        verify(service).process(PAYLOAD, 5);
        verify(delay, never()).pauseBeforeRollback(anyInt());
    }

    @ParameterizedTest(name = "JMSXDeliveryCount {0}: pause, then the same MyException is rethrown")
    @ValueSource(ints = {1, 3})
    @DisplayName("the rollback branch pauses for the delivery's redelivery delay and rethrows the same MyException")
    void myExceptionPausesThenRethrowsSameInstance(int deliveryCount) throws Exception {
        MyException failure = new MyException();
        doThrow(failure).when(service).process(PAYLOAD, deliveryCount);
        TextMessage message = textMessage(deliveryCount);

        MyException thrown = assertThrows(MyException.class, () -> listener.onMessage(message));

        assertSame(failure, thrown);
        InOrder order = inOrder(service, delay);
        order.verify(service).process(PAYLOAD, deliveryCount);
        order.verify(delay).pauseBeforeRollback(deliveryCount);
        verifyNoMoreInteractions(delay);
    }

    @Test
    @DisplayName("a normal return from the flow (its catch branch) commits without a pause")
    void normalReturnCommitsWithoutPause() throws Exception {
        TextMessage message = textMessage(2);

        assertDoesNotThrow(() -> listener.onMessage(message));

        verify(service).process(PAYLOAD, 2);
        verify(delay, never()).pauseBeforeRollback(anyInt());
    }

    @Test
    @DisplayName("a non-text body is handed to the flow unchanged")
    void bytesPayloadIsForwardedUnchanged() throws Exception {
        BytesMessage message = mock(BytesMessage.class);
        byte[] bytes = {1, 2, 3};
        when(message.getBody(Object.class)).thenReturn(bytes);
        when(message.getIntProperty("JMSXDeliveryCount")).thenReturn(5);

        listener.onMessage(message);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(service).process(payload.capture(), eq(5));
        assertArrayEquals(bytes, (byte[]) payload.getValue());
        verify(delay, never()).pauseBeforeRollback(anyInt());
    }

    @Test
    @DisplayName("a body-less JMS Message is itself the payload, as Mule's inbound transformer passed it through")
    void bodylessMessageIsItselfThePayload() throws Exception {
        Message message = mock(Message.class);
        when(message.getIntProperty("JMSXDeliveryCount")).thenReturn(5);

        assertDoesNotThrow(() -> listener.onMessage(message));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(service).process(payload.capture(), eq(5));
        assertSame(message, payload.getValue());
        verify(message, never()).getBody(any());
        verify(delay, never()).pauseBeforeRollback(anyInt());
    }

    @ParameterizedTest(name = "stream ends with {0}")
    @ValueSource(strings = {"MessageEOFException", "a null value"})
    @DisplayName("a stream message yields its values up to the end of the stream or its first null value")
    void streamMessageYieldsItsValuesAsList(String end) throws Exception {
        StreamMessage message = mock(StreamMessage.class);
        if (end.equals("MessageEOFException")) {
            when(message.readObject()).thenReturn("a", 1).thenThrow(new MessageEOFException("end of stream"));
        } else {
            when(message.readObject()).thenReturn("a", 1, null, "after the null value");
        }
        when(message.getIntProperty("JMSXDeliveryCount")).thenReturn(5);

        listener.onMessage(message);

        verify(service).process(List.of("a", 1), 5);
        verify(message, never()).getBody(any());
        verify(delay, never()).pauseBeforeRollback(anyInt());
    }

    /** Messages whose payload or delivery count cannot be read, each with the exception the read throws. */
    static Stream<Arguments> unreadableMessages() throws JMSException {
        ObjectMessage undeserializable = mock(ObjectMessage.class);
        MessageFormatException deserialization =
                new MessageFormatException("Deserialization error on ActiveMQObjectMessage");
        when(undeserializable.getBody(Object.class)).thenThrow(deserialization);

        StreamMessage unreadableStream = mock(StreamMessage.class);
        MessageFormatException streamRead = new MessageFormatException("stream value cannot be read");
        when(unreadableStream.readObject()).thenThrow(streamRead);

        TextMessage countFails = mock(TextMessage.class);
        JMSException countRead = new JMSException("JMSXDeliveryCount cannot be read");
        when(countFails.getBody(Object.class)).thenReturn(PAYLOAD);
        when(countFails.getIntProperty("JMSXDeliveryCount")).thenThrow(countRead);

        Message countMissing = mock(Message.class);
        NumberFormatException missingCount = new NumberFormatException("null");
        when(countMissing.getIntProperty("JMSXDeliveryCount")).thenThrow(missingCount);

        return Stream.of(
                Arguments.of(Named.of("undeserializable object message", undeserializable), deserialization),
                Arguments.of(Named.of("unreadable stream message", unreadableStream), streamRead),
                Arguments.of(Named.of("JMSXDeliveryCount read failure", countFails), countRead),
                Arguments.of(Named.of("JMSXDeliveryCount missing", countMissing), missingCount));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unreadableMessages")
    @DisplayName("a failure reading the message goes to the choice-exception strategy and returns without a pause")
    void readFailureGoesToChoiceExceptionStrategy(Message message, Exception failure) throws Exception {
        assertDoesNotThrow(() -> listener.onMessage(message));

        verify(service).handleFailure(failure);
        verify(service, never()).process(any(), anyInt());
        verify(delay, never()).pauseBeforeRollback(anyInt());
    }

    @Test
    @DisplayName("a read failure the strategy routes to its rollback branch rethrows that MyException without a pause")
    void readFailureRolledBackByStrategyRethrowsWithoutPause() throws Exception {
        ObjectMessage message = mock(ObjectMessage.class);
        MessageFormatException failure = new MessageFormatException("Deserialization error on ActiveMQObjectMessage");
        when(message.getBody(Object.class)).thenThrow(failure);
        MyException rollback = new MyException();
        doThrow(rollback).when(service).handleFailure(failure);

        MyException thrown = assertThrows(MyException.class, () -> listener.onMessage(message));

        assertSame(rollback, thrown);
        verify(service, never()).process(any(), anyInt());
        verify(delay, never()).pauseBeforeRollback(anyInt());
    }
}
