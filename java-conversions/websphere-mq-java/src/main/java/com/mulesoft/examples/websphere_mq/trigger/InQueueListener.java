/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.websphere_mq.trigger;

import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import com.mulesoft.examples.websphere_mq.client.MqQueueSender;
import com.mulesoft.examples.websphere_mq.service.MqProcessingService;

/**
 * Flow {@code MessageProcessor} of {@code websphere-mq.xml} (lines 15-23): every message taken from WMQ queue
 * {@code in} is processed by {@link MqProcessingService} and the result is sent to WMQ queue {@code out}.
 *
 * <p>Transactions map as follows, with no transaction code in this class:
 * <ul>
 *   <li>{@code <wmq:transaction action="ALWAYS_BEGIN"/>} on the {@code in} inbound endpoint: the listener runs on
 *       the session-transacted container factory {@code transactedListenerContainerFactory} of
 *       {@code config/IbmMqConfig}, so every receive opens a local JMS transaction, committed when
 *       {@link #onMessage(String)} returns and rolled back when it throws.</li>
 *   <li>{@code <wmq:transaction action="JOIN_IF_POSSIBLE"/>} on the {@code out} outbound endpoint:
 *       {@link MqQueueSender#sendToOut(String)} runs on this listener thread through the session-transacted
 *       template of {@code config/IbmMqConfig}, which reuses the container's listener session, so the send to
 *       {@code out} commits or rolls back together with the receive from {@code in}. Adding {@code @Transactional},
 *       a transaction manager or a hand-off to another thread would give the send a session of its own and break
 *       that join.</li>
 * </ul>
 *
 * <p>The flow has no exception strategy, so nothing is caught: an exception from the processing step or the send
 * leaves {@link #onMessage(String)}, the container rolls back the receive and the send, and the broker redelivers
 * the message, as the original WMQ transaction did.
 *
 * <p>{@link #process(String)} is public and the class is not final, so tests can run the flow body directly or wrap
 * the bean in a spy, the way the original test ran flow {@code MessageProcessor} with its WMQ outbound endpoint
 * mocked.
 */
@Component
public class InQueueListener {

    private final MqProcessingService mqProcessingService;

    private final MqQueueSender mqQueueSender;

    /**
     * Creates the listener.
     *
     * @param mqProcessingService the {@code test:component} step: log, wait, append {@code " - processed"}
     * @param mqQueueSender       sends the processed text to WMQ queue {@code out}
     */
    public InQueueListener(MqProcessingService mqProcessingService, MqQueueSender mqQueueSender) {
        this.mqProcessingService = mqProcessingService;
        this.mqQueueSender = mqQueueSender;
    }

    /**
     * Receives one message from WMQ queue {@code in}, replacing {@code <wmq:inbound-endpoint queue="in">} with
     * {@code ALWAYS_BEGIN}.
     *
     * <p>The method returns nothing: a returned value would make Spring's listener adapter send a reply, and the
     * messages on {@code in} carry no {@code JMSReplyTo} (flow {@code Input} deletes {@code MULE_REPLYTO}), so every
     * delivery would fail, roll back and be redelivered without end.
     *
     * @param text the text of the received message
     */
    @JmsListener(destination = "${wmq.queues.in:in}", containerFactory = "transactedListenerContainerFactory")
    public void onMessage(String text) {
        process(text);
    }

    /**
     * Runs the body of flow {@code MessageProcessor} on one payload, in the flow's processor order: the
     * {@code test:component} step first, then the send to queue {@code out}.
     *
     * @param payload the message text
     * @return the flow's resulting payload, the value {@link MqQueueSender#sendToOut(String)} returns
     * @throws NullPointerException  when {@code payload} is {@code null}
     * @throws IllegalStateException when the processing wait is interrupted
     * @throws org.springframework.jms.JmsException when the send to {@code out} fails
     */
    public String process(String payload) {
        String processed = mqProcessingService.process(payload);
        return mqQueueSender.sendToOut(processed);
    }
}
