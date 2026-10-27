/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.client.BoxApiClient.RawResponse;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.config.BoxProperties;

/**
 * The contract of {@link BoxApiClient}, the Box requests of
 * {@code oauth2-authorization-code-using-the-HTTP-connector/src/main/app/http-authorization-code-web.xml} (the
 * token request of line 13 and the {@code GET /search} of lines 40-45), against a WireMock stand-in for Box: the
 * token forms are sent in Mule's field order and encoding, the search carries the bearer token, every status is
 * returned rather than thrown, an I/O failure surfaces as a {@link ResourceAccessException} after exactly one
 * attempt, a response still incomplete when the 10 s read timeout ends fails once, the DEBUG log shows only scheme,
 * host, port and path, and a configured URL that could carry credentials, or that the client cannot use, is
 * rejected before any request with a message naming only its key. Over {@code https} the client trusts the
 * {@code TLS_Context} trust store only and checks the host name, and redirects are followed except from
 * {@code https} to {@code http}, without the bearer token once they leave its origin.
 */
class BoxApiClientTest {

    private static final String CLIENT_ID = "test-box-client-id";
    private static final String CLIENT_SECRET = "test-box-client-secret";
    private static final String REDIRECT_URL = "https://localhost:8082/redirectUrl";
    /** {@link #REDIRECT_URL} form-encoded, written out so the expected forms do not reuse the client's encoder. */
    private static final String ENCODED_REDIRECT = "https%3A%2F%2Flocalhost%3A8082%2FredirectUrl";
    private static final String STORE_PASSWORD = "mule123";

    private static final String CODE = "auth code/1";
    private static final String REFRESH = "refresh+token=1";
    private static final String ACCESS_TOKEN = "access-token-1";

    private static final String TOKEN_PATH = "/api/oauth2/token";
    private static final String SEARCH_PATH = "/2.0/search?query=mule";
    /** Where the redirect cases send the search. */
    private static final String MOVED_PATH = "/2.0/moved";

    /**
     * The key and trust stores of the TLS cases, made at run time so no committed certificate can expire, since
     * the carried {@code keystore.jks} and {@code trust-store} hold a certificate that expired in 2015. Both server
     * keys are for {@code localhost} only; the trust store holds the first one's certificate.
     */
    @TempDir
    static Path tlsDir;
    private static Path trustedServerKeyStore;
    private static Path untrustedServerKeyStore;
    private static Path trustStore;
    /** HTTPS stand-ins for Box, presenting the trusted and the untrusted server key. */
    private static WireMockServer trustedHttps;
    private static WireMockServer untrustedHttps;

    /** Each Box exchange of the client, so a case that holds for all three runs once per exchange. */
    enum Operation {
        SEARCH(client -> client.search(ACCESS_TOKEN)),
        REQUEST_TOKEN(client -> client.requestToken(CODE)),
        REFRESH_TOKEN(client -> client.refreshToken(REFRESH));

        private final Function<BoxApiClient, RawResponse> call;

        Operation(Function<BoxApiClient, RawResponse> call) {
            this.call = call;
        }

        RawResponse callOn(BoxApiClient client) {
            return call.apply(client);
        }

        /** The path and query the exchange requests under the base URLs the tests configure. */
        String requestPath() {
            return this == SEARCH ? SEARCH_PATH : TOKEN_PATH;
        }
    }

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final Logger clientLogger = (Logger) LoggerFactory.getLogger(BoxApiClient.class);
    private final ListAppender<ILoggingEvent> clientLog = new ListAppender<>();
    private Level clientLogLevel;

    private BoxApiClient client;

    @BeforeAll
    static void startHttpsStandIns() throws IOException, InterruptedException {
        trustedServerKeyStore = tlsDir.resolve("trusted-server.jks");
        untrustedServerKeyStore = tlsDir.resolve("untrusted-server.jks");
        trustStore = tlsDir.resolve("trust.jks");
        Path certificate = tlsDir.resolve("trusted-server.pem");
        generateLocalhostKey(trustedServerKeyStore);
        generateLocalhostKey(untrustedServerKeyStore);
        keytool("-exportcert", "-rfc", "-alias", "box", "-keystore", trustedServerKeyStore.toString(),
                "-storepass", STORE_PASSWORD, "-file", certificate.toString());
        keytool("-importcert", "-noprompt", "-alias", "box", "-file", certificate.toString(),
                "-keystore", trustStore.toString(), "-storetype", "JKS", "-storepass", STORE_PASSWORD);
        trustedHttps = httpsStandIn(trustedServerKeyStore);
        untrustedHttps = httpsStandIn(untrustedServerKeyStore);
    }

    @AfterAll
    static void stopHttpsStandIns() {
        for (WireMockServer server : Arrays.asList(trustedHttps, untrustedHttps)) {
            if (server != null) {
                server.stop();
            }
        }
    }

    @BeforeEach
    void setUp() {
        wm.resetAll();
        trustedHttps.resetAll();
        untrustedHttps.resetAll();
        clientLogLevel = clientLogger.getLevel();
        clientLog.start();
        clientLogger.addAppender(clientLog);
        clientLogger.setLevel(Level.DEBUG);
        client = new BoxApiClient(properties(wm.baseUrl() + TOKEN_PATH, wm.baseUrl() + "/2.0"));
    }

    @AfterEach
    void restoreClientLogger() {
        clientLogger.setLevel(clientLogLevel);
        clientLogger.detachAppender(clientLog);
        clientLog.stop();
    }

