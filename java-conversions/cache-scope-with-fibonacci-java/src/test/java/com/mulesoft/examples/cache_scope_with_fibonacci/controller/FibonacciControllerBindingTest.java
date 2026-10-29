package com.mulesoft.examples.cache_scope_with_fibonacci.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.web.embedded.EmbeddedWebServerFactoryCustomizerAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.HttpEncodingAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.mulesoft.examples.cache_scope_with_fibonacci.exception.GlobalExceptionHandler;
import com.mulesoft.examples.cache_scope_with_fibonacci.service.FibonacciCacheService;
import com.mulesoft.examples.cache_scope_with_fibonacci.service.FibonacciService;
import com.mulesoft.examples.cache_scope_with_fibonacci.support.HttpTestClient;

/**
 * {@link FibonacciController}'s request binding on the wire: which handler a method reaches and which query value
 * the flow reads, through the project's ST-1 client on Undertow.
 *
 * <ul>
 *   <li>{@code OPTIONS} runs the flow like every other method, because the listener of
 *       cache-scope-with-fibonacci/src/main/app/cache-scope.xml:44 declares no {@code allowedMethods}: the reply
 *       carries the calculation and its {@code cost} header, never Spring MVC's own {@code Allow} answer.</li>
 *   <li>A repeated query parameter takes its last occurrence, a bare name's null included, as Mule 3.8.0's
 *       {@code ParameterMap.get} returned it for {@code http.query.params.n} and {@code .nocache}.</li>
 * </ul>
 *
 * <p>Every case starts on an empty cache, so its cost follows {@link FibonacciCacheService}'s cold-cache model:
 * {@code n >= 2} costs {@code n + 1}; with the {@code nocache} query parameter the two direct children bypass the
 * cache, so {@code n = 10} costs 12: 9 bypasses and costs 10 (8 cold at 9, 7 a hit at 0, plus 1), 8 then
 * bypasses and costs 1 (two hits, plus 1), plus 1.
 */
@SpringBootTest(
        classes = FibonacciControllerBindingTest.WireConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Pins the listener to the address the client connects to; application.yml binds localhost.
        properties = "server.address=" + FibonacciControllerBindingTest.LOOPBACK)
class FibonacciControllerBindingTest {

    /** IPv4 loopback literal: {@code localhost} may resolve to {@code ::1}, where nothing listens. */
    static final String LOOPBACK = "127.0.0.1";

    private static final String TOO_HIGH = "ERROR: n must be less than 20";

    @LocalServerPort
    int port;

    @Autowired
    CacheManager cacheManager;

    private HttpTestClient client;

    @BeforeEach
    void setUp() {
        Cache cache = cacheManager.getCache(FibonacciCacheService.CACHE_NAME);
        assertThat(cache).as("cache %s", FibonacciCacheService.CACHE_NAME).isNotNull();
        cache.clear();
        client = new HttpTestClient(LOOPBACK, port);
    }

    @ParameterizedTest(name = "OPTIONS {0} body {1}")
    @MethodSource("optionsCases")
    void optionsRunsTheFlowLikeEveryOtherMethod(String path, String requestBody, String expectedBody,
                                                String expectedCost) {
        Map<String, String> headers = requestBody == null ? null : Map.of("Content-Type", "text/plain");
        byte[] body = requestBody == null ? null : requestBody.getBytes(StandardCharsets.UTF_8);

        HttpTestClient.Response response = client.send("OPTIONS", path, headers, body);

        assertFlowReply(response, expectedBody, expectedCost);
    }

    /** Request target, request body (sent as {@code text/plain}, or none), then the reply body and cost header. */
    private static Stream<Arguments> optionsCases() {
        return Stream.of(
                Arguments.of("/fibonacci?n=10", null, "Fibonacci(10) = 55\nCOST: 11", "11"),
                Arguments.of("/fibonacci?n=20", null, TOO_HIGH, null),
                // The favicon wildcard-filter tests the payload: a rejected message is an empty 200.
                Arguments.of("/fibonacci?n=10", "/favicon.ico", "", null));
    }

