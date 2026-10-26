/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.xml_only_soap_webservice.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
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
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Behaviour of {@link CreateEpisodeRequestMapper}, the owner of the {@code %dw 1.0} script
 * "upsertPatientResponse to createEpisode" ({@code xml-only-soap-webservice/src/main/app/Hospital_Admissions_SOA.xml},
 * line 73): {@code ns0#createEpisode { ns1#PatientId: payload.upsertPatientResponse.ns1#PatientId }}.
 *
 * <p>The root is matched by local name only, {@code PatientId} only in the model namespace, and every input that
 * selects nothing yields the null form, the empty {@code ns1:PatientId} element. Expected documents are spelled out
 * with explicit {@code \n} and {@code \t} in {@link DwXmlWriter}'s layout: the single-quoted declaration, tab
 * indentation, LF line ends and nothing after the root end tag.
 */
class CreateEpisodeRequestMapperTest {

    private static final String MESSAGE_NS = "http://www.mule-health.com/SOA/message/1.0";

    private static final String MODEL_NS = "http://www.mule-health.com/SOA/model/1.0";

    /** A timestamp PatientId value. */
    private static final String PATIENT_ID = "2015-01-22T20:10:20.123-08:00";

    /** A PatientService response body: {@code ns0:upsertPatientResponse} holding one {@code ns1:PatientId}. */
    private static final String UPSERT_PATIENT_RESPONSE = "<ns0:upsertPatientResponse"
            + " xmlns:ns0=\"" + MESSAGE_NS + "\" xmlns:ns1=\"" + MODEL_NS + "\">"
            + "<ns1:PatientId>" + PATIENT_ID + "</ns1:PatientId>"
            + "</ns0:upsertPatientResponse>";

    /** The document written when the selector matches nothing or reads blank text. */
    private static final String NULL_FORM = String.join("\n",
            "<?xml version='1.0' encoding='UTF-8'?>",
            "<ns0:createEpisode xmlns:ns0=\"http://www.mule-health.com/SOA/message/1.0\">",
            "\t<ns1:PatientId xmlns:ns1=\"http://www.mule-health.com/SOA/model/1.0\"/>",
            "</ns0:createEpisode>");

    private CreateEpisodeRequestMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new CreateEpisodeRequestMapper(new DwXmlWriter());
    }

    static Stream<Arguments> matchingInputs() {
        return Stream.of(
                Arguments.of("copiesPatientId", parse(UPSERT_PATIENT_RESPONSE), PATIENT_ID),
                Arguments.of("copiesPatientIdRegardlessOfPrefix",
                        parse("<m:upsertPatientResponse xmlns:m=\"" + MESSAGE_NS + "\" xmlns:d=\"" + MODEL_NS + "\">"
                                + "<d:PatientId>" + PATIENT_ID + "</d:PatientId></m:upsertPatientResponse>"),
                        PATIENT_ID),
                Arguments.of("defaultNamespaceDeclarations",
                        parse("<upsertPatientResponse xmlns=\"" + MESSAGE_NS + "\">"
                                + "<PatientId xmlns=\"" + MODEL_NS + "\">" + PATIENT_ID + "</PatientId>"
                                + "</upsertPatientResponse>"),
                        PATIENT_ID),
                Arguments.of("noNamespaceRootWithQualifiedPatientId",
                        parse("<upsertPatientResponse xmlns:ns1=\"" + MODEL_NS + "\">"
                                + "<ns1:PatientId>" + PATIENT_ID + "</ns1:PatientId></upsertPatientResponse>"),
                        PATIENT_ID),
                Arguments.of("rootInAnotherNamespace",
                        parse("<o:upsertPatientResponse xmlns:o=\"urn:other\" xmlns:ns1=\"" + MODEL_NS + "\">"
                                + "<ns1:PatientId>" + PATIENT_ID + "</ns1:PatientId></o:upsertPatientResponse>"),
                        PATIENT_ID),
                Arguments.of("rootBuiltWithoutNamespaceAwareness", namespaceUnawareRootWithPatientId(PATIENT_ID),
                        PATIENT_ID),
                Arguments.of("firstModelPatientIdWins",
                        parse("<ns0:upsertPatientResponse xmlns:ns0=\"" + MESSAGE_NS + "\" xmlns:ns1=\"" + MODEL_NS
                                + "\"><PatientId>unqualified</PatientId><ns0:PatientId>message</ns0:PatientId>"
                                + "<ns1:PatientName>name</ns1:PatientName><ns1:PatientId>first</ns1:PatientId>"
                                + "<ns1:PatientId>second</ns1:PatientId></ns0:upsertPatientResponse>"),
                        "first"),
                Arguments.of("textKeptUnchangedAndEscaped",
                        parse("<ns0:upsertPatientResponse xmlns:ns0=\"" + MESSAGE_NS + "\" xmlns:ns1=\"" + MODEL_NS
                                + "\"><ns1:PatientId> P&amp;1 &lt;2&gt; \"3\" </ns1:PatientId>"
                                + "</ns0:upsertPatientResponse>"),
                        " P&amp;1 &lt;2&gt; \"3\" "));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("matchingInputs")
    void copiesPatientId(String scenario, Element upsertPatientResponse, String writtenPatientId) {
        assertThat(mapper.toXml(upsertPatientResponse)).isEqualTo(String.join("\n",
                "<?xml version='1.0' encoding='UTF-8'?>",
                "<ns0:createEpisode xmlns:ns0=\"http://www.mule-health.com/SOA/message/1.0\">",
                "\t<ns1:PatientId xmlns:ns1=\"http://www.mule-health.com/SOA/model/1.0\">" + writtenPatientId
                        + "</ns1:PatientId>",
                "</ns0:createEpisode>"));
    }

    static Stream<Arguments> nonMatchingInputs() {
        return Stream.of(
                Arguments.of("nullInput", null),
                Arguments.of("absentPatientIdProducesNullForm",
                        parse("<ns0:upsertPatientResponse xmlns:ns0=\"" + MESSAGE_NS + "\" xmlns:ns1=\"" + MODEL_NS
                                + "\"><ns1:PatientName>Donald</ns1:PatientName></ns0:upsertPatientResponse>")),
                Arguments.of("unqualifiedPatientIdProducesNullForm",
                        parse("<ns0:upsertPatientResponse xmlns:ns0=\"" + MESSAGE_NS + "\">"
                                + "<PatientId>" + PATIENT_ID + "</PatientId></ns0:upsertPatientResponse>")),
                Arguments.of("wrongNamespacePatientIdProducesNullForm (message namespace)",
                        parse("<ns0:upsertPatientResponse xmlns:ns0=\"" + MESSAGE_NS + "\">"
                                + "<ns0:PatientId>" + PATIENT_ID + "</ns0:PatientId></ns0:upsertPatientResponse>")),
                Arguments.of("wrongNamespacePatientIdProducesNullForm (urn:other)",
                        parse("<ns0:upsertPatientResponse xmlns:ns0=\"" + MESSAGE_NS + "\" xmlns:o=\"urn:other\">"
                                + "<o:PatientId>" + PATIENT_ID + "</o:PatientId></ns0:upsertPatientResponse>")),
                Arguments.of("otherRootProducesNullForm",
                        parse("<ns0:getPatientResponse xmlns:ns0=\"" + MESSAGE_NS + "\" xmlns:ns1=\"" + MODEL_NS
                                + "\"><ns1:PatientId>" + PATIENT_ID + "</ns1:PatientId></ns0:getPatientResponse>")),
                Arguments.of("emptyPatientIdProducesNullForm",
                        parse("<ns0:upsertPatientResponse xmlns:ns0=\"" + MESSAGE_NS + "\" xmlns:ns1=\"" + MODEL_NS
                                + "\"><ns1:PatientId/></ns0:upsertPatientResponse>")),
                Arguments.of("whitespaceOnlyPatientIdProducesNullForm",
                        parse("<ns0:upsertPatientResponse xmlns:ns0=\"" + MESSAGE_NS + "\" xmlns:ns1=\"" + MODEL_NS
                                + "\"><ns1:PatientId> \t&#13;\n </ns1:PatientId></ns0:upsertPatientResponse>")),
                Arguments.of("namespaceUnawareDomProducesNullForm", parseWithoutNamespaces(UPSERT_PATIENT_RESPONSE)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonMatchingInputs")
    void nonMatchingInputProducesNullForm(String scenario, Element upsertPatientResponse) {
        assertThat(mapper.toXml(upsertPatientResponse)).isEqualTo(NULL_FORM);

        Element patientId = patientIdOf(mapper.toElement(upsertPatientResponse));
        assertThat(patientId.hasChildNodes()).isFalse();
        assertThat(patientId.getTextContent()).isEmpty();
    }

    @Test
    void toElementHasSameStructure() {
        Element upsertPatientResponse = parse(UPSERT_PATIENT_RESPONSE);

        Element createEpisode = mapper.toElement(upsertPatientResponse);

        assertThat(createEpisode.getOwnerDocument()).isNotSameAs(upsertPatientResponse.getOwnerDocument());
        assertThat(createEpisode.getOwnerDocument().getDocumentElement()).isSameAs(createEpisode);
        assertThat(childNodeSummary(createEpisode)).containsExactly("#text:\n\t", "ns0:createEpisode/ns1:PatientId",
                "#text:\n");
        Element patientId = patientIdOf(createEpisode);
        assertThat(patientId.getPrefix()).isEqualTo("ns1");
        assertThat(patientId.getTextContent()).isEqualTo(PATIENT_ID);
        assertThat(createEpisode.isEqualNode(parse(mapper.toXml(upsertPatientResponse)))).isTrue();
    }

    static Stream<Arguments> structuredInputs() {
        return Stream.of(
                Arguments.of("childrenCopiedWithNamesNamespacesPrefixesAndAttributes",
                        parse(String.join("\n",
                                "<ns0:upsertPatientResponse xmlns:ns0=\"" + MESSAGE_NS + "\" xmlns:ns1=\"" + MODEL_NS
                                        + "\">",
                                "  <ns1:PatientId>",
                                "    <ns1:id kind=\"mrn\" xml:lang=\"en\">7</ns1:id>",
                                "    <value xmlns=\"urn:v\">",
                                "      <inner>1</inner>",
                                "      <plain xmlns=\"\">2</plain>",
                                "    </value>",
                                "    <x:ext xmlns:x=\"urn:x\" x:flag=\"true\"/>",
                                "    <note>n</note>",
                                "  </ns1:PatientId>",
                                "</ns0:upsertPatientResponse>")),
                        String.join("\n",
                                "<?xml version='1.0' encoding='UTF-8'?>",
                                "<ns0:createEpisode xmlns:ns0=\"http://www.mule-health.com/SOA/message/1.0\">",
                                "\t<ns1:PatientId xmlns:ns1=\"http://www.mule-health.com/SOA/model/1.0\">",
                                "\t\t<ns1:id kind=\"mrn\" xml:lang=\"en\">7</ns1:id>",
                                "\t\t<value xmlns=\"urn:v\">",
                                "\t\t\t<inner>1</inner>",
                                "\t\t\t<plain xmlns=\"\">2</plain>",
                                "\t\t</value>",
                                "\t\t<x:ext xmlns:x=\"urn:x\" x:flag=\"true\"/>",
                                "\t\t<note>n</note>",
                                "\t</ns1:PatientId>",
                                "</ns0:createEpisode>")),
                Arguments.of("declarationAttributesOfChildBuiltWithoutNamespaceAwarenessSkipped",
                        patientIdWithNamespaceUnawareChild(),
                        String.join("\n",
                                "<?xml version='1.0' encoding='UTF-8'?>",
                                "<ns0:createEpisode xmlns:ns0=\"http://www.mule-health.com/SOA/message/1.0\">",
                                "\t<ns1:PatientId xmlns:ns1=\"http://www.mule-health.com/SOA/model/1.0\">",
                                "\t\t<entry code=\"A\">1</entry>",
                                "\t</ns1:PatientId>",
                                "</ns0:createEpisode>")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("structuredInputs")
    void copiesStructuredPatientIdAsObject(String scenario, Element upsertPatientResponse, String expected) {
        assertThat(mapper.toXml(upsertPatientResponse)).isEqualTo(expected);
    }

    @Test
    void toElementFailsWithoutStderrWhenOutputIsNotWellFormed() {
        // A DOM text node may hold U+0001, which the writer copies unescaped and no XML 1.0 parser accepts.
        Element upsertPatientResponse = namespaceUnawareRootWithPatientId("P\u00011");
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Throwable thrown;
        System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
        try {
            thrown = catchThrowable(() -> mapper.toElement(upsertPatientResponse));
        } finally {
            System.setErr(originalErr);
        }

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("DataWeave XML output could not be parsed: ")
                .hasCauseInstanceOf(SAXParseException.class);
        assertThat(stderr.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void constants() {
        assertThat(CreateEpisodeRequestMapper.NS0).isEqualTo("http://www.mule-health.com/SOA/message/1.0");
        assertThat(CreateEpisodeRequestMapper.NS1).isEqualTo("http://www.mule-health.com/SOA/model/1.0");
        assertThat(CreateEpisodeRequestMapper.NS0_PREFIX).isEqualTo("ns0");
        assertThat(CreateEpisodeRequestMapper.NS1_PREFIX).isEqualTo("ns1");
    }

    /** Asserts the {@code ns0:createEpisode} root and returns its only child element, the model PatientId. */
    private static Element patientIdOf(Element createEpisode) {
        assertThat(createEpisode.getNamespaceURI()).isEqualTo(MESSAGE_NS);
        assertThat(createEpisode.getLocalName()).isEqualTo("createEpisode");
        assertThat(createEpisode.getPrefix()).isEqualTo("ns0");
        List<Element> children = new ArrayList<>();
        for (Node node = createEpisode.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                children.add((Element) node);
            }
        }
        assertThat(children).hasSize(1);
        Element patientId = children.get(0);
        assertThat(patientId.getNamespaceURI()).isEqualTo(MODEL_NS);
        assertThat(patientId.getLocalName()).isEqualTo("PatientId");
        return patientId;
    }

    /** Each child node as {@code #text:<data>}, or {@code <parent>/<child>} by qualified name for an element. */
    private static List<String> childNodeSummary(Element parent) {
        List<String> summary = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            summary.add(node.getNodeType() == Node.TEXT_NODE
                    ? "#text:" + node.getNodeValue()
                    : parent.getNodeName() + "/" + node.getNodeName());
        }
        return summary;
    }

    /**
     * A root created without namespace awareness, so its local name is {@code null} and its node name
     * {@code m:upsertPatientResponse}, holding a namespace-aware model PatientId with the given text.
     */
    private static Element namespaceUnawareRootWithPatientId(String text) {
        Document document = newDocumentBuilder(true).newDocument();
        Element root = document.createElement("m:upsertPatientResponse");
        document.appendChild(root);
        Element patientId = document.createElementNS(MODEL_NS, "d:PatientId");
        patientId.setTextContent(text);
        root.appendChild(patientId);
        return root;
    }

    /**
     * A namespace-aware response whose model PatientId holds one child created without namespace awareness, whose
     * {@code xmlns} and {@code xmlns:q} attributes therefore carry no namespace URI and are known by name only.
     */
    private static Element patientIdWithNamespaceUnawareChild() {
        Document document = newDocumentBuilder(true).newDocument();
        Element root = document.createElementNS(MESSAGE_NS, "ns0:upsertPatientResponse");
        document.appendChild(root);
        Element patientId = document.createElementNS(MODEL_NS, "ns1:PatientId");
        root.appendChild(patientId);
        Element entry = document.createElement("entry");
        entry.setAttribute("xmlns", "urn:z");
        entry.setAttribute("xmlns:q", "urn:q");
        entry.setAttribute("code", "A");
        entry.setTextContent("1");
        patientId.appendChild(entry);
        return root;
    }

    private static Element parse(String xml) {
        return parse(xml, true);
    }

    private static Element parseWithoutNamespaces(String xml) {
        return parse(xml, false);
    }

    private static Element parse(String xml, boolean namespaceAware) {
        try {
            return newDocumentBuilder(namespaceAware).parse(new InputSource(new StringReader(xml)))
                    .getDocumentElement();
        } catch (SAXException | IOException e) {
            throw new IllegalArgumentException("Test input is not well-formed XML: " + xml, e);
        }
    }

    private static DocumentBuilder newDocumentBuilder(boolean namespaceAware) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        try {
            factory.setNamespaceAware(namespaceAware);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("Cannot configure a secure XML parser", e);
        }
    }
}
