/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.websphere_mq.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jms.UncategorizedJmsException;
import org.springframework.jms.annotation.JmsListener;

import com.mulesoft.examples.websphere_mq.client.MqQueueSender;
import com.mulesoft.examples.websphere_mq.service.MqProcessingService;

/**
 * Trigger test of {@link InQueueListener}, which replaces flow {@code MessageProcessor} of
 * {@code websphere-mq/src/main/app/websphere-mq.xml:15-23}: {@code <wmq:inbound-endpoint queue="in">} with
 * {@code ALWAYS_BEGIN}, then {@code <test:component appendString=" - processed" waitTime="15000"/>}, then
 * {@code <wmq:outbound-endpoint queue="out">} with {@code JOIN_IF_POSSIBLE}.
 *
 * <p>The listener is real and both collaborators are Mockito mocks, so the flow body runs in its processor order
 * without a Spring context, a broker, an IBM MQ connection or the 15 s wait. The flow declares no exception strategy,
 * so every failure must leave the listener as thrown: that is what makes the transacted listener container roll back
 * the receive from {@code in} together with the send to {@code out}. The original {@code MessageProcessor}
 * assertion ({@code WebsphereMQIT.java:51-52}) is ported in {@code WebsphereMQTest}, and the transacted run on an
 * embedded broker is {@code WebsphereMqBrokerTest}.
 */
class InQueueListenerTest {

    private static final String PAYLOAD = "hello";

    /** {@link #PAYLOAD} followed by the original component's {@code appendString}, leading space included. */
    private static final String PROCESSED = "hello - processed";

    private MqProcessingService mqProcessingService;

    private MqQueueSender mqQueueSender;

    private InQueueListener listener;

    @BeforeEach
    void setUp() {
        mqProcessingService = mock(MqProcessingService.class);
        mqQueueSender = mock(MqQueueSender.class);
        listener = new InQueueListener(mqProcessingService, mqQueueSender);
    }

    @Test
    void processRunsServiceThenSendsResultToOut() {
        when(mqProcessingService.process(PAYLOAD)).thenReturn(PROCESSED);
        when(mqQueueSender.sendToOut(PROCESSED)).thenReturn(PROCESSED);

        assertThat(listener.process(PAYLOAD)).isEqualTo(PROCESSED);

        verifyProcessedThenSentToOut();
    }

    /** The flow's resulting payload is the outbound endpoint's result, not the processing step's. */
    @Test
    void processReturnsWhatTheSenderReturns() {
        when(mqProcessingService.process(PAYLOAD)).thenReturn(PROCESSED);
        when(mqQueueSender.sendToOut(PROCESSED)).thenReturn("sent");

        assertThat(listener.process(PAYLOAD)).isEqualTo("sent");

        verifyProcessedThenSentToOut();
    }

    @Test
    void onMessageFollowsTheSamePath() {
        when(mqProcessingService.process(PAYLOAD)).thenReturn(PROCESSED);
        when(mqQueueSender.sendToOut(PROCESSED)).thenReturn(PROCESSED);

        listener.onMessage(PAYLOAD);

        verifyProcessedThenSentToOut();
    }

    /**
     * A failure of the processing step (an interrupted wait, or a {@code null} payload) stops the flow before the
     * send, so nothing reaches {@code out} and the exception rolls the receive back.
     */
    @Test
    void serviceFailurePropagatesAndNothingIsSent() {
        IllegalStateException interrupted = new IllegalStateException("interrupted");
        NullPointerException nullPayload = new NullPointerException();
        when(mqProcessingService.process(PAYLOAD)).thenThrow(interrupted);
        when(mqProcessingService.process(null)).thenThrow(nullPayload);

        assertThatThrownBy(() -> listener.onMessage(PAYLOAD)).isSameAs(interrupted);
        assertThatThrownBy(() -> listener.process(null)).isSameAs(nullPayload);

        verify(mqProcessingService).process(PAYLOAD);
        verify(mqProcessingService).process(null);
        verifyNoMoreInteractions(mqProcessingService);
        verifyNoInteractions(mqQueueSender);
    }

    /** A failed send to {@code out} leaves the listener unwrapped, so the transacted session rolls back. */
    @Test
    void senderFailurePropagatesUnchanged() {
        UncategorizedJmsException outUnavailable = new UncategorizedJmsException("out unavailable");
        when(mqProcessingService.process(PAYLOAD)).thenReturn(PROCESSED);
        when(mqQueueSender.sendToOut(PROCESSED)).thenThrow(outUnavailable);

        assertThatThrownBy(() -> listener.onMessage(PAYLOAD)).isSameAs(outUnavailable);

        verifyProcessedThenSentToOut();
    }

    /** {@code ALWAYS_BEGIN} on queue {@code in} maps to the session-transacted container factory. */
    @Test
    void onMessageIsATransactedListenerOnQueueIn() throws NoSuchMethodException {
        Method onMessage = InQueueListener.class.getMethod("onMessage", String.class);
        JmsListener jmsListener = onMessage.getAnnotation(JmsListener.class);

        assertThat(jmsListener).isNotNull();
        assertThat(jmsListener.destination()).isEqualTo("${wmq.queues.in:in}");
        assertThat(jmsListener.containerFactory()).isEqualTo("transactedListenerContainerFactory");
    }

    /** Verifies the flow's processor order for {@link #PAYLOAD} and that nothing else was called. */
    private void verifyProcessedThenSentToOut() {
        InOrder order = inOrder(mqProcessingService, mqQueueSender);
        order.verify(mqProcessingService).process(PAYLOAD);
        order.verify(mqQueueSender).sendToOut(PROCESSED);
        verifyNoMoreInteractions(mqProcessingService, mqQueueSender);
    }
}
