/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_ms_dynamics.mapper;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

/**
 * Reads CSV text as the DataWeave 1.0 {@code application/csv} reader did with its default options: shared component
 * SC-4 of the conversion plan, and the single CSV-reading policy of every converted project.
 *
 * <p>The result holds one map per data record, keyed by the header names in header order. Every value is the field's
 * text as written: never trimmed, never coerced, so {@code 096548763} keeps its leading zero and {@code 2.0} stays the
 * string {@code "2.0"}.
 *
 * <p>DataWeave 1.0 reader defaults reproduced:
 * <ul>
 *   <li>{@code header=true}: the first non-empty record names the columns, so an empty input and a header without
 *       data records both read as an empty list;</li>
 *   <li>{@code separator=","}: an empty field reads as {@code ""}, and a record ending in a separator ends with an
 *       empty last value;</li>
 *   <li>{@code quote='"'}: a field whose first character is a double quote is quoted; inside it separators, CR and
 *       LF are content kept exactly as written (a CRLF included), and {@code ""} stands for one double quote. A
 *       double quote anywhere else in a field is content;</li>
 *   <li>{@code escape='\'}: inside or outside quotes the backslash is dropped and the character after it is taken
 *       as content, whatever that character is;</li>
 *   <li>a record ends at an LF, a CRLF (one line end, not an empty line) or a lone CR outside quotes, and the last
 *       record is read with or without a line end after it. An empty line, zero characters between two line ends,
 *       is skipped wherever it appears; a line holding only spaces is a record whose one value is those spaces.</li>
 * </ul>
 *
 * <p>Tier 2A verification points. None of the inputs the original examples read quotes a field, is ragged or holds a
 * backslash, so the rules below are plan defaults; a fixture captured from the original runtime that shows another
 * result governs, and the rule then changes here and in every copy:
 * <ul>
 *   <li>a short row, with fewer values than header names, omits the keys it has no value for, rather than mapping
 *       them to {@code null} or {@code ""};</li>
 *   <li>a long row keys each value beyond the header names {@code column_<i>}, {@code i} being the zero-based index
 *       of the value in its record: with two header names a third value is keyed {@code column_2};</li>
 *   <li>the backslash escape rule above, with a lone backslash at the very end of the input kept as content;</li>
 *   <li>leniency: characters between a closing quote and the next separator or line end are appended to the value,
 *       and a quoted field that is never closed runs to the end of the input and becomes the value; neither is an
 *       error.</li>
 * </ul>
 *
 * <p>Repeated header names follow {@link Map#put}: the later value replaces the earlier one at the first name's
 * position. A byte order mark is not stripped; it stays part of the first header name.
 *
 * <p>Copy rule: this project is the first consumer, so this class is the reference implementation. Every other
 * project that reads CSV carries a copy of it, and of its unit test, differing in the {@code package} line alone; it
 * therefore depends on the JDK and Spring's {@link Component} only, and never on a project type.
 *
 * <p>Instances are stateless and safe to share between threads: every call parses with state of its own.
 */
@Component
public class DwCsvReader {

    private static final char SEPARATOR = ',';
    private static final char QUOTE = '"';
    private static final char ESCAPE = '\\';
    private static final char CR = '\r';
    private static final char LF = '\n';
    private static final String EXTRA_COLUMN_PREFIX = "column_";

    /**
     * Creates a reader; it holds no state, so one instance serves every caller.
     */
    public DwCsvReader() {
    }

    /**
     * Reads UTF-8 encoded CSV bytes.
     *
     * @param content the CSV bytes
     * @return one mutable map per data record, in input order; an empty mutable list when there is none
     * @throws NullPointerException when {@code content} is {@code null}
     */
    public List<Map<String, String>> read(byte[] content) {
        return read(content, StandardCharsets.UTF_8);
    }

    /**
     * Reads CSV bytes in the given encoding.
     *
     * @param content the CSV bytes
     * @param charset the encoding of {@code content}
     * @return one mutable map per data record, in input order; an empty mutable list when there is none
     * @throws NullPointerException when {@code content} or {@code charset} is {@code null}
     */
    public List<Map<String, String>> read(byte[] content, Charset charset) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(charset, "charset");
        return read(new String(content, charset));
    }

    /**
     * Reads CSV text.
     *
     * @param content the CSV text
     * @return one mutable map per data record, in input order, each keyed in header order; an empty mutable list when
     *         there is none
     * @throws NullPointerException when {@code content} is {@code null}
     */
    public List<Map<String, String>> read(String content) {
        Objects.requireNonNull(content, "content");
        List<List<String>> records = new RecordParser(content).parse();
        List<Map<String, String>> rows = new ArrayList<>();
        // Every parsed record is non-empty, so the first one is the header and each later one a data record.
        for (int r = 1; r < records.size(); r++) {
            rows.add(toRow(records.get(0), records.get(r)));
        }
        return rows;
    }

    private static Map<String, String> toRow(List<String> header, List<String> values) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < values.size(); i++) {
            String key = i < header.size() ? header.get(i) : EXTRA_COLUMN_PREFIX + i;
            row.put(key, values.get(i));
        }
        return row;
    }

    /**
     * Splits one input into its non-empty records, each a list of field values. Single use: it holds the state of
     * one {@link DwCsvReader#read(String)} call.
     */
    private static final class RecordParser {

        private final String content;
        private final List<List<String>> records = new ArrayList<>();
        private final StringBuilder field = new StringBuilder();
        private List<String> fields = new ArrayList<>();
        private int position;
        private boolean inQuotes;
        private boolean atFieldStart = true;
        // False until the current record consumes a character; a line end reached while false ends an empty line.
        private boolean recordStarted;

        RecordParser(String content) {
            this.content = content;
        }

        List<List<String>> parse() {
            while (position < content.length()) {
                consume(content.charAt(position++));
            }
            endRecord();
            return records;
        }

        private void consume(char c) {
            if (c == ESCAPE) {
                appendContent(position < content.length() ? content.charAt(position++) : ESCAPE);
            } else if (inQuotes) {
                consumeQuoted(c);
            } else if (c == QUOTE && atFieldStart) {
                inQuotes = true;
                atFieldStart = false;
                recordStarted = true;
            } else if (c == SEPARATOR) {
                endField();
                recordStarted = true;
            } else if (c == CR || c == LF) {
                if (c == CR && nextIs(LF)) {
                    position++;
                }
                endRecord();
            } else {
                appendContent(c);
            }
        }

        // Inside quotes every character but the quote is content; the record and field flags are already set.
        private void consumeQuoted(char c) {
            if (c != QUOTE) {
                field.append(c);
            } else if (nextIs(QUOTE)) {
                field.append(QUOTE);
                position++;
            } else {
                inQuotes = false;
            }
        }

        private boolean nextIs(char expected) {
            return position < content.length() && content.charAt(position) == expected;
        }

        private void appendContent(char c) {
            field.append(c);
            atFieldStart = false;
            recordStarted = true;
        }

        private void endField() {
            fields.add(field.toString());
            field.setLength(0);
            atFieldStart = true;
        }

        private void endRecord() {
            if (!recordStarted) {
                return;
            }
            endField();
            records.add(fields);
            fields = new ArrayList<>();
            recordStarted = false;
        }
    }
}
