/**
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.mulesoft.examples.testing_apikit_with_munit.support.HttpTestClient;

/**
 * Wire-level test of {@link ApiKitMethodCaseFilter} on the {@code /api/*} listener of {@code api/api.raml}: a
 * request whose method token is not upper case, such as {@code get}, {@code Get} or {@code delete}, gets exactly the
 * answer its upper-case form gets, because the original listener declares no {@code allowedMethods} and its APIkit
 * router lower-cased the method before routing, before the RAML descriptor check and before the console
 * (testing-apikit-with-munit/src/main/app/api.xml).
 *
 * <p>Each case sends the upper-case form and then the token itself to the same path with the same headers and body,
 * and asserts that both get the same status line, body bytes and headers, {@code Date} aside, and what the
 * upper-case form gets: the four {@code /munit} flows' responses for the declared methods; APIkit's 405
 * {@code application/json} {@code { "message": "Method not allowed" }} for {@code PATCH}, {@code OPTIONS},
 * {@code TRACE} and a token naming no method, such as {@code foo}; the RAML from the console; and, outside the base
 * path, where the filter leaves the request alone, the listener's no-listener 404.
 *
 * <p>A lower-case {@code head} is not sent: Undertow compares method tokens ignoring case and so writes no body for
 * it, while ST-1 skips the body only for the exact token {@code HEAD} and would wait for one. The filter's unit test
 * covers that token.
 *
 * <p>The cases run the whole application on Undertow (random port) and send requests through ST-1
 * {@link HttpTestClient}, so the status line, headers and body bytes are asserted as the listener writes them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiKitMethodCaseListenerTest {

    /** The body of APIkit's 405 mapping, as the original flow's exception strategy wrote it. */
    private static final String APIKIT_METHOD_NOT_ALLOWED = "{ \"message\": \"Method not allowed\" }";

    private static final String RESOURCE = "/api/munit";

    private static final String OK = "HTTP/1.1 200 OK";

    private static final String CREATED = "HTTP/1.1 201 Created";

    private static final String METHOD_NOT_ALLOWED = "HTTP/1.1 405 Method Not Allowed";

    private static final String JSON = "application/json";

    @LocalServerPort
    private int port;

    /**
     * Every request sent with a token that is not upper case: the token, the path, the request headers and body
     * ({@code null} for none), then the status line and {@code Content-Type} its upper-case form gets and that form's
     * exact body, or {@code null} where the body is only compared between the two.
     */
    private static Stream<Arguments> requestsWithANonUpperCaseToken() {
        return Stream.of(
                Arguments.of("get", RESOURCE, null, null, OK, JSON, "\"GET RESPONSE\""),
                Arguments.of("Get", RESOURCE, null, null, OK, JSON, "\"GET RESPONSE\""),
                Arguments.of("gEt", RESOURCE, null, null, OK, JSON, "\"GET RESPONSE\""),
                Arguments.of("post", RESOURCE, null, "", CREATED, JSON, "\"POST RESPONSE\""),
                Arguments.of("put", RESOURCE, null, "", CREATED, JSON, "\"PUT RESPONSE\""),
                Arguments.of("delete", RESOURCE, null, null, OK, JSON, "\"DELETE RESPONSE\""),
                Arguments.of("Delete", RESOURCE, null, null, OK, JSON, "\"DELETE RESPONSE\""),
                Arguments.of("patch", RESOURCE, null, null, METHOD_NOT_ALLOWED, JSON, APIKIT_METHOD_NOT_ALLOWED),
                Arguments.of("foo", RESOURCE, null, null, METHOD_NOT_ALLOWED, JSON, APIKIT_METHOD_NOT_ALLOWED),
                Arguments.of("options", RESOURCE, null, null, METHOD_NOT_ALLOWED, JSON, APIKIT_METHOD_NOT_ALLOWED),
                Arguments.of("trace", RESOURCE, null, null, METHOD_NOT_ALLOWED, JSON, APIKIT_METHOD_NOT_ALLOWED),
                Arguments.of("get", "/api/console/?raml", null, null, OK, "application/raml+yaml", null),
                Arguments.of("get", "/api/", Map.of("Accept", "application/raml+yaml"), null, OK,
                        "application/raml+yaml", null),
                Arguments.of("get", "/other", null, null, "HTTP/1.1 404 Not Found", "text/plain",
                        "No listener for endpoint: /other"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("requestsWithANonUpperCaseToken")
    void tokenIsAnsweredAsItsUpperCaseForm(String token, String path, Map<String, String> headers, String body,
                                           String expectedStatusLine, String expectedContentType,
                                           String expectedBody) {
        HttpTestClient client = HttpTestClient.localhost(port);
        byte[] bodyBytes = body == null ? null : body.getBytes(StandardCharsets.UTF_8);

        HttpTestClient.Response upperCase = client.send(token.toUpperCase(Locale.ROOT), path, headers, bodyBytes);
        HttpTestClient.Response response = client.send(token, path, headers, bodyBytes);

        assertEquals(expectedStatusLine, upperCase.statusLine(), upperCase::toString);
        assertEquals(expectedContentType, upperCase.header("Content-Type"), upperCase::toString);
        if (expectedBody != null) {
            assertArrayEquals(expectedBody.getBytes(StandardCharsets.UTF_8), upperCase.body(), upperCase::toString);
        }
        assertEquals(upperCase.statusLine(), response.statusLine(), response::toString);
        assertEquals(upperCase.header("Content-Type"), response.header("Content-Type"), response::toString);
        assertArrayEquals(upperCase.body(), response.body(), response::toString);
        assertEquals(headerLines(upperCase), headerLines(response), response::toString);
    }

    /** Every header but {@code Date} as {@code name: value} with the name lower-cased, sorted, duplicates kept. */
    private static List<String> headerLines(HttpTestClient.Response response) {
        return response.headers().stream()
                .filter(header -> !header.getKey().equalsIgnoreCase("Date"))
                .map(header -> header.getKey().toLowerCase(Locale.ROOT) + ": " + header.getValue())
                .sorted()
                .toList();
    }
}
