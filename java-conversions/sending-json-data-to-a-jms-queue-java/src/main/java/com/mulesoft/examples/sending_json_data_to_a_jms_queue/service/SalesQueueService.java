/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_json_data_to_a_jms_queue.service;

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
 */
@Service
public class SalesQueueService {

    private static final Logger LOG = LoggerFactory.getLogger(SalesQueueService.class);

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
     * Sends {@code payload} to the queue, logs it, and returns it unchanged as the flow's response payload.
     *
     * <p>The template's default {@code SimpleMessageConverter} sends the string as a {@code TextMessage} whose
     * text is the payload exactly; the payload is never parsed, because the original forwards bodies that are not
     * valid JSON.
     *
     * @param payload the request body as decoded by the controller
     * @return the same {@code payload} instance
     * @throws org.springframework.jms.JmsException when the send fails; it is deliberately left to propagate
     */
    public String send(String payload) {
        jmsTemplate.convertAndSend(queue, payload);
        // The original logger declares no message, so Mule logged the message itself; the payload stands in for it.
        LOG.info("{}", payload);
        return payload;
    }
}
