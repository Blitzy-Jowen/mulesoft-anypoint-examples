/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.client;

/**
 * The vendor systems whose calls {@code UpstreamCallExecutor} guards, part of the shared connector failure
 * handling (SC-9).
 *
 * <p>Each display name is the {@code upstream} value written into the 502, 429 and 503 bodies
 * ({@code { "message": "...", "upstream": "<display name>" }}) and named in the executor's ERROR log, so the
 * names are part of the caller-facing contract and must not change.
 *
 * <p>Every connector project carries an identical copy of this file; only the {@code package} line differs.
 */
public enum UpstreamSystem {

    /** Salesforce, called through the SOAP Partner API. */
    SALESFORCE("Salesforce"),

    /** NetSuite, called through its REST web services (SuiteQL and record API). */
    NETSUITE("NetSuite"),

    /** Workday, called through the Revenue_Management SOAP web service. */
    WORKDAY("Workday"),

    /** Microsoft Dynamics 365, called through the Dataverse Web API. */
    MICROSOFT_DYNAMICS_365("Microsoft Dynamics 365");

    private final String displayName;

    private UpstreamSystem(String displayName) {
        this.displayName = displayName;
    }

    /**
     * @return the name callers see in the {@code upstream} field of a failure response
     */
    public String displayName() {
        return displayName;
    }

    /**
     * @return the display name, so string concatenation and log placeholders show the vendor's name rather than
     *         the constant's
     */
    @Override
    public String toString() {
        return displayName;
    }
}
