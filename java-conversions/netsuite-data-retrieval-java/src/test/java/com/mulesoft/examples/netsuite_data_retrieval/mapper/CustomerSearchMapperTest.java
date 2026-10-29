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
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * Unit test of {@link CustomerSearchMapper}, the owner of the {@code get:/customers} response script
 * {@code payload map $} ({@code netsuite-data-retrieval/src/main/app/netsuite-api.xml}, line 44). The REST customer,
 * its metadata catalog and the {@code customfield} lookup rows are the committed fixtures under
 * {@code src/test/resources/netsuite/}; the parity target is the committed RAML example
 * {@code api/customers-response.json}, read unchanged from the main classpath. The real {@link DwJsonWriter} and
 * {@link NetsuiteRecordShapeMapper} produce the bytes: plain JUnit 5 and AssertJ, no Spring context, no mocks.
 */
class CustomerSearchMapperTest {

    /** Decimals as {@code BigDecimal}, so {@code -32.3} and {@code 49.98} compare exactly. */
    private static final ObjectMapper JSON =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private static final TypeReference<LinkedHashMap<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private static final String EXPECTED_RESOURCE = "api/customers-response.json";

    private static final String COMPANY_NAME = "Ramsey Inc. Wholesale Distribution V2011.1";

    private DwJsonWriter writer;

    private CustomerSearchMapper mapper;

    private Map<String, Object> customer;

    private Map<String, Object> catalog;

    private byte[] expectedBytes;

    private String expectedText;

    @BeforeEach
    void setUp() throws IOException {
        NetsuiteRecordShapeMapper shape = new NetsuiteRecordShapeMapper(ZoneId.of("America/Los_Angeles"));
        writer = new DwJsonWriter();
        mapper = new CustomerSearchMapper(shape, writer);
        customer = readObject("netsuite/rest-customer.json");
        catalog = readObject("netsuite/rest-customer-catalog.json");
        expectedBytes = readResource(EXPECTED_RESOURCE);
        expectedText = new String(expectedBytes, StandardCharsets.UTF_8);
    }

    @Test
    void reproducesCommittedCustomersResponseByteForByte() throws IOException {
        assertThat(expectedText)
                .as("committed %s layout: one-element array, no trailing line feed", EXPECTED_RESOURCE)
                .startsWith("[\n  {")
                .endsWith("\n  }\n]");

        assertThat(mapper.toJson(List.of(customer), catalog, sourceIds())).isEqualTo(expectedText);

        byte[] bytes = writer.writeBytes(mapper.map(List.of(customer), catalog, sourceIds()));
        assertThat(bytes).isEqualTo(expectedBytes);
    }

    @Test
    void matchesCommittedCustomersResponseTreeAndFieldOrder() throws IOException {
        JsonNode expected = JSON.readTree(expectedText);
        JsonNode actual = JSON.readTree(mapper.toJson(List.of(customer), catalog, sourceIds()));

        assertThat(actual).isEqualTo(expected);
        assertSameFieldOrder(expected, actual, "$");
    }

    @Test
    void mapsTheSearchedCustomerToItsSuiteTalkRecord() throws IOException {
        List<Map<String, Object>> customers = mapper.map(List.of(customer), catalog, sourceIds());

        assertThat(customers).hasSize(1);
        assertThat(customers.get(0))
                .containsEntry("companyName", COMPANY_NAME)
                .containsEntry("internalId", "-5")
                .containsEntry("externalId", "entity-5");
        // The original MUnit suite's contains-assertion on the response [netsuite-api-test-suite.xml:28].
        assertThat(mapper.toJson(List.of(customer), catalog, sourceIds()))
                .contains("\"companyName\": \"" + COMPANY_NAME + "\"");
    }

