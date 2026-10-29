/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_ms_dynamics.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import io.netty.channel.ChannelOption;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClientConfig;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.mulesoft.examples.import_contacts_into_ms_dynamics.client.DynamicsFailureClassifier;
import com.mulesoft.examples.import_contacts_into_ms_dynamics.client.UpstreamCallExecutor;
import com.mulesoft.examples.import_contacts_into_ms_dynamics.client.UpstreamFailureKind;
import com.mulesoft.examples.import_contacts_into_ms_dynamics.client.UpstreamFailureKind.Category;
import com.mulesoft.examples.import_contacts_into_ms_dynamics.client.UpstreamSystem;
import com.mulesoft.examples.import_contacts_into_ms_dynamics.config.DynamicsOAuthConfig.DynamicsProperties;
import com.mulesoft.examples.import_contacts_into_ms_dynamics.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.import_contacts_into_ms_dynamics.exception.UpstreamRateLimitException;

/**
 * Tests {@link DynamicsOAuthConfig}'s token and Dataverse transport, through the configuration's real beans wired by
 * hand as the application context wires them, behind SC-9's {@link UpstreamCallExecutor} and the
 * {@link DynamicsFailureClassifier}, against a WireMock Entra token endpoint and Dataverse Web API.
 *
 * <p>Covered: an Entra 429 token response is a rate limit carrying its {@code Retry-After}, never retried; an Entra
 * 5xx, or an unreadable 200, 400, 403 or 404 token response, is rethrown unchanged; an Entra 401, whatever its body,
 * and an Entra 400 OAuth 2.0 rejection such as {@code unauthorized_client} are authentication failures,
 * re-authenticated and retried once; a granted token reaches Dataverse once; the classifier
 * rules and upstream messages for Entra's OAuth 2.0 errors; one transport attempt per call, the reset connection
 * included; both timeouts, their 10 s defaults, the rejection of anything shorter than 1 ms and the cap of very long
 * ones; and the start-up with blank credentials.
 *
 * <p>Every credential is a fake value and every server listens on a free loopback port.
 */
class DynamicsOAuthConfigTest {

    private static final String TOKEN_PATH = "/test-tenant/oauth2/v2.0/token";

    private static final String CONTACTS_PATH = "/api/data/v9.2/contacts";

    private static final String CONTACT_JSON = "{\"firstname\":\"John\",\"lastname\":\"Doe\"}";

    private static final String TOKEN_GRANTED_JSON =
            "{\"token_type\":\"Bearer\",\"expires_in\":3599,\"access_token\":\"test-token\"}";

    private static final String ENTRA_THROTTLED_JSON =
            "{\"error\":\"temporarily_unavailable\",\"error_description\":\"AADSTS50196: test throttling\"}";

    private static final String ENTRA_SERVER_ERROR_JSON =
            "{\"error\":\"server_error\",\"error_description\":\"AADSTS90033: test server error\"}";

    private static final String ENTRA_INVALID_CLIENT_JSON =
            "{\"error\":\"invalid_client\",\"error_description\":\"AADSTS7000215: test invalid client secret\"}";

    private static final String ENTRA_UNAUTHORIZED_CLIENT_JSON = "{\"error\":\"unauthorized_client\","
            + "\"error_description\":\"AADSTS700016: test application not found in the directory\"}";

