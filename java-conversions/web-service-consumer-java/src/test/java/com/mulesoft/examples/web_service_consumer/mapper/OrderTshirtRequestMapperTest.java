/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.web_service_consumer.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXParseException;

import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * Contract of {@link OrderTshirtRequestMapper}, the owner of the {@code orderTshirt} script
 * {@code ns0#OrderTshirt: payload} ({@code %namespace ns0 http://mulesoft.org/tshirt-service},
 * {@code %output application/xml}): the JSON order becomes the {@code ns0:OrderTshirt} element in
 * {@link DwXmlWriter} layout, members in body order, a repeated key or an array item once per occurrence,
 * {@code null} as an empty element, decimals with their scale; a scalar body is the root's text and a {@code null}
 * body the empty root, as the script places any payload under {@code ns0#OrderTshirt}; an empty or malformed body,
 * and a root array, which the writer refuses as XML output has a single root, are rejected with
 * {@link IllegalArgumentException} by both conversions.
 *
 * <p>The sample is the original example's {@code /message.json}, read from the test classpath as bytes. Expected
 * documents are spelled out with explicit {@code \n} and {@code \t}. The mapper is built directly over a real
 * {@link DwXmlWriter}; no Spring context is started.
 */
class OrderTshirtRequestMapperTest {

    private static final String TSHIRT_NAMESPACE = "http://mulesoft.org/tshirt-service";

    private static final String EMPTY_BODY = "Request body is empty";

    private static final String SINGLE_ROOT = "XML output requires a single root element";

    private static final String DECLARATION_LINE = "<?xml version='1.0' encoding='UTF-8'?>\n";

    private static final String ROOT_START = "<ns0:OrderTshirt xmlns:ns0=\"http://mulesoft.org/tshirt-service\">\n";

    private static final String ROOT_END = "</ns0:OrderTshirt>";

    /** An order without members: the root alone, self-closed. */
    private static final String EMPTY_ORDER = DECLARATION_LINE
            + "<ns0:OrderTshirt xmlns:ns0=\"http://mulesoft.org/tshirt-service\"/>";

    /** {@code message.json} as the script wrote it: 403 characters, nothing after the root end tag. */
    private static final String MESSAGE_XML = "<?xml version='1.0' encoding='UTF-8'?>\n"
            + "<ns0:OrderTshirt xmlns:ns0=\"http://mulesoft.org/tshirt-service\">\n"
            + "\t<email>customerID@gmail.com</email>\n"
            + "\t<address1>Corrientes 316</address1>\n"
            + "\t<address2>EP</address2>\n"
            + "\t<city>Buenos Aires</city>\n"
            + "\t<country>Argentina</country>\n"
            + "\t<name>MuleSoft Argentina</name>\n"
            + "\t<postalCode>C1043AAQ</postalCode>\n"
            + "\t<size>L</size>\n"
            + "\t<stateOrProvince>CABA</stateOrProvince>\n"
            + "</ns0:OrderTshirt>";

    /** The nine members of {@code message.json}, in body order. */
    private static final List<Map.Entry<String, String>> MESSAGE_FIELDS = List.of(
            Map.entry("email", "customerID@gmail.com"),
            Map.entry("address1", "Corrientes 316"),
            Map.entry("address2", "EP"),
            Map.entry("city", "Buenos Aires"),
            Map.entry("country", "Argentina"),
            Map.entry("name", "MuleSoft Argentina"),
            Map.entry("postalCode", "C1043AAQ"),
            Map.entry("size", "L"),
            Map.entry("stateOrProvince", "CABA"));

    private final OrderTshirtRequestMapper mapper = new OrderTshirtRequestMapper(new DwXmlWriter());

    private final DwXmlToJson reader = new DwXmlToJson();

    @Test
    void namespaceAndRootAreTheScriptsNs0AndOrderTshirt() {
        assertThat(OrderTshirtRequestMapper.NAMESPACE).isEqualTo(TSHIRT_NAMESPACE);
        assertThat(OrderTshirtRequestMapper.ROOT).isEqualTo("OrderTshirt");
    }

    @Test
    void sampleMessageIsWrittenInBodyOrderWithNothingAfterTheRootEndTag() throws IOException {
        String xml = mapper.toXml(message());

        assertThat(xml).isEqualTo(MESSAGE_XML).hasSize(403).endsWith(ROOT_END);
    }

    static Stream<Arguments> encodedSamples() throws IOException {
        String json = new String(message(), StandardCharsets.UTF_8);
        byte[] utf8 = json.getBytes(StandardCharsets.UTF_8);
        byte[] utf8WithByteOrderMark = new byte[utf8.length + 3];
        utf8WithByteOrderMark[0] = (byte) 0xEF;
        utf8WithByteOrderMark[1] = (byte) 0xBB;
        utf8WithByteOrderMark[2] = (byte) 0xBF;
        System.arraycopy(utf8, 0, utf8WithByteOrderMark, 3, utf8.length);
        return Stream.of(
                Arguments.of("UTF-8 with byte-order mark", utf8WithByteOrderMark),
                Arguments.of("UTF-16 with byte-order mark", json.getBytes(StandardCharsets.UTF_16)),
                Arguments.of("UTF-16BE", json.getBytes(StandardCharsets.UTF_16BE)),
                Arguments.of("UTF-16LE", json.getBytes(StandardCharsets.UTF_16LE)),
                Arguments.of("UTF-32BE", json.getBytes(Charset.forName("UTF-32BE"))),
                Arguments.of("UTF-32LE", json.getBytes(Charset.forName("UTF-32LE"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("encodedSamples")
    void sampleMessageEncodingIsDetectedFromTheBytes(String encoding, byte[] body) {
        assertThat(mapper.toXml(body)).isEqualTo(MESSAGE_XML);
    }

    static Stream<Arguments> orders() {
        return Stream.of(
                Arguments.of("decimal keeps its scale", "{\"p\":1.50}", order("\t<p>1.50</p>")),
                Arguments.of("decimal below one keeps its scale", "{\"p\":0.10}", order("\t<p>0.10</p>")),
                Arguments.of("negative decimal", "{\"p\":-2.5}", order("\t<p>-2.5</p>")),
                Arguments.of("integer", "{\"p\":7}", order("\t<p>7</p>")),
                Arguments.of("integer beyond long", "{\"p\":12345678901234567890}",
                        order("\t<p>12345678901234567890</p>")),
                Arguments.of("true", "{\"p\":true}", order("\t<p>true</p>")),
                Arguments.of("false", "{\"p\":false}", order("\t<p>false</p>")),
                Arguments.of("null is an empty element", "{\"x\":null}", order("\t<x/>")),
                Arguments.of("empty string has a start and an end tag", "{\"p\":\"\"}", order("\t<p></p>")),
                Arguments.of("nested objects one tab deeper per level",
                        "{\"address\":{\"city\":\"Buenos Aires\",\"geo\":{\"lat\":-34.60}}}",
                        order("\t<address>",
                                "\t\t<city>Buenos Aires</city>",
                                "\t\t<geo>",
                                "\t\t\t<lat>-34.60</lat>",
                                "\t\t</geo>",
                                "\t</address>")),
                Arguments.of("empty nested object is an empty element", "{\"address\":{}}",
                        order("\t<address/>")),
                Arguments.of("array repeats its key's element once per item", "{\"size\":[\"S\",\"M\"],\"count\":2}",
                        order("\t<size>S</size>", "\t<size>M</size>", "\t<count>2</count>")),
                Arguments.of("array of objects", "{\"item\":[{\"code\":1},{\"code\":2}]}",
                        order("\t<item>",
                                "\t\t<code>1</code>",
                                "\t</item>",
                                "\t<item>",
                                "\t\t<code>2</code>",
                                "\t</item>")),
                Arguments.of("nested array and null item repeat the same key", "{\"a\":[[1,2],null,3]}",
                        order("\t<a>1</a>", "\t<a>2</a>", "\t<a/>", "\t<a>3</a>")),
                Arguments.of("empty array writes no element", "{\"a\":[],\"b\":1}", order("\t<b>1</b>")),
                Arguments.of("repeated key written once per occurrence", "{\"size\":\"S\",\"size\":\"M\"}",
                        order("\t<size>S</size>", "\t<size>M</size>")),
                Arguments.of("members in body order, not the schema's", "{\"size\":\"L\",\"email\":\"a@b.c\"}",
                        order("\t<size>L</size>", "\t<email>a@b.c</email>")),
                Arguments.of("markup characters escaped, quotes kept", "{\"s\":\"a & b < c > d \\\" e ' f\"}",
                        order("\t<s>a &amp; b &lt; c &gt; d \" e ' f</s>")),
                Arguments.of("JSON escapes decoded, non-ASCII written as is", "{\"s\":\"\\u00d1and\\u00fa\\t\\/\"}",
                        order("\t<s>\u00d1and\u00fa\t/</s>")),
                Arguments.of("JSON whitespace around tokens ignored", " \r\n\t{ \"p\" : 1 }\n ",
                        order("\t<p>1</p>")),
                Arguments.of("empty object is the self-closed root", "{}", EMPTY_ORDER),
                Arguments.of("object holding only an empty array is the self-closed root", "{\"a\":[]}",
                        EMPTY_ORDER));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("orders")
    void jsonValuesAreWrittenAsTheScriptWroteThem(String description, String json, String expected) {
        assertThat(mapper.toXml(utf8(json))).isEqualTo(expected);
    }

    @Test
    void documentOfTheSampleIsOrderTshirtInTheNamespaceOverNineUnqualifiedChildren() throws IOException {
        Element root = mapper.toDocument(message()).getDocumentElement();

        assertThat(root.getNamespaceURI()).isEqualTo(TSHIRT_NAMESPACE);
        assertThat(root.getLocalName()).isEqualTo("OrderTshirt");
        assertThat(root.getPrefix()).isEqualTo("ns0");
        List<Map.Entry<String, String>> children = new ArrayList<>();
        for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                assertThat(child.getNamespaceURI()).as(child.getNodeName()).isNull();
                assertThat(child.getPrefix()).as(child.getNodeName()).isNull();
                children.add(Map.entry(child.getLocalName(), child.getTextContent()));
            }
        }
        assertThat(children).isEqualTo(MESSAGE_FIELDS);
        // The writer's layout is sent unchanged, so the whitespace between elements stays in the DOM.
        assertThat(root.getFirstChild().getNodeType()).isEqualTo(Node.TEXT_NODE);
        assertThat(root.getFirstChild().getNodeValue()).isEqualTo("\n\t");
        assertThat(root.getLastChild().getNodeValue()).isEqualTo("\n");
    }

    @Test
    void dataWeaveXmlReaderAgreesWithTheDocumentOnTheRootAndReadsBackTheJsonMembers() throws IOException {
        Element fromDocument = mapper.toDocument(message()).getDocumentElement();
        Document parsed = reader.parse(mapper.toXml(message()));

        assertThat(parsed.getDocumentElement().getNamespaceURI()).isEqualTo(fromDocument.getNamespaceURI());
        assertThat(parsed.getDocumentElement().getLocalName()).isEqualTo(fromDocument.getLocalName());
        assertThat(reader.select(parsed, OrderTshirtRequestMapper.NAMESPACE, OrderTshirtRequestMapper.ROOT))
                .isInstanceOfSatisfying(DwXmlToJson.DwObject.class,
                        order -> assertThat(List.copyOf(order.entrySet())).isEqualTo(MESSAGE_FIELDS));
    }

    @Test
    void escapedTextReadsBackFromTheDocumentAsTheJsonString() {
        Document document = mapper.toDocument(utf8("{\"s\":\"a & b < c > d \\\" e ' f \\u00d1and\\u00fa\"}"));

        Node s = document.getDocumentElement().getElementsByTagName("s").item(0);
        assertThat(s.getTextContent()).isEqualTo("a & b < c > d \" e ' f \u00d1and\u00fa");
    }

    @Test
    void valueTreeHandedToTheWriterKeepsRepeatedKeysAndTheParsedJavaTypes() {
        List<Object> rootNames = new ArrayList<>();
        List<Object> trees = new ArrayList<>();
        DwXmlWriter recordingWriter = new DwXmlWriter() {
            @Override
            public DwXmlWriter.Element fromObject(String prefix, String uri, String name, Object value) {
                rootNames.addAll(List.of(prefix, uri, name));
                trees.add(value);
                return super.fromObject(prefix, uri, name, value);
            }
        };
        String json = "{\"price\":1.50,\"count\":7,\"big\":12345678901234567890,\"gift\":true,\"note\":null,"
                + "\"size\":\"S\",\"tags\":[\"a\"],\"address\":{\"city\":\"X\"},\"size\":\"M\"}";

        new OrderTshirtRequestMapper(recordingWriter).toXml(utf8(json));

        assertThat(rootNames).containsExactly("ns0", TSHIRT_NAMESPACE, "OrderTshirt");
        assertThat(trees).singleElement().isInstanceOf(Map.class);
        Map<?, ?> order = (Map<?, ?>) trees.get(0);
        assertThat(order.entrySet().stream().map(entry -> String.valueOf(entry.getKey())).toList())
                .containsExactly("price", "count", "big", "gift", "note", "size", "tags", "address", "size");
        assertThat(order.size()).isEqualTo(9);
        assertThat(order.get("size")).isEqualTo("S");
        // BigDecimal.equals compares the scale, so 1.50 is not 1.5.
        assertThat(order.get("price")).isEqualTo(new BigDecimal("1.50"));
        assertThat(order.get("count")).isEqualTo(7);
        assertThat(order.get("big")).isEqualTo(new BigInteger("12345678901234567890"));
        assertThat(order.get("gift")).isEqualTo(Boolean.TRUE);
        assertThat(order.containsKey("note")).isTrue();
        assertThat(order.get("note")).isNull();
        assertThat(order.get("tags")).isEqualTo(List.of("a"));
        assertThat(order.get("address")).isInstanceOf(Map.class);
        assertThat(List.copyOf(((Map<?, ?>) order.get("address")).entrySet()))
                .isEqualTo(List.of(Map.entry("city", "X")));
        assertThatThrownBy(order::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    static Stream<Arguments> scalarBodies() {
        return Stream.of(
                Arguments.of("string", "\"order\"", rootWithText("order"), "order"),
                Arguments.of("integer", "42", rootWithText("42"), "42"),
                Arguments.of("decimal keeps its scale", "1.50", rootWithText("1.50"), "1.50"),
                Arguments.of("integer beyond long", "12345678901234567890", rootWithText("12345678901234567890"),
                        "12345678901234567890"),
                Arguments.of("true", "true", rootWithText("true"), "true"),
                Arguments.of("false", "false", rootWithText("false"), "false"),
                Arguments.of("empty string has a start and an end tag", "\"\"", rootWithText(""), ""),
                Arguments.of("markup character escaped", "\"a & b\"", rootWithText("a &amp; b"), "a & b"),
                Arguments.of("null is the self-closed root", "null", EMPTY_ORDER, ""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scalarBodies")
    void scalarOrNullBodyIsTheTextOfTheRootInBothConversions(String description, String json, String expectedXml,
            String expectedText) {
        assertThat(mapper.toXml(utf8(json))).isEqualTo(expectedXml);

        Element root = mapper.toDocument(utf8(json)).getDocumentElement();
        assertThat(root.getNamespaceURI()).isEqualTo(TSHIRT_NAMESPACE);
        assertThat(root.getLocalName()).isEqualTo("OrderTshirt");
        assertThat(root.getPrefix()).isEqualTo("ns0");
        assertThat(root.getElementsByTagName("*").getLength()).isZero();
        assertThat(root.getTextContent()).isEqualTo(expectedText);
    }

    static Stream<Arguments> rootArrays() {
        return Stream.of(
                Arguments.of("array of numbers", "[1,2]"),
                Arguments.of("empty array", "[]"),
                Arguments.of("array of an empty object", "[{}]"),
                Arguments.of("array of an object", "[{\"a\":1}]"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rootArrays")
    void rootArrayIsRejectedByTheWriterInBothConversions(String description, String json) {
        byte[] body = utf8(json);

        assertThatThrownBy(() -> mapper.toXml(body))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage(SINGLE_ROOT)
                .hasNoCause();
        assertThatThrownBy(() -> mapper.toDocument(body))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage(SINGLE_ROOT)
                .hasNoCause();
    }

    static Stream<Arguments> emptyBodies() {
        return Stream.of(
                Arguments.of("null", null),
                Arguments.of("no bytes", new byte[0]),
                Arguments.of("JSON whitespace only", utf8(" \t\r\n ")),
                Arguments.of("byte-order mark and whitespace", new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, ' '}),
                Arguments.of("UTF-16 whitespace", " \n".getBytes(StandardCharsets.UTF_16BE)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("emptyBodies")
    void emptyBodyIsRejectedByBothConversions(String description, byte[] body) {
        assertThatThrownBy(() -> mapper.toXml(body))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage(EMPTY_BODY)
                .hasNoCause();
        assertThatThrownBy(() -> mapper.toDocument(body))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage(EMPTY_BODY)
                .hasNoCause();
    }

    static Stream<Arguments> malformedBodies() {
        return Stream.of(
                Arguments.of("object cut off after a colon", "{\"a\":"),
                Arguments.of("object cut off after a member", "{\"a\":1"),
                Arguments.of("array cut off after a comma", "{\"a\":[1,"),
                Arguments.of("member without a colon", "{\"a\" 1}"),
                Arguments.of("unquoted key", "{a:1}"),
                Arguments.of("unknown literal", "{\"a\":tru}"),
                Arguments.of("trailing comma", "{\"a\":1,}"),
                Arguments.of("stray close bracket", "]"),
                Arguments.of("form feed is not JSON whitespace", "\f{}"),
                Arguments.of("text after the object", "{\"a\":1} x"),
                Arguments.of("second value after the object", "{\"a\":1}{\"b\":2}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedBodies")
    void malformedJsonIsRejectedByBothConversionsWithTheParserMessageAndCause(String description, String json) {
        byte[] body = utf8(json);

        for (Runnable conversion : List.<Runnable>of(() -> mapper.toXml(body), () -> mapper.toDocument(body))) {
            assertThatThrownBy(conversion::run)
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasCauseInstanceOf(JsonProcessingException.class)
                    .satisfies(e -> assertThat(e.getMessage()).isNotBlank().isEqualTo(e.getCause().getMessage()));
        }
    }

    @Test
    void secondValueAfterTheObjectIsNamedAsATrailingToken() {
        byte[] body = utf8("{\"a\":1}{\"b\":2}");

        assertThatThrownBy(() -> mapper.toXml(body))
                .hasMessageStartingWith("Trailing token (of type START_OBJECT) found after the JSON value");
        assertThatThrownBy(() -> mapper.toDocument(body))
                .hasMessageStartingWith("Trailing token (of type START_OBJECT) found after the JSON value");
    }

    @Test
    void parserMessageNamesThePositionWithoutQuotingTheBody() {
        byte[] body = utf8("{\"apiKey\":\"abc12345\",\"size\":");

        assertThatThrownBy(() -> mapper.toXml(body))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("line: 1, column: ")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("abc12345").doesNotContain("apiKey"));
    }

    @Test
    void controlCharacterInAValueIsRejectedByTheWriterInBothConversions() {
        byte[] body = utf8("{\"p\":\"a\\u0001b\"}");
        String invalid = "Invalid XML 1.0 character U+0001 in the text of element <p>";

        assertThatThrownBy(() -> mapper.toXml(body))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(invalid)
                .hasNoCause();
        assertThatThrownBy(() -> mapper.toDocument(body))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(invalid)
                .hasNoCause();
    }

    static Stream<Arguments> bodiesWhoseXmlIsNotWellFormed() {
        return Stream.of(
                Arguments.of("space in a key", "{\"a b\":1}"),
                Arguments.of("key starting with a digit", "{\"1st\":1}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bodiesWhoseXmlIsNotWellFormed")
    void writerOutputTheParserRejectsFailsTheDocumentWithTheParserDiagnostic(String description, String json) {
        assertThatThrownBy(() -> mapper.toDocument(utf8(json)))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("DataWeave XML output could not be parsed: ")
                .hasCauseInstanceOf(SAXParseException.class);
    }

    private static String order(String... childLines) {
        return DECLARATION_LINE + ROOT_START + String.join("\n", childLines) + "\n" + ROOT_END;
    }

    /** The root holding {@code escapedText} as its text, between its start and end tags on one line. */
    private static String rootWithText(String escapedText) {
        return DECLARATION_LINE + "<ns0:OrderTshirt xmlns:ns0=\"http://mulesoft.org/tshirt-service\">" + escapedText
                + ROOT_END;
    }

    private static byte[] utf8(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] message() throws IOException {
        try (InputStream in = OrderTshirtRequestMapperTest.class.getResourceAsStream("/message.json")) {
            assertThat(in).as("/message.json on the test classpath").isNotNull();
            return in.readAllBytes();
        }
    }
}
