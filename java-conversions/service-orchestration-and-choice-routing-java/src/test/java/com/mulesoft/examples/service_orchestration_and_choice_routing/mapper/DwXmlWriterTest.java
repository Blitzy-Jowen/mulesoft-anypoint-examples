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
import static org.assertj.core.api.Assertions.entry;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Byte contract of {@link DwXmlWriter}: the single-quoted declaration, a line feed, then the root element in
 * tab layout with LF line ends, and nothing after the root end tag.
 *
 * <p>The terminal line feed policy is asserted exactly: the employees document of
 * {@code upload-to-ftp-after-converting-json-to-xml/src/test/resources/reply.xml}, built through the
 * {@link DwXmlWriter.Element} API and from a value tree, is the 633 characters before that file's final line
 * feed. The committed file is 634 bytes because it was edited by hand, and its original test compares with all
 * whitespace removed, so its final line feed is not writer output. The DataWeave-produced
 * {@code service-orchestration-and-choice-routing/src/test/resources/sample_data/summary_1.xml} (490 bytes,
 * ending at {@code </summary>}) and the options payload of {@code salesforce-data-retrieval}, whose
 * {@code </div>} the recorded page follows directly with its template's next line, end the same way.
 *
 * <p>Expected documents are spelled out with explicit {@code \n} and {@code \t}; no file or classpath resource
 * is read, so the class is copied with {@link DwXmlWriter} changing only the package line.
 */
class DwXmlWriterTest {

    private static final String DECLARATION_LINE = "<?xml version='1.0' encoding='UTF-8'?>\n";

    /**
     * The element and text tree of the upload-to-ftp {@code reply.xml}: its first 633 bytes, which end at
     * {@code </employees>}.
     */
    private static final String EMPLOYEES_DOCUMENT = DECLARATION_LINE
            + "<employees>\n"
            + "\t<employee>\n"
            + "\t\t<name>John</name>\n"
            + "\t\t<lastName>Doe</lastName>\n"
            + "\t\t<addresses>\n"
            + "\t\t\t<address>\n"
            + "\t\t\t\t<street>123 MainStreet</street>\n"
            + "\t\t\t\t<zipCode>111</zipCode>\n"
            + "\t\t\t</address>\n"
            + "\t\t\t<address>\n"
            + "\t\t\t\t<street>987 CypressAvenue</street>\n"
            + "\t\t\t\t<zipCode>222</zipCode>\n"
            + "\t\t\t</address>\n"
            + "\t\t</addresses>\n"
            + "\t</employee>\n"
            + "\t<employee>\n"
            + "\t\t<name>Jane</name>\n"
            + "\t\t<lastName>Doe</lastName>\n"
            + "\t\t<addresses>\n"
            + "\t\t\t<address>\n"
            + "\t\t\t\t<street>345 MainStreet</street>\n"
            + "\t\t\t\t<zipCode>111</zipCode>\n"
            + "\t\t\t</address>\n"
            + "\t\t\t<address>\n"
            + "\t\t\t\t<street>654 SunsetBoulevard</street>\n"
            + "\t\t\t\t<zipCode>333</zipCode>\n"
            + "\t\t\t</address>\n"
            + "\t\t</addresses>\n"
            + "\t</employee>\n"
            + "</employees>";

    /** The DataWeave-produced {@code summary_1.xml} of service-orchestration-and-choice-routing, all 490 bytes. */
    private static final String SUMMARY_DOCUMENT = DECLARATION_LINE
            + "<summary>\n"
            + "\t<orderId>12</orderId>\n"
            + "\t<customer>\n"
            + "\t\t<address>Main Street 123</address>\n"
            + "\t\t<firstName>John</firstName>\n"
            + "\t\t<lastName>Doe</lastName>\n"
            + "\t</customer>\n"
            + "\t<orderItems>\n"
            + "\t\t<item>\n"
            + "\t\t\t<manufacturer>Samsung</manufacturer>\n"
            + "\t\t\t<name>s-1</name>\n"
            + "\t\t\t<productId>AX02</productId>\n"
            + "\t\t\t<purchaseReceipt>\n"
            + "\t\t\t\t<id>1</id>\n"
            + "\t\t\t\t<status>ACCEPTED</status>\n"
            + "\t\t\t\t<totalPrice>2550.0</totalPrice>\n"
            + "\t\t\t</purchaseReceipt>\n"
            + "\t\t\t<quantity>1</quantity>\n"
            + "\t\t</item>\n"
            + "\t</orderItems>\n"
            + "</summary>";

    /** The {@code div}/{@code option} shape salesforce-data-retrieval's script writes into its page. */
    private static final String OPTIONS_DOCUMENT = DECLARATION_LINE
            + "<div>\n"
            + "\t<option value=\"AcceptedEventRelation\">Accepted Event Relation</option>\n"
            + "\t<option value=\"Account\">Account</option>\n"
            + "\t<option value=\"WebLink\">Custom Button or Link</option>\n"
            + "</div>";

