/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.web_service_consumer.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Contract of {@link ListInventoryResponseMapper}, the owner of the {@code listInventory} script
 * {@code payload.ns0#ListInventoryResponse} ({@code %namespace ns0 http://mulesoft.org/tshirt-service},
 * {@code %output application/json}): the {@code ListInventoryResponse} root in the T-shirt namespace is selected
 * whatever its prefix, and its content, not the wrapper, is written as DataWeave 1.0 JSON with one repeated
 * {@code "inventory"} key per item in document order and string leaves; a document whose root misses the selector
 * and a {@code null} document yield the JSON literal {@code null}, the DataWeave JSON writer's rendering of a
 * selector that yields null, while an empty root yields the JSON string {@code ""}, as DataWeave 1.0's XML reader
 * read an element without text.
 *
 * <p>The sample is the original example's {@code sample_data/ListInventoryResponse.xml}, inlined because that file
 * is a Studio preview input and is not carried. The mapper is built directly over a real {@link DwXmlToJson} and
 * {@link DwJsonWriter}; no Spring context is started.
 */
class ListInventoryResponseMapperTest {

    private static final String TSHIRT_NAMESPACE = "http://mulesoft.org/tshirt-service";

    private static final String DECLARATION_LINE = "<?xml version='1.0' encoding='UTF-8'?>\n";

    /** The five items of the sample, unqualified and 2-space indented, between the root's start and end tags. */
    private static final String ITEMS = "  <inventory>\n"
            + "    <productCode>4102</productCode>\n"
            + "    <size>L</size>\n"
            + "    <description>Prueba</description>\n"
            + "    <count>2</count>\n"
            + "  </inventory>\n"
            + "  <inventory>\n"
            + "    <productCode>1412</productCode>\n"
            + "    <size>L</size>\n"
            + "    <description>Foo</description>\n"
            + "    <count>9</count>\n"
            + "  </inventory>\n"
            + "  <inventory>\n"
            + "    <productCode>5656</productCode>\n"
            + "    <size>S</size>\n"
            + "    <description>Bar</description>\n"
            + "    <count>2</count>\n"
            + "  </inventory>\n"
            + "  <inventory>\n"
            + "    <productCode>5657</productCode>\n"
            + "    <size>M</size>\n"
            + "    <description>Prueba2</description>\n"
            + "    <count>3</count>\n"
            + "  </inventory>\n"
            + "  <inventory>\n"
            + "    <productCode>1411</productCode>\n"
            + "    <size>M</size>\n"
            + "    <description>Awesome Tshirt</description>\n"
            + "    <count>5</count>\n"
            + "  </inventory>\n";

    /** {@code sample_data/ListInventoryResponse.xml}: an {@code ns2}-qualified root holding the five items. */
    private static final String INVENTORY = DECLARATION_LINE
            + "<ns2:ListInventoryResponse xmlns:ns2=\"http://mulesoft.org/tshirt-service\">\n"
            + ITEMS
            + "</ns2:ListInventoryResponse>";

    /** The sample as the script wrote it: 565 characters, nothing after the closing brace. */
    private static final String EXPECTED = "{\n"
            + "  \"inventory\": {\n    \"productCode\": \"4102\",\n    \"size\": \"L\",\n"
            + "    \"description\": \"Prueba\",\n    \"count\": \"2\"\n  },\n"
            + "  \"inventory\": {\n    \"productCode\": \"1412\",\n    \"size\": \"L\",\n"
            + "    \"description\": \"Foo\",\n    \"count\": \"9\"\n  },\n"
            + "  \"inventory\": {\n    \"productCode\": \"5656\",\n    \"size\": \"S\",\n"
            + "    \"description\": \"Bar\",\n    \"count\": \"2\"\n  },\n"
            + "  \"inventory\": {\n    \"productCode\": \"5657\",\n    \"size\": \"M\",\n"
            + "    \"description\": \"Prueba2\",\n    \"count\": \"3\"\n  },\n"
            + "  \"inventory\": {\n    \"productCode\": \"1411\",\n    \"size\": \"M\",\n"
            + "    \"description\": \"Awesome Tshirt\",\n    \"count\": \"5\"\n  }\n"
            + "}";

    private static final Pattern PRODUCT_CODE = Pattern.compile("\"productCode\": \"([^\"]*)\"");

    private final DwXmlToJson reader = new DwXmlToJson();

    private final ListInventoryResponseMapper mapper = new ListInventoryResponseMapper(reader, new DwJsonWriter());

    @Test
    void namespaceAndRootAreTheScriptsNs0AndListInventoryResponse() {
        assertThat(ListInventoryResponseMapper.NAMESPACE).isEqualTo(TSHIRT_NAMESPACE);
        assertThat(ListInventoryResponseMapper.ROOT).isEqualTo("ListInventoryResponse");
    }

