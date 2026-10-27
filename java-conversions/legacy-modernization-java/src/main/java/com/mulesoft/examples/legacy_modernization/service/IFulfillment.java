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
import com.mulesoft.examples.legacy_modernization.model.ShippingOrderConfirmation;

public interface IFulfillment {

	/**
	 * 
	 * @param shippingId
	 * @param order
	 * @return
	 */
	
	public ShippingOrderConfirmation putShippingOrder(String shippingId,
													  Address billingAddress,
													  Address shippingAddress,
			 										  Order order);
	
}
