/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.websphere_mq.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.stream.Stream;

import jakarta.jms.JMSException;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.listener.adapter.MessagingMessageListenerAdapter;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;
import org.springframework.messaging.support.MessageBuilder;

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

    /**
     * Every form {@code AjaxResourceConfig} serves the page {@code /services/wmqExample/} for must publish on the
     * channel that page subscribes to (index.html:25) and {@code ajax.cache-messages-channels} lists, never on one
     * with a doubled or missing slash.
     */
    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"/services/wmqExample", "/services/wmqExample/", "services/wmqExample",
            " /services/wmqExample// "})
    void publishToDequeueUsesTheOriginalChannelForEveryEquivalentServerPath(String serverPath) {
        OutQueueListener equivalentListener = new OutQueueListener(broker, serverPath);

        assertThat(equivalentListener.publishToDequeue(PROCESSED_TEXT)).isEqualTo(PROCESSED_TEXT);

        verify(broker).publish(DEQUEUE_CHANNEL, PROCESSED_TEXT);
        verifyNoMoreInteractions(broker);
    }

    static Stream<Arguments> rejectedServerPaths() {
        return Stream.of(
                Arguments.of("/", "ajax.server-path must name a path below the root, but is '/'"),
                Arguments.of("/a//b", "ajax.server-path must be a literal path, but is '/a//b'"));
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("rejectedServerPaths")
    void constructorRejectsServerPathsTheDocrootRegistrationRejects(String serverPath, String expectedMessage) {
        assertThatIllegalStateException().isThrownBy(() -> new OutQueueListener(broker, serverPath))
                .withMessage(expectedMessage);
        verifyNoInteractions(broker);
    }

    /**
     * A JMS text message is published unchanged when Spring's {@code @JmsListener} argument resolution binds it, a
     * text without a non-whitespace character included, which Spring's payload resolver would reject as empty. The
     * adapter and handler method factory are the ones Spring builds for the annotation.
     */
    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {PROCESSED_TEXT, "", "   "})
    void onMessagePublishesTheReceivedTextOnTheDequeueChannel(String text)
            throws JMSException, NoSuchMethodException {
        TextMessage jmsMessage = mock(TextMessage.class);
        when(jmsMessage.getText()).thenReturn(text);

        jmsListenerAdapter().onMessage(jmsMessage, mock(Session.class));

        verify(broker).publish(DEQUEUE_CHANNEL, text);
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

        assertThatThrownBy(() -> listener.onMessage(MessageBuilder.withPayload(PROCESSED_TEXT).build()))
                .isSameAs(failure);
        assertThatThrownBy(() -> listener.forward(PROCESSED_TEXT)).isSameAs(failure);
    }

    /**
     * The original inbound endpoint on {@code out} declares no transaction, so the listener runs on the
     * auto-acknowledging container factory rather than the transacted one flow {@code MessageProcessor} uses.
     */
    @Test
    void onMessageIsAnAutoAckListenerOnQueueOut() throws NoSuchMethodException {
        JmsListener jmsListener =
                OutQueueListener.class.getMethod("onMessage", Message.class).getAnnotation(JmsListener.class);

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

    /**
     * Wraps {@link #listener} as Spring wraps a {@code @JmsListener} method: a {@link MessagingMessageListenerAdapter}
     * over a handler method of the default {@link DefaultMessageHandlerMethodFactory}, whose argument resolvers bind
     * the JMS message to the method's parameter.
     */
    private MessagingMessageListenerAdapter jmsListenerAdapter() throws NoSuchMethodException {
        DefaultMessageHandlerMethodFactory factory = new DefaultMessageHandlerMethodFactory();
        factory.afterPropertiesSet();
        MessagingMessageListenerAdapter adapter = new MessagingMessageListenerAdapter();
        adapter.setHandlerMethod(factory.createInvocableHandlerMethod(listener,
                OutQueueListener.class.getMethod("onMessage", Message.class)));
        return adapter;
    }
}
