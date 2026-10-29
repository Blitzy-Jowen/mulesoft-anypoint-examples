/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_a_csv_file_into_mongo_db.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit test of {@link CustomerDocumentMapper}, the hand-written owner of the {@code CSV to Map} script of
 * {@code csv-to-mongodb.xml}: {@code payload map { firstname: $.firstname, surname: $.surname, phone: $.phone,
 * email: $.email }} under {@code %output application/java}.
 *
 * <p>Inputs are built inline as the CSV reader delivers them, one {@link LinkedHashMap} per row keyed by header name;
 * the sample record is the one of {@code input.csv}. No resource, CSV reader or Spring context is used, and expected
 * keys and values are literals, so they are checked independently of the mapper's constants.
 */
class CustomerDocumentMapperTest {

    /** The document keys of the script, in its declaration order. */
    private static final List<String> DOCUMENT_KEYS = List.of("firstname", "surname", "phone", "email");

    private final CustomerDocumentMapper mapper = new CustomerDocumentMapper();

    // ---------------------------------------------------------------- shape of a document

    @Test
    @DisplayName("The input.csv record maps to its customer document, a LinkedHashMap inside an ArrayList")
    void mapsInputCsvRecordToCustomerDocument() {
        List<Map<String, Object>> result = mapper.map(List.of(john()));

        assertInstanceOf(ArrayList.class, result);
        assertEquals(1, result.size());
        Map<String, Object> document = result.get(0);
        assertInstanceOf(LinkedHashMap.class, document);
        assertEquals(Map.of(
                "firstname", "John",
                "surname", "Doe",
                "phone", "096548763",
                "email", "john.doe@texasComp.com"), document);
    }

    @Test
    @DisplayName("Keys follow the script order whatever the column order, as the 'saving objects' log line shows")
    void keepsDataWeaveKeyOrder() {
        Map<String, String> reordered = row(
                "email", "john.doe@texasComp.com",
                "phone", "096548763",
                "surname", "Doe",
                "firstname", "John");

        List<Map<String, Object>> result = mapper.map(List.of(reordered));

        assertEquals(DOCUMENT_KEYS, new ArrayList<>(result.get(0).keySet()));
        assertEquals("[{firstname=John, surname=Doe, phone=096548763, email=john.doe@texasComp.com}]",
                result.toString());
    }

    // ---------------------------------------------------------------- value fidelity

    @Test
    @DisplayName("Values stay the reader's Strings: no number coercion, no trimming, references copied as they are")
    void keepsValuesAsUntouchedStrings() {
        // Built at run time so the identity check cannot pass through string-literal interning.
        String phone = new StringBuilder("0965").append("48763").toString();
        Map<String, String> row = row(
                "firstname", " John ",
                "surname", "",
                "phone", phone,
                "email", "john.doe@texasComp.com ");

        Map<String, Object> document = mapper.map(List.of(row)).get(0);

        assertInstanceOf(String.class, document.get("phone"));
        assertEquals("096548763", document.get("phone"));
        assertSame(phone, document.get("phone"));
        assertEquals(" John ", document.get("firstname"));
        assertEquals("", document.get("surname"));
        assertEquals("john.doe@texasComp.com ", document.get("email"));
    }

