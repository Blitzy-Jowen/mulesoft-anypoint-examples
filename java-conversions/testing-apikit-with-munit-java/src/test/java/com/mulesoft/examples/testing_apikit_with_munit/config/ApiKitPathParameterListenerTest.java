/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.testing_apikit_with_munit.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.mulesoft.examples.testing_apikit_with_munit.support.HttpTestClient;

/**
 * Wire-level test of {@link ApiKitPathParameterConfig} on the {@code /api/*} listener of {@code api/api.raml}: a
 * {@code ;} in the request path is content of its segment, as it was to the original listener and its APIkit router
 * (testing-apikit-with-munit/src/main/app/api.xml), so the {@code /munit} resource is not served under an alias.
 *
 * <p>The cases assert the status line, {@code Content-Type} and exact body bytes of:
 * <ul>
 *   <li>{@code /api/munit;x=1} and {@code /api/munit;}, segments that name no resource: APIkit's 404
 *       {@code { "message": "Resource not found" }} for every method, a lower-case token, a body media type or
 *       {@code Accept} header the resource does not serve, and a CORS pre-flight, so none of the four flows
 *       answers;</li>
 *   <li>{@code /api/console;x=1}: APIkit's 404 rather than the console's redirect;</li>
 *   <li>outside the base path, {@code /api;x=1/munit}: the listener's no-listener 404 naming the raw path;</li>
 *   <li>{@code /api/munit} itself, also with a {@code ;} in its query string: the flows' own responses.</li>
 * </ul>
 *
 * <p>A lower-case {@code head} is not sent: Undertow compares method tokens ignoring case and so writes no body for
 * it, while ST-1 skips the body only for the exact token {@code HEAD} and would wait for one.
 *
 * <p>The cases run the whole application on Undertow (random port) and send requests through ST-1
 * {@link HttpTestClient}, which writes the request target byte for byte, so the {@code ;} reaches the listener as
 * sent and the status line, headers and body bytes are asserted as the listener writes them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiKitPathParameterListenerTest {

    /** The body of APIkit's 404 mapping, as the original flow's exception strategy wrote it. */
    private static final String APIKIT_RESOURCE_NOT_FOUND = "{ \"message\": \"Resource not found\" }";

    private static final String RESOURCE = "/api/munit";

    private static final String OK = "HTTP/1.1 200 OK";

    private static final String CREATED = "HTTP/1.1 201 Created";

    private static final String NOT_FOUND = "HTTP/1.1 404 Not Found";

    private static final String JSON = "application/json";

    /** The resource path followed by {@code ;} segment parameters, each sent with every method. */
    private static final List<String> ALIASES = List.of(RESOURCE + ";x=1", RESOURCE + ";");

    /** Every method the original router dispatched, RAML-declared or not; {@code HEAD} gets no body on the wire. */
    private static final List<String> METHODS = List.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS",
            "TRACE");

    @LocalServerPort
    private int port;

    /**
     * Every request: the method, the path, the request headers and body ({@code null} for none), then the status line,
     * {@code Content-Type} and exact body the listener answers with.
     */
    private static Stream<Arguments> requestsToPathsCarryingSemicolons() {
        Stream<Arguments> everyMethodOnAnAlias = ALIASES.stream().flatMap(path -> METHODS.stream()
                .map(method -> Arguments.of(method, path, null, writes(method) ? "" : null, NOT_FOUND, JSON,
                        "HEAD".equals(method) ? "" : APIKIT_RESOURCE_NOT_FOUND)));
        Stream<Arguments> aliasWhateverTheRequest = Stream.of(
                Arguments.of("OPTIONS", RESOURCE + ";x=1", preFlight("POST"), null, NOT_FOUND, JSON,
                        APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("post", RESOURCE + ";x=1", null, "", NOT_FOUND, JSON, APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("get", RESOURCE + ";x=1", null, null, NOT_FOUND, JSON, APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("POST", RESOURCE + ";x=1", Map.of("Content-Type", "text/plain"), "plain", NOT_FOUND, JSON,
                        APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("GET", RESOURCE + ";x=1", Map.of("Accept", "text/xml"), null, NOT_FOUND, JSON,
                        APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("GET", RESOURCE + ";jsessionid=1", null, null, NOT_FOUND, JSON,
                        APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("GET", RESOURCE + ";x=1/", null, null, NOT_FOUND, JSON, APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("GET", "/api/console;x=1", null, null, NOT_FOUND, JSON, APIKIT_RESOURCE_NOT_FOUND));
        Stream<Arguments> outsideTheBasePath = Stream.of(
                Arguments.of("GET", "/api;x=1/munit", null, null, NOT_FOUND, "text/plain",
                        "No listener for endpoint: /api;x=1/munit"),
                Arguments.of("POST", "/api;x=1/munit", null, "", NOT_FOUND, "text/plain",
                        "No listener for endpoint: /api;x=1/munit"));
        Stream<Arguments> theResourceItself = Stream.of(
                Arguments.of("GET", RESOURCE, null, null, OK, JSON, "\"GET RESPONSE\""),
                Arguments.of("POST", RESOURCE, null, "", CREATED, JSON, "\"POST RESPONSE\""),
                Arguments.of("PUT", RESOURCE, null, "", CREATED, JSON, "\"PUT RESPONSE\""),
                Arguments.of("DELETE", RESOURCE, null, null, OK, JSON, "\"DELETE RESPONSE\""),
                Arguments.of("GET", RESOURCE + "?x=1;y", null, null, OK, JSON, "\"GET RESPONSE\""));
        return Stream.of(everyMethodOnAnAlias, aliasWhateverTheRequest, outsideTheBasePath, theResourceItself)
                .flatMap(requests -> requests);
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("requestsToPathsCarryingSemicolons")
    void semicolonIsContentOfItsPathSegment(String method, String path, Map<String, String> headers, String body,
                                            String expectedStatusLine, String expectedContentType,
                                            String expectedBody) {
        HttpTestClient.Response response = HttpTestClient.localhost(port).send(method, path, headers,
                body == null ? null : body.getBytes(StandardCharsets.UTF_8));

        assertEquals(expectedStatusLine, response.statusLine(), response::toString);
        assertEquals(expectedContentType, response.header("Content-Type"), response::toString);
        assertArrayEquals(expectedBody.getBytes(StandardCharsets.UTF_8), response.body(), response::toString);
    }

    /** Whether {@code method} is sent with an empty body, {@code Content-Length: 0}, in the every-method cases. */
    private static boolean writes(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
    }

    /** The headers of a CORS pre-flight asking for {@code requestedMethod}. */
    private static Map<String, String> preFlight(String requestedMethod) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Origin", "http://example.com");
        headers.put("Access-Control-Request-Method", requestedMethod);
        return headers;
    }
}
