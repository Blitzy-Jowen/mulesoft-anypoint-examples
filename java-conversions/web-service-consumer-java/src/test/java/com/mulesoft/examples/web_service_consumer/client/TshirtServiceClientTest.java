/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.web_service_consumer.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ws.client.WebServiceFaultException;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.client.WebServiceTransportException;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.client.SoapFaultClientException;
import org.springframework.ws.transport.WebServiceConnection;
import org.springframework.ws.transport.WebServiceMessageSender;
import org.springframework.ws.transport.http.HttpUrlConnection;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.Diff;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.mulesoft.examples.web_service_consumer.config.TshirtServiceProperties;

/**
 * Contract of {@link TshirtServiceClient} against a WireMock stand-in for {@code http://tshirt-service.cloudhub.io},
 * the original {@code Web_Service_Consumer} with its {@code OrderTshirt} and {@code ListInventory} operations
 * ({@code tshirt-service-consumer.xml:3,17-25,38}).
 *
 * <p>Each operation sends exactly one SOAP 1.1 POST with the WSDL's {@code soapAction}; {@code OrderTshirt} carries
 * the {@code ns0:AuthenticationHeader} with the configured key and refuses to send without one, while
 * {@code ListInventory} carries no header and needs no key. The response Body child comes back as its own document,
 * no response or an empty Body as {@code null}; a SOAP fault, an HTTP error without a fault, a read timeout and a
 * refused connection surface as the Spring WS exceptions, never retried, and a fault without a readable fault string
 * as a {@code WebServiceFaultException} with a fixed message. Requests go out byte for byte as SAAJ
 * writes them, except that characters outside the BMP are sent as UTF-8 bytes rather than character references.
 * A connect or read timeout below 1 ms or above {@link Integer#MAX_VALUE} ms fails construction naming its key, and
 * an unset one reaches the connection as 10 s.
 *
 * <p>Plain JUnit 5: the client is built directly from {@link TshirtServiceProperties}; no Spring context is started.
 * Every document the test reads is parsed with DOCTYPE declarations and external entities disabled.
 */
class TshirtServiceClientTest {

    private static final String SERVICE_PATH = "/";

    /** The original demo key, hard-coded at {@code tshirt-service-consumer.xml:22}; test-only. */
    private static final String API_KEY = "abc12345";

    private static final String MISSING_API_KEY = "T-shirt service API key (tshirt.api-key) is not configured";

    private static final String INCOMPLETE_FAULT =
            "The T-shirt service returned a SOAP fault without a readable fault string";

    private static final String SOAP_ENVELOPE_NAMESPACE = "http://schemas.xmlsoap.org/soap/envelope/";

    private static final String WSDL_NAMESPACE = "http://schemas.xmlsoap.org/wsdl/";

    private static final String WSDL_SOAP_NAMESPACE = "http://schemas.xmlsoap.org/wsdl/soap/";

    private static final String TEXT_XML = "text/xml; charset=utf-8";

    /** Mule's default response timeout, as {@code tshirt.connect-timeout} and {@code tshirt.read-timeout} default. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /** The read timeout of the read-timeout test only. */
    private static final Duration SHORT_READ_TIMEOUT = Duration.ofMillis(1000);

    /** The body of {@code message.json} as the request mapper writes it. */
    private static final String ORDER_REQUEST = """
            <ns0:OrderTshirt xmlns:ns0="http://mulesoft.org/tshirt-service">
            \t<email>customerID@gmail.com</email>
            \t<address1>Corrientes 316</address1>
            \t<address2>EP</address2>
            \t<city>Buenos Aires</city>
            \t<country>Argentina</country>
            \t<name>MuleSoft Argentina</name>
            \t<postalCode>C1043AAQ</postalCode>
            \t<size>L</size>
            \t<stateOrProvince>CABA</stateOrProvince>
            </ns0:OrderTshirt>""";

    private static final String EXPECTED_ORDER_ENVELOPE = """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
              <soapenv:Header>
                <ts:AuthenticationHeader xmlns:ts="http://mulesoft.org/tshirt-service">
                  <apiKey>abc12345</apiKey>
                </ts:AuthenticationHeader>
              </soapenv:Header>
              <soapenv:Body>
                <ts:OrderTshirt xmlns:ts="http://mulesoft.org/tshirt-service">
                  <email>customerID@gmail.com</email>
                  <address1>Corrientes 316</address1>
                  <address2>EP</address2>
                  <city>Buenos Aires</city>
                  <country>Argentina</country>
                  <name>MuleSoft Argentina</name>
                  <postalCode>C1043AAQ</postalCode>
                  <size>L</size>
                  <stateOrProvince>CABA</stateOrProvince>
                </ts:OrderTshirt>
              </soapenv:Body>
            </soapenv:Envelope>""";

    private static final String EXPECTED_LIST_INVENTORY_ENVELOPE = """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
              <soapenv:Header/>
              <soapenv:Body>
                <ts:ListInventory xmlns:ts="http://mulesoft.org/tshirt-service"/>
              </soapenv:Body>
            </soapenv:Envelope>""";

    /** As the service answers {@code OrderTshirt}: the {@code APIUsageInformation} header and an ns2 body. */
    private static final String ORDER_RESPONSE = """
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Header>
                <ns2:APIUsageInformation xmlns:ns2="http://mulesoft.org/tshirt-service">
                  <apiCallsRemaining>99</apiCallsRemaining>
                </ns2:APIUsageInformation>
              </soap:Header>
              <soap:Body>
                <ns2:OrderTshirtResponse xmlns:ns2="http://mulesoft.org/tshirt-service">
                  <orderId>1</orderId>
                </ns2:OrderTshirtResponse>
              </soap:Body>
            </soap:Envelope>""";

