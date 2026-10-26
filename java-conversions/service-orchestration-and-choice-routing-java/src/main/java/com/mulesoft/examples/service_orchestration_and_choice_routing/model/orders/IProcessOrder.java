/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders;

/**
 * Interface for order processing.
 * 
 * @author Derek
 */
public interface IProcessOrder {
	/**
	 * Process an order.
	 * 
	 * @param order
	 * @return
	 */
	Order processOrder(Order order);
}
