/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.filter.FormContentFilter;

import com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.service.PersonService;
import com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.support.HttpTestClient;

/**
 * Wire-level test of {@link PersonController}, the three HTTP listeners of
 * {@code exposing-a-restful-resource-using-the-HTTP-connector/src/main/app/http-restful-resource.xml}:
 * {@code retrieveAllPersonsFlow} ({@code GET /person}, line 11), {@code storePersonFlow} ({@code POST /person},
 * lines 15-25) and {@code retrievePersonFlow} ({@code GET /person/{personId}}, lines 27-43).
 *
 * <p>The whole application runs on its own Undertow listener with every bean real: the controller, the in-memory
 * store of {@link PersonService}, {@code exception/PersonExceptionHandler} with SC-2 {@code config/ReasonPhrase},
 * and the SC-1 {@code exception/GlobalExceptionHandler}. Requests go through ST-1 {@link HttpTestClient}, which reads
 * the raw status line from the socket, so status code, reason phrase, headers and body bytes are asserted as the
 * listener writes them.
 *
 * <p>Each endpoint is covered by its happy path and at least two error cases: the original's own branches (the
 * rollback branch's 404 {@code Not Found}, AAP 0.7.5) and the listener-level platform defaults of AAP 0.4.7 (the
 * no-listener 404, the 405 outside each listener's {@code allowedMethods}, the JSON 500 of an unhandled failure). The
 * 405 also arrives before a large form body has been sent, since the context holds no {@link FormContentFilter} that
 * would read it.
 *
 * <p>Every test starts a fresh application context, so the store is empty and the id counter is at 0, as in a freshly
 * deployed original, and the first stored person always gets id {@code 1}.
 *
 * <p>Content-Type is asserted as exactly one header carrying its complete value, so a duplicated header or an added
 * parameter such as {@code charset} fails: the platform defaults send bare {@code text/plain} and
 * {@code application/json}, and error cases assert the raw status line. The success replies of
 * {@code storePersonFlow} and {@code retrievePersonFlow}, whose untyped {@code set-payload}s the Mule listener sent
 * without a Content-Type, are asserted to carry none, with a {@code Content-Length} equal to their body bytes.
 *
 * <p>The original {@code HttpRestfulResourceIT} assertions are ported separately in {@code HttpRestfulResourceTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class PersonControllerTest {

    /** The {@code set-payload} of {@code storePersonFlow} (line 24), as the original test asserts it. */
    private static final String REPLY =
            "{ \"status\": \"success\", \"statusDescription\": \"person created successfully\"}";

    /** The port the original writes literally into the {@code Location} header (line 18). */
    private static final int ORIGINAL_LOCATION_PORT = 8081;

    private static final Map<String, String> JSON_CONTENT_TYPE = Map.of(HttpHeaders.CONTENT_TYPE, "application/json");

    /** A form body large enough that reading it whole before dispatch keeps the response back measurably. */
    private static final int LARGE_FORM_BYTES = 300_000;

    /** Bytes of a large form body written before its response is read; the rest is written only afterwards. */
    private static final int LARGE_FORM_PREFIX_BYTES = 1024;

    /** {@code src/test/resources/person.json}, a byte copy of the original test input: one line, no line ending. */
    private static byte[] personJsonBytes;

    private static String personJson;

    @LocalServerPort
    private int port;

    /** The running application's context, inspected for the servlet filters it registers. */
    @Autowired
    private ApplicationContext applicationContext;

    private HttpTestClient client;

    @BeforeAll
    static void loadPerson() throws IOException {
        try (InputStream in = PersonControllerTest.class.getResourceAsStream("/person.json")) {
            assertNotNull(in, "person.json is missing from the test classpath");
            personJsonBytes = in.readAllBytes();
        }
        assertEquals(145, personJsonBytes.length, "person.json must stay the original's 145 bytes");
        personJson = new String(personJsonBytes, StandardCharsets.UTF_8);
    }

    @BeforeEach
    void createClient() {
        client = HttpTestClient.localhost(port);
    }

    // GET /person (retrieveAllPersonsFlow)

    @Test
    void getPersonsReturnsEmptyArrayWhenStoreIsEmpty() {
        HttpTestClient.Response response = client.get("/person");

        assertEquals(200, response.status(), response::toString);
        assertEquals("OK", response.reason(), response::toString);
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertEquals("[]", response.bodyAsString());
    }

    @Test
    void getPersonsReturnsStoredPerson() {
        postPerson();

        HttpTestClient.Response response = client.get("/person");

        assertEquals(200, response.status(), response::toString);
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertEquals("[" + personJson + "]", response.bodyAsString());
    }

    @Test
    void putPersonIsMethodNotAllowed() {
        HttpTestClient.Response response = client.put("/person", "application/json", personJson);

        assertEquals("HTTP/1.1 405 Method Not Allowed", response.statusLine(), response::toString);
        assertContentType(response, MediaType.TEXT_PLAIN_VALUE);
        assertEquals("Method not allowed for endpoint: /person", response.bodyAsString());
    }

    @Test
    void headPersonIsMethodNotAllowed() {
        // ST-1 reads no body for HEAD, so the Content-Length of the 405 cannot make the read block.
        HttpTestClient.Response response = client.send("HEAD", "/person", null, null);

        assertEquals("HTTP/1.1 405 Method Not Allowed", response.statusLine(), response::toString);
        assertContentType(response, MediaType.TEXT_PLAIN_VALUE);
        assertEquals(0, response.body().length, response::toString);
    }

    @Test
    void unservedPathReturnsNoListener404() {
        HttpTestClient.Response response = client.get("/people");

        assertEquals("HTTP/1.1 404 Not Found", response.statusLine(), response::toString);
        assertContentType(response, MediaType.TEXT_PLAIN_VALUE);
        assertEquals("No listener for endpoint: /people", response.bodyAsString());
    }

    // POST /person (storePersonFlow)

    @Test
    void postPersonReturnsCreatedWithLocation() {
        HttpTestClient.Response response = client.send("POST", "/person", JSON_CONTENT_TYPE, personJsonBytes);

        assertEquals(201, response.status(), response::toString);
        assertEquals("Created", response.reason(), response::toString);

        String location = response.header(HttpHeaders.LOCATION);
        assertEquals("http://0.0.0.0:8081/person/1", location, response::toString);
        assertEquals(PersonController.LOCATION_PREFIX + "1", location);
        // The listener runs on a random port, so a Location naming 8081 proves the literal is kept.
        assertNotEquals(ORIGINAL_LOCATION_PORT, port, "the random listener port must differ from the literal one");
        assertFalse(location.contains(":" + port + "/"), () -> "Location must not name the listener port: " + location);

        assertUntypedPayload(REPLY.getBytes(StandardCharsets.UTF_8), response);
        assertEquals(PersonService.STORE_SUCCESS_RESPONSE, response.bodyAsString());

        // personId is the store's counter after incrementAndGet (line 22): the next person gets the next id.
        HttpTestClient.Response second = client.send("POST", "/person", JSON_CONTENT_TYPE, personJsonBytes);
        assertEquals(201, second.status(), second::toString);
        assertEquals(PersonController.LOCATION_PREFIX + "2", second.header(HttpHeaders.LOCATION));
    }

    @Test
    void postPersonAcceptsTextPlainContentType() {
        // The Mule listener checked no request Content-Type, so a person posted as text is stored too.
        HttpTestClient.Response response = client.post("/person", "text/plain", personJson);

        assertEquals(201, response.status(), response::toString);
        assertEquals(PersonController.LOCATION_PREFIX + "1", response.header(HttpHeaders.LOCATION));
        assertEquals(REPLY, response.bodyAsString());
    }

    @Test
    void postPersonIgnoresAcceptHeader() {
        // The Mule listener checked no Accept header, so the untyped reply is written whatever the caller accepts.
        HttpTestClient.Response response = client.post("/person", "application/json", personJson,
                Map.of(HttpHeaders.ACCEPT, "application/xml"));

        assertEquals(201, response.status(), response::toString);
        assertUntypedPayload(REPLY.getBytes(StandardCharsets.UTF_8), response);
    }

    @Test
    void postMalformedJsonReturns500() {
        HttpTestClient.Response response = client.post("/person", "application/json", "{");

        assertUnhandledFailure(response);

        // The body is converted before the counter is incremented (lines 21-22), so the failure consumed no id.
        HttpTestClient.Response stored = client.send("POST", "/person", JSON_CONTENT_TYPE, personJsonBytes);
        assertEquals(201, stored.status(), stored::toString);
        assertEquals(PersonController.LOCATION_PREFIX + "1", stored.header(HttpHeaders.LOCATION));
    }

    @Test
    void postUnknownPropertyReturns500() {
        HttpTestClient.Response response =
                client.post("/person", "application/json", "{\"firstname\":\"Tito\",\"nickname\":\"x\"}");

        assertUnhandledFailure(response);
        assertEquals("[]", client.get("/person").bodyAsString(), "a rejected person must not be stored");
    }

    @Test
    void postToPersonItemIsMethodNotAllowed() {
        HttpTestClient.Response response = client.send("POST", "/person/1", JSON_CONTENT_TYPE, personJsonBytes);

        assertEquals("HTTP/1.1 405 Method Not Allowed", response.statusLine(), response::toString);
        assertContentType(response, MediaType.TEXT_PLAIN_VALUE);
        assertEquals("Method not allowed for endpoint: /person/1", response.bodyAsString());
    }

    @Test
    void optionsPersonIsMethodNotAllowed() {
        HttpTestClient.Response response = client.send("OPTIONS", "/person", Map.of(), null);

        assertEquals("HTTP/1.1 405 Method Not Allowed", response.statusLine(), response::toString);
        assertContentType(response, MediaType.TEXT_PLAIN_VALUE);
        assertEquals("Method not allowed for endpoint: /person", response.bodyAsString());
    }

    // GET /person/{personId} (retrievePersonFlow)

    @Test
    void getPersonReturnsStoredPerson() {
        postPerson();

        HttpTestClient.Response response = client.get("/person/1");

        assertEquals(200, response.status(), response::toString);
        assertEquals("OK", response.reason(), response::toString);
        assertUntypedPayload(personJsonBytes, response);
    }

    @Test
    void getMissingPersonReturns404NotFound() {
        postPerson();

        HttpTestClient.Response response = client.get("/person/999");

        // The rollback branch's errorStatusCode and errorReasonPhrase (lines 39-40) on the status line.
        assertEquals("HTTP/1.1 404 Not Found", response.statusLine(), response::toString);
        assertEquals(404, response.status());
        assertEquals("Not Found", response.reason());
        assertEquals(0, response.body().length, response::toString);
        assertNull(response.header(HttpHeaders.CONTENT_TYPE), response::toString);
    }

    @Test
    void getNonNumericPersonIdReturns500() {
        HttpTestClient.Response response = client.get("/person/abc");

        // Integer.valueOf (line 31) fails, and the choice exception strategy has no branch for it.
        assertEquals("HTTP/1.1 500 Internal Server Error", response.statusLine(), response::toString);
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertEquals("{ \"message\": \"For input string: \\\"abc\\\"\" }", response.bodyAsString());
    }

    @Test
    void headPersonItemIsMethodNotAllowed() {
        postPerson();

        HttpTestClient.Response response = client.send("HEAD", "/person/1", null, null);

        assertEquals("HTTP/1.1 405 Method Not Allowed", response.statusLine(), response::toString);
        assertContentType(response, MediaType.TEXT_PLAIN_VALUE);
        assertEquals(0, response.body().length, response::toString);
    }

    // Large form bodies on rejected methods

    /**
     * Both listeners checked the method before they read the body, so a large form body on a method outside their
     * {@code allowedMethods} is never read: a form declaring {@value #LARGE_FORM_BYTES} bytes, of which only the first
     * {@value #LARGE_FORM_PREFIX_BYTES} are written, gets the whole 405 before the rest is sent, and nothing is
     * stored. Boot's form-content filter, which {@code spring.mvc.formcontent.filter.enabled: false} switches off,
     * would read the whole body into memory before dispatch, so no response would arrive until the last byte had been
     * sent.
     *
     * @param method a method whose form body Boot's form-content filter would read
     * @param path   the collection listener's path or the item listener's path with an id
     * @throws IOException when the connection fails
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("largeFormRequests")
    void largeFormOnMethodOutsideAllowedMethodsIsMethodNotAllowedBeforeItsBodyIsSent(String method, String path)
            throws IOException {
        byte[] form = ("name=" + "x".repeat(LARGE_FORM_BYTES - "name=".length())).getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), HttpTestClient.TIMEOUT_MILLIS);
            socket.setSoTimeout(HttpTestClient.TIMEOUT_MILLIS);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            out.write((method + " " + path + " HTTP/1.1\r\nHost: localhost:" + port + "\r\n" + HttpHeaders.CONTENT_TYPE
                    + ": " + MediaType.APPLICATION_FORM_URLENCODED_VALUE + "\r\n" + HttpHeaders.CONTENT_LENGTH + ": "
                    + form.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(form, 0, LARGE_FORM_PREFIX_BYTES);
            out.flush();

            RawResponse refused;
            try {
                refused = readResponse(in);
            } catch (SocketTimeoutException timeout) {
                refused = fail(String.format("No response to %s %s within %d ms while %d of its %d form bytes were "
                        + "sent: the body was buffered before dispatch", method, path, HttpTestClient.TIMEOUT_MILLIS,
                        LARGE_FORM_PREFIX_BYTES, form.length), timeout);
            }
            writeRemainderIgnoringFailure(out, form, LARGE_FORM_PREFIX_BYTES);

            assertEquals("HTTP/1.1 405 Method Not Allowed", refused.statusLine(), refused::toString);
            assertEquals(List.of(MediaType.TEXT_PLAIN_VALUE), refused.headers(HttpHeaders.CONTENT_TYPE),
                    refused::toString);
            assertArrayEquals(("Method not allowed for endpoint: " + path).getBytes(StandardCharsets.UTF_8),
                    refused.body(), refused::toString);
        }
        assertEquals("[]", client.get("/person").bodyAsString(), "the rejected request must store nothing");
    }

    private static Stream<Arguments> largeFormRequests() {
        return Stream.of("PUT", "PATCH", "DELETE").flatMap(method ->
                Stream.of("/person", "/person/1").map(path -> Arguments.of(method, path)));
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

    // Paths carrying ; segment parameters (config/RawPathSegmentConfig)

    /**
     * A listener path whose literal segment carries {@code ;} parameters is not the listener's path: the Mule
     * listener compared the raw path, query removed, segment by segment, so every method gets the no-listener 404
     * naming the path with its parameters, and no flow runs. {@code /person/}, which the item listener's alias
     * {@link PersonController#EMPTY_ID_ITEM_PATH} matches although it has no id segment, and {@code /person//} get
     * the same 404. With one person stored first, the collection still holds only that person and the next
     * {@code POST /person} gets id {@code 2}, so the rejected request stored nothing and consumed no id.
     *
     * @param method the request method; {@code POST} carries {@code person.json} as {@code application/json}
     * @param path   a listener path whose literal {@code person} segment carries {@code ;} parameters, or the item
     *               listener's path with an absent or empty id segment
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("unservedListenerPathRequests")
    void unservedListenerPathReturnsNoListener404(String method, String path) {
        postPerson();
        boolean withBody = "POST".equals(method);

        HttpTestClient.Response response = client.send(method, path, withBody ? JSON_CONTENT_TYPE : null,
                withBody ? personJsonBytes : null);

        assertEquals("HTTP/1.1 404 Not Found", response.statusLine(), response::toString);
        assertContentType(response, MediaType.TEXT_PLAIN_VALUE);
        // ST-1 reads no body for HEAD.
        String body = "HEAD".equals(method) ? "" : "No listener for endpoint: " + path;
        assertEquals(body, response.bodyAsString(), response::toString);

        assertEquals("[" + personJson + "]", client.get("/person").bodyAsString(),
                "the rejected request must store nothing");
        HttpTestClient.Response next = client.send("POST", "/person", JSON_CONTENT_TYPE, personJsonBytes);
        assertEquals(PersonController.LOCATION_PREFIX + "2", next.header(HttpHeaders.LOCATION),
                "the rejected request must consume no id");
    }

    private static Stream<Arguments> unservedListenerPathRequests() {
        return Stream.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS").flatMap(method ->
                Stream.of("/person;x=1", "/person;jsessionid=ABC", "/person;", "/person;x=1/1", "/person/", "/person//")
                        .map(path -> Arguments.of(method, path)));
    }

    /**
     * The {@code {personId}} segment is bound whole, its {@code ;} parameters included, as the Mule listener bound
     * the raw segment, also when it is empty before its {@code ;}: {@code Integer.valueOf} rejects the id although
     * person {@code 1} is stored, and no exception branch matches, so SC-1 answers its 500. The collection still holds
     * only that person and the next {@code POST /person} gets id {@code 2}.
     *
     * @param path     the item listener's path with an id segment carrying {@code ;} parameters
     * @param personId the raw id segment the flow receives
     */
    @ParameterizedTest(name = "GET {0}")
    @CsvSource({"/person/1;x=1, 1;x=1", "/person/;x=1, ;x=1", "/person/;, ;"})
    void personIdSegmentWithParametersIsBoundWholeAndReturns500(String path, String personId) {
        postPerson();

        HttpTestClient.Response response = client.get(path);

        assertEquals("HTTP/1.1 500 Internal Server Error", response.statusLine(), response::toString);
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertEquals("{ \"message\": \"For input string: \\\"" + personId + "\\\"\" }", response.bodyAsString());
        assertEquals("[" + personJson + "]", client.get("/person").bodyAsString(), "the request must store nothing");
        HttpTestClient.Response next = client.send("POST", "/person", JSON_CONTENT_TYPE, personJsonBytes);
        assertEquals(PersonController.LOCATION_PREFIX + "2", next.header(HttpHeaders.LOCATION),
                "the request must consume no id");
    }

    /**
     * A {@code {personId}} segment carrying {@code ;} parameters, also one empty before its {@code ;}, still matches
     * the item listener's path, so a method outside its {@code allowedMethods} gets the listener's 405 naming the
     * path with its parameters, not the 404; nothing is stored and the next {@code POST /person} gets id {@code 1}.
     *
     * @param method a method {@code retrievePersonFlow} does not accept; {@code POST} carries {@code person.json}
     * @param path   the item listener's path with an id segment carrying {@code ;} parameters
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("itemListenerRejectedRequests")
    void personIdSegmentWithParametersKeepsTheItemListener405(String method, String path) {
        boolean withBody = "POST".equals(method);

        HttpTestClient.Response response = client.send(method, path, withBody ? JSON_CONTENT_TYPE : null,
                withBody ? personJsonBytes : null);

        assertEquals("HTTP/1.1 405 Method Not Allowed", response.statusLine(), response::toString);
        assertContentType(response, MediaType.TEXT_PLAIN_VALUE);
        // ST-1 reads no body for HEAD.
        String body = "HEAD".equals(method) ? "" : "Method not allowed for endpoint: " + path;
        assertEquals(body, response.bodyAsString(), response::toString);
        assertEquals("[]", client.get("/person").bodyAsString(), "the rejected request must store nothing");
        HttpTestClient.Response next = client.send("POST", "/person", JSON_CONTENT_TYPE, personJsonBytes);
        assertEquals(PersonController.LOCATION_PREFIX + "1", next.header(HttpHeaders.LOCATION),
                "the rejected request must consume no id");
    }

    private static Stream<Arguments> itemListenerRejectedRequests() {
        return Stream.of("POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS").flatMap(method ->
                Stream.of("/person/1;x=1", "/person/;x=1").map(path -> Arguments.of(method, path)));
    }

    @Test
    void semicolonInQueryStringIsNotASegmentParameter() {
        HttpTestClient.Response response = client.get("/person?a=1;b");

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertEquals("[]", response.bodyAsString());
    }

    // Helpers

    /** Stores {@code person.json} through {@code POST /person}; it gets the next id, {@code 1} in a fresh context. */
    private void postPerson() {
        HttpTestClient.Response response = client.send("POST", "/person", JSON_CONTENT_TYPE, personJsonBytes);
        assertEquals(201, response.status(), response::toString);
    }

    /**
     * Asserts the SC-1 500 for an exception no flow branch handles: {@code application/json} and APIkit's
     * {@code { "message": "<exception message>" }} spacing. The message itself is Jackson's, which names the parser
     * location, so only the envelope is compared.
     */
    private static void assertUnhandledFailure(HttpTestClient.Response response) {
        assertEquals("HTTP/1.1 500 Internal Server Error", response.statusLine(), response::toString);
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        String body = response.bodyAsString();
        assertTrue(body.startsWith("{ \"message\": \""), () -> "unexpected 500 body: " + body);
        assertTrue(body.endsWith("\" }"), () -> "unexpected 500 body: " + body);
    }

    /**
     * Asserts the reply of a flow ending in an untyped {@code set-payload}: no {@code Content-Type} header at all, a
     * single {@code Content-Length} equal to the body's byte count, and exactly the {@code expected} body bytes.
     */
    private static void assertUntypedPayload(byte[] expected, HttpTestClient.Response response) {
        assertEquals(List.of(), response.headers(HttpHeaders.CONTENT_TYPE), response::toString);
        assertEquals(List.of(String.valueOf(expected.length)), response.headers(HttpHeaders.CONTENT_LENGTH),
                response::toString);
        assertArrayEquals(expected, response.body(), response::toString);
    }

    /** Exactly one {@code Content-Type} header, equal to {@code expected} with no parameter appended. */
    private static void assertContentType(HttpTestClient.Response response, String expected) {
        assertEquals(List.of(expected), response.headers(HttpHeaders.CONTENT_TYPE), response::toString);
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
        List<String> contentLength = head.headers(HttpHeaders.CONTENT_LENGTH);
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
