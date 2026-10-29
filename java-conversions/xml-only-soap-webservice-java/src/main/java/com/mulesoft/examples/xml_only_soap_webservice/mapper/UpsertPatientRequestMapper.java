/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.xml_only_soap_webservice.mapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.springframework.stereotype.Component;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Hand-written reimplementation of the {@code %dw 1.0} script "admitSubject to upsertPatient"
 * ({@code xml-only-soap-webservice/src/main/app/Hospital_Admissions_SOA.xml}, sub-flow {@code upsertPatient},
 * header line 53), of which this class is the sole owner:
 * <pre>
 * %dw 1.0
 * %namespace ns0 http://www.mule-health.com/SOA/message/1.0
 * %namespace ns1 http://www.mule-health.com/SOA/model/1.0
 * %output application/xml
 * ---
 * ns0#"upsertPatient": {
 *     ns1#"Subject": payload.Envelope.Body.admitSubject.Subject
 * }
 * </pre>
 * The original {@code cxf:proxy-service payload="envelope"} handed the script the whole SOAP envelope;
 * {@code controller/AdmissionServiceEndpoint} passes the SOAP Body child {@code admitSubject} instead, the node
 * the script reaches through {@code Envelope.Body}, and {@code service/AdmissionOrchestrationService} sends the
 * result to the {@code PatientService} as the request body.
 *
 * <p>Selection, as DataWeave 1.0 evaluates the unqualified selectors:
 * <ul>
 *   <li>the argument must have the local name {@code admitSubject}, in any namespace; otherwise, and for
 *       {@code null}, the selected value is null;</li>
 *   <li>{@code Subject} is the first child element of {@code admitSubject} with the local name {@code Subject},
 *       in any namespace, or null when there is none.</li>
 * </ul>
 *
 * <p>Value written under {@code ns1:Subject}, as DataWeave reads XML:
 * <ul>
 *   <li>an element with child elements is an object: each child element is copied in document order, repeated
 *       names kept, with its own local name, namespace URI and prefix (the unqualified children of the SOA model
 *       stay unqualified, with no {@code xmlns} added), its attributes except namespace declarations, and its
 *       content by these same rules; text, CDATA, comments and processing instructions beside child elements
 *       are dropped;</li>
 *   <li>an element without child elements is its text content; empty or whitespace-only text is null;</li>
 *   <li>null is written as the empty element {@code <q/>}.</li>
 * </ul>
 * The attributes of {@code Subject} itself are not copied, because the script's new key {@code ns1#Subject}
 * carries none. Attributes are copied in the order the DOM reports them; the JDK DOM sorts them by name.
 *
 * <p>Bytes and layout are {@link DwXmlWriter}'s. For the original {@code message.xml} the result is:
 * <pre>{@code
 * <?xml version='1.0' encoding='UTF-8'?>
 * <ns0:upsertPatient xmlns:ns0="http://www.mule-health.com/SOA/message/1.0">
 *     <ns1:Subject xmlns:ns1="http://www.mule-health.com/SOA/model/1.0">
 *         <nationalId>1234</nationalId>
 *         ...
 *         <address2/>
 *         <address3/>
 *         ...
 *         <dateOfBirth>1970-08-07</dateOfBirth>
 *     </ns1:Subject>
 * </ns0:upsertPatient>
 * }</pre>
 * (indentation shown as spaces; the writer emits tabs, and nothing follows the root end tag). Without a
 * selectable {@code Subject} the root holds the null form
 * {@code <ns1:Subject xmlns:ns1="http://www.mule-health.com/SOA/model/1.0"/>}.
 *
 * <p>Transformation only: no I/O, no configuration, no logging. Stateless and thread-safe.
 */
@Component
public class UpsertPatientRequestMapper {

    /** Namespace of the SOA messages, the script's {@code ns0}. */
    public static final String NS0 = "http://www.mule-health.com/SOA/message/1.0";

    /** Namespace of the SOA model, the script's {@code ns1}. */
    public static final String NS1 = "http://www.mule-health.com/SOA/model/1.0";

    /** Prefix the script declares for {@link #NS0}. */
    public static final String NS0_PREFIX = "ns0";

    /** Prefix the script declares for {@link #NS1}. */
    public static final String NS1_PREFIX = "ns1";

    private static final String ROOT = "upsertPatient";
    private static final String ADMIT_SUBJECT = "admitSubject";
    private static final String SUBJECT = "Subject";

    private static final String NO_NAMESPACE = "";

    private static final String DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl";
    private static final String EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities";
    private static final String EXTERNAL_PARAMETER_ENTITIES =
            "http://xml.org/sax/features/external-parameter-entities";
    private static final String LOAD_EXTERNAL_DTD = "http://apache.org/xml/features/nonvalidating/load-external-dtd";

    private final DwXmlWriter writer;

    /**
     * Creates the mapper.
     *
     * @param writer the DataWeave XML writer that renders the element tree
     */
    public UpsertPatientRequestMapper(DwXmlWriter writer) {
        this.writer = writer;
    }

    /**
     * Builds the {@code ns0:upsertPatient} document the script produced.
     *
     * @param admitSubject the SOAP Body child of the {@code AdmissionService} request, from a namespace-aware
     *        DOM; {@code null} for an empty Body
     * @return the DataWeave XML document, declaration included, without a trailing line feed
     * @throws IllegalArgumentException when a copied element or attribute name is not an XML name, which only a DOM
     *         built without strict error checking can hold; the writer refuses it before anything is written
     */
    public String toXml(Element admitSubject) {
        DwXmlWriter.Element subject = DwXmlWriter.element(NS1_PREFIX, NS1, SUBJECT);
        Element selected = selectSubject(admitSubject);
        if (selected != null) {
            // Neither ns0:upsertPatient nor ns1:Subject declares a default namespace.
            copyContent(selected, subject, NO_NAMESPACE);
        }
        return writer.write(DwXmlWriter.element(NS0_PREFIX, NS0, ROOT).child(subject));
    }

