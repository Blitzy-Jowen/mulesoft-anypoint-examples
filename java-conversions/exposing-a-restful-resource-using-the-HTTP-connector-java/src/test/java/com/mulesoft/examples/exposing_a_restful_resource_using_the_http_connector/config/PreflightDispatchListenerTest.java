/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
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
import org.springframework.boot.autoconfigure.web.servlet.MultipartAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.controller.PersonController;
import com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.exception.GlobalExceptionHandler;
import com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.exception.PersonExceptionHandler;
import com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.service.PersonService;
import com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.support.HttpTestClient;

/**
 * Wire-level test of {@link PreflightDispatchConfig} on the person resource's listeners, {@code /person}
 * ({@code GET} and {@code POST}) and {@code /person/{personId}} ({@code GET})
 * (exposing-a-restful-resource-using-the-HTTP-connector/src/main/app/http-restful-resource.xml:11,16,28): a CORS
 * pre-flight is answered exactly as a plain {@code OPTIONS} request to its path is, with the listener's 405
 * {@code text/plain} {@code Method not allowed for endpoint: <path>} and no {@code Access-Control-*} or {@code Vary}
 * header, and no flow runs, whatever origin it comes from and whatever method it asks for, because the original
 * listeners apply no CORS policy.
 *
 * <p>The cases run the real controller, {@link PersonService}, {@link PersonExceptionHandler} and SC-1 handler on
 * Undertow ({@link WireConfig}, random port). The service is a Mockito spy, so it keeps its real behaviour while any
 * flow step a request reaches is recorded. Requests go through ST-1 {@link HttpTestClient}, so the status line,
 * headers and body bytes are asserted as the listener writes them.
 */
@SpringBootTest(classes = PreflightDispatchListenerTest.WireConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Pins the listener to IPv4 loopback whatever server.address the project's application.yml sets.
        properties = "server.address=127.0.0.1")
class PreflightDispatchListenerTest {

    private static final String ORIGIN = "https://example.invalid";

    @SpyBean
    private PersonService personService;

    @LocalServerPort
    private int port;

    /**
     * Path of the pre-flight, its origin, its requested method, then its {@code Access-Control-Request-Headers}, or
     * {@code null}; every case is sent to each of the two listener paths from each of two origins.
     */
    private static Stream<Arguments> preflights() {
        return Stream.of("/person", "/person/1").flatMap(path -> Stream.of(ORIGIN, "http://a.example")
                .flatMap(origin -> Stream.of(
                        Arguments.of(path, origin, "GET", null),
                        Arguments.of(path, origin, "POST", null),
                        Arguments.of(path, origin, "PUT", null),
                        Arguments.of(path, origin, "DELETE", null),
                        Arguments.of(path, origin, "PATCH", null),
                        Arguments.of(path, origin, "OPTIONS", null),
                        Arguments.of(path, origin, "POST", "content-type"))));
    }

