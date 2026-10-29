/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_a_csv_file_into_mongo_db.mapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Hand-written owner of the {@code CSV to Map} transformation of {@code csv-to-mongodb.xml}, the {@code %dw 1.0}
 * script starting at line 11 of the original flow file:
 * <pre>
 * %dw 1.0
 * %output application/java
 * ---
 * payload map
 * {
 *     firstname: $.firstname,
 *     surname: $.surname,
 *     phone: $.phone,
 *     email: $.email
 * }
 * </pre>
 *
 * <p>{@code %output application/java} produced an {@link ArrayList} of {@link LinkedHashMap}s in the declared key
 * order. The same types are returned here because the flow logs the list itself ({@code saving objects: #[payload]}),
 * so its {@code toString()} must read
 * {@code [{firstname=John, surname=Doe, phone=096548763, email=john.doe@texasComp.com}]} for the sample input.
 *
 * <p>Values pass through as the CSV reader produced them, as strings: no trimming or number coercion, so a phone
 * number such as {@code 096548763} keeps its leading zero. The class is stateless and therefore thread-safe.
 */
@Component
public class CustomerDocumentMapper {

    /** CSV column and document key of the first name. */
    public static final String FIRSTNAME = "firstname";

    /** CSV column and document key of the last name. */
    public static final String SURNAME = "surname";

    /** CSV column and document key of the phone number. */
    public static final String PHONE = "phone";

    /** CSV column and document key of the e-mail address. */
    public static final String EMAIL = "email";

    /**
     * Maps CSV rows to customer documents, one document per row in input order.
     *
     * <p>Each document holds exactly {@code firstname}, {@code surname}, {@code phone} and {@code email}, in that
     * order. A column absent from a row is kept as its key with a {@code null} value, as DataWeave's {@code $.x}
     * selector yields {@code null} for a missing key; columns the script does not name are not copied.
     *
     * <p>Every call returns new mutable collections and never modifies or retains the input, so the caller may hand
     * each document to the Mongo save, which may add an {@code _id} entry to it.
     *
     * @param rows the CSV rows keyed by header name, as {@link DwCsvReader} returns them
     * @return a new mutable list of new {@link LinkedHashMap} documents; empty when {@code rows} is empty
     */
    public List<Map<String, Object>> map(List<Map<String, String>> rows) {
        List<Map<String, Object>> documents = new ArrayList<>(rows.size());
        for (Map<String, String> row : rows) {
            Map<String, Object> document = new LinkedHashMap<>();
            document.put(FIRSTNAME, row.get(FIRSTNAME));
            document.put(SURNAME, row.get(SURNAME));
            document.put(PHONE, row.get(PHONE));
            document.put(EMAIL, row.get(EMAIL));
            documents.add(document);
        }
        return documents;
    }
}
