/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_expression_language_basics.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit test of {@link GreetingCsvMapper}, the owner of the "Map to CSV" script of flow {@code docs-greetingFlow4}
 * ({@code %output application/csv header=false}, record {@code username}, {@code age}, {@code age_of}) of
 * {@code mule-expression-language-basics/src/main/app/greeting.xml}.
 *
 * <p>The original test's greet4 request ({@code username=Mule&age=1}) gives the exact line {@code Mule,1,false}
 * followed by a line feed and no header; the record keeps the script's column order and the caller's value
 * instances; {@code age_of} follows the mapper's DataWeave 1.0 rule: a String age is compared with {@code "18"} as
 * text, a numeric age with 18 as a number, and a missing age is never over 18.
 *
 * <p>The collaborator is real: {@link DwCsvWriter} writes the CSV. No Spring context, mock or resource file is used,
 * and every expected value is written inline.
 */
class GreetingCsvMapperTest {

    private final GreetingCsvMapper mapper = new GreetingCsvMapper(new DwCsvWriter());

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("csvCases")
    @DisplayName("toCsv: one header-less line, comma separated, ending in a single LF, no CR")
    void toCsvWritesOneHeaderlessLine(Map<String, ?> payload, String expected) {
        String csv = mapper.toCsv(payload);

        assertThat(csv).isEqualTo(expected)
                .endsWith("\n")
                .doesNotContain("\r")
                .doesNotStartWith("username");
        assertThat(csv.getBytes(StandardCharsets.UTF_8)).isEqualTo(expected.getBytes(StandardCharsets.UTF_8));
        assertThat(csv.chars().filter(c -> c == '\n').count()).isEqualTo(1);
    }

    static Stream<Arguments> csvCases() {
        Map<String, Object> reorderedWithExtraKey = new LinkedHashMap<>();
        reorderedWithExtraKey.put("extra", "ignored");
        reorderedWithExtraKey.put("age", "1");
        reorderedWithExtraKey.put("username", "Mule");

        return Stream.of(
                // The original MuleExpressionLanguageBasicsIT greet4 assertion.
                Arguments.of(Named.of("username=Mule&age=1", Map.of("username", "Mule", "age", "1")),
                        "Mule,1,false\n"),
                Arguments.of(Named.of("username=Mule&age=22", Map.of("username", "Mule", "age", "22")),
                        "Mule,22,true\n"),
                Arguments.of(Named.of("username=Mule&age=18", Map.of("username", "Mule", "age", "18")),
                        "Mule,18,false\n"),
                Arguments.of(Named.of("username=Mule&age=100", Map.of("username", "Mule", "age", "100")),
                        "Mule,100,false\n"),
                Arguments.of(Named.of("Integer age 19", Map.of("username", "Mule", "age", 19)),
                        "Mule,19,true\n"),
                Arguments.of(Named.of("BigDecimal age 18.50",
                        Map.of("username", "Mule", "age", new BigDecimal("18.50"))), "Mule,18.50,true\n"),
                Arguments.of(Named.of("username holding a comma", Map.of("username", "Doe, John", "age", "30")),
                        "\"Doe, John\",30,true\n"),
                Arguments.of(Named.of("no username, no age", Map.of()),
                        ",,false\n"),
                Arguments.of(Named.of("username and age mapped to null", nullableMap(null, null)),
                        ",,false\n"),
                Arguments.of(Named.of("username only", Map.of("username", "Mule")),
                        "Mule,,false\n"),
                Arguments.of(Named.of("payload keys reordered, extra key", reorderedWithExtraKey),
                        "Mule,1,false\n"));
    }

    @Test
    @DisplayName("map: columns username, age, age_of in that order; the caller's value instances kept")
    void mapBuildsRecordInScriptColumnOrderWithCallerValues() {
        // Fresh instances rather than interned literals, so isSameAs proves the caller's objects are handed on.
        String username = new String("Mule");
        String age = new String("1");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("age", age);
        payload.put("username", username);
        payload.put("extra", "ignored");
        Map<String, Object> before = new LinkedHashMap<>(payload);

        Map<String, Object> record = mapper.map(payload);

        assertThat(record.keySet()).containsExactly("username", "age", "age_of");
        assertThat(record.get("username")).isSameAs(username);
        assertThat(record.get("age")).isSameAs(age);
        assertThat(record.get("age_of")).isInstanceOf(Boolean.class).isEqualTo(Boolean.FALSE);
        assertThat(payload).containsExactlyEntriesOf(before);
    }

