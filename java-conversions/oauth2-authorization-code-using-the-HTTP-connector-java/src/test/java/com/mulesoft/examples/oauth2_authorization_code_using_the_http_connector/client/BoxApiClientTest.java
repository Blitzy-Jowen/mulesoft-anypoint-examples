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
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLEncoder;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.client.BoxApiClient.RawResponse;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.config.BoxProperties;

/**
 * The contract of {@link BoxApiClient}, the Box requests of
 * {@code oauth2-authorization-code-using-the-HTTP-connector/src/main/app/http-authorization-code-web.xml} (the
 * token request of line 13 and the {@code GET /search} of lines 40-45), against a WireMock stand-in for Box: the
 * token forms are sent in Mule's field order and encoding, the search carries the bearer token, every status is
 * returned rather than thrown, an I/O failure surfaces as a {@link ResourceAccessException}, a body still
 * arriving when the 10 s read timeout ends fails once with an {@link HttpTimeoutException} cause, the DEBUG log
 * shows only scheme, host, port and path, and a configured URL that could carry credentials, or that the client
 * cannot use, is rejected before any request with a message naming only its key.
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

    @BeforeEach
    void setUp() {
        wm.resetAll();
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
        wm.verify(1, getRequestedFor(urlEqualTo(SEARCH_PATH))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer " + ACCESS_TOKEN))
                .withoutHeader(HttpHeaders.CONTENT_TYPE));
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
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        String closed = "http://127.0.0.1:" + port;
        BoxApiClient refusedClient = new BoxApiClient(properties(closed + TOKEN_PATH, closed + "/2.0"));

        Throwable thrown = catchThrowable(() -> operation.callOn(refusedClient));

        assertThat(thrown).isInstanceOf(ResourceAccessException.class);
        assertThat(causeChain(thrown)).anyMatch(ConnectException.class::isInstance);
    }

    @ParameterizedTest
    @EnumSource(Fault.class)
    void transportFaultSurfacesAsResourceAccessException(Fault fault) {
        wm.stubFor(get(urlEqualTo(SEARCH_PATH)).willReturn(aResponse().withFault(fault)));

        Throwable thrown = catchThrowable(() -> client.search(ACCESS_TOKEN));

        assertThat(thrown).isInstanceOf(ResourceAccessException.class);
        assertThat(causeChain(thrown)).anyMatch(IOException.class::isInstance);
    }

    @Test
    void debugLogShowsOnlySchemeHostPortAndPath() {
        String sentinel = sentinel();
        String tokenPathAndQuery = TOKEN_PATH + "?tenant=" + sentinel;
        BoxApiClient sentinelClient =
                new BoxApiClient(properties(wm.baseUrl() + tokenPathAndQuery, wm.baseUrl() + "/2.0/"));
        wm.stubFor(post(urlEqualTo(tokenPathAndQuery)).willReturn(ok()));
        wm.stubFor(get(urlEqualTo(SEARCH_PATH)).willReturn(ok()));

        assertThat(sentinelClient.requestToken(CODE).status()).isEqualTo(200);
        assertThat(sentinelClient.search(sentinel).status()).isEqualTo(200);

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

        Throwable thrown = catchThrowable(() -> operation.callOn(new BoxApiClient(properties)));

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage(key + " " + problem).hasNoCause();
        assertThat(causeChain(thrown)).extracting(Throwable::toString).noneMatch(text -> text.contains(sentinel));
        assertThat(wm.getAllServeEvents()).isEmpty();
        assertThat(clientLog.list).isEmpty();
    }

    // BoxApiClient has one constructor and a fixed 10 s READ_TIMEOUT, so this case waits out a real stalled body.
    @ParameterizedTest
    @EnumSource(Operation.class)
    void bodyStalledAfterHeadersTimesOutOnceWithinTheReadTimeout(Operation operation)
            throws IOException, InterruptedException {
        // A raw socket rather than WireMock, whose dribble delay also holds back the headers: the response head
        // arrives at once and the body stalls after 10 of its 1000 bytes, so only a deadline on the body read,
        // not the request factory's header timeout, can end the call.
        AtomicInteger requests = new AtomicInteger();
        List<Socket> connections = Collections.synchronizedList(new ArrayList<>());
        List<IOException> closeFailures = new ArrayList<>();
        ServerSocket upstream = new ServerSocket(0);
        Thread server = new Thread(() -> sendHeadersThenStall(upstream, requests, connections),
                "stalled-box-upstream");
        server.setDaemon(true);
        try {
            server.start();
            String stalled = "http://127.0.0.1:" + upstream.getLocalPort();
            BoxApiClient stalledClient = new BoxApiClient(properties(stalled + TOKEN_PATH, stalled + "/2.0"));

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
        } finally {
            // The one owner of the sockets: runs whether the call returned, the watchdog gave up on it or the
            // server thread failed to start. Closing the listening socket ends the accept loop, and closing each
            // accepted connection ends any client read still blocked on it. Every close is attempted whatever the
            // others do; failures are reported only after the cleanup, so none of them replaces the failure that
            // brought the test here.
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
            // A thread that never started is not alive, so this returns at once if start() failed.
            server.join(Duration.ofSeconds(5).toMillis());
        }
        assertThat(closeFailures).as("failures closing the stalled upstream's sockets").isEmpty();
        assertThat(server.isAlive()).as("stalled upstream thread still running").isFalse();
    }

    /**
     * Accepts connections until {@code upstream} is closed. On each it reads the request head, counts it, sends a
     * complete response head announcing 1000 body bytes and then only the first 10, and leaves the connection
     * open, so the body never completes.
     *
     * <p>Each accepted connection is added to {@code connections}, a synchronized list the caller closes after
     * closing {@code upstream}. A connection accepted while {@code upstream} was being closed may join the list
     * after the caller has closed its contents, so this loop closes that one itself and stops.
     */
    private static void sendHeadersThenStall(ServerSocket upstream, AtomicInteger requests,
                                             List<Socket> connections) {
        try {
            while (!upstream.isClosed()) {
                Socket connection = upstream.accept();
                connections.add(connection);
                if (upstream.isClosed()) {
                    connection.close();
                    return;
                }
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(connection.getInputStream(), US_ASCII));
                String line;
                do {
                    line = reader.readLine();
                } while (line != null && !line.isEmpty());
                requests.incrementAndGet();
                OutputStream out = connection.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1000\r\n\r\n"
                        + "0123456789").getBytes(US_ASCII));
                out.flush();
            }
        } catch (IOException closed) {
            // The test closed the listening socket, or the client dropped the connection: nothing more to serve.
        }
    }

    /**
     * The configuration {@code application-test.yml} gives the application: the demo client and store values and
     * the flow's local URLs, with the two Box endpoints pointed at the given stand-in.
     */
    private static BoxProperties properties(String tokenUrl, String apiBaseUrl) {
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
                "trust-store",
                STORE_PASSWORD);
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
