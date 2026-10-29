/**
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
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit test of {@link DwCsvReader} (shared component SC-4): the DataWeave 1.0 {@code application/csv} reader
 * defaults it reproduces, namely a header row, the {@code ,} separator, {@code "} quoting with the {@code ""} and
 * backslash escapes, LF, CRLF and lone-CR record ends, skipped empty lines and untrimmed string values.
 *
 * <p>SC-4 reference test; copied with only the package line changed into every project that carries the reader.
 * It therefore depends on the reader, JUnit 5 and the JDK alone: no Spring context, no project type and no resource
 * file. Every input is an inline literal with its line ends written as escapes, and non-ASCII text is written as
 * Unicode escapes so the source stays ASCII.
 */
class DwCsvReaderTest {

    private static final String UTF8_SAMPLE = "name,city\nZo\u00EB,K\u00F6ln\n";

    private final DwCsvReader reader = new DwCsvReader();

    // line ends

    @Test
    @DisplayName("LF record ends: one map per data record, keyed by the header")
    void readsLfSeparatedRecords() {
        assertAbRows(reader.read("a,b\n1,2\n3,4\n"));
    }

    @Test
    @DisplayName("CRLF record ends: the same records, and no value keeps the CR")
    void readsCrlfSeparatedRecords() {
        List<Map<String, String>> rows = reader.read("a,b\r\n1,2\r\n3,4\r\n");

        assertAbRows(rows);
        for (Map<String, String> row : rows) {
            for (Map.Entry<String, String> entry : row.entrySet()) {
                assertFalse(entry.getKey().endsWith("\r"), "key " + entry.getKey());
                assertFalse(entry.getValue().endsWith("\r"), "value of " + entry.getKey());
            }
        }
    }

    @Test
    @DisplayName("Lone CR record ends: the same records")
    void readsLoneCrSeparatedRecords() {
        assertAbRows(reader.read("a,b\r1,2\r3,4\r"));
    }

    @Test
    @DisplayName("The last record is read with or without a line end after it")
    void readsLastRecordWithOrWithoutTrailingLineEnd() {
        List<Map<String, String>> withoutLineEnd = reader.read("a,b\n1,2");
        List<Map<String, String>> withLineEnd = reader.read("a,b\n1,2\n");

        assertEquals(1, withoutLineEnd.size());
        assertRow(withoutLineEnd.get(0), "a", "1", "b", "2");
        assertEquals(1, withLineEnd.size());
        assertRow(withLineEnd.get(0), "a", "1", "b", "2");
        assertEquals(withoutLineEnd, withLineEnd);
    }

    @Test
    @DisplayName("A contact-list sample: four columns, two records, no trailing line end")
    void readsOriginalContactsSampleShape() {
        List<Map<String, String>> rows = reader.read("firstname,surname,phone,email\n"
                + "John,Doe,096548763,john.doe@texasComp.com\n"
                + "Jane,Doe,091558780,jane.doe@texasComp.com");

        assertEquals(2, rows.size());
        assertRow(rows.get(0),
                "firstname", "John", "surname", "Doe", "phone", "096548763", "email", "john.doe@texasComp.com");
        assertRow(rows.get(1),
                "firstname", "Jane", "surname", "Doe", "phone", "091558780", "email", "jane.doe@texasComp.com");
    }

    // quoting

    @Test
    @DisplayName("A separator inside quotes is content")
    void keepsQuotedSeparator() {
        List<Map<String, String>> rows = reader.read("name,city\n\"Doe, John\",Austin\n");

        assertEquals(1, rows.size());
        assertRow(rows.get(0), "name", "Doe, John", "city", "Austin");
    }

    @Test
    @DisplayName("An LF inside quotes is content, not a record end")
    void keepsQuotedLf() {
        List<Map<String, String>> rows = reader.read("id,note\n1,\"line1\nline2\"\n2,x\n");

        assertEquals(2, rows.size());
        assertRow(rows.get(0), "id", "1", "note", "line1\nline2");
        assertRow(rows.get(1), "id", "2", "note", "x");
    }

    @Test
    @DisplayName("A CRLF inside quotes is kept verbatim")
    void keepsQuotedCrlfVerbatim() {
        List<Map<String, String>> rows = reader.read("id,note\r\n1,\"a\r\nb\"\r\n");

        assertEquals(1, rows.size());
        assertRow(rows.get(0), "id", "1", "note", "a\r\nb");
    }

