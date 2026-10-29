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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.Function;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.springframework.stereotype.Component;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Builds the {@code admitSubjectResponse} the AdmissionService returns from the EHRService
 * {@code createEpisodeResponse}: the {@code %dw 1.0} script of
 * {@code xml-only-soap-webservice/src/main/app/Hospital_Admissions_SOA.xml:26-46}, reimplemented by hand and
 * written through {@link DwXmlWriter}.
 *
 * <pre>{@code
 * ns0#"admitSubjectResponse": {
 *     ns1#"Episode": {
 *         episodeId: payload.createEpisodeResponse.Episode.episodeId,
 *         ns1#PatientId: payload.createEpisodeResponse.Episode.PatientId,
 *         admission: payload.createEpisodeResponse.Episode.admission,
 *         (startDate: payload.createEpisodeResponse.Episode.startDate as :date) when ...startDate?,
 *         (endDate: (payload.createEpisodeResponse.Episode.endDate as :date) +|P5D|) when ...endDate?,
 *         care: payload.createEpisodeResponse.Episode.care
 *     },
 *     ns1#"Bill ": { costPerNight: "100", initialStateEstimate: "5", runningTotal: "500", status: "ADMITTED" }
 * }
 * }</pre>
 *
 * <p>The input is the response body root the EHRService mock writes
 * ({@code xml-only-soap-webservice/src/main/app/mocks.xml:57-65}):
 * {@code ns0:createEpisodeResponse > ns1:Episode { episodeId, ns1:PatientId, admission, startDate, endDate, care }},
 * with both dates holding a DataWeave {@code now} date-time.
 *
 * <ul>
 *   <li>Selectors: every step of {@code payload.createEpisodeResponse.Episode.<field>} is unqualified, so it
 *       matches by local name in any namespace and takes the first match. A {@code null} input or a root
 *       other than {@code createEpisodeResponse} selects nothing: the four always-written fields are null and
 *       both dates are absent.</li>
 *   <li>Values: a leaf is its text content, unchanged; blank text and a missing element are null, which the
 *       writer renders as an empty element. A field with child elements is copied as those elements, names,
 *       namespaces and attributes kept, as a DataWeave object value is; text mixed in with child elements is
 *       dropped, since DataWeave XML output carries no mixed content.</li>
 *   <li>{@code startDate} (line 36) is written only when the upstream element exists, as that value
 *       coerced to a date. {@code endDate} (line 37) is written only when the upstream {@code endDate} exists,
 *       as that value coerced to a date plus {@code P5D}; it is never derived from {@code startDate}. The
 *       coercion takes the calendar date in the value's own offset or zone, accepting an offset date-time, a
 *       zoned date-time, a local date-time or a date, and writes {@code yyyy-MM-dd}. A present element that
 *       none of these forms reads fails with {@link IllegalArgumentException}, which the SOAP layer answers
 *       with a {@code soap:Server} fault carrying its message. Which strings DataWeave 1.0 itself coerced is
 *       a Tier 2A verification point.</li>
 *   <li>The bill element is named {@code "Bill "}, trailing space included, exactly as line 40 keys it. The
 *       writer emits the name verbatim ({@code <ns1:Bill  xmlns:ns1="...">} ... {@code </ns1:Bill >}),
 *       which XML reads as the element {@code Bill}; the exact bytes are a Tier 2A verification point.</li>
 *   <li>The bill keeps the script's {@code initialStateEstimate}. {@code src/test/resources/reply.xml} and
 *       the {@code billType} of {@code SOA-Model-1.0.xsd} spell it {@code initialStayEstimate}, but no flow
 *       validates against that schema and the original test asserts only {@code runningTotal} and
 *       {@code status}, so the transformation rule of the script governs.</li>
 * </ul>
 *
 * <p>The mapper reads no clock and holds no state; it is thread-safe.
 */
@Component
public class AdmitSubjectResponseMapper {

    /** Namespace bound to {@code ns0} by the script: the SOA message namespace. */
    public static final String NS0 = "http://www.mule-health.com/SOA/message/1.0";

    /** Namespace bound to {@code ns1} by the script: the SOA model namespace. */
    public static final String NS1 = "http://www.mule-health.com/SOA/model/1.0";

    /** Prefix of the response root. */
    public static final String NS0_PREFIX = "ns0";

    /** Prefix of {@code Episode}, {@code PatientId} and the bill. */
    public static final String NS1_PREFIX = "ns1";

    private static final String INPUT_ROOT = "createEpisodeResponse";
    private static final String START_DATE = "startDate";
    private static final String END_DATE = "endDate";

