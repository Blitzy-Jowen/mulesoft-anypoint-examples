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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/**
 * On-the-wire test of the provider's {@code /token} endpoint ({@link TokenController}), which replaces the token
 * endpoint the Mule {@code oauth2-provider:config} generated (original {@code http-oauth-provider.xml} lines 26-41).
 *
 * <p>The whole application runs on its two Undertow listeners, each on a free loopback port: the resource listener
 * ({@code http.listener.port}, {@code /resources}, {@code /redirect}) and the SC-10 provider listener
 * ({@code http.provider.port}, {@code /authorize}, {@code /token}). Nothing is mocked: every code is issued by a real
 * login at {@code POST /authorize}, and every answer is read by ST-1 from the socket, so status line, headers and
 * body bytes are asserted as a browser receives them. Codes are single-use, so each test that exchanges one
 * obtains its own.
 *
 * <p>The client and resource-owner credentials are the original demo values of the {@code test} profile
 * ({@code src/test/resources/application-test.yml}), injected rather than repeated here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TokenControllerTest {

    private static final String FORM = "application/x-www-form-urlencoded";

    private static final String READ_RESOURCE = "READ_RESOURCE";

    private static final String METHOD_NOT_ALLOWED_BODY = "Method not allowed for endpoint: /token";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static int listenerPort;

    private static int providerPort;

    @Value("${oauth.provider.client-id}")
    private String clientId;

    @Value("${oauth.provider.client-secret}")
    private String clientSecret;

    @Value("${oauth.provider.owner.username}")
    private String ownerUsername;

    @Value("${oauth.provider.owner.password}")
    private String ownerPassword;

    private HttpTestClient provider;

    private HttpTestClient listener;

    /**
     * Reserves two distinct free loopback ports, one per listener, and hands them to the application, which binds
     * them once the sockets reserving them are closed.
     */
    @DynamicPropertySource
    static void ports(DynamicPropertyRegistry registry) {
        try (ServerSocket listenerSocket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
             ServerSocket providerSocket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            listenerPort = listenerSocket.getLocalPort();
            providerPort = providerSocket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot reserve free loopback ports for the two listeners", e);
        }
        registry.add("http.listener.port", () -> listenerPort);
        registry.add("http.provider.port", () -> providerPort);
    }

    /** The ports are known only once the context has been created, after the test instance itself. */
    @BeforeEach
    void clients() {
        provider = HttpTestClient.localhost(providerPort);
        listener = HttpTestClient.localhost(listenerPort);
    }

    @Test
    void getTokenExchangesAuthorizationCode() throws IOException {
        String code = authorizeCode(READ_RESOURCE, redirectUri());

        HttpTestClient.Response resp = provider.get("/token" + query(
                "grant_type", "authorization_code",
                "client_id", clientId,
                "client_secret", clientSecret,
                "code", code,
                "redirect_uri", redirectUri()));

        assertIssuedToken(resp, READ_RESOURCE);
    }

    @Test
    void postFormTokenExchangesAuthorizationCode() throws IOException {
        String code = authorizeCode(READ_RESOURCE, redirectUri());

        HttpTestClient.Response resp = provider.post("/token", FORM, form(
                "grant_type", "authorization_code",
                "client_id", clientId,
                "client_secret", clientSecret,
                "code", code,
                "redirect_uri", redirectUri()));

        assertIssuedToken(resp, READ_RESOURCE);
    }

    @Test
    void basicAuthenticatedClientExchangesCode() throws IOException {
        String code = authorizeCode(READ_RESOURCE, redirectUri());
        String credentials = Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

        HttpTestClient.Response resp = provider.post("/token", FORM, form(
                        "grant_type", "authorization_code",
                        "code", code,
                        "redirect_uri", redirectUri()),
                Map.of("Authorization", "Basic " + credentials));

        assertIssuedToken(resp, READ_RESOURCE);
    }

    /**
     * The browser hop of the original: {@code /redirect} answers a 302 whose {@code Location} is the original
     * template verbatim, empty parameter between {@code &&} included, and following it with a GET of exactly that
     * target, nothing re-encoded, yields the token page the original test read its {@code access_token} from.
     */
    @Test
    void redirectHopLocationIssuesToken() throws IOException {
        String code = authorizeCode(READ_RESOURCE, redirectUri());

        HttpTestClient.Response redirect = listener.get("/redirect" + query("code", code));

        assertThat(redirect.status()).as("%s", redirect).isEqualTo(302);
        assertThat(redirect.reason()).isEqualTo("Found");
        String location = redirect.header("Location");
        String providerBase = "http://localhost:" + providerPort;
        assertThat(location).startsWith(providerBase);
        String target = location.substring(providerBase.length());
        String tokenPrefix = "/token?grant_type=authorization_code&&client_id=" + clientId
                + "&client_secret=" + clientSecret + "&code=";
        assertThat(target).startsWith(tokenPrefix);
        assertThat(target).isEqualTo(tokenPrefix + code + "&redirect_uri=" + redirectUri());

        assertIssuedToken(provider.get(target), READ_RESOURCE);
    }

    @Test
    void reusedCodeIsInvalidGrant() throws IOException {
        String code = authorizeCode(READ_RESOURCE, redirectUri());
        String target = "/token" + query(
                "grant_type", "authorization_code",
                "client_id", clientId,
                "client_secret", clientSecret,
                "code", code,
                "redirect_uri", redirectUri());

        assertIssuedToken(provider.get(target), READ_RESOURCE);

        assertOAuthError(provider.get(target), 400, "invalid_grant");
    }

    @Test
    void wrongClientSecretIsInvalidClient() throws IOException {
        String code = authorizeCode(READ_RESOURCE, redirectUri());

        HttpTestClient.Response resp = provider.post("/token", FORM, form(
                "grant_type", "authorization_code",
                "client_id", clientId,
                "client_secret", "wrong",
                "code", code,
                "redirect_uri", redirectUri()));

        assertOAuthError(resp, 401, "invalid_client");
        // Only a client that authenticated with HTTP Basic is challenged; parameter credentials get no challenge.
        assertThat(resp.header("WWW-Authenticate")).isNull();
    }

    @Test
    void passwordGrantIsUnsupported() throws IOException {
        HttpTestClient.Response resp = provider.post("/token", FORM, form(
                "grant_type", "password",
                "username", ownerUsername,
                "password", ownerPassword,
                "client_id", clientId,
                "client_secret", clientSecret));

        assertOAuthError(resp, 400, "unsupported_grant_type");
    }

    @Test
    void missingCodeIsInvalidRequest() throws IOException {
        HttpTestClient.Response resp = provider.post("/token", FORM, form(
                "grant_type", "authorization_code",
                "client_id", clientId,
                "client_secret", clientSecret,
                "redirect_uri", redirectUri()));

        assertOAuthError(resp, 400, "invalid_request");
    }

    @Test
    void mismatchedRedirectUriIsInvalidGrant() throws IOException {
        String code = authorizeCode(READ_RESOURCE, redirectUri());

        HttpTestClient.Response resp = provider.post("/token", FORM, form(
                "grant_type", "authorization_code",
                "client_id", clientId,
                "client_secret", clientSecret,
                "code", code,
                "redirect_uri", "http://localhost:" + listenerPort + "/other"));

        assertOAuthError(resp, 400, "invalid_grant");
    }

    @Test
    void deleteTokenIsMethodNotAllowed() {
        HttpTestClient.Response resp = provider.delete("/token");

        assertThat(resp.status()).as("%s", resp).isEqualTo(405);
        assertThat(resp.reason()).isEqualTo("Method Not Allowed");
        assertMediaType(resp, MediaType.TEXT_PLAIN);
        assertThat(resp.bodyAsString()).isEqualTo(METHOD_NOT_ALLOWED_BODY);
    }

    /** The redirect URI the codes are issued for: the resource listener's {@code /redirect}, as in the original. */
    private static String redirectUri() {
        return "http://localhost:" + listenerPort + "/redirect";
    }

    /** {@code application/x-www-form-urlencoded} pairs, names and values encoded as UTF-8. */
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

    /** A query string, {@code ?} included, of the encoded pairs of {@link #form}. */
    private static String query(String... keysAndValues) {
        return "?" + form(keysAndValues);
    }

    /**
     * Logs the resource owner in at {@code POST /authorize} on the provider listener, as the login form submits its
     * hidden fields and credentials, and returns the issued code.
     */
    private String authorizeCode(String scope, String redirectUri) {
        HttpTestClient.Response resp = provider.post("/authorize", FORM, form(
                "response_type", "code",
                "client_id", clientId,
                "redirect_uri", redirectUri,
                "scope", scope,
                "state", "xyz",
                "username", ownerUsername,
                "password", ownerPassword));

        assertThat(resp.status()).as("%s", resp).isEqualTo(302);
        String location = resp.header("Location");
        String codePrefix = redirectUri + "?code=";
        assertThat(location).startsWith(codePrefix);
        int end = location.indexOf('&', codePrefix.length());
        String rawCode = end < 0 ? location.substring(codePrefix.length())
                : location.substring(codePrefix.length(), end);
        return URLDecoder.decode(rawCode, StandardCharsets.UTF_8);
    }

    /**
     * Asserts the token response and returns its access token: 200 {@code OK}, {@code application/json}, no
     * caching, and one flat object of exactly the four token fields. Flatness is what the original test relied on:
     * it parsed the page from its first opening brace to its first closing brace, which must therefore span the
     * whole object.
     */
    private static String assertIssuedToken(HttpTestClient.Response resp, String scope) throws IOException {
        assertThat(resp.status()).as("%s", resp).isEqualTo(200);
        assertThat(resp.reason()).isEqualTo("OK");
        assertMediaType(resp, MediaType.APPLICATION_JSON);
        assertThat(resp.header("Cache-Control")).isEqualTo("no-store");
        assertThat(resp.header("Pragma")).isEqualTo("no-cache");

        JsonNode body = JSON.readTree(resp.body());
        assertThat(body.isObject()).as("%s", resp).isTrue();
        List<String> fieldNames = new ArrayList<>();
        body.fieldNames().forEachRemaining(fieldNames::add);
        assertThat(fieldNames).containsExactlyInAnyOrder("access_token", "token_type", "expires_in", "scope");
        body.elements().forEachRemaining(value -> assertThat(value.isValueNode()).as("%s", resp).isTrue());

        String text = resp.bodyAsString();
        assertThat(JSON.readTree(text.substring(text.indexOf('{'), text.indexOf('}') + 1))).isEqualTo(body);

        JsonNode accessToken = body.path("access_token");
        assertThat(accessToken.isTextual()).isTrue();
        assertThat(accessToken.asText()).isNotBlank();
        assertThat(body.path("token_type").asText()).isEqualTo("bearer");
        assertThat(body.path("expires_in").isNumber()).isTrue();
        assertThat(body.path("expires_in").asLong()).isEqualTo(86400L);
        assertThat(body.path("scope").asText()).isEqualTo(scope);
        return accessToken.asText();
    }

    /** Asserts an OAuth error answer: the status, {@code application/json} and the body's {@code error} code. */
    private static void assertOAuthError(HttpTestClient.Response resp, int status, String error) throws IOException {
        assertThat(resp.status()).as("%s", resp).isEqualTo(status);
        assertMediaType(resp, MediaType.APPLICATION_JSON);
        assertThat(JSON.readTree(resp.body()).path("error").asText()).isEqualTo(error);
    }

    /** Asserts the {@code Content-Type} type and subtype, ignoring parameters such as the charset Spring adds. */
    private static void assertMediaType(HttpTestClient.Response resp, MediaType expected) {
        String contentType = resp.header("Content-Type");
        assertThat(contentType).as("%s", resp).isNotNull();
        assertThat(MediaType.parseMediaType(contentType).equalsTypeAndSubtype(expected))
                .as("Content-Type %s", contentType)
                .isTrue();
    }
}
