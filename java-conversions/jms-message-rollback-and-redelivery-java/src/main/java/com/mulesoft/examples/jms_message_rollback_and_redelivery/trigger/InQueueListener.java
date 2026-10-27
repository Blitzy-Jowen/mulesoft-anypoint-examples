/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.jms_message_rollback_and_redelivery.trigger;

import com.mulesoft.examples.jms_message_rollback_and_redelivery.config.JmsConfig;
import com.mulesoft.examples.jms_message_rollback_and_redelivery.exception.MyException;
import com.mulesoft.examples.jms_message_rollback_and_redelivery.service.RedeliveryService;

import java.util.ArrayList;
import java.util.List;

import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.MapMessage;
import jakarta.jms.Message;
import jakarta.jms.MessageEOFException;
import jakarta.jms.ObjectMessage;
import jakarta.jms.StreamMessage;
import jakarta.jms.TextMessage;

import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

/**
 * Inbound endpoint of flow {@code JMSRedeliver}: {@code <jms:inbound-endpoint queue="in">} with
 * {@code <jms:transaction action="ALWAYS_BEGIN"/>} ({@code jms-redelivery.xml}, lines 21-24).
 *
 * <p>Every delivery runs in the locally transacted session of the container built by
 * {@link JmsConfig#IN_QUEUE_LISTENER_FACTORY}: the session commits when {@link #onMessage(Message)} returns and
 * rolls back when it throws, which undoes the receive together with any {@code topic1} send made on that session,
 * and the broker redelivers.
 *
 * <p>The class only binds the message to the flow's input and hands it on. The choice on
 * {@code JMSXDeliveryCount}, the send to {@code topic1}, the logging and both exception branches belong to
 * {@link RedeliveryService}; the timing of the redelivery belongs to {@link RedeliveryDelay}.
 *
 * <p>{@link RedeliveryService#process(Object, int)} describes what a hard crash during the pause before a rollback
 * does to the delivery count.
 */
@Component
public class InQueueListener {

    private final RedeliveryService redeliveryService;

    private final RedeliveryDelay redeliveryDelay;

    /**
     * Creates the listener.
     *
     * @param redeliveryService the body of flow {@code JMSRedeliver}
     * @param redeliveryDelay   the pause that precedes the rollback of a failed delivery
     */
    public InQueueListener(RedeliveryService redeliveryService, RedeliveryDelay redeliveryDelay) {
        this.redeliveryService = redeliveryService;
        this.redeliveryDelay = redeliveryDelay;
    }

    /**
     * Receives one delivery from queue {@code in} and runs the flow on its payload and delivery count.
     *
     * <p>The payload follows Mule's inbound JMS transformer ({@code JmsMessageUtils.toObject}): the body of a
     * text, bytes, map or object message; the values of a stream message, read in order up to its end or its
     * first {@code null} value, as a {@code List}; and the message itself for a message of any other type, such
     * as a body-less {@link Message}, which is then published unchanged.
     *
     * <p>Reading the payload or the {@code JMSXDeliveryCount} property is part of the flow, as the transformer
     * was in Mule, so a failure there goes to the flow's choice-exception strategy
     * ({@link RedeliveryService#handleFailure(Exception)}); not caused by {@link MyException}, it takes the catch
     * branch and the delivery commits. Should that strategy take its rollback branch instead, its
     * {@code MyException} propagates without a pause, because no delivery count is known.
     *
     * <p>When the flow takes its rollback branch, the listener pauses for the redelivery delay while the
     * transacted session is still open and then rethrows the same {@link MyException}, so the container rolls the
     * session back and the next delivery starts after the original ActiveMQ policy's interval (5000 ms after the
     * first delivery, 2000 ms after each later one). Any other exception the flow lets escape propagates
     * unchanged and without a pause.
     *
     * @param message the delivered message
     * @throws MyException when the flow takes its rollback branch, so the delivery is rolled back and redelivered
     */
    @JmsListener(destination = "${jms.inbound-queue:in}", containerFactory = JmsConfig.IN_QUEUE_LISTENER_FACTORY)
    public void onMessage(Message message) throws MyException {
        Object payload;
        int deliveryCount;
        try {
            payload = toPayload(message);
            // JMS-defined property, counted from 1 by Artemis as by ActiveMQ, so the flow's "== 5" holds on the
            // fifth delivery.
            deliveryCount = message.getIntProperty("JMSXDeliveryCount");
        } catch (JMSException | RuntimeException e) {
            redeliveryService.handleFailure(e);
            return;
        }
        try {
            redeliveryService.process(payload, deliveryCount);
        } catch (MyException e) {
            // The pause must precede the throw: the rollback, and with it the broker's redelivery, starts only once
            // the exception leaves this method.
            redeliveryDelay.pauseBeforeRollback(deliveryCount);
            throw e;
        }
    }

    /**
     * The flow's payload for a delivered message, as Mule's {@code JmsMessageUtils.toObject} derived it.
     *
     * @param message the delivered message
     * @return the body of a text, bytes, map or object message, the values of a stream message, or the message
     *         itself for any other type
     * @throws JMSException when the body cannot be read, for example an object message whose class cannot be
     *                      deserialized
     */
    private static Object toPayload(Message message) throws JMSException {
        if (message instanceof StreamMessage stream) {
            return readStream(stream);
        }
        if (message instanceof TextMessage || message instanceof BytesMessage || message instanceof MapMessage
                || message instanceof ObjectMessage) {
            return message.getBody(Object.class);
        }
        return message;
    }

    /**
     * The values of a stream message in the order they were written, ending before its first {@code null} value
     * or at the end of the stream, whichever comes first, as Mule read them.
     *
     * @param stream the delivered stream message
     * @return the values read, possibly none
     * @throws JMSException when a value cannot be read
     */
    private static List<Object> readStream(StreamMessage stream) throws JMSException {
        List<Object> values = new ArrayList<>();
        while (true) {
            Object value;
            try {
                value = stream.readObject();
            } catch (MessageEOFException endOfStream) {
                return values;
            }
            if (value == null) {
                return values;
            }
            values.add(value);
        }
    }
}
