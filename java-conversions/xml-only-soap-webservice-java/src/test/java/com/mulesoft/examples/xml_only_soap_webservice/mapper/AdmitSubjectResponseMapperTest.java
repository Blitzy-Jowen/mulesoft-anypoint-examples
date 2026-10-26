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
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.io.StringReader;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.assertj.core.api.AbstractThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Contract of {@link AdmitSubjectResponseMapper}, the {@code admitSubjectResponse} script of
 * {@code xml-only-soap-webservice/src/main/app/Hospital_Admissions_SOA.xml:26-46}: {@code startDate} is the
 * upstream {@code startDate} as a date and {@code endDate} the upstream {@code endDate} as a date plus
 * {@code P5D}, each written only when its upstream element is present; the bill keyed {@code "Bill "} carries
 * the string values {@code 100}, {@code 5}, {@code 500} and {@code ADMITTED}.
 *
 * <p>Inputs are built in the shape the EHRService mock writes ({@code mocks.xml:57-65}). Expected documents
 * are spelled out with explicit tabs and line feeds and compared in full, with no trailing line feed; no file
 * or classpath resource is read.
 */
class AdmitSubjectResponseMapperTest {

    private static final String NS0_URI = "http://www.mule-health.com/SOA/message/1.0";
    private static final String NS1_URI = "http://www.mule-health.com/SOA/model/1.0";

    /** An upstream date-time whose calendar date in its own offset (22nd) differs from its UTC date (23rd). */
    private static final String START_DATE_TIME = "2015-01-22T20:10:20.123-08:00";
    private static final String END_DATE_TIME = "2015-01-30T10:00:00.000-08:00";

    /** The mapper's output for {@link #START_DATE_TIME} and {@link #END_DATE_TIME}. */
    private static final String DATE_TIME_DOCUMENT = String.join("\n",
            "<?xml version='1.0' encoding='UTF-8'?>",
            "<ns0:admitSubjectResponse xmlns:ns0=\"http://www.mule-health.com/SOA/message/1.0\">",
            "\t<ns1:Episode xmlns:ns1=\"http://www.mule-health.com/SOA/model/1.0\">",
            "\t\t<episodeId>E1</episodeId>",
            "\t\t<ns1:PatientId>P1</ns1:PatientId>",
            "\t\t<admission>Elective</admission>",
            "\t\t<startDate>2015-01-22</startDate>",
            "\t\t<endDate>2015-02-04</endDate>",
            "\t\t<care>Private</care>",
            "\t</ns1:Episode>",
            "\t<ns1:Bill  xmlns:ns1=\"http://www.mule-health.com/SOA/model/1.0\">",
            "\t\t<costPerNight>100</costPerNight>",
            "\t\t<initialStateEstimate>5</initialStateEstimate>",
            "\t\t<runningTotal>500</runningTotal>",
            "\t\t<status>ADMITTED</status>",
            "\t</ns1:Bill >",
            "</ns0:admitSubjectResponse>");

