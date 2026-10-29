/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.foreach_processing_and_choice_routing.service;

import com.mulesoft.examples.foreach_processing_and_choice_routing.model.CreditProfile;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.Customer;


/**
 * <code>CreditAgencyService</code> the service that provides a credit score for a
 * customer.
 */
public interface CreditAgencyService
{
    CreditProfile getCreditProfile(Customer customer);
}