    @Test
    void sampleIsWrittenAsOneRepeatedInventoryKeyPerItemInDocumentOrder() {
        String json = mapper.toJson(reader.parse(INVENTORY));

        assertThat(EXPECTED).hasSize(565);
        assertThat(json).isEqualTo(EXPECTED);
        assertThat(occurrences(json, "\"inventory\": {")).isEqualTo(5);
        assertThat(productCodes(json)).containsExactly("4102", "1412", "5656", "5657", "1411");
    }

    @Test
    void sampleContainsTheValuesTheOriginalInventoryTestAsserted() {
        assertThat(mapper.toJson(reader.parse(INVENTORY)))
                .contains("\"productCode\": \"1412\"")
                .contains("\"description\": \"Prueba2\"")
                .contains("\"count\": \"5\"");
    }

    @Test
    void wrapperElementIsNotWrittenBecauseTheSelectorTakesTheRootsContent() {
        assertThat(mapper.toJson(reader.parse(INVENTORY)))
                .doesNotContain("ListInventoryResponse")
                .startsWith("{\n  \"inventory\": {\n");
    }

    @Test
    void leavesStayStringsAlthoughTheWsdlTypesCountAsInteger() {
        assertThat(mapper.toJson(reader.parse(INVENTORY)))
                .contains("\"count\": \"2\"")
                .doesNotContain("\"count\": 2")
                .doesNotContainPattern(": [0-9]");
    }

    static Stream<Arguments> sameResponseUnderAnotherPrefixOrLayout() {
        return Stream.of(
                Arguments.of("ns0 prefix", DECLARATION_LINE
                        + "<ns0:ListInventoryResponse xmlns:ns0=\"http://mulesoft.org/tshirt-service\">\n"
                        + ITEMS
                        + "</ns0:ListInventoryResponse>"),
                Arguments.of("default namespace", DECLARATION_LINE
                        + "<ListInventoryResponse xmlns=\"http://mulesoft.org/tshirt-service\">\n"
                        + ITEMS
                        + "</ListInventoryResponse>"),
                Arguments.of("no declaration and no indentation",
                        "<ns2:ListInventoryResponse xmlns:ns2=\"http://mulesoft.org/tshirt-service\">"
                                + ITEMS.replaceAll(">\\s+<", "><").strip()
                                + "</ns2:ListInventoryResponse>"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sameResponseUnderAnotherPrefixOrLayout")
    void selectorMatchesTheNamespaceNotThePrefixOrLayout(String variant, String xml) {
        assertThat(mapper.toJson(reader.parse(xml))).isEqualTo(EXPECTED);
    }

    static Stream<Arguments> responsesTheSelectorMisses() {
        return Stream.of(
                Arguments.of("null document", null),
                Arguments.of("another root", "<OrderTshirtResponse><orderId>1</orderId></OrderTshirtResponse>"),
                Arguments.of("ListInventoryResponse in another namespace",
                        "<x:ListInventoryResponse xmlns:x=\"urn:other\"><inventory><productCode>1</productCode>"
                                + "</inventory></x:ListInventoryResponse>"),
                Arguments.of("ListInventoryResponse in no namespace",
                        "<ListInventoryResponse><inventory><productCode>1</productCode></inventory>"
                                + "</ListInventoryResponse>"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("responsesTheSelectorMisses")
    void responseTheSelectorMissesIsWrittenAsJsonNull(String variant, String xml) {
        assertThat(mapper.toJson(xml == null ? null : reader.parse(xml))).isEqualTo("null");
    }

    @Test
    void emptyResponseIsWrittenAsJsonEmptyString() {
        // The selector matches; DataWeave 1.0's XML reader read the element without text as "", not null.
        String json = mapper.toJson(
                reader.parse("<ns2:ListInventoryResponse xmlns:ns2=\"http://mulesoft.org/tshirt-service\"/>"));

        assertThat(json).isEqualTo("\"\"");
    }

    @Test
    void singleItemIsOneInventoryKeyHoldingItsFourLeaves() {
        String xml = DECLARATION_LINE
                + "<ns2:ListInventoryResponse xmlns:ns2=\"http://mulesoft.org/tshirt-service\">\n"
                + "  <inventory>\n"
                + "    <productCode>1412</productCode>\n"
                + "    <size>L</size>\n"
                + "    <description>Foo</description>\n"
                + "    <count>9</count>\n"
                + "  </inventory>\n"
                + "</ns2:ListInventoryResponse>";

        assertThat(mapper.toJson(reader.parse(xml))).isEqualTo("{\n"
                + "  \"inventory\": {\n"
                + "    \"productCode\": \"1412\",\n"
                + "    \"size\": \"L\",\n"
                + "    \"description\": \"Foo\",\n"
                + "    \"count\": \"9\"\n"
                + "  }\n"
                + "}");
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    private static List<String> productCodes(String json) {
        List<String> codes = new ArrayList<>();
        Matcher matcher = PRODUCT_CODE.matcher(json);
        while (matcher.find()) {
            codes.add(matcher.group(1));
        }
        return codes;
    }
}
