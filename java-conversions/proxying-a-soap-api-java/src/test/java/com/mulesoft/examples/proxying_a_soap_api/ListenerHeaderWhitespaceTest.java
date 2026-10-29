/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_soap_api;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.NestedTestConfiguration;

import com.mulesoft.examples.proxying_a_soap_api.support.HttpTestClient;

/**
 * The fidelity contract for request header values: every value the caller sends reaches the upstream as sent, apart
 * from its leading and trailing SP and HTAB, which are not part of a field value, and an obs-fold, which arrives as
 * one SP. Inner runs of SP and HTAB, a single HTAB included, arrive unchanged, so the caller's {@code Authorization}
 * and {@code SOAPAction} reach the upstream unchanged, as the original forwarded the caller's headers.
 *
 * <p>Undertow 2.3.17's HTTP/1.1 parser turns every run of SP and HTAB inside a value into one SP, and no option turns
 * that off. The request-framing guard records each request head's fields before that parser runs, and the proxy
 * forwards the recorded values; the cases here fail as soon as the listener forwards anything else, a value byte for
 * byte included. Covered: the caller's {@code Authorization}, {@code SOAPAction} and a value with an inner double
 * space forwarded with the envelope byte for byte and the {@code cartId} answer returned; values with every mix of SP
 * and HTAB, in {@code Authorization}, {@code SOAPAction} and a single {@code Cookie} line too; an obs-fold; two
 * pipelined requests on one connection, each forwarding its own values; a {@code GET /?wsdl}, which the listener
 * answers itself, followed on the same connection by a forwarded request; several {@code Cookie} lines, forwarded as
 * the one value Undertow joins them into; and, in a context with an Undertow idle timeout, a {@code GET /?wsdl} and
 * two forwarded requests on one keep-alive connection, each forwarded request with its own values.
 *
 * <p>The application boots with its own {@code application.yml} on Undertow (random port on IPv4 loopback) with the
 * offline WSDL stand-in. ST-1 {@link HttpTestClient} writes each header line as given, a plain socket writes the
 * obs-fold and the requests that share a connection, and an upstream on a raw loopback socket records each request
 * head and body exactly as they arrive, which WireMock's parser would normalise itself.
 */
@SpringBootTest(
        classes = ProxyingASoapApiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Pins the listener to the address ST-1 connects to, whatever server.address the project sets.
        properties = {
                "server.address=" + ListenerHeaderWhitespaceTest.LOOPBACK,
                "proxy.wsdl-location=classpath:upstream/ShopService.wsdl"})
class ListenerHeaderWhitespaceTest {

    /** IPv4 loopback literal: {@code localhost} may resolve to {@code ::1}, where nothing listens. */
    static final String LOOPBACK = "127.0.0.1";

    /** The {@code http:request path} on the upstream. */
    private static final String UPSTREAM_PATH = "/shop/ShopService";

    private static final String SOAP11_CONTENT_TYPE = "text/xml; charset=UTF-8";

    /** The upstream's answer to every request, a {@code create} answer as one compact envelope. */
    private static final byte[] CREATE_RESPONSE = (
            "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
            + "<soapenv:Body><ns:createResponse xmlns:ns=\"http://predic8.com/wsdl/shop/1/\"><cartId>42</cartId>"
            + "</ns:createResponse></soapenv:Body></soapenv:Envelope>").getBytes(UTF_8);

    /** The bytes of the original test's request, {@code src/test/resources/message.xml}. */
    private static final byte[] MESSAGE = message();

    private static final RawUpstream UPSTREAM = new RawUpstream();

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void upstreamUrl(DynamicPropertyRegistry registry) {
        registry.add("proxy.upstream-url", () -> "http://" + LOOPBACK + ":" + UPSTREAM.port() + UPSTREAM_PATH);
    }

    @AfterAll
    static void closeUpstream() throws IOException {
        UPSTREAM.close();
    }

    @BeforeEach
    void forgetEarlierRequests() {
        UPSTREAM.requests.clear();
    }

