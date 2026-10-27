/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.websphere_mq.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jms.annotation.JmsListener;

import com.mulesoft.examples.websphere_mq.service.AjaxChannelBroker;

/**
 * Trigger test of {@link OutQueueListener}, which replaces flow {@code Output} of
 * {@code websphere-mq/src/main/app/websphere-mq.xml:24-27}: a non-transacted {@code wmq:inbound-endpoint} on queue
 * {@code out} feeding {@code <ajax:outbound-endpoint cacheMessages="true" channel="/services/wmqExample/dequeue"/>},
 * whose connector {@code serverUrl} {@code http://0.0.0.0:8086/services/wmqExample} (line 7) gives the server path.
 *
 * <p>The listener is real and the broker a Mockito mock, so {@code onMessage} → {@code forward} →
 * {@code publishToDequeue} → {@link AjaxChannelBroker#publish(String, Object)} runs unchanged and no Spring context,
 * queue manager or Ajax client is involved. Holding messages for the first subscriber ({@code cacheMessages}) is the
 * broker's behaviour and is covered by {@code AjaxChannelBrokerTest}. The original {@code Output} assertion
 * ({@code WebsphereMQIT.java:53-54}) is ported in {@code WebsphereMQTest}.
 */
class OutQueueListenerTest {

    /** Server path of the original Ajax connector, as {@code ajax.server-path} configures it. */
    private static final String SERVER_PATH = "/services/wmqExample";

    /** The channel the copied {@code static/services/wmqExample/index.html} subscribes to. */
    private static final String DEQUEUE_CHANNEL = "/services/wmqExample/dequeue";

    private static final String PROCESSED_TEXT = "hello - processed";

    private AjaxChannelBroker broker;

    private OutQueueListener listener;

    @BeforeEach
    void setUp() {
        broker = mock(AjaxChannelBroker.class);
        listener = new OutQueueListener(broker, SERVER_PATH);
    }

    @Test
    void forwardPublishesToDequeueChannelAndReturnsPayload() {
        assertThat(listener.forward(PROCESSED_TEXT)).isEqualTo(PROCESSED_TEXT);

        verify(broker).publish(DEQUEUE_CHANNEL, PROCESSED_TEXT);
        verifyNoMoreInteractions(broker);
    }

    @Test
    void publishToDequeueBuildsChannelFromServerPath() {
        OutQueueListener otherListener = new OutQueueListener(broker, "/other");

        assertThat(otherListener.publishToDequeue("x")).isEqualTo("x");

        verify(broker).publish("/other/dequeue", "x");
        verifyNoMoreInteractions(broker);
    }

    @Test
    void onMessagePublishesTheReceivedTextOnTheDequeueChannel() {
        listener.onMessage(PROCESSED_TEXT);

        verify(broker).publish(DEQUEUE_CHANNEL, PROCESSED_TEXT);
        verifyNoMoreInteractions(broker);
    }

    /**
     * Flow {@code Output} declares no exception strategy, so a broker failure must leave the listener as thrown and
     * reach the listener container unwrapped.
     */
    @Test
    void brokerFailurePropagatesUnchanged() {
        IllegalStateException failure = new IllegalStateException("broker shut down");
        doThrow(failure).when(broker).publish(anyString(), any());

        assertThatThrownBy(() -> listener.onMessage(PROCESSED_TEXT)).isSameAs(failure);
        assertThatThrownBy(() -> listener.forward(PROCESSED_TEXT)).isSameAs(failure);
    }

    /**
     * The original inbound endpoint on {@code out} declares no transaction, so the listener runs on the
     * auto-acknowledging container factory rather than the transacted one flow {@code MessageProcessor} uses.
     */
    @Test
    void onMessageIsAnAutoAckListenerOnQueueOut() throws NoSuchMethodException {
        JmsListener jmsListener =
                OutQueueListener.class.getMethod("onMessage", String.class).getAnnotation(JmsListener.class);

        assertThat(jmsListener).isNotNull();
        assertThat(jmsListener.destination()).isEqualTo("${wmq.queues.out:out}");
        assertThat(jmsListener.containerFactory()).isEqualTo("autoAckListenerContainerFactory");
    }

    @Test
    void constructorRejectsNullArguments() {
        assertThatNullPointerException().isThrownBy(() -> new OutQueueListener(null, SERVER_PATH))
                .withMessage("broker");
        assertThatNullPointerException().isThrownBy(() -> new OutQueueListener(broker, null))
                .withMessage("serverPath");
    }
}
