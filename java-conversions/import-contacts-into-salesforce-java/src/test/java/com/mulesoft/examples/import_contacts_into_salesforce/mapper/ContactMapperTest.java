/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_salesforce.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit test of {@link ContactMapper}, the hand-written owner of the {@code Map CSV to Contacts} script of
 * {@code contacts-to-SFDC.xml}: {@code payload map { FirstName: $.firstname, LastName : $.surname, Email : $.email,
 * Phone : $.phone }} under {@code %output application/java}.
 *
 * <p>Inputs are built inline as the CSV reader delivers them, one {@link LinkedHashMap} per row in the header order
 * of {@code contacts.csv} ({@code firstname,surname,phone,email}); no resource, CSV reader or Spring context is used.
 * Expected Contact names and values are written as literals, so they are checked independently of the mapper's
 * constants.
 */
class ContactMapperTest {

    /** The Contact fields of the script, in its declaration order (which differs from the CSV column order). */
    private static final List<String> CONTACT_FIELDS = List.of("FirstName", "LastName", "Email", "Phone");

    private final ContactMapper mapper = new ContactMapper();

    // key names and order

    @Test
    @DisplayName("Each record holds exactly FirstName, LastName, Email, Phone in that order, as a LinkedHashMap")
    void recordKeysFollowTheScriptOrder() {
        List<Map<String, Object>> contacts = mapper.map(List.of(john()));

        assertEquals(1, contacts.size());
        Map<String, Object> contact = contacts.get(0);
        assertInstanceOf(LinkedHashMap.class, contact);
        assertEquals(CONTACT_FIELDS, new ArrayList<>(contact.keySet()));
    }

    @Test
    @DisplayName("The contacts.csv rows map to John Doe and Jane Doe, in input order (original IT assertions)")
    void mapsTheOriginalContactsInInputOrder() {
        List<Map<String, Object>> contacts = mapper.map(List.of(john(), jane()));

        assertEquals(2, contacts.size());
        assertContact(contacts.get(0), "John", "Doe", "john.doe@texasComp.com", "096548763");
        assertContact(contacts.get(1), "Jane", "Doe", "jane.doe@texasComp.com", "091558780");
    }

    @Test
    @DisplayName("surname becomes LastName and firstname becomes FirstName; no CSV column name is kept")
    void renamesColumnsToContactFields() {
        Map<String, Object> contact =
                mapper.map(List.of(csvRow("Anna", "Smith", "0123", "anna.smith@example.com"))).get(0);

        assertEquals("Smith", contact.get("LastName"));
        assertEquals("Anna", contact.get("FirstName"));
        for (String column : List.of("firstname", "surname", "phone", "email")) {
            assertFalse(contact.containsKey(column), "CSV column " + column + " must not reach the Contact");
        }
    }

    // absent values

    @ParameterizedTest(name = "row without {0} -> {1} kept with a null value")
    @CsvSource({"phone, Phone", "email, Email", "firstname, FirstName", "surname, LastName"})
    @DisplayName("A column missing from a row gives its field a null value; the four keys stay in order")
    void missingColumnGivesNullField(String column, String field) {
        Map<String, String> row = john();
        row.remove(column);

        Map<String, Object> contact = mapper.map(List.of(row)).get(0);

        assertTrue(contact.containsKey(field));
        assertNull(contact.get(field));
        assertEquals(CONTACT_FIELDS, new ArrayList<>(contact.keySet()));
        Map<String, Object> expected = contact("John", "Doe", "john.doe@texasComp.com", "096548763");
        expected.put(field, null);
        assertEquals(expected, contact);
    }

    @Test
    @DisplayName("An empty row and a null row each give the four keys, all null, like DataWeave's null-safe $.x")
    void emptyOrNullRowGivesFourNullFields() {
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(new LinkedHashMap<>());
        rows.add(null);

        List<Map<String, Object>> contacts = mapper.map(rows);

        assertEquals(2, contacts.size());
        for (Map<String, Object> contact : contacts) {
            assertContact(contact, null, null, null, null);
        }
    }

    // value fidelity

