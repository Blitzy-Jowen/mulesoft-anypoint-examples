/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.model.samsung;

/**
 * Ported from the original {@code com.mulesoft.se.samsung.SamsungProduct} with its JAX-RS
 * {@code @Path("/samsung")}, {@code @POST @Path("orders")} and JAX-WS {@code @WebParam}
 * annotations removed.
 * <p>
 * No flow of the original registers or calls this class (its Jersey resource is
 * {@code ProductPrice}), so it is deliberately neither a controller nor a Spring bean:
 * exposing it would add an endpoint the original never served. The Samsung SOAP service
 * is {@code SamsungServiceImpl}.
 */
public class SamsungProduct {

	public OrderResponse createServiceProvider(OrderRequest request) {
		System.out.println("Creating service provider for authorizationId : " + request.getName() + " qu : " + request.getQuantity());
		OrderResponse orderResponse = new OrderResponse();
		orderResponse.setResult("Ok");
		return orderResponse;
	}

}
