/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.service;

import java.io.Serializable;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.ObjectMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.support.JmsUtils;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.IProcessOrder;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.Order;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.OrderItem;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.PurchaseReceipt;

/**
 * The order orchestration of {@code fulfillment.xml}: the {@code orderService} flow (lines 28-48) behind the SOAP
 * operation {@code IProcessOrder.processOrder}, its {@code defaultErrorHandler} sub-flow (lines 156-158) and the
 * {@code databaseInitialisation} flow (lines 159-172).
 *
 * <p>{@code controller/OrderProcessingEndpoint} and {@code controller/PopulateController} only bind the requests; every
 * routing decision is made here:
 * <ol>
 *   <li>{@code set-session-variable totalValue="0"}: the order's total starts as the string {@code "0"} and travels
 *       through every item as an {@link AtomicReference} holder.</li>
 *   <li>{@code foreach orderItems} with the enricher writing each item's {@code purchaseReceipt}: a manufacturer equal
 *       to {@code Samsung} (MEL {@code ==}, exact and case-sensitive) goes to the request-response VM endpoint
 *       {@code samsungOrder}, now {@link SamsungOrderService#order(OrderItem, AtomicReference)}; every other item goes
 *       to the request-response JMS endpoint {@code inhouseOrder} ({@link #orderInHouse(OrderItem, AtomicReference)}).
 *   </li>
 *   <li>The one-way VM endpoint {@code audit}, now the {@code @Async} {@link AuditService#audit(Order, String)}.</li>
 *   <li>{@code catch-exception-strategy}: {@code defaultErrorHandler} ({@link #logError(Object)}), after which the
 *       exception is rethrown so SC-11 {@code config/SoapFaultConfig} answers with a {@code soap:Server} fault.</li>
 * </ol>
 *
 * <p>The class is deliberately not transactional, as the original flow is not: the in-house leg runs in the JTA
 * transaction of {@code trigger/InHouseOrderListener}, the audit in its own, and the DDL auto-commits. It holds no
 * mutable state, so one instance serves concurrent orders.
 */
@Service
public class OrderOrchestrationService implements IProcessOrder {

	private static final Logger LOG = LoggerFactory.getLogger(OrderOrchestrationService.class);

	/** {@code Create orders Table} (line 162), verbatim. */
	public static final String ORDERS_DDL = "CREATE TABLE orders (i int generated always as identity, "
			+ "product_id varchar(256), name varchar(256), manufacturer varchar(256), quantity integer, price integer)";

	/** {@code Create order_audits Table} (line 166), verbatim. */
	public static final String ORDER_AUDITS_DDL = "CREATE TABLE order_audits (i int generated always as identity, "
			+ "order_id varchar(256), total_value integer)";

	/** The manufacturer of {@code when expression="#[payload.manufacturer == 'Samsung']"} (line 35). */
	private static final String SAMSUNG_MANUFACTURER = "Samsung";

	/** {@code set-session-variable value="0" variableName="totalValue"} (line 31): a string, not a number. */
	private static final String INITIAL_TOTAL_VALUE = "0";

	/** {@code set-payload value="db populated"} (line 168). */
	private static final String DB_POPULATED = "db populated";

	/** The catch branch's {@code set-payload value="table already populated"} (line 170). */
	private static final String TABLE_ALREADY_POPULATED = "table already populated";

	/** Number of causes named after the outermost exception before the logged chain is cut short. */
	private static final int CAUSE_LOG_LIMIT = 10;

	private final SamsungOrderService samsungOrderService;

	private final JmsTemplate inHouseRequestJmsTemplate;

	private final AuditService auditService;

	private final JdbcTemplate jdbcTemplate;

	private final String inHouseQueue;

	/**
	 * @param samsungOrderService       the {@code samsungOrder} flow
	 * @param inHouseRequestJmsTemplate the non-transacted request-reply template of {@code config/XaTransactionConfig};
	 *                                  never the primary XA template, whose enlisted session cannot consume the
	 *                                  temporary reply queue
	 * @param auditService              the {@code auditService} flow
	 * @param jdbcTemplate              the template over the XA Derby {@code DataSource}, used outside any transaction
	 *                                  for the DDL
	 * @param inHouseQueue              the queue of the in-house dispatch, {@code orders.inhouse.queue}
	 * @throws NullPointerException     when any collaborator is {@code null}
	 * @throws IllegalArgumentException when the queue name is blank
	 */
	public OrderOrchestrationService(SamsungOrderService samsungOrderService,
			@Qualifier("inHouseRequestJmsTemplate") JmsTemplate inHouseRequestJmsTemplate,
			AuditService auditService, JdbcTemplate jdbcTemplate,
			@Value("${orders.inhouse.queue:inhouseOrder}") String inHouseQueue) {
		this.samsungOrderService = Objects.requireNonNull(samsungOrderService, "samsungOrderService");
		this.inHouseRequestJmsTemplate = Objects.requireNonNull(inHouseRequestJmsTemplate,
				"inHouseRequestJmsTemplate");
		this.auditService = Objects.requireNonNull(auditService, "auditService");
		this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
		if (inHouseQueue == null || inHouseQueue.isBlank()) {
			throw new IllegalArgumentException("orders.inhouse.queue must not be blank");
		}
		this.inHouseQueue = inHouseQueue;
	}

