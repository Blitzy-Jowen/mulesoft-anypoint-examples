/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.trigger;

import java.util.Objects;

import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageProducer;
import jakarta.jms.ObjectMessage;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.support.converter.MessageConversionException;
import org.springframework.stereotype.Component;

import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.OrderItem;
import com.mulesoft.examples.service_orchestration_and_choice_routing.service.InHouseOrderService;
import com.mulesoft.examples.service_orchestration_and_choice_routing.service.OrderOrchestrationService;

/**
 * The message source of the {@code inhouseOrder} flow of {@code fulfillment.xml} (lines 98-129):
 * {@code jms:inbound-endpoint queue="inhouseOrder"} with {@code xa-transaction action="ALWAYS_BEGIN"}, answering the
 * request-response {@code jms:outbound-endpoint} of {@code orderService} (line 39), which
 * {@code OrderOrchestrationService.orderInHouse} sends as an {@link ObjectMessage} of the {@link OrderItem} with the
 * String property {@value InHouseOrderService#TOTAL_VALUE_PROPERTY} and a temporary reply queue.
 *
 * <p>The class only binds the message and returns the reply. {@link InHouseOrderService} owns the flow body (price
 * lookup, insert, {@code totalPrice}, the {@code totalValue} update, the {@code ACCEPTED} receipt), the rollback
 * strategy's INFO log of the payload and the redelivery limit with its {@code defaultErrorHandler} call.
 *
 * <p>Transaction boundary: the {@code inHouseListenerContainerFactory} of {@code config/XaTransactionConfig} receives
 * each delivery in a new JTA (Narayana) transaction on its XA Artemis session, the {@code ALWAYS_BEGIN}. The service's
 * insert joins it through the XA Derby {@code DataSource} ({@code ALWAYS_JOIN}), and the reply is sent on the same
 * session, so the consumed request, the {@code orders} row and the reply commit or roll back together, as Mule sent the
 * reply inside the transacted session. Any exception escaping {@link #onMessage(Message, Session)} rolls the
 * transaction back, and the broker redelivers the request after the queue's {@code redelivery-delay}: that redelivery
 * is the {@code rollback-exception-strategy}.
 *
 * <p>Redelivery: Mule's {@code maxRedeliveryAttempts="3"} counts redeliveries as {@code JMSXDeliveryCount - 1}.
 * Deliveries 1-4 that fail roll back; delivery 5 is exhausted, so {@code on-redelivery-attempts-exceeded} runs
 * {@code defaultErrorHandler} ({@code OrderOrchestrationService.logError}) and the delivery commits, consuming the
 * request without a reply. The requester's {@code sendAndReceive} then returns {@code null} after
 * {@code orders.inhouse.reply-timeout-ms}, leaving the item's receipt unset; whether the original answered the
 * requester at that point is a Tier 2A verification point. The broker allows unlimited delivery attempts on this
 * queue, so a body that is not an {@code OrderItem}, or cannot be read, is bounded by the same limit here rather than
 * redelivered forever.
 *
 * <p>Artemis deserializes the {@link ObjectMessage} body in {@code getObject()}. If a deserialization allow list is
 * ever configured on the Artemis connection factory, it must admit the {@code model.orders} package of this
 * application together with {@code java.lang} and {@code java.util}.
 */
@Component
public class InHouseOrderListener {

	private static final Logger log = LoggerFactory.getLogger(InHouseOrderListener.class);

	/** Message property in which the broker counts deliveries: 1 for the first, incremented on each redelivery. */
	static final String DELIVERY_COUNT_PROPERTY = "JMSXDeliveryCount";

	private final InHouseOrderService inHouseOrderService;

	private final OrderOrchestrationService orderOrchestrationService;

	/**
	 * @param inHouseOrderService       the {@code inhouseOrder} flow body and its redelivery limit
	 * @param orderOrchestrationService the owner of {@code defaultErrorHandler}, called here only for a request whose
	 *                                  body is not an order item once its redeliveries are exhausted
	 * @throws NullPointerException when either collaborator is {@code null}
	 */
	public InHouseOrderListener(InHouseOrderService inHouseOrderService,
			OrderOrchestrationService orderOrchestrationService) {
		this.inHouseOrderService = Objects.requireNonNull(inHouseOrderService, "inHouseOrderService");
		this.orderOrchestrationService = Objects.requireNonNull(orderOrchestrationService,
				"orderOrchestrationService");
	}

