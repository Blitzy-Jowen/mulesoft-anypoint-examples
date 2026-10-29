/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.foreach_processing_and_choice_routing.model;

import java.io.Serializable;

/**
 * <code>CustomerQuoteRequest</code> is the request sent by the the LoanBroker
 */
public class CustomerQuoteRequest implements Serializable
{
    /**
     * Serial version
     */
    private static final long serialVersionUID = 6365612435470800746L;

    /** The customer that requested the quote */
    private Customer customer;

    /** The requested loan Amount */
    private double loanAmount;

    /** the duration of the loan */
    private int loanDuration;

    public CustomerQuoteRequest()
    {
        super();
    }

    public CustomerQuoteRequest(Customer customer, double loanAmount, int loanDuration)
    {
        this.customer = customer;
        this.loanAmount = loanAmount;
        this.loanDuration = loanDuration;
    }

    public Customer getCustomer()
    {
        return customer;
    }

    public void setCustomer(Customer customer)
    {
        this.customer = customer;
    }

    public double getLoanAmount()
    {
        return loanAmount;
    }

    public void setLoanAmount(double loanAmount)
    {
        this.loanAmount = loanAmount;
    }

    public int getLoanDuration()
    {
        return loanDuration;
    }

    public void setLoanDuration(int loanDuration)
    {
        this.loanDuration = loanDuration;
    }
}
