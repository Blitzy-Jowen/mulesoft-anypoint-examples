/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test of {@link DwCsvWriter} (shared component SC-4): the bytes of the DataWeave 1.0 {@code application/csv}
 * output the original processing-orders-with-dataweave-and-APIkit example committed as {@code report.csv}, and the
 * column, number, quoting and null rules the writer documents.
 *
 * <p>Every project that carries {@code DwCsvWriter} carries this test with only the {@code package} line changed,
 * so it depends on the writer, JUnit 5, AssertJ and the JDK alone: no Spring context, no project type and no
 * resource file. Every expected value is written inline.
 */
class DwCsvWriterTest {

    private final DwCsvWriter writer = new DwCsvWriter();

    @Test
    @DisplayName("report.csv: header and row equal the committed 68 bytes, LF line ends, trailing LF, no CR")
    void writesCommittedReportBytes() throws NoSuchAlgorithmException {
        List<Map<String, Object>> records = List.of(row(
                "totalNumberOfItems", 4,
                "totalAmount", new BigDecimal("149.93"),
                "averageSellingPrice", new BigDecimal("37.4825")));

        String csv = writer.write(records);
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);

        assertThat(csv).isEqualTo("totalNumberOfItems,totalAmount,averageSellingPrice\n4,149.93,37.4825\n");
        assertThat(bytes).hasSize(68);
        assertThat(csv).doesNotContain("\r").endsWith("\n");
        assertThat(csv.chars().filter(c -> c == '\n').count()).isEqualTo(2);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                .isEqualTo("a714697949e651545156ed6df4731e73c2dd4f73bda6b9fcac6eed8c5c806fc7");
        assertThat(writer.write(records, true)).isEqualTo(csv);
    }

    @Test
    @DisplayName("header-less form: only the record lines, each ending in LF")
    void writesNoHeaderWhenAsked() {
        List<Map<String, Object>> records = List.of(
                row("totalNumberOfItems", 4, "totalAmount", new BigDecimal("149.93")),
                row("totalNumberOfItems", 2, "totalAmount", new BigDecimal("27.60")));

        assertThat(writer.write(records, false)).isEqualTo("4,149.93\n2,27.60\n");
    }

    @Test
    @DisplayName("header-less form of the report.csv row: the row line alone, ending in LF")
    void writesReportRowWithoutHeader() {
        List<Map<String, Object>> records = List.of(row(
                "totalNumberOfItems", 4,
                "totalAmount", new BigDecimal("149.93"),
                "averageSellingPrice", new BigDecimal("37.4825")));

        assertThat(writer.write(records, false)).isEqualTo("4,149.93,37.4825\n");
    }

    @ParameterizedTest(name = "header = {0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("empty record list: no bytes at all, not even a header")
    void writesNothingForEmptyRecordList(boolean header) {
        assertThat(writer.write(List.of(), header)).isEmpty();
        assertThat(writer.write(new LinkedList<Map<String, Object>>(), header)).isEmpty();
        assertThat(writer.write(List.of())).isEmpty();
    }

    @Test
    @DisplayName("columns: keys of the first record in its order; missing or null -> empty field; extra key ignored")
    void writesEveryRecordInFirstRecordColumnOrder() {
        List<Map<String, Object>> records = new LinkedList<>();
        records.add(row("b", 1, "a", 2, "c", 3));
        records.add(row("a", "x", "c", null, "z", "ignored"));
        records.add(row());

        assertThat(writer.write(records)).isEqualTo("b,a,c\n1,2,3\n,x,\n,,\n");
    }

    @Test
    @DisplayName("null value in the first record: an empty field between its neighbours, column still in the header")
    void writesNullValueAsEmptyField() {
        List<Map<String, Object>> records = List.of(row("a", "x", "b", null, "c", "z"));

        assertThat(writer.write(records)).isEqualTo("a,b,c\nx,,z\n");
    }

    static Stream<Arguments> laterRecordsWithOtherKeys() {
        return Stream.of(
                Arguments.of("missing column -> empty field", row("a", 3), "a,b\n1,2\n3,\n"),
                Arguments.of("extra key -> not written", row("a", 3, "z", 9), "a,b\n1,2\n3,\n"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("laterRecordsWithOtherKeys")
    @DisplayName("a later record is read in the first record's columns")
    void readsLaterRecordInFirstRecordColumns(String description, Map<String, Object> second, String expected) {
        List<Map<String, Object>> records = List.of(row("a", 1, "b", 2), second);

        assertThat(writer.write(records)).isEqualTo(expected);
    }

    static Stream<Arguments> scalarFields() {
        return Stream.of(
                Arguments.of("BigDecimal keeps its scale", new BigDecimal("149.93"), "149.93"),
                Arguments.of("BigDecimal keeps trailing zeros", new BigDecimal("27.60"), "27.60"),
                Arguments.of("BigDecimal zero keeps its scale", new BigDecimal("0.00"), "0.00"),
                Arguments.of("negative BigDecimal", new BigDecimal("-19.7934"), "-19.7934"),
                Arguments.of("BigDecimal with a positive exponent, written plainly", new BigDecimal("1E+3"), "1000"),
                Arguments.of("BigDecimal with a negative exponent, written plainly", new BigDecimal("1.5E-7"),
                        "0.00000015"),
                Arguments.of("Double", 37.4825d, "37.4825"),
                Arguments.of("Double from 1e7, no exponent", 1.0E10d, "10000000000"),
                Arguments.of("Double below 1e-3, no exponent", 1.5E-4d, "0.00015"),
                Arguments.of("Double NaN", Double.NaN, "NaN"),
                Arguments.of("Double negative infinity", Double.NEGATIVE_INFINITY, "-Infinity"),
                Arguments.of("Float", 2.5f, "2.5"),
                Arguments.of("Float from 1e7, no exponent", 1.0E10f, "10000000000"),
                Arguments.of("Float NaN", Float.NaN, "NaN"),
                Arguments.of("Float positive infinity", Float.POSITIVE_INFINITY, "Infinity"),
                Arguments.of("Integer", 4, "4"),
                Arguments.of("Long", -9L, "-9"),
                Arguments.of("Boolean", Boolean.TRUE, "true"),
                Arguments.of("String", "Harry Potter", "Harry Potter"));
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("scalarFields")
    @DisplayName("scalar values keep their written form; numbers never use exponent notation")
    void writesScalarsInTheirWrittenForm(String description, Object value, String expected) {
        assertThat(writer.write(List.of(row("value", value)), false)).isEqualTo(expected + "\n");
    }

    static Stream<Arguments> quotedAndVerbatimFields() {
        return Stream.of(
                Arguments.of("comma -> quoted", "a,b", "\"a,b\""),
                Arguments.of("double quotes -> quoted, each preceded by a backslash", "say \"hi\"",
                        "\"say \\\"hi\\\"\""),
                Arguments.of("lone double quote -> quoted and escaped", "\"", "\"\\\"\""),
                Arguments.of("embedded double quote -> quoted, the quote preceded by a backslash", "a\"b",
                        "\"a\\\"b\""),
                Arguments.of("LF -> quoted, kept as is", "line1\nline2", "\"line1\nline2\""),
                Arguments.of("CR LF -> quoted, kept as is", "line1\r\nline2", "\"line1\r\nline2\""),
                Arguments.of("trailing CR -> quoted", "end\r", "\"end\r\""),
                Arguments.of("quoted field ending in a backslash -> backslash escaped", "a,b\\", "\"a,b\\\\\""),
                Arguments.of("backslash right before an embedded quote -> both escaped", "a\\\"b",
                        "\"a\\\\\\\"b\""),
                Arguments.of("backslash before a comma in a quoted field -> backslash escaped", "x\\,y",
                        "\"x\\\\,y\""),
                Arguments.of("backslash with no quoting trigger -> verbatim", "a\\b", "a\\b"),
                Arguments.of("lone backslash -> verbatim", "\\", "\\"),
                Arguments.of("empty string -> empty field", "", ""),
                Arguments.of("leading and trailing spaces -> verbatim, not trimmed", " padded ", " padded "),
                Arguments.of("semicolon and tab -> verbatim", "a;b\tc", "a;b\tc"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("quotedAndVerbatimFields")
    @DisplayName("quoting: comma, double quote, CR or LF -> quoted, with quote and backslash escaped by a backslash")
    void quotesAndEscapesValues(String description, String field, String expected) {
        assertThat(writer.write(List.of(row("value", field)), false)).isEqualTo(expected + "\n");
    }

    @Test
    @DisplayName("quoted value under a header: plain header line, then the quoted field line, each ending in LF")
    void writesQuotedValueUnderHeader() {
        assertThat(writer.write(List.of(row("v", "a,b")))).isEqualTo("v\n\"a,b\"\n");
    }

    @Test
    @DisplayName("quoted field ending in a backslash: line bytes 34,97,44,98,92,92,34,10, the closing quote unescaped")
    void escapesBackslashBeforeClosingQuote() {
        byte[] line = writer.write(List.of(row("value", "a,b\\")), false).getBytes(StandardCharsets.UTF_8);

        assertThat(line).isEqualTo(new byte[] {34, 97, 44, 98, 92, 92, 34, 10});
    }

    @Test
    @DisplayName("header names follow the quoting rule of values")
    void quotesHeaderNames() {
        List<Map<String, Object>> records = List.of(row("a,b", 1, "say \"hi\"", 2, "c\\d", 3, "e\\", "f,g\\"));

        assertThat(writer.write(records))
                .isEqualTo("\"a,b\",\"say \\\"hi\\\"\",c\\d,e\\\n1,2,3,\"f,g\\\\\"\n");
    }

    @Test
    @DisplayName("null record list -> NullPointerException 'records', with and without header")
    void rejectsNullRecordList() {
        assertThatThrownBy(() -> writer.write(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("records");
        assertThatThrownBy(() -> writer.write(null, false))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("records");
    }

    @ParameterizedTest(name = "null record at index {0}")
    @ValueSource(ints = {0, 1, 2})
    @DisplayName("null record -> NullPointerException naming its index")
    void rejectsNullRecord(int index) {
        List<Map<String, Object>> records = new ArrayList<>(List.of(row("a", 1), row("a", 2), row("a", 3)));
        records.set(index, null);

        assertThatThrownBy(() -> writer.write(records))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("records[" + index + "] is null");
    }

    @Test
    @DisplayName("one shared instance: concurrent writes from eight threads all give the same text")
    void sharedInstanceWritesConcurrently() throws InterruptedException, ExecutionException, TimeoutException {
        List<Map<String, Object>> records = List.of(
                row("name", "a,b\\", "price", new BigDecimal("27.60")),
                row("name", "say \"hi\"", "price", new BigDecimal("262.80")));
        String expected = "name,price\n\"a,b\\\\\",27.60\n\"say \\\"hi\\\"\",262.80\n";
        int threads = 8;
        int writesPerThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<List<String>>> results = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                results.add(pool.submit(() -> {
                    start.await();
                    List<String> outputs = new ArrayList<>();
                    for (int i = 0; i < writesPerThread; i++) {
                        outputs.add(writer.write(records));
                    }
                    return outputs;
                }));
            }
            start.countDown();
            for (Future<List<String>> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).hasSize(writesPerThread).containsOnly(expected);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // LinkedHashMap keeps the column order each test states and, unlike Map.of, accepts null values.
    private static Map<String, Object> row(Object... keysAndValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            row.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return row;
    }
}
