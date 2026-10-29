/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.netsuite_data_retrieval.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit test of {@link DwJsonWriter} (shared component SC-4): the bytes of the DataWeave 1.0 {@code application/json}
 * output the original processing-orders-with-dataweave-and-APIkit example committed as {@code orders.json}, and the
 * layout, value, escaping and non-finite number rules the writer documents.
 *
 * <p>Every project that carries {@code DwJsonWriter} carries this test with only the {@code package} line changed,
 * so it depends on the writer, JUnit 5, AssertJ and the JDK alone: no Spring context, no project type and no
 * resource file. Every expected value is written inline, and control and non-ASCII characters are built from their
 * code points so the source stays plain ASCII.
 */
class DwJsonWriterTest {

    /**
     * {@code processing-orders-with-dataweave-and-APIkit/src/main/resources/orders.json} as committed: 793 bytes,
     * 48 lines separated by 47 LFs, and nothing after the closing brace.
     */
    private static final String ORDERS_JSON = """
            {
              "orders": [
                {
                  "title": "Everyday Italian",
                  "prices": [
                    {
                      "price": 27.60,
                      "currency": "EUR"
                    },
                    {
                      "price": 262.80,
                      "currency": "ARS"
                    },
                    {
                      "price": 19.80,
                      "currency": "GBP"
                    }
                  ],
                  "authors": [
                    {
                      "author": "Giada De Laurentiis"
                    }
                  ]
                },
                {
                  "title": "Harry Potter",
                  "prices": [
                    {
                      "price": 27.5908,
                      "currency": "EUR"
                    },
                    {
                      "price": 262.7124,
                      "currency": "ARS"
                    },
                    {
                      "price": 19.7934,
                      "currency": "GBP"
                    }
                  ],
                  "authors": [
                    {
                      "author": "J K. Rowling"
                    }
                  ]
                }
              ]
            }""";

    /** An enum whose {@code toString()} differs from its {@code name()}, which the writer must use. */
    private enum Currency {
        EUR {
            @Override
            public String toString() {
                return "euro";
            }
        }
    }

    private final DwJsonWriter writer = new DwJsonWriter();

    @Test
    @DisplayName("orders.json: the full order tree equals the committed 793 bytes, two-space indent, LF only, no trailing LF")
    void writesCommittedOrdersJsonBytes() throws NoSuchAlgorithmException {
        Map<String, Object> orders = obj("orders", List.of(
                order("Everyday Italian", "Giada De Laurentiis", "27.60", "262.80", "19.80"),
                order("Harry Potter", "J K. Rowling", "27.5908", "262.7124", "19.7934")));

        String json = writer.write(orders);
        byte[] bytes = writer.writeBytes(orders);

        assertThat(json).isEqualTo(ORDERS_JSON);
        assertThat(bytes).hasSize(793).isEqualTo(ORDERS_JSON.getBytes(StandardCharsets.UTF_8));
        assertThat(json).doesNotContain("\r").doesNotContain("\t").endsWith("\n}");
        assertThat(json.chars().filter(c -> c == '\n').count()).isEqualTo(47);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                .isEqualTo("0784d83836248b1c080655606990288f794693889fb54d41b04e6af8a4990610");
    }

