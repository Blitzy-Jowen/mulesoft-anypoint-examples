/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit.config;

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

import com.mulesoft.examples.rest_api_with_apikit.support.HttpTestClient;

/**
 * Wire-level test of {@link ApiKitMethodCaseFilter} on the {@code /api/*} listener of {@code leagues.raml}: a
 * request whose method token is not upper case, such as {@code get}, {@code Get} or {@code post}, gets exactly the
 * answer its upper-case form gets, because the original listener declares no {@code allowedMethods} and its APIkit
 * router lower-cased the method before routing, before the RAML descriptor check and before the console
 * (rest-api-with-apikit/src/main/app/rest-api-with-apikit.xml:3-44).
 *
 * <p>Each case sends the upper-case form and then the token itself to the same path with the same headers and body,
 * and asserts that both get the same status line, body bytes and headers, {@code Date} aside, and what the
 * upper-case form gets: the league's reads; the APIkit 404 and 400 mappings and the RAML-declared 409 of the
 * writes; APIkit's 405 {@code application/json} {@code { "message": "Method not allowed" }} for {@code PATCH},
 * {@code OPTIONS}, {@code TRACE} and a token naming no method, such as {@code foo}; the RAML descriptor and the
 * console's redirect; and, outside the base path, where the filter leaves the request alone, the listener's
 * no-listener 404.
 *
 * <p>The league is an in-memory singleton, so no request here changes it: every write is one the league or the
 * RAML validation rejects, and only an absent team is deleted. A lower-case {@code head} is not sent: Undertow
 * compares method tokens ignoring case and so writes no body for it, while ST-1 skips the body only for the exact
 * token {@code HEAD} and would wait for one. The filter's unit test covers that token.
 *
 * <p>The cases run the whole application on Undertow (random port) and send requests through ST-1
 * {@link HttpTestClient}, so the status line, headers and body bytes are asserted as the listener writes them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiKitMethodCaseListenerTest {

    /** The body of APIkit's 405 mapping, as the original flow's exception strategy wrote it. */
    private static final String APIKIT_METHOD_NOT_ALLOWED = "{ \"message\": \"Method not allowed\" }";

    /** The body of APIkit's 404 mapping, as the original flow's exception strategy wrote it. */
    private static final String APIKIT_RESOURCE_NOT_FOUND = "{ \"message\": \"Resource not found\" }";

    /** The body of APIkit's 400 mapping, as the original flow's exception strategy wrote it. */
    private static final String APIKIT_BAD_REQUEST = "{ \"message\": \"Bad request\" }";

    private static final String OK = "HTTP/1.1 200 OK";

    private static final String NOT_FOUND = "HTTP/1.1 404 Not Found";

    private static final String BAD_REQUEST = "HTTP/1.1 400 Bad Request";

    private static final String METHOD_NOT_ALLOWED = "HTTP/1.1 405 Method Not Allowed";

    private static final String JSON = "application/json";

    private static final String RAML = "application/raml+yaml";

    private static final Map<String, String> JSON_BODY = Map.of("Content-Type", JSON);

    @LocalServerPort
    private int port;

    /**
     * Every request sent with a token that is not upper case: the token, the path, the request headers and body
     * ({@code null} for none), then the status line and {@code Content-Type} its upper-case form gets and that form's
     * exact body, or {@code null} where the body is only compared between the two.
     */
    private static Stream<Arguments> requestsWithANonUpperCaseToken() {
        Stream<Arguments> reads = Stream.of("get", "Get", "gEt").flatMap(token -> Stream.of(
                Arguments.of(token, "/api/teams", null, null, OK, JSON, null),
                Arguments.of(token, "/api/teams?city=Barcelona", null, null, OK, JSON, null),
                Arguments.of(token, "/api/teams/BAR", null, null, OK, JSON, null),
                Arguments.of(token, "/api/teams/ZZZ", null, null, NOT_FOUND, JSON, APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of(token, "/api/teams/TOOLONG", null, null, BAD_REQUEST, JSON, APIKIT_BAD_REQUEST),
                Arguments.of(token, "/api/positions", null, null, OK, JSON, null),
                Arguments.of(token, "/api/fixture", null, null, OK, JSON, null),
                Arguments.of(token, "/api/fixture/BAR/ZZZ", null, null, NOT_FOUND, JSON,
                        APIKIT_RESOURCE_NOT_FOUND)));
        Stream<Arguments> rejectedWrites = Stream.of(
                Arguments.of("post", "/api/teams", JSON_BODY, "{\"id\":\"TOOLONG\"}", BAD_REQUEST, JSON,
                        APIKIT_BAD_REQUEST),
                Arguments.of("Post", "/api/teams", JSON_BODY, "{\"id\":\"BAR\",\"name\":\"Barcelona\"}",
                        "HTTP/1.1 409 Conflict", JSON, "{ \"message\": \"There is already a team with id BAR\" }"),
                Arguments.of("put", "/api/teams/BAR", JSON_BODY, "{\"name\":1}", BAD_REQUEST, JSON,
                        APIKIT_BAD_REQUEST),
                Arguments.of("put", "/api/fixture/BAR/ZZZ", JSON_BODY, "{\"homeTeamScore\":\"x\"}", BAD_REQUEST, JSON,
                        APIKIT_BAD_REQUEST),
                Arguments.of("delete", "/api/teams/ZZZ", null, null, NOT_FOUND, JSON, APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("Delete", "/api/teams/ZZZ", null, null, NOT_FOUND, JSON, APIKIT_RESOURCE_NOT_FOUND));
        Stream<Arguments> undeclaredMethods = Stream.of("patch", "foo", "options", "trace").map(token ->
                Arguments.of(token, "/api/teams", null, null, METHOD_NOT_ALLOWED, JSON, APIKIT_METHOD_NOT_ALLOWED));
        Stream<Arguments> console = Stream.of(
                Arguments.of("get", "/api/", Map.of("Accept", RAML), null, OK, RAML, null),
                Arguments.of("get", "/api/console/?raml", null, null, OK, RAML, null),
                Arguments.of("get", "/api/console", null, null, "HTTP/1.1 301 Moved Permanently", null, ""));
        Stream<Arguments> outsideTheBasePath = Stream.of(
                Arguments.of("get", "/other", null, null, NOT_FOUND, "text/plain",
                        "No listener for endpoint: /other"),
                Arguments.of("get", "/apix", null, null, NOT_FOUND, "text/plain", "No listener for endpoint: /apix"));
        return Stream.of(reads, rejectedWrites, undeclaredMethods, console, outsideTheBasePath)
                .flatMap(requests -> requests);
    }

    @ParameterizedTest(name = "{0} {1} {3}")
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
