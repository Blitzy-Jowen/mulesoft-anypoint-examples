/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.io.InputStream;
import java.io.StringReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Unit test of {@link OrdersXmlMapper}, the owner of the {@code Map CSV to XML} script of the original
 * {@code imap-to-xml.xml} flow: one {@code order} per CSV row, in row order, holding {@code orderId},
 * {@code name}, {@code units} and {@code pricePerUnit} as the raw CSV strings, serialized by {@link DwXmlWriter}.
 *
 * <p>The expected documents are the DataWeave 1.0 bytes: the single-quoted declaration, a line feed, tab
 * indentation, LF line ends and no line feed after {@code </orders>}. The renderings of a missing value
 * ({@code <units/>}) and of an element without children ({@code <orders/>}) are {@link DwXmlWriter}'s rule for an
 * element with neither text nor children.
 */
class OrdersXmlMapperTest {

    private static final String DECLARATION_LINE = "<?xml version='1.0' encoding='UTF-8'?>\n";

    /** The orders document of the original {@code input.csv}: 289 UTF-8 bytes ending at {@code </orders>}. */
    private static final String EXPECTED_INPUT_CSV_XML = DECLARATION_LINE
            + "<orders>\n"
            + "\t<order>\n"
            + "\t\t<orderId>1</orderId>\n"
            + "\t\t<name>aaa</name>\n"
            + "\t\t<units>2.0</units>\n"
            + "\t\t<pricePerUnit>10</pricePerUnit>\n"
            + "\t</order>\n"
            + "\t<order>\n"
            + "\t\t<orderId>2</orderId>\n"
            + "\t\t<name>bbb</name>\n"
            + "\t\t<units>4.15</units>\n"
            + "\t\t<pricePerUnit>5</pricePerUnit>\n"
            + "\t</order>\n"
            + "</orders>";

    private static final int EXPECTED_INPUT_CSV_XML_BYTES = 289;

    private static final String EXPECTED_INPUT_CSV_XML_SHA256 =
            "c5d9c000e8caa4640d880c08c0f9b75ab2010991b8cf3a93840760af55a906ee";

    /** A single order of the first {@code input.csv} row. */
    private static final String EXPECTED_SINGLE_ORDER_XML = DECLARATION_LINE
            + "<orders>\n"
            + "\t<order>\n"
            + "\t\t<orderId>1</orderId>\n"
            + "\t\t<name>aaa</name>\n"
            + "\t\t<units>2.0</units>\n"
            + "\t\t<pricePerUnit>10</pricePerUnit>\n"
            + "\t</order>\n"
            + "</orders>";

    private static final List<String> ORDER_CHILDREN = List.of("orderId", "name", "units", "pricePerUnit");

    private static final Pattern ORDER_ID_VALUE = Pattern.compile("<orderId>(.*?)</orderId>");

