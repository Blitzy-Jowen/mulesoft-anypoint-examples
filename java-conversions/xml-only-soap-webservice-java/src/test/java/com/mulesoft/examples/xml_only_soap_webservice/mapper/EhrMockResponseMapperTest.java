/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.xml_only_soap_webservice.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Contract of {@link EhrMockResponseMapper}, the owner of the two scripts of the mock {@code EHRService} flow:
 * {@code createEpisodeResponse} ({@code xml-only-soap-webservice/src/main/app/mocks.xml:52}) and
 * {@code findEpisodesResponse} ({@code mocks.xml:71}).
 *
 * <p>Every {@code now} reads a fixed clock, so the documents are compared exactly, never by containment. Expected
 * documents are spelled out with {@code \n} and {@code \t}, ending at the root end tag as {@link DwXmlWriter}
 * writes them, with the namespace URIs as literals rather than through the mapper's constants. Requests are
 * inline SOAP Body roots parsed namespace-aware with DOCTYPEs, XInclude and entity expansion off.
 */
class EhrMockResponseMapperTest {

    private static final String NS0 = "http://www.mule-health.com/SOA/message/1.0";
    private static final String NS1 = "http://www.mule-health.com/SOA/model/1.0";

    private static final String CREATE_EPISODE = "createEpisode";
    private static final String FIND_EPISODES = "findEpisodes";

    /** 2015-01-23T04:10:20.123Z seen from Los Angeles, whose January offset is -08:00. */
    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2015-01-23T04:10:20.123Z"), ZoneId.of("America/Los_Angeles"));

    /** The text every {@code now} takes on {@link #FIXED}. */
    private static final String NOW = "2015-01-22T20:10:20.123-08:00";

    private static final String PATIENT_ID_P42 = "<ns1:PatientId>P42</ns1:PatientId>";

    /** {@code ns1:PatientId} echoing {@code P42}, at its depth in both responses. */
    private static final String P42_LINE = "\t\t" + PATIENT_ID_P42;

    /** The null form {@link DwXmlWriter} writes for a {@code PatientId} the request does not supply. */
    private static final String NULL_PATIENT_ID_LINE = "\t\t<ns1:PatientId/>";

