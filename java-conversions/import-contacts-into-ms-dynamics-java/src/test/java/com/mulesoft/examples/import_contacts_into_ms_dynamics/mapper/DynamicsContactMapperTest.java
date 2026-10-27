/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_ms_dynamics.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test of {@link DynamicsContactMapper}, the owner of the {@code CSV To Map} script of
 * {@code import-contacts-into-ms-dynamics.xml} (line 9) and of the attribute set its {@code Create Contact}
 * operation sends for each record (lines 21-30):
 * <pre>
 * payload map { firstname: $.firstname, lastname: $.surname, businessphone: $.phone, email: $.email }
 * firstname = #[payload.firstname], lastname = #[payload.lastname],
 * emailaddress1 = #[payload.email], telephone1 = #[payload.businessphone]
 * </pre>
 *
 * <p>Rows are built inline in the form the CSV reader delivers them: {@link LinkedHashMap}s of string values keyed by
 * the header of {@code contacts.csv} ({@code firstname,surname,phone,email}), holding that file's two records. No
 * resource, CSV reader, Spring context or mock is involved, so the mapper is tested on its own.
 */
class DynamicsContactMapperTest {

    private static final String JOHN_EMAIL = "john.doe@texasComp.com";
    private static final String JANE_EMAIL = "jane.doe@texasComp.com";
    private static final String JOHN_PHONE = "096548763";
    private static final String JANE_PHONE = "091558780";

    private final DynamicsContactMapper mapper = new DynamicsContactMapper();

    // ---------------------------------------------------------------- map

