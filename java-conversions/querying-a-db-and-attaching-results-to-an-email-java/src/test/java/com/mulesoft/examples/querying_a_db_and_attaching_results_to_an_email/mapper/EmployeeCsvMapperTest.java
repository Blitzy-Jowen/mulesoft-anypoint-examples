/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.mapper;

import static com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.mapper.EmployeeCsvMapper.COLUMNS;
import static com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.mapper.EmployeeCsvMapper.DOB;
import static com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.mapper.EmployeeCsvMapper.FIRST_NAME;
import static com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.mapper.EmployeeCsvMapper.GENDER;
import static com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.mapper.EmployeeCsvMapper.HIRE_DATE;
import static com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.mapper.EmployeeCsvMapper.LAST_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test of {@link EmployeeCsvMapper}, the owner of the "Map Collection to CSV" script of {@code attachments.xml}:
 * the original test request ({@code Chava Puckett}, {@code Quentin Puckett}, {@code Mona Sosa}) against the seeded
 * {@code employees} rows gives the exact CSV attachment holding the original test's two expected lines; columns come
 * in the script's order whatever order the row map holds; {@code dob} and {@code hire_date} are written {@code as
 * :date} from every JDBC and text form; an entry without a row becomes five empty fields; only each entry's first row
 * is read; and a value that is not a date fails.
 *
 * <p>The collaborator is real: {@link DwCsvWriter} writes the CSV. No Spring context, mock or resource file is used,
 * and every expected value is written inline.
 */
class EmployeeCsvMapperTest {

    private static final String HEADER = "first_name,last_name,gender,dob,hire_date\n";

    private static final String CHAVA_LINE = "Chava,Puckett,F,1985-09-02,2008-10-12";

    private static final String MONA_LINE = "Mona,Sosa,M,1950-09-26,2007-11-27";

    private static final String EMPTY_LINE = ",,,,\n";

    private EmployeeCsvMapper mapper;

    @BeforeEach
    void createMapper() {
        mapper = new EmployeeCsvMapper(new DwCsvWriter());
    }

    @Test
    @DisplayName("original request: three employees in request order, exact CSV holding the original test's two lines")
    void writesOriginalRequestCsv() {
        List<List<Map<String, Object>>> aggregate = List.of(
                List.of(row("Chava", "Puckett", "F", LocalDate.of(1985, 9, 2), LocalDate.of(2008, 10, 12))),
                List.of(row("Quentin", "Puckett", "F", LocalDate.of(1971, 10, 21), LocalDate.of(2008, 9, 15))),
                List.of(row("Mona", "Sosa", "M", LocalDate.of(1950, 9, 26), LocalDate.of(2007, 11, 27))));

        String csv = mapper.toCsv(aggregate);

        assertThat(csv).isEqualTo(HEADER
                + "Chava,Puckett,F,1985-09-02,2008-10-12\n"
                + "Quentin,Puckett,F,1971-10-21,2008-09-15\n"
                + "Mona,Sosa,M,1950-09-26,2007-11-27\n");
        assertThat(csv).contains(CHAVA_LINE).contains(MONA_LINE);
    }

    @Test
    @DisplayName("columns: first_name, last_name, gender, dob, hire_date, whatever order the row map holds")
    void writesColumnsInScriptOrder() {
        assertThat(List.of(FIRST_NAME, LAST_NAME, GENDER, DOB, HIRE_DATE))
                .containsExactly("first_name", "last_name", "gender", "dob", "hire_date");
        assertThat(COLUMNS).containsExactly("first_name", "last_name", "gender", "dob", "hire_date");

        Map<String, Object> reversed = new LinkedHashMap<>();
        reversed.put(HIRE_DATE, LocalDate.of(2008, 10, 12));
        reversed.put(DOB, LocalDate.of(1985, 9, 2));
        reversed.put(GENDER, "F");
        reversed.put(LAST_NAME, "Puckett");
        reversed.put(FIRST_NAME, "Chava");
        List<List<Map<String, Object>>> aggregate = List.of(List.of(reversed));

        List<Map<String, Object>> records = mapper.map(aggregate);
        String csv = mapper.toCsv(aggregate);

        assertThat(records).hasSize(1);
        assertThat(records.get(0)).isInstanceOf(LinkedHashMap.class);
        assertThat(records.get(0).keySet())
                .containsExactly("first_name", "last_name", "gender", "dob", "hire_date");
        assertThat(records.get(0).values()).containsExactly("Chava", "Puckett", "F", "1985-09-02", "2008-10-12");
        assertThat(csv.lines().findFirst()).hasValue("first_name,last_name,gender,dob,hire_date");
        assertThat(csv).isEqualTo(HEADER + CHAVA_LINE + "\n");
    }

    static Stream<Arguments> dateForms() {
        return Stream.of(
                Arguments.of("java.sql.Date", java.sql.Date.valueOf("1985-09-02")),
                Arguments.of("LocalDate", LocalDate.of(1985, 9, 2)),
                Arguments.of("ISO String", "1985-09-02"),
                Arguments.of("java.util.Date at local midnight",
                        Date.from(LocalDate.of(1985, 9, 2).atStartOfDay(ZoneId.systemDefault()).toInstant())),
                Arguments.of("java.sql.Timestamp with a time part", Timestamp.valueOf("1985-09-02 13:45:30")));
    }

