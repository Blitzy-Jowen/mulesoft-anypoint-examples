/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_rest_api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

/**
 * What the listener refuses and accepts before the proxy runs, with the request-framing guard of
 * {@link com.mulesoft.examples.proxying_a_rest_api.config.RequestFramingGuardConfig} in front of Undertow 2.3.17's
 * parser.
 *
 * <ul>
 *   <li>A bare CR inside a request header value never splits the header into one the proxy forwards: the listener
 *       answers the request with 400 before any handler runs, so nothing reaches the upstream. Without that, a caller
 *       could add headers, a second {@code Authorization} among them, to the delegated upstream call. A bare LF and
 *       the other control characters except HTAB are answered the same way, and so are a CR before the final LF,
 *       whitespace before a colon and ambiguous body framing.</li>
 *   <li>A malformed request behind a well-formed one on the same connection, after a body without one or after a
 *       chunked one, is answered with 400 once the first is forwarded; only the first reaches the upstream.</li>
 *   <li>A head arriving in small fragments is judged and forwarded whole, and an obs-fold line continues its header
 *       value as Undertow folds it.</li>
 *   <li>A request body has no size limit, as on the original listener; later Undertow releases cap one at 2 MB
 *       unless configured otherwise.</li>
 * </ul>
 *
 * <p>The application boots with its own {@code application.yml} on Undertow (random port on IPv4 loopback) in front
 * of a WireMock upstream. Requests are written to a plain socket, because ST-1 refuses CR and LF in a header value;
 * the same writer sends well-formed control requests, which must reach the upstream with their headers and body.
 */
@SpringBootTest(
        classes = ProxyingARestApiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Pins the listener to the address the socket connects to, whatever server.address the project sets.
        properties = "server.address=" + ListenerRequestParsingTest.LOOPBACK)
@ActiveProfiles("test")
class ListenerRequestParsingTest {

    /** IPv4 loopback literal: {@code localhost} may resolve to {@code ::1}, where nothing listens. */
    static final String LOOPBACK = "127.0.0.1";

    /** Connect and read timeout of the raw socket, in milliseconds. */
    private static final int TIMEOUT_MILLIS = 10_000;

    /** A body larger than the 2 MB (2,097,152 bytes) later Undertow releases allow by default. */
    private static final int LARGE_BODY_BYTES = 3 * 1024 * 1024;

    /** Bytes per write when a head is sent in fragments. */
    private static final int FRAGMENT_BYTES = 3;

    /** Pause between fragments, so that the listener reads them one by one. */
    private static final long FRAGMENT_PAUSE_MILLIS = 2;

    /** Largest chunk of a chunked request body. */
    private static final int CHUNK_BYTES = 64 * 1024;

