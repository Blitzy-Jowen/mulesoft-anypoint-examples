/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.legacy_modernization.service;

import com.mulesoft.examples.legacy_modernization.model.Address;
import com.mulesoft.examples.legacy_modernization.model.Order;
import com.mulesoft.examples.legacy_modernization.model.ShippingOrder;
import com.mulesoft.examples.legacy_modernization.model.ShippingOrderConfirmation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

// Prototype scope gives every call a fresh instance, as Mule's <component class="..."/> did,
// so concurrent SOAP requests never share the per-call public fields.
@Service
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class FulfillmentService implements IFulfillment{
	
	private static final Logger log = LoggerFactory.getLogger(FulfillmentService.class);

	public ShippingOrder shippingOrder;
	public ShippingOrderConfirmation shippingOrderConfirmation;
	
	@Override
	public ShippingOrderConfirmation putShippingOrder(String shippingId, Address billingAddress, Address shippingAddress, Order order){

		try {
			shippingOrder = new ShippingOrder(shippingId, billingAddress, shippingAddress, order);
			shippingOrderConfirmation = new ShippingOrderConfirmation(shippingOrder,true);
		} catch(Exception e)
		
		{
			// Only the exception type and its throw site are logged: the exception text can carry request
			// values (shipping id, addresses, order items).
			StackTraceElement[] stackTrace = e.getStackTrace();
			log.error("putShippingOrder failed: {} thrown at {}", e.getClass().getName(),
					stackTrace.length > 0 ? stackTrace[0] : "unknown");
		}

		return shippingOrderConfirmation;
	}
}
