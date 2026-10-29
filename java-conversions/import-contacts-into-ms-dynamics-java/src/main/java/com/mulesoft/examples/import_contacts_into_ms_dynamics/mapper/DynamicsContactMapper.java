/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_ms_dynamics.mapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

/**
 * Hand-written owner of the contact transformation of {@code import-contacts-into-ms-dynamics.xml}.
 *
 * <p>{@link #map(List)} reimplements the {@code CSV To Map} script (line 9 of the original flow file):
 * <pre>
 * %dw 1.0
 * %output application/java
 * ---
 * payload map { firstname: $.firstname, lastname: $.surname, businessphone: $.phone, email: $.email }
 * </pre>
 * {@code %output application/java} produced a {@code List} of {@code LinkedHashMap}s in the declared key order, and
 * the same types and order are returned here, so each record's {@code toString()} matches the original's.
 *
 * <p>{@link #toContactAttributes(Map)} reproduces the attribute set the {@code For Each} scope passed to the
 * {@code Create Contact} operation ({@code dynamicscrm:create logicalName="contact"}, lines 21-30), keyed by the
 * Dataverse {@code contact} column names.
 *
 * <p>Values pass through as the CSV reader produced them: no trimming, number coercion or case change, so a phone
 * number such as {@code 096548763} keeps its leading zero. The class is stateless and therefore thread-safe.
 */
@Component
public class DynamicsContactMapper {

    /** CSV column holding the first name; also the record and attribute key of the same name. */
    public static final String CSV_FIRSTNAME = "firstname";

    /** CSV column holding the last name. */
    public static final String CSV_SURNAME = "surname";

    /** CSV column holding the phone number. */
    public static final String CSV_PHONE = "phone";

    /** CSV column holding the e-mail address. */
    public static final String CSV_EMAIL = "email";

    /** Record key of the first name. */
    public static final String FIRSTNAME = "firstname";

    /** Record key of the last name, read from the {@code surname} column. */
    public static final String LASTNAME = "lastname";

    /** Record key of the phone number, read from the {@code phone} column. */
    public static final String BUSINESSPHONE = "businessphone";

    /** Record key of the e-mail address. */
    public static final String EMAIL = "email";

    /** Dataverse {@code contact} column receiving the first name. */
    public static final String ATTRIBUTE_FIRSTNAME = "firstname";

    /** Dataverse {@code contact} column receiving the last name. */
    public static final String ATTRIBUTE_LASTNAME = "lastname";

    /** Dataverse {@code contact} column receiving the e-mail address. */
    public static final String ATTRIBUTE_EMAILADDRESS1 = "emailaddress1";

    /** Dataverse {@code contact} column receiving the phone number. */
    public static final String ATTRIBUTE_TELEPHONE1 = "telephone1";

    /**
     * Maps CSV rows to contact records, one record per row in input order.
     *
     * <p>Each record holds exactly {@code firstname}, {@code lastname}, {@code businessphone} and {@code email}, in
     * that order. A column absent from a row yields a {@code null} value under its key, and a {@code null} row yields
     * a record of four {@code null} values, as DataWeave's null-safe {@code $.x} selector does. Columns the script
     * does not name are ignored, and the input is never modified.
     *
     * @param rows the CSV rows keyed by header name; {@code null} or empty yields an empty list
     * @return a new mutable list of new {@link LinkedHashMap} records
     */
    public List<Map<String, Object>> map(List<Map<String, String>> rows) {
        if (rows == null || rows.isEmpty()) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> records = new ArrayList<>(rows.size());
        for (Map<String, String> row : rows) {
            Map<String, String> source = row == null ? Collections.emptyMap() : row;
            Map<String, Object> contact = new LinkedHashMap<>();
            contact.put(FIRSTNAME, source.get(CSV_FIRSTNAME));
            contact.put(LASTNAME, source.get(CSV_SURNAME));
            contact.put(BUSINESSPHONE, source.get(CSV_PHONE));
            contact.put(EMAIL, source.get(CSV_EMAIL));
            records.add(contact);
        }
        return records;
    }

    /**
     * Builds the Dataverse {@code contact} attributes for one record produced by {@link #map(List)}.
     *
     * <p>The result holds exactly {@code firstname}, {@code lastname}, {@code emailaddress1} and {@code telephone1},
     * in that order. A {@code null} value is kept under its key, because the original passed each
     * {@code #[payload.x]} expression unconditionally; the input is never modified.
     *
     * @param record a contact record keyed as {@link #map(List)} produces it
     * @return a new {@link LinkedHashMap} of contact attributes
     * @throws NullPointerException if {@code record} is {@code null}
     */
    public Map<String, Object> toContactAttributes(Map<String, Object> record) {
        Objects.requireNonNull(record, "record");
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(ATTRIBUTE_FIRSTNAME, record.get(FIRSTNAME));
        attributes.put(ATTRIBUTE_LASTNAME, record.get(LASTNAME));
        attributes.put(ATTRIBUTE_EMAILADDRESS1, record.get(EMAIL));
        attributes.put(ATTRIBUTE_TELEPHONE1, record.get(BUSINESSPHONE));
        return attributes;
    }
}
