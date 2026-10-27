/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.netsuite_data_retrieval.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
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

import com.mulesoft.examples.netsuite_data_retrieval.controller.ApiKitConsoleController;
import com.mulesoft.examples.netsuite_data_retrieval.exception.ApiKitExceptionHandler;
import com.mulesoft.examples.netsuite_data_retrieval.exception.GlobalExceptionHandler;
import com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamExceptionHandler;
import com.mulesoft.examples.netsuite_data_retrieval.support.HttpTestClient;

/**
 * Wire-level test of {@link ApiKitMethodCaseFilter} on the {@code /api/*} listener of {@code api/netsuite-api.raml}:
 * a request whose method token is not upper case, such as {@code get} or {@code Get}, gets exactly the answer its
 * upper-case form gets, because the original listener declares no {@code allowedMethods} and its APIkit router
 * lower-cased the method before routing, before the RAML descriptor check and before the console
 * (netsuite-data-retrieval/src/main/app/netsuite-api.xml:12-21).
 *
 * <p>Each case sends the upper-case form and then the token itself to the same path with the same headers and body,
 * and asserts that both get the same status line, body bytes and headers, {@code Date} aside, and what the
 * upper-case form gets: the RAML descriptor from the API root and the console, a file the RAML includes, the
 * console's redirect, APIkit's 404 {@code application/json} {@code { "message": "Resource not found" }} for a
 * resource the RAML does not declare, and, outside the base path, where the filter leaves the request alone, the
 * listener's no-listener 404.
 *
 * <p>The cases run the filter, the APIkit console and the SC-9, SC-3 and SC-1 handlers on Undertow
 * ({@link WireConfig}, random port, bound to the loopback address ST-1 connects to), and send requests through ST-1
 * {@link HttpTestClient}, so the status line, headers and body bytes are asserted as the listener writes them.
 */
@SpringBootTest(classes = ApiKitMethodCaseListenerTest.WireConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Pins the listener to the address ST-1 connects to; the project binds 0.0.0.0, and localhost may be ::1.
        properties = "server.address=" + ApiKitMethodCaseListenerTest.LOOPBACK)
class ApiKitMethodCaseListenerTest {

    /** IPv4 loopback literal: {@code localhost} may resolve to {@code ::1}, where nothing listens. */
    static final String LOOPBACK = "127.0.0.1";

    /** The body of APIkit's 404 mapping, as the original flow's exception strategy wrote it. */
    private static final String APIKIT_RESOURCE_NOT_FOUND = "{ \"message\": \"Resource not found\" }";

    private static final String OK = "HTTP/1.1 200 OK";

    private static final String NOT_FOUND = "HTTP/1.1 404 Not Found";

    private static final String RAML = "application/raml+yaml";

    @LocalServerPort
    private int port;

    /**
     * Every request sent with a token that is not upper case: the token, the path, the request headers and body
     * ({@code null} for none), then the status line and {@code Content-Type} its upper-case form gets and that form's
     * exact body, or {@code null} where the body is only compared between the two.
     */
    private static Stream<Arguments> requestsWithANonUpperCaseToken() {
        return Stream.of(
                Arguments.of("get", "/api/console/?raml", null, null, OK, RAML, null),
                Arguments.of("Get", "/api/console/?raml", null, null, OK, RAML, null),
                Arguments.of("get", "/api/", Map.of("Accept", RAML), null, OK, RAML, null),
                Arguments.of("get", "/api/customers-response.json", null, null, OK, "application/octet-stream",
                        classpathText("api/customers-response.json")),
                Arguments.of("get", "/api/console", null, null, "HTTP/1.1 301 Moved Permanently", null, ""),
                Arguments.of("get", "/api/nothing", null, null, NOT_FOUND, "application/json",
                        APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("post", "/api/nothing", Map.of("Content-Type", "application/json"), "{}", NOT_FOUND,
                        "application/json", APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("get", "/other", null, null, NOT_FOUND, "text/plain",
                        "No listener for endpoint: /other"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("requestsWithANonUpperCaseToken")
    void tokenIsAnsweredAsItsUpperCaseForm(String token, String path, Map<String, String> headers, String body,
                                           String expectedStatusLine, String expectedContentType,
                                           String expectedBody) {
        HttpTestClient client = new HttpTestClient(LOOPBACK, port);
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
        try (InputStream in = new ClassPathResource(location).getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read classpath resource " + location, e);
        }
    }

    /**
     * The test's whole context: Spring Boot's servlet web stack on Undertow, {@link ApiKitMethodCaseFilter}, the
     * APIkit console, and the SC-9, SC-3 and SC-1 handlers.
     *
     * <p>It names the web auto-configurations rather than enabling auto-configuration and scans nothing, so the
     * context holds the listener alone, without the NetSuite clients; and it is not a
     * {@code @SpringBootConfiguration}, so it never competes with the project's application class.
     */
    @Configuration(proxyBeanMethods = false)
    @Import({ApiKitMethodCaseFilter.class, ApiKitConsoleController.class, UpstreamExceptionHandler.class,
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
