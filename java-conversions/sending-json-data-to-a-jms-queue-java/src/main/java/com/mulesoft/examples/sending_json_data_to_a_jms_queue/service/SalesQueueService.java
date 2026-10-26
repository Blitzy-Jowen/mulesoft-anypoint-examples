/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_json_data_to_a_jms_queue.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

/**
 * Reproduces the {@code jms:outbound-endpoint} and {@code logger} processors of flow {@code json-to-jmsFlow}
 * (sending-json-data-to-a-jms-queue/src/main/app/json-to-jms.xml:9-10).
 *
 * <p>The endpoint is one-way: the payload is sent to the queue synchronously on the caller's thread, with no
 * reply-to and no receive, so a failed send fails the request before anything is logged.
 *
 * <p>The logger declares no message, so it logs a description of the message in the layout of Mule's
 * {@code DefaultMuleMessage.toString()}: the payload's class name and the message metadata, never the request
 * body.
 */
@Service
public class SalesQueueService {

    private static final Logger LOG = LoggerFactory.getLogger(SalesQueueService.class);

    /** Class name Mule's message rendering opens with. */
    private static final String MULE_MESSAGE_CLASS = "org.mule.DefaultMuleMessage";

    /** Payload class Mule's message holds when there is no payload; its payload is never {@code null}. */
    private static final String NULL_PAYLOAD_CLASS = "org.mule.transport.NullPayload";

    /** Mule's rendering of an unset correlation ID or exception payload. */
    private static final String NOT_SET = "<not set>";

    /** Mule's value for an unset correlation group size or sequence. */
    private static final int NO_CORRELATION = -1;

    /** The application's default encoding, which Mule's message reports when the payload sets none. */
    private static final String DEFAULT_ENCODING = "UTF-8";

    /**
     * The property scopes Mule's message rendering lists, in its order; Mule skips the application scope there,
     * because a message does not hold that scope.
     */
    private static final String[] PROPERTY_SCOPES = {"INVOCATION", "INBOUND", "OUTBOUND", "SESSION"};

    private final JmsTemplate jmsTemplate;

    private final String queue;

    /**
     * @param jmsTemplate the template Boot auto-configures against the project's broker
     * @param queue       destination queue name, {@code jms.queue}; defaults to the original's fixed {@code sales}
     */
    public SalesQueueService(JmsTemplate jmsTemplate, @Value("${jms.queue:sales}") String queue) {
        this.jmsTemplate = jmsTemplate;
        this.queue = queue;
    }

    /**
     * Sends {@code payload} to the queue, logs a description of the message at INFO, and returns the payload
     * unchanged as the flow's response payload.
     *
     * <p>The template's default {@code SimpleMessageConverter} sends the string as a {@code TextMessage} whose
     * text is the payload exactly; the payload is never parsed, because the original forwards bodies that are not
     * valid JSON. The log line names the payload's class and the message metadata only; the request body is
     * never logged.
     *
     * @param payload the request body as decoded by the controller
     * @return the same {@code payload} instance
     * @throws org.springframework.jms.JmsException when the send fails; it is deliberately left to propagate
     */
    public String send(String payload) {
        jmsTemplate.convertAndSend(queue, payload);
        // The original logger declares no message, so Mule 3.8's LoggerMessageProcessor logged the MuleMessage,
        // whose toString renders the payload's class name and the message metadata but never the body; this
        // reproduces that description, checking the level first as Mule did, and keeps the request body out of
        // the log event.
        if (LOG.isInfoEnabled()) {
            LOG.info("{}", describeMessage(payload));
        }
        return payload;
    }

    /**
     * Renders the message the way Mule's {@code DefaultMuleMessage.toString()} renders it, followed by its
     * {@code Message properties:} section, each line ending with the platform line separator.
     *
     * <p>The request body is deliberately kept out of the rendering: only its class name appears, so caller
     * data, credentials and line breaks in the body never reach the log. The {@code id} is a fresh random UUID,
     * as each Mule message carried its own unique ID. The {@code encoding} line shows the application default,
     * {@code UTF-8}, because this service receives only the decoded {@code String} and no encoding travels with
     * it. No property entries are listed under the scope headers, because this service carries no Mule message
     * properties: the HTTP inbound properties, which include caller headers such as {@code Authorization}, and
     * Mule's {@code MULE_*} transport properties have no counterpart here.
     *
     * @param payload the request body, or {@code null}; only its class name is rendered
     * @return the multi-line message description
     */
    private static String describeMessage(String payload) {
        String ls = System.lineSeparator();
        StringBuilder buf = new StringBuilder(400);
        buf.append(ls).append(MULE_MESSAGE_CLASS).append(ls)
                .append('{').append(ls)
                .append("  id=").append(UUID.randomUUID()).append(ls)
                .append("  payload=").append(payload == null ? NULL_PAYLOAD_CLASS : payload.getClass().getName())
                .append(ls)
                .append("  correlationId=").append(NOT_SET).append(ls)
                .append("  correlationGroup=").append(NO_CORRELATION).append(ls)
                .append("  correlationSeq=").append(NO_CORRELATION).append(ls)
                .append("  encoding=").append(DEFAULT_ENCODING).append(ls)
                .append("  exceptionPayload=").append(NOT_SET).append(ls)
                .append(ls).append("Message properties:").append(ls);
        for (String scope : PROPERTY_SCOPES) {
            buf.append("  ").append(scope).append(" scoped properties:").append(ls);
        }
        return buf.append('}').toString();
    }
}
