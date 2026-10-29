/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.xml_only_soap_webservice.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Contract of {@link PatientMockResponseMapper}, the owner of the two scripts of the mock {@code PatientService}
 * flow: {@code upsertPatientResponse} ({@code xml-only-soap-webservice/src/main/app/mocks.xml:12}) and
 * {@code getPatientResponse} ({@code mocks.xml:24}).
 *
 * <p>Every {@code now} reads a fixed clock, so the documents are compared exactly, never by containment. Expected
 * documents are spelled out with {@code \n} and {@code \t}, ending at the root end tag as {@link DwXmlWriter}
 * writes them, with the namespace URIs as literals rather than through the mapper's constants.
 */
class PatientMockResponseMapperTest {

    private static final String NS0 = "http://www.mule-health.com/SOA/message/1.0";
    private static final String NS1 = "http://www.mule-health.com/SOA/model/1.0";

    /** 2015-01-23T04:10:20.123Z seen from Los Angeles, whose January offset is -08:00. */
    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2015-01-23T04:10:20.123Z"), ZoneId.of("America/Los_Angeles"));

    /** The text every {@code now} takes on {@link #FIXED}. */
    private static final String NOW = "2015-01-22T20:10:20.123-08:00";

    private static final List<String> PATIENT_FIELDS = List.of("patientId", "dateOfBirth", "gender", "nationality",
            "address1", "lastName", "firstName", "nationalId");