    static Stream<Arguments> emptyContainers() {
        return Stream.of(
                Arguments.of("empty LinkedHashMap", new LinkedHashMap<String, Object>(), "{}"),
                Arguments.of("empty Map.of()", Map.of(), "{}"),
                Arguments.of("empty List", List.of(), "[]"),
                Arguments.of("empty Object[]", new Object[0], "[]"),
                Arguments.of("empty Iterable that is not a Collection",
                        (Iterable<Object>) Collections::emptyIterator, "[]"),
                Arguments.of("object holding an empty array, [] on its key's line",
                        obj("orders", List.of()), "{\n  \"orders\": []\n}"),
                Arguments.of("object holding an empty object, {} on its key's line",
                        obj("a", new LinkedHashMap<String, Object>()), "{\n  \"a\": {}\n}"),
                Arguments.of("empty containers as entries and elements, on their own or their key's line",
                        obj("a", Map.of(), "b", List.of(), "c", List.of(List.of(), Map.of())),
                        "{\n  \"a\": {},\n  \"b\": [],\n  \"c\": [\n    [],\n    {}\n  ]\n}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("emptyContainers")
    @DisplayName("empty object -> {} and empty array -> [], nothing between the brackets")
    void writesEmptyContainersAsBracketPairs(String description, Object value, String expected) {
        assertThat(writer.write(value)).isEqualTo(expected);
    }

    static Stream<Arguments> containers() {
        Map<Object, Object> nonStringKeys = new LinkedHashMap<>();
        nonStringKeys.put(1, "one");
        nonStringKeys.put(null, "none");
        nonStringKeys.put('c', true);
        return Stream.of(
                Arguments.of("object entries in map order, comma and LF between, closer at the parent indent",
                        obj("b", 1, "a", 2), "{\n  \"b\": 1,\n  \"a\": 2\n}"),
                Arguments.of("array elements one per line",
                        List.of(1, 2, 3), "[\n  1,\n  2,\n  3\n]"),
                Arguments.of("Object[] is an array, null element written as null",
                        new Object[] {"x", null}, "[\n  \"x\",\n  null\n]"),
                Arguments.of("Iterable that is not a Collection, in iteration order",
                        (Iterable<Object>) () -> List.<Object>of(3, 1, 2).iterator(), "[\n  3,\n  1,\n  2\n]"),
                Arguments.of("LinkedHashSet in insertion order",
                        new LinkedHashSet<>(List.of("z", "a")), "[\n  \"z\",\n  \"a\"\n]"),
                Arguments.of("null entry value written as null",
                        obj("a", null), "{\n  \"a\": null\n}"),
                Arguments.of("non-String keys written as the JSON string of String.valueOf(key)",
                        nonStringKeys, "{\n  \"1\": \"one\",\n  \"null\": \"none\",\n  \"c\": true\n}"),
                Arguments.of("keys escaped like string values",
                        obj("say \"hi\"\n", 1), "{\n  \"say \\\"hi\\\"\\n\": 1\n}"),
                Arguments.of("nested object and array open on their key's line, two spaces per level",
                        obj("outer", obj("inner", List.of(obj("k", "v")))),
                        "{\n  \"outer\": {\n    \"inner\": [\n      {\n        \"k\": \"v\"\n      }\n    ]\n  }\n}"),
                Arguments.of("nested arrays and objects as array elements",
                        List.of(1, new Object[] {2, null}, obj("k", "v")),
                        "[\n  1,\n  [\n    2,\n    null\n  ],\n  {\n    \"k\": \"v\"\n  }\n]"),
                Arguments.of("array of scalars as an entry value, one element per line at the next indent",
                        obj("a", List.of(1, 2)), "{\n  \"a\": [\n    1,\n    2\n  ]\n}"),
                Arguments.of("arrays as array elements, an empty one written [] on its own line",
                        List.of(List.of(1), List.of()), "[\n  [\n    1\n  ],\n  []\n]"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("containers")
    @DisplayName("layout: \"key\": value entries and array elements one per line, LF only, no trailing LF")
    void writesContainersOneEntryPerLine(String description, Object value, String expected) {
        assertThat(writer.write(value)).isEqualTo(expected);
    }

    @Test
    @DisplayName("key order: a map's own iteration order, neither sorted nor reordered")
    void writesKeysInTheMapsOwnIterationOrder() {
        Map<String, Object> linked = new LinkedHashMap<>();
        linked.put("z", 1);
        linked.put("a", 2);
        linked.put("m", 3);

        assertThat(writer.write(linked)).isEqualTo("{\n  \"z\": 1,\n  \"a\": 2,\n  \"m\": 3\n}");

        List<String> insertionOrder = List.of("title", "prices", "authors", "price", "currency");
        Map<String, Object> hashMap = new HashMap<>();
        for (int i = 0; i < insertionOrder.size(); i++) {
            hashMap.put(insertionOrder.get(i), i + 1);
        }
        // These keys iterate in neither insertion nor alphabetical order, so sorting or reordering would show.
        assertThat(new ArrayList<>(hashMap.keySet()))
                .isNotEqualTo(insertionOrder)
                .isNotEqualTo(insertionOrder.stream().sorted().toList());
        StringJoiner expected = new StringJoiner(",\n", "{\n", "\n}");
        for (String key : hashMap.keySet()) {
            expected.add("  \"" + key + "\": " + hashMap.get(key));
        }

        assertThat(writer.write(hashMap)).isEqualTo(expected.toString());
    }

    static Stream<Arguments> scalars() {
        int[] primitives = {1, 2};
        Object quoting = new Object() {
            @Override
            public String toString() {
                return "a\"b";
            }
        };
        return Stream.of(
                Arguments.of("null", null, "null"),
                Arguments.of("Boolean true", Boolean.TRUE, "true"),
                Arguments.of("Boolean false", Boolean.FALSE, "false"),
                Arguments.of("BigDecimal keeps its scale", new BigDecimal("27.60"), "27.60"),
                Arguments.of("BigDecimal with scale 0", new BigDecimal("4"), "4"),
                Arguments.of("BigDecimal zero keeps its scale", new BigDecimal("0.00"), "0.00"),
                Arguments.of("BigDecimal keeps its trailing zeros", new BigDecimal("1.000"), "1.000"),
                Arguments.of("negative BigDecimal", new BigDecimal("-19.7934"), "-19.7934"),
                Arguments.of("BigDecimal with a positive exponent, written plainly", new BigDecimal("1E+3"), "1000"),
                Arguments.of("BigDecimal with a negative exponent, written plainly", new BigDecimal("1.5E-7"),
                        "0.00000015"),
                Arguments.of("Byte", (byte) -8, "-8"),
                Arguments.of("Short", (short) 300, "300"),
                Arguments.of("Integer", 4, "4"),
                Arguments.of("Long", Long.MAX_VALUE, "9223372036854775807"),
                Arguments.of("BigInteger", new BigInteger("123456789012345678901234567890"),
                        "123456789012345678901234567890"),
                Arguments.of("Double keeps the digits of Double.toString", 37.4825d, "37.4825"),
                Arguments.of("Double 3.0 keeps its fraction digit", 3.0d, "3.0"),
                Arguments.of("fractional Double", 1.5d, "1.5"),
                Arguments.of("negative Double", -0.5d, "-0.5"),
                Arguments.of("Double from 1e7, no exponent", 1.0E10d, "10000000000"),
                Arguments.of("Double below 1e-3, no exponent", 1.5E-4d, "0.00015"),
                Arguments.of("Float is not widened to a double", 0.1f, "0.1"),
                Arguments.of("Float 3.0 keeps its fraction digit", 3.0f, "3.0"),
                Arguments.of("negative Float", -2.5f, "-2.5"),
                Arguments.of("Float from 1e7, no exponent", 1.0E10f, "10000000000"),
                Arguments.of("String", "Harry Potter", "\"Harry Potter\""),
                Arguments.of("empty String", "", "\"\""),
                Arguments.of("StringBuilder is a CharSequence", new StringBuilder("EUR"), "\"EUR\""),
                Arguments.of("Character", 'x', "\"x\""),
                Arguments.of("Character double quote escaped", '"', "\"\\\"\""),
                Arguments.of("Enum written by name(), not toString()", Currency.EUR, "\"EUR\""),
                Arguments.of("other type -> JSON string of String.valueOf", LocalDate.of(2016, 1, 31),
                        "\"2016-01-31\""),
                Arguments.of("other type -> String.valueOf escaped", quoting, "\"a\\\"b\""),
                Arguments.of("primitive int[] is not an array -> JSON string of String.valueOf", primitives,
                        "\"" + String.valueOf(primitives) + "\""));
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("scalars")
    @DisplayName("root scalars: their declared written form, no exponent notation, no trailing LF")
    void writesScalarsInTheirDeclaredForm(String description, Object value, String expected) {
        assertThat(writer.write(value)).isEqualTo(expected);
    }

    static Stream<Arguments> strings() {
        return Stream.of(
                Arguments.of("double quote -> backslash and quote", "a\"b", "\"a\\\"b\""),
                Arguments.of("backslash -> two backslashes", "a\\b", "\"a\\\\b\""),
                Arguments.of("backspace -> backslash b", "\b", "\"\\b\""),
                Arguments.of("form feed -> backslash f", "\f", "\"\\f\""),
                Arguments.of("line feed -> backslash n", "line1\nline2", "\"line1\\nline2\""),
                Arguments.of("carriage return -> backslash r", "end\r", "\"end\\r\""),
                Arguments.of("tab -> backslash t", "a\tb", "\"a\\tb\""),
                Arguments.of("U+0000 -> six-character escape", cp(0x00), "\"\\u0000\""),
                Arguments.of("U+000B -> six-character escape, lowercase hex", cp(0x0B), "\"\\u000b\""),
                Arguments.of("U+001A -> six-character escape, lowercase hex", cp(0x1A), "\"\\u001a\""),
                Arguments.of("U+001F, the last control character -> six-character escape", cp(0x1F),
                        "\"\\u001f\""),
                Arguments.of("space, the first character not escaped", " ", "\" \""),
                Arguments.of("U+007F unchanged", cp(0x7F), quoted(cp(0x7F))),
                Arguments.of("solidus unchanged", "a/b", "\"a/b\""),
                Arguments.of("U+00E9 unchanged", "Jos" + cp(0xE9), quoted("Jos" + cp(0xE9))),
                Arguments.of("U+2028 unchanged", cp(0x2028), quoted(cp(0x2028))),
                Arguments.of("U+2029 unchanged", cp(0x2029), quoted(cp(0x2029))),
                Arguments.of("surrogate pair unchanged", cp(0x1F600), quoted(cp(0x1F600))),
                Arguments.of("escapes mixed with plain text",
                        "\"q\"\\" + cp(0x01) + "\t/x", "\"\\\"q\\\"\\\\\\u0001\\t/x\""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strings")
    @DisplayName("strings: quote and backslash escaped, named and six-character control escapes, the rest unchanged")
    void escapesStrings(String description, String text, String expected) {
        assertThat(writer.write(text)).isEqualTo(expected);
    }

    static Stream<Arguments> nonFiniteNumbers() {
        return Stream.of(
                Arguments.of("Double NaN", Double.NaN, "JSON cannot represent NaN"),
                Arguments.of("Double positive infinity", Double.POSITIVE_INFINITY, "JSON cannot represent Infinity"),
                Arguments.of("Double negative infinity", Double.NEGATIVE_INFINITY, "JSON cannot represent -Infinity"),
                Arguments.of("Float NaN", Float.NaN, "JSON cannot represent NaN"),
                Arguments.of("Float positive infinity", Float.POSITIVE_INFINITY, "JSON cannot represent Infinity"),
                Arguments.of("Float negative infinity", Float.NEGATIVE_INFINITY, "JSON cannot represent -Infinity"),
                Arguments.of("NaN nested in an object inside an array", List.of(obj("price", Double.NaN)),
                        "JSON cannot represent NaN"),
                Arguments.of("infinity as an Object[] element", new Object[] {1, Float.NEGATIVE_INFINITY},
                        "JSON cannot represent -Infinity"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonFiniteNumbers")
    @DisplayName("NaN and infinite Double or Float, at the root or nested -> IllegalArgumentException")
    void rejectsNonFiniteNumbers(String description, Object value, String message) {
        assertThatThrownBy(() -> writer.write(value))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
        assertThatThrownBy(() -> writer.writeBytes(value))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    static Stream<Arguments> utf8Encodings() {
        return Stream.of(
                Arguments.of("null", null, bytes('n', 'u', 'l', 'l')),
                Arguments.of("ASCII string", "EUR", bytes('"', 'E', 'U', 'R', '"')),
                Arguments.of("U+00E9, two bytes", cp(0xE9), bytes('"', 0xC3, 0xA9, '"')),
                Arguments.of("U+20AC, three bytes", cp(0x20AC), bytes('"', 0xE2, 0x82, 0xAC, '"')),
                Arguments.of("U+2028, three bytes, not escaped", cp(0x2028), bytes('"', 0xE2, 0x80, 0xA8, '"')),
                Arguments.of("surrogate pair, one four-byte sequence", cp(0x1F600),
                        bytes('"', 0xF0, 0x9F, 0x98, 0x80, '"')),
                Arguments.of("object holding a non-ASCII value", obj("c", cp(0xE9)),
                        bytes('{', '\n', ' ', ' ', '"', 'c', '"', ':', ' ', '"', 0xC3, 0xA9, '"', '\n', '}')));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("utf8Encodings")
    @DisplayName("writeBytes: the UTF-8 encoding of write, with no byte order mark")
    void writeBytesEncodesUtf8(String description, Object value, byte[] expected) {
        assertThat(writer.writeBytes(value))
                .isEqualTo(expected)
                .isEqualTo(writer.write(value).getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> order(String title, String author, String eur, String ars, String gbp) {
        return obj(
                "title", title,
                "prices", List.of(price(eur, "EUR"), price(ars, "ARS"), price(gbp, "GBP")),
                "authors", List.of(obj("author", author)));
    }

    private static Map<String, Object> price(String amount, String currency) {
        return obj("price", new BigDecimal(amount), "currency", currency);
    }

    // LinkedHashMap keeps the key order each test states and, unlike Map.of, accepts null values.
    private static Map<String, Object> obj(Object... keysAndValues) {
        Map<String, Object> object = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            object.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return object;
    }

    private static String cp(int codePoint) {
        return Character.toString(codePoint);
    }

    private static String quoted(String text) {
        return "\"" + text + "\"";
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }
}
