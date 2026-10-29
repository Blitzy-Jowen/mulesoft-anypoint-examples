/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.netsuite_data_retrieval.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mulesoft.examples.netsuite_data_retrieval.mapper.NetsuiteRecordShapeMapper.RecordKind;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test of {@link OpportunitySearchMapper}, the owner of the {@code payload map $} script of the flow
 * {@code get:/oportunities} ({@code netsuite-data-retrieval/src/main/app/netsuite-api.xml} l.99) behind
 * {@code GET /api/opportunities}. The REST opportunity {@code netsuite/rest-opportunity.json} and its metadata
 * catalog {@code netsuite/rest-opportunity-catalog.json} go through the real {@link NetsuiteRecordShapeMapper} and
 * the real SC-4 {@link DwJsonWriter}; the parity target is the committed {@code api/opportunities-response.json}.
 *
 * <p>That committed file is not strict JSON: both {@code addrText} values hold raw line feeds, each address with
 * its own indentation, while the writer escapes line feeds. Byte equality is therefore impossible by design, so the
 * output is compared as a JSON tree, key order included, after both {@code addrText} values have had their
 * whitespace normalized. The committed file is only read, never rewritten. Plain JUnit 5 and AssertJ: no Spring
 * context, no mocks and no network.
 */
class OpportunitySearchMapperTest {

    /** Strict JSON: it rejects raw control characters inside strings, so parsing the output proves the escaping. */
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    /** Reads the committed example, whose {@code addrText} values carry raw line feeds. */
    private static final ObjectMapper LENIENT = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private static final String COMMITTED_EXAMPLE = "api/opportunities-response.json";

    private static final List<String> ADDRESS_FIELDS = List.of("shippingAddress", "billingAddress");

    /** The committed key order of a SuiteTalk {@code Address} {@code [opportunities-response.json:billingAddress]}. */
    private static final List<String> ADDRESS_KEYS = List.of("zip", "country", "addr2", "addr1", "city", "addr3",
            "addrText", "addrPhone", "internalId", "addressee", "attention", "state", "override", "nullFieldList",
            "customFieldList");

    private NetsuiteRecordShapeMapper shape;

    private DwJsonWriter writer;

    private OpportunitySearchMapper mapper;

    private Map<String, Object> opportunity;

    private Map<String, Object> catalog;

    @BeforeEach
    void setUp() throws IOException {
        shape = new NetsuiteRecordShapeMapper(ZoneId.of("America/Los_Angeles"));
        writer = new DwJsonWriter();
        mapper = new OpportunitySearchMapper(shape, writer);
        opportunity = fixture("netsuite/rest-opportunity.json");
        catalog = fixture("netsuite/rest-opportunity-catalog.json");
    }

    /**
     * The written array is the committed example as a JSON tree in the committed key order at every level, once
     * both sides' {@code addrText} values are whitespace-normalized; the output itself parses as strict JSON.
     */
    @Test
    void writesCommittedExampleTreeInCommittedKeyOrder() throws IOException {
        String json = mapper.toJson(List.of(opportunity), catalog, Map.of());

        JsonNode actual = JSON.readTree(json);
        JsonNode expected = LENIENT.readTree(resource(COMMITTED_EXAMPLE));
        assertThat(json).contains("\"addrText\": \"Williams Electronics and Communications\\n<br>123 Main St.");
        assertThat(actual.isArray()).as("output is a JSON array").isTrue();
        assertThat(expected.isArray()).as("committed example is a JSON array").isTrue();
        normalizeAddrText((ArrayNode) expected);
        normalizeAddrText((ArrayNode) actual);

        assertThat(actual).isEqualTo(expected);
        assertSameFieldOrder(expected, actual, "$");
    }

    /**
     * Each element is the shape mapper's opportunity, kept as returned: the status as text, the identifiers, the
     * integral and decimal numbers and the native booleans.
     */
    @Test
    void keepsStatusTextIdentifiersNumbersAndBooleans() {
        List<Map<String, Object>> mapped = mapper.map(List.of(opportunity), catalog, Map.of());

        assertThat(mapped).hasSize(1);
        Map<String, Object> record = mapped.get(0);
        assertThat(record).isEqualTo(shape.toSuiteTalk(RecordKind.OPPORTUNITY, opportunity, catalog, Map.of()));
        assertThat(record)
                .containsEntry("status", "Issued Quote")
                .containsEntry("tranId", "OPP00000044")
                .containsEntry("title", "Short wave")
                .containsEntry("internalId", "1271")
                .containsEntry("estGrossProfitPercent", BigInteger.valueOf(100))
                .containsEntry("projectedTotal", new BigDecimal("3875.85"))
                .containsEntry("shipIsResidential", Boolean.FALSE)
                .containsEntry("isBudgetApproved", Boolean.FALSE);
        assertThat(mapper.toJson(List.of(opportunity), catalog, Map.of()))
                .contains("\"estGrossProfitPercent\": 100,", "\"projectedTotal\": 3875.85,",
                        "\"status\": \"Issued Quote\"");
    }