    @Test
    @DisplayName("Values stay the reader's Strings: no coercion, trimming, re-casing, or empty-to-null")
    void valuesArePassedThroughUntouched() {
        Map<String, Object> contact =
                mapper.map(List.of(csvRow(" Jane ", "", "096548763", "john.doe@texasComp.com"))).get(0);

        assertInstanceOf(String.class, contact.get("Phone"));
        assertContact(contact, " Jane ", "", "john.doe@texasComp.com", "096548763");
    }

    // collection handling

    @Test
    @DisplayName("An empty input, immutable or mutable, gives an empty, non-null list")
    void emptyInputGivesEmptyList() {
        List<Map<String, String>> mutableEmpty = new ArrayList<>();

        List<Map<String, Object>> fromImmutable = mapper.map(List.of());
        List<Map<String, Object>> fromMutable = mapper.map(mutableEmpty);

        assertNotNull(fromImmutable);
        assertTrue(fromImmutable.isEmpty());
        assertNotNull(fromMutable);
        assertTrue(fromMutable.isEmpty());
        assertNotSame(mutableEmpty, fromMutable);
    }

    @Test
    @DisplayName("A null row list is rejected with a NullPointerException naming rows")
    void nullInputIsRejected() {
        NullPointerException failure = assertThrows(NullPointerException.class, () -> mapper.map(null));

        assertEquals("rows", failure.getMessage());
    }

    @Test
    @DisplayName("Columns the script does not name (company, Id, column_4) are ignored")
    void extraColumnsAreIgnored() {
        Map<String, String> row = john();
        row.put("company", "Acme");
        row.put("Id", "003xx");
        row.put("column_4", "z");

        Map<String, Object> contact = mapper.map(List.of(row)).get(0);

        assertEquals(CONTACT_FIELDS, new ArrayList<>(contact.keySet()));
        assertFalse(contact.containsKey("company"));
        assertFalse(contact.containsKey("Id"));
        assertFalse(contact.containsKey("column_4"));
        assertContact(contact, "John", "Doe", "john.doe@texasComp.com", "096548763");
    }

    @Test
    @DisplayName("The input list and rows are not modified, and the records share no state with them")
    void inputIsNotMutated() {
        Map<String, String> row = john();
        row.put("company", "Acme");
        Map<String, String> before = new LinkedHashMap<>(row);
        List<String> keysBefore = new ArrayList<>(row.keySet());
        List<Map<String, String>> rows = new ArrayList<>(List.of(row));

        List<Map<String, Object>> contacts = mapper.map(rows);
        contacts.get(0).put("FirstName", "Changed");

        assertEquals(1, rows.size());
        assertSame(row, rows.get(0));
        assertEquals(before, row);
        assertEquals(keysBefore, new ArrayList<>(row.keySet()));
    }

    // fixtures and assertions

    /** A CSV row keyed by the {@code contacts.csv} header, in header order. */
    private static Map<String, String> csvRow(String firstname, String surname, String phone, String email) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("firstname", firstname);
        row.put("surname", surname);
        row.put("phone", phone);
        row.put("email", email);
        return row;
    }

    /** First data row of {@code contacts.csv}. */
    private static Map<String, String> john() {
        return csvRow("John", "Doe", "096548763", "john.doe@texasComp.com");
    }

    /** Second data row of {@code contacts.csv}. */
    private static Map<String, String> jane() {
        return csvRow("Jane", "Doe", "091558780", "jane.doe@texasComp.com");
    }

    /** The Contact record the script produces for the given values, in its field order. */
    private static Map<String, Object> contact(String firstName, String lastName, String email, String phone) {
        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("FirstName", firstName);
        contact.put("LastName", lastName);
        contact.put("Email", email);
        contact.put("Phone", phone);
        return contact;
    }

    /** Asserts the record's entries, keys and values, in order. */
    private static void assertContact(Map<String, Object> actual, String firstName, String lastName, String email,
            String phone) {
        assertIterableEquals(contact(firstName, lastName, email, phone).entrySet(), actual.entrySet());
    }
}
