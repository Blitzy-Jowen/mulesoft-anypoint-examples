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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.web.embedded.EmbeddedWebServerFactoryCustomizerAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.HttpEncodingAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.mulesoft.examples.testing_apikit_with_munit.controller.ApiKitConsoleController;
import com.mulesoft.examples.testing_apikit_with_munit.controller.MunitController;
import com.mulesoft.examples.testing_apikit_with_munit.exception.ApiKitExceptionHandler;
import com.mulesoft.examples.testing_apikit_with_munit.exception.GlobalExceptionHandler;
import com.mulesoft.examples.testing_apikit_with_munit.service.MunitService;
import com.mulesoft.examples.testing_apikit_with_munit.support.HttpTestClient;

/**
 * Wire-level test of {@link PreflightDispatchConfig} on the {@code /api/*} listener of {@code api/api.raml}: a CORS
 * pre-flight to {@code /api/munit} is answered exactly as a plain {@code OPTIONS} request is, with APIkit's 405
 * {@code application/json} {@code { "message": "Method not allowed" }} and no {@code Access-Control-*} or
 * {@code Vary} header, and no flow runs, whatever method it asks for, because the original listener declares no
 * {@code allowedMethods} and applies no CORS policy, and its APIkit router answered every method the resource does
 * not declare with that 405 (testing-apikit-with-munit/src/main/app/api.xml).
 *
 * <p>A pre-flight asking for {@code GET}, {@code POST}, {@code PUT} or {@code DELETE}, the methods {@code /munit}
 * declares, would otherwise select that method's handler and be refused by Spring with 403
 * {@code Invalid CORS request}.
 *
 * <p>The cases run the real controller, the APIkit console and the SC-3 and SC-1 handlers on Undertow
 * ({@link WireConfig}, random port) with {@link MunitService} mocked, so any flow that ran would be recorded, and send
 * requests through ST-1 {@link HttpTestClient}, so the status line, headers and body bytes are asserted as the
 * listener writes them.
 */
