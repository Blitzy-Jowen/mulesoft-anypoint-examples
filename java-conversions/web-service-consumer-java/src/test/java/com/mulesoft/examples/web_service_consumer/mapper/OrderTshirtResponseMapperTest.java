/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.web_service_consumer.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Contract of {@link OrderTshirtResponseMapper}, the owner of the {@code orderTshirt} script
 * {@code %output application/json --- payload}: the {@code OrderTshirt} response document is written whole in
 * DataWeave 1.0 JSON layout, keyed by local names with the namespace dropped, leaves as strings, an element without
 * text as {@code null}, and no trailing line feed; a {@code null} document yields {@code null}.
 *
 * <p>The original example's Studio sample {@code sample_data/OrderTshirtResponse.xml} is not carried, so it is
 * inlined byte for byte. Inputs are parsed with the project's own {@link DwXmlToJson}, and the mapper is built over
 * real collaborators; no Spring context is started.
 */
class OrderTshirtResponseMapperTest {

    /** {@code sample_data/OrderTshirtResponse.xml}: declaration, two-space pretty-printing, no trailing line feed. */
    private static final String UNPREFIXED = "<?xml version='1.0' encoding='UTF-8'?>\n"
            + "<OrderTshirtResponse>\n"
            + "  <orderId>1</orderId>\n"
            + "</OrderTshirtResponse>";

    /** The response body as the TshirtService returns it, root in the service namespace. */
    private static final String PREFIXED = "<ns2:OrderTshirtResponse xmlns:ns2=\"http://mulesoft.org/tshirt-service\">"
            + "<orderId>1</orderId>"
            + "</ns2:OrderTshirtResponse>";

    /** The script's output for either input: 53 characters, nothing after the closing brace. */
    private static final String EXPECTED = "{\n"
            + "  \"OrderTshirtResponse\": {\n"
            + "    \"orderId\": \"1\"\n"
            + "  }\n"
            + "}";

    private final DwXmlToJson reader = new DwXmlToJson();

    private final OrderTshirtResponseMapper mapper = new OrderTshirtResponseMapper(reader, new DwJsonWriter());

    static Stream<Arguments> responses() {
        return Stream.of(
                Arguments.of("unprefixed Studio sample", UNPREFIXED),
                Arguments.of("namespace-prefixed service response", PREFIXED));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("responses")
    void responseIsWrittenAsDataWeaveJsonWithLocalNamesAndStringLeaves(String description, String xml) {
        String json = mapper.toJson(reader.parse(xml));

        assertThat(json).isEqualTo(EXPECTED).hasSize(53).doesNotEndWith("\n");
        // The original WebServiceConsumerIT asserted only that the /orders response contains orderId.
        assertThat(json).contains("orderId");
        assertThat(json).contains("\"orderId\": \"1\"").doesNotContain("\"orderId\": 1");
    }

    @Test
    void nullDocumentYieldsNull() {
        assertThat(mapper.toJson(null)).isNull();
    }

    @Test
    void elementWithoutTextIsWrittenAsNull() {
        String json = mapper.toJson(reader.parse("<OrderTshirtResponse><orderId/></OrderTshirtResponse>"));

        assertThat(json).isEqualTo("{\n"
                + "  \"OrderTshirtResponse\": {\n"
                + "    \"orderId\": null\n"
                + "  }\n"
                + "}");
    }
}
