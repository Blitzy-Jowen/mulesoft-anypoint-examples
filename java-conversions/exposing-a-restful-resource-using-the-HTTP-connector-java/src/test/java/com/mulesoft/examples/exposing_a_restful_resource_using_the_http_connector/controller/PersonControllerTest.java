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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;

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
 * no-listener 404, the 405 outside each listener's {@code allowedMethods}, the JSON 500 of an unhandled failure).
 *
 * <p>Every test starts a fresh application context, so the store is empty and the id counter is at 0, as in a freshly
 * deployed original, and the first stored person always gets id {@code 1}.
 *
 * <p>Content-Types written by Spring's string converter are compared by type and subtype, because it adds the
 * {@code charset} parameter to {@code text/plain} but not to {@code application/json}. The success replies of
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

    /** {@code src/test/resources/person.json}, a byte copy of the original test input: one line, no line ending. */
    private static byte[] personJsonBytes;

    private static String personJson;

    @LocalServerPort
    private int port;

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

    // ---------------------------------------------------------------------------------------------------------------
    // GET /person (retrieveAllPersonsFlow)
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void getPersonsReturnsEmptyArrayWhenStoreIsEmpty() {
        HttpTestClient.Response response = client.get("/person");

        assertEquals(200, response.status(), response::toString);
        assertEquals("OK", response.reason(), response::toString);
        assertMediaType(MediaType.APPLICATION_JSON, response);
        assertEquals("[]", response.bodyAsString());
    }

    @Test
    void getPersonsReturnsStoredPerson() {
        postPerson();

        HttpTestClient.Response response = client.get("/person");

        assertEquals(200, response.status(), response::toString);
        assertMediaType(MediaType.APPLICATION_JSON, response);
        assertEquals("[" + personJson + "]", response.bodyAsString());
    }

    @Test
    void putPersonIsMethodNotAllowed() {
        HttpTestClient.Response response = client.put("/person", "application/json", personJson);

        assertEquals(405, response.status(), response::toString);
        assertMediaType(MediaType.TEXT_PLAIN, response);
        assertEquals("Method not allowed for endpoint: /person", response.bodyAsString());
    }

    @Test
    void headPersonIsMethodNotAllowed() {
        // ST-1 reads no body for HEAD, so the Content-Length of the 405 cannot make the read block.
        HttpTestClient.Response response = client.send("HEAD", "/person", null, null);

        assertEquals(405, response.status(), response::toString);
    }

    @Test
    void unservedPathReturnsNoListener404() {
        HttpTestClient.Response response = client.get("/people");

        assertEquals(404, response.status(), response::toString);
        assertMediaType(MediaType.TEXT_PLAIN, response);
        assertEquals("No listener for endpoint: /people", response.bodyAsString());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // POST /person (storePersonFlow)
    // ---------------------------------------------------------------------------------------------------------------

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

        assertEquals(405, response.status(), response::toString);
        assertMediaType(MediaType.TEXT_PLAIN, response);
        assertEquals("Method not allowed for endpoint: /person/1", response.bodyAsString());
    }

    @Test
    void optionsPersonIsMethodNotAllowed() {
        HttpTestClient.Response response = client.send("OPTIONS", "/person", Map.of(), null);

        assertEquals(405, response.status(), response::toString);
        assertMediaType(MediaType.TEXT_PLAIN, response);
        assertEquals("Method not allowed for endpoint: /person", response.bodyAsString());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // GET /person/{personId} (retrievePersonFlow)
    // ---------------------------------------------------------------------------------------------------------------

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
        assertEquals(500, response.status(), response::toString);
        assertMediaType(MediaType.APPLICATION_JSON, response);
        assertEquals("{ \"message\": \"For input string: \\\"abc\\\"\" }", response.bodyAsString());
    }

    @Test
    void headPersonItemIsMethodNotAllowed() {
        postPerson();

        HttpTestClient.Response response = client.send("HEAD", "/person/1", null, null);

        assertEquals(405, response.status(), response::toString);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------------

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
        assertEquals(500, response.status(), response::toString);
        assertMediaType(MediaType.APPLICATION_JSON, response);
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

    /** Asserts the response's Content-Type has the type and subtype of {@code expected}, whatever its parameters. */
    private static void assertMediaType(MediaType expected, HttpTestClient.Response response) {
        String contentType = response.header(HttpHeaders.CONTENT_TYPE);
        assertNotNull(contentType, () -> "no Content-Type in\n" + response);
        MediaType actual = MediaType.parseMediaType(contentType);
        assertTrue(actual.isCompatibleWith(expected), () -> "Content-Type " + contentType + " is not " + expected);
        assertEquals(expected.getType(), actual.getType(), contentType);
        assertEquals(expected.getSubtype(), actual.getSubtype(), contentType);
    }
}
