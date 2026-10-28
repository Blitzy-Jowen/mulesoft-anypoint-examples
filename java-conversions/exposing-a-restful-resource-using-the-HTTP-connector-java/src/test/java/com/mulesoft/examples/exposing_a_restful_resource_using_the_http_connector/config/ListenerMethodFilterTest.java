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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;

import com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.controller.PersonController;
import com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.exception.GlobalExceptionHandler;
import com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.exception.PersonExceptionHandler;
import com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.service.PersonService;
import com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.support.HttpTestClient;

/**
 * Tests {@link ListenerMethodFilter}, which matches {@code GET} and {@code POST} on the person resource's listeners
 * ignoring case, as Mule's {@code MethodRequestMatcher} did for their {@code allowedMethods}
 * (exposing-a-restful-resource-using-the-HTTP-connector/src/main/app/http-restful-resource.xml:11,16,28), in two
 * layers.
 *
 * <ul>
 *   <li>Unit layer: the filter alone over a {@link MockFilterChain}, checking which request the chain receives and
 *       the method it reports.</li>
 *   <li>Wire layer: the person resource on Undertow ({@link WireConfig}, random port on IPv4 loopback), called
 *       through the project's ST-1 client with the method token exactly as written, so the raw status line, the
 *       headers and the body bytes are asserted as the listeners write them. A case that stores a person discards
 *       its context afterwards and every other case stores nothing, so each case starts from an empty store and
 *       ids start at 1.</li>
 * </ul>
 */
@SpringBootTest(classes = ListenerMethodFilterTest.WireConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Pins the listener to IPv4 loopback whatever server.address the project's application.yml sets.
        properties = "server.address=127.0.0.1")
class ListenerMethodFilterTest {

    /** The original example's test person, {@code src/test/resources/person.json}, as its file bytes. */
    private static final byte[] PERSON = personJson();

    private static final Map<String, String> JSON_CONTENT = Map.of("Content-Type", "application/json");

    @LocalServerPort
    private int port;

    @ParameterizedTest
    @CsvSource({"get, GET", "Get, GET", "gEt, GET", "post, POST", "Post, POST", "pOsT, POST"})
    void caseVariantOfAnAllowedMethodReachesTheChainInItsCanonicalSpelling(String method, String canonical)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/person");
        request.setContentType("application/json");
        request.setContent(PERSON);
        MockFilterChain chain = new MockFilterChain();

        new ListenerMethodFilter().doFilter(request, new MockHttpServletResponse(), chain);

