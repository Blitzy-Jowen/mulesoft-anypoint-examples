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
import java.net.URLDecoder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.support.SimpleBeanDefinitionRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xmlunit.xpath.JAXPXPathEngine;

import com.mulesoft.examples.xml_only_soap_webservice.config.WebServiceConfig;
import com.mulesoft.examples.xml_only_soap_webservice.support.HttpTestClient;
import com.mulesoft.examples.xml_only_soap_webservice.support.HttpTestClient.Response;

/**
 * Wire-level test of {@link EhrServiceMockEndpoint}, the converted mock {@code EHRService} flow
 * ({@code xml-only-soap-webservice/src/main/app/mocks.xml:45-84}): the {@code http:listener path="EHRService"}, which
 * accepts every method, behind a {@code cxf:proxy-service payload="body"}, choosing on {@code fn:local-name(/*)}
 * between the {@code createEpisodeResponse} script ({@code mocks.xml:52}) and the {@code otherwise}
 * {@code findEpisodesResponse} script ({@code mocks.xml:71}).
 *
 * <p>Every request runs through the whole application on a random port and the raw-socket {@link HttpTestClient}, so
 * the status line with its reason phrase, the headers and the envelope bytes are asserted as Undertow sent them,
 * together with the listener-level behaviour of SC-11 and {@link WebServiceConfig}: the {@code soap:Client} fault for
 * a body that is not a SOAP envelope, the no-listener 404 and {@code ?wsdl}/{@code ?xsd=} publication.
 * {@code MockWebServiceClient} is not used, because the project dispatches by listener path, which only an HTTP
 * transport carries. The only test double is {@link FixedClockConfig}'s clock, which makes every DataWeave
 * {@code now} the fixed {@link #NOW}; response element order is checked on DOM child lists, never by substring.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EhrServiceMockEndpointTest {

    /** The text every {@code now} takes on {@link FixedClockConfig#testClock()}: Los Angeles in January, -08:00. */
    static final String NOW = "2015-01-22T20:10:20.123-08:00";

    private static final String SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String NS0 = "http://www.mule-health.com/SOA/message/1.0";
    private static final String NS1 = "http://www.mule-health.com/SOA/model/1.0";
    private static final String WSDL_SOAP_NS = "http://schemas.xmlsoap.org/wsdl/soap/";
    private static final String XSD_NS = "http://www.w3.org/2001/XMLSchema";

    /** The CXF envelope style: {@code soap} prefix, no Header, no XML declaration, no whitespace between them. */
    private static final String ENVELOPE_START =
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>";
    private static final String ENVELOPE_END = "</soap:Body></soap:Envelope>";

    private static final String P = WebServiceConfig.EHR_PATH;

    private static final String XML_CONTENT_TYPE = "text/xml;charset=UTF-8";
    private static final Map<String, String> XML_HEADERS = Map.of("Content-Type", XML_CONTENT_TYPE);

    /** Upper bound of the schema-closure walk, so a rewrite loop fails the test instead of hanging it. */
    private static final int MAX_SCHEMA_FETCHES = 20;

    /** Replaces {@code ClockConfig}'s system clock for every mock {@code now}; it is primary, not an override. */
    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2015-01-23T04:10:20.123Z"), ZoneId.of("America/Los_Angeles"));
        }
    }

    /** One raw request for the parameterized boundary cases. */
    private record SoapRequest(String method, Map<String, String> headers, byte[] body) {
    }

    @LocalServerPort
    int port;

    private HttpTestClient http;

    @BeforeEach
    void setUp() {
        http = HttpTestClient.localhost(port);
    }

    @Test
    void createEpisodeEchoesPatientIdWithNowDates() throws Exception {
        Element root = assertSoapResponse(postXml(P,
                envelope("<ns0:createEpisode><ns1:PatientId>1234</ns1:PatientId></ns0:createEpisode>")));

        assertName(root, NS0, "ns0", "createEpisodeResponse");
        List<Element> episodes = children(root);
        assertThat(episodes).hasSize(1);
        Element episode = episodes.get(0);
        assertName(episode, NS1, "ns1", "Episode");

        List<Element> fields = children(episode);
        assertThat(fields).extracting(Element::getLocalName)
                .containsExactly("episodeId", "PatientId", "admission", "startDate", "endDate", "care");
        assertThat(fields).extracting(Element::getNamespaceURI)
                .containsExactly(null, NS1, null, null, null, null);
        assertThat(fields.get(1).getPrefix()).isEqualTo("ns1");
        assertThat(fields).extracting(Element::getTextContent)
                .containsExactly(NOW, "1234", "Elective", NOW, NOW, "Private");
    }

    // Every root other than createEpisode takes the otherwise script; its selector reads findEpisodes only, so any
    // other root, or an empty Body (the endpoint receives null), yields an empty PatientId rather than a fault.
    @ParameterizedTest
    @MethodSource("otherwiseBranchBodies")
    void otherwiseBranchAnswersFindEpisodesResponse(String bodyContent, String expectedPatientId) throws Exception {
        Element root = assertSoapResponse(postXml(P, envelope(bodyContent)));

        assertName(root, NS0, "ns0", "findEpisodesResponse");
        List<Element> episodes = children(root);
        assertThat(episodes).hasSize(1);
        Element episode = episodes.get(0);
        assertName(episode, NS1, "ns1", "Episode");

        List<Element> fields = children(episode);
        assertThat(fields).hasSize(1);
        assertName(fields.get(0), NS1, "ns1", "PatientId");
        assertThat(fields.get(0).getTextContent()).isEqualTo(expectedPatientId);
    }

    /**
     * U+1F600 lies outside the Basic Multilingual Plane: CXF echoed it as its four UTF-8 bytes, as it did the BMP
     * characters, where SAAJ alone writes {@code &#128512;}; Content-Length counts the bytes sent.
     */
    @Test
    void findEpisodesEchoesCharactersOutsideTheBmpAsRawUtf8() throws Exception {
        String patientId = "Zo\u00eb-\u674e-\uD83D\uDE00";
        Response response = postXml(P,
                envelope("<ns0:findEpisodes><ns1:PatientId>" + patientId + "</ns1:PatientId></ns0:findEpisodes>"));

        Element root = assertSoapResponse(response);
        assertThat(children(children(root).get(0)).get(0).getTextContent()).isEqualTo(patientId);
        assertThat(response.bodyAsString()).contains(">" + patientId + "</ns1:PatientId>").doesNotContain("&#");
        assertThat(response.header("Content-Length")).isEqualTo(Integer.toString(response.body().length));
    }

    static Stream<Arguments> otherwiseBranchBodies() {
        return Stream.of(
                Arguments.of(Named.of("findEpisodes echoes its PatientId",
                        "<ns0:findEpisodes><ns1:PatientId>1234</ns1:PatientId></ns0:findEpisodes>"), "1234"),
                Arguments.of(Named.of("unknown operation deleteEpisode",
                        "<ns0:deleteEpisode><ns1:PatientId>1234</ns1:PatientId></ns0:deleteEpisode>"), ""),
                Arguments.of(Named.of("empty Body", ""), ""));
    }

    // The listener accepts every method, so a plain GET is read as a message and never answered with 405.
    @ParameterizedTest
    @MethodSource("unreadableRequests")
    void unreadableRequestGetsSoapClientFault(SoapRequest request) throws Exception {
        Response response = http.send(request.method(), P, request.headers(), request.body());

        Element payload = assertEnvelope(response, 500, "Internal Server Error");
        assertName(payload, SOAP_NS, "soap", "Fault");

        JAXPXPathEngine xpath = new JAXPXPathEngine();
        xpath.setNamespaceContext(Map.of("soap", SOAP_NS));
        Document document = payload.getOwnerDocument();
        assertThat(xpath.evaluate("count(/soap:Envelope/soap:Body/soap:Fault/faultcode)", document)).isEqualTo("1");
        assertThat(xpath.evaluate("/soap:Envelope/soap:Body/soap:Fault/faultcode", document))
                .isEqualTo("soap:Client");
        assertThat(xpath.evaluate("/soap:Envelope/soap:Body/soap:Fault/faultstring", document)).isNotBlank();
    }

    static Stream<Arguments> unreadableRequests() {
        String unclosed = "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\"><soapenv:Body>"
                + "<ns0:createEpisode xmlns:ns0=\"http://www.mule-health.com/SOA/message/1.0\">";
        return Stream.of(
                Arguments.of(Named.of("malformed XML",
                        new SoapRequest("POST", XML_HEADERS, unclosed.getBytes(UTF_8)))),
                Arguments.of(Named.of("empty body", new SoapRequest("POST", XML_HEADERS, new byte[0]))),
                Arguments.of(Named.of("non-envelope root",
                        new SoapRequest("POST", XML_HEADERS, "<foo/>".getBytes(UTF_8)))),
                Arguments.of(Named.of("plain GET", new SoapRequest("GET", Map.of(), null))));
    }

    // The listener path is matched exactly, so a sub-path is another path, and the 404 text omits the query.
    @ParameterizedTest
    @MethodSource("unservedTargets")
    void unservedPathGetsNoListener404(String target, String expectedPath) {
        Response response = http.get(target);

        assertStatus(response, 404, "Not Found");
        assertThat(contentType(response)).isEqualTo("text/plain");
        assertThat(response.body()).as("%s", response)
                .isEqualTo(("No listener for endpoint: " + expectedPath).getBytes(UTF_8));
    }

    static Stream<Arguments> unservedTargets() {
        return Stream.of(
                Arguments.of("/EHRService/extra", "/EHRService/extra"),
                Arguments.of("/EHRService/extra?x=1", "/EHRService/extra"));
    }

    @Test
    void wsdlIsPublishedWithRewrittenAddressAndSchemaClosure() throws Exception {
        String base = "http://localhost:" + port;
        String xsdUrlPrefix = base + P + "?xsd=";

        Response wsdl = http.get(P + "?wsdl");
        assertStatus(wsdl, 200, "OK");
        assertThat(contentType(wsdl)).startsWith("text/xml");
        // Only the address is checked: the namespaces of the WSDL also start with http://www.mule-health.com.
        assertThat(wsdl.bodyAsString()).doesNotContain("location=\"http://www.mule-health.com\"");

        Document wsdlDocument = parse(wsdl.body());
        NodeList addresses = wsdlDocument.getElementsByTagNameNS(WSDL_SOAP_NS, "address");
        assertThat(addresses.getLength()).isEqualTo(1);
        assertThat(((Element) addresses.item(0)).getAttribute("location")).isEqualTo(base + P);

        Deque<String> pending = new ArrayDeque<>(schemaLocations(wsdlDocument));
        assertThat(pending).as("schema references of the WSDL").isNotEmpty();
        Set<String> visited = new HashSet<>();
        Map<String, String> targetNamespaceByKey = new LinkedHashMap<>();
        int fetches = 0;
        while (!pending.isEmpty()) {
            String url = pending.removeFirst();
            if (!visited.add(url)) {
                continue;
            }
            assertThat(++fetches).as("schema fetches").isLessThanOrEqualTo(MAX_SCHEMA_FETCHES);
            assertThat(url).startsWith(xsdUrlPrefix);

            Response schema = http.get(url.substring(base.length()));
            assertStatus(schema, 200, "OK");
            assertThat(contentType(schema)).startsWith("text/xml");
            Element schemaRoot = parse(schema.body()).getDocumentElement();
            assertThat(schemaRoot.getNamespaceURI()).isEqualTo(XSD_NS);
            assertThat(schemaRoot.getLocalName()).isEqualTo("schema");

            String key = URLDecoder.decode(url.substring(xsdUrlPrefix.length()), UTF_8);
            targetNamespaceByKey.put(key, schemaRoot.getAttribute("targetNamespace"));
            pending.addAll(schemaLocations(schemaRoot.getOwnerDocument()));
        }

        // EHRService.wsdl imports xsd/SOA-Message-1.0.xsd relative to service/, which imports SOA-Model-1.0.xsd.
        assertThat(targetNamespaceByKey).hasSize(2);
        assertThat(targetNamespaceByKey).hasEntrySatisfying(keyEndingWith(targetNamespaceByKey, "SOA-Message-1.0.xsd"),
                namespace -> assertThat(namespace).isEqualTo(NS0));
        assertThat(targetNamespaceByKey).hasEntrySatisfying(keyEndingWith(targetNamespaceByKey, "SOA-Model-1.0.xsd"),
                namespace -> assertThat(namespace).isEqualTo(NS1));
    }

    // WebServiceConfig's listener-path mapping looks this endpoint up by name. Spring WS does not alias @Endpoint's
    // value to @Component, so the name is an explicit @Component name, never the deprecated convention-based reading
    // of the stereotype's value.
    @Test
    void beanNameIsTheExplicitComponentName() {
        Class<EhrServiceMockEndpoint> type = EhrServiceMockEndpoint.class;

        Endpoint endpoint = AnnotationUtils.findAnnotation(type, Endpoint.class);
        assertThat(endpoint).as("@Endpoint on %s", type.getSimpleName()).isNotNull();
        assertThat(endpoint.value()).as("@Endpoint value").isEmpty();
        Component component = type.getDeclaredAnnotation(Component.class);
        assertThat(component).as("@Component directly present on %s", type.getSimpleName()).isNotNull();
        assertThat(component.value()).as("@Component value").isEqualTo(WebServiceConfig.EHR_ENDPOINT);
        assertThat(new AnnotationBeanNameGenerator().generateBeanName(new AnnotatedGenericBeanDefinition(type),
                new SimpleBeanDefinitionRegistry())).as("generated bean name")
                .isEqualTo(WebServiceConfig.EHR_ENDPOINT);
    }

    /** UTF-8 bytes of a SOAP 1.1 request envelope declaring {@code ns0} and {@code ns1}, with an empty Header. */
    private static byte[] envelope(String bodyContent) {
        return ("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " xmlns:ns0=\"" + NS0 + "\" xmlns:ns1=\"" + NS1 + "\">"
                + "<soapenv:Header/><soapenv:Body>" + bodyContent + "</soapenv:Body></soapenv:Envelope>")
                .getBytes(UTF_8);
    }

    private Response postXml(String path, byte[] body) {
        return http.send("POST", path, XML_HEADERS, body);
    }

    /**
     * Asserts a successful response in the CXF envelope style and returns the single element of its SOAP Body.
     */
    private static Element assertSoapResponse(Response response) throws Exception {
        return assertEnvelope(response, 200, "OK");
    }

    /**
     * Asserts status, reason, an XML Content-Type without the {@code Accept} and {@code SOAPAction} request headers
     * SAAJ keeps on every message, the CXF envelope style ({@link #ENVELOPE_START} ... {@link #ENVELOPE_END},
     * Envelope holding only Body, no Header anywhere), and returns the Body's single element.
     */
    private static Element assertEnvelope(Response response, int status, String reason) throws Exception {
        assertStatus(response, status, reason);
        assertThat(contentType(response)).startsWith("text/xml");
        assertThat(response.header("Accept")).as("Accept of %s", response).isNull();
        assertThat(response.header("SOAPAction")).as("SOAPAction of %s", response).isNull();
        assertThat(response.bodyAsString()).as("%s", response).startsWith(ENVELOPE_START).endsWith(ENVELOPE_END);

        Document document = parse(response.body());
        Element envelope = document.getDocumentElement();
        assertName(envelope, SOAP_NS, "soap", "Envelope");
        assertThat(document.getElementsByTagNameNS(SOAP_NS, "Header").getLength()).isZero();
        List<Element> envelopeChildren = children(envelope);
        assertThat(envelopeChildren).hasSize(1);
        Element body = envelopeChildren.get(0);
        assertName(body, SOAP_NS, "soap", "Body");

        List<Element> payload = children(body);
        assertThat(payload).as("%s", response).hasSize(1);
        return payload.get(0);
    }

    private static void assertStatus(Response response, int status, String reason) {
        assertThat(response.status()).as("%s", response).isEqualTo(status);
        assertThat(response.reason()).as("%s", response).isEqualTo(reason);
    }

    private static void assertName(Element element, String namespace, String prefix, String localName) {
        assertThat(element.getNamespaceURI()).as("namespace of %s", element.getNodeName()).isEqualTo(namespace);
        assertThat(element.getPrefix()).as("prefix of %s", element.getNodeName()).isEqualTo(prefix);
        assertThat(element.getLocalName()).as("local name of %s", element.getNodeName()).isEqualTo(localName);
    }

    /** The lower-cased Content-Type, which must be present. */
    private static String contentType(Response response) {
        String contentType = response.header("Content-Type");
        assertThat(contentType).as("Content-Type of %s", response).isNotNull();
        return contentType.toLowerCase(Locale.ROOT);
    }

    /** Element children only; DataWeave's tab indentation inside the payload arrives as whitespace text nodes. */
    private static List<Element> children(Element parent) {
        List<Element> elements = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                elements.add(element);
            }
        }
        return elements;
    }

    /** Every {@code schemaLocation} of an XSD {@code import}, {@code include} or {@code redefine}, in document order. */
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

    private static String keyEndingWith(Map<String, String> map, String suffix) {
        return map.keySet().stream().filter(key -> key.endsWith(suffix)).findFirst()
                .orElseThrow(() -> new AssertionError("No schema key ending with " + suffix + " in " + map.keySet()));
    }

    /** Namespace-aware parse with DOCTYPE declarations and external entities refused. */
    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(xml));
    }
}