    /** The {@code div} shape with a single {@code option}. */
    private static final String SINGLE_OPTION_DOCUMENT = DwXmlWriter.DECLARATION + "\n"
            + "<div>\n"
            + "\t<option value=\"Account\">Account</option>\n"
            + "</div>";

    private static final String TSHIRT_NAMESPACE = "http://mulesoft.org/tshirt-service";

    /** The {@code ns0#OrderTshirt} request shape web-service-consumer's request script writes. */
    private static final String ORDER_TSHIRT_DOCUMENT = DwXmlWriter.DECLARATION + "\n"
            + "<ns0:OrderTshirt xmlns:ns0=\"http://mulesoft.org/tshirt-service\">\n"
            + "\t<email>a@b.c</email>\n"
            + "\t<size>L</size>\n"
            + "</ns0:OrderTshirt>";

    /** A table row of repeated {@code td} siblings. */
    private static final String ROW_DOCUMENT = DwXmlWriter.DECLARATION + "\n"
            + "<tr>\n"
            + "\t<td>a</td>\n"
            + "\t<td>b</td>\n"
            + "\t<td>c</td>\n"
            + "</tr>";

    /** A ns0 subtree that inherits its root's binding beside a ns1 subtree that declares its own once. */
    private static final String INHERITED_PREFIX_DOCUMENT = DwXmlWriter.DECLARATION + "\n"
            + "<ns0:ListInventoryResponse xmlns:ns0=\"http://mulesoft.org/tshirt-service\">\n"
            + "\t<ns0:inventory>\n"
            + "\t\t<ns0:productCode>4102</ns0:productCode>\n"
            + "\t</ns0:inventory>\n"
            + "\t<ns1:audit xmlns:ns1=\"urn:other\">\n"
            + "\t\t<ns1:entry>x</ns1:entry>\n"
            + "\t</ns1:audit>\n"
            + "</ns0:ListInventoryResponse>";

    private final DwXmlWriter writer = new DwXmlWriter();

    @Test
    void declarationIsSingleQuotedVersionOneUtf8() {
        assertThat(DwXmlWriter.DECLARATION).isEqualTo("<?xml version='1.0' encoding='UTF-8'?>");
        assertThat(DECLARATION_LINE).isEqualTo(DwXmlWriter.DECLARATION + "\n");
    }

    @Test
    void employeesDocumentEndsAtRootEndTagWithoutTerminalLineFeed() {
        assertThat(EMPLOYEES_DOCUMENT.length()).isEqualTo(633);

        String output = writer.write(employeesElement());

        assertThat(output).isEqualTo(EMPLOYEES_DOCUMENT);
        assertThat(output).hasSize(633).startsWith(DwXmlWriter.DECLARATION + "\n").endsWith("</employees>");
        assertThat(output).doesNotEndWith("\n");
        assertThat(lineFeeds(output)).isEqualTo(30);
    }

    @Test
    void employeesValueTreeEndsAtRootEndTagWithoutTerminalLineFeed() {
        assertThat(EMPLOYEES_DOCUMENT.length()).isEqualTo(633);

        String output = writer.write(writer.fromObject("employees", employeesValue()));

        assertThat(output).isEqualTo(EMPLOYEES_DOCUMENT);
        assertThat(output).doesNotEndWith("\n");
    }

    @Test
    void employeesDocumentBytesAreUtf8WithoutTerminalLineFeed() {
        byte[] bytes = writer.writeBytes(employeesElement());

        assertThat(bytes).hasSize(633).isEqualTo(EMPLOYEES_DOCUMENT.getBytes(StandardCharsets.UTF_8));
        assertThat(bytes[bytes.length - 1]).isEqualTo((byte) '>');
        assertThat(writer.writeBytes(writer.fromObject("employees", employeesValue()))).isEqualTo(bytes);
    }

    @Test
    void committedReplyXmlFinalLineFeedIsNotWriterOutput() {
        String committedReplyXml = EMPLOYEES_DOCUMENT + "\n";
        String output = writer.write(employeesElement());

        assertThat(committedReplyXml.length()).isEqualTo(634);
        assertThat(lineFeeds(committedReplyXml)).isEqualTo(31);
        assertThat(output).isNotEqualTo(committedReplyXml);
        assertThat(withoutWhitespace(output)).isEqualTo(withoutWhitespace(committedReplyXml));
    }

    @Test
    void summaryDocumentEndsAtRootEndTagWithoutTerminalLineFeed() {
        assertThat(SUMMARY_DOCUMENT.length()).isEqualTo(490);

        String output = writer.write(writer.fromObject("summary", summaryValue()));

        assertThat(output).isEqualTo(SUMMARY_DOCUMENT);
        assertThat(output).endsWith("</summary>").doesNotEndWith("\n");
    }