    @Test
    @DisplayName("A doubled quote inside quotes stands for one double quote")
    void unescapesDoubledQuote() {
        // Tier 2A verification point: DataWeave 1.0 handling of "" inside quotes
        List<Map<String, String>> rows = reader.read("id,quote\n1,\"say \"\"hi\"\"\"\n");

        assertEquals(1, rows.size());
        assertRow(rows.get(0), "id", "1", "quote", "say \"hi\"");
    }

    @Test
    @DisplayName("A backslash before a double quote escapes it")
    void unescapesBackslashQuote() {
        // Tier 2A verification point: DataWeave 1.0 default escape character \
        List<Map<String, String>> rows = reader.read("id,quote\n1,\"say \\\"hi\\\"\"\n");

        assertEquals(1, rows.size());
        assertRow(rows.get(0), "id", "1", "quote", "say \"hi\"");
    }

    @Test
    @DisplayName("A double quote inside an unquoted field is content")
    void keepsQuoteInsideUnquotedField() {
        List<Map<String, String>> rows = reader.read("size,label\n5\"x,ok\n");

        assertEquals(1, rows.size());
        assertRow(rows.get(0), "size", "5\"x", "label", "ok");
    }

    @Test
    @DisplayName("A lone backslash at the very end of the input is kept as content")
    void keepsTrailingLoneBackslash() {
        // Tier 2A verification point: DataWeave 1.0 handling of a backslash with nothing after it
        List<Map<String, String>> rows = reader.read("a,b\n1,2\\");

        assertEquals(1, rows.size());
        assertRow(rows.get(0), "a", "1", "b", "2\\");
    }

    // empty input

    @Test
    @DisplayName("An empty middle cell reads as an empty string under its key")
    void readsEmptyMiddleCellAsEmptyString() {
        List<Map<String, String>> rows = reader.read("a,b,c\n1,,3\n");

        assertEquals(1, rows.size());
        assertTrue(rows.get(0).containsKey("b"));
        assertRow(rows.get(0), "a", "1", "b", "", "c", "3");
    }

    @Test
    @DisplayName("A record ending in a separator ends with an empty last value")
    void readsTrailingSeparatorAsEmptyLastValue() {
        List<Map<String, String>> rows = reader.read("a,b,c\n1,2,\n");

        assertEquals(1, rows.size());
        assertRow(rows.get(0), "a", "1", "b", "2", "c", "");
    }

    @Test
    @DisplayName("Empty lines between and after records are skipped, with LF and with CRLF")
    void skipsEmptyLines() {
        List<Map<String, String>> lfRows = reader.read("a,b\n\n1,2\n\n\n3,4\n\n");
        List<Map<String, String>> crlfRows = reader.read("a,b\r\n\r\n1,2\r\n");

        assertAbRows(lfRows);
        assertEquals(1, crlfRows.size());
        assertRow(crlfRows.get(0), "a", "1", "b", "2");
    }

    @Test
    @DisplayName("Empty input, as text or as bytes, reads as an empty list")
    void readsEmptyInputAsEmptyList() {
        List<Map<String, String>> fromText = reader.read("");
        List<Map<String, String>> fromBytes = reader.read(new byte[0]);

        assertNotNull(fromText);
        assertTrue(fromText.isEmpty());
        assertNotNull(fromBytes);
        assertTrue(fromBytes.isEmpty());
    }

    @Test
    @DisplayName("A header without data records reads as an empty list, whatever its line end")
    void readsHeaderOnlyAsEmptyList() {
        for (String headerOnly : List.of("a,b", "a,b\n", "a,b\r\n")) {
            List<Map<String, String>> rows = reader.read(headerOnly);

            assertNotNull(rows, () -> "result for " + escaped(headerOnly));
            assertTrue(rows.isEmpty(), () -> "result for " + escaped(headerOnly));
        }
    }

    // values and keys

    @Test
    @DisplayName("Leading zeros and decimals stay the strings written, never coerced")
    void keepsNumericLookingValuesAsWritten() {
        List<Map<String, String>> rows = reader.read("phone,price,units\n096548763,4.150,2.0\n");

        assertEquals(1, rows.size());
        assertRow(rows.get(0), "phone", "096548763", "price", "4.150", "units", "2.0");
    }

    @Test
    @DisplayName("Values are not trimmed")
    void keepsSurroundingSpaces() {
        List<Map<String, String>> rows = reader.read("a,b\n 1 , x \n");

        assertEquals(1, rows.size());
        assertRow(rows.get(0), "a", " 1 ", "b", " x ");
    }

