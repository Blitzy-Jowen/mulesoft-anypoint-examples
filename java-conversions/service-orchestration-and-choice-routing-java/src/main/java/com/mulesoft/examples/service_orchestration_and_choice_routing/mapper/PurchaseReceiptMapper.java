/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.mapper;

import org.springframework.stereotype.Component;

import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.PurchaseReceipt;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.Status;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.samsung.OrderResponse;

/**
 * Hand-written reimplementation of the {@code %dw 1.0} script "OrderResponse to PurchaseReceipt" of flow
 * {@code samsungOrder} ({@code service-orchestration-and-choice-routing/src/main/app/fulfillment.xml}, header
 * line 68), of which this class is the sole owner (AAP 0.5.8):
 * <pre>
 * %dw 1.0
 * %output application/java
 * ---
 * {
 *   id:         payload.id,
 *   status:     payload.result,
 *   totalPrice: payload.price
 * } as :object { class: "com.mulesoft.se.orders.PurchaseReceipt"}
 * </pre>
 * The Samsung {@code purchase} response carries all three values as strings; {@code as :object} coerced
 * {@code result} into the {@link Status} constant of the same name and {@code price} into the receipt's
 * {@code float}, so {@code "2550"} becomes {@code 2550.0}.
 *
 * <p>Nothing is caught or defaulted here: a missing or unrecognised {@code result}, or a missing or non-numeric
 * {@code price}, fails the mapping, and the {@code samsungOrder} catch branch of the calling service turns that
 * failure into the {@code REJECTED} receipt, as the original's catch-exception-strategy did.
 */
@Component
public class PurchaseReceiptMapper {

    /**
     * Maps a Samsung order response to the purchase receipt of the ordered item.
     *
     * @param response the Samsung {@code purchase} response
     * @return a receipt with the response's id, its result as a {@link Status} and its price as the total price
     * @throws NullPointerException if {@code response}, its result or its price is {@code null}
     * @throws IllegalArgumentException if the result names no {@link Status} constant (case-sensitive)
     * @throws NumberFormatException if the price is not a number
     */
    public PurchaseReceipt map(OrderResponse response) {
        PurchaseReceipt receipt = new PurchaseReceipt();
        receipt.setId(response.getId());
        receipt.setStatus(Status.valueOf(response.getResult()));
        receipt.setTotalPrice(Float.parseFloat(response.getPrice()));
        return receipt;
    }
}
