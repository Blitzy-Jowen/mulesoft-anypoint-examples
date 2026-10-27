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

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The "JSON to XML" script of the Ajax {@code orderRequest} flow
 * ({@code service-orchestration-and-choice-routing/src/main/app/mule-config.xml}, header line 9), as
 * {@link AjaxOrderXmlMapper} reimplements it over the real SC-4 {@link DwXmlWriter}:
 * <pre>
 * order: { orderId: payload.orderId, customer: payload.customer, orderItems: payload.orderItems.item }
 * </pre>
 * Expected documents are spelled out with explicit {@code \t} and {@code \n}, so the assertions pin DataWeave's
 * tab layout, LF line ends and missing final line feed rather than whatever an editor indents with.
 */
class AjaxOrderXmlMapperTest {

    /** The Studio sample {@code src/test/resources/sample_data/json_1.json} of the original, compacted. */
    private static final String SAMPLE = """
            {"orderId":"123",\
            "customer":{"firstName":"Nial","lastName":"Darbey","address":"Buenos Aires"},\
            "orderItems":[\
            {"item":{"manufacturer":"Samsung","name":"Galaxy","productId":"i9000","quantity":"30"}},\
            {"item":{"manufacturer":"Philips","name":"P3123","productId":"23","quantity":"10"}}]}""";

    /** What the script wrote for {@link #SAMPLE}: one {@code orderItems} element per entry, no {@code item}. */
    private static final String SAMPLE_XML = "<?xml version='1.0' encoding='UTF-8'?>\n"
            + "<order>\n"
            + "\t<orderId>123</orderId>\n"
            + "\t<customer>\n"
            + "\t\t<firstName>Nial</firstName>\n"
            + "\t\t<lastName>Darbey</lastName>\n"
            + "\t\t<address>Buenos Aires</address>\n"
            + "\t</customer>\n"
            + "\t<orderItems>\n"
            + "\t\t<manufacturer>Samsung</manufacturer>\n"
            + "\t\t<name>Galaxy</name>\n"
            + "\t\t<productId>i9000</productId>\n"
            + "\t\t<quantity>30</quantity>\n"
            + "\t</orderItems>\n"
            + "\t<orderItems>\n"
            + "\t\t<manufacturer>Philips</manufacturer>\n"
            + "\t\t<name>P3123</name>\n"
            + "\t\t<productId>23</productId>\n"
            + "\t\t<quantity>10</quantity>\n"
            + "\t</orderItems>\n"
            + "</order>";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AjaxOrderXmlMapper mapper = new AjaxOrderXmlMapper(new DwXmlWriter(), objectMapper);

    @Test
    void writesTheStudioSampleExactly() {
        assertThat(mapper.toXml(SAMPLE)).isEqualTo(SAMPLE_XML);
    }

    @Test
    void startsWithTheDataWeaveDeclaration() {
        String xml = mapper.toXml(SAMPLE);

        assertThat(xml).startsWith(DwXmlWriter.DECLARATION + "\n<order>")
                .doesNotContain("\r")
                .doesNotContain("<item>")
                .doesNotEndWith("\n");
    }

    @Test
    void keepsCustomerKeyOrder() {
        String json = """
                {"orderId":"123",\
                "customer":{"address":"Buenos Aires","lastName":"Darbey","firstName":"Nial"},\
                "orderItems":[]}""";

        assertThat(mapper.toXml(json)).contains("\t<customer>\n"
                + "\t\t<address>Buenos Aires</address>\n"
                + "\t\t<lastName>Darbey</lastName>\n"
                + "\t\t<firstName>Nial</firstName>\n"
                + "\t</customer>\n");
    }