	/**
	 * Handles one delivery of the in-house queue inside the container's JTA transaction.
	 *
	 * @param message the request: an {@link ObjectMessage} of an {@link OrderItem} with the String property
	 *                {@value InHouseOrderService#TOTAL_VALUE_PROPERTY}
	 * @param session the container's transacted session, on which the reply is sent
	 * @throws JMSException               when reading the request's properties or sending the reply fails, or when the
	 *                                    body cannot be read and redelivery is not exhausted
	 * @throws MessageConversionException when the body is not an {@link OrderItem} and redelivery is not exhausted
	 * @throws RuntimeException           any failure of {@link InHouseOrderService#receive(OrderItem, String, int)},
	 *                                    propagated unchanged so the transaction rolls back
	 */
	@JmsListener(destination = "${orders.inhouse.queue}", containerFactory = "inHouseListenerContainerFactory")
	public void onMessage(Message message, Session session) throws JMSException {
		int deliveryCount = message.propertyExists(DELIVERY_COUNT_PROPERTY)
				? message.getIntProperty(DELIVERY_COUNT_PROPERTY)
				: 1;
		String totalValue = message.getStringProperty(InHouseOrderService.TOTAL_VALUE_PROPERTY);

		Object payload;
		try {
			payload = payloadOf(message);
		} catch (JMSException e) {
			// An unreadable body (for example a class that cannot be deserialized) fails the flow before it starts, so
			// it is bounded by the redelivery limit like any other failure instead of returning forever.
			if (inHouseOrderService.isRedeliveryExhausted(deliveryCount)) {
				orderOrchestrationService.logError(message);
				return;
			}
			log.info("{}", message);
			throw e;
		}

		if (!(payload instanceof OrderItem item)) {
			rejectUnexpectedPayload(message, payload, deliveryCount);
			return;
		}

		InHouseOrderService.Result result = inHouseOrderService.receive(item, totalValue, deliveryCount);
		if (result != null) {
			reply(message, session, result);
		}
	}

	/**
	 * The body as Mule's JMS transformer exposed it: the object of an {@link ObjectMessage}, the text of a
	 * {@link TextMessage}, otherwise the message itself.
	 */
	private static Object payloadOf(Message message) throws JMSException {
		if (message instanceof ObjectMessage objectMessage) {
			return objectMessage.getObject();
		}
		if (message instanceof TextMessage textMessage) {
			return textMessage.getText();
		}
		return message;
	}

	/**
	 * A body the flow cannot process: the original failed at {@code payload.productId}. Within the redelivery limit the
	 * rollback strategy logs the payload and the delivery rolls back; once exhausted, {@code defaultErrorHandler} runs
	 * and the delivery commits without a reply.
	 */
	private void rejectUnexpectedPayload(Message message, Object payload, int deliveryCount) throws JMSException {
		if (inHouseOrderService.isRedeliveryExhausted(deliveryCount)) {
			orderOrchestrationService.logError(payload);
			return;
		}
		log.info("{}", payload);
		String typeName = payload == null ? "null" : payload.getClass().getName();
		throw new MessageConversionException("Expected an OrderItem ObjectMessage body on "
				+ message.getJMSDestination() + " but received " + typeName);
	}

	/**
	 * Sends the receipt and the updated {@code totalValue} to the request's reply destination on the transacted
	 * session, so the reply is delivered only when the transaction commits. Producing to the requester's temporary
	 * queue from this connection is allowed by JMS; only consuming from it is restricted to the creating connection. A
	 * request without a reply destination (a one-way sender) gets no reply.
	 */
	private static void reply(Message request, Session session, InHouseOrderService.Result result)
			throws JMSException {
		Destination replyTo = request.getJMSReplyTo();
		if (replyTo == null) {
			return;
		}
		ObjectMessage reply = session.createObjectMessage(result.receipt());
		reply.setStringProperty(InHouseOrderService.TOTAL_VALUE_PROPERTY, result.totalValue());
		String correlationId = request.getJMSCorrelationID();
		reply.setJMSCorrelationID(correlationId != null ? correlationId : request.getJMSMessageID());
		MessageProducer producer = session.createProducer(replyTo);
		try {
			producer.send(reply);
		} finally {
			producer.close();
		}
	}

}
