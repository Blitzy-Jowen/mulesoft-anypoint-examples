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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

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
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.controller.ApiKitConsoleController;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.controller.CurrencyController;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.exception.ApiKitExceptionHandler;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.exception.GlobalExceptionHandler;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.mapper.ParseTemplateRenderer;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.service.CurrencyService;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.support.HttpTestClient;

/**
 * Wire-level test of {@link ApiKitMethodCaseFilter} on the {@code /api/*} listener of {@code api/currency.raml}: a
 * request whose method token is not upper case, such as {@code get} or {@code Get}, gets exactly the answer its
 * upper-case form gets, because the original listener declares no {@code allowedMethods} and its APIkit router
 * lower-cased the method before routing, before the RAML descriptor check and before the console
 * (processing-orders-with-dataweave-and-APIkit/src/main/app/currency.xml).
 *
 * <p>Each case sends the upper-case form and then the token itself to the same path with the same headers and body,
 * and asserts that both get the same status line, body bytes and headers, {@code Date} aside, and what the
 * upper-case form gets: the currency document of {@code GET /api/currencies}; APIkit's 405 {@code application/json}
 * {@code { "message": "Method not allowed" }} for {@code POST}, which {@code /currencies} does not declare; the RAML
 * descriptor; and, outside the base path, where the filter leaves the request alone, the listener's no-listener
 * 404.
 *
 * <p>The cases run the filter, {@link PreflightDispatchConfig}, the real controller and currency service, the APIkit
 * console and the SC-3 and SC-1 handlers on Undertow ({@link WireConfig}, random port), and send requests through
 * ST-1 {@link HttpTestClient}, so the status line, headers and body bytes are asserted as the listener writes them.
 */
@SpringBootTest(classes = ApiKitMethodCaseListenerTest.WireConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiKitMethodCaseListenerTest {

    /** The body of APIkit's 405 mapping, as the original flow's exception strategy wrote it. */
    private static final String APIKIT_METHOD_NOT_ALLOWED = "{ \"message\": \"Method not allowed\" }";

    private static final String RESOURCE = "/api/currencies";

    private static final String OK = "HTTP/1.1 200 OK";

    private static final String RAML = "application/raml+yaml";

    @LocalServerPort
    private int port;

    /**
     * Every request sent with a token that is not upper case: the token, the path, the request headers and body
     * ({@code null} for none), then the status line and {@code Content-Type} its upper-case form gets and that form's
     * exact body, or {@code null} where the body is only compared between the two.
     */
    private static Stream<Arguments> requestsWithANonUpperCaseToken() {
        String currencies = classpathText("currency.json");
        return Stream.of(
                Arguments.of("get", RESOURCE, null, null, OK, "application/json", currencies),
                Arguments.of("Get", RESOURCE, null, null, OK, "application/json", currencies),
                Arguments.of("post", RESOURCE, Map.of("Content-Type", "application/json"), "{}",
                        "HTTP/1.1 405 Method Not Allowed", "application/json", APIKIT_METHOD_NOT_ALLOWED),
                Arguments.of("get", "/api/", Map.of("Accept", RAML), null, OK, RAML, null),
                Arguments.of("get", "/api/console/?raml", null, null, OK, RAML, null),
                Arguments.of("get", "/other", null, null, "HTTP/1.1 404 Not Found", "text/plain",
                        "No listener for endpoint: /other"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("requestsWithANonUpperCaseToken")
    void tokenIsAnsweredAsItsUpperCaseForm(String token, String path, Map<String, String> headers, String body,
                                           String expectedStatusLine, String expectedContentType,
                                           String expectedBody) {
        HttpTestClient client = HttpTestClient.localhost(port);
        byte[] bodyBytes = body == null ? null : body.getBytes(StandardCharsets.UTF_8);

        HttpTestClient.Response upperCase = client.send(token.toUpperCase(Locale.ROOT), path, headers, bodyBytes);
        HttpTestClient.Response response = client.send(token, path, headers, bodyBytes);

        assertEquals(expectedStatusLine, upperCase.statusLine(), upperCase::toString);
        assertEquals(expectedContentType, upperCase.header("Content-Type"), upperCase::toString);
        if (expectedBody != null) {
            assertArrayEquals(expectedBody.getBytes(StandardCharsets.UTF_8), upperCase.body(), upperCase::toString);
        }
        assertEquals(upperCase.statusLine(), response.statusLine(), response::toString);
        assertEquals(upperCase.header("Content-Type"), response.header("Content-Type"), response::toString);
        assertArrayEquals(upperCase.body(), response.body(), response::toString);
        assertEquals(headerLines(upperCase), headerLines(response), response::toString);
    }

    /** Every header but {@code Date} as {@code name: value} with the name lower-cased, sorted, duplicates kept. */
    private static List<String> headerLines(HttpTestClient.Response response) {
        return response.headers().stream()
                .filter(header -> !header.getKey().equalsIgnoreCase("Date"))
                .map(header -> header.getKey().toLowerCase(Locale.ROOT) + ": " + header.getValue())
                .sorted()
                .toList();
    }

    /** The UTF-8 text of the classpath resource {@code location}. */
    private static String classpathText(String location) {
        try {
            return StreamUtils.copyToString(new ClassPathResource(location).getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read classpath resource " + location, e);
        }
    }

    /**
     * The test's whole context: Spring Boot's servlet web stack on Undertow, {@link ApiKitMethodCaseFilter},
     * {@link PreflightDispatchConfig}, the RAML controller with the currency service and its template renderer, the
     * APIkit console, and the SC-3 and SC-1 handlers.
     *
     * <p>It names the web auto-configurations rather than enabling auto-configuration and scans nothing, so the
     * context holds the listener alone, without the order file trigger; and it is not a
     * {@code @SpringBootConfiguration}, so it never competes with the project's application class.
     */
    @Configuration(proxyBeanMethods = false)
    @Import({ApiKitMethodCaseFilter.class, PreflightDispatchConfig.class, CurrencyController.class,
            CurrencyService.class, ParseTemplateRenderer.class, ApiKitConsoleController.class,
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
