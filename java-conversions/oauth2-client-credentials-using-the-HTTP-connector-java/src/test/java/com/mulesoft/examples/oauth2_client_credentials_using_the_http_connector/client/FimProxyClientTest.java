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
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.request;
import static com.github.tomakehurst.wiremock.client.WireMock.requestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * The contract of {@link FimProxyClient}, the {@code http:request} of the {@code /FIM/*} flow in
 * {@code oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml} (lines
 * 29 and 41), against a WireMock stand-in for {@code https://tfim01.demos.ibm.com:443}: the inbound method and
 * raw request URI reach the upstream unchanged, a body and Content-Type are sent only when supplied, for TRACE and
 * extension methods too, a success comes back with its status, headers and body, a status of 400 or above fails
 * once with Mule's {@code Response code <code> mapped as failure.}, redirects are followed as {@code http:request}
 * follows them by default, every request is sent exactly once whatever ends it (a reset, a connection closed
 * before the response, a refused connect or the deadline), a response whose head or body has not fully arrived when
 * the response timeout ends fails with an {@link HttpTimeoutException} cause, and concurrent calls are not queued.
 */
class FimProxyClientTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    /** Holds the generated loopback key store of {@link #loopbackTls()}. */
    @TempDir
    static Path tlsDir;

    /** The TLS context of the https upstream, built on first use; guarded by the class lock. */
    private static SSLContext loopbackTls;

    private FimProxyClient client;

    @BeforeEach
    void setUp() {
        wm.resetAll();
        client = new FimProxyClient(wm.baseUrl(), Duration.ofSeconds(2));
    }

    @AfterEach
    void closeClient() {
        client.close();
    }

    @Test
    void rawUriAndMethodForwardedVerbatim() {
        String rawUri = "/FIM/a%20b/c?x=1&y=%2F";
        wm.stubFor(get(urlEqualTo(rawUri)).willReturn(ok()));

        ResponseEntity<byte[]> response = client.send("GET", rawUri, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNull();
        // urlEqualTo compares the URL as received, so a decoded or re-encoded path or query would not match.
        wm.verify(1, getRequestedFor(urlEqualTo(rawUri)).withoutHeader("Content-Type"));
        List<LoggedRequest> requests = wm.findAll(getRequestedFor(urlEqualTo(rawUri)));
        assertThat(requests).singleElement().satisfies(logged -> assertThat(logged.getBody()).isEmpty());
        assertThat(wm.getAllServeEvents()).hasSize(1);
    }

    /** TRACE and extension methods carry their body as every other method does, as the original requester sent it. */
    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH", "TRACE", "PROPFIND"})
    void postBodyAndContentTypeForwarded(String method) {
        String json = "{\"k\":\"v\"}";
        wm.stubFor(request(method, urlEqualTo("/FIM/submit")).willReturn(ok()));

        ResponseEntity<byte[]> response =
                client.send(method, "/FIM/submit", MediaType.APPLICATION_JSON, json.getBytes(UTF_8));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        wm.verify(1, requestedFor(method, urlEqualTo("/FIM/submit"))
                .withRequestBody(equalTo(json))
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("Content-Length", equalTo(String.valueOf(json.getBytes(UTF_8).length))));
        assertThat(wm.getAllServeEvents()).hasSize(1);
    }

    @Test
    void successfulResponseReturnedWithHeadersAndBody() {
        wm.stubFor(get(urlEqualTo("/FIM/ok")).willReturn(aResponse()
                .withStatus(202)
                .withHeader("X-Upstream", "v")
                .withBody("accepted")));

        ResponseEntity<byte[]> response = client.send("GET", "/FIM/ok", null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getHeaders().getFirst("X-Upstream")).isEqualTo("v");
        assertThat(response.getBody()).isNotNull();
        assertThat(new String(response.getBody(), UTF_8)).isEqualTo("accepted");
        wm.verify(1, getRequestedFor(urlEqualTo("/FIM/ok")));
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 500})
    void errorStatusesThrowMappedAsFailure(int status) {
        wm.stubFor(get(urlEqualTo("/FIM/err")).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "text/plain; charset=UTF-8")
                .withBody("upstream-error")));

        assertThatThrownBy(() -> client.send("GET", "/FIM/err", null, null))
                .isInstanceOf(RestClientResponseException.class)
                .hasMessage("Response code " + status + " mapped as failure.")
                .isInstanceOfSatisfying(RestClientResponseException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(status);
                    assertThat(ex.getResponseBodyAsString()).isEqualTo("upstream-error");
                });
        // Exactly one attempt: a failure status is never retried.
        wm.verify(1, getRequestedFor(urlEqualTo("/FIM/err")));
        assertThat(wm.getAllServeEvents()).hasSize(1);
    }

    @Test
    void redirectIsFollowed() {
        wm.stubFor(get(urlEqualTo("/FIM/old")).willReturn(aResponse()
                .withStatus(302)
                .withHeader("Location", wm.baseUrl() + "/FIM/new")));
        wm.stubFor(get(urlEqualTo("/FIM/new")).willReturn(ok("new-body")));

        ResponseEntity<byte[]> response = client.send("GET", "/FIM/old", null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(new String(response.getBody(), UTF_8)).isEqualTo("new-body");
        wm.verify(1, getRequestedFor(urlEqualTo("/FIM/old")));
        wm.verify(1, getRequestedFor(urlEqualTo("/FIM/new")));
    }

    /**
     * A 307 or 308 re-sends the method and the body, and a 302 or 303 answering a POST continues as a GET without
     * the body; every hop carries the request's Content-Type, as the redirect stays on the same origin.
     */
    @ParameterizedTest(name = "{0} continues as {1}")
    @CsvSource({"307, POST, payload", "308, POST, payload", "302, GET, ''", "303, GET, ''"})
    void redirectOfAPostKeepsOrDropsTheBodyByStatus(int status, String followedMethod, String followedBody) {
        wm.stubFor(request("POST", urlEqualTo("/FIM/old")).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Location", "/FIM/new")));
        wm.stubFor(request(followedMethod, urlEqualTo("/FIM/new")).willReturn(ok("new-body")));

        ResponseEntity<byte[]> response =
                client.send("POST", "/FIM/old", MediaType.TEXT_PLAIN, "payload".getBytes(UTF_8));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("new-body".getBytes(UTF_8));
        wm.verify(1, requestedFor("POST", urlEqualTo("/FIM/old")).withRequestBody(equalTo("payload")));
        List<LoggedRequest> followed = wm.findAll(requestedFor(followedMethod, urlEqualTo("/FIM/new")));
        assertThat(followed).singleElement().satisfies(logged -> {
            assertThat(logged.getBodyAsString()).isEqualTo(followedBody);
            assertThat(logged.getHeader("Content-Type")).startsWith("text/plain");
        });
        assertThat(wm.getAllServeEvents()).hasSize(2);
    }

    /**
     * A 303 answering a HEAD continues as a GET, as {@code java.net.http} and the Mule requester follow it, while a
     * 301, 302, 307 or 308 keeps the HEAD. Each hop is sent once, the followed one without a body and with the
     * request's Content-Type, as the redirect stays on the same origin.
     */
    @ParameterizedTest(name = "HEAD answered {0} continues as {1}")
    @CsvSource(nullValues = "null", value = {
            "303, GET, new-body", "301, HEAD, null", "302, HEAD, null", "307, HEAD, null", "308, HEAD, null"})
    void redirectOfAHeadContinuesAsAGetOnlyAfterA303(int status, String followedMethod, String expectedBody) {
        wm.stubFor(any(urlEqualTo("/FIM/old")).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Location", "/FIM/new")));
        wm.stubFor(any(urlEqualTo("/FIM/new")).willReturn(ok("new-body")));

        ResponseEntity<byte[]> response = client.send("HEAD", "/FIM/old", MediaType.TEXT_PLAIN, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        // The answer to a HEAD has no body; the answer to the GET is returned with its body.
        assertThat(response.getBody()).isEqualTo(expectedBody == null ? null : expectedBody.getBytes(UTF_8));
        assertThat(upstreamRequests()).containsExactly("HEAD /FIM/old", followedMethod + " /FIM/new");
        List<LoggedRequest> followed = wm.findAll(requestedFor(followedMethod, urlEqualTo("/FIM/new")));
        assertThat(followed).singleElement().satisfies(FimProxyClientTest::assertBodylessWithTheRequestContentType);
    }

    /**
     * Once a redirect has continued as a GET, every later redirect of the exchange is followed as a GET without the
     * body, whatever its status, as {@code java.net.http} and the Mule requester follow it; httpclient5 alone would
     * go back to the original method and body. Each hop is sent once, and every one carries the request's
     * Content-Type, as the redirects stay on the same origin.
     */
    @ParameterizedTest(name = "{0} answered {2}, then {3}")
    @CsvSource(nullValues = "null", value = {
            "HEAD, null, 303, 301", "HEAD, null, 303, 302", "HEAD, null, 303, 303", "HEAD, null, 303, 307",
            "HEAD, null, 303, 308", "POST, payload, 302, 307", "POST, payload, 301, 308", "POST, payload, 303, 307",
            "PUT, payload, 303, 302", "TRACE, payload, 303, 308"})
    void everyRedirectAfterOneContinuedAsAGetIsFollowedAsABodylessGet(String method, String body, int first,
                                                                     int second) {
        wm.stubFor(any(urlEqualTo("/FIM/first")).willReturn(aResponse()
                .withStatus(first)
                .withHeader("Location", "/FIM/second")));
        wm.stubFor(any(urlEqualTo("/FIM/second")).willReturn(aResponse()
                .withStatus(second)
                .withHeader("Location", "/FIM/final")));
        wm.stubFor(any(urlEqualTo("/FIM/final")).willReturn(ok("final-body")));

        ResponseEntity<byte[]> response = client.send(method, "/FIM/first", MediaType.TEXT_PLAIN,
                body == null ? null : body.getBytes(UTF_8));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("final-body".getBytes(UTF_8));
        assertThat(upstreamRequests())
                .containsExactly(method + " /FIM/first", "GET /FIM/second", "GET /FIM/final");
        assertThat(wm.findAll(requestedFor(method, urlEqualTo("/FIM/first")))).singleElement()
                .satisfies(logged -> assertThat(logged.getBodyAsString()).isEqualTo(body == null ? "" : body));
        assertThat(wm.findAll(getRequestedFor(urlMatching("/FIM/(second|final)")))).hasSize(2)
                .allSatisfy(FimProxyClientTest::assertBodylessWithTheRequestContentType);
    }

    /**
     * A redirect from https to http is returned as received, so nothing, the body a 307 or 308 would re-send
     * included, reaches the cleartext URL. The https upstream presents a certificate generated for the loopback
     * host, which the client trusts as the JVM's default TLS context while it is built.
     */
    @ParameterizedTest(name = "{0} answered {1} towards http")
    @CsvSource({"POST, 307", "POST, 308", "POST, 302", "POST, 303", "GET, 301"})
    void redirectFromHttpsToHttpIsReturnedAsReceived(String method, int status) throws Exception {
        String location = wm.baseUrl() + "/FIM/cleartext";
        wm.stubFor(any(urlEqualTo("/FIM/cleartext")).willReturn(ok("reached")));
        String body = "GET".equals(method) ? null : "private-payload";
        List<String> received = Collections.synchronizedList(new ArrayList<>());
        SSLContext loopback = loopbackTls();
        HttpsServer upstream = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        upstream.setHttpsConfigurator(new HttpsConfigurator(loopback));
        upstream.createContext("/FIM/", exchange -> {
            String receivedBody = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
            received.add(exchange.getRequestMethod() + " " + receivedBody);
            byte[] moved = "moved".getBytes(UTF_8);
            exchange.getResponseHeaders().add("Location", location);
            exchange.sendResponseHeaders(status, moved.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(moved);
            }
        });
        upstream.start();
        try {
            FimProxyClient tlsClient;
            SSLContext previous = SSLContext.getDefault();
            SSLContext.setDefault(loopback);
            try {
                tlsClient = new FimProxyClient("https://localhost:" + upstream.getAddress().getPort(),
                        Duration.ofSeconds(2));
            } finally {
                SSLContext.setDefault(previous);
            }
            try (tlsClient) {
                ResponseEntity<byte[]> response = tlsClient.send(method, "/FIM/from", MediaType.TEXT_PLAIN,
                        body == null ? null : body.getBytes(UTF_8));

                assertThat(response.getStatusCode().value()).isEqualTo(status);
                assertThat(response.getHeaders().getFirst("Location")).isEqualTo(location);
                assertThat(response.getBody()).isEqualTo("moved".getBytes(UTF_8));
            }
        } finally {
            upstream.stop(0);
        }
        assertThat(received).containsExactly(method + " " + (body == null ? "" : body));
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    /** As {@code java.net.http}'s {@code Redirect.NORMAL}: four redirects in a row are followed, the fifth returned. */
    @Test
    void fifthRedirectInARowIsReturnedAsReceived() {
        wm.stubFor(get(urlEqualTo("/FIM/loop")).willReturn(aResponse()
                .withStatus(302)
                .withHeader("Location", "/FIM/loop")
                .withBody("still-redirecting")));

        ResponseEntity<byte[]> response = client.send("GET", "/FIM/loop", null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(302);
        assertThat(response.getBody()).isEqualTo("still-redirecting".getBytes(UTF_8));
        wm.verify(5, getRequestedFor(urlEqualTo("/FIM/loop")));
    }

    @Test
    void redirectWithoutLocationFailsAsInvalidRedirection() {
        wm.stubFor(get(urlEqualTo("/FIM/nowhere")).willReturn(aResponse().withStatus(302)));

        Throwable thrown = catchThrowable(() -> client.send("GET", "/FIM/nowhere", null, null));

        assertThat(thrown).isInstanceOf(ResourceAccessException.class).hasMessageEndingWith(": Invalid redirection");
        wm.verify(1, getRequestedFor(urlEqualTo("/FIM/nowhere")));
    }

    /**
     * A connection reset, or a connection closed before any response byte, ends the call with the one request the
     * upstream received, whatever the method: idempotent GET and HEAD included.
     */
    @ParameterizedTest(name = "{0} with {1}")
    @CsvSource({
            "GET, CONNECTION_RESET_BY_PEER", "GET, EMPTY_RESPONSE",
            "HEAD, CONNECTION_RESET_BY_PEER", "HEAD, EMPTY_RESPONSE",
            "OPTIONS, CONNECTION_RESET_BY_PEER", "OPTIONS, EMPTY_RESPONSE",
            "TRACE, CONNECTION_RESET_BY_PEER", "TRACE, EMPTY_RESPONSE",
            "POST, CONNECTION_RESET_BY_PEER", "POST, EMPTY_RESPONSE",
            "PUT, CONNECTION_RESET_BY_PEER", "PUT, EMPTY_RESPONSE",
            "DELETE, CONNECTION_RESET_BY_PEER", "DELETE, EMPTY_RESPONSE",
            "PATCH, CONNECTION_RESET_BY_PEER", "PATCH, EMPTY_RESPONSE",
            "PROPFIND, CONNECTION_RESET_BY_PEER", "PROPFIND, EMPTY_RESPONSE"})
    void abruptlyClosedConnectionIsNotRetried(String method, Fault fault) {
        wm.stubFor(request(method, urlEqualTo("/FIM/abrupt")).willReturn(aResponse().withFault(fault)));

        Throwable thrown = catchThrowable(() -> client.send(method, "/FIM/abrupt", null, null));

        assertThat(thrown).isInstanceOf(ResourceAccessException.class);
        wm.verify(1, requestedFor(method, urlEqualTo("/FIM/abrupt")));
        assertThat(wm.getAllServeEvents()).hasSize(1);
    }

    /**
     * A refused connection cannot be counted at the server, so the connect failure itself shows the attempts: the
     * transport names every address it tried, and nothing else was attempted before the call failed.
     */
    @Test
    void connectionRefusedSurfacesConnectExceptionAfterOneAttempt() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        Throwable thrown;
        try (FimProxyClient refusedClient = new FimProxyClient("http://localhost:" + port, Duration.ofSeconds(2))) {
            thrown = catchThrowable(() -> refusedClient.send("GET", "/FIM/x", null, null));
        }

        assertThat(thrown).isInstanceOf(ResourceAccessException.class);
        assertThat(causeChain(thrown)).filteredOn(ConnectException.class::isInstance).singleElement()
                .satisfies(refused -> {
                    assertThat(refused.getSuppressed()).isEmpty();
                    assertThat(refused.getMessage()).contains("[" + InetAddress.getByName("localhost") + "]");
                });
    }

    /**
     * A raw socket rather than WireMock, so each stage of the response is controlled: the head never arrives, the
     * head arrives one byte every 50 ms, the body stops after 10 of its 1000 bytes, or the body arrives one byte
     * every 50 ms. A dribble keeps every socket read inside the timeout, so only the one deadline over the whole
     * response, headers and body, ends those calls within it; each call fails once, on its only connection.
     */
    @ParameterizedTest
    @EnumSource(StalledUpstream.class)
    void responseNotCompleteWithinTheTimeoutFailsOnceAtTheDeadline(StalledUpstream behaviour)
            throws IOException, InterruptedException {
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        List<Socket> connections = Collections.synchronizedList(new ArrayList<>());
        List<Thread> handlers = Collections.synchronizedList(new ArrayList<>());
        List<IOException> closeFailures = new ArrayList<>();
        ServerSocket upstream = new ServerSocket(0);
        Thread server = new Thread(() -> serve(upstream, behaviour, accepted, requests, connections, handlers),
                "stalled-fim-upstream");
        server.setDaemon(true);
        try {
            server.start();
            Throwable thrown;
            Duration elapsed;
            try (FimProxyClient shortTimeoutClient =
                         new FimProxyClient("http://127.0.0.1:" + upstream.getLocalPort(), Duration.ofMillis(300))) {
                long start = System.nanoTime();
                // The watchdog is independent of the client's own timeout, so a read that never ends fails the test
                // here instead of hanging the build.
                thrown = assertTimeoutPreemptively(Duration.ofSeconds(10),
                        () -> catchThrowable(() -> shortTimeoutClient.send("GET", "/FIM/stalled", null, null)));
                elapsed = Duration.ofNanos(System.nanoTime() - start);
            }

            assertThat(thrown).isInstanceOf(ResourceAccessException.class)
                    .hasMessageEndingWith(": Response not received within 300 ms");
            assertThat(causeChain(thrown)).anyMatch(HttpTimeoutException.class::isInstance);
            assertThat(elapsed).isBetween(Duration.ofMillis(250), Duration.ofMillis(2000));
            // Exactly one attempt: a timed-out request is never retried, on this connection or another.
            assertThat(accepted).hasValue(1);
            assertThat(requests).hasValue(1);
        } finally {
            // The one owner of the sockets: runs whether the call returned, the watchdog gave up on it or the
            // server thread failed to start. Closing the listening socket ends the accept loop, and closing each
            // accepted connection ends any client read and any upstream write still blocked on it. Every close is
            // attempted whatever the others do; failures are reported only after the cleanup, so none of them
            // replaces the failure that brought the test here.
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
            synchronized (handlers) {
                for (Thread handler : handlers) {
                    handler.join(Duration.ofSeconds(5).toMillis());
                }
            }
        }
        assertThat(closeFailures).as("failures closing the stalled upstream's sockets").isEmpty();
        assertThat(server.isAlive()).as("stalled upstream thread still running").isFalse();
        assertThat(handlers).as("stalled upstream connection threads still running").noneMatch(Thread::isAlive);
    }

    /**
     * Ten concurrent calls reach the upstream together: the upstream answers each only once all ten have arrived,
     * so a connection pool that queued some of them would leave the others waiting until the upstream answers 503.
     */
    @Test
    void concurrentCallsAreNotQueuedByTheConnectionPool() throws Exception {
        int callers = 10;
        CountDownLatch allArrived = new CountDownLatch(callers);
        ExecutorService upstreamThreads = Executors.newFixedThreadPool(callers);
        ExecutorService callerThreads = Executors.newFixedThreadPool(callers);
        HttpServer upstream = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        upstream.setExecutor(upstreamThreads);
        upstream.createContext("/FIM/", exchange -> {
            allArrived.countDown();
            boolean together;
            try {
                together = allArrived.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                together = false;
            }
            byte[] reply = (together ? "together" : "queued").getBytes(US_ASCII);
            exchange.sendResponseHeaders(together ? 200 : 503, reply.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(reply);
            }
        });
        upstream.start();
        try (FimProxyClient concurrentClient = new FimProxyClient(
                "http://127.0.0.1:" + upstream.getAddress().getPort(), Duration.ofSeconds(8))) {
            List<Future<ResponseEntity<byte[]>>> calls = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                String rawUri = "/FIM/concurrent/" + i;
                calls.add(callerThreads.submit(() -> concurrentClient.send("GET", rawUri, null, null)));
            }
            for (Future<ResponseEntity<byte[]>> call : calls) {
                ResponseEntity<byte[]> response = call.get(20, TimeUnit.SECONDS);
                assertThat(response.getStatusCode().value()).isEqualTo(200);
                assertThat(response.getBody()).isEqualTo("together".getBytes(US_ASCII));
            }
        } finally {
            upstream.stop(0);
            upstreamThreads.shutdownNow();
            callerThreads.shutdownNow();
        }
    }

    /** What the raw upstream sends once it has read a request head; none of them completes the response. */
    enum StalledUpstream {
        /** Nothing: the response head never arrives. */
        HEAD_NEVER_SENT,
        /** The response head, one byte every 50 ms; it announces a body that never follows. */
        HEAD_DRIBBLED,
        /** The response head announcing 1000 body bytes, then only the first 10. */
        BODY_STALLED,
        /** The response head at once, then its 1000 body bytes one every 50 ms. */
        BODY_DRIBBLED
    }

    /** A complete response head announcing a 1000-byte body. */
    private static final byte[] HEAD_ANNOUNCING_1000_BYTES =
            "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 1000\r\n\r\n".getBytes(US_ASCII);

    /**
     * Accepts connections until {@code upstream} is closed, counting each in {@code accepted} and serving it on its
     * own daemon thread, added to {@code handlers}, so a second connection would be accepted and counted while the
     * first is still being served. Each handler reads the request head, counts it in {@code requests} and answers
     * as {@code behaviour} says, leaving the connection open.
     *
     * <p>Each accepted connection is added to {@code connections}, a synchronized list the caller closes after
     * closing {@code upstream}. A connection accepted while {@code upstream} was being closed may join the list
     * after the caller has closed its contents, so this loop closes that one itself and stops.
     */
    private static void serve(ServerSocket upstream, StalledUpstream behaviour, AtomicInteger accepted,
                              AtomicInteger requests, List<Socket> connections, List<Thread> handlers) {
        try {
            while (!upstream.isClosed()) {
                Socket connection = upstream.accept();
                connections.add(connection);
                if (upstream.isClosed()) {
                    connection.close();
                    return;
                }
                accepted.incrementAndGet();
                Thread handler = new Thread(() -> answer(connection, behaviour, requests), "stalled-fim-connection");
                handler.setDaemon(true);
                handlers.add(handler);
                handler.start();
            }
        } catch (IOException closed) {
            // The test closed the listening socket: nothing more to accept.
        }
    }

    private static void answer(Socket connection, StalledUpstream behaviour, AtomicInteger requests) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), US_ASCII));
            String line;
            do {
                line = reader.readLine();
            } while (line != null && !line.isEmpty());
            requests.incrementAndGet();
            OutputStream out = connection.getOutputStream();
            switch (behaviour) {
                case HEAD_NEVER_SENT -> {
                    // The connection stays open and silent until the test closes it.
                }
                case HEAD_DRIBBLED -> dribble(out, HEAD_ANNOUNCING_1000_BYTES);
                case BODY_STALLED -> {
                    out.write(HEAD_ANNOUNCING_1000_BYTES);
                    out.write("0123456789".getBytes(US_ASCII));
                    out.flush();
                }
                case BODY_DRIBBLED -> {
                    out.write(HEAD_ANNOUNCING_1000_BYTES);
                    out.flush();
                    byte[] body = new byte[1000];
                    Arrays.fill(body, (byte) 'x');
                    dribble(out, body);
                }
            }
        } catch (IOException closed) {
            // The client dropped the connection, or the test closed it: nothing more to send.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A TLS context whose key store and trust store hold one self-signed certificate for {@code localhost} and
     * {@code 127.0.0.1}, generated once per run by the JDK's {@code keytool} under a random password into
     * {@link #tlsDir}, so the https upstream presents it and a client built over the context trusts it.
     */
    private static synchronized SSLContext loopbackTls() throws Exception {
        if (loopbackTls == null) {
            Path store = tlsDir.resolve("loopback.p12");
            char[] password = UUID.randomUUID().toString().toCharArray();
            String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
            Process generation = new ProcessBuilder(keytool, "-genkeypair", "-alias", "loopback", "-keyalg", "RSA",
                    "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost",
                    "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12", "-keystore", store.toString(),
                    "-storepass", new String(password))
                    .redirectErrorStream(true)
                    .start();
            String output = new String(generation.getInputStream().readAllBytes(), UTF_8);
            assertThat(generation.waitFor(60, TimeUnit.SECONDS)).as("keytool finished").isTrue();
            assertThat(generation.exitValue()).as("keytool exit status; output: %s", output).isZero();
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(store)) {
                keyStore.load(in, password);
            }
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(keyStore, password);
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(keyStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
            loopbackTls = context;
        }
        return loopbackTls;
    }

    /** Writes {@code bytes} one at a time, 50 ms apart, each flushed so it reaches the client on its own. */
    private static void dribble(OutputStream out, byte[] bytes) throws IOException, InterruptedException {
        for (byte b : bytes) {
            out.write(b);
            out.flush();
            Thread.sleep(50);
        }
    }

    /** Every request the WireMock upstream received, oldest first, as {@code <method> <url>}. */
    private static List<String> upstreamRequests() {
        List<String> requests = new ArrayList<>();
        // The journal lists the most recent request first.
        wm.getAllServeEvents().forEach(event ->
                requests.add(0, event.getRequest().getMethod().getName() + " " + event.getRequest().getUrl()));
        return requests;
    }

    /** A followed hop: no body, no body framing, and the {@code text/plain} Content-Type the request was sent with. */
    private static void assertBodylessWithTheRequestContentType(LoggedRequest logged) {
        assertThat(logged.getBody()).isEmpty();
        assertThat(logged.containsHeader("Content-Length")).isFalse();
        assertThat(logged.containsHeader("Transfer-Encoding")).isFalse();
        assertThat(logged.getHeader("Content-Type")).startsWith("text/plain");
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