    @Test
    @DisplayName("The contacts.csv rows map to one record per row, in row order, with the script's keys and values")
    void mapsContactsCsvRowsInRowOrder() {
        List<Map<String, Object>> records = mapper.map(List.of(john(), jane()));

        assertThat(records).hasSize(2);
        assertRecord(records.get(0), "John", "Doe", JOHN_PHONE, JOHN_EMAIL);
        assertRecord(records.get(1), "Jane", "Doe", JANE_PHONE, JANE_EMAIL);
        assertThat(records.get(0).get(DynamicsContactMapper.BUSINESSPHONE)).isInstanceOf(String.class);
        assertThat(records.get(0))
                .hasToString("{firstname=John, lastname=Doe, businessphone=096548763, email=" + JOHN_EMAIL + "}");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("No rows, null or empty, map to a new, empty and mutable list")
    void mapsNoRowsToNewEmptyMutableList(List<Map<String, String>> rows) {
        List<Map<String, Object>> records = mapper.map(rows);

        assertThat(records).isNotNull().isNotSameAs(rows).isEmpty();
        records.add(new LinkedHashMap<>());
        assertThat(records).hasSize(1);
    }

    @Test
    @DisplayName("A null row maps to a record of four null values, keeping its place in the list")
    void mapsNullRowToRecordOfNulls() {
        List<Map<String, String>> rows = Arrays.asList(
                null,
                csvRow("firstname", "Jane", "surname", "Doe", "phone", "091558780", "email", JANE_EMAIL));

        List<Map<String, Object>> records = mapper.map(rows);

        assertThat(records).hasSize(2);
        assertRecord(records.get(0), null, null, null, null);
        assertRecord(records.get(1), "Jane", "Doe", "091558780", JANE_EMAIL);
    }

    @Test
    @DisplayName("A column absent from a row yields null under its key; lastname reads surname, never lastname")
    void mapsAbsentColumnsToNullValues() {
        List<Map<String, String>> rows = List.of(
                csvRow("firstname", "Ann", "surname", "Lee"),
                csvRow("firstname", "Bob", "lastname", "Ray", "businessphone", "555", "email", "bob@example.com"),
                csvRow());

        List<Map<String, Object>> records = mapper.map(rows);

        assertThat(records).hasSize(3);
        assertRecord(records.get(0), "Ann", "Lee", null, null);
        assertRecord(records.get(1), "Bob", null, null, "bob@example.com");
        assertRecord(records.get(2), null, null, null, null);
    }

    @Test
    @DisplayName("Columns the script does not name are dropped")
    void ignoresColumnsTheScriptDoesNotName() {
        List<Map<String, String>> rows = List.of(csvRow("company", "Texas Comp", "firstname", "John",
                "surname", "Doe", "phone", "096548763", "email", JOHN_EMAIL, "column_5", "extra"));

        List<Map<String, Object>> records = mapper.map(rows);

        assertThat(records).hasSize(1);
        assertRecord(records.get(0), "John", "Doe", "096548763", JOHN_EMAIL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"096548763", " John ", "", "Doe, John", "0.50", "Zo\u00EB"})
    @DisplayName("Values pass through as read: not trimmed, coerced, re-cased or nulled")
    void passesValuesThroughUnchanged(String value) {
        Map<String, String> row = csvRow("firstname", value, "surname", value, "phone", value, "email", value);

        Map<String, Object> record = mapper.map(List.of(row)).get(0);

        assertRecord(record, value, value, value, value);
        assertThat(record.values()).allSatisfy(actual -> assertThat(actual).isSameAs(value));
    }

    @Test
    @DisplayName("map leaves its input unchanged and returns new record maps")
    void mapDoesNotModifyItsInput() {
        Map<String, String> row = csvRow("firstname", "John", "surname", "Doe", "phone", "096548763",
                "email", JOHN_EMAIL, "company", "Texas Comp");
        List<Map<String, String>> rows = new ArrayList<>(List.of(row));
        Map<String, String> rowBefore = new LinkedHashMap<>(row);

        List<Map<String, Object>> records = mapper.map(rows);

        assertThat(rows).containsExactly(row);
        assertThat(row).isEqualTo(rowBefore);
        assertThat(new ArrayList<>(row.keySet())).isEqualTo(new ArrayList<>(rowBefore.keySet()));
        assertThat(records).isNotSameAs(rows);
        assertThat(records.get(0)).isNotSameAs(row);
    }

    // ---------------------------------------------------------------- toContactAttributes

    @Test
    @DisplayName("Each mapped contact, in For Each order, becomes firstname, lastname, emailaddress1, telephone1")
    void buildsCreateContactAttributesForEachMappedRecord() {
        List<Map<String, Object>> attributeSets = mapper.map(List.of(john(), jane())).stream()
                .map(mapper::toContactAttributes)
                .toList();

        assertThat(attributeSets).hasSize(2);
        assertAttributes(attributeSets.get(0), "John", "Doe", JOHN_EMAIL, JOHN_PHONE);
        assertAttributes(attributeSets.get(1), "Jane", "Doe", JANE_EMAIL, JANE_PHONE);
        assertThat(attributeSets.get(0)).hasToString(
                "{firstname=John, lastname=Doe, emailaddress1=" + JOHN_EMAIL + ", telephone1=096548763}");
    }

    @Test
    @DisplayName("Null and absent record values are sent as null attributes under their keys")
    void keepsNullAttributeValues() {
        Map<String, Object> recordOfNullRow = mapper.map(Collections.singletonList(null)).get(0);
        Map<String, Object> fromNullRow = mapper.toContactAttributes(recordOfNullRow);
        Map<String, Object> fromEmptyRecord = mapper.toContactAttributes(new LinkedHashMap<>());
        Map<String, Object> partial = new LinkedHashMap<>();
        partial.put(DynamicsContactMapper.FIRSTNAME, "Ann");
        partial.put(DynamicsContactMapper.LASTNAME, null);
        partial.put(DynamicsContactMapper.EMAIL, "ann@example.com");

        assertAttributes(fromNullRow, null, null, null, null);
        assertAttributes(fromEmptyRecord, null, null, null, null);
        assertAttributes(mapper.toContactAttributes(partial), "Ann", null, "ann@example.com", null);
    }

    @Test
    @DisplayName("toContactAttributes drops keys it does not send, leaves the record unchanged and returns a new map")
    void toContactAttributesIgnoresOtherKeysAndDoesNotModifyItsInput() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put(DynamicsContactMapper.EMAIL, JOHN_EMAIL);
        record.put(DynamicsContactMapper.BUSINESSPHONE, "096548763");
        record.put(DynamicsContactMapper.LASTNAME, "Doe");
        record.put(DynamicsContactMapper.FIRSTNAME, "John");
        record.put("company", "Texas Comp");
        Map<String, Object> recordBefore = new LinkedHashMap<>(record);

        Map<String, Object> attributes = mapper.toContactAttributes(record);

        assertAttributes(attributes, "John", "Doe", JOHN_EMAIL, "096548763");
        assertThat(attributes).isNotSameAs(record);
        assertThat(record).isEqualTo(recordBefore);
        assertThat(new ArrayList<>(record.keySet())).isEqualTo(new ArrayList<>(recordBefore.keySet()));
    }