    @ParameterizedTest(name = "{0} -> 1985-09-02")
    @MethodSource("dateForms")
    @DisplayName("dob and hire_date as :date: every JDBC and text form renders yyyy-MM-dd with no time part")
    void writesDateFormsAsIsoDate(String description, Object value) {
        List<List<Map<String, Object>>> aggregate = List.of(List.of(row("Chava", "Puckett", "F", value, value)));

        Map<String, Object> record = mapper.map(aggregate).get(0);

        assertThat(record).containsEntry(DOB, "1985-09-02").containsEntry(HIRE_DATE, "1985-09-02");
        assertThat(mapper.toCsv(aggregate)).isEqualTo(HEADER + "Chava,Puckett,F,1985-09-02,1985-09-02\n");
    }

    static Stream<Arguments> entriesWithoutValues() {
        Map<String, Object> nullValues = row(null, null, null, null, null);
        return Stream.of(
                Arguments.of("entry with no row", List.of(List.of())),
                Arguments.of("null entry", Arrays.asList((List<Map<String, Object>>) null)),
                Arguments.of("null first row", List.of(Arrays.asList((Map<String, Object>) null))),
                Arguments.of("row with every value null", List.of(List.of(nullValues))),
                Arguments.of("row with every key absent", List.of(List.of(new LinkedHashMap<String, Object>()))));
    }

    @ParameterizedTest(name = "{0} -> ,,,,")
    @MethodSource("entriesWithoutValues")
    @DisplayName("an entry without a row or values: five empty fields under the header")
    void writesEmptyFieldsForEntryWithoutValues(String description, List<List<Map<String, Object>>> aggregate) {
        List<Map<String, Object>> records = mapper.map(aggregate);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).keySet())
                .containsExactly("first_name", "last_name", "gender", "dob", "hire_date");
        assertThat(records.get(0).values()).containsExactly("", "", "", "", "");
        assertThat(mapper.toCsv(aggregate)).isEqualTo(HEADER + EMPTY_LINE);
    }

    @Test
    @DisplayName("$[0]: only each entry's first row is written; later rows are ignored")
    void writesFirstRowOnly() {
        List<List<Map<String, Object>>> aggregate = List.of(List.of(
                row("Chava", "Puckett", "F", LocalDate.of(1985, 9, 2), LocalDate.of(2008, 10, 12)),
                row("Other", "Puckett", "M", LocalDate.of(1990, 1, 1), LocalDate.of(2010, 1, 1))));

        String csv = mapper.toCsv(aggregate);

        assertThat(mapper.map(aggregate)).hasSize(1);
        assertThat(csv).isEqualTo(HEADER + CHAVA_LINE + "\n").doesNotContain("Other");
    }

    @Test
    @DisplayName("no employee in the aggregate: no record and no bytes, not even a header")
    void writesNothingForEmptyAggregate() {
        assertThat(mapper.map(List.of())).isEmpty();
        assertThat(mapper.toCsv(List.of())).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {DOB, HIRE_DATE})
    @DisplayName("date text that is not yyyy-MM-dd -> IllegalArgumentException naming the field and value")
    void rejectsUnparsableDateText(String field) {
        List<List<Map<String, Object>>> aggregate = List.of(List.of(rowWith(field, "02/09/1985")));
        String message = "Cannot coerce " + field + " value \"02/09/1985\" to :date";

        assertThatThrownBy(() -> mapper.map(aggregate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message)
                .hasCauseInstanceOf(DateTimeParseException.class);
        assertThatThrownBy(() -> mapper.toCsv(aggregate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message)
                .hasCauseInstanceOf(DateTimeParseException.class);
    }

    static Stream<Arguments> nonDateValues() {
        return Stream.of(
                Arguments.of(DOB, 19850902, "java.lang.Integer"),
                Arguments.of(HIRE_DATE, 20081012, "java.lang.Integer"),
                Arguments.of(DOB, Time.valueOf("13:45:30"), "java.sql.Time"));
    }

    @ParameterizedTest(name = "{0} = {2} -> IllegalArgumentException")
    @MethodSource("nonDateValues")
    @DisplayName("a value of a type with no date -> IllegalArgumentException naming the field and type")
    void rejectsNonDateType(String field, Object value, String typeName) {
        List<List<Map<String, Object>>> aggregate = List.of(List.of(rowWith(field, value)));
        String message = "Cannot coerce " + field + " of type " + typeName + " to :date";

        assertThatThrownBy(() -> mapper.map(aggregate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message)
                .hasNoCause();
        assertThatThrownBy(() -> mapper.toCsv(aggregate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message)
                .hasNoCause();
    }

    @Test
    @DisplayName("null aggregate -> NullPointerException 'aggregate' from map and toCsv")
    void rejectsNullAggregate() {
        assertThatThrownBy(() -> mapper.map(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("aggregate");
        assertThatThrownBy(() -> mapper.toCsv(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("aggregate");
    }

    @Test
    @DisplayName("null writer -> NullPointerException 'dwCsvWriter'")
    void rejectsNullWriter() {
        assertThatThrownBy(() -> new EmployeeCsvMapper(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("dwCsvWriter");
    }

    // LinkedHashMap keeps the column order of a selected row and, unlike Map.of, accepts null values.
    private static Map<String, Object> row(Object firstName, Object lastName, Object gender, Object dob,
            Object hireDate) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(FIRST_NAME, firstName);
        row.put(LAST_NAME, lastName);
        row.put(GENDER, gender);
        row.put(DOB, dob);
        row.put(HIRE_DATE, hireDate);
        return row;
    }

    // Chava Puckett's seeded row with one date column replaced.
    private static Map<String, Object> rowWith(String field, Object value) {
        Map<String, Object> row = row("Chava", "Puckett", "F", LocalDate.of(1985, 9, 2), LocalDate.of(2008, 10, 12));
        row.put(field, value);
        return row;
    }
}
