/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.service;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.service_orchestration_and_choice_routing.client.SamsungServiceClient;
import com.mulesoft.examples.service_orchestration_and_choice_routing.mapper.OrderRequestMapper;
import com.mulesoft.examples.service_orchestration_and_choice_routing.mapper.PurchaseReceiptMapper;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.OrderItem;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.PurchaseReceipt;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.Status;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.samsung.OrderRequest;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.samsung.OrderResponse;

/**
 * The {@code samsungOrder} flow of {@code fulfillment.xml} (lines 49-93): orders one Samsung item from the Samsung
 * {@code purchase} web service and answers with its purchase receipt.
 *
 * <p>The original flow is reached through the request-response VM endpoint {@code samsungOrder}, which the order
 * flow's choice dispatches to for items whose manufacturer is {@code Samsung}; here the order orchestration calls
 * {@link #order(OrderItem, AtomicReference)} directly. The flow's processors run in their original order:
 * <ol>
 *   <li>"OrderItem to OrderRequest" ({@link OrderRequestMapper}): the item's {@code name} and {@code quantity}.</li>
 *   <li>{@code flow-ref samsungWebServiceClient} ({@link SamsungServiceClient#purchase(OrderRequest)}).</li>
 *   <li>{@code message-filter throwOnUnaccepted="true"} over the inbound property {@code http.status=200}: any other
 *       status, a reply without a SOAP message included, is a rejection that raises an exception.</li>
 *   <li>{@code set-session-variable totalValue = #[totalValue + payload.price]}. Both operands are strings, the
 *       session variable starting as {@code "0"} and the Samsung price being the text of {@code orderResponse/price},
 *       so MEL concatenated them: {@code "0"} and {@code "2550"} give {@code "02550"}, the value the audit flow
 *       stores. The update happens before the receipt mapping and is kept when that mapping fails, as a session
 *       variable set earlier in a flow survived its exception strategy.</li>
 *   <li>"OrderResponse to PurchaseReceipt" ({@link PurchaseReceiptMapper}): id, status and total price.</li>
 * </ol>
 *
 * <p>The flow's {@code catch-exception-strategy} answers every failure of these steps, a SOAP fault, a transport
 * error, a timeout, the filter rejection or a mapping error, with the receipt its Groovy transformer built: status
 * {@link Status#REJECTED}, total price {@code 0} and no id. Nothing is retried, as the README states. Errors are not
 * caught, because a Mule exception strategy handles exceptions, not JVM errors.
 *
 * <p>The class holds no mutable state; the per-order total is the caller's holder, so one instance serves concurrent
 * orders.
 */
@Service
public class SamsungOrderService {

	private static final Logger LOG = LoggerFactory.getLogger(SamsungOrderService.class);

	/** The HTTP status {@code message-property-filter pattern="http.status=200"} accepts (line 63). */
	private static final int ACCEPTED_HTTP_STATUS = 200;

	/** The receipt's total price on the catch branch, {@code receipt.setTotalPrice(0)} (line 83). */
	private static final float REJECTED_TOTAL_PRICE = 0;

	/** Number of causes named after the outermost exception before the logged chain is cut short. */
	private static final int CAUSE_LOG_LIMIT = 10;

	private final OrderRequestMapper orderRequestMapper;

	private final SamsungServiceClient samsungServiceClient;

	private final PurchaseReceiptMapper purchaseReceiptMapper;

	/**
	 * @param orderRequestMapper    owner of the "OrderItem to OrderRequest" script (lines 51-60)
	 * @param samsungServiceClient  the {@code samsungWebServiceClient} sub-flow (lines 94-97)
	 * @param purchaseReceiptMapper owner of the "OrderResponse to PurchaseReceipt" script (lines 66-78)
	 * @throws NullPointerException when any collaborator is {@code null}
	 */
	public SamsungOrderService(OrderRequestMapper orderRequestMapper, SamsungServiceClient samsungServiceClient,
			PurchaseReceiptMapper purchaseReceiptMapper) {
		this.orderRequestMapper = Objects.requireNonNull(orderRequestMapper, "orderRequestMapper");
		this.samsungServiceClient = Objects.requireNonNull(samsungServiceClient, "samsungServiceClient");
		this.purchaseReceiptMapper = Objects.requireNonNull(purchaseReceiptMapper, "purchaseReceiptMapper");
	}

	/**
	 * Orders one Samsung item.
	 *
	 * @param item       the order item routed to Samsung; {@code null} fails the request mapping and so yields the
	 *                   {@code REJECTED} receipt, as a failing transform did in the original flow
	 * @param totalValue the order's session variable {@code totalValue}; after an accepted 200 reply it holds its
	 *                   previous text followed by the Samsung price, and it is left unchanged when the call fails or
	 *                   the reply is rejected by the status filter
	 * @return the receipt mapped from the Samsung response, or a {@code REJECTED} receipt with total price {@code 0}
	 *         and no id when any step fails
	 * @throws NullPointerException when {@code totalValue} is {@code null}; the caller always supplies the order's
	 *                              holder, so this is a programming error and no Samsung call is made
	 */
	public PurchaseReceipt order(OrderItem item, AtomicReference<String> totalValue) {
		Objects.requireNonNull(totalValue, "totalValue");
		try {
			OrderRequest request = orderRequestMapper.map(item);
			ResponseEntity<OrderResponse> response = samsungServiceClient.purchase(request);
			if (response == null || response.getStatusCode().value() != ACCEPTED_HTTP_STATUS) {
				throw new IllegalStateException("Message has been rejected by filter.");
			}
			OrderResponse body = response.getBody();
			if (body == null) {
				throw new IllegalStateException("Samsung purchase reply carries no order response");
			}
			// MEL's + on two strings is concatenation, never numeric addition: "0" and "2550" give "02550".
			totalValue.accumulateAndGet(body.getPrice(), (current, price) -> current + price);
			return purchaseReceiptMapper.map(body);
		} catch (Exception e) {
			// Mule's catch-exception-strategy logs the exception before running its branch. Only the exception's
			// type chain is logged, never its text: a SOAP fault string or a mapping error can quote the caller's
			// order data.
			LOG.error("Samsung order failed, answering with a REJECTED purchase receipt: {}", describe(e));
			PurchaseReceipt receipt = new PurchaseReceipt();
			receipt.setStatus(Status.REJECTED);
			receipt.setTotalPrice(REJECTED_TOTAL_PRICE);
			return receipt;
		}
	}

	/**
	 * The class name of {@code ex} followed by the class name of each cause, separated by {@code " <- "}, for example
	 * {@code org.springframework.ws.client.WebServiceIOException <- java.net.ConnectException}. The walk stops at a
	 * cause already named, marked {@code [circular]}, and after {@link #CAUSE_LOG_LIMIT} causes, marked {@code ...}.
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
