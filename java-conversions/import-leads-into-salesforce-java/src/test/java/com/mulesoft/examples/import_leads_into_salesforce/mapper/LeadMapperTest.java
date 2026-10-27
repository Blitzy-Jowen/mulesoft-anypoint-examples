/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_leads_into_salesforce.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit test of {@link LeadMapper}, the hand-written owner of the {@code Transform CSV to Maps} script of
 * {@code import-leads-into-salesforce.xml}: {@code payload map { Company: $.Company, Email: $.Email, FirstName:
 * $.FirstName, LastName: $.LastName }} under {@code %output application/java}.
 *
 * <p>Inputs are built inline as the CSV reader delivers them, one {@link LinkedHashMap} per row in the header order
 * of {@code leads.csv} ({@code Company,FirstName,LastName,Birthday,Email}), which differs from the script's key order;
 * one test reads the classpath {@code leads.csv} through {@link DwCsvReader} instead. No Spring context is used.
 * Expected Lead names, values and {@code toString()} renderings are written as literals, so they are checked
 * independently of the mapper's constants; the renderings are what the {@code LeadInsertStep} logger printed for
 * {@code Got Record #[payload]}.
 */
class LeadMapperTest {

    /** The Lead fields of the script, in its declaration order (which differs from the CSV column order). */
    private static final List<String> LEAD_FIELDS = List.of("Company", "Email", "FirstName", "LastName");

    /** The record the script produces for the first data row of {@code leads.csv}, as its logger printed it. */
    private static final String ISHMAEL_RECORD =
            "{Company=Aliquam Tincidunt Nunc LLC, Email=faucibus@egetmetus.org, FirstName=Ishmael, LastName=Alexander}";

    /** The record the script produces for the second data row of {@code leads.csv}, as its logger printed it. */
    private static final String COLE_RECORD =
            "{Company=Eu Odio PC, Email=sem.egestas@mollis.org, FirstName=Cole, LastName=Burks}";

    /** Byte length of the carried {@code leads.csv}, identical to the original's. */
    private static final int LEADS_CSV_LENGTH = 176;

    private final LeadMapper mapper = new LeadMapper();

    // ---------------------------------------------------------------- records, key names and order

    @Test
    @DisplayName("One LinkedHashMap record per row, in input order: Ishmael Alexander, then Cole Burks")
    void mapsOneLeadPerRowInInputOrder() {
        List<Map<String, Object>> leads = mapper.map(List.of(ishmael(), cole()));

        assertThat(leads).hasSize(2);
        assertThat(leads).allSatisfy(lead -> assertThat(lead).isInstanceOf(LinkedHashMap.class));
        assertLead(leads.get(0), "Aliquam Tincidunt Nunc LLC", "faucibus@egetmetus.org", "Ishmael", "Alexander");
        assertLead(leads.get(1), "Eu Odio PC", "sem.egestas@mollis.org", "Cole", "Burks");
    }

    @Test
    @DisplayName("Each record holds exactly Company, Email, FirstName, LastName in that order, not the CSV order")
    void recordKeysFollowTheScriptOrder() {
        Map<String, String> row = ishmael();

        Map<String, Object> lead = mapper.map(List.of(row)).get(0);

        assertThat(new ArrayList<>(row.keySet())).isEqualTo(
                List.of("Company", "FirstName", "LastName", "Birthday", "Email"));
        assertThat(new ArrayList<>(lead.keySet())).isEqualTo(LEAD_FIELDS);
        assertThat(lead).hasToString(ISHMAEL_RECORD);
        assertThat(LeadMapper.FIELDS).isEqualTo(LEAD_FIELDS);
    }

    @Test
    @DisplayName("Columns the script does not name (Birthday, Notes) are dropped")
    void extraColumnsAreDropped() {
        Map<String, String> row = ishmael();
        row.put("Notes", "met at the trade fair");

        Map<String, Object> lead = mapper.map(List.of(row)).get(0);

        assertThat(lead).hasSize(4).doesNotContainKeys("Birthday", "Notes");
        assertThat(lead).hasToString(ISHMAEL_RECORD);
    }

    // ---------------------------------------------------------------- absent values

    @ParameterizedTest(name = "row without {0} -> {1}")
    @CsvSource({
        "Company, '{Company=null, Email=faucibus@egetmetus.org, FirstName=Ishmael, LastName=Alexander}'",
        "Email, '{Company=Aliquam Tincidunt Nunc LLC, Email=null, FirstName=Ishmael, LastName=Alexander}'",
        "FirstName, '{Company=Aliquam Tincidunt Nunc LLC, Email=faucibus@egetmetus.org, "
                + "FirstName=null, LastName=Alexander}'",
        "LastName, '{Company=Aliquam Tincidunt Nunc LLC, Email=faucibus@egetmetus.org, "
                + "FirstName=Ishmael, LastName=null}'"
    })
    @DisplayName("A column missing from a row gives its field a present null value; the four keys stay in order")
    void missingColumnGivesNullField(String column, String expectedRecord) {
        Map<String, String> row = ishmael();
        row.remove(column);

        Map<String, Object> lead = mapper.map(List.of(row)).get(0);

        assertThat(lead).containsKey(column).containsEntry(column, null).hasSize(4);
        assertThat(new ArrayList<>(lead.keySet())).isEqualTo(LEAD_FIELDS);
        assertThat(lead).hasToString(expectedRecord);
    }

    @Test
    @DisplayName("An empty row and a null row each give the four keys, all null, like DataWeave's null-safe $.x")
    void emptyOrNullRowGivesFourNullFields() {
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(new LinkedHashMap<>());
        rows.add(null);

        List<Map<String, Object>> leads = mapper.map(rows);

        assertThat(leads).hasSize(2);
        for (Map<String, Object> lead : leads) {
            assertLead(lead, null, null, null, null);
        }
    }

    @Test
    @DisplayName("An empty cell stays the empty string, never null")
    void emptyStringIsNotNull() {
        Map<String, Object> lead = mapper.map(List.of(csvRow("", "", "", "", ""))).get(0);

        assertLead(lead, "", "", "", "");
        assertThat(lead.values()).doesNotContainNull();
    }

    // ---------------------------------------------------------------- value fidelity

    @Test
    @DisplayName("Values are the reader's own Strings: no trimming, re-casing or type coercion")
    void valuesArePassedThroughUntouched() {
        String company = "  Eu Odio PC ";
        String firstName = "007";
        String lastName = "12/16/2013";
        String email = "Sem.Egestas@Mollis.ORG";

        Map<String, Object> lead =
                mapper.map(List.of(csvRow(company, firstName, lastName, "03/08/2014", email))).get(0);

        assertThat(lead.get("Company")).isInstanceOf(String.class).isSameAs(company);
        assertThat(lead.get("Email")).isInstanceOf(String.class).isSameAs(email);
        assertThat(lead.get("FirstName")).isInstanceOf(String.class).isSameAs(firstName);
        assertThat(lead.get("LastName")).isInstanceOf(String.class).isSameAs(lastName);
        assertThat(lead).hasToString(
                "{Company=  Eu Odio PC , Email=Sem.Egestas@Mollis.ORG, FirstName=007, LastName=12/16/2013}");
    }

    // ---------------------------------------------------------------- collection handling

    @Test
    @DisplayName("An empty input, immutable or mutable, gives an empty, non-null list")
    void emptyInputGivesEmptyList() {
        List<Map<String, String>> mutableEmpty = new ArrayList<>();

        List<Map<String, Object>> fromImmutable = mapper.map(List.of());
        List<Map<String, Object>> fromMutable = mapper.map(mutableEmpty);

        assertThat(fromImmutable).isNotNull().isEmpty();
        assertThat(fromMutable).isNotNull().isNotSameAs(mutableEmpty).isEmpty();
    }

    @Test
    @DisplayName("A null row list is rejected with a NullPointerException naming rows")
    void nullInputIsRejected() {
        assertThatThrownBy(() -> mapper.map(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("rows");
    }

    @Test
    @DisplayName("The input list and rows are not modified, and the records share no state with them")
    void inputIsNotMutated() {
        Map<String, String> row = ishmael();
        Map<String, String> before = new LinkedHashMap<>(row);
        List<String> keysBefore = new ArrayList<>(row.keySet());
        List<Map<String, String>> rows = new ArrayList<>(List.of(row));

        List<Map<String, Object>> leads = mapper.map(rows);
        leads.get(0).put("FirstName", "Changed");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).isSameAs(row);
        assertThat(row).isEqualTo(before);
        assertThat(new ArrayList<>(row.keySet())).isEqualTo(keysBefore);
    }

    // ---------------------------------------------------------------- carried leads.csv

    @Test
    @DisplayName("The CR-only classpath leads.csv maps to Ishmael Alexander and Cole Burks (original IT targets)")
    void mapsTheCarriedLeadsCsv() throws IOException {
        byte[] bytes;
        try (InputStream in = LeadMapperTest.class.getResourceAsStream("/leads.csv")) {
            assertThat(in).as("classpath resource /leads.csv").isNotNull();
            bytes = in.readAllBytes();
        }

        assertThat(bytes).hasSize(LEADS_CSV_LENGTH).contains((byte) '\r').doesNotContain((byte) '\n');

        List<Map<String, Object>> leads = mapper.map(new DwCsvReader().read(bytes));

        assertThat(leads).hasSize(2);
        assertThat(leads.get(0)).hasToString(ISHMAEL_RECORD);
        assertThat(leads.get(1)).hasToString(COLE_RECORD);
        assertThat(leads.get(0)).containsEntry("Email", "faucibus@egetmetus.org").containsEntry("FirstName", "Ishmael");
        assertThat(leads.get(1)).containsEntry("Email", "sem.egestas@mollis.org").containsEntry("LastName", "Burks");
    }

    // ---------------------------------------------------------------- fixtures and assertions

    /** A CSV row keyed by the {@code leads.csv} header, in header order. */
    private static Map<String, String> csvRow(String company, String firstName, String lastName, String birthday,
            String email) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("Company", company);
        row.put("FirstName", firstName);
        row.put("LastName", lastName);
        row.put("Birthday", birthday);
        row.put("Email", email);
        return row;
    }

    /** First data row of {@code leads.csv}. */
    private static Map<String, String> ishmael() {
        return csvRow("Aliquam Tincidunt Nunc LLC", "Ishmael", "Alexander", "03/08/2014", "faucibus@egetmetus.org");
    }

    /** Second data row of {@code leads.csv}. */
    private static Map<String, String> cole() {
        return csvRow("Eu Odio PC", "Cole", "Burks", "12/16/2013", "sem.egestas@mollis.org");
    }

    /** The Lead record the script produces for the given values, in its field order. */
    private static Map<String, Object> lead(String company, String email, String firstName, String lastName) {
        Map<String, Object> lead = new LinkedHashMap<>();
        lead.put("Company", company);
        lead.put("Email", email);
        lead.put("FirstName", firstName);
        lead.put("LastName", lastName);
        return lead;
    }

    /** Asserts the record's entries, keys and values, in order. */
    private static void assertLead(Map<String, Object> actual, String company, String email, String firstName,
            String lastName) {
        assertThat(new ArrayList<>(actual.entrySet()))
                .containsExactlyElementsOf(lead(company, email, firstName, lastName).entrySet());
    }
}
