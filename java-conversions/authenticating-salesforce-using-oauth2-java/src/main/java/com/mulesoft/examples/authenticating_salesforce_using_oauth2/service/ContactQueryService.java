/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.authenticating_salesforce_using_oauth2.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.authenticating_salesforce_using_oauth2.client.SalesforcePartnerClient;

/**
 * The part of flow {@code salesforce-oauthFlow1} that runs once the user has authorized, lines 13-17 of
 * {@code authenticating-salesforce-using-oauth2/src/main/app/salesforce-oauth.xml}: the {@code sfdc:query} of
 * {@link #CONTACT_QUERY}, the {@code foreach} that logs every contact at INFO, and the {@code set-payload} that
 * reports how many contacts the query returned.
 *
 * <p>Orchestration only. The Salesforce session, the re-authentication after an expired token and the classification
 * of upstream failures all live in {@link SalesforcePartnerClient}, which runs every call through the shared upstream
 * executor; this class neither retries nor catches.
 */
@Service
public class ContactQueryService {

    private static final Logger log = LoggerFactory.getLogger(ContactQueryService.class);

    /** The SOQL of the original {@code sfdc:query}, byte for byte (salesforce-oauth.xml line 13). */
    public static final String CONTACT_QUERY = "SELECT id,lastname,lastmodifieddate from contact limit 10";

    private final SalesforcePartnerClient partnerClient;

    /**
     * Creates the service over the project's single Salesforce client.
     *
     * @param partnerClient the Salesforce Partner API client, in session mode with the user's OAuth access token
     * @throws NullPointerException if {@code partnerClient} is {@code null}
     */
    public ContactQueryService(SalesforcePartnerClient partnerClient) {
        this.partnerClient = Objects.requireNonNull(partnerClient, "partnerClient");
    }

    /**
     * Queries the contacts, logs each one as {@code contact: <record>} at INFO in the order Salesforce returned them
     * (salesforce-oauth.xml lines 13-16), and builds the response text of the {@code set-payload} (line 17).
     *
     * <p>{@code query} is used rather than {@code queryAll}: {@code sfdc:query} returns no deleted or archived rows.
     * Each record is logged as a {@link HashMap}, the type the Mule connector handed to the {@code foreach}, so the
     * log line keeps the {@code {LastModifiedDate=..., Id=..., LastName=..., type=Contact}} rendering of the original.
     *
     * @return {@code Salesforce query returned <n> contacts.}, where {@code n} is the number of records returned
     * @throws Exception every failure of the query, unchanged: the SC-9 upstream exceptions for an authentication
     *                   failure that persists, a rate limit or an unavailable Salesforce, otherwise the WSC exception
     */
    public String queryContacts() throws Exception {
        List<Map<String, Object>> records = partnerClient.query(CONTACT_QUERY);
        int count = 0;
        if (records != null) {
            for (Map<String, Object> record : records) {
                log.info("contact: {}", asHashMap(record));
                count++;
            }
        }
        return "Salesforce query returned " + count + " contacts.";
    }

    /**
     * Keeps a record that already is a {@code HashMap} (what {@link SalesforcePartnerClient#toMap} returns) and copies
     * any other map into one, so the logged rendering never depends on the map implementation a caller supplies.
     */
    private static Map<String, Object> asHashMap(Map<String, Object> record) {
        return record == null || record instanceof HashMap ? record : new HashMap<>(record);
    }
}
