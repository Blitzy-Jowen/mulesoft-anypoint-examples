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

import jakarta.jms.JMSException;
import jakarta.jms.Message;

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
     * Receives one delivery from queue {@code in} and runs the flow on its body and delivery count.
     *
     * <p>When the flow takes its rollback branch, the listener pauses for the redelivery delay while the
     * transacted session is still open and then rethrows the same {@link MyException}, so the container rolls the
     * session back and the next delivery starts after the original ActiveMQ policy's interval (5000 ms after the
     * first delivery, 2000 ms after each later one). Any other exception the flow lets escape propagates
     * unchanged and without a pause.
     *
     * @param message the delivered message; a text message yields its {@code String} body
     * @throws JMSException when the body or the {@code JMSXDeliveryCount} property cannot be read
     * @throws MyException  when the flow takes its rollback branch, so the delivery is rolled back and redelivered
     */
    @JmsListener(destination = "${jms.inbound-queue:in}", containerFactory = JmsConfig.IN_QUEUE_LISTENER_FACTORY)
    public void onMessage(Message message) throws JMSException, MyException {
        Object payload = message.getBody(Object.class);
        // JMS-defined property, counted from 1 by Artemis as by ActiveMQ, so the flow's "== 5" holds on the
        // fifth delivery.
        int deliveryCount = message.getIntProperty("JMSXDeliveryCount");
        try {
            redeliveryService.process(payload, deliveryCount);
        } catch (MyException e) {
            // The pause must precede the throw: the rollback, and with it the broker's redelivery, starts only once
            // the exception leaves this method.
            redeliveryDelay.pauseBeforeRollback(deliveryCount);
            throw e;
        }
    }
}
