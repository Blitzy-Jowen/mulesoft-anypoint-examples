/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.dataweave_with_flowreflookup.mapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.mulesoft.examples.dataweave_with_flowreflookup.service.SalesRegionLookupService;

/**
 * Hand-written owner of the {@code CSV to Maps} transformation of {@code dataweave-with-flowref.xml}, the only
 * transformation of the {@code CreateNewSalesforceAccountFlow} flow.
 *
 * <p>{@link #map(List)} reimplements the {@code %dw 1.0} script whose header is line 9 of
 * {@code dataweave-with-flowreflookup/src/main/app/dataweave-with-flowref.xml}:
 * <pre>
 * %dw 1.0
 * %output application/java
 * ---
 * payload map {
 *   Name: $.company_name,
 *   BillingStreet: $.company_address,
 *   BillingCity: $.company_city,
 *   BillingState: upper $.company_state,
 *   BillingPostalCode: $.company_zip,
 *   Region__c: lookup("LookUpSalesRegionFlow", $).region
 * }
 * </pre>
 * Its output was the {@code #[payload]} of {@code sfdc:create type="Account"}. The six mappings are:
 * <ul>
 *   <li>{@code Name} from the {@code company_name} column;</li>
 *   <li>{@code BillingStreet} from the {@code company_address} column;</li>
 *   <li>{@code BillingCity} from the {@code company_city} column;</li>
 *   <li>{@code BillingState} from the {@code company_state} column, upper-cased;</li>
 *   <li>{@code BillingPostalCode} from the {@code company_zip} column;</li>
 *   <li>{@code Region__c} from the {@code region} entry the {@code LookUpSalesRegionFlow} lookup returns, now
 *       {@link SalesRegionLookupService#lookUpSalesRegion(Map)}.</li>
 * </ul>
 * {@code %output application/java} produced a {@code List} of {@code LinkedHashMap}s in the declared key order, and
 * the same types and order are returned here. Values are the strings the CSV reader produced, copied as they are:
 * the script coerces nothing, so {@code 33126} stays a string, and apart from the upper-cased state nothing is
 * trimmed or re-cased.
 *
 * <p>The records go to Salesforce unchanged: {@code SalesforcePartnerClient.create(String, List)} takes the object
 * type {@value #SOBJECT_TYPE} and these maps and builds the WSC {@code SObject}s itself, so this mapper has no
 * {@code SObject} conversion and depends on no Salesforce type.
 *
 * <p>The mapper logs nothing, as the transform did not; the console line and the INFO line of each lookup belong
 * to {@link SalesRegionLookupService}.
 *
 * <p>The class holds only its injected lookup service, which is itself stateless, and is therefore thread-safe.
 */
@Component
public class AccountMapper {

    /** CSV column holding the company name. */
    public static final String CSV_COMPANY_NAME = "company_name";

    /** CSV column holding the street address. */
    public static final String CSV_COMPANY_ADDRESS = "company_address";

    /** CSV column holding the city. */
    public static final String CSV_COMPANY_CITY = "company_city";

    /** CSV column holding the state code, in any case. */
    public static final String CSV_COMPANY_STATE = "company_state";

    /** CSV column holding the postal code. */
    public static final String CSV_COMPANY_ZIP = "company_zip";

    /** Account field receiving the {@code company_name} column. */
    public static final String NAME = "Name";

    /** Account field receiving the {@code company_address} column. */
    public static final String BILLING_STREET = "BillingStreet";

    /** Account field receiving the {@code company_city} column. */
    public static final String BILLING_CITY = "BillingCity";

    /** Account field receiving the upper-cased {@code company_state} column. */
    public static final String BILLING_STATE = "BillingState";

    /** Account field receiving the {@code company_zip} column. */
    public static final String BILLING_POSTAL_CODE = "BillingPostalCode";

    /** Custom Account field receiving the looked-up sales region. */
    public static final String REGION = "Region__c";

    /** Salesforce object type the records are created as, the {@code type} of the original {@code sfdc:create}. */
    public static final String SOBJECT_TYPE = "Account";

    private final SalesRegionLookupService salesRegionLookupService;

    /**
     * Creates a mapper over the region lookup that replaces flow {@code LookUpSalesRegionFlow}.
     *
     * @param salesRegionLookupService the lookup called once per record
     * @throws NullPointerException if {@code salesRegionLookupService} is {@code null}
     */
    public AccountMapper(SalesRegionLookupService salesRegionLookupService) {
        this.salesRegionLookupService = Objects.requireNonNull(salesRegionLookupService, "salesRegionLookupService");
    }

    /**
     * Maps CSV rows to Account records, one record per row in input order.
     *
     * <p>Each record holds exactly {@code Name}, {@code BillingStreet}, {@code BillingCity}, {@code BillingState},
     * {@code BillingPostalCode} and {@code Region__c}, in that order. A column absent from a row yields a
     * {@code null} value under its key; an absent or {@code null} state yields a {@code null} {@code BillingState}
     * rather than a failure. Columns the script does not name, such as {@code has_given_contact_permission}, are
     * ignored; rows are neither filtered, de-duplicated nor validated, and the input is never modified.
     *
     * <p>The region lookup runs once per row, while that row is mapped, and receives the row itself as read: the
     * lookup upper-cases {@code company_state} on its own, as the Groovy script of {@code LookUpSalesRegionFlow} did,
     * and its console and log lines therefore interleave record by record as in the original.
     *
     * @param rows the CSV rows keyed by header name
     * @return a new mutable list of new {@link LinkedHashMap} records; empty, without any lookup, when {@code rows}
     *         is empty
     * @throws NullPointerException if {@code rows} is {@code null} or holds a {@code null} row
     */
    public List<Map<String, Object>> map(List<Map<String, String>> rows) {
        Objects.requireNonNull(rows, "rows");
        List<Map<String, Object>> accounts = new ArrayList<>(rows.size());
        for (Map<String, String> row : rows) {
            // Unlike a lookup-free mapping, a null row cannot map to a record of nulls: LookUpSalesRegionFlow failed
            // on a null payload, so the whole transformation failed, and the lookup service rejects null likewise.
            Objects.requireNonNull(row, "rows must not hold a null row: the region lookup fails on a null record");
            Map<String, Object> account = new LinkedHashMap<>();
            account.put(NAME, row.get(CSV_COMPANY_NAME));
            account.put(BILLING_STREET, row.get(CSV_COMPANY_ADDRESS));
            account.put(BILLING_CITY, row.get(CSV_COMPANY_CITY));
            account.put(BILLING_STATE, upper(row.get(CSV_COMPANY_STATE)));
            account.put(BILLING_POSTAL_CODE, row.get(CSV_COMPANY_ZIP));
            Map<String, String> region = salesRegionLookupService.lookUpSalesRegion(row);
            account.put(REGION, region.get(SalesRegionLookupService.REGION_KEY));
            accounts.add(account);
        }
        return accounts;
    }

    /**
     * DataWeave's {@code upper}, locale-independent; {@code null} stays {@code null}.
     */
    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }
}
