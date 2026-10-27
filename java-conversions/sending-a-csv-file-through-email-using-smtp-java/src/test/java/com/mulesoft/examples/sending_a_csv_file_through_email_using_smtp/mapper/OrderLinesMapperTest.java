/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit test of {@link OrderLinesMapper}, the owner of the {@code Transform CSV to Map} script of the original
 * {@code csv-to-smtp.xml} flow: one {@link LinkedHashMap} per CSV row, keyed in the script's order, with
 * {@code name} and {@code orderId} as the CSV text and {@code pricePerUnit}, {@code units} and {@code totalPrice} as
 * {@link BigDecimal}s whose integral values carry scale 0.
 *
 * <p>The mail-body test compares with the original test's committed {@code email.txt} exactly, as the original
 * {@code SendingACsvFileThroughEmailUsingSmtpIT} did. Every other input is a row built in the column order of
 * {@code input.csv} ({@code orderId}, {@code name}, {@code units}, {@code pricePerUnit}), as {@link DwCsvReader} reads
 * it, so the key-order assertions also prove that the CSV order is not carried over.
 */
class OrderLinesMapperTest {

    private static final List<String> SCRIPT_KEY_ORDER =
            List.of("name", "orderId", "pricePerUnit", "units", "totalPrice");

    private static final List<String> NUMBER_KEYS = List.of("pricePerUnit", "units", "totalPrice");

    private final OrderLinesMapper mapper = new OrderLinesMapper();

    @Test
    void keysFollowScriptOrder() {
        Map<String, Object> line = mapOne(row("1", "aaa", "2.0", "10"));

        assertIterableEquals(SCRIPT_KEY_ORDER, line.keySet());
    }

    @Test
    void returnsArrayListOfLinkedHashMapsInInputOrder() {
        List<Map<String, String>> rows = List.of(row("1", "aaa", "2.0", "10"), row("2", "bbb", "4.15", "5"));
        List<Map<String, String>> unchanged = rows.stream().<Map<String, String>>map(LinkedHashMap::new).toList();

        List<Map<String, Object>> lines = mapper.map(rows);

        assertInstanceOf(ArrayList.class, lines);
        assertEquals(2, lines.size());
        for (Map<String, Object> line : lines) {
            assertInstanceOf(LinkedHashMap.class, line);
        }
        assertEquals("aaa", lines.get(0).get("name"));
        assertEquals("bbb", lines.get(1).get("name"));
        assertEquals(unchanged, rows, "input rows are never modified");
    }

    @ParameterizedTest(name = "orderId [{0}], name [{1}]")
    @CsvSource({
        "1,   aaa",
        // Leading zeros and surrounding spaces survive: both values are the CSV text, never coerced or trimmed.
        "007, ' bbb '"
    })
    void nameAndOrderIdStayStrings(String orderId, String name) {
        Map<String, Object> line = mapOne(row(orderId, name, "2.0", "10"));

        assertEquals(orderId, assertInstanceOf(String.class, line.get("orderId")));
        assertEquals(name, assertInstanceOf(String.class, line.get("name")));
    }

    @ParameterizedTest(name = "units {0} x pricePerUnit {1} = {4}")
    @CsvSource({
        // units, pricePerUnit, expected units, expected pricePerUnit, expected totalPrice
        "2.0,  10, 2,    10, 20",
        // Trailing zeros of a non-integral value (such as 2.50) are not asserted: neither the original output nor
        // the DataWeave 1.0 evidence settles how they rendered.
        "4.15, 5,  4.15, 5,  20.75"
    })
    void numbersAreBigDecimalWithIntegralScaleZero(String units, String pricePerUnit, String expectedUnits,
            String expectedPricePerUnit, String expectedTotalPrice) {
        Map<String, Object> line = mapOne(row("1", "aaa", units, pricePerUnit));

        assertNumbers(line, expectedUnits, expectedPricePerUnit, expectedTotalPrice);
        for (String key : NUMBER_KEYS) {
            BigDecimal value = (BigDecimal) line.get(key);
            if (value.stripTrailingZeros().scale() <= 0) {
                assertEquals(0, value.scale(), key + " is integral, so its scale is 0");
            }
        }
    }

    @ParameterizedTest(name = "units {0} x pricePerUnit {1} = {4}")
    @CsvSource({
        // units, pricePerUnit, expected units, expected pricePerUnit, expected totalPrice
        "1,  100,     1,  100,  100",
        // stripTrailingZeros() alone would render these 1E+3 and 1E+4.
        "10, 1000.00, 10, 1000, 10000"
    })
    void integralValuesNeverUseExponentNotation(String units, String pricePerUnit, String expectedUnits,
            String expectedPricePerUnit, String expectedTotalPrice) {
        Map<String, Object> line = mapOne(row("1", "aaa", units, pricePerUnit));

        assertNumbers(line, expectedUnits, expectedPricePerUnit, expectedTotalPrice);
        assertNoExponent(line);
    }

