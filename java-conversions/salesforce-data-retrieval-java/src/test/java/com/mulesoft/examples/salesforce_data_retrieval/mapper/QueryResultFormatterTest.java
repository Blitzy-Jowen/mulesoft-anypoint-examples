/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_data_retrieval.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

/**
 * Unit test of {@link QueryResultFormatter}, the owner of the response text of the original POST flow
 * ({@code salesforce-id-retrieval.xml:28-35}), which appended every {@code java.util.HashMap} record of
 * {@code sfdc:query-all} to an empty list and returned that list as a string.
 *
 * <p>The expected text is taken from {@code toString()} of the very JDK collections passed to the formatter, because
 * {@code List.toString()} over {@code HashMap.toString()} is the byte contract the original produced; literal
 * expectations are used only where the input's iteration order is fixed by construction.
 */
class QueryResultFormatterTest {

    /** The original's {@code reply.txt}, carried unchanged on the test classpath. */
    private static final String REPLY_RESOURCE = "/reply.txt";

    private static final int REPLY_LINE_COUNT = 6;

    private static final int FIELDS_PER_RECORD = 3;

    private QueryResultFormatter formatter;

    @BeforeEach
    void createFormatter() {
        formatter = new QueryResultFormatter();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void noRecordsRenderAsEmptyList(List<Map<String, Object>> records) {
        assertThat(formatter.format(records)).isEqualTo("[]");
    }

    @Test
    void singleRecordRendersAsListOfOneMap() {
        Map<String, Object> user = new HashMap<>();
        user.put("type", "User");
        user.put("Id", "X");

        String text = formatter.format(List.of(user));

        assertThat(text).isEqualTo(List.of(user).toString()).startsWith("[{").endsWith("}]");
    }

    @Test
    void twoRecordsRenderInListOrderSeparatedByComma() {
        Map<String, Object> first = user("kicks+a@mulesoft.com", "00520000003LtYCAA0", "Mule orgA");
        Map<String, Object> second =
                user("kicks+a-integration@mulesoft.com", "00520000003LtvGAAS", "Mule orgA Integration");

        String text = formatter.format(List.of(first, second));

        assertThat(text).isEqualTo(List.of(first, second).toString()).contains("}, {");
        assertThat(text.indexOf(first.toString())).isLessThan(text.indexOf(second.toString()));
    }

    @Test
    void recordKeysFollowTheMapsOwnIterationOrderWithoutSorting() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("z", 1);
        record.put("a", 2);

        assertThat(formatter.format(List.of(record))).isEqualTo("[{z=1, a=2}]");
    }

    @Test
    void nullFieldValueRendersAsNull() {
        Map<String, Object> record = new HashMap<>();
        record.put("Email", null);
        record.put("Id", "X");

        String text = formatter.format(List.of(record));

        assertThat(text).contains("Email=null").isEqualTo(List.of(record).toString());
    }

    @Test
    void nestedMapValueRendersAsMap() {
        Map<String, Object> owner = new HashMap<>();
        owner.put("Name", "Joe");
        owner.put("type", "User");
        Map<String, Object> record = new HashMap<>();
        record.put("Id", "X");
        record.put("Owner", owner);

        String text = formatter.format(List.of(record));

        assertThat(text).contains("Owner=" + owner).isEqualTo(List.of(record).toString());
    }

    static Stream<Arguments> collectionValues() {
        return Stream.of(
                Arguments.of(List.of("a", "b"), "[{Tags=[a, b]}]"),
                Arguments.of(new LinkedHashSet<>(List.of("x", "y")), "[{Tags=[x, y]}]"));
    }

    @ParameterizedTest
    @MethodSource("collectionValues")
    void collectionValueRendersAsList(Collection<String> tags, String expected) {
        Map<String, Object> record = new HashMap<>();
        record.put("Tags", tags);

        String text = formatter.format(List.of(record));

        assertThat(text).isEqualTo(expected).isEqualTo(List.of(record).toString());
    }

    @Test
    void nullRecordRendersAsNull() {
        Map<String, Object> user = new HashMap<>();
        user.put("type", "User");
        user.put("Id", "X");
        List<Map<String, Object>> records = Arrays.asList(user, null);

        String text = formatter.format(records);

        assertThat(text).isEqualTo(records.toString()).endsWith(", null]");
    }

    @Test
    void recordHoldingItselfRendersThisMapAsTheJdkDoes() {
        Map<String, Object> record = new HashMap<>();
        record.put("Id", "X");
        record.put("Self", record);

        String text = formatter.format(List.of(record));

        assertThat(text).contains("Self=(this Map)").isEqualTo(List.of(record).toString());
    }

    @Test
    void mapKeyedByItselfRendersThisMapAsTheJdkDoes() {
        // Identity hashing keeps the self-keyed map from recursing through its own hashCode.
        Map<Object, Object> selfKeyed = new IdentityHashMap<>();
        selfKeyed.put(selfKeyed, "k");
        Map<String, Object> record = new HashMap<>();
        record.put("Self", selfKeyed);

        String text = formatter.format(List.of(record));

        assertThat(text).contains("Self={(this Map)=k}").isEqualTo(List.of(record).toString());
    }

    @Test
    void collectionHoldingItselfRendersThisCollectionAsTheJdkDoes() {
        List<Object> tags = new ArrayList<>();
        tags.add("a");
        tags.add(tags);
        Map<String, Object> record = new HashMap<>();
        record.put("Tags", tags);

        String text = formatter.format(List.of(record));

        assertThat(text).contains("Tags=[a, (this Collection)]").isEqualTo(List.of(record).toString());
    }

    /**
     * The original {@code SalesforceIdRetrievalIT.testGetData} asserted that the POST response contains each line of
     * {@code reply.txt}; the two {@code User} records those lines describe, as the Salesforce client returns them
     * (with the connector's {@code type} key), must satisfy the same assertion.
     */
    @Test
    void replyRecordsContainEveryOriginalReplyLine() throws IOException {
        String[] lines = readReplyLines();
        assertThat(lines).hasSize(REPLY_LINE_COUNT);
        Map<String, Object> first = userFromLines(lines, 0);
        Map<String, Object> second = userFromLines(lines, FIELDS_PER_RECORD);

        String text = formatter.format(List.of(first, second));

        assertThat(text).contains(lines).isEqualTo(List.of(first, second).toString());
    }

    private static Map<String, Object> user(String email, String id, String name) {
        Map<String, Object> user = new HashMap<>();
        user.put("type", "User");
        user.put("Email", email);
        user.put("Id", id);
        user.put("Name", name);
        return user;
    }

    /** Builds a {@code User} record from {@code key=value} lines, splitting each at its first {@code =}. */
    private static Map<String, Object> userFromLines(String[] lines, int from) {
        Map<String, Object> user = new HashMap<>();
        user.put("type", "User");
        for (int i = from; i < from + FIELDS_PER_RECORD; i++) {
            int separator = lines[i].indexOf('=');
            assertThat(separator).as("'=' in reply line %d", i + 1).isPositive();
            user.put(lines[i].substring(0, separator), lines[i].substring(separator + 1));
        }
        return user;
    }

    private static String[] readReplyLines() throws IOException {
        try (InputStream in = QueryResultFormatterTest.class.getResourceAsStream(REPLY_RESOURCE)) {
            assertThat(in).as(REPLY_RESOURCE + " on the test classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n");
        }
    }
}
