/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_oauth_provider.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.filter.FormContentFilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.mulesoft.examples.http_oauth_provider.support.HttpTestClient;
import com.mulesoft.examples.http_oauth_provider.support.PortInUseRetryingContextLoader;

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
 * <p>The two ports are drawn for every attempt to load the context, and an attempt that loses one to another process
 * before Undertow binds it is repeated on newly drawn ports ({@link PortInUseRetryingContextLoader}); the tests
 * therefore read both ports, and every URI built from them, from the loaded context.
 *
 * <p>The client and resource-owner credentials are the original demo values of the {@code test} profile
 * ({@code src/test/resources/application-test.yml}), injected rather than repeated here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ContextConfiguration(loader = PortInUseRetryingContextLoader.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TokenControllerTest {

    private static final String FORM = "application/x-www-form-urlencoded";

    private static final String READ_RESOURCE = "READ_RESOURCE";

    private static final String METHOD_NOT_ALLOWED_BODY = "Method not allowed for endpoint: /token";

    /** A form body large enough that reading it whole before dispatch keeps the response back measurably. */
    private static final int LARGE_FORM_BYTES = 300_000;

    /** Bytes of a large form body written before its response is read; the rest is written only afterwards. */
    private static final int LARGE_FORM_PREFIX_BYTES = 1024;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The resource listener's port in the loaded context, the main {@code server.port}. */
    @LocalServerPort
    private int listenerPort;

    /** The SC-10 provider listener's port in the loaded context. */
    @Value("${http.provider.port}")
    private int providerPort;

    @Value("${oauth.provider.client-id}")
    private String clientId;

    @Value("${oauth.provider.client-secret}")
    private String clientSecret;

    @Value("${oauth.provider.owner.username}")
    private String ownerUsername;

    @Value("${oauth.provider.owner.password}")
    private String ownerPassword;

    /** The running application's context, inspected for the servlet filters it registers. */
    @Autowired
    private ApplicationContext applicationContext;

    private HttpTestClient provider;

    private HttpTestClient listener;

    /**
     * Draws two distinct free loopback ports, one per listener, both sockets open at once, and hands them to the
     * application, which binds them once those sockets are closed. Spring calls this method again for every attempt
     * to load the context, so each attempt runs on ports drawn for it.
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
            throw new UncheckedIOException("Cannot draw free loopback ports for the two listeners", e);
        }
        registry.add("http.listener.port", () -> drawnListenerPort);
        registry.add("http.provider.port", () -> drawnProviderPort);
    }

    /** The clients connect to the ports of the loaded context, injected into the test instance. */
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

    /**
     * A method outside GET and POST gets the listener's 405 without an {@code Allow} header; {@code OPTIONS} is
     * answered by the same 405 rather than by Spring MVC's own 200 with {@code Allow}.
     */
    @ParameterizedTest(name = "{0} /token")
    @ValueSource(strings = {"DELETE", "OPTIONS"})
    void unsupportedMethodOnTokenIsMethodNotAllowed(String method) {
        HttpTestClient.Response resp = provider.send(method, "/token", null, null);

        assertThat(resp.status()).as("%s", resp).isEqualTo(405);
        assertThat(resp.reason()).isEqualTo("Method Not Allowed");
        assertMediaType(resp, MediaType.TEXT_PLAIN);
        assertThat(resp.bodyAsString()).isEqualTo(METHOD_NOT_ALLOWED_BODY);
        assertThat(resp.header("Allow")).as("%s", resp).isNull();
    }

    /**
     * The provider listener's {@code /token} and {@code /authorize} accept GET and POST only and check the method
     * before they read the body, so a large form body on a method neither accepts is never read: a form declaring
     * {@value #LARGE_FORM_BYTES} bytes, of which only the first {@value #LARGE_FORM_PREFIX_BYTES} are written, gets
     * SC-1's whole 405 before the rest is sent, although {@code RequestBodyDrainFilter} reads the unread rest once the
     * response is complete. Boot's form-content filter, which {@code spring.mvc.formcontent.filter.enabled: false}
     * switches off, would read the whole body into memory before dispatch, so no response would arrive until the last
     * byte had been sent.
     *
     * @param path   a provider endpoint that accepts GET and POST only
     * @param method a method whose form body Boot's form-content filter would read
     * @throws IOException when the connection fails
     */
    @ParameterizedTest(name = "{1} {0}")
    @CsvSource({
        "/token,     PUT",
        "/token,     PATCH",
        "/token,     DELETE",
        "/authorize, PUT",
        "/authorize, PATCH",
        "/authorize, DELETE",
    })
    void largeFormOnMethodOutsideGetAndPostGets405BeforeItsBodyIsSent(String path, String method) throws IOException {
        byte[] form = ("state=" + "x".repeat(LARGE_FORM_BYTES - "state=".length())).getBytes(StandardCharsets.US_ASCII);
        try (Socket socket = new Socket("localhost", providerPort)) {
            socket.setSoTimeout(HttpTestClient.TIMEOUT_MILLIS);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            out.write((method + " " + path + " HTTP/1.1\r\nHost: localhost:" + providerPort + "\r\nContent-Type: "
                    + FORM + "\r\nContent-Length: " + form.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(form, 0, LARGE_FORM_PREFIX_BYTES);
            out.flush();

            RawResponse refused;
            try {
                refused = readResponse(in);
            } catch (SocketTimeoutException timeout) {
                refused = fail(String.format("No response to %s %s within %d ms while %d of its %d form bytes were "
                        + "sent: the body was buffered before dispatch", method, path, HttpTestClient.TIMEOUT_MILLIS,
                        LARGE_FORM_PREFIX_BYTES, form.length), timeout);
            }
            writeRemainderIgnoringFailure(out, form, LARGE_FORM_PREFIX_BYTES);

            assertThat(refused.statusLine()).as(refused.toString()).isEqualTo("HTTP/1.1 405 Method Not Allowed");
            assertThat(refused.header("Content-Type")).as(refused.toString()).isEqualTo(MediaType.TEXT_PLAIN_VALUE);
            assertThat(refused.body()).as(refused.toString()).isEqualTo("Method not allowed for endpoint: " + path);
        }
    }

    /**
     * The application registers no {@link FormContentFilter}, the filter that reads a {@code PUT}, {@code PATCH} or
     * {@code DELETE} form body before dispatch, because {@code spring.mvc.formcontent.filter.enabled} is
     * {@code false}.
     */
    @Test
    void contextHoldsNoFormContentFilter() {
        assertThat(applicationContext.getBeanNamesForType(FormContentFilter.class)).as("FormContentFilter beans")
                .isEmpty();
    }

    /** The redirect URI the codes are issued for: the resource listener's {@code /redirect}, as in the original. */
    private String redirectUri() {
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

    /**
     * Writes the bytes of {@code body} from {@code offset} to its end once the response has been read, so the server
     * can finish the exchange; a failed write is ignored, because the server may close a connection whose body it
     * refused unread.
     */
    private static void writeRemainderIgnoringFailure(OutputStream out, byte[] body, int offset) {
        try {
            out.write(body, offset, body.length - offset);
            out.flush();
        } catch (IOException closedByServer) {
            // The response is already read; the server need not take the rest of a body it never reads.
        }
    }

    /**
     * Reads one response off the connection: its status line, its headers and the {@code Content-Length} bytes of its
     * body.
     */
    private static RawResponse readResponse(InputStream in) throws IOException {
        String statusLine = readLine(in);
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
            int colon = line.indexOf(':');
            assertThat(colon).as("header line %s", line).isPositive();
            headers.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        String contentLength = headers.get("Content-Length");
        assertThat(contentLength).as("Content-Length of %s %s", statusLine, headers).isNotNull();
        int length = Integer.parseInt(contentLength);
        byte[] body = in.readNBytes(length);
        assertThat(body).as("body of %s %s", statusLine, headers).hasSize(length);
        return new RawResponse(statusLine, headers, new String(body, StandardCharsets.UTF_8));
    }

    /** One line of a response head without its CRLF, one character per byte; fails when the connection ends first. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int c = in.read(); c != '\n'; c = in.read()) {
            if (c < 0) {
                throw new EOFException("connection closed in a response head after \"" + line + "\"");
            }
            if (c != '\r') {
                line.append((char) c);
            }
        }
        return line.toString();
    }

    /**
     * A response read off a raw connection.
     *
     * @param statusLine the status line, such as {@code HTTP/1.1 405 Method Not Allowed}
     * @param headers    the headers, names compared case-insensitively
     * @param body       the body as UTF-8 text
     */
    private record RawResponse(String statusLine, Map<String, String> headers, String body) {

        String header(String name) {
            return headers.get(name);
        }
    }
}