    @Test
    void salesforceOptionsDocumentEndsAtDivEndTagWithoutTerminalLineFeed() {
        String output = writer.write(optionsElement());

        assertThat(output).isEqualTo(OPTIONS_DOCUMENT);
        assertThat(output).endsWith("</div>").doesNotEndWith("\n");

        // The page template's payload line, as salesforce-data-retrieval renders it: the line after </div> is the
        // template's own </select> line, with no empty line between.
        String template = "\t\t\tObject: <select name=\"object\" >\n\t\t\t\t#[message.payload]\n\t\t\t</select>\n";
        String page = template.replace("#[message.payload]", output);
        assertThat(page).contains("</div>\n\t\t\t</select>\n").doesNotContain("</div>\n\n");
    }

    @Test
    void singleOptionWritesItsAttributeAndTextOnOneIndentedLine() {
        assertThat(writer.write(singleOptionElement())).isEqualTo(SINGLE_OPTION_DOCUMENT);
    }

    @Test
    void prefixedValueTreeRootDeclaresItsNamespaceOnceAndLeavesChildrenUnprefixed() {
        String output = writer.write(orderTshirtElement());

        assertThat(output).isEqualTo(ORDER_TSHIRT_DOCUMENT);
        assertThat(occurrences(output, "xmlns:ns0")).isEqualTo(1);
        assertThat(output).doesNotContain("<ns0:email", "<ns0:size");
    }

    @Test
    void repeatedSiblingsWriteOneLineEachFromElementsAndFromAValueList() {
        assertThat(writer.write(rowElement())).isEqualTo(ROW_DOCUMENT);
        assertThat(writer.write(rowValueTree())).isEqualTo(ROW_DOCUMENT);
    }

    @Test
    void scalarRootValuesRenderTheirDecimalOrBooleanText() {
        Object[][] cases = {
            {"p", new BigDecimal("1.50"), "<p>1.50</p>"},
            {"b", Boolean.TRUE, "<b>true</b>"},
            {"i", Integer.valueOf(7), "<i>7</i>"},
            {"l", Long.valueOf(9876543210L), "<l>9876543210</l>"}
        };
        for (Object[] scalar : cases) {
            assertThat(writer.write(writer.fromObject((String) scalar[0], scalar[1])))
                    .as("%s %s", scalar[1].getClass().getSimpleName(), scalar[1])
                    .isEqualTo(DwXmlWriter.DECLARATION + "\n" + scalar[2]);
        }
    }

    @Test
    void prefixBoundByAnAncestorIsNotDeclaredAgainAndASiblingPrefixIsDeclaredOnItsFirstElementOnly() {
        String output = writer.write(inheritedPrefixElement());

        assertThat(output).isEqualTo(INHERITED_PREFIX_DOCUMENT);
        assertThat(occurrences(output, "xmlns:ns0")).isEqualTo(1);
        assertThat(occurrences(output, "xmlns:ns1")).isEqualTo(1);
        assertThat(output).contains("\t<ns1:audit xmlns:ns1=\"urn:other\">\n\t\t<ns1:entry>x</ns1:entry>\n");
    }

    @Test
    void nonAsciiTextIsWrittenAsUtf8BytesAndNeverAsCharacterReferences() {
        DwXmlWriter.Element description = nonAsciiElement();

        String output = writer.write(description);
        byte[] bytes = writer.writeBytes(description);

        assertThat(output).isEqualTo(DwXmlWriter.DECLARATION + "\n<description>\u00d1and\u00fa</description>");
        assertThat(output).doesNotContain("&#");
        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo(output).doesNotContain("&#");
        // N-tilde is C3 91 and u-acute C3 BA in UTF-8, so each adds one byte to the character count.
        assertThat(bytes).containsSequence(
                (byte) 0xC3, (byte) 0x91, (byte) 'a', (byte) 'n', (byte) 'd', (byte) 0xC3, (byte) 0xBA);
        assertThat(bytes).hasSize(output.length() + 2);
    }

    @Test
    void everyWrittenDocumentHasLfLinesTabIndentationAndNoTerminalLineFeed() {
        List<String> documents = List.of(
                writer.write(employeesElement()),
                writer.write(writer.fromObject("employees", employeesValue())),
                writer.write(writer.fromObject("summary", summaryValue())),
                writer.write(optionsElement()),
                writer.write(singleOptionElement()),
                writer.write(orderTshirtElement()),
                writer.write(rowElement()),
                writer.write(rowValueTree()),
                writer.write(inheritedPrefixElement()),
                writer.write(nonAsciiElement()));

        for (String document : documents) {
            assertThat(document).doesNotEndWith("\n").doesNotContain("\r");
            String[] lines = document.split("\n", -1);
            assertThat(lines.length).as("line count of %s", document).isGreaterThan(1);
            assertThat(lines[0]).isEqualTo(DwXmlWriter.DECLARATION);
            for (int i = 1; i < lines.length; i++) {
                assertThat(lines[i]).as("line %d of %s", i + 1, document).matches("^\\t*<.*");
            }
        }
    }

