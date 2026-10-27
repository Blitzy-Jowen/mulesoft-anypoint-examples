/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_oauth_provider.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.http_oauth_provider.support.HttpTestClient;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;

/**
 * The whole OAuth flow under {@code logging.level.root=TRACE}: the client secret, the authorization codes, the access
 * tokens and the resource owner's password reach the parties they reach in the original, and none of them reaches the
 * log, because {@code application.yml} pins the loggers that would write them at INFO, {@code DispatcherServlet} at
 * WARN. The original never logged them.
 *
 * <p>The application runs on its two Undertow listeners, each on a free loopback port ({@code DEFINED_PORT}, as the
 * controller tests run it: the SC-10 provider listener needs a concrete port, and the {@code /redirect} location and
 * the payroll {@code uri} embed both ports). Every exchange goes over the socket through ST-1. The client secret and
 * the owner password are sentinels overriding the demo values of profile {@code test}; the codes and tokens are the
 * ones the provider issues, collected as each test obtains them.
 *
 * <p>Besides root TRACE, the {@code web} group is raised to TRACE, as {@code --debug} and {@code --trace} raise it, and
 * {@code spring.mvc.log-request-details} is on, under which {@code DispatcherServlet} would also write parameters and
 * headers. Unpinned loggers still write their TRACE and DEBUG lines, which the positive controls assert, so a clean
 * log means the pins held rather than that nothing was logged.
 *
 * <p>The logger levels found before the context starts are restored after the class, and the context is discarded
 * with it, so no later test runs at TRACE.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "logging.level.root=TRACE",
        "logging.level.web=TRACE",
        "spring.mvc.log-request-details=true"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class SensitiveLogLevelsTest {

    private static final String CLIENT_SECRET = "SENTINEL_CLIENT_SECRET";

    private static final String OWNER_PASSWORD = "SENTINEL_OWNER_PASSWORD";

    /** The prefix every fixed sentinel of this class carries; none may reach the log. */
    private static final String SENTINEL_PREFIX = "SENTINEL_";

    private static final String WRONG_CLIENT_SECRET = "SENTINEL_WRONG_CLIENT_SECRET";

    private static final String WRONG_OWNER_PASSWORD = "SENTINEL_WRONG_OWNER_PASSWORD";

    private static final String READ_RESOURCE = "READ_RESOURCE";

    private static final String FORM = "application/x-www-form-urlencoded";

    /** How long the output must stay free of secrets after an exchange, for lines written after the reply. */
    private static final Duration SETTLE = Duration.ofMillis(300);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The logger levels set before this class's context started, by logger name. */
    private static final Map<String, Level> LEVELS_BEFORE = new HashMap<>();

    private static int listenerPort;

    private static int providerPort;

    /** The codes, tokens and encoded credentials this test obtained or sent; none may reach the log. */
    private final List<String> secrets = new ArrayList<>();

    @Value("${oauth.provider.client-id}")
    private String clientId;

    @Value("${oauth.provider.owner.username}")
    private String ownerUsername;

    private HttpTestClient provider;

    private HttpTestClient listener;

    /** How the client presents its credentials at {@code /token}; the provider accepts each. */
    enum ClientCredentials {

        /** {@code GET} with the credentials in the query, as the browser follows the {@code /redirect} 302. */
        QUERY {
            @Override
            HttpTestClient.Response exchange(HttpTestClient provider, String clientId, String secret, String code,
                                             List<String> secrets) {
                return provider.get("/token" + query(
                        "grant_type", "authorization_code",
                        "client_id", clientId,
                        "client_secret", secret,
                        "code", code,
                        "redirect_uri", redirectUri()));
            }
        },

        /** {@code POST} with the credentials in the form body. */
        FORM_BODY {
            @Override
            HttpTestClient.Response exchange(HttpTestClient provider, String clientId, String secret, String code,
                                             List<String> secrets) {
                return provider.post("/token", FORM, form(
                        "grant_type", "authorization_code",
                        "client_id", clientId,
                        "client_secret", secret,
                        "code", code,
                        "redirect_uri", redirectUri()));
            }
        },

        /** {@code POST} with the credentials in an HTTP Basic {@code Authorization} header. */
        BASIC_HEADER {
            @Override
            HttpTestClient.Response exchange(HttpTestClient provider, String clientId, String secret, String code,
                                             List<String> secrets) {
                String credentials = Base64.getEncoder()
                        .encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
                secrets.add(credentials);
                return provider.post("/token", FORM, form(
                                "grant_type", "authorization_code",
                                "code", code,
                                "redirect_uri", redirectUri()),
                        Map.of("Authorization", "Basic " + credentials));
            }
        };

        /**
         * Exchanges {@code code} at {@code /token}, adding to {@code secrets} any encoded form of the credentials it
         * sends.
         */
        abstract HttpTestClient.Response exchange(HttpTestClient provider, String clientId, String secret, String code,
                                                  List<String> secrets);
    }

    /**
     * Reserves two distinct free loopback ports, both sockets open at once, for the resource and provider listeners;
     * {@code application.yml} derives {@code server.port} from {@code http.listener.port}. Supplies the sentinel
     * client secret and owner password here too: the test framework's own TRACE lines print the inlined
     * {@code @SpringBootTest} properties, but never the values of a dynamic property source.
     */
    @DynamicPropertySource
    static void portsAndSecrets(DynamicPropertyRegistry registry) {
        try (ServerSocket listenerSocket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
             ServerSocket providerSocket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            listenerPort = listenerSocket.getLocalPort();
            providerPort = providerSocket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot reserve the listener and provider ports", e);
        }
        registry.add("http.listener.port", () -> listenerPort);
        registry.add("http.provider.port", () -> providerPort);
        registry.add("oauth.provider.client-secret", () -> CLIENT_SECRET);
        registry.add("oauth.provider.owner.password", () -> OWNER_PASSWORD);
    }

    @BeforeAll
    static void recordLevels() {
        LEVELS_BEFORE.clear();
        for (Logger logger : loggerContext().getLoggerList()) {
            if (logger.getLevel() != null) {
                LEVELS_BEFORE.put(logger.getName(), logger.getLevel());
            }
        }
    }

    @AfterAll
    static void restoreLevels() {
        for (Logger logger : loggerContext().getLoggerList()) {
            Level before = LEVELS_BEFORE.get(logger.getName());
            // The root logger always has a level and cannot be given none.
            if (before != null || !org.slf4j.Logger.ROOT_LOGGER_NAME.equals(logger.getName())) {
                logger.setLevel(before);
            }
        }
    }

    /** The ports are known only once the context has been created, after the test instance itself. */
    @BeforeEach
    void clients() {
        provider = HttpTestClient.localhost(providerPort);
        listener = HttpTestClient.localhost(listenerPort);
    }

    /**
     * Root is at TRACE, yet each logger {@code application.yml} pins, and the classes below a pinned package, write
     * at the pinned level and above only: INFO for most, WARN for {@code DispatcherServlet}, whose INFO stays off so
     * that a root WARN also silences its servlet start-up lines.
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "org.springframework.web.servlet.DispatcherServlet, WARN",
            "org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor, INFO",
            "org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver, INFO",
            "org.springframework.web.method.HandlerMethod, INFO",
            "io.undertow.request.io, INFO"})
    void pinnedLoggerStaysAtItsLevelUnderRootTrace(String name, String pinned) {
        assertThat(loggerContext().getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getLevel()).isEqualTo(Level.TRACE);

        Logger logger = loggerContext().getLogger(name);
        Level pinnedLevel = Level.toLevel(pinned, null);

        assertThat(logger.getEffectiveLevel()).isEqualTo(pinnedLevel);
        for (Level level : List.of(Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR)) {
            assertThat(logger.isEnabledFor(level)).as("%s %s", name, level)
                    .isEqualTo(level.isGreaterOrEqual(pinnedLevel));
        }
    }

    /**
     * The original browser flow: the login page, the owner's login, the {@code /redirect} hop whose 302 carries the
     * client secret to {@code /token}, the token page, and the payroll resource with the bearer token; a token offered
     * in the query instead of the header is rejected.
     */
    @Test
    void browserFlowServesPayrollWithoutLoggingAnySecret(CapturedOutput output) throws IOException {
        HttpTestClient.Response page = provider.get("/authorize" + query(
                "response_type", "code",
                "client_id", clientId,
                "redirect_uri", redirectUri(),
                "scope", READ_RESOURCE,
                "state", "xyz"));
        assertThat(page.status()).as("%s", page).isEqualTo(200);
        assertMediaType(page, MediaType.TEXT_HTML);
        assertThat(page.bodyAsString()).contains("name=\"username\"", "name=\"password\"");

        String code = authorizeCode(OWNER_PASSWORD);

        HttpTestClient.Response redirect = listener.get("/redirect" + query("code", code));
        assertThat(redirect.status()).as("%s", redirect).isEqualTo(302);
        String providerBase = "http://localhost:" + providerPort;
        String target = "/token?grant_type=authorization_code&&client_id=" + clientId
                + "&client_secret=" + CLIENT_SECRET + "&code=" + code + "&redirect_uri=" + redirectUri();
        assertThat(redirect.header("Location")).isEqualTo(providerBase + target);

        String token = assertIssuedToken(provider.get(target));

        assertPayroll(listener.get("/resources", Map.of("Authorization", "Bearer " + token)));

        HttpTestClient.Response queryToken = listener.get("/resources" + query("access_token", token));
        assertThat(queryToken.status()).as("%s", queryToken).isEqualTo(401);
        assertThat(queryToken.header("WWW-Authenticate")).startsWithIgnoringCase("Bearer ");

        assertLogHoldsNoSecret(output);
        assertThat(output.getAll())
                .as("unpinned loggers still write: OrderedRequestContextFilter at TRACE, PortRoutingFilter at DEBUG")
                .contains("Bound request context to thread", "serves the request through dispatcherServlet");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ClientCredentials.class)
    void tokenExchangeIssuesUsableTokenWithoutLoggingIt(ClientCredentials credentials, CapturedOutput output)
            throws IOException {
        String code = authorizeCode(OWNER_PASSWORD);

        String token = assertIssuedToken(credentials.exchange(provider, clientId, CLIENT_SECRET, code, secrets));

        assertPayroll(listener.get("/resources", Map.of("Authorization", "Bearer " + token)));
        assertLogHoldsNoSecret(output);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ClientCredentials.class)
    void wrongClientSecretIsInvalidClientWithoutLoggingIt(ClientCredentials credentials, CapturedOutput output)
            throws IOException {
        String code = authorizeCode(OWNER_PASSWORD);

        HttpTestClient.Response response =
                credentials.exchange(provider, clientId, WRONG_CLIENT_SECRET, code, secrets);

        assertOAuthError(response, 401, "invalid_client");
        assertLogHoldsNoSecret(output);
    }

    @Test
    void reusedCodeIsInvalidGrantWithoutLoggingIt(CapturedOutput output) throws IOException {
        String code = authorizeCode(OWNER_PASSWORD);
        assertIssuedToken(ClientCredentials.FORM_BODY.exchange(provider, clientId, CLIENT_SECRET, code, secrets));

        HttpTestClient.Response reused =
                ClientCredentials.FORM_BODY.exchange(provider, clientId, CLIENT_SECRET, code, secrets);

        assertOAuthError(reused, 400, "invalid_grant");
        assertLogHoldsNoSecret(output);
    }

    @Test
    void wrongOwnerPasswordRendersLoginAgainWithoutLoggingIt(CapturedOutput output) {
        HttpTestClient.Response response = provider.post("/authorize", FORM, loginForm(WRONG_OWNER_PASSWORD));

        assertThat(response.status()).as("%s", response).isEqualTo(401);
        assertMediaType(response, MediaType.TEXT_HTML);
        assertThat(response.header("Location")).isNull();
        assertLogHoldsNoSecret(output);
    }

    /** Tokens the resource listener rejects: an unknown bearer token, and tokens offered outside the header. */
    static Stream<Arguments> rejectedResourceRequests() {
        return Stream.of(
                Arguments.of("unknown bearer token", "GET", "/resources",
                        Map.of("Authorization", "Bearer SENTINEL_UNKNOWN_BEARER"), null),
                Arguments.of("token in the query", "GET", "/resources?access_token=SENTINEL_QUERY_TOKEN",
                        Map.of(), null),
                Arguments.of("token in a form body", "POST", "/resources", Map.of("Content-Type", FORM),
                        "access_token=SENTINEL_FORM_TOKEN"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedResourceRequests")
    void rejectedTokenIs401WithoutLoggingIt(String description, String method, String path,
                                            Map<String, String> headers, String body, CapturedOutput output) {
        HttpTestClient.Response response = listener.send(method, path, headers,
                body == null ? null : body.getBytes(StandardCharsets.UTF_8));

        assertThat(response.status()).as("%s", response).isEqualTo(401);
        assertThat(response.header("WWW-Authenticate")).startsWithIgnoringCase("Bearer ");
        assertMediaType(response, MediaType.APPLICATION_JSON);
        assertLogHoldsNoSecret(output);
    }

    /**
     * A query value that is not valid percent-encoding fails the listener's request parsing, which answers 400 before
     * any servlet runs; the parse failure, whose exception quotes the value, is not logged.
     */
    @Test
    void undecodableQueryValueIsBadRequestWithoutLoggingIt(CapturedOutput output) {
        HttpTestClient.Response response = provider.get("/token?grant_type=authorization_code&client_id=" + clientId
                + "&client_secret=SENTINEL_UNDECODABLE%zz&code=SENTINEL_UNDECODABLE_CODE");

        assertThat(response.status()).as("%s", response).isEqualTo(400);
        assertLogHoldsNoSecret(output);
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

    /** The login form as {@code templates/login.html} submits it: the hidden request fields and the credentials. */
    private String loginForm(String password) {
        return form(
                "response_type", "code",
                "client_id", clientId,
                "redirect_uri", redirectUri(),
                "scope", READ_RESOURCE,
                "state", "xyz",
                "username", ownerUsername,
                "password", password);
    }

    /** Logs the owner in at {@code POST /authorize} and returns the issued code, recorded as a secret. */
    private String authorizeCode(String password) {
        HttpTestClient.Response response = provider.post("/authorize", FORM, loginForm(password));

        assertThat(response.status()).as("%s", response).isEqualTo(302);
        String location = response.header("Location");
        String codePrefix = redirectUri() + "?code=";
        assertThat(location).startsWith(codePrefix);
        int end = location.indexOf('&', codePrefix.length());
        String code = URLDecoder.decode(end < 0 ? location.substring(codePrefix.length())
                : location.substring(codePrefix.length(), end), StandardCharsets.UTF_8);
        assertThat(code).isNotBlank();
        secrets.add(code);
        return code;
    }

    /** Asserts the token page, 200 {@code application/json} with a bearer token, and returns the token, recorded. */
    private String assertIssuedToken(HttpTestClient.Response response) throws IOException {
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        assertMediaType(response, MediaType.APPLICATION_JSON);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.path("token_type").asText()).isEqualTo("bearer");
        assertThat(body.path("scope").asText()).isEqualTo(READ_RESOURCE);
        String token = body.path("access_token").asText();
        assertThat(token).isNotBlank();
        secrets.add(token);
        return token;
    }

    /** Asserts the payroll resource of {@code protectedAuthcodeFlow}, byte for byte. */
    private static void assertPayroll(HttpTestClient.Response response) {
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        assertMediaType(response, MediaType.APPLICATION_JSON);
        assertThat(response.bodyAsString()).isEqualTo(
                "{\"name\":\"payroll\",\"uri\":\"http://localhost:" + listenerPort + "/resources/payroll\"}");
    }

    /** Asserts an OAuth error answer: the status, {@code application/json} and the body's {@code error} code. */
    private static void assertOAuthError(HttpTestClient.Response response, int status, String error)
            throws IOException {
        assertThat(response.status()).as("%s", response).isEqualTo(status);
        assertMediaType(response, MediaType.APPLICATION_JSON);
        assertThat(JSON.readTree(response.body()).path("error").asText()).isEqualTo(error);
    }

    /** Asserts the {@code Content-Type} type and subtype, ignoring parameters such as the charset Spring adds. */
    private static void assertMediaType(HttpTestClient.Response response, MediaType expected) {
        String contentType = response.header("Content-Type");
        assertThat(contentType).as("%s", response).isNotNull();
        assertThat(MediaType.parseMediaType(contentType).equalsTypeAndSubtype(expected))
                .as("Content-Type %s", contentType)
                .isTrue();
    }

    /**
     * Asserts that the output captured since the class started holds no sentinel and none of this test's codes,
     * tokens and encoded credentials, and still holds none after the exchange has settled. A failure names only the
     * offending lines.
     */
    private void assertLogHoldsNoSecret(CapturedOutput output) {
        List<String> forbidden = new ArrayList<>(secrets);
        forbidden.add(SENTINEL_PREFIX);
        await().during(SETTLE).atMost(SETTLE.multipliedBy(4)).untilAsserted(() -> assertThat(output.getAll().lines()
                .filter(line -> forbidden.stream().anyMatch(line::contains)))
                .as("captured lines holding a secret")
                .isEmpty());
    }

    private static LoggerContext loggerContext() {
        return (LoggerContext) LoggerFactory.getILoggerFactory();
    }
}
