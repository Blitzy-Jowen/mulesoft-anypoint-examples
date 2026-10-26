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
import org.springframework.stereotype.Component;

/**
 * Provides the credit profile for a customer.
 */
@Component
public class DefaultCreditAgency implements CreditAgencyService
{
     public CreditProfile getCreditProfile(Customer customer)
     {
         CreditProfile cp = new CreditProfile();
         cp.setCreditHistory(getCreditHistoryLength(customer.getSsn()));
         cp.setCreditScore(getCreditScore(customer.getSsn()));
         return cp;
     }

     protected int getCreditScore(int ssn)
     {
         int credit_score;

         credit_score = (int)(Math.random() * 600 + 300);

         return credit_score;
     }

     protected int getCreditHistoryLength(int ssn)
     {
         int credit_history_length;

         credit_history_length = (int)(Math.random() * 19 + 1);

         return credit_history_length;
     }
}