    @Test
    void leafElementsWriteTextEmptyTextAndNoContent() {
        DwXmlWriter.Element root = DwXmlWriter.element("leaves").children(
                DwXmlWriter.element("text").text("value"),
                DwXmlWriter.element("emptyText").text(""),
                DwXmlWriter.element("noContent"),
                DwXmlWriter.element("cleared").text("gone").text(null),
                DwXmlWriter.element("decimal").text(new BigDecimal("27.60")),
                DwXmlWriter.element("exponent").text(new BigDecimal("1E+3")),
                DwXmlWriter.element("integer").text(4),
                DwXmlWriter.element("double").text(2550.0),
                DwXmlWriter.element("bool").text(Boolean.TRUE));

        assertThat(writer.write(root)).isEqualTo(DECLARATION_LINE
                + "<leaves>\n"
                + "\t<text>value</text>\n"
                + "\t<emptyText></emptyText>\n"
                + "\t<noContent/>\n"
                + "\t<cleared/>\n"
                + "\t<decimal>27.60</decimal>\n"
                + "\t<exponent>1000</exponent>\n"
                + "\t<integer>4</integer>\n"
                + "\t<double>2550.0</double>\n"
                + "\t<bool>true</bool>\n"
                + "</leaves>");
        assertThat(writer.write(DwXmlWriter.element("root"))).isEqualTo(DECLARATION_LINE + "<root/>");
        assertThat(writer.write(DwXmlWriter.element("root").text(""))).isEqualTo(DECLARATION_LINE + "<root></root>");
    }

    @Test
    void markupCharactersAreEscapedAndNonAsciiIsWrittenAsUtf8() {
        String raw = "a & b < c > d \"q\" 'p'";
        DwXmlWriter.Element root = DwXmlWriter.element("r").children(
                DwXmlWriter.element("text").text(raw),
                DwXmlWriter.element("attr").attribute("v", raw),
                DwXmlWriter.element("city").text("Z\u00fcrich \u20ac"));

        String expected = DECLARATION_LINE
                + "<r>\n"
                + "\t<text>a &amp; b &lt; c &gt; d \"q\" 'p'</text>\n"
                + "\t<attr v=\"a &amp; b &lt; c &gt; d &quot;q&quot; 'p'\"/>\n"
                + "\t<city>Z\u00fcrich \u20ac</city>\n"
                + "</r>";
        assertThat(writer.write(root)).isEqualTo(expected);

        byte[] bytes = writer.writeBytes(root);
        assertThat(bytes).isEqualTo(expected.getBytes(StandardCharsets.UTF_8));
        // u-umlaut takes two bytes and the euro sign three in UTF-8.
        assertThat(bytes).hasSize(expected.length() + 1 + 2);
    }