	/**
	 * The {@code orderService} flow: orders every item, in list order, sets its purchase receipt and dispatches the
	 * audit with the order's final {@code totalValue}.
	 *
	 * @param order the unmarshalled order; a {@code null} item list is ordered as empty, as Mule's {@code foreach}
	 *              split nothing, and only the audit runs
	 * @return the same order instance with each answered item's {@code purchaseReceipt} set; an in-house item whose
	 *         reply timed out keeps the receipt it had
	 * @throws RuntimeException any failure of a collaborator, rethrown unchanged after {@link #logError(Object)} has
	 *                          logged the order; no audit is dispatched when an item fails
	 */
	@Override
	public Order processOrder(Order order) {
		try {
			AtomicReference<String> totalValue = new AtomicReference<>(INITIAL_TOTAL_VALUE);
			List<OrderItem> items = order.getOrderItems();
			if (items != null) {
				for (OrderItem item : items) {
					PurchaseReceipt receipt = SAMSUNG_MANUFACTURER.equals(item.getManufacturer())
							? samsungOrderService.order(item, totalValue)
							: orderInHouse(item, totalValue);
					if (receipt != null) {
						item.setPurchaseReceipt(receipt);
					}
				}
			}
			auditService.audit(order, totalValue.get());
			return order;
		} catch (RuntimeException e) {
			// Mule's catch-exception-strategy logs the exception before running its branch. Only the type chain is
			// logged, never the text: a JMS or JAXB message can quote the caller's order data.
			LOG.error("Order processing failed: {}", describe(e));
			logError(order);
			// defaultErrorHandler only logs and shapes no reply, so the platform default answers: the exception leaves
			// the endpoint and SoapFaultConfig returns a soap:Server fault.
			throw e;
		}
	}

	/**
	 * The {@code otherwise} branch: {@code jms:outbound-endpoint queue="inhouseOrder" exchange-pattern="request-response"}
	 * (line 39). The item is sent as an {@link ObjectMessage} with the order's {@code totalValue} as the String
	 * property {@value InHouseOrderService#TOTAL_VALUE_PROPERTY}, as Mule carried the session variable with the message,
	 * and the template waits on a temporary reply queue for {@code orders.inhouse.reply-timeout-ms}.
	 *
	 * @param item       the order item
	 * @param totalValue the order's {@code totalValue}; set to the reply's property when the reply carries one
	 * @return the reply's purchase receipt, or {@code null} when no reply arrives in time, leaving {@code totalValue}
	 *         unchanged
	 * @throws IllegalStateException when the reply is not an {@link ObjectMessage} holding a {@link PurchaseReceipt}
	 * @throws org.springframework.jms.JmsException when sending the request or reading the reply fails
	 */
	public PurchaseReceipt orderInHouse(OrderItem item, AtomicReference<String> totalValue) {
		Message reply = inHouseRequestJmsTemplate.sendAndReceive(inHouseQueue, session -> {
			ObjectMessage request = session.createObjectMessage(item);
			request.setStringProperty(InHouseOrderService.TOTAL_VALUE_PROPERTY, totalValue.get());
			return request;
		});
		if (reply == null) {
			return null;
		}
		try {
			if (!(reply instanceof ObjectMessage objectReply)) {
				throw new IllegalStateException("Unexpected in-house reply: " + reply.getClass().getName());
			}
			Serializable body = objectReply.getObject();
			if (!(body instanceof PurchaseReceipt receipt)) {
				throw new IllegalStateException(
						"Unexpected in-house reply: " + (body == null ? "null" : body.getClass().getName()));
			}
			String replyTotalValue = reply.getStringProperty(InHouseOrderService.TOTAL_VALUE_PROPERTY);
			if (replyTotalValue != null) {
				totalValue.set(replyTotalValue);
			}
			return receipt;
		} catch (JMSException e) {
			throw JmsUtils.convertJmsAccessException(e);
		}
	}

	/**
	 * The {@code defaultErrorHandler} sub-flow: logs {@code Error occurred: #[payload]} at INFO. {@code AuditService}
	 * and {@code InHouseOrderService} reach it lazily through an {@code ObjectProvider}.
	 *
	 * @param payload the message payload when the error occurred; {@code null} is logged as {@code "null"}
	 */
	public void logError(Object payload) {
		LOG.info("Error occurred: {}", String.valueOf(payload));
	}

	/**
	 * The {@code databaseInitialisation} flow: creates {@code orders}, then {@code order_audits}, each statement
	 * auto-committed outside any transaction as {@code db:execute-ddl} did.
	 *
	 * @return {@code db populated}, or {@code table already populated} when either statement fails, including the
	 *         second after the first has created its table; never throws
	 */
	public String populateDatabase() {
		try {
			jdbcTemplate.execute(ORDERS_DDL);
			jdbcTemplate.execute(ORDER_AUDITS_DDL);
			return DB_POPULATED;
		} catch (RuntimeException e) {
			// The catch-exception-strategy logs the exception before its set-payload, as Mule's strategies do.
			LOG.error("Database initialisation failed: {}", describe(e));
			return TABLE_ALREADY_POPULATED;
		}
	}

	/**
	 * The class name of {@code ex} followed by the class name of each cause, separated by {@code " <- "}, for example
	 * {@code org.springframework.jms.UncategorizedJmsException <- jakarta.jms.JMSException}. The walk stops at a cause
	 * already named, marked {@code [circular]}, and after {@link #CAUSE_LOG_LIMIT} causes, marked {@code ...}.
	 */
	private static String describe(Throwable ex) {
		StringBuilder out = new StringBuilder(128).append(ex.getClass().getName());
		Set<Throwable> named = Collections.newSetFromMap(new IdentityHashMap<>());
		named.add(ex);
		int causes = 0;
		for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
			out.append(" <- ");
			if (causes == CAUSE_LOG_LIMIT) {
				return out.append("...").toString();
			}
			if (!named.add(cause)) {
				return out.append("[circular]").toString();
			}
			out.append(cause.getClass().getName());
			causes++;
		}
		return out.toString();
	}

}
