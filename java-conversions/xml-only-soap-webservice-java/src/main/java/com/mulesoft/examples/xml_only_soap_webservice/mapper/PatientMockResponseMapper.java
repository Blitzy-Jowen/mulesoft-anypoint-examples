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
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Hand-written reimplementation of the two {@code %dw 1.0} scripts of the mock {@code PatientService} flow
 * ({@code xml-only-soap-webservice/src/main/app/mocks.xml:4-44}), of which this class is the sole owner. Both
 * scripts declare {@code %namespace ns0 http://www.mule-health.com/SOA/message/1.0},
 * {@code %namespace ns1 http://www.mule-health.com/SOA/model/1.0} and {@code %output application/xml}, and
 * neither reads the payload:
 * <ul>
 *   <li>{@code mocks.xml:12}, the {@code upsertPatient} branch:
 *       <pre>
 * ns0#"upsertPatientResponse": {
 * 	ns1#"PatientId": now as :string
 * }</pre></li>
 *   <li>{@code mocks.xml:24}, the {@code otherwise} branch:
 *       <pre>
 * ns0#"getPatientResponse": {
 * 	ns1#"Patient": {
 * 		patientId: now as :string,
 * 		dateOfBirth: |1930-01-01|,
 * 		gender: "Male",
 * 		nationality: "USA",
 * 		address1: "DisneyLand",
 * 		lastName: "Duck",
 * 		firstName: "Donald",
 * 		nationalId: now as :string
 * 	}
 * }</pre></li>
 * </ul>
 * The choice between them ({@code xpath('fn:local-name(/*)')}, {@code mocks.xml:7-10}) belongs to
 * {@code service/PatientMockService}.
 *
 * <p>Naming convention: {@code <name>Xml()} returns the document text the script produced, written through
 * {@link DwXmlWriter} (DataWeave 1.0 declaration, tab indentation, LF line ends, no trailing line feed), and
 * {@code <name>()} returns the document element of that same text, parsed once, for the DOM endpoint to send.
 * Unqualified script keys become unqualified elements, as {@code SOA-Model-1.0.xsd} is
 * {@code elementFormDefault="unqualified"}.
 *
 * <p>{@code now}: every value reads the injected {@link Clock} (the {@code ClockConfig} bean, the JVM default
 * zone, as DataWeave's {@code now}), truncated to milliseconds and written as {@link #NOW_FORMAT}. Each public
 * call reads the clock once, so {@code patientId} and {@code nationalId} carry the same instant, as one
 * evaluation of the script does. The exact text DataWeave 1.0 wrote for {@code now as :string} is a Tier 2A
 * verification point; the scenarios that embed it are nondeterministic.
 *
 * <p>Stateless and thread-safe: the writer and the clock are immutable, and every call parses with its own parser.
 */
@Component
public class PatientMockResponseMapper {

    /** Namespace bound to {@code ns0} in both scripts: the SOA message schema. */
    public static final String NS0 = "http://www.mule-health.com/SOA/message/1.0";

    /** Namespace bound to {@code ns1} in both scripts: the SOA model schema. */
    public static final String NS1 = "http://www.mule-health.com/SOA/model/1.0";

    /** Prefix the scripts declare for {@link #NS0}. */
    public static final String NS0_PREFIX = "ns0";

    /** Prefix the scripts declare for {@link #NS1}. */
    public static final String NS1_PREFIX = "ns1";

    /** Text form of every {@code now}: ISO-8601 local date-time with milliseconds and the clock zone's offset. */
    public static final String NOW_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX";

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern(NOW_FORMAT, Locale.ROOT);

    /** The script's Date literal {@code |1930-01-01|}. */
    private static final LocalDate DATE_OF_BIRTH = LocalDate.of(1930, 1, 1);

    private static final String DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl";
    private static final String EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities";
    private static final String EXTERNAL_PARAMETER_ENTITIES =
            "http://xml.org/sax/features/external-parameter-entities";
    private static final String LOAD_EXTERNAL_DTD = "http://apache.org/xml/features/nonvalidating/load-external-dtd";

    private final DwXmlWriter writer;
    private final Clock clock;

    /**
     * Creates the mapper.
     *
     * @param writer the SC-4 DataWeave XML writer
     * @param clock the clock every {@code now} reads
     * @throws NullPointerException when either argument is {@code null}
     */
    public PatientMockResponseMapper(DwXmlWriter writer, Clock clock) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Script {@code mocks.xml:12} as text: {@code ns0:upsertPatientResponse} holding {@code ns1:PatientId} = now.
     *
     * @return the declaration and the document
     */
    public String upsertPatientResponseXml() {
        DwXmlWriter.Element response = DwXmlWriter.element(NS0_PREFIX, NS0, "upsertPatientResponse")
                .child(DwXmlWriter.element(NS1_PREFIX, NS1, "PatientId").text(now()));
        return writer.write(response);
    }

    /**
     * Script {@code mocks.xml:12} as a DOM element.
     *
     * @return the parsed document element of {@link #upsertPatientResponseXml()}, {@code {NS0}upsertPatientResponse}
     */
    public Element upsertPatientResponse() {
        return parse(upsertPatientResponseXml());
    }

    /**
     * Script {@code mocks.xml:24} as text: {@code ns0:getPatientResponse} holding {@code ns1:Patient} with the
     * fixed Donald Duck patient, whose {@code patientId} and {@code nationalId} are the same now.
     *
     * @return the declaration and the document
     */
    public String getPatientResponseXml() {
        String now = now();
        DwXmlWriter.Element patient = DwXmlWriter.element(NS1_PREFIX, NS1, "Patient").children(
                leaf("patientId", now),
                leaf("dateOfBirth", DATE_OF_BIRTH.format(DateTimeFormatter.ISO_LOCAL_DATE)),
                leaf("gender", "Male"),
                leaf("nationality", "USA"),
                leaf("address1", "DisneyLand"),
                leaf("lastName", "Duck"),
                leaf("firstName", "Donald"),
                leaf("nationalId", now));
        return writer.write(DwXmlWriter.element(NS0_PREFIX, NS0, "getPatientResponse").child(patient));
    }

    /**
     * Script {@code mocks.xml:24} as a DOM element.
     *
     * @return the parsed document element of {@link #getPatientResponseXml()}, {@code {NS0}getPatientResponse}
     */
    public Element getPatientResponse() {
        return parse(getPatientResponseXml());
    }

    private String now() {
        return ZonedDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS).format(FORMATTER);
    }

    private static DwXmlWriter.Element leaf(String name, String text) {
        return DwXmlWriter.element(name).text(text);
    }

    private static Element parse(String xml) {
        try {
            return newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();
        } catch (SAXException | IOException e) {
            throw new IllegalStateException("Cannot parse the PatientService mock response: " + e.getMessage(), e);
        }
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
            factory.setFeature(LOAD_EXTERNAL_DTD, false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("Cannot configure a secure XML parser: " + e.getMessage(), e);
        }
    }
}
