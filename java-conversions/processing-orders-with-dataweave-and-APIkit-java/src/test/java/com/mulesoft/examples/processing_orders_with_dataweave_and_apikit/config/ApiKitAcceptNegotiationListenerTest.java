/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
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
import org.springframework.core.io.ClassPathResource;

import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.support.HttpTestClient;

/**
 * Wire-level test of {@link ApiKitAcceptNegotiator} on the {@code /api/*} listener of {@code api/currency.raml}:
 * APIkit's router negotiated the response of {@code GET /currencies}, whose success response declares an
 * {@code application/json} body, and answered an {@code Accept} header for which it chose no declared type with its
 * 406 mapping {@code { "message": "Not acceptable" }}
 * (processing-orders-with-dataweave-and-APIkit/src/main/app/currency.xml:21-25), before the flow ran.
 *
 * <p>The cases assert that:
 * <ul>
 *   <li>every form that gives JSON quality 0 gets that 406;</li>
 *   <li>the forms APIkit accepted, {@code *}{@code /*;q=0} among them, get {@code currency.json} byte for byte, as
 *       a request without {@code Accept} does;</li>
 *   <li>the methods the RAML does not declare, unserved paths, the console and the RAML descriptor get with
 *       {@code application/json;q=0} exactly what they get without it.</li>
 * </ul>
 *
 * <p>The class only reads. The whole application runs on Undertow (random port), so the scheduled
 * {@code OrderFileTrigger} also runs, on the directories the surefire configuration points under
 * {@code target/test-orders/}, as for {@code CurrencyControllerTest}; this class leaves them alone. Requests go
 * through ST-1 {@link HttpTestClient}, so the status line, headers and body bytes are asserted as the listener writes
 * them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiKitAcceptNegotiationListenerTest {

    /** The body of APIkit's 406 mapping, as the original flow's exception strategy wrote it. */
    private static final String APIKIT_NOT_ACCEPTABLE = "{ \"message\": \"Not acceptable\" }";

    /** The body of APIkit's 405 mapping, as the original flow's exception strategy wrote it. */
    private static final String APIKIT_METHOD_NOT_ALLOWED = "{ \"message\": \"Method not allowed\" }";

    /** The body of APIkit's 404 mapping, as the original flow's exception strategy wrote it. */
    private static final String APIKIT_RESOURCE_NOT_FOUND = "{ \"message\": \"Resource not found\" }";

    private static final String RESOURCE = "/api/currencies";

    /** Classpath location of the template the flow's {@code parse-template} returns. */
    private static final String TEMPLATE_LOCATION = "currency.json";

    private static final String OK = "HTTP/1.1 200 OK";

    private static final String NOT_ACCEPTABLE = "HTTP/1.1 406 Not Acceptable";

    private static final String NOT_FOUND = "HTTP/1.1 404 Not Found";

    private static final String METHOD_NOT_ALLOWED = "HTTP/1.1 405 Method Not Allowed";

    private static final String JSON = "application/json";

    private static final String RAML = "application/raml+yaml";

    private static final String JSON_Q0 = "application/json;q=0";

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

    private static Stream<List<String>> notAcceptedHeaders() {
        return NOT_ACCEPTED.stream();
    }

    @ParameterizedTest(name = "GET " + RESOURCE + " with Accept {0} -> APIkit 406")
    @MethodSource("notAcceptedHeaders")
    void jsonAtQualityZeroGetsApiKitNotAcceptable(List<String> acceptLines) {
        HttpTestClient.Response response = client.sendHeaderPairs("GET", RESOURCE, accept(acceptLines), null);

        assertEquals(NOT_ACCEPTABLE, response.statusLine(), response::toString);
        assertEquals(JSON, response.header("Content-Type"), response::toString);
        assertArrayEquals(bytes(APIKIT_NOT_ACCEPTABLE), response.body(), response::toString);
    }

    private static Stream<List<String>> acceptedHeaders() {
        return ACCEPTED.stream();
    }

    @ParameterizedTest(name = "GET " + RESOURCE + " with Accept {0} -> 200 currency.json")
    @MethodSource("acceptedHeaders")
    void acceptedFormsGetTheCurrencies(List<String> acceptLines) throws IOException {
        byte[] currencies = new ClassPathResource(TEMPLATE_LOCATION).getContentAsByteArray();

        HttpTestClient.Response withoutAccept = client.get(RESOURCE);
        HttpTestClient.Response response = client.sendHeaderPairs("GET", RESOURCE, accept(acceptLines), null);

        assertEquals(OK, withoutAccept.statusLine(), withoutAccept::toString);
        assertArrayEquals(currencies, withoutAccept.body(), withoutAccept::toString);
        assertEquals(OK, response.statusLine(), response::toString);
        assertEquals(withoutAccept.header("Content-Type"), response.header("Content-Type"), response::toString);
        assertArrayEquals(currencies, response.body(), response::toString);
    }

    /**
     * Requests APIkit did not negotiate: the method, the path, the {@code Accept} value sent without and then with
     * {@code application/json;q=0} appended ({@code null} for none), the JSON request body ({@code null} for none),
     * then the status line, {@code Content-Type} and exact body both get, or {@code null} where the body is only
     * compared between the two.
     */
    private static Stream<Arguments> requestsNotNegotiated() {
        Stream<Arguments> undeclaredMethods = Stream.of(
                Arguments.of("POST", RESOURCE, null, "{}", METHOD_NOT_ALLOWED, JSON, APIKIT_METHOD_NOT_ALLOWED),
                Arguments.of("OPTIONS", RESOURCE, null, null, METHOD_NOT_ALLOWED, JSON, APIKIT_METHOD_NOT_ALLOWED),
                Arguments.of("HEAD", RESOURCE, null, null, METHOD_NOT_ALLOWED, JSON, ""));
        Stream<Arguments> unservedPaths = Stream.of(
                Arguments.of("GET", "/api/unknown", null, null, NOT_FOUND, JSON, APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("GET", "/other", null, null, NOT_FOUND, "text/plain", "No listener for endpoint: /other"),
                Arguments.of("GET", "/apix", null, null, NOT_FOUND, "text/plain", "No listener for endpoint: /apix"));
        Stream<Arguments> console = Stream.of(
                Arguments.of("GET", "/api/", RAML, null, OK, RAML, null),
                Arguments.of("GET", "/api/console/?raml", null, null, OK, RAML, null),
                Arguments.of("GET", "/api/console", null, null, "HTTP/1.1 301 Moved Permanently", null, ""),
                Arguments.of("GET", "/api/console/", null, null, OK, "text/html", null));
        return Stream.of(undeclaredMethods, unservedPaths, console).flatMap(requests -> requests);
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
