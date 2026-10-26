/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_json_data_to_a_amqp_queue.config;

import java.util.Objects;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Destination and request-reply settings of the request-response {@code amqp:outbound-endpoint} of
 * {@code json-to-rabbitmq.xml} (line 9): exchange {@code sales_exchange}, queue {@code sales_queue},
 * no routing key and a {@code responseTimeout} of 10000 ms.
 *
 * <p>The broker connection itself is Boot's auto-configured one, built from {@code spring.rabbitmq.*}
 * ({@code spring.rabbitmq.host} defaults to {@code amqp.host}), which carries the Mule AMQP connector
 * defaults the original relied on (line 3 sets no host, port, user or virtual host). This class
 * declares no exchange, queue or binding, so Boot's {@code RabbitAdmin} has nothing to declare: the
 * original only used {@code sales_exchange} and {@code sales_queue} passively, and its README has the
 * user create and bind both by hand.
 *
 * <p>The connector's {@code validateConnections="true"} is deliberately not reproduced: the connection
 * opens lazily on the first publish so the application starts without a broker, a difference the
 * project README records.
 */
@Configuration(proxyBeanMethods = false)
public class AmqpConfig {

    /**
     * Routing key of every publish. The original endpoint sets no {@code routingKey}, so Mule's AMQP
     * transport published to {@code sales_exchange} with an empty routing key.
     */
    public static final String DEFAULT_ROUTING_KEY = "";

    private final String exchangeName;

    private final String queueName;

    private final long responseTimeoutMillis;

    /**
     * Binds the endpoint settings. Each default repeats the value the original endpoint fixed, so the
     * class keeps the original destination when a key is absent from the configuration.
     *
     * @param exchangeName          the exchange published to ({@code exchangeName})
     * @param queueName             the queue the original endpoint names ({@code queueName})
     * @param responseTimeoutMillis how long a publish waits for its reply, in milliseconds
     *                              ({@code responseTimeout})
     */
    public AmqpConfig(
            @Value("${amqp.exchange-name:sales_exchange}") String exchangeName,
            @Value("${amqp.queue-name:sales_queue}") String queueName,
            @Value("${amqp.response-timeout:10000}") long responseTimeoutMillis) {
        this.exchangeName = exchangeName;
        this.queueName = queueName;
        this.responseTimeoutMillis = responseTimeoutMillis;
    }

    /**
     * The destination {@code service/SalesPublisherService} publishes to.
     *
     * @return the configured exchange and queue, the empty routing key and the reply timeout
     */
    @Bean
    public SalesDestination salesDestination() {
        return new SalesDestination(exchangeName, queueName, DEFAULT_ROUTING_KEY, responseTimeoutMillis);
    }

    /**
     * Request-reply settings for Boot's {@link RabbitTemplate}. Boot applies template customizers after
     * its own {@code spring.rabbitmq.template.*} configuration, so these two settings are authoritative;
     * the exchange and routing key are left unset because the service passes both on every call.
     *
     * <ul>
     *   <li>Temporary reply queues: every {@code sendAndReceive} declares a server-named, exclusive,
     *       auto-delete queue and sets it as {@code replyTo}, as Mule's request-response endpoint did
     *       with {@code channel.queueDeclare()}. This also keeps the template from probing direct
     *       reply-to ({@code amq.rabbitmq.reply-to}), which only RabbitMQ provides and the Qpid
     *       Broker-J test broker lacks.</li>
     *   <li>Reply timeout: the endpoint's {@code responseTimeout}. When no reply arrives in time,
     *       {@code sendAndReceive} returns {@code null} instead of throwing.</li>
     * </ul>
     *
     * @return the customizer Boot applies to its auto-configured template
     */
    @Bean
    public RabbitTemplateCustomizer salesReplyTemplateCustomizer() {
        return (RabbitTemplate template) -> {
            template.setUseTemporaryReplyQueues(true);
            template.setReplyTimeout(responseTimeoutMillis);
        };
    }

    /**
     * The only destination contract {@code service/SalesPublisherService} consumes. The publish goes to
     * {@code exchangeName} with {@code routingKey}; {@code queueName} is the queue the original endpoint
     * names, which the broker is expected to have bound to the exchange.
     *
     * @param exchangeName          the exchange every publish goes to
     * @param queueName             the queue bound to the exchange on the broker
     * @param routingKey            the routing key of every publish
     * @param responseTimeoutMillis how long a publish waits for its reply, in milliseconds
     */
    public record SalesDestination(String exchangeName, String queueName, String routingKey,
            long responseTimeoutMillis) {

        /**
         * Rejects a missing exchange, queue or routing key.
         *
         * @throws NullPointerException if {@code exchangeName}, {@code queueName} or {@code routingKey}
         *                              is {@code null}
         */
        public SalesDestination {
            Objects.requireNonNull(exchangeName, "exchangeName");
            Objects.requireNonNull(queueName, "queueName");
            Objects.requireNonNull(routingKey, "routingKey");
        }
    }
}
