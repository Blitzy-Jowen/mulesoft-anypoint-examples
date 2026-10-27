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
import java.io.OutputStream;
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
import org.junit.jupiter.api.Test;
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
 * The fidelity contract for request header values: every value the caller sends reaches the upstream as sent, apart
 * from its leading and trailing SP and HTAB, which are not part of a field value, and an obs-fold, which arrives as
 * one SP. Inner runs of SP and HTAB, a single HTAB included, arrive unchanged, so the caller's {@code Authorization},
 * a Digest credential with {@code username="John  Smith"} for example, reaches the upstream unchanged.
 *
 * <p>Undertow 2.3.17's HTTP/1.1 parser turns every run of SP and HTAB inside a value into one SP, and no option turns
 * that off. The request-framing guard records each request head's fields before that parser runs, and the proxy
 * forwards the recorded values; the cases here fail as soon as the listener forwards anything else, a value byte for
 * byte included. Covered: values with every mix of SP and HTAB, in {@code Authorization} and a single
 * {@code Cookie} line too; an obs-fold; two pipelined requests on one connection, each forwarding its own values; a
 * request the listener answers itself ({@code OPTIONS *}) followed on the same connection by a forwarded one; and
 * several {@code Cookie} lines, forwarded as the one value Undertow joins them into.
 *
 * <p>The application boots with its own {@code application.yml} on Undertow (random port on IPv4 loopback). ST-1
 * {@link HttpTestClient} writes each header line as given, a plain socket writes the obs-fold and the requests that
 * share a connection, and an upstream on a raw loopback socket records the request head exactly as it arrives, which
 * WireMock's parser would normalise itself.
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
                        "Digest username=\"John  Smith\", realm=\"r\"", "Digest username=\"John  Smith\", realm=\"r\""),
                Arguments.of("Digest quoted-string with a tab", "Authorization",
                        "Digest username=\"John\tSmith\", realm=\"r\"", "Digest username=\"John\tSmith\", realm=\"r\""),
                Arguments.of("Digest quoted-string with single spaces", "Authorization",
                        "Digest username=\"John Smith\", realm=\"r\"", "Digest username=\"John Smith\", realm=\"r\""),
                Arguments.of("Bearer with leading, inner and trailing spaces", "Authorization",
                        "   Bearer   t1  ", "Bearer   t1"),
                Arguments.of("leading and trailing tabs and spaces", "X-Tab", "\t a\tb \t", "a\tb"),
                Arguments.of("four spaces", "X-Sp", "a    b", "a    b"),
                Arguments.of("two tabs", "X-Tab", "a\t\tb", "a\t\tb"),
                Arguments.of("space then tab", "X-Mixed", "a \tb", "a \tb"),
                Arguments.of("tab then space", "X-Mixed", "a\t b", "a\t b"),
                Arguments.of("single tab", "X-Tab", "a\tb", "a\tb"),
                Arguments.of("single Cookie line with a tab", "Cookie", "a=1;\tb=2", "a=1;\tb=2"));
    }

    // The name leaves out the values, whose tabs the test reports cannot carry.
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("headerValues")
    void headerValueReachesTheUpstreamAsSentApartFromItsOuterWhitespace(String scenario, String name, String sent,
                                                                        String forwarded)
            throws InterruptedException {
        HttpTestClient.Response response = new HttpTestClient(LOOPBACK, port)
                .sendHeaderPairs("GET", "/ws", List.of(Map.entry(name, sent)), null);

        assertThat(response.statusLine()).as("%s: %s", scenario, response).isEqualTo("HTTP/1.1 200 OK");
        List<byte[]> values = UPSTREAM.nextHeadValues(name);
        assertThat(values).as("%s: %s lines the upstream received", scenario, name).hasSize(1);
        assertThat(values.get(0)).as(scenario).isEqualTo(forwarded.getBytes(US_ASCII));
    }

    /** An obs-fold and the whitespace around it arrive as one SP; the runs inside each line arrive as sent. */
    @Test
    void obsFoldReachesTheUpstreamAsOneSpace() throws IOException, InterruptedException {
        try (Socket socket = connect()) {
            write(socket, "GET /ws HTTP/1.1\r\nHost: " + authority() + "\r\n"
                    + "X-Fold: a  b \t\r\n \tc\td\r\nConnection: close\r\n\r\n");

            assertThat(readResponse(socket).statusLine()).isEqualTo("HTTP/1.1 200 OK");
        }

        assertThat(RawUpstream.values(UPSTREAM.nextHead(), "X-Fold")).containsExactly("a  b c\td");
    }

    /**
     * Two requests written in one go on one connection: each reaches the upstream with its own values, which only
     * the whitespace between their words tells apart.
     */
    @Test
    void pipelinedRequestsEachForwardTheirOwnValues() throws IOException, InterruptedException {
        try (Socket socket = connect()) {
            write(socket, "GET /ws/1 HTTP/1.1\r\nHost: " + authority() + "\r\n"
                    + "X-Order: a\tb\r\nAuthorization: Digest username=\"John\tSmith\"\r\n\r\n"
                    + "GET /ws/2 HTTP/1.1\r\nHost: " + authority() + "\r\n"
                    + "X-Order: a  b\r\nAuthorization: Digest username=\"John  Smith\"\r\nConnection: close\r\n\r\n");

            assertThat(readResponse(socket).statusLine()).isEqualTo("HTTP/1.1 200 OK");
            assertThat(readResponse(socket).statusLine()).isEqualTo("HTTP/1.1 200 OK");
        }

        String first = UPSTREAM.nextHead();
        String second = UPSTREAM.nextHead();
        assertThat(first).startsWith("GET /ws/1 ");
        assertThat(RawUpstream.values(first, "X-Order")).containsExactly("a\tb");
        assertThat(RawUpstream.values(first, "Authorization"))
                .containsExactly("Digest username=\"John\tSmith\"");
        assertThat(second).startsWith("GET /ws/2 ");
        assertThat(RawUpstream.values(second, "X-Order")).containsExactly("a  b");
        assertThat(RawUpstream.values(second, "Authorization"))
                .containsExactly("Digest username=\"John  Smith\"");
    }

    /**
     * {@code OPTIONS *} is answered by the listener itself and never forwarded; the next request on the same
     * connection still forwards its own values, not the ones of the request before it.
     */
    @Test
    void requestAfterOneTheListenerAnswersItselfForwardsItsOwnValues() throws IOException, InterruptedException {
        try (Socket socket = connect()) {
            write(socket, "OPTIONS * HTTP/1.1\r\nHost: " + authority() + "\r\nX-Order: a  b\r\n\r\n");
            RawResponse answered = readResponse(socket);
            write(socket, "GET /ws HTTP/1.1\r\nHost: " + authority() + "\r\nX-Order: a\tb\r\n"
                    + "Connection: close\r\n\r\n");
            RawResponse forwarded = readResponse(socket);

            assertThat(answered.statusLine()).isEqualTo("HTTP/1.1 404 Not Found");
            assertThat(answered.body()).isEqualTo("No listener for endpoint: *");
            assertThat(forwarded.statusLine()).isEqualTo("HTTP/1.1 200 OK");
        }

        String head = UPSTREAM.nextHead();
        assertThat(head).startsWith("GET /ws ");
        assertThat(RawUpstream.values(head, "X-Order")).containsExactly("a\tb");
        assertThat(UPSTREAM.heads).as("requests the upstream received besides the GET").isEmpty();
    }

    /** Several {@code Cookie} lines reach the upstream as the one {@code Cookie} value Undertow joins them into. */
    @Test
    void severalCookieLinesReachTheUpstreamAsOneJoinedValue() throws InterruptedException {
        HttpTestClient.Response response = new HttpTestClient(LOOPBACK, port).sendHeaderPairs("GET", "/ws",
                List.of(Map.entry("Cookie", "a=1"), Map.entry("Cookie", "b=2")), null);

        assertThat(response.statusLine()).as(response::toString).isEqualTo("HTTP/1.1 200 OK");
        assertThat(RawUpstream.values(UPSTREAM.nextHead(), "Cookie")).containsExactly("a=1; b=2");
    }

    /** A plain socket to the listener with the ST-1 timeouts. */
    private Socket connect() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(LOOPBACK, port), HttpTestClient.TIMEOUT_MILLIS);
        socket.setSoTimeout(HttpTestClient.TIMEOUT_MILLIS);
        return socket;
    }

    /** The listener's {@code 127.0.0.1:<port>}, sent as {@code Host}. */
    private String authority() {
        return LOOPBACK + ":" + port;
    }

    /** Writes {@code bytes} as ISO-8859-1, one byte per character. */
    private static void write(Socket socket, String bytes) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(bytes.getBytes(ISO_8859_1));
        out.flush();
    }

    /** Reads one response: its head, then as many body bytes as its {@code Content-Length} names, none without one. */
    private static RawResponse readResponse(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        String[] head = RawUpstream.readHead(in).split("\r\n");
        int bodyLength = 0;
        for (String line : head) {
            if (line.regionMatches(true, 0, "Content-Length:", 0, "Content-Length:".length())) {
                bodyLength = Integer.parseInt(line.substring("Content-Length:".length()).trim());
            }
        }
        return new RawResponse(head[0], new String(in.readNBytes(bodyLength), ISO_8859_1));
    }

    /** A response's status line and body, one ISO-8859-1 character per byte. */
    private record RawResponse(String statusLine, String body) {
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
         * {@code name}, as {@link #values} reads them.
         */
        List<byte[]> nextHeadValues(String name) throws InterruptedException {
            return values(nextHead(), name).stream().map(value -> value.getBytes(ISO_8859_1)).toList();
        }

        /** Waits for the next request head, one ISO-8859-1 character per byte, without the blank line ending it. */
        String nextHead() throws InterruptedException {
            String head = heads.poll(5, TimeUnit.SECONDS);
            assertThat(head).as("request head received").isNotNull();
            return head;
        }

        /**
         * The value of each header line of {@code head} named {@code name}, ignoring case, in arrival order, one
         * ISO-8859-1 character per byte; a value is everything after the {@code ": "} the client writes behind the
         * name.
         */
        static List<String> values(String head, String name) {
            List<String> values = new ArrayList<>();
            String[] lines = head.split("\r\n");
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(name)) {
                    assertThat(line.startsWith(": ", colon)).as("separator of %s", line).isTrue();
                    values.add(line.substring(colon + 2));
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
