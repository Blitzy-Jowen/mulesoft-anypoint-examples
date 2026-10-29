/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_soap_api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.http.LoggedResponse;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

/**
 * What the listener refuses and accepts before the proxy runs, with the request-framing guard of
 * {@link com.mulesoft.examples.proxying_a_soap_api.config.RequestFramingGuardConfig} in front of Undertow 2.3.17's
 * parser.
 *
 * <ul>
 *   <li>A bare CR or LF inside a request header value never splits the header into one the proxy forwards: the
 *       listener answers the request with an empty 400 before any handler runs, so neither the headers nor the
 *       envelope reach the upstream. Without that, a caller could add headers, a second {@code Authorization} among
 *       them, to the forwarded {@code POST}. NUL and the other control characters except HTAB are answered the same
 *       way, and so are a CR before the final LF, whitespace before a colon and ambiguous body framing.</li>
 *   <li>The same request with CRLF line ends only is forwarded with the caller's {@code Authorization},
 *       {@code SOAPAction} and envelope as sent, and the upstream's {@code cartId} envelope comes back.</li>
 *   <li>A malformed request behind a well-formed one on the same connection, after a request without a body, after
 *       a {@code Content-Length} envelope or after a chunked one, is answered with 400 once the first is forwarded;
 *       only the first reaches the upstream.</li>
 *   <li>A head arriving in small fragments is judged and forwarded whole, an obs-fold line continues its header
 *       value as Undertow folds it, and a body spanning many reads of the guard reaches the upstream byte for
 *       byte.</li>
 * </ul>
 *
 * <p>The application boots with its own {@code application.yml} on Undertow (random port on IPv4 loopback) in front
 * of a WireMock server standing in for the ShopService, with the offline WSDL stand-in. Requests are written to a
 * plain socket, because ST-1 refuses CR and LF in a header value; the same writer sends the well-formed control
 * requests, which must reach the upstream with their headers and body.
 */
@SpringBootTest(
        classes = ProxyingASoapApiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Pins the listener to the address the socket connects to, whatever server.address the project sets.
        properties = {
                "server.address=" + ListenerRequestParsingTest.LOOPBACK,
                "proxy.wsdl-location=classpath:upstream/ShopService.wsdl"})
class ListenerRequestParsingTest {

    /** IPv4 loopback literal: {@code localhost} may resolve to {@code ::1}, where nothing listens. */
    static final String LOOPBACK = "127.0.0.1";

    /** The {@code http:request path} on the upstream. */
    private static final String UPSTREAM_PATH = "/shop/ShopService";

    /** The caller credential of the forwarding cases, {@code caller:pw} in Basic form. */
    private static final String CALLER_AUTHORIZATION = "Basic Y2FsbGVyOnB3";

    /** The upstream's answer to {@code create}, as one compact envelope. */
    private static final byte[] CREATE_RESPONSE = (
            "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
            + "<soapenv:Body><ns:createResponse xmlns:ns=\"http://predic8.com/wsdl/shop/1/\"><cartId>42</cartId>"
            + "</ns:createResponse></soapenv:Body></soapenv:Envelope>").getBytes(UTF_8);

    /** The bytes of the original test's request, {@code src/test/resources/message.xml}. */
    private static final byte[] MESSAGE = message();

    /** Connect and read timeout of the raw socket, in milliseconds. */
    private static final int TIMEOUT_MILLIS = 10_000;

    /** A body many times the 16 KiB the guard reads from the socket at once. */
    private static final int LARGE_BODY_BYTES = 1024 * 1024;

    /** Bytes per write when a head is sent in fragments. */
    private static final int FRAGMENT_BYTES = 3;

    /** Pause between fragments, so that the listener reads them one by one. */
    private static final long FRAGMENT_PAUSE_MILLIS = 2;

    /** Largest chunk of a chunked request body. */
    private static final int CHUNK_BYTES = 64 * 1024;

