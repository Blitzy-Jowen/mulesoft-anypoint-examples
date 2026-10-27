/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_oauth_provider.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.http_oauth_provider.support.HttpTestClient;
import com.mulesoft.examples.http_oauth_provider.support.HttpTestClient.Response;

/**
 * Wire-level test of {@link PreflightDispatchConfig} on the application's two listeners: a CORS pre-flight is answered
 * exactly as a plain {@code OPTIONS} request to the same port and path is, with no {@code Access-Control-*} or
 * {@code Vary} header, because neither original listener applied a CORS policy
 * (http-oauth-provider/src/main/app/http-oauth-provider.xml:26-57).
 * <ul>
 *   <li>The resource listener ({@code http.listener.port}) declares no {@code allowedMethods}, so its flows run:
 *       {@code /resources} checks the bearer token, 401 {@code Access token missing} without one and 200 payroll with
 *       a {@code READ_RESOURCE} token, and {@code /redirect} answers its 302 to the provider's {@code /token}.</li>
 *   <li>The provider listener's {@code /token} and {@code /authorize} ({@code http.provider.port}) serve GET and
 *       POST only, so they answer 405 {@code Method not allowed for endpoint: <path>} without an {@code Allow}
 *       header, whatever method the pre-flight asks for.</li>
 *   <li>A path the receiving port does not serve gets the SC-10 no-listener 404.</li>
 * </ul>
 *
 * <p>The whole application runs, nothing mocked, on both Undertow listeners bound to loopback ports reserved before
 * the context starts ({@code DEFINED_PORT}): the SC-10 provider listener requires a concrete port,
 * {@code PortRoutingFilter} tells the two listeners apart by local port, and the payroll {@code uri} and the
 * {@code /redirect} {@code Location} embed both configured ports. Tokens come from the real login at
 * {@code POST /authorize} and exchange at {@code GET /token} with the demo client and owner of profile {@code test};
 * requests go through ST-1 {@link HttpTestClient}, so status line, headers and body bytes are asserted as the
 * listeners write them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PreflightDispatchListenerTest {

    private static final String ORIGIN = "https://example.invalid";

    private static final String FORM = "application/x-www-form-urlencoded";

    private static final String READ_RESOURCE = "READ_RESOURCE";

    /** The SC-10 no-listener answer's Content-Type (AAP 0.4.7). */
    private static final String NO_LISTENER_CONTENT_TYPE = "text/plain;charset=UTF-8";

    /** Names the resource listener in test arguments, whose port is known only once the context starts. */
    private static final String RESOURCE_LISTENER = "resource";

    /** Names the provider listener in test arguments, whose port is known only once the context starts. */
    private static final String PROVIDER_LISTENER = "provider";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static int listenerPort;

    private static int providerPort;

    @Value("${oauth.provider.provider-name}")
    private String providerName;

    @Value("${oauth.provider.client-id}")
    private String clientId;

    @Value("${oauth.provider.client-secret}")
    private String clientSecret;

    @Value("${oauth.provider.owner.username}")
    private String ownerUsername;

    @Value("${oauth.provider.owner.password}")
    private String ownerPassword;

    private HttpTestClient listener;

    private HttpTestClient provider;

    /**
     * Reserves two distinct free loopback ports, both sockets open at once, for the resource and provider
     * listeners; {@code application.yml} derives {@code server.port} from {@code http.listener.port}.
     */
    @DynamicPropertySource
    static void ports(DynamicPropertyRegistry registry) {
        try (ServerSocket listenerSocket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
             ServerSocket providerSocket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            listenerPort = listenerSocket.getLocalPort();
            providerPort = providerSocket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot reserve the resource and provider listener ports", e);
        }
        registry.add("http.listener.port", () -> listenerPort);
        registry.add("http.provider.port", () -> providerPort);
    }

    /** The ports are known only once the context has been created, after the test instance itself. */
    @BeforeEach
    void clients() {
        listener = HttpTestClient.localhost(listenerPort);
        provider = HttpTestClient.localhost(providerPort);
    }

    /** Requested method of the pre-flight, then its {@code Access-Control-Request-Headers}, or {@code null}. */
    static Stream<Arguments> preflights() {
        return Stream.of(
                Arguments.of("GET", null),
                Arguments.of("POST", null),
                Arguments.of("PUT", null),
                Arguments.of("DELETE", null),
                Arguments.of("OPTIONS", null),
                Arguments.of("POST", "authorization, content-type"));
    }

    /** Each provider endpoint, then each pre-flight of {@link #preflights()}. */
    static Stream<Arguments> providerEndpointPreflights() {
        return Stream.of("/token", "/authorize").flatMap(path -> preflights()
                .map(preflight -> Arguments.of(path, preflight.get()[0], preflight.get()[1])));
    }

    /** Listener receiving the pre-flight, a path it does not serve, then the requested method. */
    static Stream<Arguments> unservedPreflights() {
        return Stream.of(
                Arguments.of(PROVIDER_LISTENER, "/resources", "GET"),
                Arguments.of(PROVIDER_LISTENER, "/resources", "POST"),
                Arguments.of(PROVIDER_LISTENER, "/redirect", "GET"),
                Arguments.of(PROVIDER_LISTENER, "/unknown", "POST"),
                Arguments.of(RESOURCE_LISTENER, "/token", "POST"),
                Arguments.of(RESOURCE_LISTENER, "/token", "GET"),
                Arguments.of(RESOURCE_LISTENER, "/authorize", "GET"),
                Arguments.of(RESOURCE_LISTENER, "/unknown", "POST"));
    }

    @ParameterizedTest(name = "Access-Control-Request-Method {0}, Access-Control-Request-Headers {1}")
    @MethodSource("preflights")
    void preflightToResourcesWithoutTokenRunsTheTokenCheck(String requestedMethod, String requestedHeaders) {
        Response preflight = assertAnsweredLikePlainOptions(listener, "/resources", Map.of(), requestedMethod,
                requestedHeaders);

        assertEquals("HTTP/1.1 401 Unauthorized", preflight.statusLine(), preflight::toString);
        assertEquals("Bearer realm=\"" + providerName + "\"", preflight.header("WWW-Authenticate"),
                preflight::toString);
        assertMediaType(preflight, MediaType.APPLICATION_JSON);
        assertArrayEquals(bytes("{ \"message\": \"Access token missing\" }"), preflight.body(), preflight::toString);
    }

    @ParameterizedTest(name = "Access-Control-Request-Method {0}, Access-Control-Request-Headers {1}")
    @MethodSource("preflights")
    void preflightToResourcesWithReadTokenAnswersPayroll(String requestedMethod, String requestedHeaders)
            throws IOException {
        Map<String, String> authorization = Map.of("Authorization", "Bearer " + accessToken());

        Response preflight = assertAnsweredLikePlainOptions(listener, "/resources", authorization, requestedMethod,
                requestedHeaders);

        assertEquals("HTTP/1.1 200 OK", preflight.statusLine(), preflight::toString);
        assertMediaType(preflight, MediaType.APPLICATION_JSON);
        assertArrayEquals(bytes("{\"name\":\"payroll\",\"uri\":\"http://localhost:" + listenerPort
                + "/resources/payroll\"}"), preflight.body(), preflight::toString);
    }

    @ParameterizedTest(name = "Access-Control-Request-Method {0}, Access-Control-Request-Headers {1}")
    @MethodSource("preflights")
    void preflightToRedirectAnswersTheRedirectToTheTokenEndpoint(String requestedMethod, String requestedHeaders) {
        Response preflight = assertAnsweredLikePlainOptions(listener, "/redirect?code=abc", Map.of(), requestedMethod,
                requestedHeaders);

        assertEquals("HTTP/1.1 302 Found", preflight.statusLine(), preflight::toString);
        assertEquals("http://localhost:" + providerPort + "/token?grant_type=authorization_code&&client_id="
                        + clientId + "&client_secret=" + clientSecret + "&code=abc&redirect_uri=" + redirectUri(),
                preflight.header("Location"), preflight::toString);
        assertArrayEquals(new byte[0], preflight.body(), preflight::toString);
    }

    @ParameterizedTest(name = "{0}: Access-Control-Request-Method {1}, Access-Control-Request-Headers {2}")
    @MethodSource("providerEndpointPreflights")
    void preflightToProviderEndpointGetsTheListenerMethod405(String path, String requestedMethod,
                                                             String requestedHeaders) {
        Response preflight = assertAnsweredLikePlainOptions(provider, path, Map.of(), requestedMethod,
                requestedHeaders);

        assertEquals("HTTP/1.1 405 Method Not Allowed", preflight.statusLine(), preflight::toString);
        assertMediaType(preflight, MediaType.TEXT_PLAIN);
        assertArrayEquals(bytes("Method not allowed for endpoint: " + path), preflight.body(), preflight::toString);
        assertNull(preflight.header("Allow"), preflight::toString);
    }

    @ParameterizedTest(name = "{1} on the {0} listener, Access-Control-Request-Method {2}")
    @MethodSource("unservedPreflights")
    void preflightToAPathTheListenerDoesNotServeGetsTheNoListener404(String listenerName, String path,
                                                                    String requestedMethod) {
        HttpTestClient client = RESOURCE_LISTENER.equals(listenerName) ? listener : provider;

        Response preflight = assertAnsweredLikePlainOptions(client, path, Map.of(), requestedMethod, null);

        assertEquals("HTTP/1.1 404 Not Found", preflight.statusLine(), preflight::toString);
        assertEquals(MediaType.parseMediaType(NO_LISTENER_CONTENT_TYPE),
                MediaType.parseMediaType(preflight.header("Content-Type")), preflight::toString);
        assertArrayEquals(bytes("No listener for endpoint: " + path), preflight.body(), preflight::toString);
    }

    /**
     * Sends {@code OPTIONS target} with {@code headers}, then the same request as a pre-flight from {@link #ORIGIN}
     * asking for {@code requestedMethod} (and {@code requestedHeaders} unless {@code null}), and asserts the two
     * responses are the same and the pre-flight's carries no CORS header.
     *
     * @return the pre-flight's response
     */
    private static Response assertAnsweredLikePlainOptions(HttpTestClient client, String target,
                                                           Map<String, String> headers, String requestedMethod,
                                                           String requestedHeaders) {
        Response plain = client.send("OPTIONS", target, headers, null);

        Map<String, String> preflightHeaders = new LinkedHashMap<>(headers);
        preflightHeaders.put("Origin", ORIGIN);
        preflightHeaders.put("Access-Control-Request-Method", requestedMethod);
        if (requestedHeaders != null) {
            preflightHeaders.put("Access-Control-Request-Headers", requestedHeaders);
        }
        Response preflight = client.send("OPTIONS", target, preflightHeaders, null);

        assertSameResponse(plain, preflight);
        assertNoCorsHeaders(preflight);
        return preflight;
    }

    /** Same status line, same body bytes and the same headers but {@code Date}, compared as sorted lines. */
    private static void assertSameResponse(Response expected, Response actual) {
        assertEquals(expected.statusLine(), actual.statusLine(), actual::toString);
        assertArrayEquals(expected.body(), actual.body(), actual::toString);
        assertEquals(headerLines(expected), headerLines(actual), actual::toString);
    }

    /** Every header but {@code Date} as {@code name: value} with the name lower-cased, sorted, duplicates kept. */
    private static List<String> headerLines(Response response) {
        return response.headers().stream()
                .filter(header -> !header.getKey().equalsIgnoreCase("Date"))
                .map(header -> header.getKey().toLowerCase(Locale.ROOT) + ": " + header.getValue())
                .sorted()
                .toList();
    }

    private static void assertNoCorsHeaders(Response response) {
        assertTrue(response.headers().stream().map(Map.Entry::getKey).noneMatch(name ->
                        name.regionMatches(true, 0, "Access-Control-", 0, "Access-Control-".length())
                                || name.equalsIgnoreCase("Vary")),
                response::toString);
    }

    /** The {@code Content-Type} has the type and subtype of {@code type}, whatever its parameters. */
    private static void assertMediaType(Response response, MediaType type) {
        String contentType = response.header("Content-Type");
        assertNotNull(contentType, response::toString);
        assertTrue(MediaType.parseMediaType(contentType).equalsTypeAndSubtype(type),
                () -> "Content-Type " + contentType + " is not " + type + ": " + response);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** The redirect URI the codes are issued for: the resource listener's {@code /redirect}, as in the original. */
    private static String redirectUri() {
        return "http://localhost:" + listenerPort + "/redirect";
    }

    /**
     * Logs the resource owner in at {@code POST /authorize} for {@code READ_RESOURCE}, exchanges the issued code at
     * {@code GET /token} and returns the access token; codes are single-use, so every call logs in afresh.
     */
    private String accessToken() throws IOException {
        Response login = provider.post("/authorize", FORM, form(
                "response_type", "code",
                "client_id", clientId,
                "redirect_uri", redirectUri(),
                "scope", READ_RESOURCE,
                "state", "xyz",
                "username", ownerUsername,
                "password", ownerPassword));
        assertEquals(302, login.status(), login::toString);
        String codePrefix = redirectUri() + "?code=";
        String location = login.header("Location");
        assertTrue(location != null && location.startsWith(codePrefix), login::toString);
        int end = location.indexOf('&', codePrefix.length());
        String code = URLDecoder.decode(end < 0 ? location.substring(codePrefix.length())
                : location.substring(codePrefix.length(), end), StandardCharsets.UTF_8);

        Response token = provider.get("/token?" + form(
                "grant_type", "authorization_code",
                "client_id", clientId,
                "client_secret", clientSecret,
                "code", code,
                "redirect_uri", redirectUri()));
        assertEquals(200, token.status(), token::toString);
        JsonNode body = JSON.readTree(token.body());
        assertEquals(READ_RESOURCE, body.path("scope").asText(), token::toString);
        String accessToken = body.path("access_token").asText();
        assertFalse(accessToken.isBlank(), token::toString);
        return accessToken;
    }

    /** {@code name=value} pairs joined by {@code &}, names and values form-encoded in UTF-8. */
    private static String form(String... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("keys and values must come in pairs");
        }
        StringJoiner pairs = new StringJoiner("&");
        for (int i = 0; i < keysAndValues.length; i += 2) {
            pairs.add(URLEncoder.encode(keysAndValues[i], StandardCharsets.UTF_8) + "="
                    + URLEncoder.encode(keysAndValues[i + 1], StandardCharsets.UTF_8));
        }
        return pairs.toString();
    }
}