    @Test
    @DisplayName("Keys keep the header order, neither sorted nor hashed")
    void keepsHeaderOrder() {
        List<Map<String, String>> rows = reader.read("z,a,m\n1,2,3\n");

        assertEquals(1, rows.size());
        assertInstanceOf(LinkedHashMap.class, rows.get(0));
        assertIterableEquals(List.of("z", "a", "m"), new ArrayList<>(rows.get(0).keySet()));
        assertRow(rows.get(0), "z", "1", "a", "2", "m", "3");
    }

    @Test
    @DisplayName("A short row omits the keys it has no value for")
    void omitsKeysMissingFromShortRow() {
        List<Map<String, String>> rows = reader.read("a,b,c\n1,2\n");

        assertEquals(1, rows.size());
        assertRow(rows.get(0), "a", "1", "b", "2");
        assertFalse(rows.get(0).containsKey("c"));
    }

    @Test
    @DisplayName("A long row keys each extra value column_<zero-based index>")
    void keysExtraValuesOfLongRowByZeroBasedIndex() {
        List<Map<String, String>> rows = reader.read("a,b\n1,2,3\n");

        assertEquals(1, rows.size());
        assertRow(rows.get(0), "a", "1", "b", "2", "column_2", "3");
    }

    // entry points

    @Test
    @DisplayName("read(byte[]) decodes UTF-8")
    void readsUtf8Bytes() {
        List<Map<String, String>> rows = reader.read(UTF8_SAMPLE.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, rows.size());
        assertRow(rows.get(0), "name", "Zo\u00EB", "city", "K\u00F6ln");
    }

    @Test
    @DisplayName("read(byte[], Charset) decodes with the given charset")
    void readsBytesInGivenCharset() {
        Charset latin1 = StandardCharsets.ISO_8859_1;
        byte[] latin1Bytes = UTF8_SAMPLE.getBytes(latin1);

        List<Map<String, String>> rows = reader.read(latin1Bytes, latin1);

        assertEquals(1, rows.size());
        assertRow(rows.get(0), "name", "Zo\u00EB", "city", "K\u00F6ln");
        // The same bytes are not valid UTF-8, so only an honoured charset argument yields the expected text.
        assertNotEquals("Zo\u00EB", reader.read(latin1Bytes).get(0).get("name"));
    }

    @Test
    @DisplayName("read(String) gives the same records as read(byte[]) of its UTF-8 bytes")
    void readsTextLikeItsUtf8Bytes() {
        List<Map<String, String>> fromText = reader.read(UTF8_SAMPLE);

        assertEquals(reader.read(UTF8_SAMPLE.getBytes(StandardCharsets.UTF_8)), fromText);
        assertEquals(1, fromText.size());
        assertRow(fromText.get(0), "name", "Zo\u00EB", "city", "K\u00F6ln");
    }

    @Test
    @DisplayName("Null content or charset is rejected with a NullPointerException")
    void rejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> reader.read((String) null));
        assertThrows(NullPointerException.class, () -> reader.read((byte[]) null));
        assertThrows(NullPointerException.class, () -> reader.read(null, StandardCharsets.UTF_8));
        assertThrows(NullPointerException.class, () -> reader.read(new byte[0], null));
    }

    // helpers

    /** Asserts the two records {@code {a=1, b=2}} and {@code {a=3, b=4}} shared by the line-end cases. */
    private static void assertAbRows(List<Map<String, String>> rows) {
        assertEquals(2, rows.size());
        assertRow(rows.get(0), "a", "1", "b", "2");
        assertRow(rows.get(1), "a", "3", "b", "4");
    }

    /**
     * Asserts that {@code actual} is a {@link LinkedHashMap} holding exactly the given keys, in the given order, each
     * mapped to its given value.
     *
     * @param actual    the row under test
     * @param keyValues alternating expected keys and values: key, value, key, value, ...
     */
    private static void assertRow(Map<String, String> actual, String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("keyValues must alternate keys and values");
        }
        assertInstanceOf(LinkedHashMap.class, actual);
        List<String> expectedKeys = new ArrayList<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            expectedKeys.add(keyValues[i]);
        }
        assertIterableEquals(expectedKeys, new ArrayList<>(actual.keySet()));
        for (int i = 0; i < keyValues.length; i += 2) {
            assertEquals(keyValues[i + 1], actual.get(keyValues[i]), "value of " + keyValues[i]);
        }
    }

    /** Renders CR and LF visibly in assertion messages. */
    private static String escaped(String text) {
        return text.replace("\r", "\\r").replace("\n", "\\n");
    }
}
