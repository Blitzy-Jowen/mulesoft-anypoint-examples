/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.filtering_a_message.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.mulesoft.examples.filtering_a_message.support.HttpTestClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Wire-level test of {@link DiscountController}, the HTTP trigger of flow {@code filteringFlow1}
 * (filtering-a-message/src/main/app/filtering.xml:4-19), on the application's own Undertow listener with the real
 * service, filter and SC-1 handler.
 *
 * <p>Requests go through the project's ST-1 {@link HttpTestClient}, which reads the raw status line from the
 * socket, so the reason phrase, the headers and the body bytes are asserted exactly as the listener writes them:
 * <ul>
 *   <li>the granted reply of {@code set-payload} (filtering.xml:16) and the empty 200 a rejecting filter leaves
 *       (filtering.xml:9-15; FilteringMessageIT.java:61-67), for JSON, text, form, multipart and empty bodies;</li>
 *   <li>the listener's {@code allowedMethods="POST"} (filtering.xml:5): {@code POST} in any letter case runs the
 *       flow, every other method gets SC-1's 405, and every other path SC-1's no-listener 404;</li>
 *   <li>the listener's own 400 for a form body it cannot decode, and the JSON 500 for a payload the
 *       {@code json-to-object-transformer} (filtering.xml:14) or the custom filter
 *       (FreeMembershipDiscountFilter.java:18-30) fails on, since the flow declares no exception strategy.</li>
 * </ul>
 *
 * <p>The original {@code FilteringMessageIT} assertions are ported separately in {@code FilteringMessageTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DiscountControllerTest {

    private static final String LISTENER_PATH = "/";

    private static final String GRANTED_TEXT = "the discount was granted.";

    private static final String JSON = "application/json";

    private static final String MULTIPART_BOUNDARY = "discount-boundary";

    @LocalServerPort
    private int port;

    private HttpTestClient client;

    @BeforeEach
    void createClient() {
        client = HttpTestClient.localhost(port);
    }

    @Test
    void postGrantedMessageReturnsGrantedText() {
        HttpTestClient.Response response = postJson(resource("message.json"));

        assertGranted(response);
    }

    @Test
    void postRejectedMessageReturnsEmpty200() {
        HttpTestClient.Response response = postJson(resource("message1.json"));

        assertEmpty200(response);
    }

    @Test
    void postWithoutBodyReturnsEmpty200() {
        HttpTestClient.Response response = client.send("POST", LISTENER_PATH, Map.of("Content-Type", JSON),
                new byte[0]);

        assertEmpty200(response);
    }

    /** The listener declared no accepted media type, so a JSON body sent as text is converted and granted alike. */
    @Test
    void postWithTextPlainContentTypeIsGranted() {
        HttpTestClient.Response response = client.send("POST", LISTENER_PATH, Map.of("Content-Type", "text/plain"),
                resource("message.json"));

        assertGranted(response);
    }

    /**
     * The listener compared the request method with {@code allowedMethods} ignoring case, and the and-filter's
     * {@code http.method=post} property filter is {@code caseSensitive="false"} (filtering.xml:11).
     *
     * @param method {@code POST} in another letter case
     */
    @ParameterizedTest
    @ValueSource(strings = {"post", "Post"})
    void postInAnyLetterCaseIsGranted(String method) {
        HttpTestClient.Response response = client.send(method, LISTENER_PATH, Map.of("Content-Type", JSON),
                resource("message.json"));

        assertGranted(response);
    }

    /**
     * The payload-type filter (filtering.xml:10) accepted only the request stream, which the listener did not hand
     * over for a form-urlencoded body (decoded to a parameter map) or a multipart body (moved to attachments), so
     * even values that qualify for the discount end the flow with the empty 200.
     *
     * @param label       the case name
     * @param contentType the request {@code Content-Type}
     * @param body        the request body
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("nonStreamPayloads")
    void formAndMultipartBodiesAreRejectedWithEmpty200(String label, String contentType, byte[] body) {
        HttpTestClient.Response response = client.send("POST", LISTENER_PATH, Map.of("Content-Type", contentType),
                body);

        assertEmpty200(response);
    }

    static Stream<Arguments> nonStreamPayloads() {
        byte[] multipart = multipartBody(resource("message.json"));
        return Stream.of(
                Arguments.of("form-urlencoded", "application/x-www-form-urlencoded",
                        utf8("purchases=2000&months=12&membership=free")),
                Arguments.of("multipart", "multipart/form-data; boundary=" + MULTIPART_BOUNDARY, multipart));
    }

    /**
     * A malformed percent escape fails before the flow runs, with the listener's own 400: Undertow's standard reason
     * phrase, the failure's message and its length, and no {@code Content-Type}.
     */
    @Test
    void malformedFormEscapeReturnsListener400() {
        HttpTestClient.Response response = client.send("POST", LISTENER_PATH,
                Map.of("Content-Type", "application/x-www-form-urlencoded"), utf8("months=%ZZ"));

        String message = "Cannot decode x-www-form-urlencoded payload";
        assertEquals("HTTP/1.1 400 Bad Request", response.statusLine(), response::toString);
        assertEquals(400, response.status(), response::toString);
        assertEquals("Bad Request", response.reason(), response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertEquals(String.valueOf(utf8(message).length), response.header("Content-Length"), response::toString);
        assertArrayEquals(utf8(message), response.body(), response::toString);
    }

    /**
     * {@code OPTIONS} gets the 405 like every other method: the controller's explicit {@code OPTIONS} mapping keeps
     * Spring MVC from answering it with its own 200 and {@code Allow} header.
     *
     * @param method a method outside the listener's {@code allowedMethods}
     */
    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT", "DELETE", "OPTIONS"})
    void methodOtherThanPostReturns405(String method) {
        HttpTestClient.Response response = client.send(method, LISTENER_PATH, Map.of(), new byte[0]);

        assertEquals("HTTP/1.1 405 Method Not Allowed", response.statusLine(), response::toString);
        assertEquals(405, response.status(), response::toString);
        assertEquals("Method Not Allowed", response.reason(), response::toString);
        assertContentType(response, "text/plain");
        assertEquals("Method not allowed for endpoint: " + LISTENER_PATH, response.bodyAsString(),
                response::toString);
    }

    @Test
    void postToOtherPathReturnsNoListener404() {
        HttpTestClient.Response response = client.send("POST", "/other", Map.of("Content-Type", JSON),
                resource("message.json"));

        assertEquals("HTTP/1.1 404 Not Found", response.statusLine(), response::toString);
        assertEquals(404, response.status(), response::toString);
        assertEquals("Not Found", response.reason(), response::toString);
        assertContentType(response, "text/plain");
        assertEquals("No listener for endpoint: /other", response.bodyAsString(), response::toString);
    }

    /**
     * The flow declares no exception strategy, so each failure reaches SC-1's JSON 500. The whole body is asserted:
     * the service answers every failure with a fixed message of its own, never JDK or Jackson text that could quote
     * the request or vary between versions.
     *
     * @param label   the case name
     * @param body    the JSON request body
     * @param message the message the 500 body carries
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unprocessablePayloads")
    void unprocessablePayloadReturns500JsonMessage(String label, String body, String message) {
        HttpTestClient.Response response = postJson(utf8(body));

        assertEquals("HTTP/1.1 500 Internal Server Error", response.statusLine(), response::toString);
        assertEquals(500, response.status(), response::toString);
        assertEquals("Internal Server Error", response.reason(), response::toString);
        assertContentType(response, JSON);
        assertEquals("{ \"message\": \"" + message + "\" }", response.bodyAsString(), response::toString);
    }

    static Stream<Arguments> unprocessablePayloads() {
        String filterInput =
                "JSON payload does not hold the membership, months and purchases values the discount filter requires";
        return Stream.of(
                Arguments.of("malformed JSON", "{\"purchases\": 2000,", "Payload is not valid JSON"),
                Arguments.of("JSON array", "[1]", "JSON payload is not an object"),
                Arguments.of("JSON null", "null", "JSON payload is not an object"),
                Arguments.of("missing membership", "{}", filterInput),
                Arguments.of("non-integer months",
                        "{\"purchases\": 2000, \"months\": \"12.5\", \"membership\": \"free\"}", filterInput));
    }

    private HttpTestClient.Response postJson(byte[] body) {
        return client.send("POST", LISTENER_PATH, Map.of("Content-Type", JSON), body);
    }

    /**
     * The granted reply carries no {@code Content-Type}, only the length of its 25 bytes: the original's
     * {@code set-payload} declares no MIME type, so its listener sent none, and the controller reproduces those
     * bytes rather than adding a {@code text/plain} header the original never wrote.
     */
    private static void assertGranted(HttpTestClient.Response response) {
        byte[] expected = utf8(GRANTED_TEXT);
        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertEquals(200, response.status(), response::toString);
        assertEquals("OK", response.reason(), response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertEquals(String.valueOf(expected.length), response.header("Content-Length"), response::toString);
        assertEquals(GRANTED_TEXT, response.bodyAsString(), response::toString);
        assertArrayEquals(expected, response.body(), response::toString);
    }

    /** A rejecting filter ends the flow with no payload: a 200 with no body and no {@code Content-Type}. */
    private static void assertEmpty200(HttpTestClient.Response response) {
        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertEquals(200, response.status(), response::toString);
        assertEquals("OK", response.reason(), response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertEquals("0", response.header("Content-Length"), response::toString);
        assertEquals(0, response.body().length, response::toString);
    }

    /**
     * Compares the whole value: SC-1 presets the bare media type on the response entity, so the listener writes
     * exactly one {@code Content-Type} header carrying that value and no charset parameter.
     */
    private static void assertContentType(HttpTestClient.Response response, String expected) {
        assertEquals(List.of(expected), response.headers("Content-Type"), response::toString);
    }

    /** A {@code multipart/form-data} body whose single {@code file} part holds {@code content}. */
    private static byte[] multipartBody(byte[] content) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(utf8("--" + MULTIPART_BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"message.json\"\r\n"
                + "Content-Type: application/json\r\n\r\n"));
        body.writeBytes(content);
        body.writeBytes(utf8("\r\n--" + MULTIPART_BOUNDARY + "--\r\n"));
        return body.toByteArray();
    }

    /** The bytes of a test resource at the classpath root. */
    private static byte[] resource(String name) {
        try (InputStream in = DiscountControllerTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(in, "test resource /" + name + " is missing");
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read test resource /" + name, e);
        }
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
