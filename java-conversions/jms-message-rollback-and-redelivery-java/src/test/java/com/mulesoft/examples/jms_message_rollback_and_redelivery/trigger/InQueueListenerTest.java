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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.TextMessage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
}
