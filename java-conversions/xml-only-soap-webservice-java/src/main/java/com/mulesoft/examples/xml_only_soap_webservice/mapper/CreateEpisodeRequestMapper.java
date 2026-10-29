/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.xml_only_soap_webservice.mapper;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.springframework.stereotype.Component;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Hand-written reimplementation of the {@code %dw 1.0} script "upsertPatientResponse to createEpisode"
 * ({@code xml-only-soap-webservice/src/main/app/Hospital_Admissions_SOA.xml}, sub-flow {@code createEpisode},
 * header line 73), of which this class is the sole owner:
 * <pre>
 * %dw 1.0
 * %namespace ns0 http://www.mule-health.com/SOA/message/1.0
 * %namespace ns1 http://www.mule-health.com/SOA/model/1.0
 * %output application/xml
 * ---
 * ns0#"createEpisode": {
 *     ns1#"PatientId": payload.upsertPatientResponse.ns1#PatientId
 * }
 * </pre>
 * The payload is the PatientService response body, {@code ns0:upsertPatientResponse} holding
 * {@code ns1:PatientId} ({@code xml-only-soap-webservice/src/main/app/mocks.xml}, lines 12-19); the original's
 * {@code cxf:proxy-client payload="body"} had already removed the envelope. The result is the
 * {@code createEpisode} request sent to the EHRService.
 *
 * <p>Selector semantics, as DataWeave 1.0 evaluates the script against an XML payload:
 * <ul>
 *   <li>{@code payload.upsertPatientResponse} is unqualified, so it matches a root element whose local name is
 *       {@code upsertPatientResponse} in any namespace, or none;</li>
 *   <li>{@code .ns1#PatientId} is qualified, so it matches the first child element whose namespace URI is exactly
 *       {@link #NS1} and whose local name is {@code PatientId}; a {@code PatientId} in another namespace, or in
 *       none, does not match;</li>
 *   <li>a {@code null} input, another root, or no matching child selects {@code null};</li>
 *   <li>a matched element without child elements selects its text, unchanged; empty or whitespace-only text
 *       reads as {@code null}, as DataWeave reads blank XML content;</li>
 *   <li>a matched element with child elements selects an object, written as copies of those children with
 *       their local names, namespaces, prefixes and attributes, and with blank text between them dropped.</li>
 * </ul>
 * A selected {@code null} is written as the empty element {@code <ns1:PatientId xmlns:ns1="..."/>}. Bytes and
 * layout are {@link DwXmlWriter}'s; for the PatientService mock's response the result is:
 * <pre>{@code
 * <?xml version='1.0' encoding='UTF-8'?>
 * <ns0:createEpisode xmlns:ns0="http://www.mule-health.com/SOA/message/1.0">
 *     <ns1:PatientId xmlns:ns1="http://www.mule-health.com/SOA/model/1.0">2024-01-02T03:04:05.006Z</ns1:PatientId>
 * </ns0:createEpisode>
 * }</pre>
 * (indentation shown as spaces; the writer emits a tab, and nothing follows the root end tag).
 *
 * <p>Transformation only: no I/O, no configuration, no logging. Stateless and thread-safe.
 */
@Component
public class CreateEpisodeRequestMapper {

    /** Namespace of the SOA messages, the script's {@code ns0}. */
    public static final String NS0 = "http://www.mule-health.com/SOA/message/1.0";

    /** Namespace of the SOA model, the script's {@code ns1}. */
    public static final String NS1 = "http://www.mule-health.com/SOA/model/1.0";

    /** Prefix the script declares for {@link #NS0}. */
    public static final String NS0_PREFIX = "ns0";

    /** Prefix the script declares for {@link #NS1}. */
    public static final String NS1_PREFIX = "ns1";

    private static final String CREATE_EPISODE = "createEpisode";

    private static final String UPSERT_PATIENT_RESPONSE = "upsertPatientResponse";

    private static final String PATIENT_ID = "PatientId";

    private static final String DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl";
    private static final String EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities";
    private static final String EXTERNAL_PARAMETER_ENTITIES =
            "http://xml.org/sax/features/external-parameter-entities";

    /** Raises every warning, error and fatal error, so the parser never writes diagnostics to stderr. */
    private static final ErrorHandler RETHROWING_ERROR_HANDLER = new ErrorHandler() {
        @Override
        public void warning(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    };

    private final DwXmlWriter writer;

    /**
     * Creates the mapper.
     *
     * @param writer the DataWeave XML writer that renders the element tree
     */
    public CreateEpisodeRequestMapper(DwXmlWriter writer) {
        this.writer = writer;
    }

    /**
     * Builds the {@code ns0:createEpisode} document the script produced from the PatientService response body.
     *
     * @param upsertPatientResponse the response body's root element; {@code null} is accepted and selects
     *        {@code null}, as every non-matching input does
     * @return the DataWeave XML document, declaration included, without a trailing line feed
     * @throws IllegalArgumentException when a copied element or attribute name is not an XML name, which only a DOM
     *         built without strict error checking can hold; the writer refuses it before anything is written
     */
    public String toXml(Element upsertPatientResponse) {
        DwXmlWriter.Element patientId = DwXmlWriter.element(NS1_PREFIX, NS1, PATIENT_ID);
        Element selected = selectPatientId(upsertPatientResponse);
        if (selected != null) {
            // Neither output ancestor binds the default namespace, so a copied child starts outside one.
            fillContent(patientId, selected, null);
        }
        return writer.write(DwXmlWriter.element(NS0_PREFIX, NS0, CREATE_EPISODE).child(patientId));
    }

    /**
     * Builds the {@code ns0:createEpisode} document and parses it into a namespace-aware DOM, the form the
     * EHRService client sends as the SOAP body. Whitespace text nodes between elements are kept, as the original
     * sent the DataWeave output unchanged.
     *
     * @param upsertPatientResponse the response body's root element, as for {@link #toXml(Element)}
     * @return the document element, {@code createEpisode} in {@link #NS0}
     * @throws IllegalArgumentException under the conditions of {@link #toXml(Element)}
     * @throws IllegalStateException when no secure XML parser can be configured, or the writer's output cannot
     *         be parsed
     */
    public Element toElement(Element upsertPatientResponse) {
        String xml = toXml(upsertPatientResponse);
        DocumentBuilder builder = newDocumentBuilder();
        try {
            return builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
        } catch (SAXException | IOException e) {
            throw new IllegalStateException("DataWeave XML output could not be parsed: " + e.getMessage(), e);
        }
    }

    /** {@code payload.upsertPatientResponse.ns1#PatientId}: the matched element, or {@code null}. */
    private static Element selectPatientId(Element upsertPatientResponse) {
        if (upsertPatientResponse == null || !UPSERT_PATIENT_RESPONSE.equals(localName(upsertPatientResponse))) {
            return null;
        }
        for (Element child : childElements(upsertPatientResponse)) {
            if (NS1.equals(child.getNamespaceURI()) && PATIENT_ID.equals(child.getLocalName())) {
                return child;
            }
        }
        return null;
    }

    /**
     * Writes a selected element's value into {@code target}: copies of its child elements when it has any,
     * otherwise its text, which stays unset (written as {@code null}) when blank.
     *
     * @param defaultUri the default namespace the output binds at {@code target}, {@code null} for none
     */
    private static void fillContent(DwXmlWriter.Element target, Element source, String defaultUri) {
        List<Element> children = childElements(source);
        if (children.isEmpty()) {
            String text = source.getTextContent();
            target.text(isBlank(text) ? null : text);
            return;
        }
        for (Element child : children) {
            target.child(copy(child, defaultUri));
        }
    }

    private static DwXmlWriter.Element copy(Element source, String inheritedDefaultUri) {
        String uri = emptyToNull(source.getNamespaceURI());
        String prefix = emptyToNull(source.getPrefix());
        DwXmlWriter.Element target = DwXmlWriter.element(prefix, uri, localName(source));

        String defaultUri = inheritedDefaultUri;
        if (prefix == null) {
            if (uri != null) {
                defaultUri = uri;
            } else if (inheritedDefaultUri != null) {
                // An unqualified child of a default-namespaced copy must undeclare it, or it would change namespace.
                target.namespace(null, "");
                defaultUri = null;
            }
        }

        NamedNodeMap attributes = source.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr attribute = (Attr) attributes.item(i);
            if (isNamespaceDeclaration(attribute)) {
                continue;
            }
            String attributePrefix = emptyToNull(attribute.getPrefix());
            String attributeUri = emptyToNull(attribute.getNamespaceURI());
            if (attributePrefix != null && attributeUri != null
                    && !XMLConstants.XML_NS_PREFIX.equals(attributePrefix)) {
                target.namespace(attributePrefix, attributeUri);
            }
            target.attribute(attribute.getName(), attribute.getValue());
        }

        fillContent(target, source, defaultUri);
        return target;
    }

    /** {@code xmlns} and {@code xmlns:*} are declarations the writer derives from names, not attributes. */
    private static boolean isNamespaceDeclaration(Attr attribute) {
        String name = attribute.getName();
        return XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())
                || XMLConstants.XMLNS_ATTRIBUTE.equals(name)
                || name.startsWith(XMLConstants.XMLNS_ATTRIBUTE + ":");
    }

    private static List<Element> childElements(Element parent) {
        List<Element> children = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                children.add((Element) node);
            }
        }
        return children;
    }

    /** The local name, also for a node built without namespace awareness, whose {@code getLocalName()} is null. */
    private static String localName(Node node) {
        String localName = node.getLocalName();
        if (localName != null) {
            return localName;
        }
        String nodeName = node.getNodeName();
        return nodeName.substring(nodeName.indexOf(':') + 1);
    }

    /** Empty, or only XML white space (space, tab, carriage return, line feed; XML 1.0 production S). */
    private static boolean isBlank(String text) {
        if (text == null) {
            return true;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
                return false;
            }
        }
        return true;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static DocumentBuilder newDocumentBuilder() {
        // The JDK's built-in implementation, so every feature and attribute below is recognized whatever parser
        // the classpath offers through the DocumentBuilderFactory service lookup.
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        try {
            factory.setNamespaceAware(true);
            factory.setIgnoringElementContentWhitespace(false);
            factory.setFeature(DISALLOW_DOCTYPE, true);
            factory.setFeature(EXTERNAL_GENERAL_ENTITIES, false);
            factory.setFeature(EXTERNAL_PARAMETER_ENTITIES, false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(RETHROWING_ERROR_HANDLER);
            return builder;
        } catch (ParserConfigurationException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot configure a secure XML parser: " + e.getMessage(), e);
        }
    }
}