    private PatientMockResponseMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new PatientMockResponseMapper(new DwXmlWriter(), FIXED);
    }

    @Test
    void nowFormat() {
        assertThat(PatientMockResponseMapper.NOW_FORMAT).isEqualTo("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
        assertThat(DateTimeFormatter.ofPattern(PatientMockResponseMapper.NOW_FORMAT, Locale.ROOT)
                .format(ZonedDateTime.now(FIXED))).isEqualTo(NOW);
    }

    @Test
    void upsertPatientResponseXmlExact() {
        assertThat(mapper.upsertPatientResponseXml()).isEqualTo(upsertPatientResponseDocument(NOW));
    }

    @Test
    void getPatientResponseXmlExact() {
        assertThat(mapper.getPatientResponseXml()).isEqualTo(getPatientResponseDocument(NOW));
    }

    @Test
    void patientIdAndNationalIdShareOneNow() {
        PatientMockResponseMapper ticking = new PatientMockResponseMapper(new DwXmlWriter(),
                new TickingClock(FIXED.instant(), FIXED.getZone()));

        assertThat(ticking.getPatientResponseXml()).isEqualTo(getPatientResponseDocument(NOW));
        assertThat(ticking.getPatientResponseXml())
                .isEqualTo(getPatientResponseDocument("2015-01-22T20:10:20.124-08:00"));

        List<Element> fields = childElements(childElements(ticking.getPatientResponse()).get(0));
        assertThat(fields.get(0).getTextContent()).isEqualTo("2015-01-22T20:10:20.125-08:00");
        assertThat(fields.get(7).getTextContent()).isEqualTo(fields.get(0).getTextContent());
    }

    @Test
    void upsertPatientResponseElementStructure() {
        Element response = mapper.upsertPatientResponse();

        assertThat(response.getOwnerDocument().getXmlVersion()).isEqualTo("1.0");
        assertThat(response.getOwnerDocument().getXmlEncoding()).isEqualTo("UTF-8");
        assertQualified(response, "ns0", NS0, "upsertPatientResponse");

        List<Element> children = childElements(response);
        assertThat(children).hasSize(1);
        Element patientId = children.get(0);
        assertQualified(patientId, "ns1", NS1, "PatientId");
        assertThat(childElements(patientId)).isEmpty();
        assertThat(patientId.getTextContent()).isEqualTo(NOW);
    }

    @Test
    void getPatientResponseElementStructure() {
        Element response = mapper.getPatientResponse();

        assertThat(response.getOwnerDocument().getXmlEncoding()).isEqualTo("UTF-8");
        assertQualified(response, "ns0", NS0, "getPatientResponse");

        List<Element> children = childElements(response);
        assertThat(children).hasSize(1);
        Element patient = children.get(0);
        assertQualified(patient, "ns1", NS1, "Patient");

        List<Element> fields = childElements(patient);
        assertThat(fields).extracting(Element::getLocalName).containsExactlyElementsOf(PATIENT_FIELDS);
        assertThat(fields).allSatisfy(field -> {
            assertThat(field.getNamespaceURI()).isNull();
            assertThat(field.getPrefix()).isNull();
            assertThat(childElements(field)).isEmpty();
        });
        assertThat(fields).extracting(Element::getTextContent)
                .containsExactly(NOW, "1930-01-01", "Male", "USA", "DisneyLand", "Duck", "Donald", NOW);
    }

    @Test
    void utcClockRendersZ() {
        PatientMockResponseMapper utc = new PatientMockResponseMapper(new DwXmlWriter(),
                Clock.fixed(FIXED.instant(), ZoneOffset.UTC));

        assertThat(utc.upsertPatientResponseXml())
                .isEqualTo(upsertPatientResponseDocument("2015-01-23T04:10:20.123Z"));
        assertThat(utc.getPatientResponseXml()).isEqualTo(getPatientResponseDocument("2015-01-23T04:10:20.123Z"));
    }

    @ParameterizedTest
    @CsvSource({
        "2015-01-23T04:10:20.123456789Z, 2015-01-23T04:10:20.123Z",
        "2015-01-23T04:10:20.999999999Z, 2015-01-23T04:10:20.999Z"
    })
    void subMillisecondNanosTruncated(String instant, String expectedNow) {
        PatientMockResponseMapper utc = new PatientMockResponseMapper(new DwXmlWriter(),
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));

        assertThat(utc.upsertPatientResponseXml()).isEqualTo(upsertPatientResponseDocument(expectedNow));
        assertThat(utc.getPatientResponseXml()).isEqualTo(getPatientResponseDocument(expectedNow));
    }

    @Test
    void constants() {
        assertThat(PatientMockResponseMapper.NS0).isEqualTo(NS0);
        assertThat(PatientMockResponseMapper.NS1).isEqualTo(NS1);
        assertThat(PatientMockResponseMapper.NS0_PREFIX).isEqualTo("ns0");
        assertThat(PatientMockResponseMapper.NS1_PREFIX).isEqualTo("ns1");
    }

    @Test
    void constructorRejectsNullArguments() {
        assertThatNullPointerException().isThrownBy(() -> new PatientMockResponseMapper(null, FIXED))
                .withMessage("writer");
        assertThatNullPointerException().isThrownBy(() -> new PatientMockResponseMapper(new DwXmlWriter(), null))
                .withMessage("clock");
    }

    private static String upsertPatientResponseDocument(String now) {
        return String.join("\n",
                "<?xml version='1.0' encoding='UTF-8'?>",
                "<ns0:upsertPatientResponse xmlns:ns0=\"" + NS0 + "\">",
                "\t<ns1:PatientId xmlns:ns1=\"" + NS1 + "\">" + now + "</ns1:PatientId>",
                "</ns0:upsertPatientResponse>");
    }

    private static String getPatientResponseDocument(String now) {
        return String.join("\n",
                "<?xml version='1.0' encoding='UTF-8'?>",
                "<ns0:getPatientResponse xmlns:ns0=\"" + NS0 + "\">",
                "\t<ns1:Patient xmlns:ns1=\"" + NS1 + "\">",
                "\t\t<patientId>" + now + "</patientId>",
                "\t\t<dateOfBirth>1930-01-01</dateOfBirth>",
                "\t\t<gender>Male</gender>",
                "\t\t<nationality>USA</nationality>",
                "\t\t<address1>DisneyLand</address1>",
                "\t\t<lastName>Duck</lastName>",
                "\t\t<firstName>Donald</firstName>",
                "\t\t<nationalId>" + now + "</nationalId>",
                "\t</ns1:Patient>",
                "</ns0:getPatientResponse>");
    }

    private static void assertQualified(Element element, String prefix, String namespace, String localName) {
        assertThat(element.getPrefix()).isEqualTo(prefix);
        assertThat(element.getNamespaceURI()).isEqualTo(namespace);
        assertThat(element.getLocalName()).isEqualTo(localName);
    }

    /** The element children of {@code parent} in document order, skipping the whitespace text of the tab layout. */
    private static List<Element> childElements(Element parent) {
        List<Element> elements = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element) {
                elements.add(element);
            }
        }
        return elements;
    }

    /** A clock that advances one millisecond every time it is read, so two reads never return the same instant. */
    private static final class TickingClock extends Clock {

        private final AtomicReference<Instant> next;
        private final ZoneId zone;

        TickingClock(Instant start, ZoneId zone) {
            this(new AtomicReference<>(start), zone);
        }

        private TickingClock(AtomicReference<Instant> next, ZoneId zone) {
            this.next = next;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new TickingClock(next, zone);
        }

        @Override
        public Instant instant() {
            return next.getAndUpdate(current -> current.plusMillis(1));
        }
    }
}
