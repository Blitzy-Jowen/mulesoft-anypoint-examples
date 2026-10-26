/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.service;

import java.util.Date;

import com.mulesoft.examples.mule_component_bindings.exception.InvalidStockSymbol;
import com.mulesoft.examples.mule_component_bindings.model.StockStats;

/**
 * Defines an interface for classes that can fetch historical stock stats.
 */
public interface StockService {

	StockStats getHistoricalPrices(String stock, Date date) throws InvalidStockSymbol;
	
}
