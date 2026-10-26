/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_json_data_to_a_jms_queue.config;

import jakarta.jms.ConnectionFactory;

import org.apache.activemq.artemis.api.core.client.ActiveMQClient;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jms.connection.SingleConnectionFactory;

/**
 * Bounds how long the Artemis JMS client waits for the broker to answer a blocking call, so an unresponsive broker
 * fails {@code POST /sales} after {@code jms.call-timeout} milliseconds (10000 by default) instead of Artemis' own
 * 30000 ms.
 *
 * <p>Why: the send to queue {@code sales} runs on the caller's thread, and each blocking client call (opening the
 * connection, creating the session and producer, checking the destination) waits for the broker's response for the
 * factory's {@code callTimeout}. Neither Boot nor {@code application.yml} sets one, so Artemis'
 * {@link ActiveMQClient#DEFAULT_CALL_TIMEOUT} of 30000 ms applied, and a broker that accepts the connection but
 * stops answering held every request for 30 s. 10 s is the Mule default response timeout. Boot 3.2 offers no
 * customizer for the Artemis client connection factory, and a {@code ?callTimeout=} default in the broker URL would
 * be lost whenever the URL is overridden, so the bound is applied to the factory bean itself.
 *
 * <p>What it touches: only the {@code callTimeout} of an {@link ActiveMQConnectionFactory}, either a bean of that
 * type (Boot's {@code jmsConnectionFactory} when {@code spring.jms.cache.enabled} is {@code false}) or the target of
 * a {@link SingleConnectionFactory} bean such as Boot's default caching {@code jmsConnectionFactory}. The value is
 * set before the factory opens its first connection, and only while it still holds Artemis' 30000 ms default, so a
 * broker URL that sets another {@code callTimeout} keeps its own value; a URL that sets exactly 30000 cannot be
 * told apart from the default, and {@code jms.call-timeout: 30000} restores Artemis' value instead. It applies in
 * both {@code spring.artemis.mode}s; every other bean passes through unchanged.
 *
 * <p>It never retries: a call that times out fails with a {@code JMSException} caused by the client's
 * {@code ActiveMQConnectionTimedOutException}, which reaches the caller through the normal JMS exception path exactly
 * as any other send failure does. Nothing here reconnects, resends or changes the reconnect settings, so a timed-out
 * send is never repeated silently.
 */
@Configuration(proxyBeanMethods = false)
public class ArtemisClientConfig {

    /** Configuration key of the bound, in milliseconds. */
    public static final String CALL_TIMEOUT_PROPERTY = "jms.call-timeout";

    /** The bound used when {@value #CALL_TIMEOUT_PROPERTY} is absent: the Mule default response timeout. */
    public static final long DEFAULT_CALL_TIMEOUT_MILLIS = 10_000L;

    /**
     * The post-processor that applies {@code jms.call-timeout} to the Artemis client factory. The method is static,
     * so Spring creates the post-processor without instantiating this configuration class and every other bean stays
     * eligible for all post-processors. The value is read from the {@link Environment} rather than through
     * {@code @Value}, because post-processors are created before a context is guaranteed to resolve placeholders.
     *
     * @param environment the environment holding {@value #CALL_TIMEOUT_PROPERTY}, in milliseconds; absent means
     *                    10000
     * @return the post-processor
     * @throws IllegalArgumentException if the value is zero or negative, so the application refuses to start
     * @throws org.springframework.core.convert.ConversionException if the value is not a whole number of
     *                                  milliseconds, which also stops the start
     */
    @Bean
    public static BeanPostProcessor artemisCallTimeoutPostProcessor(Environment environment) {
        long callTimeoutMillis =
                environment.getProperty(CALL_TIMEOUT_PROPERTY, Long.class, DEFAULT_CALL_TIMEOUT_MILLIS);
        return new CallTimeoutPostProcessor(requirePositiveTimeout(callTimeoutMillis));
    }

    /**
     * Rejects a bound that is not positive. Artemis accepts any value, but with zero or a negative value every
     * blocking call times out before the broker can answer, so no message could ever be sent.
     *
     * @param timeoutMillis the configured bound, in milliseconds
     * @return {@code timeoutMillis}, unchanged
     * @throws IllegalArgumentException if {@code timeoutMillis} is zero or negative
     */
    private static long requirePositiveTimeout(long timeoutMillis) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException(CALL_TIMEOUT_PROPERTY
                    + " must be a positive number of milliseconds, but was " + timeoutMillis);
        }
        return timeoutMillis;
    }

    /**
     * Sets the bound on each Artemis client factory a bean is or wraps, and returns every bean unchanged in
     * identity.
     */
    private static final class CallTimeoutPostProcessor implements BeanPostProcessor {

        private final long callTimeoutMillis;

        private CallTimeoutPostProcessor(long callTimeoutMillis) {
            this.callTimeoutMillis = callTimeoutMillis;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            ActiveMQConnectionFactory factory = artemisFactoryOf(bean);
            if (factory != null && factory.getCallTimeout() == ActiveMQClient.DEFAULT_CALL_TIMEOUT) {
                factory.setCallTimeout(callTimeoutMillis);
            }
            return bean;
        }

        /**
         * The Artemis client factory {@code bean} is, or the one a {@link SingleConnectionFactory} bean (Boot's
         * {@code CachingConnectionFactory} included) delegates to; {@code null} for any other bean, including a
         * single-connection factory without a target or with a target of another vendor.
         */
        private static ActiveMQConnectionFactory artemisFactoryOf(Object bean) {
            if (bean instanceof ActiveMQConnectionFactory artemis) {
                return artemis;
            }
            if (bean instanceof SingleConnectionFactory single) {
                ConnectionFactory target = single.getTargetConnectionFactory();
                if (target instanceof ActiveMQConnectionFactory artemis) {
                    return artemis;
                }
            }
            return null;
        }
    }
}
