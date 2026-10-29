/*
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.mulesoft.examples.rest_api_with_apikit.support.HttpTestClient;

/**
 * Wire-level test of {@link ApiKitPathParameterConfig} on the {@code /api/*} listener of {@code leagues.raml}: a
 * {@code ;} in the request path is content of its segment, as it was to the original listener and its APIkit router
 * (rest-api-with-apikit/src/main/app/rest-api-with-apikit.xml:3-44), so no RAML resource is served under an alias.
 *
 * <p>The cases assert the status line, {@code Content-Type} and exact body bytes of:
 * <ul>
 *   <li>a literal segment carrying {@code ;} ({@code /api/teams;x=1}, {@code /api/teams;}, {@code /api/positions;x=1},
 *       {@code /api/fixture;x=1}, {@code /api/console;x=1}): APIkit's 404 for every method, a lower-case token, a
 *       body media type or {@code Accept} header no resource serves, and a CORS pre-flight;</li>
 *   <li>a URI parameter carrying {@code ;}, bound whole: APIkit's 400 where the value breaks the RAML's
 *       {@code minLength}/{@code maxLength} 3, the league's 404 for a three-character value naming no team, and
 *       APIkit's 405 for a method the resource does not declare, which APIkit checked before the parameters;</li>
 *   <li>outside the base path, {@code /api;x=1/teams}: the listener's no-listener 404 naming the raw path;</li>
 *   <li>a {@code ;} in the query string: the resource's own answer, byte for byte the answer without that
 *       query.</li>
 * </ul>
 * Two further tests show that no write sent to such a path reaches the league: the team a POST to
 * {@code /api/teams;x=1} carries is not created, and team {@code BAR} is unchanged after a PUT and a DELETE sent to
 * {@code /api/teams/BAR;x=1} and {@code /api/teams/BAR;jsessionid=1}.
 *
 * <p>The league is an in-memory singleton, so no request here changes it: every write is one a missing resource or
 * the RAML validation rejects. A lower-case {@code head} is not sent: Undertow compares method tokens ignoring case
 * and so writes no body for it, while ST-1 skips the body only for the exact token {@code HEAD} and would wait for
 * one.
 *
 * <p>The cases run the whole application on Undertow (random port) and send requests through ST-1
 * {@link HttpTestClient}, which writes the request target byte for byte, so the {@code ;} reaches the listener as
 * sent and the status line, headers and body bytes are asserted as the listener writes them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiKitPathParameterListenerTest {

    /** The body of APIkit's 404 mapping, as the original flow's exception strategy wrote it. */
    private static final String APIKIT_RESOURCE_NOT_FOUND = "{ \"message\": \"Resource not found\" }";

    /** The body of APIkit's 400 mapping, as the original flow's exception strategy wrote it. */
    private static final String APIKIT_BAD_REQUEST = "{ \"message\": \"Bad request\" }";

    /** The body of APIkit's 405 mapping, as the original flow's exception strategy wrote it. */
    private static final String APIKIT_METHOD_NOT_ALLOWED = "{ \"message\": \"Method not allowed\" }";

    private static final String OK = "HTTP/1.1 200 OK";

    private static final String NOT_FOUND = "HTTP/1.1 404 Not Found";

    private static final String BAD_REQUEST = "HTTP/1.1 400 Bad Request";

    private static final String METHOD_NOT_ALLOWED = "HTTP/1.1 405 Method Not Allowed";

    private static final String JSON = "application/json";

    private static final Map<String, String> JSON_BODY = Map.of("Content-Type", JSON);

    /** A team body every schema of {@code leagues.raml} accepts, so only the path can reject the write. */
    private static final String TEAM_BODY = "{\"id\":\"SMC\",\"name\":\"Semicolon\",\"homeCity\":\"Nowhere\","
            + "\"stadium\":\"None\"}";

    /** A team update body {@code schemas/teamid-schema-input.json} accepts. */
    private static final String UPDATE_BODY = "{\"name\":\"Semicolon\",\"homeCity\":\"Nowhere\",\"stadium\":\"None\"}";

    /** The literal resource paths carrying {@code ;} that every method is sent to. */
    private static final List<String> LITERAL_ALIASES = List.of("/api/teams;x=1", "/api/teams;",
            "/api/positions;x=1", "/api/fixture;x=1");

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
        Stream<Arguments> everyMethodOnALiteralAlias = LITERAL_ALIASES.stream().flatMap(path -> METHODS.stream()
                .map(method -> Arguments.of(method, path, writes(method) ? JSON_BODY : null,
                        writes(method) ? TEAM_BODY : null, NOT_FOUND, JSON,
                        "HEAD".equals(method) ? "" : APIKIT_RESOURCE_NOT_FOUND)));
        Stream<Arguments> preFlightOnALiteralAlias = LITERAL_ALIASES.stream().map(path ->
                Arguments.of("OPTIONS", path, preFlight("POST"), null, NOT_FOUND, JSON, APIKIT_RESOURCE_NOT_FOUND));
        Stream<Arguments> literalAliasWhateverTheHeaders = Stream.of(
                Arguments.of("POST", "/api/teams;x=1", Map.of("Content-Type", "text/plain"), "plain", NOT_FOUND, JSON,
                        APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("POST", "/api/teams;x=1", null, TEAM_BODY, NOT_FOUND, JSON, APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("GET", "/api/teams;x=1", Map.of("Accept", "text/xml"), null, NOT_FOUND, JSON,
                        APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("post", "/api/teams;x=1", JSON_BODY, TEAM_BODY, NOT_FOUND, JSON,
                        APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("GET", "/api/teams;x=1?city=Barcelona", null, null, NOT_FOUND, JSON,
                        APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("GET", "/api/console;x=1", null, null, NOT_FOUND, JSON, APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("GET", "/api/console;x=1/", null, null, NOT_FOUND, JSON, APIKIT_RESOURCE_NOT_FOUND));
        Stream<Arguments> uriParameterBoundWhole = Stream.of(
                Arguments.of("GET", "/api/teams/BAR;x=1", null, null, BAD_REQUEST, JSON, APIKIT_BAD_REQUEST),
                Arguments.of("GET", "/api/teams/BAR;", null, null, BAD_REQUEST, JSON, APIKIT_BAD_REQUEST),
                Arguments.of("GET", "/api/teams/;x=1", null, null, BAD_REQUEST, JSON, APIKIT_BAD_REQUEST),
                Arguments.of("get", "/api/teams/BAR;x=1", null, null, BAD_REQUEST, JSON, APIKIT_BAD_REQUEST),
                Arguments.of("DELETE", "/api/teams/BAR;jsessionid=1", null, null, BAD_REQUEST, JSON,
                        APIKIT_BAD_REQUEST),
                Arguments.of("PUT", "/api/teams/BAR;x=1", JSON_BODY, UPDATE_BODY, BAD_REQUEST, JSON,
                        APIKIT_BAD_REQUEST),
                Arguments.of("GET", "/api/fixture/BAR;a/OSA", null, null, BAD_REQUEST, JSON, APIKIT_BAD_REQUEST),
                Arguments.of("GET", "/api/fixture/BAR/OSA;a", null, null, BAD_REQUEST, JSON, APIKIT_BAD_REQUEST),
                Arguments.of("GET", "/api/teams/B;x", null, null, NOT_FOUND, JSON, APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("HEAD", "/api/teams/BAR;x=1", null, null, METHOD_NOT_ALLOWED, JSON, ""),
                Arguments.of("OPTIONS", "/api/teams/BAR;x=1", null, null, METHOD_NOT_ALLOWED, JSON,
                        APIKIT_METHOD_NOT_ALLOWED),
                Arguments.of("PATCH", "/api/teams/BAR;x=1", JSON_BODY, UPDATE_BODY, METHOD_NOT_ALLOWED, JSON,
                        APIKIT_METHOD_NOT_ALLOWED),
                Arguments.of("OPTIONS", "/api/teams/BAR;x=1", preFlight("DELETE"), null, METHOD_NOT_ALLOWED, JSON,
                        APIKIT_METHOD_NOT_ALLOWED));
        Stream<Arguments> outsideTheBasePath = Stream.of(
                Arguments.of("GET", "/api;x=1/teams", null, null, NOT_FOUND, "text/plain",
                        "No listener for endpoint: /api;x=1/teams"),
                Arguments.of("POST", "/api;x=1/teams", JSON_BODY, TEAM_BODY, NOT_FOUND, "text/plain",
                        "No listener for endpoint: /api;x=1/teams"),
                Arguments.of("GET", "/api;x=1", null, null, NOT_FOUND, "text/plain",
                        "No listener for endpoint: /api;x=1"));
        Stream<Arguments> semicolonInTheQuery = Stream.of(
                // No seeded team has the home city "a;b"
                Arguments.of("GET", "/api/teams?city=a;b", null, null, OK, JSON, "{\"teams\":[]}"));
        return Stream.of(everyMethodOnALiteralAlias, preFlightOnALiteralAlias, literalAliasWhateverTheHeaders,
                uriParameterBoundWhole, outsideTheBasePath, semicolonInTheQuery).flatMap(requests -> requests);
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

    /**
     * A read whose query string carries {@code ;}, a query parameter the RAML does not declare, and the same read
     * without that query: both get the same status line, {@code Content-Type} and body bytes.
     */
    @ParameterizedTest(name = "GET {0} answers as GET {1}")
    @CsvSource({
            "/api/teams?x=1;y, /api/teams",
            "/api/teams/BAR?x=1;y, /api/teams/BAR",
            "/api/positions?x=1;y, /api/positions",
            "/api/fixture/BAR/OSA?x=1;y, /api/fixture/BAR/OSA"})
    void semicolonInTheQueryLeavesTheAnswerUnchanged(String pathWithQuery, String path) {
        HttpTestClient client = HttpTestClient.localhost(port);

        HttpTestClient.Response expected = client.get(path);
        HttpTestClient.Response response = client.get(pathWithQuery);

        assertEquals(OK, expected.statusLine(), expected::toString);
        assertEquals(expected.statusLine(), response.statusLine(), response::toString);
        assertEquals(JSON, response.header("Content-Type"), response::toString);
        assertArrayEquals(expected.body(), response.body(), response::toString);
    }

    @Test
    void postToALiteralAliasCreatesNoTeam() {
        HttpTestClient client = HttpTestClient.localhost(port);
        byte[] teamsBefore = client.get("/api/teams").body();

        HttpTestClient.Response post = client.post("/api/teams;x=1", JSON, TEAM_BODY);

        assertEquals(NOT_FOUND, post.statusLine(), post::toString);
        HttpTestClient.Response created = client.get("/api/teams/SMC");
        assertEquals(NOT_FOUND, created.statusLine(), created::toString);
        assertArrayEquals(APIKIT_RESOURCE_NOT_FOUND.getBytes(StandardCharsets.UTF_8), created.body(),
                created::toString);
        assertArrayEquals(teamsBefore, client.get("/api/teams").body());
    }

    @Test
    void writesToAUriParameterCarryingSemicolonsLeaveTheTeamUnchanged() {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response before = client.get("/api/teams/BAR");
        assertEquals(OK, before.statusLine(), before::toString);

        HttpTestClient.Response put = client.put("/api/teams/BAR;x=1", JSON, UPDATE_BODY);
        HttpTestClient.Response delete = client.delete("/api/teams/BAR;jsessionid=1");

        assertEquals(BAD_REQUEST, put.statusLine(), put::toString);
        assertEquals(BAD_REQUEST, delete.statusLine(), delete::toString);
        HttpTestClient.Response after = client.get("/api/teams/BAR");
        assertEquals(OK, after.statusLine(), after::toString);
        assertArrayEquals(before.body(), after.body(), after::toString);
    }

    /** Whether {@code method} is sent with a JSON body in the every-method cases. */
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