    /** The key of {@code Hospital_Admissions_SOA.xml:40}, trailing space included. */
    private static final String BILL = "Bill ";
    private static final String COST_PER_NIGHT = "100";
    private static final String INITIAL_STATE_ESTIMATE = "5";
    private static final String RUNNING_TOTAL = "500";
    private static final String STATUS = "ADMITTED";

    /** The {@code |P5D|} added to the upstream {@code endDate}. */
    private static final Period END_DATE_EXTENSION = Period.ofDays(5);

    /** The string forms {@code as :date} accepts, tried in this order. */
    private static final List<Function<String, LocalDate>> DATE_FORMS = List.of(
            text -> OffsetDateTime.parse(text).toLocalDate(),
            text -> ZonedDateTime.parse(text).toLocalDate(),
            text -> LocalDateTime.parse(text).toLocalDate(),
            LocalDate::parse);

    private final DwXmlWriter writer;

    /**
     * Creates the mapper.
     *
     * @param writer the DataWeave XML writer
     * @throws IllegalArgumentException when {@code writer} is {@code null}
     */
    public AdmitSubjectResponseMapper(DwXmlWriter writer) {
        if (writer == null) {
            throw new IllegalArgumentException("AdmitSubjectResponseMapper requires a DwXmlWriter");
        }
        this.writer = writer;
    }

    /**
     * Renders the {@code admitSubjectResponse} document in DataWeave 1.0 XML layout.
     *
     * @param createEpisodeResponse the EHRService response body root, or {@code null} for an empty body
     * @return the declaration and the document, without a trailing line feed
     * @throws IllegalArgumentException when an upstream {@code startDate} or {@code endDate} is present but
     *         cannot be coerced to a date, or a copied element or attribute name is not an XML name, which only a
     *         DOM built without strict error checking can hold and the writer refuses before anything is written
     */
    public String toXml(Element createEpisodeResponse) {
        Element episode = createEpisodeResponse != null && INPUT_ROOT.equals(localName(createEpisodeResponse))
                ? firstChild(createEpisodeResponse, "Episode")
                : null;
        Element startDate = firstChild(episode, START_DATE);
        Element endDate = firstChild(episode, END_DATE);

        DwXmlWriter.Element episodeOut = DwXmlWriter.element(NS1_PREFIX, NS1, "Episode")
                .child(field(null, null, "episodeId", firstChild(episode, "episodeId")))
                .child(field(NS1_PREFIX, NS1, "PatientId", firstChild(episode, "PatientId")))
                .child(field(null, null, "admission", firstChild(episode, "admission")));
        if (startDate != null) {
            episodeOut.child(DwXmlWriter.element(START_DATE).text(format(dateOf(START_DATE, startDate))));
        }
        if (endDate != null) {
            LocalDate extended = dateOf(END_DATE, endDate).plus(END_DATE_EXTENSION);
            episodeOut.child(DwXmlWriter.element(END_DATE).text(format(extended)));
        }
        episodeOut.child(field(null, null, "care", firstChild(episode, "care")));

        DwXmlWriter.Element bill = DwXmlWriter.element(NS1_PREFIX, NS1, BILL)
                .child(DwXmlWriter.element("costPerNight").text(COST_PER_NIGHT))
                .child(DwXmlWriter.element("initialStateEstimate").text(INITIAL_STATE_ESTIMATE))
                .child(DwXmlWriter.element("runningTotal").text(RUNNING_TOTAL))
                .child(DwXmlWriter.element("status").text(STATUS));

        return writer.write(DwXmlWriter.element(NS0_PREFIX, NS0, "admitSubjectResponse")
                .child(episodeOut)
                .child(bill));
    }

    /**
     * Builds the {@code admitSubjectResponse} as a DOM element, the SOAP response body payload. The element is
     * parsed from {@link #toXml(Element)} with its whitespace kept, so the payload carries DataWeave's layout.
     *
     * @param createEpisodeResponse the EHRService response body root, or {@code null} for an empty body
     * @return the {@code {NS0}admitSubjectResponse} document element
     * @throws IllegalArgumentException under the conditions of {@link #toXml(Element)}
     * @throws IllegalStateException when the rendered document cannot be parsed
     */
    public Element toElement(Element createEpisodeResponse) {
        String xml = toXml(createEpisodeResponse);
        try {
            DocumentBuilder builder = newDocumentBuilderFactory().newDocumentBuilder();
            // Throws on fatal errors without the default handler's output on stderr.
            builder.setErrorHandler(new DefaultHandler());
            return builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalStateException("Cannot parse the admitSubjectResponse document: " + e.getMessage(), e);
        }
    }

