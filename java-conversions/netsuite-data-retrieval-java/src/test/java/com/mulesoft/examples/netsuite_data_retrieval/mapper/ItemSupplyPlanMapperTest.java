/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.netsuite_data_retrieval.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

/**
 * Unit test of {@link ItemSupplyPlanMapper}, the owner of the {@code payload map $} script of flow
 * {@code get:/items:netsuite-api-config} ({@code netsuite-data-retrieval/src/main/app/netsuite-api.xml} l.70).
 * The REST item supply plans {@code netsuite/rest-itemsupplyplan-9.json} and {@code -18.json} are the plans
 * {@code NetsuiteSearchService} keeps; the committed RAML example {@code api/items-response.json} is the parity
 * target, compared byte for byte and never edited. The real {@link NetsuiteRecordShapeMapper} and
 * {@link DwJsonWriter} produce the output: plain JUnit 5 and AssertJ, no Spring context and no mocks.
 */
class ItemSupplyPlanMapperTest {

    private static final ObjectMapper JSON =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private static final TypeReference<LinkedHashMap<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    /** Item supply plans carry no custom select field, so no select source ID is ever needed. */
    private static final Map<String, String> NO_SOURCE_IDS = Map.of();

    private DwJsonWriter writer;

    private ItemSupplyPlanMapper mapper;

    private Map<String, Object> plan9;

    private Map<String, Object> plan18;

    private Map<String, Object> catalog;

    private byte[] expectedBytes;

    private String expected;

    @BeforeEach
    void setUp() {
        NetsuiteRecordShapeMapper shape = new NetsuiteRecordShapeMapper(ZoneId.of("America/Los_Angeles"));
        writer = new DwJsonWriter();
        mapper = new ItemSupplyPlanMapper(shape, writer);
        plan9 = fixture("netsuite/rest-itemsupplyplan-9.json");
        plan18 = fixture("netsuite/rest-itemsupplyplan-18.json");
        catalog = fixture("netsuite/rest-itemsupplyplan-catalog.json");
        expectedBytes = resource("api/items-response.json");
        expected = new String(expectedBytes, StandardCharsets.UTF_8);
    }

    @Test
    void writesTheCommittedItemsResponseByteForByte() {
        assertThat(mapper.toJson(List.of(plan9, plan18), catalog, NO_SOURCE_IDS)).isEqualTo(expected);
        assertThat(writer.writeBytes(mapper.map(List.of(plan9, plan18), catalog, NO_SOURCE_IDS)))
                .isEqualTo(expectedBytes);
    }

    @Test
    void matchesTheCommittedTreeInTheCommittedFieldOrder() throws IOException {
        JsonNode expectedTree = JSON.readTree(expectedBytes);
        JsonNode actualTree = JSON.readTree(mapper.toJson(List.of(plan9, plan18), catalog, NO_SOURCE_IDS));

        assertThat(actualTree).isEqualTo(expectedTree);
        assertSameFieldOrder(expectedTree, actualTree, "$");
    }

    @Test
    void writesUnitsOnlyWhenSetBetweenLocationAndSubsidiaryAndDropsTheOrderLines() {
        assertThat(plan9).containsKey("order").doesNotContainKey("units");
        assertThat(plan18).containsKeys("order", "units");

        List<Map<String, Object>> plans = mapper.map(List.of(plan9, plan18), catalog, NO_SOURCE_IDS);

        assertThat(plans).hasSize(2);
        assertThat(plans.get(0).keySet()).containsExactly("internalId", "item", "location", "subsidiary");
        assertThat(plans.get(1).keySet()).containsExactly("internalId", "item", "location", "units", "subsidiary");
        assertThat(plans).allSatisfy(plan -> assertThat(plan).doesNotContainKey("order"));

        Map<String, Object> units = asObject(plans.get(1).get("units"));
        assertThat(units.keySet()).containsExactly("externalId", "type", "internalId", "name");
        assertThat(units.values()).containsExactly(null, null, "15", "Square Feet");
    }

    @Test
    void keepsTheInputOrderOfThePlans() {
        List<Map<String, Object>> plans = mapper.map(List.of(plan18, plan9), catalog, NO_SOURCE_IDS);

        assertThat(plans).extracting(plan -> plan.get("internalId")).containsExactly("18", "9");
    }

    @ParameterizedTest
    @NullAndEmptySource
    void writesAnEmptyArrayWhenNoPlanWasKept(List<Map<String, Object>> records) {
        assertThat(mapper.map(records, catalog, NO_SOURCE_IDS)).isEmpty();
        assertThat(mapper.toJson(records, catalog, NO_SOURCE_IDS)).isEqualTo("[]");
    }

    /**
     * Asserts that every object under {@code actual} lists its field names in the order of the matching object
     * under {@code expected}, recursing through object fields and pairwise through array elements; JSON tree
     * equality alone ignores field order.
     */
    private static void assertSameFieldOrder(JsonNode expected, JsonNode actual, String path) {
        assertThat(actual).as("node at %s", path).isNotNull();
        assertThat(actual.getNodeType()).as("node type at %s", path).isEqualTo(expected.getNodeType());
        if (expected.isObject()) {
            List<String> expectedNames = new ArrayList<>();
            expected.fieldNames().forEachRemaining(expectedNames::add);
            List<String> actualNames = new ArrayList<>();
            actual.fieldNames().forEachRemaining(actualNames::add);
            assertThat(actualNames).as("field order at %s", path).containsExactlyElementsOf(expectedNames);
            for (String name : expectedNames) {
                assertSameFieldOrder(expected.get(name), actual.get(name), path + "." + name);
            }
        } else if (expected.isArray()) {
            assertThat(actual.size()).as("element count at %s", path).isEqualTo(expected.size());
            for (int i = 0; i < expected.size(); i++) {
                assertSameFieldOrder(expected.get(i), actual.get(i), path + "[" + i + "]");
            }
        }
    }

    /** Reads a classpath JSON object afresh, so no test sees another test's maps. */
    private static Map<String, Object> fixture(String path) {
        try {
            return JSON.readValue(resource(path), JSON_OBJECT);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot parse " + path, e);
        }
    }

    private static byte[] resource(String path) {
        try (InputStream in = ItemSupplyPlanMapperTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("classpath resource %s", path).isNotNull();
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }
}
