/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.testing_apikit_with_munit.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;

import com.mulesoft.examples.testing_apikit_with_munit.service.MunitService;
import com.mulesoft.examples.testing_apikit_with_munit.support.HttpTestClient;

/**
 * Wire-level test of {@link MunitController}, the RAML resource {@code /munit} of {@code api/api.raml} served on
 * the original {@code api/*} listener (testing-apikit-with-munit/src/main/app/api.xml:38-60).
 *
 * <p>The whole application runs on Undertow on a random port and every request goes through ST-1
 * {@link HttpTestClient}, so the status code, the reason phrase, {@code Content-Type} and the body bytes are
 * asserted as the listener writes them:
 * <ul>
 *   <li>the four declared methods answer with the 2xx their RAML method declares (GET 200, POST 201, PUT 201,
 *       DELETE 200) and the flow payload as a JSON string, quotes included, exactly as the MUnit suite asserts
 *       (testing-apikit-with-munit/src/test/munit/api-test-suite.xml:15-45);</li>
 *   <li>the RAML declares no request body, so any body and media type is accepted and ignored;</li>
 *   <li>a method the resource does not declare, an {@code Accept} header excluding JSON and a path no resource
 *       serves under {@code /api} get APIkit's 405, 406 and 404 mappings with their literal bodies
 *       (api.xml:10-36);</li>
 *   <li>a path outside {@code /api}, which the original listener never served, gets the listener's no-listener
 *       404.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MunitControllerTest {

    private static final String BASE = "/api/munit";

    /** Body of APIkit's 404 mapping (api.xml:14). */
    private static final String NOT_FOUND = "{ \"message\": \"Resource not found\" }";

    /** Body of APIkit's 405 mapping (api.xml:19). */
    private static final String METHOD_NOT_ALLOWED = "{ \"message\": \"Method not allowed\" }";

    /** Body of APIkit's 406 mapping (api.xml:29). */
    private static final String NOT_ACCEPTABLE = "{ \"message\": \"Not acceptable\" }";

    /** The MUnit {@code munit:set payload="#['']"} the POST and PUT tests send: a zero-length body. */
    private static final byte[] EMPTY_BODY = new byte[0];

    @LocalServerPort
    private int port;

    private HttpTestClient client;

    @BeforeEach
    void createClient() {
        client = HttpTestClient.localhost(port);
    }

    /**
     * Each declared method: the method, the request body ({@code null} for none), the RAML status and Undertow's
     * reason phrase, the exact response body, and the raw flow literal of {@link MunitService} it must serialize.
     */
    private static Stream<Arguments> declaredMethods() {
        return Stream.of(
                Arguments.of("GET", null, 200, "OK", "\"GET RESPONSE\"", MunitService.GET_RESPONSE),
                Arguments.of("POST", EMPTY_BODY, 201, "Created", "\"POST RESPONSE\"", MunitService.POST_RESPONSE),
                Arguments.of("PUT", EMPTY_BODY, 201, "Created", "\"PUT RESPONSE\"", MunitService.PUT_RESPONSE),
                Arguments.of("DELETE", null, 200, "OK", "\"DELETE RESPONSE\"", MunitService.DELETE_RESPONSE));
    }

    @ParameterizedTest(name = "{0} " + BASE + " -> {2} {3} {4}")
    @MethodSource("declaredMethods")
    void declaredMethodReturnsRamlStatusAndFlowPayload(String method, byte[] body, int status, String reason,
                                                       String expectedBody, String flowLiteral) {
        HttpTestClient.Response response = client.send(method, BASE, null, body);

        assertJsonResponse(response, status, reason, expectedBody);
        // The RAML declares a body-carrying 2xx for every /munit method; a 204 would drop the flow payload.
        assertNotEquals(204, response.status(), response::toString);
        // The hard-coded literal above guards the wire bytes; this ties them to the flow constant as well.
        assertEquals("\"" + flowLiteral + "\"", response.bodyAsString(), response::toString);
    }

    /** POST and PUT with the JSON body each answers with. */
    private static Stream<Arguments> writeMethods() {
        return Stream.of(
                Arguments.of("POST", "\"POST RESPONSE\""),
                Arguments.of("PUT", "\"PUT RESPONSE\""));
    }

    @ParameterizedTest(name = "{0} " + BASE + " with an application/json body -> 201")
    @MethodSource("writeMethods")
    void postAndPutAcceptJsonBody(String method, String expectedBody) {
        HttpTestClient.Response response = client.send(method, BASE,
                Map.of("Content-Type", MediaType.APPLICATION_JSON_VALUE),
                "{\"a\":1}".getBytes(StandardCharsets.UTF_8));

        assertJsonResponse(response, 201, "Created", expectedBody);
    }

    @ParameterizedTest(name = "{0} " + BASE + " with a text/plain body -> 201")
    @MethodSource("writeMethods")
    void postAndPutAcceptTextPlainBody(String method, String expectedBody) {
        HttpTestClient.Response response = client.send(method, BASE,
                Map.of("Content-Type", MediaType.TEXT_PLAIN_VALUE),
                "hello".getBytes(StandardCharsets.UTF_8));

        assertJsonResponse(response, 201, "Created", expectedBody);
    }

    @ParameterizedTest(name = "GET " + BASE + " with Accept {0} -> 200")
    @ValueSource(strings = {MediaType.APPLICATION_JSON_VALUE, MediaType.ALL_VALUE})
    void getSucceedsWhenAcceptAllowsJson(String accept) {
        HttpTestClient.Response response = client.get(BASE, Map.of("Accept", accept));

        assertJsonResponse(response, 200, "OK", "\"GET RESPONSE\"");
    }

    @ParameterizedTest(name = "{0} " + BASE + " -> APIkit 405")
    @ValueSource(strings = {"PATCH", "OPTIONS"})
    void undeclaredMethodReturnsApiKit405(String method) {
        HttpTestClient.Response response = client.send(method, BASE, null, null);

        assertApiKitError(response, 405, "Method Not Allowed", METHOD_NOT_ALLOWED);
    }

    /**
     * HEAD goes through ST-1 like every other request: the client reads no body for a HEAD response, so the exchange
     * ends at the empty line after the headers. APIkit's 405 mapping answers, not a Spring default: the raw status
     * line, exactly one bare {@code application/json} {@code Content-Type} and no body bytes. The
     * {@code Content-Length} announced is the length of the 405 mapping's body, which is not sent.
     */
    @Test
    void headReturns405() {
        HttpTestClient.Response response = client.send("HEAD", BASE, null, null);

        assertEquals("HTTP/1.1 405 Method Not Allowed", response.statusLine(), response::toString);
        assertMediaType(response, MediaType.APPLICATION_JSON_VALUE);
        assertEquals(0, response.body().length, response::toString);
        assertEquals(List.of(String.valueOf(METHOD_NOT_ALLOWED.getBytes(StandardCharsets.UTF_8).length)),
                response.headers("Content-Length"), response::toString);
    }

    /** Each declared method with the request body it is sent with ({@code null} for none). */
    private static Stream<Arguments> declaredMethodsWithBodies() {
        return Stream.of(
                Arguments.of("GET", null),
                Arguments.of("POST", EMPTY_BODY),
                Arguments.of("PUT", EMPTY_BODY),
                Arguments.of("DELETE", null));
    }

    @ParameterizedTest(name = "{0} " + BASE + " with Accept text/xml -> APIkit 406")
    @MethodSource("declaredMethodsWithBodies")
    void notAcceptableReturns406(String method, byte[] body) {
        HttpTestClient.Response response = client.send(method, BASE, Map.of("Accept", MediaType.TEXT_XML_VALUE),
                body);

        assertApiKitError(response, 406, "Not Acceptable", NOT_ACCEPTABLE);
    }

    /** Every method and path under {@code /api} that no RAML resource serves. */
    private static Stream<Arguments> unservedPathsUnderApi() {
        return Stream.of(
                Arguments.of("GET", "/api/unknown", null),
                Arguments.of("POST", "/api/unknown", EMPTY_BODY),
                Arguments.of("PUT", "/api/unknown", EMPTY_BODY),
                Arguments.of("DELETE", "/api/unknown", null),
                Arguments.of("GET", BASE + "/extra", null));
    }

    @ParameterizedTest(name = "{0} {1} -> APIkit 404")
    @MethodSource("unservedPathsUnderApi")
    void unknownPathUnderApiReturnsApiKit404(String method, String path, byte[] body) {
        HttpTestClient.Response response = client.send(method, path, null, body);

        assertApiKitError(response, 404, "Not Found", NOT_FOUND);
    }

    @Test
    void pathOutsideApiReturnsNoListener404() {
        HttpTestClient.Response response = client.get("/other");

        assertEquals(404, response.status(), response::toString);
        assertEquals("Not Found", response.reason(), response::toString);
        assertMediaType(response, MediaType.TEXT_PLAIN_VALUE);
        assertArrayEquals("No listener for endpoint: /other".getBytes(StandardCharsets.UTF_8), response.body(),
                response::toString);
    }

    /** Status, reason phrase, {@code application/json} media type and the exact body bytes. */
    private static void assertJsonResponse(HttpTestClient.Response response, int status, String reason,
                                           String expectedBody) {
        assertEquals(status, response.status(), response::toString);
        assertEquals(reason, response.reason(), response::toString);
        assertMediaType(response, MediaType.APPLICATION_JSON_VALUE);
        assertArrayEquals(expectedBody.getBytes(StandardCharsets.UTF_8), response.body(), response::toString);
    }

    /** One of APIkit's error mappings, which all answer {@code application/json} with a literal body. */
    private static void assertApiKitError(HttpTestClient.Response response, int status, String reason,
                                          String body) {
        assertJsonResponse(response, status, reason, body);
    }

    /** Exactly one {@code Content-Type} header, equal to the bare {@code expected} with no parameter appended. */
    private static void assertMediaType(HttpTestClient.Response response, String expected) {
        assertEquals(List.of(expected), response.headers("Content-Type"), response::toString);
    }
}
