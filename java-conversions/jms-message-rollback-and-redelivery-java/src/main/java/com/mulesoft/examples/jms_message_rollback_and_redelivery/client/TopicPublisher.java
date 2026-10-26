/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.jms_message_rollback_and_redelivery.client;

import jakarta.jms.ConnectionFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
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
 * <p>Delivery mode, message converter and destination resolver keep their defaults: PERSISTENT reproduces the
 * connector's {@code persistentDelivery="true"}, a {@code String} payload is sent as a {@code TextMessage},
 * and the topic is resolved by name.
 */
@Component
public class TopicPublisher {

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
     * @param payload the message payload; a {@code String} is sent as a {@code TextMessage}
     * @throws org.springframework.jms.JmsException when the payload cannot be converted or the broker rejects
     *                                              the send; it reaches the caller unwrapped so the flow's
     *                                              exception branches see it
     */
    public void publish(Object payload) {
        template.convertAndSend(topic, payload);
    }
}
