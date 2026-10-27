/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.service;

import org.springframework.stereotype.Service;

import com.mulesoft.examples.service_orchestration_and_choice_routing.model.samsung.OrderRequest;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.samsung.OrderResponse;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.samsung.SamsungService;

/**
 * The Samsung SOAP service behind the {@code samsungService} flow (port 9090, {@code /samsung/orders}), ported from
 * the original {@code com.mulesoft.se.samsung.SamsungServiceImpl}.
 *
 * <p>The SOAP binding and the flow's {@code aaaa} logger belong to {@code controller.SamsungServiceEndpoint}; the
 * wire contract is carried by {@code model.samsung.Purchase} and {@code PurchaseResponse}.
 */
@Service
public class SamsungServiceImpl implements SamsungService {

	/**
	 * Accepts every order: id {@code "1"}, result {@code "ACCEPTED"}, price {@code 2550 * quantity} in {@code int}
	 * arithmetic, as the original computed it.
	 *
	 * @param orderRequest the order; {@code null} fails with a {@link NullPointerException}, as in the original
	 * @return the order response
	 */
	@Override
	public OrderResponse purchase(OrderRequest orderRequest) {
		OrderResponse orderResponse = new OrderResponse();
		orderResponse.setId("1");
		orderResponse.setResult("ACCEPTED");
		Integer price = 2550 * orderRequest.getQuantity();
		orderResponse.setPrice(price.toString());
		return orderResponse;
	}

}