    @ParameterizedTest(name = "units {0} x pricePerUnit {1} = {4}")
    @CsvSource({
        // units, pricePerUnit, expected units, expected pricePerUnit, expected totalPrice
        "0,    -3.5, 0,  -3.5, 0",
        "0.00, 7,    0,  7,    0",
        "-2,   3,    -2, 3,    -6"
    })
    void zeroAndNegativeValuesMapArithmetically(String units, String pricePerUnit, String expectedUnits,
            String expectedPricePerUnit, String expectedTotalPrice) {
        Map<String, Object> line = mapOne(row("1", "aaa", units, pricePerUnit));

        assertNumbers(line, expectedUnits, expectedPricePerUnit, expectedTotalPrice);
        BigDecimal product = new BigDecimal(units).multiply(new BigDecimal(pricePerUnit));
        assertEquals(0, product.compareTo((BigDecimal) line.get("totalPrice")),
                "totalPrice equals units * pricePerUnit");
        assertNoExponent(line);
    }

    @ParameterizedTest(name = "{0} [{1}]")
    @CsvSource({
        "units,        abc",
        "pricePerUnit, x1",
        // Values are never trimmed, so a number with a surrounding space is not a number.
        "units,        ' 2'",
        // An empty CSV field reads as an empty string, which is not a number either.
        "pricePerUnit, ''"
    })
    void nonNumericValueRaisesIllegalArgumentNamingField(String field, String value) {
        Map<String, String> row = row("1", "aaa", "2.0", "10");
        row.put(field, value);
        List<Map<String, String>> rows = List.of(row);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> mapper.map(rows));

        // NumberFormatException is itself an IllegalArgumentException, so the exact type proves it was wrapped.
        assertEquals(IllegalArgumentException.class, e.getClass());
        assertEquals(field + " is not a number: " + value, e.getMessage());
        assertInstanceOf(NumberFormatException.class, e.getCause());
    }

    @ParameterizedTest(name = "without {0}")
    @CsvSource({
        "units,        'totalPrice cannot be computed: units=null, pricePerUnit=10'",
        "pricePerUnit, 'totalPrice cannot be computed: units=2.0, pricePerUnit=null'"
    })
    void missingNumericColumnFailsOnProduct(String missingColumn, String expectedMessage) {
        Map<String, String> row = row("1", "aaa", "2.0", "10");
        row.remove(missingColumn);
        List<Map<String, String>> rows = List.of(row);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> mapper.map(rows));

        assertEquals(IllegalArgumentException.class, e.getClass());
        assertEquals(expectedMessage, e.getMessage());
    }

    @Test
    void emptyInputGivesEmptyList() {
        List<Map<String, Object>> lines = mapper.map(List.of());

        assertInstanceOf(ArrayList.class, lines);
        assertTrue(lines.isEmpty());
    }

    @Test
    void nullRowsOrNullRowRaiseNullPointerException() {
        NullPointerException nullRows = assertThrows(NullPointerException.class, () -> mapper.map(null));
        assertEquals("rows", nullRows.getMessage());

        List<Map<String, String>> withNullRow = new ArrayList<>();
        withNullRow.add(null);
        assertThrows(NullPointerException.class, () -> mapper.map(withNullRow));
    }

    @Test
    void inputCsvMapsToEmailTxtBody() throws IOException {
        byte[] csv = classpathResource("/input.csv");
        String expectedBody = new String(classpathResource("/email.txt"), StandardCharsets.UTF_8);

        List<Map<String, Object>> lines = mapper.map(new DwCsvReader().read(csv));

        assertEquals(expectedBody, lines.toString());
    }

    /** A CSV row as {@link DwCsvReader} reads {@code input.csv}: keyed in its column order. */
    private static Map<String, String> row(String orderId, String name, String units, String pricePerUnit) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("orderId", orderId);
        row.put("name", name);
        row.put("units", units);
        row.put("pricePerUnit", pricePerUnit);
        return row;
    }

    private Map<String, Object> mapOne(Map<String, String> row) {
        List<Map<String, Object>> lines = mapper.map(List.of(row));
        assertEquals(1, lines.size());
        return lines.get(0);
    }

    /** Each number is a {@link BigDecimal} with the expected rendering and the scale that rendering implies. */
    private static void assertNumbers(Map<String, Object> line, String expectedUnits, String expectedPricePerUnit,
            String expectedTotalPrice) {
        assertNumber(line, "units", expectedUnits);
        assertNumber(line, "pricePerUnit", expectedPricePerUnit);
        assertNumber(line, "totalPrice", expectedTotalPrice);
    }

    private static void assertNumber(Map<String, Object> line, String key, String expected) {
        BigDecimal actual = assertInstanceOf(BigDecimal.class, line.get(key), key);
        assertEquals(expected, actual.toString(), key);
        assertEquals(new BigDecimal(expected).scale(), actual.scale(), key + " scale");
    }

    private static void assertNoExponent(Map<String, Object> line) {
        for (String key : NUMBER_KEYS) {
            String text = line.get(key).toString();
            assertFalse(text.contains("E"), key + " rendered " + text);
        }
    }

    private static byte[] classpathResource(String name) throws IOException {
        try (InputStream in = OrderLinesMapperTest.class.getResourceAsStream(name)) {
            assertNotNull(in, "classpath resource " + name);
            return in.readAllBytes();
        }
    }
}
