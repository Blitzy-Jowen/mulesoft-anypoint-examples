/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.websphere_mq.trigger;

import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import com.mulesoft.examples.websphere_mq.service.AjaxChannelBroker;

/**
 * Flow {@code Output} of {@code websphere-mq.xml} (lines 24-27): every message taken from WMQ queue {@code out}
 * is published on the Ajax channel {@code <ajax.server-path>/dequeue}, to which the served page
 * {@code static/services/wmqExample/index.html} subscribes and whose deliveries it reads as {@code message.data}.
 *
 * <p>The original inbound endpoint declares no transaction, so the listener runs on the non-transacted,
 * auto-acknowledging container factory {@code autoAckListenerContainerFactory} of {@code config/IbmMqConfig}: a
 * message is consumed whatever happens to it here. The flow has no exception strategy, so nothing is caught; a
 * failure is logged by the listener container and the message is not redelivered, as with Mule's default strategy on
 * a non-transacted endpoint.
 *
 * <p>The channel is outbound: its {@code cacheMessages="true"} is honoured by {@link AjaxChannelBroker}, which holds a
 * message published while nobody subscribes for the first subscriber ({@code ajax.cache-messages-channels}). No
 * reply is ever produced, matching the connector's {@code disableReplyTo="true"}.
 *
 * <p>{@link #forward(String)} and {@link #publishToDequeue(String)} are public, non-final and called through
 * {@code this}, so a test spy can replace the Ajax publication the way the original test mocked the
 * {@code ajax:outbound-endpoint}.
 */
@Component
public class OutQueueListener {

    /** Appended to {@code ajax.server-path} to form the channel of the original {@code ajax:outbound-endpoint}. */
    static final String DEQUEUE_SUFFIX = "/dequeue";

    private final AjaxChannelBroker broker;

    private final String serverPath;

    /**
     * Creates the listener.
     *
     * @param broker     the Ajax channel state that delivers to the dequeue channel's subscribers
     * @param serverPath the Ajax connector's server path ({@code ajax.server-path}, {@code /services/wmqExample})
     * @throws NullPointerException when an argument is {@code null}
     */
    public OutQueueListener(AjaxChannelBroker broker, @Value("${ajax.server-path}") String serverPath) {
        this.broker = Objects.requireNonNull(broker, "broker");
        this.serverPath = Objects.requireNonNull(serverPath, "serverPath");
    }

    /**
     * Receives one message from WMQ queue {@code out}, replacing {@code <wmq:inbound-endpoint queue="out"/>}.
     *
     * <p>The method returns nothing: a returned value would make Spring's listener adapter send a reply, and the
     * messages on {@code out} carry no {@code JMSReplyTo}.
     *
     * @param text the text of the received message
     */
    @JmsListener(destination = "${wmq.queues.out:out}", containerFactory = "autoAckListenerContainerFactory")
    public void onMessage(String text) {
        forward(text);
    }

    /**
     * Runs the remainder of flow {@code Output} on one payload.
     *
     * @param payload the message text
     * @return the flow's resulting payload, the value {@link #publishToDequeue(String)} returns
     */
    public String forward(String payload) {
        return publishToDequeue(payload);
    }

    /**
     * Publishes one payload on the dequeue channel, replacing
     * {@code <ajax:outbound-endpoint cacheMessages="true" channel="/services/wmqExample/dequeue"/>}.
     *
     * @param payload the message text
     * @return {@code payload} unchanged, as the one-way outbound endpoint left the message payload as it was
     */
    public String publishToDequeue(String payload) {
        broker.publish(serverPath + DEQUEUE_SUFFIX, payload);
        return payload;
    }
}