    @ParameterizedTest(name = "{0}: Origin {1}, Access-Control-Request-Method {2}, Access-Control-Request-Headers {3}")
    @MethodSource("preflights")
    void preflightGetsTheListenerMethod405LikeAPlainOptionsRequest(String path, String origin, String requestedMethod,
                                                                   String requestedHeaders) {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", path, null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", path,
                preflightHeaders(origin, requestedMethod, requestedHeaders), null);

        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 405 Method Not Allowed", preflight.statusLine(), preflight::toString);
        assertEquals("text/plain", preflight.header("Content-Type"), preflight::toString);
        assertArrayEquals(("Method not allowed for endpoint: " + path).getBytes(StandardCharsets.UTF_8),
                preflight.body(), preflight::toString);
        assertNoCorsHeaders(preflight);
        verifyNoInteractions(personService);
    }

    @Test
    void preflightToAnUnservedPathGetsTheNoListener404() {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", "/unknown", null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", "/unknown",
                preflightHeaders(ORIGIN, "POST", null), null);

        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 404 Not Found", preflight.statusLine(), preflight::toString);
        assertEquals("text/plain", preflight.header("Content-Type"), preflight::toString);
        assertArrayEquals("No listener for endpoint: /unknown".getBytes(StandardCharsets.UTF_8), preflight.body(),
                preflight::toString);
        assertNoCorsHeaders(preflight);
        verifyNoInteractions(personService);
    }

    // A fresh context, so the person store is empty and the stored person gets id 1.
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void crossOriginRequestsStillRunTheThreeFlowsWithoutCorsHeaders() throws Exception {
        byte[] person = personJson();
        HttpTestClient client = HttpTestClient.localhost(port);
        Map<String, String> postHeaders = new LinkedHashMap<>();
        postHeaders.put("Origin", ORIGIN);
        postHeaders.put("Content-Type", "application/json");

        HttpTestClient.Response stored = client.send("POST", "/person", postHeaders, person);

        assertEquals("HTTP/1.1 201 Created", stored.statusLine(), stored::toString);
        assertEquals("http://0.0.0.0:8081/person/1", stored.header("Location"), stored::toString);
        assertEquals(List.of(), stored.headers("Content-Type"), stored::toString);
        assertEquals(List.of("74"), stored.headers("Content-Length"), stored::toString);
        assertArrayEquals(PersonService.STORE_SUCCESS_RESPONSE.getBytes(StandardCharsets.UTF_8), stored.body(),
                stored::toString);
        assertNoCorsHeaders(stored);

        HttpTestClient.Response item = client.send("GET", "/person/1", Map.of("Origin", ORIGIN), null);

        assertEquals("HTTP/1.1 200 OK", item.statusLine(), item::toString);
        assertEquals(List.of(), item.headers("Content-Type"), item::toString);
        assertEquals(List.of(String.valueOf(person.length)), item.headers("Content-Length"), item::toString);
        assertArrayEquals(person, item.body(), item::toString);
        assertNoCorsHeaders(item);

        HttpTestClient.Response collection = client.send("GET", "/person", Map.of("Origin", ORIGIN), null);

        assertEquals("HTTP/1.1 200 OK", collection.statusLine(), collection::toString);
        assertEquals("application/json", collection.header("Content-Type"), collection::toString);
        assertEquals("[" + new String(person, StandardCharsets.UTF_8) + "]", collection.bodyAsString(),
                collection::toString);
        assertNoCorsHeaders(collection);

        verify(personService).storePerson(any());
        verify(personService).retrievePersonJson("1");
        verify(personService).retrieveAllPersonsJson();
        verifyNoMoreInteractions(personService);
    }

    private static Map<String, String> preflightHeaders(String origin, String requestedMethod,
                                                        String requestedHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Origin", origin);
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

    /** The original example's test person, {@code src/test/resources/person.json}, as its file bytes. */
    private static byte[] personJson() {
        try (InputStream in = PreflightDispatchListenerTest.class.getResourceAsStream("/person.json")) {
            if (in == null) {
                throw new IllegalStateException("person.json is not on the test classpath");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read person.json", e);
        }
    }

    /**
     * The test's whole context: Spring Boot's servlet web stack on Undertow, {@link PreflightDispatchConfig},
     * {@link ListenerMethodFilter} and the person resource's own beans.
     *
     * <p>It names the web auto-configurations rather than enabling auto-configuration and scans nothing, so the
     * context holds the listeners alone; and it is not a {@code @SpringBootConfiguration}, so the project's other
     * {@code @SpringBootTest} classes still find exactly one application class.
     */
    @Configuration(proxyBeanMethods = false)
    @Import({PreflightDispatchConfig.class, ListenerMethodFilter.class, PersonController.class, PersonService.class,
            PersonExceptionHandler.class, GlobalExceptionHandler.class})
    @ImportAutoConfiguration({
            PropertyPlaceholderAutoConfiguration.class,
            ServletWebServerFactoryAutoConfiguration.class,
            EmbeddedWebServerFactoryCustomizerAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
            HttpEncodingAutoConfiguration.class,
            MultipartAutoConfiguration.class})
    static class WireConfig {
    }
}
