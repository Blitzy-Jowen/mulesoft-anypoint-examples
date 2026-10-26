/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;

/**
 * Unit test of {@link OrderMapper}, the owner of the orders JSON script of {@code books.xml}: the bundled
 * {@code orders.xml} and {@code currency.json} produce the original's committed {@code orders.json} byte for byte,
 * and the year filter, the currency conversion with its {@link BigDecimal} scale, the distinct authors, the
 * non-numeric failures and the plan defaults behave as the mapper documents.
 *
 * <p>The collaborators are real: {@link DwJsonWriter} writes the JSON, the JDK parser builds the DOM without
 * namespace awareness, and {@code currency.json} is read with exact decimals, as {@link OrderMapper} requires of its
 * callers, so {@code 0.92} keeps scale 2.
 */
class OrderMapperTest {

    /** The original's {@code orders.json}, carried unchanged on the test classpath. */
    private static final String EXPECTED_ORDERS_JSON = "/expected/orders.json";

    private static final int EXPECTED_ORDERS_JSON_LENGTH = 793;

    private static final String EXPECTED_ORDERS_JSON_SHA256 =
            "0784d83836248b1c080655606990288f794693889fb54d41b04e6af8a4990610";

    /** Reads JSON numbers as {@link BigDecimal} and keeps their trailing zeros, so each ratio keeps its scale. */
    private static final ObjectMapper EXACT_DECIMALS = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);

    private OrderMapper mapper;

    private JsonNode currencies;

    @BeforeEach
    void createMapperAndReadCurrencies() throws IOException {
        mapper = new OrderMapper(new DwJsonWriter());
        try (InputStream in = resource("/currency.json")) {
            currencies = EXACT_DECIMALS.readTree(in);
        }
    }

    @Test
    @DisplayName("orders.xml with currency.json -> the committed orders.json, equal as text and as bytes")
    void writesCommittedOrdersJson() throws Exception {
        byte[] expectedBytes;
        try (InputStream in = resource(EXPECTED_ORDERS_JSON)) {
            expectedBytes = in.readAllBytes();
        }
        assertThat(expectedBytes).hasSize(EXPECTED_ORDERS_JSON_LENGTH);
        assertThat(sha256(expectedBytes)).isEqualTo(EXPECTED_ORDERS_JSON_SHA256);
        String expected = new String(expectedBytes, StandardCharsets.UTF_8);

        String json = mapper.toJson(parse(resource("/orders.xml")), currencies);

        assertThat(json).isEqualTo(expected);
        assertThat(json.getBytes(StandardCharsets.UTF_8)).containsExactly(expectedBytes);
    }

    @Test
    @DisplayName("year filter: 2004 is not greater than 2004 and is dropped, 2005 is kept")
    void keepsOnlyItemsAfter2004() throws Exception {
        Document orders = parseXml(ordersXml(
                item("A", "30", "2004", "Author A"),
                item("B", "30", "2005", "Author B")));

        List<Map<String, Object>> kept = keptOrders(mapper.map(orders, currencies));

        assertThat(kept).hasSize(1);
        assertThat(kept.get(0)).containsEntry("title", "B");
    }

    @Test
    @DisplayName("authors: distinct by value in first-occurrence order, each a single-key {author} map")
    void listsDistinctAuthorsInFirstOccurrenceOrder() throws Exception {
        Document orders = parseXml(ordersXml(item("XQuery Kick Start", "49.99", "2005",
                "James McGovern", "Per Bothner", "Kurt Cagle", "James Linn", "Kurt Cagle", "Vaidyanathan Nagarajan")));

        List<Map<String, Object>> kept = keptOrders(mapper.map(orders, currencies));

        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).get("authors")).isEqualTo(List.of(
                Map.of("author", "James McGovern"),
                Map.of("author", "Per Bothner"),
                Map.of("author", "Kurt Cagle"),
                Map.of("author", "James Linn"),
                Map.of("author", "Vaidyanathan Nagarajan")));
    }

    static Stream<Arguments> convertedPrices() {
        return Stream.of(
                Arguments.of("29.99", 4, List.of("27.5908", "262.7124", "19.7934")),
                Arguments.of("30", 2, List.of("27.60", "262.80", "19.80")));
    }

    @ParameterizedTest(name = "price {0} -> {2}, scale {1}")
    @MethodSource("convertedPrices")
    @DisplayName("prices: ratio x price as BigDecimal, scale the sum of the operands', in USD order EUR, ARS, GBP")
    void convertsPriceWithEachUsdRatio(String price, int scale, List<String> expected) throws Exception {
        Document orders = parseXml(ordersXml(item("Priced", price, "2005", "Author")));

        List<Map<String, Object>> prices = prices(keptOrders(mapper.map(orders, currencies)).get(0));

        assertThat(prices).allSatisfy(entry -> assertThat(entry).containsOnlyKeys("price", "currency"));
        assertThat(prices).extracting(entry -> entry.get("currency")).containsExactly("EUR", "ARS", "GBP");
        assertThat(prices).extracting(entry -> entry.get("price")).allSatisfy(
                value -> assertThat(value).isExactlyInstanceOf(BigDecimal.class));
        assertThat(prices).extracting(entry -> ((BigDecimal) entry.get("price")).toPlainString())
                .containsExactlyElementsOf(expected);
        assertThat(prices).extracting(entry -> ((BigDecimal) entry.get("price")).scale()).containsOnly(scale);
    }

    static Stream<Arguments> nonNumericItems() {
        return Stream.of(
                Arguments.of("price abc", item("Bad Price", "abc", "2005", "Author")),
                Arguments.of("year abc", item("Bad Year", "30", "abc", "Author")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonNumericItems")
    @DisplayName("a price or year that is not a number -> NumberFormatException")
    void rejectsNonNumericPriceOrYear(String description, String item) throws Exception {
        Document orders = parseXml(ordersXml(item));

        assertThatThrownBy(() -> mapper.map(orders, currencies)).isExactlyInstanceOf(NumberFormatException.class);
    }

    @Test
    @DisplayName("<orders/> without items -> an empty orders list, written {\"orders\": []}")
    void mapsOrdersWithoutItemsToEmptyList() throws Exception {
        Document orders = parseXml("<orders/>");

        assertThat(keptOrders(mapper.map(orders, currencies))).isEmpty();
        assertThat(mapper.toJson(orders, currencies)).isEqualTo("{\n  \"orders\": []\n}");
    }

    @Test
    @DisplayName("an item without properties/authors -> \"authors\": null (plan default)")
    void mapsAbsentAuthorsToNull() throws Exception {
        Document orders = parseXml("<orders><item><type>book</type><price>30</price><properties>"
                + "<title>No Authors</title><year>2005</year></properties></item></orders>");

        List<Map<String, Object>> kept = keptOrders(mapper.map(orders, currencies));

        assertThat(kept).hasSize(1);
        assertThat(kept.get(0)).containsEntry("authors", null);
    }

    @Test
    @DisplayName("USD with no entries -> an empty prices list, the item's price never read")
    void mapsEmptyUsdToEmptyPricesWithoutReadingPrice() throws Exception {
        Document orders = parseXml("<orders><item><type>book</type><properties><title>No Price</title>"
                + "<authors><author>Author</author></authors><year>2005</year></properties></item></orders>");
        JsonNode noRatios = EXACT_DECIMALS.readTree("{\"USD\": []}");

        List<Map<String, Object>> kept = keptOrders(mapper.map(orders, noRatios));

        assertThat(kept).hasSize(1);
        assertThat(prices(kept.get(0))).isEmpty();
    }

    /** One {@code item} in the layout of the bundled {@code orders.xml}: price beside properties. */
    private static String item(String title, String price, String year, String... authors) {
        StringBuilder xml = new StringBuilder("<item><type>book</type><price>").append(price)
                .append("</price><properties><title>").append(title).append("</title><authors>");
        for (String author : authors) {
            xml.append("<author>").append(author).append("</author>");
        }
        return xml.append("</authors><year>").append(year).append("</year></properties></item>").toString();
    }

    private static String ordersXml(String... items) {
        return "<orders>" + String.join("", items) + "</orders>";
    }

    private static Document parseXml(String xml) throws IOException, SAXException, ParserConfigurationException {
        return parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static Document parse(InputStream xml) throws IOException, SAXException, ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        try (InputStream in = xml) {
            return factory.newDocumentBuilder().parse(in);
        }
    }

    private static InputStream resource(String location) {
        InputStream stream = OrderMapperTest.class.getResourceAsStream(location);
        assertThat(stream).as("classpath resource %s", location).isNotNull();
        return stream;
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> keptOrders(Map<String, Object> result) {
        assertThat(result).containsOnlyKeys("orders");
        assertThat(result.get("orders")).isInstanceOf(List.class);
        return (List<Map<String, Object>>) result.get("orders");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> prices(Map<String, Object> order) {
        assertThat(order.get("prices")).isInstanceOf(List.class);
        return (List<Map<String, Object>>) order.get("prices");
    }
}
