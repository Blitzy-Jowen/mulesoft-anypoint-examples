/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.mapper;

import java.util.Objects;

import org.springframework.stereotype.Component;

import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.OrderItem;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.samsung.OrderRequest;

/**
 * Hand-written reimplementation of the {@code %dw 1.0} script "OrderItem to OrderRequest"
 * ({@code service-orchestration-and-choice-routing/src/main/app/fulfillment.xml}, flow {@code samsungOrder},
 * header line 53), of which this class is the sole owner (AAP 0.5.8):
 * <pre>
 * %dw 1.0
 * %output application/java
 * ---
 * {
 *     name:     payload.name,
 *     quantity: payload.quantity
 * } as :object { class: "com.mulesoft.se.samsung.OrderRequest"}
 * </pre>
 *
 * <p>Only {@code name} and {@code quantity} are copied: the item's {@code productId}, {@code manufacturer} and
 * {@code purchaseReceipt} never reach the Samsung {@code purchase} request. The unused Java transformer
 * {@code OrderItemToOrderRequest} of the original is not the source of this mapping; the active script is.
 * A {@code null} name is passed through unchanged, as DataWeave did.
 *
 * <p>A {@code null} item fails with a {@link NullPointerException} rather than producing an empty request: in the
 * original this transform runs inside the {@code samsungOrder} catch-exception-strategy, so the caller answers any
 * failure here with the {@code REJECTED} purchase receipt.
 */
@Component
public class OrderRequestMapper {

	/**
	 * Builds the Samsung order request for one order item.
	 *
	 * @param item the order item routed to Samsung; must not be {@code null}
	 * @return a new request carrying the item's {@code name} and {@code quantity}
	 * @throws NullPointerException if {@code item} is {@code null}
	 */
	public OrderRequest map(OrderItem item) {
		Objects.requireNonNull(item, "item");
		OrderRequest request = new OrderRequest();
		request.setName(item.getName());
		request.setQuantity(item.getQuantity());
		return request;
	}
}
