/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.xml_only_soap_webservice.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.Diff;

/**
 * Contract of {@link UpsertPatientRequestMapper}, the owner of the script "admitSubject to upsertPatient"
 * ({@code xml-only-soap-webservice/src/main/app/Hospital_Admissions_SOA.xml:53}):
 * {@code ns0#upsertPatient: { ns1#Subject: payload.Envelope.Body.admitSubject.Subject }}.
 *
 * <p>The input is the original request {@code message.xml}, reached by namespace and local name through its SOAP
 * Body, or a small document built for one selection or copy rule. Documents are compared exactly, never by
 * containment. Expected documents are spelled out with {@code \n} and {@code \t}, ending at the root end tag as
 * {@link DwXmlWriter} writes them, with the namespace URIs as literals rather than through the mapper's
 * constants.
 */
class UpsertPatientRequestMapperTest {

    private static final String NS0 = "http://www.mule-health.com/SOA/message/1.0";
    private static final String NS1 = "http://www.mule-health.com/SOA/model/1.0";
    private static final String SOAP_ENVELOPE = "http://schemas.xmlsoap.org/soap/envelope/";

    private static final String DECLARATION = "<?xml version='1.0' encoding='UTF-8'?>";
    private static final String ROOT_START =
            "<ns0:upsertPatient xmlns:ns0=\"http://www.mule-health.com/SOA/message/1.0\">";
    private static final String ROOT_END = "</ns0:upsertPatient>";
    private static final String SUBJECT_START = "\t<ns1:Subject xmlns:ns1=\"http://www.mule-health.com/SOA/model/1.0\">";
    private static final String SUBJECT_END = "\t</ns1:Subject>";

    /** The document for an input without a selectable {@code Subject}, as the mapper's Javadoc states it. */
    private static final String NULL_FORM = String.join("\n",
            DECLARATION,
            ROOT_START,
            "\t<ns1:Subject xmlns:ns1=\"http://www.mule-health.com/SOA/model/1.0\"/>",
            ROOT_END);

    /** The nine {@code Subject} children of {@code message.xml}, in document order. */
    private static final List<String> SUBJECT_FIELDS = List.of("nationalId", "firstName", "lastName", "address1",
            "address2", "address3", "nationality", "gender", "dateOfBirth");

    /** The text of each of {@link #SUBJECT_FIELDS}; the empty {@code address2} and {@code address3} hold none. */
    private static final List<String> SUBJECT_VALUES = List.of("1234", "Nial", "Darbey", "Buenos Aires", "", "",
            "Irish", "Male", "1970-08-07");

    /** {@code toXml} of {@code message.xml}: the empty address lines in the writer's null spelling. */
    private static final String MESSAGE_DOCUMENT = expectedDocument(
            "\t\t<nationalId>1234</nationalId>",
            "\t\t<firstName>Nial</firstName>",
            "\t\t<lastName>Darbey</lastName>",
            "\t\t<address1>Buenos Aires</address1>",
            "\t\t<address2/>",
            "\t\t<address3/>",
            "\t\t<nationality>Irish</nationality>",
            "\t\t<gender>Male</gender>",
            "\t\t<dateOfBirth>1970-08-07</dateOfBirth>");

    /** An {@code admitSubject} start tag binding the prefixes {@code message.xml} uses. */
    private static final String ADMIT_SUBJECT_START =
            "<ns:admitSubject xmlns:ns=\"" + NS0 + "\" xmlns:ns1=\"" + NS1 + "\">";
    private static final String ADMIT_SUBJECT_END = "</ns:admitSubject>";

