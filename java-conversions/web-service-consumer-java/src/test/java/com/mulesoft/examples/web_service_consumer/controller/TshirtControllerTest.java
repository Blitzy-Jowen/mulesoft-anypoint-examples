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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

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

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
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
 * listener declares {@code allowedMethods}), and the platform-default 500 for unreadable input, a SOAP fault and a
 * connectivity failure, none of which the flows catch or retry. Unserved paths get the no-listener 404. The
 * original {@code WebServiceConsumerIT} contains-assertions are ported separately in {@code WebServiceConsumerTest}.
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

    private static final String ERROR_BODY_PREFIX = "{ \"message\": ";

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

    /** The two listeners, each with the operation it calls and the request the original test sent it. */
    enum Endpoint {

        ORDERS("/orders", ORDER_ACTION, true),
        INVENTORY("/inventory", INVENTORY_ACTION, false);

        final String path;
        final String soapAction;
        /** {@code true} when the original test posted {@code message.json}; the inventory call had no payload. */
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

        // The original test sent a null payload: no body and no Content-Type.
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

    /** A truncated JSON order, and an unterminated XML element where {@code /inventory} expects a SOAP body. */
    static Stream<Arguments> unreadableBodies() {
        return Stream.of(
                Arguments.of("/orders", JSON, "{\"email\":"),
                Arguments.of("/inventory", "text/xml",
                        "<ns0:ListInventory xmlns:ns0=\"http://mulesoft.org/tshirt-service\">"));
    }

    // Each body is rejected by the flow's reader before the web service is called.
    @ParameterizedTest(name = "{0} with {2}")
    @MethodSource("unreadableBodies")
    void unreadableBodyIsAnUnhandled500WithoutAnySoapCall(String path, String contentType, String body) {
        HttpTestClient.Response response = client().send("POST", path, Map.of("Content-Type", contentType),
                body.getBytes(StandardCharsets.UTF_8));

        assertUnhandled500(response);
        upstream.verify(0, postRequestedFor(anyUrl()));
    }

    // SOAP 1.1 over HTTP carries a fault with status 500; the fault string becomes the error message.
    @ParameterizedTest(name = "{0}")
    @EnumSource(Endpoint.class)
    void soapFaultIsAnUnhandled500WithTheFaultString(Endpoint endpoint) {
        stub(endpoint.soapAction, 500, FAULT_RESPONSE);

        HttpTestClient.Response response = sendOriginalRequest(endpoint);

        assertUnhandled500(response);
        assertThat(response.bodyAsString()).isEqualTo("{ \"message\": \"Invalid API key\" }");
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

        assertUnhandled500(response);
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
        assertThat(response.header("Content-Type")).startsWith("text/plain");
        assertThat(response.bodyAsString()).isEqualTo("No listener for endpoint: " + path);
        upstream.verify(0, postRequestedFor(anyUrl()));
    }

    private HttpTestClient client() {
        return HttpTestClient.localhost(port);
    }

    /** Sends {@code message.json} unmodified to {@code /orders} with {@code method}. */
    private HttpTestClient.Response sendOrder(String method) {
        return client().send(method, "/orders", Map.of("Content-Type", JSON), message());
    }

    private HttpTestClient.Response sendOriginalRequest(Endpoint endpoint) {
        return endpoint.sendsOrder ? sendOrder("POST") : client().send("POST", endpoint.path, null, null);
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

    private static String inventoryItem(String productCode, String size, String description, String count) {
        return "<inventory><productCode>" + productCode + "</productCode><size>" + size + "</size><description>"
                + description + "</description><count>" + count + "</count></inventory>";
    }

    private static void assertJsonOk(HttpTestClient.Response response, String expectedBody) {
        assertThat(response.statusLine()).as(response.toString()).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.header("Content-Type")).startsWith(JSON);
        assertThat(response.bodyAsString()).isEqualTo(expectedBody);
    }

    private static void assertUnhandled500(HttpTestClient.Response response) {
        assertThat(response.statusLine()).as(response.toString()).isEqualTo("HTTP/1.1 500 Internal Server Error");
        assertThat(response.status()).isEqualTo(500);
        assertThat(response.header("Content-Type")).startsWith(JSON);
        assertThat(response.bodyAsString()).startsWith(ERROR_BODY_PREFIX).endsWith(" }");
    }
}
