/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.sun.net.httpserver.HttpServer;

import com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.config.TlsProperties;

/**
 * The contract of {@link BoxOAuthClient}, the {@code HTTP_Request_Configuration} with its
 * {@code oauth2:client-credentials-grant-type} and the GET {@code path="/"} of the {@code /oauth} flow in
 * {@code oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml} (lines 12-28
 * and 34), against a WireMock stand-in for both {@code https://app.box.com/api/oauth2/token} and
 * {@code app.box.com:443/api/oauth2/authorize}: the token is requested once with the client-credentials grant and an
 * HTTP Basic client authentication, cached and sent as a Bearer header whatever {@code token_type} says; a 401 or
 * 403 re-runs the grant and retries the GET exactly once; any other status of 400 or above fails at once with
 * Mule's {@code Response code <code> mapped as failure.}; a failed or token-less token response fails before the
 * GET; redirects are followed, the access token only within the origin, a 307 or 308 of the token request
 * re-sends the form, and one after a 301, 302 or 303 of the token request is followed as a GET without it; neither
 * request is sent twice after a reset, a connection closed before the response, a refused connect or the deadline,
 * which a response not complete within the timeout fails at, releasing the token lock; concurrent first calls share
 * one token request and are not queued; and an https base URL whose TLS stores cannot be opened fails before any
 * request is sent.
 */
class BoxOAuthClientTest {

    private static final String TOKEN_PATH = "/api/oauth2/token";
    private static final String BASE_PATH = "/api/oauth2/authorize";
    /** The GET of line 34, {@code path="/"}, appended to the request configuration's basePath. */
    private static final String AUTHORIZE_PATH = BASE_PATH + "/";

    private static final String CLIENT_ID = "id";
    private static final String CLIENT_SECRET = "secret";

    /** Every client a test builds, closed after it. */
    private static final List<BoxOAuthClient> CLIENTS = new CopyOnWriteArrayList<>();

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @BeforeEach
    void resetUpstream() {
        wm.resetAll();
    }

    @AfterEach
    void closeClients() {
        CLIENTS.forEach(BoxOAuthClient::close);
        CLIENTS.clear();
    }