        ServletRequest passed = chain.getRequest();
        assertNotSame(request, passed);
        HttpServletRequest wrapped = (HttpServletRequest) passed;
        assertEquals(canonical, wrapped.getMethod());
        // Everything but the method is the original request's.
        assertEquals("/person", wrapped.getRequestURI());
        assertEquals("application/json", wrapped.getContentType());
        assertArrayEquals(PERSON, wrapped.getInputStream().readAllBytes());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "HEAD", "head", "options", "OPTIONS", "put", "trace", "postx", "ge",
            "po\u017Ft"})
    void otherMethodTokenPassesUnchanged(String method) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/person");
        MockFilterChain chain = new MockFilterChain();

        new ListenerMethodFilter().doFilter(request, new MockHttpServletResponse(), chain);

        assertSame(request, chain.getRequest());
        assertEquals(method, request.getMethod());
    }

    @Test
    void requestWithoutMethodPassesUnchanged() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod(null);
        MockFilterChain chain = new MockFilterChain();

        new ListenerMethodFilter().doFilter(request, new MockHttpServletResponse(), chain);

        assertSame(request, chain.getRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"post", "Post", "pOsT"})
    @DirtiesContext
    void postInAnyLetterCaseRunsStorePersonFlow(String method) {
        HttpTestClient client = HttpTestClient.localhost(port);

        HttpTestClient.Response response = client.send(method, "/person", JSON_CONTENT, PERSON);

        assertEquals("HTTP/1.1 201 Created", response.statusLine(), response::toString);
        assertEquals("http://0.0.0.0:8081/person/1", response.header("Location"), response::toString);
        assertEquals(List.of(), response.headers("Content-Type"), response::toString);
        assertEquals(List.of("74"), response.headers("Content-Length"), response::toString);
        assertEquals("{ \"status\": \"success\", \"statusDescription\": \"person created successfully\"}",
                response.bodyAsString(), response::toString);
        HttpTestClient.Response stored = client.get("/person/1");
        assertEquals("HTTP/1.1 200 OK", stored.statusLine(), stored::toString);
        assertArrayEquals(PERSON, stored.body(), stored::toString);
    }

    /**
     * A {@code GET} in some letter case, the path it reads after one person was stored, the expected
     * {@code Content-Type} ({@code null} for none: the item flow's untyped {@code set-payload}) and the expected body.
     */
    static Stream<Arguments> getsOfAStoredPerson() {
        String person = new String(PERSON, StandardCharsets.UTF_8);
        return Stream.of("get", "Get", "gEt").flatMap(method -> Stream.of(
                Arguments.of(method, "/person", "application/json", "[" + person + "]"),
                Arguments.of(method, "/person/1", null, person)));
    }

    @ParameterizedTest(name = "{index}: {0} {1}")
    @MethodSource("getsOfAStoredPerson")
    @DirtiesContext
    void getInAnyLetterCaseRunsTheRetrieveFlows(String method, String path, String contentType, String expectedBody) {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response stored = client.send("POST", "/person", JSON_CONTENT, PERSON);
        assertEquals("HTTP/1.1 201 Created", stored.statusLine(), stored::toString);

        HttpTestClient.Response response = client.send(method, path, null, null);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertEquals(contentType == null ? List.of() : List.of(contentType), response.headers("Content-Type"),
                response::toString);
        assertEquals(expectedBody, response.bodyAsString(), response::toString);
    }

    /**
     * A {@code GET} of an item in some letter case that fails, the expected status line, {@code Content-Type}
     * ({@code null} for none) and body: an id the store does not hold reaches {@code PersonExceptionHandler}'s 404,
     * a non-numeric id SC-1's 500.
     */
    static Stream<Arguments> failingGetsOfAnItem() {
        return Stream.of(
                Arguments.of("get", "/person/99", "HTTP/1.1 404 Not Found", null, ""),
                Arguments.of("Get", "/person/99", "HTTP/1.1 404 Not Found", null, ""),
                Arguments.of("gEt", "/person/99", "HTTP/1.1 404 Not Found", null, ""),
                Arguments.of("get", "/person/abc", "HTTP/1.1 500 Internal Server Error", "application/json",
                        "{ \"message\": \"For input string: \\\"abc\\\"\" }"));
    }

    @ParameterizedTest(name = "{index}: {0} {1}")
    @MethodSource("failingGetsOfAnItem")
    void failingGetInAnyLetterCaseIsAnsweredAsAnUppercaseGet(String method, String path, String statusLine,
                                                            String contentType, String body) {
        HttpTestClient client = HttpTestClient.localhost(port);

        HttpTestClient.Response response = client.send(method, path, null, null);

        assertEquals(statusLine, response.statusLine(), response::toString);
        assertEquals(contentType == null ? List.of() : List.of(contentType), response.headers("Content-Type"),
                response::toString);
        assertEquals(body, response.bodyAsString(), response::toString);
        assertSameResponse(client.send("GET", path, null, null), response);
    }

    /**
     * A {@code POST} in some letter case whose body {@code storePerson} cannot bind or parse: its
     * {@code Content-Type} ({@code null} for none) and body ({@code null} for none).
     */
    static Stream<Arguments> failingPosts() {
        byte[] json = "{\"firstname\":\"A\"}".getBytes(StandardCharsets.UTF_8);
        return Stream.of("post", "Post", "pOsT").flatMap(method -> Stream.of(
                // The body is rebuilt from the parsed form parameters, so the JSON arrives URL-encoded.
                Arguments.of(method, "application/x-www-form-urlencoded", json),
                // An unparseable Content-Type fails the body binding.
                Arguments.of(method, ";;;", PERSON),
                // No body binds null, which the flow's JSON conversion rejects.
                Arguments.of(method, null, null)));
    }

    @ParameterizedTest(name = "{index}: {0} with Content-Type [{1}]")
    @MethodSource("failingPosts")
    void failingPostInAnyLetterCaseIsAnsweredAsAnUppercasePost(String method, String contentType, byte[] body) {
        HttpTestClient client = HttpTestClient.localhost(port);
        Map<String, String> headers = contentType == null ? null : Map.of("Content-Type", contentType);

        HttpTestClient.Response response = client.send(method, "/person", headers, body);

        assertEquals("HTTP/1.1 500 Internal Server Error", response.statusLine(), response::toString);
        assertEquals("application/json", response.header("Content-Type"), response::toString);
        assertSameResponse(client.send("POST", "/person", headers, body), response);
        // A failed conversion consumes no id and stores nothing, in either spelling.
        assertEquals("[]", client.get("/person").bodyAsString());
    }

    /**
     * Spellings the listeners do not allow on a path, with the headers sent: each keeps the listener's 405, an
     * {@code options} with the headers of a CORS pre-flight included. {@code head} has its own case below.
     */
    static Stream<Arguments> otherMethodsOnTheWire() {
        Map<String, String> preflightHeaders = new LinkedHashMap<>();
        preflightHeaders.put("Origin", "https://example.invalid");
        preflightHeaders.put("Access-Control-Request-Method", "GET");
        return Stream.of(
                Arguments.of("options", "/person", Map.of()),
                Arguments.of("options", "/person", preflightHeaders),
                Arguments.of("put", "/person", Map.of()),
                Arguments.of("trace", "/person", Map.of()),
                Arguments.of("postx", "/person", Map.of()),
                Arguments.of("post", "/person/1", Map.of()));
    }

    @ParameterizedTest(name = "{index}: {0} {1} {2}")
    @MethodSource("otherMethodsOnTheWire")
    void otherMethodSpellingKeepsTheListener405(String method, String path, Map<String, String> headers) {
        HttpTestClient.Response response = HttpTestClient.localhost(port).send(method, path, headers, null);

        assertEquals("HTTP/1.1 405 Method Not Allowed", response.statusLine(), response::toString);
        assertEquals("text/plain", response.header("Content-Type"), response::toString);
        assertEquals("Method not allowed for endpoint: " + path, response.bodyAsString(), response::toString);
    }

    /**
     * {@code head} keeps the listener's 405 and, like {@code HEAD}, carries no body: Undertow compares the method
     * ignoring case when it decides whether a response has a body, so it writes the head of the 405 alone. ST-1 skips
     * the body for the exact token {@code HEAD} only, so this case reads the raw response up to the server's close.
     */
    @Test
    void lowerCaseHeadKeepsTheListener405AndIsAnsweredAsAnUppercaseHead() throws IOException {
        HttpTestClient.Response uppercase = HttpTestClient.localhost(port).send("HEAD", "/person", null, null);

        String raw = new String(rawExchange("head /person HTTP/1.1\r\nHost: localhost:" + port
                + "\r\nConnection: close\r\n\r\n"), StandardCharsets.ISO_8859_1);

        int headEnd = raw.indexOf("\r\n\r\n");
        assertTrue(headEnd > 0, raw);
        assertEquals("", raw.substring(headEnd + 4), raw);
        List<String> lines = List.of(raw.substring(0, headEnd).split("\r\n"));
        assertEquals("HTTP/1.1 405 Method Not Allowed", lines.get(0), raw);
        List<String> headers = lines.subList(1, lines.size()).stream()
                .map(line -> line.substring(0, line.indexOf(':')).toLowerCase(Locale.ROOT) + ": "
                        + line.substring(line.indexOf(':') + 1).trim())
                .filter(line -> !line.startsWith("date: "))
                .sorted()
                .toList();
        assertTrue(headers.contains("content-type: text/plain"), raw);
        assertTrue(headers.contains("content-length: " + "Method not allowed for endpoint: /person".length()), raw);
        assertEquals(uppercase.statusLine(), lines.get(0), uppercase::toString);
        assertEquals(headerLines(uppercase), headers, uppercase::toString);
    }

    /**
     * Writes {@code requestHead} on a new connection and returns every byte the server sends until it closes the
     * connection, within ST-1's timeouts.
     */
    private byte[] rawExchange(String requestHead) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), HttpTestClient.TIMEOUT_MILLIS);
            socket.setSoTimeout(HttpTestClient.TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write(requestHead.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return socket.getInputStream().readAllBytes();
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

    private static byte[] personJson() {
        try (InputStream in = ListenerMethodFilterTest.class.getResourceAsStream("/person.json")) {
            if (in == null) {
                throw new IllegalStateException("person.json is not on the test classpath");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read person.json", e);
        }
    }

    /**
     * The test's whole context: Spring Boot's servlet web stack on Undertow, {@link ListenerMethodFilter},
     * {@link PreflightDispatchConfig} and the person resource's own beans.
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
