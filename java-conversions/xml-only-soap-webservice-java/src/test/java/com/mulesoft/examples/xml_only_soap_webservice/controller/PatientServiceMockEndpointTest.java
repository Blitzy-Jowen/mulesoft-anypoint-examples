/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.xml_only_soap_webservice.controller;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xmlunit.xpath.JAXPXPathEngine;

import com.mulesoft.examples.xml_only_soap_webservice.config.WebServiceConfig;
import com.mulesoft.examples.xml_only_soap_webservice.support.HttpTestClient;
import com.mulesoft.examples.xml_only_soap_webservice.support.HttpTestClient.Response;

/**
 * Wire-level endpoint test of {@link PatientServiceMockEndpoint}, the converted mock {@code PatientService} flow
 * ({@code xml-only-soap-webservice/src/main/app/mocks.xml:4-44}): an {@code http:listener path="PatientService"}
 * without {@code allowedMethods}, a {@code cxf:proxy-service payload="body"} publishing
 * {@code service/PatientService.wsdl}, and a choice on {@code fn:local-name(/*)} of the Body content —
 * {@code upsertPatient} answers {@code ns0:upsertPatientResponse}, every other root, and an empty Body, the fixed
 * Donald Duck {@code ns0:getPatientResponse}.
 *
 * <p>Dispatch is by listener path, so every request is a real HTTP call on the random port through the raw-socket
 * {@link HttpTestClient}: status line with reason phrase, headers and body bytes are asserted as Undertow sent them,
 * and element order is read from DOM child lists. The whole application runs unmodified apart from a fixed
 * {@code @Primary} clock, which makes every DataWeave {@code now} the value {@link #NOW}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PatientServiceMockEndpointTest {

    /** {@code 2015-01-23T04:10:20.123Z} in {@code America/Los_Angeles}, written in the mapper's {@code now} format. */
    static final String NOW = "2015-01-22T20:10:20.123-08:00";

    static final String SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/";
    static final String NS0 = "http://www.mule-health.com/SOA/message/1.0";
    static final String NS1 = "http://www.mule-health.com/SOA/model/1.0";
    static final String WSDL_SOAP_NS = "http://schemas.xmlsoap.org/wsdl/soap/";
    static final String XSD_NS = "http://www.w3.org/2001/XMLSchema";

    /** CXF's compact envelope start: {@code soap} prefix, no XML declaration, no empty Header. */
    static final String ENVELOPE_START =
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>";
    static final String ENVELOPE_END = "</soap:Body></soap:Envelope>";

    static final String P = WebServiceConfig.PATIENT_PATH;

    /** Upper bound on schema fetches while walking the published closure, so a reference cycle cannot loop. */
    private static final int MAX_SCHEMA_FETCHES = 20;

    private static final List<String> PATIENT_FIELDS = List.of("patientId", "dateOfBirth", "gender", "nationality",
            "address1", "lastName", "firstName", "nationalId");

    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2015-01-23T04:10:20.123Z"), ZoneId.of("America/Los_Angeles"));
        }
    }

    @LocalServerPort
    int port;

    private HttpTestClient http;

    @BeforeEach
    void client() {
        http = HttpTestClient.localhost(port);
    }

    @Test
    void upsertPatientReturnsPatientIdNow() {
        Response response = postXml(P, envelope("<ns0:upsertPatient><ns1:Subject><nationalId>1234</nationalId>"
                + "<firstName>Nial</firstName><lastName>Darbey</lastName></ns1:Subject></ns0:upsertPatient>"));

        Element root = assertSoapResponse(response);
        assertQName(root, NS0, "upsertPatientResponse", "ns0");
        List<Element> children = children(root);
        assertThat(children).as("children of %s", root.getNodeName()).hasSize(1);
        assertQName(children.get(0), NS1, "PatientId", "ns1");
        assertThat(children.get(0).getTextContent()).isEqualTo(NOW);
    }

    // getPatient, an operation the WSDL does not declare and an empty Body all take the same otherwise branch, so
    // they share one test; none of them is a fault.
    @ParameterizedTest(name = "{0}")
    @MethodSource("otherwiseBranchBodies")
    void otherwiseBranchReturnsDonaldDuck(String bodyContent) {
        assertGetPatientResponse(assertSoapResponse(postXml(P, envelope(bodyContent))));
    }

    static Stream<Arguments> otherwiseBranchBodies() {
        return Stream.of(
                Arguments.of(Named.of("getPatient",
                        "<ns0:getPatient><ns1:PatientId>1234</ns1:PatientId></ns0:getPatient>")),
                Arguments.of(Named.of("unknown operation deletePatient",
                        "<ns0:deletePatient><ns1:PatientId>1234</ns1:PatientId></ns0:deletePatient>")),
                Arguments.of(Named.of("empty Body, handed to the endpoint as null", "")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unreadableBodies")
    void unreadableBodyBecomesSoapClientFault(byte[] body) {
        assertClientFault(postXml(P, body));
    }

    static Stream<Arguments> unreadableBodies() {
        return Stream.of(
                Arguments.of(Named.of("malformed XML", ("<soapenv:Envelope xmlns:soapenv=\"" + SOAP_NS + "\">"
                        + "<soapenv:Body><ns0:upsertPatient xmlns:ns0=\"" + NS0 + "\">").getBytes(UTF_8))),
                Arguments.of(Named.of("empty body", new byte[0])),
                Arguments.of(Named.of("non-envelope root", "<foo/>".getBytes(UTF_8))));
    }

    // The listener declares no allowedMethods, so a GET without a metadata query is read as a message, never 405.
    @Test
    void plainGetIsReadAsMessageAndBecomesSoapClientFault() {
        assertClientFault(http.send("GET", P, Map.of(), null));
    }

    // The listener matches its path exactly; the query is not part of the reported path.
    @ParameterizedTest
    @ValueSource(strings = {"/PatientService/extra", "/PatientService/extra?x=1"})
    void subPathIsNoListener(String target) {
        assertNoListener(http.get(target), "/PatientService/extra");
    }

    @Test
    void wsdlIsPublishedWithRewrittenAddressAndSchemaClosure() {
        String base = "http://localhost:" + port;
        Response wsdl = http.get(P + "?wsdl");

        assertThat(wsdl.status()).as("%s", wsdl).isEqualTo(200);
        assertThat(wsdl.reason()).as("%s", wsdl).isEqualTo("OK");
        assertThat(contentType(wsdl)).startsWith("text/xml");
        assertThat(wsdl.bodyAsString()).doesNotContain("location=\"http://www.mule-health.com\"");
        Document definitions = parse(wsdl.body());
        NodeList addresses = definitions.getElementsByTagNameNS(WSDL_SOAP_NS, "address");
        assertThat(addresses.getLength()).as("soap:address elements").isEqualTo(1);
        assertThat(((Element) addresses.item(0)).getAttribute("location")).isEqualTo(base + "/PatientService");

        String xsdPrefix = base + P + "?xsd=";
        Deque<String> pending = new ArrayDeque<>(schemaLocations(definitions));
        assertThat(pending).as("schemaLocation references of the WSDL").isNotEmpty();
        Set<String> fetched = new LinkedHashSet<>();
        Map<String, String> targetNamespaceByLocation = new LinkedHashMap<>();
        while (!pending.isEmpty()) {
            String url = pending.removeFirst();
            if (!fetched.add(url)) {
                continue;
            }
            assertThat(fetched).as("schema fetches").hasSizeLessThanOrEqualTo(MAX_SCHEMA_FETCHES);
            assertThat(url).startsWith(xsdPrefix);

            Response schema = http.get(url.substring(base.length()));
            assertThat(schema.status()).as("%s", schema).isEqualTo(200);
            assertThat(schema.reason()).as("%s", schema).isEqualTo("OK");
            Document document = parse(schema.body());
            Element root = document.getDocumentElement();
            assertThat(root.getNamespaceURI()).as("root namespace of %s", url).isEqualTo(XSD_NS);
            assertThat(root.getLocalName()).as("root element of %s", url).isEqualTo("schema");
            targetNamespaceByLocation.put(URLDecoder.decode(url.substring(xsdPrefix.length()), UTF_8),
                    root.getAttribute("targetNamespace"));
            pending.addAll(schemaLocations(document));
        }

        // PatientService.wsdl imports xsd/SOA-Message-1.0.xsd relative to service/, which imports SOA-Model-1.0.xsd.
        assertThat(targetNamespaceByLocation.keySet())
                .anySatisfy(location -> assertThat(location).endsWith("SOA-Message-1.0.xsd"))
                .anySatisfy(location -> assertThat(location).endsWith("SOA-Model-1.0.xsd"));
        assertThat(targetNamespaceByLocation.values()).contains(NS0, NS1);
    }

    /** A one-line SOAP 1.1 request envelope with {@code ns0}/{@code ns1} declared and an empty Header. */
    private static byte[] envelope(String bodyContent) {
        return ("<soapenv:Envelope xmlns:soapenv=\"" + SOAP_NS + "\" xmlns:ns0=\"" + NS0 + "\" xmlns:ns1=\"" + NS1
                + "\"><soapenv:Header/><soapenv:Body>" + bodyContent + "</soapenv:Body></soapenv:Envelope>")
                .getBytes(UTF_8);
    }

    // No SOAPAction header: dispatch is by listener path. Host, Content-Length and Connection come from the client.
    private Response postXml(String path, byte[] body) {
        return http.send("POST", path, Map.of("Content-Type", "text/xml;charset=UTF-8"), body);
    }

    private static Document parse(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new AssertionError("Response body is not well-formed XML: " + new String(xml, UTF_8), e);
        }
    }

    /** The element children of {@code parent} in document order; the payload keeps DataWeave's indentation text. */
    private static List<Element> children(Element parent) {
        List<Element> elements = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                elements.add(element);
            }
        }
        return elements;
    }

    /** Asserts a 200 CXF-style envelope and returns the single element of its Body. */
    private static Element assertSoapResponse(Response response) {
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        assertThat(response.reason()).as("%s", response).isEqualTo("OK");
        assertThat(contentType(response)).startsWith("text/xml");
        assertThat(response.bodyAsString()).startsWith(ENVELOPE_START).endsWith(ENVELOPE_END);

        Element body = assertCxfEnvelope(parse(response.body()));
        List<Element> payload = children(body);
        assertThat(payload).as("Body children of %s", response).hasSize(1);
        return payload.get(0);
    }

    /** Asserts the HTTP 500 {@code soap:Client} fault of the listener boundary. */
    private static void assertClientFault(Response response) {
        assertThat(response.status()).as("%s", response).isEqualTo(500);
        assertThat(response.reason()).as("%s", response).isEqualTo("Internal Server Error");
        assertThat(contentType(response)).startsWith("text/xml");
        assertThat(response.bodyAsString()).startsWith(ENVELOPE_START);

        Document document = parse(response.body());
        List<Element> payload = children(assertCxfEnvelope(document));
        assertThat(payload).as("Body children of %s", response).hasSize(1);
        assertQName(payload.get(0), SOAP_NS, "Fault", "soap");

        JAXPXPathEngine xpath = new JAXPXPathEngine();
        xpath.setNamespaceContext(Map.of("soap", SOAP_NS));
        assertThat(xpath.evaluate("/soap:Envelope/soap:Body/soap:Fault/faultcode", document))
                .as("faultcode of %s", response).isEqualTo("soap:Client");
        assertThat(xpath.evaluate("/soap:Envelope/soap:Body/soap:Fault/faultstring", document))
                .as("faultstring of %s", response).isNotBlank();
    }

    /** Asserts the {@code soap:Envelope} with {@code soap:Body} first and no {@code soap:Header}; returns the Body. */
    private static Element assertCxfEnvelope(Document document) {
        Element envelope = document.getDocumentElement();
        assertQName(envelope, SOAP_NS, "Envelope", "soap");
        List<Element> structure = children(envelope);
        assertThat(structure).as("Envelope children").isNotEmpty();
        assertQName(structure.get(0), SOAP_NS, "Body", "soap");
        assertThat(document.getElementsByTagNameNS(SOAP_NS, "Header").getLength()).as("soap:Header elements")
                .isZero();
        return structure.get(0);
    }

    private static void assertNoListener(Response response, String path) {
        assertThat(response.status()).as("%s", response).isEqualTo(404);
        assertThat(response.reason()).as("%s", response).isEqualTo("Not Found");
        assertThat(contentType(response)).startsWith("text/plain");
        assertThat(response.body()).containsExactly(("No listener for endpoint: " + path).getBytes(UTF_8));
    }

    /** The fixed Donald Duck patient of {@code mocks.xml:24}, its eight unqualified fields in script order. */
    private static void assertGetPatientResponse(Element root) {
        assertQName(root, NS0, "getPatientResponse", "ns0");
        List<Element> children = children(root);
        assertThat(children).as("children of %s", root.getNodeName()).hasSize(1);
        Element patient = children.get(0);
        assertQName(patient, NS1, "Patient", "ns1");

        List<Element> fields = children(patient);
        assertThat(fields).extracting(Element::getLocalName).containsExactlyElementsOf(PATIENT_FIELDS);
        assertThat(fields).allSatisfy(field -> {
            assertThat(field.getNamespaceURI()).as("namespace of %s", field.getLocalName()).isNull();
            assertThat(field.getPrefix()).as("prefix of %s", field.getLocalName()).isNull();
        });
        assertThat(fields).extracting(Element::getTextContent)
                .containsExactly(NOW, "1930-01-01", "Male", "USA", "DisneyLand", "Duck", "Donald", NOW);
    }

    private static void assertQName(Element element, String namespace, String localName, String prefix) {
        assertThat(element.getNamespaceURI()).as("namespace of <%s>", element.getNodeName()).isEqualTo(namespace);
        assertThat(element.getLocalName()).as("local name of <%s>", element.getNodeName()).isEqualTo(localName);
        assertThat(element.getPrefix()).as("prefix of <%s>", element.getNodeName()).isEqualTo(prefix);
    }

    private static List<String> schemaLocations(Document document) {
        List<String> locations = new ArrayList<>();
        for (String reference : List.of("import", "include", "redefine")) {
            NodeList elements = document.getElementsByTagNameNS(XSD_NS, reference);
            for (int i = 0; i < elements.getLength(); i++) {
                String location = ((Element) elements.item(i)).getAttribute("schemaLocation");
                if (!location.isEmpty()) {
                    locations.add(location);
                }
            }
        }
        return locations;
    }

    private static String contentType(Response response) {
        String contentType = response.header("Content-Type");
        assertThat(contentType).as("Content-Type of %s", response).isNotNull();
        return contentType.toLowerCase(Locale.ROOT);
    }
}