    @AfterEach
    void closeClient() {
        // null only when setUp failed before building it, which is the failure to report.
        if (client != null) {
            client.close();
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "REQUEST_TOKEN | code=auth+code%2F1&grant_type=authorization_code&client_secret=test-box-client-secret"
                + "&redirect_uri=" + ENCODED_REDIRECT + "&client_id=test-box-client-id",
        "REFRESH_TOKEN | refresh_token=refresh%2Btoken%3D1&grant_type=refresh_token"
                + "&client_secret=test-box-client-secret"
                + "&redirect_uri=" + ENCODED_REDIRECT + "&client_id=test-box-client-id"
    })
    void tokenFormSentInMuleFieldOrderAndEncoding(Operation operation, String expectedForm) {
        assertThat(ENCODED_REDIRECT).isEqualTo(URLEncoder.encode(REDIRECT_URL, UTF_8));
        wm.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"a\"}")));

        RawResponse response = operation.callOn(client);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.headers().getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo("application/json");
        assertThat(new String(response.body(), UTF_8)).isEqualTo("{\"access_token\":\"a\"}");
        // equalTo compares the body exactly, so field order and encoding are both checked.
        wm.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH))
                .withHeader(HttpHeaders.CONTENT_TYPE, equalTo("application/x-www-form-urlencoded"))
                .withoutHeader(HttpHeaders.AUTHORIZATION)
                .withoutHeader(HttpHeaders.ACCEPT_ENCODING)
                .withRequestBody(equalTo(expectedForm)));
        assertThat(wm.getAllServeEvents()).hasSize(1);
    }

    @Test
    void searchSendsBearerTokenWithoutBody() {
        wm.stubFor(get(urlEqualTo(SEARCH_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withHeader("X-Box", "v")
                .withBody("{\"entries\":[]}")));

        RawResponse response = client.search(ACCESS_TOKEN);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.headers().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.headers().getFirst("X-Box")).isEqualTo("v");
        assertThat(new String(response.body(), UTF_8)).isEqualTo("{\"entries\":[]}");
        // No compression is negotiated and no HTTP/2 upgrade is offered: the Mule requester spoke plain HTTP/1.1.
        wm.verify(1, getRequestedFor(urlEqualTo(SEARCH_PATH))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer " + ACCESS_TOKEN))
                .withoutHeader(HttpHeaders.CONTENT_TYPE)
                .withoutHeader(HttpHeaders.ACCEPT_ENCODING)
                .withoutHeader(HttpHeaders.UPGRADE));
        assertThat(wm.getAllServeEvents()).singleElement()
                .satisfies(event -> assertThat(event.getRequest().getBody()).isEmpty());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "SEARCH        | 401 | unauthorized",
        "SEARCH        | 404 | not found",
        "SEARCH        | 500 | server error",
        "REQUEST_TOKEN | 400 | invalid_grant",
        "REFRESH_TOKEN | 503 | unavailable",
        "REQUEST_TOKEN | 204 | ''"
    })
    void everyStatusIsReturnedNotThrown(Operation operation, int status, String body) {
        wm.stubFor(any(urlEqualTo(operation.requestPath()))
                .willReturn(aResponse().withStatus(status).withHeader(HttpHeaders.RETRY_AFTER, "5").withBody(body)));

        RawResponse response = operation.callOn(client);

        assertThat(response.status()).isEqualTo(status);
        // Headers are copied with every status, so the caller sees what Box sent alongside an error.
        assertThat(response.headers().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
        assertThat(response.body()).isEqualTo(body.getBytes(UTF_8));
        // Exactly one attempt: an error status is never retried.
        assertThat(wm.getAllServeEvents()).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void refusedConnectionSurfacesAsResourceAccessException(Operation operation) throws IOException {
        // The port stays reserved, and so refusing, until every assertion has run.
        try (Socket reserved = reserveRefusingPort()) {
            String refused = "http://127.0.0.1:" + reserved.getLocalPort();
            Throwable thrown;
            try (BoxApiClient refusedClient = new BoxApiClient(properties(refused + TOKEN_PATH, refused + "/2.0"))) {
                thrown = catchThrowable(() -> operation.callOn(refusedClient));
            }

            assertThat(thrown).isInstanceOf(ResourceAccessException.class);
            assertThat(causeChain(thrown)).anyMatch(ConnectException.class::isInstance);
        }
    }

    @ParameterizedTest
    @MethodSource("operationsAndFaults")
    void transportFaultSurfacesAsResourceAccessExceptionAfterOneAttempt(Operation operation, Fault fault) {
        wm.stubFor(any(urlEqualTo(operation.requestPath())).willReturn(aResponse().withFault(fault)));

        Throwable thrown = catchThrowable(() -> operation.callOn(client));

        assertThat(thrown).isInstanceOf(ResourceAccessException.class);
        assertThat(causeChain(thrown)).anyMatch(IOException.class::isInstance);
        // Exactly one attempt: a request whose connection is reset or closed before or during the response is not
        // sent again, as java.net.http.HttpClient sends a GET again.
        wm.verify(1, anyRequestedFor(urlEqualTo(operation.requestPath())));
        assertThat(wm.getAllServeEvents()).hasSize(1);
    }

    /** Every exchange with every WireMock fault, so a fault WireMock adds later is covered too. */
    static Stream<Arguments> operationsAndFaults() {
        return Arrays.stream(Operation.values())
                .flatMap(operation -> Arrays.stream(Fault.values()).map(fault -> Arguments.of(operation, fault)));
    }

    /**
     * A raw upstream counts connections as well as requests, so it also sees a second attempt that a transport
     * would make on a new connection.
     */
    @ParameterizedTest
    @MethodSource("operationsAndHangups")
    void connectionEndedWithoutResponseIsNeverAttemptedAgain(Operation operation, Answer hangup)
            throws IOException {
        Throwable thrown;
        try (CountingUpstream upstream = new CountingUpstream(hangup);
             BoxApiClient upstreamClient = new BoxApiClient(
                     properties(upstream.url() + TOKEN_PATH, upstream.url() + "/2.0"))) {

            thrown = catchThrowable(() -> operation.callOn(upstreamClient));

            upstream.stop();
            assertThat(upstream.connections()).as("connections opened").isEqualTo(1);
            assertThat(upstream.requests()).as("requests received").isEqualTo(1);
        }
        assertThat(thrown).isInstanceOf(ResourceAccessException.class);
        assertThat(causeChain(thrown)).anyMatch(IOException.class::isInstance);
    }

    static Stream<Arguments> operationsAndHangups() {
        return Arrays.stream(Operation.values()).flatMap(operation ->
                Stream.of(Answer.EARLY_EOF, Answer.RESET).map(hangup -> Arguments.of(operation, hangup)));
    }

    /**
     * A pooled connection the upstream closed while it sat idle is not reused: it is checked before the next request
     * and replaced, so the request succeeds on a new connection instead of failing on the closed one, with no
     * request repeated.
     */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void pooledConnectionClosedByTheUpstreamIsReplacedBeforeReuse(Operation operation)
            throws IOException, InterruptedException {
        try (CountingUpstream upstream = new CountingUpstream(Answer.RESPOND_THEN_CLOSE);
             BoxApiClient upstreamClient = new BoxApiClient(
                     properties(upstream.url() + TOKEN_PATH, upstream.url() + "/2.0"))) {

            assertThat(operation.callOn(upstreamClient).status()).isEqualTo(200);
            assertThat(upstream.awaitClosedConnections(1)).as("first connection closed by the upstream").isTrue();
            assertThat(operation.callOn(upstreamClient).status()).isEqualTo(200);

            upstream.stop();
            assertThat(upstream.connections()).as("connections opened").isEqualTo(2);
            assertThat(upstream.requests()).as("requests received").isEqualTo(2);
        }
    }

    /**
     * httpclient5 would try the next address of a host whose first address refused the connection; the client
     * connects to the first address only, as java.net.http.HttpClient did.
     */
    @Test
    void hostResolvesToItsFirstAddressOnly() throws UnknownHostException {
        InetAddress first = InetAddress.getByAddress("box.example", new byte[] {127, 0, 0, 1});
        InetAddress second = InetAddress.getByAddress("box.example", new byte[] {127, 0, 0, 2});
        DnsResolver twoAddresses = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) {
                return new InetAddress[] {first, second};
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return "canonical." + host;
            }
        };

        BoxApiClient.FirstAddressDnsResolver resolver = new BoxApiClient.FirstAddressDnsResolver(twoAddresses);

        assertThat(resolver.resolve("box.example")).containsExactly(first);
        assertThat(resolver.resolveCanonicalHostname("box.example")).isEqualTo("canonical.box.example");
    }

    /**
     * The stand-in's certificate is in the run-time trust store only, never in the JDK {@code cacerts}, so the
     * exchange succeeding shows that {@code TLS_Context} decided the trust.
     */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void httpsExchangeTrustsTheTlsContextTrustStore(Operation operation) {
        trustedHttps.stubFor(any(urlEqualTo(operation.requestPath())).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{}")));
        String origin = "https://localhost:" + trustedHttps.httpsPort();

        try (BoxApiClient tlsClient = new BoxApiClient(
                properties(origin + TOKEN_PATH, origin + "/2.0", trustStore.toString()))) {
            RawResponse response = operation.callOn(tlsClient);

            assertThat(response.status()).isEqualTo(200);
            assertThat(new String(response.body(), UTF_8)).isEqualTo("{}");
        }
        assertThat(trustedHttps.getAllServeEvents()).hasSize(1);
    }

    /**
     * A server whose certificate does not name the requested host, or that the trust store does not hold, is
     * refused during the handshake: no request reaches it, and none is tried again.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "127.0.0.1 | true  | javax.net.ssl.SSLPeerUnverifiedException",
        "localhost | false | javax.net.ssl.SSLHandshakeException"
    })
    void httpsServerTheTlsContextCannotVerifyIsRefusedBeforeAnyRequest(String host, boolean trusted,
                                                                       Class<?> expectedCause) {
        WireMockServer server = trusted ? trustedHttps : untrustedHttps;
        server.stubFor(any(anyUrl()).willReturn(ok()));
        String origin = "https://" + host + ":" + server.httpsPort();

        Throwable thrown;
        try (BoxApiClient tlsClient = new BoxApiClient(
                properties(origin + TOKEN_PATH, origin + "/2.0", trustStore.toString()))) {
            thrown = catchThrowable(() -> tlsClient.search(ACCESS_TOKEN));
        }

        assertThat(thrown).isInstanceOf(ResourceAccessException.class);
        assertThat(causeChain(thrown)).anyMatch(expectedCause::isInstance);
        assertThat(server.getAllServeEvents()).isEmpty();
    }

    /**
     * Stores whose password is wrong are never opened by a plain-HTTP exchange, and fail the first {@code https}
     * one with the store error before any request is written.
     */
    @Test
    void tlsContextStoresAreLoadedByTheFirstHttpsConnectionOnly() {
        String httpsOrigin = "https://localhost:" + trustedHttps.httpsPort();
        BoxProperties wrongPasswords = new BoxProperties(CLIENT_ID, CLIENT_SECRET,
                "https://www.box.com/api/oauth2/authorize", "https://localhost:8082/authorization", REDIRECT_URL,
                wm.baseUrl() + TOKEN_PATH, httpsOrigin + "/2.0",
                "https://localhost:8082/authorization?onCompleteRedirectTo=http://localhost:8081/web/loginDone",
                "keystore.jks", "wrong-password", "wrong-password", trustStore.toString(), "wrong-password");
        wm.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(ok()));
        trustedHttps.stubFor(get(urlEqualTo(SEARCH_PATH)).willReturn(ok()));

        Throwable thrown;
        try (BoxApiClient lazyClient = new BoxApiClient(wrongPasswords)) {
            assertThat(lazyClient.requestToken(CODE).status()).isEqualTo(200);

            thrown = catchThrowable(() -> lazyClient.search(ACCESS_TOKEN));
        }

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessage("Cannot load key store 'keystore.jks'");
        assertThat(causeChain(thrown)).extracting(Throwable::toString)
                .noneMatch(text -> text.contains("wrong-password"));
        assertThat(trustedHttps.getAllServeEvents()).isEmpty();
        assertThat(wm.getAllServeEvents()).hasSize(1);
    }

    /**
     * {@code HTTP_ORIGIN} is the plain stand-in, {@code HTTP_OTHER_ORIGIN} the same server under another host name
     * and so another origin, {@code HTTPS_ORIGIN} the trusted https stand-in. The search on {@code scheme}'s
     * stand-in answers 302 to {@code target}{@link #MOVED_PATH}, which answers 200.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "http  | HTTP_ORIGIN       | 200 | true",
        "http  | HTTP_OTHER_ORIGIN | 200 | false",
        "http  | HTTPS_ORIGIN      | 200 | false",
        "https | HTTPS_ORIGIN      | 200 | true",
        "https | HTTP_ORIGIN       | 302 | false"
    })
    void redirectFollowedExceptFromHttpsToHttpAndTokenKeptToItsOrigin(String scheme, String target,
                                                                     int expectedStatus, boolean tokenForwarded) {
        String httpOrigin = wm.baseUrl();
        String httpsOrigin = "https://localhost:" + trustedHttps.httpsPort();
        String targetOrigin = switch (target) {
            case "HTTP_ORIGIN" -> httpOrigin;
            case "HTTP_OTHER_ORIGIN" -> "http://127.0.0.1:" + wm.getPort();
            case "HTTPS_ORIGIN" -> httpsOrigin;
            default -> throw new IllegalArgumentException("Unknown target " + target);
        };
        String location = targetOrigin + MOVED_PATH;
        // Both stand-ins redirect the search and answer the moved path, whichever of them the case starts on.
        wm.stubFor(get(urlEqualTo(SEARCH_PATH))
                .willReturn(aResponse().withStatus(302).withHeader(HttpHeaders.LOCATION, location)));
        wm.stubFor(get(urlEqualTo(MOVED_PATH)).willReturn(aResponse().withStatus(200).withBody("moved")));
        trustedHttps.stubFor(get(urlEqualTo(SEARCH_PATH))
                .willReturn(aResponse().withStatus(302).withHeader(HttpHeaders.LOCATION, location)));
        trustedHttps.stubFor(get(urlEqualTo(MOVED_PATH)).willReturn(aResponse().withStatus(200).withBody("moved")));
        String origin = "https".equals(scheme) ? httpsOrigin : httpOrigin;

        RawResponse response;
        try (BoxApiClient redirectClient = new BoxApiClient(
                properties(origin + TOKEN_PATH, origin + "/2.0", trustStore.toString()))) {
            response = redirectClient.search(ACCESS_TOKEN);
        }

        assertThat(response.status()).isEqualTo(expectedStatus);
        List<LoggedRequest> searches = new ArrayList<>(wm.findAll(getRequestedFor(urlEqualTo(SEARCH_PATH))));
        searches.addAll(trustedHttps.findAll(getRequestedFor(urlEqualTo(SEARCH_PATH))));
        assertThat(searches).singleElement().satisfies(search ->
                assertThat(search.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + ACCESS_TOKEN));
        List<LoggedRequest> moved = new ArrayList<>(wm.findAll(getRequestedFor(urlEqualTo(MOVED_PATH))));
        moved.addAll(trustedHttps.findAll(getRequestedFor(urlEqualTo(MOVED_PATH))));
        if (expectedStatus == 302) {
            // Not followed: the redirect is returned as received and nothing is sent to the http URL.
            assertThat(response.headers().getFirst(HttpHeaders.LOCATION)).isEqualTo(location);
            assertThat(moved).isEmpty();
        } else {
            assertThat(new String(response.body(), UTF_8)).isEqualTo("moved");
            assertThat(moved).singleElement().satisfies(hop -> {
                assertThat(hop.getAbsoluteUrl()).isEqualTo(location);
                assertThat(hop.containsHeader(HttpHeaders.AUTHORIZATION)).isEqualTo(tokenForwarded);
            });
        }
    }

    /**
     * A search that redirects to itself is followed {@link BoxApiClient#MAX_REDIRECTS_FOLLOWED} times, as
     * java.net.http.HttpClient followed it, and the redirect after them is returned rather than thrown.
     */
    @Test
    void redirectLoopEndsWithTheRedirectReturnedAfterTheFollowLimit() {
        wm.stubFor(get(urlEqualTo(SEARCH_PATH))
                .willReturn(aResponse().withStatus(302).withHeader(HttpHeaders.LOCATION, wm.baseUrl() + SEARCH_PATH)));

        RawResponse response = client.search(ACCESS_TOKEN);

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.headers().getFirst(HttpHeaders.LOCATION)).isEqualTo(wm.baseUrl() + SEARCH_PATH);
        wm.verify(1 + BoxApiClient.MAX_REDIRECTS_FOLLOWED, getRequestedFor(urlEqualTo(SEARCH_PATH)));
        assertThat(wm.getAllServeEvents()).hasSize(1 + BoxApiClient.MAX_REDIRECTS_FOLLOWED);
    }

    @Test
    void debugLogShowsOnlySchemeHostPortAndPath() {
        String sentinel = sentinel();
        String tokenPathAndQuery = TOKEN_PATH + "?tenant=" + sentinel;
        wm.stubFor(post(urlEqualTo(tokenPathAndQuery)).willReturn(ok()));
        wm.stubFor(get(urlEqualTo(SEARCH_PATH)).willReturn(ok()));

        try (BoxApiClient sentinelClient =
                     new BoxApiClient(properties(wm.baseUrl() + tokenPathAndQuery, wm.baseUrl() + "/2.0/"))) {
            assertThat(sentinelClient.requestToken(CODE).status()).isEqualTo(200);
            assertThat(sentinelClient.search(sentinel).status()).isEqualTo(200);
        }

        // The token endpoint's query is sent unchanged, and the access token as the bearer credential...
        wm.verify(1, postRequestedFor(urlEqualTo(tokenPathAndQuery)));
        wm.verify(1, getRequestedFor(urlEqualTo(SEARCH_PATH))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer " + sentinel)));
        // ...while the log shows scheme, host, port and path only.
        assertThat(clientLog.list).extracting(ILoggingEvent::getLevel).containsOnly(Level.DEBUG);
        assertThat(clientLog.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "POST " + wm.baseUrl() + TOKEN_PATH,
                "GET " + wm.baseUrl() + "/2.0/search");
        assertThat(clientLog.list).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains(sentinel));
    }

    /**
     * {@code HOSTPORT} becomes the WireMock stand-in's address, so a URL the client failed to reject would reach
     * it, {@code SENTINEL} a value made at run time that must appear in no message or log line, and
     * {@code USERINFO} user information assembled at run time with that value as its password.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "box.token-url | REQUEST_TOKEN | http://USERINFO@HOSTPORT/api/oauth2/token"
                + " | must not contain user information",
        "box.api-base-url | SEARCH | https://USERINFO@HOSTPORT/2.0 | must not contain user information",
        "box.api-base-url | SEARCH | http://USERINFO@bad_host/2.0 | has no host",
        "box.token-url | REFRESH_TOKEN | http://HOSTPORT/api/oauth2/token#SENTINEL | must not contain a fragment",
        "box.api-base-url | SEARCH | http://HOSTPORT/2.0#SENTINEL | must not contain a fragment",
        "box.api-base-url | SEARCH | http://HOSTPORT/2.0?x=SENTINEL | must not contain a query",
        "box.token-url | REQUEST_TOKEN | ftp://HOSTPORT/SENTINEL | is not an http or https URL",
        "box.api-base-url | SEARCH | mailto:SENTINEL@example.com | is not an http or https URL",
        "box.token-url | REFRESH_TOKEN | http:///SENTINEL | has no host",
        "box.token-url | REQUEST_TOKEN | /api/oauth2/token?x=SENTINEL | is not an absolute URL",
        "box.token-url | REQUEST_TOKEN | http://HOSTPORT/api/oauth2/token?x=SENTINEL y"
                + " | is not a valid URL: Illegal character in query",
        "box.api-base-url | SEARCH | http://HOSTPORT/2.0/SENTINEL%zz | is not a valid URL: Malformed escape pair"
    })
    void unusableUrlRejectedBeforeAnyRequestNamingOnlyItsKey(String key, Operation operation, String template,
                                                            String problem) {
        String sentinel = sentinel();
        String url = template.replace("USERINFO", "sentinel-user" + ':' + sentinel)
                .replace("HOSTPORT", "127.0.0.1:" + wm.getPort()).replace("SENTINEL", sentinel);
        BoxProperties properties = "box.token-url".equals(key)
                ? properties(url, wm.baseUrl() + "/2.0")
                : properties(wm.baseUrl() + TOKEN_PATH, url);
        wm.stubFor(any(anyUrl()).willReturn(ok()));

        // Built inside catchThrowable, so the rejection is caught whether construction or the call raises it.
        Throwable thrown = catchThrowable(() -> {
            try (BoxApiClient unusableClient = new BoxApiClient(properties)) {
                operation.callOn(unusableClient);
            }
        });

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage(key + " " + problem).hasNoCause();
        assertThat(causeChain(thrown)).extracting(Throwable::toString).noneMatch(text -> text.contains(sentinel));
        assertThat(wm.getAllServeEvents()).isEmpty();
        assertThat(clientLog.list).isEmpty();
    }

    // BoxApiClient has one constructor and a fixed 10 s READ_TIMEOUT, so each case waits out a real stall.
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "SEARCH        | http  | HEAD",
        "REQUEST_TOKEN | http  | HEAD_TRICKLE",
        "SEARCH        | http  | BODY",
        "REQUEST_TOKEN | http  | BODY_TRICKLE",
        "REFRESH_TOKEN | http  | BODY_TRICKLE",
        "SEARCH        | https | BODY_TRICKLE"
    })
    void responseStalledTimesOutOnceWithinTheReadTimeout(Operation operation, String scheme, Stall stall)
            throws IOException, GeneralSecurityException, InterruptedException {
        // A raw socket rather than WireMock, whose dribble delay also holds back the headers. A silent stall is
        // ended by the socket timeout as much as by a deadline; a trickle never lets the socket timeout expire, so
        // only the deadline on the response head, or on the body, which aborts the connection, can end the call.
        // Over https that abort has to end a TLS read.
        AtomicInteger requests = new AtomicInteger();
        List<Socket> connections = Collections.synchronizedList(new ArrayList<>());
        List<Exception> closeFailures = new ArrayList<>();
        ServerSocket upstream = "https".equals(scheme)
                ? serverContext(trustedServerKeyStore).getServerSocketFactory().createServerSocket(0)
                : new ServerSocket(0);
        Thread server = new Thread(() -> answerThenStall(upstream, stall, requests, connections),
                "stalled-box-upstream");
        server.setDaemon(true);
        // Set once the client is built, and closed by the finally block below rather than by try-with-resources.
        BoxApiClient clientToClose = null;
        try {
            server.start();
            // localhost is the name the https server key is for.
            String stalled = scheme + "://localhost:" + upstream.getLocalPort();
            BoxApiClient stalledClient = new BoxApiClient(
                    properties(stalled + TOKEN_PATH, stalled + "/2.0", trustStore.toString()));
            clientToClose = stalledClient;

            long start = System.nanoTime();
            // The watchdog is independent of the client's own timeout, so a body read that never ends fails
            // the test here instead of hanging the build.
            Throwable thrown = assertTimeoutPreemptively(BoxApiClient.READ_TIMEOUT.multipliedBy(2),
                    () -> catchThrowable(() -> operation.callOn(stalledClient)));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(thrown).isInstanceOf(ResourceAccessException.class);
            assertThat(causeChain(thrown)).anyMatch(HttpTimeoutException.class::isInstance);
            assertThat(elapsed).isBetween(BoxApiClient.READ_TIMEOUT, BoxApiClient.READ_TIMEOUT.plusSeconds(5));
            // Exactly one attempt: a timed-out request is never retried.
            assertThat(requests).hasValue(1);
            assertThat(connections).as("connections opened").hasSize(1);
        } finally {
            // The one owner of the sockets and the client: runs whether the call returned, the watchdog gave up on
            // it, or starting the server thread or building the client failed. Closing the listening socket ends
            // the accept loop, and closing each accepted connection ends any client read still blocked on it. The
            // client is closed only after both, so its graceful close of a connection the watchdog left leased, TLS
            // included, never waits on an upstream still open. Every close is attempted whatever the others do;
            // failures are reported only after the cleanup, so none of them replaces the failure that brought the
            // test here.
            try {
                upstream.close();
            } catch (IOException ex) {
                closeFailures.add(ex);
            }
            synchronized (connections) {
                for (Socket connection : connections) {
                    try {
                        connection.close();
                    } catch (IOException ex) {
                        closeFailures.add(ex);
                    }
                }
            }
            if (clientToClose != null) {
                try {
                    clientToClose.close();
                } catch (RuntimeException ex) {
                    closeFailures.add(ex);
                }
            }
            // A thread that never started is not alive, so this returns at once if start() failed.
            server.join(Duration.ofSeconds(5).toMillis());
        }
        assertThat(closeFailures).as("failures closing the stalled upstream's sockets and the client").isEmpty();
        assertThat(server.isAlive()).as("stalled upstream thread still running").isFalse();
    }

    /** How the stalled upstream keeps its response from completing. */
    enum Stall {
        /** Sends nothing. */
        HEAD,
        /** Sends the status line, then a header value one byte every 500 ms, never ending the head. */
        HEAD_TRICKLE,
        /** Sends a complete response head announcing 1000 body bytes and the first 10 of them, then nothing. */
        BODY,
        /** Sends a complete response head announcing 1000 body bytes, then one body byte every 500 ms. */
        BODY_TRICKLE
    }

    /** Pause between the bytes of a trickle, well inside the socket timeout. */
    private static final Duration TRICKLE_INTERVAL = Duration.ofMillis(500);

    /**
     * Accepts connections until {@code upstream} is closed, each answered on a daemon thread of its own, so a
     * trickle on one never delays accepting, and so counting, another. Each reads the request head, counts it,
     * and answers as {@code stall} says, so the response never completes; a trickle goes on until a write fails
     * because the connection has ended.
     *
     * <p>Each accepted connection is added to {@code connections}, a synchronized list the caller closes after
     * closing {@code upstream}, which also ends the connection's thread. A connection accepted while
     * {@code upstream} was being closed may join the list after the caller has closed its contents, so this loop
     * closes that one itself and stops.
     */
    private static void answerThenStall(ServerSocket upstream, Stall stall, AtomicInteger requests,
                                        List<Socket> connections) {
        while (!upstream.isClosed()) {
            Socket connection;
            try {
                connection = upstream.accept();
            } catch (IOException closed) {
                // The test closed the listening socket: nothing more to accept.
                return;
            }
            connections.add(connection);
            if (upstream.isClosed()) {
                closeQuietly(connection);
                return;
            }
            Thread handler = new Thread(() -> readThenStall(connection, stall, requests), "stalled-box-connection");
            handler.setDaemon(true);
            handler.start();
        }
    }

    private static void readThenStall(Socket connection, Stall stall, AtomicInteger requests) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), US_ASCII));
            String line;
            do {
                line = reader.readLine();
            } while (line != null && !line.isEmpty());
            requests.incrementAndGet();
            stall(connection.getOutputStream(), stall);
        } catch (IOException dropped) {
            // The client dropped the connection, or the test closed it: nothing more to send.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void stall(OutputStream out, Stall stall) throws IOException, InterruptedException {
        String head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1000\r\n\r\n";
        switch (stall) {
            case HEAD -> {
                // Nothing is sent; the connection stays open until the client or the test closes it.
            }
            case HEAD_TRICKLE -> {
                out.write("HTTP/1.1 200 OK\r\nX-Trickle: ".getBytes(US_ASCII));
                trickle(out);
            }
            case BODY -> {
                out.write((head + "0123456789").getBytes(US_ASCII));
                out.flush();
            }
            case BODY_TRICKLE -> {
                out.write(head.getBytes(US_ASCII));
                trickle(out);
            }
            default -> throw new IllegalStateException("Unknown stall " + stall);
        }
    }

    /** Writes one byte every {@link #TRICKLE_INTERVAL} until a write fails because the connection has ended. */
    private static void trickle(OutputStream out) throws IOException, InterruptedException {
        while (true) {
            out.write('a');
            out.flush();
            Thread.sleep(TRICKLE_INTERVAL.toMillis());
        }
    }

    private static void closeQuietly(Socket connection) {
        try {
            connection.close();
        } catch (IOException ignored) {
            // The connection is being discarded.
        }
    }

    /** How {@link CountingUpstream} answers each request it reads. */
    enum Answer {
        /** Closes the connection without a response, so the client reads end-of-stream for the status line. */
        EARLY_EOF,
        /** Resets the connection without a response (SO_LINGER 0), so the client's read fails with a reset. */
        RESET,
        /** Sends a complete {@code 200} response that keeps the connection alive, then closes it anyway. */
        RESPOND_THEN_CLOSE
    }

    /**
     * An HTTP/1.1 upstream on a port of its own that counts the connections it accepts and the requests it reads,
     * head and {@code Content-Length} body, answering each request as its {@link Answer} says. {@link #stop()},
     * which {@link #close()} also runs, ends the accept loop, closes every accepted connection and waits for the
     * thread, so the counts are final once it returns.
     */
    private static final class CountingUpstream implements AutoCloseable {

        private final Answer answer;
        private final ServerSocket server;
        private final Thread thread;
        private final AtomicInteger connections = new AtomicInteger();
        private final AtomicInteger requests = new AtomicInteger();
        private final Semaphore closedConnections = new Semaphore(0);
        private final List<Socket> accepted = Collections.synchronizedList(new ArrayList<>());

        CountingUpstream(Answer answer) throws IOException {
            this.answer = answer;
            this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            this.thread = new Thread(this::serve, "counting-box-upstream");
            thread.setDaemon(true);
            thread.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getLocalPort();
        }

        int connections() {
            return connections.get();
        }

        int requests() {
            return requests.get();
        }

        /** Waits up to five seconds until the upstream has itself closed {@code count} connections. */
        boolean awaitClosedConnections(int count) throws InterruptedException {
            return closedConnections.tryAcquire(count, 5, TimeUnit.SECONDS);
        }

        private void serve() {
            try {
                while (!server.isClosed()) {
                    Socket connection = server.accept();
                    accepted.add(connection);
                    connections.incrementAndGet();
                    // One connection at a time: none of the cases runs two exchanges at once.
                    answer(connection);
                }
            } catch (IOException closed) {
                // stop() closed the listening socket: nothing more to accept.
            }
        }

        private void answer(Socket connection) {
            try (connection) {
                InputStream in = connection.getInputStream();
                while (readRequest(in)) {
                    requests.incrementAndGet();
                    switch (answer) {
                        case EARLY_EOF -> {
                            return;
                        }
                        case RESET -> {
                            connection.setSoLinger(true, 0);
                            return;
                        }
                        case RESPOND_THEN_CLOSE -> {
                            OutputStream out = connection.getOutputStream();
                            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n"
                                    + "\r\n{}").getBytes(US_ASCII));
                            out.flush();
                            return;
                        }
                        default -> throw new IllegalStateException("Unknown answer " + answer);
                    }
                }
            } catch (IOException dropped) {
                // The client dropped the connection: nothing more to read from it.
            } finally {
                closedConnections.release();
            }
        }

        /**
         * Reads one request, its head and the {@code Content-Length} bytes of its body, so that closing the
         * connection afterwards sends FIN rather than the reset unread bytes would cause.
         *
         * @return {@code false} when the connection ended before a request head
         */
        private static boolean readRequest(InputStream in) throws IOException {
            StringBuilder head = new StringBuilder();
            int next;
            while ((next = in.read()) != -1) {
                head.append((char) next);
                if (head.length() >= 4 && head.substring(head.length() - 4).equals("\r\n\r\n")) {
                    break;
                }
            }
            if (next == -1) {
                return false;
            }
            long contentLength = 0;
            for (String line : head.toString().split("\r\n")) {
                if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    contentLength = Long.parseLong(line.substring("content-length:".length()).trim());
                }
            }
            for (long read = 0; read < contentLength; read++) {
                if (in.read() == -1) {
                    return false;
                }
            }
            return true;
        }

        void stop() {
            try {
                server.close();
            } catch (IOException ignored) {
                // Already closed; the accept loop ends either way.
            }
            synchronized (accepted) {
                for (Socket connection : accepted) {
                    try {
                        connection.close();
                    } catch (IOException ignored) {
                        // The connection is being discarded; its count is already taken.
                    }
                }
            }
            try {
                thread.join(Duration.ofSeconds(5).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while stopping the counting upstream", e);
            }
            assertThat(thread.isAlive()).as("counting upstream thread still running").isFalse();
        }

        @Override
        public void close() {
            stop();
        }
    }

    /**
     * The configuration {@code application-test.yml} gives the application: the demo client and store values and
     * the flow's local URLs, with the two Box endpoints pointed at the given stand-in.
     */
    private static BoxProperties properties(String tokenUrl, String apiBaseUrl) {
        return properties(tokenUrl, apiBaseUrl, "trust-store");
    }

    /** As {@link #properties(String, String)}, with the trust store at {@code truststorePath}. */
    private static BoxProperties properties(String tokenUrl, String apiBaseUrl, String truststorePath) {
        return new BoxProperties(
                CLIENT_ID,
                CLIENT_SECRET,
                "https://www.box.com/api/oauth2/authorize",
                "https://localhost:8082/authorization",
                REDIRECT_URL,
                tokenUrl,
                apiBaseUrl,
                "https://localhost:8082/authorization?onCompleteRedirectTo=http://localhost:8081/web/loginDone",
                "keystore.jks",
                STORE_PASSWORD,
                STORE_PASSWORD,
                truststorePath,
                STORE_PASSWORD);
    }

    /** A JKS key store at {@code store} holding a new EC key whose certificate names {@code localhost} only. */
    private static void generateLocalhostKey(Path store) throws IOException, InterruptedException {
        keytool("-genkeypair", "-alias", "box", "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "2",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-keystore", store.toString(),
                "-storetype", "JKS", "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD);
    }

    /** How long {@link #keytool} waits for keytool to finish. */
    private static final Duration KEYTOOL_TIMEOUT = Duration.ofMinutes(1);

    /** How long {@link #keytool} waits for a keytool it has killed to end. */
    private static final Duration KEYTOOL_REAP_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Runs the keytool of the JDK running the tests, failing with its output when it does not succeed. Its standard
     * output and error go to a file under {@link #tlsDir} rather than a pipe, so keytool never blocks on a full pipe
     * and the {@link #KEYTOOL_TIMEOUT} wait starts at once. A keytool still running when that wait ends is killed and
     * reaped, for up to {@link #KEYTOOL_REAP_TIMEOUT}, and the test fails with its output; an interrupted wait kills
     * and reaps it the same way, then restores the interrupt and rethrows.
     */
    private static void keytool(String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(Arrays.asList(arguments));
        Path outputFile = Files.createTempFile(tlsDir, "keytool-", ".log");
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
                .start();
        boolean exited;
        boolean reaped = true;
        try {
            exited = process.waitFor(KEYTOOL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                reaped = destroyAndReap(process);
            }
        } catch (InterruptedException interrupted) {
            // Interrupted waiting for keytool, or for the killed keytool to end: it is killed and reaped all the same.
            try {
                destroyAndReap(process);
            } catch (InterruptedException again) {
                interrupted.addSuppressed(again);
            }
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        String outcome = exited
                ? "exit code " + process.exitValue()
                : "still running after " + KEYTOOL_TIMEOUT.toSeconds() + " s, killed" + (reaped ? "" : ", not reaped");
        // Decoded leniently, as Files.readString is not, so output that is not UTF-8 still reaches the message.
        String output = new String(Files.readAllBytes(outputFile), UTF_8);
        assertThat(exited && process.exitValue() == 0).as("keytool %s (%s): %s", arguments[0], outcome, output)
                .isTrue();
    }

    /**
     * Kills {@code process} and waits up to {@link #KEYTOOL_REAP_TIMEOUT} for it to end.
     *
     * @return whether it ended within that time
     */
    private static boolean destroyAndReap(Process process) throws InterruptedException {
        process.destroyForcibly();
        return process.waitFor(KEYTOOL_REAP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** A started HTTPS-only WireMock server presenting the key in {@code keyStore}. */
    private static WireMockServer httpsStandIn(Path keyStore) {
        WireMockServer server = new WireMockServer(wireMockConfig()
                .httpDisabled(true)
                .dynamicHttpsPort()
                .keystorePath(keyStore.toString())
                .keystoreType("JKS")
                .keystorePassword(STORE_PASSWORD)
                .keyManagerPassword(STORE_PASSWORD));
        server.start();
        return server;
    }

    /** A server-side TLS context presenting the key in {@code keyStore}. */
    private static SSLContext serverContext(Path keyStore) throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("JKS");
        try (InputStream in = Files.newInputStream(keyStore)) {
            store.load(in, STORE_PASSWORD.toCharArray());
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(store, STORE_PASSWORD.toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, null);
        return context;
    }

    /**
     * A socket bound to a free port on every local address that never listens or connects: a connection to its port
     * is refused while it is open, and with SO_REUSEADDR off no other socket can bind that port meanwhile.
     */
    private static Socket reserveRefusingPort() throws IOException {
        Socket socket = new Socket();
        try {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(0));
            return socket;
        } catch (IOException | RuntimeException ex) {
            try {
                socket.close();
            } catch (IOException closeFailure) {
                ex.addSuppressed(closeFailure);
            }
            throw ex;
        }
    }

    /** A value made at run time, unique to the call, that a test looks for where it must never appear. */
    private static String sentinel() {
        return "sentinel" + UUID.randomUUID().toString().replace("-", "");
    }

    /** The throwable and every cause beneath it, each once, so a cyclic cause chain cannot loop. */
    private static List<Throwable> causeChain(Throwable top) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Throwable> chain = new ArrayList<>();
        for (Throwable current = top; current != null && seen.add(current); current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }
}
