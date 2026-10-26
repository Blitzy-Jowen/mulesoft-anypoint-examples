/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_json_data_to_a_amqp_queue.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.sending_json_data_to_a_amqp_queue.config.AmqpConfig;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ShutdownSignalException;

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
 * <p>Nothing is wrapped or retried. Every {@link org.springframework.amqp.AmqpException} (an unreachable
 * broker, a missing exchange or queue, a channel error) reaches the caller unchanged after one attempt,
 * because the original flow declares no exception strategy. A failed passive check is logged at ERROR before
 * its exception is rethrown: the line names the exchange or queue checked and the broker's reply code and
 * reply text (for a missing exchange, {@code reply-code=404, reply-text=NOT_FOUND - no exchange
 * 'sales_exchange' in vhost '/'}), or the failure's cause chain when the broker gave no reason. Spring AMQP
 * logs a channel closed by a passive declaration only at DEBUG, and the exception's own message is the bare
 * {@code java.io.IOException}, so without this line a missing exchange, a missing queue and a refused access
 * would be indistinguishable.
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
     *                                                queue does not exist, or the publish fails; a failed
     *                                                passive check is logged at ERROR with the broker's
     *                                                reason first, and its exception is not changed
     */
    public byte[] publish(byte[] payload) {
        // The original logger writes the decoded body verbatim (#[new String(payload)]); the same text is
        // logged here, unescaped, so the log line stays identical to the original's.
        log.info("payload to be sent: {}", new String(payload, StandardCharsets.UTF_8));

        rabbitTemplate.execute(channel -> {
            try {
                channel.exchangeDeclarePassive(destination.exchangeName());
            } catch (IOException ex) {
                log.error("Passive check of exchange '{}' failed: {}", destination.exchangeName(),
                        describePassiveCheckFailure(ex));
                throw ex;
            }
            try {
                channel.queueDeclarePassive(destination.queueName());
            } catch (IOException ex) {
                log.error("Passive check of queue '{}' failed: {}", destination.queueName(),
                        describePassiveCheckFailure(ex));
                throw ex;
            }
            return null;
        });

        // MessageProperties defaults the content type to application/octet-stream and the priority to 0, and
        // both would be published; the original endpoint set neither, so both are cleared.
        Message message = MessageBuilder.withBody(payload)
                .setContentType(null)
                .setPriority(null)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
        Message reply = rabbitTemplate.sendAndReceive(destination.exchangeName(), destination.routingKey(), message);
        return reply == null ? null : reply.getBody();
    }

    /**
     * Describes why a passive declaration failed. amqp-client raises an {@link IOException} without a message
     * whose cause chain holds the {@link ShutdownSignalException} that closed the channel; its reason is the
     * {@code channel.close} (or {@code connection.close}) carrying the reply code and text that name the actual
     * problem. The broker sends that close, except when the application closed the channel or connection
     * itself, for example while its connection factory is destroyed mid-request, and amqp-client marks the
     * signal as initiated by the application.
     *
     * @param failure what the passive declaration threw
     * @return {@code the broker closed the channel with reply-code=<code>, reply-text=<text>} (or
     *         {@code connection}) for the first close in the cause chain, worded {@code the application closed
     *         ...} instead when {@link ShutdownSignalException#isInitiatedByApplication()} is {@code true};
     *         otherwise every throwable of the chain, each as its {@code toString()}, joined by
     *         {@code " <- "}. A cause seen before ends the walk, so a cyclic chain cannot loop
     */
    private static String describePassiveCheckFailure(IOException failure) {
        StringBuilder chain = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof ShutdownSignalException signal) {
                String closedBy = signal.isInitiatedByApplication() ? "the application" : "the broker";
                if (signal.getReason() instanceof AMQP.Channel.Close close) {
                    return closedBy + " closed the channel with reply-code=" + close.getReplyCode()
                            + ", reply-text=" + close.getReplyText();
                }
                if (signal.getReason() instanceof AMQP.Connection.Close close) {
                    return closedBy + " closed the connection with reply-code=" + close.getReplyCode()
                            + ", reply-text=" + close.getReplyText();
                }
            }
            if (chain.length() > 0) {
                chain.append(" <- ");
            }
            chain.append(current);
        }
        return chain.toString();
    }
}
