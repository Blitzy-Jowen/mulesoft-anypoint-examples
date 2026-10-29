/*
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
import java.io.InputStream;
import java.io.UncheckedIOException;
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
import org.springframework.core.io.ClassPathResource;

import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.support.HttpTestClient;

/**
 * Wire-level test of {@link ApiKitPathParameterConfig} on the {@code /api/*} listener of {@code api/currency.raml}:
 * a {@code ;} in the request path is content of its segment, as it was to the original listener and its APIkit
 * router (processing-orders-with-dataweave-and-APIkit/src/main/app/currency.xml:4-39), so the {@code /currencies}
 * resource is not served under an alias.
 *
 * <p>The cases assert the status line, {@code Content-Type} and exact body bytes of:
 * <ul>
 *   <li>{@code /api/currencies;x=1} and {@code /api/currencies;}, segments that name no resource: APIkit's 404
 *       {@code { "message": "Resource not found" }} for every method, a lower-case token, an {@code Accept} header
 *       excluding JSON and a CORS pre-flight, so the {@code parse-template} flow never answers;</li>
 *   <li>{@code /api/console;x=1}: APIkit's 404 rather than the console's redirect;</li>
 *   <li>outside the base path, {@code /api;x=1/currencies}: the listener's no-listener 404 naming the raw path;</li>
 *   <li>{@code /api/currencies} itself, the path the order flow's {@code CurrencyClient} reads, also with a
 *       {@code ;} in its query string: {@code currency.json} byte for byte.</li>
 * </ul>
 *
 * <p>A lower-case {@code head} is not sent: Undertow compares method tokens ignoring case and so writes no body for
 * it, while ST-1 skips the body only for the exact token {@code HEAD} and would wait for one.
 *
 * <p>The cases run the whole application on Undertow (random port) and send requests through ST-1
 * {@link HttpTestClient}, which writes the request target byte for byte, so the {@code ;} reaches the listener as
 * sent and the status line, headers and body bytes are asserted as the listener writes them. The context also runs
 * the scheduled {@code OrderFileTrigger}, whose directories the surefire configuration points under
 * {@code target/test-orders/}; this class leaves them alone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiKitPathParameterListenerTest {

    /** The body of APIkit's 404 mapping, as the original flow's exception strategy wrote it. */
    private static final String APIKIT_RESOURCE_NOT_FOUND = "{ \"message\": \"Resource not found\" }";

    private static final String RESOURCE = "/api/currencies";

    private static final String OK = "HTTP/1.1 200 OK";

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
                .map(method -> Arguments.of(method, path, null, null, NOT_FOUND, JSON,
                        "HEAD".equals(method) ? "" : APIKIT_RESOURCE_NOT_FOUND)));
        String currencies = classpathText("currency.json");
        Stream<Arguments> aliasWhateverTheRequest = Stream.of(
                Arguments.of("OPTIONS", RESOURCE + ";x=1", preFlight("GET"), null, NOT_FOUND, JSON,
                        APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("get", RESOURCE + ";x=1", null, null, NOT_FOUND, JSON, APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("GET", RESOURCE + ";x=1", Map.of("Accept", "text/xml"), null, NOT_FOUND, JSON,
                        APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("POST", RESOURCE + ";x=1", Map.of("Content-Type", "application/json"), "{}", NOT_FOUND,
                        JSON, APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("GET", RESOURCE + ";jsessionid=1", null, null, NOT_FOUND, JSON,
                        APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("GET", RESOURCE + ";x=1/", null, null, NOT_FOUND, JSON, APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("GET", "/api/console;x=1", null, null, NOT_FOUND, JSON, APIKIT_RESOURCE_NOT_FOUND));
        Stream<Arguments> outsideTheBasePath = Stream.of(
                Arguments.of("GET", "/api;x=1/currencies", null, null, NOT_FOUND, "text/plain",
                        "No listener for endpoint: /api;x=1/currencies"));
        Stream<Arguments> theResourceItself = Stream.of(
                Arguments.of("GET", RESOURCE, null, null, OK, JSON, currencies),
                Arguments.of("GET", RESOURCE + "?x=1;y", null, null, OK, JSON, currencies));
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

    /** The headers of a CORS pre-flight asking for {@code requestedMethod}. */
    private static Map<String, String> preFlight(String requestedMethod) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Origin", "http://example.com");
        headers.put("Access-Control-Request-Method", requestedMethod);
        return headers;
    }

    /** The UTF-8 text of the classpath resource {@code location}. */
    private static String classpathText(String location) {
        try (InputStream in = new ClassPathResource(location).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read classpath resource " + location, e);
        }
    }
}
