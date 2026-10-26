/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_json_data_to_a_amqp_queue.service;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.sending_json_data_to_a_amqp_queue.config.AmqpConfig;

/**
 * Reproduces the {@code logger} and the request-response {@code amqp:outbound-endpoint} of flow
 * {@code json-to-rabbitmqFlow} (sending-json-data-to-a-amqp-queue/src/main/app/json-to-rabbitmq.xml:8-9),
 * in that order.
 *
 * <p>The exchange and the queue are only checked passively, never declared or bound: the original endpoint
 * sets no exchange type and no queue flags, so Mule's AMQP transport only checked that both existed. They
 * must already exist on the broker, with the queue bound to the exchange, as the original README has the
 * user create them.
 *
 * <p>Nothing is caught, wrapped or retried. Every {@link org.springframework.amqp.AmqpException} (an
 * unreachable broker, a missing exchange or queue, a channel error) reaches the caller unchanged after one
 * attempt, because the original flow declares no exception strategy.
 */
@Service
public class SalesPublisherService {

    private static final Logger log = LoggerFactory.getLogger(SalesPublisherService.class);

    private final RabbitTemplate rabbitTemplate;

    private final AmqpConfig.SalesDestination destination;

    /**
     * @param rabbitTemplate Boot's template, configured by {@link AmqpConfig} with temporary reply queues and
     *                       the endpoint's reply timeout
     * @param destination    the exchange, queue and routing key every publish uses
     */
    public SalesPublisherService(RabbitTemplate rabbitTemplate, AmqpConfig.SalesDestination destination) {
        this.rabbitTemplate = rabbitTemplate;
        this.destination = destination;
    }

    /**
     * Logs the payload, checks that the exchange and the queue exist, publishes the payload request-response
     * and returns the reply body.
     *
     * <p>The bytes are forwarded unchanged and never parsed, because the original forwards bodies that are not
     * valid JSON. The message is persistent, the Mule AMQP connector's default delivery mode, and carries no
     * content type or other property the original did not set.
     *
     * @param payload the request body; not {@code null}, an absent body is an empty array
     * @return the reply body, or {@code null} when no reply arrived within the template's reply timeout
     * @throws org.springframework.amqp.AmqpException when the broker cannot be reached, the exchange or the
     *                                                queue does not exist, or the publish fails
     */
    public byte[] publish(byte[] payload) {
        // The original logger writes the decoded body verbatim (#[new String(payload)]); the same text is
        // logged here, unescaped, so the log line stays identical to the original's.
        log.info("payload to be sent: {}", new String(payload, StandardCharsets.UTF_8));

        rabbitTemplate.execute(channel -> {
            channel.exchangeDeclarePassive(destination.exchangeName());
            channel.queueDeclarePassive(destination.queueName());
            return null;
        });

        Message message = MessageBuilder.withBody(payload)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
        Message reply = rabbitTemplate.sendAndReceive(destination.exchangeName(), destination.routingKey(), message);
        return reply == null ? null : reply.getBody();
    }
}