    private EhrMockResponseMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new EhrMockResponseMapper(new DwXmlWriter(), FIXED);
    }

    @Test
    void constants() {
        assertThat(EhrMockResponseMapper.NS0).isEqualTo(NS0);
        assertThat(EhrMockResponseMapper.NS1).isEqualTo(NS1);
        assertThat(EhrMockResponseMapper.NS0_PREFIX).isEqualTo("ns0");
        assertThat(EhrMockResponseMapper.NS1_PREFIX).isEqualTo("ns1");
        assertThat(EhrMockResponseMapper.NOW_FORMAT).isEqualTo("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
        assertThat(DateTimeFormatter.ofPattern(EhrMockResponseMapper.NOW_FORMAT, Locale.ROOT)
                .format(ZonedDateTime.now(FIXED))).isEqualTo(NOW);
    }

    static Stream<Arguments> missingCollaborators() {
        return Stream.of(
                Arguments.of(null, FIXED, "writer"),
                Arguments.of(new DwXmlWriter(), null, "clock"));
    }

    @ParameterizedTest(name = "{2}")
    @MethodSource("missingCollaborators")
    void constructorRejectsMissingCollaborator(DwXmlWriter writer, Clock clock, String name) {
        assertThatNullPointerException()
                .isThrownBy(() -> new EhrMockResponseMapper(writer, clock))
                .withMessage(name);
    }

    @Test
    void createEpisodeResponseXmlExact() {
        assertThat(mapper.createEpisodeResponseXml(request(CREATE_EPISODE, PATIENT_ID_P42)))
                .isEqualTo(createEpisodeDocument(NOW, P42_LINE));
    }

    @Test
    void createEpisodeSharesOneNow() {
        TickingClock clock = new TickingClock(FIXED.instant(), FIXED.getZone());
        EhrMockResponseMapper ticking = new EhrMockResponseMapper(new DwXmlWriter(), clock);
        Element request = request(CREATE_EPISODE, PATIENT_ID_P42);

        assertThat(ticking.createEpisodeResponseXml(request)).isEqualTo(createEpisodeDocument(NOW, P42_LINE));
        assertThat(clock.reads()).isEqualTo(1);
        assertThat(ticking.createEpisodeResponseXml(request))
                .isEqualTo(createEpisodeDocument("2015-01-22T20:10:20.124-08:00", P42_LINE));
        assertThat(clock.reads()).isEqualTo(2);

        List<Element> fields = childElements(childElements(ticking.createEpisodeResponse(request)).get(0));
        assertThat(clock.reads()).isEqualTo(3);
        assertThat(fields.get(0).getTextContent()).isEqualTo("2015-01-22T20:10:20.125-08:00");
        assertThat(fields.get(3).getTextContent()).isEqualTo(fields.get(0).getTextContent());
        assertThat(fields.get(4).getTextContent()).isEqualTo(fields.get(0).getTextContent());
    }

    @Test
    void findEpisodesResponseXmlExact() {
        TickingClock clock = new TickingClock(FIXED.instant(), FIXED.getZone());
        EhrMockResponseMapper counting = new EhrMockResponseMapper(new DwXmlWriter(), clock);

        assertThat(counting.findEpisodesResponseXml(request(FIND_EPISODES, PATIENT_ID_P42)))
                .isEqualTo(findEpisodesDocument(P42_LINE));
        assertThat(clock.reads()).isZero();
    }

    static Stream<Arguments> createEpisodeRequestsWithoutPatientId() {
        return requestsWithoutPatientId(CREATE_EPISODE, FIND_EPISODES);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("createEpisodeRequestsWithoutPatientId")
    void createEpisodeWithoutPatientIdGivesNullForm(String description, Element request) {
        assertThat(mapper.createEpisodeResponseXml(request))
                .isEqualTo(createEpisodeDocument(NOW, NULL_PATIENT_ID_LINE));
    }

    static Stream<Arguments> findEpisodesRequestsWithoutPatientId() {
        return requestsWithoutPatientId(FIND_EPISODES, CREATE_EPISODE);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("findEpisodesRequestsWithoutPatientId")
    void findEpisodesWithoutPatientIdGivesNullForm(String description, Element request) {
        assertThat(mapper.findEpisodesResponseXml(request)).isEqualTo(findEpisodesDocument(NULL_PATIENT_ID_LINE));
    }

    static Stream<Arguments> wrongNamespacePatientIds() {
        return Stream.of(CREATE_EPISODE, FIND_EPISODES).flatMap(operation -> Stream.of(
                Arguments.of(operation, "<PatientId>P42</PatientId>"),
                Arguments.of(operation, "<ns0:PatientId>P42</ns0:PatientId>"),
                Arguments.of(operation, "<x:PatientId xmlns:x='urn:other'>P42</x:PatientId>"),
                Arguments.of(operation, "<PatientId xmlns='urn:other'>P42</PatientId>")));
    }

    /**
     * {@code payload.ns0#X.ns1#PatientId} matches only a {@code PatientId} child in the model namespace, so a
     * {@code PatientId} carrying any other namespace, or none, is not echoed.
     */
    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("wrongNamespacePatientIds")
    void wrongNamespacePatientIdGivesNullForm(String operation, String patientId) {
        assertThat(render(operation, request(operation, patientId)))
                .isEqualTo(expectedDocument(operation, NULL_PATIENT_ID_LINE));
    }

    static Stream<Arguments> echoedPatientIds() {
        return Stream.of(
                Arguments.of("indented request",
                        "\n\t<ns1:PatientId>P42</ns1:PatientId>\n", PATIENT_ID_P42),
                Arguments.of("whitespace kept",
                        "<ns1:PatientId> P 42 </ns1:PatientId>", "<ns1:PatientId> P 42 </ns1:PatientId>"),
                Arguments.of("markup escaped",
                        "<ns1:PatientId>A&amp;B&lt;C&gt;\"'</ns1:PatientId>",
                        "<ns1:PatientId>A&amp;B&lt;C&gt;\"'</ns1:PatientId>"),
                Arguments.of("CDATA as text",
                        "<ns1:PatientId><![CDATA[P<42>]]></ns1:PatientId>", "<ns1:PatientId>P&lt;42&gt;</ns1:PatientId>"),
                Arguments.of("comment left out",
                        "<ns1:PatientId>P<!-- note -->42</ns1:PatientId>", PATIENT_ID_P42),
                Arguments.of("non-ASCII kept",
                        "<ns1:PatientId>P\u00e9-42</ns1:PatientId>", "<ns1:PatientId>P\u00e9-42</ns1:PatientId>"),
                Arguments.of("first of two",
                        "<ns1:PatientId>P42</ns1:PatientId><ns1:PatientId>P2</ns1:PatientId>", PATIENT_ID_P42),
                Arguments.of("after a sibling, other prefix",
                        "<ns0:Note>n</ns0:Note><m:PatientId xmlns:m='" + NS1 + "'>P42</m:PatientId>", PATIENT_ID_P42),
                Arguments.of("default namespace",
                        "<PatientId xmlns='" + NS1 + "'>P42</PatientId>", PATIENT_ID_P42));
    }

    /**
     * The first {@code {NS1}PatientId} child is selected whatever its prefix or position, and its text content is
     * echoed unchanged: surrounding whitespace kept, comments left out, markup characters escaped by the writer.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("echoedPatientIds")
    void patientIdIsSelectedByNamespaceAndEchoed(String description, String patientId, String expected) {
        Element request = request(FIND_EPISODES, patientId);

        assertThat(mapper.findEpisodesResponseXml(request)).isEqualTo(findEpisodesDocument("\t\t" + expected));
        assertThat(mapper.createEpisodeResponseXml(request(CREATE_EPISODE, patientId)))
                .isEqualTo(createEpisodeDocument(NOW, "\t\t" + expected));
    }

    static Stream<Arguments> objectValuedPatientIds() {
        Element domLevel1 = request(FIND_EPISODES, "<ns1:PatientId/>");
        Element patientId = childElements(domLevel1).get(0);
        Element legacy = domLevel1.getOwnerDocument().createElement("mrn");
        legacy.setAttribute("xmlns:p", "urn:p");
        legacy.setAttribute("system", "local");
        legacy.setTextContent("42");
        patientId.appendChild(legacy);

        return Stream.of(
                Arguments.of("unqualified children, text between them dropped",
                        request(FIND_EPISODES,
                                "<ns1:PatientId>before<mrn>42</mrn> <site/><ward> </ward>after</ns1:PatientId>"),
                        lines(
                                "\t\t<ns1:PatientId>",
                                "\t\t\t<mrn>42</mrn>",
                                "\t\t\t<site/>",
                                "\t\t\t<ward/>",
                                "\t\t</ns1:PatientId>")),
                Arguments.of("inherited default namespace declared, unqualified grandchild undeclares it",
                        request(FIND_EPISODES, "<ns1:PatientId xmlns='urn:outer'>"
                                + "<record><mrn>42</mrn><site xmlns=''>A</site></record></ns1:PatientId>"),
                        lines(
                                "\t\t<ns1:PatientId>",
                                "\t\t\t<record xmlns=\"urn:outer\">",
                                "\t\t\t\t<mrn>42</mrn>",
                                "\t\t\t\t<site xmlns=\"\">A</site>",
                                "\t\t\t</record>",
                                "\t\t</ns1:PatientId>")),
                Arguments.of("prefixed child keeps its prefix, declaration and attributes",
                        request(FIND_EPISODES, "<ns1:PatientId><p:mrn xmlns:p='urn:p' system='local' p:kind='x'"
                                + " xml:lang='en'>42</p:mrn></ns1:PatientId>"),
                        lines(
                                "\t\t<ns1:PatientId>",
                                "\t\t\t<p:mrn xmlns:p=\"urn:p\" p:kind=\"x\" system=\"local\" xml:lang=\"en\">42</p:mrn>",
                                "\t\t</ns1:PatientId>")),
                Arguments.of("prefixes bound by ancestors declared unless the output binds them",
                        request(FIND_EPISODES, "<ns1:PatientId xmlns:q='urn:q'><q:mrn>42</q:mrn>"
                                + "<site q:code='7'>A</site><ns1:ward>W</ns1:ward></ns1:PatientId>"),
                        lines(
                                "\t\t<ns1:PatientId>",
                                "\t\t\t<q:mrn xmlns:q=\"urn:q\">42</q:mrn>",
                                "\t\t\t<site xmlns:q=\"urn:q\" q:code=\"7\">A</site>",
                                "\t\t\t<ns1:ward>W</ns1:ward>",
                                "\t\t</ns1:PatientId>")),
                Arguments.of("XML 1.1 prefix undeclaration not copied",
                        parse("<?xml version='1.1'?><ns0:findEpisodes xmlns:ns0='" + NS0 + "' xmlns:ns1='" + NS1
                                + "'><ns1:PatientId><mrn xmlns:p=''>42</mrn></ns1:PatientId></ns0:findEpisodes>"),
                        lines(
                                "\t\t<ns1:PatientId>",
                                "\t\t\t<mrn>42</mrn>",
                                "\t\t</ns1:PatientId>")),
                Arguments.of("child built without namespace awareness",
                        domLevel1,
                        lines(
                                "\t\t<ns1:PatientId>",
                                "\t\t\t<mrn xmlns:p=\"urn:p\" system=\"local\">42</mrn>",
                                "\t\t</ns1:PatientId>")));
    }

    /** A {@code PatientId} holding elements is a DataWeave object, so it is copied as nested elements. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("objectValuedPatientIds")
    void objectValuedPatientIdIsCopiedAsNestedElements(String description, Element request, String expected) {
        assertThat(mapper.findEpisodesResponseXml(request)).isEqualTo(findEpisodesDocument(expected));
    }

    @Test
    void createEpisodeResponseElementStructure() {
        Element response = mapper.createEpisodeResponse(request(CREATE_EPISODE, PATIENT_ID_P42));

        assertThat(response.getOwnerDocument().getXmlVersion()).isEqualTo("1.0");
        assertThat(response.getOwnerDocument().getXmlEncoding()).isEqualTo("UTF-8");
        assertQualified(response, "ns0", NS0, "createEpisodeResponse");
        assertThat(response.getFirstChild().getNodeValue()).isEqualTo("\n\t");

        List<Element> children = childElements(response);
        assertThat(children).hasSize(1);
        Element episode = children.get(0);
        assertQualified(episode, "ns1", NS1, "Episode");
        assertThat(episode.getFirstChild().getNodeValue()).isEqualTo("\n\t\t");

        List<Element> fields = childElements(episode);
        assertThat(fields).extracting(Node::getLocalName)
                .containsExactly("episodeId", "PatientId", "admission", "startDate", "endDate", "care");
        assertThat(fields).extracting(Node::getNamespaceURI).containsExactly(null, NS1, null, null, null, null);
        assertThat(fields).extracting(Node::getPrefix).containsExactly(null, "ns1", null, null, null, null);
        assertThat(fields).extracting(Node::getTextContent)
                .containsExactly(NOW, "P42", "Elective", NOW, NOW, "Private");
        assertThat(fields).allSatisfy(field -> assertThat(childElements(field)).isEmpty());
    }

    @Test
    void findEpisodesResponseElementStructure() {
        Element response = mapper.findEpisodesResponse(request(FIND_EPISODES, PATIENT_ID_P42));

        assertThat(response.getOwnerDocument().getXmlEncoding()).isEqualTo("UTF-8");
        assertQualified(response, "ns0", NS0, "findEpisodesResponse");
        assertThat(response.getFirstChild().getNodeValue()).isEqualTo("\n\t");

        List<Element> children = childElements(response);
        assertThat(children).hasSize(1);
        Element episode = children.get(0);
        assertQualified(episode, "ns1", NS1, "Episode");

        List<Element> fields = childElements(episode);
        assertThat(fields).hasSize(1);
        assertQualified(fields.get(0), "ns1", NS1, "PatientId");
        assertThat(fields.get(0).getTextContent()).isEqualTo("P42");

        Element nullForm = childElements(childElements(mapper.findEpisodesResponse(null)).get(0)).get(0);
        assertQualified(nullForm, "ns1", NS1, "PatientId");
        assertThat(nullForm.hasChildNodes()).isFalse();
    }

    /** A UTC clock writes {@code Z}; the instant is truncated to milliseconds, which are always three digits. */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "2015-01-23T04:10:20.123Z,       2015-01-23T04:10:20.123Z",
        "2015-01-23T04:10:20.123456789Z, 2015-01-23T04:10:20.123Z",
        "2015-01-23T04:10:20Z,           2015-01-23T04:10:20.000Z"
    })
    void utcClockRendersZ(String instant, String expected) {
        EhrMockResponseMapper utc =
                new EhrMockResponseMapper(new DwXmlWriter(), Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));

        assertThat(utc.createEpisodeResponseXml(request(CREATE_EPISODE, PATIENT_ID_P42)))
                .isEqualTo(createEpisodeDocument(expected, P42_LINE));
    }

    static Stream<Arguments> unparseableRenderings() {
        UnaryOperator<String> truncated = xml -> xml.substring(0, xml.length() - 1);
        UnaryOperator<String> withDoctype = xml -> xml.replaceFirst("\n",
                "\n<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]>\n");
        return Stream.of(
                Arguments.of(CREATE_EPISODE, "truncated", truncated, 11),
                Arguments.of(FIND_EPISODES, "truncated", truncated, 6),
                Arguments.of(CREATE_EPISODE, "DOCTYPE", withDoctype, 2),
                Arguments.of(FIND_EPISODES, "DOCTYPE", withDoctype, 2));
    }

    /**
     * Text the writer renders but the hardened parser rejects reaches the mapper's error handler, which raises the
     * diagnostic instead of printing it, and surfaces as {@link IllegalStateException} with the parse error as its
     * cause, located where the text breaks.
     */
    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("unparseableRenderings")
    void unparseableRenderingRaisesIllegalStateException(String operation, String corruption,
            UnaryOperator<String> corrupt, int line) {
        DwXmlWriter corrupting = new DwXmlWriter() {
            @Override
            public String write(DwXmlWriter.Element root) {
                return corrupt.apply(super.write(root));
            }
        };
        EhrMockResponseMapper broken = new EhrMockResponseMapper(corrupting, FIXED);
        Element request = request(operation, PATIENT_ID_P42);

        assertThatIllegalStateException()
                .isThrownBy(() -> {
                    if (CREATE_EPISODE.equals(operation)) {
                        broken.createEpisodeResponse(request);
                    } else {
                        broken.findEpisodesResponse(request);
                    }
                })
                .withMessageStartingWith("Cannot parse the EHRService mock response: ")
                .withCauseInstanceOf(SAXParseException.class)
                .satisfies(e -> {
                    SAXParseException cause = (SAXParseException) e.getCause();
                    assertThat(e).hasMessage("Cannot parse the EHRService mock response: " + cause.getMessage());
                    assertThat(cause.getLineNumber()).isEqualTo(line);
                });
    }

    /**
     * The handler raises warnings and recoverable errors as well as fatal errors. The mapper's non-validating,
     * DOCTYPE-free parser reports only fatal errors for any text, so the other two are called directly on the
     * handler the mapper installs.
     */
    @ParameterizedTest
    @ValueSource(strings = {"warning", "error", "fatalError"})
    void errorHandlerRaisesEveryDiagnostic(String severity) throws ReflectiveOperationException {
        Field field = EhrMockResponseMapper.class.getDeclaredField("RETHROWING_ERROR_HANDLER");
        field.setAccessible(true);
        ErrorHandler handler = (ErrorHandler) field.get(null);
        SAXParseException diagnostic = new SAXParseException(severity, null, null, 3, 7);

        assertThatThrownBy(() -> {
            switch (severity) {
                case "warning" -> handler.warning(diagnostic);
                case "error" -> handler.error(diagnostic);
                default -> handler.fatalError(diagnostic);
            }
        }).isSameAs(diagnostic);
    }

    private String render(String operation, Element request) {
        return CREATE_EPISODE.equals(operation)
                ? mapper.createEpisodeResponseXml(request)
                : mapper.findEpisodesResponseXml(request);
    }

    private static String expectedDocument(String operation, String patientId) {
        return CREATE_EPISODE.equals(operation)
                ? createEpisodeDocument(NOW, patientId)
                : findEpisodesDocument(patientId);
    }

    /** Requests whose {@code payload.ns0#<operation>.ns1#PatientId} selects nothing, or a blank leaf. */
    private static Stream<Arguments> requestsWithoutPatientId(String operation, String otherOperation) {
        return Stream.of(
                Arguments.of("no request", null),
                Arguments.of("no children", request(operation, "")),
                Arguments.of("other children only", request(operation, "<ns1:Episode>E1</ns1:Episode>")),
                Arguments.of("empty PatientId", request(operation, "<ns1:PatientId/>")),
                Arguments.of("blank PatientId", request(operation, "<ns1:PatientId> \n\t </ns1:PatientId>")),
                Arguments.of("PatientId below a child",
                        request(operation, "<ns1:Patient>" + PATIENT_ID_P42 + "</ns1:Patient>")),
                Arguments.of("root of the other operation", request(otherOperation, PATIENT_ID_P42)),
                Arguments.of("root in another namespace", parse("<x:" + operation + " xmlns:x='urn:other' xmlns:ns1='"
                        + NS1 + "'>" + PATIENT_ID_P42 + "</x:" + operation + ">")),
                Arguments.of("unqualified root",
                        parse("<" + operation + " xmlns:ns1='" + NS1 + "'>" + PATIENT_ID_P42 + "</" + operation + ">")));
    }

    private static String createEpisodeDocument(String now, String patientId) {
        return lines(
                "<?xml version='1.0' encoding='UTF-8'?>",
                "<ns0:createEpisodeResponse xmlns:ns0=\"" + NS0 + "\">",
                "\t<ns1:Episode xmlns:ns1=\"" + NS1 + "\">",
                "\t\t<episodeId>" + now + "</episodeId>",
                patientId,
                "\t\t<admission>Elective</admission>",
                "\t\t<startDate>" + now + "</startDate>",
                "\t\t<endDate>" + now + "</endDate>",
                "\t\t<care>Private</care>",
                "\t</ns1:Episode>",
                "</ns0:createEpisodeResponse>");
    }

    private static String findEpisodesDocument(String patientId) {
        return lines(
                "<?xml version='1.0' encoding='UTF-8'?>",
                "<ns0:findEpisodesResponse xmlns:ns0=\"" + NS0 + "\">",
                "\t<ns1:Episode xmlns:ns1=\"" + NS1 + "\">",
                patientId,
                "\t</ns1:Episode>",
                "</ns0:findEpisodesResponse>");
    }

    private static String lines(String... lines) {
        return String.join("\n", lines);
    }

    /** A SOAP Body root {@code ns0:<operation>} binding {@code ns0} and {@code ns1}, holding {@code content}. */
    private static Element request(String operation, String content) {
        return parse("<ns0:" + operation + " xmlns:ns0='" + NS0 + "' xmlns:ns1='" + NS1 + "'>" + content
                + "</ns0:" + operation + ">");
    }

    private static Element parse(String xml) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        try {
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            return document.getDocumentElement();
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalArgumentException("Test request is not well-formed XML: " + xml, e);
        }
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

    private static void assertQualified(Element element, String prefix, String namespace, String localName) {
        assertThat(element.getPrefix()).isEqualTo(prefix);
        assertThat(element.getNamespaceURI()).isEqualTo(namespace);
        assertThat(element.getLocalName()).isEqualTo(localName);
    }

    /** A clock in a fixed zone whose every {@link #instant()} read is one millisecond after the previous one. */
    private static final class TickingClock extends Clock {

        private final Instant start;
        private final ZoneId zone;
        private final AtomicLong reads;

        TickingClock(Instant start, ZoneId zone) {
            this(start, zone, new AtomicLong());
        }

        private TickingClock(Instant start, ZoneId zone, AtomicLong reads) {
            this.start = start;
            this.zone = zone;
            this.reads = reads;
        }

        long reads() {
            return reads.get();
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId newZone) {
            return new TickingClock(start, newZone, reads);
        }

        @Override
        public Instant instant() {
            return start.plusMillis(reads.getAndIncrement());
        }
    }
}