    /**
     * Coerces an upstream date element, as {@code as :date} does: an element with child elements is an object
     * and never a date.
     */
    private static LocalDate dateOf(String field, Element source) {
        if (hasElementChildren(source)) {
            throw coercionFailure(field, source.getTextContent(), null);
        }
        return toDate(field, source.getTextContent());
    }

    private static LocalDate toDate(String field, String raw) {
        String value = raw == null ? "" : raw.trim();
        DateTimeParseException lastFailure = null;
        if (!value.isEmpty()) {
            for (Function<String, LocalDate> form : DATE_FORMS) {
                try {
                    return form.apply(value);
                } catch (DateTimeParseException notThisForm) {
                    lastFailure = notThisForm;
                }
            }
        }
        throw coercionFailure(field, raw, lastFailure);
    }

    private static IllegalArgumentException coercionFailure(String field, String raw, Exception cause) {
        return new IllegalArgumentException("Cannot coerce " + field + " value '" + raw + "' to :date", cause);
    }

    private static String format(LocalDate date) {
        return DateTimeFormatter.ISO_LOCAL_DATE.format(date);
    }

    /** {@code name: <selected value>}: a missing element gives null, a leaf its text, an object its children. */
    private static DwXmlWriter.Element field(String prefix, String uri, String name, Element source) {
        DwXmlWriter.Element target = DwXmlWriter.element(prefix, uri, name);
        fill(target, source, "");
        return target;
    }

    /**
     * Copies the content of {@code source} into {@code target}.
     *
     * @param defaultNamespace the default namespace in scope at {@code target}, {@code ""} for none
     */
    private static void fill(DwXmlWriter.Element target, Element source, String defaultNamespace) {
        if (source == null) {
            return;
        }
        if (hasElementChildren(source)) {
            for (Node node = source.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element child) {
                    target.child(copy(child, defaultNamespace));
                }
            }
            return;
        }
        String text = source.getTextContent();
        target.text(text == null || text.isBlank() ? null : text);
    }

    private static DwXmlWriter.Element copy(Element source, String defaultNamespace) {
        String uri = source.getNamespaceURI();
        String prefix = source.getPrefix();
        String name = localName(source);
        DwXmlWriter.Element target;
        String childDefaultNamespace = defaultNamespace;
        if (uri == null || uri.isEmpty()) {
            target = DwXmlWriter.element(name);
            if (!defaultNamespace.isEmpty()) {
                // An unqualified element under a default namespace must undeclare it to stay unqualified.
                target.namespace(null, "");
                childDefaultNamespace = "";
            }
        } else if (prefix == null || prefix.isEmpty()) {
            target = DwXmlWriter.element(null, uri, name);
            childDefaultNamespace = uri;
        } else {
            target = DwXmlWriter.element(prefix, uri, name);
        }
        copyAttributes(source, target);
        fill(target, source, childDefaultNamespace);
        return target;
    }

    private static void copyAttributes(Element source, DwXmlWriter.Element target) {
        NamedNodeMap attributes = source.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr attribute = (Attr) attributes.item(i);
            String name = attribute.getName();
            boolean declaration = XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())
                    || XMLConstants.XMLNS_ATTRIBUTE.equals(name)
                    || name.startsWith(XMLConstants.XMLNS_ATTRIBUTE + ":");
            // The writer declares the namespaces the copied elements and attributes use.
            if (!declaration) {
                String prefix = attribute.getPrefix();
                String uri = attribute.getNamespaceURI();
                if (prefix != null && !prefix.isEmpty() && uri != null && !XMLConstants.XML_NS_PREFIX.equals(prefix)) {
                    target.namespace(prefix, uri);
                }
                target.attribute(name, attribute.getValue());
            }
        }
    }

    private static Element firstChild(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child && localName.equals(localName(child))) {
                return child;
            }
        }
        return null;
    }

    private static boolean hasElementChildren(Element element) {
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                return true;
            }
        }
        return false;
    }

    /** The local name, also for a node built without namespace awareness, whose local name is null. */
    private static String localName(Node node) {
        String local = node.getLocalName();
        if (local != null) {
            return local;
        }
        String qualified = node.getNodeName();
        return qualified.substring(qualified.indexOf(':') + 1);
    }

    private static DocumentBuilderFactory newDocumentBuilderFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setNamespaceAware(true);
        factory.setIgnoringElementContentWhitespace(false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }
}
