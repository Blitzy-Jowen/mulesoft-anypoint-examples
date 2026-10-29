/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_leads_into_salesforce.mapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

/**
 * Hand-written owner of the {@code Transform CSV to Maps} transformation of {@code import-leads-into-salesforce.xml},
 * the input phase of the {@code CreateLeadsBatch} batch job.
 *
 * <p>{@link #map(List)} reimplements the {@code %dw 1.0} script whose header is line 13 of
 * {@code import-leads-into-salesforce/src/main/app/import-leads-into-salesforce.xml}:
 * <pre>
 * %dw 1.0
 * %output application/java
 * ---
 * payload map { Company : $.Company, Email : $.Email, FirstName : $.FirstName, LastName : $.LastName }
 * </pre>
 * Each output map became one batch record: {@code LeadExistsStep} reads its {@code Email}, {@code LeadInsertStep}
 * logs it as {@code Got Record #[payload], ...} and hands each commit group to {@code sfdc:create type="Lead"}. The
 * four mappings copy the CSV column of the same name:
 * <ul>
 *   <li>{@code Company} from the {@code Company} column;</li>
 *   <li>{@code Email} from the {@code Email} column;</li>
 *   <li>{@code FirstName} from the {@code FirstName} column;</li>
 *   <li>{@code LastName} from the {@code LastName} column.</li>
 * </ul>
 * {@code %output application/java} produced a {@code List} of {@code LinkedHashMap}s in the declared key order, and
 * the same types and order are returned here, so each record's {@code toString()} reads as the original logger
 * printed it, for example
 * {@code {Company=Eu Odio PC, Email=sem.egestas@mollis.org, FirstName=Cole, LastName=Burks}}. Values are the strings
 * the CSV reader produced, copied as they are: the script coerces nothing, so nothing is trimmed or re-cased. Columns
 * the script does not name, such as {@code Birthday} in {@code leads.csv}, are dropped.
 *
 * <p>The records go to Salesforce unchanged: {@code SalesforcePartnerClient.create(String, List)} takes the object
 * type and these maps and builds the WSC {@code SObject}s itself, so this mapper has no {@code SObject} conversion
 * and depends on no Salesforce type.
 *
 * <p>The class is stateless and therefore thread-safe.
 */
@Component
public class LeadMapper {

    /** Lead field receiving the {@code Company} column. */
    public static final String COMPANY = "Company";

    /** Lead field receiving the {@code Email} column. */
    public static final String EMAIL = "Email";

    /** Lead field receiving the {@code FirstName} column. */
    public static final String FIRST_NAME = "FirstName";

    /** Lead field receiving the {@code LastName} column. */
    public static final String LAST_NAME = "LastName";

    /**
     * The fields every record holds, in the script's key order. Each is read from the CSV column of the same name.
     */
    public static final List<String> FIELDS = List.of(COMPANY, EMAIL, FIRST_NAME, LAST_NAME);

    /**
     * Creates a mapper; it holds no state, so one instance serves every caller.
     */
    public LeadMapper() {
    }

    /**
     * Maps CSV rows to Lead records, one record per row in input order.
     *
     * <p>Each record holds exactly {@code Company}, {@code Email}, {@code FirstName} and {@code LastName}, in that
     * order. A column absent from a row yields a {@code null} value under its key, and a {@code null} row yields a
     * record of four {@code null} values, as DataWeave's null-safe {@code $.x} selector does. Columns the script does
     * not name are ignored; rows are neither filtered, de-duplicated nor validated, and the input is never modified.
     *
     * @param rows the CSV rows keyed by header name
     * @return a new mutable list of new {@link LinkedHashMap} records; empty when {@code rows} is empty
     * @throws NullPointerException if {@code rows} is {@code null}
     */
    public List<Map<String, Object>> map(List<Map<String, String>> rows) {
        Objects.requireNonNull(rows, "rows");
        List<Map<String, Object>> leads = new ArrayList<>(rows.size());
        for (Map<String, String> row : rows) {
            Map<String, String> source = row == null ? Collections.emptyMap() : row;
            Map<String, Object> lead = new LinkedHashMap<>();
            for (String field : FIELDS) {
                lead.put(field, source.get(field));
            }
            leads.add(lead);
        }
        return leads;
    }
}
