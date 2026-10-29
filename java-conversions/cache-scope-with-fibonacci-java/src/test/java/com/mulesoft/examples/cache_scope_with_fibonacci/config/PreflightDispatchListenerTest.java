/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.cache_scope_with_fibonacci.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
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

import com.mulesoft.examples.cache_scope_with_fibonacci.controller.FibonacciController;
import com.mulesoft.examples.cache_scope_with_fibonacci.exception.GlobalExceptionHandler;
import com.mulesoft.examples.cache_scope_with_fibonacci.model.FibonacciResult;
import com.mulesoft.examples.cache_scope_with_fibonacci.service.FibonacciCacheService;
import com.mulesoft.examples.cache_scope_with_fibonacci.service.FibonacciService;
import com.mulesoft.examples.cache_scope_with_fibonacci.support.HttpTestClient;

/**
 * Wire-level test of {@link PreflightDispatchConfig} on the {@code /fibonacci} listener on {@code localhost}: a CORS
 * pre-flight to {@code /fibonacci?n=10} runs the flow, whatever method it asks for, and gets its reply, 200
 * {@code Fibonacci(10) = 55\nCOST: 11} with {@code cost: 11}, exactly as a plain {@code OPTIONS} request to it does,
 * never Spring's 403 and with no {@code Access-Control-*} or {@code Vary} header, because the original listener
 * declares no {@code allowedMethods}, so it runs the flow for every method, and applies no CORS policy
 * (cache-scope-with-fibonacci/src/main/app/cache-scope.xml:44). An ordinary request carrying {@code Origin} is still
 * dispatched to the flow.
 *
 * <p>The cases run the real controller, {@link FibonacciService}, {@link ListenerPathConfig} and SC-1 handler on
 * Undertow ({@link WireConfig}, random port) and send requests through ST-1 {@link HttpTestClient}, so the status
 * line, headers and body bytes are asserted as the listener writes them. {@link FibonacciCacheService} is a mock
 * returning the original test's result for {@code n=10} with its full cost on every call, so repeated requests get
 * identical replies, where the real cache would answer the second one with cost 0.
 */
@SpringBootTest(classes = PreflightDispatchListenerTest.WireConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PreflightDispatchListenerTest {

    private static final String PATH = "/fibonacci?n=10";

    private static final String N = "10";

    private static final String ORIGIN = "https://example.invalid";

    @LocalServerPort
    private int port;

    @MockBean
    private FibonacciCacheService fibonacciCacheService;

    @BeforeEach
    void stubTheCalculation() {
        // n=10 computed without a cache hit: Fibonacci(10) = 55 at cost 11 (CacheScopeWithFibonacciIT.java:49-52).
        when(fibonacciCacheService.calculate(N, true, true)).thenReturn(new FibonacciResult("55", 11));
    }

    /** Requested method of the pre-flight, then its {@code Access-Control-Request-Headers}, or {@code null}. */
    private static Stream<Arguments> preflights() {
        return Stream.of(
                Arguments.of("GET", null),
                Arguments.of("POST", null),
                Arguments.of("PUT", null),
                Arguments.of("DELETE", null),
                Arguments.of("PATCH", null),
                Arguments.of("OPTIONS", null),
                Arguments.of("POST", "content-type"));
    }

    @ParameterizedTest(name = "Access-Control-Request-Method {0}, Access-Control-Request-Headers {1}")
    @MethodSource("preflights")
    void preflightRunsTheFlowLikeAPlainOptionsRequest(String requestedMethod, String requestedHeaders) {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", PATH, null, null);
        verify(fibonacciCacheService).calculate(N, true, true);

        HttpTestClient.Response preflight = client.send("OPTIONS", PATH,
                preflightHeaders(requestedMethod, requestedHeaders), null);

        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 200 OK", preflight.statusLine(), preflight::toString);
        assertEquals("11", preflight.header("cost"), preflight::toString);
        assertArrayEquals("Fibonacci(10) = 55\nCOST: 11".getBytes(StandardCharsets.UTF_8), preflight.body(),
                preflight::toString);
        assertNoCorsHeaders(preflight);
        // The second calculation is the pre-flight's own run of the flow.
        verify(fibonacciCacheService, times(2)).calculate(N, true, true);
        verifyNoMoreInteractions(fibonacciCacheService);
    }

    @Test
    void requestWithAnOriginStillRunsTheFlow() {
        HttpTestClient client = HttpTestClient.localhost(port);

        HttpTestClient.Response response = client.send("GET", PATH, Map.of("Origin", ORIGIN), null);

        verify(fibonacciCacheService).calculate(N, true, true);
        verifyNoMoreInteractions(fibonacciCacheService);
        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertEquals("11", response.header("cost"), response::toString);
        assertArrayEquals("Fibonacci(10) = 55\nCOST: 11".getBytes(StandardCharsets.UTF_8), response.body(),
                response::toString);
        assertNoCorsHeaders(response);
    }

    /**
     * A path no listener serves, and the listener's path with {@code ;} segment parameters, which
     * {@link ListenerPathConfig} refuses after the pre-flight has selected the {@code OPTIONS} handler, so the flow
     * calculates nothing.
     *
     * @param target the request target, query string included
     * @param path   the path the 404 names: the target without its query string
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"/unknown, /unknown", "/fibonacci;x=1?n=" + N + ", /fibonacci;x=1"})
    void preflightToAnUnservedPathGetsTheNoListener404(String target, String path) {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", target, null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", target, preflightHeaders("POST", null), null);

        verifyNoInteractions(fibonacciCacheService);
        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 404 Not Found", preflight.statusLine(), preflight::toString);
        assertEquals("text/plain", preflight.header("Content-Type"), preflight::toString);
        assertArrayEquals(("No listener for endpoint: " + path).getBytes(StandardCharsets.UTF_8), preflight.body(),
                preflight::toString);
        assertNoCorsHeaders(preflight);
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
     * The test's whole context: Spring Boot's servlet web stack on Undertow, {@link PreflightDispatchConfig},
     * {@link ListenerPathConfig} and the listener's own beans, with the test's mock {@link FibonacciCacheService} in
     * place of the cache-backed service.
     *
     * <p>It names the web auto-configurations rather than enabling auto-configuration and scans nothing, so the
     * context holds the listener and nothing else, no cache manager included; and it is not a
     * {@code @SpringBootConfiguration}, so the project's other {@code @SpringBootTest} classes still find exactly one
     * application class.
     */
    @Configuration(proxyBeanMethods = false)
    @Import({PreflightDispatchConfig.class, ListenerPathConfig.class, FibonacciController.class,
            FibonacciService.class, GlobalExceptionHandler.class})
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
