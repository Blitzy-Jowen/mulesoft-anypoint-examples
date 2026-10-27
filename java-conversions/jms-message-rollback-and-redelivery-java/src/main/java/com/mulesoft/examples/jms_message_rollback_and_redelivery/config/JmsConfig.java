/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.jms_message_rollback_and_redelivery.config;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import org.apache.activemq.artemis.api.core.ActiveMQException;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.activemq.artemis.jms.client.ActiveMQMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.jms.DefaultJmsListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.connection.SingleConnectionFactory;
import org.springframework.jms.listener.DefaultMessageListenerContainer;

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
 * failing transaction, together with the broker's address settings, a zero consumer window on the
 * Artemis connection factory ({@link #zeroConsumerWindowPostProcessor()}) and a message consumer per receive
 * that is closed before the listener runs ({@link ConsumerClosingListenerContainer}). As with ActiveMQ's default
 * blocking redelivery, the consumer thread serving that delivery takes no other message during the pause: its
 * session holds no consumer until the delivery is committed or rolled back.
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
     * <p>Every delivery is served by a {@code MessageConsumer} of its own, which is closed before the listener
     * runs ({@link ConsumerClosingListenerContainer}). The cache level is therefore {@code CACHE_SESSION} instead
     * of the {@code CACHE_CONSUMER} the container picks when no transaction manager is set: each consumer thread
     * keeps its transacted session and the shared connection but opens a new consumer for every receive. Boot's
     * {@code CachingConnectionFactory} does not cache consumers ({@code spring.jms.cache.consumers} is
     * {@code false} by default), so each close reaches the broker. The listener session is still bound under the
     * same {@code ConnectionFactory} while the listener runs, so {@code TopicPublisher} keeps joining it.
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
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory() {
            @Override
            protected DefaultMessageListenerContainer createContainerInstance() {
                return new ConsumerClosingListenerContainer();
            }
        };
        configurer.configure(factory, connectionFactory);
        factory.setSessionTransacted(true);
        factory.setSessionAcknowledgeMode(Session.SESSION_TRANSACTED);
        factory.setPubSubDomain(false);
        // "n-n" fixes the consumer count; a bare "n" would let the container scale between 1 and n.
        factory.setConcurrency(concurrentConsumers + "-" + concurrentConsumers);
        // A new MessageConsumer per receive on the cached transacted session, so the container may close it.
        factory.setCacheLevel(DefaultMessageListenerContainer.CACHE_SESSION);
        return factory;
    }

    /**
     * Listener container that closes the {@code MessageConsumer} of a receive as soon as the receive has returned a
     * message, before the listener runs, so the session serving a delivery has no consumer while the listener
     * processes it and pauses before a rollback.
     *
     * <p>The zero consumer window alone does not keep a pausing consumer free of further messages. A receive that
     * times out withdraws its one message of credit, and the broker's reset to 0 also wipes out the charge for a
     * message it dispatched at that moment. The Artemis client then makes one forced-delivery attempt within the
     * same {@code receive(timeout)} call and returns that message, after which the consumer holds credit for
     * another one: the broker would place a rolled-back or newly sent message behind the listener's pause and
     * stretch its redelivery gap. Once the consumer is closed the broker cannot dispatch to it, and a message
     * dispatched to it before the close goes back to the queue unprocessed, where a free consumer takes it.
     *
     * <p>Closing the consumer keeps the received message in the session's transaction: the Artemis client sends
     * the message's acknowledgement, which belongs to that transaction, before it closes the consumer, so the
     * container's commit or rollback still applies to it. The body of an Artemis large message is read into memory
     * first, because it would otherwise be streamed through the consumer when the listener reads it.
     *
     * <p>The container must open a consumer per receive, cache level {@code CACHE_SESSION} or lower; a cached
     * consumer would stay closed.
     */
    static class ConsumerClosingListenerContainer extends DefaultMessageListenerContainer {

        /**
         * Receives a message and, when one arrives, closes the consumer that received it.
         *
         * @param consumer the consumer opened for this receive
         * @return the received message, or {@code null} when the receive timed out
         * @throws JMSException when the receive or the close fails; the container recovers as from any failed
         *                      receive
         */
        @Override
        protected Message receiveMessage(MessageConsumer consumer) throws JMSException {
            Message message = super.receiveMessage(consumer);
            if (message != null && bodyComplete(message)) {
                consumer.close();
            }
            return message;
        }

        /**
         * Reads the whole body of an Artemis large message into memory; the body of any other message is complete
         * on receipt.
         *
         * @param message the received message
         * @return {@code false} when a large body cannot be read, so the consumer is left open and the listener
         *         meets that failure when it reads the body, exactly as it would with an open consumer
         */
        private static boolean bodyComplete(Message message) {
            if (message instanceof ActiveMQMessage artemis) {
                try {
                    artemis.getCoreMessage().checkCompletion();
                } catch (ActiveMQException e) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Sets {@code consumerWindowSize} to 0 on the Artemis {@code ActiveMQConnectionFactory} behind Boot's
     * {@code ConnectionFactory} bean: the bean itself, or the target of the {@code CachingConnectionFactory}
     * Boot wraps it in.
     *
     * <p>The pause before rollback blocks the listener thread inside its open transaction. A consumer with a
     * window keeps prefetched messages while it waits, so rolled-back and newly sent messages queue up behind
     * the pause instead of reaching an idle consumer, and the 5000/2000 ms redelivery gaps stretch. With a zero
     * window the consumer requests one message per receive, which reproduces ActiveMQ's blocking redelivery: a
     * paused consumer takes no other message, and every waiting message goes to a consumer free to take it.
     *
     * <p>The window works together with {@link ConsumerClosingListenerContainer}, which closes each receive's
     * consumer before the listener runs, because the credit reset that ends a timed-out receive can leave a
     * consumer with credit for one more message. The window in turn keeps a new consumer from prefetching every
     * waiting message, all but one of which its close would hand back to the queue.
     *
     * <p>The factory is changed in place and every bean is returned as the very instance passed in, so the
     * listener container and {@code TopicPublisher} still share one {@code ConnectionFactory} as the session
     * key. The setting is applied while the bean is initialized, before any connection exists, because Artemis
     * rejects the change once the factory has opened one. It concerns consumers only, so the sends to
     * {@code topic1} are unaffected, and it is set in code rather than in {@code spring.artemis.broker-url}, so
     * it holds whatever broker URL is configured.
     *
     * <p>The method is {@code static} so that registering the post-processor does not instantiate this
     * configuration class early.
     *
     * @return the post-processor that zeroes the consumer window of the Artemis connection factory and leaves
     *         every other bean untouched
     */
    @Bean
    public static BeanPostProcessor zeroConsumerWindowPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                // CachingConnectionFactory inherits getTargetConnectionFactory() from SingleConnectionFactory.
                Object target = bean instanceof SingleConnectionFactory single
                        ? single.getTargetConnectionFactory()
                        : bean;
                if (target instanceof ActiveMQConnectionFactory artemis) {
                    artemis.setConsumerWindowSize(0);
                }
                return bean;
            }
        };
    }
}
