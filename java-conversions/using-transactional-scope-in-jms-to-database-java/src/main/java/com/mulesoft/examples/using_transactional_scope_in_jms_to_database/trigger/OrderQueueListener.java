/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.using_transactional_scope_in_jms_to_database.trigger;

import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.mapper.OrderXmlMapper;
import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.model.Order;
import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.service.OrderTransactionService;

import java.nio.charset.StandardCharsets;

import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

/**
 * Trigger of flow {@code transactionsFlow1} ({@code transactions.xml}): the {@code Active_MQ} connector's
 * {@code maxRedelivery="2"} (line 3), the {@code jms:inbound-endpoint queue="in"} (line 7) and the
 * {@code logger "XXXXXXX order: #[payload]"} (line 11).
 *
 * <p>Every delivery runs in the locally transacted session of the {@code jmsListenerContainerFactory} container
 * (see {@code config/TransactionConfig}). The class deliberately opens no Spring transaction: the session
 * commits when {@link #onMessage(Message)} returns and rolls back when it throws, and the database transaction
 * belongs to {@link OrderTransactionService#process(Order)} alone. Nothing is caught here, so every failure,
 * including the transactional scope's {@code MyException}, rolls the session back and the broker redelivers.
 *
 * <p>The class only guards redelivery, binds the message text, logs and delegates: the XML conversion belongs to
 * {@link OrderXmlMapper} and the insert, component and send to {@link OrderTransactionService}.
 */
@Component
public class OrderQueueListener {

    /** Name of the original connector, quoted in the redelivery-exceeded log line. */
    public static final String CONNECTOR_NAME = "Active_MQ";

    /**
     * Text of Mule's {@code MessageRedeliveredException} as the original README shows it: message ID, redelivery
     * count, endpoint URI, {@code maxRedelivery}, connector name and the simple name of the JMS message class.
     */
    public static final String REDELIVERY_EXCEEDED_FORMAT = "Message with id \"%s\" has been redelivered %d times on endpoint \"%s\", which exceeds the maxRedelivery setting of %d on the connector \"%s\". Message payload is of type: %s";

    private static final Logger log = LoggerFactory.getLogger(OrderQueueListener.class);

    /** JMS-defined property carrying the 1-based delivery attempt. */
    private static final String DELIVERY_COUNT_PROPERTY = "JMSXDeliveryCount";

    private final OrderXmlMapper orderXmlMapper;

    private final OrderTransactionService orderTransactionService;

    private final int maxRedelivery;

    private final String inboundQueue;

    /**
     * Creates the listener.
     *
     * @param orderXmlMapper          the "Convert XML to Order" step (lines 8-10)
     * @param orderTransactionService the transactional scope (lines 12-21)
     * @param maxRedelivery           the connector's {@code maxRedelivery}, 2 in the original
     * @param inboundQueue            the inbound endpoint's queue, {@code in} in the original
     */
    public OrderQueueListener(OrderXmlMapper orderXmlMapper, OrderTransactionService orderTransactionService,
            @Value("${jms.max-redelivery:2}") int maxRedelivery, @Value("${jms.inbound-queue:in}") String inboundQueue) {
        this.orderXmlMapper = orderXmlMapper;
        this.orderTransactionService = orderTransactionService;
        this.maxRedelivery = maxRedelivery;
        this.inboundQueue = inboundQueue;
    }

    /**
     * Receives one delivery from the inbound queue and runs the flow on it.
     *
     * <p>As Mule's JMSX redelivery handler did for the connector's {@code maxRedelivery}, a delivery whose
     * redelivery count ({@code JMSXDeliveryCount - 1}, the count taken as 1 when the property is absent) exceeds
     * {@code maxRedelivery} is logged at ERROR and returns without being processed, so the container commits and
     * the message is consumed. With the original value 2, deliveries 1 to 3 are processed and roll back, and
     * delivery 4 is consumed.
     *
     * <p>Otherwise the message text is converted to an {@link Order}, logged at INFO and handed to the
     * transactional scope.
     *
     * @param message the delivery, a text or bytes message holding an {@code order} document
     * @throws Exception unchanged: a {@link JMSException} reading the message, the
     *         {@link IllegalArgumentException} of an unsupported message type or a document the mapper rejects,
     *         or whatever the transactional scope throws (in practice {@code MyException("exception customized")})
     */
    @JmsListener(destination = "${jms.inbound-queue:in}", containerFactory = "jmsListenerContainerFactory")
    public void onMessage(Message message) throws Exception {
        int deliveryCount = message.propertyExists(DELIVERY_COUNT_PROPERTY)
                ? message.getIntProperty(DELIVERY_COUNT_PROPERTY) : 1;
        int redeliveryCount = deliveryCount - 1;
        if (redeliveryCount > maxRedelivery) {
            log.error(String.format(REDELIVERY_EXCEEDED_FORMAT, message.getJMSMessageID(), redeliveryCount,
                    "jms://" + inboundQueue, maxRedelivery, CONNECTOR_NAME, message.getClass().getSimpleName()));
            return;
        }

        String text = extractText(message);
        Order order = orderXmlMapper.toOrder(text);
        log.info("XXXXXXX order: " + order);
        orderTransactionService.process(order);
    }

    /**
     * The message body as text: a text message's text, or a bytes message's body decoded as UTF-8.
     *
     * @param message the delivery
     * @return the body text
     * @throws JMSException when the body cannot be read
     * @throws IllegalArgumentException for any other message type
     */
    private static String extractText(Message message) throws JMSException {
        if (message instanceof TextMessage textMessage) {
            return textMessage.getText();
        }
        if (message instanceof BytesMessage bytesMessage) {
            byte[] bytes = new byte[(int) bytesMessage.getBodyLength()];
            bytesMessage.readBytes(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
        throw new IllegalArgumentException("Unsupported JMS message type: " + message.getClass().getName());
    }
}
