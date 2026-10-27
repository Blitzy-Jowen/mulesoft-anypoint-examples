/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.web_service_consumer.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingXPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.mulesoft.examples.web_service_consumer.client.TshirtServiceClient;
import com.mulesoft.examples.web_service_consumer.support.HttpTestClient;

/**
 * Wire-level test of {@link TshirtController}, the two HTTP listeners of
 * {@code web-service-consumer/src/main/app/tshirt-service-consumer.xml}: {@code /orders} (flow {@code orderTshirt},
 * line 7) and {@code /inventory} (flow {@code listInventory}, line 35).
 *
 * <p>The whole application runs on its Undertow listener at a random port with the {@code test} profile, which
 * supplies the original demo API key. Nothing is mocked: the real service, DataWeave mappers, Spring WS client and
 * SC-1 handler run end to end, and the only upstream is a WireMock {@code TshirtService} on localhost serving the
 * original sample responses. Requests go through ST-1 {@link HttpTestClient}, which reads the raw status line from
 * the socket, so status code, reason phrase, {@code Content-Type} and body bytes are asserted as the listener writes
 * them.
 *
 * <p>Covered per endpoint: the happy path with the exact DataWeave 1.0 JSON, every method being accepted (neither
 * listener declares {@code allowedMethods}), a body sent with a form-urlencoded {@code Content-Type} reaching the
 * flow as the caller sent it, and the platform-default 500 for unreadable input, a SOAP fault, a SOAP fault without a
 * readable fault string and a connectivity failure, none of which the flows catch or retry. Each response must carry
 * exactly one {@code Content-Type}, equal to the value the listener writes, and exactly the expected UTF-8 body bytes.
 * Unserved paths get the no-listener 404. The original {@code WebServiceConsumerIT} contains-assertions are ported
 * separately in {@code WebServiceConsumerTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TshirtControllerTest {

    /** SOAPAction of {@code OrderTshirt} ({@code tshirt.wsdl} line 177), quoted as SOAP 1.1 sends it. */
    private static final String ORDER_ACTION = "\"http://mulesoft.org/tshirt-service/order-tshirt\"";

    /** SOAPAction of {@code ListInventory} ({@code tshirt.wsdl} line 195), quoted as SOAP 1.1 sends it. */
    private static final String INVENTORY_ACTION = "\"http://mulesoft.org/tshirt-service/list-inventory\"";

    /** The demo API key {@code application-test.yml} supplies (tshirt-service-consumer.xml:22). */
    private static final String DEMO_API_KEY = "abc12345";

    private static final String JSON = "application/json";

    private static final String TEXT_PLAIN = "text/plain";

    private static final String FORM = "application/x-www-form-urlencoded";

    private static final String SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    private static final String TSHIRT_NS = "http://mulesoft.org/tshirt-service";

    /** A {@code ListInventory} request with content, so it cannot be mistaken for the empty one sent without a body. */
    private static final String INVENTORY_REQUEST =
            "<ns0:ListInventory xmlns:ns0=\"http://mulesoft.org/tshirt-service\"><size>L</size></ns0:ListInventory>";

    private static final String ORDER_RESPONSE_PREFIXED =
            "<ns2:OrderTshirtResponse xmlns:ns2=\"http://mulesoft.org/tshirt-service\">"
                    + "<orderId>1</orderId></ns2:OrderTshirtResponse>";

    /** The shape of the original {@code sample_data/OrderTshirtResponse.xml}: no namespace on the root. */
    private static final String ORDER_RESPONSE_UNPREFIXED =
            "<OrderTshirtResponse><orderId>1</orderId></OrderTshirtResponse>";

    /** The five items of the original {@code sample_data/ListInventoryResponse.xml}, in document order. */
    private static final String INVENTORY_RESPONSE =
            "<ns2:ListInventoryResponse xmlns:ns2=\"http://mulesoft.org/tshirt-service\">"
                    + inventoryItem("4102", "L", "Prueba", "2")
                    + inventoryItem("1412", "L", "Foo", "9")
                    + inventoryItem("5656", "S", "Bar", "2")
                    + inventoryItem("5657", "M", "Prueba2", "3")
                    + inventoryItem("1411", "M", "Awesome Tshirt", "5")
                    + "</ns2:ListInventoryResponse>";

    /** The WSDL's {@code TshirtFault} (line 102) as a SOAP 1.1 client fault. */
    private static final String FAULT_RESPONSE = "<soap:Fault><faultcode>soap:Client</faultcode>"
            + "<faultstring>Invalid API key</faultstring><detail>"
            + "<ns2:TshirtFault xmlns:ns2=\"http://mulesoft.org/tshirt-service\">"
            + "<errorMessage>Invalid API key</errorMessage></ns2:TshirtFault></detail></soap:Fault>";

    /** DataWeave 1.0 JSON of the {@code OrderTshirt} response: the root prefix dropped, leaves as strings. */
    private static final String ORDER_JSON = """
            {
              "OrderTshirtResponse": {
                "orderId": "1"
              }
            }""";

    /** DataWeave 1.0 JSON of {@code payload.ns0#ListInventoryResponse}: one repeated key per item, in order. */
    private static final String INVENTORY_JSON = """
            {
              "inventory": {
                "productCode": "4102",
                "size": "L",
                "description": "Prueba",
                "count": "2"
              },
              "inventory": {
                "productCode": "1412",
                "size": "L",
                "description": "Foo",
                "count": "9"
              },
              "inventory": {
                "productCode": "5656",
                "size": "S",
                "description": "Bar",
                "count": "2"
              },
              "inventory": {
                "productCode": "5657",
                "size": "M",
                "description": "Prueba2",
                "count": "3"
              },
              "inventory": {
                "productCode": "1411",
                "size": "M",
                "description": "Awesome Tshirt",
                "count": "5"
              }
            }""";

    /** The 500 body for a SOAP fault without a readable fault string: a fixed text naming no Java type or member. */
    private static final String INCOMPLETE_FAULT_BODY =
            "{ \"message\": \"The T-shirt service returned a SOAP fault without a readable fault string\" }";

    @RegisterExtension
    static WireMockExtension upstream = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("tshirt.service-address", upstream::baseUrl);
    }

    /** The two listeners, each with the operation it calls and the kind of request the original test sent it. */
    enum Endpoint {

        ORDERS("/orders", ORDER_ACTION, true),
        INVENTORY("/inventory", INVENTORY_ACTION, false);

        final String path;
        final String soapAction;
        /**
         * {@code true} when the original test posted {@code message.json}; its inventory call was the same POST with
         * the same {@code Content-Type: application/json} and a null payload.
         */
        final boolean sendsOrder;

        Endpoint(String path, String soapAction, boolean sendsOrder) {
            this.path = path;
            this.soapAction = soapAction;
            this.sendsOrder = sendsOrder;
        }
    }

    @Test
    void soapActionsAreTheQuotedActionsOfTheClientBinding() {
        assertThat(ORDER_ACTION).isEqualTo("\"" + TshirtServiceClient.ORDER_TSHIRT_ACTION + "\"");
        assertThat(INVENTORY_ACTION).isEqualTo("\"" + TshirtServiceClient.LIST_INVENTORY_ACTION + "\"");
    }

    @Test
    void postOrdersReturnsOrderJson() {
        stub(ORDER_ACTION, 200, ORDER_RESPONSE_PREFIXED);

        HttpTestClient.Response response = sendOrder("POST");

        assertJsonOk(response, ORDER_JSON);
        upstream.verify(1, postRequestedFor(anyUrl()).withHeader("SOAPAction", equalTo(ORDER_ACTION)));
    }

    @Test
    void postOrdersWithUnprefixedUpstreamRootReturnsSameJson() {
        stub(ORDER_ACTION, 200, ORDER_RESPONSE_UNPREFIXED);

        HttpTestClient.Response response = sendOrder("POST");

        assertJsonOk(response, ORDER_JSON);
    }

    @Test
    void postInventoryWithoutBodyReturnsInventoryJson() {
        stub(INVENTORY_ACTION, 200, INVENTORY_RESPONSE);

        // Additional coverage beyond the original request, which kept Content-Type: application/json with its null
        // payload (sendOriginalRequest, WebServiceConsumerTest): here the POST has no body and no Content-Type at all.
        HttpTestClient.Response response = client().send("POST", "/inventory", null, null);

        assertJsonOk(response, INVENTORY_JSON);
        assertThat(response.bodyAsString())
                .contains("\"productCode\": \"1412\"", "\"description\": \"Prueba2\"", "\"count\": \"5\"");
        upstream.verify(1, postRequestedFor(anyUrl()).withHeader("SOAPAction", equalTo(INVENTORY_ACTION)));
    }

    @Test
    void ordersCarriesApiKeyHeader() {
        stub(ORDER_ACTION, 200, ORDER_RESPONSE_PREFIXED);

        assertJsonOk(sendOrder("POST"), ORDER_JSON);

        upstream.verify(postRequestedFor(anyUrl())
                .withHeader("SOAPAction", equalTo(ORDER_ACTION))
                .withRequestBody(matchingXPath(
                        "//*[local-name()='AuthenticationHeader']/*[local-name()='apiKey']/text()",
                        equalTo(DEMO_API_KEY))));
    }

    // Without a body the order mapper rejects the empty input, so every method carries message.json.
    @ParameterizedTest(name = "{0} /orders")
    @ValueSource(strings = {"GET", "PUT", "PATCH", "DELETE", "OPTIONS"})
    void ordersAcceptsEveryMethod(String method) {
        stub(ORDER_ACTION, 200, ORDER_RESPONSE_PREFIXED);

        HttpTestClient.Response response = sendOrder(method);

        assertJsonOk(response, ORDER_JSON);
        upstream.verify(1, postRequestedFor(anyUrl()).withHeader("SOAPAction", equalTo(ORDER_ACTION)));
    }

    @ParameterizedTest(name = "{0} /inventory")
    @ValueSource(strings = {"GET", "PUT", "PATCH", "DELETE", "OPTIONS"})
    void inventoryAcceptsEveryMethod(String method) {
        stub(INVENTORY_ACTION, 200, INVENTORY_RESPONSE);

        HttpTestClient.Response response = client().send(method, "/inventory", null, null);

        assertJsonOk(response, INVENTORY_JSON);
        upstream.verify(1, postRequestedFor(anyUrl()).withHeader("SOAPAction", equalTo(INVENTORY_ACTION)));
    }

    // A form Content-Type does not turn the body into form parameters: the XML the caller sent is the SOAP body.
    @ParameterizedTest(name = "{0} /inventory")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void inventoryBodyWithFormContentTypeIsSentAsTheSoapBodyUnchanged(String method) throws Exception {
        stub(INVENTORY_ACTION, 200, INVENTORY_RESPONSE);

        HttpTestClient.Response response = client().send(method, "/inventory", Map.of("Content-Type", FORM),
                INVENTORY_REQUEST.getBytes(StandardCharsets.UTF_8));

        assertJsonOk(response, INVENTORY_JSON);
        Element sent = soapBodyChildOfTheOnlyRequest(INVENTORY_ACTION);
        assertThat(sent.getNamespaceURI()).isEqualTo(TSHIRT_NS);
        assertThat(sent.getLocalName()).isEqualTo("ListInventory");
        assertThat(childTexts(sent)).containsExactlyEntriesOf(Map.of("size", "L"));
    }

    // A form Content-Type does not turn the body into form parameters: the order mapper reads message.json as sent.
    @ParameterizedTest(name = "{0} /orders")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void ordersBodyWithFormContentTypeReachesTheOrderMapperUnchanged(String method) throws Exception {
        stub(ORDER_ACTION, 200, ORDER_RESPONSE_PREFIXED);

        HttpTestClient.Response response = client().send(method, "/orders", Map.of("Content-Type", FORM),
                message());

        assertJsonOk(response, ORDER_JSON);
        Element sent = soapBodyChildOfTheOnlyRequest(ORDER_ACTION);
        assertThat(sent.getNamespaceURI()).isEqualTo(TSHIRT_NS);
        assertThat(sent.getLocalName()).isEqualTo("OrderTshirt");
        assertThat(childTexts(sent)).containsExactlyEntriesOf(
                new ObjectMapper().readValue(message(), new TypeReference<LinkedHashMap<String, String>>() { }));
    }

    /**
     * A truncated JSON order, and an unterminated XML element where {@code /inventory} expects a SOAP body, each with
     * the 500 body carrying its reader's message. Jackson's message for the order is fixed English text, its line
     * break JSON-escaped. The JDK XML parser words its message in the default locale's language (the JDK ships
     * translations such as de, fr and ja), so the inventory's expected message is the one {@link #xmlParserMessage}
     * obtains from this JVM's own parser for the same bytes, and the expected body is exact in every locale.
     */
    static Stream<Arguments> unreadableBodies() throws Exception {
        String unterminatedInventory = "<ns0:ListInventory xmlns:ns0=\"http://mulesoft.org/tshirt-service\">";
        return Stream.of(
                Arguments.of("/orders", JSON, "{\"email\":", "{ \"message\": \"Unexpected end-of-input within/between"
                        + " Object entries\\n at [Source: UNKNOWN; line: 1, column: 10]\" }"),
                Arguments.of("/inventory", "text/xml", unterminatedInventory,
                        "{ \"message\": \"" + jsonEscaped(xmlParserMessage(unterminatedInventory)) + "\" }"));
    }

    // Each body is rejected by the flow's reader before the web service is called.
    @ParameterizedTest(name = "{0} with {2}")
    @MethodSource("unreadableBodies")
    void unreadableBodyIsAnUnhandled500WithoutAnySoapCall(String path, String contentType, String body,
                                                          String expectedBody) {
        HttpTestClient.Response response = client().send("POST", path, Map.of("Content-Type", contentType),
                body.getBytes(StandardCharsets.UTF_8));

        assertUnhandled500(response, expectedBody);
        upstream.verify(0, postRequestedFor(anyUrl()));
    }

    // SOAP 1.1 over HTTP carries a fault with status 500; the fault string becomes the error message.
    @ParameterizedTest(name = "{0}")
    @EnumSource(Endpoint.class)
    void soapFaultIsAnUnhandled500WithTheFaultString(Endpoint endpoint) {
        stub(endpoint.soapAction, 500, FAULT_RESPONSE);

        HttpTestClient.Response response = sendOriginalRequest(endpoint);

        assertUnhandled500(response, "{ \"message\": \"Invalid API key\" }");
        upstream.verify(1, postRequestedFor(anyUrl()));
    }

    /** Each endpoint with a SOAP 1.1 fault lacking the {@code faultstring} SOAP 1.1 requires. */
    static Stream<Arguments> faultsWithoutAFaultString() {
        List<Arguments> cases = new ArrayList<>();
        for (Endpoint endpoint : Endpoint.values()) {
            cases.add(Arguments.of(endpoint, "empty Fault", "<soap:Fault/>"));
            cases.add(Arguments.of(endpoint, "faultcode only",
                    "<soap:Fault><faultcode>soap:Server</faultcode></soap:Fault>"));
        }
        return cases.stream();
    }

    // SAAJ cannot read such a fault; the 500 reports it without the runtime's class and field names.
    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("faultsWithoutAFaultString")
    void faultWithoutAFaultStringIsAnUnhandled500WithAFixedMessage(Endpoint endpoint, String description,
                                                                   String fault) {
        stub(endpoint.soapAction, 500, fault);

        HttpTestClient.Response response = sendOriginalRequest(endpoint);

        assertUnhandled500(response, INCOMPLETE_FAULT_BODY);
        assertThat(response.bodyAsString()).doesNotContain("jakarta", "faultStringElement", "Cannot invoke");
        upstream.verify(1, postRequestedFor(anyUrl()));
    }

    // A connectivity failure is never retried: OrderTshirt may already have placed the order.
    @ParameterizedTest(name = "{0}")
    @EnumSource(Endpoint.class)
    void connectionResetIsAnUnhandled500AfterExactlyOneAttempt(Endpoint endpoint) {
        upstream.stubFor(post(anyUrl())
                .withHeader("SOAPAction", equalTo(endpoint.soapAction))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        HttpTestClient.Response response = sendOriginalRequest(endpoint);

        assertUnhandled500(response, "{ \"message\": \"I/O error: Connection reset\" }");
        upstream.verify(1, postRequestedFor(anyUrl()));
    }

    // Exact matches only: neither listener serves a sub-path or a trailing slash, and /services/* is unmapped.
    @ParameterizedTest(name = "GET {0}")
    @ValueSource(strings = {"/order", "/orders/1", "/orders/", "/inventory/x", "/services/x"})
    void unservedPathGetsTheNoListener404(String path) {
        HttpTestClient.Response response = client().get(path);

        assertThat(response.statusLine()).as(response.toString()).isEqualTo("HTTP/1.1 404 Not Found");
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.reason()).isEqualTo("Not Found");
        assertContent(response, TEXT_PLAIN, "No listener for endpoint: " + path);
        upstream.verify(0, postRequestedFor(anyUrl()));
    }

    private HttpTestClient client() {
        return HttpTestClient.localhost(port);
    }

    /** Sends {@code message.json} unmodified to {@code /orders} with {@code method}. */
    private HttpTestClient.Response sendOrder(String method) {
        return client().send(method, "/orders", Map.of("Content-Type", JSON), message());
    }

    /**
     * The request the original {@code WebServiceConsumerIT} sent to {@code endpoint}: a POST with
     * {@code Content-Type: application/json} carrying {@code message.json} to {@code /orders} (the original also
     * replaced the {@code ID} of its e-mail address with the current time, which no stubbed response depends on), and
     * the same POST with a null payload, sent as an empty body as {@code WebServiceConsumerTest} sends it, to
     * {@code /inventory}.
     */
    private HttpTestClient.Response sendOriginalRequest(Endpoint endpoint) {
        return endpoint.sendsOrder ? sendOrder("POST")
                : client().send("POST", endpoint.path, Map.of("Content-Type", JSON), new byte[0]);
    }

    /** The original test's order, {@code src/test/resources/message.json}, read from the classpath. */
    private byte[] message() {
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream("/message.json"),
                "classpath resource /message.json")) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void stub(String action, int status, String bodyContent) {
        upstream.stubFor(post(anyUrl())
                .withHeader("SOAPAction", equalTo(action))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "text/xml; charset=utf-8")
                        .withBody(envelope(bodyContent))));
    }

    private static String envelope(String bodyContent) {
        return "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
                + bodyContent + "</soap:Body></soap:Envelope>";
    }

    /**
     * The one element inside the SOAP 1.1 {@code Body} of the only request the upstream received, after checking that
     * exactly one request arrived and that it carried {@code soapAction}.
     */
    private static Element soapBodyChildOfTheOnlyRequest(String soapAction) throws Exception {
        List<LoggedRequest> requests = upstream.findAll(postRequestedFor(anyUrl()));
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).getHeader("SOAPAction")).isEqualTo(soapAction);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document envelope = factory.newDocumentBuilder().parse(new ByteArrayInputStream(requests.get(0).getBody()));
        NodeList bodies = envelope.getElementsByTagNameNS(SOAP_NS, "Body");
        assertThat(bodies.getLength()).as(requests.get(0).getBodyAsString()).isEqualTo(1);
        List<Element> children = childElements((Element) bodies.item(0));
        assertThat(children).as(requests.get(0).getBodyAsString()).hasSize(1);
        return children.get(0);
    }

    /** Each child element of {@code parent} as local name to text, in document order. */
    private static Map<String, String> childTexts(Element parent) {
        Map<String, String> texts = new LinkedHashMap<>();
        for (Element child : childElements(parent)) {
            texts.put(child.getLocalName(), child.getTextContent());
        }
        return texts;
    }

    private static List<Element> childElements(Element parent) {
        List<Element> elements = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element) {
                elements.add(element);
            }
        }
        return elements;
    }

    private static String inventoryItem(String productCode, String size, String description, String count) {
        return "<inventory><productCode>" + productCode + "</productCode><size>" + size + "</size><description>"
                + description + "</description><count>" + count + "</count></inventory>";
    }

    /**
     * The message of the {@link SAXParseException} the JDK's built-in XML parser raises for the UTF-8 bytes of
     * {@code xml}, configured as the {@code /inventory} reader configures it: namespace-aware, DOCTYPE declarations,
     * external entities, external DTDs, XInclude and entity-reference expansion off, secure processing on, and every
     * warning, error and fatal error raised. The text is in the default locale's language.
     */
    private static String xmlParserMessage(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new ErrorHandler() {
            @Override
            public void warning(SAXParseException exception) throws SAXException {
                throw exception;
            }

            @Override
            public void error(SAXParseException exception) throws SAXException {
                throw exception;
            }

            @Override
            public void fatalError(SAXParseException exception) throws SAXException {
                throw exception;
            }
        });
        try {
            builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (SAXParseException e) {
            assertThat(e.getMessage()).as("JDK XML parser message for %s", xml).isNotBlank();
            return e.getMessage();
        }
        throw new AssertionError("The JDK XML parser accepted " + xml);
    }

    /**
     * {@code text} escaped for a JSON string as the {@code { "message": ... }} body escapes it: quotation mark,
     * reverse solidus, backspace, form feed, line feed, carriage return and tab in their two-character forms, every
     * other character below U+0020, U+2028 and U+2029 as a six-character escape with four lower-case hex digits, and
     * every other character unchanged.
     */
    private static String jsonEscaped(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c < 0x20 || c == '\u2028' || c == '\u2029'
                        ? String.format(Locale.ROOT, "\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        return out.toString();
    }

    private static void assertJsonOk(HttpTestClient.Response response, String expectedBody) {
        assertThat(response.statusLine()).as(response.toString()).isEqualTo("HTTP/1.1 200 OK");
        assertContent(response, JSON, expectedBody);
    }

    private static void assertUnhandled500(HttpTestClient.Response response, String expectedBody) {
        assertThat(response.statusLine()).as(response.toString()).isEqualTo("HTTP/1.1 500 Internal Server Error");
        assertThat(response.status()).isEqualTo(500);
        assertContent(response, JSON, expectedBody);
    }

    /** Exactly one {@code Content-Type}, equal to {@code contentType}, and exactly the expected body's UTF-8 bytes. */
    private static void assertContent(HttpTestClient.Response response, String contentType, String expectedBody) {
        assertThat(response.headers("Content-Type")).as(response.toString()).containsExactly(contentType);
        assertThat(response.body()).as(response.toString()).isEqualTo(expectedBody.getBytes(StandardCharsets.UTF_8));
        assertThat(response.bodyAsString()).isEqualTo(expectedBody);
    }
}
