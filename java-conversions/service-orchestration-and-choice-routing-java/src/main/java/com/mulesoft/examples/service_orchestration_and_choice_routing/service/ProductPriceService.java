/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.service;

import org.springframework.stereotype.Service;

/**
 * Product price lookup behind the price service on port 9999, ported from the Jersey resource
 * {@code ProductPrice}. The HTTP mapping, method restriction and media type belong to
 * {@code controller.ProductPriceController}.
 */
@Service
public class ProductPriceService {

	/**
	 * Returns the price of a product. Every product costs 159, as in the original resource.
	 *
	 * @param productId the product identifier from the request path; it does not affect the price
	 * @return the price as plain text, always {@code "159"}
	 */
	public String getProductPrice(String productId) {
		return "159";
	}

}
