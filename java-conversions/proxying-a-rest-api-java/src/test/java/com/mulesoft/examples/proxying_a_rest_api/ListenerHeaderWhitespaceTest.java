/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_rest_api;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.mulesoft.examples.proxying_a_rest_api.support.HttpTestClient;

/**
 * Pins the bytes of a request header value that reach the upstream when the caller's value holds runs of SP or HTAB,
 * as in a Digest credential with {@code username="John  Smith"}.
 *
 * <p>Undertow's HTTP/1.1 parser drops leading and trailing whitespace and keeps only the first SP or HTAB of every
 * run inside a value, and no option turns that off. The original Mule listener kept the value as sent, so the
 * caller's {@code Authorization} reaches the upstream unchanged only when it has no such run; this is a known
 * listener difference and a Tier 2A verification point. The cases here fail as soon as the listener forwards
 * anything else, a value byte for byte included.
 *
 * <p>The application boots with its own {@code application.yml} on Undertow (random port on IPv4 loopback). ST-1
 * {@link HttpTestClient} writes each header line as given, and an upstream on a raw loopback socket records the
 * request head exactly as it arrives, which WireMock's parser would normalise itself.
 */
@SpringBootTest(
        classes = ProxyingARestApiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Pins the listener to the address ST-1 connects to, whatever server.address the project sets.
        properties = "server.address=" + ListenerHeaderWhitespaceTest.LOOPBACK)
@ActiveProfiles("test")
class ListenerHeaderWhitespaceTest {

    /** IPv4 loopback literal: {@code localhost} may resolve to {@code ::1}, where nothing listens. */
    static final String LOOPBACK = "127.0.0.1";

    private static final RawUpstream UPSTREAM = new RawUpstream();

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void upstreamUrl(DynamicPropertyRegistry registry) {
        registry.add("proxy.upstream-url", () -> "http://" + LOOPBACK + ":" + UPSTREAM.port());
    }

    @AfterAll
    static void closeUpstream() throws IOException {
        UPSTREAM.close();
    }

    @BeforeEach
    void forgetEarlierRequests() {
        UPSTREAM.heads.clear();
    }

    /** Scenario, header name, value as the caller sends it, value bytes the upstream receives. */
    private static Stream<Arguments> headerValues() {
        return Stream.of(
                Arguments.of("Digest quoted-string with two spaces", "Authorization",
                        "Digest username=\"John  Smith\", realm=\"r\"", "Digest username=\"John Smith\", realm=\"r\""),
                Arguments.of("Bearer with leading, inner and trailing spaces", "Authorization",
                        "   Bearer   t1  ", "Bearer t1"),
                Arguments.of("four spaces", "X-Sp", "a    b", "a b"),
                Arguments.of("two tabs", "X-Tab", "a\t\tb", "a\tb"),
                Arguments.of("space then tab", "X-Mixed", "a \tb", "a b"),
                Arguments.of("tab then space", "X-Mixed", "a\t b", "a\tb"),
                // Values without a run of whitespace arrive unchanged.
                Arguments.of("Digest quoted-string with single spaces", "Authorization",
                        "Digest username=\"John Smith\", realm=\"r\"", "Digest username=\"John Smith\", realm=\"r\""),
                Arguments.of("single tab", "X-Tab", "a\tb", "a\tb"));
    }

    // The name leaves out the values, whose tabs the test reports cannot carry.
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("headerValues")
    void whitespaceRunsInAHeaderValueReachTheUpstreamAsTheListenerParsesThem(String scenario, String name,
                                                                             String sent, String forwarded)
            throws InterruptedException {
        HttpTestClient.Response response = new HttpTestClient(LOOPBACK, port)
                .sendHeaderPairs("GET", "/ws", List.of(Map.entry(name, sent)), null);

        assertThat(response.statusLine()).as("%s: %s", scenario, response).isEqualTo("HTTP/1.1 200 OK");
        List<byte[]> values = UPSTREAM.nextHeadValues(name);
        assertThat(values).as("%s: %s lines the upstream received", scenario, name).hasSize(1);
        assertThat(values.get(0)).as(scenario).isEqualTo(forwarded.getBytes(US_ASCII));
    }

    /**
     * An HTTP/1.1 upstream on a raw loopback socket. It records every request head byte for byte, one ISO-8859-1
     * character per byte, and answers each request with an empty 200 and {@code Connection: close}.
     */
    private static final class RawUpstream implements AutoCloseable {

        private final ServerSocket server;

        private final BlockingQueue<String> heads = new LinkedBlockingQueue<>();

        RawUpstream() {
            try {
                server = new ServerSocket();
                server.bind(new InetSocketAddress(InetAddress.getByName(LOOPBACK), 0));
            } catch (IOException e) {
                throw new IllegalStateException("cannot open the raw upstream on " + LOOPBACK, e);
            }
            Thread acceptor = new Thread(this::acceptConnections, "raw-upstream");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return server.getLocalPort();
        }

        /**
         * Waits for the next request head and returns the value bytes of each of its header lines named
         * {@code name}, ignoring case, in arrival order; a value is everything after the {@code ": "} the client
         * writes behind the name.
         */
        List<byte[]> nextHeadValues(String name) throws InterruptedException {
            String head = heads.poll(5, TimeUnit.SECONDS);
            assertThat(head).as("request head received").isNotNull();
            List<byte[]> values = new ArrayList<>();
            String[] lines = head.split("\r\n");
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(name)) {
                    assertThat(line.startsWith(": ", colon)).as("separator of %s", line).isTrue();
                    values.add(line.substring(colon + 2).getBytes(ISO_8859_1));
                }
            }
            return values;
        }

        @Override
        public void close() throws IOException {
            server.close();
        }

        private void acceptConnections() {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(HttpTestClient.TIMEOUT_MILLIS);
                    heads.add(readHead(socket.getInputStream()));
                    socket.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(US_ASCII));
                    socket.getOutputStream().flush();
                } catch (IOException e) {
                    // A closed server socket ends the loop; a failed connection only loses its own request.
                }
            }
        }

        /** Reads byte by byte up to the blank line that ends the head, which is returned without it. */
        private static String readHead(InputStream in) throws IOException {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            byte[] terminator = {'\r', '\n', '\r', '\n'};
            int matched = 0;
            while (matched < terminator.length) {
                int b = in.read();
                if (b == -1) {
                    throw new IOException("connection closed inside the request head");
                }
                head.write(b);
                matched = b == terminator[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
            }
            String text = head.toString(ISO_8859_1);
            return text.substring(0, text.length() - terminator.length);
        }
    }
}
