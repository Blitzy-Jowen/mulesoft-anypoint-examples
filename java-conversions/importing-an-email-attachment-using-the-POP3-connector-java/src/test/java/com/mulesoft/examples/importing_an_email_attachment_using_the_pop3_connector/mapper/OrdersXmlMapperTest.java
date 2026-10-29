/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.mapper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.xml.sax.SAXException;

/**
 * Unit test of {@link OrdersXmlMapper}, the hand reimplementation of the {@code %dw 1.0} script "Map CSV to XML" of
 * {@code importing-an-email-attachment-using-the-POP3-connector/src/main/app/pop-to-xml.xml} (header line 13):
 * <pre>
 * orders: { (payload map order: { orderId: $.orderId, name: $.name, units: $.units, pricePerUnit: $.pricePerUnit }) }
 * </pre>
 *
 * <p>The mapper runs over the real SC-4 {@link DwCsvReader} and {@link DwXmlWriter}, never a re-implementation of
 * either, so the assertions cover the bytes the converted flow writes to {@code orders.xml}. Every expected document
 * is a fixed literal spelled out with explicit {@code \n} and {@code \t}: a text block could add a trailing line feed
 * or strip the tabs, and the DataWeave 1.0 writer emits neither. When the mapper output differs from a literal, the
 * mapper is wrong, not the literal.
 *
 * <p>Plain JUnit 5 without a Spring context; the only resources read are the carried {@code /input.csv} and
 * {@code /orders.xsd}.
 */
class OrdersXmlMapperTest {

    /** The document the script produced for the original {@code input.csv}: 289 bytes in UTF-8. */
    private static final String EXPECTED_TWO_ORDERS = "<?xml version='1.0' encoding='UTF-8'?>\n"
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

    /** SHA-256 of the UTF-8 bytes of {@link #EXPECTED_TWO_ORDERS}, the digest the mapper's output must match. */
    private static final String EXPECTED_TWO_ORDERS_SHA256 =
            "c5d9c000e8caa4640d880c08c0f9b75ab2010991b8cf3a93840760af55a906ee";

    /** The document for the first {@code input.csv} record alone: 173 bytes in UTF-8. */
    private static final String EXPECTED_ONE_ORDER = "<?xml version='1.0' encoding='UTF-8'?>\n"
            + "<orders>\n"
            + "\t<order>\n"
            + "\t\t<orderId>1</orderId>\n"
            + "\t\t<name>aaa</name>\n"
            + "\t\t<units>2.0</units>\n"
            + "\t\t<pricePerUnit>10</pricePerUnit>\n"
            + "\t</order>\n"
            + "</orders>";

    /** An {@code orders} root without children, as {@link DwXmlWriter} writes an element with no content. */
    private static final String EXPECTED_NO_ORDERS = "<?xml version='1.0' encoding='UTF-8'?>\n<orders/>";

    private OrdersXmlMapper mapper;

    private DwCsvReader csvReader;

    @BeforeEach
    void setUp() {
        mapper = new OrdersXmlMapper(new DwXmlWriter());
        csvReader = new DwCsvReader();
    }

    @Test
    void mapsClasspathInputCsvToExactLiteral() throws IOException {
        byte[] csv = readResource("/input.csv");
        assertEquals(57, csv.length, "carried input.csv drifted from the original's 57 bytes");

        String xml = mapper.toXml(csvReader.read(csv));

        assertEquals(EXPECTED_TWO_ORDERS, xml);
        assertEquals(289, xml.getBytes(StandardCharsets.UTF_8).length);
        assertFalse(xml.endsWith("\n"), "nothing may follow the root end tag");
    }

    @Test
    void mapsInlineRowsToExactLiteral() {
        String xml = mapper.toXml(List.of(
                row("orderId", "1", "name", "aaa", "units", "2.0", "pricePerUnit", "10"),
                row("orderId", "2", "name", "bbb", "units", "4.15", "pricePerUnit", "5")));

        assertEquals(EXPECTED_TWO_ORDERS, xml);
    }

    @Test
    void toXmlBytesAreTheUtf8EncodingOfTheDocument() throws NoSuchAlgorithmException {
        byte[] bytes = mapper.toXmlBytes(List.of(
                row("orderId", "1", "name", "aaa", "units", "2.0", "pricePerUnit", "10"),
                row("orderId", "2", "name", "bbb", "units", "4.15", "pricePerUnit", "5")));

        assertArrayEquals(EXPECTED_TWO_ORDERS.getBytes(StandardCharsets.UTF_8), bytes);
        assertEquals(289, bytes.length);
        assertEquals(EXPECTED_TWO_ORDERS_SHA256,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));

