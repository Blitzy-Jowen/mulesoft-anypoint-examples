/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_oauth_provider.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.http_oauth_provider.support.HttpTestClient;
import com.mulesoft.examples.http_oauth_provider.support.HttpTestClient.Response;
import com.mulesoft.examples.http_oauth_provider.support.PortInUseRetryingContextLoader;

/**
 * On-the-wire test of {@link ResourcesController}: the two flows of the original resource listener
 * ({@code http-oauth-provider.xml}), {@code protectedAuthcodeFlow} on {@code /resources} (xml:44-49) and
 * {@code redirectFlow} on {@code /redirect} (xml:53-57), with their listener-level 404s.
 *
 * <p>The whole application runs on Undertow and every request goes through the project's ST-1
 * {@link HttpTestClient}, so the raw status line with its reason phrase, the headers and the body bytes are
 * asserted as they reach the wire. Nothing is mocked: each bearer token is issued by the real provider endpoints,
 * {@code POST /authorize} with the demo owner and {@code GET /token} with the demo client of profile {@code test},
 * as the original test obtained its token through the browser login.
 *
 * <p>Both listeners run on concrete ports drawn on the loopback interface for every attempt to load the context
 * ({@code DEFINED_PORT}) rather than on {@code RANDOM_PORT}: the SC-10 {@code provider} listener requires a
 * concrete port, {@code PortRoutingFilter} tells the two listeners apart by local port, and the expected payroll
 * {@code uri} and {@code /redirect} {@code Location} embed {@code http.listener.port} and
 * {@code http.provider.port}, which must therefore be the ports actually served. An attempt that loses a drawn port
 * to another process before Undertow binds it is repeated on newly drawn ports
 * ({@link PortInUseRetryingContextLoader}), so the ports, and every expected value built from them, are read from
 * the loaded context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ContextConfiguration(loader = PortInUseRetryingContextLoader.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ResourcesControllerTest {

    private static final String FORM = "application/x-www-form-urlencoded";

    /** The SC-10 no-listener answer's Content-Type (AAP 0.4.7). */
    private static final String NO_LISTENER_CONTENT_TYPE = "text/plain";

    /** APIkit's error body spacing, {@code { "message": "..." }}. */
    private static final Pattern MESSAGE_BODY = Pattern.compile("^\\{ \"message\": \".*\" \\}$");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The resource listener's port in the loaded context, the main {@code server.port}. */
    @LocalServerPort
    private int listenerPort;

    /** The SC-10 provider listener's port in the loaded context. */
    @Value("${http.provider.port}")
    private int providerPort;

    private HttpTestClient listener;

    private HttpTestClient provider;

    @DynamicPropertySource
    static void listenerPorts(DynamicPropertyRegistry registry) {
        // Called again for every attempt to load the context, so each attempt gets ports drawn for it.
        // Both sockets are open at once, so the two ports differ; closing them frees the ports for Undertow.
        int resource;
        int oauthProvider;
        try (ServerSocket resourceSocket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
             ServerSocket providerSocket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            resource = resourceSocket.getLocalPort();
            oauthProvider = providerSocket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot draw the resource and provider listener ports", e);
        }
        // http.listener.port is the main server.port; http.provider.port is the SC-10 "provider" listener.
        registry.add("http.listener.port", () -> resource);
        registry.add("http.provider.port", () -> oauthProvider);
    }

    @BeforeEach
    void clients() {
        listener = HttpTestClient.localhost(listenerPort);
        provider = HttpTestClient.localhost(providerPort);
    }

    @Test
    void getResourcesWithReadTokenReturnsPayroll() throws IOException {
        Response response = listener.get("/resources", bearer(accessToken("READ_RESOURCE")));

        assertPayroll(response);
        // The original test's assertions (HttpOauthProviderIT:105-107).
        JsonNode resource = JSON.readTree(response.body());
        assertThat(resource.path("name").textValue()).isEqualTo("payroll");
        assertThat(resource.path("uri").textValue()).isEqualTo(payrollUri());
    }

    @Test
    void postResourcesWithReadTokenReturnsPayroll() throws IOException {
        // The listener declares no allowedMethods, so the flow runs for POST exactly as for GET.
        Response response = listener.send("POST", "/resources", bearer(accessToken("READ_RESOURCE")), new byte[0]);

        assertPayroll(response);
    }

    @Test
    void missingTokenIs401() {
        Response response = listener.get("/resources");

        assertThat(response.status()).as(response::toString).isEqualTo(401);
        assertThat(response.reason()).isEqualTo("Unauthorized");
        // RFC 6750 section 3: a bearer-protected resource answers a request without a token with a Bearer challenge.
        assertThat(response.header("WWW-Authenticate")).isNotBlank().startsWithIgnoringCase("Bearer ");
        assertMediaType(response, MediaType.APPLICATION_JSON);
        assertThat(response.bodyAsString()).isEqualTo("{ \"message\": \"Access token missing\" }");
    }

    @Test
    void bogusTokenIs401InvalidToken() {
        Response response = listener.get("/resources", bearer("not-a-real-token"));

        assertThat(response.status()).as(response::toString).isEqualTo(401);
        assertThat(response.reason()).isEqualTo("Unauthorized");
        assertThat(response.header("WWW-Authenticate"))
                .startsWithIgnoringCase("Bearer ")
                .contains("error=\"invalid_token\"");
        assertMessageBody(response);
    }

    @Test
    void postResourceOnlyTokenIs403() throws IOException {
        // A valid token whose only scope is POST_RESOURCE fails validate scopes="READ_RESOURCE" (xml:46).
        Response response = listener.get("/resources", bearer(accessToken("POST_RESOURCE")));

        assertThat(response.status()).as(response::toString).isEqualTo(403);
        assertThat(response.reason()).isEqualTo("Forbidden");
        assertThat(response.header("WWW-Authenticate"))
                .startsWithIgnoringCase("Bearer ")
                .contains("error=\"insufficient_scope\"");
        assertMessageBody(response);
    }

    @Test
    void redirectWithCodeBuildsTokenLocation() {
        Response response = listener.get("/redirect?code=abc");

        assertThat(response.status()).as(response::toString).isEqualTo(302);
        assertThat(response.reason()).isEqualTo("Found");
        // xml:56 verbatim: the empty parameter between "&&" and the unencoded redirect_uri are kept.
        assertThat(response.header("Location")).isEqualTo("http://localhost:" + providerPort
                + "/token?grant_type=authorization_code&&client_id=myclientid&client_secret=myclientsecret"
                + "&code=abc&redirect_uri=http://localhost:" + listenerPort + "/redirect");
        assertThat(response.body()).isEmpty();
    }

    @Test
    void redirectWithoutCodeCarriesNull() {
        Response response = listener.get("/redirect");

        assertThat(response.status()).as(response::toString).isEqualTo(302);
        assertThat(response.reason()).isEqualTo("Found");
        // MEL renders the missing query parameter of #[message.inboundProperties['http.query.params'].code] as null.
        assertThat(response.header("Location"))
                .startsWith("http://localhost:" + providerPort + "/token?grant_type=authorization_code&&")
                .contains("&code=null&");
        assertThat(response.body()).isEmpty();
    }

    @Test
    void resourcesOnProviderPortHasNoListener() {
        assertNoListener(provider.get("/resources"), "/resources");
    }

    @Test
    void resourcesSubPathHasNoListener() {
        // The listener path /resources is exact; the payroll uri it advertises is not itself served.
        assertNoListener(listener.get("/resources/payroll"), "/resources/payroll");
    }

    @Test
    void redirectOnProviderPortHasNoListener() {
        assertNoListener(provider.get("/redirect?code=abc"), "/redirect");
    }

    @Test
    void redirectSubPathHasNoListener() {
        assertNoListener(listener.get("/redirect/extra"), "/redirect/extra");
    }

    /** The resource the original serves: 200 and the exact bytes of xml:47-48 written by the JSON transformer. */
    private void assertPayroll(Response response) {
        String expected = "{\"name\":\"payroll\",\"uri\":\"" + payrollUri() + "\"}";
        assertThat(response.status()).as(response::toString).isEqualTo(200);
        assertThat(response.reason()).isEqualTo("OK");
        assertMediaType(response, MediaType.APPLICATION_JSON);
        assertThat(response.bodyAsString()).isEqualTo(expected);
        assertThat(response.body()).isEqualTo(expected.getBytes(StandardCharsets.UTF_8));
    }

    /** The no-listener answer of a port that does not serve {@code path} (AAP 0.4.7, SC-10). */
    private static void assertNoListener(Response response, String path) {
        assertThat(response.status()).as(response::toString).isEqualTo(404);
        assertThat(response.reason()).isEqualTo("Not Found");
        assertContentType(response, NO_LISTENER_CONTENT_TYPE);
        assertThat(response.bodyAsString()).isEqualTo("No listener for endpoint: " + path);
    }

    private String payrollUri() {
        return "http://localhost:" + listenerPort + "/resources/payroll";
    }

    private String redirectUri() {
        return "http://localhost:" + listenerPort + "/redirect";
    }

    /**
     * Logs the demo owner in on the provider's {@code /authorize} form and returns the authorization code carried
     * by the 302 back to {@link #redirectUri()}.
     */
    private String authorizeCode(String scope) {
        Response response = provider.post("/authorize", FORM, form(
                "response_type", "code",
                "client_id", "myclientid",
                "redirect_uri", redirectUri(),
                "scope", scope,
                "state", "xyz",
                "username", "mule",
                "password", "mule"));

        assertThat(response.status()).as(response::toString).isEqualTo(302);
        String prefix = redirectUri() + "?code=";
        String location = response.header("Location");
        assertThat(location).startsWith(prefix);
        String rest = location.substring(prefix.length());
        int end = rest.indexOf('&');
        String code = URLDecoder.decode(end < 0 ? rest : rest.substring(0, end), StandardCharsets.UTF_8);
        assertThat(code).isNotBlank();
        return code;
    }

    /** Exchanges a fresh authorization code for {@code scope} at the provider's {@code /token} by GET. */
    private String accessToken(String scope) throws IOException {
        Response response = provider.get("/token" + query(
                "grant_type", "authorization_code",
                "client_id", "myclientid",
                "client_secret", "myclientsecret",
                "code", authorizeCode(scope),
                "redirect_uri", redirectUri()));

        assertThat(response.status()).as(response::toString).isEqualTo(200);
        JsonNode token = JSON.readTree(response.body());
        assertThat(token.path("scope").textValue()).isEqualTo(scope);
        String accessToken = token.path("access_token").textValue();
        assertThat(accessToken).isNotBlank();
        return accessToken;
    }

    private static Map<String, String> bearer(String token) {
        return Map.of("Authorization", "Bearer " + token);
    }

    /** {@code name=value} pairs joined by {@code &}, each value form-encoded in UTF-8. */
    private static String form(String... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("Name/value pairs expected, got " + kv.length + " arguments");
        }
        StringJoiner pairs = new StringJoiner("&");
        for (int i = 0; i < kv.length; i += 2) {
            pairs.add(kv[i] + "=" + URLEncoder.encode(kv[i + 1], StandardCharsets.UTF_8));
        }
        return pairs.toString();
    }

    /** The query string of {@link #form}, with its leading {@code ?}. */
    private static String query(String... kv) {
        return "?" + form(kv);
    }

    /** The {@code Content-Type} equals {@code expected}, parameters included (charset compared ignoring case). */
    private static void assertContentType(Response response, String expected) {
        String contentType = response.header("Content-Type");
        assertThat(contentType).as("Content-Type of %s", response).isNotNull();
        assertThat(MediaType.parseMediaType(contentType)).isEqualTo(MediaType.parseMediaType(expected));
    }

    /** The {@code Content-Type} has the type and subtype of {@code type}, whatever its parameters. */
    private static void assertMediaType(Response response, MediaType type) {
        String contentType = response.header("Content-Type");
        assertThat(contentType).as("Content-Type of %s", response).isNotNull();
        MediaType actual = MediaType.parseMediaType(contentType);
        assertThat(actual.getType()).isEqualTo(type.getType());
        assertThat(actual.getSubtype()).isEqualTo(type.getSubtype());
    }

    /** A {@code ResourcesController} OAuth error body: {@code application/json} {@code { "message": "..." }}. */
    private static void assertMessageBody(Response response) {
        assertMediaType(response, MediaType.APPLICATION_JSON);
        assertThat(response.bodyAsString()).matches(MESSAGE_BODY);
    }
}