    @RegisterExtension
    static WireMockExtension upstream = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().bindAddress(LOOPBACK))
            .build();

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void upstreamUrl(DynamicPropertyRegistry registry) {
        registry.add("proxy.upstream-url", () -> "http://" + LOOPBACK + ":" + upstream.getPort());
    }

    /**
     * Scenario, then the header section between the {@code Host} line and {@code Connection: close}: each control
     * character sits inside a header value, and the lines themselves end with CRLF.
     */
    private static Stream<Arguments> controlCharacterHeaders() {
        return Stream.of(
                Arguments.of("bare CR adds a second Authorization",
                        "X-Test: a\rAuthorization: Bearer attacker\r\nAuthorization: Bearer t1\r\n"),
                Arguments.of("bare CR inside Authorization", "Authorization: Bearer t1\rX-Injected: 1\r\n"),
                Arguments.of("bare CR adds a header", "X-Test: a\rX-Injected: 1\r\n"),
                Arguments.of("bare LF adds a header", "X-Test: a\nX-Injected: 1\r\n"),
                Arguments.of("NUL", "X-Test: a\u0000b\r\n"),
                Arguments.of("VT", "X-Test: a\u000bb\r\n"),
                Arguments.of("FF", "X-Test: a\u000cb\r\n"),
                Arguments.of("DEL", "X-Test: a\u007fb\r\n"));
    }

    // The name leaves out the header lines, whose control characters the test reports cannot carry.
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("controlCharacterHeaders")
    void controlCharacterInAHeaderValueIsRejectedAndNothingIsForwarded(String scenario, String headerLines)
            throws IOException {
        upstream.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("ok")));

        String response = exchange("GET /2.0/folders/0 HTTP/1.1\r\n"
                + "Host: " + LOOPBACK + ":" + port + "\r\n"
                + headerLines
                + "Connection: close\r\n\r\n");

        assertThat(statusLine(response)).as("%s: %s", scenario, response).isEqualTo("HTTP/1.1 400 Bad Request");
        assertThat(body(response)).as("%s: response body", scenario).isEmpty();
        assertThat(upstream.getAllServeEvents()).as("%s: requests the upstream received", scenario).isEmpty();
    }

    /** The same writer with CRLF line ends only: the request is forwarded with the caller's headers as sent. */
    @Test
    void wellFormedHeadersAreForwarded() throws IOException {
        upstream.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("ok")));

        String response = exchange("GET /2.0/folders/0 HTTP/1.1\r\n"
                + "Host: " + LOOPBACK + ":" + port + "\r\n"
                + "X-Test: a\r\n"
                + "Authorization: Bearer t1\r\n"
                + "Connection: close\r\n\r\n");

        assertThat(statusLine(response)).as(response).isEqualTo("HTTP/1.1 200 OK");
        List<ServeEvent> received = upstream.getAllServeEvents();
        assertThat(received).hasSize(1);
        assertThat(received.get(0).getRequest().getHeaders().getHeader("Authorization").values())
                .containsExactly("Bearer t1");
        assertThat(received.get(0).getRequest().getHeaders().getHeader("X-Test").values()).containsExactly("a");
        assertThat(received.get(0).getRequest().containsHeader("X-Injected")).isFalse();
    }

    // Later Undertow releases would close the connection after 2 MB of either framing by default.
    @ParameterizedTest(name = "chunked: {0}")
    @ValueSource(booleans = {false, true})
    void bodyLargerThanUndertowsDefaultLimitIsForwarded(boolean chunked) throws IOException {
        upstream.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("ok")));
        byte[] body = new byte[LARGE_BODY_BYTES];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i * 31 + 7);
        }

        String response = exchange("POST /2.0/files/content HTTP/1.1\r\n"
                + "Host: " + LOOPBACK + ":" + port + "\r\n"
                + "Content-Type: application/octet-stream\r\n"
                + (chunked ? "Transfer-Encoding: chunked\r\n" : "Content-Length: " + body.length + "\r\n")
                + "Connection: close\r\n\r\n", chunked ? chunkedEncoding(body) : body);

        assertThat(statusLine(response)).as(response).isEqualTo("HTTP/1.1 200 OK");
        List<ServeEvent> received = upstream.getAllServeEvents();
        assertThat(received).hasSize(1);
        assertThat(received.get(0).getRequest().getBody()).isEqualTo(body);
    }

    /** Scenario, then a whole request head, and a body where one follows; {@code {host}} is the listener's. */
    private static Stream<Arguments> malformedHeads() {
        return Stream.of(
                Arguments.of("CR CR before the final LF",
                        "GET /2.0/folders/0 HTTP/1.1\r\nHost: {host}\r\nConnection: close\r\n\r\r\n"),
                Arguments.of("whitespace before a colon",
                        "GET /2.0/folders/0 HTTP/1.1\r\nHost: {host}\r\nAuthorization : Bearer attacker\r\n"
                                + "Connection: close\r\n\r\n"),
                Arguments.of("header line without a colon",
                        "GET /2.0/folders/0 HTTP/1.1\r\nHost: {host}\r\nX-Test\r\nConnection: close\r\n\r\n"),
                Arguments.of("obs-fold on the first header line",
                        "GET /2.0/folders/0 HTTP/1.1\r\n X-Test: a\r\nHost: {host}\r\nConnection: close\r\n\r\n"),
                Arguments.of("bare LF ending the request line",
                        "GET /2.0/folders/0 HTTP/1.1\nHost: {host}\r\nConnection: close\r\n\r\n"),
                Arguments.of("Transfer-Encoding with Content-Length",
                        "POST /2.0/files/content HTTP/1.1\r\nHost: {host}\r\nTransfer-Encoding: chunked\r\n"
                                + "Content-Length: 5\r\nConnection: close\r\n\r\n0\r\n\r\n"),
                Arguments.of("two Content-Length values",
                        "POST /2.0/files/content HTTP/1.1\r\nHost: {host}\r\nContent-Length: 1\r\n"
                                + "Content-Length: 2\r\nConnection: close\r\n\r\nab"));
    }

    // The name leaves out the head, whose control characters the test reports cannot carry.
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("malformedHeads")
    void malformedRequestHeadIsRejectedAndNothingIsForwarded(String scenario, String head) throws IOException {
        upstream.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("ok")));

        String response = exchange(withHost(head));

        assertThat(statusLine(response)).as("%s: %s", scenario, response).isEqualTo("HTTP/1.1 400 Bad Request");
        assertThat(body(response)).as("%s: response body", scenario).isEmpty();
        assertThat(upstream.getAllServeEvents()).as("%s: requests the upstream received", scenario).isEmpty();
    }

    /**
     * Scenario, a well-formed request that keeps the connection open, the URL and the body the upstream must receive
     * from it; {@code {host}} is the listener's.
     */
    private static Stream<Arguments> requestsAheadOfAMalformedOne() {
        return Stream.of(
                Arguments.of("request without a body",
                        "GET /2.0/folders/0 HTTP/1.1\r\nHost: {host}\r\nAuthorization: Bearer t1\r\n\r\n",
                        "/2.0/folders/0", ""),
                Arguments.of("Content-Length body",
                        "POST /2.0/files/content HTTP/1.1\r\nHost: {host}\r\nAuthorization: Bearer t1\r\n"
                                + "Content-Length: 5\r\n\r\nhello",
                        "/2.0/files/content", "hello"),
                Arguments.of("chunked body with an extension and a trailer",
                        "POST /2.0/files/content HTTP/1.1\r\nHost: {host}\r\nAuthorization: Bearer t1\r\n"
                                + "Transfer-Encoding: chunked\r\n\r\n5;name=value\r\nhello\r\n6\r\n world\r\n0\r\n"
                                + "X-Trailer: t\r\n\r\n",
                        "/2.0/files/content", "hello world"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("requestsAheadOfAMalformedOne")
    void malformedRequestPipelinedBehindAWellFormedOneIsRejectedOnceTheFirstIsForwarded(
            String scenario, String first, String forwardedUrl, String forwardedBody) throws IOException {
        upstream.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("ok")));
        String malformed = "GET /2.0/folders/1 HTTP/1.1\r\nHost: {host}\r\n"
                + "X-Test: a\rAuthorization: Bearer attacker\r\n\r\n";

        String responses = exchange(withHost(first) + withHost(malformed));

        assertThat(statusLines(responses)).as("%s: %s", scenario, responses)
                .containsExactly("HTTP/1.1 200 OK", "HTTP/1.1 400 Bad Request");
        List<ServeEvent> received = upstream.getAllServeEvents();
        assertThat(received).as("%s: requests the upstream received", scenario).hasSize(1);
        assertThat(received.get(0).getRequest().getUrl()).isEqualTo(forwardedUrl);
        assertThat(received.get(0).getRequest().getBodyAsString()).isEqualTo(forwardedBody);
        assertThat(received.get(0).getRequest().getHeaders().getHeader("Authorization").values())
                .containsExactly("Bearer t1");
    }

    /**
     * Scenario, the bytes after the {@code Host} line, the status line, and the only {@code Authorization} value the
     * upstream receives or {@code null} when nothing is forwarded.
     */
    private static Stream<Arguments> fragmentedHeads() {
        return Stream.of(
                Arguments.of("well-formed", "X-Test: a\r\nAuthorization: Bearer t1\r\nConnection: close\r\n\r\n",
                        "HTTP/1.1 200 OK", "Bearer t1"),
                // The client stops at the first byte the listener refuses: the listener answers and closes there,
                // and anything sent after it would meet a closed connection.
                Arguments.of("bare CR adds a second Authorization", "X-Test: a\rA", "HTTP/1.1 400 Bad Request",
                        null));
    }

    // Each fragment is a separate write on a socket without Nagle's delay, so the listener reads it on its own.
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("fragmentedHeads")
    void headWrittenInSmallFragmentsIsJudgedWhole(String scenario, String afterHostLine, String statusLine,
                                                  String forwardedAuthorization)
            throws IOException, InterruptedException {
        upstream.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("ok")));

        String response = exchangeInFragments("GET /2.0/folders/0 HTTP/1.1\r\n"
                + "Host: " + LOOPBACK + ":" + port + "\r\n"
                + afterHostLine);

        assertThat(statusLine(response)).as("%s: %s", scenario, response).isEqualTo(statusLine);
        List<ServeEvent> received = upstream.getAllServeEvents();
        if (forwardedAuthorization == null) {
            assertThat(received).as("%s: requests the upstream received", scenario).isEmpty();
        } else {
            assertThat(received).as("%s: requests the upstream received", scenario).hasSize(1);
            assertThat(received.get(0).getRequest().getHeaders().getHeader("Authorization").values())
                    .containsExactly(forwardedAuthorization);
            assertThat(received.get(0).getRequest().getHeaders().getHeader("X-Test").values()).containsExactly("a");
        }
    }

    /** An obs-fold line continues the value before it, joined by one SP, as Undertow folds it. */
    @Test
    void obsFoldLineContinuesTheHeaderValue() throws IOException {
        upstream.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("ok")));

        String response = exchange("GET /2.0/folders/0 HTTP/1.1\r\n"
                + "Host: " + LOOPBACK + ":" + port + "\r\n"
                + "X-Test: a\r\n \tb\r\n"
                + "Connection: close\r\n\r\n");

        assertThat(statusLine(response)).as(response).isEqualTo("HTTP/1.1 200 OK");
        List<ServeEvent> received = upstream.getAllServeEvents();
        assertThat(received).hasSize(1);
        assertThat(received.get(0).getRequest().getHeaders().getHeader("X-Test").values()).containsExactly("a b");
    }

    /** {@code text} with {@code {host}} replaced by the listener's {@code 127.0.0.1:<port>}. */
    private String withHost(String text) {
        return text.replace("{host}", LOOPBACK + ":" + port);
    }

    /** {@code body} in chunks of at most 64 KiB, then the last chunk and an empty trailer section. */
    private static byte[] chunkedEncoding(byte[] body) {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream(body.length + 1024);
        for (int start = 0; start < body.length; start += CHUNK_BYTES) {
            int length = Math.min(CHUNK_BYTES, body.length - start);
            encoded.writeBytes((Integer.toHexString(length) + "\r\n").getBytes(ISO_8859_1));
            encoded.write(body, start, length);
            encoded.writeBytes("\r\n".getBytes(ISO_8859_1));
        }
        encoded.writeBytes("0\r\n\r\n".getBytes(ISO_8859_1));
        return encoded.toByteArray();
    }

    /** Sends {@code head} with no body; see {@link #exchange(String, byte[])}. */
    private String exchange(String head) throws IOException {
        return exchange(head, new byte[0]);
    }

    /**
     * Writes {@code head} as ISO-8859-1 bytes, one byte per character, then {@code body} as given, on a new
     * connection and reads until the listener closes it; every request sends {@code Connection: close}.
     */
    private String exchange(String head, byte[] body) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(LOOPBACK, port), TIMEOUT_MILLIS);
            socket.setSoTimeout(TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write(head.getBytes(ISO_8859_1));
            out.write(body);
            out.flush();
            return new String(socket.getInputStream().readAllBytes(), ISO_8859_1);
        }
    }

    /**
     * Writes {@code head} as ISO-8859-1 bytes in writes of {@link #FRAGMENT_BYTES}, pausing between them, and reads
     * until the listener closes the connection.
     */
    private String exchangeInFragments(String head) throws IOException, InterruptedException {
        byte[] bytes = head.getBytes(ISO_8859_1);
        try (Socket socket = new Socket()) {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(LOOPBACK, port), TIMEOUT_MILLIS);
            socket.setSoTimeout(TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            for (int start = 0; start < bytes.length; start += FRAGMENT_BYTES) {
                out.write(bytes, start, Math.min(FRAGMENT_BYTES, bytes.length - start));
                out.flush();
                Thread.sleep(FRAGMENT_PAUSE_MILLIS);
            }
            return new String(socket.getInputStream().readAllBytes(), ISO_8859_1);
        }
    }

    /**
     * The status line of each response in {@code responses}, taken one after another: each response's body is as
     * long as its {@code Content-Length}, and a response without one runs to the end.
     */
    private static List<String> statusLines(String responses) {
        List<String> lines = new ArrayList<>();
        int position = 0;
        while (position < responses.length()) {
            int headEnd = responses.indexOf("\r\n\r\n", position);
            if (headEnd < 0) {
                lines.add(statusLine(responses.substring(position)));
                break;
            }
            String[] head = responses.substring(position, headEnd).split("\r\n");
            lines.add(head[0]);
            int bodyLength = responses.length() - headEnd - 4;
            for (String line : head) {
                if (line.regionMatches(true, 0, "Content-Length:", 0, "Content-Length:".length())) {
                    bodyLength = Integer.parseInt(line.substring("Content-Length:".length()).trim());
                }
            }
            position = headEnd + 4 + bodyLength;
        }
        return lines;
    }

    /** The response's first line without its CRLF, or the whole response when it holds no CRLF. */
    private static String statusLine(String response) {
        int end = response.indexOf("\r\n");
        return end < 0 ? response : response.substring(0, end);
    }

    /** Everything after the blank line that ends the response head, or the whole response when it has none. */
    private static String body(String response) {
        int end = response.indexOf("\r\n\r\n");
        return end < 0 ? response : response.substring(end + 4);
    }
}
