/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.model.samsung;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ported from the original {@code com.mulesoft.se.samsung.SamsungProduct} with its JAX-RS
 * {@code @Path("/samsung")}, {@code @POST @Path("orders")} and JAX-WS {@code @WebParam}
 * annotations removed.
 * <p>
 * No flow of the original registers or calls this class (its Jersey resource is
 * {@code ProductPrice}), so it is deliberately neither a controller nor a Spring bean:
 * exposing it would add an endpoint the original never served. The Samsung SOAP service
 * is {@code SamsungServiceImpl}.
 * <p>
 * The original's {@code System.out} diagnostic is written through SLF4J at INFO with the
 * original text.
 */
public class SamsungProduct {

	private static final Logger LOG = LoggerFactory.getLogger(SamsungProduct.class);

	public OrderResponse createServiceProvider(OrderRequest request) {
		LOG.info("Creating service provider for authorizationId : {} qu : {}", request.getName(), request.getQuantity());
		OrderResponse orderResponse = new OrderResponse();
		orderResponse.setResult("Ok");
		return orderResponse;
	}

}
