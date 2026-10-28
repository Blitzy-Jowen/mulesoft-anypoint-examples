/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

/**
 * Unit test of {@link OrderProcessingSoapClient}, the replacement of the two
 * {@code http:request method="POST" path="orders" port="1080"} elements of the Ajax flows, each followed by an
 * {@code object-to-string-transformer}: {@code orderRequest} ({@code responseTimeout="10000000"}) and
 * {@code orderProxy} (no {@code responseTimeout}, so Mule's 10000 ms default)
 * (service-orchestration-and-choice-routing/src/main/app/mule-config.xml:21-22,40-41).
 *
 * <p>A WireMock server on a dynamic localhost port stands in for the application's own POST-only
 * {@code IProcessOrder} listener; the real 1080 listener is never reached. Every case runs for both
 * {@link OrderProcessingSoapClient#postOrderRequest(String)} and {@link OrderProcessingSoapClient#proxy(String)}.
 * The success case sends the original {@code processOrder} request ({@code message.xml}) and answers with the exact
 * CXF reply ({@code reply.xml}, single line, no trailing newline), proving the envelope leaves byte for byte and the
 * reply comes back unchanged. Every failure case, a SOAP fault over 500, the listener's 405, a timeout or a broken
 * connection, also proves exactly one request was sent, because the original requests had no retry.
 *
 * <p>No Spring context is started: the client is built directly with its three constructor arguments.
 */
class OrderProcessingSoapClientTest {

    private static final String ORDERS_PATH = "/orders";

    private static final int TIMEOUT_MS = 5000;

    /** Timeout of the method under test in the timeout case, far below the stub's delay so it fails fast. */
    private static final int SHORT_TIMEOUT_MS = 300;

    private static final int RESPONSE_DELAY_MS = 2000;

    private static final String SOAP_FAULT = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
            + "<soap:Body><soap:Fault><faultcode>soap:Server</faultcode><faultstring>boom</faultstring>"
            + "</soap:Fault></soap:Body></soap:Envelope>";

    private static final String METHOD_NOT_ALLOWED = "Method not allowed for endpoint: " + ORDERS_PATH;

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(WireMockConfiguration.wireMockConfig().dynamicPort())
            .build();

    /** The raw bytes of {@code message.xml}, as the test classpath holds them. */
    private static final byte[] MESSAGE_BYTES = resourceBytes("/message.xml");

    private static final String MESSAGE = new String(MESSAGE_BYTES, StandardCharsets.UTF_8);

    private static final String REPLY = new String(resourceBytes("/reply.xml"), StandardCharsets.UTF_8);

    private OrderProcessingSoapClient client;

    /** The two public calls; each builds a client in which only its own timeout key takes the given value. */
    enum Call {
        POST_ORDER_REQUEST(OrderProcessingSoapClient::postOrderRequest) {
            @Override
            OrderProcessingSoapClient clientWithOwnTimeout(String url, int ownTimeoutMs, int otherTimeoutMs) {
                return new OrderProcessingSoapClient(url, ownTimeoutMs, otherTimeoutMs);
            }
        },
        PROXY(OrderProcessingSoapClient::proxy) {
            @Override
            OrderProcessingSoapClient clientWithOwnTimeout(String url, int ownTimeoutMs, int otherTimeoutMs) {
                return new OrderProcessingSoapClient(url, otherTimeoutMs, ownTimeoutMs);
            }
        };

        private final BiFunction<OrderProcessingSoapClient, String, String> method;

        Call(BiFunction<OrderProcessingSoapClient, String, String> method) {
            this.method = method;
        }

        String invoke(OrderProcessingSoapClient client, String envelope) {
            return method.apply(client, envelope);
        }

        Call other() {
            return this == POST_ORDER_REQUEST ? PROXY : POST_ORDER_REQUEST;
        }

        /**
         * @param ownTimeoutMs   the timeout of this call: {@code orders.soap.request-timeout-ms} for
         *                       {@link #POST_ORDER_REQUEST}, {@code orders.soap.proxy-timeout-ms} for {@link #PROXY}
         * @param otherTimeoutMs the timeout of the other call
         */
        abstract OrderProcessingSoapClient clientWithOwnTimeout(String url, int ownTimeoutMs, int otherTimeoutMs);
    }

    @BeforeEach
    void createClient() {
        client = new OrderProcessingSoapClient(ordersUrl(), TIMEOUT_MS, TIMEOUT_MS);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Call.class)
    void postsEnvelopeBytesAndReturnsBodyUnchanged(Call call) {
        // The test resource decodes and re-encodes losslessly, so the bytes the client sends are the file's bytes.
        assertArrayEquals(MESSAGE_BYTES, MESSAGE.getBytes(StandardCharsets.UTF_8));
        wm.stubFor(post(urlEqualTo(ORDERS_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml; charset=UTF-8")
                        .withBody(REPLY)));

        String reply = call.invoke(client, MESSAGE);

        assertEquals(REPLY, reply);
        wm.verify(1, postRequestedFor(urlEqualTo(ORDERS_PATH))
                .withHeader("Content-Type", matching("(?i)text/xml;\\s*charset=UTF-8"))
                .withoutHeader("SOAPAction")
                .withRequestBody(binaryEqualTo(MESSAGE_BYTES)));
        assertThat(wm.getAllServeEvents()).hasSize(1);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Call.class)
    void serverErrorThrowsWithStatus500(Call call) {
        wm.stubFor(post(urlEqualTo(ORDERS_PATH))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "text/xml; charset=UTF-8")
                        .withBody(SOAP_FAULT)));