    /**
     * The caller's credential, its SOAP action and a value with an inner double space reach the upstream as sent,
     * with the envelope byte for byte, and the upstream's {@code cartId} envelope comes back.
     */
    @Test
    void callerHeadersReachTheUpstreamAsSentWithTheEnvelopeUnchanged() throws InterruptedException {
        HttpTestClient.Response response = new HttpTestClient(LOOPBACK, port).sendHeaderPairs("POST", "/",
                List.of(Map.entry("Content-Type", SOAP11_CONTENT_TYPE),
                        Map.entry("Authorization", "Basic Y2FsbGVyOnB3"),
                        Map.entry("SOAPAction", "\"create\""),
                        Map.entry("X-Trace", "trace  42")),
                MESSAGE);

        assertThat(response.statusLine()).as(response::toString).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.body()).isEqualTo(CREATE_RESPONSE);
        assertThat(response.bodyAsString()).contains("<cartId>42</cartId>");
        RawRequest forwarded = UPSTREAM.nextRequest();
        assertThat(forwarded.head()).startsWith("POST " + UPSTREAM_PATH + " HTTP/1.1\r\n");
        assertThat(RawUpstream.values(forwarded.head(), "Authorization")).containsExactly("Basic Y2FsbGVyOnB3");
        assertThat(RawUpstream.values(forwarded.head(), "SOAPAction")).containsExactly("\"create\"");
        assertThat(RawUpstream.values(forwarded.head(), "X-Trace")).containsExactly("trace  42");
        assertThat(RawUpstream.values(forwarded.head(), "Content-Type")).containsExactly(SOAP11_CONTENT_TYPE);
        assertThat(forwarded.body()).isEqualTo(MESSAGE);
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
                Arguments.of("SOAPAction with two spaces", "SOAPAction",
                        "\"urn:create  cart\"", "\"urn:create  cart\""),
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
        HttpTestClient.Response response = new HttpTestClient(LOOPBACK, port).sendHeaderPairs("POST", "/",
                List.of(Map.entry("Content-Type", SOAP11_CONTENT_TYPE), Map.entry(name, sent)), MESSAGE);