    /**
     * Builds the {@code ns0:upsertPatient} document and parses it into a namespace-aware DOM, the form the
     * {@code PatientService} client sends as the request body. Whitespace text between elements is kept, as the
     * original sent the DataWeave output unchanged.
     *
     * @param admitSubject the SOAP Body child of the {@code AdmissionService} request; {@code null} for an empty
     *        Body
     * @return the document element, {@code upsertPatient} in {@link #NS0}
     * @throws IllegalArgumentException under the conditions of {@link #toXml(Element)}
     * @throws IllegalStateException when no secure XML parser can be configured, or the writer's output cannot be
     *         parsed
     */
    public Element toElement(Element admitSubject) {
        byte[] xml = toXml(admitSubject).getBytes(StandardCharsets.UTF_8);
        DocumentBuilder builder = newDocumentBuilder();
        try {
            return builder.parse(new ByteArrayInputStream(xml)).getDocumentElement();
        } catch (SAXException | IOException e) {
            throw new IllegalStateException("upsertPatient XML could not be parsed: " + e.getMessage(), e);
        }
    }

    /** {@code admitSubject.Subject}: the first {@code Subject} child of an {@code admitSubject}, else null. */
    private static Element selectSubject(Element admitSubject) {
        if (admitSubject == null || !ADMIT_SUBJECT.equals(localName(admitSubject))) {
            return null;
        }
        for (Node node = admitSubject.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE && SUBJECT.equals(localName(node))) {
                return (Element) node;
            }
        }
        return null;
    }

    /**
     * Writes the DataWeave value of {@code source} into {@code target}: its child elements, or its non-blank text.
     *
     * @param defaultUri the default namespace in scope at {@code target} in the output, {@code ""} for none
     */
    private static void copyContent(Element source, DwXmlWriter.Element target, String defaultUri) {
        if (!hasElementChildren(source)) {
            String text = source.getTextContent();
            if (text != null && !text.isBlank()) {
                target.text(text);
            }
            return;
        }
        for (Node node = source.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                target.child(copyElement((Element) node, defaultUri));
            }
        }
    }

    private static DwXmlWriter.Element copyElement(Element source, String defaultUri) {
        String prefix = source.getPrefix();
        String uri = source.getNamespaceURI() == null ? NO_NAMESPACE : source.getNamespaceURI();
        // A node built without namespace support has no local name; its qualified name is written as it is.
        String name = source.getLocalName() != null ? source.getLocalName() : source.getNodeName();

        DwXmlWriter.Element copy;
        String childDefaultUri = defaultUri;
        if (prefix != null && !prefix.isEmpty()) {
            copy = DwXmlWriter.element(prefix, uri, name);
        } else {
            copy = DwXmlWriter.element(null, uri, name);
            if (uri.isEmpty() && !defaultUri.isEmpty()) {
                // An unqualified element under a default-namespace ancestor must undeclare it to stay unqualified.
                copy.namespace(null, NO_NAMESPACE);
            }
            childDefaultUri = uri;
        }
        copyAttributes(source, copy);
        copyContent(source, copy, childDefaultUri);
        return copy;
    }

    private static void copyAttributes(Element source, DwXmlWriter.Element target) {
        NamedNodeMap attributes = source.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr attribute = (Attr) attributes.item(i);
            if (isNamespaceDeclaration(attribute)) {
                continue;
            }
            String prefix = attribute.getPrefix();
            String uri = attribute.getNamespaceURI();
            // A qualified attribute keeps its namespace, so its prefix must be bound in the output too; the xml
            // prefix is bound by definition.
            if (prefix != null && !prefix.isEmpty() && uri != null && !XMLConstants.XML_NS_PREFIX.equals(prefix)) {
                target.namespace(prefix, uri);
            }
            target.attribute(attribute.getNodeName(), attribute.getValue());
        }
    }

    private static boolean isNamespaceDeclaration(Attr attribute) {
        String name = attribute.getNodeName();
        return XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())
                || XMLConstants.XMLNS_ATTRIBUTE.equals(name)
                || name.startsWith(XMLConstants.XMLNS_ATTRIBUTE + ":");
    }

    private static boolean hasElementChildren(Element element) {
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                return true;
            }
        }
        return false;
    }

    /** The local name, also for a node built without namespace support, whose qualified name is split here. */
    private static String localName(Node node) {
        String localName = node.getLocalName();
        if (localName != null) {
            return localName;
        }
        String name = node.getNodeName();
        return name.substring(name.indexOf(':') + 1);
    }

    /** A new builder per call: a {@link DocumentBuilderFactory} is not guaranteed to be thread-safe. */
    private static DocumentBuilder newDocumentBuilder() {
        // The JDK's built-in implementation, so every setting below is recognized whatever parser the classpath
        // offers through the DocumentBuilderFactory service lookup.
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        try {
            factory.setNamespaceAware(true);
            factory.setFeature(DISALLOW_DOCTYPE, true);
            factory.setFeature(EXTERNAL_GENERAL_ENTITIES, false);
            factory.setFeature(EXTERNAL_PARAMETER_ENTITIES, false);
            factory.setFeature(LOAD_EXTERNAL_DTD, false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder();
        } catch (ParserConfigurationException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot configure a secure XML parser: " + e.getMessage(), e);
        }
    }
}
