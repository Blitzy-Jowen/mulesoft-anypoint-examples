/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.addition_using_javascript_transformer.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.mulesoft.examples.addition_using_javascript_transformer.support.HttpTestClient;

/**
 * Wire-level test of {@link CalculatorController}, the HTTP trigger of flow {@code javascript-calculatorFlow1}
 * (addition-using-javascript-transformer/src/main/app/javascript-calculator.xml:5-15), on the application's own
 * Undertow listener with the real {@code AdditionService} and SC-1 {@code GlobalExceptionHandler}.
 *
 * <p>Requests go through the project's ST-1 {@link HttpTestClient}, which reads the raw status line from the socket,
 * so the reason phrase, the {@code Content-Type} and {@code Content-Length} headers and the body bytes are asserted
 * exactly as the listener writes them. Each scenario of {@code src/test/resources/fixtures/SCENARIOS.md} is sent with
 * the request its row names, and its label starts with the scenario name:
 * <ul>
 *   <li>the happy paths {@code post-root-sum} (the original {@code AdditionUsingJavascriptTransformerIT} message),
 *       {@code post-root-readme-example}, {@code post-root-empty-object} and {@code post-root-string-value};</li>
 *   <li>the error cases {@code post-root-malformed-body} (the platform 500, as the flow declares no exception
 *       strategy), {@code get-root-method-not-allowed} (the listener's {@code allowedMethods="POST"}) and
 *       {@code post-unknown-path} (the no-listener 404).</li>
 * </ul>
 * The listener-level behaviour of the controller is covered as well: {@code POST} in any letter case, the charset the
 * {@code Content-Type} declares, the ignored {@code Accept} header, the form-urlencoded parameter-map payload of the
 * Mule listener with its 400 for an undecodable body, and the 405 for every other method whatever its body.
 *
 * <p>The original assertion is ported separately in {@code AdditionUsingJavascriptTransformerTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CalculatorControllerTest {

    private static final String ROOT = "/";

    /** The message of {@code AdditionUsingJavascriptTransformerIT}. */
    private static final String ORIGINAL_MESSAGE = "{ \"a\" : 1, \"b\": 2 }";

    /** The message of the original README's run step. */
    private static final String README_MESSAGE = "{ \"a\": 3, \"b\": 4 }";

    /** The body of scenario {@code post-root-malformed-body}: seven characters, ending with a space. */
    private static final String MALFORMED_MESSAGE = "{ \"a\": ";

    /** A string value the script's {@code +} concatenates, one non-ASCII character included. */
    private static final String ACCENTED_MESSAGE = "{ \"a\": 1, \"b\": \"\u00e9\" }";

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    private static final String TEXT_PLAIN_UTF8 = "text/plain;charset=UTF-8";

    private static final String FORM = MediaType.APPLICATION_FORM_URLENCODED_VALUE;

    private static final String METHOD_NOT_ALLOWED = "Method not allowed for endpoint: /";

    private static final String ERROR_BODY_START = "{ \"message\": \"";

    private static final String ERROR_BODY_END = "\" }";

    @LocalServerPort
    private int port;

    private HttpTestClient client;

    @BeforeEach
    void createClient() {
        client = HttpTestClient.localhost(port);
    }

    /**
     * Every scenario body is sent with the {@code application/json} its row names and with {@code text/plain}; the
     * listener accepted any {@code Content-Type}, so the script's sum is the same for both and without one.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("scriptSums")
    void postRootAnswersTheScriptSum(String label, String contentType, String body, String expected) {
        assertSum(client.post(ROOT, contentType, body), expected);
    }

    private static Stream<Arguments> scriptSums() {
        return Stream.of(
                sum("post-root-sum", JSON, ORIGINAL_MESSAGE, "Sum is: 3.0."),
                sum("post-root-sum", TEXT_PLAIN_UTF8, ORIGINAL_MESSAGE, "Sum is: 3.0."),
                sum("post-root-sum", null, ORIGINAL_MESSAGE, "Sum is: 3.0."),
                sum("post-root-readme-example", JSON, README_MESSAGE, "Sum is: 7.0."),
                sum("post-root-readme-example", TEXT_PLAIN_UTF8, README_MESSAGE, "Sum is: 7.0."),
                // The loop visits no property, so the sum stays the Double 0.
                sum("post-root-empty-object", JSON, "{}", "Sum is: 0.0."),
                sum("post-root-empty-object", TEXT_PLAIN_UTF8, "{}", "Sum is: 0.0."),
                // 0 + 1 is the number 1; 1 + "x" concatenates JavaScript's ToString(1) with "x".
                sum("post-root-string-value", JSON, "{ \"a\": 1, \"b\": \"x\" }", "Sum is: 1x."),
                sum("post-root-string-value", TEXT_PLAIN_UTF8, "{ \"a\": 1, \"b\": \"x\" }", "Sum is: 1x."));
    }

    private static Arguments sum(String scenario, String contentType, String body, String expected) {
        String label = scenario + ": " + (contentType == null ? "no Content-Type" : "Content-Type " + contentType);
        return Arguments.of(label, contentType, body, expected);
    }

    /** The Mule listener compared the method with {@code allowedMethods} ignoring case. */
    @ParameterizedTest
    @ValueSource(strings = {"post", "Post"})
    void postInAnyLetterCaseIsAccepted(String method) {
        HttpTestClient.Response response = client.send(method, ROOT, Map.of(HttpHeaders.CONTENT_TYPE, JSON),
                ORIGINAL_MESSAGE.getBytes(StandardCharsets.UTF_8));

        assertSum(response, "Sum is: 3.0.");
    }

    /**
     * The body is decoded with the charset its {@code Content-Type} declares, and with UTF-8 when it declares none or
     * one that does not exist; the payload is always written as UTF-8.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("declaredCharsets")
    void bodyIsDecodedWithTheDeclaredCharset(String label, String contentType, byte[] body) {
        HttpTestClient.Response response = client.send("POST", ROOT, Map.of(HttpHeaders.CONTENT_TYPE, contentType),
                body);

        assertSum(response, "Sum is: 1\u00e9.");
    }

    private static Stream<Arguments> declaredCharsets() {
        return Stream.of(
                Arguments.of("ISO-8859-1 declared", "text/plain;charset=ISO-8859-1",
                        ACCENTED_MESSAGE.getBytes(StandardCharsets.ISO_8859_1)),
                Arguments.of("no charset declared", JSON, ACCENTED_MESSAGE.getBytes(StandardCharsets.UTF_8)),
                Arguments.of("unknown charset declared", "text/plain;charset=no-such-charset",
                        ACCENTED_MESSAGE.getBytes(StandardCharsets.UTF_8)));
    }

    /** The listener ignored {@code Accept}, so no content negotiation can turn the reply into a 406. */
    @Test
    void acceptHeaderIsIgnored() {
        HttpTestClient.Response response = client.post(ROOT, JSON, ORIGINAL_MESSAGE,
                Map.of(HttpHeaders.ACCEPT, MediaType.APPLICATION_XML_VALUE));

        assertSum(response, "Sum is: 3.0.");
    }

    /**
     * A form-urlencoded body reaches the script as the Mule listener's parameter map, rendered as text: a body holding
     * only separators decodes to no parameter and renders as {@code {}}. Getting its sum, rather than the 500 of an
     * empty body, shows the controller read the raw stream before any servlet form parsing could consume it.
     */
    @Test
    void formBodyWithoutParametersSumsTheEmptyParameterMap() {
        assertSum(client.post(ROOT, FORM, "&"), "Sum is: 0.0.");
    }

    /** The Mule listener rejected a form body with a malformed percent escape before the flow ran. */
    @Test
    void undecodableFormBodyGetsTheListener400() {
        HttpTestClient.Response response = client.post(ROOT, FORM, "a=%zz");

        byte[] expected = "Cannot decode x-www-form-urlencoded payload".getBytes(StandardCharsets.UTF_8);
        assertEquals("HTTP/1.1 400 Bad Request", response.statusLine(), response::toString);
        assertNull(response.header(HttpHeaders.CONTENT_TYPE), response::toString);
        assertEquals(String.valueOf(expected.length), response.header(HttpHeaders.CONTENT_LENGTH),
                response::toString);
        assertArrayEquals(expected, response.body(), response::toString);
    }

    /**
     * Every method outside the listener's {@code allowedMethods="POST"}, sent without a body, gets its 405; scenario
     * {@code get-root-method-not-allowed} is the {@code GET}. {@code OPTIONS} is included, which Spring would
     * otherwise answer itself with 200 and an {@code Allow} header, and {@code TRACE}, which the servlet would
     * otherwise echo.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedMethods")
    void methodOutsideAllowedMethodsGetsTheListener405(String label, String method) {
        assertMethodNotAllowed(client.send(method, ROOT, null, null));
    }

    private static Stream<Arguments> rejectedMethods() {
        return Stream.of(
                Arguments.of("get-root-method-not-allowed: GET", "GET"),
                Arguments.of("PUT", "PUT"),
                Arguments.of("DELETE", "DELETE"),
                Arguments.of("OPTIONS", "OPTIONS"),
                Arguments.of("PATCH", "PATCH"),
                Arguments.of("TRACE", "TRACE"));
    }

    /**
     * The method is checked before the body is read, so a rejected method gets the 405 whatever its body, a form body
     * the listener could not decode included.
     */
    @ParameterizedTest(name = "{0} with Content-Type {1}")
    @MethodSource("rejectedMethodsWithBodies")
    void rejectedMethodGetsThe405WhateverItsBody(String method, String contentType, String body) {
        HttpTestClient.Response response = client.send(method, ROOT, Map.of(HttpHeaders.CONTENT_TYPE, contentType),
                body.getBytes(StandardCharsets.UTF_8));

        assertMethodNotAllowed(response);
    }

    private static Stream<Arguments> rejectedMethodsWithBodies() {
        return Stream.of("GET", "PUT", "PATCH", "DELETE").flatMap(method -> Stream.of(
                Arguments.of(method, JSON, ORIGINAL_MESSAGE),
                Arguments.of(method, FORM, "a=%zz")));
    }

    /**
     * {@code HEAD} is outside {@code allowedMethods} too: it gets the headers of the 405 and no body. ST-1 reads no
     * body for a {@code HEAD} response, so it does not wait for the announced {@code Content-Length}.
     */
    @Test
    void headGetsTheListener405WithoutBody() {
        HttpTestClient.Response response = client.send("HEAD", ROOT, null, null);

        assertEquals("HTTP/1.1 405 Method Not Allowed", response.statusLine(), response::toString);
        assertEquals(List.of(MediaType.TEXT_PLAIN_VALUE), response.headers(HttpHeaders.CONTENT_TYPE),
                response::toString);
        assertEquals(0, response.body().length, response::toString);
    }

    /**
     * Scenario {@code post-unknown-path} names no {@code Content-Type}; it is also sent with the {@code text/plain}
     * and the form type curl sends by default. Boot's static resources and error endpoint are disabled, so their
     * paths are unserved like any other.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unservedPaths")
    void unservedPathGetsTheNoListener404(String label, String method, String path, String contentType, String body) {
        HttpTestClient.Response response = client.send(method, path,
                contentType == null ? null : Map.of(HttpHeaders.CONTENT_TYPE, contentType),
                body == null ? null : body.getBytes(StandardCharsets.UTF_8));

        assertEquals("HTTP/1.1 404 Not Found", response.statusLine(), response::toString);
        assertEquals(List.of(MediaType.TEXT_PLAIN_VALUE), response.headers(HttpHeaders.CONTENT_TYPE),
                response::toString);
        assertArrayEquals(("No listener for endpoint: " + path).getBytes(StandardCharsets.UTF_8), response.body(),
                response::toString);
    }

    private static Stream<Arguments> unservedPaths() {
        return Stream.of(
                Arguments.of("post-unknown-path: no Content-Type", "POST", "/other", null, ORIGINAL_MESSAGE),
                Arguments.of("post-unknown-path: Content-Type " + TEXT_PLAIN_UTF8, "POST", "/other",
                        TEXT_PLAIN_UTF8, ORIGINAL_MESSAGE),
                Arguments.of("post-unknown-path: Content-Type " + FORM, "POST", "/other", FORM, ORIGINAL_MESSAGE),
                Arguments.of("GET /other", "GET", "/other", null, null),
                Arguments.of("GET /error", "GET", "/error", null, null),
                Arguments.of("GET /favicon.ico", "GET", "/favicon.ico", null, null));
    }

    /**
     * Any evaluation failure gets the platform 500, the flow declaring no exception strategy. The message comes from
     * the service and is a Tier 2A verification point, so only the body's shape and a non-empty message are asserted.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("failedEvaluations")
    void failedEvaluationGetsThePlatform500(String label, String contentType, String body) {
        HttpTestClient.Response response = client.post(ROOT, contentType, body);

        assertEquals("HTTP/1.1 500 Internal Server Error", response.statusLine(), response::toString);
        assertEquals(List.of(MediaType.APPLICATION_JSON_VALUE), response.headers(HttpHeaders.CONTENT_TYPE),
                response::toString);
        String text = response.bodyAsString();
        assertTrue(text.startsWith(ERROR_BODY_START) && text.endsWith(ERROR_BODY_END)
                && text.length() > ERROR_BODY_START.length() + ERROR_BODY_END.length(), response::toString);
    }

    private static Stream<Arguments> failedEvaluations() {
        return Stream.of(
                Arguments.of("post-root-malformed-body: Content-Type " + JSON, JSON, MALFORMED_MESSAGE),
                Arguments.of("post-root-malformed-body: Content-Type " + TEXT_PLAIN_UTF8, TEXT_PLAIN_UTF8,
                        MALFORMED_MESSAGE),
                Arguments.of("empty body with Content-Length 0", TEXT_PLAIN_UTF8, ""),
                Arguments.of("no body", null, null),
                // Rendered as the Mule listener's parameter map, {{ "a" : 1, "b": 2 }=null}, which is no literal.
                Arguments.of("form body rendered as a parameter map", FORM, ORIGINAL_MESSAGE));
    }

    /**
     * The flow's {@code set-payload} declares no MIME type, so the original listener sent no {@code Content-Type}:
     * the reply carries none, only the length of its UTF-8 bytes.
     */
    private static void assertSum(HttpTestClient.Response response, String expected) {
        byte[] bytes = expected.getBytes(StandardCharsets.UTF_8);
        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertNull(response.header(HttpHeaders.CONTENT_TYPE), response::toString);
        assertEquals(String.valueOf(bytes.length), response.header(HttpHeaders.CONTENT_LENGTH), response::toString);
        assertArrayEquals(bytes, response.body(), response::toString);
    }

    private static void assertMethodNotAllowed(HttpTestClient.Response response) {
        assertEquals("HTTP/1.1 405 Method Not Allowed", response.statusLine(), response::toString);
        assertEquals(List.of(MediaType.TEXT_PLAIN_VALUE), response.headers(HttpHeaders.CONTENT_TYPE),
                response::toString);
        assertArrayEquals(METHOD_NOT_ALLOWED.getBytes(StandardCharsets.UTF_8), response.body(), response::toString);
    }
}
