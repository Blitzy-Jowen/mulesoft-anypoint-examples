/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.implementing_a_choice_exception_strategy.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.mulesoft.examples.implementing_a_choice_exception_strategy.controller.ValidationController;
import com.mulesoft.examples.implementing_a_choice_exception_strategy.exception.ChoiceExceptionHandler;
import com.mulesoft.examples.implementing_a_choice_exception_strategy.exception.GlobalExceptionHandler;
import com.mulesoft.examples.implementing_a_choice_exception_strategy.service.InputDataValidator;
import com.mulesoft.examples.implementing_a_choice_exception_strategy.service.InputValidationService;
import com.mulesoft.examples.implementing_a_choice_exception_strategy.support.HttpTestClient;

/**
 * Wire-level test of {@link PreflightDispatchConfig} on the {@code /} listener: a CORS pre-flight carrying an order
 * runs the flow and is answered exactly as a plain {@code OPTIONS} request with the same order is, 200
 * {@code Input data validation passed.} with no {@code Content-Type}, as the Mule listener sent the flow's untyped
 * {@code set-payload}, for the original's {@code message.json} and the "Catch Missing Data" branch's 400 with reason
 * {@code Missing input data} and {@code text/plain;charset=UTF-8} for its {@code message_wrong.json}, with no
 * {@code Access-Control-*} or {@code Vary} header, because the original listener declares no {@code allowedMethods}
 * and applies no CORS policy (implementing-a-choice-exception-strategy/src/main/app/choice-error-handling.xml:5-7,13,
 * 31-45).
 *
 * <p>The cases run the real controller, services, the project's {@link ChoiceExceptionHandler} and the SC-1 handler on
 * Undertow ({@link WireConfig}, random port), so {@link ReasonPhrase} writes the branch's reason on the exchange, and
 * send requests through ST-1 {@link HttpTestClient}, so the status line, headers and body bytes are asserted as the
 * listener writes them.
 */
@SpringBootTest(classes = PreflightDispatchListenerTest.WireConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PreflightDispatchListenerTest {

    private static final String PATH = "/";

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
        byte[] order = resource("message.json");
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", PATH, Map.of("Content-Type", JSON), order);

        HttpTestClient.Response preflight = client.send("OPTIONS", PATH,
                preflightHeaders(requestedMethod, requestedHeaders, JSON), order);

        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 200 OK", preflight.statusLine(), preflight::toString);
        assertNull(preflight.header("Content-Type"), preflight::toString);
        assertArrayEquals("Input data validation passed.".getBytes(StandardCharsets.UTF_8), preflight.body(),
                preflight::toString);
        assertNoCorsHeaders(preflight);
    }

    @ParameterizedTest(name = "Access-Control-Request-Method {0}, Access-Control-Request-Headers {1}")
    @MethodSource("preflights")
    void preflightReachesTheMissingDataBranchLikeAPlainOptionsRequest(String requestedMethod, String requestedHeaders)
            throws IOException {
        byte[] order = resource("message_wrong.json");
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", PATH, Map.of("Content-Type", JSON), order);

        HttpTestClient.Response preflight = client.send("OPTIONS", PATH,
                preflightHeaders(requestedMethod, requestedHeaders, JSON), order);

        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 400 Missing input data", preflight.statusLine(), preflight::toString);
        assertEquals("text/plain;charset=UTF-8", preflight.header("Content-Type"), preflight::toString);
        String body = preflight.bodyAsString();
        assertTrue(body.startsWith("Missing input data:"), preflight::toString);
        // The original test's assertions on the rendered payload (ImplementingChoiceExceptionStrategyIT.java:65-73).
        for (String entry : List.of("item price per unit=1", "membership=free", "item name=aa", "item units=10")) {
            assertTrue(body.contains(entry), preflight::toString);
        }
        assertNoCorsHeaders(preflight);
    }

    @Test
    void preflightToAnUnservedPathGetsTheNoListener404() {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", "/unknown", null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", "/unknown", preflightHeaders("POST", null, null),
                null);

        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 404 Not Found", preflight.statusLine(), preflight::toString);
        assertEquals("text/plain", preflight.header("Content-Type"), preflight::toString);
        assertArrayEquals("No listener for endpoint: /unknown".getBytes(StandardCharsets.UTF_8), preflight.body(),
                preflight::toString);
        assertNoCorsHeaders(preflight);
    }

    /** The pre-flight headers, followed by {@code Content-Type} when {@code contentType} is not {@code null}. */
    private static Map<String, String> preflightHeaders(String requestedMethod, String requestedHeaders,
                                                        String contentType) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Origin", ORIGIN);
        headers.put("Access-Control-Request-Method", requestedMethod);
        if (requestedHeaders != null) {
            headers.put("Access-Control-Request-Headers", requestedHeaders);
        }
        if (contentType != null) {
            headers.put("Content-Type", contentType);
        }
        return headers;
    }

    /** The bytes of the original's test resource {@code name}, read from the test classpath. */
    private static byte[] resource(String name) throws IOException {
        try (InputStream in = PreflightDispatchListenerTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(in, name + " is missing from the test classpath");
            return in.readAllBytes();
        }
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
     * The test's whole context: Spring Boot's servlet web stack on Undertow, {@link PreflightDispatchConfig} and the
     * listener's own beans.
     *
     * <p>It names the web auto-configurations rather than enabling auto-configuration and scans nothing, so the
     * context holds the listener and nothing else; and it is not a {@code @SpringBootConfiguration}, so the
     * project's other {@code @SpringBootTest} classes still find exactly one application class.
     */
    @Configuration(proxyBeanMethods = false)
    @Import({PreflightDispatchConfig.class, ValidationController.class, InputValidationService.class,
            InputDataValidator.class, ChoiceExceptionHandler.class, GlobalExceptionHandler.class})
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