    private OrdersXmlMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new OrdersXmlMapper(new DwXmlWriter());
    }

    @Test
    void toXmlMatchesDataWeaveOutputForInputCsvRows() throws Exception {
        String xml = mapper.toXml(List.of(
                row("orderId", "1", "name", "aaa", "units", "2.0", "pricePerUnit", "10"),
                row("orderId", "2", "name", "bbb", "units", "4.15", "pricePerUnit", "5")));

        assertThat(xml).isEqualTo(EXPECTED_INPUT_CSV_XML);
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        assertThat(bytes).hasSize(EXPECTED_INPUT_CSV_XML_BYTES);
        assertThat(sha256Hex(bytes)).isEqualTo(EXPECTED_INPUT_CSV_XML_SHA256);
    }

    @Test
    void toXmlOfRowsReadFromInputCsvResource() throws Exception {
        List<Map<String, String>> rows = inputCsvRows();

        assertThat(rows).hasSize(2);
        assertThat(mapper.toXml(rows)).isEqualTo(EXPECTED_INPUT_CSV_XML);
    }

    @Test
    void eachOrderIsValidAgainstOrdersXsd() throws Exception {
        URL xsd = OrdersXmlMapperTest.class.getResource("/orders.xsd");
        assertThat(xsd).as("test classpath resource /orders.xsd").isNotNull();
        Schema schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                .newSchema(new StreamSource(xsd.toExternalForm()));
        DocumentBuilder builder = newDocumentBuilder();

        String xml = mapper.toXml(inputCsvRows());
        List<Element> orders = orderElements(xml);
        assertThat(orders).hasSize(2);

        for (Element order : orders) {
            assertThat(childElementNames(order)).containsExactlyElementsOf(ORDER_CHILDREN);

            // ordersType admits exactly one order, so each order is validated inside an orders root of its own.
            Document single = builder.newDocument();
            Element root = single.createElementNS(null, "orders");
            single.appendChild(root);
            root.appendChild(single.importNode(order, true));

            Validator validator = schema.newValidator();
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            assertDoesNotThrow(() -> validator.validate(new DOMSource(single)));
        }
    }

    @Test
    void childOrderIsFixedRegardlessOfInputColumnOrder() {
        Map<String, String> reversed = row("pricePerUnit", "10", "units", "2.0", "name", "aaa", "orderId", "1");

        assertThat(mapper.toXml(List.of(reversed))).isEqualTo(EXPECTED_SINGLE_ORDER_XML);
    }

    @Test
    void valuesAreKeptAsRawStrings() {
        String xml = mapper.toXml(List.of(
                row("orderId", " 7 ", "name", "aaa", "units", "2.0", "pricePerUnit", "010"),
                row("orderId", "2", "name", "bbb", "units", "4.15", "pricePerUnit", ""),
                row("orderId", "3", "name", "a & <b>", "units", "1", "pricePerUnit", "5")));

        assertThat(xml)
                .contains("<units>2.0</units>")
                .contains("<units>4.15</units>")
                .contains("<pricePerUnit>010</pricePerUnit>")
                .contains("<orderId> 7 </orderId>")
                // An empty CSV field is an empty string, not a missing value, so it keeps its text element.
                .contains("<pricePerUnit></pricePerUnit>")
                .contains("<name>a &amp; &lt;b&gt;</name>");
    }

    @Test
    void missingOrNullColumnRendersAsDwXmlWriterNull() {
        Map<String, String> missing = row("orderId", "1", "name", "aaa", "pricePerUnit", "10");
        Map<String, String> nullValue = row("orderId", "1", "name", "aaa", "units", null, "pricePerUnit", "10");

        String missingXml = mapper.toXml(List.of(missing));
        String nullXml = mapper.toXml(List.of(nullValue));

        assertThat(missingXml).isEqualTo(nullXml);
        assertThat(missingXml).isEqualTo(DECLARATION_LINE
                + "<orders>\n"
                + "\t<order>\n"
                + "\t\t<orderId>1</orderId>\n"
                + "\t\t<name>aaa</name>\n"
                + "\t\t<units/>\n"
                + "\t\t<pricePerUnit>10</pricePerUnit>\n"
                + "\t</order>\n"
                + "</orders>");
    }

    @Test
    void emptyListGivesEmptyOrdersElement() {
        String xml = mapper.toXml(List.of());

        assertThat(xml).isEqualTo(DECLARATION_LINE + "<orders/>");
        assertThat(xml).startsWith("<?xml version='1.0' encoding='UTF-8'?>").doesNotEndWith("\n");
    }

    @Test
    void extraColumnsAreIgnored() {
        Map<String, String> withExtras = row("orderId", "1", "customer", "acme", "name", "aaa",
                "comment", "x", "units", "2.0", "pricePerUnit", "10");
        Map<String, String> withoutExtras = row("orderId", "1", "name", "aaa", "units", "2.0", "pricePerUnit", "10");

        String xml = mapper.toXml(List.of(withExtras));

        assertThat(xml).isEqualTo(mapper.toXml(List.of(withoutExtras)));
        assertThat(xml).doesNotContain("customer").doesNotContain("comment").doesNotContain("acme");
    }

    @Test
    void ordersKeepInputRowOrder() {
        String xml = mapper.toXml(List.of(
                row("orderId", "3", "name", "ccc", "units", "3", "pricePerUnit", "30"),
                row("orderId", "1", "name", "aaa", "units", "1", "pricePerUnit", "10"),
                row("orderId", "2", "name", "bbb", "units", "2", "pricePerUnit", "20")));

        List<String> orderIds = new ArrayList<>();
        Matcher matcher = ORDER_ID_VALUE.matcher(xml);
        while (matcher.find()) {
            orderIds.add(matcher.group(1));
        }
        assertThat(orderIds).containsExactly("3", "1", "2");
    }

    @Test
    void nullRowsAreRejected() {
        assertThatThrownBy(() -> mapper.toXml(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CSV rows to map to orders XML must not be null");
    }

    @Test
    void writerIsRequired() {
        assertThatThrownBy(() -> new OrdersXmlMapper(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("dwXmlWriter must not be null");
    }

    /** An insertion-ordered row from alternating column names and values; values may be {@code null}. */
    private static Map<String, String> row(String... columnsAndValues) {
        if (columnsAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("Column names and values must alternate");
        }
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < columnsAndValues.length; i += 2) {
            row.put(columnsAndValues[i], columnsAndValues[i + 1]);
        }
        return row;
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * The rows {@link DwCsvReader} reads from the main classpath resource {@code /input.csv}.
     *
     * @return the CSV rows in file order, each keyed by header name
     * @throws Exception when the resource cannot be read
     */
    private static List<Map<String, String>> inputCsvRows() throws Exception {
        byte[] csv;
        try (InputStream in = OrdersXmlMapperTest.class.getResourceAsStream("/input.csv")) {
            assertThat(in).as("main classpath resource /input.csv").isNotNull();
            csv = in.readAllBytes();
        }
        return new DwCsvReader().read(csv);
    }

    /** Parses {@code xml} and returns the {@code order} children of its no-namespace {@code orders} root. */
    private static List<Element> orderElements(String xml) throws Exception {
        Document document = newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        Element root = document.getDocumentElement();
        assertThat(root.getNamespaceURI()).isNull();
        assertThat(root.getLocalName()).isEqualTo("orders");

        List<Element> orders = new ArrayList<>();
        for (Element child : childElements(root)) {
            assertThat(child.getNamespaceURI()).isNull();
            assertThat(child.getLocalName()).isEqualTo("order");
            orders.add(child);
        }
        return orders;
    }

    /** The local names of {@code parent}'s element children; every other child must be whitespace text. */
    private static List<String> childElementNames(Element parent) {
        List<String> names = new ArrayList<>();
        for (Element child : childElements(parent)) {
            names.add(child.getLocalName());
        }
        return names;
    }

    private static List<Element> childElements(Element parent) {
        List<Element> elements = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                elements.add((Element) child);
            } else {
                assertThat(child.getNodeType()).isEqualTo(Node.TEXT_NODE);
                assertThat(child.getTextContent()).isBlank();
            }
        }
        return elements;
    }

    /** A namespace-aware builder that refuses DOCTYPEs, XInclude and entity expansion. */
    private static DocumentBuilder newDocumentBuilder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }
}