    private static final String EXPECTED_ORDER_PAYLOAD = """
            <ts:OrderTshirtResponse xmlns:ts="http://mulesoft.org/tshirt-service">
              <orderId>1</orderId>
            </ts:OrderTshirtResponse>""";

    /** As the service answers {@code ListInventory}: the example's {@code sample_data/ListInventoryResponse.xml}. */
    private static final String INVENTORY_RESPONSE = """
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body>
                <ns2:ListInventoryResponse xmlns:ns2="http://mulesoft.org/tshirt-service">
                  <inventory>
                    <productCode>4102</productCode>
                    <size>L</size>
                    <description>Prueba</description>
                    <count>2</count>
                  </inventory>
                  <inventory>
                    <productCode>1412</productCode>
                    <size>L</size>
                    <description>Foo</description>
                    <count>9</count>
                  </inventory>
                  <inventory>
                    <productCode>5656</productCode>
                    <size>S</size>
                    <description>Bar</description>
                    <count>2</count>
                  </inventory>
                  <inventory>
                    <productCode>5657</productCode>
                    <size>M</size>
                    <description>Prueba2</description>
                    <count>3</count>
                  </inventory>
                  <inventory>
                    <productCode>1411</productCode>
                    <size>M</size>
                    <description>Awesome Tshirt</description>
                    <count>5</count>
                  </inventory>
                </ns2:ListInventoryResponse>
              </soap:Body>
            </soap:Envelope>""";

    private static final String EXPECTED_INVENTORY_PAYLOAD = """
            <ts:ListInventoryResponse xmlns:ts="http://mulesoft.org/tshirt-service">
              <inventory>
                <productCode>4102</productCode>
                <size>L</size>
                <description>Prueba</description>
                <count>2</count>
              </inventory>
              <inventory>
                <productCode>1412</productCode>
                <size>L</size>
                <description>Foo</description>
                <count>9</count>
              </inventory>
              <inventory>
                <productCode>5656</productCode>
                <size>S</size>
                <description>Bar</description>
                <count>2</count>
              </inventory>
              <inventory>
                <productCode>5657</productCode>
                <size>M</size>
                <description>Prueba2</description>
                <count>3</count>
              </inventory>
              <inventory>
                <productCode>1411</productCode>
                <size>M</size>
                <description>Awesome Tshirt</description>
                <count>5</count>
              </inventory>
            </ts:ListInventoryResponse>""";

    /** The WSDL's {@code TshirtFault}, as a SOAP 1.1 client fault. */
    private static final String FAULT_RESPONSE = """
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body>
                <soap:Fault>
                  <faultcode>soap:Client</faultcode>
                  <faultstring>Invalid API key</faultstring>
                  <detail>
                    <ns2:TshirtFault xmlns:ns2="http://mulesoft.org/tshirt-service">
                      <errorMessage>Invalid API key</errorMessage>
                    </ns2:TshirtFault>
                  </detail>
                </soap:Fault>
              </soap:Body>
            </soap:Envelope>""";

    private static final String EMPTY_BODY_RESPONSE = """
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body/>
            </soap:Envelope>""";

    /** The SAAJ wire form of an {@code OrderTshirt} envelope up to its Body content, with no XML declaration. */
    private static final String ORDER_ENVELOPE_START = "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"" + SOAP_ENVELOPE_NAMESPACE
            + "\"><SOAP-ENV:Header><ns0:AuthenticationHeader xmlns:ns0=\"" + TshirtServiceClient.NAMESPACE
            + "\"><apiKey>" + API_KEY + "</apiKey></ns0:AuthenticationHeader></SOAP-ENV:Header><SOAP-ENV:Body>";

    /** The SAAJ wire form of a {@code ListInventory} envelope up to its Body content: empty Header, no declaration. */
    private static final String LIST_INVENTORY_ENVELOPE_START = "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\""
            + SOAP_ENVELOPE_NAMESPACE + "\"><SOAP-ENV:Header/><SOAP-ENV:Body>";

    private static final String ENVELOPE_END = "</SOAP-ENV:Body></SOAP-ENV:Envelope>";

    private static final String NS0 = "xmlns:ns0=\"" + TshirtServiceClient.NAMESPACE + "\"";

    /** U+1F600, U+20000 and U+10FFFF, characters outside the BMP that the JDK serializer writes as references. */
    private static final String SUPPLEMENTARY = Character.toString(0x1F600) + " " + Character.toString(0x20000) + " "
            + Character.toString(0x10FFFF);

    private static final String GRINNING_FACE = Character.toString(0x1F600);

    // gzip left enabled: the client must not advertise it, or an empty 200 would arrive compressed.
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    /** The two operations, each called with the request the original flow sent. */
    enum Operation {

        ORDER_TSHIRT(TshirtServiceClient.ORDER_TSHIRT_ACTION, ORDER_RESPONSE, EXPECTED_ORDER_PAYLOAD) {
            @Override
            Document call(TshirtServiceClient client) {
                return client.orderTshirt(orderRequest());
            }
        },

        LIST_INVENTORY(TshirtServiceClient.LIST_INVENTORY_ACTION, INVENTORY_RESPONSE, EXPECTED_INVENTORY_PAYLOAD) {
            @Override
            Document call(TshirtServiceClient client) {
                return client.listInventory(null);
            }
        };

        private final String soapAction;

        private final String response;

        private final String expectedPayload;

        Operation(String soapAction, String response, String expectedPayload) {
            this.soapAction = soapAction;
            this.response = response;
            this.expectedPayload = expectedPayload;
        }

