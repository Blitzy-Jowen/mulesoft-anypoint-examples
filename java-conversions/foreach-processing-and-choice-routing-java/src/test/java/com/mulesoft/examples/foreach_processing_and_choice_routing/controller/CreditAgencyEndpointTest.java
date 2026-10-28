package com.mulesoft.examples.foreach_processing_and_choice_routing.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.wsdl.Binding;
import javax.wsdl.Definition;
import javax.wsdl.Port;
import javax.wsdl.PortType;
import javax.wsdl.Service;
import javax.wsdl.extensions.soap.SOAPAddress;
import javax.wsdl.factory.WSDLFactory;
import javax.wsdl.xml.WSDLReader;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;

import com.mulesoft.examples.foreach_processing_and_choice_routing.client.CreditAgencyClient;
import com.mulesoft.examples.foreach_processing_and_choice_routing.config.SoapFaultConfig;
import com.mulesoft.examples.foreach_processing_and_choice_routing.config.WebServiceConfig;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.CreditProfile;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.Customer;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.soap.GetCreditProfile;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.soap.GetCreditProfileResponse;
import com.mulesoft.examples.foreach_processing_and_choice_routing.support.HttpTestClient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/**
 * Endpoint test of the mock credit agency, the original flow {@code TheCreditAgencyService}: a listener on 18080 with
 * {@code basePath="/mule/TheCreditAgencyService"} and {@code path="/*"} accepting every method, in front of a
 * {@code cxf:jaxws-service} over a singleton {@code DefaultCreditAgency}.
 *
 * <p>The whole application runs with the real {@link CreditAgencyEndpoint} and credit agency, and every assertion is
 * made on the wire through the project's ST-1 client, so the CXF behaviour the conversion reproduces is checked as a
 * caller saw it: the document/literal-wrapped {@code getCreditProfile} binding with {@code arg0}/{@code return}, the
 * compact {@code soap:}/{@code ns2:} response envelope with no XML declaration and no empty Header, the published
 * WSDL with its {@code soap:address} pointing at the request URL, the listener's no-listener 404 for another
 * listener's path, and the {@code soap:Client}/{@code soap:Server} faults for unreadable, unknown and failing
 * requests.
 *
 * <p>The credit agency and the five banks listen on fixed ports, because the endpoints and the port routing tell
 * listeners apart by their local port; each is a free port chosen for this class, never an original port, so the
 * test runs beside the cached contexts of other classes and beside a running application.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CreditAgencyEndpointTest {

    private static final String SOAP_11_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    private static final String CREDIT_AGENCY_NS = CreditAgencyEndpoint.NAMESPACE_URI;

    private static final String BANK_NS = BankEndpoint.NAMESPACE_URI;

    private static final String LISTENER_PATH = WebServiceConfig.CREDIT_AGENCY_PATH;

    /** The path the loan broker's outbound request config calls: base path plus the listener's {@code path="/*"}. */
    private static final String CLIENT_PATH = LISTENER_PATH + "/*";

    private static final String TEXT_XML_UTF8 = "text/xml;charset=UTF-8";

    private static final int MIN_CREDIT_SCORE = 300;
    private static final int MAX_CREDIT_SCORE = 899;
    private static final int MIN_CREDIT_HISTORY = 1;
    private static final int MAX_CREDIT_HISTORY = 19;

    private static final int BANK_COUNT = WebServiceConfig.BANK_COUNT;

    /** The original listener ports, which a running application or another clone may hold. */
    private static final Set<Integer> ORIGINAL_PORTS = Set.of(11081, 18080, 10080, 20080, 30080, 40080, 50080);

    private static final int CREDIT_AGENCY_PORT;

    private static final int[] BANK_PORTS = new int[BANK_COUNT];

    static {
        Set<Integer> chosen = new LinkedHashSet<>();
        while (chosen.size() < BANK_COUNT + 1) {
            int candidate = TestSocketUtils.findAvailableTcpPort();
            if (!ORIGINAL_PORTS.contains(candidate)) {
                chosen.add(candidate);
            }
        }
        Integer[] ports = chosen.toArray(new Integer[0]);
        CREDIT_AGENCY_PORT = ports[0];
        for (int bank = 0; bank < BANK_COUNT; bank++) {
            BANK_PORTS[bank] = ports[bank + 1];
        }
    }

    /**
     * The whole CXF response a successful call must produce, with only the two random numbers left open:
     * {@code creditHistory} (group 1) before {@code creditScore} (group 2), JAXB's name order for the unannotated
     * {@code CreditProfile}.
     */
    private static final Pattern CXF_RESPONSE = Pattern.compile(
            Pattern.quote("<soap:Envelope xmlns:soap=\"" + SOAP_11_NS + "\"><soap:Body>"
                    + "<ns2:getCreditProfileResponse xmlns:ns2=\"" + CREDIT_AGENCY_NS + "\"><return><creditHistory>")
                    + "(\\d+)"
                    + Pattern.quote("</creditHistory><creditScore>")
                    + "(\\d+)"
                    + Pattern.quote("</creditScore></return></ns2:getCreditProfileResponse></soap:Body>"
                    + "</soap:Envelope>"));

    @Autowired
    private CreditAgencyClient creditAgencyClient;

    @Autowired
    private WebServiceTemplate webServiceTemplate;

    @DynamicPropertySource
    static void listenerPorts(DynamicPropertyRegistry registry) {
        registry.add("ports.credit-agency", () -> CREDIT_AGENCY_PORT);
        for (int bank = 0; bank < BANK_COUNT; bank++) {
            int port = BANK_PORTS[bank];
            registry.add("ports.bank" + (bank + 1), () -> port);
        }
        registry.add("loanbroker.outbound-host", () -> "localhost");
    }

    @Test
    void creditAgencyClientReceivesCreditProfileWithinOriginalRanges() {
        CreditProfile profile = creditAgencyClient.getCreditProfile(new Customer("Muley", 1234));

        assertProfileInOriginalRanges(profile);
    }

    @Test
    void productionTemplateReachesTheListenerBasePath() {
        Object reply = webServiceTemplate.marshalSendAndReceive(
                "http://localhost:" + CREDIT_AGENCY_PORT + LISTENER_PATH,
                new GetCreditProfile(new Customer("Muley", 1234)));

        assertThat(reply).isInstanceOf(GetCreditProfileResponse.class);
        assertProfileInOriginalRanges(((GetCreditProfileResponse) reply).getReturn());
    }

    @ParameterizedTest(name = "POST {0}")
    @ValueSource(strings = {LISTENER_PATH, CLIENT_PATH})
    void getCreditProfileIsAnsweredWithTheExactCxfEnvelope(String path) {
        HttpTestClient.Response response = post(path, envelope("<cre:getCreditProfile><arg0><name>Muley</name>"
                + "<ssn>1234</ssn></arg0></cre:getCreditProfile>"));

        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        assertThat(response.header("Content-Type")).startsWith("text/xml");
        assertNoRequestHeaders(response);
        String body = response.bodyAsString();
        assertThat(body).doesNotStartWith("<?xml").doesNotContain("Header");
        Matcher matcher = CXF_RESPONSE.matcher(body);
        assertThat(matcher.matches()).as("CXF response envelope, but was: %s", body).isTrue();
        assertThat(Integer.parseInt(matcher.group(1))).isBetween(MIN_CREDIT_HISTORY, MAX_CREDIT_HISTORY);
        assertThat(Integer.parseInt(matcher.group(2))).isBetween(MIN_CREDIT_SCORE, MAX_CREDIT_SCORE);
    }

    @Test
    void wsdlQueryPublishesTheCreditAgencyContractAtTheRequestUrl() throws Exception {
        HttpTestClient.Response response = client().get(LISTENER_PATH + "?wsdl");

        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        assertThat(normalizedContentType(response)).isEqualTo(normalized(TEXT_XML_UTF8));

        WSDLReader reader = WSDLFactory.newInstance().newWSDLReader();
        reader.setFeature("javax.wsdl.verbose", false);
        reader.setFeature("javax.wsdl.importDocuments", false);
        Definition definition = reader.readWSDL(null, new InputSource(new ByteArrayInputStream(response.body())));

        PortType portType = definition.getPortType(new QName(CREDIT_AGENCY_NS, "CreditAgencyService"));
        assertThat(portType).isNotNull();
        assertThat(portType.getOperation("getCreditProfile", null, null)).isNotNull();
        Binding binding = definition.getBinding(new QName(CREDIT_AGENCY_NS, "DefaultCreditAgencyServiceSoapBinding"));
        assertThat(binding).isNotNull();
        assertThat(binding.getPortType().getQName()).isEqualTo(portType.getQName());
        Service service = definition.getService(new QName(CREDIT_AGENCY_NS, "DefaultCreditAgencyService"));
        assertThat(service).isNotNull();
        Port port = service.getPort("DefaultCreditAgencyPort");
        assertThat(port).isNotNull();
        assertThat(port.getBinding().getQName()).isEqualTo(binding.getQName());

        List<?> extensions = port.getExtensibilityElements();
        List<SOAPAddress> addresses = extensions.stream()
                .filter(SOAPAddress.class::isInstance)
                .map(SOAPAddress.class::cast)
                .toList();
        assertThat(addresses).hasSize(1);
        // ST-1 sends "Host: localhost:<port>", from which the request URL is built.
        assertThat(addresses.get(0).getLocationURI())
                .isEqualTo("http://localhost:" + CREDIT_AGENCY_PORT + LISTENER_PATH);
    }

    @Test
    void bankPathOnTheCreditAgencyPortIsAnsweredWithTheNoListener404() {
        String bankPath = WebServiceConfig.BANK_PATH_PREFIX + 1;

        HttpTestClient.Response response = post(bankPath, envelope(bankQuoteRequest()));

        assertThat(response.status()).as(response.toString()).isEqualTo(404);
        assertThat(response.header("Content-Type")).isEqualTo("text/plain");
        assertThat(response.bodyAsString()).isEqualTo("No listener for endpoint: " + bankPath);
    }

    static Stream<Arguments> unreadableRequests() {
        return Stream.of(
                Arguments.of("malformed XML", "POST", "<soapenv:Envelope"),
                Arguments.of("empty body", "POST", ""),
                Arguments.of("GET without a metadata query", "GET", null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unreadableRequests")
    void requestThatIsNotAnEnvelopeIsAnsweredWithClientFault(String description, String method, String body)
            throws Exception {
        HttpTestClient.Response response = "GET".equals(method)
                ? client().get(LISTENER_PATH)
                : post(LISTENER_PATH, body);

        assertThat(response.status()).as(response.toString()).isEqualTo(500);
        Element envelope = parse(response).getDocumentElement();
        assertThat(envelope.getNamespaceURI()).isEqualTo(SOAP_11_NS);
        assertThat(envelope.getLocalName()).isEqualTo("Envelope");
        assertThat(envelope.getPrefix()).isEqualTo("soap");
        assertThat(faultcode(response)).isEqualTo("soap:Client");
        assertNoRequestHeaders(response);
    }

    static Stream<Arguments> unknownOperations() {
        return Stream.of(
                Arguments.of(bankQuoteRequest(), BankEndpoint.PAYLOAD_ROOT),
                Arguments.of("<unk:doSomething xmlns:unk=\"urn:example:unknown\"><arg0>x</arg0></unk:doSomething>",
                        new QName("urn:example:unknown", "doSomething")));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("unknownOperations")
    void operationTheCreditAgencyDoesNotHaveIsAnsweredWithCxfUnknownPartFault(String payload, QName payloadRoot)
            throws Exception {
        HttpTestClient.Response response = post(LISTENER_PATH, envelope(payload));

        assertThat(response.status()).as(response.toString()).isEqualTo(500);
        assertThat(faultcode(response)).isEqualTo("soap:Client");
        assertThat(faultstring(response)).isEqualTo(
                "Message part " + payloadRoot + " was not recognized.  (Does it exist in service WSDL?)");
    }

    @Test
    void requestWithoutCustomerIsAnsweredWithServerFault() throws Exception {
        HttpTestClient.Response response = post(LISTENER_PATH, envelope("<cre:getCreditProfile/>"));

        assertThat(response.status()).as(response.toString()).isEqualTo(500);
        assertThat(faultcode(response)).isEqualTo("soap:Server");
        // The agency fails with a NullPointerException the JVM raised; its computed message names internal classes,
        // so the fault carries CXF's text for an exception without a message, as the original's Java 8 runtime did.
        assertThat(faultstring(response)).isEqualTo(SoapFaultConfig.DEFAULT_FAULT_STRING);
        assertNoRequestHeaders(response);
    }

    private static void assertProfileInOriginalRanges(CreditProfile profile) {
        assertThat(profile).isNotNull();
        assertThat(profile.getCreditScore()).isBetween(MIN_CREDIT_SCORE, MAX_CREDIT_SCORE);
        assertThat(profile.getCreditHistory()).isBetween(MIN_CREDIT_HISTORY, MAX_CREDIT_HISTORY);
    }

    private static HttpTestClient client() {
        return HttpTestClient.localhost(CREDIT_AGENCY_PORT);
    }

    /** A SOAP 1.1 POST as the CXF client sent it; ST-1 adds {@code Content-Length} for every non-null body. */
    private static HttpTestClient.Response post(String path, String body) {
        return client().post(path, TEXT_XML_UTF8, body, Map.of("SOAPAction", "\"\""));
    }

    /** A soapUI-style request envelope with an empty Header and the credit agency and bank prefixes declared. */
    private static String envelope(String bodyContent) {
        return "<soapenv:Envelope xmlns:soapenv=\"" + SOAP_11_NS + "\" xmlns:cre=\"" + CREDIT_AGENCY_NS
                + "\" xmlns:bank=\"" + BANK_NS + "\"><soapenv:Header/><soapenv:Body>" + bodyContent
                + "</soapenv:Body></soapenv:Envelope>";
    }

    /** A well-formed {@code getLoanQuote} request, children in JAXB's name order as the bank binding reads them. */
    private static String bankQuoteRequest() {
        return "<bank:getLoanQuote><arg0>"
                + "<creditProfile><creditHistory>10</creditHistory><creditScore>700</creditScore></creditProfile>"
                + "<customerRequest><customer><name>Muley</name><ssn>1234</ssn></customer>"
                + "<loanAmount>20000.0</loanAmount><loanDuration>48</loanDuration></customerRequest>"
                + "</arg0></bank:getLoanQuote>";
    }

    private static String normalizedContentType(HttpTestClient.Response response) {
        String contentType = response.header("Content-Type");
        assertThat(contentType).as("Content-Type of %s", response).isNotNull();
        return normalized(contentType);
    }

    private static String normalized(String contentType) {
        return contentType.replace(" ", "").toLowerCase(Locale.ROOT);
    }

    /** SAAJ keeps {@code Accept} and {@code SOAPAction} in every message's MIME headers; CXF sent neither back. */
    private static void assertNoRequestHeaders(HttpTestClient.Response response) {
        assertThat(response.header("Accept")).as("Accept of %s", response).isNull();
        assertThat(response.header("SOAPAction")).as("SOAPAction of %s", response).isNull();
    }

    private static String faultcode(HttpTestClient.Response response) throws Exception {
        return faultChildText(response, "faultcode");
    }

    private static String faultstring(HttpTestClient.Response response) throws Exception {
        return faultChildText(response, "faultstring");
    }

    /** Text of the unqualified child {@code name} of the response's {@code soap:Body/soap:Fault}. */
    private static String faultChildText(HttpTestClient.Response response, String name) throws Exception {
        Element envelope = parse(response).getDocumentElement();
        Element body = soapChild(envelope, "Body", response);
        Element fault = soapChild(body, "Fault", response);
        for (Node child = fault.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && element.getNamespaceURI() == null
                    && name.equals(element.getLocalName())) {
                return element.getTextContent();
            }
        }
        throw new AssertionError("No " + name + " in the fault of " + response);
    }

    private static Element soapChild(Element parent, String localName, HttpTestClient.Response response) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && SOAP_11_NS.equals(element.getNamespaceURI())
                    && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        throw new AssertionError("No soap:" + localName + " in " + response);
    }

    private static Document parse(HttpTestClient.Response response) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(response.body()));
    }
}