    /**
     * Values holding a character the XML 1.0 {@code Char} production excludes, with the code point the writer
     * reports: C0 controls other than tab, line feed and carriage return, unpaired surrogates, U+FFFE and U+FFFF.
     * A value holding several is reported at its first.
     */
    static Stream<Arguments> charactersXmlForbids() {
        return Stream.of(
                Arguments.of("NUL", "\u0000", "U+0000"),
                Arguments.of("U+0001 between letters", "a\u0001b", "U+0001"),
                Arguments.of("backspace", "\b", "U+0008"),
                Arguments.of("vertical tab", "\u000b", "U+000B"),
                Arguments.of("form feed", "\f", "U+000C"),
                Arguments.of("shift out", "\u000e", "U+000E"),
                Arguments.of("unit separator, the last C0 control", "\u001f", "U+001F"),
                Arguments.of("lone high surrogate ending the value", "\ud800", "U+D800"),
                Arguments.of("high surrogate before a letter", "x\ud800y", "U+D800"),
                Arguments.of("lone low surrogate", "\udc00", "U+DC00"),
                Arguments.of("low surrogate before a high one", "\udc00\ud800", "U+DC00"),
                Arguments.of("U+FFFE", "\ufffe", "U+FFFE"),
                Arguments.of("U+FFFF", "\uffff", "U+FFFF"),
                Arguments.of("the CSV name of the QA report, two controls", "bad\u0001char\u000b", "U+0001"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("charactersXmlForbids")
    void characterXmlForbidsFailsTheWriteInTextAttributeAndNamespaceUri(String description, String value,
            String codePoint) {
        String invalid = "Invalid XML 1.0 character " + codePoint + " in ";
        List<Map.Entry<DwXmlWriter.Element, String>> placements = List.of(
                Map.entry(singleOrder(value), invalid + "the text of element <name>"),
                Map.entry(DwXmlWriter.element("r").child(DwXmlWriter.element("p", "urn:p", "e").attribute("v", value)),
                        invalid + "attribute 'v' of element <p:e>"),
                Map.entry(DwXmlWriter.element("r").child(DwXmlWriter.element("p", value, "e")),
                        invalid + "the namespace URI of xmlns:p on element <p:e>"),
                Map.entry(DwXmlWriter.element("r").child(DwXmlWriter.element(null, value, "e")),
                        invalid + "the namespace URI of xmlns on element <e>"));

        for (Map.Entry<DwXmlWriter.Element, String> placement : placements) {
            assertThatThrownBy(() -> writer.write(placement.getKey()))
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage(placement.getValue())
                    .hasNoCause();
            assertThatThrownBy(() -> writer.writeBytes(placement.getKey()))
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage(placement.getValue())
                    .hasNoCause();
        }
    }

    /**
     * The edges of the XML 1.0 {@code Char} production with their UTF-8 encodings: tab, line feed, carriage
     * return, U+0020, U+D7FF, U+E000, U+FFFD, and the first and last supplementary characters as surrogate pairs.
     */
    static Stream<Arguments> boundaryCharactersXmlAllows() {
        return Stream.of(
                Arguments.of("tab", "\t", bytes(0x09)),
                Arguments.of("line feed", "\n", bytes(0x0A)),
                Arguments.of("carriage return", "\r", bytes(0x0D)),
                Arguments.of("space, U+0020", " ", bytes(0x20)),
                Arguments.of("U+D7FF, below the surrogates", "\ud7ff", bytes(0xED, 0x9F, 0xBF)),
                Arguments.of("U+E000, above the surrogates", "\ue000", bytes(0xEE, 0x80, 0x80)),
                Arguments.of("U+FFFD, below U+FFFE", "\ufffd", bytes(0xEF, 0xBF, 0xBD)),
                Arguments.of("U+10000 as a surrogate pair", "\ud800\udc00", bytes(0xF0, 0x90, 0x80, 0x80)),
                Arguments.of("U+10FFFF as a surrogate pair", "\udbff\udfff", bytes(0xF4, 0x8F, 0xBF, 0xBF)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("boundaryCharactersXmlAllows")
    void boundaryCharacterXmlAllowsIsWrittenUnchangedBesideEscapedMarkup(String description, String character,
            byte[] utf8) {
        String value = "a" + character + "&<>\"" + character + "z";
        DwXmlWriter.Element root = DwXmlWriter.element("r").children(
                DwXmlWriter.element("text").text(value),
                DwXmlWriter.element("attr").attribute("v", value));

        String expected = DECLARATION_LINE
                + "<r>\n"
                + "\t<text>a" + character + "&amp;&lt;&gt;\"" + character + "z</text>\n"
                + "\t<attr v=\"a" + character + "&amp;&lt;&gt;&quot;" + character + "z\"/>\n"
                + "</r>";
        String output = writer.write(root);
        assertThat(output).isEqualTo(expected).doesNotContain("&#");

        byte[] written = writer.writeBytes(root);
        assertThat(character.getBytes(StandardCharsets.UTF_8)).isEqualTo(utf8);
        assertThat(written).isEqualTo(expected.getBytes(StandardCharsets.UTF_8));
        assertThat(written).hasSize(expected.length() + 4 * (utf8.length - character.length()));
    }

    @Test
    void attributesKeepFirstInsertionOrderAndNullValueRemovesThem() {
        DwXmlWriter.Element option = DwXmlWriter.element("option")
                .attribute("value", "Account")
                .attribute("xsi:type", "T")
                .attribute("value", "Contact")
                .attribute("removed", "x")
                .attribute("removed", null)
                .attribute("absent", null)
                .text("Contact");

        assertThat(option.getAttributes()).containsExactly(entry("value", "Contact"), entry("xsi:type", "T"));
        assertThat(writer.write(option))
                .isEqualTo(DECLARATION_LINE + "<option value=\"Contact\" xsi:type=\"T\">Contact</option>");
    }

    @Test
    void namespaceBindingsAreDeclaredWhereFirstNeeded() {
        String tshirt = "http://mulesoft.org/tshirt-service";
        DwXmlWriter.Element order = DwXmlWriter.element("ns0", tshirt, "OrderTshirt").children(
                DwXmlWriter.element("email").text("customerID@gmail.com"),
                DwXmlWriter.element("ns0", tshirt, "size").text("L"));
        assertThat(writer.write(order)).isEqualTo(DECLARATION_LINE
                + "<ns0:OrderTshirt xmlns:ns0=\"http://mulesoft.org/tshirt-service\">\n"
                + "\t<email>customerID@gmail.com</email>\n"
                + "\t<ns0:size>L</ns0:size>\n"
                + "</ns0:OrderTshirt>");

        // Explicit declarations come first in insertion order, then the element's own binding; siblings each
        // declare again; a default-namespace element declares xmlns once, however its prefix is given.
        DwXmlWriter.Element siblings = DwXmlWriter.element("root").children(
                DwXmlWriter.element("a", "urn:a", "first")
                        .namespace("xsi", "http://www.w3.org/2001/XMLSchema-instance")
                        .attribute("xsi:type", "T"),
                DwXmlWriter.element("a", "urn:a", "second"),
                DwXmlWriter.element(null, "urn:default", "third")
                        .child(DwXmlWriter.element("", "urn:default", "inner")),
                DwXmlWriter.element("", "urn:default", "fourth").namespace(null, "urn:default"));
        assertThat(writer.write(siblings)).isEqualTo(DECLARATION_LINE
                + "<root>\n"
                + "\t<a:first xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:a=\"urn:a\""
                + " xsi:type=\"T\"/>\n"
                + "\t<a:second xmlns:a=\"urn:a\"/>\n"
                + "\t<third xmlns=\"urn:default\">\n"
                + "\t\t<inner/>\n"
                + "\t</third>\n"
                + "\t<fourth xmlns=\"urn:default\"/>\n"
                + "</root>");

        // An ancestor's binding is not repeated, a prefix bound again to another URI is declared again, the
        // default namespace can be undeclared, and namespace URIs escape quotes.
        DwXmlWriter.Element scoped = DwXmlWriter.element("p", "urn:one", "root").namespace("q", "urn:q").children(
                DwXmlWriter.element("q", null, "bound").namespace("q", "urn:q"),
                DwXmlWriter.element("p", "urn:two", "rebound"),
                DwXmlWriter.element("p", "urn:one", "same"),
                DwXmlWriter.element("plain").namespace(null, "").namespace("x", "urn:\"quoted\""));
        assertThat(writer.write(scoped)).isEqualTo(DECLARATION_LINE
                + "<p:root xmlns:q=\"urn:q\" xmlns:p=\"urn:one\">\n"
                + "\t<q:bound/>\n"
                + "\t<p:rebound xmlns:p=\"urn:two\"/>\n"
                + "\t<p:same/>\n"
                + "\t<plain xmlns=\"\" xmlns:x=\"urn:&quot;quoted&quot;\"/>\n"
                + "</p:root>");

        // A prefixed element without a URI of its own is bound by its explicit declaration for that prefix, and
        // an explicit declaration equal to the element's own binding is written once.
        assertThat(writer.write(DwXmlWriter.element("s", null, "Envelope").namespace("s", "urn:s")))
                .isEqualTo(DECLARATION_LINE + "<s:Envelope xmlns:s=\"urn:s\"/>");
        assertThat(writer.write(DwXmlWriter.element("s", "urn:s", "Envelope").namespace("s", "urn:s")))
                .isEqualTo(DECLARATION_LINE + "<s:Envelope xmlns:s=\"urn:s\"/>");
    }

    @Test
    void invalidNamespaceBindingsAreRejected() {
        assertThatThrownBy(() -> writer.write(DwXmlWriter.element("ns0", null, "Orphan")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Prefix 'ns0' of element <ns0:Orphan> is not bound to a namespace URI");
        assertThatThrownBy(() -> writer.write(DwXmlWriter.element("r").child(DwXmlWriter.element("x", "", "c"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Prefix 'x' of element <x:c> is not bound to a namespace URI");
        assertThatThrownBy(() -> writer.writeBytes(DwXmlWriter.element("p", "urn:a", "c").namespace("p", "urn:b")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Element <p:c> is in namespace 'urn:a' but declares its prefix for 'urn:b'");

        DwXmlWriter.Element element = DwXmlWriter.element("e");
        assertThatThrownBy(() -> element.namespace("p", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Namespace prefix 'p' needs a namespace URI");
        assertThatThrownBy(() -> element.namespace(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Namespace prefix '' needs a namespace URI");
        assertThatThrownBy(() -> element.namespace("p", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Namespace prefix 'p' needs a namespace URI");
        assertThat(element.getNamespaces()).isEmpty();
    }

    @Test
    void valueTreesConvertMapsIterablesArraysAndNulls() {
        Map<String, Object> value = ordered(
                "list", List.of("a", "b"),
                "nested", List.of(List.of("n1", "n2"), "n3"),
                "objects", new Object[] {"o1", null},
                "ints", new int[] {1, 2},
                "empty", List.of(),
                "none", null,
                "decimal", new BigDecimal("27.60"),
                "map", ordered("inner", true));

        DwXmlWriter.Element root = writer.fromObject("ns0", "urn:ns0", "root", value);

        assertThat(root.getPrefix()).isEqualTo("ns0");
        assertThat(root.getUri()).isEqualTo("urn:ns0");
        assertThat(root.getChildren()).allSatisfy(child -> {
            assertThat(child.getPrefix()).isNull();
            assertThat(child.getUri()).isNull();
        });
        assertThat(writer.write(root)).isEqualTo(DECLARATION_LINE
                + "<ns0:root xmlns:ns0=\"urn:ns0\">\n"
                + "\t<list>a</list>\n"
                + "\t<list>b</list>\n"
                + "\t<nested>n1</nested>\n"
                + "\t<nested>n2</nested>\n"
                + "\t<nested>n3</nested>\n"
                + "\t<objects>o1</objects>\n"
                + "\t<objects/>\n"
                + "\t<ints>1</ints>\n"
                + "\t<ints>2</ints>\n"
                + "\t<none/>\n"
                + "\t<decimal>27.60</decimal>\n"
                + "\t<map>\n"
                + "\t\t<inner>true</inner>\n"
                + "\t</map>\n"
                + "</ns0:root>");

        assertThat(writer.write(writer.fromObject("r", "text"))).isEqualTo(DECLARATION_LINE + "<r>text</r>");
        assertThat(writer.write(writer.fromObject("r", 5))).isEqualTo(DECLARATION_LINE + "<r>5</r>");
        assertThat(writer.write(writer.fromObject("r", null))).isEqualTo(DECLARATION_LINE + "<r/>");
        assertThat(writer.write(writer.fromObject("r", ""))).isEqualTo(DECLARATION_LINE + "<r></r>");
        assertThat(writer.write(writer.fromObject("r", Map.of()))).isEqualTo(DECLARATION_LINE + "<r/>");
        assertThat(writer.write(writer.fromObject("", "", "r", "x"))).isEqualTo(DECLARATION_LINE + "<r>x</r>");
    }

    @Test
    void invalidTreesAndArgumentsAreRejected() {
        DwXmlWriter.Element mixed = DwXmlWriter.element("r").text("t").child(DwXmlWriter.element("c"));
        assertThatThrownBy(() -> writer.write(mixed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Element <r> holds both text and child elements;"
                        + " DataWeave XML output has no mixed content");
        assertThatThrownBy(() -> writer.writeBytes(DwXmlWriter.element("root").child(mixed)))
                .isInstanceOf(IllegalStateException.class);
        // The other call order, a child first and then text, is the same mixed content.
        DwXmlWriter.Element childThenText = DwXmlWriter.element("r").child(DwXmlWriter.element("c")).text("t");
        assertThatThrownBy(() -> writer.write(childThenText))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Element <r> holds both text and child elements;"
                        + " DataWeave XML output has no mixed content");
        assertThatThrownBy(() -> writer.writeBytes(childThenText))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> writer.write(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("XML output requires a root element");
        assertThatThrownBy(() -> writer.writeBytes(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("XML output requires a root element");

        for (Object repeated : List.of(List.of("a"), new String[] {"a"}, new int[] {1})) {
            assertThatThrownBy(() -> writer.fromObject("r", repeated))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("XML output requires a single root element");
            assertThatThrownBy(() -> writer.fromObject("p", "urn:p", "r", repeated))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("XML output requires a single root element");
        }

        String emptyName = "An XML element name must not be null or empty";
        assertThatThrownBy(() -> DwXmlWriter.element(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(emptyName);
        assertThatThrownBy(() -> DwXmlWriter.element(""))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(emptyName);
        assertThatThrownBy(() -> DwXmlWriter.element("p", "urn:p", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(emptyName);
        assertThatThrownBy(() -> writer.fromObject(null, "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(emptyName);
        assertThatThrownBy(() -> writer.fromObject("", "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(emptyName);
        assertThatThrownBy(() -> writer.fromObject("r", ordered("", "x")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(emptyName);

        DwXmlWriter.Element element = DwXmlWriter.element("e");
        String emptyAttribute = "An XML attribute name must not be null or empty";
        assertThatThrownBy(() -> element.attribute(null, "v"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(emptyAttribute);
        assertThatThrownBy(() -> element.attribute("", "v"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(emptyAttribute);
        assertThatThrownBy(() -> element.child(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("An XML child element must not be null");
        assertThatThrownBy(() -> element.children((Iterable<DwXmlWriter.Element>) null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("XML child elements must not be null");
        assertThatThrownBy(() -> element.children((DwXmlWriter.Element[]) null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("XML child elements must not be null");
        assertThatThrownBy(() -> element.children(Arrays.asList((DwXmlWriter.Element) null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("An XML child element must not be null");
        assertThatThrownBy(() -> element.children((DwXmlWriter.Element) null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("An XML child element must not be null");
        assertThat(element.getAttributes()).isEmpty();
        assertThat(element.getChildren()).isEmpty();
    }

    @Test
    void gettersExposeStateThroughUnmodifiableViews() {
        DwXmlWriter.Element child = DwXmlWriter.element("child");
        DwXmlWriter.Element parent = DwXmlWriter.element("p", "urn:p", "parent")
                .namespace(null, "urn:default")
                .namespace("x", "urn:x")
                .attribute("a", "1")
                .child(child);
        DwXmlWriter.Element leaf = DwXmlWriter.element("leaf").text(new BigDecimal("27.60"));

        assertThat(parent.getName()).isEqualTo("parent");
        assertThat(parent.getPrefix()).isEqualTo("p");
        assertThat(parent.getUri()).isEqualTo("urn:p");
        assertThat(parent.getNamespaces()).containsExactly(entry("", "urn:default"), entry("x", "urn:x"));
        assertThat(parent.getAttributes()).containsExactly(entry("a", "1"));
        assertThat(parent.getText()).isNull();
        assertThat(parent.getChildren()).containsExactly(child);
        assertThat(leaf.getText()).isEqualTo("27.60");
        assertThat(leaf.getPrefix()).isNull();
        assertThat(leaf.getUri()).isNull();
        assertThat(leaf.getChildren()).isEmpty();

        assertThatThrownBy(() -> parent.getAttributes().put("b", "2"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> parent.getNamespaces().put("y", "urn:y"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> parent.getChildren().add(leaf))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(parent.getAttributes()).hasSize(1);
        assertThat(parent.getNamespaces()).hasSize(2);
        assertThat(parent.getChildren()).hasSize(1);
    }

    private static DwXmlWriter.Element employeesElement() {
        return DwXmlWriter.element("employees").children(List.of(
                employee("John", "Doe", address("123 MainStreet", "111"), address("987 CypressAvenue", "222")),
                employee("Jane", "Doe", address("345 MainStreet", "111"), address("654 SunsetBoulevard", "333"))));
    }

    private static DwXmlWriter.Element employee(String name, String lastName, DwXmlWriter.Element... addresses) {
        return DwXmlWriter.element("employee").children(
                DwXmlWriter.element("name").text(name),
                DwXmlWriter.element("lastName").text(lastName),
                DwXmlWriter.element("addresses").children(addresses));
    }

    private static DwXmlWriter.Element address(String street, String zipCode) {
        return DwXmlWriter.element("address").children(
                DwXmlWriter.element("street").text(street),
                DwXmlWriter.element("zipCode").text(zipCode));
    }

    private static Map<String, Object> employeesValue() {
        return ordered("employee", List.of(
                ordered("name", "John", "lastName", "Doe", "addresses", ordered("address", List.of(
                        ordered("street", "123 MainStreet", "zipCode", "111"),
                        ordered("street", "987 CypressAvenue", "zipCode", "222")))),
                ordered("name", "Jane", "lastName", "Doe", "addresses", ordered("address", List.of(
                        ordered("street", "345 MainStreet", "zipCode", "111"),
                        ordered("street", "654 SunsetBoulevard", "zipCode", "333"))))));
    }

    private static Map<String, Object> summaryValue() {
        Map<String, Object> receipt = ordered("id", 1, "status", "ACCEPTED", "totalPrice", 2550.0);
        Map<String, Object> item = ordered("manufacturer", "Samsung", "name", "s-1", "productId", "AX02",
                "purchaseReceipt", receipt, "quantity", 1);
        return ordered(
                "orderId", 12,
                "customer", ordered("address", "Main Street 123", "firstName", "John", "lastName", "Doe"),
                "orderItems", ordered("item", List.of(item)));
    }

    private static DwXmlWriter.Element optionsElement() {
        return DwXmlWriter.element("div").children(
                option("AcceptedEventRelation", "Accepted Event Relation"),
                option("Account", "Account"),
                option("WebLink", "Custom Button or Link"));
    }

    private static DwXmlWriter.Element singleOptionElement() {
        return DwXmlWriter.element("div").child(option("Account", "Account"));
    }

    private static DwXmlWriter.Element option(String value, String label) {
        return DwXmlWriter.element("option").attribute("value", value).text(label);
    }

    private DwXmlWriter.Element orderTshirtElement() {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("email", "a@b.c");
        order.put("size", "L");
        return writer.fromObject("ns0", TSHIRT_NAMESPACE, "OrderTshirt", order);
    }

    private static DwXmlWriter.Element rowElement() {
        return DwXmlWriter.element("tr").children(
                DwXmlWriter.element("td").text("a"),
                DwXmlWriter.element("td").text("b"),
                DwXmlWriter.element("td").text("c"));
    }

    private DwXmlWriter.Element rowValueTree() {
        return writer.fromObject("tr", ordered("td", List.of("a", "b", "c")));
    }

    private static DwXmlWriter.Element inheritedPrefixElement() {
        return DwXmlWriter.element("ns0", TSHIRT_NAMESPACE, "ListInventoryResponse").children(
                DwXmlWriter.element("ns0", TSHIRT_NAMESPACE, "inventory")
                        .child(DwXmlWriter.element("ns0", TSHIRT_NAMESPACE, "productCode").text("4102")),
                DwXmlWriter.element("ns1", "urn:other", "audit")
                        .child(DwXmlWriter.element("ns1", "urn:other", "entry").text("x")));
    }

    private static DwXmlWriter.Element nonAsciiElement() {
        return DwXmlWriter.element("description").text("\u00d1and\u00fa");
    }

    /** The {@code orders/order} shape of one CSV row {@code 1,<name>,1,1}. */
    private static DwXmlWriter.Element singleOrder(String name) {
        return DwXmlWriter.element("orders").child(DwXmlWriter.element("order").children(
                DwXmlWriter.element("orderId").text("1"),
                DwXmlWriter.element("name").text(name),
                DwXmlWriter.element("units").text("1"),
                DwXmlWriter.element("pricePerUnit").text("1")));
    }

    /** Bytes from unsigned values, so UTF-8 sequences read as they are written. */
    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }

    /** An insertion-ordered map from alternating keys and values; values may be {@code null}. */
    private static Map<String, Object> ordered(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("Keys and values must alternate");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private static long lineFeeds(String text) {
        return text.chars().filter(c -> c == '\n').count();
    }

    /** The number of non-overlapping occurrences of {@code token} in {@code text}. */
    private static int occurrences(String text, String token) {
        int count = 0;
        for (int from = text.indexOf(token); from >= 0; from = text.indexOf(token, from + token.length())) {
            count++;
        }
        return count;
    }

    /** The comparison the original upload-to-ftp test applies: every whitespace character removed. */
    private static String withoutWhitespace(String text) {
        return text.replaceAll("\\s", "");
    }
}