    /** Bounds every blocking wait, well above the 10 s transport timeouts, so a hung call fails the test. */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);

    /** How long the reset-connection call holds back its request body, and with it the request headers. */
    private static final Duration BODY_DELAY = Duration.ofMillis(300);

    private static final DynamicsFailureClassifier CLASSIFIER = new DynamicsFailureClassifier();

    private static WireMockServer wireMock;

    private static Level rootLogLevel;

    @BeforeAll
    static void startWireMock() {
        // Without a logback-test.xml, logback logs at DEBUG; Netty and WireMock would flood the build output.
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        rootLogLevel = root.getLevel();
        root.setLevel(Level.INFO);
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(rootLogLevel);
    }

    @BeforeEach
    void resetWireMock() {
        wireMock.resetAll();
    }

    @ParameterizedTest(name = "Retry-After {0}")
    @NullSource
    @ValueSource(strings = "7")
    void throttledTokenRequestIsARateLimitWithItsRetryAfterAndIsNeverRetried(String retryAfter) {
        ResponseDefinitionBuilder throttled = entraError(429, ENTRA_THROTTLED_JSON);
        stubToken(retryAfter == null ? throttled : throttled.withHeader(HttpHeaders.RETRY_AFTER, retryAfter));
        Connection connection = new Connection(properties(wireMock.baseUrl(), null, null));

        assertThatThrownBy(connection::createContact)
                .isInstanceOfSatisfying(UpstreamRateLimitException.class, rateLimit -> {
                    assertThat(rateLimit.getSystem()).isEqualTo(UpstreamSystem.MICROSOFT_DYNAMICS_365);
                    assertThat(rateLimit.getRetryAfter()).isEqualTo(retryAfter);
                    assertThat(rateLimit.getMessage()).isEqualTo("AADSTS50196: test throttling");
                    assertThat(rateLimit.getCause())
                            .isInstanceOfSatisfying(WebClientResponseException.class, response -> {
                                assertThat(response.getStatusCode().value()).isEqualTo(429);
                                assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                                        .isEqualTo(retryAfter);
                            });
                });
        assertThat(tokenRequests()).isEqualTo(1);
        assertThat(dataverseRequests()).isZero();
        assertThat(connection.reauthentications).hasValue(0);
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {500, 503})
    void tokenServerErrorIsRethrownUnchangedWithItsStatus(int status) {
        stubToken(entraError(status, ENTRA_SERVER_ERROR_JSON));
        Connection connection = new Connection(properties(wireMock.baseUrl(), null, null));

        assertThatThrownBy(connection::createContact)
                .isInstanceOfSatisfying(WebClientResponseException.class, response -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(status);
                    assertThat(CLASSIFIER.classify(response)).isEqualTo(UpstreamFailureKind.OTHER);
                    assertThat(CLASSIFIER.upstreamMessage(response)).isEqualTo("AADSTS90033: test server error");
                });
        assertThat(tokenRequests()).isEqualTo(1);
        assertThat(dataverseRequests()).isZero();
        assertThat(connection.reauthentications).hasValue(0);
    }

    /**
     * Spring Security reads a 200 token response without an access token as an error response without a code. A 400,
     * 403 or 404 reaches Spring Security too, so one whose body it cannot read is a failed token exchange as well.
     */
    @ParameterizedTest(name = "HTTP {0}, {1}: {2}")
    @CsvSource(delimiter = '|', value = {
            "200 | application/json | not-json                                          | invalid_token_response",
            "200 | text/html        | <html><body>test proxy page</body></html>         | invalid_token_response",
            "200 | application/json | {\"token_type\":\"Bearer\",\"expires_in\":3599}  | server_error",
            "400 | text/html        | <html><body>test bad request page</body></html>   | invalid_token_response",
            "403 | text/html        | <html><body>test forbidden page</body></html>     | invalid_token_response",
            "404 | text/html        | <html><body>test not found page</body></html>     | invalid_token_response"})
    void unreadableTokenResponseIsRethrownUnchangedAndNotReauthenticated(int status, String contentType, String body,
            String expectedErrorCode) {
        stubToken(aResponse().withStatus(status).withHeader(HttpHeaders.CONTENT_TYPE, contentType).withBody(body));
        Connection connection = new Connection(properties(wireMock.baseUrl(), null, null));

        assertThatThrownBy(connection::createContact)
                .isInstanceOfSatisfying(OAuth2AuthorizationException.class, authorization -> {
                    assertThat(authorization.getError().getErrorCode()).isEqualTo(expectedErrorCode);
                    assertThat(CLASSIFIER.classify(authorization)).isEqualTo(UpstreamFailureKind.OTHER);
                });
        assertThat(tokenRequests()).isEqualTo(1);
        assertThat(dataverseRequests()).isZero();
        assertThat(connection.reauthentications).hasValue(0);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedTokenResponses")
    void rejectedClientIsAnAuthenticationFailureReauthenticatedAndRetriedOnce(String description,
            ResponseDefinitionBuilder rejected, String expectedMessage) {
        stubToken(rejected);
        Connection connection = new Connection(properties(wireMock.baseUrl(), null, null));

        assertThatThrownBy(connection::createContact)
                .isInstanceOfSatisfying(UpstreamAuthenticationException.class, authentication -> {
                    assertThat(authentication.getSystem()).isEqualTo(UpstreamSystem.MICROSOFT_DYNAMICS_365);
                    assertThat(authentication.getMessage()).isEqualTo(expectedMessage);
                    assertThat(authentication.getCause()).isInstanceOfSatisfying(WebClientResponseException.class,
                            response -> assertThat(response.getStatusCode().value()).isEqualTo(401));
                });
        assertThat(tokenRequests()).isEqualTo(2);
        assertThat(dataverseRequests()).isZero();
        assertThat(connection.reauthentications).hasValue(1);
    }

    /**
     * Entra's own 401 OAuth 2.0 error, whose description is the message, and the empty and non-JSON 401 bodies a
     * proxy in front of Entra can send, for which the response exception's own message is the message.
     */
    static Stream<Arguments> rejectedTokenResponses() {
        String statusMessage = "401 Unauthorized from POST " + wireMock.baseUrl() + TOKEN_PATH;
        return Stream.of(
                Arguments.of("Entra invalid_client", entraError(401, ENTRA_INVALID_CLIENT_JSON),
                        "AADSTS7000215: test invalid client secret"),
                Arguments.of("empty body", aResponse().withStatus(401), statusMessage),
                Arguments.of("HTML page", aResponse().withStatus(401)
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE)
                        .withBody("<html>Unauthorized</html>"), statusMessage));
    }

    @Test
    void entraOAuthErrorOfA400IsAnAuthenticationFailureReauthenticatedAndRetriedOnce() {
        stubToken(entraError(400, ENTRA_UNAUTHORIZED_CLIENT_JSON));
        Connection connection = new Connection(properties(wireMock.baseUrl(), null, null));

        assertThatThrownBy(connection::createContact)
                .isInstanceOfSatisfying(UpstreamAuthenticationException.class, authentication -> {
                    assertThat(authentication.getSystem()).isEqualTo(UpstreamSystem.MICROSOFT_DYNAMICS_365);
                    assertThat(authentication.getMessage())
                            .isEqualTo("AADSTS700016: test application not found in the directory");
                    assertThat(authentication.getCause()).isInstanceOfSatisfying(
                            OAuth2AuthorizationException.class,
                            cause -> assertThat(cause.getError().getErrorCode()).isEqualTo("unauthorized_client"));
                });
        assertThat(tokenRequests()).isEqualTo(2);
        assertThat(dataverseRequests()).isZero();
        assertThat(connection.reauthentications).hasValue(1);
    }

    @Test
    void grantedTokenIsSentWithTheODataHeadersToOneDataverseRequest() throws Exception {
        stubToken(aResponse().withStatus(200)
                .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .withBody(TOKEN_GRANTED_JSON));
        wireMock.stubFor(post(urlEqualTo(CONTACTS_PATH)).willReturn(aResponse().withStatus(204).withHeader(
                "OData-EntityId", wireMock.baseUrl() + CONTACTS_PATH + "(00000000-0000-0000-0000-000000000001)")));
        Connection connection = new Connection(properties(wireMock.baseUrl(), null, null));

        connection.createContact();

        wireMock.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH))
                .withRequestBody(containing("grant_type=client_credentials")));
        wireMock.verify(1, postRequestedFor(urlEqualTo(CONTACTS_PATH))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer test-token"))
                .withHeader("OData-MaxVersion", equalTo("4.0"))
                .withHeader("OData-Version", equalTo("4.0"))
                .withRequestBody(equalTo(CONTACT_JSON)));
        assertThat(tokenRequests()).isEqualTo(1);
        assertThat(connection.reauthentications).hasValue(0);
    }

    @Test
    void resetConnectionIsAttemptedOnceAndNeverReauthenticated() throws Exception {
        stubToken(aResponse().withStatus(200)
                .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .withBody(TOKEN_GRANTED_JSON));
        try (ResettingServer dataverse = new ResettingServer()) {
            Connection connection = new Connection(properties(dataverse.url(), null, null));

            // Reactor Netty's transport retry acts only on a reset that arrives before the request headers are
            // written. The headers leave with the first body chunk, so holding the body back lets the server's
            // reset arrive first: the window in which an enabled retry would open a second connection.
            assertThatThrownBy(() -> connection.createContact(Mono.delay(BODY_DELAY).thenReturn(CONTACT_JSON)))
                    .isInstanceOf(Exception.class);

            assertThat(dataverse.connections()).isEqualTo(1);
            assertThat(dataverse.failures()).isEmpty();
            assertThat(connection.reauthentications).hasValue(0);
            assertThat(tokenRequests()).isEqualTo(1);
        }
    }

    @Test
    void beansStartWithBlankCredentialsAndTheFirstCallNamesTheMissingKey() {
        DynamicsProperties blank = new DynamicsProperties(wireMock.baseUrl(), null, null,
                new DynamicsProperties.OAuth("", "", "", "", ""));

        Connection connection = new Connection(blank);

        assertThatThrownBy(connection::createContact)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("dynamics.oauth.client-id is not configured");
        assertThat(tokenRequests()).isZero();
        assertThat(dataverseRequests()).isZero();
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "invalid_token_response,  OTHER",
            "server_error,            OTHER",
            "temporarily_unavailable, OTHER",
            "invalid_client,          AUTHENTICATION",
            "unauthorized_client,     AUTHENTICATION",
            "invalid_grant,           AUTHENTICATION"})
    void tokenErrorIsAnAuthenticationFailureOnlyWhenEntraRejectedTheClientOrGrant(String errorCode,
            Category expected) {
        OAuth2Error error = new OAuth2Error(errorCode, "test description", null);

        assertThat(CLASSIFIER.classify(new OAuth2AuthorizationException(error)).category()).isEqualTo(expected);
        assertThat(CLASSIFIER.classify(new ClientAuthorizationException(error, DynamicsOAuthConfig.REGISTRATION_ID))
                .category()).isEqualTo(expected);
    }

    @Test
    void unreadableTokenResponseCausedByATimeoutIsUnavailable() {
        ClientAuthorizationException failure = new ClientAuthorizationException(
                new OAuth2Error("invalid_token_response"), DynamicsOAuthConfig.REGISTRATION_ID,
                new SocketTimeoutException("test read timeout"));

        assertThat(CLASSIFIER.classify(failure)).isEqualTo(UpstreamFailureKind.UNAVAILABLE);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "{\"error\":\"temporarily_unavailable\",\"error_description\":\"AADSTS50196: test throttling\"}"
                    + " | AADSTS50196: test throttling",
            "{\"error\":\"temporarily_unavailable\"}                                  | temporarily_unavailable",
            "{\"error\":\"temporarily_unavailable\",\"error_description\":\"  \"}     | temporarily_unavailable",
            "{\"error\":\"  \"}                                                       | 429 Too Many Requests",
            "{\"error\":{\"code\":\"0x80072321\",\"message\":\"test Dataverse limit\"}} | test Dataverse limit",
            "<html><body>test proxy page</body></html>                                | 429 Too Many Requests"})
    void upstreamMessageOfAThrottledResponseIsTheVendorsOwnText(String body, String expected) {
        WebClientResponseException throttled = WebClientResponseException.create(429, "Too Many Requests",
                new HttpHeaders(), body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        assertThat(CLASSIFIER.upstreamMessage(throttled)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "connect {0}, read {1}")
    @MethodSource("configuredTimeouts")
    void httpClientAppliesBothTimeoutsAndMakesOneTransportAttempt(Duration connectTimeout, Duration readTimeout,
            int expectedConnectTimeoutMillis, Duration expectedResponseTimeout) {
        HttpClientConfig configuration =
                DynamicsOAuthConfig.httpClient(properties(wireMock.baseUrl(), connectTimeout, readTimeout))
                        .configuration();

        assertThat(configuration.isRetryDisabled()).isTrue();
        assertThat(configuration.options().get(ChannelOption.CONNECT_TIMEOUT_MILLIS))
                .isEqualTo(expectedConnectTimeoutMillis);
        assertThat(configuration.responseTimeout()).isEqualTo(expectedResponseTimeout);
    }

    static Stream<Arguments> configuredTimeouts() {
        return Stream.of(
                Arguments.of(null, null, 10_000, Duration.ofSeconds(10)),
                Arguments.of(Duration.ofMillis(1), Duration.ofMillis(1), 1, Duration.ofMillis(1)),
                Arguments.of(Duration.ofNanos(1_500_000), Duration.ofNanos(1_500_000), 1, Duration.ofNanos(1_500_000)),
                Arguments.of(Duration.ofMillis(2_500), Duration.ofSeconds(30), 2_500, Duration.ofSeconds(30)),
                Arguments.of(Duration.ofMillis(Integer.MAX_VALUE + 1L), Duration.ofDays(365), Integer.MAX_VALUE,
                        Duration.ofDays(365)),
                Arguments.of(Duration.ofSeconds(Long.MAX_VALUE), Duration.ofSeconds(Long.MAX_VALUE), Integer.MAX_VALUE,
                        Duration.ofMillis(Long.MAX_VALUE)));
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("timeoutsShorterThanOneMillisecond")
    void timeoutShorterThanOneMillisecondIsRejectedNamingItsKey(String key, Duration timeout) {
        DynamicsProperties properties = "dynamics.connect-timeout".equals(key)
                ? properties(wireMock.baseUrl(), timeout, null)
                : properties(wireMock.baseUrl(), null, timeout);

        assertThatThrownBy(() -> DynamicsOAuthConfig.connector(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith(key + " must be a positive duration");
    }

    static Stream<Arguments> timeoutsShorterThanOneMillisecond() {
        return Stream.of("dynamics.connect-timeout", "dynamics.read-timeout")
                .flatMap(key -> Stream.of(Duration.ZERO, Duration.ofMillis(-1), Duration.ofNanos(1),
                                Duration.ofNanos(500_000), Duration.ofNanos(999_999))
                        .map(timeout -> Arguments.of(key, timeout)));
    }

    private static DynamicsProperties properties(String serviceUrl, Duration connectTimeout, Duration readTimeout) {
        return new DynamicsProperties(serviceUrl, connectTimeout, readTimeout, new DynamicsProperties.OAuth(
                "test-tenant", "test-client", "test-secret", wireMock.baseUrl() + TOKEN_PATH, null));
    }

    private static ResponseDefinitionBuilder entraError(int status, String body) {
        return aResponse().withStatus(status)
                .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .withBody(body);
    }

    private static void stubToken(ResponseDefinitionBuilder response) {
        wireMock.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(response));
    }

    private static int tokenRequests() {
        return wireMock.findAll(postRequestedFor(urlEqualTo(TOKEN_PATH))).size();
    }

    private static int dataverseRequests() {
        return wireMock.findAll(postRequestedFor(urlEqualTo(CONTACTS_PATH))).size();
    }

    /**
     * The configuration's real beans, wired as the application context wires them, and a contact create made
     * through SC-9 with the token eviction that {@code client/DynamicsWebApiClient} runs as its re-authentication
     * step.
     */
    private static final class Connection {

        private final DynamicsProperties properties;

        private final ReactiveOAuth2AuthorizedClientService service;

        private final WebClient webClient;

        private final UpstreamCallExecutor executor =
                new UpstreamCallExecutor(List.of(new DynamicsFailureClassifier()));

        private final AtomicInteger reauthentications = new AtomicInteger();

        Connection(DynamicsProperties properties) {
            DynamicsOAuthConfig config = new DynamicsOAuthConfig();
            ReactiveClientRegistrationRepository repository = config.dynamicsClientRegistrationRepository(properties);
            this.properties = properties;
            this.service = config.dynamicsAuthorizedClientService(repository);
            ReactiveOAuth2AuthorizedClientManager manager =
                    config.dynamicsAuthorizedClientManager(repository, service, properties);
            this.webClient = config.dynamicsWebClient(WebClient.builder(), manager, properties);
        }

        void createContact() throws Exception {
            createContact(Mono.just(CONTACT_JSON));
        }

        void createContact(Mono<String> body) throws Exception {
            executor.call(UpstreamSystem.MICROSOFT_DYNAMICS_365,
                    () -> webClient.post()
                            .uri(properties.requireServiceUrl() + CONTACTS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body, String.class)
                            .retrieve()
                            .toBodilessEntity()
                            .block(CALL_TIMEOUT),
                    this::evictToken);
        }

        private void evictToken() {
            reauthentications.incrementAndGet();
            service.removeAuthorizedClient(DynamicsOAuthConfig.REGISTRATION_ID, DynamicsOAuthConfig.PRINCIPAL_NAME)
                    .block(CALL_TIMEOUT);
        }
    }

    /**
     * A loopback server that resets every connection as soon as it accepts it ({@code SO_LINGER} 0), counting the
     * connections it accepted.
     */
    private static final class ResettingServer implements AutoCloseable {

        private final ServerSocket socket;

        private final AtomicInteger connections = new AtomicInteger();

        private final List<IOException> failures = new CopyOnWriteArrayList<>();

        private final Thread acceptor;

        ResettingServer() throws IOException {
            socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            acceptor = new Thread(this::acceptAndReset, "dynamics-resetting-server");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        String url() {
            return "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + socket.getLocalPort();
        }

        int connections() {
            return connections.get();
        }

        /** @return the failures to reset an accepted connection, which would leave the test's premise unmet */
        List<IOException> failures() {
            return failures;
        }

        private void acceptAndReset() {
            while (true) {
                Socket accepted;
                try {
                    accepted = socket.accept();
                } catch (IOException closed) {
                    return;
                }
                connections.incrementAndGet();
                try (Socket connection = accepted) {
                    connection.setSoLinger(true, 0);
                } catch (IOException resetFailure) {
                    failures.add(resetFailure);
                }
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
            try {
                acceptor.join(CALL_TIMEOUT.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
