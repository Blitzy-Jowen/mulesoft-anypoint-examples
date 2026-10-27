/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.websphere_mq.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.websphere_mq.service.AjaxChannelBroker;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.JmsException;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

/**
 * Outbound WMQ adapter of {@code websphere-mq.xml}: every send the original makes to IBM MQ.
 *
 * <ul>
 *   <li>Flow {@code Input} (websphere-mq.xml:8-14) as a whole: the {@code ajax:inbound-endpoint} on channel
 *       {@code /services/wmqExample/enqueue} (line 9) becomes the handler {@link #register()} installs on the
 *       project's {@link AjaxChannelBroker}, and the {@code wmq:outbound-endpoint queue="in"} (line 13) becomes
 *       {@link #sendToIn(String)}.</li>
 *   <li>The {@code wmq:outbound-endpoint queue="out"} with {@code JOIN_IF_POSSIBLE} of flow {@code MessageProcessor}
 *       (lines 20-22) becomes {@link #sendToOut(String)}, called by {@code trigger/InQueueListener} on its
 *       transacted listener thread.</li>
 * </ul>
 *
 * <p>The {@code message-properties-transformer} deleting {@code MULE_REPLYTO} (lines 10-12) is reproduced by never
 * setting a reply destination, correlation ID or property on a sent message, so nothing downstream replies to the
 * page. The Ajax connector's {@code disableReplyTo="true"} (line 7) is applied by the broker itself
 * ({@code ajax.disable-reply-to}), so the result of {@link #enqueue(Object)} never reaches the publisher.
 *
 * <p>Transactions are entirely the injected template's and the listener container's (see
 * {@code config/IbmMqConfig#jmsTemplate}): this class opens, commits and rolls back nothing, and catches nothing, so
 * a failed send propagates unchanged and, on the listener thread, rolls back the receive from {@code in}.
 *
 * <p>The class is deliberately not final and {@link #enqueue(Object)} calls {@link #sendToIn(String)} on
 * {@code this}, so a test spy can stub the two sends as the original test mocked its WMQ outbound endpoints.
 */
@Component
public class MqQueueSender {

    private final JmsTemplate jmsTemplate;

    private final AjaxChannelBroker broker;

    private final ObjectMapper objectMapper;

    private final String serverPath;

    private final String inQueue;

    private final String outQueue;

    /**
     * Creates the adapter.
     *
     * @param jmsTemplate  the session-transacted template of {@code config/IbmMqConfig}, over the same
     *                     {@code ConnectionFactory} as the listener containers
     * @param broker       the Ajax channel broker the enqueue channel is registered on
     * @param objectMapper serializes a non-text Ajax payload to the JSON text sent to {@code in}
     * @param serverPath   {@code ajax.server-path}, the path of the Ajax connector's {@code serverUrl}
     * @param inQueue      {@code wmq.queues.in}, the original's {@code queue="in"}
     * @param outQueue     {@code wmq.queues.out}, the original's {@code queue="out"}
     */
    public MqQueueSender(JmsTemplate jmsTemplate, AjaxChannelBroker broker, ObjectMapper objectMapper,
                         @Value("${ajax.server-path}") String serverPath,
                         @Value("${wmq.queues.in:in}") String inQueue,
                         @Value("${wmq.queues.out:out}") String outQueue) {
        this.jmsTemplate = jmsTemplate;
        this.broker = broker;
        this.objectMapper = objectMapper;
        this.serverPath = serverPath;
        this.inQueue = inQueue;
        this.outQueue = outQueue;
    }

    /**
     * Registers {@link #enqueue(Object)} as the handler of channel {@code <ajax.server-path>/enqueue}, replacing
     * {@code <ajax:inbound-endpoint channel="/services/wmqExample/enqueue" responseTimeout="10000"/>}
     * (websphere-mq.xml:9); the broker applies the response timeout ({@code ajax.response-timeout-ms}).
     *
     * @throws IllegalStateException when the channel already has a handler
     */
    @PostConstruct
    public void register() {
        broker.registerInbound(serverPath + "/enqueue", this::enqueue);
    }

    /**
     * Flow {@code Input}: sends one enqueue publication to queue {@code in}.
     *
     * <p>The page publishes the plain text of its input field ({@code mule.rpc(channel, data)}), which is sent
     * unchanged; any other value, such as the map or list a JSON publication decodes to, is sent as its JSON text.
     * Runs on the broker's executor thread, outside any transaction, so the template commits right after the send.
     *
     * @param data the publication's payload
     * @return the text sent, as returned by {@link #sendToIn(String)}
     * @throws IllegalArgumentException when a non-text payload cannot be serialized to JSON
     * @throws JmsException             when the send fails
     */
    public String enqueue(Object data) {
        String text;
        if (data instanceof String string) {
            text = string;
        } else {
            try {
                text = objectMapper.writeValueAsString(data);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Ajax payload cannot be serialized to JSON text", e);
            }
        }
        return sendToIn(text);
    }

    /**
     * Sends a text message to queue {@code in}: {@code <wmq:outbound-endpoint queue="in"/>} of flow {@code Input}
     * (websphere-mq.xml:13).
     *
     * @param payload the message text
     * @return {@code payload}
     * @throws JmsException when the send fails
     */
    public String sendToIn(String payload) {
        jmsTemplate.send(inQueue, session -> session.createTextMessage(payload));
        return payload;
    }

    /**
     * Sends a text message to queue {@code out}: {@code <wmq:outbound-endpoint queue="out">} with
     * {@code <wmq:transaction action="JOIN_IF_POSSIBLE"/>} of flow {@code MessageProcessor} (websphere-mq.xml:20-22).
     *
     * <p>Called on the session-transacted {@code in} listener thread, the template reuses that listener's session,
     * so the message is committed or rolled back together with the receive from {@code in}.
     *
     * @param payload the message text
     * @return {@code payload}
     * @throws JmsException when the send fails
     */
    public String sendToOut(String payload) {
        jmsTemplate.send(outQueue, session -> session.createTextMessage(payload));
        return payload;
    }
}
