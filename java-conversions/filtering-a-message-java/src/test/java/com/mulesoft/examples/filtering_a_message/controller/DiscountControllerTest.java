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
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.web.filter.FormContentFilter;

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
 *       flow, every other method gets SC-1's 405, also before a large form body has been sent, since the context
 *       holds no {@link FormContentFilter} that would read it first, and every other path SC-1's no-listener 404;</li>
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

    /** A form body large enough that reading it whole before dispatch keeps the response back measurably. */
    private static final int LARGE_FORM_BYTES = 300_000;

    /** Bytes of a large form body written before its response is read; the rest is written only afterwards. */
    private static final int LARGE_FORM_PREFIX_BYTES = 1024;

    @LocalServerPort
    private int port;

    /** The running application's context, inspected for the servlet filters it registers. */
    @Autowired
    private ApplicationContext applicationContext;

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

    /**
     * The listener checked the method before it read the body, so a large form body on a method outside
     * {@code allowedMethods} is never read: a form declaring {@value #LARGE_FORM_BYTES} bytes, of which only the first
     * {@value #LARGE_FORM_PREFIX_BYTES} are written, gets SC-1's whole 405 before the rest is sent. Boot's
     * form-content filter, which {@code spring.mvc.formcontent.filter.enabled: false} switches off, would read the
     * whole body into memory before dispatch, so no response would arrive until the last byte had been sent.
     *
     * @param method a method whose form body Boot's form-content filter would read
     * @throws IOException when the connection fails
     */
    @ParameterizedTest
    @ValueSource(strings = {"PUT", "PATCH", "DELETE"})
    void largeFormOnMethodOtherThanPostReturns405BeforeItsBodyIsSent(String method) throws IOException {
        byte[] form = utf8("months=" + "1".repeat(LARGE_FORM_BYTES - "months=".length()));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), HttpTestClient.TIMEOUT_MILLIS);
            socket.setSoTimeout(HttpTestClient.TIMEOUT_MILLIS);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            out.write((method + " " + LISTENER_PATH + " HTTP/1.1\r\nHost: localhost:" + port
                    + "\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: " + form.length
                    + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(form, 0, LARGE_FORM_PREFIX_BYTES);
            out.flush();

            RawResponse refused;
            try {
                refused = readResponse(in);
            } catch (SocketTimeoutException timeout) {
                refused = fail(String.format("No response to %s %s within %d ms while %d of its %d form bytes were "
                        + "sent: the body was buffered before dispatch", method, LISTENER_PATH,
                        HttpTestClient.TIMEOUT_MILLIS, LARGE_FORM_PREFIX_BYTES, form.length), timeout);
            }
            writeRemainderIgnoringFailure(out, form, LARGE_FORM_PREFIX_BYTES);

            assertEquals("HTTP/1.1 405 Method Not Allowed", refused.statusLine(), refused::toString);
            assertEquals(List.of("text/plain"), refused.headers("Content-Type"), refused::toString);
            assertArrayEquals(utf8("Method not allowed for endpoint: " + LISTENER_PATH), refused.body(),
                    refused::toString);
        }
    }

    /**
     * The application registers no {@link FormContentFilter}, the filter that reads a {@code PUT}, {@code PATCH} or
     * {@code DELETE} form body before dispatch, because {@code spring.mvc.formcontent.filter.enabled} is
     * {@code false}.
     */
    @Test
    void contextHoldsNoFormContentFilter() {
        assertArrayEquals(new String[0], applicationContext.getBeanNamesForType(FormContentFilter.class),
                "FormContentFilter beans");
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

    /**
     * Writes the bytes of {@code body} from {@code offset} to its end once the response has been read, so the server
     * can finish the exchange; a failed write is ignored, because the server may close a connection whose body it
     * refused unread.
     */
    private static void writeRemainderIgnoringFailure(OutputStream out, byte[] body, int offset) {
        try {
            out.write(body, offset, body.length - offset);
            out.flush();
        } catch (IOException closedByServer) {
            // The response is already read; the server need not take the rest of a body it never reads.
        }
    }

    /**
     * Reads one response off the connection: its status line, its header lines and the {@code Content-Length} bytes
     * of its body.
     */
    private static RawResponse readResponse(InputStream in) throws IOException {
        String statusLine = readLine(in);
        List<String> headerLines = new ArrayList<>();
        for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
            headerLines.add(line);
        }
        RawResponse head = new RawResponse(statusLine, headerLines, new byte[0]);
        List<String> contentLength = head.headers("Content-Length");
        assertEquals(1, contentLength.size(), head::toString);
        int length = Integer.parseInt(contentLength.get(0));
        byte[] body = in.readNBytes(length);
        assertEquals(length, body.length, head::toString);
        return new RawResponse(statusLine, headerLines, body);
    }

    /** One line of a response head without its CRLF; fails when the connection ends first. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int c = in.read(); c != '\n'; c = in.read()) {
            if (c < 0) {
                throw new EOFException("connection closed in a response head after \"" + line + "\"");
            }
            if (c != '\r') {
                line.append((char) c);
            }
        }
        return line.toString();
    }

    /**
     * A response read off a raw connection.
     *
     * @param statusLine  the status line, such as {@code HTTP/1.1 405 Method Not Allowed}
     * @param headerLines the header lines in arrival order
     * @param body        the body bytes
     */
    private record RawResponse(String statusLine, List<String> headerLines, byte[] body) {

        /** The trimmed values, in arrival order, of the header lines named {@code name}, compared ignoring case. */
        List<String> headers(String name) {
            List<String> values = new ArrayList<>();
            for (String line : headerLines) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                    values.add(line.substring(colon + 1).trim());
                }
            }
            return values;
        }

        @Override
        public String toString() {
            return statusLine + " " + headerLines + " " + new String(body, StandardCharsets.UTF_8);
        }
    }
}
