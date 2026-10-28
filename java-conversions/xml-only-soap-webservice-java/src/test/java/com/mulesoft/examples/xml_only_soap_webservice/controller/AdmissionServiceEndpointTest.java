package com.mulesoft.examples.xml_only_soap_webservice.controller;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Source;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.support.SimpleBeanDefinitionRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.server.endpoint.adapter.DefaultMethodEndpointAdapter;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
import org.xmlunit.builder.Input;
import org.xmlunit.xpath.JAXPXPathEngine;

import com.mulesoft.examples.xml_only_soap_webservice.config.WebServiceConfig;
import com.mulesoft.examples.xml_only_soap_webservice.support.HttpTestClient;
import com.mulesoft.examples.xml_only_soap_webservice.support.HttpTestClient.Response;

/**
 * Wire-level test of {@link AdmissionServiceEndpoint}, the converted {@code admitPatientService} flow of the original
 * {@code Hospital_Admissions_SOA.xml}: the {@code http:listener path="AdmissionService"}, which declares no
 * {@code allowedMethods}, in front of a {@code cxf:proxy-service payload="envelope"} publishing
 * {@code service/AdmissionService.wsdl}.
 *
 * <p>The whole application runs on a random port and every request goes through the raw-socket
 * {@link HttpTestClient}, so status code, reason phrase, headers and body bytes are asserted as Undertow sent them.
 * The admission chain calls the PatientService and EHRService mocks of the same process over HTTP, exactly as the
 * original's {@code http:request}s targeted its own listener; nothing is mocked. The only substitution is the clock:
 * {@link FixedClockConfig} makes every mock {@code now} equal {@link #NOW}, so the {@code Episode} values are exact.
 * The nested {@link UnderRootTrace} run covers the logger levels {@code application.yml} pins, in a context of its own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdmissionServiceEndpointTest {

    /** Every mock {@code now}: {@code 2015-01-23T04:10:20.123Z} in {@code America/Los_Angeles}. */
    static final String NOW = "2015-01-22T20:10:20.123-08:00";

    static final String SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/";
    static final String NS0 = "http://www.mule-health.com/SOA/message/1.0";
    static final String NS1 = "http://www.mule-health.com/SOA/model/1.0";
    static final String WSDL_SOAP_NS = "http://schemas.xmlsoap.org/wsdl/soap/";
    static final String XSD_NS = "http://www.w3.org/2001/XMLSchema";

    /** The compact CXF envelope opening: {@code soap} prefix, no XML declaration, no {@code Header}. */
    static final String ENVELOPE_START = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
            + "<soap:Body>";
    static final String ENVELOPE_END = "</soap:Body></soap:Envelope>";

    static final String P = WebServiceConfig.ADMISSION_PATH;

    /** The content type every request body is sent with; the original test sent no {@code SOAPAction}. */
    static final String XML_CONTENT_TYPE = "text/xml;charset=UTF-8";

    /** The schema import of {@code AdmissionService.wsdl}, which resolves only from the classpath root. */
    static final String CLASSPATH_ROOT_IMPORT = "service/xsd/SOA-Message-1.0.xsd";

    /** Upper bound of the schema-closure walk, so a rewrite loop fails the test instead of hanging it. */
    static final int MAX_SCHEMA_FETCHES = 20;

    @TestConfiguration
    static class FixedClockConfig {

        // Named testClock: a second bean named clock would override ClockConfig's, which Boot rejects.
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2015-01-23T04:10:20.123Z"), ZoneId.of("America/Los_Angeles"));
        }
    }

    @LocalServerPort
    int port;

    HttpTestClient http;

    @BeforeEach
    void client() {
        http = HttpTestClient.localhost(port);
    }

    @Test
    void admitSubjectReturnsEpisodeAndBillInOrder() {
        Response response = postXml(P, message());

        Element root = assertSoapResponse(response);
        assertQName(root, NS0, "ns0", "admitSubjectResponse");

        List<Element> parts = children(root);
        assertThat(parts).extracting(Element::getLocalName).containsExactly("Episode", "Bill");
        Element episode = parts.get(0);
        // The script keys the bill "Bill " with a trailing space; parsed XML can only carry the name Bill.
        Element bill = parts.get(1);
        assertQName(episode, NS1, "ns1", "Episode");
        assertQName(bill, NS1, "ns1", "Bill");

        List<Element> episodeFields = children(episode);
        assertThat(episodeFields).extracting(Element::getLocalName)
                .containsExactly("episodeId", "PatientId", "admission", "startDate", "endDate", "care");
        // episodeId is the EHR mock's now; PatientId is the PatientService mock's now, carried through createEpisode.
        assertLeaf(episodeFields.get(0), null, null, "episodeId", NOW);
        assertLeaf(episodeFields.get(1), NS1, "ns1", "PatientId", NOW);
        assertLeaf(episodeFields.get(2), null, null, "admission", "Elective");
        // startDate is the upstream startDate as a date; endDate is the upstream endDate as a date plus P5D.
        assertLeaf(episodeFields.get(3), null, null, "startDate", "2015-01-22");
        assertLeaf(episodeFields.get(4), null, null, "endDate", "2015-01-27");
        assertLeaf(episodeFields.get(5), null, null, "care", "Private");

        // The script's initialStateEstimate governs over the initialStayEstimate of reply.xml, which no test reads.
        List<Element> billFields = children(bill);
        assertThat(billFields).extracting(Element::getLocalName)
                .containsExactly("costPerNight", "initialStateEstimate", "runningTotal", "status");
        assertLeaf(billFields.get(0), null, null, "costPerNight", "100");
        assertLeaf(billFields.get(1), null, null, "initialStateEstimate", "5");
        assertLeaf(billFields.get(2), null, null, "runningTotal", "500");
        assertLeaf(billFields.get(3), null, null, "status", "ADMITTED");
    }

    /**
     * The listener was a {@code cxf:proxy-service}, from which Mule 3.8 removed CXF's
     * {@code MustUnderstandInterceptor}, so a {@code mustUnderstand="1"} header block nothing understands is not
     * checked: the request is processed and answered exactly as it is without the block, never with a
     * {@code soap:MustUnderstand} fault.
     */
    @Test
    void mustUnderstandHeaderBlockIsNotCheckedAndRequestIsProcessed() {
        String plain = new String(message(), UTF_8);
        String withBlock = plain.replace("<soapenv:Header/>",
                "<soapenv:Header><x:Sec xmlns:x=\"urn:x\" soapenv:mustUnderstand=\"1\"/></soapenv:Header>");
        assertThat(withBlock).as("message.xml with the header block").isNotEqualTo(plain);

        Response response = postXml(P, withBlock.getBytes(UTF_8));

        assertQName(assertSoapResponse(response), NS0, "ns0", "admitSubjectResponse");
        assertThat(response.bodyAsString()).as("body of %s", response).doesNotContain("Fault")
                .isEqualTo(postXml(P, message()).bodyAsString());
    }

    /**
     * Requests the listener boundary cannot read as a SOAP 1.1 envelope. The listener accepts every method, so a
     * plain {@code GET} without a metadata query is read as a message too and faults, never 405.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("unreadableRequests")
    void unreadableRequestIsClientFault(String method, byte[] body) {
        assertClientFault(send(method, P, body));
    }

    static Stream<Arguments> unreadableRequests() {
        byte[] message = message();
        return Stream.of(
                Arguments.of("POST", Named.of("malformedXmlIsClientFault",
                        Arrays.copyOf(message, message.length - 40))),
                Arguments.of("POST", Named.of("emptyBodyIsClientFault", new byte[0])),
                Arguments.of("POST", Named.of("nonEnvelopeRootIsClientFault", "<foo/>".getBytes(UTF_8))),
                Arguments.of("GET", Named.<byte[]>of("plainGetIsReadAsMessageAndFaults", null)));
    }

    /** Paths no listener serves; the listener matches its path exactly and the query is not part of it. */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("unservedPaths")
    void unservedPathIsNoListener(String method, String target, byte[] body, String path) {
        assertNoListener(send(method, target, body), path);
    }

    static Stream<Arguments> unservedPaths() {
        return Stream.of(
                Arguments.of("GET", P + "/extra", null, "/AdmissionService/extra"),
                Arguments.of("GET", P + "/extra?x=1", null, "/AdmissionService/extra"),
                Arguments.of("GET", "/unknown", null, "/unknown"),
                Arguments.of("POST", "/", message(), "/"));
    }

    @Test
    void wsdlIsPublishedWithRewrittenAddressAndSchemaClosure() {
        String base = "http://localhost:" + port;
        String xsdPrefix = base + P + "?xsd=";

        Response wsdl = send("GET", P + "?wsdl", null);
        assertThat(wsdl.status()).as("%s", wsdl).isEqualTo(200);
        assertThat(wsdl.reason()).as("%s", wsdl).isEqualTo("OK");
        assertThat(contentType(wsdl)).startsWith("text/xml");
        Document wsdlDocument = parse(wsdl.body());

        NodeList addresses = wsdlDocument.getElementsByTagNameNS(WSDL_SOAP_NS, "address");
        assertThat(addresses.getLength()).as("soap:address elements in %s", wsdl).isEqualTo(1);
        assertThat(((Element) addresses.item(0)).getAttribute("location")).isEqualTo(base + P);
        // The target namespaces legitimately start with http://www.mule-health.com; only the placeholder must go.
        assertThat(wsdl.bodyAsString()).doesNotContain("location=\"http://www.mule-health.com\"");

        List<String> wsdlReferences = schemaLocations(wsdlDocument);
        assertThat(wsdlReferences).as("schema references of %s", wsdl).isNotEmpty();

        Deque<String> pending = new ArrayDeque<>(wsdlReferences);
        Set<String> fetched = new LinkedHashSet<>();
        List<String> keys = new ArrayList<>();
        Set<String> targetNamespaces = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            String url = pending.poll();
            if (!fetched.add(url)) {
                continue;
            }
            assertThat(fetched.size()).as("schema fetches so far: %s", fetched).isLessThanOrEqualTo(MAX_SCHEMA_FETCHES);
            assertThat(url).as("rewritten schemaLocation").startsWith(xsdPrefix);
            String key = URLDecoder.decode(url.substring(xsdPrefix.length()), UTF_8);

            Response schema = send("GET", url.substring(base.length()), null);
            String fallbackNote = CLASSPATH_ROOT_IMPORT.equals(key)
                    ? " (AdmissionService.wsdl imports it relative to the classpath root, so this 200 proves the"
                            + " classpath-root fallback of SC-11 WsdlQueryPublisher)"
                    : "";
            assertThat(schema.status()).as("GET %s%s: %s", url, fallbackNote, schema).isEqualTo(200);
            assertThat(schema.reason()).as("%s", schema).isEqualTo("OK");
            assertThat(contentType(schema)).startsWith("text/xml");

            Document schemaDocument = parse(schema.body());
            Element root = schemaDocument.getDocumentElement();
            assertQName(root, XSD_NS, null, "schema");
            keys.add(key);
            targetNamespaces.add(root.getAttribute("targetNamespace"));
            pending.addAll(schemaLocations(schemaDocument));
        }

        assertThat(keys).as("served xsd= keys")
                .anyMatch(key -> key.endsWith("SOA-Message-1.0.xsd"))
                .anyMatch(key -> key.endsWith("SOA-Model-1.0.xsd"));
        assertThat(targetNamespaces).as("schema target namespaces").contains(NS0, NS1);
    }

    /** The admission of {@link LoggedAdmission} under {@code logging.level.root=TRACE}. */
    @Nested
    @TestPropertySource(properties = "logging.level.root=TRACE")
    class UnderRootTrace extends LoggedAdmission {
    }

    /**
     * Tests the logger levels {@code application.yml} pins, which keep the patient out of the log under a root
     * {@code TRACE} level: {@code Hospital_Admissions_SOA.xml} and {@code mocks.xml} declare no logger.
     *
     * <p>The whole application runs on a random port in a context of its own, with the real clock. The original
     * test's {@code admitSubject} is posted to {@link WebServiceConfig#ADMISSION_PATH}, which upserts the patient at
     * the PatientService mock and creates the episode at the EHRService mock over HTTP, so every client and server
     * {@code MessageTracing} logger of the chain is reached. The exchange is asserted on the wire, then its own log
     * events are asserted to hold the unpinned {@code DefaultMethodEndpointAdapter} {@code TRACE} invocation line and
     * the unpinned {@code WebServiceTemplate} {@code DEBUG} connection line to each mock, which prove the capture sees
     * those levels, and the console output is asserted to hold none of the {@code Subject}'s values. The run restores
     * the logger levels it found and closes its context, so later test classes do not run at {@code TRACE}.
     */
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @DirtiesContext
    @ExtendWith(OutputCaptureExtension.class)
    abstract static class LoggedAdmission {

        /** The {@code Subject} values of {@code message.xml}, which must stay out of the log. */
        static final List<String> PATIENT_DATA =
                List.of("<nationalId>1234", "Nial", "Darbey", "Buenos Aires", "1970-08-07");

        /** How long lines written after the reply get to arrive before the output is scanned for patient data. */
        private static final Duration SETTLE = Duration.ofMillis(300);

        /** Most offending lines a failure reports; the capture holds the whole run, so it is never dumped. */
        private static final int REPORTED_LINES = 20;

        /** The logger levels set before this run's context started, by logger name. */
        private static final Map<String, Level> LEVELS_BEFORE = new HashMap<>();

        @LocalServerPort
        int port;

        @BeforeAll
        static void recordLevels() {
            LEVELS_BEFORE.clear();
            for (Logger logger : loggerContext().getLoggerList()) {
                if (logger.getLevel() != null) {
                    LEVELS_BEFORE.put(logger.getName(), logger.getLevel());
                }
            }
        }

        @AfterAll
        static void restoreLevels() {
            for (Logger logger : loggerContext().getLoggerList()) {
                Level before = LEVELS_BEFORE.get(logger.getName());
                // The root logger always has a level and cannot be given none.
                if (before != null || !org.slf4j.Logger.ROOT_LOGGER_NAME.equals(logger.getName())) {
                    logger.setLevel(before);
                }
            }
        }

        @Test
        void admissionAnswersWhileTheLogHoldsNoPatientData(CapturedOutput output) {
            byte[] message = message();
            assertThat(new String(message, UTF_8)).contains(PATIENT_DATA);
            Logger root = loggerContext().getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            ListAppender<ILoggingEvent> events = new ListAppender<>();
            events.setContext(loggerContext());
            events.start();
            root.addAppender(events);
            Response response;
            try {
                response = HttpTestClient.localhost(port).send("POST", P,
                        Map.of("Content-Type", XML_CONTENT_TYPE), message);
            } finally {
                root.detachAppender(events);
                events.stop();
            }
            // The appender appends under its own lock, so reading under it sees every event the request wrote.
            List<ILoggingEvent> logged;
            synchronized (events) {
                logged = List.copyOf(events.list);
            }

            assertQName(assertSoapResponse(response), NS0, "ns0", "admitSubjectResponse");

            ILoggingEvent invocation = logged.stream()
                    .filter(event -> event.getLevel() == Level.TRACE
                            && DefaultMethodEndpointAdapter.class.getName().equals(event.getLoggerName())
                            && event.getFormattedMessage().startsWith("Invoking [")
                            && event.getFormattedMessage().contains(AdmissionServiceEndpoint.class.getName()))
                    .findFirst()
                    .orElse(null);
            assertThat(invocation).as("the unpinned DefaultMethodEndpointAdapter TRACE line of the admission")
                    .isNotNull();
            for (String mockPath : List.of(WebServiceConfig.PATIENT_PATH, WebServiceConfig.EHR_PATH)) {
                assertThat(logged).as("the unpinned WebServiceTemplate DEBUG connection line to %s", mockPath)
                        .anyMatch(event -> event.getLevel() == Level.DEBUG
                                && WebServiceTemplate.class.getName().equals(event.getLoggerName())
                                && event.getFormattedMessage().startsWith("Opening [")
                                && event.getFormattedMessage().endsWith(mockPath + "]"));
            }
            assertThat(output.getAll()).as("the console capture holds the TRACE line of the admission")
                    .contains(invocation.getFormattedMessage());
            // Lines written after the reply get SETTLE to arrive; the capture only grows, so one scan covers the wait.
            await().pollDelay(SETTLE).until(() -> true);
            assertThat(linesHoldingPatientData(output.getAll())).as("console lines holding the patient's Subject")
                    .isEmpty();
        }

        /** The distinct lines of {@code output} holding a {@code Subject} value, at most {@link #REPORTED_LINES}. */
        private static List<String> linesHoldingPatientData(String output) {
            // The capture of a TRACE run is large, so it is split into lines only when it holds a value at all.
            if (PATIENT_DATA.stream().noneMatch(output::contains)) {
                return List.of();
            }
            return output.lines()
                    .filter(line -> PATIENT_DATA.stream().anyMatch(line::contains))
                    .distinct()
                    .limit(REPORTED_LINES)
                    .toList();
        }

        private static LoggerContext loggerContext() {
            return (LoggerContext) LoggerFactory.getILoggerFactory();
        }
    }

    /**
     * {@link WebServiceConfig}'s listener-path mapping looks this endpoint up by the bean name
     * {@value WebServiceConfig#ADMISSION_ENDPOINT}. Spring WS does not alias {@code @Endpoint}'s value to
     * {@code @Component}, so the name is an explicit {@code @Component} name, never the deprecated convention-based
     * reading of the stereotype's value.
     */
    @Test
    void beanNameIsTheExplicitComponentName() {
        Class<AdmissionServiceEndpoint> type = AdmissionServiceEndpoint.class;

        Endpoint endpoint = AnnotationUtils.findAnnotation(type, Endpoint.class);
        assertThat(endpoint).as("@Endpoint on %s", type.getSimpleName()).isNotNull();
        assertThat(endpoint.value()).as("@Endpoint value").isEmpty();
        Component component = type.getDeclaredAnnotation(Component.class);
        assertThat(component).as("@Component directly present on %s", type.getSimpleName()).isNotNull();
        assertThat(component.value()).as("@Component value").isEqualTo(WebServiceConfig.ADMISSION_ENDPOINT);
        assertThat(new AnnotationBeanNameGenerator().generateBeanName(new AnnotatedGenericBeanDefinition(type),
                new SimpleBeanDefinitionRegistry())).as("generated bean name")
                .isEqualTo(WebServiceConfig.ADMISSION_ENDPOINT);
    }

    private Response postXml(String path, byte[] body) {
        return send("POST", path, body);
    }

    /** A request with no body and no headers, or a body sent as {@value #XML_CONTENT_TYPE}. */
    private Response send(String method, String target, byte[] body) {
        if (body == null) {
            return http.send(method, target, Map.of(), null);
        }
        return http.send(method, target, Map.of("Content-Type", XML_CONTENT_TYPE), body);
    }

    /** Checks the CXF-style success envelope and returns the single {@code soap:Body} child. */
    private static Element assertSoapResponse(Response response) {
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        assertThat(response.reason()).as("%s", response).isEqualTo("OK");
        assertThat(contentType(response)).startsWith("text/xml");
        assertThat(response.bodyAsString()).as("body of %s", response).startsWith(ENVELOPE_START)
                .endsWith(ENVELOPE_END);

        Document document = parse(response.body());
        Element body = soapBody(document);
        List<Element> payload = children(body);
        assertThat(payload).as("soap:Body children of %s", response).hasSize(1);
        return payload.get(0);
    }

    /** Checks the HTTP 500 {@code soap:Client} fault the listener boundary answers an unreadable request with. */
    private static void assertClientFault(Response response) {
        assertThat(response.status()).as("%s", response).isEqualTo(500);
        assertThat(response.reason()).as("%s", response).isEqualTo("Internal Server Error");
        assertThat(contentType(response)).startsWith("text/xml");
        assertThat(response.bodyAsString()).as("body of %s", response).startsWith(ENVELOPE_START);

        Document document = parse(response.body());
        List<Element> faults = children(soapBody(document));
        assertThat(faults).as("soap:Body children of %s", response).hasSize(1);
        assertQName(faults.get(0), SOAP_NS, "soap", "Fault");

        JAXPXPathEngine xpath = new JAXPXPathEngine();
        xpath.setNamespaceContext(Map.of("soap", SOAP_NS));
        Source source = Input.fromDocument(document).build();
        assertThat(xpath.evaluate("/soap:Envelope/soap:Body/soap:Fault/faultcode", source))
                .as("faultcode of %s", response).isEqualTo("soap:Client");
        assertThat(xpath.evaluate("/soap:Envelope/soap:Body/soap:Fault/faultstring", source))
                .as("faultstring of %s", response).isNotBlank();
    }

    /** Checks the listener's no-listener answer, body compared byte for byte. */
    private static void assertNoListener(Response response, String path) {
        assertThat(response.status()).as("%s", response).isEqualTo(404);
        assertThat(response.reason()).as("%s", response).isEqualTo("Not Found");
        assertThat(contentType(response)).isEqualTo("text/plain");
        assertThat(response.body()).as("body bytes of %s", response)
                .isEqualTo(("No listener for endpoint: " + path).getBytes(UTF_8));
    }

    /** The {@code soap:Envelope} root, its first child {@code soap:Body}, and no {@code soap:Header} anywhere. */
    private static Element soapBody(Document document) {
        Element envelope = document.getDocumentElement();
        assertQName(envelope, SOAP_NS, "soap", "Envelope");
        List<Element> envelopeChildren = children(envelope);
        assertThat(envelopeChildren).as("soap:Envelope children").isNotEmpty();
        Element body = envelopeChildren.get(0);
        assertQName(body, SOAP_NS, "soap", "Body");
        assertThat(document.getElementsByTagNameNS(SOAP_NS, "Header").getLength()).as("soap:Header elements")
                .isZero();
        return body;
    }

    private static void assertLeaf(Element element, String namespaceOrNull, String prefixOrNull, String localName,
            String text) {
        assertQName(element, namespaceOrNull, prefixOrNull, localName);
        assertThat(children(element)).as("child elements of %s", localName).isEmpty();
        assertThat(element.getTextContent()).as("text of %s", localName).isEqualTo(text);
    }

    /** Namespace URI ({@code null} for none), prefix when given, and local name of {@code element}. */
    private static void assertQName(Element element, String namespaceOrNull, String prefixOrNull, String localName) {
        assertThat(element.getLocalName()).as("local name of <%s>", element.getNodeName()).isEqualTo(localName);
        assertThat(element.getNamespaceURI()).as("namespace of <%s>", element.getNodeName())
                .isEqualTo(namespaceOrNull);
        if (prefixOrNull != null) {
            assertThat(element.getPrefix()).as("prefix of <%s>", element.getNodeName()).isEqualTo(prefixOrNull);
        }
    }

    /** Element children only; the payload keeps the DataWeave writer's tab indentation as text nodes. */
    private static List<Element> children(Element parent) {
        List<Element> elements = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                elements.add(element);
            }
        }
        return elements;
    }

    /** Every {@code schemaLocation} of an {@code xsd:import}, {@code xsd:include} or {@code xsd:redefine}. */
    private static List<String> schemaLocations(Document document) {
        List<String> locations = new ArrayList<>();
        NodeList elements = document.getElementsByTagNameNS(XSD_NS, "*");
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            String name = element.getLocalName();
            if (("import".equals(name) || "include".equals(name) || "redefine".equals(name))
                    && element.hasAttribute("schemaLocation")) {
                locations.add(element.getAttribute("schemaLocation"));
            }
        }
        return locations;
    }

    private static String contentType(Response response) {
        String contentType = response.header("Content-Type");
        assertThat(contentType).as("Content-Type of %s", response).isNotNull();
        return contentType;
    }

    /** Namespace-aware parse with DOCTYPE declarations and external entities disabled. */
    private static Document parse(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            // Fatal errors throw without the default handler's report on stderr.
            builder.setErrorHandler(new DefaultHandler());
            return builder.parse(new ByteArrayInputStream(xml));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new AssertionError("Not well-formed XML: " + new String(xml, UTF_8), e);
        }
    }

    /** The bytes of {@code src/test/resources/message.xml}: the {@code admitSubject} envelope the original posts. */
    private static byte[] message() {
        try (InputStream in = AdmissionServiceEndpointTest.class.getResourceAsStream("/message.xml")) {
            assertThat(in).as("classpath resource /message.xml").isNotNull();
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read /message.xml", e);
        }
    }
}
