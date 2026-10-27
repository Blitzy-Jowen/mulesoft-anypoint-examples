/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.jms_message_rollback_and_redelivery.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.stream.Stream;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

import org.apache.activemq.artemis.api.core.ActiveMQException;
import org.apache.activemq.artemis.api.core.client.ActiveMQClient;
import org.apache.activemq.artemis.api.core.client.ClientMessage;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.activemq.artemis.jms.client.ActiveMQMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.jms.DefaultJmsListenerContainerFactoryConfigurer;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.SimpleJmsListenerEndpoint;
import org.springframework.jms.connection.CachingConnectionFactory;
import org.springframework.jms.listener.DefaultMessageListenerContainer;

/**
 * Unit tests for {@link JmsConfig#zeroConsumerWindowPostProcessor()}, which turns off Artemis client prefetch so
 * that a consumer paused before its rollback holds no other message and the 5000/2000 ms redelivery gaps of
 * {@code trigger/RedeliveryDelay} hold with several messages in flight (AAP 0.7.3), and for the listener container
 * factory with its {@link JmsConfig.ConsumerClosingListenerContainer}, which closes each delivery's consumer before
 * the listener runs and pauses.
 *
 * <p>No Spring context or broker is involved: building an {@link ActiveMQConnectionFactory} opens no connection,
 * which is also why the post-processor may still change its settings. The broker-side timing is covered by
 * {@code trigger/InQueueListenerBrokerTest}.
 */
class JmsConfigTest {

    private static final String BROKER_URL = "tcp://localhost:61616";

    private static final String BEAN_NAME = "jmsConnectionFactory";

    private final BeanPostProcessor postProcessor = JmsConfig.zeroConsumerWindowPostProcessor();

    private final JmsConfig.ConsumerClosingListenerContainer container =
            new JmsConfig.ConsumerClosingListenerContainer();

    static Stream<Arguments> artemisFactoryGetsZeroConsumerWindow() {
        ActiveMQConnectionFactory cached = new ActiveMQConnectionFactory(BROKER_URL);
        ActiveMQConnectionFactory raw = new ActiveMQConnectionFactory(BROKER_URL);
        return Stream.of(
                Arguments.of("CachingConnectionFactory (spring.jms.cache.enabled=true)",
                        new CachingConnectionFactory(cached), cached),
                Arguments.of("ActiveMQConnectionFactory (spring.jms.cache.enabled=false)", raw, raw));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    @DisplayName("the Artemis factory behind the ConnectionFactory bean gets window 0; the bean itself is returned")
    void artemisFactoryGetsZeroConsumerWindow(String shape, ConnectionFactory bean,
                                              ActiveMQConnectionFactory artemis) {
        assertEquals(ActiveMQClient.DEFAULT_CONSUMER_WINDOW_SIZE, artemis.getConsumerWindowSize());

        Object processed = postProcessor.postProcessAfterInitialization(bean, BEAN_NAME);

        // The listener container and TopicPublisher must keep receiving the identical instance.
        assertSame(bean, processed);
        assertEquals(0, artemis.getConsumerWindowSize());
    }

    static Stream<Arguments> otherBeanIsReturnedUnchanged() {
        return Stream.of(
                Arguments.of("non-Artemis ConnectionFactory", mock(ConnectionFactory.class)),
                Arguments.of("CachingConnectionFactory on a non-Artemis target",
                        new CachingConnectionFactory(mock(ConnectionFactory.class))),
                Arguments.of("CachingConnectionFactory without a target", new CachingConnectionFactory()),
                Arguments.of("bean that is not a ConnectionFactory", "topic1"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    @DisplayName("any other bean is returned as the same instance")
    void otherBeanIsReturnedUnchanged(String shape, Object bean) {
        assertSame(bean, postProcessor.postProcessAfterInitialization(bean, BEAN_NAME));
    }

    @Test
    @DisplayName("the factory builds a transacted container that opens a consumer per receive and closes it")
    void factoryBuildsConsumerClosingContainer() {
        DefaultJmsListenerContainerFactoryConfigurer configurer =
                mock(DefaultJmsListenerContainerFactoryConfigurer.class);
        // Of Boot's configuration, only the hand-over of the injected connection factory matters here.
        doAnswer(invocation -> {
            DefaultJmsListenerContainerFactory factory = invocation.getArgument(0);
            factory.setConnectionFactory(invocation.getArgument(1));
            return null;
        }).when(configurer).configure(any(), any());
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        SimpleJmsListenerEndpoint endpoint = new SimpleJmsListenerEndpoint();
        endpoint.setDestination("in");
        endpoint.setMessageListener(message -> { });

        DefaultMessageListenerContainer container = new JmsConfig()
                .inQueueListenerContainerFactory(configurer, connectionFactory, 4)
                .createListenerContainer(endpoint);

        assertInstanceOf(JmsConfig.ConsumerClosingListenerContainer.class, container);
        // A cached consumer would stay closed after its first delivery.
        assertEquals(DefaultMessageListenerContainer.CACHE_SESSION, container.getCacheLevel());
        assertTrue(container.isSessionTransacted());
        assertEquals(Session.SESSION_TRANSACTED, container.getSessionAcknowledgeMode());
        assertFalse(container.isPubSubDomain());
        assertEquals(4, container.getConcurrentConsumers());
        assertEquals(4, container.getMaxConcurrentConsumers());
        // TopicPublisher joins the listener session only through this very instance.
        assertSame(connectionFactory, container.getConnectionFactory());
    }

    @Test
    @DisplayName("a received message has its consumer closed before it reaches the listener")
    void receivedMessageClosesConsumer() throws Exception {
        MessageConsumer consumer = mock(MessageConsumer.class);
        TextMessage message = mock(TextMessage.class);
        when(consumer.receive(anyLong())).thenReturn(message);

        assertSame(message, container.receiveMessage(consumer));

        verify(consumer).close();
    }

    @Test
    @DisplayName("a timed-out receive leaves its consumer to the container, which closes it after the attempt")
    void timedOutReceiveLeavesConsumerOpen() throws Exception {
        MessageConsumer consumer = mock(MessageConsumer.class);

        assertNull(container.receiveMessage(consumer));

        verify(consumer, never()).close();
    }

    @Test
    @DisplayName("an Artemis message has its body read into memory before its consumer is closed")
    void artemisBodyIsCompletedBeforeConsumerCloses() throws Exception {
        MessageConsumer consumer = mock(MessageConsumer.class);
        ClientMessage core = mock(ClientMessage.class);
        ActiveMQMessage message = mock(ActiveMQMessage.class);
        when(message.getCoreMessage()).thenReturn(core);
        when(consumer.receive(anyLong())).thenReturn(message);

        assertSame(message, container.receiveMessage(consumer));

        InOrder order = inOrder(core, consumer);
        order.verify(core).checkCompletion();
        order.verify(consumer).close();
    }

    @Test
    @DisplayName("an Artemis body that cannot be read leaves the consumer open, so the listener meets the failure")
    void unreadableArtemisBodyLeavesConsumerOpen() throws Exception {
        MessageConsumer consumer = mock(MessageConsumer.class);
        ClientMessage core = mock(ClientMessage.class);
        doThrow(new ActiveMQException("large message body interrupted")).when(core).checkCompletion();
        ActiveMQMessage message = mock(ActiveMQMessage.class);
        when(message.getCoreMessage()).thenReturn(core);
        when(consumer.receive(anyLong())).thenReturn(message);

        assertSame(message, container.receiveMessage(consumer));

        verify(consumer, never()).close();
    }
}
