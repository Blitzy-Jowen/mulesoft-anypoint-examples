/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.mapper;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

/**
 * Hand-written reimplementation of the "Map Collection to CSV" DataWeave script of the original
 * {@code attachments.xml} (lines 20-30), which the conversion plan (AAP 0.5.8) names this class the only owner of:
 *
 * <pre>
 * %dw 1.0
 * %output application/csv header=true
 * ---
 * payload map {
 *     first_name : $[0].first_name,
 *     last_name  : $[0].last_name,
 *     gender     : $[0].gender,
 *     dob        : $[0].dob as :date,
 *     hire_date  : $[0].hire_date as :date
 * }
 * </pre>
 *
 * <p>The script's input is the {@code collection-aggregator} result: one entry per split {@code <employee>}
 * element, in request order, each the list of rows its {@code db:select} returned. Each entry becomes one CSV record
 * built from its first row only; further rows are ignored, as {@code $[0]} ignores them. An entry without a row
 * becomes a record of five empty fields. Dates are written as ISO {@code yyyy-MM-dd}, the form DataWeave 1.0 gives a
 * {@code :date}, with no time part.
 *
 * <p>The CSV bytes themselves (separator, header row, line ends, quoting) come from {@link DwCsvWriter}, the single
 * CSV policy of the project. For the original test request ({@code Chava Puckett}, {@code Quentin Puckett},
 * {@code Mona Sosa}) against the seeded {@code employees} table, {@link #toCsv(List)} returns:
 *
 * <pre>
 * first_name,last_name,gender,dob,hire_date
 * Chava,Puckett,F,1985-09-02,2008-10-12
 * Quentin,Puckett,F,1971-10-21,2008-09-15
 * Mona,Sosa,M,1950-09-26,2007-11-27
 * </pre>
 *
 * <p>Instances are stateless apart from the shared writer and safe to share between threads.
 */
@Component
public class EmployeeCsvMapper {

    /** Key of the first row read, and CSV column, for the employee's first name. */
    public static final String FIRST_NAME = "first_name";

    /** Key of the first row read, and CSV column, for the employee's last name. */
    public static final String LAST_NAME = "last_name";

    /** Key of the first row read, and CSV column, for the employee's gender. */
    public static final String GENDER = "gender";

    /** Key of the first row read, and CSV column, for the employee's date of birth ({@code as :date}). */
    public static final String DOB = "dob";

    /** Key of the first row read, and CSV column, for the employee's hire date ({@code as :date}). */
    public static final String HIRE_DATE = "hire_date";

    /** The script's output columns, in the order its object literal declares them and the CSV writes them. */
    public static final List<String> COLUMNS = List.of(FIRST_NAME, LAST_NAME, GENDER, DOB, HIRE_DATE);

    private final DwCsvWriter dwCsvWriter;

    /**
     * Creates the mapper.
     *
     * @param dwCsvWriter the writer that turns the mapped records into CSV text
     * @throws NullPointerException when {@code dwCsvWriter} is {@code null}
     */
    public EmployeeCsvMapper(DwCsvWriter dwCsvWriter) {
        this.dwCsvWriter = Objects.requireNonNull(dwCsvWriter, "dwCsvWriter");
    }

    /**
     * Applies the script's {@code payload map { ... }} to the aggregated query results.
     *
     * <p>Values are read from each entry's first row by key, so a row map that resolves keys case-insensitively
     * (such as Spring's {@code LinkedCaseInsensitiveMap}) is read correctly whatever case the database reported its
     * column labels in.
     *
     * @param aggregate one entry per employee in request order, each the rows selected for that employee; an entry
     *                  may be {@code null} or empty, and its first row may be {@code null}, all meaning "no row"
     * @return a new mutable list holding one record per entry, in the same order; each record is a
     *         {@link LinkedHashMap} keyed {@link #COLUMNS} in order, every value a {@link String} ({@code ""} where
     *         the row lacks the value or there is no row)
     * @throws NullPointerException     when {@code aggregate} is {@code null}
     * @throws IllegalArgumentException when a {@code dob} or {@code hire_date} value cannot be read as a date
     */
    public List<Map<String, Object>> map(List<List<Map<String, Object>>> aggregate) {
        Objects.requireNonNull(aggregate, "aggregate");
        List<Map<String, Object>> records = new ArrayList<>(aggregate.size());
        for (List<Map<String, Object>> rows : aggregate) {
            records.add(toRecord(firstRow(rows)));
        }
        return records;
    }

    /**
     * Applies the script and writes its result as CSV with a header row, as {@code header=true} requests.
     *
     * @param aggregate one entry per employee in request order, each the rows selected for that employee
     * @return the CSV text produced by {@link DwCsvWriter#write(List)} for the mapped records
     * @throws NullPointerException     when {@code aggregate} is {@code null}
     * @throws IllegalArgumentException when a {@code dob} or {@code hire_date} value cannot be read as a date
     */
    public String toCsv(List<List<Map<String, Object>>> aggregate) {
        return dwCsvWriter.write(map(aggregate));
    }

    private static Map<String, Object> firstRow(List<Map<String, Object>> rows) {
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    private static Map<String, Object> toRecord(Map<String, Object> row) {
        Map<String, Object> record = new LinkedHashMap<>();
        if (row == null) {
            for (String column : COLUMNS) {
                record.put(column, "");
            }
            return record;
        }
        record.put(FIRST_NAME, toText(row.get(FIRST_NAME)));
        record.put(LAST_NAME, toText(row.get(LAST_NAME)));
        record.put(GENDER, toText(row.get(GENDER)));
        record.put(DOB, toIsoDate(DOB, row.get(DOB)));
        record.put(HIRE_DATE, toIsoDate(HIRE_DATE, row.get(HIRE_DATE)));
        return record;
    }

    private static String toText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /*
     * The script's "as :date". java.sql.Date is tested before java.util.Date because its toInstant() throws
     * UnsupportedOperationException; java.sql.Time, which throws the same and carries no date, is left to the
     * unsupported-type failure. A java.util.Date (a Timestamp included) is an instant, read in the JVM's zone.
     */
    private static String toIsoDate(String field, Object value) {
        if (value == null) {
            return "";
        }
        LocalDate date;
        if (value instanceof LocalDate localDate) {
            date = localDate;
        } else if (value instanceof java.sql.Date sqlDate) {
            date = sqlDate.toLocalDate();
        } else if (value instanceof java.util.Date utilDate && !(value instanceof java.sql.Time)) {
            date = utilDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        } else if (value instanceof String text) {
            try {
                date = LocalDate.parse(text);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Cannot coerce " + field + " value \"" + text + "\" to :date", e);
            }
        } else {
            throw new IllegalArgumentException(
                    "Cannot coerce " + field + " of type " + value.getClass().getName() + " to :date");
        }
        return date.toString();
    }
}
