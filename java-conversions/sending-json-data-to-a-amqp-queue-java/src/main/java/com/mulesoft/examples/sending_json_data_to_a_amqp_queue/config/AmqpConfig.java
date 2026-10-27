/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_json_data_to_a_amqp_queue.config;

import java.util.Objects;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.ConnectionFactoryCustomizer;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateConfigurer;
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
 * <p>The {@link RabbitTemplate} is a {@link CorrelatedReplyRabbitTemplate} declared here and configured exactly
 * as Boot configures its auto-configured one, which backs off. It is declared for security: Spring AMQP's
 * template returns the first message that reaches a temporary reply queue, so any client able to publish to the
 * queue named in the request's {@code reply_to} could substitute the answer returned to the HTTP caller. The
 * subclass gives every request an unpredictable correlation id and returns only the reply that carries it.
 *
 * <p>The connector's {@code validateConnections="true"} is deliberately not reproduced: the connection
 * opens lazily on the first publish so the application starts without a broker, a difference the
 * project README records.
 *
 * <p>Transport budgets are separate from the reply timeout. Before a {@code sendAndReceive} publishes,
 * the client may have to open the TCP connection and a channel, then declares the temporary reply queue
 * and registers its consumer. {@code spring.rabbitmq.connection-timeout} bounds the TCP connect, and
 * {@code spring.rabbitmq.channel-rpc-timeout} bounds each channel RPC ({@code channel.open},
 * {@code queue.declare}, {@code basic.consume}, {@code basic.cancel}); {@code application.yml} sets both
 * to 10 s, and the AMQP handshake keeps amqp-client's 10 s default. A broker that withholds one of those
 * replies fails the call with an {@code AmqpException} once its budget is spent, and nothing retries it.
 * {@code amqp.response-timeout} starts only after the request is published and bounds only the wait
 * for its reply.
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
     *                              ({@code responseTimeout}); must be positive
     * @throws IllegalArgumentException if {@code amqp.response-timeout} is zero or negative, which
     *                                  would make the template wait for a reply without limit (negative)
     *                                  or not at all (zero), so the application refuses to start
     */
    public AmqpConfig(
            @Value("${amqp.exchange-name:sales_exchange}") String exchangeName,
            @Value("${amqp.queue-name:sales_queue}") String queueName,
            @Value("${amqp.response-timeout:10000}") long responseTimeoutMillis) {
        this.exchangeName = exchangeName;
        this.queueName = queueName;
        this.responseTimeoutMillis = requirePositiveTimeout(responseTimeoutMillis, "amqp.response-timeout");
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
     * The application's template: a {@link CorrelatedReplyRabbitTemplate}, built the way Boot's
     * {@code RabbitAutoConfiguration} builds its own {@link RabbitTemplate}, whose bean backs off because
     * it is conditional on no {@code RabbitOperations} bean being present. Boot's configurer first applies
     * {@code spring.rabbitmq.template.*}, the message converter and any retry settings, then every
     * {@link RabbitTemplateCustomizer} runs in order, {@link #salesReplyTemplateCustomizer()} included.
     * Creating the template opens no connection, so the application still starts without a broker.
     *
     * <p>Boot's template is replaced for security, not configuration: on a temporary reply queue it returns
     * the first message that arrives, whoever published it, while this subclass sets a random correlation
     * id on each request and returns only the reply that carries the same id. Any other message on the
     * reply queue is discarded with a WARN line, and the wait goes on until the reply timeout.
     *
     * @param configurer        Boot's configurer of {@code spring.rabbitmq.template.*}
     * @param connectionFactory Boot's auto-configured connection factory
     * @param customizers       the template customizers, applied in order after the configurer
     * @return the template every publish of {@code service/SalesPublisherService} goes through
     */
    @Bean
    public CorrelatedReplyRabbitTemplate rabbitTemplate(RabbitTemplateConfigurer configurer,
            ConnectionFactory connectionFactory, ObjectProvider<RabbitTemplateCustomizer> customizers) {
        CorrelatedReplyRabbitTemplate template = new CorrelatedReplyRabbitTemplate();
        configurer.configure(template, connectionFactory);
        customizers.orderedStream().forEach(customizer -> customizer.customize(template));
        return template;
    }

    /**
     * Request-reply settings for the application's {@link RabbitTemplate}. {@link #rabbitTemplate} applies
     * template customizers after Boot's {@code spring.rabbitmq.template.*} configuration, so these two
     * settings are authoritative; the exchange and routing key are left unset because the service passes
     * both on every call.
     *
     * <ul>
     *   <li>Temporary reply queues: every {@code sendAndReceive} declares a server-named, exclusive,
     *       auto-delete queue and sets it as {@code replyTo}, as Mule's request-response endpoint did
     *       with {@code channel.queueDeclare()}, together with the correlation id the reply must carry.
     *       This also keeps the template from probing direct reply-to ({@code amq.rabbitmq.reply-to}),
     *       which only RabbitMQ provides and the Qpid Broker-J test broker lacks.</li>
     *   <li>Reply timeout: the endpoint's {@code responseTimeout}, always positive because the
     *       constructor rejects zero and negative values. It starts once the request is published and
     *       bounds only the wait for the reply; when no reply arrives in time, {@code sendAndReceive}
     *       returns {@code null} instead of throwing.</li>
     * </ul>
     *
     * @return the customizer {@link #rabbitTemplate} applies to the template
     */
    @Bean
    public RabbitTemplateCustomizer salesReplyTemplateCustomizer() {
        return (RabbitTemplate template) -> {
            template.setUseTemporaryReplyQueues(true);
            template.setReplyTimeout(responseTimeoutMillis);
        };
    }

    /**
     * Refuses to start when Boot's connection factory would wait for the broker without limit. Boot
     * applies connection factory customizers to the native RabbitMQ factory while it builds its
     * auto-configured connection factory, after copying {@code spring.rabbitmq.*} onto it and before any
     * connection is opened, so this check keeps the lazy connection. It only reads the two timeouts: their
     * values come from {@code spring.rabbitmq.connection-timeout} and
     * {@code spring.rabbitmq.channel-rpc-timeout}, and it adds no retry or recovery. amqp-client already
     * rejects negative values; zero is the one it accepts as "wait forever", so the customizer throws
     * {@link IllegalStateException} for a zero timeout and the context fails to start.
     *
     * @return the customizer Boot applies to its native connection factory
     */
    @Bean
    public ConnectionFactoryCustomizer salesTransportTimeoutValidator() {
        return factory -> {
            if (factory.getConnectionTimeout() <= 0) {
                throw new IllegalStateException("spring.rabbitmq.connection-timeout must be a positive duration,"
                        + " but was " + factory.getConnectionTimeout() + " ms, which waits for the TCP connect"
                        + " without limit");
            }
            if (factory.getChannelRpcTimeout() <= 0) {
                throw new IllegalStateException("spring.rabbitmq.channel-rpc-timeout must be a positive duration,"
                        + " but was " + factory.getChannelRpcTimeout() + " ms, which waits for every channel RPC"
                        + " reply without limit");
            }
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
     * @param responseTimeoutMillis how long a publish waits for its reply, in milliseconds; positive
     */
    public record SalesDestination(String exchangeName, String queueName, String routingKey,
            long responseTimeoutMillis) {

        /**
         * Rejects a missing exchange, queue or routing key, and a reply timeout that is not positive.
         *
         * @param exchangeName          the exchange every publish goes to
         * @param queueName             the queue bound to the exchange on the broker
         * @param routingKey            the routing key of every publish
         * @param responseTimeoutMillis how long a publish waits for its reply, in milliseconds
         * @throws NullPointerException     if {@code exchangeName}, {@code queueName} or
         *                                  {@code routingKey} is {@code null}
         * @throws IllegalArgumentException if {@code responseTimeoutMillis} is zero or negative
         */
        public SalesDestination {
            Objects.requireNonNull(exchangeName, "exchangeName");
            Objects.requireNonNull(queueName, "queueName");
            Objects.requireNonNull(routingKey, "routingKey");
            requirePositiveTimeout(responseTimeoutMillis, "responseTimeoutMillis");
        }
    }

    /**
     * Rejects a reply timeout that is not positive. {@link RabbitTemplate#setReplyTimeout(long)} accepts
     * any value, but a negative one makes {@code sendAndReceive} wait for its reply without limit and
     * zero makes it give up before any reply can arrive.
     *
     * @param timeoutMillis the timeout, in milliseconds
     * @param name          the configuration key or record component the value came from, for the message
     * @return {@code timeoutMillis}, unchanged
     * @throws IllegalArgumentException if {@code timeoutMillis} is zero or negative
     */
    private static long requirePositiveTimeout(long timeoutMillis, String name) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException(
                    name + " must be a positive number of milliseconds, but was " + timeoutMillis);
        }
        return timeoutMillis;
    }
}