    @Test
    void oneOrderItemsElementPerEntry() {
        String json = """
                {"orderId":"7","customer":{"firstName":"Ann"},\
                "orderItems":[{"item":{"manufacturer":"Sony","name":"Bravia","productId":"s-1","quantity":"2"}}]}""";

        String xml = mapper.toXml(json);

        assertThat(occurrences(xml, "<orderItems>")).isEqualTo(1);
        assertThat(xml).contains("\t<orderItems>\n"
                + "\t\t<manufacturer>Sony</manufacturer>\n"
                + "\t\t<name>Bravia</name>\n"
                + "\t\t<productId>s-1</productId>\n"
                + "\t\t<quantity>2</quantity>\n"
                + "\t</orderItems>\n</order>");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("orderItemsSelections")
    void selectsOrderItemsAsTheScriptDid(String selection, String json, String expected) {
        assertThat(mapper.toXml(json)).isEqualTo(expected);
    }

    static Stream<Arguments> orderItemsSelections() {
        return Stream.of(
                Arguments.of("an object selects its single item",
                        "{\"orderId\":\"1\",\"customer\":{\"firstName\":\"Ann\"},"
                                + "\"orderItems\":{\"item\":{\"name\":\"Galaxy\",\"quantity\":\"3\"}}}",
                        document("<order>",
                                "\t<orderId>1</orderId>",
                                "\t<customer>",
                                "\t\t<firstName>Ann</firstName>",
                                "\t</customer>",
                                "\t<orderItems>",
                                "\t\t<name>Galaxy</name>",
                                "\t\t<quantity>3</quantity>",
                                "\t</orderItems>",
                                "</order>")),
                Arguments.of("array entries without an item member are skipped",
                        "{\"orderId\":\"1\",\"customer\":{\"firstName\":\"Ann\"},"
                                + "\"orderItems\":[{\"other\":\"x\"},5,null,{\"item\":{\"name\":\"P3123\"}}]}",
                        document("<order>",
                                "\t<orderId>1</orderId>",
                                "\t<customer>",
                                "\t\t<firstName>Ann</firstName>",
                                "\t</customer>",
                                "\t<orderItems>",
                                "\t\t<name>P3123</name>",
                                "\t</orderItems>",
                                "</order>")),
                Arguments.of("an empty array writes no orderItems element",
                        "{\"orderId\":\"1\",\"customer\":{\"firstName\":\"Ann\"},\"orderItems\":[]}",
                        document("<order>",
                                "\t<orderId>1</orderId>",
                                "\t<customer>",
                                "\t\t<firstName>Ann</firstName>",
                                "\t</customer>",
                                "</order>")),
                Arguments.of("a scalar selects nothing",
                        "{\"orderId\":\"1\",\"customer\":{\"firstName\":\"Ann\"},\"orderItems\":\"none\"}",
                        document("<order>",
                                "\t<orderId>1</orderId>",
                                "\t<customer>",
                                "\t\t<firstName>Ann</firstName>",
                                "\t</customer>",
                                "\t<orderItems/>",
                                "</order>")),
                Arguments.of("absent and null members are empty elements and other members are dropped",
                        "{\"status\":\"NEW\",\"orderId\":null}",
                        document("<order>",
                                "\t<orderId/>",
                                "\t<customer/>",
                                "\t<orderItems/>",
                                "</order>")));
    }

    @Test
    void writesValuesWithTheirLiteralDigitsAndEscapes() {
        String json = """
                {"orderId":12345678901234567890,\
                "customer":{"vip":true,"discount":1.50,"middleName":null,"tags":["a","b"],\
                "note":"A & B <c>","address":{"lines":[1,2]}},\
                "orderItems":[{"item":{"quantity":30,"price":19.90}}]}""";

        assertThat(mapper.toXml(json)).isEqualTo(document("<order>",
                "\t<orderId>12345678901234567890</orderId>",
                "\t<customer>",
                "\t\t<vip>true</vip>",
                "\t\t<discount>1.50</discount>",
                "\t\t<middleName/>",
                "\t\t<tags>a</tags>",
                "\t\t<tags>b</tags>",
                "\t\t<note>A &amp; B &lt;c&gt;</note>",
                "\t\t<address>",
                "\t\t\t<lines>1</lines>",
                "\t\t\t<lines>2</lines>",
                "\t\t</address>",
                "\t</customer>",
                "\t<orderItems>",
                "\t\t<quantity>30</quantity>",
                "\t\t<price>19.90</price>",
                "\t</orderItems>",
                "</order>"));
    }

    @Test
    void leavesTheApplicationObjectMapperUnchanged() {
        mapper.toXml(SAMPLE);

        assertThat(objectMapper.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)).isFalse();
        assertThat(objectMapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)).isFalse();
    }

    @Test
    void writesTheOrderRootThatXmlToSoapEnvelopeSelects() throws Exception {
        Document document = parse(mapper.toXml(SAMPLE));

        Element root = document.getDocumentElement();
        assertThat(root.getLocalName()).isEqualTo(AjaxOrderXmlMapper.ROOT);
        assertThat(root.getNamespaceURI()).isNull();
        assertThat(childNames(root)).containsExactly("orderId", "customer", "orderItems", "orderItems");
    }

    @Test
    void malformedJsonThrows() {
        assertThatThrownBy(() -> mapper.toXml("{\"orderId\":"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasCauseInstanceOf(JsonProcessingException.class);
    }

    @Test
    void contentAfterTheOrderThrows() {
        assertThatThrownBy(() -> mapper.toXml("{\"orderId\":\"1\"} {}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasCauseInstanceOf(JsonProcessingException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "\"x\"", "42", "null"})
    void nonObjectJsonThrows(String json) {
        assertThatThrownBy(() -> mapper.toXml(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Order request must be a JSON object, but was ");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t\n"})
    void emptyBodyThrows(String json) {
        assertThatThrownBy(() -> mapper.toXml(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Order request body is empty");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidMemberNames")
    void copiedMemberNameThatIsNotAnNcNameThrows(String name, String json) {
        assertThatThrownBy(() -> mapper.toXml(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Order request member name is not a valid XML element name");
    }

    static Stream<Arguments> invalidMemberNames() {
        return Stream.of(
                Arguments.of("markup that injects an element",
                        "{\"customer\":{\"x/><firstName>Injected</firstName><x\":null}}"),
                Arguments.of("an empty name", "{\"orderId\":\"1\",\"customer\":{\"\":\"x\"}}"),
                Arguments.of("a space in an object nested in customer", "{\"customer\":{\"address\":{\"a b\":\"x\"}}}"),
                Arguments.of("an object inside an array", "{\"customer\":{\"tags\":[{\"<x>\":1}]}}"),
                Arguments.of("a selected item of an orderItems array",
                        "{\"orderItems\":[{\"item\":{\"na me\":\"Galaxy\"}}]}"),
                Arguments.of("the item of a single orderItems object",
                        "{\"orderItems\":{\"item\":{\"na me\":\"Galaxy\"}}}"),
                Arguments.of("an object orderId", "{\"orderId\":{\"a/b\":\"1\"}}"),
                Arguments.of("a colon", "{\"customer\":{\"ns:foo\":\"x\"}}"),
                Arguments.of("a leading digit", "{\"customer\":{\"1abc\":\"x\"}}"),
                Arguments.of("a leading hyphen", "{\"customer\":{\"-x\":\"x\"}}"),
                Arguments.of("a leading full stop", "{\"customer\":{\".x\":\"x\"}}"),
                Arguments.of("an ampersand", "{\"customer\":{\"a&b\":\"x\"}}"),
                Arguments.of("a quotation mark", "{\"customer\":{\"a\\\"b\":\"x\"}}"),
                Arguments.of("a control character", "{\"customer\":{\"a\\u0001b\":\"x\"}}"),
                Arguments.of("a lone surrogate", "{\"customer\":{\"x\\ud800\":\"x\"}}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ncNameMemberNames")
    void writesEveryNcNameMemberNameAsItsElement(String name, String jsonName, String elementName) {
        String json = "{\"orderId\":\"1\",\"customer\":{\"" + jsonName + "\":\"v\"}}";

        assertThat(mapper.toXml(json)).isEqualTo(document("<order>",
                "\t<orderId>1</orderId>",
                "\t<customer>",
                "\t\t<" + elementName + ">v</" + elementName + ">",
                "\t</customer>",
                "\t<orderItems/>",
                "</order>"));
    }

    static Stream<Arguments> ncNameMemberNames() {
        return Stream.of(
                Arguments.of("Latin-1 letter", "ñame", "ñame"),
                Arguments.of("CJK letters", "名前", "名前"),
                Arguments.of("underscore start", "_x", "_x"),
                Arguments.of("hyphen, full stop and digit after the start", "a-b.c9", "a-b.c9"),
                Arguments.of("middle dot", "x\u00B7y", "x\u00B7y"),
                Arguments.of("combining mark after the start", "e\u0301", "e\u0301"),
                Arguments.of("supplementary-plane start", "\\ud800\\udc00x", "\ud800\udc00x"),
                Arguments.of("xml prefix", "xmlData", "xmlData"));
    }

    @Test
    void ignoresTheNamesOfDroppedMembers() {
        String json = "{\"bad name\":\"x\",\"orderId\":\"1\",\"customer\":{\"firstName\":\"Ann\"},"
                + "\"orderItems\":[{\"bad name\":\"skipped\"},{\"bad name\":1,\"item\":{\"name\":\"G\"}}]}";

        assertThat(mapper.toXml(json)).isEqualTo(document("<order>",
                "\t<orderId>1</orderId>",
                "\t<customer>",
                "\t\t<firstName>Ann</firstName>",
                "\t</customer>",
                "\t<orderItems>",
                "\t\t<name>G</name>",
                "\t</orderItems>",
                "</order>"));
    }

    /** The DataWeave declaration, a line feed, then the given lines joined by line feeds with none at the end. */
    private static String document(String... lines) {
        return DwXmlWriter.DECLARATION + "\n" + String.join("\n", lines);
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        for (int at = text.indexOf(token); at >= 0; at = text.indexOf(token, at + token.length())) {
            count++;
        }
        return count;
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private static List<String> childNames(Element parent) {
        List<String> names = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                names.add(child.getLocalName());
            }
        }
        return names;
    }
}
