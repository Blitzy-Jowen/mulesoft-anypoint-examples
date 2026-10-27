/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.service;

import java.math.BigDecimal;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.OrderItem;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.PurchaseReceipt;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.Status;

/**
 * The {@code inhouseOrder} flow of {@code fulfillment.xml} (lines 98-129): prices, stores and accepts every order item
 * whose manufacturer is not Samsung.
 *
 * <p>{@code trigger/InHouseOrderListener} owns the {@code jms:inbound-endpoint queue="inhouseOrder"} with
 * {@code xa-transaction action="ALWAYS_BEGIN"}: it receives the {@code OrderItem} and its String property
 * {@value #TOTAL_VALUE_PROPERTY} inside a new JTA transaction, calls {@link #receive(OrderItem, String, int)}, and sends
 * the returned receipt and total as the reply on the same transacted session. Every method here joins that transaction
 * ({@link Propagation#MANDATORY}, the {@code ALWAYS_JOIN} of the insert), so the {@code orders} row, the consumed
 * request and the reply commit or roll back together.
 *
 * <p>The flow's {@code rollback-exception-strategy maxRedeliveryAttempts="3"} is reproduced in two parts:
 * <ul>
 *   <li>A failure while processing logs the payload at INFO ({@code logger message="#[payload]"}) and is rethrown, so
 *       the listener's transaction rolls back and the broker redelivers the request.</li>
 *   <li>{@code on-redelivery-attempts-exceeded}: Mule counts redeliveries as {@code JMSXDeliveryCount - 1} and detects
 *       exhaustion when the message is received, before the flow runs. {@link #receive(OrderItem, String, int)} does
 *       the same, runs {@code defaultErrorHandler} and returns normally, so the request is consumed. The broker's
 *       in-house queue allows unlimited delivery attempts ({@code config/XaTransactionConfig}), which leaves this limit
 *       the only one.</li>
 * </ul>
 *
 * <p>The price comes from the {@code GET api/prices/#[payload.productId]} request on port 9999, while that listener
 * accepts POST only ({@code fulfillment.xml:145}, {@code controller/ProductPriceController}). The request is therefore
 * answered with 405 and every in-house item ends in the rollback branch and is redelivered until exhausted, exactly as
 * in the original; the accepted path is reached when the price lookup succeeds, as the tests arrange by mocking
 * {@link PriceCacheService}.
 *
 * <p>{@code OrderOrchestrationService} dispatches to this flow and owns {@code defaultErrorHandler}, so it is resolved
 * lazily through an {@link ObjectProvider} and never during construction.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class InHouseOrderService {

	/** Name of the JMS String property carrying the session variable {@code totalValue} with the request and reply. */
	public static final String TOTAL_VALUE_PROPERTY = "totalValue";

	private static final Logger LOG = LoggerFactory.getLogger(InHouseOrderService.class);

	/** {@code Save Order Item} (lines 110-112), with the MEL expressions as bind parameters. */
	static final String INSERT_ORDER =
			"insert into orders (product_id, name, manufacturer, quantity, price) values (?, ?, ?, ?, ?)";

	/** {@code set-variable price = "0"} (line 102): the value before the price enrichment. */
	private static final String INITIAL_PRICE = "0";

	/**
	 * Outcome of one in-house item, the reply of the request-response JMS exchange.
	 *
	 * @param receipt    the {@code ACCEPTED} purchase receipt built by the flow's Groovy transformer (lines 116-122)
	 * @param totalValue the session variable {@code totalValue} after {@code totalValue += totalPrice} (line 115)
	 */
	public record Result(PurchaseReceipt receipt, String totalValue) {
	}

	private final PriceCacheService priceCacheService;

	private final JdbcTemplate jdbcTemplate;

	private final ObjectProvider<OrderOrchestrationService> orderOrchestrationService;

	private final int maxRedeliveryAttempts;

	/**
	 * @param priceCacheService         the cached price lookup ({@code Cache the Price}, lines 104-107)
	 * @param jdbcTemplate              the template over the XA Derby {@code DataSource} of
	 *                                  {@code config/XaTransactionConfig}
	 * @param orderOrchestrationService lazy access to the owner of {@code defaultErrorHandler}
	 * @param maxRedeliveryAttempts     the rollback strategy's {@code maxRedeliveryAttempts},
	 *                                  {@code orders.inhouse.max-redelivery-attempts}
	 */
	public InHouseOrderService(PriceCacheService priceCacheService, JdbcTemplate jdbcTemplate,
			ObjectProvider<OrderOrchestrationService> orderOrchestrationService,
			@Value("${orders.inhouse.max-redelivery-attempts:3}") int maxRedeliveryAttempts) {
		this.priceCacheService = Objects.requireNonNull(priceCacheService, "priceCacheService");
		this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
		this.orderOrchestrationService = Objects.requireNonNull(orderOrchestrationService,
				"orderOrchestrationService");
		this.maxRedeliveryAttempts = maxRedeliveryAttempts;
	}

	/**
	 * Whether a delivery comes after the rollback strategy's redelivery attempts are used up.
	 *
	 * @param deliveryCount the request's {@code JMSXDeliveryCount}, 1 for the first delivery
	 * @return {@code true} when the redeliveries so far, {@code deliveryCount - 1}, exceed
	 *         {@code maxRedeliveryAttempts}: from the fifth delivery with the default of 3
	 */
	public boolean isRedeliveryExhausted(int deliveryCount) {
		return deliveryCount - 1 > maxRedeliveryAttempts;
	}

	/**
	 * Handles one delivery of the {@code inhouseOrder} queue.
	 *
	 * @param item          the order item, the request body
	 * @param totalValue    the request's {@value #TOTAL_VALUE_PROPERTY} property; {@code null} when absent
	 * @param deliveryCount the request's {@code JMSXDeliveryCount}
	 * @return the receipt and updated total, or {@code null} when redelivery is exhausted: {@code defaultErrorHandler}
	 *         has logged the item and the listener consumes the request without a reply
	 * @throws RuntimeException any failure of {@link #process(OrderItem, String)}, rethrown so the transaction rolls
	 *                          back
	 */
	public Result receive(OrderItem item, String totalValue, int deliveryCount) {
		if (isRedeliveryExhausted(deliveryCount)) {
			orderOrchestrationService.getObject().logError(item);
			return null;
		}
		return process(item, totalValue);
	}

	/**
	 * The processors of the {@code inhouseOrder} flow, in their order: price enrichment, insert, total price, session
	 * total, receipt.
	 *
	 * <p>The price is bound as the text the price service returned, and Derby converts it for the integer column
	 * {@code price}, as the original parameterized query bound the String flow variable. {@code totalValue} is built
	 * as MEL's {@code #[totalValue + totalPrice]} did with a String session variable: by concatenation, so
	 * {@code "0"} and a total price of 318 give {@code "0318"}; an absent property contributes nothing.
	 *
	 * @param item       the order item
	 * @param totalValue the session variable {@code totalValue} before this item; {@code null} when absent
	 * @return the {@code ACCEPTED} receipt, with no ID and {@code totalPrice} = price &times; quantity, and the updated
	 *         total
	 * @throws RuntimeException any failure (a price request answered with an error status, a database error, a price
	 *                          that is not a number, a {@code null} item) after the item is logged at INFO, so the
	 *                          listener's transaction rolls back and the broker redelivers the request
	 */
	public Result process(OrderItem item, String totalValue) {
		String price = INITIAL_PRICE;
		try {
			price = priceCacheService.price(item.getProductId());
			jdbcTemplate.update(INSERT_ORDER, item.getProductId(), item.getName(), item.getManufacturer(),
					item.getQuantity(), price);
			BigDecimal totalPrice = new BigDecimal(price.trim()).multiply(BigDecimal.valueOf(item.getQuantity()));
			String newTotalValue = (totalValue == null ? "" : totalValue) + totalPrice.toPlainString();
			PurchaseReceipt receipt = new PurchaseReceipt();
			receipt.setStatus(Status.ACCEPTED);
			receipt.setTotalPrice(Float.valueOf(totalPrice.toPlainString()));
			return new Result(receipt, newTotalValue);
		} catch (RuntimeException e) {
			LOG.info(String.valueOf(item));
			throw e;
		}
	}

}
