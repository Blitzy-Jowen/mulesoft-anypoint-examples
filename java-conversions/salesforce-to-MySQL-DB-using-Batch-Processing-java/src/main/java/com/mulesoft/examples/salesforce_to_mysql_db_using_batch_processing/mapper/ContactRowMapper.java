/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.mapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

/**
 * Hand-written reimplementation of the {@code %dw 1.0} script "Transform SFDC fields to DB fields"
 * ({@code salesforce-to-MySQL-DB-using-Batch-Processing/src/main/app/salesforce-to-database.xml}, lines 15-23, batch
 * step {@code queryExistingContactInDbStep}), of which this class is the sole owner:
 * <pre>
 * %dw 1.0
 * %output application/java
 * ---
 * { "email" : payload.Email, "first_name" : payload.FirstName,
 *   "last_name" : payload.LastName, "last_modified" : payload.LastModifiedDate }
 * </pre>
 * The input is one Salesforce Contact record of the poll query, a map keyed by the Salesforce field names. The result
 * is the {@code application/java} object the script produced: a map holding exactly the four keys, in the script's
 * order, each value the same object the record carried. No value is converted, so a {@code LastModifiedDate} reaches
 * the batch steps with the type Salesforce delivered.
 * <p>
 * Transformation only: no I/O, no configuration, no logging. Stateless and thread-safe, as the batch job may map
 * records concurrently.
 */
@Component
public class ContactRowMapper {

    /** Output key read by the database lookup, insert and update. */
    public static final String EMAIL = "email";

    /** Output key written to {@code contact.first_name}. */
    public static final String FIRST_NAME = "first_name";

    /** Output key written to {@code contact.last_name}. */
    public static final String LAST_NAME = "last_name";

    /** Output key carrying the record's modification time; no SQL statement of the batch job reads it. */
    public static final String LAST_MODIFIED = "last_modified";

    private static final String SOURCE_EMAIL = "Email";
    private static final String SOURCE_FIRST_NAME = "FirstName";
    private static final String SOURCE_LAST_NAME = "LastName";
    private static final String SOURCE_LAST_MODIFIED_DATE = "LastModifiedDate";

    /**
     * Maps one Salesforce Contact record to the database-shaped record of the batch job.
     *
     * <p>All four output keys are always present: a field the record lacks, or holds as {@code null}, yields its key
     * with a {@code null} value, as the script's {@code application/java} output kept it. Every other field of the
     * record ({@code type}, {@code Id}, ...) is dropped. The input map is not modified.
     *
     * @param contact the Salesforce Contact record, keyed by Salesforce field name
     * @return a new mutable map with the keys {@value #EMAIL}, {@value #FIRST_NAME}, {@value #LAST_NAME} and
     *         {@value #LAST_MODIFIED}, iterated in that order
     * @throws NullPointerException if {@code contact} is {@code null}
     */
    public Map<String, Object> map(Map<String, Object> contact) {
        Objects.requireNonNull(contact, "Salesforce contact record must not be null");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(EMAIL, contact.get(SOURCE_EMAIL));
        row.put(FIRST_NAME, contact.get(SOURCE_FIRST_NAME));
        row.put(LAST_NAME, contact.get(SOURCE_LAST_NAME));
        row.put(LAST_MODIFIED, contact.get(SOURCE_LAST_MODIFIED_DATE));
        return row;
    }
}