        assertThat(response.statusLine()).as("%s: %s", scenario, response).isEqualTo("HTTP/1.1 200 OK");
        RawRequest request = UPSTREAM.nextRequest();
        List<byte[]> values = RawUpstream.values(request.head(), name).stream()
                .map(value -> value.getBytes(ISO_8859_1)).toList();
        assertThat(values).as("%s: %s lines the upstream received", scenario, name).hasSize(1);
        assertThat(values.get(0)).as(scenario).isEqualTo(forwarded.getBytes(US_ASCII));
        assertThat(request.body()).as("%s: envelope", scenario).isEqualTo(MESSAGE);
    }

    /** An obs-fold and the whitespace around it arrive as one SP; the runs inside each line arrive as sent. */
    @Test
    void obsFoldReachesTheUpstreamAsOneSpace() throws IOException, InterruptedException {
        try (Socket socket = connect()) {
            write(socket, envelopeRequest("X-Fold: a  b \t\r\n \tc\td\r\nConnection: close\r\n"));

            assertThat(readResponse(socket).statusLine()).isEqualTo("HTTP/1.1 200 OK");
        }

        assertThat(RawUpstream.values(UPSTREAM.nextRequest().head(), "X-Fold")).containsExactly("a  b c\td");
    }

    /**
     * Two requests written in one go on one connection: each reaches the upstream with its own values, which only
     * the whitespace between their words tells apart.
     */
    @Test
    void pipelinedRequestsEachForwardTheirOwnValues() throws IOException, InterruptedException {
        try (Socket socket = connect()) {
            write(socket, envelopeRequest("X-Order: a\tb\r\nAuthorization: Digest username=\"John\tSmith\"\r\n")
                    + envelopeRequest("X-Order: a  b\r\nAuthorization: Digest username=\"John  Smith\"\r\n"
                            + "Connection: close\r\n"));

            assertThat(readResponse(socket).statusLine()).isEqualTo("HTTP/1.1 200 OK");
            assertThat(readResponse(socket).statusLine()).isEqualTo("HTTP/1.1 200 OK");
        }

        RawRequest first = UPSTREAM.nextRequest();
        RawRequest second = UPSTREAM.nextRequest();
        assertThat(RawUpstream.values(first.head(), "X-Order")).containsExactly("a\tb");
        assertThat(RawUpstream.values(first.head(), "Authorization"))
                .containsExactly("Digest username=\"John\tSmith\"");
        assertThat(RawUpstream.values(second.head(), "X-Order")).containsExactly("a  b");
        assertThat(RawUpstream.values(second.head(), "Authorization"))
                .containsExactly("Digest username=\"John  Smith\"");
        assertThat(first.body()).isEqualTo(MESSAGE);
        assertThat(second.body()).isEqualTo(MESSAGE);
    }

    /**
     * {@code GET /?wsdl} is answered by the listener itself and never forwarded; the next request on the same
     * connection still forwards its own values, not the ones of the request before it.
     */
    @Test
    void requestAfterOneTheListenerAnswersItselfForwardsItsOwnValues() throws IOException, InterruptedException {
        try (Socket socket = connect()) {
            write(socket, "GET /?wsdl HTTP/1.1\r\nHost: " + authority() + "\r\nX-Order: a  b\r\n\r\n");
            RawResponse answered = readResponse(socket);
            write(socket, envelopeRequest("X-Order: a\tb\r\nConnection: close\r\n"));
            RawResponse forwarded = readResponse(socket);

            assertThat(answered.statusLine()).isEqualTo("HTTP/1.1 200 OK");
            assertThat(answered.body()).contains("wsdl:definitions");
            assertThat(forwarded.statusLine()).isEqualTo("HTTP/1.1 200 OK");
        }

        RawRequest request = UPSTREAM.nextRequest();
        assertThat(request.head()).startsWith("POST " + UPSTREAM_PATH + " ");
        assertThat(RawUpstream.values(request.head(), "X-Order")).containsExactly("a\tb");
        assertThat(UPSTREAM.requests).as("requests the upstream received besides the POST").isEmpty();
    }

    /** Several {@code Cookie} lines reach the upstream as the one {@code Cookie} value Undertow joins them into. */
    @Test
    void severalCookieLinesReachTheUpstreamAsOneJoinedValue() throws InterruptedException {
        HttpTestClient.Response response = new HttpTestClient(LOOPBACK, port).sendHeaderPairs("POST", "/",
                List.of(Map.entry("Content-Type", SOAP11_CONTENT_TYPE), Map.entry("Cookie", "a=1"),
                        Map.entry("Cookie", "b=2")),
                MESSAGE);

        assertThat(response.statusLine()).as(response::toString).isEqualTo("HTTP/1.1 200 OK");
        assertThat(RawUpstream.values(UPSTREAM.nextRequest().head(), "Cookie")).containsExactly("a=1; b=2");
    }

    /**
     * Under an Undertow idle timeout, which makes Undertow create each connection on a conduit of its own above the
     * request-framing guard, the requests of one keep-alive connection still each forward their own values as sent.
     */
    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(
            classes = ProxyingASoapApiApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                    "server.address=" + LOOPBACK,
                    "proxy.wsdl-location=classpath:upstream/ShopService.wsdl",
                    "server.undertow.options.server.IDLE_TIMEOUT=30000"})
    @DirtiesContext
    class UnderAnIdleTimeout {

        @LocalServerPort
        int idleListenerPort;

        @DynamicPropertySource
        static void idleListenerUpstreamUrl(DynamicPropertyRegistry registry) {
            registry.add("proxy.upstream-url", () -> "http://" + LOOPBACK + ":" + UPSTREAM.port() + UPSTREAM_PATH);
        }

        /**
         * A {@code GET /?wsdl} the listener answers itself, then two forwarded requests, on one connection: each
         * forwarded request carries its own values, not those of the request before it.
         */
        @Test
        void keepAliveRequestsEachForwardTheirOwnValuesAsSent() throws IOException, InterruptedException {
            try (Socket socket = connect(idleListenerPort)) {
                write(socket, "GET /?wsdl HTTP/1.1\r\nHost: " + authority(idleListenerPort)
                        + "\r\nX-Order: w  w\r\n\r\n");
                RawResponse answered = readResponse(socket);
                write(socket, envelopeRequest(idleListenerPort,
                        "X-Order: a  b\r\nAuthorization: Digest username=\"John  Smith\"\r\n"));
                RawResponse first = readResponse(socket);
                write(socket, envelopeRequest(idleListenerPort,
                        "X-Order: a\tb\r\nAuthorization: Digest username=\"John\tSmith\"\r\nConnection: close\r\n"));
                RawResponse second = readResponse(socket);

                assertThat(answered.statusLine()).isEqualTo("HTTP/1.1 200 OK");
                assertThat(answered.body()).contains("wsdl:definitions");
                assertThat(first.statusLine()).isEqualTo("HTTP/1.1 200 OK");
                assertThat(second.statusLine()).isEqualTo("HTTP/1.1 200 OK");
            }

            RawRequest one = UPSTREAM.nextRequest();
            RawRequest two = UPSTREAM.nextRequest();
            assertThat(UPSTREAM.requests).as("requests the upstream received besides the two POSTs").isEmpty();
            assertThat(RawUpstream.values(one.head(), "Authorization"))
                    .containsExactly("Digest username=\"John  Smith\"");
            assertThat(RawUpstream.values(one.head(), "X-Order")).containsExactly("a  b");
            assertThat(RawUpstream.values(two.head(), "Authorization"))
                    .containsExactly("Digest username=\"John\tSmith\"");
            assertThat(RawUpstream.values(two.head(), "X-Order")).containsExactly("a\tb");
            assertThat(one.body()).isEqualTo(MESSAGE);
            assertThat(two.body()).isEqualTo(MESSAGE);
        }
    }

    /**
     * A {@code POST /} carrying {@code message.xml}: request line, {@code Host}, {@code Content-Type},
     * {@code Content-Length}, then {@code lines} as given, the blank line and the envelope.
     */
    private String envelopeRequest(String lines) {
        return envelopeRequest(port, lines);
    }

    /** {@link #envelopeRequest(String)} for the listener on {@code listenerPort}. */
    private static String envelopeRequest(int listenerPort, String lines) {
        return "POST / HTTP/1.1\r\nHost: " + authority(listenerPort) + "\r\nContent-Type: " + SOAP11_CONTENT_TYPE
                + "\r\nContent-Length: " + MESSAGE.length + "\r\n" + lines + "\r\n" + new String(MESSAGE, ISO_8859_1);
    }

    /** A plain socket to the listener with the ST-1 timeouts. */
    private Socket connect() throws IOException {
        return connect(port);
    }

    /** {@link #connect()} to the listener on {@code listenerPort}. */
    private static Socket connect(int listenerPort) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(LOOPBACK, listenerPort), HttpTestClient.TIMEOUT_MILLIS);
        socket.setSoTimeout(HttpTestClient.TIMEOUT_MILLIS);
        return socket;
    }

    /** The listener's {@code 127.0.0.1:<port>}, sent as {@code Host}. */
    private String authority() {
        return authority(port);
    }

    /** {@link #authority()} of the listener on {@code listenerPort}. */
    private static String authority(int listenerPort) {
        return LOOPBACK + ":" + listenerPort;
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
        String head = RawUpstream.readHead(in);
        return new RawResponse(head.split("\r\n")[0],
                new String(in.readNBytes(RawUpstream.contentLength(head)), ISO_8859_1));
    }

    /** The request envelope of the original test, read from the classpath. */
    private static byte[] message() {
        try {
            return new ClassPathResource("message.xml").getContentAsByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read message.xml from the test classpath", e);
        }
    }

    /** A response's status line and body, one ISO-8859-1 character per byte. */
    private record RawResponse(String statusLine, String body) {
    }

    /** A request head without its blank line, one ISO-8859-1 character per byte, and its body bytes. */
    private record RawRequest(String head, byte[] body) {
    }

    /**
     * An HTTP/1.1 upstream on a raw loopback socket. It records every request head byte for byte, one ISO-8859-1
     * character per byte, with the body its {@code Content-Length} announces, and answers each request with the
     * {@code cartId} envelope and {@code Connection: close}.
     */
    private static final class RawUpstream implements AutoCloseable {

        private final ServerSocket server;

        private final BlockingQueue<RawRequest> requests = new LinkedBlockingQueue<>();

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

        /** Waits for the next request the upstream received. */
        RawRequest nextRequest() throws InterruptedException {
            RawRequest request = requests.poll(5, TimeUnit.SECONDS);
            assertThat(request).as("request received").isNotNull();
            return request;
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

        /** The {@code Content-Length} of {@code head}, or 0 when it has none. */
        static int contentLength(String head) {
            List<String> lengths = values(head, "Content-Length");
            return lengths.isEmpty() ? 0 : Integer.parseInt(lengths.get(0).trim());
        }

        @Override
        public void close() throws IOException {
            server.close();
        }

        private void acceptConnections() {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(HttpTestClient.TIMEOUT_MILLIS);
                    InputStream in = socket.getInputStream();
                    String head = readHead(in);
                    requests.add(new RawRequest(head, in.readNBytes(contentLength(head))));
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/xml;charset=UTF-8\r\nContent-Length: "
                            + CREATE_RESPONSE.length + "\r\nConnection: close\r\n\r\n").getBytes(US_ASCII));
                    out.write(CREATE_RESPONSE);
                    out.flush();
                } catch (IOException e) {
                    // A closed server socket ends the loop; a failed connection only loses its own request.
                }
            }
        }

        /** Reads byte by byte up to the blank line that ends the head, which is returned without it. */
        static String readHead(InputStream in) throws IOException {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            byte[] terminator = {'\r', '\n', '\r', '\n'};
            int matched = 0;
            while (matched < terminator.length) {
                int b = in.read();
                if (b == -1) {
                    throw new IOException("connection closed inside the head");
                }
                head.write(b);
                matched = b == terminator[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
            }
            String text = head.toString(ISO_8859_1);
            return text.substring(0, text.length() - terminator.length);
        }
    }
}
