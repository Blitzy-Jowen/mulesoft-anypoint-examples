/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit.config;

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
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import com.mulesoft.examples.rest_api_with_apikit.controller.ApiKitConsoleController;
import com.mulesoft.examples.rest_api_with_apikit.controller.FixtureController;
import com.mulesoft.examples.rest_api_with_apikit.controller.PositionsController;
import com.mulesoft.examples.rest_api_with_apikit.controller.TeamsController;
import com.mulesoft.examples.rest_api_with_apikit.exception.ApiKitExceptionHandler;
import com.mulesoft.examples.rest_api_with_apikit.exception.GlobalExceptionHandler;
import com.mulesoft.examples.rest_api_with_apikit.exception.LeagueExceptionHandler;
import com.mulesoft.examples.rest_api_with_apikit.mapper.LeagueJsonMapper;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Fixture;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Match;
import com.mulesoft.examples.rest_api_with_apikit.service.LeagueAPI;
import com.mulesoft.examples.rest_api_with_apikit.support.HttpTestClient;

/**
 * Wire-level test of {@link PreflightDispatchConfig} on the {@code /api/*} listener of {@code leagues.raml}: a CORS
 * pre-flight is answered exactly as a plain {@code OPTIONS} request to the same path is, with no
 * {@code Access-Control-*} or {@code Vary} header, and the league is neither read nor written, whatever method it
 * asks for, because the original listener declares no {@code allowedMethods} and applies no CORS policy, and its
 * APIkit router answered every method a resource does not declare with 405 {@code application/json}
 * {@code { "message": "Method not allowed" }} (rest-api-with-apikit/src/main/app/rest-api-with-apikit.xml:3-44).
 *
 * <p>A pre-flight asking for a declared method, such as {@code POST /api/teams} or
 * {@code PUT /api/fixture/{homeTeamId}/{awayTeamId}}, would otherwise select that write handler and be refused by
 * Spring with 403 {@code Invalid CORS request}. No RAML resource declares {@code OPTIONS}, so a pre-flight to any of
 * them gets that 405. The API root {@code /api/} is no resource, so a pre-flight to it gets APIkit's 404
 * {@code application/json} {@code { "message": "Resource not found" }}, as every non-RAML request there did. A RAML
 * include is served by the console for {@code GET} alone, so there only the pre-flight's equality with the plain
 * {@code OPTIONS} response is asserted: what a plain {@code OPTIONS} request receives there is the console's
 * contract, not this mapping's.
 *
 * <p>The cases run the real controllers, the APIkit console and the league, SC-3 and SC-1 handlers on Undertow
 * ({@link WireConfig}, random port) with {@link LeagueAPI} mocked, so any read or write would be recorded, and send
 * requests through ST-1 {@link HttpTestClient}, so the status line, headers and body bytes are asserted as the
 * listener writes them.
 */