    @Test
    @DisplayName("map: each call returns a new map, independent of the payload and of earlier results")
    void mapReturnsNewIndependentMapOnEveryCall() {
        Map<String, Object> payload = new HashMap<>(Map.of("username", "Mule", "age", "22"));

        Map<String, Object> first = mapper.map(payload);
        Map<String, Object> second = mapper.map(payload);

        assertThat(first).isNotSameAs(payload).isNotSameAs(second).isEqualTo(second);
        first.put("username", "changed");
        first.remove("age");
        assertThat(payload).containsOnly(Map.entry("username", "Mule"), Map.entry("age", "22"));
        assertThat(second).containsExactly(
                Map.entry("username", "Mule"), Map.entry("age", "22"), Map.entry("age_of", Boolean.TRUE));
        assertThat(mapper.map(payload)).isEqualTo(second);
    }

    @Test
    @DisplayName("map: an absent username or age stays null and age_of is false")
    void mapKeepsAbsentValuesAsNull() {
        Map<String, Object> record = mapper.map(Map.of());

        assertThat(record.keySet()).containsExactly("username", "age", "age_of");
        assertThat(record.get("username")).isNull();
        assertThat(record.get("age")).isNull();
        assertThat(record.get("age_of")).isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("null payload -> NullPointerException from map and toCsv")
    void nullPayloadIsRejected() {
        assertThatThrownBy(() -> mapper.map(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> mapper.toCsv(null)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("ageCases")
    @DisplayName("age > 18: null false; Number numerically; String and other types as text against \"18\"")
    void ageOver18FollowsDataWeaveCoercion(Object age, boolean expected) {
        assertThat(GreetingCsvMapper.ageOver18(age)).isEqualTo(expected);
        assertThat(mapper.map(nullableMap("Mule", age)).get("age_of")).isEqualTo(Boolean.valueOf(expected));
    }

    static Stream<Arguments> ageCases() {
        return Stream.of(
                Arguments.of(Named.of("null", null), false),
                // Number: numeric comparison with 18.
                Arguments.of(Named.of("Integer 1", 1), false),
                Arguments.of(Named.of("Integer 18", 18), false),
                Arguments.of(Named.of("Integer 19", 19), true),
                Arguments.of(Named.of("Integer -1", -1), false),
                Arguments.of(Named.of("Long 100", 100L), true),
                Arguments.of(Named.of("Short 18", (short) 18), false),
                Arguments.of(Named.of("Byte 22", (byte) 22), true),
                Arguments.of(Named.of("BigInteger 19", BigInteger.valueOf(19)), true),
                Arguments.of(Named.of("BigDecimal 18.0", new BigDecimal("18.0")), false),
                Arguments.of(Named.of("BigDecimal 18.01", new BigDecimal("18.01")), true),
                Arguments.of(Named.of("Double 18.0", 18.0d), false),
                Arguments.of(Named.of("Double 18.5", 18.5d), true),
                Arguments.of(Named.of("Double 1.0E10", 1.0E10d), true),
                Arguments.of(Named.of("Float 17.9", 17.9f), false),
                Arguments.of(Named.of("Double NaN", Double.NaN), false),
                Arguments.of(Named.of("Float NaN", Float.NaN), false),
                Arguments.of(Named.of("Double +Infinity", Double.POSITIVE_INFINITY), true),
                Arguments.of(Named.of("Double -Infinity", Double.NEGATIVE_INFINITY), false),
                // String: lexicographic comparison with "18", never parsed as a number.
                Arguments.of(Named.of("\"22\"", "22"), true),
                Arguments.of(Named.of("\"5\"", "5"), true),
                Arguments.of(Named.of("\"abc\"", "abc"), true),
                Arguments.of(Named.of("\"19\"", "19"), true),
                Arguments.of(Named.of("\"180\"", "180"), true),
                Arguments.of(Named.of("\"18.5\"", "18.5"), true),
                Arguments.of(Named.of("\"1\"", "1"), false),
                Arguments.of(Named.of("\"18\"", "18"), false),
                Arguments.of(Named.of("\"100\"", "100"), false),
                Arguments.of(Named.of("\"\"", ""), false),
                Arguments.of(Named.of("\"-5\"", "-5"), false),
                Arguments.of(Named.of("\" 22\"", " 22"), false),
                // Any other type: its String.valueOf text compared with "18".
                Arguments.of(Named.of("Boolean true", Boolean.TRUE), true),
                Arguments.of(Named.of("Character '9'", '9'), true),
                Arguments.of(Named.of("StringBuilder \"100\"", new StringBuilder("100")), false),
                Arguments.of(Named.of("StringBuilder \"22\"", new StringBuilder("22")), true));
    }

    // A payload map that admits null values, as Map.of does not.
    private static Map<String, Object> nullableMap(Object username, Object age) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("username", username);
        payload.put("age", age);
        return payload;
    }
}
