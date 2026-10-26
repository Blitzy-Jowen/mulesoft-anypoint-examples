/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_expression_language_basics.mapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

/**
 * Writes records as the bytes a DataWeave 1.0 {@code %output application/csv} script produced: shared component
 * SC-4 of the conversion plan, and the single CSV policy of every converted project.
 *
 * <p>The policy is taken from the DataWeave-produced {@code report.csv} of the original
 * processing-orders-with-dataweave-and-APIkit example
 * ({@code totalNumberOfItems,totalAmount,averageSellingPrice\n4,149.93,37.4825\n}, 68 bytes):
 * <ul>
 *   <li>comma separator, no padding;</li>
 *   <li>a header row holding the keys of the first record, in its iteration order, unless the caller asks for
 *       the header-less form;</li>
 *   <li>every line, the last included, ends with a single LF, whatever the platform line separator is;</li>
 *   <li>numbers keep their written form: a {@link BigDecimal} keeps its scale ({@code 149.93},
 *       {@code 37.4825}) and no number is written in exponent notation;</li>
 *   <li>an empty record list produces no bytes at all, not even a header.</li>
 * </ul>
 *
 * <p>Quoting: a field (header name or value) holding a comma, a double quote, a CR or an LF is wrapped in double
 * quotes, and each double quote and each backslash inside it is preceded by a backslash, DataWeave 1.0's default
 * CSV escape character, so no backslash in the field can be read as escaping the quote after it; every other
 * field, any backslash in it included, is written verbatim. No committed output of the originals contains such a
 * field, so this rule is a plan default and a Tier 2A verification point: a fixture captured from the original
 * runtime that shows other bytes governs, and the rule changes here and in every copy.
 *
 * <p>Copy rule: this project is the first consumer, so this class is the reference implementation. Every other
 * project that writes CSV carries a copy of it, and of its unit test, differing in the {@code package} line
 * alone; it therefore depends on the JDK and Spring's {@link Component} only, and never on a project type.
 *
 * <p>Instances are stateless and safe to share between threads.
 */
@Component
public class DwCsvWriter {

    private static final char SEPARATOR = ',';
    private static final char QUOTE = '"';
    private static final char ESCAPE = '\\';
    private static final char CR = '\r';
    private static final char LF = '\n';

    /**
     * Creates a writer; it holds no state, so one instance serves every caller.
     */
    public DwCsvWriter() {
    }

    /**
     * Writes {@code records} with a header row.
     *
     * @param records the rows to write, each a map from column name to value
     * @return the CSV text; empty when {@code records} is empty
     * @throws NullPointerException when {@code records} or one of its elements is {@code null}
     */
    public String write(List<? extends Map<String, ?>> records) {
        return write(records, true);
    }

    /**
     * Writes {@code records}, one line per record.
     *
     * <p>The columns are the keys of the first record, in its iteration order. Each record is read in that column
     * order: a column the record lacks, or maps to {@code null}, becomes an empty field, and a key that is not a
     * column is ignored.
     *
     * @param records the rows to write, each a map from column name to value
     * @param header  whether the first line holds the column names
     * @return the CSV text; empty when {@code records} is empty, whatever {@code header} is
     * @throws NullPointerException when {@code records} or one of its elements is {@code null}
     */
    public String write(List<? extends Map<String, ?>> records, boolean header) {
        Objects.requireNonNull(records, "records");
        StringBuilder csv = new StringBuilder();
        List<String> columns = null;
        int index = 0;
        // Iterated rather than indexed, so a linked list is not walked again for every record.
        for (Map<String, ?> row : records) {
            if (row == null) {
                throw new NullPointerException("records[" + index + "] is null");
            }
            if (columns == null) {
                columns = new ArrayList<>(row.keySet());
                if (header) {
                    appendHeader(csv, columns);
                }
            }
            appendRow(csv, columns, row);
            index++;
        }
        return csv.toString();
    }

    private static void appendHeader(StringBuilder csv, List<String> columns) {
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                csv.append(SEPARATOR);
            }
            appendField(csv, render(columns.get(i)));
        }
        csv.append(LF);
    }

    private static void appendRow(StringBuilder csv, List<String> columns, Map<String, ?> row) {
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                csv.append(SEPARATOR);
            }
            appendField(csv, render(row.get(columns.get(i))));
        }
        csv.append(LF);
    }

    // Double.toString and Float.toString switch to exponent notation below 1e-3 and from 1e7. Reading their digits
    // into a BigDecimal and writing it plainly keeps those digits without an exponent: 1.0E10 -> 10000000000.
    private static String render(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof Double d) {
            return d.isNaN() || d.isInfinite() ? String.valueOf(d) : BigDecimal.valueOf(d).toPlainString();
        }
        if (value instanceof Float f) {
            return f.isNaN() || f.isInfinite() ? String.valueOf(f) : new BigDecimal(Float.toString(f)).toPlainString();
        }
        return value.toString();
    }

    private static void appendField(StringBuilder csv, String field) {
        if (!needsQuoting(field)) {
            csv.append(field);
            return;
        }
        csv.append(QUOTE);
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (c == QUOTE || c == ESCAPE) {
                csv.append(ESCAPE);
            }
            csv.append(c);
        }
        csv.append(QUOTE);
    }

    private static boolean needsQuoting(String field) {
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (c == SEPARATOR || c == QUOTE || c == CR || c == LF) {
                return true;
            }
        }
        return false;
    }
}