    private UpsertPatientRequestMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new UpsertPatientRequestMapper(new DwXmlWriter());
    }

    @Test
    void toXmlCopiesSubjectFromMessageXml() {
        assertThat(mapper.toXml(admitSubject())).isEqualTo(MESSAGE_DOCUMENT);
    }

    @Test
    void toXmlUsesNamespaceConstants() {
        assertThat(UpsertPatientRequestMapper.NS0).isEqualTo(NS0);
        assertThat(UpsertPatientRequestMapper.NS1).isEqualTo(NS1);
        assertThat(UpsertPatientRequestMapper.NS0_PREFIX).isEqualTo("ns0");
        assertThat(UpsertPatientRequestMapper.NS1_PREFIX).isEqualTo("ns1");

        Element root = parse(mapper.toXml(admitSubject())).getDocumentElement();
        assertQualified(root, UpsertPatientRequestMapper.NS0_PREFIX, UpsertPatientRequestMapper.NS0, "upsertPatient");
        List<Element> children = childElements(root);
        assertThat(children).hasSize(1);
        assertQualified(children.get(0), UpsertPatientRequestMapper.NS1_PREFIX, UpsertPatientRequestMapper.NS1,
                "Subject");
    }

    @Test
    void toElementHasSameStructure() {
        Element root = mapper.toElement(admitSubject());

        assertThat(root.getOwnerDocument().getDocumentElement()).isSameAs(root);
        assertQualified(root, "ns0", NS0, "upsertPatient");
        List<Element> rootChildren = childElements(root);
        assertThat(rootChildren).hasSize(1);
        Element subject = rootChildren.get(0);
        assertQualified(subject, "ns1", NS1, "Subject");

        List<Element> fields = childElements(subject);
        assertThat(fields).extracting(Node::getNamespaceURI).hasSize(SUBJECT_FIELDS.size()).containsOnlyNulls();
        assertThat(fields).extracting(Node::getLocalName).containsExactlyElementsOf(SUBJECT_FIELDS);
        assertThat(fields).extracting(Node::getTextContent).containsExactlyElementsOf(SUBJECT_VALUES);

        // The writer's layout whitespace stays in the DOM, as the original sent the DataWeave output unchanged.
        assertThat(root.getFirstChild().getNodeType()).isEqualTo(Node.TEXT_NODE);
        assertThat(root.getFirstChild().getNodeValue()).isEqualTo("\n\t");
        assertIdentical(root, MESSAGE_DOCUMENT);
    }

    @Test
    void childrenAreUnqualified() {
        Element subject = childElements(mapper.toElement(admitSubject())).get(0);

        // Subject binds only its own prefix, so no default namespace is in scope for its children.
        assertThat(subject.getAttributes().getLength()).isEqualTo(1);
        assertThat(subject.getAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "ns1")).isEqualTo(NS1);
        assertThat(subject.lookupNamespaceURI(null)).isNull();

        List<Element> fields = childElements(subject);
        assertThat(fields).hasSize(SUBJECT_FIELDS.size());
        for (Element field : fields) {
            assertThat(field.getPrefix()).as(field.getTagName()).isNull();
            assertThat(field.getNamespaceURI()).as(field.getTagName()).isNull();
            assertThat(field.getAttributes().getLength()).as(field.getTagName()).isZero();
            assertThat(childElements(field)).as(field.getTagName()).isEmpty();
        }
    }

    static Stream<Arguments> absentSubjects() {
        return Stream.of(
                Arguments.of("admitSubject with Referer and Referral only", ADMIT_SUBJECT_START
                        + "<ns1:Referer><clientId>1234</clientId></ns1:Referer>"
                        + "<ns1:Referral><procedure><code>In-patient</code></procedure></ns1:Referral>"
                        + ADMIT_SUBJECT_END),
                Arguments.of("empty admitSubject", "<ns:admitSubject xmlns:ns=\"" + NS0 + "\"/>"),
                Arguments.of("Subject without content", admitSubjectXml("")),
                Arguments.of("Subject with whitespace only", admitSubjectXml(" \n\t ")),
                Arguments.of("Subject with a comment only", admitSubjectXml("<!-- no subject data -->")),
                Arguments.of("argument not named admitSubject", "<ns:upsertPatient xmlns:ns=\"" + NS0
                        + "\" xmlns:ns1=\"" + NS1 + "\"><ns1:Subject><nationalId>1234</nationalId></ns1:Subject>"
                        + "</ns:upsertPatient>"),
                Arguments.of("SOAP Envelope instead of its Body child", resource("/message.xml")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("absentSubjects")
    void absentSubjectProducesNullForm(String description, String xml) {
        assertNullForm(parse(xml).getDocumentElement());
    }

    @Test
    void nullAdmitSubjectProducesNullForm() {
        assertNullForm(null);
    }

    @Test
    void ignoresRefererAndReferral() {
        Element admitSubject = admitSubject();
        assertThat(childElements(admitSubject)).extracting(Node::getLocalName)
                .containsExactly("Referer", "Referral", "Subject");

        Document output = mapper.toElement(admitSubject).getOwnerDocument();

        for (String name : List.of("Referer", "clientId", "Referral", "procedure", "code", "admission",
                "department")) {
            assertThat(output.getElementsByTagNameNS("*", name).getLength()).as(name).isZero();
        }
    }

    static Stream<Arguments> selectableSubjects() {
        return Stream.of(
                Arguments.of("admitSubject and Subject in another namespace", "<o:admitSubject xmlns:o=\"urn:other\">"
                        + "<o:Subject><nationalId>1234</nationalId></o:Subject></o:admitSubject>"),
                Arguments.of("unqualified admitSubject and Subject",
                        "<admitSubject><Subject><nationalId>1234</nationalId></Subject></admitSubject>"),
                Arguments.of("Subject after text, a comment and another element", ADMIT_SUBJECT_START
                        + "\n\t<!-- referer first -->\n\t<ns1:Referer><clientId>1</clientId></ns1:Referer>"
                        + "\n\t<ns1:Subject><nationalId>1234</nationalId></ns1:Subject>\n" + ADMIT_SUBJECT_END),
                Arguments.of("first of two Subjects", ADMIT_SUBJECT_START
                        + "<ns1:Subject><nationalId>1234</nationalId></ns1:Subject>"
                        + "<ns1:Subject><nationalId>5678</nationalId></ns1:Subject>" + ADMIT_SUBJECT_END),
                Arguments.of("attributes and declarations of Subject itself", ADMIT_SUBJECT_START
                        + "<ns1:Subject id=\"7\" xmlns:z=\"urn:z\" z:origin=\"x\"><nationalId>1234</nationalId>"
                        + "</ns1:Subject>" + ADMIT_SUBJECT_END));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("selectableSubjects")
    void selectsFirstSubjectChildByLocalName(String description, String xml) {
        assertThat(mapper.toXml(parse(xml).getDocumentElement()))
                .isEqualTo(expectedDocument("\t\t<nationalId>1234</nationalId>"));
    }

    static Stream<Arguments> subjectContents() {
        return Stream.of(
                Arguments.of("nested elements one depth deeper each",
                        "<address><street>Main Street</street><zip>1111</zip></address>",
                        List.of("\t\t<address>",
                                "\t\t\t<street>Main Street</street>",
                                "\t\t\t<zip>1111</zip>",
                                "\t\t</address>")),
                Arguments.of("repeated names kept in document order",
                        "<phone>1</phone><email>a@b.c</email><phone>2</phone>",
                        List.of("\t\t<phone>1</phone>",
                                "\t\t<email>a@b.c</email>",
                                "\t\t<phone>2</phone>")),
                Arguments.of("empty and whitespace-only leaves are null",
                        "<a></a><b/><c> \n\t </c>",
                        List.of("\t\t<a/>",
                                "\t\t<b/>",
                                "\t\t<c/>")),
                Arguments.of("text, comments, CDATA and instructions beside elements dropped",
                        "lead<a>x<b>1</b>y<!-- c --><?pi data?><![CDATA[z]]></a>tail",
                        List.of("\t\t<a>",
                                "\t\t\t<b>1</b>",
                                "\t\t</a>")),
                Arguments.of("leaf text content kept verbatim and escaped",
                        "<a> padded </a><b><![CDATA[x < y & z > w]]></b><c>1<!-- c -->2</c>",
                        List.of("\t\t<a> padded </a>",
                                "\t\t<b>x &lt; y &amp; z &gt; w</b>",
                                "\t\t<c>12</c>")),
                Arguments.of("Subject namespace prefix not declared again",
                        "<ns1:extra>1</ns1:extra>",
                        List.of("\t\t<ns1:extra>1</ns1:extra>")),
                Arguments.of("other namespace prefix declared where first used",
                        "<o:extra xmlns:o=\"urn:other\"><o:code>1</o:code></o:extra>",
                        List.of("\t\t<o:extra xmlns:o=\"urn:other\">",
                                "\t\t\t<o:code>1</o:code>",
                                "\t\t</o:extra>")),
                Arguments.of("default namespace kept and undeclared for unqualified descendants",
                        "<d xmlns=\"urn:default\"><e>1</e><u xmlns=\"\"><f>2</f></u></d>",
                        List.of("\t\t<d xmlns=\"urn:default\">",
                                "\t\t\t<e>1</e>",
                                "\t\t\t<u xmlns=\"\">",
                                "\t\t\t\t<f>2</f>",
                                "\t\t\t</u>",
                                "\t\t</d>")),
                Arguments.of("attributes in DOM order without namespace declarations",
                        "<a b=\"2\" a=\"1\" xmlns:o=\"urn:other\">x</a>",
                        List.of("\t\t<a a=\"1\" b=\"2\">x</a>")),
                Arguments.of("qualified attributes bind their prefix unless in scope or xml",
                        "<a xmlns:o=\"urn:other\" o:k=\"v\" ns1:m=\"w\" xml:lang=\"en\">x</a>",
                        List.of("\t\t<a xmlns:o=\"urn:other\" ns1:m=\"w\" o:k=\"v\" xml:lang=\"en\">x</a>")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("subjectContents")
    void copiesSubjectContentAsDataWeaveReadsIt(String description, String subjectContent, List<String> body) {
        Element admitSubject = parse(admitSubjectXml(subjectContent)).getDocumentElement();
        String expected = expectedDocument(body.toArray(String[]::new));

        assertThat(mapper.toXml(admitSubject)).isEqualTo(expected);
        assertIdentical(mapper.toElement(admitSubject), expected);
    }

    @Test
    void copiesNodesBuiltWithoutNamespaceSupport() {
        Element admitSubject = parse(ADMIT_SUBJECT_START + "<ns1:Referer/><ns1:Subject>"
                + "<nationalId k=\"v\" xmlns=\"urn:default\" xmlns:x=\"urn:x\">1234</nationalId>"
                + "<firstName>Nial</firstName></ns1:Subject>" + ADMIT_SUBJECT_END, false).getDocumentElement();
        assertThat(admitSubject.getLocalName()).isNull();

        String expected = expectedDocument(
                "\t\t<nationalId k=\"v\">1234</nationalId>",
                "\t\t<firstName>Nial</firstName>");

        assertThat(mapper.toXml(admitSubject)).isEqualTo(expected);
        assertIdentical(mapper.toElement(admitSubject), expected);
    }

    @Test
    void writerRejectsACharacterXmlForbidsInBothConversions() {
        Document input = builder(true).newDocument();
        Element admitSubject = input.createElementNS(NS0, "ns:admitSubject");
        Element nationalId = input.createElementNS(null, "nationalId");
        // A DOM built in memory accepts a character XML 1.0 forbids; the writer rejects it before anything is parsed.
        nationalId.appendChild(input.createTextNode("12\u000134"));
        admitSubject.appendChild(input.createElementNS(NS1, "ns1:Subject")).appendChild(nationalId);
        String invalid = "Invalid XML 1.0 character U+0001 in the text of element <nationalId>";

        assertThatThrownBy(() -> mapper.toXml(admitSubject))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(invalid)
                .hasNoCause();
        assertThatThrownBy(() -> mapper.toElement(admitSubject))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(invalid)
                .hasNoCause();
    }

    @Test
    void toElementFailsWhenWriterOutputCannotBeParsed() {
        Document input = builder(true).newDocument();
        // Without strict error checking a DOM accepts an element name that is not an XML Name; the mapper copies the
        // name as it is, so the writer's output is not well-formed and the parser rejects it.
        input.setStrictErrorChecking(false);
        Element admitSubject = input.createElementNS(NS0, "ns:admitSubject");
        Element notAName = input.createElementNS(null, "a b");
        notAName.appendChild(input.createTextNode("1234"));
        admitSubject.appendChild(input.createElementNS(NS1, "ns1:Subject")).appendChild(notAName);

        assertThat(mapper.toXml(admitSubject)).isEqualTo(expectedDocument("\t\t<a b>1234</a b>"));
        assertThatThrownBy(() -> mapper.toElement(admitSubject))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("upsertPatient XML could not be parsed: ")
                .hasCauseInstanceOf(SAXException.class);
    }

    private void assertNullForm(Element admitSubject) {
        assertThat(mapper.toXml(admitSubject)).isEqualTo(NULL_FORM);

        Element root = mapper.toElement(admitSubject);
        assertQualified(root, "ns0", NS0, "upsertPatient");
        List<Element> children = childElements(root);
        assertThat(children).hasSize(1);
        assertQualified(children.get(0), "ns1", NS1, "Subject");
        assertThat(children.get(0).hasChildNodes()).isFalse();
        assertIdentical(root, NULL_FORM);
    }

    /** The full expected document whose {@code ns1:Subject} holds the given lines, each indented as written. */
    private static String expectedDocument(String... subjectBody) {
        List<String> lines = new ArrayList<>();
        lines.add(DECLARATION);
        lines.add(ROOT_START);
        lines.add(SUBJECT_START);
        lines.addAll(List.of(subjectBody));
        lines.add(SUBJECT_END);
        lines.add(ROOT_END);
        return String.join("\n", lines);
    }

    /** An {@code admitSubject} whose only child is {@code ns1:Subject} with the given content. */
    private static String admitSubjectXml(String subjectContent) {
        return ADMIT_SUBJECT_START + "<ns1:Subject>" + subjectContent + "</ns1:Subject>" + ADMIT_SUBJECT_END;
    }

    /** The {@code admitSubject} of {@code message.xml}: the SOAP Body's child in {@link #NS0}. */
    private static Element admitSubject() {
        Element envelope = parse(resource("/message.xml")).getDocumentElement();
        Element body = firstChild(envelope, SOAP_ENVELOPE, "Body");
        return firstChild(body, NS0, "admitSubject");
    }

    private static Element firstChild(Element parent, String namespaceUri, String localName) {
        return childElements(parent).stream()
                .filter(child -> namespaceUri.equals(child.getNamespaceURI()) && localName.equals(child.getLocalName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No {" + namespaceUri + "}" + localName + " under <" + parent.getTagName() + ">"));
    }

    private static List<Element> childElements(Node parent) {
        List<Element> elements = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                elements.add((Element) node);
            }
        }
        return elements;
    }

    private static void assertQualified(Element element, String prefix, String namespaceUri, String localName) {
        assertThat(element.getPrefix()).as("prefix of <%s>", element.getTagName()).isEqualTo(prefix);
        assertThat(element.getNamespaceURI()).as("namespace of <%s>", element.getTagName()).isEqualTo(namespaceUri);
        assertThat(element.getLocalName()).as("local name of <%s>", element.getTagName()).isEqualTo(localName);
    }

    /** The document of {@code actual} is node for node the parse of {@code expectedXml}, whitespace included. */
    private static void assertIdentical(Element actual, String expectedXml) {
        Diff diff = DiffBuilder.compare(Input.fromNode(parse(expectedXml)))
                .withTest(Input.fromNode(actual.getOwnerDocument()))
                .checkForIdentical()
                .build();
        assertThat(diff.hasDifferences()).as(diff.toString()).isFalse();
    }

    private static String resource(String name) {
        try (InputStream in = UpsertPatientRequestMapperTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Document parse(String xml) {
        return parse(xml, true);
    }

    private static Document parse(String xml, boolean namespaceAware) {
        try {
            return builder(namespaceAware).parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (SAXException | IOException e) {
            throw new IllegalStateException("Test XML could not be parsed: " + e.getMessage(), e);
        }
    }

    private static DocumentBuilder builder(boolean namespaceAware) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setNamespaceAware(namespaceAware);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("Cannot configure the test XML parser: " + e.getMessage(), e);
        }
    }
}