    /**
     * Both addresses are the fixed 15-key SuiteTalk {@code Address} in the committed order, the country code
     * translated to its constant and the unset fields kept as {@code null}.
     */
    @ParameterizedTest
    @ValueSource(strings = {"shippingAddress", "billingAddress"})
    void writesAddressAsFifteenKeyObjectWithNullsKept(String field) throws IOException {
        Map<String, Object> address = cast(mapper.map(List.of(opportunity), catalog, Map.of()).get(0).get(field));

        assertThat(fieldNames(LENIENT.readTree(resource(COMMITTED_EXAMPLE)).get(0).get(field)))
                .containsExactlyElementsOf(ADDRESS_KEYS);
        assertThat(address.keySet()).containsExactlyElementsOf(ADDRESS_KEYS);
        assertThat(address)
                .containsEntry("country", "UNITED_STATES")
                .containsEntry("addr3", null)
                .containsEntry("addrPhone", null)
                .containsEntry("attention", null)
                .containsEntry("nullFieldList", null)
                .containsEntry("customFieldList", null);
    }

    /**
     * The shipping address carries its REST {@code id} and {@code override}; the billing address has neither, so
     * both stay {@code null} keys, and every null is written as {@code null}.
     */
    @Test
    void keepsShippingIdentityAndBillingNulls() {
        Map<String, Object> record = mapper.map(List.of(opportunity), catalog, Map.of()).get(0);
        Map<String, Object> shipping = cast(record.get("shippingAddress"));
        Map<String, Object> billing = cast(record.get("billingAddress"));

        assertThat(shipping)
                .containsEntry("internalId", "2475")
                .containsEntry("override", Boolean.FALSE);
        assertThat(billing)
                .containsEntry("internalId", null)
                .containsEntry("override", null);
        assertThat(mapper.toJson(List.of(opportunity), catalog, Map.of()))
                .contains("\"addr3\": null", "\"internalId\": null", "\"override\": null", "\"override\": false");
    }

    /** No opportunity, {@code null} or empty, maps to an empty list and is written as {@code []}. */
    @ParameterizedTest
    @NullAndEmptySource
    void writesEmptyArrayWhenThereAreNoRecords(List<Map<String, Object>> records) {
        assertThat(mapper.toJson(records, catalog, Map.of())).isEqualTo("[]");
        assertThat(mapper.map(records, catalog, Map.of())).isEmpty();
    }

    /** Both collaborators are required. */
    @Test
    void rejectsMissingCollaborators() {
        assertThatNullPointerException()
                .isThrownBy(() -> new OpportunitySearchMapper(null, writer))
                .withMessage("shapeMapper");
        assertThatNullPointerException()
                .isThrownBy(() -> new OpportunitySearchMapper(shape, null))
                .withMessage("jsonWriter");
    }

    /**
     * Removes whitespace differences only: whitespace around each {@code <br>} is dropped and any other run of
     * whitespace becomes one space.
     */
    static String normalize(String s) {
        return s.replaceAll("\\s*<br>\\s*", "<br>").replaceAll("\\s+", " ").trim();
    }

    /** Normalizes {@code addrText} in place; {@link ObjectNode#put} keeps the field at its position. */
    private static void normalizeAddrText(ArrayNode records) {
        for (JsonNode record : records) {
            for (String field : ADDRESS_FIELDS) {
                if (record.get(field) instanceof ObjectNode address && address.path("addrText").isTextual()) {
                    address.put("addrText", normalize(address.get("addrText").textValue()));
                }
            }
        }
    }

    /**
     * Asserts that every object of {@code actual} lists its keys in the order of the matching object of
     * {@code expected}, which {@link JsonNode#equals} does not check.
     */
    private static void assertSameFieldOrder(JsonNode expected, JsonNode actual, String path) {
        if (expected.isObject()) {
            assertThat(actual.isObject()).as("object at %s", path).isTrue();
            assertThat(fieldNames(actual)).as("key order at %s", path)
                    .containsExactlyElementsOf(fieldNames(expected));
            expected.fields().forEachRemaining(entry ->
                    assertSameFieldOrder(entry.getValue(), actual.get(entry.getKey()), path + "." + entry.getKey()));
        } else if (expected.isArray()) {
            assertThat(actual.isArray()).as("array at %s", path).isTrue();
            assertThat(actual.size()).as("array size at %s", path).isEqualTo(expected.size());
            for (int i = 0; i < expected.size(); i++) {
                assertSameFieldOrder(expected.get(i), actual.get(i), path + "[" + i + "]");
            }
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Map<String, Object> fixture(String path) throws IOException {
        return JSON.readValue(resource(path), new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = OpportunitySearchMapperTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Classpath resource " + path + " is missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }
}