        RestClientResponseException thrown =
                assertThrows(RestClientResponseException.class, () -> call.invoke(client, MESSAGE));

        assertEquals(500, thrown.getStatusCode().value());
        assertEquals(SOAP_FAULT, thrown.getResponseBodyAsString());
        assertSingleRequest();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Call.class)
    void methodNotAllowedThrowsWithStatus405(Call call) {
        wm.stubFor(post(urlEqualTo(ORDERS_PATH))
                .willReturn(aResponse()
                        .withStatus(405)
                        .withHeader("Content-Type", "text/plain")
                        .withBody(METHOD_NOT_ALLOWED)));

        RestClientResponseException thrown =
                assertThrows(RestClientResponseException.class, () -> call.invoke(client, MESSAGE));

        assertEquals(405, thrown.getStatusCode().value());
        assertEquals(METHOD_NOT_ALLOWED, thrown.getResponseBodyAsString());
        assertSingleRequest();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Call.class)
    void ownTimeoutElapsingThrowsWithoutRetry(Call call) {
        wm.stubFor(post(urlEqualTo(ORDERS_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml; charset=UTF-8")
                        .withBody(REPLY)
                        .withFixedDelay(RESPONSE_DELAY_MS)));
        OrderProcessingSoapClient shortTimeoutClient =
                call.clientWithOwnTimeout(ordersUrl(), SHORT_TIMEOUT_MS, TIMEOUT_MS);

        ResourceAccessException thrown =
                assertThrows(ResourceAccessException.class, () -> call.invoke(shortTimeoutClient, MESSAGE));

        assertInstanceOf(SocketTimeoutException.class, thrown.getCause());
        assertSingleRequest();

        // The other call of the same client keeps its own, longer timeout and outlasts the delay.
        wm.resetRequests();
        assertEquals(REPLY, call.other().invoke(shortTimeoutClient, MESSAGE));
        assertSingleRequest();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("callsAndConnectionFaults")
    void connectionFailureThrowsWithoutRetry(Call call, Fault fault) {
        wm.stubFor(post(urlEqualTo(ORDERS_PATH)).willReturn(aResponse().withFault(fault)));

        assertThrows(ResourceAccessException.class, () -> call.invoke(client, MESSAGE));

        assertSingleRequest();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Call.class)
    void nullEnvelopeThrowsAndSendsNothing(Call call) {
        assertThrows(NullPointerException.class, () -> call.invoke(client, null));

        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Call.class)
    void emptyBodyReturnsEmptyString(Call call) {
        wm.stubFor(post(urlEqualTo(ORDERS_PATH)).willReturn(aResponse().withStatus(200)));

        assertEquals("", call.invoke(client, MESSAGE));

        assertSingleRequest();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("callsAndResponseCharsets")
    void bodyDecodedWithResponseCharsetOrUtf8(Call call, String contentType, Charset charset) {
        String summary = "<summary><customer>Jos\u00e9 \u00a3</customer></summary>";
        wm.stubFor(post(urlEqualTo(ORDERS_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", contentType)
                        .withBody(summary.getBytes(charset))));

        assertEquals(summary, call.invoke(client, MESSAGE));
    }

    static Stream<Arguments> callsAndConnectionFaults() {
        // A reset, or a close without any response, before the response head: the cases a client could re-send.
        return Stream.of(Call.values()).flatMap(call -> Stream.of(Fault.CONNECTION_RESET_BY_PEER, Fault.EMPTY_RESPONSE)
                .map(fault -> Arguments.of(call, fault)));
    }

    static Stream<Arguments> callsAndResponseCharsets() {
        return Stream.of(Call.values()).flatMap(call -> Stream.of(
                Arguments.of(call, "text/xml; charset=ISO-8859-1", StandardCharsets.ISO_8859_1),
                Arguments.of(call, "text/xml", StandardCharsets.UTF_8)));
    }

    private static String ordersUrl() {
        return "http://localhost:" + wm.getPort() + ORDERS_PATH;
    }

    /** Exactly one POST reached the orders path and no other request reached the server: no retry was made. */
    private static void assertSingleRequest() {
        wm.verify(1, postRequestedFor(urlEqualTo(ORDERS_PATH)));
        assertThat(wm.getAllServeEvents()).hasSize(1);
    }

    private static byte[] resourceBytes(String name) {
        try (InputStream in = OrderProcessingSoapClientTest.class.getResourceAsStream(name)) {
            assertNotNull(in, () -> "test resource " + name + " is missing");
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