        abstract Document call(TshirtServiceClient client);
    }

    private static TshirtServiceClient newClient() {
        return newClient(wm.baseUrl(), API_KEY);
    }

    private static TshirtServiceClient newClient(String serviceAddress, String apiKey) {
        return newClient(serviceAddress, apiKey, DEFAULT_TIMEOUT);
    }

    private static TshirtServiceClient newClient(String serviceAddress, String apiKey, Duration readTimeout) {
        TshirtServiceProperties properties = new TshirtServiceProperties();
        properties.setServiceAddress(serviceAddress);
        properties.setApiKey(apiKey);
        properties.setConnectTimeout(DEFAULT_TIMEOUT);
        properties.setReadTimeout(readTimeout);
        return new TshirtServiceClient(properties);
    }

    /**
     * Settings for the WireMock service with the demo key and the given timeouts.
     *
     * @param connectTimeout {@code tshirt.connect-timeout}, {@code null} for unset
     * @param readTimeout {@code tshirt.read-timeout}, {@code null} for unset
     * @return the settings
     */
    private static TshirtServiceProperties timeoutProperties(Duration connectTimeout, Duration readTimeout) {
        TshirtServiceProperties properties = new TshirtServiceProperties();
        properties.setServiceAddress(wm.baseUrl());
        properties.setApiKey(API_KEY);
        properties.setConnectTimeout(connectTimeout);
        properties.setReadTimeout(readTimeout);
        return properties;
    }

    /**
     * Asserts the timeouts, in milliseconds, of the connection the client's one message sender prepares for the
     * service. The sender opens and prepares that connection without connecting it, with the timeouts every request
     * is sent with; the template is read from the client's private field.
     *
     * @param client the client under test
     * @param connectMillis the expected connect timeout, in milliseconds
     * @param readMillis the expected read timeout, in milliseconds
     * @throws IOException when the sender cannot open the connection
     */
    private static void assertConnectionTimeouts(TshirtServiceClient client, int connectMillis, int readMillis)
            throws IOException {
        WebServiceTemplate template = (WebServiceTemplate) ReflectionTestUtils.getField(client, "template");
        assertThat(template).isNotNull();
        assertThat(template.getMessageSenders()).hasSize(1);
        WebServiceMessageSender sender = template.getMessageSenders()[0];
        try (WebServiceConnection connection = sender.createConnection(URI.create(wm.baseUrl()))) {
            HttpURLConnection prepared = ((HttpUrlConnection) connection).getConnection();
            assertThat(prepared.getConnectTimeout()).as("connect timeout, ms").isEqualTo(connectMillis);
            assertThat(prepared.getReadTimeout()).as("read timeout, ms").isEqualTo(readMillis);
        }
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    /** A SOAP 1.1 envelope whose Body holds {@code fault} alone, without whitespace between the elements. */
    private static String compactFaultEnvelope(String fault) {
        return "<soap:Envelope xmlns:soap=\"" + SOAP_ENVELOPE_NAMESPACE + "\"><soap:Body>" + fault
                + "</soap:Body></soap:Envelope>";
    }

    private static ResponseDefinitionBuilder soapResponse(int status, String envelope) {
        return aResponse().withStatus(status).withHeader("Content-Type", TEXT_XML).withBody(envelope);
    }

    private static void stubService(ResponseDefinitionBuilder response) {
        wm.stubFor(post(urlEqualTo(SERVICE_PATH)).willReturn(response));
    }

    private static LoggedRequest singlePost() {
        List<LoggedRequest> requests = wm.findAll(postRequestedFor(urlEqualTo(SERVICE_PATH)));
        assertThat(requests).hasSize(1);
        return requests.get(0);
    }

    private static DocumentBuilderFactory secureDocumentBuilderFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        }
        catch (ParserConfigurationException ex) {
            throw new IllegalStateException("The XML parser does not support secure processing", ex);
        }
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private static Document parse(byte[] xml) {
        try {
            return secureDocumentBuilderFactory().newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        }
        catch (ParserConfigurationException | SAXException | IOException ex) {
            throw new IllegalStateException("Cannot parse " + new String(xml, UTF_8), ex);
        }
    }

    private static Document orderRequest() {
        return parse(ORDER_REQUEST.getBytes(UTF_8));
    }

    private static byte[] classpathBytes(String name) throws IOException {
        try (InputStream in = TshirtServiceClientTest.class.getResourceAsStream(name)) {
            assertThat(in).as("test classpath resource %s", name).isNotNull();
            return in.readAllBytes();
        }
    }

    private static void assertSimilar(String expected, Input.Builder actual) {
        Diff diff = DiffBuilder.compare(Input.fromString(expected))
                .withTest(actual)
                .withDocumentBuilderFactory(secureDocumentBuilderFactory())
                .ignoreWhitespace()
                .checkForSimilar()
                .build();
        assertThat(diff.hasDifferences()).as(diff.toString()).isFalse();
    }