@SpringBootTest(classes = PreflightDispatchListenerTest.WireConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PreflightDispatchListenerTest {

    private static final String PATH = "/api/munit";

    private static final String ORIGIN = "https://example.invalid";

    /** The body of APIkit's 405 mapping, as the original flow's exception strategy wrote it. */
    private static final String APIKIT_METHOD_NOT_ALLOWED = "{ \"message\": \"Method not allowed\" }";

    @MockBean
    private MunitService munitService;

    @LocalServerPort
    private int port;

    /** Every pre-flight sent to {@code /api/munit}. */
    private static Stream<Arguments> preflightsToTheResource() {
        return preflightsTo(PATH);
    }

    @ParameterizedTest(name = "{0}, Access-Control-Request-Method {1}, Access-Control-Request-Headers {2}")
    @MethodSource("preflightsToTheResource")
    void preflightGetsTheApiKit405LikeAPlainOptionsRequest(String path, String requestedMethod,
                                                           String requestedHeaders) {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", path, null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", path,
                preflightHeaders(requestedMethod, requestedHeaders), null);

        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 405 Method Not Allowed", preflight.statusLine(), preflight::toString);
        assertEquals("application/json", preflight.header("Content-Type"), preflight::toString);
        assertArrayEquals(APIKIT_METHOD_NOT_ALLOWED.getBytes(StandardCharsets.UTF_8), preflight.body(),
                preflight::toString);
        assertNoCorsHeaders(preflight);
        verifyNoInteractions(munitService);
    }

    /** Every pre-flight sent to the API root, where the APIkit console serves the RAML descriptor. */
    private static Stream<Arguments> preflightsToTheApiRoot() {
        return preflightsTo("/api/");
    }

    /**
     * The API root's plain {@code OPTIONS} response is the APIkit console's contract, not this mapping's, so only
     * the pre-flight's equality with it is asserted.
     */
    @ParameterizedTest(name = "{0}, Access-Control-Request-Method {1}, Access-Control-Request-Headers {2}")
    @MethodSource("preflightsToTheApiRoot")
    void preflightToTheApiRootIsAnsweredLikeAPlainOptionsRequest(String path, String requestedMethod,
                                                                 String requestedHeaders) {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", path, null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", path,
                preflightHeaders(requestedMethod, requestedHeaders), null);

        assertSameResponse(plain, preflight);
        assertNotEquals(403, preflight.status(), preflight::toString);
        assertNoCorsHeaders(preflight);
        verifyNoInteractions(munitService);
    }

    @Test
    void preflightToAnUnservedPathGetsTheNoListener404() {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", "/nope", null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", "/nope", preflightHeaders("POST", null), null);

        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 404 Not Found", preflight.statusLine(), preflight::toString);
        assertEquals("text/plain", preflight.header("Content-Type"), preflight::toString);
        assertArrayEquals("No listener for endpoint: /nope".getBytes(StandardCharsets.UTF_8), preflight.body(),
                preflight::toString);
        assertNoCorsHeaders(preflight);
        verifyNoInteractions(munitService);
    }

    @Test
    void crossOriginGetStillReachesTheController() {
        String payload = "\"GET RESPONSE\"";
        when(munitService.get()).thenReturn(payload);

        HttpTestClient.Response response = HttpTestClient.localhost(port)
                .send("GET", PATH, Map.of("Origin", ORIGIN), null);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertEquals("application/json", response.header("Content-Type"), response::toString);
        assertArrayEquals(payload.getBytes(StandardCharsets.UTF_8), response.body(), response::toString);
        assertNoCorsHeaders(response);
        verify(munitService).get();
        verifyNoMoreInteractions(munitService);
    }

    /**
     * Every pre-flight sent to each of {@code paths}: one per requested method, then one also asking for the
     * {@code content-type} header.
     */
    private static Stream<Arguments> preflightsTo(String... paths) {
        return Stream.of(paths).flatMap(path -> Stream.of(
                Arguments.of(path, "GET", null),
                Arguments.of(path, "POST", null),
                Arguments.of(path, "PUT", null),
                Arguments.of(path, "DELETE", null),
                Arguments.of(path, "PATCH", null),
                Arguments.of(path, "OPTIONS", null),
                Arguments.of(path, "POST", "content-type")));
    }

    private static Map<String, String> preflightHeaders(String requestedMethod, String requestedHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Origin", ORIGIN);
        headers.put("Access-Control-Request-Method", requestedMethod);
        if (requestedHeaders != null) {
            headers.put("Access-Control-Request-Headers", requestedHeaders);
        }
        return headers;
    }

    /** Same status line, same body bytes and the same headers but {@code Date}, compared as sorted lines. */
    private static void assertSameResponse(HttpTestClient.Response expected, HttpTestClient.Response actual) {
        assertEquals(expected.statusLine(), actual.statusLine(), actual::toString);
        assertArrayEquals(expected.body(), actual.body(), actual::toString);
        assertEquals(headerLines(expected), headerLines(actual), actual::toString);
    }

    /** Every header but {@code Date} as {@code name: value} with the name lower-cased, sorted, duplicates kept. */
    private static List<String> headerLines(HttpTestClient.Response response) {
        return response.headers().stream()
                .filter(header -> !header.getKey().equalsIgnoreCase("Date"))
                .map(header -> header.getKey().toLowerCase(Locale.ROOT) + ": " + header.getValue())
                .sorted()
                .toList();
    }

    private static void assertNoCorsHeaders(HttpTestClient.Response response) {
        assertTrue(response.headers().stream().map(Map.Entry::getKey).noneMatch(name ->
                        name.regionMatches(true, 0, "Access-Control-", 0, "Access-Control-".length())
                                || name.equalsIgnoreCase("Vary")),
                response::toString);
    }

    /**
     * The test's whole context: Spring Boot's servlet web stack on Undertow, {@link PreflightDispatchConfig}, the
     * RAML controller, the APIkit console, and the SC-3 and SC-1 handlers, with {@link MunitService} supplied by the
     * test as a mock.
     *
     * <p>It names the web auto-configurations rather than enabling auto-configuration and scans nothing, so the
     * context holds the listener alone; and it is not a {@code @SpringBootConfiguration}, so it never competes with
     * the project's application class.
     */
    @Configuration(proxyBeanMethods = false)
    @Import({PreflightDispatchConfig.class, MunitController.class, ApiKitConsoleController.class,
            ApiKitExceptionHandler.class, GlobalExceptionHandler.class})
    @ImportAutoConfiguration({
            PropertyPlaceholderAutoConfiguration.class,
            ServletWebServerFactoryAutoConfiguration.class,
            EmbeddedWebServerFactoryCustomizerAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
            HttpEncodingAutoConfiguration.class})
    static class WireConfig {
    }
}
