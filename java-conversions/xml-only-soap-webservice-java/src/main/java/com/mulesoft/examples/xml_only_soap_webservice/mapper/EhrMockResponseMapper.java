/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.xml_only_soap_webservice.mapper;

import java.io.IOException;
import java.io.StringReader;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

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
 * Reimplements the two DataWeave scripts of the mock {@code EHRService} flow,
 * {@code xml-only-soap-webservice/src/main/app/mocks.xml} lines 45-84, by hand:
 * <ul>
 *   <li>line 52, the {@code when flowVars.operation == 'createEpisode'} branch:
 *       {@code ns0#createEpisodeResponse > ns1#Episode} with {@code episodeId: now as :string},
 *       {@code ns1#PatientId: payload.ns0#createEpisode.ns1#PatientId}, {@code admission: "Elective"},
 *       {@code startDate: now}, {@code endDate: now} and {@code care: "Private"};</li>
 *   <li>line 71, the {@code otherwise} branch:
 *       {@code ns0#findEpisodesResponse > ns1#Episode > ns1#PatientId: payload.ns0#findEpisodes.ns1#PatientId}.</li>
 * </ul>
 * {@code ns0} is {@link #NS0}, {@code ns1} is {@link #NS1}, and both scripts declare
 * {@code %output application/xml}, so the bytes come from {@link DwXmlWriter}.
 *
 * <p>The mock's {@code cxf:proxy-service payload="body"} (line 47) makes the payload the SOAP Body's root
 * element, which is what every method here receives. The operation choice (lines 48-50) belongs to
 * {@code service/EhrMockService}; this class only shapes the data.
 *
 * <p>Each script has two entry points: {@code <name>Xml(request)} returns the DataWeave XML text, and
 * {@code <name>(request)} parses that same text into the element the mock endpoint returns as its SOAP
 * response payload, so the whitespace text nodes of DataWeave's layout travel inside the envelope.
 *
 * <p>The selector {@code payload.ns0#X.ns1#PatientId} is namespace-qualified: the request root must be
 * {@code {NS0}X} and the value is its first child {@code {NS1}PatientId}. A leaf's text content is copied
 * unchanged; a missing request, root or child, or blank text, is null and written as {@link DwXmlWriter}
 * writes a null ({@code <ns1:PatientId/>}). A {@code PatientId} holding elements, which the mock's callers never
 * send, is read as a DataWeave object and copied as nested elements.
 *
 * <p>Every {@code now} reads the injected {@link Clock} (the {@code ClockConfig} bean in the application), is
 * truncated to milliseconds and is written as {@link #NOW_FORMAT}, an ISO-8601 date-time with offset that
 * {@code AdmitSubjectResponseMapper} parses back as a date. One instant is taken per call, so
 * {@code episodeId}, {@code startDate} and {@code endDate} agree. The scenarios built on these values are
 * nondeterministic; DataWeave's string form of {@code now}, and {@code startDate}/{@code endDate} written
 * without {@code as :string} in the same text form as {@code episodeId}, are Tier 2A verification points.
 *
 * <p>Instances are stateless and thread-safe.
 */
@Component
public class EhrMockResponseMapper {

    /** Namespace bound to {@code ns0} in both scripts: the SOA message schema. */
    public static final String NS0 = "http://www.mule-health.com/SOA/message/1.0";

    /** Namespace bound to {@code ns1} in both scripts: the SOA model schema. */
    public static final String NS1 = "http://www.mule-health.com/SOA/model/1.0";

    /** Prefix the scripts declare for {@link #NS0}. */
    public static final String NS0_PREFIX = "ns0";

    /** Prefix the scripts declare for {@link #NS1}. */
    public static final String NS1_PREFIX = "ns1";

    /** Text form of every {@code now}: milliseconds and the clock zone's offset ({@code Z} for UTC). */
    public static final String NOW_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX";

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern(NOW_FORMAT, Locale.ROOT);

    private static final String CREATE_EPISODE = "createEpisode";
    private static final String CREATE_EPISODE_RESPONSE = "createEpisodeResponse";
    private static final String FIND_EPISODES = "findEpisodes";
    private static final String FIND_EPISODES_RESPONSE = "findEpisodesResponse";
    private static final String EPISODE = "Episode";
    private static final String PATIENT_ID = "PatientId";
    private static final String ADMISSION = "Elective";
    private static final String CARE = "Private";

    private static final String DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl";
    private static final String EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities";
    private static final String EXTERNAL_PARAMETER_ENTITIES =
            "http://xml.org/sax/features/external-parameter-entities";
    private static final String LOAD_EXTERNAL_DTD = "http://apache.org/xml/features/nonvalidating/load-external-dtd";
    private static final String XMLNS_PREFIX = "xmlns";
    private static final String XML_PREFIX = "xml";

    /** Raises every parser diagnostic, so nothing is printed to stderr. */
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
    private final Clock clock;

    /**
     * Creates the mapper.
     *
     * @param writer the DataWeave 1.0 XML writer
     * @param clock the clock every {@code now} reads
     * @throws NullPointerException when either argument is {@code null}
     */
    public EhrMockResponseMapper(DwXmlWriter writer, Clock clock) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Renders the {@code createEpisode} branch, script {@code mocks.xml:52}.
     *
     * <pre>{@code
     * <?xml version='1.0' encoding='UTF-8'?>
     * <ns0:createEpisodeResponse xmlns:ns0="http://www.mule-health.com/SOA/message/1.0">
     *     <ns1:Episode xmlns:ns1="http://www.mule-health.com/SOA/model/1.0">
     *         <episodeId>2024-05-06T07:08:09.123Z</episodeId>
     *         <ns1:PatientId>P1</ns1:PatientId>
     *         <admission>Elective</admission>
     *         <startDate>2024-05-06T07:08:09.123Z</startDate>
     *         <endDate>2024-05-06T07:08:09.123Z</endDate>
     *         <care>Private</care>
     *     </ns1:Episode>
     * </ns0:createEpisodeResponse>
     * }</pre>
     * (indentation shown as spaces; the writer emits tabs)
     *
     * @param request the SOAP Body root, expected to be {@code {NS0}createEpisode}; may be {@code null}
     * @return the DataWeave XML text, without a trailing line feed
     * @throws IllegalArgumentException when a copied element or attribute name is not an XML name, which only a DOM
     *         built without strict error checking can hold; the writer refuses it before anything is written
     */
    public String createEpisodeResponseXml(Element request) {
        String now = now();
        DwXmlWriter.Element episode = DwXmlWriter.element(NS1_PREFIX, NS1, EPISODE).children(
                DwXmlWriter.element("episodeId").text(now),
                patientId(request, CREATE_EPISODE),
                DwXmlWriter.element("admission").text(ADMISSION),
                DwXmlWriter.element("startDate").text(now),
                DwXmlWriter.element("endDate").text(now),
                DwXmlWriter.element("care").text(CARE));
        return writer.write(DwXmlWriter.element(NS0_PREFIX, NS0, CREATE_EPISODE_RESPONSE).child(episode));
    }

    /**
     * Returns the document element of {@link #createEpisodeResponseXml(Element)}, rendered once.
     *
     * @param request the SOAP Body root, expected to be {@code {NS0}createEpisode}; may be {@code null}
     * @return the {@code {NS0}createEpisodeResponse} element
     * @throws IllegalArgumentException under the conditions of {@link #createEpisodeResponseXml(Element)}
     * @throws IllegalStateException when the rendered text cannot be parsed
     */
    public Element createEpisodeResponse(Element request) {
        return parse(createEpisodeResponseXml(request));
    }

    /**
     * Renders the {@code otherwise} branch, script {@code mocks.xml:71}: every operation other than
     * {@code createEpisode} answers with it, echoing {@code payload.ns0#findEpisodes.ns1#PatientId}.
     *
     * <pre>{@code
     * <?xml version='1.0' encoding='UTF-8'?>
     * <ns0:findEpisodesResponse xmlns:ns0="http://www.mule-health.com/SOA/message/1.0">
     *     <ns1:Episode xmlns:ns1="http://www.mule-health.com/SOA/model/1.0">
     *         <ns1:PatientId>P1</ns1:PatientId>
     *     </ns1:Episode>
     * </ns0:findEpisodesResponse>
     * }</pre>
     * (indentation shown as spaces; the writer emits tabs)
     *
     * @param request the SOAP Body root, expected to be {@code {NS0}findEpisodes}; may be {@code null}
     * @return the DataWeave XML text, without a trailing line feed
     * @throws IllegalArgumentException when a copied element or attribute name is not an XML name, which only a DOM
     *         built without strict error checking can hold; the writer refuses it before anything is written
     */
    public String findEpisodesResponseXml(Element request) {
        DwXmlWriter.Element episode = DwXmlWriter.element(NS1_PREFIX, NS1, EPISODE)
                .child(patientId(request, FIND_EPISODES));
        return writer.write(DwXmlWriter.element(NS0_PREFIX, NS0, FIND_EPISODES_RESPONSE).child(episode));
    }

    /**
     * Returns the document element of {@link #findEpisodesResponseXml(Element)}.
     *
     * @param request the SOAP Body root, expected to be {@code {NS0}findEpisodes}; may be {@code null}
     * @return the {@code {NS0}findEpisodesResponse} element
     * @throws IllegalArgumentException under the conditions of {@link #findEpisodesResponseXml(Element)}
     * @throws IllegalStateException when the rendered text cannot be parsed
     */
    public Element findEpisodesResponse(Element request) {
        return parse(findEpisodesResponseXml(request));
    }

    private String now() {
        return ZonedDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS).format(FORMATTER);
    }

    /** Builds {@code ns1:PatientId} from {@code payload.ns0#<wrapper>.ns1#PatientId}. */
    private static DwXmlWriter.Element patientId(Element request, String wrapper) {
        DwXmlWriter.Element target = DwXmlWriter.element(NS1_PREFIX, NS1, PATIENT_ID);
        Element source = selectPatientId(request, wrapper);
        if (source != null) {
            // No default namespace is in scope at ns1:PatientId: its ancestors are both prefixed.
            fill(target, source, "");
        }
        return target;
    }

    private static Element selectPatientId(Element request, String wrapper) {
        if (request == null || !NS0.equals(request.getNamespaceURI()) || !wrapper.equals(request.getLocalName())) {
            return null;
        }
        for (Node node = request.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child && NS1.equals(child.getNamespaceURI())
                    && PATIENT_ID.equals(child.getLocalName())) {
                return child;
            }
        }
        return null;
    }

    /**
     * Gives {@code target} the value of {@code source}: its element children when it has any (DataWeave reads
     * such an element as an object, so its text nodes are dropped), otherwise its text, blank text being null.
     */
    private static void fill(DwXmlWriter.Element target, Element source, String defaultNamespace) {
        List<Element> children = childElements(source);
        if (children.isEmpty()) {
            String text = source.getTextContent();
            if (!text.isBlank()) {
                target.text(text);
            }
            return;
        }
        for (Element child : children) {
            target.child(copy(child, defaultNamespace));
        }
    }

    /**
     * Copies one element of an object-valued {@code PatientId} with its prefix, namespace URI, namespace
     * declarations and attributes. {@code defaultNamespace} is the default namespace in scope in the output
     * ({@code ""} for none), so an unqualified element under a default namespace undeclares it.
     */
    private static DwXmlWriter.Element copy(Element source, String defaultNamespace) {
        String prefix = emptyToNull(source.getPrefix());
        String uri = emptyToNull(source.getNamespaceURI());
        DwXmlWriter.Element target = DwXmlWriter.element(prefix, uri, localName(source));

        String childDefault = defaultNamespace;
        if (prefix == null) {
            if (uri == null && !defaultNamespace.isEmpty()) {
                target.namespace(null, "");
            }
            childDefault = uri == null ? "" : uri;
        }

        NamedNodeMap attributes = source.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr attribute = (Attr) attributes.item(i);
            String name = attribute.getName();
            // Matched by qualified name, so declarations of nodes built without namespace awareness count too.
            if (XMLNS_PREFIX.equals(name) || name.startsWith(XMLNS_PREFIX + ':')) {
                String declared = name.length() > XMLNS_PREFIX.length()
                        ? name.substring(XMLNS_PREFIX.length() + 1) : null;
                // An empty URI can only undeclare the default namespace; XML 1.0 has no prefix undeclaration.
                if (declared == null || !attribute.getValue().isEmpty()) {
                    target.namespace(declared, attribute.getValue());
                    if (declared == null) {
                        childDefault = attribute.getValue();
                    }
                }
                continue;
            }
            String attributePrefix = emptyToNull(attribute.getPrefix());
            String attributeUri = emptyToNull(attribute.getNamespaceURI());
            if (attributePrefix != null && attributeUri != null && !XML_PREFIX.equals(attributePrefix)) {
                target.namespace(attributePrefix, attributeUri);
            }
            target.attribute(name, attribute.getValue());
        }

        fill(target, source, childDefault);
        return target;
    }

    private static List<Element> childElements(Element parent) {
        List<Element> children = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child) {
                children.add(child);
            }
        }
        return children;
    }

    private static String localName(Element element) {
        String localName = element.getLocalName();
        if (localName != null) {
            return localName;
        }
        String nodeName = element.getNodeName();
        return nodeName.substring(nodeName.indexOf(':') + 1);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static Element parse(String xml) {
        try {
            return newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();
        } catch (SAXException | IOException e) {
            throw new IllegalStateException("Cannot parse the EHRService mock response: " + e.getMessage(), e);
        }
    }

    private static DocumentBuilder newDocumentBuilder() {
        // The JDK's built-in implementation, so every feature below is recognized whatever parser the classpath
        // offers through the DocumentBuilderFactory service lookup.
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        try {
            factory.setNamespaceAware(true);
            factory.setIgnoringElementContentWhitespace(false);
            factory.setFeature(DISALLOW_DOCTYPE, true);
            factory.setFeature(EXTERNAL_GENERAL_ENTITIES, false);
            factory.setFeature(EXTERNAL_PARAMETER_ENTITIES, false);
            factory.setFeature(LOAD_EXTERNAL_DTD, false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(RETHROWING_ERROR_HANDLER);
            return builder;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("Cannot configure a secure XML parser: " + e.getMessage(), e);
        }
    }
}