    private AdmitSubjectResponseMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new AdmitSubjectResponseMapper(new DwXmlWriter());
    }

    @Test
    void dateTimeInputs() {
        String xml = mapper.toXml(parse(episodeResponse(START_DATE_TIME, END_DATE_TIME)));

        assertThat(xml).isEqualTo(DATE_TIME_DOCUMENT);
    }

    @Test
    void dateInputs() {
        String xml = mapper.toXml(parse(episodeResponse("2015-01-22", "2015-12-29")));

        assertThat(xml).isEqualTo(standardDocument(
                "\t\t<startDate>2015-01-22</startDate>",
                "\t\t<endDate>2016-01-03</endDate>"));
    }

    @Test
    void endDateIsUpstreamEndPlusFiveDaysNotStartPlusFive() {
        String xml = mapper.toXml(parse(episodeResponse("2016-01-10", "2016-02-26")));

        // 2016 is a leap year: 26 February + 5 days is 2 March; startDate + 5 days would be 2016-01-15.
        assertThat(xml)
                .isEqualTo(standardDocument(
                        "\t\t<startDate>2016-01-10</startDate>",
                        "\t\t<endDate>2016-03-02</endDate>"))
                .doesNotContain("2016-01-15");
    }

    @Test
    void absentStartDateOmitsElement() {
        String xml = mapper.toXml(parse(episodeResponse(null, END_DATE_TIME)));

        assertThat(xml)
                .isEqualTo(standardDocument("\t\t<endDate>2015-02-04</endDate>"))
                .doesNotContain("startDate");
    }

    @Test
    void absentEndDateOmitsElement() {
        String xml = mapper.toXml(parse(episodeResponse(START_DATE_TIME, null)));

        assertThat(xml)
                .isEqualTo(standardDocument("\t\t<startDate>2015-01-22</startDate>"))
                .doesNotContain("endDate");
    }

    @Test
    void bothDatesAbsent() {
        String xml = mapper.toXml(parse(episodeResponse(null, null)));

        assertThat(xml)
                .isEqualTo(standardDocument())
                .doesNotContain("startDate")
                .doesNotContain("endDate");
    }

    /** Each string form {@code as :date} accepts, coerced to the calendar date in the value's own offset or zone. */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "2015-01-22T20:10:20.123-08:00, 2015-01-22, 2015-01-27",
            "2015-01-23T01:00:00+09:00, 2015-01-23, 2015-01-28",
            "2015-01-22T23:59:59Z, 2015-01-22, 2015-01-27",
            "2015-01-22T20:10:20.123-08:00[America/Los_Angeles], 2015-01-22, 2015-01-27",
            "2015-01-22T20:10:20, 2015-01-22, 2015-01-27",
            "2015-01-22, 2015-01-22, 2015-01-27"
    })
    void acceptedDateForms(String upstream, String startDate, String endDate) {
        String xml = mapper.toXml(parse(episodeResponse(upstream, upstream)));

        assertThat(xml).isEqualTo(standardDocument(
                "\t\t<startDate>" + startDate + "</startDate>",
                "\t\t<endDate>" + endDate + "</endDate>"));
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"not-a-date", "2015-13-01", "22/01/2015", "", "   "})
    void unparseableStartDateThrows(String value) {
        Element input = parse(episodeResponse(value, END_DATE_TIME));

        assertCoercionFailure(input, "startDate", value);
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"not-a-date", "2015-13-01", "22/01/2015", "", "   "})
    void unparseableEndDateThrows(String value) {
        Element input = parse(episodeResponse(START_DATE_TIME, value));

        assertCoercionFailure(input, "endDate", value);
    }

    /** A date element holding elements is an object, never a date, even when its text would parse. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"startDate", "endDate"})
    void dateHoldingChildElementsThrows(String field) {
        String objectValue = "<day>2015-01-22</day>";
        Element input = parse("startDate".equals(field)
                ? episodeResponse(objectValue, END_DATE_TIME)
                : episodeResponse(START_DATE_TIME, objectValue));

        assertThatThrownBy(() -> mapper.toXml(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cannot coerce " + field + " value '2015-01-22' to :date")
                .hasNoCause();
    }

    @Test
    void toElementStructure() {
        Element root = mapper.toElement(parse(episodeResponse(START_DATE_TIME, END_DATE_TIME)));

        assertThat(root.getOwnerDocument().getDocumentElement()).isSameAs(root);
        assertThat(root.getNamespaceURI()).isEqualTo(NS0_URI);
        assertThat(root.getLocalName()).isEqualTo("admitSubjectResponse");
        assertThat(root.getPrefix()).isEqualTo("ns0");

        List<Element> sections = elementChildren(root);
        assertThat(sections)
                .extracting(Element::getNamespaceURI, Element::getLocalName, Element::getPrefix)
                .containsExactly(tuple(NS1_URI, "Episode", "ns1"), tuple(NS1_URI, "Bill", "ns1"));

        assertThat(elementChildren(sections.get(0)))
                .extracting(Element::getNamespaceURI, Element::getLocalName, Element::getTextContent)
                .containsExactly(
                        tuple(null, "episodeId", "E1"),
                        tuple(NS1_URI, "PatientId", "P1"),
                        tuple(null, "admission", "Elective"),
                        tuple(null, "startDate", "2015-01-22"),
                        tuple(null, "endDate", "2015-02-04"),
                        tuple(null, "care", "Private"));

        assertThat(elementChildren(sections.get(1)))
                .extracting(Element::getNamespaceURI, Element::getLocalName, Element::getTextContent)
                .containsExactly(
                        tuple(null, "costPerNight", "100"),
                        tuple(null, "initialStateEstimate", "5"),
                        tuple(null, "runningTotal", "500"),
                        tuple(null, "status", "ADMITTED"));

        // DataWeave's layout travels in the payload as whitespace text nodes.
        assertThat(root.getFirstChild().getNodeType()).isEqualTo(Node.TEXT_NODE);
        assertThat(root.getFirstChild().getNodeValue()).isEqualTo("\n\t");
        assertThat(root.getLastChild().getNodeValue()).isEqualTo("\n");
    }

    @Test
    void toElementPropagatesCoercionFailure() {
        Element input = parse(episodeResponse("not-a-date", END_DATE_TIME));

        assertThatThrownBy(() -> mapper.toElement(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cannot coerce startDate value 'not-a-date' to :date");
    }

    /**
     * A DOM built in memory can hold a character XML 1.0 forbids, which the writer copies as it is; the
     * rendered document then does not parse back into an element.
     */
    @Test
    void toElementRejectsARenderedDocumentThatDoesNotParse() {
        Element input = parse(episodeResponse(START_DATE_TIME, END_DATE_TIME));
        input.getElementsByTagName("episodeId").item(0).setTextContent("E\u0001");

        assertThat(mapper.toXml(input)).contains("\t\t<episodeId>E\u0001</episodeId>\n");
        assertThatThrownBy(() -> mapper.toElement(input))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Cannot parse the admitSubjectResponse document: ")
                .hasCauseInstanceOf(SAXException.class);
    }

    /** The two assertions of the original {@code XMLWithSoapIT}, held in addition to the exact document. */
    @Test
    void containsOriginalReplyFragments() {
        String xml = mapper.toXml(parse(episodeResponse(START_DATE_TIME, END_DATE_TIME)));

        assertThat(xml)
                .isEqualTo(DATE_TIME_DOCUMENT)
                .contains("<runningTotal>500</runningTotal>")
                .contains("<status>ADMITTED</status>");
    }

    @Test
    void constants() {
        assertThat(AdmitSubjectResponseMapper.NS0).isEqualTo("http://www.mule-health.com/SOA/message/1.0");
        assertThat(AdmitSubjectResponseMapper.NS1).isEqualTo("http://www.mule-health.com/SOA/model/1.0");
        assertThat(AdmitSubjectResponseMapper.NS0_PREFIX).isEqualTo("ns0");
        assertThat(AdmitSubjectResponseMapper.NS1_PREFIX).isEqualTo("ns1");
    }

    @Test
    void constructorRejectsNullWriter() {
        assertThatThrownBy(() -> new AdmitSubjectResponseMapper(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AdmitSubjectResponseMapper requires a DwXmlWriter");
    }

    /**
     * A {@code null} input, a root other than {@code createEpisodeResponse}, or a root without an
     * {@code Episode} selects nothing: the four always-written fields are null and both dates are absent.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("inputsWithoutAnEpisode")
    void inputWithoutAnEpisodeWritesEmptyFieldsAndNoDates(String description, Element input) {
        assertThat(mapper.toXml(input)).isEqualTo(document(
                "\t\t<episodeId/>",
                "\t\t<ns1:PatientId/>",
                "\t\t<admission/>",
                "\t\t<care/>"));
    }

    static Stream<Arguments> inputsWithoutAnEpisode() {
        return Stream.of(
                Arguments.of("null input", null),
                Arguments.of("root other than createEpisodeResponse",
                        parse(episodeResponse(START_DATE_TIME, END_DATE_TIME)
                                .replace("createEpisodeResponse", "findEpisodesResponse"))),
                Arguments.of("createEpisodeResponse without Episode",
                        parse("<ns0:createEpisodeResponse xmlns:ns0=\"" + NS0_URI + "\"/>")));
    }

    /** A leaf's text is copied unchanged; blank text and a missing element are written as empty elements. */
    @Test
    void leafTextIsCopiedUnchangedAndBlankOrMissingFieldsAreEmpty() {
        Element input = parse("<ns0:createEpisodeResponse xmlns:ns0=\"" + NS0_URI + "\" xmlns:ns1=\"" + NS1_URI + "\">"
                + "<ns1:Episode>"
                + "<episodeId> \n\t</episodeId>"
                + "<admission> Elective &amp; Urgent </admission>"
                + "<startDate>2015-01-22</startDate>"
                + "</ns1:Episode>"
                + "</ns0:createEpisodeResponse>");

        assertThat(mapper.toXml(input)).isEqualTo(document(
                "\t\t<episodeId/>",
                "\t\t<ns1:PatientId/>",
                "\t\t<admission> Elective &amp; Urgent </admission>",
                "\t\t<startDate>2015-01-22</startDate>",
                "\t\t<care/>"));
    }

    /**
     * A field holding elements is copied as those elements with their names, namespaces and attributes; text
     * mixed in with them is dropped, and an unqualified element under a default namespace undeclares it.
     */
    @Test
    void objectValuedFieldIsCopiedAsElements() {
        Element input = parse(episodeResponse(START_DATE_TIME, END_DATE_TIME).replace("<care>Private</care>",
                "<care>Ward "
                        + "<x:unit xmlns:x=\"urn:example:unit\" floor=\"2\" x:code=\"U1\" xml:lang=\"en\">"
                        + "Cardiology</x:unit>"
                        + "<ward xmlns=\"urn:example:ward\"><bed xmlns=\"\">7</bed><room>12</room></ward>"
                        + "<note/>"
                        + "</care>"));

        assertThat(mapper.toXml(input)).isEqualTo(document(
                "\t\t<episodeId>E1</episodeId>",
                "\t\t<ns1:PatientId>P1</ns1:PatientId>",
                "\t\t<admission>Elective</admission>",
                "\t\t<startDate>2015-01-22</startDate>",
                "\t\t<endDate>2015-02-04</endDate>",
                "\t\t<care>",
                "\t\t\t<x:unit xmlns:x=\"urn:example:unit\" floor=\"2\" x:code=\"U1\" xml:lang=\"en\">"
                        + "Cardiology</x:unit>",
                "\t\t\t<ward xmlns=\"urn:example:ward\">",
                "\t\t\t\t<bed xmlns=\"\">7</bed>",
                "\t\t\t\t<room>12</room>",
                "\t\t\t</ward>",
                "\t\t\t<note/>",
                "\t\t</care>"));
    }

    /** Every selector step is unqualified: it matches by local name in any namespace and takes the first match. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("inputsReadByLocalName")
    void selectorsMatchByLocalNameAndTakeTheFirstMatch(String description, Element input) {
        assertThat(mapper.toXml(input)).isEqualTo(DATE_TIME_DOCUMENT);
    }

    static Stream<Arguments> inputsReadByLocalName() {
        return Stream.of(
                Arguments.of("other namespace, repeated elements",
                        parse("<createEpisodeResponse xmlns=\"urn:example:other\">"
                                + "<Episode>"
                                + "<episodeId>E1</episodeId><episodeId>E2</episodeId>"
                                + "<PatientId>P1</PatientId>"
                                + "<admission>Elective</admission>"
                                + "<startDate>" + START_DATE_TIME + "</startDate><startDate>2099-01-01</startDate>"
                                + "<endDate>" + END_DATE_TIME + "</endDate>"
                                + "<care>Private</care>"
                                + "</Episode>"
                                + "<Episode><episodeId>E9</episodeId></Episode>"
                                + "</createEpisodeResponse>")),
                Arguments.of("DOM built without namespace awareness",
                        parse(episodeResponse(START_DATE_TIME, END_DATE_TIME), false)));
    }

    /** The EHRService mock's own {@code createEpisodeResponse}, layout whitespace included, read end to end. */
    @Test
    void readsTheEhrMockCreateEpisodeResponse() {
        Clock clock = Clock.fixed(Instant.parse("2015-01-23T04:10:20.123Z"), ZoneOffset.ofHours(-8));
        EhrMockResponseMapper ehrMock = new EhrMockResponseMapper(new DwXmlWriter(), clock);
        Element createEpisode = parse("<ns0:createEpisode xmlns:ns0=\"" + NS0_URI + "\" xmlns:ns1=\"" + NS1_URI + "\">"
                + "<ns1:PatientId>P1</ns1:PatientId>"
                + "</ns0:createEpisode>");

        String xml = mapper.toXml(ehrMock.createEpisodeResponse(createEpisode));

        assertThat(xml).isEqualTo(document(
                "\t\t<episodeId>2015-01-22T20:10:20.123-08:00</episodeId>",
                "\t\t<ns1:PatientId>P1</ns1:PatientId>",
                "\t\t<admission>Elective</admission>",
                "\t\t<startDate>2015-01-22</startDate>",
                "\t\t<endDate>2015-01-27</endDate>",
                "\t\t<care>Private</care>"));
    }

    /** A blank value is never parsed, so only a non-blank one carries the last parser failure as its cause. */
    private void assertCoercionFailure(Element input, String field, String value) {
        AbstractThrowableAssert<?, ? extends Throwable> failure = assertThatThrownBy(() -> mapper.toXml(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cannot coerce " + field + " value '" + value + "' to :date");
        if (value.isBlank()) {
            failure.hasNoCause();
        } else {
            failure.hasCauseInstanceOf(DateTimeParseException.class);
        }
    }

    /**
     * The EHRService {@code createEpisodeResponse} body root, with {@code startDate} and {@code endDate} holding
     * the given content and each omitted when {@code null}.
     */
    private static String episodeResponse(String startDate, String endDate) {
        StringBuilder xml = new StringBuilder()
                .append("<ns0:createEpisodeResponse xmlns:ns0=\"").append(NS0_URI)
                .append("\" xmlns:ns1=\"").append(NS1_URI).append("\">")
                .append("<ns1:Episode>")
                .append("<episodeId>E1</episodeId>")
                .append("<ns1:PatientId>P1</ns1:PatientId>")
                .append("<admission>Elective</admission>");
        if (startDate != null) {
            xml.append("<startDate>").append(startDate).append("</startDate>");
        }
        if (endDate != null) {
            xml.append("<endDate>").append(endDate).append("</endDate>");
        }
        return xml.append("<care>Private</care>")
                .append("</ns1:Episode>")
                .append("</ns0:createEpisodeResponse>")
                .toString();
    }

    /** The expected document for the standard Episode fields, with the given date lines between admission and care. */
    private static String standardDocument(String... dateLines) {
        List<String> episodeLines = new ArrayList<>();
        episodeLines.add("\t\t<episodeId>E1</episodeId>");
        episodeLines.add("\t\t<ns1:PatientId>P1</ns1:PatientId>");
        episodeLines.add("\t\t<admission>Elective</admission>");
        episodeLines.addAll(List.of(dateLines));
        episodeLines.add("\t\t<care>Private</care>");
        return document(episodeLines.toArray(String[]::new));
    }

    /** The expected document with the given lines as the Episode's content. */
    private static String document(String... episodeLines) {
        List<String> lines = new ArrayList<>();
        lines.add("<?xml version='1.0' encoding='UTF-8'?>");
        lines.add("<ns0:admitSubjectResponse xmlns:ns0=\"http://www.mule-health.com/SOA/message/1.0\">");
        lines.add("\t<ns1:Episode xmlns:ns1=\"http://www.mule-health.com/SOA/model/1.0\">");
        lines.addAll(List.of(episodeLines));
        lines.add("\t</ns1:Episode>");
        lines.add("\t<ns1:Bill  xmlns:ns1=\"http://www.mule-health.com/SOA/model/1.0\">");
        lines.add("\t\t<costPerNight>100</costPerNight>");
        lines.add("\t\t<initialStateEstimate>5</initialStateEstimate>");
        lines.add("\t\t<runningTotal>500</runningTotal>");
        lines.add("\t\t<status>ADMITTED</status>");
        lines.add("\t</ns1:Bill >");
        lines.add("</ns0:admitSubjectResponse>");
        return String.join("\n", lines);
    }

    private static List<Element> elementChildren(Element parent) {
        List<Element> children = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child) {
                children.add(child);
            }
        }
        return children;
    }

    private static Element parse(String xml) {
        return parse(xml, true);
    }

    /** Parses with DOCTYPE declarations rejected, XInclude off and entity references left unexpanded. */
    private static Element parse(String xml, boolean namespaceAware) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setNamespaceAware(namespaceAware);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            return builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalStateException("Cannot parse test input: " + e.getMessage(), e);
        }
    }
}
