package com.mulesoft.examples.hello_world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Structure comparison of nondeterministic JSON bodies (ST-2): {@link FixtureParityTest.Content#structureDifference}
 * compares each object's member names in order, every occurrence of a repeated name kept (DataWeave's XML-to-JSON
 * writes repeated elements as repeated keys), value kinds and array lengths, and never values. Content that is not
 * one JSON value (empty, malformed, or followed by trailing tokens) is not JSON: an actual body of that kind fails,
 * and an expected body of that kind compares as text, by empty versus non-empty. The helpers are called alone, on
 * literal bodies, so none of the replay state {@link FixtureParityTest} keeps for its own run is touched.
 *
 * <p>In the table an empty column is {@code null}: no content type, empty content, or no difference.
 */
class FixtureParityJsonStructureTest {

    private static final String JSON = "application/json";

    @ParameterizedTest(name = "[{index}] {0} | {1} | {2} -> {3}")
    @CsvSource(delimiter = '|', textBlock = """
            # Every occurrence of a repeated member name counts, in document order: removed, added or reordered.
            application/json | {"a":1,"a":2} | {"a":2} | $: expected keys [a, a] but was [a]
            application/json | {"a":2} | {"a":1,"a":2} | $: expected keys [a] but was [a, a]
            application/json | {"a":1,"b":2,"a":3} | {"a":1,"a":3,"b":2} | $: expected keys [a, b, a] but was [a, a, b]
            application/json | {"a":1,"a":3,"b":2} | {"a":3,"b":2} | $: expected keys [a, a, b] but was [a, b]
            # Repeated members nested in arrays and objects; each occurrence pairs with the same occurrence.
            application/json | [{"x":1,"x":2}] | [{"x":2}] | $[0]: expected keys [x, x] but was [x]
            application/json | {"o":{"k":"v","k":"w"}} | {"o":{"k":"w"}} | $.o: expected keys [k, k] but was [k]
            application/json | {"r":[{},{"e":1,"e":2}]} | {"r":[{},{"e":2}]} | $.r[1]: expected keys [e, e] but was [e]
            application/json | {"a":{},"a":{"y":1}} | {"a":{},"a":{"x":1}} | $.a: expected keys [y] but was [x]
            application/json | {"a":1,"a":"x"} | {"a":1,"a":2} | $.a: expected STRING but was NUMBER
            application/json | {"a":[1],"a":[1,2]} | {"a":[1],"a":[1]} | $.a: expected 2 elements but was 1
            # Value kinds, array lengths and key order.
            application/json | {"n":"1"} | {"n":1} | $.n: expected STRING but was NUMBER
            application/json | {"n":null} | {"n":{}} | $.n: expected NULL but was OBJECT
            application/json | [true] | ["true"] | $[0]: expected BOOLEAN but was STRING
            application/json | {} | [] | $: expected OBJECT but was ARRAY
            application/json | {"l":[1,2]} | {"l":[1]} | $.l: expected 2 elements but was 1
            application/json | {"a":1,"b":2} | {"b":2,"a":1} | $: expected keys [a, b] but was [b, a]
            # Values are never compared: the same structure with other values, number forms or layout agrees.
            application/json | {"a":1,"a":2} | {"a":7,"a":9} |
            application/json | {"id":"x1","id":"x2","n":[1,2]} | { "id": "y1", "id": "y2", "n": [3, 4] } |
            application/json | [{"x":1,"x":2},{"x":3}] | [{"x":5,"x":6},{"x":7}] |
            application/json | {"v":1} | {"v":-1.25e3} |
            application/json | "x" | "y" |
            application/json | true | false |
            # Not one JSON value: the actual body fails; the expected body falls back to empty versus non-empty.
            application/json | {"a":1} | {"a":1} {"a":1} | the actual content is not JSON
            application/json | {"a":1} | {"a":1}x | the actual content is not JSON
            application/json | {"a":1} | {"a":1,} | the actual content is not JSON
            application/json | {"a":1} | {"a":1 | the actual content is not JSON
            application/json | {"a":1} | '   ' | the actual content is not JSON
            application/json | {"a":1} | | the actual content is not JSON
            application/json | {"a":1} {"a":1} | {"b":[]} |
            application/json | {"a": | plain text |
            application/json | {"a":1} trailing | | expected non-empty content
            application/json | | {"a":1} | expected empty content
            application/json | | |
            # Detection: a content type naming JSON or XML decides, otherwise the expected body's first character.
                             | {"a":1,"a":2} | {"a":2} | $: expected keys [a, a] but was [a]
            text/plain       | '  [{"a":1,"a":2}]' | [{"a":2}] | $[0]: expected keys [a, a] but was [a]
            application/problem+json;charset=UTF-8 | {"a":1,"a":2} | {"a":2} | $: expected keys [a, a] but was [a]
            application/xml  | {"a":1,"a":2} | {"a":2} |
            text/plain       | plain {"a":1,"a":2} | plain {"a":2} |
            """)
    void comparesJsonStructureOnly(String contentType, String expected, String actual, String difference) {
        assertEquals(difference, FixtureParityTest.Content.structureDifference(bytes(expected), bytes(actual),
                contentType));
    }

    @ParameterizedTest(name = "content type {0}")
    @NullSource
    @ValueSource(strings = JSON)
    void aByteOrderMarkBeforeTheJsonStillReadsAsJson(String contentType) {
        byte[] expected = bytes("\uFEFF{\"a\":1,\"a\":2}");

        assertEquals("$: expected keys [a, a] but was [a]",
                FixtureParityTest.Content.structureDifference(expected, bytes("{\"a\":2}"), contentType));
        assertNull(FixtureParityTest.Content.structureDifference(expected, bytes("{\"a\":3,\"a\":4}"), contentType));
    }

    /** The deepest nesting the parser accepts reads without exhausting the stack; one level more is not JSON. */
    @ParameterizedTest(name = "actual depth {0}")
    @CsvSource(delimiter = '|', value = {
            "1000 |",
            "1001 | the actual content is not JSON",
    })
    void nestingUpToTheParserLimitIsCompared(int actualDepth, String difference) {
        assertEquals(difference, FixtureParityTest.Content.structureDifference(bytes(nested(1000)),
                bytes(nested(actualDepth)), JSON));
    }

    @Test
    void aNondeterministicReplayFailsOnARemovedRepeatedMember() {
        AssertionError failure = assertThrows(AssertionError.class, () -> FixtureParityTest.Content.compare(
                "scenario", false, "body", bytes("{\"a\":1,\"a\":2}"), bytes("{\"a\":2}"), JSON));

        assertTrue(failure.getMessage().startsWith(
                "scenario: body differs in structure ($: expected keys [a, a] but was [a])"), failure.getMessage());
    }

    /** UTF-8 bytes of the text, or no bytes for an empty table column. */
    private static byte[] bytes(String text) {
        return text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
    }

    /** Arrays nested {@code depth} deep, the innermost one empty. */
    private static String nested(int depth) {
        return "[".repeat(depth) + "]".repeat(depth);
    }
}
