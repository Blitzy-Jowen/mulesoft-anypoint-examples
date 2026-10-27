/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.jms_message_rollback_and_redelivery.client;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.Message;
import jakarta.jms.Session;
import jakarta.jms.StreamMessage;
import jakarta.jms.TextMessage;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jms.JmsException;
import org.springframework.jms.UncategorizedJmsException;
import org.springframework.jms.core.JmsTemplate;

/**
 * Unit tests for {@link TopicPublisher}, the replacement of the
 * {@code <jms:outbound-endpoint topic="topic1">} with {@code <jms:transaction action="ALWAYS_JOIN"/>} of
 * {@code jms-redelivery.xml:35-37}. The payload is the one the original
 * {@code JmsMessageRollbackAndRedeliveryIT} expects to arrive on {@code topic1}.
 */
class TopicPublisherTest {

    private static final String TOPIC = "topic1";

    private static final String MESSAGE = "Message123";

    @Test
    void publishSendsPayloadToConfiguredTopic() {
        JmsTemplate template = mock(JmsTemplate.class);

        new TopicPublisher(template, TOPIC).publish(MESSAGE);

        // eq(String) on the destination selects convertAndSend(String, Object) over (Destination, Object).
        verify(template).convertAndSend(eq(TOPIC), eq((Object) MESSAGE));
    }

    @Test
    void publishPropagatesJmsExceptionUnwrapped() {
        JmsTemplate template = mock(JmsTemplate.class);
        UncategorizedJmsException boom = new UncategorizedJmsException("boom");
        // A typed any(Object.class) keeps (String, Object) apart from (Object, MessagePostProcessor).
        doThrow(boom).when(template).convertAndSend(eq(TOPIC), any(Object.class));
        TopicPublisher publisher = new TopicPublisher(template, TOPIC);

        // RedeliveryService routes on the exception it receives, so the same instance must reach its catch
        // branch rather than a wrapper.
        assertSame(boom, assertThrows(JmsException.class, () -> publisher.publish(MESSAGE)));
    }

    @Test
    void productionConstructorBuildsTransactedPubSubTemplate() {
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);

        TopicPublisher publisher = new TopicPublisher(connectionFactory, TOPIC);

        assertTrue(publisher.template().isPubSubDomain());
        assertTrue(publisher.template().isSessionTransacted());
        assertSame(connectionFactory, publisher.template().getConnectionFactory());
        assertInstanceOf(TopicPublisher.StreamListMessageConverter.class, publisher.template().getMessageConverter());
        // Building the template only checks the factory for null; no connection is opened before a send.
        verifyNoInteractions(connectionFactory);
    }

    @Test
    void converterSendsListAsStreamMessageInElementOrder() throws Exception {
        Session session = mock(Session.class);
        StreamMessage stream = mock(StreamMessage.class);
        when(session.createStreamMessage()).thenReturn(stream);
        byte[] bytes = {1, 2};

        Message converted = new TopicPublisher.StreamListMessageConverter().toMessage(List.of("a", 1, bytes), session);

        assertSame(stream, converted);
        InOrder order = inOrder(stream);
        order.verify(stream).writeObject("a");
        order.verify(stream).writeObject(1);
        order.verify(stream).writeObject(bytes);
        verifyNoMoreInteractions(stream);
    }

    @Test
    void converterLeavesOtherPayloadsToSimpleMessageConverter() throws Exception {
        Session session = mock(Session.class);
        TextMessage text = mock(TextMessage.class);
        when(session.createTextMessage(MESSAGE)).thenReturn(text);
        Message received = mock(Message.class);
        TopicPublisher.StreamListMessageConverter converter = new TopicPublisher.StreamListMessageConverter();

        assertSame(text, converter.toMessage(MESSAGE, session));
        // A JMS Message payload, such as a body-less inbound message, is sent unchanged.
        assertSame(received, converter.toMessage(received, session));
        verify(session, never()).createStreamMessage();
    }
}
