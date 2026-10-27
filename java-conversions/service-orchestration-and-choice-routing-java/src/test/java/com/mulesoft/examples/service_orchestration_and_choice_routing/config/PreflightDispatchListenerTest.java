/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

import com.mulesoft.examples.service_orchestration_and_choice_routing.controller.ProductPriceController;
import com.mulesoft.examples.service_orchestration_and_choice_routing.exception.GlobalExceptionHandler;
import com.mulesoft.examples.service_orchestration_and_choice_routing.service.ProductPriceService;
import com.mulesoft.examples.service_orchestration_and_choice_routing.support.HttpTestClient;

/**
 * Wire-level test of {@link PreflightDispatchConfig} on the {@code POST}-only price listener
 * {@code /api/prices/{productId}}: a CORS pre-flight gets the listener's 405 {@code text/plain}
 * {@code Method not allowed for endpoint: <path>}, exactly as a plain {@code OPTIONS} request to the same path does,
 * never Spring's 403 {@code Invalid CORS request}, with no {@code Access-Control-*} or {@code Vary} header, and the
 * price lookup is never run, whatever method it asks for, a requested {@code POST} included, because the original
 * listener allows {@code POST} only and applies no CORS policy
 * (service-orchestration-and-choice-routing/src/main/app/fulfillment.xml:145).
 *
 * <p>The cases run the real controller and SC-1 handler on Undertow ({@link WireConfig}, random port) with
 * {@link ProductPriceService} mocked, so any price lookup would be recorded, and send requests through ST-1
 * {@link HttpTestClient}, so the status line, headers and body bytes are asserted as the listener writes them.
 */
@SpringBootTest(classes = PreflightDispatchListenerTest.WireConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PreflightDispatchListenerTest {

    private static final String PATH = "/api/prices/1";

    private static final String PRODUCT_ID = "1";

    private static final String ORIGIN = "https://example.invalid";

    @MockBean
    private ProductPriceService productPriceService;

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
    void preflightGetsTheListener405LikeAPlainOptionsRequest(String requestedMethod, String requestedHeaders) {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", PATH, null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", PATH,
                preflightHeaders(requestedMethod, requestedHeaders), null);

        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 405 Method Not Allowed", preflight.statusLine(), preflight::toString);
        assertEquals("text/plain", preflight.header("Content-Type"), preflight::toString);
        assertArrayEquals("Method not allowed for endpoint: /api/prices/1".getBytes(StandardCharsets.UTF_8),
                preflight.body(), preflight::toString);
        assertNoCorsHeaders(preflight);
        verifyNoInteractions(productPriceService);
    }

    @Test
    void crossOriginPostStillReachesTheController() {
        when(productPriceService.getProductPrice(PRODUCT_ID)).thenReturn("159");

        HttpTestClient.Response response = HttpTestClient.localhost(port).send("POST", PATH,
                Map.of("Origin", ORIGIN), new byte[0]);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertEquals("text/plain", response.header("Content-Type"), response::toString);
        assertArrayEquals("159".getBytes(StandardCharsets.UTF_8), response.body(), response::toString);
        assertNoCorsHeaders(response);
        verify(productPriceService).getProductPrice(PRODUCT_ID);
        verifyNoMoreInteractions(productPriceService);
    }

    @Test
    void preflightToAnUnservedPathGetsTheNoListener404() {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", "/api/unknown", null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", "/api/unknown", preflightHeaders("POST", null),
                null);

        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 404 Not Found", preflight.statusLine(), preflight::toString);
        assertEquals("text/plain", preflight.header("Content-Type"), preflight::toString);
        assertArrayEquals("No listener for endpoint: /api/unknown".getBytes(StandardCharsets.UTF_8),
                preflight.body(), preflight::toString);
        assertNoCorsHeaders(preflight);
        verifyNoInteractions(productPriceService);
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
     * The test's whole context: Spring Boot's servlet web stack on Undertow, {@link PreflightDispatchConfig} and the
     * price listener's own beans, with {@link ProductPriceService} supplied by the test as a mock.
     *
     * <p>It names the web auto-configurations rather than enabling auto-configuration and scans nothing, so the
     * context holds the price listener and no broker, database, transaction manager or SOAP endpoint; it leaves out
     * SC-10's listener routing, which serves {@code /api/**} on the price port alone and would refuse the random
     * port; and it is not a {@code @SpringBootConfiguration}, so the project's other {@code @SpringBootTest} classes
     * still find exactly one application class.
     */
    @Configuration(proxyBeanMethods = false)
    @Import({PreflightDispatchConfig.class, ProductPriceController.class, GlobalExceptionHandler.class})
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