@SpringBootTest(classes = PreflightDispatchListenerTest.WireConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PreflightDispatchListenerTest {

    private static final String ORIGIN = "https://example.invalid";

    /** The body of APIkit's 405 mapping, as the original flow's exception strategy wrote it. */
    private static final String APIKIT_METHOD_NOT_ALLOWED = "{ \"message\": \"Method not allowed\" }";

    /** The body of APIkit's 404 mapping, as the original flow's exception strategy wrote it. */
    private static final String APIKIT_RESOURCE_NOT_FOUND = "{ \"message\": \"Resource not found\" }";

    /** A file {@code leagues.raml} includes, which the APIkit console serves relative to the API base path. */
    private static final String RAML_INCLUDE = "schemas/teams-schema-input.json";

    @MockBean
    private LeagueAPI leagueAPI;

    @LocalServerPort
    private int port;

    /**
     * Every pre-flight to each path that answers {@code OPTIONS} with an APIkit error, followed by that error's status
     * line and body: the RAML resources, none of which declares {@code OPTIONS}, answer APIkit's 405, and the API root
     * of the RAML descriptor, which answers every non-RAML request as an unknown resource, APIkit's 404.
     */
    private static Stream<Arguments> preflightsAnsweredWithAnApiKitError() {
        return Stream.concat(
                answeredWith(preflightsTo("/api/teams", "/api/teams/BAR", "/api/positions", "/api/fixture",
                        "/api/fixture/BAR/RMA"), "HTTP/1.1 405 Method Not Allowed", APIKIT_METHOD_NOT_ALLOWED),
                answeredWith(preflightsTo("/api/"), "HTTP/1.1 404 Not Found", APIKIT_RESOURCE_NOT_FOUND));
    }

    @ParameterizedTest(name = "{0}, Access-Control-Request-Method {1}, Access-Control-Request-Headers {2}")
    @MethodSource("preflightsAnsweredWithAnApiKitError")
    void preflightGetsTheApiKitErrorLikeAPlainOptionsRequest(String path, String requestedMethod,
                                                             String requestedHeaders, String expectedStatusLine,
                                                             String expectedBody) {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", path, null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", path,
                preflightHeaders(requestedMethod, requestedHeaders), null);

        assertSameResponse(plain, preflight);
        assertEquals(expectedStatusLine, preflight.statusLine(), preflight::toString);
        assertEquals("application/json", preflight.header("Content-Type"), preflight::toString);
        assertArrayEquals(expectedBody.getBytes(StandardCharsets.UTF_8), preflight.body(), preflight::toString);
        assertNoCorsHeaders(preflight);
        verifyNoInteractions(leagueAPI);
    }

    /**
     * A RAML include, which the console maps for {@code GET} alone, so that its plain {@code OPTIONS} response is the
     * console's to decide, with every pre-flight.
     */
    private static Stream<Arguments> preflightsToARamlInclude() {
        return preflightsTo("/api/" + RAML_INCLUDE);
    }

    @ParameterizedTest(name = "{0}, Access-Control-Request-Method {1}, Access-Control-Request-Headers {2}")
    @MethodSource("preflightsToARamlInclude")
    void preflightIsAnsweredLikeAPlainOptionsRequest(String path, String requestedMethod, String requestedHeaders) {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", path, null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", path,
                preflightHeaders(requestedMethod, requestedHeaders), null);

        assertSameResponse(plain, preflight);
        assertNotEquals(403, preflight.status(), preflight::toString);
        assertNoCorsHeaders(preflight);
        verifyNoInteractions(leagueAPI);
    }

    @Test
    void preflightToAnUnservedPathGetsTheNoListener404() {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", "/nope", null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", "/nope", preflightHeaders("POST", null), null);

        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 404 Not Found", preflight.statusLine(), preflight::toString);
        assertEquals("text/plain", preflight.header("Content-Type"), preflight::toString);
        assertArrayEquals("No listener for endpoint: /nope".getBytes(StandardCharsets.UTF_8), preflight.body(),
                preflight::toString);
        assertNoCorsHeaders(preflight);
        verifyNoInteractions(leagueAPI);
    }

    @Test
    void crossOriginGetStillReachesTheController() {
        Match match = new Match();
        match.setHomeTeam("BAR");
        match.setAwayTeam("RMA");
        Fixture fixture = new Fixture();
        fixture.setFixture(List.of(match));
        when(leagueAPI.getFixture()).thenReturn(fixture);

        HttpTestClient.Response response = HttpTestClient.localhost(port)
                .send("GET", "/api/fixture", Map.of("Origin", ORIGIN), null);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertEquals("application/json", response.header("Content-Type"), response::toString);
        assertArrayEquals(new LeagueJsonMapper().toJson(fixture).getBytes(StandardCharsets.UTF_8), response.body(),
                response::toString);
        assertNoCorsHeaders(response);
        verify(leagueAPI).getFixture();
        verifyNoMoreInteractions(leagueAPI);
    }

    /**
     * The APIkit console registers a {@code GET} mapping per RAML include on the bean
     * {@code requestMappingHandlerMapping} once every singleton exists; that bean is now the pre-flight dispatching
     * mapping, and the include is still served from it.
     */
    @Test
    void ramlIncludeRegisteredOnTheDispatchingMappingIsServed() throws IOException {
        byte[] include = StreamUtils.copyToByteArray(new ClassPathResource(RAML_INCLUDE).getInputStream());

        HttpTestClient.Response response = HttpTestClient.localhost(port).get("/api/" + RAML_INCLUDE);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertArrayEquals(include, response.body(), response::toString);
        verifyNoInteractions(leagueAPI);
    }

    /**
     * Every pre-flight sent to each of {@code paths}: one per requested method, then one also asking for the
     * {@code content-type} header.
     */
    private static Stream<Arguments> preflightsTo(String... paths) {
        return Stream.of(paths).flatMap(path -> Stream.of(
                Arguments.of(path, "GET", null),
                Arguments.of(path, "POST", null),
                Arguments.of(path, "PUT", null),
                Arguments.of(path, "DELETE", null),
                Arguments.of(path, "PATCH", null),
                Arguments.of(path, "OPTIONS", null),
                Arguments.of(path, "POST", "content-type")));
    }

    /** Each of {@code preflights} with {@code statusLine} and {@code body}, the response expected for it, appended. */
    private static Stream<Arguments> answeredWith(Stream<Arguments> preflights, String statusLine, String body) {
        return preflights.map(preflight -> {
            Object[] arguments = Arrays.copyOf(preflight.get(), preflight.get().length + 2);
            arguments[arguments.length - 2] = statusLine;
            arguments[arguments.length - 1] = body;
            return Arguments.of(arguments);
        });
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
     * The test's whole context: Spring Boot's servlet web stack on Undertow, {@link PreflightDispatchConfig}, the
     * three RAML controllers with their mapper and schema validator, the APIkit console, and the league, SC-3 and
     * SC-1 handlers, with {@link LeagueAPI} supplied by the test as a mock.
     *
     * <p>It names the web auto-configurations rather than enabling auto-configuration and scans nothing, so the
     * context holds the listener alone; and it is not a {@code @SpringBootConfiguration}, so the project's other
     * {@code @SpringBootTest} classes still find exactly one application class.
     */
    @Configuration(proxyBeanMethods = false)
    @Import({PreflightDispatchConfig.class, TeamsController.class, PositionsController.class,
            FixtureController.class, ApiKitConsoleController.class, LeagueJsonMapper.class,
            JsonSchemaValidator.class, LeagueExceptionHandler.class, ApiKitExceptionHandler.class,
            GlobalExceptionHandler.class})
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
