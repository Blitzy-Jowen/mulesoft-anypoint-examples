/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.web_service_consumer.config;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.mulesoft.examples.web_service_consumer.client.TshirtServiceClient;
import com.mulesoft.examples.web_service_consumer.support.HttpTestClient;

/**
 * Wire-level test of {@link PreflightDispatchConfig} on the two listeners of
 * {@code web-service-consumer/src/main/app/tshirt-service-consumer.xml}, {@code /orders} (line 7) and
 * {@code /inventory} (line 35). Neither declares {@code allowedMethods} and Mule applied no CORS policy, so a CORS
 * pre-flight runs the flow and is answered exactly as a plain {@code OPTIONS} request to the same path is, with no
 * {@code Access-Control-*} or {@code Vary} header: {@code /inventory} calls {@code ListInventory} once and returns its
 * JSON, {@code /orders} without a body fails in the order mapper before any SOAP call, {@code /orders} carrying an
 * order places it, and an unserved path gets the no-listener 404.
 *
 * <p>The whole application runs on its Undertow listener at a random port with the {@code test} profile, which
 * supplies the original demo API key, against a WireMock {@code TshirtService}; requests go through ST-1
 * {@link HttpTestClient}, so the status line, headers and body bytes are asserted as the listener writes them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PreflightDispatchListenerTest {

    private static final String ORIGIN = "https://example.invalid";

    private static final String JSON = "application/json";

    /** SOAPAction of {@code OrderTshirt}, quoted as SOAP 1.1 sends it. */
    private static final String ORDER_ACTION = "\"" + TshirtServiceClient.ORDER_TSHIRT_ACTION + "\"";

    /** SOAPAction of {@code ListInventory}, quoted as SOAP 1.1 sends it. */
    private static final String INVENTORY_ACTION = "\"" + TshirtServiceClient.LIST_INVENTORY_ACTION + "\"";

    private static final String ORDER_RESPONSE =
            "<ns2:OrderTshirtResponse xmlns:ns2=\"http://mulesoft.org/tshirt-service\">"
                    + "<orderId>1</orderId></ns2:OrderTshirtResponse>";

    /** Two items of the original {@code sample_data/ListInventoryResponse.xml}, in document order. */
    private static final String INVENTORY_RESPONSE =
            "<ns2:ListInventoryResponse xmlns:ns2=\"http://mulesoft.org/tshirt-service\">"
                    + "<inventory><productCode>1412</productCode><size>L</size><description>Foo</description>"
                    + "<count>9</count></inventory>"
                    + "<inventory><productCode>5657</productCode><size>M</size><description>Prueba2</description>"
                    + "<count>3</count></inventory>"
                    + "</ns2:ListInventoryResponse>";

    /** DataWeave 1.0 JSON of {@code payload.ns0#ListInventoryResponse}: one repeated key per item, in order. */
    private static final String INVENTORY_JSON = """
            {
              "inventory": {
                "productCode": "1412",
                "size": "L",
                "description": "Foo",
                "count": "9"
              },
              "inventory": {
                "productCode": "5657",
                "size": "M",
                "description": "Prueba2",
                "count": "3"
              }
            }""";

    /** DataWeave 1.0 JSON of the {@code OrderTshirt} response. */
    private static final String ORDER_JSON = """
            {
              "OrderTshirtResponse": {
                "orderId": "1"
              }
            }""";

    /** The platform-default 500 of the order mapper's rejection of a missing body. */
    private static final String EMPTY_BODY_ERROR = "{ \"message\": \"Request body is empty\" }";

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

    /** Requested method of the pre-flight, then its {@code Access-Control-Request-Headers}, or {@code null}. */
    static Stream<Arguments> preflights() {
        return Stream.of(
                Arguments.of("GET", null),
                Arguments.of("POST", null),
                Arguments.of("PUT", null),
                Arguments.of("DELETE", null),
                Arguments.of("PATCH", null),
                Arguments.of("OPTIONS", null),
                Arguments.of("POST", "content-type"));
    }

    @ParameterizedTest(name = "Access-Control-Request-Method {0}, Access-Control-Request-Headers {1}")
    @MethodSource("preflights")
    void preflightToInventoryCallsListInventoryOnceLikeAPlainOptionsRequest(String requestedMethod,
            String requestedHeaders) {
        upstream.stubFor(post(anyUrl())
                .withHeader("SOAPAction", equalTo(INVENTORY_ACTION))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml; charset=utf-8")
                        .withBody(envelope(INVENTORY_RESPONSE))));
        HttpTestClient.Response plain = client().send("OPTIONS", "/inventory", null, null);
        upstream.resetRequests();

        HttpTestClient.Response preflight = client().send("OPTIONS", "/inventory",
                preflightHeaders(requestedMethod, requestedHeaders, null), null);

        assertSameResponse(plain, preflight);
        assertThat(preflight.statusLine()).as(preflight.toString()).isEqualTo("HTTP/1.1 200 OK");
        assertThat(preflight.header("Content-Type")).startsWith(JSON);
        assertThat(preflight.bodyAsString()).isEqualTo(INVENTORY_JSON);
        assertNoCorsHeaders(preflight);
        upstream.verify(1, postRequestedFor(anyUrl()));
        upstream.verify(1, postRequestedFor(anyUrl()).withHeader("SOAPAction", equalTo(INVENTORY_ACTION)));
    }

    // The order mapper rejects the missing body, so the flow ends in the platform-default 500 before ws:consumer.
    @ParameterizedTest(name = "Access-Control-Request-Method {0}, Access-Control-Request-Headers {1}")
    @MethodSource("preflights")
    void preflightToOrdersWithoutBodyRunsTheEmptyBodyBranchLikeAPlainOptionsRequest(String requestedMethod,
            String requestedHeaders) {
        HttpTestClient.Response plain = client().send("OPTIONS", "/orders", null, null);

        HttpTestClient.Response preflight = client().send("OPTIONS", "/orders",
                preflightHeaders(requestedMethod, requestedHeaders, null), null);

        assertSameResponse(plain, preflight);
        assertThat(preflight.statusLine()).as(preflight.toString())
                .isEqualTo("HTTP/1.1 500 Internal Server Error");
        assertThat(preflight.header("Content-Type")).startsWith(JSON);
        assertThat(preflight.bodyAsString()).isEqualTo(EMPTY_BODY_ERROR);
        assertNoCorsHeaders(preflight);
        upstream.verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    void preflightToOrdersCarryingAnOrderPlacesItOnce() {
        upstream.stubFor(post(anyUrl())
                .withHeader("SOAPAction", equalTo(ORDER_ACTION))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml; charset=utf-8")
                        .withBody(envelope(ORDER_RESPONSE))));

        HttpTestClient.Response preflight = client().send("OPTIONS", "/orders",
                preflightHeaders("POST", "content-type", JSON), message());

        assertThat(preflight.statusLine()).as(preflight.toString()).isEqualTo("HTTP/1.1 200 OK");
        assertThat(preflight.header("Content-Type")).startsWith(JSON);
        assertThat(preflight.bodyAsString()).isEqualTo(ORDER_JSON);
        assertNoCorsHeaders(preflight);
        upstream.verify(1, postRequestedFor(anyUrl()));
        upstream.verify(1, postRequestedFor(anyUrl())
                .withHeader("SOAPAction", equalTo(ORDER_ACTION))
                .withRequestBody(matchingXPath(
                        "//*[local-name()='OrderTshirt']/*[local-name()='email']/text()",
                        equalTo("customerID@gmail.com")))
                .withRequestBody(matchingXPath(
                        "//*[local-name()='OrderTshirt']/*[local-name()='size']/text()", equalTo("L"))));
    }

    @Test
    void preflightToAnUnservedPathGetsTheNoListener404() {
        HttpTestClient.Response plain = client().send("OPTIONS", "/unknown", null, null);

        HttpTestClient.Response preflight = client().send("OPTIONS", "/unknown",
                preflightHeaders("POST", null, null), null);

        assertSameResponse(plain, preflight);
        assertThat(preflight.statusLine()).as(preflight.toString()).isEqualTo("HTTP/1.1 404 Not Found");
        assertThat(preflight.header("Content-Type")).startsWith("text/plain");
        assertThat(preflight.bodyAsString()).isEqualTo("No listener for endpoint: /unknown");
        assertNoCorsHeaders(preflight);
        upstream.verify(0, postRequestedFor(anyUrl()));
    }

    private HttpTestClient client() {
        return HttpTestClient.localhost(port);
    }

    private static Map<String, String> preflightHeaders(String requestedMethod, String requestedHeaders,
            String contentType) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Origin", ORIGIN);
        headers.put("Access-Control-Request-Method", requestedMethod);
        if (requestedHeaders != null) {
            headers.put("Access-Control-Request-Headers", requestedHeaders);
        }
        if (contentType != null) {
            headers.put("Content-Type", contentType);
        }
        return headers;
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

    private static String envelope(String bodyContent) {
        return "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
                + bodyContent + "</soap:Body></soap:Envelope>";
    }

    /** Same status line, same body bytes and the same headers but {@code Date}, compared as sorted lines. */
    private static void assertSameResponse(HttpTestClient.Response expected, HttpTestClient.Response actual) {
        assertThat(actual.statusLine()).as(actual.toString()).isEqualTo(expected.statusLine());
        assertThat(actual.body()).as(actual.toString()).isEqualTo(expected.body());
        assertThat(headerLines(actual)).as(actual.toString()).isEqualTo(headerLines(expected));
    }

    /** Every header but {@code Date} as {@code name: value} with the name lower-cased, sorted, duplicates kept. */
    private static List<String> headerLines(HttpTestClient.Response response) {
        return response.headers().stream()
                .filter(header -> !header.getKey().equalsIgnoreCase("Date"))
                .map(header -> header.getKey().toLowerCase(Locale.ROOT) + ": " + header.getValue())
                .sorted()
                .toList();
    }

    private static void assertNoCorsHeaders(HttpTestClient.Response response) {
        assertThat(response.headers()).as(response.toString()).noneMatch(header ->
                header.getKey().regionMatches(true, 0, "Access-Control-", 0, "Access-Control-".length())
                        || header.getKey().equalsIgnoreCase("Vary"));
    }
}