    @Test
    void optionsWithNonNumericNReachesTheGeneric500() {
        HttpTestClient.Response response = client.send("OPTIONS", "/fibonacci?n=abc", null, null);

        assertThat(response.statusLine()).as(response.toString()).isEqualTo("HTTP/1.1 500 Internal Server Error");
        assertThat(response.headers("Content-Type")).as(response.toString()).containsExactly("application/json");
        assertThat(response.headers("cost")).as(response.toString()).isEmpty();
        assertThat(response.headers("Allow")).as(response.toString()).isEmpty();
        assertThat(response.body()).as(response.toString())
                .isEqualTo("{ \"message\": \"Invalid value for n: abc\" }".getBytes(StandardCharsets.UTF_8));
    }

    @ParameterizedTest(name = "GET {0}")
    @MethodSource("repeatedParameterCases")
    void repeatedQueryParameterTakesItsLastOccurrence(String path, String expectedBody, String expectedCost) {
        HttpTestClient.Response response = client.get(path);

        assertFlowReply(response, expectedBody, expectedCost);
    }

    /** Request target, then the reply body and cost header; a bare name is null and an empty value is not. */
    private static Stream<Arguments> repeatedParameterCases() {
        return Stream.of(
                Arguments.of("/fibonacci?n=5&n=10", "Fibonacci(10) = 55\nCOST: 11", "11"),
                Arguments.of("/fibonacci?n=10&n=5", "Fibonacci(5) = 5\nCOST: 6", "6"),
                Arguments.of("/fibonacci?n&n=5", "Fibonacci(5) = 5\nCOST: 6", "6"),
                Arguments.of("/fibonacci?n=5&n", TOO_HIGH, null),
                Arguments.of("/fibonacci?n=10&nocache=true", "Fibonacci(10) = 55\nCOST: 12", "12"),
                Arguments.of("/fibonacci?n=10&nocache=true&nocache", "Fibonacci(10) = 55\nCOST: 11", "11"),
                Arguments.of("/fibonacci?n=10&nocache&nocache=true", "Fibonacci(10) = 55\nCOST: 12", "12"),
                Arguments.of("/fibonacci?n=10&nocache=&nocache", "Fibonacci(10) = 55\nCOST: 11", "11"),
                Arguments.of("/fibonacci?n=10&nocache&nocache=", "Fibonacci(10) = 55\nCOST: 12", "12"));
    }

    /**
     * Asserts the reply the controller writes for a flow run: 200 with the exact body bytes and their
     * Content-Length, the {@code cost} header only when {@code expectedCost} is not null, and no Content-Type,
     * {@code Allow} or {@code Accept-Patch} header.
     */
    private static void assertFlowReply(HttpTestClient.Response response, String expectedBody, String expectedCost) {
        byte[] expectedBytes = expectedBody.getBytes(StandardCharsets.UTF_8);
        String description = response.toString();

        assertThat(response.statusLine()).as(description).isEqualTo("HTTP/1.1 200 OK");
        if (expectedCost == null) {
            assertThat(response.headers("cost")).as(description).isEmpty();
        } else {
            assertThat(response.headers("cost")).as(description).containsExactly(expectedCost);
        }
        assertThat(response.headers("Content-Length")).as(description)
                .containsExactly(Integer.toString(expectedBytes.length));
        assertThat(response.headers("Content-Type")).as(description).isEmpty();
        assertThat(response.headers("Allow")).as(description).isEmpty();
        assertThat(response.headers("Accept-Patch")).as(description).isEmpty();
        assertThat(response.body()).as(description).isEqualTo(expectedBytes);
    }

    /**
     * The test's whole context: Spring Boot's servlet web stack on Undertow and the cache over Caffeine that
     * {@code application.yml} configures, with the controller, the two services and SC-1.
     *
     * <p>It names the auto-configurations rather than enabling auto-configuration and scans nothing, and it is
     * not a {@code @SpringBootConfiguration}, so the project's {@code @SpringBootTest} classes that look up the
     * application class still find exactly one.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    @Import({FibonacciController.class, FibonacciService.class, FibonacciCacheService.class,
            GlobalExceptionHandler.class})
    @ImportAutoConfiguration({
            PropertyPlaceholderAutoConfiguration.class,
            ServletWebServerFactoryAutoConfiguration.class,
            EmbeddedWebServerFactoryCustomizerAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
            HttpEncodingAutoConfiguration.class,
            CacheAutoConfiguration.class})
    static class WireConfig {
    }
}
