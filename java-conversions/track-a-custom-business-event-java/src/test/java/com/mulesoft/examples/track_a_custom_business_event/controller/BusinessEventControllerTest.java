package com.mulesoft.examples.track_a_custom_business_event.controller;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;

import com.mulesoft.examples.track_a_custom_business_event.support.HttpTestClient;

/**
 * Wire-level test of {@link BusinessEventController}, the HTTP listener of flow {@code custom-business-eventsFlow1}
 * (track-a-custom-business-event/src/main/app/custom-business-events.xml:3-5), on the application's own Undertow
 * listener with the real service and tracker behind it.
 *
 * <p>Requests go through the project's ST-1 {@link HttpTestClient}, which adds no {@code Content-Type} of its own
 * and reads the raw status line from the socket, so the reason phrase, every {@code Content-Type} value and the
 * body bytes are asserted exactly as the listener writes them. The happy paths cover the response media type the
 * request's {@code Content-Type} decides, the request's charset decoding the body, and every method, because the
 * original listener declares no {@code allowedMethods}; the error cases are the flow's own script failures, an
 * unpriced item and a price nested 20000 deep, whose message is rendered on the listener's worker thread without
 * exhausting its stack, and the listener-level platform defaults of SC-1: the JSON 500 for a body the flow cannot
 * read and the no-listener 404 for paths the flow does not serve.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BusinessEventControllerTest {

    /** IPv4 loopback literal: the listener binds {@code 0.0.0.0}, and {@code localhost} may resolve to {@code ::1}. */
    private static final String LOOPBACK = "127.0.0.1";

    private static final String PATH = "/customBusinessEvents";

    /** A shoes request at price 10 whose email holds {@code é}, which Latin-1 encodes as the single byte 0xE9. */
    private static final String CAFE_SHOES = "{\"email\":\"caf\u00e9@abc.sk\",\"item name\":\"shoes\","
            + "\"item units\":2,\"item price per unit\":10}";

    @LocalServerPort
    private int port;

    private HttpTestClient client() {
        return new HttpTestClient(LOOPBACK, port);
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("mediaTypedRequests")
    void responseCarriesTheRequestMediaTypeWithItsEncoding(String requestContentType, byte[] body,
            String expectedContentType) {
        HttpTestClient.Response response =
                client().send("POST", PATH, Map.of("Content-Type", requestContentType), body);

        assertDiscountedPrice(response);
        assertEquals(List.of(expectedContentType), response.headers("Content-Type"), response::toString);
    }

    static Stream<Arguments> mediaTypedRequests() throws IOException {
        return Stream.of(
                Arguments.of("application/json", message(), "application/json; charset=UTF-8"),
                Arguments.of("application/json; charset=UTF-8", message(), "application/json; charset=UTF-8"),
                // Latin-1 bytes are not valid UTF-8: the declared charset decodes them.
                Arguments.of("application/json; charset=ISO-8859-1", CAFE_SHOES.getBytes(ISO_8859_1),
                        "application/json; charset=ISO-8859-1"),
                // An alias is answered with the charset's canonical name.
                Arguments.of("application/json; charset=latin1", CAFE_SHOES.getBytes(ISO_8859_1),
                        "application/json; charset=ISO-8859-1"),
                Arguments.of("text/plain", message(), "text/plain; charset=UTF-8"),
                Arguments.of("application/octet-stream", message(), "application/octet-stream; charset=UTF-8"),
                Arguments.of("text/*", message(), "text/*; charset=UTF-8"),
                // Type and subtype in lower case; parameters other than the charset dropped.
                Arguments.of("Application/JSON; Charset=utf8; profile=price", message(),
                        "application/json; charset=UTF-8"));
    }

    /** No {@code Content-Type}, or the wildcard media type, is answered without one. */
    @ParameterizedTest(name = "Content-Type {0}")
    @NullSource
    @ValueSource(strings = "*/*")
    void requestWithoutMediaTypeIsAnsweredWithoutContentType(String requestContentType) throws IOException {
        Map<String, String> headers =
                requestContentType == null ? Map.of() : Map.of("Content-Type", requestContentType);

        HttpTestClient.Response response = client().send("POST", PATH, headers, message());

        assertDiscountedPrice(response);
        assertEquals(List.of(), response.headers("Content-Type"), response::toString);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "GET", "DELETE", "PATCH", "OPTIONS"})
    void everyMethodIsAccepted(String method) throws IOException {
        HttpTestClient.Response response =
                client().send(method, PATH, Map.of("Content-Type", "application/json"), message());

        assertDiscountedPrice(response);
        assertEquals(List.of("application/json; charset=UTF-8"), response.headers("Content-Type"),
                response::toString);
        assertEquals(List.of(), response.headers("Allow"), response::toString);
    }

    @Test
    void headAnswersTheHeadersWithoutTheBody() throws IOException {
        HttpTestClient.Response response =
                client().send("HEAD", PATH, Map.of("Content-Type", "application/json"), message());

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertEquals(List.of("application/json; charset=UTF-8"), response.headers("Content-Type"),
                response::toString);
        assertEquals("3", response.header("Content-Length"), response::toString);
        assertArrayEquals(new byte[0], response.body(), response::toString);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failingRequests")
    void unprocessableBodyReturns500JsonMessage(String description, String body, String expectedBody) {
        HttpTestClient.Response response = client().post(PATH, "application/json", body);

        assertEquals(500, response.status(), response::toString);
        assertEquals("Internal Server Error", response.reason(), response::toString);
        assertEquals(1, response.headers("Content-Type").size(), response::toString);
        assertMediaType(response, MediaType.APPLICATION_JSON);
        assertEquals(expectedBody, response.bodyAsString(), response::toString);
    }

    static Stream<Arguments> failingRequests() throws IOException {
        String hats = new String(message(), UTF_8).replace("\"shoes\"", "\"hats\"");
        String nestedPrice = "[".repeat(20_000) + "0" + "]".repeat(20_000);
        String nestedPriceBody = new String(message(), UTF_8)
                .replace("\"item price per unit\": 10", "\"item price per unit\": " + nestedPrice);
        return Stream.of(
                Arguments.of("unknown item hats", hats, "{ \"message\": \"nil can't be coerced into Fixnum\" }"),
                // Rendered into the message without exhausting the stack of the listener's worker thread.
                Arguments.of("array price nested 20000 deep", nestedPriceBody,
                        "{ \"message\": \"undefined method `*' for " + nestedPrice + "\" }"),
                Arguments.of("malformed JSON", "{\"item name\": ", "{ \"message\": \"Payload is not valid JSON\" }"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/customBusinessEvents/x", "/customBusinessEventsX", "/unknown"})
    void unservedPathReturnsNoListener404(String path) throws IOException {
        HttpTestClient.Response response =
                client().send("POST", path, Map.of("Content-Type", "application/json"), message());

        assertEquals(404, response.status(), response::toString);
        assertEquals("Not Found", response.reason(), response::toString);
        assertMediaType(response, MediaType.TEXT_PLAIN);
        assertEquals("No listener for endpoint: " + path, response.bodyAsString(), response::toString);
    }

    /** Asserts the 200 of shoes at 10: the text {@code 8.5} with its {@code Content-Length}. */
    private static void assertDiscountedPrice(HttpTestClient.Response response) {
        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertEquals("3", response.header("Content-Length"), response::toString);
        assertArrayEquals("8.5".getBytes(UTF_8), response.body(), response::toString);
    }

    /** Compares type and subtype only: the string converter of the error handler may append a charset. */
    private static void assertMediaType(HttpTestClient.Response response, MediaType expected) {
        String contentType = response.header("Content-Type");
        assertNotNull(contentType, response::toString);
        assertTrue(MediaType.parseMediaType(contentType).equalsTypeAndSubtype(expected),
                () -> "expected " + expected + " but was " + response);
    }

    /** The bytes of the ported {@code message.json} (shoes, 2 units, price 10), unchanged. */
    private static byte[] message() throws IOException {
        try (InputStream in = BusinessEventControllerTest.class.getResourceAsStream("/message.json")) {
            assertNotNull(in, "message.json is on the test classpath");
            return in.readAllBytes();
        }
    }
}
