/**
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
 * What the Undertow listener refuses and accepts before the proxy runs.
 *
 * <ul>
 *   <li>A bare CR inside a request header value never splits the header into one the proxy forwards: the listener
 *       answers the request with 400 before any handler runs, so nothing reaches the upstream. Without that, a caller
 *       could add headers, a second {@code Authorization} among them, to the delegated upstream call. A bare LF and
 *       the other control characters except HTAB are answered the same way.</li>
 *   <li>A request body has no size limit, as on the original listener, although Undertow caps one at 2 MB unless
 *       configured otherwise.</li>
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

    /** A body larger than Undertow's default limit of 2 MB (2,097,152 bytes). */
    private static final int LARGE_BODY_BYTES = 3 * 1024 * 1024;

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

    // Undertow's own default would close the connection after 2 MB of either framing.
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