    @RegisterExtension
    static WireMockExtension upstream = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().bindAddress(LOOPBACK).gzipDisabled(true))
            .build();

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void upstreamUrl(DynamicPropertyRegistry registry) {
        registry.add("proxy.upstream-url", () -> "http://" + LOOPBACK + ":" + upstream.getPort() + UPSTREAM_PATH);
    }

    /**
     * Scenario, then a whole request head that announces {@code message.xml} as its body; {@code {host}} is the
     * listener's authority and {@code {length}} the envelope's length.
     */
    private static Stream<Arguments> malformedHeads() {
        return Stream.of(
                Arguments.of("bare CR adds a second Authorization",
                        envelopeHead("X-Test: a\rAuthorization: Bearer SMUGGLED\r\n")),
                Arguments.of("bare LF adds a second Authorization",
                        envelopeHead("X-Test: a\nAuthorization: Bearer SMUGGLED\r\n")),
                Arguments.of("bare CR inside Authorization",
                        envelopeHead("Authorization: " + CALLER_AUTHORIZATION + "\rX-Injected: 1\r\n")),
                Arguments.of("bare CR inside SOAPAction",
                        envelopeHead("SOAPAction: \"create\"\rAuthorization: Bearer SMUGGLED\r\n")),
                Arguments.of("NUL", envelopeHead("X-Test: a\u0000b\r\n")),
                Arguments.of("VT", envelopeHead("X-Test: a\u000bb\r\n")),
                Arguments.of("FF", envelopeHead("X-Test: a\u000cb\r\n")),
                Arguments.of("DEL", envelopeHead("X-Test: a\u007fb\r\n")),
                Arguments.of("CR CR before the final LF",
                        "POST / HTTP/1.1\r\nHost: {host}\r\nContent-Type: text/xml\r\nContent-Length: {length}\r\n"
                                + "Connection: close\r\n\r\r\n"),
                Arguments.of("whitespace before a colon", envelopeHead("Authorization : Bearer SMUGGLED\r\n")),
                Arguments.of("header line without a colon", envelopeHead("X-Test\r\n")),
                Arguments.of("obs-fold on the first header line",
                        "POST / HTTP/1.1\r\n X-Test: a\r\nHost: {host}\r\nContent-Type: text/xml\r\n"
                                + "Content-Length: {length}\r\nConnection: close\r\n\r\n"),
                Arguments.of("bare LF ending the request line",
                        "POST / HTTP/1.1\nHost: {host}\r\nContent-Type: text/xml\r\nContent-Length: {length}\r\n"
                                + "Connection: close\r\n\r\n"),
                Arguments.of("Transfer-Encoding with Content-Length",
                        "POST / HTTP/1.1\r\nHost: {host}\r\nContent-Type: text/xml\r\nTransfer-Encoding: chunked\r\n"
                                + "Content-Length: {length}\r\nConnection: close\r\n\r\n"),
                Arguments.of("two Content-Length values",
                        "POST / HTTP/1.1\r\nHost: {host}\r\nContent-Type: text/xml\r\nContent-Length: {length}\r\n"
                                + "Content-Length: 1\r\nConnection: close\r\n\r\n"));
    }

    // The name leaves out the head, whose control characters the test reports cannot carry.
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("malformedHeads")
    void malformedRequestHeadIsRejectedAndNothingIsForwarded(String scenario, String head) throws IOException {
        stubCreate();

        String response = exchange(expand(head), MESSAGE);

        assertThat(statusLine(response)).as("%s: %s", scenario, response).isEqualTo("HTTP/1.1 400 Bad Request");
        assertThat(body(response)).as("%s: response body", scenario).isEmpty();
        assertThat(upstream.getAllServeEvents()).as("%s: requests the upstream received", scenario).isEmpty();
    }

    /**
     * The same writer with CRLF line ends only: the request is forwarded with the caller's headers and envelope as
     * sent, and the upstream's answer comes back.
     */
    @Test
    void wellFormedRequestIsForwardedWithTheCallersHeadersAndEnvelope() throws IOException {
        stubCreate();

        String response = exchange(expand(envelopeHead("X-Test: a\r\nAuthorization: " + CALLER_AUTHORIZATION
                + "\r\nSOAPAction: \"create\"\r\n")), MESSAGE);

        assertThat(statusLine(response)).as(response).isEqualTo("HTTP/1.1 200 OK");
        assertThat(body(response)).isEqualTo(new String(CREATE_RESPONSE, ISO_8859_1)).contains("<cartId>42</cartId>");
        LoggedRequest received = singleForwarded();
        assertThat(received.getUrl()).isEqualTo(UPSTREAM_PATH);
        assertThat(received.getHeaders().getHeader("Authorization").values()).containsExactly(CALLER_AUTHORIZATION);
        assertThat(received.getHeaders().getHeader("SOAPAction").values()).containsExactly("\"create\"");
        assertThat(received.getHeaders().getHeader("X-Test").values()).containsExactly("a");
        assertThat(received.containsHeader("X-Injected")).isFalse();
        assertThat(received.getBody()).isEqualTo(MESSAGE);
    }

    /**
     * Scenario, a well-formed request that keeps the connection open, and the body the upstream must receive from
     * it; {@code {host}} is the listener's authority and {@code {length}} the envelope's length.
     */
    private static Stream<Arguments> requestsAheadOfAMalformedOne() {
        String envelope = new String(MESSAGE, ISO_8859_1);
        return Stream.of(
                Arguments.of("request without a body",
                        "GET / HTTP/1.1\r\nHost: {host}\r\nAuthorization: " + CALLER_AUTHORIZATION + "\r\n\r\n", ""),
                Arguments.of("Content-Length envelope",
                        "POST / HTTP/1.1\r\nHost: {host}\r\nContent-Type: text/xml\r\nAuthorization: "
                                + CALLER_AUTHORIZATION + "\r\nContent-Length: {length}\r\n\r\n" + envelope,
                        envelope),
                Arguments.of("chunked envelope with an extension and a trailer",
                        "POST / HTTP/1.1\r\nHost: {host}\r\nContent-Type: text/xml\r\nAuthorization: "
                                + CALLER_AUTHORIZATION + "\r\nTransfer-Encoding: chunked\r\n\r\n"
                                + Integer.toHexString(100) + ";name=value\r\n" + envelope.substring(0, 100) + "\r\n"
                                + Integer.toHexString(envelope.length() - 100) + "\r\n" + envelope.substring(100)
                                + "\r\n0\r\nX-Trailer: t\r\n\r\n",
                        envelope));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("requestsAheadOfAMalformedOne")
    void malformedRequestPipelinedBehindAWellFormedOneIsRejectedOnceTheFirstIsForwarded(
            String scenario, String first, String forwardedBody) throws IOException {
        stubCreate();
        String malformed = "POST / HTTP/1.1\r\nHost: {host}\r\nContent-Length: 0\r\n"
                + "X-Test: a\rAuthorization: Bearer SMUGGLED\r\n\r\n";

        String responses = exchange(expand(first) + expand(malformed), new byte[0]);

        assertThat(statusLines(responses)).as("%s: %s", scenario, responses)
                .containsExactly("HTTP/1.1 200 OK", "HTTP/1.1 400 Bad Request");
        LoggedRequest received = singleForwarded();
        assertThat(received.getUrl()).isEqualTo(UPSTREAM_PATH);
        assertThat(received.getBodyAsString()).as(scenario).isEqualTo(forwardedBody);
        assertThat(received.getHeaders().getHeader("Authorization").values()).containsExactly(CALLER_AUTHORIZATION);
    }

    /**
     * Scenario, the header lines after the {@code Host} line, the status line, and the only {@code Authorization}
     * value the upstream receives or {@code null} when nothing is forwarded.
     */
    private static Stream<Arguments> fragmentedHeads() {
        return Stream.of(
                Arguments.of("well-formed",
                        "Content-Type: text/xml\r\nContent-Length: {length}\r\nX-Test: a\r\nAuthorization: "
                                + CALLER_AUTHORIZATION + "\r\nConnection: close\r\n\r\n",
                        "HTTP/1.1 200 OK", CALLER_AUTHORIZATION),
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
        stubCreate();

        String response = exchangeInFragments(expand("POST / HTTP/1.1\r\nHost: {host}\r\n" + afterHostLine),
                forwardedAuthorization == null ? new byte[0] : MESSAGE);

        assertThat(statusLine(response)).as("%s: %s", scenario, response).isEqualTo(statusLine);
        if (forwardedAuthorization == null) {
            assertThat(upstream.getAllServeEvents()).as("%s: requests the upstream received", scenario).isEmpty();
        } else {
            LoggedRequest received = singleForwarded();
            assertThat(received.getHeaders().getHeader("Authorization").values())
                    .containsExactly(forwardedAuthorization);
            assertThat(received.getHeaders().getHeader("X-Test").values()).containsExactly("a");
            assertThat(received.getBody()).isEqualTo(MESSAGE);
        }
    }

    /** An obs-fold line continues the value before it, joined by one SP, as Undertow folds it. */
    @Test
    void obsFoldLineContinuesTheHeaderValue() throws IOException {
        stubCreate();

        String response = exchange(expand(envelopeHead("X-Test: a\r\n \tb\r\n")), MESSAGE);

        assertThat(statusLine(response)).as(response).isEqualTo("HTTP/1.1 200 OK");
        assertThat(singleForwarded().getHeaders().getHeader("X-Test").values()).containsExactly("a b");
    }

    /** The guard releases body bytes as it reads them, whichever framing the caller chose. */
    @ParameterizedTest(name = "chunked: {0}")
    @ValueSource(booleans = {false, true})
    void bodySpanningManyReadsOfTheGuardIsForwardedByteForByte(boolean chunked) throws IOException {
        stubCreate();
        byte[] body = new byte[LARGE_BODY_BYTES];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i * 31 + 7);
        }

        String response = exchange(expand("POST / HTTP/1.1\r\nHost: {host}\r\n"
                + "Content-Type: text/xml\r\n"
                + (chunked ? "Transfer-Encoding: chunked\r\n" : "Content-Length: " + body.length + "\r\n")
                + "Connection: close\r\n\r\n"), chunked ? chunkedEncoding(body) : body);

        assertThat(statusLine(response)).as(response).isEqualTo("HTTP/1.1 200 OK");
        assertThat(singleForwarded().getBody()).isEqualTo(body);
    }

    /**
     * A {@code POST /} head carrying {@code message.xml}: request line, {@code Host}, {@code Content-Type},
     * {@code Content-Length}, then {@code lines} as given, then {@code Connection: close} and the blank line.
     */
    private static String envelopeHead(String lines) {
        return "POST / HTTP/1.1\r\nHost: {host}\r\nContent-Type: text/xml; charset=UTF-8\r\n"
                + "Content-Length: {length}\r\n" + lines + "Connection: close\r\n\r\n";
    }

    /** Answers the {@code POST} to {@code shop/ShopService} with the {@code cartId} envelope. */
    private static void stubCreate() {
        upstream.stubFor(post(urlEqualTo(UPSTREAM_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/xml;charset=UTF-8")
                .withBody(CREATE_RESPONSE)));
    }

    /** The one request the upstream received, after checking that it received exactly one and answered it. */
    private static LoggedRequest singleForwarded() {
        List<ServeEvent> received = upstream.getAllServeEvents();
        assertThat(received).as("requests the upstream received").hasSize(1);
        LoggedResponse answer = received.get(0).getResponse();
        assertThat(answer.getStatus()).as("upstream status, so a stub matched").isEqualTo(200);
        return received.get(0).getRequest();
    }

    /** {@code text} with {@code {host}} replaced by the listener's authority and {@code {length}} by the envelope's. */
    private String expand(String text) {
        return text.replace("{host}", LOOPBACK + ":" + port).replace("{length}", String.valueOf(MESSAGE.length));
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

    /**
     * Writes {@code head} as ISO-8859-1 bytes, one byte per character, and {@code body} as given in one write on a
     * new connection, and reads until the listener closes it; every exchange ends with {@code Connection: close} or
     * a request the listener refuses.
     */
    private String exchange(String head, byte[] body) throws IOException {
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        request.writeBytes(head.getBytes(ISO_8859_1));
        request.writeBytes(body);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(LOOPBACK, port), TIMEOUT_MILLIS);
            socket.setSoTimeout(TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write(request.toByteArray());
            out.flush();
            return new String(socket.getInputStream().readAllBytes(), ISO_8859_1);
        }
    }

    /**
     * Writes {@code head} as ISO-8859-1 bytes in writes of {@link #FRAGMENT_BYTES}, pausing between them, then
     * {@code body} in one write, and reads until the listener closes the connection.
     */
    private String exchangeInFragments(String head, byte[] body) throws IOException, InterruptedException {
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
            out.write(body);
            out.flush();
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

    /** The request envelope of the original test, read from the classpath. */
    private static byte[] message() {
        try {
            return new ClassPathResource("message.xml").getContentAsByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read message.xml from the test classpath", e);
        }
    }
}