    private static List<Element> childElements(Node parent) {
        List<Element> elements = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element) {
                elements.add(element);
            }
        }
        return elements;
    }

    private static Element envelopeChild(Document envelope, String localName) {
        NodeList matches = envelope.getElementsByTagNameNS(SOAP_ENVELOPE_NAMESPACE, localName);
        assertThat(matches.getLength()).as("SOAP %s elements", localName).isEqualTo(1);
        return (Element) matches.item(0);
    }

    private static List<Throwable> causeChain(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable cause = failure; cause != null && !chain.contains(cause); cause = cause.getCause()) {
            chain.add(cause);
        }
        return chain;
    }

    /**
     * A socket bound to a free port on every local address that never listens or connects: a connection to its port
     * is refused while it is open, and with SO_REUSEADDR off no other socket can bind that port meanwhile.
     */
    private static Socket reserveRefusingPort() throws IOException {
        Socket socket = new Socket();
        try {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(0));
            return socket;
        }
        catch (IOException | RuntimeException ex) {
            try {
                socket.close();
            }
            catch (IOException closeFailure) {
                ex.addSuppressed(closeFailure);
            }
            throw ex;
        }
    }

    private static Element bindingOperation(Document wsdl, String name) {
        NodeList operations = wsdl.getElementsByTagNameNS(WSDL_NAMESPACE, "operation");
        for (int i = 0; i < operations.getLength(); i++) {
            Element operation = (Element) operations.item(i);
            if (name.equals(operation.getAttribute("name"))
                    && "binding".equals(operation.getParentNode().getLocalName())) {
                return operation;
            }
        }
        throw new AssertionError("tshirt.wsdl binds no operation " + name);
    }

    private static List<String> inputHeaderMessages(Element bindingOperation) {
        List<String> messages = new ArrayList<>();
        Element input = (Element) bindingOperation.getElementsByTagNameNS(WSDL_NAMESPACE, "input").item(0);
        NodeList headers = input.getElementsByTagNameNS(WSDL_SOAP_NAMESPACE, "header");
        for (int i = 0; i < headers.getLength(); i++) {
            messages.add(((Element) headers.item(i)).getAttribute("message"));
        }
        return messages;
    }

    @Test
    void constantsAndDefaultAddressAreTheWsdlsNamespaceSoapActionsAndPortAddress() throws IOException {
        Document wsdl = parse(classpathBytes("/tshirt.wsdl"));
        Element orderTshirt = bindingOperation(wsdl, "OrderTshirt");
        Element listInventory = bindingOperation(wsdl, "ListInventory");
        Element address = (Element) wsdl.getElementsByTagNameNS(WSDL_SOAP_NAMESPACE, "address").item(0);

        assertThat(wsdl.getDocumentElement().getAttribute("targetNamespace"))
                .isEqualTo(TshirtServiceClient.NAMESPACE);
        assertThat(((Element) orderTshirt.getElementsByTagNameNS(WSDL_SOAP_NAMESPACE, "operation").item(0))
                .getAttribute("soapAction")).isEqualTo(TshirtServiceClient.ORDER_TSHIRT_ACTION);
        assertThat(((Element) listInventory.getElementsByTagNameNS(WSDL_SOAP_NAMESPACE, "operation").item(0))
                .getAttribute("soapAction")).isEqualTo(TshirtServiceClient.LIST_INVENTORY_ACTION);
        assertThat(inputHeaderMessages(orderTshirt)).containsExactly("ms:AuthenticationHeader");
        assertThat(inputHeaderMessages(listInventory)).isEmpty();
        assertThat(address.getAttribute("location")).isEqualTo(new TshirtServiceProperties().getServiceAddress());
    }

    @Test
    void propertiesDefaultToMulesTenSecondTimeoutsAndNoKeyAndNeverRenderTheKey() {
        TshirtServiceProperties defaults = new TshirtServiceProperties();

        assertThat(defaults.getConnectTimeout()).isEqualTo(DEFAULT_TIMEOUT);
        assertThat(defaults.getReadTimeout()).isEqualTo(DEFAULT_TIMEOUT);
        assertThat(defaults.getApiKey()).isEmpty();
        assertThat(defaults).hasToString("TshirtServiceProperties{serviceAddress='http://tshirt-service.cloudhub.io',"
                + " apiKey='', connectTimeout=PT10S, readTimeout=PT10S}");

        TshirtServiceProperties configured = new TshirtServiceProperties();
        configured.setApiKey(API_KEY);
        assertThat(configured.toString()).contains("apiKey='****'").doesNotContain(API_KEY);
    }

    /**
     * Timeouts whose whole milliseconds are outside 1 to {@link Integer#MAX_VALUE}, each given to one setting while
     * the other keeps the default.
     *
     * @return the key, the rejected value, and the configured connect and read timeouts
     */
    static Stream<Arguments> unusableTimeouts() {
        List<Duration> unusable = List.of(Duration.ZERO, Duration.ofNanos(1), Duration.ofNanos(500_000),
                Duration.ofNanos(999_999), Duration.ofMillis(-1), Duration.ofMillis(Integer.MAX_VALUE + 1L),
                Duration.ofSeconds(Long.MAX_VALUE));
        List<Arguments> cases = new ArrayList<>();
        for (Duration timeout : unusable) {
            cases.add(Arguments.of("tshirt.connect-timeout", timeout, timeout, DEFAULT_TIMEOUT));
            cases.add(Arguments.of("tshirt.read-timeout", timeout, DEFAULT_TIMEOUT, timeout));
        }
        return cases.stream();
    }

    // HttpURLConnection waits forever on 0 ms, which zero and sub-millisecond values truncate to.
    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("unusableTimeouts")
    void timeoutOutsideOneMillisecondToIntMaxFailsConstructionNamingItsKey(String key, Duration rejected,
            Duration connectTimeout, Duration readTimeout) {
        TshirtServiceProperties properties = timeoutProperties(connectTimeout, readTimeout);

        assertThatThrownBy(() -> new TshirtServiceClient(properties))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(key + " ")
                .hasMessage(key + " must be between 1 ms and 2147483647 ms, was " + rejected);
    }

    /**
     * Configured timeouts that construct a client.
     *
     * @return a description, the connect and read timeouts ({@code null} for unset), and the whole milliseconds the
     *         connection gets for each
     */
    static Stream<Arguments> usableTimeouts() {
        Duration longest = Duration.ofMillis(Integer.MAX_VALUE);
        return Stream.of(
                Arguments.of("both unset: Mule's 10 s default", null, null, 10_000, 10_000),
                Arguments.of("connect unset", null, Duration.ofMillis(250), 10_000, 250),
                Arguments.of("read unset", Duration.ofMillis(250), null, 250, 10_000),
                Arguments.of("1 ms, the shortest", Duration.ofMillis(1), Duration.ofMillis(1), 1, 1),
                Arguments.of("fractions above 1 ms, truncated", Duration.ofNanos(1_999_999),
                        Duration.ofNanos(1_000_001), 1, 1),
                Arguments.of("Integer.MAX_VALUE ms, the longest", longest, longest.plusNanos(999_999),
                        Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("usableTimeouts")
    void usableOrUnsetTimeoutReachesTheConnectionInWholeMilliseconds(String description, Duration connectTimeout,
            Duration readTimeout, int connectMillis, int readMillis) throws IOException {
        TshirtServiceProperties properties = timeoutProperties(connectTimeout, readTimeout);

        TshirtServiceClient client = assertDoesNotThrow(() -> new TshirtServiceClient(properties));

        assertConnectionTimeouts(client, connectMillis, readMillis);
    }

    @Test
    void orderTshirtSendsOneSoap11PostWithItsActionTheAuthenticationHeaderAndTheBodyUnchanged() {
        stubService(soapResponse(200, ORDER_RESPONSE));

        newClient().orderTshirt(orderRequest());

        LoggedRequest request = singlePost();
        assertThat(request.getHeader("SOAPAction")).isEqualTo("\"" + TshirtServiceClient.ORDER_TSHIRT_ACTION + "\"");
        assertThat(request.getHeader("Content-Type")).startsWith("text/xml");
        assertThat(request.containsHeader("Accept-Encoding")).isFalse();
        assertSimilar(EXPECTED_ORDER_ENVELOPE, Input.fromByteArray(request.getBody()));
        Document envelope = parse(request.getBody());
        List<Element> headers = childElements(envelopeChild(envelope, "Header"));
        assertThat(headers).hasSize(1);
        Element authentication = headers.get(0);
        assertThat(authentication.getNamespaceURI()).isEqualTo(TshirtServiceClient.NAMESPACE);
        assertThat(authentication.getPrefix()).isEqualTo("ns0");
        assertThat(authentication.getLocalName()).isEqualTo("AuthenticationHeader");
        List<Element> keys = childElements(authentication);
        assertThat(keys).hasSize(1);
        assertThat(keys.get(0).getNamespaceURI()).isNull();
        assertThat(keys.get(0).getLocalName()).isEqualTo("apiKey");
        assertThat(keys.get(0).getTextContent()).isEqualTo(API_KEY);
        assertThat(childElements(envelopeChild(envelope, "Body")).get(0).getPrefix()).isEqualTo("ns0");
    }

    @Test
    void orderTshirtRejectsAMissingRequestBeforeAnyRequest() {
        TshirtServiceClient client = newClient();

        assertThatThrownBy(() -> client.orderTshirt(null))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("request must not be null");
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    // tshirt.api-key is ${TSHIRT_API_KEY:}: unset, the order is refused here instead of reaching the service.
    @ParameterizedTest(name = "api key [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void orderTshirtWithoutAnApiKeyFailsNamingTheKeyBeforeAnyRequest(String apiKey) {
        stubService(soapResponse(200, ORDER_RESPONSE));
        TshirtServiceClient client = assertDoesNotThrow(() -> newClient(wm.baseUrl(), apiKey));
        Document request = orderRequest();

        assertThatThrownBy(() -> client.orderTshirt(request))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(MISSING_API_KEY);
        wm.verify(exactly(0), anyRequestedFor(anyUrl()));
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    // The WSDL binds no input header to ListInventory, so neither the header nor the key is needed.
    @ParameterizedTest(name = "api key [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", API_KEY})
    void listInventoryWithoutABodySendsTheEmptyListInventoryElementAndNoHeaderWhateverTheKey(String apiKey) {
        stubService(soapResponse(200, INVENTORY_RESPONSE));

        Document response = newClient(wm.baseUrl(), apiKey).listInventory(null);

        assertThat(response).isNotNull();
        LoggedRequest request = singlePost();
        assertThat(request.getHeader("SOAPAction"))
                .isEqualTo("\"" + TshirtServiceClient.LIST_INVENTORY_ACTION + "\"");
        assertThat(request.getHeader("Content-Type")).startsWith("text/xml");
        assertSimilar(EXPECTED_LIST_INVENTORY_ENVELOPE, Input.fromByteArray(request.getBody()));
        Document envelope = parse(request.getBody());
        assertThat(childElements(envelopeChild(envelope, "Header"))).isEmpty();
        assertThat(envelope.getElementsByTagNameNS("*", "AuthenticationHeader").getLength()).isZero();
        assertThat(childElements(envelopeChild(envelope, "Body")).get(0).getPrefix()).isEqualTo("ns0");
    }

    @Test
    void listInventoryWithABodySendsThatElementUnchanged() {
        stubService(soapResponse(200, INVENTORY_RESPONSE));
        Document body = parse("<inv:ListInventory xmlns:inv=\"http://mulesoft.org/tshirt-service\"/>".getBytes(UTF_8));

        newClient().listInventory(body);

        Document envelope = parse(singlePost().getBody());
        List<Element> sent = childElements(envelopeChild(envelope, "Body"));
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).getPrefix()).isEqualTo("inv");
        assertThat(sent.get(0).getNamespaceURI()).isEqualTo(TshirtServiceClient.NAMESPACE);
        assertThat(sent.get(0).getLocalName()).isEqualTo("ListInventory");
        assertThat(sent.get(0).hasChildNodes()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Operation.class)
    void responseBodyChildIsReturnedAsItsOwnDocumentWithoutResponseHeaders(Operation operation) {
        stubService(soapResponse(200, operation.response));

        Document response = operation.call(newClient());

        assertThat(response).isNotNull();
        assertThat(response.getDocumentElement().getNamespaceURI()).isEqualTo(TshirtServiceClient.NAMESPACE);
        assertSimilar(operation.expectedPayload, Input.fromDocument(response));
        assertThat(response.getElementsByTagNameNS("*", "APIUsageInformation").getLength()).isZero();
        assertThat(response.getElementsByTagNameNS(SOAP_ENVELOPE_NAMESPACE, "*").getLength()).isZero();
        assertThat(singlePost().getHeader("SOAPAction")).isEqualTo("\"" + operation.soapAction + "\"");
    }

    static Stream<Arguments> noPayloadResponses() {
        List<Arguments> cases = new ArrayList<>();
        for (Operation operation : Operation.values()) {
            cases.add(Arguments.of(operation, "empty 200", 200, ""));
            cases.add(Arguments.of(operation, "empty 202", 202, ""));
            cases.add(Arguments.of(operation, "200 envelope with an empty Body", 200, EMPTY_BODY_RESPONSE));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("noPayloadResponses")
    void noResponseOrAnEmptyBodyYieldsNull(Operation operation, String description, int status, String body) {
        stubService(soapResponse(status, body));

        Document response = operation.call(newClient());

        assertThat(response).isNull();
        wm.verify(exactly(1), postRequestedFor(urlEqualTo(SERVICE_PATH)));
    }

    // SOAP 1.1 over HTTP sends a fault with status 500.
    @ParameterizedTest(name = "{0}")
    @EnumSource(Operation.class)
    void soapFaultSurfacesAsSoapFaultClientExceptionAfterOneRequest(Operation operation) {
        stubService(soapResponse(500, FAULT_RESPONSE));
        TshirtServiceClient client = newClient();

        SoapFaultClientException fault = assertThrows(SoapFaultClientException.class, () -> operation.call(client));

        assertThat(fault.getFaultStringOrReason()).isEqualTo("Invalid API key");
        assertThat(fault.getMessage()).isEqualTo("Invalid API key");
        assertThat(fault.getFaultCode()).isEqualTo(new QName(SOAP_ENVELOPE_NAMESPACE, "Client"));
        wm.verify(exactly(1), postRequestedFor(urlEqualTo(SERVICE_PATH)));
    }

    /** Faults whose fault string SAAJ can read, with the message the raised exception carries. */
    static Stream<Arguments> faultsWithAReadableFaultString() {
        List<Arguments> cases = new ArrayList<>();
        for (Operation operation : Operation.values()) {
            cases.add(Arguments.of(operation, "faultstring without a faultcode",
                    "<soap:Fault><faultstring>Invalid API key</faultstring></soap:Fault>", "Invalid API key"));
            cases.add(Arguments.of(operation, "empty faultstring",
                    "<soap:Fault><faultcode>soap:Server</faultcode><faultstring/></soap:Fault>", null));
        }
        return cases.stream();
    }

    // A fault string that can be read is the message, however incomplete the rest of the fault is.
    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("faultsWithAReadableFaultString")
    void faultWithAReadableFaultStringSurfacesAsSoapFaultClientExceptionAfterOneRequest(Operation operation,
            String description, String fault, String expectedMessage) {
        stubService(soapResponse(500, compactFaultEnvelope(fault)));
        TshirtServiceClient client = newClient();

        SoapFaultClientException failure = assertThrows(SoapFaultClientException.class, () -> operation.call(client));

        assertThat(failure.getMessage()).isEqualTo(expectedMessage);
        assertThat(failure.getFaultStringOrReason()).isEqualTo(expectedMessage);
        wm.verify(exactly(1), postRequestedFor(urlEqualTo(SERVICE_PATH)));
    }

    /** Faults without the {@code faultstring} SOAP 1.1 requires, which SAAJ cannot read. */
    static Stream<Arguments> faultsWithoutAFaultString() {
        List<Arguments> cases = new ArrayList<>();
        for (Operation operation : Operation.values()) {
            cases.add(Arguments.of(operation, "empty Fault", "<soap:Fault/>"));
            cases.add(Arguments.of(operation, "faultcode only",
                    "<soap:Fault><faultcode>soap:Server</faultcode></soap:Fault>"));
        }
        return cases.stream();
    }

    // SAAJ's NullPointerException names its own interface and field; it stays the cause, never the message.
    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("faultsWithoutAFaultString")
    void faultWithoutAFaultStringFailsWithAFixedMessageAfterOneRequest(Operation operation, String description,
                                                                       String fault) {
        stubService(soapResponse(500, compactFaultEnvelope(fault)));
        TshirtServiceClient client = newClient();

        WebServiceFaultException failure = assertThrows(WebServiceFaultException.class, () -> operation.call(client));

        assertThat(failure).isExactlyInstanceOf(WebServiceFaultException.class).hasMessage(INCOMPLETE_FAULT);
        assertThat(failure.getMessage()).doesNotContain("jakarta", "faultStringElement", "Cannot invoke");
        assertThat(failure.getCause()).isExactlyInstanceOf(NullPointerException.class);
        assertThat(failure.getWebServiceMessage()).isNull();
        wm.verify(exactly(1), postRequestedFor(urlEqualTo(SERVICE_PATH)));
    }

    static Stream<Arguments> httpErrorsWithoutFault() {
        List<Arguments> cases = new ArrayList<>();
        for (Operation operation : Operation.values()) {
            cases.add(Arguments.of(operation, 404, "text/html", "<html><body>Not Found</body></html>"));
            cases.add(Arguments.of(operation, 500, "text/plain", "Internal failure"));
            cases.add(Arguments.of(operation, 503, "text/plain", ""));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}: HTTP {1} {2}")
    @MethodSource("httpErrorsWithoutFault")
    void httpErrorWithoutAFaultSurfacesAsWebServiceTransportExceptionAfterOneRequest(Operation operation, int status,
                                                                                    String contentType, String body) {
        stubService(aResponse().withStatus(status).withHeader("Content-Type", contentType).withBody(body));
        TshirtServiceClient client = newClient();

        assertThatThrownBy(() -> operation.call(client))
                .isExactlyInstanceOf(WebServiceTransportException.class)
                .hasMessageEndingWith("[" + status + "]");
        wm.verify(exactly(1), postRequestedFor(urlEqualTo(SERVICE_PATH)));
    }

    // A timed-out OrderTshirt is never re-sent: the service may already have placed the order.
    @ParameterizedTest(name = "{0}")
    @EnumSource(Operation.class)
    void readTimeoutSurfacesAsWebServiceIOExceptionAndIsNeverRetried(Operation operation) {
        stubService(soapResponse(200, operation.response).withFixedDelay(5000));
        TshirtServiceClient client = newClient(wm.baseUrl(), API_KEY, SHORT_READ_TIMEOUT);

        long started = System.nanoTime();
        WebServiceIOException failure = assertThrows(WebServiceIOException.class, () -> operation.call(client));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(causeChain(failure)).anyMatch(SocketTimeoutException.class::isInstance);
        assertThat(elapsedMs).as("elapsed until the %d ms read timeout fired", SHORT_READ_TIMEOUT.toMillis())
                .isLessThan(4500);
        // WireMock journals a request on arrival, so any retry made before the failure surfaced is already counted.
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> wm.verify(exactly(1), postRequestedFor(urlEqualTo(SERVICE_PATH))));
        wm.verify(exactly(1), postRequestedFor(urlEqualTo(SERVICE_PATH)));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Operation.class)
    void refusedConnectionSurfacesAsWebServiceIOException(Operation operation) throws IOException {
        try (Socket reserved = reserveRefusingPort()) {
            TshirtServiceClient client = newClient("http://localhost:" + reserved.getLocalPort(), API_KEY);

            WebServiceIOException failure = assertThrows(WebServiceIOException.class, () -> operation.call(client));

            assertThat(causeChain(failure)).anyMatch(ConnectException.class::isInstance);
        }
    }

    // The JDK re-sends a buffered POST once after a reset; the service has already received the order by then.
    @ParameterizedTest(name = "{0}")
    @EnumSource(Operation.class)
    void connectionResetSurfacesAsWebServiceIOExceptionAndIsNeverRetried(Operation operation) {
        stubService(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER));
        TshirtServiceClient client = newClient();

        assertThrows(WebServiceIOException.class, () -> operation.call(client));

        wm.verify(exactly(1), postRequestedFor(urlEqualTo(SERVICE_PATH)));
    }

    // Single-attempt sending streams the buffered envelope with its exact length, so the request is not chunked.
    @ParameterizedTest(name = "{0}")
    @EnumSource(Operation.class)
    void requestCarriesItsContentLengthAndIsNotChunked(Operation operation) {
        stubService(soapResponse(200, operation.response));

        operation.call(newClient());

        LoggedRequest request = singlePost();
        assertThat(request.getHeader("Content-Length")).isEqualTo(String.valueOf(request.getBody().length));
        assertThat(request.containsHeader("Transfer-Encoding")).isFalse();
    }

    /**
     * Requests and the exact Body content SAAJ sends for them. Each request is written in the serializer's own form
     * (no declaration, {@code />} for an empty element, its escaping), so apart from the CDATA case the Body content
     * is the request itself.
     *
     * @return the operation, a description, the request ({@code null} for none) and the expected Body content
     *         ({@code null} when it is the request)
     */
    static Stream<Arguments> wireForms() {
        String bmp = "M\u00fcller \u65e5\u672c \u20ac &amp;&lt;&gt;\"' &#13; x";
        String bmpAttribute = "&#13;&#10;&#9; " + bmp.replace("\"", "&quot;");
        return Stream.of(
                Arguments.of(Operation.ORDER_TSHIRT, "supplementary characters in element text",
                        "<ns0:OrderTshirt " + NS0 + "><email>utf8@x</email><name>M\u00fcller \u65e5\u672c \u20ac "
                                + SUPPLEMENTARY + "</name></ns0:OrderTshirt>", null),
                Arguments.of(Operation.LIST_INVENTORY, "supplementary characters in an attribute value",
                        "<ns0:ListInventory " + NS0 + " filter=\"" + SUPPLEMENTARY + "\"/>", null),
                Arguments.of(Operation.LIST_INVENTORY, "supplementary characters in passed-through text and namespace",
                        "<ns0:ListInventory " + NS0 + "><size>" + SUPPLEMENTARY + "</size><x xmlns=\"urn:"
                                + GRINNING_FACE + "\"/></ns0:ListInventory>", null),
                Arguments.of(Operation.ORDER_TSHIRT, "BMP text, escapes, a carriage return and an empty element",
                        "<ns0:OrderTshirt " + NS0 + "><name>" + bmp + "</name><empty/></ns0:OrderTshirt>", null),
                Arguments.of(Operation.LIST_INVENTORY, "BMP attribute escapes and character references",
                        "<ns0:ListInventory " + NS0 + " filter=\"" + bmpAttribute + "\"/>", null),
                // A comment is written verbatim, so its literal reference stays text; SAAJ sends CDATA as text.
                Arguments.of(Operation.LIST_INVENTORY, "literal references in a comment and a CDATA section",
                        "<ns0:ListInventory " + NS0 + "><!-- " + GRINNING_FACE + " &#128512; &#x1F600; --><filter>"
                                + "<![CDATA[" + GRINNING_FACE + " &#128512; & <]]></filter></ns0:ListInventory>",
                        "<ns0:ListInventory " + NS0 + "><!-- " + GRINNING_FACE + " &#128512; &#x1F600; --><filter>"
                                + GRINNING_FACE + " &amp;#128512; &amp; &lt;</filter></ns0:ListInventory>"),
                Arguments.of(Operation.LIST_INVENTORY, "the default ListInventory element", null,
                        "<ns0:ListInventory " + NS0 + "/>"));
    }

    // The original's CXF/Woodstox stack wrote supplementary characters as UTF-8; every other byte is SAAJ's.
    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("wireForms")
    void requestIsSentInSaajsWireFormWithSupplementaryCharactersAsUtf8Bytes(Operation operation, String description,
                                                                           String request, String expectedOrNull) {
        stubService(soapResponse(200, operation.response));
        TshirtServiceClient client = newClient();
        Document document = request == null ? null : parse(request.getBytes(UTF_8));

        if (operation == Operation.ORDER_TSHIRT) {
            client.orderTshirt(document);
        }
        else {
            client.listInventory(document);
        }

        String start = operation == Operation.ORDER_TSHIRT ? ORDER_ENVELOPE_START : LIST_INVENTORY_ENVELOPE_START;
        String expected = start + (expectedOrNull == null ? request : expectedOrNull) + ENVELOPE_END;
        LoggedRequest sent = singlePost();
        assertThat(new String(sent.getBody(), UTF_8)).isEqualTo(expected);
        assertThat(sent.getBody()).isEqualTo(expected.getBytes(UTF_8));
        assertThat(sent.getHeader("Content-Length")).isEqualTo(String.valueOf(sent.getBody().length));
        assertThat(sent.containsHeader("Transfer-Encoding")).isFalse();
        assertThat(sent.getHeader("Content-Type")).isEqualToIgnoringCase(TEXT_XML);
    }

    static Stream<Arguments> characterReferenceRewrites() {
        return Stream.of(
                Arguments.of("decimal reference", "<a>&#128512;</a>", "<a>" + GRINNING_FACE + "</a>"),
                Arguments.of("hexadecimal reference", "<a>&#x1F600;&#x1f600;</a>",
                        "<a>" + GRINNING_FACE + GRINNING_FACE + "</a>"),
                Arguments.of("leading zeros", "<a>&#0128512;&#x0001F600;</a>",
                        "<a>" + GRINNING_FACE + GRINNING_FACE + "</a>"),
                Arguments.of("lowest and highest supplementary code points", "<a b=\"&#65536;\">&#1114111;</a>",
                        "<a b=\"" + Character.toString(0x10000) + "\">" + Character.toString(0x10FFFF) + "</a>"),
                Arguments.of("between multi-byte characters", "\u00fc&#128512;\u65e5",
                        "\u00fc" + GRINNING_FACE + "\u65e5"),
                Arguments.of("after a closed comment, CDATA section and instruction",
                        "<!--x-->&#128512;<![CDATA[x]]>&#128512;<?x?>&#128512;",
                        "<!--x-->" + GRINNING_FACE + "<![CDATA[x]]>" + GRINNING_FACE + "<?x?>" + GRINNING_FACE),
                Arguments.of("empty comment", "<!---->&#128512;", "<!---->" + GRINNING_FACE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("characterReferenceRewrites")
    void supplementaryCharacterReferencesBecomeUtf8Bytes(String description, String xml, String expected) {
        byte[] rewritten = TshirtServiceClient.encodeSupplementaryCharacterReferences(xml.getBytes(UTF_8));

        assertThat(rewritten).isEqualTo(expected.getBytes(UTF_8));
    }

    // Only references the serializer wrote for characters outside the BMP change; everything else is kept as sent.
    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {
            "", "&", "&#", "&#x", "&#;", "&#x;", "&#128512", "&#x1F600", "&#X1F600;", "&#12a;", "&# 128512;",
            "&#13;&#10;&#9;&#127;&#65535;&#xFFFF;", "&#1114112;", "&#x110000;", "&#99999999999999999999;",
            "&amp;#128512;", "<!-- &#128512; -->", "<![CDATA[&#128512;]]>", "<?pi &#128512;?>",
            "<?xml version=\"1.0\"?>", "<!-- &#128512;", "<![CDATA[&#128512;", "<?pi &#128512;", "<!-->&#128512;-->",
            "<a>\uD83D\uDE00 \u00fc \u65e5</a>"})
    void everyOtherByteIsKept(String xml) {
        byte[] bytes = xml.getBytes(UTF_8);

        assertThat(TshirtServiceClient.encodeSupplementaryCharacterReferences(bytes)).isSameAs(bytes);
    }
}
