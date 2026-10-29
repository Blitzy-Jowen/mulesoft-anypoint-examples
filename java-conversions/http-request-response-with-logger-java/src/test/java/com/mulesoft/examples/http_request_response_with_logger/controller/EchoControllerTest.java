/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_request_response_with_logger.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.mulesoft.examples.http_request_response_with_logger.service.EchoService;
import com.mulesoft.examples.http_request_response_with_logger.support.HttpTestClient;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;

/**
 * Wire-level test of {@link EchoController}, the HTTP trigger of flow {@code EchoFlow}
 * (http-request-response-with-logger/src/main/app/echo.xml:5-7), on the application's own Undertow listener.
 *
 * <p>Requests go through the project's ST-1 {@link HttpTestClient}, which sends the request target byte for byte
 * and reads the raw status line from the socket, so the reason phrase, the headers and the body bytes are asserted
 * exactly as the listener writes them. The original listener ({@code path="/*"}) declares no {@code allowedMethods},
 * so the happy paths cover every method and ignore every request body; the payload is Mule's
 * {@code http.request.path}, the path as sent, without the query string and without percent-decoding.
 *
 * <p>The error cases are the SC-1 JSON 500 for an exception no branch handles. The listener-level 404 and 405 are not
 * asserted here: the {@code /**} mappings serve every path and every method, so neither is reachable over the wire,
 * and {@code exception/GlobalExceptionHandlerTest} covers both.
 *
 * <p>The original {@code HttpRequestResponseWithLoggerTest} assertion is ported separately under its own name.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EchoControllerTest {

    @LocalServerPort
    private int port;

    private HttpTestClient client() {
        return HttpTestClient.localhost(port);
    }

    /** The original assertion of {@code HttpRequestResponseWithLoggerTest}: a GET to {@code /message}. */
    @Test
    void getMessageEchoesItsPath() {
        assertEcho(client().get("/message"), "/message");
    }

    /**
     * Request target, sent verbatim, and the echoed path: the README examples, the root and a nested path, a query
     * string that is dropped, a percent-encoded path that is not decoded, and {@code /error}, which no Boot error
     * endpoint claims because {@code application.yml} excludes it.
     */
    @ParameterizedTest(name = "GET {0} echoes {1}")
    @CsvSource({
            "/echo, /echo",
            "/moon, /moon",
            "/, /",
            "/a/b/c, /a/b/c",
            "/echo?x=1&y=2, /echo",
            "/hello%20world, /hello%20world",
            "/error, /error"})
    void getEchoesTheRawRequestPath(String target, String echoedPath) {
        assertEcho(client().get(target), echoedPath);
    }

    /** The mapping declares no {@code produces}, so an {@code Accept} the reply cannot satisfy never yields a 406. */
    @Test
    void acceptHeaderIsIgnored() {
        assertEcho(client().get("/moon", Map.of("Accept", MediaType.APPLICATION_JSON_VALUE)), "/moon");
    }

    /**
     * Every method without a body, and so without {@code Content-Length}, is echoed. {@code TRACE} reaches the
     * controller because {@code application.yml} sets {@code spring.mvc.dispatch-trace-request}; without it the
     * servlet would answer with a {@code message/http} echo of the request.
     *
     * @param method the request method
     */
    @ParameterizedTest(name = "{0} /moon without a body")
    @ValueSource(strings = {"GET", "POST", "PUT", "DELETE", "PATCH", "TRACE"})
    void everyMethodWithoutBodyEchoesThePath(String method) {
        assertEcho(client().send(method, "/moon", null, null), "/moon");
    }

    /**
     * Each method that carries a body, crossed with each body shape: empty ({@code Content-Length: 0}), plain text,
     * JSON and an unparseable form body.
     */
    private static Stream<Arguments> methodsWithBodies() {
        return Stream.of("POST", "PUT", "DELETE", "PATCH")
                .flatMap(method -> Stream.of(
                        Arguments.of(method, null, ""),
                        Arguments.of(method, MediaType.TEXT_PLAIN_VALUE, "hello"),
                        Arguments.of(method, MediaType.APPLICATION_JSON_VALUE, "{\"a\": 1}"),
                        Arguments.of(method, MediaType.APPLICATION_FORM_URLENCODED_VALUE, "a=1&b=%ZZ")));
    }

    /**
     * The flow reads no part of the request body, so the reply never depends on it. The form case holds because
     * {@code application.yml} disables Boot's form-content filter: a form body on {@code PUT}, {@code PATCH} or
     * {@code DELETE} is not parsed before dispatch, so a malformed one gets the reply {@code POST} gets.
     *
     * @param method      the request method
     * @param contentType the {@code Content-Type} sent, or {@code null} for none
     * @param body        the UTF-8 request body, sent with its {@code Content-Length}
     */
    @ParameterizedTest(name = "{0} /moon with Content-Type {1} and body \"{2}\"")
    @MethodSource("methodsWithBodies")
    void requestBodyIsIgnored(String method, String contentType, String body) {
        Map<String, String> headers = contentType == null ? null : Map.of("Content-Type", contentType);

        HttpTestClient.Response response = client().send(method, "/moon", headers,
                body.getBytes(StandardCharsets.UTF_8));

        assertEcho(response, "/moon");
    }

    /**
     * {@code OPTIONS} runs the flow through the explicit {@code OPTIONS} mapping, as the Mule listener did, instead
     * of getting Spring MVC's own answer, an {@code Allow} header and no body.
     */
    @Test
    void optionsRunsTheFlowInsteadOfAnsweringWithAllow() {
        HttpTestClient.Response response = client().send("OPTIONS", "/moon", null, null);

        assertEcho(response, "/moon");
        assertNull(response.header("Allow"), response::toString);
    }

    /**
     * {@code HEAD} gets the {@code GET} reply's status line and headers without its body. ST-1 reads no body bytes
     * for a {@code HEAD} request whatever its {@code Content-Length}, so the response is read through it.
     */
    @Test
    void headReturnsTheGetHeadersWithoutBody() {
        HttpTestClient.Response response = client().send("HEAD", "/moon", null, null);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertEquals(List.of("5"), response.headers("Content-Length"), response::toString);
        assertArrayEquals(new byte[0], response.body(), response::toString);
    }

    /**
     * A failing {@link EchoService} reaches the SC-1 catch-all. The mock gives this class its own application
     * context, so its listener runs on its own random port; each case stubs the mock itself, and Mockito resets it
     * after every case.
     */
    @Nested
    class WhenServiceFails {

        @MockBean
        private EchoService echoService;

        @LocalServerPort
        private int mockedPort;

        @Test
        void serviceFailureReturns500JsonMessage() {
            when(echoService.echo(anyString())).thenThrow(new RuntimeException("boom"));

            HttpTestClient.Response response = HttpTestClient.localhost(mockedPort).get("/moon");

            assertInternalServerError(response, "{ \"message\": \"boom\" }");
            verify(echoService).echo("/moon");
        }

        @Test
        void serviceFailureWithoutMessageReturns500JsonNull() {
            when(echoService.echo(anyString())).thenThrow(new RuntimeException((String) null));

            HttpTestClient.Response response = HttpTestClient.localhost(mockedPort).get("/moon?x=1");

            assertInternalServerError(response, "{ \"message\": null }");
            verify(echoService).echo("/moon");
        }
    }

    /**
     * Asserts the flow's reply: 200 with the echoed path's UTF-8 bytes and their {@code Content-Length}, and no
     * {@code Content-Type}, because the original's {@code set-payload} declares no MIME type and the Mule listener
     * sent none for such a payload.
     */
    private static void assertEcho(HttpTestClient.Response response, String echoedPath) {
        byte[] expected = echoedPath.getBytes(StandardCharsets.UTF_8);
        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertEquals(200, response.status(), response::toString);
        assertEquals("OK", response.reason(), response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertEquals(List.of(String.valueOf(expected.length)), response.headers("Content-Length"),
                response::toString);
        assertArrayEquals(expected, response.body(), response::toString);
    }

    /**
     * Asserts the SC-1 500, whose single {@code Content-Type} is the bare {@code application/json} the handler presets
     * on the response entity, without a charset parameter.
     */
    private static void assertInternalServerError(HttpTestClient.Response response, String expectedBody) {
        assertEquals("HTTP/1.1 500 Internal Server Error", response.statusLine(), response::toString);
        assertEquals(List.of(MediaType.APPLICATION_JSON_VALUE), response.headers("Content-Type"), response::toString);
        assertArrayEquals(expectedBody.getBytes(StandardCharsets.UTF_8), response.body(), response::toString);
    }
}
