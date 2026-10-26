/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.using_transactional_scope_in_jms_to_database.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.model.Order;

import jakarta.xml.bind.JAXBException;

/**
 * Unit test of {@link OrderXmlMapper}, the replacement of the flow's {@code mulexml:xml-to-object-transformer}
 * "Convert XML to Order" with alias {@code order} ({@code transactions.xml} lines 8-10).
 *
 * <p>The success case reads the committed {@code message.xml}, the message the original test sent to queue
 * {@code in}; its expected values are that document's children and the original {@code Order.toString()}.
 * Every rejected input must surface as {@link IllegalArgumentException} with the mapper's message prefix, so the
 * transacted listener rolls the message back as the platform did. Integer cases take their expected values from the
 * algorithm of XStream's {@code IntConverter} ({@link Long#decode(String)} and an {@code int} cast), or for a plain
 * decimal beyond its range from the low 32 bits the JAXB runtime keeps, never from hand-typed numbers.
 */
class OrderXmlMapperTest {

    private static final String PREFIX = "Failed to convert XML to Order: ";

    private final OrderXmlMapper mapper = new OrderXmlMapper();

    @Test
    void convertsClasspathMessageXml() throws IOException {
        String xml;
        try (InputStream in = new ClassPathResource("message.xml").getInputStream()) {
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        Order order = mapper.toOrder(xml);

        assertThat(order).isNotNull();
        assertThat(order.getItemId()).isEqualTo(1);
        assertThat(order.getItemUnits()).isEqualTo(2);
        assertThat(order.getCustomerId()).isEqualTo(1);
        assertThat(order.toString()).isEqualTo("Order [item_id=1, item_units=2, customer_id=1]");
    }

    static Stream<Arguments> documentsWithMissingChild() {
        return Stream.of(
                Arguments.of("<order><itemId>7</itemId><itemUnits>3</itemUnits></order>", 7, 3, 0),
                Arguments.of("<order><itemId>7</itemId><customerId>3</customerId></order>", 7, 0, 3));
    }

    @ParameterizedTest
    @MethodSource("documentsWithMissingChild")
    void missingChildDefaultsToZero(String xml, int itemId, int itemUnits, int customerId) {
        Order order = mapper.toOrder(xml);

        assertThat(order.getItemId()).isEqualTo(itemId);
        assertThat(order.getItemUnits()).isEqualTo(itemUnits);
        assertThat(order.getCustomerId()).isEqualTo(customerId);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void rejectsEmptyInput(String xml) {
        assertThatThrownBy(() -> mapper.toOrder(xml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(PREFIX);
    }

    static Stream<Arguments> invalidDocuments() {
        return Stream.of(
                // Rejected while the StAX reader is created, before unmarshalling starts.
                Arguments.of("XML declaration without version",
                        "<?xml encoding=\"UTF-8\"?><order><itemId>1</itemId><itemUnits>2</itemUnits>"
                                + "<customerId>1</customerId></order>"),
                Arguments.of("unclosed root", "<order><itemId>1</itemId>"),
                Arguments.of("mismatched end tag", "<order><itemId>1</order>"),
                Arguments.of("root other than order",
                        "<purchase><itemId>1</itemId><itemUnits>2</itemUnits><customerId>1</customerId></purchase>"),
                Arguments.of("unknown child element",
                        "<order><itemId>1</itemId><itemUnits>2</itemUnits><customerId>1</customerId>"
                                + "<discount>5</discount></order>"),
                Arguments.of("non-numeric value",
                        "<order><itemId>abc</itemId><itemUnits>2</itemUnits><customerId>1</customerId></order>"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidDocuments")
    void rejectsInvalidDocument(String description, String xml) {
        assertThatThrownBy(() -> mapper.toOrder(xml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(PREFIX)
                .cause().isNotNull();
    }

    @Test
    void rejectsExternalEntityWithoutLeakingContent(@TempDir Path tmp) throws IOException {
        String marker = "XXE-MARKER-" + UUID.randomUUID();
        Path markerFile = Files.writeString(tmp.resolve("secret.txt"), marker);
        String xml = "<!DOCTYPE order [<!ENTITY xxe SYSTEM \"" + markerFile.toUri() + "\">]>"
                + "<order><itemId>&xxe;</itemId><itemUnits>2</itemUnits><customerId>1</customerId></order>";

        Throwable thrown = catchThrowable(() -> mapper.toOrder(xml));

        assertThat(thrown)
                .as("an external entity must not be resolved into an Order")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(PREFIX);
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            assertThat(t.toString()).doesNotContain(marker);
            if (t.getMessage() != null) {
                assertThat(t.getMessage()).doesNotContain(marker);
            }
        }
    }

    static Stream<Arguments> integerContents() {
        return Stream.of(
                Arguments.of("explicit plus sign", "+5", "+5"),
                Arguments.of("negative zero", "-0", "-0"),
                Arguments.of("octal", "010", "010"),
                Arguments.of("hexadecimal", "0x1F", "0x1F"),
                Arguments.of("hash-prefixed hexadecimal", "#1f", "#1f"),
                Arguments.of("negative hexadecimal", "-0x10", "-0x10"),
                Arguments.of("hexadecimal above Integer.MAX_VALUE", "0xFFFFFFFF", "0xFFFFFFFF"),
                Arguments.of("decimal above Integer.MAX_VALUE", "4294967295", "4294967295"),
                Arguments.of("Arabic-Indic digits", "\u0661\u0662", "\u0661\u0662"),
                Arguments.of("text split by a comment", "1<!--c-->2", "12"),
                Arguments.of("CDATA section", "<![CDATA[7]]>", "7"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("integerContents")
    void convertsIntegerTextAsXStreamDid(String description, String content, String text) {
        // XStream IntConverter's conversion of the element text: Long.decode, then the int cast its range allows.
        int expected = (int) Long.decode(text).longValue();

        Order order = mapper.toOrder(orderWithEach(content));

        assertThat(order.getItemId()).isEqualTo(expected);
        assertThat(order.getItemUnits()).isEqualTo(expected);
        assertThat(order.getCustomerId()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"99999999999", "+99999999999", "-99999999999", "99999999999999999999"})
    void plainDecimalBeyondIntRangeWraps(String text) {
        // The JAXB runtime's conversion keeps the low 32 bits, so "99999999999" binds to (int) 99999999999L.
        int expected = new BigInteger(text).intValue();

        assertThat(mapper.toOrder(orderWith("customerId", text)).getCustomerId()).isEqualTo(expected);
    }

    static Stream<Arguments> nonIntegerTexts() {
        return Stream.of(
                Arguments.of("internal space", "itemId", "1 2"),
                Arguments.of("internal newline", "itemUnits", "1\n2"),
                Arguments.of("leading space", "customerId", " 1"),
                Arguments.of("trailing space", "itemId", "1 "),
                Arguments.of("minus between digits", "itemUnits", "1-2"),
                Arguments.of("plus between digits", "customerId", "1+2"),
                Arguments.of("double plus", "itemId", "++1"),
                Arguments.of("double minus", "itemUnits", "--1"),
                Arguments.of("plus then minus", "customerId", "+-1"),
                Arguments.of("plus alone", "itemId", "+"),
                Arguments.of("minus alone", "itemUnits", "-"),
                Arguments.of("empty element", "customerId", ""),
                Arguments.of("leading zero before a non-octal digit", "itemId", "08"),
                Arguments.of("hexadecimal prefix without digits", "itemUnits", "0x"),
                Arguments.of("hexadecimal beyond 32 bits", "customerId", "0x100000000"),
                Arguments.of("non-ASCII digits followed by a letter", "itemId", "\u0661\u0662x"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonIntegerTexts")
    void rejectsTextXStreamCouldNotConvert(String description, String element, String text) {
        Throwable thrown = catchThrowable(() -> mapper.toOrder(orderWith(element, text)));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(PREFIX)
                .hasMessage(PREFIX + "element " + element + " does not hold an integer")
                .hasRootCauseInstanceOf(NumberFormatException.class);
        if (!text.isEmpty()) {
            assertThat(thrown.getMessage()).as("the element text is not echoed").doesNotContain(text);
        }
    }

    @Test
    void rejectionChainsUnmarshalStreamAndNumberFormatExceptions() {
        Throwable thrown = catchThrowable(() -> mapper.toOrder(orderWith("customerId", "1 2")));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(PREFIX + "element customerId does not hold an integer");
        assertThat(thrown.getCause()).isInstanceOf(JAXBException.class);
        assertThat(thrown.getCause().getCause())
                .isInstanceOf(XMLStreamException.class)
                .hasMessage("element customerId does not hold an integer")
                .cause().isInstanceOf(NumberFormatException.class);
    }

    @Test
    void rejectsElementInsideIntegerChild() {
        assertThatThrownBy(() -> mapper.toOrder(orderWith("itemId", "<value>1</value>")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(PREFIX)
                .cause().isNotNull();
    }

    @Test
    void convertsDocumentWithDeclarationAndProlog() {
        Order order = mapper.toOrder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!--order--><?note x?>\n"
                + orderWith("itemUnits", "0x2"));

        assertThat(order.toString())
                .isEqualTo("Order [item_id=1, item_units=" + xstreamIntText("0x2") + ", customer_id=1]");
    }

    @Test
    void readerReportsConvertedTextAsOneCharactersEvent() throws XMLStreamException {
        String converted = xstreamIntText("0x1F");
        XMLStreamReader plain = XMLInputFactory.newDefaultFactory()
                .createXMLStreamReader(new StringReader("<order><itemId>0x1F</itemId></order>"));
        plain.nextTag();
        OrderXmlMapper.IntegerTextReader reader = new OrderXmlMapper.IntegerTextReader(plain);

        assertThat(reader.next()).isEqualTo(XMLStreamConstants.START_ELEMENT);
        assertThat(reader.next()).isEqualTo(XMLStreamConstants.CHARACTERS);

        assertThat(reader.getEventType()).isEqualTo(XMLStreamConstants.CHARACTERS);
        assertThat(reader.isCharacters()).isTrue();
        assertThat(reader.isStartElement()).isFalse();
        assertThat(reader.isEndElement()).isFalse();
        assertThat(reader.hasText()).isTrue();
        assertThat(reader.hasName()).isFalse();
        assertThat(reader.isWhiteSpace()).isFalse();
        assertThat(reader.getText()).isEqualTo(converted);
        assertThat(new String(reader.getTextCharacters(), reader.getTextStart(), reader.getTextLength()))
                .isEqualTo(converted);
        char[] target = new char[4];
        assertThat(reader.getTextCharacters(0, target, 1, 3)).isEqualTo(converted.length());
        assertThat(target).containsExactly('\0', converted.charAt(0), converted.charAt(1), '\0');
        assertThat(reader.getTextCharacters(1, target, 0, 1)).isEqualTo(1);
        assertThat(target[0]).isEqualTo(converted.charAt(1));
        assertThat(reader.getTextCharacters(5, target, 0, 4)).isZero();
        assertThatThrownBy(() -> reader.getTextCharacters(-1, target, 0, 1))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> reader.getTextCharacters(0, target, 3, 2))
                .isInstanceOf(IndexOutOfBoundsException.class);

        assertThat(reader.next()).isEqualTo(XMLStreamConstants.END_ELEMENT);
        assertThat(reader.isEndElement()).isTrue();
        assertThat(reader.getLocalName()).isEqualTo("itemId");
        assertThat(reader.next()).isEqualTo(XMLStreamConstants.END_ELEMENT);
        assertThat(reader.getLocalName()).isEqualTo("order");
    }

    @Test
    void readerElementTextAndNextTagGoThroughConversion() throws XMLStreamException {
        OrderXmlMapper.IntegerTextReader reader = readerOf("<?xml version=\"1.0\"?><!--c--><?pi x?>\n<order>\n"
                + " <itemId>010</itemId>\n <itemUnits>1 2</itemUnits>\n <customerId>5</customerId>\n</order>");

        assertThat(reader.nextTag()).isEqualTo(XMLStreamConstants.START_ELEMENT);
        assertThat(reader.getLocalName()).isEqualTo("order");
        assertThat(reader.nextTag()).isEqualTo(XMLStreamConstants.START_ELEMENT);
        assertThat(reader.getElementText()).isEqualTo(xstreamIntText("010"));
        assertThat(reader.isEndElement()).isTrue();
        assertThat(reader.nextTag()).isEqualTo(XMLStreamConstants.START_ELEMENT);
        assertThatThrownBy(reader::getElementText)
                .isInstanceOf(XMLStreamException.class)
                .hasMessage("element itemUnits does not hold an integer")
                .hasCauseInstanceOf(NumberFormatException.class);
        assertThat(reader.isEndElement()).isTrue();
        assertThat(reader.nextTag()).isEqualTo(XMLStreamConstants.START_ELEMENT);
        assertThat(reader.getLocalName()).isEqualTo("customerId");
        assertThatThrownBy(reader::nextTag)
                .as("the converted text is not whitespace")
                .isInstanceOf(XMLStreamException.class)
                .hasMessageContaining("expected start or end tag");
        assertThat(reader.nextTag()).isEqualTo(XMLStreamConstants.END_ELEMENT);
        assertThat(reader.getLocalName()).isEqualTo("customerId");
        assertThat(reader.nextTag()).isEqualTo(XMLStreamConstants.END_ELEMENT);
        assertThat(reader.getLocalName()).isEqualTo("order");
    }

    @Test
    void readerPassesOtherTextThrough() throws XMLStreamException {
        OrderXmlMapper.IntegerTextReader reader = readerOf("<order><note>a<!--c-->b<?pi x?>c</note>"
                + "<itemId xmlns=\"urn:other\">1 2</itemId><wrapper><itemId> 3 </itemId></wrapper></order>");

        reader.nextTag();
        reader.nextTag();
        assertThat(reader.getElementText()).isEqualTo("abc");
        reader.nextTag();
        assertThat(reader.getElementText()).as("an itemId in another namespace").isEqualTo("1 2");
        reader.nextTag();
        reader.nextTag();
        assertThat(reader.next()).as("an itemId below a direct child").isEqualTo(XMLStreamConstants.CHARACTERS);
        assertThat(reader.isCharacters()).isTrue();
        assertThat(reader.hasText()).isTrue();
        assertThat(reader.hasName()).isFalse();
        assertThat(reader.isWhiteSpace()).isFalse();
        assertThat(reader.getText()).isEqualTo(" 3 ");
        assertThat(new String(reader.getTextCharacters(), reader.getTextStart(), reader.getTextLength()))
                .isEqualTo(" 3 ");
        char[] target = new char[3];
        assertThat(reader.getTextCharacters(0, target, 0, 3)).isEqualTo(3);
        assertThat(target).containsExactly(' ', '3', ' ');
        assertThat(reader.next()).isEqualTo(XMLStreamConstants.END_ELEMENT);
        assertThat(reader.nextTag()).isEqualTo(XMLStreamConstants.END_ELEMENT);
        assertThat(reader.getLocalName()).isEqualTo("wrapper");
        assertThat(reader.nextTag()).isEqualTo(XMLStreamConstants.END_ELEMENT);
        assertThat(reader.getLocalName()).isEqualTo("order");
    }

    @Test
    void readerRejectsElementTextOutsideTextOnlyElement() throws XMLStreamException {
        OrderXmlMapper.IntegerTextReader reader = readerOf("<order><note>text</note></order>");

        assertThatThrownBy(reader::getElementText)
                .isInstanceOf(XMLStreamException.class)
                .hasMessageContaining("must be on START_ELEMENT");
        reader.nextTag();
        assertThatThrownBy(reader::getElementText)
                .isInstanceOf(XMLStreamException.class)
                .hasMessageContaining("may not contain event type " + XMLStreamConstants.START_ELEMENT);
        assertThatThrownBy(reader::nextTag)
                .isInstanceOf(XMLStreamException.class)
                .hasMessageContaining("expected start or end tag");
    }

    /** An order whose three children each hold the given content. */
    private static String orderWithEach(String content) {
        return "<order><itemId>" + content + "</itemId><itemUnits>" + content + "</itemUnits><customerId>"
                + content + "</customerId></order>";
    }

    /** An order whose named child holds the given content and whose other two children hold {@code 1}. */
    private static String orderWith(String element, String content) {
        StringBuilder xml = new StringBuilder("<order>");
        for (String name : new String[] {"itemId", "itemUnits", "customerId"}) {
            xml.append('<').append(name).append('>').append(name.equals(element) ? content : "1")
                    .append("</").append(name).append('>');
        }
        return xml.append("</order>").toString();
    }

    /** XStream {@code IntConverter}'s conversion of an integer element's text, as the decimal the mapper presents. */
    private static String xstreamIntText(String text) {
        return Integer.toString((int) Long.decode(text).longValue());
    }

    private static OrderXmlMapper.IntegerTextReader readerOf(String xml) throws XMLStreamException {
        return new OrderXmlMapper.IntegerTextReader(
                XMLInputFactory.newDefaultFactory().createXMLStreamReader(new StringReader(xml)));
    }
}
