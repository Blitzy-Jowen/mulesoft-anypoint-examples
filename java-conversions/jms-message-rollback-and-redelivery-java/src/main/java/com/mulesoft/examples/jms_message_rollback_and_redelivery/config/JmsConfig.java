/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.jms_message_rollback_and_redelivery.config;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.Session;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jms.DefaultJmsListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;

/**
 * Listener container for queue {@code in}, replacing the JMS connector and the transacted inbound
 * endpoint of {@code jms-redelivery.xml} (lines 13-24).
 *
 * <ul>
 *   <li>Lines 13-16, {@code ActiveMQConnectionFactory} on {@code failover:(tcp://localhost:61616)}: no
 *       connection factory is declared here. Boot's Artemis auto-configuration builds it from
 *       {@code spring.artemis.broker-url}.</li>
 *   <li>Line 19, {@code jms:activemq-connector}: it declares no {@code numberOfConsumers}, so the Mule JMS
 *       connector's default of 4 consumers applies, fixed here as minimum and maximum
 *       ({@code jms.concurrent-consumers}). Its {@code maxRedelivery="5"} is a broker-side setting on
 *       address {@code in} ({@code max-delivery-attempts} 6), not a container setting.</li>
 *   <li>Lines 22-24, {@code jms:inbound-endpoint queue="in"} with {@code ALWAYS_BEGIN}: every receive runs
 *       in a locally transacted session that commits when the listener returns and rolls back when it
 *       throws, which makes the broker redeliver.</li>
 * </ul>
 *
 * <p>Session sharing with {@code client/TopicPublisher}, which reproduces the {@code ALWAYS_JOIN} send to
 * {@code topic1} (lines 35-37): with no transaction manager and {@code exposeListenerSession} left
 * {@code true}, the container binds its transacted session as a transactional resource keyed by its own
 * {@code ConnectionFactory} while the listener runs. {@code TopicPublisher}'s {@code JmsTemplate} is built
 * on the same Boot {@code ConnectionFactory} bean, so it finds that session, sends on it and leaves the
 * commit or rollback to the container. Both rules are therefore load-bearing: the factory is passed to the
 * container exactly as injected, never unwrapped, and no transaction manager is set, because either change
 * gives the publisher a session of its own whose sends survive a rollback of the receive.
 *
 * <p>No error handler or back-off is configured. A listener failure rolls the session back, and the
 * 5000/2000 ms redelivery timing comes from {@code trigger/RedeliveryDelay}, which pauses inside the
 * failing transaction, together with the broker's address settings. As with ActiveMQ's default blocking
 * redelivery, the consumer serving that delivery takes no other message during the pause.
 */
@Configuration
public class JmsConfig {

    /** Bean name of the container factory; {@code @JmsListener(containerFactory = ...)} refers to it. */
    public static final String IN_QUEUE_LISTENER_FACTORY = "inQueueListenerContainerFactory";

    /**
     * Container factory for the listener on queue {@code in}.
     *
     * <p>The settings applied after {@code configure} take precedence over any {@code spring.jms.listener.*}
     * or {@code spring.jms.pub-sub-domain} value Boot's configurer copies onto the factory.
     *
     * @param configurer          Boot's configurer, which applies the shared JMS settings and the
     *                            injected connection factory
     * @param connectionFactory   Boot's {@code ConnectionFactory} bean, the same instance
     *                            {@code TopicPublisher} sends through
     * @param concurrentConsumers number of consumers, used as both minimum and maximum
     * @return the session-transacted, point-to-point listener container factory
     */
    @Bean(IN_QUEUE_LISTENER_FACTORY)
    public DefaultJmsListenerContainerFactory inQueueListenerContainerFactory(
            DefaultJmsListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            @Value("${jms.concurrent-consumers:4}") int concurrentConsumers) {
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setSessionTransacted(true);
        factory.setSessionAcknowledgeMode(Session.SESSION_TRANSACTED);
        factory.setPubSubDomain(false);
        // "n-n" fixes the consumer count; a bare "n" would let the container scale between 1 and n.
        factory.setConcurrency(concurrentConsumers + "-" + concurrentConsumers);
        return factory;
    }
}
