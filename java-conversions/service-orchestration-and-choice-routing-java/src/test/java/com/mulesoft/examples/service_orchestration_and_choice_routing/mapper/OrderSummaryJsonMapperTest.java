/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The "XML to JSON" script of the Ajax {@code orderRequest} flow ({@code mule-config.xml}, header line 27), run
 * through {@link OrderSummaryJsonMapper} over the real SC-4 reader and writer.
 *
 * <p>The input is the {@code summary} element of the original test's {@code reply.xml}, the 1080 {@code /orders}
 * response that {@code SoapEnvelopeToBodyChild.xslt} reduces to that element. Expected outputs are DataWeave 1.0
 * JSON: two-space indentation, {@code "key": value}, LF line ends, no trailing line feed, and every XML leaf a
 * JSON string. They are compared as exact strings.
 */
class OrderSummaryJsonMapperTest {

    private static final String SOAPENV_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    private static final String ORD_NS = "http://orders.se.mulesoft.com/";

    private static final String REPLY = resource("/reply.xml");

    /** Order 12 for John Doe with one accepted Samsung {@code s-1} ({@code AX02}) item of quantity 1. */
    private static final String SUMMARY = element(REPLY, "summary");

    private static final String SUMMARY_CHILDREN =
            SUMMARY.substring("<summary>".length(), SUMMARY.length() - "</summary>".length());

    private static final String SAMSUNG_ITEM = element(SUMMARY, "item");

    private static final String PHILIPS_ITEM = "<item><manufacturer>Philips</manufacturer><name>p-1</name>"
            + "<productId>PH01</productId><purchaseReceipt><id>2</id><status>REJECTED</status>"
            + "<totalPrice>0.0</totalPrice></purchaseReceipt><quantity>2</quantity></item>";

    /** {@code sample_data/summary_1.xml}: the summary as DataWeave's XML writer laid it out, tabs and LF. */
    private static final String PRETTY_SUMMARY = String.join("\n",
            "<?xml version='1.0' encoding='UTF-8'?>",
            "<summary>",
            "\t<orderId>12</orderId>",
            "\t<customer>",
            "\t\t<address>Main Street 123</address>",
            "\t\t<firstName>John</firstName>",
            "\t\t<lastName>Doe</lastName>",
            "\t</customer>",
            "\t<orderItems>",
            "\t\t<item>",
            "\t\t\t<manufacturer>Samsung</manufacturer>",
            "\t\t\t<name>s-1</name>",
            "\t\t\t<productId>AX02</productId>",
            "\t\t\t<purchaseReceipt>",
            "\t\t\t\t<id>1</id>",
            "\t\t\t\t<status>ACCEPTED</status>",
            "\t\t\t\t<totalPrice>2550.0</totalPrice>",
            "\t\t\t</purchaseReceipt>",
            "\t\t\t<quantity>1</quantity>",
            "\t\t</item>",
            "\t</orderItems>",
            "</summary>");

    /** The shape {@code xsl:copy-of} emits: a declaration and the envelope's in-scope namespace declarations. */
    private static final String XSLT_SUMMARY = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><summary xmlns:soap=\""
            + SOAPENV_NS + "\" xmlns:ns2=\"" + ORD_NS + "\">" + SUMMARY_CHILDREN + "</summary>";

    private static final String EXPECTED = """
            {
              "orderId": "12",
              "customer": {
                "address": "Main Street 123",
                "firstName": "John",
                "lastName": "Doe"
              },
              "orderItems": {
                "item": {
                  "manufacturer": "Samsung",
                  "name": "s-1",
                  "productId": "AX02",
                  "purchaseReceipt": {
                    "id": "1",
                    "status": "ACCEPTED",
                    "totalPrice": "2550.0"
                  },
                  "quantity": "1"
                }
              }
            }""";

    private static final String EXPECTED_TWO_ITEMS = """
            {
              "orderId": "12",
              "customer": {
                "address": "Main Street 123",
                "firstName": "John",
                "lastName": "Doe"
              },
              "orderItems": {
                "item": {
                  "manufacturer": "Samsung",
                  "name": "s-1",
                  "productId": "AX02",
                  "purchaseReceipt": {
                    "id": "1",
                    "status": "ACCEPTED",
                    "totalPrice": "2550.0"
                  },
                  "quantity": "1"
                },
                "item": {
                  "manufacturer": "Philips",
                  "name": "p-1",
                  "productId": "PH01",
                  "purchaseReceipt": {
                    "id": "2",
                    "status": "REJECTED",
                    "totalPrice": "0.0"
                  },
                  "quantity": "2"
                }
              }
            }""";

    private static final String ALL_NULL = """
            {
              "orderId": null,
              "customer": null,
              "orderItems": null
            }""";

    private static final String ORDER_ID_ONLY = """
            {
              "orderId": "12",
              "customer": null,
              "orderItems": null
            }""";

    private static final String ALL_EMPTY = """
            {
              "orderId": "",
              "customer": "",
              "orderItems": ""
            }""";

    /**
     * The summary the 1080 service answers for a page order: the items are not read from the request, so
     * {@code orderItems} comes back as the empty element {@code <orderItems/>}.
     */
    private static final String ITEMLESS_SUMMARY = "<summary><orderId>77</orderId><customer>"
            + "<address>1 Main St</address><firstName>Ann</firstName><lastName>Lee</lastName></customer>"
            + "<orderItems/></summary>";

    private static final String EXPECTED_ITEMLESS = """
            {
              "orderId": "77",
              "customer": {
                "address": "1 Main St",
                "firstName": "Ann",
                "lastName": "Lee"
              },
              "orderItems": ""
            }""";

