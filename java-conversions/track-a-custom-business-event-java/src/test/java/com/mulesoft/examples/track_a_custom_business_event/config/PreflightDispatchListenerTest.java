/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.track_a_custom_business_event.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.web.embedded.EmbeddedWebServerFactoryCustomizerAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.HttpEncodingAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.mulesoft.examples.track_a_custom_business_event.controller.BusinessEventController;
import com.mulesoft.examples.track_a_custom_business_event.exception.GlobalExceptionHandler;
import com.mulesoft.examples.track_a_custom_business_event.service.BusinessEventTracker;
import com.mulesoft.examples.track_a_custom_business_event.service.PriceDiscountService;
import com.mulesoft.examples.track_a_custom_business_event.support.HttpTestClient;

/**
 * Wire-level test of {@link PreflightDispatchConfig} on the {@code /customBusinessEvents} listener: a CORS pre-flight
 * carrying {@code message.json} runs the flow and is answered exactly as a plain {@code OPTIONS} request with the same
 * body is, 200 {@code 8.5} for shoes at 10, with no {@code Access-Control-*} or {@code Vary} header, because the
 * original listener declares no {@code allowedMethods} and applies no CORS policy
 * (track-a-custom-business-event/src/main/app/custom-business-events.xml:5).
 *
 * <p>The cases run the real controller, services, {@link ListenerPathConfig} and SC-1 handler on Undertow
 * ({@link WireConfig}, random port) and send requests through ST-1 {@link HttpTestClient}, so the status line,
 * headers and body bytes are asserted as the listener writes them. The response Content-Type is compared between the
 * two requests only, not to a fixed value.
 */
@SpringBootTest(classes = PreflightDispatchListenerTest.WireConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PreflightDispatchListenerTest {

    private static final String PATH = "/customBusinessEvents";

    private static final String ORIGIN = "https://example.invalid";

    private static final String JSON = "application/json";

    @LocalServerPort
    private int port;

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
    void preflightRunsTheFlowLikeAPlainOptionsRequest(String requestedMethod, String requestedHeaders)
            throws IOException {
        HttpTestClient client = HttpTestClient.localhost(port);
        byte[] message = message();
        Map<String, String> plainHeaders = new LinkedHashMap<>();
        plainHeaders.put("Content-Type", JSON);
        HttpTestClient.Response plain = client.send("OPTIONS", PATH, plainHeaders, message);

        Map<String, String> headers = preflightHeaders(requestedMethod, requestedHeaders);
        headers.put("Content-Type", JSON);
        HttpTestClient.Response preflight = client.send("OPTIONS", PATH, headers, message);

        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 200 OK", preflight.statusLine(), preflight::toString);
        assertArrayEquals("8.5".getBytes(StandardCharsets.UTF_8), preflight.body(), preflight::toString);
        assertNoCorsHeaders(preflight);
    }

    /**
     * A path no listener serves, and the listener's path with {@code ;} segment parameters, which
     * {@link ListenerPathConfig} refuses after the pre-flight has selected the {@code OPTIONS} handler.
     *
     * @param path the unserved request path
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/unknown", PATH + ";x=1"})
    void preflightToAnUnservedPathGetsTheNoListener404(String path) {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", path, null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", path, preflightHeaders("POST", null), null);

        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 404 Not Found", preflight.statusLine(), preflight::toString);
        assertEquals("text/plain", preflight.header("Content-Type"), preflight::toString);
        assertArrayEquals(("No listener for endpoint: " + path).getBytes(StandardCharsets.UTF_8), preflight.body(),
                preflight::toString);
        assertNoCorsHeaders(preflight);
    }

    /** The bytes of the ported {@code message.json} (shoes, 2 units, price 10), unchanged. */
    private static byte[] message() throws IOException {
        try (InputStream in = PreflightDispatchListenerTest.class.getResourceAsStream("/message.json")) {
            assertNotNull(in, "message.json is on the test classpath");
            return in.readAllBytes();
        }
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
     * {@link ListenerPathConfig} and the listener's own beans.
     *
     * <p>It names the web auto-configurations rather than enabling auto-configuration and scans nothing, so the
     * context holds the listener and nothing else; and it is not a {@code @SpringBootConfiguration}, so the
     * project's other {@code @SpringBootTest} classes still find exactly one application class.
     */
    @Configuration(proxyBeanMethods = false)
    @Import({PreflightDispatchConfig.class, ListenerPathConfig.class, BusinessEventController.class,
            PriceDiscountService.class, BusinessEventTracker.class, GlobalExceptionHandler.class})
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
