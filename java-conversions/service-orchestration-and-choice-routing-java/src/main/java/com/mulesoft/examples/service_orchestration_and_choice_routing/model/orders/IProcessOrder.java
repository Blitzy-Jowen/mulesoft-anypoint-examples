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
	 * @param order the order to process; its items are ordered in list order, and each answered item has its
	 *              {@code purchaseReceipt} set
	 * @return the same order instance, its items now carrying their purchase receipts; the SOAP operation returns it
	 *         as {@code summary}
	 * @throws RuntimeException any failure while processing the order; it is not recovered from but propagates to the
	 *                          caller, and the SOAP endpoint answers it with a {@code soap:Server} fault
	 */
	Order processOrder(Order order);
}