    @Test
    @DisplayName("A column absent from a row keeps its key with a null value, like DataWeave's $.x")
    void missingColumnGivesNullValue() {
        Map<String, String> withoutPhone = row(
                "firstname", "John",
                "surname", "Doe",
                "email", "john.doe@texasComp.com");
        Map<String, String> emailOnly = row("email", "john.doe@texasComp.com");

        List<Map<String, Object>> result = mapper.map(List.of(withoutPhone, emailOnly));

        Map<String, Object> noPhone = result.get(0);
        assertEquals(DOCUMENT_KEYS, new ArrayList<>(noPhone.keySet()));
        assertTrue(noPhone.containsKey("phone"));
        assertNull(noPhone.get("phone"));
        assertEquals("John", noPhone.get("firstname"));
        assertEquals("Doe", noPhone.get("surname"));
        assertEquals("john.doe@texasComp.com", noPhone.get("email"));

        Map<String, Object> onlyEmail = result.get(1);
        assertEquals(DOCUMENT_KEYS, new ArrayList<>(onlyEmail.keySet()));
        for (String absent : List.of("firstname", "surname", "phone")) {
            assertTrue(onlyEmail.containsKey(absent), absent + " must be present");
            assertNull(onlyEmail.get(absent), absent + " must be null");
        }
        assertEquals("john.doe@texasComp.com", onlyEmail.get("email"));
    }

    @Test
    @DisplayName("Columns the script does not name (company, city) are not copied")
    void dropsExtraColumns() {
        Map<String, String> row = john();
        row.put("company", "texasComp");
        row.put("city", "Austin");

        Map<String, Object> document = mapper.map(List.of(row)).get(0);

        assertEquals(DOCUMENT_KEYS, new ArrayList<>(document.keySet()));
        assertFalse(document.containsKey("company"));
        assertFalse(document.containsKey("city"));
    }

    // ---------------------------------------------------------------- collection handling

    @Test
    @DisplayName("Each row gives one document, in input order")
    void oneDocumentPerRowInInputOrder() {
        List<Map<String, String>> rows = List.of(
                john(),
                row("firstname", "Jane", "surname", "Roe", "phone", "0011223344", "email", "jane.roe@example.com"),
                row("firstname", "Max", "surname", "Mustermann", "phone", "555-0100", "email", "max@example.org"));

        List<Map<String, Object>> result = mapper.map(rows);

        assertEquals(3, result.size());
        assertEquals("John", result.get(0).get("firstname"));
        assertEquals("Jane", result.get(1).get("firstname"));
        assertEquals("Max", result.get(2).get("firstname"));
        assertEquals("Roe", result.get(1).get("surname"));
        assertEquals("555-0100", result.get(2).get("phone"));
    }

    @Test
    @DisplayName("An empty input, immutable or mutable, gives an empty, non-null list")
    void emptyInputGivesEmptyList() {
        List<Map<String, Object>> fromImmutable = mapper.map(List.of());
        List<Map<String, Object>> fromMutable = mapper.map(new ArrayList<>());

        assertNotNull(fromImmutable);
        assertTrue(fromImmutable.isEmpty());
        assertNotNull(fromMutable);
        assertTrue(fromMutable.isEmpty());
    }

    @Test
    @DisplayName("The result is a new list of new maps, unaffected by later changes to the input")
    void returnsNewListAndNewMaps() {
        Map<String, String> johnRow = john();
        Map<String, String> janeRow = row("firstname", "Jane", "surname", "Roe");
        List<Map<String, String>> rows = new ArrayList<>(List.of(johnRow, janeRow));

        List<Map<String, Object>> result = mapper.map(rows);

        assertNotSame(rows, result);
        assertNotSame(johnRow, result.get(0));
        assertNotSame(janeRow, result.get(1));

        rows.add(row("firstname", "Max"));
        johnRow.put("firstname", "Changed");

        assertEquals(2, result.size());
        assertEquals("John", result.get(0).get("firstname"));
    }

    // ---------------------------------------------------------------- fixtures

    /** The data record of {@code input.csv}, keyed by its header {@code firstname,surname,phone,email}. */
    private static Map<String, String> john() {
        return row(
                "firstname", "John",
                "surname", "Doe",
                "phone", "096548763",
                "email", "john.doe@texasComp.com");
    }

    /** A mutable CSV row from alternating column names and values, in the given column order. */
    private static Map<String, String> row(String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("row needs column/value pairs, got " + keyValues.length + " strings");
        }
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put(keyValues[i], keyValues[i + 1]);
        }
        return row;
    }
}