    @Test
    void tokenRequestUsesClientCredentialsGrant() {
        stubToken();
        wm.stubFor(get(urlEqualTo(AUTHORIZE_PATH)).willReturn(aResponse().withStatus(200).withBody("authorized")));
        BoxOAuthClient client = httpClient();
        // The token is requested lazily, so construction contacts no upstream.
        assertThat(wm.getAllServeEvents()).isEmpty();

        ResponseEntity<byte[]> response = client.authorize();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("authorized".getBytes(UTF_8));
        // Mule 3.8 defaults encodeClientCredentialsInBody to false: the credentials travel in the Basic header only.
        String basic = "Basic " + Base64.getEncoder().encodeToString("id:secret".getBytes(UTF_8));
        wm.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withHeader("Authorization", equalTo(basic))
                .withRequestBody(containing("grant_type=client_credentials"))
                .withRequestBody(notContaining("client_secret")));
        wm.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        wm.verify(1, getRequestedFor(urlEqualTo(AUTHORIZE_PATH)));
    }

    @Test
    void cachedTokenIsReused() {
        stubToken();
        wm.stubFor(get(urlEqualTo(AUTHORIZE_PATH)).willReturn(aResponse().withStatus(200).withBody("authorized")));
        BoxOAuthClient client = httpClient();

        client.authorize();
        client.authorize();

        wm.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        wm.verify(2, getRequestedFor(urlEqualTo(AUTHORIZE_PATH)));
        wm.verify(2, getRequestedFor(urlEqualTo(AUTHORIZE_PATH)).withHeader("Authorization", equalTo("Bearer tok-1")));
    }

    /** Mule's default {@code refreshTokenWhen}: the resource server rejected the access token. */
    @ParameterizedTest(name = "{0} refreshes the token and retries once")
    @ValueSource(ints = {401, 403})
    void rejectedTokenIsRefreshedAndRetriedOnce(int rejection) {
        wm.stubFor(post(urlEqualTo(TOKEN_PATH)).inScenario("token")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(tokenResponse("tok-1"))
                .willSetStateTo("second"));
        wm.stubFor(post(urlEqualTo(TOKEN_PATH)).inScenario("token")
                .whenScenarioStateIs("second")
                .willReturn(tokenResponse("tok-2")));
        wm.stubFor(get(urlEqualTo(AUTHORIZE_PATH)).inScenario("authz")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(rejection))
                .willSetStateTo("ok"));
        wm.stubFor(get(urlEqualTo(AUTHORIZE_PATH)).inScenario("authz")
                .whenScenarioStateIs("ok")
                .willReturn(aResponse().withStatus(200).withBody("granted")));

        ResponseEntity<byte[]> response = httpClient().authorize();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("granted".getBytes(UTF_8));
        wm.verify(2, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        wm.verify(2, getRequestedFor(urlEqualTo(AUTHORIZE_PATH)));
        List<LoggedRequest> gets = wm.findAll(getRequestedFor(urlEqualTo(AUTHORIZE_PATH)));
        assertThat(gets).extracting(logged -> logged.getHeader("Authorization"))
                .containsExactly("Bearer tok-1", "Bearer tok-2");
    }

    @ParameterizedTest(name = "a second {0} fails without a further retry")
    @ValueSource(ints = {401, 403})
    void secondRejectionFailsWithoutFurtherRetry(int rejection) {
        stubToken();
        wm.stubFor(get(urlEqualTo(AUTHORIZE_PATH)).willReturn(aResponse().withStatus(rejection)));
        BoxOAuthClient client = httpClient();

        assertThatThrownBy(client::authorize)
                .isInstanceOfSatisfying(RestClientResponseException.class, failure -> {
                    assertThat(failure).hasMessage("Response code " + rejection + " mapped as failure.");
                    assertThat(failure.getStatusCode().value()).isEqualTo(rejection);
                });

        wm.verify(2, getRequestedFor(urlEqualTo(AUTHORIZE_PATH)));
        wm.verify(2, postRequestedFor(urlEqualTo(TOKEN_PATH)));
    }

    @ParameterizedTest(name = "{0} fails at once")
    @ValueSource(ints = {400, 404, 500})
    void failureStatusOtherThanRejectionIsNotRetried(int status) {
        stubToken();
        wm.stubFor(get(urlEqualTo(AUTHORIZE_PATH)).willReturn(aResponse().withStatus(status).withBody("boom")));
        BoxOAuthClient client = httpClient();

        assertThatThrownBy(client::authorize)
                .isInstanceOfSatisfying(RestClientResponseException.class, failure -> {
                    assertThat(failure).hasMessage("Response code " + status + " mapped as failure.");
                    assertThat(failure.getStatusCode().value()).isEqualTo(status);
                    assertThat(failure.getResponseBodyAsString(UTF_8)).isEqualTo("boom");
                });

        wm.verify(1, getRequestedFor(urlEqualTo(AUTHORIZE_PATH)));
        wm.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
    }

    @ParameterizedTest(name = "token endpoint {0} fails before the GET")
    @ValueSource(ints = {401, 500})
    void tokenEndpointErrorThrowsIllegalState(int status) {
        wm.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(status)));
        wm.stubFor(get(urlEqualTo(AUTHORIZE_PATH)).willReturn(aResponse().withStatus(200)));
        BoxOAuthClient client = httpClient();

        assertThatThrownBy(client::authorize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(wm.baseUrl() + TOKEN_PATH)
                .hasMessageContaining(String.valueOf(status));

        wm.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        wm.verify(0, getRequestedFor(urlEqualTo(AUTHORIZE_PATH)));
    }

    @ParameterizedTest(name = "token response {0} carries no access token")
    @ValueSource(strings = {
            "{\"token_type\":\"bearer\",\"expires_in\":3600}",
            "{\"access_token\":\"\",\"token_type\":\"bearer\"}",
            "{\"access_token\":null,\"token_type\":\"bearer\"}",
            "not json"})
    void tokenWithoutAccessTokenThrowsIllegalState(String tokenBody) {
        wm.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(tokenBody)));
        wm.stubFor(get(urlEqualTo(AUTHORIZE_PATH)).willReturn(aResponse().withStatus(200)));
        BoxOAuthClient client = httpClient();

        assertThatThrownBy(client::authorize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(wm.baseUrl() + TOKEN_PATH)
                .hasMessageContaining("access_token");

        wm.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        wm.verify(0, getRequestedFor(urlEqualTo(AUTHORIZE_PATH)));
    }

    /** Line 18 extracts {@code token_type} into the token store; the resource request is still a Bearer call. */
    @ParameterizedTest(name = "token_type {0} still sends Bearer")
    @ValueSource(strings = {"bearer", "Bearer", "mac"})
    void tokenTypeInResponseStillSendsBearer(String tokenType) {
        wm.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"tok-1\",\"token_type\":\"" + tokenType + "\",\"expires_in\":3600}")));
        wm.stubFor(get(urlEqualTo(AUTHORIZE_PATH)).willReturn(aResponse().withStatus(200).withBody("authorized")));

        httpClient().authorize();

        wm.verify(1, getRequestedFor(urlEqualTo(AUTHORIZE_PATH)));
        wm.verify(1, getRequestedFor(urlEqualTo(AUTHORIZE_PATH)).withHeader("Authorization", equalTo("Bearer tok-1")));
    }

    @Test
    void redirectIsFollowed() {
        stubToken();
        wm.stubFor(get(urlEqualTo(AUTHORIZE_PATH)).willReturn(aResponse()
                .withStatus(302)
                .withHeader("Location", wm.baseUrl() + "/elsewhere")));
        wm.stubFor(get(urlEqualTo("/elsewhere")).willReturn(aResponse().withStatus(200).withBody("moved-body")));

        ResponseEntity<byte[]> response = httpClient().authorize();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("moved-body".getBytes(UTF_8));
        wm.verify(1, getRequestedFor(urlEqualTo(AUTHORIZE_PATH)));
        wm.verify(1, getRequestedFor(urlEqualTo("/elsewhere")));
        wm.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
    }

    /** A redirect to another origin carries no access token; one within the origin keeps it. */
    @ParameterizedTest(name = "redirect to {0} keeps the access token: {1}")
    @CsvSource({"localhost, true", "127.0.0.1, false"})
    void redirectCarriesTheAccessTokenOnlyWithinTheOrigin(String host, boolean tokenKept) {
        stubToken();
        wm.stubFor(get(urlEqualTo(AUTHORIZE_PATH)).willReturn(aResponse()
                .withStatus(302)
                .withHeader("Location", "http://" + host + ":" + wm.getPort() + "/elsewhere")));
        wm.stubFor(get(urlEqualTo("/elsewhere")).willReturn(aResponse().withStatus(200).withBody("moved-body")));

        ResponseEntity<byte[]> response = client("http://localhost:" + wm.getPort(), Duration.ofSeconds(2)).authorize();

        assertThat(response.getBody()).isEqualTo("moved-body".getBytes(UTF_8));
        assertThat(wm.findAll(getRequestedFor(urlEqualTo("/elsewhere")))).singleElement()
                .satisfies(moved -> assertThat(moved.getHeader("Authorization"))
                        .isEqualTo(tokenKept ? "Bearer tok-1" : null));
    }

    /** A 307 or 308 of the token request re-sends the form and the client credentials, within the origin. */
    @ParameterizedTest(name = "token endpoint {0} re-sends the form")
    @ValueSource(ints = {307, 308})
    void tokenRedirectResendsTheForm(int status) {
        wm.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Location", TOKEN_PATH + "/moved")));
        wm.stubFor(post(urlEqualTo(TOKEN_PATH + "/moved")).willReturn(tokenResponse("tok-1")));
        wm.stubFor(get(urlEqualTo(AUTHORIZE_PATH)).willReturn(aResponse().withStatus(200).withBody("authorized")));

        httpClient().authorize();

        String basic = "Basic " + Base64.getEncoder().encodeToString("id:secret".getBytes(UTF_8));
        wm.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH + "/moved"))
                .withHeader("Authorization", equalTo(basic))
                .withHeader("Content-Type", equalTo("application/x-www-form-urlencoded"))
                .withRequestBody(equalTo("grant_type=client_credentials")));
        wm.verify(1, getRequestedFor(urlEqualTo(AUTHORIZE_PATH)).withHeader("Authorization", equalTo("Bearer tok-1")));
    }

    /**
     * Once a redirect of the token request has continued as a GET, a later 307 or 308 is followed as a GET without
     * the body too, as {@code java.net.http} and the Mule requester follow it; httpclient5 alone would re-send the
     * POST with the form. Each hop is sent once and keeps the request's headers, as the redirects stay on the same
     * origin, and the token is read from the final response.
     */
    @ParameterizedTest(name = "token endpoint {0}, then {1}")
    @CsvSource({"302, 307", "301, 308", "303, 307"})
    void tokenRedirectAfterOneContinuedAsAGetIsFollowedAsABodylessGet(int first, int second) {
        // Any method matches, so a hop re-sent as a POST is answered too and shows in the journal.
        wm.stubFor(any(urlEqualTo(TOKEN_PATH)).willReturn(aResponse()
                .withStatus(first)
                .withHeader("Location", TOKEN_PATH + "/second")));
        wm.stubFor(any(urlEqualTo(TOKEN_PATH + "/second")).willReturn(aResponse()
                .withStatus(second)
                .withHeader("Location", TOKEN_PATH + "/final")));
        wm.stubFor(any(urlEqualTo(TOKEN_PATH + "/final")).willReturn(tokenResponse("tok-final")));
        wm.stubFor(get(urlEqualTo(AUTHORIZE_PATH)).willReturn(aResponse().withStatus(200).withBody("authorized")));

        ResponseEntity<byte[]> response = httpClient().authorize();

        assertThat(response.getBody()).isEqualTo("authorized".getBytes(UTF_8));
        assertThat(upstreamRequests()).containsExactly("POST " + TOKEN_PATH, "GET " + TOKEN_PATH + "/second",
                "GET " + TOKEN_PATH + "/final", "GET " + AUTHORIZE_PATH);
        assertThat(wm.findAll(postRequestedFor(urlEqualTo(TOKEN_PATH)))).singleElement()
                .satisfies(logged -> assertThat(logged.getBodyAsString()).isEqualTo("grant_type=client_credentials"));
        String basic = "Basic " + Base64.getEncoder().encodeToString("id:secret".getBytes(UTF_8));
        assertThat(wm.findAll(getRequestedFor(urlMatching(TOKEN_PATH + "/(second|final)")))).hasSize(2)
                .allSatisfy(logged -> {
                    assertThat(logged.getBody()).isEmpty();
                    assertThat(logged.containsHeader("Content-Length")).isFalse();
                    assertThat(logged.containsHeader("Transfer-Encoding")).isFalse();
                    assertThat(logged.getHeader("Authorization")).isEqualTo(basic);
                    assertThat(logged.getHeader("Content-Type")).isEqualTo("application/x-www-form-urlencoded");
                });
        wm.verify(1, getRequestedFor(urlEqualTo(AUTHORIZE_PATH))
                .withHeader("Authorization", equalTo("Bearer tok-final")));
    }

    /**
     * A connection reset, or a connection closed before any response byte, ends the call with the one request the
     * upstream received: the token POST is not re-sent, and neither is the idempotent GET.
     */
    @ParameterizedTest(name = "{0} with {1}")
    @CsvSource({
            "TOKEN, CONNECTION_RESET_BY_PEER", "TOKEN, EMPTY_RESPONSE",
            "AUTHORIZE, CONNECTION_RESET_BY_PEER", "AUTHORIZE, EMPTY_RESPONSE"})
    void abruptlyClosedConnectionIsNotRetried(Target target, Fault fault) {
        stubDisturbed(target, aResponse().withFault(fault));

        Throwable thrown = catchThrowable(httpClient()::authorize);

        assertThat(thrown).isInstanceOf(ResourceAccessException.class);
        wm.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        wm.verify(target == Target.TOKEN ? 0 : 1, getRequestedFor(urlEqualTo(AUTHORIZE_PATH)));
        assertThat(wm.getAllServeEvents()).hasSize(target == Target.TOKEN ? 1 : 2);
    }

    /**
     * A refused connection cannot be counted at the server, so the connect failure itself shows the attempts: the
     * transport names every address it tried, and nothing else was attempted before the call failed.
     */
    @ParameterizedTest
    @EnumSource(Target.class)
    void refusedConnectionSurfacesConnectExceptionAfterOneAttempt(Target target) throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        String refusedUrl = "http://localhost:" + port;
        stubToken();
        wm.stubFor(get(urlEqualTo(AUTHORIZE_PATH)).willReturn(aResponse().withStatus(200)));
        BoxOAuthClient client = target == Target.TOKEN
                ? track(new BoxOAuthClient(refusedUrl + TOKEN_PATH, CLIENT_ID, CLIENT_SECRET, wm.baseUrl(), BASE_PATH,
                        tls(""), Duration.ofSeconds(2)))
                : client(refusedUrl, Duration.ofSeconds(2));
        String resolved = "[" + InetAddress.getByName("localhost") + "]";

        Throwable thrown = catchThrowable(client::authorize);

        assertThat(thrown).isInstanceOf(ResourceAccessException.class);
        assertThat(causeChain(thrown).filter(ConnectException.class::isInstance).toList()).singleElement()
                .satisfies(refused -> {
                    assertThat(refused.getSuppressed()).isEmpty();
                    assertThat(refused.getMessage()).contains(resolved);
                });
        wm.verify(target == Target.TOKEN ? 0 : 1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        wm.verify(0, getRequestedFor(urlEqualTo(AUTHORIZE_PATH)));
    }

    /**
     * The one deadline bounds each request's whole response: a head held back past it, and a body still dribbling
     * in when it passes, both end the call at the deadline, with the one request sent.
     */
    @ParameterizedTest(name = "{0} {1} not complete")
    @CsvSource({"TOKEN, HEAD", "TOKEN, BODY", "AUTHORIZE, HEAD", "AUTHORIZE, BODY"})
    void responseNotCompleteWithinTheTimeoutFailsAtTheDeadline(Target target, Stall stall) {
        ResponseDefinitionBuilder late = target == Target.TOKEN
                ? tokenResponse("tok-1")
                : aResponse().withStatus(200).withBody("a resource body that arrives far too slowly");
        stubDisturbed(target, stall == Stall.HEAD
                ? late.withFixedDelay(3000)
                : late.withChunkedDribbleDelay(30, 3000));
        BoxOAuthClient client = client(wm.baseUrl(), Duration.ofMillis(300));

        long start = System.nanoTime();
        // The watchdog is independent of the client's own timeout, so a read that never ends fails the test here.
        Throwable thrown = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> catchThrowable(client::authorize));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(thrown).isInstanceOf(ResourceAccessException.class)
                .hasMessageEndingWith(": Response not received within 300 ms");
        assertThat(causeChain(thrown)).anyMatch(HttpTimeoutException.class::isInstance);
        assertThat(elapsed).isBetween(Duration.ofMillis(250), Duration.ofMillis(2000));
        wm.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        wm.verify(target == Target.TOKEN ? 0 : 1, getRequestedFor(urlEqualTo(AUTHORIZE_PATH)));
    }

    /**
     * A stalled token response holds the token lock only until its deadline: of two concurrent first calls, the one
     * whose token request stalls fails at the deadline and leaves no token, and the one waiting for the lock then
     * requests its own token and succeeds, both long before the stalled response would have arrived.
     */
    @Test
    void stalledTokenResponseReleasesTheTokenLockAtTheDeadline() throws InterruptedException {
        wm.stubFor(post(urlEqualTo(TOKEN_PATH)).inScenario("token")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(tokenResponse("tok-1").withFixedDelay(5000))
                .willSetStateTo("answering"));
        wm.stubFor(post(urlEqualTo(TOKEN_PATH)).inScenario("token")
                .whenScenarioStateIs("answering")
                .willReturn(tokenResponse("tok-2")));
        wm.stubFor(get(urlEqualTo(AUTHORIZE_PATH)).willReturn(aResponse().withStatus(200).withBody("authorized")));
        BoxOAuthClient client = client(wm.baseUrl(), Duration.ofMillis(300));
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            long start = System.nanoTime();
            List<Future<ResponseEntity<byte[]>>> calls = List.of(callers.submit(client::authorize),
                    callers.submit(client::authorize));
            List<Throwable> failures = new ArrayList<>();
            List<ResponseEntity<byte[]>> successes = new ArrayList<>();
            for (Future<ResponseEntity<byte[]>> call : calls) {
                try {
                    successes.add(call.get(10, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                } catch (TimeoutException e) {
                    failures.add(e);
                }
            }
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(failures).singleElement().isInstanceOf(ResourceAccessException.class)
                    .satisfies(timedOut -> assertThat(timedOut)
                            .hasMessageEndingWith(": Response not received within 300 ms"));
            assertThat(successes).singleElement()
                    .satisfies(success -> assertThat(success.getBody()).isEqualTo("authorized".getBytes(UTF_8)));
            assertThat(elapsed).isLessThan(Duration.ofMillis(3000));
            wm.verify(2, postRequestedFor(urlEqualTo(TOKEN_PATH)));
            wm.verify(1, getRequestedFor(urlEqualTo(AUTHORIZE_PATH))
                    .withHeader("Authorization", equalTo("Bearer tok-2")));
        } finally {
            callers.shutdownNow();
        }
    }

    /**
     * Ten concurrent first calls share one token request, and their GETs reach Box together: Box answers each GET
     * only once all ten have arrived, so a connection pool that queued some of them would leave the others waiting
     * until it answers 503.
     */
    @Test
    void concurrentFirstCallsShareOneTokenRequestAndAreNotQueued() throws Exception {
        int callers = 10;
        AtomicInteger tokenRequests = new AtomicInteger();
        CountDownLatch allArrived = new CountDownLatch(callers);
        ExecutorService upstreamThreads = Executors.newFixedThreadPool(callers + 1);
        ExecutorService callerThreads = Executors.newFixedThreadPool(callers);
        HttpServer upstream = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        upstream.setExecutor(upstreamThreads);
        upstream.createContext(TOKEN_PATH, exchange -> {
            tokenRequests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] token = "{\"access_token\":\"tok-1\",\"token_type\":\"bearer\"}".getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, token.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(token);
            }
        });
        upstream.createContext(AUTHORIZE_PATH, exchange -> {
            allArrived.countDown();
            boolean together;
            try {
                together = allArrived.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                together = false;
            }
            byte[] reply = (together ? "together" : "queued").getBytes(UTF_8);
            exchange.sendResponseHeaders(together ? 200 : 503, reply.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(reply);
            }
        });
        upstream.start();
        try {
            String base = "http://127.0.0.1:" + upstream.getAddress().getPort();
            BoxOAuthClient client = track(new BoxOAuthClient(base + TOKEN_PATH, CLIENT_ID, CLIENT_SECRET, base,
                    BASE_PATH, tls(""), Duration.ofSeconds(8)));
            List<Future<ResponseEntity<byte[]>>> calls = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                calls.add(callerThreads.submit(client::authorize));
            }
            for (Future<ResponseEntity<byte[]>> call : calls) {
                ResponseEntity<byte[]> response = call.get(20, TimeUnit.SECONDS);
                assertThat(response.getStatusCode().value()).isEqualTo(200);
                assertThat(response.getBody()).isEqualTo("together".getBytes(UTF_8));
            }
            assertThat(tokenRequests).hasValue(1);
        } finally {
            upstream.stop(0);
            upstreamThreads.shutdownNow();
            callerThreads.shutdownNow();
        }
    }

    @Test
    void httpsWithBlankTlsPasswordsFailsBeforeAuthorize() {
        stubToken();
        wm.stubFor(get(urlEqualTo(AUTHORIZE_PATH)).willReturn(aResponse().withStatus(200)));
        BoxOAuthClient client = client("https://127.0.0.1:" + wm.getPort(), Duration.ofSeconds(2));

        assertThatThrownBy(client::authorize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("keystore.password");

        wm.verify(0, getRequestedFor(urlEqualTo(AUTHORIZE_PATH)));
        // The TLS context is built before the token request, so no client secret leaves on a broken TLS setup.
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    /** The carried stores of {@code src/main/resources}, with {@code password} in all three password slots. */
    private static TlsProperties tls(String password) {
        return new TlsProperties("keystore.jks", password, password, "trust-store", password);
    }

    private static BoxOAuthClient client(String baseUrl, Duration timeout) {
        return track(new BoxOAuthClient(wm.baseUrl() + TOKEN_PATH, CLIENT_ID, CLIENT_SECRET, baseUrl, BASE_PATH,
                tls(""), timeout));
    }

    /** Registers {@code client} to be closed after the test. */
    private static BoxOAuthClient track(BoxOAuthClient client) {
        CLIENTS.add(client);
        return client;
    }

    /** Stubs both requests, the {@code target} one with {@code disturbed} and the other with a success. */
    private static void stubDisturbed(Target target, ResponseDefinitionBuilder disturbed) {
        if (target == Target.TOKEN) {
            wm.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(disturbed));
            wm.stubFor(get(urlEqualTo(AUTHORIZE_PATH)).willReturn(aResponse().withStatus(200)));
        } else {
            stubToken();
            wm.stubFor(get(urlEqualTo(AUTHORIZE_PATH)).willReturn(disturbed));
        }
    }

    /** The request of {@link BoxOAuthClient#authorize()} a test disturbs. */
    enum Target {
        /** The client-credentials token POST. */
        TOKEN,
        /** The Box GET of line 34. */
        AUTHORIZE
    }

    /** The part of a response that has not arrived when the deadline passes. */
    enum Stall {
        /** The head, held back by a fixed delay. */
        HEAD,
        /** The body, dribbled in chunks over longer than the timeout. */
        BODY
    }

    /** An http base URL, so the resource request never reads the TLS stores. */
    private static BoxOAuthClient httpClient() {
        return client(wm.baseUrl(), Duration.ofSeconds(2));
    }

    private static void stubToken() {
        wm.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(tokenResponse("tok-1")));
    }

    private static ResponseDefinitionBuilder tokenResponse(String accessToken) {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"" + accessToken + "\",\"token_type\":\"bearer\",\"expires_in\":3600}");
    }

    private static Stream<Throwable> causeChain(Throwable thrown) {
        return Stream.iterate(thrown, Objects::nonNull, Throwable::getCause);
    }

    /** Every request the WireMock upstream received, oldest first, as {@code <method> <url>}. */
    private static List<String> upstreamRequests() {
        List<String> requests = new ArrayList<>();
        // The journal lists the most recent request first.
        wm.getAllServeEvents().forEach(event ->
                requests.add(0, event.getRequest().getMethod().getName() + " " + event.getRequest().getUrl()));
        return requests;
    }
}
