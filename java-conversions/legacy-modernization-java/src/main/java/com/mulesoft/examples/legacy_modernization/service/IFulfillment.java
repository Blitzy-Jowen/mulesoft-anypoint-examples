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
	 * The {@code putShippingOrder} operation of the {@code http://ordermgmt.org/} fulfillment service: accepts a
	 * shipping order and returns its confirmation. The four inputs are carried into the confirmed shipping order as
	 * given, neither validated nor copied.
	 *
	 * @param shippingId      the shipping order's identifier
	 * @param billingAddress  the address the order is billed to
	 * @param shippingAddress the address the order is shipped to
	 * @param order           the ordered items
	 * @return the confirmation, with {@code orderReceivedStatus} {@code true}, of a {@code ShippingOrder} built from
	 *         the four inputs; {@code null} when building it fails with an exception, which is logged rather than
	 *         propagated
	 */
	
	public ShippingOrderConfirmation putShippingOrder(String shippingId,
													  Address billingAddress,
													  Address shippingAddress,
			 										  Order order);
	
}
