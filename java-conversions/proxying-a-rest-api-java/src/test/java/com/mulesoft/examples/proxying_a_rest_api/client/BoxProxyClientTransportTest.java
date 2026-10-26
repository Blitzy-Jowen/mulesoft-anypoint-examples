/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_rest_api.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;

import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.mulesoft.examples.proxying_a_rest_api.mapper.ProxyHeaderMapper;
import com.mulesoft.examples.proxying_a_rest_api.service.RestProxyService;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * The transport under {@link BoxProxyClient}, against loopback upstreams: a stalled exchange fails within the
 * timeout, once, and gives its connection up; no failure is retried; responses come back unparsed and cookies are
 * never replayed; the headers the header mapping keeps, {@code Expect} and {@code Upgrade} included, reach the
 * upstream; a request without body bytes is framed by its method; a TRACE with body bytes is refused before
 * anything is sent, and through {@link RestProxyService} the bytes of TRACE and PRI, which the Mule requester sent
 * without payload, never reach the upstream; a redirect carries the caller's headers only on the original origin,
 * and one from https to http is returned unfollowed, so nothing reaches the cleartext target; and
 * {@link BoxProxyClient#close()} releases pooled connections.
 */
class BoxProxyClientTransportTest {

    /** Timeout of the stall cases; the other cases use a generous one so that a slow host cannot fail them. */
    private static final long STALL_TIMEOUT_MS = 500;

    private static final long TIMEOUT_MS = 5000;

    @RegisterExtension
    static WireMockExtension upstream = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().bindAddress("127.0.0.1"))
            .build();

    /** Holds the key store of the HTTPS cases, generated once for the class. */
    @TempDir
    static Path tlsDir;

    private static SSLContext loopbackTls;

    private BoxProxyClient client;

    private RawUpstream raw;

    private TlsUpstream tls;

    @AfterEach
    void closeClientAndUpstream() throws IOException {
        if (client != null) {
            client.close();
        }
        if (raw != null) {
            raw.close();
        }
        if (tls != null) {
            tls.close();
        }
    }

    static Stream<Arguments> stalledUpstreams() {
        return Stream.of(
                Arguments.of("response body stalls after its headers", "http",
                        (Responder) RawUpstream::sendHeadersThenStall, HttpMethod.GET, null),
                Arguments.of("request body is never read", "http",
                        (Responder) RawUpstream::neverReadBody, HttpMethod.POST, new byte[16 * 1024 * 1024]),
                // The upstream reads the ClientHello as a request head that never ends, so no ServerHello comes.
                Arguments.of("TLS handshake is never answered", "https",
                        (Responder) RawUpstream::answerEmpty, HttpMethod.GET, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stalledUpstreams")
    void stalledExchangeTimesOutOnceAndReleasesItsConnection(String scenario, String scheme, Responder responder,
                                                             HttpMethod method, byte[] body) throws Exception {
        raw = new RawUpstream(responder);
        client = new BoxProxyClient(null, scheme + "://" + raw.authority(), STALL_TIMEOUT_MS, "", "", "");

        long started = System.nanoTime();
        Throwable failure = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> catchThrowable(() -> client.exchange(method, "/stall", null, new HttpHeaders(), body)));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(failure).isInstanceOf(ResourceAccessException.class);
        assertThat(causeChain(failure)).hasAtLeastOneElementOfType(SocketTimeoutException.class);
        assertThat(elapsedMs).isLessThan(3000);
        raw.release();
        assertThat(raw.awaitClientClose(Duration.ofSeconds(5))).as("connection closed by the client").isTrue();
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(raw.accepted()).as("connections accepted").isEqualTo(1));
    }

    // httpclient5's default retry strategy would re-send this idempotent request on a new connection.
    @Test
    void connectionClosedWithoutAResponseIsNotRetried() throws Exception {
        raw = new RawUpstream((stub, head, in, out) -> out.close());
        client = new BoxProxyClient(null, raw.baseUrl(), TIMEOUT_MS, "", "", "");

        Throwable failure = catchThrowable(
                () -> client.exchange(HttpMethod.GET, "/2.0/folders/0", null, new HttpHeaders(), null));

        assertThat(failure).isInstanceOf(ResourceAccessException.class);
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(raw.accepted()).as("connections accepted").isEqualTo(1));
    }

    // httpclient5's default retry strategy would wait for Retry-After and re-send this idempotent request.
    @Test
    void serviceUnavailableIsReturnedWithoutRetry() {
        upstream.stubFor(any(urlEqualTo("/2.0/folders/0"))
                .willReturn(aResponse().withStatus(503).withHeader(HttpHeaders.RETRY_AFTER, "1").withBody("busy")));
        client = new BoxProxyClient(null, upstreamBaseUrl(), TIMEOUT_MS, "", "", "");

        ResponseEntity<byte[]> response =
                client.exchange(HttpMethod.GET, "/2.0/folders/0", null, new HttpHeaders(), null);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(response.getBody()).isEqualTo("busy".getBytes(UTF_8));
        upstream.verify(1, new RequestPatternBuilder(RequestMethod.GET, urlEqualTo("/2.0/folders/0")));
    }

    @Test
    void compressedResponseIsReturnedUnparsedAndNoEncodingIsRequested() throws Exception {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write("{\"entries\":[]}".getBytes(UTF_8));
        }
        byte[] gzipped = compressed.toByteArray();
        raw = new RawUpstream((stub, head, in, out) -> {
            out.write(("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: " + gzipped.length
                    + "\r\n\r\n").getBytes(US_ASCII));
            out.write(gzipped);
        });
        client = new BoxProxyClient(null, raw.baseUrl(), TIMEOUT_MS, "", "", "");

        ResponseEntity<byte[]> response =
                client.exchange(HttpMethod.GET, "/2.0/folders/0/items", null, new HttpHeaders(), null);

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING)).isEqualTo("gzip");
        assertThat(response.getBody()).isEqualTo(gzipped);
        assertThat(raw.nextRequestHead().headers()).doesNotContainKey(HttpHeaders.ACCEPT_ENCODING);
    }

    // One client serves every caller, so a cookie one upstream response sets must never reach the next request.
    @Test
    void responseCookiesAreNotReplayedOnLaterRequests() {
        upstream.stubFor(any(urlEqualTo("/first"))
                .willReturn(ok().withHeader(HttpHeaders.SET_COOKIE, "session=caller-a; Path=/")));
        upstream.stubFor(any(urlEqualTo("/second")).willReturn(ok()));
        client = new BoxProxyClient(null, upstreamBaseUrl(), TIMEOUT_MS, "", "", "");

        ResponseEntity<byte[]> first = client.exchange(HttpMethod.GET, "/first", null, new HttpHeaders(), null);
        client.exchange(HttpMethod.GET, "/second", null, new HttpHeaders(), null);

        assertThat(first.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).isEqualTo("session=caller-a; Path=/");
        upstream.verify(1, new RequestPatternBuilder(RequestMethod.GET, urlEqualTo("/second"))
                .withoutHeader(HttpHeaders.COOKIE));
    }

    // The raw upstream rather than WireMock: its Jetty answers 400 to an Upgrade without Connection: upgrade,
    // which is what a proxy sends once the header mapping has removed the caller's Connection.
    @Test
    void expectAndUpgradeReachTheUpstreamThroughTheHeaderMapping() throws Exception {
        raw = new RawUpstream(RawUpstream::continueThenAnswer);
        HttpHeaders inbound = new HttpHeaders();
        inbound.add(HttpHeaders.HOST, "proxy.example:8081");
        inbound.add(HttpHeaders.CONNECTION, "close");
        inbound.add(HttpHeaders.AUTHORIZATION, "Bearer abc");
        inbound.add(HttpHeaders.EXPECT, "100-continue");
        // A token no server implements, so the upstream never attempts a protocol switch.
        inbound.add(HttpHeaders.UPGRADE, "example/1");
        inbound.add("X-Custom", "kept");
        byte[] body = "{\"name\":\"Projects\",\"parent\":{\"id\":\"0\"}}".getBytes(UTF_8);
        client = new BoxProxyClient(null, raw.baseUrl(), TIMEOUT_MS, "", "", "");

        ResponseEntity<byte[]> response = client.exchange(HttpMethod.POST, "/2.0/folders", null,
                new ProxyHeaderMapper().toUpstream(inbound), body);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("created".getBytes(US_ASCII));
        RequestHead head = raw.nextRequestHead();
        assertThat(head.requestLine()).isEqualTo("POST /2.0/folders HTTP/1.1");
        assertThat(head.headers())
                .containsEntry(HttpHeaders.EXPECT, "100-continue")
                .containsEntry(HttpHeaders.UPGRADE, "example/1")
                .containsEntry(HttpHeaders.AUTHORIZATION, "Bearer abc")
                .containsEntry("X-Custom", "kept")
                .containsEntry(HttpHeaders.HOST, raw.authority())
                .containsEntry(HttpHeaders.CONNECTION, "keep-alive")
                .containsEntry(HttpHeaders.CONTENT_LENGTH, String.valueOf(body.length));
        assertThat(raw.nextBody()).isEqualTo(body);
        assertThat(raw.bytesBeforeContinue()).as("body bytes sent before 100 Continue").isZero();
    }

    @Test
    void upgradeReachesTheUpstreamOnABodylessGet() throws Exception {
        raw = new RawUpstream(RawUpstream::answerEmpty);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.UPGRADE, "example/1");
        client = new BoxProxyClient(null, raw.baseUrl(), TIMEOUT_MS, "", "", "");

        ResponseEntity<byte[]> response = client.exchange(HttpMethod.GET, "/2.0/users/me", null, headers, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        RequestHead head = raw.nextRequestHead();
        assertThat(head.requestLine()).isEqualTo("GET /2.0/users/me HTTP/1.1");
        assertThat(head.headers()).containsEntry(HttpHeaders.UPGRADE, "example/1");
    }

    static Stream<Arguments> requestsWithoutBodyBytes() {
        return Stream.of(
                Arguments.of(HttpMethod.GET, null, null),
                Arguments.of(HttpMethod.POST, new byte[0], "0"),
                Arguments.of(HttpMethod.TRACE, new byte[0], null));
    }

    @ParameterizedTest(name = "{0}, Content-Length {2}")
    @MethodSource("requestsWithoutBodyBytes")
    void requestWithoutBodyBytesIsFramedByItsMethod(HttpMethod method, byte[] body, String contentLength)
            throws Exception {
        raw = new RawUpstream(RawUpstream::answerEmpty);
        client = new BoxProxyClient(null, raw.baseUrl(), TIMEOUT_MS, "", "", "");

        ResponseEntity<byte[]> response = client.exchange(method, "/empty", null, new HttpHeaders(), body);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEmpty();
        RequestHead head = raw.nextRequestHead();
        assertThat(head.requestLine()).isEqualTo(method.name() + " /empty HTTP/1.1");
        assertThat(head.headers().get(HttpHeaders.CONTENT_LENGTH)).isEqualTo(contentLength);
        assertThat(head.headers()).doesNotContainKey(HttpHeaders.TRANSFER_ENCODING);
    }

    // RFC 7231 forbids a client to send a TRACE body, so httpclient5 refuses one before it opens a connection; it
    // matches the method token in any letter case.
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"TRACE", "trace"})
    void traceWithBodyBytesIsRefusedBeforeAnythingReachesTheUpstream(String methodName) throws Exception {
        raw = new RawUpstream(RawUpstream::answerEmpty);
        client = new BoxProxyClient(null, raw.baseUrl(), TIMEOUT_MS, "", "", "");

        Throwable failure = catchThrowable(() -> client.exchange(HttpMethod.valueOf(methodName), "/trace/x", null,
                new HttpHeaders(), "trace-body".getBytes(US_ASCII)));

        assertThat(failure).isExactlyInstanceOf(IllegalStateException.class).hasMessageContaining("TRACE");
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(raw.accepted()).as("connections accepted").isZero());
    }

    // The Mule requester's Grizzly client wrote no payload for TRACE or PRI, so RestProxyService drops their bytes
    // and the request otherwise reaches the upstream as the caller sent it.
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"TRACE", "PRI"})
    void bodyOfAMethodTheMuleRequesterSentWithoutPayloadNeverReachesTheUpstream(String methodName)
            throws Exception {
        raw = new RawUpstream(RawUpstream::answerEmpty);
        client = new BoxProxyClient(null, raw.baseUrl(), TIMEOUT_MS, "", "", "");
        byte[] body = "trace-body".getBytes(US_ASCII);
        HttpHeaders inbound = new HttpHeaders();
        inbound.add(HttpHeaders.CONTENT_TYPE, "text/plain");
        inbound.add(HttpHeaders.CONTENT_LENGTH, String.valueOf(body.length));

        ResponseEntity<byte[]> response = new RestProxyService(new ProxyHeaderMapper(), client)
                .forward(HttpMethod.valueOf(methodName), "/trace/x", null, inbound, body);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        RequestHead head = raw.nextRequestHead();
        assertThat(head.requestLine()).isEqualTo(methodName + " /trace/x HTTP/1.1");
        assertThat(head.headers())
                .containsEntry(HttpHeaders.CONTENT_TYPE, "text/plain")
                .doesNotContainKey(HttpHeaders.TRANSFER_ENCODING);
        // Either framing sends no body.
        assertThat(head.headers().get(HttpHeaders.CONTENT_LENGTH)).isIn(null, "0");
        client.close();
        assertThat(raw.awaitClientClose(Duration.ofSeconds(5))).as("connection closed by the client").isTrue();
        assertThat(raw.unreadBytes()).as("bytes received after the request head").isZero();
    }

    static Stream<Arguments> redirects() {
        return Stream.of(
                Arguments.of(HttpMethod.POST, 303, "127.0.0.1", RequestMethod.GET, true, false),
                Arguments.of(HttpMethod.POST, 302, "127.0.0.1", RequestMethod.GET, true, false),
                Arguments.of(HttpMethod.POST, 307, "127.0.0.1", RequestMethod.POST, true, true),
                Arguments.of(HttpMethod.GET, 302, "localhost", RequestMethod.GET, false, false));
    }

    @ParameterizedTest(name = "{0} answered {1} towards {2}")
    @MethodSource("redirects")
    void redirectCarriesTheCallersHeadersOnlyOnTheOriginalOrigin(HttpMethod method, int status, String targetHost,
                                                                 RequestMethod followUpMethod,
                                                                 boolean credentialsKept, boolean bodyResent) {
        byte[] body = method == HttpMethod.GET ? null : "payload".getBytes(UTF_8);
        upstream.stubFor(any(urlEqualTo("/from")).willReturn(aResponse().withStatus(status)
                .withHeader(HttpHeaders.LOCATION, "http://" + targetHost + ":" + upstream.getPort() + "/to")));
        upstream.stubFor(any(urlEqualTo("/to")).willReturn(ok("done")));
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer abc");
        headers.add(HttpHeaders.COOKIE, "session=1");
        headers.add("X-Custom", "kept");
        client = new BoxProxyClient(null, upstreamBaseUrl(), TIMEOUT_MS, "", "", "");

        ResponseEntity<byte[]> response = client.exchange(method, "/from", null, headers, body);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("done".getBytes(UTF_8));
        RequestPatternBuilder followUp = new RequestPatternBuilder(followUpMethod, urlEqualTo("/to"))
                .withHeader("X-Custom", equalTo("kept"));
        if (credentialsKept) {
            followUp.withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer abc"))
                    .withHeader(HttpHeaders.COOKIE, equalTo("session=1"));
        } else {
            followUp.withoutHeader(HttpHeaders.AUTHORIZATION).withoutHeader(HttpHeaders.COOKIE);
        }
        if (bodyResent) {
            followUp.withRequestBody(binaryEqualTo(body));
        } else {
            followUp.withoutHeader(HttpHeaders.CONTENT_LENGTH);
        }
        upstream.verify(1, followUp);
    }

    static Stream<Arguments> downgradeRedirects() {
        return Stream.of(
                Arguments.of(HttpMethod.POST, 307),
                Arguments.of(HttpMethod.POST, 308),
                Arguments.of(HttpMethod.GET, 302),
                Arguments.of(HttpMethod.POST, 303),
                Arguments.of(HttpMethod.POST, 301));
    }

    // Each of these would otherwise reach the cleartext target, the 307 and 308 with the request body.
    @ParameterizedTest(name = "{0} answered {1} towards http")
    @MethodSource("downgradeRedirects")
    void redirectFromHttpsToHttpIsReturnedAndNothingReachesTheCleartextTarget(HttpMethod method, int status)
            throws Exception {
        upstream.stubFor(any(urlEqualTo("/to")).willReturn(ok("done")));
        String location = upstreamBaseUrl() + "/to";
        tls = new TlsUpstream();
        tls.answer("/from", status, location, "moved");
        client = clientTrustingLoopback(tls.baseUrl());
        String body = method == HttpMethod.GET ? "" : "private-write-payload";
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer abc");

        ResponseEntity<byte[]> response = client.exchange(method, "/from", null, headers,
                body.isEmpty() ? null : body.getBytes(UTF_8));

        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo(location);
        assertThat(response.getBody()).isEqualTo("moved".getBytes(UTF_8));
        assertThat(tls.received()).containsExactly(new Received(method.name() + " /from", body));
        upstream.verify(0, anyRequestedFor(anyUrl()));
    }

    @ParameterizedTest(name = "absolute Location {0}")
    @ValueSource(booleans = {true, false})
    void redirectFromHttpsToHttpsIsFollowed(boolean absolute) throws Exception {
        tls = new TlsUpstream();
        tls.answer("/from", 307, absolute ? "https://localhost:" + tls.port() + "/to" : "/to", "moved");
        tls.answer("/to", 200, null, "done");
        client = clientTrustingLoopback(tls.baseUrl());

        ResponseEntity<byte[]> response =
                client.exchange(HttpMethod.POST, "/from", null, new HttpHeaders(), "payload".getBytes(UTF_8));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("done".getBytes(UTF_8));
        assertThat(tls.received())
                .containsExactly(new Received("POST /from", "payload"), new Received("POST /to", "payload"));
    }

    // The redirect to https is followed; the one back to http is refused, as the hop it answers is https.
    @Test
    void redirectBackToHttpAfterAnUpgradeToHttpsIsReturned() throws Exception {
        String downgrade = upstreamBaseUrl() + "/to";
        tls = new TlsUpstream();
        tls.answer("/hop", 307, downgrade, "moved");
        upstream.stubFor(any(urlEqualTo("/from")).willReturn(aResponse().withStatus(307)
                .withHeader(HttpHeaders.LOCATION, tls.baseUrl() + "/hop")));
        upstream.stubFor(any(urlEqualTo("/to")).willReturn(ok("done")));
        client = clientTrustingLoopback(upstreamBaseUrl());
        byte[] body = "private-write-payload".getBytes(UTF_8);

        ResponseEntity<byte[]> response = client.exchange(HttpMethod.POST, "/from", null, new HttpHeaders(), body);

        assertThat(response.getStatusCode().value()).isEqualTo(307);
        assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo(downgrade);
        assertThat(response.getBody()).isEqualTo("moved".getBytes(UTF_8));
        assertThat(tls.received()).containsExactly(new Received("POST /hop", "private-write-payload"));
        upstream.verify(1, new RequestPatternBuilder(RequestMethod.POST, urlEqualTo("/from"))
                .withRequestBody(binaryEqualTo(body)));
        upstream.verify(0, anyRequestedFor(urlEqualTo("/to")));
    }

    @Test
    void closeReleasesPooledConnections() throws Exception {
        raw = new RawUpstream(RawUpstream::answerEmpty);
        client = new BoxProxyClient(null, raw.baseUrl(), TIMEOUT_MS, "", "", "");
        assertThat(client.exchange(HttpMethod.GET, "/pooled", null, new HttpHeaders(), null)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(raw.awaitClientClose(Duration.ofMillis(300))).as("kept alive in the pool").isFalse();

        client.close();

        assertThat(raw.awaitClientClose(Duration.ofSeconds(5))).as("connection closed by close()").isTrue();
    }

    private static String upstreamBaseUrl() {
        return "http://127.0.0.1:" + upstream.getPort();
    }

    /**
     * Builds a client while the JVM default TLS context, which a client without a key store captures, trusts the
     * loopback certificate, then restores the previous default for every other test.
     */
    private static BoxProxyClient clientTrustingLoopback(String upstreamUrl) throws Exception {
        SSLContext previous = SSLContext.getDefault();
        SSLContext.setDefault(loopbackTls());
        try {
            return new BoxProxyClient(null, upstreamUrl, TIMEOUT_MS, "", "", "");
        } finally {
            SSLContext.setDefault(previous);
        }
    }

    /**
     * A TLS context that presents, and trusts, one self-signed certificate for {@code localhost} and
     * {@code 127.0.0.1}, which keytool generates into {@link #tlsDir} on first use under a random password.
     */
    private static synchronized SSLContext loopbackTls() throws Exception {
        if (loopbackTls == null) {
            Path store = tlsDir.resolve("loopback.p12");
            Path log = tlsDir.resolve("keytool.log");
            String password = UUID.randomUUID().toString();
            Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                    "-genkeypair", "-alias", "loopback", "-keyalg", "EC", "-groupname", "secp256r1",
                    "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "1",
                    "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", password)
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            keytool.getOutputStream().close();
            boolean finished = keytool.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                keytool.destroyForcibly();
            }
            assertThat(finished).as("keytool finished within 60 s").isTrue();
            assertThat(keytool.exitValue()).as("keytool exit status; output: %s", Files.readString(log)).isZero();
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(store)) {
                keyStore.load(in, password.toCharArray());
            }
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(keyStore, password.toCharArray());
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(keyStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
            loopbackTls = context;
        }
        return loopbackTls;
    }

    private static List<Throwable> causeChain(Throwable failure) {
        return Stream.iterate(failure, Objects::nonNull, Throwable::getCause).toList();
    }

    /** What a raw upstream does on a connection once it has read the request head. */
    @FunctionalInterface
    interface Responder {
        void respond(RawUpstream upstream, RequestHead head, InputStream in, OutputStream out)
                throws IOException, InterruptedException;
    }

    /** A request head as a raw upstream received it; header names match ignoring case. */
    record RequestHead(String requestLine, Map<String, String> headers) {

        static RequestHead parse(String head) {
            String[] lines = head.split("\r\n");
            Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                headers.put(lines[i].substring(0, colon).trim(), lines[i].substring(colon + 1).trim());
            }
            return new RequestHead(lines[0], headers);
        }
    }

    /**
     * An HTTP/1.1 upstream on a raw loopback socket, for exchanges WireMock cannot stage. It counts the connections
     * it accepts, records each request head exactly as received, lets its {@link Responder} answer, and then reads
     * until the client closes the connection, which it reports, counting the bytes it read there.
     */
    static final class RawUpstream implements AutoCloseable {

        private final ServerSocket server;

        private final Responder responder;

        private final AtomicInteger accepted = new AtomicInteger();

        private final BlockingQueue<RequestHead> heads = new LinkedBlockingQueue<>();

        private final BlockingQueue<byte[]> bodies = new LinkedBlockingQueue<>();

        private final AtomicInteger bytesBeforeContinue = new AtomicInteger(-1);

        private final AtomicLong unreadBytes = new AtomicLong();

        private final CountDownLatch clientClosed = new CountDownLatch(1);

        private final CountDownLatch released = new CountDownLatch(1);

        private final List<Socket> sockets = new CopyOnWriteArrayList<>();

        private final ExecutorService threads = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "raw-upstream");
            thread.setDaemon(true);
            return thread;
        });

        RawUpstream(Responder responder) throws IOException {
            this.responder = responder;
            this.server = new ServerSocket();
            // A small receive window makes an unread request body block the client's writes quickly.
            server.setReceiveBufferSize(16 * 1024);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 50);
            threads.execute(this::acceptConnections);
        }

        static void sendHeadersThenStall(RawUpstream upstream, RequestHead head, InputStream in, OutputStream out)
                throws IOException {
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n".getBytes(US_ASCII));
            out.write(new byte[10]);
        }

        static void neverReadBody(RawUpstream upstream, RequestHead head, InputStream in, OutputStream out)
                throws InterruptedException {
            upstream.released.await(10, TimeUnit.SECONDS);
        }

        static void answerEmpty(RawUpstream upstream, RequestHead head, InputStream in, OutputStream out)
                throws IOException {
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(US_ASCII));
        }

        /** Answers {@code Expect: 100-continue}, records the body that follows, then answers 200 "created". */
        static void continueThenAnswer(RawUpstream upstream, RequestHead head, InputStream in, OutputStream out)
                throws IOException, InterruptedException {
            // Long enough to see a client that sends its body without waiting, well inside the client's own wait.
            TimeUnit.MILLISECONDS.sleep(200);
            upstream.bytesBeforeContinue.set(in.available());
            out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(US_ASCII));
            out.flush();
            upstream.bodies.add(in.readNBytes(Integer.parseInt(head.headers().get(HttpHeaders.CONTENT_LENGTH))));
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 7\r\n\r\ncreated".getBytes(US_ASCII));
        }

        String baseUrl() {
            return "http://" + authority();
        }

        String authority() {
            return "127.0.0.1:" + server.getLocalPort();
        }

        int bytesBeforeContinue() {
            return bytesBeforeContinue.get();
        }

        /**
         * Bytes the client sent after a request head that its responder did not read, final once
         * {@link #awaitClientClose} has returned {@code true}.
         */
        long unreadBytes() {
            return unreadBytes.get();
        }

        byte[] nextBody() throws InterruptedException {
            byte[] body = bodies.poll(5, TimeUnit.SECONDS);
            assertThat(body).as("request body received").isNotNull();
            return body;
        }

        int accepted() {
            return accepted.get();
        }

        /** Lets a {@link #neverReadBody} connection go on to read, so it can see the client close. */
        void release() {
            released.countDown();
        }

        boolean awaitClientClose(Duration timeout) throws InterruptedException {
            return clientClosed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        RequestHead nextRequestHead() throws InterruptedException {
            RequestHead head = heads.poll(5, TimeUnit.SECONDS);
            assertThat(head).as("request head received").isNotNull();
            return head;
        }

        @Override
        public void close() throws IOException {
            released.countDown();
            server.close();
            for (Socket socket : sockets) {
                socket.close();
            }
            threads.shutdownNow();
        }

        private void acceptConnections() {
            while (!server.isClosed()) {
                Socket socket;
                try {
                    socket = server.accept();
                } catch (IOException e) {
                    return;
                }
                accepted.incrementAndGet();
                sockets.add(socket);
                threads.execute(() -> serve(socket));
            }
        }

        private void serve(Socket socket) {
            try (socket) {
                InputStream in = socket.getInputStream();
                RequestHead head = RequestHead.parse(readHead(in));
                heads.add(head);
                OutputStream out = socket.getOutputStream();
                responder.respond(this, head, in, out);
                out.flush();
                unreadBytes.addAndGet(in.transferTo(OutputStream.nullOutputStream()));
                clientClosed.countDown();
            } catch (IOException e) {
                // An end of stream inside the head, or a reset, is the client closing the connection.
                clientClosed.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /** Reads byte by byte up to the blank line, so no request body byte is consumed with the head. */
        private static String readHead(InputStream in) throws IOException {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int matched = 0;
            byte[] terminator = {'\r', '\n', '\r', '\n'};
            while (matched < terminator.length) {
                int b = in.read();
                if (b == -1) {
                    throw new IOException("Connection closed inside the request head");
                }
                head.write(b);
                matched = b == terminator[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
            }
            return head.toString(US_ASCII);
        }
    }

    /** A request an HTTPS upstream received: its method and raw path, and its body as UTF-8 text. */
    record Received(String requestLine, String body) {
    }

    /**
     * An HTTPS upstream on loopback presenting the {@link #loopbackTls()} certificate. It records every request it
     * receives, in order, and answers each registered path with the status, {@code Location} and body given for it.
     */
    static final class TlsUpstream implements AutoCloseable {

        private final HttpsServer server;

        private final List<Received> received = new CopyOnWriteArrayList<>();

        TlsUpstream() throws Exception {
            server = HttpsServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(loopbackTls()));
            server.start();
        }

        /** Answers {@code path} with {@code status}, a {@code Location} unless it is {@code null}, and {@code body}. */
        void answer(String path, int status, String location, String body) {
            byte[] bytes = body.getBytes(UTF_8);
            server.createContext(path, exchange -> {
                try (exchange) {
                    received.add(new Received(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath(),
                            new String(exchange.getRequestBody().readAllBytes(), UTF_8)));
                    if (location != null) {
                        exchange.getResponseHeaders().set(HttpHeaders.LOCATION, location);
                    }
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
        }

        int port() {
            return server.getAddress().getPort();
        }

        String baseUrl() {
            return "https://127.0.0.1:" + port();
        }

        List<Received> received() {
            return List.copyOf(received);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
