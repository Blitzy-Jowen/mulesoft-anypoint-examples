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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import com.mulesoft.examples.netsuite_data_retrieval.service.NetsuiteSearchService;
import com.mulesoft.examples.netsuite_data_retrieval.support.HttpTestClient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;

/**
 * Wire-level test of {@link PreflightDispatchConfig} on the {@code /api/*} listener of {@code api/netsuite-api.raml}:
 * a CORS pre-flight is answered exactly as a plain {@code OPTIONS} request to the same path is, and NetSuite is never
 * searched, whatever method it asks for, because the original listener declares no {@code allowedMethods} and
 * applies no CORS policy, and its APIkit router answered every method a resource does not declare with 405
 * {@code application/json} {@code { "message": "Method not allowed" }}
 * (netsuite-data-retrieval/src/main/app/netsuite-api.xml:12-21,107-133).
 *
 * <p>A pre-flight asking for {@code GET}, the one method each of {@code /customers}, {@code /items} and
 * {@code /opportunities} declares, would otherwise select that search handler and be refused by Spring with 403
 * {@code Invalid CORS request} and three {@code Vary} headers. No RAML resource declares {@code OPTIONS}, so a
 * pre-flight to any of them gets that 405. The API root {@code /api/}, with or without an {@code Accept} header naming
 * the RAML, and every path under {@code /api} that neither a resource nor the console serves get APIkit's 404
 * {@code application/json} {@code { "message": "Resource not found" }}, as every non-RAML request there did. These
 * answers carry no {@code Access-Control-*} or {@code Vary} header.
 *
 * <p>The SC-17 console answers every method alike, so on its paths the pre-flight gets the console's own
 * {@code OPTIONS} response: the redirect of {@code /api/console}, the page and the console files with the
 * {@code Access-Control-Allow-Origin: *} the console itself sends, and APIkit's 404 for a miss. There the
 * pre-flight's equality with the plain {@code OPTIONS} response, its status line and {@code Content-Type} and the
 * absence of {@code Vary} are asserted. A RAML include is served by the console for {@code GET} alone, so there only
 * that equality, a status other than 403 and the absence of {@code Vary} are asserted: what a plain {@code OPTIONS}
 * request receives on the console paths and the includes is the console's contract, not this mapping's.
 *
 * <p>{@link ApiKitMethodCaseFilter} runs first: under {@code /api} a lower or mixed-case {@code options} token is
 * dispatched as {@code OPTIONS}, so with pre-flight headers it is a pre-flight too and gets the plain
 * {@code OPTIONS} response; outside {@code /api} the filter leaves the token alone, Spring does not take it for a
 * pre-flight, and both requests get the no-listener 404.
 *
 * <p>The whole application runs on Undertow on a random port with {@link NetsuiteSearchService} mocked, so any search
 * would be recorded, under the context configuration of the three controller tests, whose cached application context
 * it shares. Requests go through ST-1 {@link HttpTestClient}, so the status line, headers and body bytes are asserted
 * as the listener writes them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PreflightDispatchListenerTest {

    private static final String ORIGIN = "https://example.invalid";

    /** The body of APIkit's 405 mapping (netsuite-api.xml:116). */
    private static final String APIKIT_METHOD_NOT_ALLOWED = "{ \"message\": \"Method not allowed\" }";

    /** The body of APIkit's 404 mapping (netsuite-api.xml:111). */
    private static final String APIKIT_RESOURCE_NOT_FOUND = "{ \"message\": \"Resource not found\" }";

    private static final String METHOD_NOT_ALLOWED = "HTTP/1.1 405 Method Not Allowed";

    private static final String NOT_FOUND = "HTTP/1.1 404 Not Found";

    private static final String OK = "HTTP/1.1 200 OK";

    private static final String MOVED_PERMANENTLY = "HTTP/1.1 301 Moved Permanently";

    /** Media type of the RAML descriptor, which a {@code GET} of the API root naming it in {@code Accept} receives. */
    private static final String RAML = "application/raml+yaml";

    /** A file {@code api/netsuite-api.raml} includes, which the APIkit console serves relative to the API base path. */
    private static final String RAML_INCLUDE = "customers-response.json";

    @MockBean
    private NetsuiteSearchService searchService;

    @LocalServerPort
    private int port;

    /**
     * Every pre-flight to each path that answers {@code OPTIONS} with an APIkit error, followed by that error's status
     * line and body: the three RAML resources, none of which declares {@code OPTIONS}, answer APIkit's 405, whatever
     * query they carry; the API root, which answers every non-RAML request as an unknown resource even when its
     * {@code Accept} header names the RAML, and the paths no resource serves answer APIkit's 404.
     */
    private static Stream<Arguments> preflightsAnsweredWithAnApiKitError() {
        return Stream.of(
                answeredWith(preflightsTo(Map.of(), "/api/customers", "/api/customers?name=Ramsey", "/api/items",
                        "/api/items?quantity=5&operator=LESS_THAN", "/api/opportunities",
                        "/api/opportunities?title=Short"), METHOD_NOT_ALLOWED, APIKIT_METHOD_NOT_ALLOWED),
                answeredWith(preflightsTo(Map.of(), "/api/", "/api/x", "/api/customers/1"), NOT_FOUND,
                        APIKIT_RESOURCE_NOT_FOUND),
                answeredWith(preflightsTo(Map.of("Accept", RAML), "/api/"), NOT_FOUND, APIKIT_RESOURCE_NOT_FOUND))
                .flatMap(preflights -> preflights);
    }

    @ParameterizedTest(name = "{0} {1}, Access-Control-Request-Method {2}, Access-Control-Request-Headers {3}")
    @MethodSource("preflightsAnsweredWithAnApiKitError")
    void preflightGetsTheApiKitErrorLikeAPlainOptionsRequest(String path, Map<String, String> requestHeaders,
                                                             String requestedMethod, String requestedHeaders,
                                                             String expectedStatusLine, String expectedBody) {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", path, requestHeaders, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", path,
                preflightHeaders(requestHeaders, requestedMethod, requestedHeaders), null);

        assertSameResponse(plain, preflight);
        assertEquals(expectedStatusLine, preflight.statusLine(), preflight::toString);
        assertEquals("application/json", preflight.header("Content-Type"), preflight::toString);
        assertArrayEquals(expectedBody.getBytes(StandardCharsets.UTF_8), preflight.body(), preflight::toString);
        assertNoCorsHeaders(preflight);
        verifyNoInteractions(searchService);
    }

    /**
     * Every pre-flight to each path of the SC-17 console, followed by the status line and {@code Content-Type} of
     * the console's {@code OPTIONS} response there ({@code null} for none): the redirect without the trailing slash,
     * the page on the console root, its {@code ?raml} form, which serves the RAML to {@code GET} alone, and
     * {@code index.html}, the console files by their extension, and APIkit's 404 for a file the console lacks.
     */
    private static Stream<Arguments> preflightsToTheConsole() {
        return Stream.of(
                answeredWith(preflightsTo(Map.of(), "/api/console", "/api/console?raml"), MOVED_PERMANENTLY, null),
                answeredWith(preflightsTo(Map.of(), "/api/console/", "/api/console/?raml",
                        "/api/console/index.html", "/api/console/authentication/oauth2.html"), OK, "text/html"),
                answeredWith(preflightsTo(Map.of("Accept", RAML), "/api/console/"), OK, "text/html"),
                answeredWith(preflightsTo(Map.of(), "/api/console/img/spinner.gif"), OK, "image/gif"),
                answeredWith(preflightsTo(Map.of(), "/api/console/missing.js"), NOT_FOUND, "application/json"))
                .flatMap(preflights -> preflights);
    }

    @ParameterizedTest(name = "{0} {1}, Access-Control-Request-Method {2}, Access-Control-Request-Headers {3}")
    @MethodSource("preflightsToTheConsole")
    void preflightToTheConsoleGetsTheConsoleOptionsResponse(String path, Map<String, String> requestHeaders,
                                                            String requestedMethod, String requestedHeaders,
                                                            String expectedStatusLine, String expectedContentType) {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", path, requestHeaders, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", path,
                preflightHeaders(requestHeaders, requestedMethod, requestedHeaders), null);

        assertSameResponse(plain, preflight);
        assertEquals(expectedStatusLine, preflight.statusLine(), preflight::toString);
        assertEquals(expectedContentType, preflight.header("Content-Type"), preflight::toString);
        assertNoVaryHeader(preflight);
        verifyNoInteractions(searchService);
    }

    /**
     * Every pre-flight to a RAML include, which the console maps for {@code GET} alone, below the API base path and
     * below the console, so that its plain {@code OPTIONS} response is the console's to decide.
     */
    private static Stream<Arguments> preflightsToARamlInclude() {
        return preflightsTo(Map.of(), "/api/" + RAML_INCLUDE, "/api/console/" + RAML_INCLUDE);
    }

    @ParameterizedTest(name = "{0} {1}, Access-Control-Request-Method {2}, Access-Control-Request-Headers {3}")
    @MethodSource("preflightsToARamlInclude")
    void preflightToARamlIncludeIsAnsweredLikeAPlainOptionsRequest(String path, Map<String, String> requestHeaders,
                                                                   String requestedMethod, String requestedHeaders) {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", path, requestHeaders, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", path,
                preflightHeaders(requestHeaders, requestedMethod, requestedHeaders), null);

        assertSameResponse(plain, preflight);
        assertNotEquals(403, preflight.status(), preflight::toString);
        assertNoVaryHeader(preflight);
        verifyNoInteractions(searchService);
    }

    @Test
    void preflightToAnUnservedPathGetsTheNoListener404() {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", "/other", null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", "/other",
                preflightHeaders(Map.of(), "GET", null), null);

        assertSameResponse(plain, preflight);
        assertEquals(NOT_FOUND, preflight.statusLine(), preflight::toString);
        assertEquals("text/plain", preflight.header("Content-Type"), preflight::toString);
        assertArrayEquals("No listener for endpoint: /other".getBytes(StandardCharsets.UTF_8), preflight.body(),
                preflight::toString);
        assertNoCorsHeaders(preflight);
        verifyNoInteractions(searchService);
    }

    /**
     * Each {@code OPTIONS} token that is not upper case, with a path and the status line of the upper-case
     * {@code OPTIONS} request there: a resource, the console root and an unknown resource under {@code /api}, where
     * {@link ApiKitMethodCaseFilter} upper-cases the token, and a path outside it, where it does not.
     */
    private static Stream<Arguments> optionsTokensThatAreNotUpperCase() {
        return Stream.of("options", "Options").flatMap(token -> Stream.of(
                Arguments.of(token, "/api/customers", METHOD_NOT_ALLOWED),
                Arguments.of(token, "/api/console/", OK),
                Arguments.of(token, "/api/x", NOT_FOUND),
                Arguments.of(token, "/other", NOT_FOUND)));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("optionsTokensThatAreNotUpperCase")
    void preflightWithANonUpperCaseOptionsTokenIsAnsweredLikeThePlainRequest(String token, String path,
                                                                             String expectedStatusLine) {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response upperCase = client.send("OPTIONS", path, null, null);
        HttpTestClient.Response plain = client.send(token, path, null, null);

        HttpTestClient.Response preflight = client.send(token, path, preflightHeaders(Map.of(), "GET", null), null);

        assertEquals(expectedStatusLine, plain.statusLine(), plain::toString);
        if (path.startsWith("/api/")) {
            assertSameResponse(upperCase, plain);
        }
        assertSameResponse(plain, preflight);
        assertNoVaryHeader(preflight);
        verifyNoInteractions(searchService);
    }

    @Test
    void crossOriginGetStillReachesTheController() throws Exception {
        String customers = "[]";
        when(searchService.getCustomers("Ramsey")).thenReturn(customers);

        HttpTestClient.Response response = HttpTestClient.localhost(port)
                .send("GET", "/api/customers?name=Ramsey", Map.of("Origin", ORIGIN), null);

        assertEquals(OK, response.statusLine(), response::toString);
        assertEquals("application/json", response.header("Content-Type"), response::toString);
        assertArrayEquals(customers.getBytes(StandardCharsets.UTF_8), response.body(), response::toString);
        assertNoCorsHeaders(response);
        verify(searchService).getCustomers("Ramsey");
        verifyNoMoreInteractions(searchService);
    }

    /**
     * The APIkit console registers a {@code GET} mapping per RAML include on the bean
     * {@code requestMappingHandlerMapping} once every singleton exists; that bean is now the pre-flight dispatching
     * mapping, and the include is still served from it.
     */
    @Test
    void ramlIncludeRegisteredOnTheDispatchingMappingIsServed() throws IOException {
        byte[] include = new ClassPathResource("api/" + RAML_INCLUDE).getContentAsByteArray();

        HttpTestClient.Response response = HttpTestClient.localhost(port).get("/api/" + RAML_INCLUDE);

        assertEquals(OK, response.statusLine(), response::toString);
        assertArrayEquals(include, response.body(), response::toString);
        verifyNoInteractions(searchService);
    }

    /**
     * Every pre-flight sent to each of {@code paths} with {@code requestHeaders}: one per requested method, then one
     * also asking for the {@code content-type} header.
     */
    private static Stream<Arguments> preflightsTo(Map<String, String> requestHeaders, String... paths) {
        return Stream.of(paths).flatMap(path -> Stream.of(
                Arguments.of(path, requestHeaders, "GET", null),
                Arguments.of(path, requestHeaders, "POST", null),
                Arguments.of(path, requestHeaders, "PUT", null),
                Arguments.of(path, requestHeaders, "DELETE", null),
                Arguments.of(path, requestHeaders, "PATCH", null),
                Arguments.of(path, requestHeaders, "OPTIONS", null),
                Arguments.of(path, requestHeaders, "GET", "content-type")));
    }

    /** Each of {@code preflights} with {@code first} and {@code second}, the response expected for it, appended. */
    private static Stream<Arguments> answeredWith(Stream<Arguments> preflights, String first, String second) {
        return preflights.map(preflight -> {
            Object[] arguments = Arrays.copyOf(preflight.get(), preflight.get().length + 2);
            arguments[arguments.length - 2] = first;
            arguments[arguments.length - 1] = second;
            return Arguments.of(arguments);
        });
    }

    /**
     * {@code requestHeaders}, then {@code Origin}, {@code Access-Control-Request-Method} and, when not {@code null},
     * {@code Access-Control-Request-Headers}.
     */
    private static Map<String, String> preflightHeaders(Map<String, String> requestHeaders, String requestedMethod,
                                                        String requestedHeaders) {
        Map<String, String> headers = new LinkedHashMap<>(requestHeaders);
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

    private static void assertNoVaryHeader(HttpTestClient.Response response) {
        assertTrue(response.headers().stream().map(Map.Entry::getKey).noneMatch("Vary"::equalsIgnoreCase),
                response::toString);
    }
}
