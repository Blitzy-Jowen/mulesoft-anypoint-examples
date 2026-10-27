/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_salesforce.mapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

/**
 * Hand-written owner of the {@code Map CSV to Contacts} transformation of {@code contacts-to-SFDC.xml}, the only
 * transformation of the {@code Contacts_to_SFDC} flow.
 *
 * <p>{@link #map(List)} reimplements the {@code %dw 1.0} script whose header is line 12 of
 * {@code import-contacts-into-salesforce/src/main/app/contacts-to-SFDC.xml}:
 * <pre>
 * %dw 1.0
 * %output application/java
 * ---
 * payload map { FirstName: $.firstname, LastName : $.surname, Email : $.email, Phone : $.phone }
 * </pre>
 * Its output was the {@code #[payload]} of {@code sfdc:create type="Contact"}. The four mappings are:
 * <ul>
 *   <li>{@code FirstName} from the {@code firstname} column;</li>
 *   <li>{@code LastName} from the {@code surname} column;</li>
 *   <li>{@code Email} from the {@code email} column;</li>
 *   <li>{@code Phone} from the {@code phone} column.</li>
 * </ul>
 * {@code %output application/java} produced a {@code List} of {@code LinkedHashMap}s in the declared key order, and
 * the same types and order are returned here. Values are the strings the CSV reader produced, copied as they are:
 * the script coerces nothing, so {@code 096548763} keeps its leading zero and nothing is trimmed or re-cased.
 *
 * <p>The records go to Salesforce unchanged: {@code SalesforcePartnerClient.create(String, List)} takes the object
 * type and these maps and builds the WSC {@code SObject}s itself, so this mapper has no {@code SObject} conversion
 * and depends on no Salesforce type.
 *
 * <p>The class is stateless and therefore thread-safe.
 */
@Component
public class ContactMapper {

    /** CSV column holding the first name. */
    public static final String CSV_FIRSTNAME = "firstname";

    /** CSV column holding the last name. */
    public static final String CSV_SURNAME = "surname";

    /** CSV column holding the e-mail address. */
    public static final String CSV_EMAIL = "email";

    /** CSV column holding the phone number. */
    public static final String CSV_PHONE = "phone";

    /** Contact field receiving the {@code firstname} column. */
    public static final String FIRST_NAME = "FirstName";

    /** Contact field receiving the {@code surname} column. */
    public static final String LAST_NAME = "LastName";

    /** Contact field receiving the {@code email} column. */
    public static final String EMAIL = "Email";

    /** Contact field receiving the {@code phone} column. */
    public static final String PHONE = "Phone";

    /**
     * Creates a mapper; it holds no state, so one instance serves every caller.
     */
    public ContactMapper() {
    }

    /**
     * Maps CSV rows to Contact records, one record per row in input order.
     *
     * <p>Each record holds exactly {@code FirstName}, {@code LastName}, {@code Email} and {@code Phone}, in that
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
        List<Map<String, Object>> contacts = new ArrayList<>(rows.size());
        for (Map<String, String> row : rows) {
            Map<String, String> source = row == null ? Collections.emptyMap() : row;
            Map<String, Object> contact = new LinkedHashMap<>();
            contact.put(FIRST_NAME, source.get(CSV_FIRSTNAME));
            contact.put(LAST_NAME, source.get(CSV_SURNAME));
            contact.put(EMAIL, source.get(CSV_EMAIL));
            contact.put(PHONE, source.get(CSV_PHONE));
            contacts.add(contact);
        }
        return contacts;
    }
}