    /** An itemless order placed with the page's customer fields left blank. */
    private static final String BLANK_CUSTOMER_SUMMARY = "<summary><orderId>78</orderId><customer>"
            + "<address/><firstName/><lastName/></customer><orderItems/></summary>";

    private static final String EXPECTED_BLANK_CUSTOMER = """
            {
              "orderId": "78",
              "customer": {
                "address": "",
                "firstName": "",
                "lastName": ""
              },
              "orderItems": ""
            }""";

    private final OrderSummaryJsonMapper mapper = new OrderSummaryJsonMapper(new DwXmlToJson(), new DwJsonWriter());

    @Test
    void writesTheReplySummaryExactly() {
        assertThat(EXPECTED).doesNotEndWith("\n").doesNotContain("\r");

        assertThat(mapper.toJson(SUMMARY)).isEqualTo(EXPECTED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("equivalentSummaries")
    void ignoresDeclarationNamespacesAndIndentation(String summaryXml) {
        assertThat(mapper.toJson(summaryXml)).isEqualTo(EXPECTED);
    }

    static Stream<Arguments> equivalentSummaries() {
        return Stream.of(
                arguments(named("sample_data/summary_1.xml layout", PRETTY_SUMMARY)),
                arguments(named("SoapEnvelopeToBodyChild.xslt copy", XSLT_SUMMARY)),
                arguments(named("child the script does not select",
                        "<summary>" + SUMMARY_CHILDREN + "<status>OK</status></summary>")));
    }

    @Test
    void keepsRepeatedItemKeysInDocumentOrder() {
        String summaryXml = SUMMARY.replace(SAMSUNG_ITEM, SAMSUNG_ITEM + PHILIPS_ITEM);

        String json = mapper.toJson(summaryXml);

        assertThat(occurrences(json, "\"item\": {")).isEqualTo(2);
        assertThat(json.indexOf("\"manufacturer\": \"Samsung\""))
                .isNotNegative()
                .isLessThan(json.indexOf("\"manufacturer\": \"Philips\""));
        assertThat(json).isEqualTo(EXPECTED_TWO_ITEMS);
    }

    @Test
    void writesTopLevelKeysInScriptOrder() {
        // The children arrive in the reverse of the script's order; the output still follows the script.
        String reversed = "<summary>" + element(SUMMARY, "orderItems") + element(SUMMARY, "customer")
                + element(SUMMARY, "orderId") + "</summary>";

        String json = mapper.toJson(reversed);

        int orderId = json.indexOf("\"orderId\": ");
        int customer = json.indexOf("\"customer\": ");
        int orderItems = json.indexOf("\"orderItems\": ");
        assertThat(orderId).isNotNegative();
        assertThat(customer).isGreaterThan(orderId);
        assertThat(orderItems).isGreaterThan(customer);
        assertThat(json).isEqualTo(EXPECTED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("missedSelectors")
    void writesNullWhereASelectorMisses(String summaryXml, String expected) {
        assertThat(mapper.toJson(summaryXml)).isEqualTo(expected);
    }

    static Stream<Arguments> missedSelectors() {
        return Stream.of(
                arguments(named("root is the envelope, not summary", REPLY), ALL_NULL),
                arguments(named("summary is a leaf", "<summary>12</summary>"), ALL_NULL),
                arguments(named("customer and orderItems absent", "<summary><orderId>12</orderId></summary>"),
                        ORDER_ID_ONLY),
                arguments(named("repeated orderId selects the first",
                        "<summary><orderId>12</orderId><orderId>13</orderId></summary>"), ORDER_ID_ONLY));
    }

    /** DataWeave 1.0's XML reader read an element without text as "", so a selector that hits one writes "". */
    @ParameterizedTest(name = "{0}")
    @MethodSource("emptySelectedElements")
    void writesEmptyStringForEmptyElements(String summaryXml, String expected) {
        assertThat(mapper.toJson(summaryXml)).isEqualTo(expected);
    }

    static Stream<Arguments> emptySelectedElements() {
        return Stream.of(
                arguments(named("selected elements are empty", "<summary><orderId/><customer/><orderItems/></summary>"),
                        ALL_EMPTY),
                arguments(named("itemless 1080 reply", ITEMLESS_SUMMARY), EXPECTED_ITEMLESS),
                arguments(named("itemless 1080 reply with blank customer leaves", BLANK_CUSTOMER_SUMMARY),
                        EXPECTED_BLANK_CUSTOMER));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "<summary><orderId>12</summary>",
        " \n\t ",
        "<!DOCTYPE summary [<!ENTITY id \"12\">]><summary><orderId>&id;</orderId></summary>",
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>soap:ServerOrder could not be processed"
    })
    void malformedXmlThrows(String summaryXml) {
        assertThatThrownBy(() -> mapper.toJson(summaryXml)).isInstanceOf(IllegalArgumentException.class);
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        for (int at = text.indexOf(token); at >= 0; at = text.indexOf(token, at + token.length())) {
            count++;
        }
        return count;
    }

    private static String resource(String name) {
        try (InputStream in = OrderSummaryJsonMapperTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Test resource not found: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The text of {@code document} from {@code <name>} through the first {@code </name>} after it, inclusive. */
    private static String element(String document, String name) {
        String start = "<" + name + ">";
        String end = "</" + name + ">";
        int from = document.indexOf(start);
        int to = from < 0 ? -1 : document.indexOf(end, from);
        if (to < 0) {
            throw new IllegalStateException("No <" + name + "> element in " + document);
        }
        return document.substring(from, to + end.length());
    }
}
