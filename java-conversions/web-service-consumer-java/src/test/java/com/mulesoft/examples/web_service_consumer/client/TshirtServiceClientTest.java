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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
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
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.client.WebServiceTransportException;
import org.springframework.ws.soap.client.SoapFaultClientException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.Diff;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
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
 * refused connection surface as the Spring WS exceptions, never retried.
 *
 * <p>Plain JUnit 5: the client is built directly from {@link TshirtServiceProperties}; no Spring context is started.
 * Every document the test reads is parsed with DOCTYPE declarations and external entities disabled.
 */
class TshirtServiceClientTest {

    private static final String SERVICE_PATH = "/";

    /** The original demo key, hard-coded at {@code tshirt-service-consumer.xml:22}; test-only. */
    private static final String API_KEY = "abc12345";

    private static final String MISSING_API_KEY = "T-shirt service API key (tshirt.api-key) is not configured";

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
}
