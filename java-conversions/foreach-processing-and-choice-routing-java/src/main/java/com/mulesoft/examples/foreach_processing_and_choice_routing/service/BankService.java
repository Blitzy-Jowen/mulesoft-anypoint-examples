/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.foreach_processing_and_choice_routing.service;

import com.mulesoft.examples.foreach_processing_and_choice_routing.model.LoanBrokerQuoteRequest;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.LoanQuote;

/**
 * <code>BankService</code> is a representation of a bank form which to obtain loan
 * quotes.
 */
public interface BankService
{
    LoanQuote getLoanQuote(LoanBrokerQuoteRequest request);
}