        byte[] nonAscii = mapper.toXmlBytes(List.of(
                row("orderId", "3", "name", "Zo\u00EB", "units", "1", "pricePerUnit", "7")));
        String expectedNonAscii = "<?xml version='1.0' encoding='UTF-8'?>\n"
                + "<orders>\n"
                + "\t<order>\n"
                + "\t\t<orderId>3</orderId>\n"
                + "\t\t<name>Zo\u00EB</name>\n"
                + "\t\t<units>1</units>\n"
                + "\t\t<pricePerUnit>7</pricePerUnit>\n"
                + "\t</order>\n"
                + "</orders>";
        assertArrayEquals(expectedNonAscii.getBytes(StandardCharsets.UTF_8), nonAscii);
    }

    @Test
    void rendersChildrenInScriptOrderRegardlessOfMapKeyOrder() {
        String xml = mapper.toXml(List.of(row("pricePerUnit", "10", "units", "2.0", "name", "aaa", "orderId", "1")));

        assertEquals(EXPECTED_ONE_ORDER, xml);
        assertTrue(xml.contains("<units>2.0</units>"));
        assertFalse(xml.contains("<units>2</units>"), "units must not be coerced to a number");

        String reversedTwoRows = mapper.toXml(List.of(
                row("pricePerUnit", "10", "units", "2.0", "name", "aaa", "orderId", "1"),
                row("pricePerUnit", "5", "units", "4.15", "name", "bbb", "orderId", "2")));
        assertTrue(reversedTwoRows.contains("<units>4.15</units>"));
        assertEquals(EXPECTED_TWO_ORDERS, reversedTwoRows);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2.0", "4.15", "0010", " 3 ", "1E3", ""})
    void passesCsvValuesThroughVerbatim(String units) {
        String xml = mapper.toXml(List.of(row("orderId", "1", "name", "aaa", "units", units, "pricePerUnit", "10")));

        assertEquals("<?xml version='1.0' encoding='UTF-8'?>\n"
                + "<orders>\n"
                + "\t<order>\n"
                + "\t\t<orderId>1</orderId>\n"
                + "\t\t<name>aaa</name>\n"
                + "\t\t<units>" + units + "</units>\n"
                + "\t\t<pricePerUnit>10</pricePerUnit>\n"
                + "\t</order>\n"
                + "</orders>", xml);
    }

    @Test
    void ignoresExtraColumns() {
        String xml = mapper.toXml(List.of(
                row("orderId", "1", "name", "aaa", "comment", "note", "units", "2.0", "pricePerUnit", "10"),
                row("orderId", "2", "name", "bbb", "units", "4.15", "pricePerUnit", "5", "comment", "note")));

        assertEquals(EXPECTED_TWO_ORDERS, xml);
        assertFalse(xml.contains("comment"));
        assertFalse(xml.contains("note"));
    }

    @Test
    void missingColumnRendersAsNullLeaf() {
        // $.pricePerUnit on a record without that column is null: the child is still written, with no content.
        String xml = mapper.toXml(List.of(row("orderId", "1", "name", "aaa", "units", "2.0")));

        assertEquals("<?xml version='1.0' encoding='UTF-8'?>\n"
                + "<orders>\n"
                + "\t<order>\n"
                + "\t\t<orderId>1</orderId>\n"
                + "\t\t<name>aaa</name>\n"
                + "\t\t<units>2.0</units>\n"
                + "\t\t<pricePerUnit/>\n"
                + "\t</order>\n"
                + "</orders>", xml);
    }

    @Test
    void nullRecordStillYieldsAnOrderWithFourNullLeaves() {
        // Every $.field of a null record is null, so the order keeps its four children.
        String xml = mapper.toXml(Arrays.asList(
                row("orderId", "1", "name", "aaa", "units", "2.0", "pricePerUnit", "10"),
                null));

        assertEquals("<?xml version='1.0' encoding='UTF-8'?>\n"
                + "<orders>\n"
                + "\t<order>\n"
                + "\t\t<orderId>1</orderId>\n"
                + "\t\t<name>aaa</name>\n"
                + "\t\t<units>2.0</units>\n"
                + "\t\t<pricePerUnit>10</pricePerUnit>\n"
                + "\t</order>\n"
                + "\t<order>\n"
                + "\t\t<orderId/>\n"
                + "\t\t<name/>\n"
                + "\t\t<units/>\n"
                + "\t\t<pricePerUnit/>\n"
                + "\t</order>\n"
                + "</orders>", xml);
    }

    @Test
    void emptyListRendersEmptyOrdersRoot() {
        assertEquals(EXPECTED_NO_ORDERS, mapper.toXml(List.of()));
    }

    @Test
    void nullRowsReadAsNoRecords() {
        assertEquals(EXPECTED_NO_ORDERS, mapper.toXml(null));
        assertArrayEquals(EXPECTED_NO_ORDERS.getBytes(StandardCharsets.UTF_8), mapper.toXmlBytes(null));
    }

    @Test
    void singleRowOutputValidatesAgainstOrdersXsd() throws SAXException {
        // ordersType declares a single order element (maxOccurs defaults to 1), so only a one-record document can
        // be valid; the two-record input.csv output is checked by its bytes instead.
        Schema schema = ordersSchema();
        String xml = mapper.toXml(List.of(row("orderId", "1", "name", "aaa", "units", "2.0", "pricePerUnit", "10")));

        assertEquals(EXPECTED_ONE_ORDER, xml);
        assertDoesNotThrow(() -> schema.newValidator().validate(new StreamSource(new StringReader(xml))));
    }

    @Test
    void twoRowOutputExceedsXsdSingleOrderLimit() throws SAXException {
        Schema schema = ordersSchema();
        String xml = mapper.toXml(List.of(
                row("orderId", "1", "name", "aaa", "units", "2.0", "pricePerUnit", "10"),
                row("orderId", "2", "name", "bbb", "units", "4.15", "pricePerUnit", "5")));

        assertEquals(EXPECTED_TWO_ORDERS, xml);
        assertThrows(SAXException.class,
                () -> schema.newValidator().validate(new StreamSource(new StringReader(xml))));
    }

    /** A record keyed in argument order: {@code row("k1", "v1", "k2", "v2", ...)}. */
    private static Map<String, String> row(String... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("row(...) needs key/value pairs");
        }
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            row.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return row;
    }

    private static byte[] readResource(String path) throws IOException {
        try (InputStream in = OrdersXmlMapperTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing classpath resource " + path);
            return in.readAllBytes();
        }
    }

    private static Schema ordersSchema() throws SAXException {
        URL xsd = OrdersXmlMapperTest.class.getResource("/orders.xsd");
        assertNotNull(xsd, "missing classpath resource /orders.xsd");
        return SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(xsd);
    }
}
