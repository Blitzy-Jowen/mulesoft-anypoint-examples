/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.jms_message_rollback_and_redelivery.client;

import java.util.List;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.Session;
import jakarta.jms.StreamMessage;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.support.converter.SimpleMessageConverter;
import org.springframework.stereotype.Component;

/**
 * Publishes the processed message to topic {@code topic1}, replacing the
 * {@code <jms:outbound-endpoint topic="topic1">} with {@code <jms:transaction action="ALWAYS_JOIN"/>} of
 * {@code jms-redelivery.xml:35-37}.
 *
 * <p>The class builds its own pub-sub, session-transacted {@link JmsTemplate} on the Boot
 * {@link ConnectionFactory} bean instead of using the auto-configured {@code jmsTemplate}, which stays a
 * point-to-point template for the tests that send to queue {@code in}. Joining the inbound transaction
 * depends on that factory being the very instance {@code config.JmsConfig} hands to the listener container:
 * with transacted sessions and no transaction manager, the container exposes its session under its
 * {@code ConnectionFactory} as the resource key, and a template on the identical instance sends on that
 * session without committing it. The send therefore commits on the fifth delivery and is discarded with the
 * rollback of deliveries one to four, as {@code ALWAYS_JOIN} did. Called outside a listener, the template
 * opens and commits its own transacted session.
 *
 * <p>Delivery mode and destination resolver keep their defaults: PERSISTENT reproduces the connector's
 * {@code persistentDelivery="true"}, and the topic is resolved by name. The message converter is
 * {@link StreamListMessageConverter}: Spring's {@link SimpleMessageConverter}, which sends a {@code String}
 * payload as a {@code TextMessage} and a JMS {@code Message} payload unchanged, plus the {@code List} rule of
 * Mule's outbound JMS transformer.
 */
@Component
public class TopicPublisher {

    /**
     * {@link SimpleMessageConverter} with the {@code List} rule of Mule's outbound JMS transformer
     * ({@code JmsMessageUtils.toMessage}): a {@code List}, the payload a stream message yields, becomes a
     * {@code StreamMessage} holding its elements in order, where Spring's converter would serialize it into an
     * {@code ObjectMessage}. Every other payload is converted by {@code SimpleMessageConverter}.
     */
    static final class StreamListMessageConverter extends SimpleMessageConverter {

        /**
         * Converts the payload into the message sent to the topic.
         *
         * @throws jakarta.jms.MessageFormatException when a {@code List} element is not a primitive wrapper,
         *                                            {@code String} or {@code byte[]}, the types a stream message
         *                                            can hold
         */
        @Override
        public Message toMessage(Object object, Session session) throws JMSException {
            if (object instanceof List<?> values) {
                StreamMessage message = session.createStreamMessage();
                for (Object value : values) {
                    message.writeObject(value);
                }
                return message;
            }
            return super.toMessage(object, session);
        }
    }

    private final JmsTemplate template;

    private final String topic;

    /**
     * Creates the publisher on the application's JMS connection factory.
     *
     * @param connectionFactory the Boot {@code ConnectionFactory} bean, the same instance the listener
     *                          container uses; it must not be unwrapped or replaced
     * @param topic             the destination topic name, {@code jms.outbound-topic}
     */
    @Autowired
    public TopicPublisher(ConnectionFactory connectionFactory,
                          @Value("${jms.outbound-topic:topic1}") String topic) {
        JmsTemplate t = new JmsTemplate(connectionFactory);
        t.setPubSubDomain(true);
        t.setSessionTransacted(true);
        t.setMessageConverter(new StreamListMessageConverter());
        this.template = t;
        this.topic = topic;
    }

    TopicPublisher(JmsTemplate template, String topic) {
        this.template = template;
        this.topic = topic;
    }

    JmsTemplate template() {
        return template;
    }

    /**
     * Sends the payload to the configured topic, inside the current listener transaction when there is one.
     *
     * @param payload the message payload; a {@code String} is sent as a {@code TextMessage}, a {@code List} as a
     *                {@code StreamMessage}
     * @throws org.springframework.jms.JmsException when the payload cannot be converted or the broker rejects
     *                                              the send; it reaches the caller unwrapped so the flow's
     *                                              exception branches see it
     */
    public void publish(Object payload) {
        template.convertAndSend(topic, payload);
    }
}
