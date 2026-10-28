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
import java.util.AbstractMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.mulesoft.examples.rest_api_with_apikit.support.HttpTestClient;

/**
 * Wire-level test of {@link ApiKitAcceptNegotiator} on the {@code /api/*} listener of {@code leagues.raml}: APIkit's
 * router negotiated the response of every action whose success response declares a body, the five GETs here, and
 * answered an {@code Accept} header for which it chose no declared type with its 406 mapping
 * {@code { "message": "Not acceptable" }} (rest-api-with-apikit/src/main/app/rest-api-with-apikit.xml:34-38), after
 * it validated the URI parameters and before the flow ran.
 *
 * <p>The cases assert that:
 * <ul>
 *   <li>every form that gives JSON quality 0 gets that 406 on each of the five GETs, an unknown team or match
 *       included, because negotiation preceded the flow's {@code NotFoundException};</li>
 *   <li>a URI parameter that is not three characters long gets APIkit's 400 {@code { "message": "Bad request" }}
 *       with each of those forms, exactly as without {@code Accept}, because the URI parameter validation preceded
 *       negotiation;</li>
 *   <li>the forms APIkit accepted, {@code *}{@code /*;q=0} among them, get the same 200 as a request without
 *       {@code Accept};</li>
 *   <li>the actions the RAML gives no success body (POST, PUT, DELETE), the methods it does not declare, unserved
 *       paths, the console and the RAML descriptor get with {@code application/json;q=0} exactly what they get
 *       without it.</li>
 * </ul>
 *
 * <p>The league is an in-memory singleton, so no request here changes it: the writes sent are rejected by the
 * league or the RAML validation, and only an absent team is deleted. The cases run the whole application on Undertow
 * (random port) and send requests through ST-1 {@link HttpTestClient}, so the status line, headers and body bytes are
 * asserted as the listener writes them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiKitAcceptNegotiationListenerTest {

    /** The body of APIkit's 406 mapping, as the original flow's exception strategy wrote it. */
    private static final String APIKIT_NOT_ACCEPTABLE = "{ \"message\": \"Not acceptable\" }";

    /** The body of APIkit's 405 mapping, as the original flow's exception strategy wrote it. */
    private static final String APIKIT_METHOD_NOT_ALLOWED = "{ \"message\": \"Method not allowed\" }";

    /** The body of APIkit's 404 mapping, as the original flow's exception strategy wrote it. */
    private static final String APIKIT_RESOURCE_NOT_FOUND = "{ \"message\": \"Resource not found\" }";

    /** The body of APIkit's 400 mapping, as the original flow's exception strategy wrote it. */
    private static final String APIKIT_BAD_REQUEST = "{ \"message\": \"Bad request\" }";

    private static final String OK = "HTTP/1.1 200 OK";

    private static final String NOT_ACCEPTABLE = "HTTP/1.1 406 Not Acceptable";

    private static final String NOT_FOUND = "HTTP/1.1 404 Not Found";

    private static final String BAD_REQUEST = "HTTP/1.1 400 Bad Request";

    private static final String METHOD_NOT_ALLOWED = "HTTP/1.1 405 Method Not Allowed";

    private static final String JSON = "application/json";

    private static final String RAML = "application/raml+yaml";

    private static final String JSON_Q0 = "application/json;q=0";

    /** The GETs whose RAML success response declares an {@code application/json} body. */
    private static final List<String> NEGOTIATED_GETS = List.of("/api/teams", "/api/teams/RMA", "/api/positions",
            "/api/fixture", "/api/fixture/VIL/MAL");

    /** {@code Accept} header lines for which APIkit chose no type: JSON at quality 0, however combined. */
    private static final List<List<String>> NOT_ACCEPTED = List.of(
            List.of(JSON_Q0),
            List.of("text/xml, application/json;q=0"),
            List.of("application/*;q=0"),
            List.of("application/json;q=0.0"),
            List.of("application/json;q=0, */*"),
            List.of("application/json;q=0, application/json"),
            List.of("APPLICATION/JSON"),
            List.of("text/xml", JSON_Q0));

    /** {@code Accept} header lines for which APIkit chose JSON, quality-0 ranges it outweighed included. */
    private static final List<List<String>> ACCEPTED = List.of(
            List.of("*/*;q=0"),
            List.of("*;q=0"),
            List.of("application/json;q=0.5"),
            List.of("application/json;q=0.001"),
            List.of("application/json, application/json;q=0"),
            List.of("application/json;charset=UTF-8"),
            List.of("*/*"),
            List.of(""),
            List.of("text/xml", JSON));

    @LocalServerPort
    private int port;

    private HttpTestClient client;

    @BeforeEach
    void createClient() {
        client = HttpTestClient.localhost(port);
    }

    /** Each negotiated GET, unknown entities included, with each header that APIkit did not accept. */
    private static Stream<Arguments> notAcceptedRequests() {
        Stream<String> paths = Stream.concat(NEGOTIATED_GETS.stream(),
                Stream.of("/api/teams/ZZZ", "/api/fixture/BAR/ZZZ"));
        return paths.flatMap(path -> NOT_ACCEPTED.stream().map(accept -> Arguments.of(path, accept)));
    }

    @ParameterizedTest(name = "GET {0} with Accept {1} -> APIkit 406")
    @MethodSource("notAcceptedRequests")
    void jsonAtQualityZeroGetsApiKitNotAcceptable(String path, List<String> acceptLines) {
        HttpTestClient.Response response = client.sendHeaderPairs("GET", path, accept(acceptLines), null);

        assertEquals(NOT_ACCEPTABLE, response.statusLine(), response::toString);
        assertEquals(JSON, response.header("Content-Type"), response::toString);
        assertArrayEquals(bytes(APIKIT_NOT_ACCEPTABLE), response.body(), response::toString);
    }

    /**
     * Each GET with a URI parameter outside {@code minLength}/{@code maxLength} 3, with each header that APIkit did
     * not accept.
     */
    private static Stream<Arguments> invalidParameterRequests() {
        return Stream.of("/api/teams/BA", "/api/teams/TOOLONG", "/api/fixture/BA/MAL", "/api/fixture/VIL/TOOLONG")
                .flatMap(path -> NOT_ACCEPTED.stream().map(accept -> Arguments.of(path, accept)));
    }

    @ParameterizedTest(name = "GET {0} with Accept {1} -> APIkit 400, as without Accept")
    @MethodSource("invalidParameterRequests")
    void invalidUriParameterGetsApiKitBadRequestBeforeNegotiation(String path, List<String> acceptLines) {
        HttpTestClient.Response withoutAccept = client.get(path);
        HttpTestClient.Response response = client.sendHeaderPairs("GET", path, accept(acceptLines), null);

        assertEquals(BAD_REQUEST, withoutAccept.statusLine(), withoutAccept::toString);
        assertEquals(JSON, withoutAccept.header("Content-Type"), withoutAccept::toString);
        assertArrayEquals(bytes(APIKIT_BAD_REQUEST), withoutAccept.body(), withoutAccept::toString);
        assertEquals(withoutAccept.statusLine(), response.statusLine(), response::toString);
        assertArrayEquals(withoutAccept.body(), response.body(), response::toString);
        assertEquals(headerLines(withoutAccept), headerLines(response), response::toString);
    }

    /** Each negotiated GET with each header that APIkit accepted. */
    private static Stream<Arguments> acceptedRequests() {
        return NEGOTIATED_GETS.stream().flatMap(path -> ACCEPTED.stream().map(accept -> Arguments.of(path, accept)));
    }

    @ParameterizedTest(name = "GET {0} with Accept {1} -> 200 as without Accept")
    @MethodSource("acceptedRequests")
    void acceptedFormsGetTheResource(String path, List<String> acceptLines) {
        HttpTestClient.Response withoutAccept = client.get(path);
        HttpTestClient.Response response = client.sendHeaderPairs("GET", path, accept(acceptLines), null);

        assertEquals(OK, withoutAccept.statusLine(), withoutAccept::toString);
        assertEquals(JSON, withoutAccept.header("Content-Type"), withoutAccept::toString);
        assertEquals(OK, response.statusLine(), response::toString);
        assertEquals(JSON, response.header("Content-Type"), response::toString);
        assertArrayEquals(withoutAccept.body(), response.body(), response::toString);
    }

    /**
     * Requests APIkit did not negotiate: the method, the path, the {@code Accept} value sent without and then with
     * {@code application/json;q=0} appended ({@code null} for none), the JSON request body ({@code null} for
     * none), then the status line, {@code Content-Type} and exact body both get, or {@code null} where the body is
     * only compared between the two.
     */
    private static Stream<Arguments> requestsNotNegotiated() {
        Stream<Arguments> writesWithoutSuccessBody = Stream.of(
                Arguments.of("DELETE", "/api/teams/ZZZ", null, null, NOT_FOUND, JSON, APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("POST", "/api/teams", null, "{\"id\":\"TOOLONG\"}", BAD_REQUEST, JSON, APIKIT_BAD_REQUEST),
                Arguments.of("POST", "/api/teams", null, "{\"id\":\"BAR\",\"name\":\"Barcelona\"}",
                        "HTTP/1.1 409 Conflict", JSON, "{ \"message\": \"There is already a team with id BAR\" }"),
                Arguments.of("PUT", "/api/teams/BAR", null, "{\"name\":1}", BAD_REQUEST, JSON, APIKIT_BAD_REQUEST),
                Arguments.of("PUT", "/api/fixture/BAR/ZZZ", null, "{\"homeTeamScore\":\"x\"}", BAD_REQUEST, JSON,
                        APIKIT_BAD_REQUEST));
        Stream<Arguments> undeclaredMethods = Stream.of(
                Arguments.of("OPTIONS", "/api/teams", null, null, METHOD_NOT_ALLOWED, JSON, APIKIT_METHOD_NOT_ALLOWED),
                Arguments.of("HEAD", "/api/teams", null, null, METHOD_NOT_ALLOWED, JSON, ""),
                Arguments.of("OPTIONS", "/api/positions", null, null, METHOD_NOT_ALLOWED, JSON,
                        APIKIT_METHOD_NOT_ALLOWED),
                Arguments.of("HEAD", "/api/fixture/VIL/MAL", null, null, METHOD_NOT_ALLOWED, JSON, ""),
                Arguments.of("PATCH", "/api/teams/RMA", null, null, METHOD_NOT_ALLOWED, JSON,
                        APIKIT_METHOD_NOT_ALLOWED));
        Stream<Arguments> unservedPaths = Stream.of(
                Arguments.of("GET", "/api/unknown", null, null, NOT_FOUND, JSON, APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("GET", "/other", null, null, NOT_FOUND, "text/plain", "No listener for endpoint: /other"),
                Arguments.of("GET", "/apix", null, null, NOT_FOUND, "text/plain", "No listener for endpoint: /apix"));
        Stream<Arguments> console = Stream.of(
                Arguments.of("GET", "/api/", RAML, null, OK, RAML, null),
                Arguments.of("GET", "/api/console/?raml", null, null, OK, RAML, null),
                Arguments.of("GET", "/api/console", null, null, "HTTP/1.1 301 Moved Permanently", null, ""),
                Arguments.of("GET", "/api/console/", null, null, OK, "text/html", null));
        return Stream.of(writesWithoutSuccessBody, undeclaredMethods, unservedPaths, console)
                .flatMap(requests -> requests);
    }

    @ParameterizedTest(name = "{0} {1} with Accept {2} and application/json;q=0")
    @MethodSource("requestsNotNegotiated")
    void requestNotNegotiatedIsAnsweredAsWithoutQualityZero(String method, String path, String acceptPrefix,
                                                            String body, String expectedStatusLine,
                                                            String expectedContentType, String expectedBody) {
        String withQualityZero = acceptPrefix == null ? JSON_Q0 : acceptPrefix + ", " + JSON_Q0;

        HttpTestClient.Response plain = send(method, path, acceptPrefix, body);
        HttpTestClient.Response response = send(method, path, withQualityZero, body);

        assertEquals(expectedStatusLine, plain.statusLine(), plain::toString);
        assertEquals(expectedContentType, plain.header("Content-Type"), plain::toString);
        if (expectedBody != null) {
            assertArrayEquals(bytes(expectedBody), plain.body(), plain::toString);
        }
        assertEquals(plain.statusLine(), response.statusLine(), response::toString);
        assertArrayEquals(plain.body(), response.body(), response::toString);
        assertEquals(headerLines(plain), headerLines(response), response::toString);
    }

    /** Sends {@code method path} with {@code accept} ({@code null} for no header) and the JSON {@code body}. */
    private HttpTestClient.Response send(String method, String path, String accept, String body) {
        List<Map.Entry<String, String>> headers = accept == null ? List.of() : accept(List.of(accept));
        if (body == null) {
            return client.sendHeaderPairs(method, path, headers, null);
        }
        List<Map.Entry<String, String>> withContentType = Stream.concat(headers.stream(),
                Stream.of(header("Content-Type", JSON))).toList();
        return client.sendHeaderPairs(method, path, withContentType, bytes(body));
    }

    /** One {@code Accept} header line per element of {@code lines}. */
    private static List<Map.Entry<String, String>> accept(List<String> lines) {
        return lines.stream().map(line -> header("Accept", line)).toList();
    }

    private static Map.Entry<String, String> header(String name, String value) {
        return new AbstractMap.SimpleImmutableEntry<>(name, value);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
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