    @Test
    void keepsTheSearchOrderOfTheCustomers() throws IOException {
        Map<String, Object> second = new LinkedHashMap<>(customer);
        second.put("companyName", "Second Co");
        second.put("id", "-6");
        List<Map<String, Object>> records = List.of(customer, second);

        assertThat(mapper.map(records, catalog, sourceIds()))
                .extracting(record -> record.get("companyName"), record -> record.get("internalId"))
                .containsExactly(tuple(COMPANY_NAME, "-5"), tuple("Second Co", "-6"));

        String json = mapper.toJson(records, catalog, sourceIds());
        int first = json.indexOf("\"companyName\": \"" + COMPANY_NAME + "\"");
        assertThat(first).isNotNegative().isLessThan(json.indexOf("\"companyName\": \"Second Co\""));
        // The first element of a two-customer response is the committed single-customer element unchanged.
        assertThat(json).startsWith(expectedText.substring(0, expectedText.length() - "\n]".length()) + ",\n  {\n");
    }

    @ParameterizedTest
    @NullAndEmptySource
    void writesAnEmptyArrayWhenTheSearchFindsNoCustomers(List<Map<String, Object>> records) {
        assertThat(mapper.map(records, catalog, Map.of())).isEmpty();
        assertThat(mapper.toJson(records, catalog, Map.of())).isEqualTo("[]");
    }

    @Test
    void propagatesTheShapeFailureOfAnUnresolvedSelectSource() {
        assertThatThrownBy(() -> mapper.toJson(List.of(customer), catalog, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("custentity8");
    }

    /**
     * The select sources of the committed {@code customfield} lookup, keyed as
     * {@code NetsuiteRestClient.customFieldSourceIds} reports them: script IDs trimmed and lower-cased, source IDs
     * as text.
     */
    private static Map<String, String> sourceIds() throws IOException {
        Map<String, Object> response = readObject("netsuite/rest-suiteql-customfield.json");
        Map<String, String> sourceIds = new LinkedHashMap<>();
        for (Object item : (List<?>) response.get("items")) {
            Map<?, ?> row = (Map<?, ?>) item;
            sourceIds.put(String.valueOf(row.get("scriptid")).trim().toLowerCase(Locale.ROOT),
                    String.valueOf(row.get("fieldvaluetyperecord")));
        }
        assertThat(sourceIds)
                .as("select sources of the committed customer [customers-response.json:51-75]")
                .isEqualTo(Map.of(
                        "custentity8", "16",
                        "custentity7", "17",
                        "custentity_fmt_cur_sales_order", "-30",
                        "custentity6", "15"));
        return sourceIds;
    }

    /**
     * Asserts that every object of {@code actual} lists its fields in the order of the matching object of
     * {@code expected}, which {@link JsonNode#equals} ignores; arrays are compared element-wise.
     */
    private static void assertSameFieldOrder(JsonNode expected, JsonNode actual, String path) {
        if (expected.isObject()) {
            assertThat(actual.isObject()).as("%s is an object", path).isTrue();
            List<String> expectedNames = fieldNames(expected);
            assertThat(fieldNames(actual)).as("field order of %s", path).containsExactlyElementsOf(expectedNames);
            for (String name : expectedNames) {
                assertSameFieldOrder(expected.get(name), actual.get(name), path + "." + name);
            }
        } else if (expected.isArray()) {
            assertThat(actual.isArray()).as("%s is an array", path).isTrue();
            assertThat(actual.size()).as("size of %s", path).isEqualTo(expected.size());
            for (int i = 0; i < expected.size(); i++) {
                assertSameFieldOrder(expected.get(i), actual.get(i), path + "[" + i + "]");
            }
        }
    }

    private static List<String> fieldNames(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static LinkedHashMap<String, Object> readObject(String resource) throws IOException {
        return JSON.readValue(readResource(resource), JSON_OBJECT);
    }

    private static byte[] readResource(String resource) throws IOException {
        try (InputStream in = CustomerSearchMapperTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("classpath resource %s", resource).isNotNull();
            return in.readAllBytes();
        }
    }
}
