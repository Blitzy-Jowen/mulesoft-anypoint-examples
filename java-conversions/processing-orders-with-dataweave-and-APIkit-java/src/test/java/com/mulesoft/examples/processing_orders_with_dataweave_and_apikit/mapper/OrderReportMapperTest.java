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

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Unit test of {@link OrderReportMapper}, the owner of the {@code report} CSV script of {@code books.xml}: the
 * bundled {@code orders.xml} produces the original's committed {@code report.csv} byte for byte, the report counts
 * every item whatever its year, the sum and the {@link java.math.MathContext#DECIMAL128 DECIMAL128} average keep
 * their {@link BigDecimal} form, and documents without items or with unusable prices fail as the mapper declares.
 *
 * <p>This class owns the byte-exact report check; the ported original test compares with whitespace removed. The
 * collaborators are real: {@link DwCsvWriter} writes the CSV and the JDK parser builds the DOM without namespace
 * awareness, as the order flow does.
 */
class OrderReportMapperTest {

    /** The original's {@code report.csv}, carried unchanged on the test classpath. */
    private static final String EXPECTED_REPORT_CSV = "/expected/report.csv";

    private static final int EXPECTED_REPORT_CSV_LENGTH = 68;

    private static final String EXPECTED_REPORT_CSV_SHA256 =
            "a714697949e651545156ed6df4731e73c2dd4f73bda6b9fcac6eed8c5c806fc7";

    private static final String TOTAL_NUMBER_OF_ITEMS = "totalNumberOfItems";

    private static final String TOTAL_AMOUNT = "totalAmount";

    private static final String AVERAGE_SELLING_PRICE = "averageSellingPrice";

    private OrderReportMapper mapper;

    @BeforeEach
    void createMapper() {
        mapper = new OrderReportMapper(new DwCsvWriter());
    }

    @Test
    @DisplayName("orders.xml -> the committed report.csv, equal as text and as bytes")
    void writesCommittedReportCsv() throws Exception {
        byte[] expectedBytes;
        try (InputStream in = resource(EXPECTED_REPORT_CSV)) {
            expectedBytes = in.readAllBytes();
        }
        assertThat(expectedBytes).hasSize(EXPECTED_REPORT_CSV_LENGTH);
        assertThat(sha256(expectedBytes)).isEqualTo(EXPECTED_REPORT_CSV_SHA256);

        String csv = mapper.toCsv(parse(resource("/orders.xml")));

        assertThat(csv).isEqualTo("totalNumberOfItems,totalAmount,averageSellingPrice\n4,149.93,37.4825\n");
        assertThat(csv.getBytes(StandardCharsets.UTF_8)).containsExactly(expectedBytes);
    }

    @Test
    @DisplayName("orders.xml -> one row over all four items, the two 2003 items included")
    void reportsEveryBundledItem() throws Exception {
        List<Map<String, Object>> report = mapper.map(parse(resource("/orders.xml")));

        assertRow(report, 4, "149.93", "37.4825");
    }

    static Stream<Arguments> computedRows() {
        return Stream.of(
                Arguments.of("only 2003 items: no year filter applies",
                        ordersXml(item("10", "2003"), item("20", "2003")), 2, "30", "15"),
                Arguments.of("non-terminating average: DECIMAL128, 34 significant digits",
                        ordersXml(item("1", "2005"), item("1", "2005"), item("0", "2005")),
                        3, "2", "0.6666666666666666666666666666666667"),
                Arguments.of("price text trimmed, sum scale the largest operand scale",
                        ordersXml(item("\n  1.50 \t", "2005"), item("2", "2005")), 2, "3.50", "1.75"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("computedRows")
    @DisplayName("count, exact sum and sum / count rounded to DECIMAL128, over every item")
    void computesCountSumAndAverage(String description, String xml, int count, String total, String average)
            throws Exception {
        assertRow(mapper.map(parseXml(xml)), count, total, average);
    }

    @ParameterizedTest(name = "namespace-aware DOM: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("prefixed orders and item elements are selected by local name, with or without namespace awareness")
    void selectsPrefixedElementsByLocalName(boolean namespaceAware) throws Exception {
        String xml = "<o:orders xmlns:o=\"urn:example:orders\">"
                + "<o:item><o:type>book</o:type><o:price>10</o:price></o:item>"
                + "<o:item><o:type>book</o:type><o:price>20.5</o:price></o:item>"
                + "</o:orders>";

        assertRow(mapper.map(parseXml(xml, namespaceAware)), 2, "30.5", "15.25");
    }

    static Stream<Arguments> documentsWithoutItems() throws Exception {
        return Stream.of(
                Arguments.of("<orders/>", parseXml("<orders/>")),
                Arguments.of("items nested below a non-item child",
                        parseXml("<orders><order>" + item("10", "2005") + "</order></orders>")),
                Arguments.of("root other than orders", parseXml("<catalog>" + item("10", "2005") + "</catalog>")),
                Arguments.of("document without a root element", documentBuilder(false).newDocument()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("documentsWithoutItems")
    @DisplayName("no items -> ArithmeticException from map and toCsv, as DataWeave's avg of an empty array fails")
    void rejectsDocumentWithoutItems(String description, Document orders) {
        assertThatThrownBy(() -> mapper.map(orders)).isExactlyInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> mapper.toCsv(orders)).isExactlyInstanceOf(ArithmeticException.class);
    }

    static Stream<Arguments> unusablePrices() {
        return Stream.of(
                Arguments.of("price abc", ordersXml(item("10", "2005"), item("abc", "2005")),
                        NumberFormatException.class),
                Arguments.of("empty price", ordersXml(item("  ", "2005")), NumberFormatException.class),
                Arguments.of("item without price", "<orders><item><type>book</type><properties><title>No Price"
                        + "</title><authors><author>Author</author></authors><year>2005</year></properties></item>"
                        + "</orders>", IllegalArgumentException.class));
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("unusablePrices")
    @DisplayName("a price that is not a number -> NumberFormatException; a missing price -> IllegalArgumentException")
    void rejectsUnusablePrice(String description, String xml, Class<? extends Throwable> expected) throws Exception {
        Document orders = parseXml(xml);

        assertThatThrownBy(() -> mapper.map(orders)).isExactlyInstanceOf(expected);
        assertThatThrownBy(() -> mapper.toCsv(orders)).isExactlyInstanceOf(expected);
    }

    @Test
    @DisplayName("a null document -> NullPointerException from map and toCsv")
    void rejectsNullDocument() {
        assertThatThrownBy(() -> mapper.map(null)).isExactlyInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> mapper.toCsv(null)).isExactlyInstanceOf(NullPointerException.class);
    }

    /**
     * Asserts the script's single record: the three columns in script order, an {@link Integer} count and
     * {@link BigDecimal} amounts compared by their plain written form, so scale is part of the comparison.
     */
    private static void assertRow(List<Map<String, Object>> report, int count, String total, String average) {
        assertThat(report).hasSize(1);
        Map<String, Object> row = report.get(0);
        assertThat(row.keySet()).containsExactly(TOTAL_NUMBER_OF_ITEMS, TOTAL_AMOUNT, AVERAGE_SELLING_PRICE);
        assertThat(row.get(TOTAL_NUMBER_OF_ITEMS)).isExactlyInstanceOf(Integer.class).isEqualTo(count);
        assertThat(row.get(TOTAL_AMOUNT)).isExactlyInstanceOf(BigDecimal.class);
        assertThat(((BigDecimal) row.get(TOTAL_AMOUNT)).toPlainString()).isEqualTo(total);
        assertThat(row.get(AVERAGE_SELLING_PRICE)).isExactlyInstanceOf(BigDecimal.class);
        assertThat(((BigDecimal) row.get(AVERAGE_SELLING_PRICE)).toPlainString()).isEqualTo(average);
    }

    /** One {@code item} in the layout of the bundled {@code orders.xml}: price beside properties. */
    private static String item(String price, String year) {
        return "<item><type>book</type><price>" + price + "</price><properties><title>Title</title>"
                + "<authors><author>Author</author></authors><year>" + year + "</year></properties></item>";
    }

    private static String ordersXml(String... items) {
        return "<orders>" + String.join("", items) + "</orders>";
    }

    private static Document parseXml(String xml) throws IOException, SAXException, ParserConfigurationException {
        return parseXml(xml, false);
    }

    private static Document parseXml(String xml, boolean namespaceAware)
            throws IOException, SAXException, ParserConfigurationException {
        return documentBuilder(namespaceAware).parse(new InputSource(new StringReader(xml)));
    }

    private static Document parse(InputStream xml) throws IOException, SAXException, ParserConfigurationException {
        try (InputStream in = xml) {
            return documentBuilder(false).parse(in);
        }
    }

    private static DocumentBuilder documentBuilder(boolean namespaceAware) throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(namespaceAware);
        return factory.newDocumentBuilder();
    }

    private static InputStream resource(String location) {
        InputStream stream = OrderReportMapperTest.class.getResourceAsStream(location);
        assertThat(stream).as("classpath resource %s", location).isNotNull();
        return stream;
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
