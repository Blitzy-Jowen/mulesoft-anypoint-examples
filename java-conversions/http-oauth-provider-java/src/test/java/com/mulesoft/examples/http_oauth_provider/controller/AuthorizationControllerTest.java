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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import com.mulesoft.examples.http_oauth_provider.support.PortInUseRetryingContextLoader;

/**
 * On-the-wire test of {@code /authorize}, the authorization endpoint and login page the Mule OAuth2 provider
 * module generated from {@code oauth2-provider:config} (original {@code http-oauth-provider.xml} lines 26-41).
 *
 * <p>The whole application runs on its two Undertow listeners, each on a free loopback port: the resource listener
 * ({@code http.listener.port}, the main {@code server.port}) and the provider listener ({@code http.provider.port},
 * the SC-10 additional listener serving {@code /authorize} and {@code /token}). Requests go through ST-1
 * {@link HttpTestClient}, so status code, reason phrase, headers and body bytes are asserted exactly as they reach the
 * wire; nothing is mocked.
 *
 * <p>The two ports are drawn for every attempt to load the context, and an attempt that loses one to another process
 * before Undertow binds it is repeated on newly drawn ports ({@link PortInUseRetryingContextLoader}); the tests
 * therefore read both ports, and the redirect URI built from the resource port, from the loaded context.
 *
 * <p>The {@code test} profile ({@code application-test.yml}) configures the original demo client {@code myclientid}
 * and resource owner {@code mule}/{@code mule}. The happy path follows the original {@code HttpOauthProviderIT}:
 * its authorization URL renders the login form whose {@code username} and {@code password} fields the IT fills, and
 * submitting them redirects to the client's {@code redirect_uri} with a code.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ContextConfiguration(loader = PortInUseRetryingContextLoader.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthorizationControllerTest {

    private static final String FORM = "application/x-www-form-urlencoded";

    private static final String AUTHORIZE = "/authorize";

    /** The original's client ID; an identifier carried in the authorization URL, not a secret. */
    private static final String CLIENT_ID = "myclientid";

    /** The original demo resource owner, which {@code application-test.yml} configures; not a production login. */
    private static final String OWNER_USERNAME = "mule";
    private static final String OWNER_PASSWORD = "mule";

    /** The message {@link AuthorizationController} shows on the login page after a rejected login. */
    private static final String INVALID_CREDENTIALS = "Invalid username or password";

    private static final String STATE = "xyz";

    private static final String SCRIPT_STATE = "<script>alert(1)</script>";

    /** The resource listener's port in the loaded context, the main {@code server.port}. */
    @LocalServerPort
    private int listenerPort;

    /** The SC-10 provider listener's port in the loaded context. */
    @Value("${http.provider.port}")
    private int providerPort;

    private HttpTestClient provider;

    private HttpTestClient listener;

    /**
     * Draws two distinct free loopback ports, both sockets open at once, for the resource and provider listeners;
     * {@code application.yml} derives {@code server.port} from {@code http.listener.port}. Spring calls this method
     * again for every attempt to load the context, so each attempt runs on ports drawn for it.
     */
    @DynamicPropertySource
    static void ports(DynamicPropertyRegistry registry) {
        int drawnListenerPort;
        int drawnProviderPort;
        try (ServerSocket listenerSocket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
             ServerSocket providerSocket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            drawnListenerPort = listenerSocket.getLocalPort();
            drawnProviderPort = providerSocket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot draw the listener and provider ports", e);
        }
        registry.add("http.listener.port", () -> drawnListenerPort);
        registry.add("http.provider.port", () -> drawnProviderPort);
    }

    @BeforeEach
    void clients() {
        provider = HttpTestClient.localhost(providerPort);
        listener = HttpTestClient.localhost(listenerPort);
    }

    @Test
    void getAuthorizeRendersLoginPage() {
        // The original IT's authorization URL, redirect_uri unencoded as the IT sent it, plus a state.
        HttpTestClient.Response resp = provider.get(AUTHORIZE + "?response_type=code&client_id=" + CLIENT_ID
                + "&scope=READ_RESOURCE&redirect_uri=" + redirectUri() + "&state=" + STATE);

        assertThat(resp.status()).as("%s", resp).isEqualTo(200);
        assertThat(resp.reason()).isEqualTo("OK");
        assertMediaType(resp, MediaType.TEXT_HTML);
        assertThat(resp.bodyAsString()).contains(
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">",
                "id=\"username\"", "name=\"username\"",
                "id=\"password\"", "name=\"password\"",
                "SampleAPI", "Login and Authorize",
                "value=\"code\"",
                "value=\"" + CLIENT_ID + "\"",
                "value=\"READ_RESOURCE\"",
                "value=\"" + redirectUri() + "\"",
                "value=\"" + STATE + "\"");
        assertThat(resp.bodyAsString()).doesNotContain("id=\"error\"");
    }

    @Test
    void getAuthorizeEscapesState() {
        HttpTestClient.Response resp = provider.get(AUTHORIZE + "?" + query(
                "response_type", "code",
                "client_id", CLIENT_ID,
                "scope", "READ_RESOURCE",
                "redirect_uri", redirectUri(),
                "state", SCRIPT_STATE));

        assertThat(resp.status()).as("%s", resp).isEqualTo(200);
        assertThat(resp.reason()).isEqualTo("OK");
        assertMediaType(resp, MediaType.TEXT_HTML);
        assertThat(resp.bodyAsString())
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;")
                .doesNotContain(SCRIPT_STATE);
    }

    @Test
    void getAuthorizeUnknownClientIsInvalidClient() throws IOException {
        HttpTestClient.Response resp = provider.get(AUTHORIZE + "?" + query(
                "response_type", "code",
                "client_id", "unknownclient",
                "scope", "READ_RESOURCE",
                "redirect_uri", redirectUri(),
                "state", STATE));

        assertOAuthError(resp, 400, "invalid_client");
        assertThat(resp.reason()).isEqualTo("Bad Request");
        assertThat(resp.header("Location")).isNull();
    }

    @Test
    void getAuthorizeForeignRedirectUriIsInvalidRequest() throws IOException {
        HttpTestClient.Response resp = provider.get(AUTHORIZE + "?" + query(
                "response_type", "code",
                "client_id", CLIENT_ID,
                "scope", "READ_RESOURCE",
                "redirect_uri", "http://evil.example/cb",
                "state", STATE));

        assertOAuthError(resp, 400, "invalid_request");
        assertThat(resp.reason()).isEqualTo("Bad Request");
        // The error is answered to the caller, never redirected to an unregistered URI.
        assertThat(resp.header("Location")).isNull();
    }

    @Test
    void getAuthorizeTokenResponseTypeIsUnsupported() throws IOException {
        HttpTestClient.Response resp = provider.get(AUTHORIZE + "?" + query(
                "response_type", "token",
                "client_id", CLIENT_ID,
                "scope", "READ_RESOURCE",
                "redirect_uri", redirectUri(),
                "state", STATE));

        assertOAuthError(resp, 400, "unsupported_response_type");
        assertThat(resp.reason()).isEqualTo("Bad Request");
        assertThat(resp.header("Location")).isNull();
    }

    @Test
    void getAuthorizeUnknownScopeIsInvalidScope() throws IOException {
        HttpTestClient.Response resp = provider.get(AUTHORIZE + "?" + query(
                "response_type", "code",
                "client_id", CLIENT_ID,
                "scope", "DELETE_RESOURCE",
                "redirect_uri", redirectUri(),
                "state", STATE));

        assertOAuthError(resp, 400, "invalid_scope");
        assertThat(resp.reason()).isEqualTo("Bad Request");
        assertThat(resp.header("Location")).isNull();
    }

    @Test
    void postAuthorizeValidOwnerRedirectsWithCode() {
        HttpTestClient.Response resp = provider.post(AUTHORIZE, FORM, loginForm(OWNER_PASSWORD));

        assertThat(resp.status()).as("%s", resp).isEqualTo(302);
        assertThat(resp.reason()).isEqualTo("Found");
        String location = resp.header("Location");
        assertThat(location)
                .startsWith(redirectUri() + "?code=")
                .contains("&state=" + STATE);

        int codeStart = location.indexOf("code=") + "code=".length();
        int codeEnd = location.indexOf('&', codeStart);
        String code = URLDecoder.decode(
                codeEnd < 0 ? location.substring(codeStart) : location.substring(codeStart, codeEnd),
                StandardCharsets.UTF_8);
        assertThat(code).isNotBlank();
    }

    @Test
    void postAuthorizeWrongPasswordRerendersLoginWith401() {
        HttpTestClient.Response resp = provider.post(AUTHORIZE, FORM, loginForm("wrong"));

        assertThat(resp.status()).as("%s", resp).isEqualTo(401);
        assertThat(resp.reason()).isEqualTo("Unauthorized");
        assertMediaType(resp, MediaType.TEXT_HTML);
        assertThat(resp.header("Location")).isNull();
        assertThat(resp.bodyAsString())
                .contains("id=\"error\"", INVALID_CREDENTIALS, "name=\"username\"")
                // The authorization request survives in the hidden fields; the rejected password is not echoed.
                .contains("value=\"" + STATE + "\"")
                .doesNotContain("value=\"wrong\"");
    }

    /**
     * The login form submitted with a method outside GET and POST gets the listener's 405 without an {@code Allow}
     * header and logs nobody in; {@code OPTIONS} is answered by the same 405 rather than by Spring MVC's own 200
     * with {@code Allow}.
     */
    @ParameterizedTest(name = "{0} /authorize")
    @ValueSource(strings = {"PUT", "OPTIONS"})
    void unsupportedMethodOnAuthorizeIsMethodNotAllowed(String method) {
        HttpTestClient.Response resp = provider.send(method, AUTHORIZE, Map.of("Content-Type", FORM),
                loginForm(OWNER_PASSWORD).getBytes(StandardCharsets.UTF_8));

        assertThat(resp.status()).as("%s", resp).isEqualTo(405);
        assertThat(resp.reason()).isEqualTo("Method Not Allowed");
        assertMediaType(resp, MediaType.TEXT_PLAIN);
        assertThat(resp.bodyAsString()).isEqualTo("Method not allowed for endpoint: /authorize");
        assertThat(resp.header("Allow")).as("%s", resp).isNull();
        assertThat(resp.header("Location")).as("%s", resp).isNull();
    }

    @Test
    void authorizeOnListenerPortHasNoListener() {
        HttpTestClient.Response resp = listener.get(AUTHORIZE);

        assertThat(resp.status()).as("%s", resp).isEqualTo(404);
        assertThat(resp.reason()).isEqualTo("Not Found");
        assertContentType(resp, "text/plain");
        assertThat(resp.bodyAsString()).isEqualTo("No listener for endpoint: /authorize");
    }

    /** The {@code redirect_uri} of the original IT: the resource listener's {@code /redirect}. */
    private String redirectUri() {
        return "http://localhost:" + listenerPort + "/redirect";
    }

    /** The login form as the page posts it: the five hidden fields of a valid request and the owner's login. */
    private String loginForm(String password) {
        return form(
                "response_type", "code",
                "client_id", CLIENT_ID,
                "redirect_uri", redirectUri(),
                "scope", "READ_RESOURCE",
                "state", STATE,
                "username", OWNER_USERNAME,
                "password", password);
    }

    /** An {@code application/x-www-form-urlencoded} body, encoded as a query string is. */
    private static String form(String... kv) {
        return query(kv);
    }

    /**
     * {@code k1=v1&k2=v2...} from alternating names and values, each value encoded with
     * {@link URLEncoder} in UTF-8; the names are used as given.
     */
    private static String query(String... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("names and values must alternate: " + kv.length + " arguments");
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) {
                out.append('&');
            }
            out.append(kv[i]).append('=').append(URLEncoder.encode(kv[i + 1], StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    /** The response's {@code Content-Type} equals {@code expected}, parameters included. */
    private static void assertContentType(HttpTestClient.Response resp, String expected) {
        String contentType = resp.header("Content-Type");
        assertThat(contentType).as("Content-Type of %s", resp).isNotNull();
        assertThat(MediaType.parseMediaType(contentType)).isEqualTo(MediaType.parseMediaType(expected));
    }

    /** The response's {@code Content-Type} has the type and subtype of {@code expectedTypeSubtype}. */
    private static void assertMediaType(HttpTestClient.Response resp, MediaType expectedTypeSubtype) {
        String contentType = resp.header("Content-Type");
        assertThat(contentType).as("Content-Type of %s", resp).isNotNull();
        MediaType actual = MediaType.parseMediaType(contentType);
        assertThat(actual.equalsTypeAndSubtype(expectedTypeSubtype))
                .as("Content-Type %s is %s", contentType, expectedTypeSubtype)
                .isTrue();
    }

    /** An OAuth error answer: {@code status}, {@code application/json} and the JSON {@code error} member. */
    private static void assertOAuthError(HttpTestClient.Response resp, int status, String error) throws IOException {
        assertThat(resp.status()).as("%s", resp).isEqualTo(status);
        assertMediaType(resp, MediaType.APPLICATION_JSON);
        JsonNode body = new ObjectMapper().readTree(resp.body());
        assertThat(body.path("error").asText()).as("%s", resp).isEqualTo(error);
    }
}
