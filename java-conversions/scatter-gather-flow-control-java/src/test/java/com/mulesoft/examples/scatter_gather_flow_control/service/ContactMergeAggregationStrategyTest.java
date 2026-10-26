/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.scatter_gather_flow_control.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.mulesoft.examples.scatter_gather_flow_control.model.BranchResult;

/**
 * Unit test of {@link ContactMergeAggregationStrategy}, the port of the original
 * {@code org.mule.examples.transformers.ContactMergeAggregationStrategy} custom aggregation strategy of
 * {@code scatter-gather-flow-control/src/main/app/scatter-gather.xml}.
 *
 * <p>The route payloads are the original's {@code sourceA} and {@code sourceB} expression components, built as they
 * build them: an {@link ArrayList} of {@link HashMap}s with {@code Id}, {@code Name} and {@code Email}.
 *
 * <p>Cases that differ only in their route results share one parameterized test each: one for the two-success rule
 * that merges the first successful route into the second, one for every count other than two that fails.
 */
class ContactMergeAggregationStrategyTest {

    private static final String EXPECTED_MESSAGE =
            "Data from at least one source was not able to be obtained correctly.";

    private final ContactMergeAggregationStrategy strategy = new ContactMergeAggregationStrategy();

    static Stream<Arguments> twoSuccessfulRoutes() {
        List<Map<String, String>> aMergedWithB = List.of(
                contact("vlado", "vlado@email.com", "1", "2"),
                contact("michal", "michal@email.com", "2", ""),
                contact("peter", "peter@email.com", "", "1"));
        return Stream.of(
                Arguments.of("routes A and B succeed -> A merged with B in route order",
                        List.of(BranchResult.success(sourceA()), BranchResult.success(sourceB())),
                        aMergedWithB),
                Arguments.of("a failed result is skipped -> the successes keep their order",
                        List.of(BranchResult.failure(new RuntimeException("x")),
                                BranchResult.success(sourceA()), BranchResult.success(sourceB())),
                        aMergedWithB),
                Arguments.of("B before A -> the first success is the merge's list A",
                        List.of(BranchResult.success(sourceB()), BranchResult.success(sourceA())),
                        List.of(
                                contact("peter", "peter@email.com", "1", ""),
                                contact("vlado", "vlado@email.com", "2", "1"),
                                contact("michal", "michal@email.com", "", "2"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("twoSuccessfulRoutes")
    void twoSuccessesMergeFirstIntoSecondInResultOrder(String scenario, List<BranchResult> results,
            List<Map<String, String>> expected) {
        List<Map<String, String>> merged = strategy.aggregate(results);

        assertThat(merged).isEqualTo(expected);
        // Merged contacts carry no Id, which is why the aggregation report's Id column is blank.
        assertThat(merged).allSatisfy(contact -> assertThat(contact)
                .isInstanceOf(HashMap.class)
                .containsOnlyKeys("Name", "Email", "IDInA", "IDInB")
                .doesNotContainKey("Id"));
    }

    static Stream<Arguments> successCountOtherThanTwo() {
        return Stream.of(
                Arguments.of("route A failed, route B succeeded",
                        List.of(BranchResult.failure(new RuntimeException("route A down")),
                                BranchResult.success(sourceB()))),
                Arguments.of("both routes failed",
                        List.of(BranchResult.failure(new RuntimeException("route A down")),
                                BranchResult.failure(new IllegalArgumentException("route B down")))),
                Arguments.of("no route results", List.of()),
                Arguments.of("three successful results",
                        List.of(BranchResult.success(sourceA()), BranchResult.success(sourceB()),
                                BranchResult.success(sourceA()))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("successCountOtherThanTwo")
    void successCountOtherThanTwoThrowsIllegalState(String scenario, List<BranchResult> results) {
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> strategy.aggregate(results));

        assertThat(thrown).hasMessage(EXPECTED_MESSAGE).hasNoCause();
    }

    @Test
    @DisplayName("aggregation builds new contacts and leaves the route payloads unchanged")
    void aggregateLeavesRoutePayloadsUnchanged() {
        List<Map<String, String>> routeA = sourceA();
        List<Map<String, String>> routeB = sourceB();

        strategy.aggregate(List.of(BranchResult.success(routeA), BranchResult.success(routeB)));

        assertThat(routeA).isEqualTo(sourceA());
        assertThat(routeB).isEqualTo(sourceB());
    }

    /** Route {@code sourceA} of the original flow. */
    private static List<Map<String, String>> sourceA() {
        List<Map<String, String>> source = new ArrayList<>();
        source.add(routeContact("1", "vlado", "vlado@email.com"));
        source.add(routeContact("2", "michal", "michal@email.com"));
        return source;
    }

    /** Route {@code sourceB} of the original flow. */
    private static List<Map<String, String>> sourceB() {
        List<Map<String, String>> source = new ArrayList<>();
        source.add(routeContact("1", "peter", "peter@email.com"));
        source.add(routeContact("2", "vlado", "vlado@email.com"));
        return source;
    }

    private static Map<String, String> routeContact(String id, String name, String email) {
        Map<String, String> contact = new HashMap<>();
        contact.put("Id", id);
        contact.put("Name", name);
        contact.put("Email", email);
        return contact;
    }

    /** An expected merged contact: exactly the four keys {@link ContactMerge} writes. */
    private static Map<String, String> contact(String name, String email, String idInA, String idInB) {
        Map<String, String> contact = new HashMap<>();
        contact.put("Name", name);
        contact.put("Email", email);
        contact.put("IDInA", idInA);
        contact.put("IDInB", idInB);
        return contact;
    }
}
