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

import com.mulesoft.examples.testing_apikit_with_munit.support.HttpTestClient;

/**
 * Wire-level test of {@link ApiKitAcceptNegotiator} on the {@code /api/*} listener of {@code api/api.raml}: APIkit's
 * router negotiated the response of each of the four {@code /munit} actions, whose success responses all declare an
 * {@code application/json} body, and answered an {@code Accept} header for which it chose no declared type with its
 * 406 mapping {@code { "message": "Not acceptable" }} (testing-apikit-with-munit/src/main/app/api.xml:26-30), before
 * the flow ran.
 *
 * <p>The cases assert that:
 * <ul>
 *   <li>every form that gives JSON quality 0 gets that 406 on GET, POST, PUT and DELETE;</li>
 *   <li>the forms APIkit accepted, {@code *}{@code /*;q=0} among them, get the flow's status and literal, exactly as
 *       a request without {@code Accept} does;</li>
 *   <li>the methods the RAML does not declare, unserved paths, the console and the RAML descriptor get with
 *       {@code application/json;q=0} exactly what they get without it.</li>
 * </ul>
 *
 * <p>POST and PUT send the zero-length body of the MUnit suite. The cases run the whole application on Undertow
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

    private static final String RESOURCE = "/api/munit";

    private static final String OK = "HTTP/1.1 200 OK";

    private static final String CREATED = "HTTP/1.1 201 Created";

    private static final String NOT_ACCEPTABLE = "HTTP/1.1 406 Not Acceptable";

    private static final String NOT_FOUND = "HTTP/1.1 404 Not Found";

    private static final String METHOD_NOT_ALLOWED = "HTTP/1.1 405 Method Not Allowed";

    private static final String JSON = "application/json";

    private static final String RAML = "application/raml+yaml";

    private static final String JSON_Q0 = "application/json;q=0";

    /** The MUnit {@code munit:set payload="#['']"} the POST and PUT tests send: a zero-length body. */
    private static final byte[] EMPTY_BODY = new byte[0];

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

    /** Each declared method with the body it is sent with ({@code null} for none), with each header not accepted. */
    private static Stream<Arguments> notAcceptedRequests() {
        return Stream.of(Arguments.of("GET", null), Arguments.of("POST", EMPTY_BODY), Arguments.of("PUT", EMPTY_BODY),
                        Arguments.of("DELETE", null))
                .flatMap(request -> NOT_ACCEPTED.stream()
                        .map(accept -> Arguments.of(request.get()[0], request.get()[1], accept)));
    }

    @ParameterizedTest(name = "{0} " + RESOURCE + " with Accept {2} -> APIkit 406")
    @MethodSource("notAcceptedRequests")
    void jsonAtQualityZeroGetsApiKitNotAcceptable(String method, byte[] body, List<String> acceptLines) {
        HttpTestClient.Response response = client.sendHeaderPairs(method, RESOURCE, accept(acceptLines), body);

        assertEquals(NOT_ACCEPTABLE, response.statusLine(), response::toString);
        assertEquals(JSON, response.header("Content-Type"), response::toString);
        assertArrayEquals(bytes(APIKIT_NOT_ACCEPTABLE), response.body(), response::toString);
    }

    /**
     * Each declared method with its body, its RAML status line and the flow literal as JSON, with each header
     * APIkit accepted.
     */
    private static Stream<Arguments> acceptedRequests() {
        return Stream.of(
                        Arguments.of("GET", null, OK, "\"GET RESPONSE\""),
                        Arguments.of("POST", EMPTY_BODY, CREATED, "\"POST RESPONSE\""),
                        Arguments.of("PUT", EMPTY_BODY, CREATED, "\"PUT RESPONSE\""),
                        Arguments.of("DELETE", null, OK, "\"DELETE RESPONSE\""))
                .flatMap(request -> ACCEPTED.stream().map(accept -> Arguments.of(request.get()[0],
                        request.get()[1], request.get()[2], request.get()[3], accept)));
    }

    @ParameterizedTest(name = "{0} " + RESOURCE + " with Accept {4} -> {2}")
    @MethodSource("acceptedRequests")
    void acceptedFormsGetTheFlowResponse(String method, byte[] body, String expectedStatusLine, String expectedBody,
                                         List<String> acceptLines) {
        HttpTestClient.Response withoutAccept = client.send(method, RESOURCE, null, body);
        HttpTestClient.Response response = client.sendHeaderPairs(method, RESOURCE, accept(acceptLines), body);

        assertEquals(expectedStatusLine, withoutAccept.statusLine(), withoutAccept::toString);
        assertArrayEquals(bytes(expectedBody), withoutAccept.body(), withoutAccept::toString);
        assertEquals(expectedStatusLine, response.statusLine(), response::toString);
        assertEquals(withoutAccept.header("Content-Type"), response.header("Content-Type"), response::toString);
        assertArrayEquals(bytes(expectedBody), response.body(), response::toString);
    }

    /**
     * Requests APIkit did not negotiate: the method, the path, the {@code Accept} value sent without and then with
     * {@code application/json;q=0} appended ({@code null} for none), then the status line, {@code Content-Type} and
     * exact body both get, or {@code null} where the body is only compared between the two.
     */
    private static Stream<Arguments> requestsNotNegotiated() {
        Stream<Arguments> undeclaredMethods = Stream.of(
                Arguments.of("OPTIONS", RESOURCE, null, METHOD_NOT_ALLOWED, JSON, APIKIT_METHOD_NOT_ALLOWED),
                Arguments.of("HEAD", RESOURCE, null, METHOD_NOT_ALLOWED, JSON, ""),
                Arguments.of("PATCH", RESOURCE, null, METHOD_NOT_ALLOWED, JSON, APIKIT_METHOD_NOT_ALLOWED));
        Stream<Arguments> unservedPaths = Stream.of(
                Arguments.of("GET", "/api/munit/x", null, NOT_FOUND, JSON, APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("GET", "/api/other", null, NOT_FOUND, JSON, APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("GET", "/other", null, NOT_FOUND, "text/plain", "No listener for endpoint: /other"),
                Arguments.of("GET", "/apix", null, NOT_FOUND, "text/plain", "No listener for endpoint: /apix"));
        Stream<Arguments> console = Stream.of(
                Arguments.of("GET", "/api/", RAML, OK, RAML, null),
                Arguments.of("GET", "/api/console/?raml", null, OK, RAML, null),
                Arguments.of("GET", "/api/console", null, "HTTP/1.1 301 Moved Permanently", null, ""),
                Arguments.of("GET", "/api/console/", null, OK, "text/html", null));
        return Stream.of(undeclaredMethods, unservedPaths, console).flatMap(requests -> requests);
    }

    @ParameterizedTest(name = "{0} {1} with Accept {2} and application/json;q=0")
    @MethodSource("requestsNotNegotiated")
    void requestNotNegotiatedIsAnsweredAsWithoutQualityZero(String method, String path, String acceptPrefix,
                                                            String expectedStatusLine, String expectedContentType,
                                                            String expectedBody) {
        String withQualityZero = acceptPrefix == null ? JSON_Q0 : acceptPrefix + ", " + JSON_Q0;

        HttpTestClient.Response plain = client.sendHeaderPairs(method, path,
                acceptPrefix == null ? List.of() : accept(List.of(acceptPrefix)), null);
        HttpTestClient.Response response = client.sendHeaderPairs(method, path, accept(List.of(withQualityZero)),
                null);

        assertEquals(expectedStatusLine, plain.statusLine(), plain::toString);
        assertEquals(expectedContentType, plain.header("Content-Type"), plain::toString);
        if (expectedBody != null) {
            assertArrayEquals(bytes(expectedBody), plain.body(), plain::toString);
        }
        assertEquals(plain.statusLine(), response.statusLine(), response::toString);
        assertArrayEquals(plain.body(), response.body(), response::toString);
        assertEquals(headerLines(plain), headerLines(response), response::toString);
    }

    /** One {@code Accept} header line per element of {@code lines}. */
    private static List<Map.Entry<String, String>> accept(List<String> lines) {
        return lines.stream()
                .<Map.Entry<String, String>>map(line -> new AbstractMap.SimpleImmutableEntry<>("Accept", line))
                .toList();
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