    @Test
    @DisplayName("A null record is rejected with a NullPointerException naming it")
    void rejectsNullRecord() {
        assertThatNullPointerException()
                .isThrownBy(() -> mapper.toContactAttributes(null))
                .withMessage("record");
    }

    // ---------------------------------------------------------------- names

    @Test
    @DisplayName("Key constants carry the original CSV columns, script keys and Dataverse contact columns")
    void constantsCarryTheOriginalNames() {
        assertThat(DynamicsContactMapper.CSV_FIRSTNAME).isEqualTo("firstname");
        assertThat(DynamicsContactMapper.CSV_SURNAME).isEqualTo("surname");
        assertThat(DynamicsContactMapper.CSV_PHONE).isEqualTo("phone");
        assertThat(DynamicsContactMapper.CSV_EMAIL).isEqualTo("email");
        assertThat(DynamicsContactMapper.FIRSTNAME).isEqualTo("firstname");
        assertThat(DynamicsContactMapper.LASTNAME).isEqualTo("lastname");
        assertThat(DynamicsContactMapper.BUSINESSPHONE).isEqualTo("businessphone");
        assertThat(DynamicsContactMapper.EMAIL).isEqualTo("email");
        assertThat(DynamicsContactMapper.ATTRIBUTE_FIRSTNAME).isEqualTo("firstname");
        assertThat(DynamicsContactMapper.ATTRIBUTE_LASTNAME).isEqualTo("lastname");
        assertThat(DynamicsContactMapper.ATTRIBUTE_EMAILADDRESS1).isEqualTo("emailaddress1");
        assertThat(DynamicsContactMapper.ATTRIBUTE_TELEPHONE1).isEqualTo("telephone1");
    }

    // ---------------------------------------------------------------- helpers

    /** The first record of {@code contacts.csv}, the original IT's {@code EMAIL1} contact. */
    private static Map<String, String> john() {
        return csvRow("firstname", "John", "surname", "Doe", "phone", JOHN_PHONE, "email", JOHN_EMAIL);
    }

    /** The second record of {@code contacts.csv}, the original IT's {@code EMAIL2} contact. */
    private static Map<String, String> jane() {
        return csvRow("firstname", "Jane", "surname", "Doe", "phone", JANE_PHONE, "email", JANE_EMAIL);
    }

    /**
     * Builds a CSV row as the CSV reader returns it.
     *
     * @param keyValues alternating column names and values: name, value, name, value, ...
     */
    private static Map<String, String> csvRow(String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("keyValues must alternate column names and values");
        }
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put(keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    /** Asserts a {@link LinkedHashMap} record holding exactly the script's four keys, in order, with these values. */
    private static void assertRecord(Map<String, Object> record, String firstname, String lastname,
            String businessphone, String email) {
        assertThat(record).isInstanceOf(LinkedHashMap.class);
        assertThat(record.keySet()).containsExactly(DynamicsContactMapper.FIRSTNAME, DynamicsContactMapper.LASTNAME,
                DynamicsContactMapper.BUSINESSPHONE, DynamicsContactMapper.EMAIL);
        assertThat(record)
                .containsEntry(DynamicsContactMapper.FIRSTNAME, firstname)
                .containsEntry(DynamicsContactMapper.LASTNAME, lastname)
                .containsEntry(DynamicsContactMapper.BUSINESSPHONE, businessphone)
                .containsEntry(DynamicsContactMapper.EMAIL, email);
    }

    /** Asserts a {@link LinkedHashMap} holding exactly the four contact attributes, in order, with these values. */
    private static void assertAttributes(Map<String, Object> attributes, String firstname, String lastname,
            String emailaddress1, String telephone1) {
        assertThat(attributes).isInstanceOf(LinkedHashMap.class);
        assertThat(attributes.keySet()).containsExactly(DynamicsContactMapper.ATTRIBUTE_FIRSTNAME,
                DynamicsContactMapper.ATTRIBUTE_LASTNAME, DynamicsContactMapper.ATTRIBUTE_EMAILADDRESS1,
                DynamicsContactMapper.ATTRIBUTE_TELEPHONE1);
        assertThat(attributes)
                .containsEntry(DynamicsContactMapper.ATTRIBUTE_FIRSTNAME, firstname)
                .containsEntry(DynamicsContactMapper.ATTRIBUTE_LASTNAME, lastname)
                .containsEntry(DynamicsContactMapper.ATTRIBUTE_EMAILADDRESS1, emailaddress1)
                .containsEntry(DynamicsContactMapper.ATTRIBUTE_TELEPHONE1, telephone1);
    }
}
