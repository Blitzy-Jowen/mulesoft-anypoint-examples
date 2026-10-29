/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_soap_api.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.headRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.HttpHostConnectException;
import org.apache.hc.core5.http.NoHttpResponseException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.function.ThrowingConsumer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.client.ResourceAccessException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.diff.Diff;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.mulesoft.examples.proxying_a_soap_api.config.WsdlQueryPublisher;
import com.mulesoft.examples.proxying_a_soap_api.mapper.ProxyHeaderMapper;

/**
 * The contract of {@link ShopServiceClient} against a WireMock stand-in for {@code www.predic8.com:8080}, the
 * original {@code HTTP_Request_Configuration} with its {@code http:request method="POST" path="shop/ShopService"}
 * and the {@code cxf:proxy-service wsdlLocation} read ({@code soap-api-proxy.xml:4,7,10}).
 *
 * <p>{@code post} sends exactly one HTTP/1.1 POST carrying the envelope bytes and the given headers unchanged,
 * {@code Expect} and {@code Upgrade} included, returns every upstream status as a value with its headers and exact
 * body, follows redirects while carrying the caller's credentials on the upstream's origin only, and turns a timeout,
 * a body stalled in either direction or a refused connection into one {@link ResourceAccessException} without
 * retrying; a raw loopback upstream stages the exchanges WireMock cannot. {@code wsdlResource} reads an
 * {@code http} WSDL and the schemas it reaches under the same timeout, each absolute reference location replaced by a
 * key it resolves back, so {@code WsdlQueryPublisher} serves the whole closure through the proxy; it resolves any
 * other location through the {@code ResourceLoader}. Only localhost is ever contacted.
 */
class ShopServiceClientTest {

    private static final String UPSTREAM_PATH = "/shop/ShopService";

    private static final String WSDL_PATH_AND_QUERY = "/shop/ShopService?wsdl";

    private static final long TIMEOUT_MS = 1000L;

    private static final String CLASSPATH_WSDL = "classpath:upstream/ShopService.wsdl";

    /** A user, password and query token no failure may quote, assembled so that no literal URL carries credentials. */
    private static final String USER = "alice";

    private static final String PASSWORD = "s3cr3t";

    private static final String TOKEN = "t0k";

    private static final String USER_INFO = USER + ":" + PASSWORD + "@";

    private static final byte[] SOAP_RESPONSE =
            ("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                    + "<soap:Body><ns:createResponse xmlns:ns=\"http://predic8.com/wsdl/shop/1/\"><cartId>42</cartId>"
                    + "</ns:createResponse></soap:Body></soap:Envelope>").getBytes(UTF_8);

    private static final byte[] SOAP_FAULT =
            ("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                    + "<soap:Body><soap:Fault><faultcode>soap:Server</faultcode><faultstring>boom</faultstring>"
                    + "</soap:Fault></soap:Body></soap:Envelope>").getBytes(UTF_8);

    // gzip disabled: WireMock must serve the stubbed bytes exactly as given.
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true))
            .build();

    /** Every client a test creates, closed after it so that no connection pool or deadline thread outlives it. */
    private final List<ShopServiceClient> clients = new ArrayList<>();

    /**
     * One exchange with the stand-in before the first test, under a generous timeout and then forgotten: a freshly
     * started WireMock pays its class loading on its first answer, which on a loaded build host takes longer than
     * {@link #TIMEOUT_MS} and would fail whichever test happens to run first.
     */
    @BeforeAll
    static void warmUpTheStandIn() {
        wm.stubFor(post(urlEqualTo(UPSTREAM_PATH)).willReturn(ok()));
        try (ShopServiceClient client = new ShopServiceClient(new DefaultResourceLoader(),
                wm.baseUrl() + UPSTREAM_PATH, CLASSPATH_WSDL, 30_000L)) {
            client.post(soapHeaders(), new byte[0]);
        }
        wm.resetAll();
    }

    @AfterEach
    void closeClients() {
        clients.forEach(ShopServiceClient::close);
    }

    private ShopServiceClient client() {
        return client(wm.baseUrl() + WSDL_PATH_AND_QUERY);
    }

    private ShopServiceClient client(String wsdlLocation) {
        return client(wm.baseUrl() + UPSTREAM_PATH, wsdlLocation);
    }

    private ShopServiceClient client(String upstreamUrl, String wsdlLocation) {
        ShopServiceClient client = new ShopServiceClient(new DefaultResourceLoader(), upstreamUrl, wsdlLocation,
                TIMEOUT_MS);
        clients.add(client);
        return client;
    }

    private byte[] classpathBytes(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/" + name)) {
            assertThat(in).as("test resource %s", name).isNotNull();
            return in.readAllBytes();
        }
    }

    /** The original test's request, {@code message.xml}: a 248-byte SOAP 1.1 {@code create} envelope. */
    private byte[] envelope() throws IOException {
        return classpathBytes("message.xml");
    }

    private static HttpHeaders soapHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, "text/xml; charset=UTF-8");
        headers.add("SOAPAction", "\"\"");
        return headers;
    }

    private static byte[] read(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        }
    }

    private static LoggedRequest singlePost() {
        List<LoggedRequest> requests = wm.findAll(postRequestedFor(urlEqualTo(UPSTREAM_PATH)));
        assertThat(requests).hasSize(1);
        return requests.get(0);
    }

    private static List<Throwable> causeChain(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable cause = failure; cause != null && !chain.contains(cause); cause = cause.getCause()) {
            chain.add(cause);
        }
        return chain;
    }

    /** What a raw upstream does on a connection once it has read the request head. */
    @FunctionalInterface
    interface Responder {
        void respond(RawUpstream upstream, RequestHead head, InputStream in, OutputStream out)
                throws IOException, InterruptedException;
    }

    /** A request head exactly as a raw upstream received it; header names match ignoring case. */
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
     * An HTTP/1.1 upstream on a raw loopback socket, for exchanges WireMock cannot stage: its Jetty acts on
     * {@code Expect} itself and answers 400 to an {@code Upgrade} without {@code Connection: upgrade}, which is what
     * the proxy sends once {@link ProxyHeaderMapper} has removed the caller's {@code Connection}. It counts the
     * connections it accepts, records each request head exactly as received, lets its {@link Responder} answer, and
     * then reads until the client closes the connection, which it reports.
     */
    static final class RawUpstream implements AutoCloseable {

        private final ServerSocket server;

        private final Responder responder;

        private final AtomicInteger accepted = new AtomicInteger();

        private final BlockingQueue<RequestHead> heads = new LinkedBlockingQueue<>();

        private final BlockingQueue<byte[]> bodies = new LinkedBlockingQueue<>();

        private final AtomicInteger bytesBeforeContinue = new AtomicInteger(-1);

        private final CountDownLatch clientClosed = new CountDownLatch(1);

        private final CountDownLatch upstreamClosed = new CountDownLatch(1);

        private final CountDownLatch released = new CountDownLatch(1);

        private final List<Socket> sockets = new CopyOnWriteArrayList<>();

        /** The connection the calling thread serves, for a responder that acts on the socket itself. */
        private final ThreadLocal<Socket> serving = new ThreadLocal<>();

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

        /** Answers {@code Expect: 100-continue}, records the body that follows, then answers 200 with an envelope. */
        static void continueThenAnswer(RawUpstream upstream, RequestHead head, InputStream in, OutputStream out)
                throws IOException, InterruptedException {
            // Long enough to see a client that sends its body without waiting, well inside the client's own wait.
            TimeUnit.MILLISECONDS.sleep(200);
            upstream.bytesBeforeContinue.set(in.available());
            out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(US_ASCII));
            out.flush();
            upstream.bodies.add(in.readNBytes(Integer.parseInt(head.headers().get(HttpHeaders.CONTENT_LENGTH))));
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/xml;charset=UTF-8\r\nContent-Length: "
                    + SOAP_RESPONSE.length + "\r\n\r\n").getBytes(US_ASCII));
            out.write(SOAP_RESPONSE);
        }

        /** Sends the status line and headers at once, then 10 of the 1000 body bytes they announce, and stalls. */
        static void sendHeadersThenStall(RawUpstream upstream, RequestHead head, InputStream in, OutputStream out)
                throws IOException {
            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: 1000\r\n\r\n".getBytes(US_ASCII));
            out.write(new byte[10]);
        }

        /** Sends a 404 status line and headers at once, then 10 of the 1000 body bytes they announce, and stalls. */
        static void sendNotFoundHeadersThenStall(RawUpstream upstream, RequestHead head, InputStream in,
                                                 OutputStream out) throws IOException {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: 1000\r\n\r\n"
                    .getBytes(US_ASCII));
            out.write(new byte[10]);
        }

        /** Reads nothing of the request body until {@link #release()}, so the client's writes block. */
        static void neverReadBody(RawUpstream upstream, RequestHead head, InputStream in, OutputStream out)
                throws InterruptedException {
            upstream.released.await(10, TimeUnit.SECONDS);
        }

        /**
         * Reads the request body, answers 200 with an envelope framed by {@code Content-Length} and without
         * {@code Connection: close}, so the client keeps the connection for reuse, then closes the connection itself.
         */
        static void answerThenClose(RawUpstream upstream, RequestHead head, InputStream in, OutputStream out)
                throws IOException {
            // Read in full: a socket closed with unread request bytes resets the connection instead of ending it.
            upstream.bodies.add(in.readNBytes(Integer.parseInt(head.headers().get(HttpHeaders.CONTENT_LENGTH))));
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/xml;charset=UTF-8\r\nContent-Length: "
                    + SOAP_RESPONSE.length + "\r\n\r\n").getBytes(US_ASCII));
            out.write(SOAP_RESPONSE);
            out.flush();
            // Closing the socket's stream closes the socket, which sends the client its end of stream.
            out.close();
            upstream.upstreamClosed.countDown();
        }

        /** Closes the connection without answering, so the client reads an end of stream where the status should be. */
        static void closeWithoutAnswering(RawUpstream upstream, RequestHead head, InputStream in, OutputStream out)
                throws IOException {
            out.close();
        }

        /** Resets the connection without answering: with a zero linger time, closing it sends a TCP reset. */
        static void resetWithoutAnswering(RawUpstream upstream, RequestHead head, InputStream in, OutputStream out)
                throws IOException {
            Socket socket = upstream.serving.get();
            socket.setSoLinger(true, 0);
            socket.close();
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

        byte[] nextBody() throws InterruptedException {
            byte[] body = bodies.poll(5, TimeUnit.SECONDS);
            assertThat(body).as("request body received").isNotNull();
            return body;
        }

        RequestHead nextRequestHead() throws InterruptedException {
            RequestHead head = heads.poll(5, TimeUnit.SECONDS);
            assertThat(head).as("request head received").isNotNull();
            return head;
        }

        int accepted() {
            return accepted.get();
        }

        /** Request heads received and not yet taken by {@link #nextRequestHead()}; at most one per connection. */
        int requestsReceived() {
            return heads.size();
        }

        /** Lets a {@link #neverReadBody} connection go on to read, so it can see the client close. */
        void release() {
            released.countDown();
        }

        boolean awaitClientClose(Duration timeout) throws InterruptedException {
            return clientClosed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        /** Waits until an {@link #answerThenClose} connection has been closed from the upstream's side. */
        boolean awaitUpstreamClose(Duration timeout) throws InterruptedException {
            return upstreamClosed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
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
            serving.set(socket);
            try (socket) {
                InputStream in = socket.getInputStream();
                RequestHead head = RequestHead.parse(readHead(in));
                heads.add(head);
                OutputStream out = socket.getOutputStream();
                responder.respond(this, head, in, out);
                out.flush();
                in.transferTo(OutputStream.nullOutputStream());
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

    static Stream<Arguments> invalidSettings() {
        String upstream = "http://localhost/shop/ShopService";
        return Stream.of(
                Arguments.of("blank upstream URL", " ", CLASSPATH_WSDL, TIMEOUT_MS),
                Arguments.of("relative upstream URL", UPSTREAM_PATH, CLASSPATH_WSDL, TIMEOUT_MS),
                Arguments.of("non-HTTP upstream URL", "ftp://localhost/shop/ShopService", CLASSPATH_WSDL, TIMEOUT_MS),
                Arguments.of("blank WSDL location", upstream, "", TIMEOUT_MS),
                Arguments.of("zero timeout", upstream, CLASSPATH_WSDL, 0L));
    }

    // Bound to proxy.upstream-url, proxy.wsdl-location and proxy.response-timeout-ms: a bad value fails start-up.
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSettings")
    void invalidSettingsAreRejectedAtConstruction(String description, String upstreamUrl, String wsdlLocation,
                                                  long timeoutMs) {
        assertThrows(IllegalArgumentException.class,
                () -> new ShopServiceClient(new DefaultResourceLoader(), upstreamUrl, wsdlLocation, timeoutMs));
    }

    static Stream<Arguments> invalidUrlSettings() {
        String upstream = "http://localhost/shop/ShopService";
        return Stream.of(
                Arguments.of("unparseable WSDL location", "proxy.wsdl-location", IllegalStateException.class,
                        upstream, "http://" + USER_INFO + "localhost/x y?token=" + TOKEN),
                Arguments.of("WSDL location without a valid URL port", "proxy.wsdl-location",
                        IllegalStateException.class, upstream,
                        "https://" + USER_INFO + "localhost:99999999999/x?token=" + TOKEN),
                Arguments.of("unparseable upstream URL", "proxy.upstream-url", IllegalArgumentException.class,
                        "http://" + USER_INFO + "localhost/x y?token=" + TOKEN, CLASSPATH_WSDL));
    }

    // A URL setting may carry credentials or a token: the start-up failure names the key, never the value.
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidUrlSettings")
    void invalidUrlSettingFailsWithoutQuotingItsValue(String description, String key,
                                                      Class<? extends RuntimeException> type, String upstreamUrl,
                                                      String wsdlLocation) {
        Throwable failure = catchThrowable(() -> client(upstreamUrl, wsdlLocation).wsdlResource());

        assertThat(failure).isExactlyInstanceOf(type).hasMessageContaining(key);
        assertThat(causeChain(failure)).extracting(Throwable::toString)
                .allSatisfy(text -> assertThat(text).doesNotContain(USER, PASSWORD, TOKEN));
    }

    // The caller's Authorization and SOAPAction reach predic8 unchanged: the proxy forwards the caller's identity.
    @Test
    void postSendsTheEnvelopeBytesAndEveryGivenHeaderUnchanged() throws IOException {
        wm.stubFor(post(urlEqualTo(UPSTREAM_PATH)).willReturn(ok()));
        byte[] bytes = envelope();
        HttpHeaders headers = soapHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer abc");
        headers.add("X-Correlation-Id", "c-1");
        headers.add("X-Multi", "a");
        headers.add("X-Multi", "b");

        ResponseEntity<byte[]> response = client().post(headers, bytes);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        wm.verify(1, postRequestedFor(urlEqualTo(UPSTREAM_PATH))
                .withRequestBody(binaryEqualTo(bytes))
                .withHeader(HttpHeaders.CONTENT_TYPE, equalTo("text/xml; charset=UTF-8"))
                .withHeader("SOAPAction", equalTo("\"\""))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer abc"))
                .withHeader("X-Correlation-Id", equalTo("c-1"))
                .withHeader(HttpHeaders.CONTENT_LENGTH, equalTo(String.valueOf(bytes.length))));
        assertThat(singlePost().getHeaders().getHeader("X-Multi").values()).containsExactly("a", "b");
    }

    static Stream<Arguments> upstreamResponses() {
        return Stream.of(
                Arguments.of(200, "text/xml;charset=UTF-8", SOAP_RESPONSE),
                // A one-way operation's answer: no body, returned as byte[0], never null.
                Arguments.of(202, "text/xml", new byte[0]),
                Arguments.of(404, "text/plain", "Not Found".getBytes(UTF_8)),
                Arguments.of(500, "text/xml", SOAP_FAULT));
    }

    // Deciding which status is a failure is the proxy service's job; the client only transports.
    @ParameterizedTest(name = "HTTP {0}")
    @MethodSource("upstreamResponses")
    void everyUpstreamStatusIsReturnedAsAValueWithItsHeadersAndExactBody(int status, String contentType, byte[] body)
            throws IOException {
        wm.stubFor(post(urlEqualTo(UPSTREAM_PATH)).willReturn(aResponse().withStatus(status)
                .withHeader(HttpHeaders.CONTENT_TYPE, contentType)
                .withHeader("X-Upstream-Trace", "t1")
                .withBody(body)));
        ShopServiceClient client = client();
        HttpHeaders headers = soapHeaders();
        byte[] bytes = envelope();

        ResponseEntity<byte[]> response = assertDoesNotThrow(() -> client.post(headers, bytes));

        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType(contentType));
        assertThat(response.getHeaders().getFirst("X-Upstream-Trace")).isEqualTo("t1");
        assertThat(response.getBody()).isEqualTo(body);
    }

    static Stream<Arguments> redirects() {
        return Stream.of(
                Arguments.of("on the upstream's origin", "/shop/Moved", true),
                // 127.0.0.1 reaches the same stand-in under another host name, hence on another origin.
                Arguments.of("to another origin", "http://127.0.0.1:%d/shop/Moved", false));
    }

    // A POST answered with 302 continues as a GET. The caller's Authorization follows a redirect only while it stays
    // on the upstream's origin; its other headers always do.
    @ParameterizedTest(name = "{0}")
    @MethodSource("redirects")
    void redirectIsFollowedToTheFinalResponse(String description, String location, boolean authorizationKept)
            throws IOException {
        wm.stubFor(post(urlEqualTo(UPSTREAM_PATH)).willReturn(aResponse().withStatus(302)
                .withHeader(HttpHeaders.LOCATION, String.format(location, wm.getPort()))));
        wm.stubFor(any(urlEqualTo("/shop/Moved")).willReturn(ok("moved")));
        HttpHeaders headers = soapHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer abc");
        headers.add("X-Correlation-Id", "c-1");

        ResponseEntity<byte[]> response = client().post(headers, envelope());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("moved".getBytes(UTF_8));
        wm.verify(1, postRequestedFor(urlEqualTo(UPSTREAM_PATH)));
        wm.verify(1, anyRequestedFor(urlEqualTo("/shop/Moved")));
        List<LoggedRequest> followed = wm.findAll(getRequestedFor(urlEqualTo("/shop/Moved")));
        assertThat(followed).hasSize(1);
        assertThat(followed.get(0).getHeader("X-Correlation-Id")).isEqualTo("c-1");
        assertThat(followed.get(0).containsHeader(HttpHeaders.AUTHORIZATION)).isEqualTo(authorizationKept);
    }

    // A timed-out envelope is never re-sent: predic8 may already have acted on it.
    @Test
    void timeoutFailsWithinTheConfiguredLimitAndIsNotRetried() throws IOException {
        wm.stubFor(post(urlEqualTo(UPSTREAM_PATH)).willReturn(ok().withFixedDelay(3000)));
        ShopServiceClient client = client();
        HttpHeaders headers = soapHeaders();
        byte[] bytes = envelope();

        long started = System.nanoTime();
        assertThrows(ResourceAccessException.class, () -> client.post(headers, bytes));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(elapsedMs).as("elapsed until the %d ms timeout fired", TIMEOUT_MS).isLessThan(2900);
        // WireMock journals a request on arrival, so any retry made before the failure surfaced is already counted.
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> wm.verify(exactly(1), postRequestedFor(urlEqualTo(UPSTREAM_PATH))));
        wm.verify(exactly(1), postRequestedFor(urlEqualTo(UPSTREAM_PATH)));
    }

    @Test
    void refusedConnectionFailsWithResourceAccessException() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        ShopServiceClient client = client("http://localhost:" + port + UPSTREAM_PATH,
                wm.baseUrl() + WSDL_PATH_AND_QUERY);
        HttpHeaders headers = soapHeaders();
        byte[] bytes = envelope();

        ResourceAccessException failure = assertThrows(ResourceAccessException.class,
                () -> client.post(headers, bytes));

        assertThat(causeChain(failure)).hasAtLeastOneElementOfType(ConnectException.class);
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    static Stream<Arguments> transportFailures() {
        return Stream.of(
                // No answer: nothing listens on the upstream port.
                Arguments.of("refused connection", null, ConnectException.class, "I/O error: Connection refused"),
                Arguments.of("read timeout", ok().withFixedDelay(3000), SocketTimeoutException.class,
                        "I/O error: Read timed out"),
                Arguments.of("connection reset", aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER),
                        SocketException.class, "I/O error: Connection reset"),
                Arguments.of("empty response", aResponse().withFault(Fault.EMPTY_RESPONSE),
                        NoHttpResponseException.class, "I/O error: The target server failed to respond"));
    }

    // The caller's 500 body carries the message: it names the failure, never predic8's URL, host, address or port.
    @ParameterizedTest(name = "{0}")
    @MethodSource("transportFailures")
    void transportFailureIsReportedWithoutTheUpstreamLocation(String description, ResponseDefinitionBuilder answer,
                                                             Class<? extends IOException> causeType,
                                                             String expectedMessage) throws IOException {
        int port;
        if (answer == null) {
            port = closedPort();
        } else {
            wm.stubFor(post(urlEqualTo(UPSTREAM_PATH)).willReturn(answer));
            port = wm.getPort();
        }
        ShopServiceClient client = client("http://localhost:" + port + UPSTREAM_PATH, CLASSPATH_WSDL);
        HttpHeaders headers = soapHeaders();
        byte[] bytes = envelope();

        ResourceAccessException failure = assertThrows(ResourceAccessException.class,
                () -> client.post(headers, bytes));

        assertThat(failure).hasMessage(expectedMessage);
        assertThat(failure.getMessage()).doesNotContain("://", "127.0.0.1", "localhost", String.valueOf(port));
        assertThat(failure.getCause()).isInstanceOf(causeType);
    }

    private static <T extends Throwable> T causedBy(T failure, Throwable cause) {
        failure.initCause(cause);
        return failure;
    }

    private static IOException cyclicChainNamingLocations() {
        IOException outer = new IOException("Cannot reach http://shop.example/shop/ShopService");
        IOException inner = new IOException("Redirected to http://other.example/");
        outer.initCause(inner);
        inner.initCause(outer);
        return outer;
    }

    static Stream<Arguments> ioFailureReasons() {
        String endpoint = "Connect to http://shop.example:8080 [shop.example/10.0.0.7]";
        return Stream.of(
                Arguments.of("unknown host, whose message is the host", new UnknownHostException("shop.example"),
                        "I/O error: Unknown host"),
                Arguments.of("connect failure naming the endpoint",
                        new HttpHostConnectException(endpoint + " failed: Connection refused"),
                        "I/O error: Connection refused"),
                Arguments.of("connect timeout naming the endpoint",
                        new ConnectTimeoutException(endpoint + " failed: Connect timed out"),
                        "I/O error: Connect timed out"),
                Arguments.of("connect failure without the socket's text", new HttpHostConnectException(endpoint
                        + " refused"), "I/O error"),
                Arguments.of("URL", new IOException("Circular redirect to 'http://other.example/x'"), "I/O error"),
                Arguments.of("upstream host in another letter case", new IOException("SHOP.EXAMPLE closed the stream"),
                        "I/O error"),
                Arguments.of("IPv4 literal", new IOException("No route to 10.0.0.7"), "I/O error"),
                Arguments.of("bracketed address", new IOException("Peer [::1] went away"), "I/O error"),
                Arguments.of("angle-bracketed host name",
                        new SSLPeerUnverifiedException("Certificate for <other.example> doesn't match"), "I/O error"),
                Arguments.of("no message", new IOException((String) null), "I/O error"),
                Arguments.of("blank message", new IOException(" "), "I/O error"),
                Arguments.of("no cause", null, "I/O error"),
                Arguments.of("location-bearing wrapper of a usable cause",
                        new IOException("Cannot reach http://shop.example", new SocketException("Connection reset")),
                        "I/O error: Connection reset"),
                Arguments.of("write timeout over the socket failure its deadline caused",
                        causedBy(new SocketTimeoutException("Request body write timed out after 1000 ms"),
                                new SocketException("Socket closed")),
                        "I/O error: Request body write timed out after 1000 ms"),
                Arguments.of("cyclic chain naming locations only", cyclicChainNamingLocations(), "I/O error"));
    }

    // RestClient's own message quotes the upstream URL; the reported one keeps the I/O failure as its cause only.
    @ParameterizedTest(name = "{0}")
    @MethodSource("ioFailureReasons")
    void ioFailureMessageNeverNamesALocation(String description, IOException cause, String expectedMessage) {
        String restClientMessage = "I/O error on POST request for \"http://shop.example/shop/ShopService\": "
                + (cause != null ? cause.getMessage() : null);
        ResourceAccessException failure = cause != null
                ? new ResourceAccessException(restClientMessage, cause)
                : new ResourceAccessException(restClientMessage);

        ResourceAccessException reported = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> ShopServiceClient.withoutLocation(failure, "Shop.Example"));

        assertThat(reported).hasMessage(expectedMessage);
        assertThat(reported.getCause()).isSameAs(cause);
    }

    // SC-13 keeps Expect and Upgrade, so they reach predic8 as the caller sent them, and Expect keeps its meaning:
    // the envelope follows the upstream's 100 Continue. Host and Connection are the client's own.
    @Test
    void expectAndUpgradeReachTheUpstreamThroughTheHeaderMapping() throws Exception {
        try (RawUpstream upstream = new RawUpstream(RawUpstream::continueThenAnswer)) {
            HttpHeaders inbound = soapHeaders();
            inbound.add(HttpHeaders.HOST, "proxy.example:8081");
            inbound.add(HttpHeaders.CONNECTION, "close");
            inbound.add(HttpHeaders.EXPECT, "100-continue");
            // A token no server implements, so the upstream never attempts a protocol switch.
            inbound.add(HttpHeaders.UPGRADE, "custom");
            inbound.add("X-Keep", "1");
            byte[] bytes = envelope();
            ShopServiceClient client = client(upstream.baseUrl() + UPSTREAM_PATH, CLASSPATH_WSDL);

            ResponseEntity<byte[]> response = client.post(new ProxyHeaderMapper().toUpstream(inbound), bytes);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isEqualTo(SOAP_RESPONSE);
            RequestHead head = upstream.nextRequestHead();
            assertThat(head.requestLine()).isEqualTo("POST " + UPSTREAM_PATH + " HTTP/1.1");
            assertThat(head.headers())
                    .containsEntry(HttpHeaders.EXPECT, "100-continue")
                    .containsEntry(HttpHeaders.UPGRADE, "custom")
                    .containsEntry("X-Keep", "1")
                    .containsEntry(HttpHeaders.CONTENT_TYPE, "text/xml; charset=UTF-8")
                    .containsEntry("SOAPAction", "\"\"")
                    .containsEntry(HttpHeaders.HOST, upstream.authority())
                    .containsEntry(HttpHeaders.CONNECTION, "keep-alive")
                    .containsEntry(HttpHeaders.CONTENT_LENGTH, String.valueOf(bytes.length));
            assertThat(upstream.nextBody()).isEqualTo(bytes);
            assertThat(upstream.bytesBeforeContinue()).as("body bytes sent before 100 Continue").isZero();
        }
    }

    static Stream<Arguments> stalledUpstreams() {
        return Stream.of(
                Arguments.of("response body stalls after its headers", (Responder) RawUpstream::sendHeadersThenStall,
                        1024),
                // Far more than the socket buffers hold, so the client's writes block once the upstream stops reading.
                Arguments.of("request body is never read", (Responder) RawUpstream::neverReadBody, 16 * 1024 * 1024));
    }

    // A stalled exchange holds no servlet worker beyond the timeout, and its envelope is never re-sent.
    @ParameterizedTest(name = "{0}")
    @MethodSource("stalledUpstreams")
    void stalledExchangeFailsWithinTheConfiguredLimitOnOneConnection(String description, Responder responder,
                                                                     int bodyLength) throws Exception {
        try (RawUpstream upstream = new RawUpstream(responder)) {
            ShopServiceClient client = client(upstream.baseUrl() + UPSTREAM_PATH, CLASSPATH_WSDL);
            HttpHeaders headers = soapHeaders();
            byte[] body = new byte[bodyLength];

            long started = System.nanoTime();
            Throwable failure = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> catchThrowable(() -> client.post(headers, body)));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(failure).isInstanceOf(ResourceAccessException.class);
            assertThat(causeChain(failure)).hasAtLeastOneElementOfType(SocketTimeoutException.class);
            assertThat(elapsedMs).as("elapsed until the %d ms timeout fired", TIMEOUT_MS).isLessThan(TIMEOUT_MS + 1500);
            upstream.release();
            assertThat(upstream.awaitClientClose(Duration.ofSeconds(5))).as("connection closed by the client").isTrue();
            await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(3))
                    .untilAsserted(() -> assertThat(upstream.accepted()).as("connections accepted").isEqualTo(1));
        }
    }

    // predic8 may close an idle kept-alive connection at any time. A post is never retried, so the pool must not lease
    // a connection the upstream has closed: the next post opens a new one, however soon it follows.
    @Test
    void connectionClosedByTheUpstreamIsNotLeasedForTheNextPost() throws Exception {
        try (RawUpstream upstream = new RawUpstream(RawUpstream::answerThenClose)) {
            ShopServiceClient client = client(upstream.baseUrl() + UPSTREAM_PATH, CLASSPATH_WSDL);
            HttpHeaders headers = soapHeaders();
            byte[] bytes = envelope();

            assertThat(client.post(headers, bytes).getStatusCode().value()).isEqualTo(200);
            // Far inside the two seconds of idleness after which httpclient5 checks a pooled connection by default.
            assertThat(upstream.awaitUpstreamClose(Duration.ofMillis(500))).as("connection closed by the upstream")
                    .isTrue();
            ResponseEntity<byte[]> response = client.post(headers, bytes);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isEqualTo(SOAP_RESPONSE);
            assertThat(upstream.accepted()).as("connections accepted").isEqualTo(2);
            // The upstream answers one request per connection and then closes it, so two requests received over the
            // two connections mean each carried exactly one and nothing was re-sent.
            assertThat(upstream.requestsReceived()).as("requests received").isEqualTo(2);
            assertThat(upstream.nextBody()).isEqualTo(bytes);
            assertThat(upstream.nextBody()).isEqualTo(bytes);
        }
    }

    // httpclient5 classic speaks HTTP/1.1 only, so no http: request offers h2c, which the Mule requester never did.
    @Test
    void postIsPlainHttp11WithoutAnH2cUpgrade() throws IOException {
        wm.stubFor(post(urlEqualTo(UPSTREAM_PATH)).willReturn(ok()));
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, "text/xml");

        client().post(headers, envelope());

        LoggedRequest sent = singlePost();
        assertThat(sent.getProtocol()).isEqualTo("HTTP/1.1");
        assertThat(sent.containsHeader(HttpHeaders.UPGRADE)).isFalse();
        assertThat(sent.containsHeader("HTTP2-Settings")).isFalse();
    }

    @Test
    void httpWsdlIsReadWithItsExactBytes() throws IOException {
        byte[] wsdl = classpathBytes("upstream/ShopService.wsdl");
        wm.stubFor(get(urlEqualTo(WSDL_PATH_AND_QUERY)).willReturn(aResponse().withStatus(200)
                .withHeader(HttpHeaders.CONTENT_TYPE, "text/xml")
                .withBody(wsdl)));

        Resource resource = client().wsdlResource();

        // The timeout-applying UrlResource subclass, not a plain UrlResource.
        assertThat(resource).isInstanceOf(UrlResource.class)
                .isExactlyInstanceOf(ShopServiceClient.TimeoutUrlResource.class);
        assertThat(resource.getURL().toString()).isEqualTo(wm.baseUrl() + WSDL_PATH_AND_QUERY);
        assertThat(read(resource)).isEqualTo(wsdl);
    }

    // CXF read the WSDL and its schemas with GET only; an upstream refusing HEAD must not make the WSDL look absent.
    @Test
    void existsProbesTheHttpLocationWithGetOnly() throws IOException {
        wm.stubFor(get(urlEqualTo(WSDL_PATH_AND_QUERY)).willReturn(ok("<wsdl/>")));
        wm.stubFor(get(urlEqualTo("/shop/Missing.xsd")).willReturn(notFound()));
        Resource wsdl = client().wsdlResource();
        Resource missing = wsdl.createRelative("Missing.xsd");

        assertThat(wsdl.exists()).isTrue();
        assertThat(wsdl.isReadable()).isTrue();
        assertThat(missing.exists()).isFalse();
        assertThat(missing.isReadable()).isFalse();
        wm.verify(0, headRequestedFor(urlEqualTo(WSDL_PATH_AND_QUERY)));
        wm.verify(0, headRequestedFor(urlEqualTo("/shop/Missing.xsd")));
        wm.verify(2, getRequestedFor(urlEqualTo(WSDL_PATH_AND_QUERY)));
    }

    // A relative location resolves against the WSDL's directory, /shop/, its query (the ?xsd=1 form) included.
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ShopService.xsd", "ShopService?xsd=1"})
    void createRelativeResolvesARelativeSchemaAgainstTheWsdlUrl(String location) throws IOException {
        byte[] xsd = classpathBytes("upstream/ShopService.xsd");
        wm.stubFor(get(urlEqualTo("/shop/" + location)).willReturn(aResponse().withStatus(200)
                .withHeader(HttpHeaders.CONTENT_TYPE, "text/xml")
                .withBody(xsd)));
        Resource wsdl = client().wsdlResource();

        Resource schema = wsdl.createRelative(location);

        assertThat(schema.getURL().toString()).isEqualTo(wm.baseUrl() + "/shop/" + location);
        // The same timeout-applying type, so schema reads are bounded like the WSDL read.
        assertThat(schema.getClass()).isEqualTo(wsdl.getClass());
        assertThat(read(schema)).isEqualTo(xsd);
    }

    // JAX-WS WSDLs import schemas by absolute URL (?xsd=1), which UrlResource would append to the WSDL's directory.
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/other/Other.xsd", "/shop/ShopService?xsd=1"})
    void createRelativeKeepsAnAbsoluteSchemaLocationUnchanged(String pathAndQuery) throws IOException {
        String absolute = wm.baseUrl() + pathAndQuery;
        byte[] xsd = classpathBytes("upstream/ShopService.xsd");
        stubXml(pathAndQuery, xsd);

        Resource schema = client().wsdlResource().createRelative(absolute);

        assertThat(schema.getURL().toString()).isEqualTo(absolute);
        assertThat(read(schema)).isEqualTo(xsd);
    }

    // A WSDL fetched from the network must not make the application read and publish a local file.
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"file:/etc/hosts", "jar:file:/tmp/schemas.jar!/ShopService.xsd"})
    void createRelativeRejectsALocationOutsideHttp(String location) {
        Resource wsdl = client().wsdlResource();

        assertThrows(MalformedURLException.class, () -> wsdl.createRelative(location));
    }

    static Stream<Arguments> rejectedLocations() {
        return Stream.of(
                Arguments.of("unparseable relative location", "Shop\r\nService.xsd?token=" + TOKEN,
                        "Shop\\r\\nService.xsd"),
                Arguments.of("unparseable absolute location",
                        "http://" + USER_INFO + "localhost/x\u2028y.xsd?token=" + TOKEN,
                        "http://localhost/x\\u2028y.xsd"),
                Arguments.of("unparseable key", "./http://" + USER_INFO + "localhost/x\ny.xsd#" + TOKEN,
                        "./http://localhost/x\\ny.xsd"),
                Arguments.of("location without a valid URL port",
                        "//" + USER_INFO + "localhost:99999999999/x.xsd?token=" + TOKEN,
                        "//localhost:99999999999/x.xsd"),
                Arguments.of("location outside http", "file://" + USER_INFO + "localhost/etc/hosts?token=" + TOKEN,
                        "file://localhost/etc/hosts"),
                Arguments.of("overlong location", "x".repeat(1000) + " y.xsd", "x".repeat(256) + "...(truncated)"));
    }

    // A location and the URL it is resolved against may carry credentials, a token or line breaks forging a log line.
    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedLocations")
    void rejectedLocationIsReportedWithoutCredentialsQueryOrLineBreaks(String description, String location,
                                                                       String diagnosticLocation) {
        Resource wsdl = client("http://" + USER_INFO + "localhost/shop/ShopService?wsdl&token=" + TOKEN)
                .wsdlResource();

        MalformedURLException failure = assertThrows(MalformedURLException.class, () -> wsdl.createRelative(location));

        assertThat(failure.getMessage())
                .contains("'" + diagnosticLocation + "'", " http://localhost/shop/ShopService");
        assertThat(causeChain(failure)).extracting(Throwable::toString).allSatisfy(text -> assertThat(text)
                .doesNotContain(USER, PASSWORD, TOKEN, "\r", "\n", "\u2028"));
    }

    // A plain UrlResource would block for the full 5 s delay; the timeouts must reach every URLConnection.
    @Test
    void delayedWsdlFailsWithinTheConfiguredTimeout() {
        wm.stubFor(get(urlEqualTo(WSDL_PATH_AND_QUERY)).willReturn(ok("<wsdl/>").withFixedDelay(5000)));
        Resource resource = client().wsdlResource();

        IOException failure = assertTimeoutPreemptively(Duration.ofMillis(TIMEOUT_MS + 2500),
                () -> assertThrows(IOException.class, () -> {
                    try (InputStream in = resource.getInputStream()) {
                        in.readAllBytes();
                    }
                }));
        // The read timeout itself fired, not some other I/O failure.
        assertThat(failure).isInstanceOf(SocketTimeoutException.class);
        assertTimeoutPreemptively(Duration.ofMillis(TIMEOUT_MS + 2500),
                () -> assertThat(resource.exists()).isFalse());
    }

    static Stream<Arguments> unansweredMetadataReads() {
        Responder endOfStream = RawUpstream::closeWithoutAnswering;
        Responder reset = RawUpstream::resetWithoutAnswering;
        ThrowingConsumer<Resource> probe = resource -> assertThat(resource.exists()).isFalse();
        ThrowingConsumer<Resource> contentRead = resource -> assertThrows(IOException.class, () -> read(resource));
        return Stream.of(
                Arguments.of("existence probe answered by an end of stream", endOfStream, probe),
                Arguments.of("existence probe answered by a reset", reset, probe),
                Arguments.of("read answered by an end of stream", endOfStream, contentRead),
                Arguments.of("read answered by a reset", reset, contentRead));
    }

    // HttpURLConnection silently sends a GET again on a new connection when the first one ends or is reset before the
    // response head; a metadata read is one GET on one connection, whatever the upstream does with it.
    @ParameterizedTest(name = "{0}")
    @MethodSource("unansweredMetadataReads")
    void unansweredMetadataReadFailsOnOneConnection(String description, Responder responder,
                                                    ThrowingConsumer<Resource> failingRead) throws Exception {
        try (RawUpstream upstream = new RawUpstream(responder)) {
            Resource wsdl = client(upstream.baseUrl() + WSDL_PATH_AND_QUERY).wsdlResource();

            assertTimeoutPreemptively(Duration.ofMillis(TIMEOUT_MS + 2500), () -> failingRead.accept(wsdl));

            // A second attempt follows the first at once, so it would be counted within this window.
            await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(3))
                    .untilAsserted(() -> assertThat(upstream.accepted()).as("connections accepted").isEqualTo(1));
            assertThat(upstream.nextRequestHead().requestLine())
                    .isEqualTo("GET " + WSDL_PATH_AND_QUERY + " HTTP/1.1");
            assertThat(upstream.requestsReceived()).as("further requests received").isZero();
        }
    }

    static Stream<Arguments> unwantedBodies() {
        ThrowingConsumer<Resource> probeFindsTheDocument = resource -> assertThat(resource.exists()).isTrue();
        ThrowingConsumer<Resource> readFindsNoDocument = resource -> assertThat(catchThrowable(() -> read(resource)))
                .isExactlyInstanceOf(FileNotFoundException.class);
        return Stream.of(
                Arguments.of("existence probe answered 200", (Responder) RawUpstream::sendHeadersThenStall,
                        probeFindsTheDocument),
                Arguments.of("read answered 404", (Responder) RawUpstream::sendNotFoundHeadersThenStall,
                        readFindsNoDocument));
    }

    // Each body stalls short of its length, so reading it would end in a timeout: as with HttpURLConnection, neither
    // a probe nor a read refused by its status reads the body before the connection is closed.
    @ParameterizedTest(name = "{0}")
    @MethodSource("unwantedBodies")
    void unwantedBodyIsNeverRead(String description, Responder responder, ThrowingConsumer<Resource> call)
            throws Exception {
        try (RawUpstream upstream = new RawUpstream(responder)) {
            Resource wsdl = client(upstream.baseUrl() + WSDL_PATH_AND_QUERY).wsdlResource();

            assertTimeoutPreemptively(Duration.ofMillis(TIMEOUT_MS + 2500), () -> call.accept(wsdl));

            assertThat(upstream.awaitClientClose(Duration.ofSeconds(5))).as("connection closed by the client").isTrue();
            assertThat(upstream.accepted()).as("connections accepted").isEqualTo(1);
        }
    }

    // A status without a body, such as 204, is an empty document, as HttpURLConnection read it.
    @Test
    void statusWithoutABodyIsReadAsAnEmptyDocument() throws IOException {
        wm.stubFor(get(urlEqualTo(WSDL_PATH_AND_QUERY)).willReturn(aResponse().withStatus(204)));
        Resource resource = client().wsdlResource();

        assertThat(resource.exists()).isTrue();
        assertThat(read(resource)).isEmpty();
        wm.verify(2, getRequestedFor(urlEqualTo(WSDL_PATH_AND_QUERY)));
    }

    static Stream<Arguments> wsdlRedirects() {
        return Stream.of(
                Arguments.of("on the same scheme", "http://localhost:%d" + WSDL_PATH_AND_QUERY, true),
                Arguments.of("from http to https", "https://localhost:%d" + WSDL_PATH_AND_QUERY, false));
    }

    // As HttpURLConnection did, a WSDL read follows a redirect that keeps the scheme and returns any other as received.
    @ParameterizedTest(name = "{0}")
    @MethodSource("wsdlRedirects")
    void wsdlRedirectIsFollowedOnlyWithinItsScheme(String description, String location, boolean followed)
            throws IOException {
        byte[] wsdl = classpathBytes("upstream/ShopService.wsdl");
        stubXml(WSDL_PATH_AND_QUERY, wsdl);
        String movedPathAndQuery = "/shop/Moved?wsdl";
        wm.stubFor(get(urlEqualTo(movedPathAndQuery)).willReturn(aResponse().withStatus(302)
                .withHeader(HttpHeaders.LOCATION, String.format(location, wm.getPort()))
                .withBody("moved")));
        Resource resource = client(wm.baseUrl() + movedPathAndQuery).wsdlResource();

        assertThat(resource.exists()).isEqualTo(followed);
        assertThat(read(resource)).isEqualTo(followed ? wsdl : "moved".getBytes(UTF_8));
        assertThat(resource.getURL().toString()).isEqualTo(wm.baseUrl() + movedPathAndQuery);
        wm.verify(2, getRequestedFor(urlEqualTo(movedPathAndQuery)));
        wm.verify(followed ? 2 : 0, getRequestedFor(urlEqualTo(WSDL_PATH_AND_QUERY)));
    }

    static Stream<Arguments> credentialedWsdlReads() {
        return Stream.of(
                Arguments.of("without a redirect", null, true),
                Arguments.of("redirected on the URL's origin", WSDL_PATH_AND_QUERY, true),
                // 127.0.0.1 reaches the same stand-in under another host name, hence on another origin.
                Arguments.of("redirected to another origin", "http://127.0.0.1:%d" + WSDL_PATH_AND_QUERY, false));
    }

    // UrlResource sends a URL's user information as Basic credentials; it never reaches the request line, and a
    // redirect carries the credentials only while it stays on the URL's origin.
    @ParameterizedTest(name = "{0}")
    @MethodSource("credentialedWsdlReads")
    void wsdlUserInformationTravelsAsBasicCredentialsOnItsOriginOnly(String description, String redirect,
                                                                    boolean credentialsAtTarget) throws IOException {
        byte[] wsdl = "<wsdl/>".getBytes(UTF_8);
        stubXml(WSDL_PATH_AND_QUERY, wsdl);
        String pathAndQuery = WSDL_PATH_AND_QUERY;
        if (redirect != null) {
            pathAndQuery = "/shop/Moved?wsdl";
            wm.stubFor(get(urlEqualTo(pathAndQuery)).willReturn(aResponse().withStatus(302)
                    .withHeader(HttpHeaders.LOCATION, String.format(redirect, wm.getPort()))));
        }
        Resource resource = client("http://" + USER_INFO + "localhost:" + wm.getPort() + pathAndQuery).wsdlResource();

        assertThat(read(resource)).isEqualTo(wsdl);

        String basic = "Basic " + Base64.getUrlEncoder().encodeToString((USER + ":" + PASSWORD).getBytes(UTF_8));
        List<LoggedRequest> first = wm.findAll(getRequestedFor(urlEqualTo(pathAndQuery)));
        assertThat(first).hasSize(1);
        assertThat(first.get(0).getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo(basic);
        assertThat(first.get(0).getHeader(HttpHeaders.HOST)).isEqualTo("localhost:" + wm.getPort());
        // No content encoding is requested, so the body arrives as the upstream stored it.
        assertThat(first.get(0).containsHeader(HttpHeaders.ACCEPT_ENCODING)).isFalse();
        List<LoggedRequest> target = wm.findAll(getRequestedFor(urlEqualTo(WSDL_PATH_AND_QUERY)));
        assertThat(target).hasSize(1);
        assertThat(target.get(0).containsHeader(HttpHeaders.AUTHORIZATION)).isEqualTo(credentialsAtTarget);
    }

    static Stream<Arguments> failedReads() {
        return Stream.of(
                Arguments.of(404, FileNotFoundException.class),
                Arguments.of(410, FileNotFoundException.class),
                Arguments.of(500, IOException.class));
    }

    // A failed read names the URL as WsdlQueryPublisher names resources, never its credentials or query.
    @ParameterizedTest(name = "HTTP {0}")
    @MethodSource("failedReads")
    void failedStatusFailsTheReadWithoutCredentialsOrQuery(int status, Class<? extends IOException> type) {
        String pathAndQuery = UPSTREAM_PATH + "?wsdl&token=" + TOKEN;
        wm.stubFor(get(urlEqualTo(pathAndQuery)).willReturn(aResponse().withStatus(status).withBody("failed")));
        Resource resource = client("http://" + USER_INFO + "localhost:" + wm.getPort() + pathAndQuery).wsdlResource();

        IOException failure = assertThrows(IOException.class, () -> read(resource));

        assertThat(failure).isExactlyInstanceOf(type);
        assertThat(failure.getMessage()).contains("http://localhost:" + wm.getPort() + UPSTREAM_PATH)
                .doesNotContain(USER, PASSWORD, TOKEN);
        if (type == IOException.class) {
            assertThat(failure.getMessage()).contains(String.valueOf(status));
        }
        assertThat(resource.exists()).isFalse();
        wm.verify(2, getRequestedFor(urlEqualTo(pathAndQuery)));
    }

    // The stand-in surefire sets as proxy.wsdl-location, so every test context starts without the network.
    @Test
    void classpathWsdlAndItsRelativeSchemaAreReadWithoutAnyHttpRequest() throws IOException {
        Resource resource = client(CLASSPATH_WSDL).wsdlResource();

        assertThat(resource.exists()).isTrue();
        assertThat(resource.isReadable()).isTrue();
        byte[] bytes = read(resource);
        assertThat(bytes).isEqualTo(classpathBytes("upstream/ShopService.wsdl"));
        assertThat(new String(bytes, UTF_8)).contains("ShopService");
        // The WSDL's own import, served by WsdlQueryPublisher at ?xsd=ShopService.xsd.
        assertThat(resource.createRelative("ShopService.xsd").exists()).isTrue();
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    private static final String XSD_NS = "http://www.w3.org/2001/XMLSchema";

    private static final String WSDL_NS = "http://schemas.xmlsoap.org/wsdl/";

    private static final String SOAP_NS = "http://schemas.xmlsoap.org/wsdl/soap/";

    /** The request URL MockHttpServletRequest reports for the publisher's listener path {@code /}. */
    private static final String PROXY_URL = "http://localhost/";

    /** The attributes WsdlQueryPublisher and the adaptation rewrite; every other node arrives as upstream sent it. */
    private static final Set<String> LOCATION_ATTRIBUTES = Set.of("schemaLocation", "location");

    private static void stubXml(String pathAndQuery, byte[] document) {
        wm.stubFor(get(urlEqualTo(pathAndQuery)).willReturn(aResponse().withStatus(200)
                .withHeader(HttpHeaders.CONTENT_TYPE, "text/xml")
                .withBody(document)));
    }

    private static Document parseXml(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    /** The location of every {@code xsd:import/include/redefine} and {@code wsdl:import}, in document order. */
    private static List<String> referenceLocations(Document document) {
        List<String> locations = new ArrayList<>();
        NodeList elements = document.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            String localName = element.getLocalName();
            String namespace = element.getNamespaceURI();
            if (XSD_NS.equals(namespace) && Set.of("import", "include", "redefine").contains(localName)) {
                locations.add(element.getAttribute("schemaLocation"));
            } else if (WSDL_NS.equals(namespace) && "import".equals(localName)) {
                locations.add(element.getAttribute("location"));
            }
        }
        return locations;
    }

    /** Whether {@code served} equals {@code upstream} in every node but the location attributes. */
    private static Diff diffIgnoringLocations(Object upstream, Object served) {
        return DiffBuilder.compare(upstream).withTest(served)
                .withAttributeFilter(attribute -> !LOCATION_ATTRIBUTES.contains(attribute.getLocalName()))
                .checkForSimilar()
                .build();
    }

    /** One GET through the publisher's filter, its query parameters decoded as the servlet container decodes them. */
    private static MockHttpServletResponse fetchThroughPublisher(WsdlQueryPublisher publisher, String url)
            throws Exception {
        URI uri = URI.create(url);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri.getRawPath());
        request.setQueryString(uri.getRawQuery());
        for (String pair : uri.getRawQuery().split("&")) {
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            request.addParameter(URLDecoder.decode(name, UTF_8), URLDecoder.decode(value, UTF_8));
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        publisher.doFilter(request, response, chain);
        assertThat(chain.getRequest()).as("GET %s passed down the filter chain unserved", url).isNull();
        return response;
    }

    // JAX-WS WSDLs import their schemas by absolute URL, which WsdlQueryPublisher never follows on its own.
    // The adapted resources must get the whole closure served by the proxy, the relative include nested in it too.
    @Test
    void absoluteImportsAndEverySchemaTheyReachArePublishedThroughTheProxy() throws Exception {
        String shopSchemaUrl = wm.baseUrl() + "/shop/ShopService?xsd=1";
        String commonSchemaUrl = wm.baseUrl() + "/common/Common.xsd";
        String wsdl = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<wsdl:definitions xmlns:wsdl=\"" + WSDL_NS + "\" xmlns:soap=\"" + SOAP_NS + "\" xmlns:xsd=\""
                + XSD_NS + "\" xmlns:tns=\"urn:shop\" targetNamespace=\"urn:shop\">"
                + "<wsdl:types><xsd:schema><xsd:import namespace=\"urn:shop\" schemaLocation=\"" + shopSchemaUrl
                + "\"/></xsd:schema></wsdl:types>"
                + "<wsdl:service name=\"ShopService\"><wsdl:port name=\"ShopServicePort\" binding=\"tns:ShopBinding\">"
                + "<soap:address location=\"" + wm.baseUrl() + UPSTREAM_PATH + "\"/></wsdl:port></wsdl:service>"
                + "</wsdl:definitions>";
        String shopSchema = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xs:schema xmlns:xs=\"" + XSD_NS + "\" targetNamespace=\"urn:shop\">"
                + "<xs:include schemaLocation=\"ShopTypes.xsd\"/>"
                + "<xs:import namespace=\"urn:common\" schemaLocation=\"" + commonSchemaUrl + "\"/>"
                + "<xs:element name=\"create\" type=\"xs:string\"/></xs:schema>";
        String shopTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xs:schema xmlns:xs=\"" + XSD_NS + "\" targetNamespace=\"urn:shop\">"
                + "<xs:element name=\"createResponse\" type=\"xs:string\"/></xs:schema>";
        // Imports the first schema back by the same absolute URL: one key, one identity, read once.
        String commonSchema = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xs:schema xmlns:xs=\"" + XSD_NS + "\" targetNamespace=\"urn:common\">"
                + "<xs:import namespace=\"urn:shop\" schemaLocation=\"" + shopSchemaUrl + "\"/>"
                + "<xs:element name=\"customerId\" type=\"xs:string\"/></xs:schema>";
        stubXml(WSDL_PATH_AND_QUERY, wsdl.getBytes(UTF_8));
        stubXml("/shop/ShopService?xsd=1", shopSchema.getBytes(UTF_8));
        stubXml("/shop/ShopTypes.xsd", shopTypes.getBytes(UTF_8));
        stubXml("/common/Common.xsd", commonSchema.getBytes(UTF_8));
        // The relative include of an absolutely imported schema is keyed by the URL it denotes.
        String shopTypesUrl = wm.baseUrl() + "/shop/ShopTypes.xsd";
        Map<String, String> upstreamByQuery = Map.of(
                "wsdl", wsdl,
                "xsd=./" + shopSchemaUrl, shopSchema,
                "xsd=./" + shopTypesUrl, shopTypes,
                "xsd=./" + commonSchemaUrl, commonSchema);

        WsdlQueryPublisher publisher = new WsdlQueryPublisher().register(client().wsdlResource(), "/");

        assertThat(publisher.registrations().get(0).schemas().keySet())
                .containsExactlyInAnyOrder("./" + shopSchemaUrl, "./" + shopTypesUrl, "./" + commonSchemaUrl);
        // Follows every reference of every served document, exactly as a client of the proxy's ?wsdl would.
        Map<String, byte[]> servedByQuery = new LinkedHashMap<>();
        Deque<String> toFetch = new ArrayDeque<>(List.of(PROXY_URL + "?wsdl"));
        while (!toFetch.isEmpty()) {
            String url = toFetch.poll();
            String query = URLDecoder.decode(URI.create(url).getRawQuery(), UTF_8);
            if (servedByQuery.containsKey(query)) {
                continue;
            }
            MockHttpServletResponse response = fetchThroughPublisher(publisher, url);
            assertThat(response.getStatus()).as("GET %s", url).isEqualTo(200);
            servedByQuery.put(query, response.getContentAsByteArray());
            for (String location : referenceLocations(parseXml(response.getContentAsByteArray()))) {
                // The proxy's own URL, never the upstream's: no vendor location is left for a client to call.
                assertThat(location).as("reference served in %s", url).startsWith(PROXY_URL + "?xsd=");
                toFetch.add(location);
            }
        }
        assertThat(servedByQuery.keySet()).containsExactlyInAnyOrderElementsOf(upstreamByQuery.keySet());
        servedByQuery.forEach((query, served) -> {
            Diff diff = diffIgnoringLocations(upstreamByQuery.get(query), served);
            assertThat(diff.hasDifferences()).as("?%s differs from the upstream document: %s", query, diff).isFalse();
        });
        Element address = (Element) parseXml(servedByQuery.get("wsdl")).getElementsByTagNameNS(SOAP_NS, "address")
                .item(0);
        assertThat(address.getAttribute("location")).isEqualTo(PROXY_URL);
    }

    // The four reference kinds are matched by namespace and local name; nothing else in the document changes.
    @Test
    void everyAbsoluteReferenceLocationIsReplacedByItsKeyAndNothingElseChanges() throws Exception {
        String wsdlImport = wm.baseUrl() + "/shop/Other.wsdl";
        String xsdImport = wm.baseUrl() + "/shop/ShopService?xsd=1";
        String httpsInclude = "https://localhost:1/shop/Types.xsd";
        String upperCaseRedefine = "HTTP://localhost:1/shop/Redefined.xsd";
        String keyLookalike = "./http://localhost:1/x.xsd";
        String foreign = wm.baseUrl() + "/foreign.xsd";
        String soapAddress = wm.baseUrl() + UPSTREAM_PATH;
        String document = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<w:definitions xmlns:w=\"" + WSDL_NS + "\" xmlns:s=\"" + XSD_NS + "\""
                + " xmlns:xsd=\"urn:not-xml-schema\" xmlns:soap=\"" + SOAP_NS + "\">"
                + "<w:import namespace=\"urn:other\" location=\"" + wsdlImport + "\"/>"
                + "<w:types><s:schema>"
                + "<s:import namespace=\"urn:shop\" schemaLocation=\"" + xsdImport + "\"/>"
                + "<s:include schemaLocation=\"" + httpsInclude + "\"/>"
                + "<s:redefine schemaLocation=\"" + upperCaseRedefine + "\"/>"
                + "<s:include schemaLocation=\"Relative.xsd\"/>"
                + "<s:include schemaLocation=\"" + keyLookalike + "\"/>"
                + "<xsd:import schemaLocation=\"" + foreign + "\"/>"
                + "</s:schema></w:types>"
                + "<w:service name=\"S\"><w:port name=\"P\" binding=\"B\"><soap:address location=\"" + soapAddress
                + "\"/></w:port></w:service></w:definitions>";
        stubXml(WSDL_PATH_AND_QUERY, document.getBytes(UTF_8));
        Resource wsdl = client().wsdlResource();

        byte[] adapted = read(wsdl);

        // A relative path whose first segment is "http:" (RFC 3986) keeps denoting what it resolves to, via its key.
        String lookalikeTarget = URI.create(wm.baseUrl() + WSDL_PATH_AND_QUERY).resolve(keyLookalike).toString();
        Document parsed = parseXml(adapted);
        assertThat(referenceLocations(parsed)).containsExactly("./" + wsdlImport, "./" + xsdImport,
                "./" + httpsInclude, "./" + upperCaseRedefine, "Relative.xsd", "./" + lookalikeTarget);
        assertThat(((Element) parsed.getElementsByTagNameNS("urn:not-xml-schema", "import").item(0))
                .getAttribute("schemaLocation")).isEqualTo(foreign);
        assertThat(((Element) parsed.getElementsByTagNameNS(SOAP_NS, "address").item(0))
                .getAttribute("location")).isEqualTo(soapAddress);
        Diff diff = diffIgnoringLocations(document, adapted);
        assertThat(diff.hasDifferences()).as("adapted document: %s", diff).isFalse();
        assertThat(wsdl.createRelative("./" + lookalikeTarget).getURL().toString()).isEqualTo(lookalikeTarget);
        // The length of the adapted bytes, learnt with GET as every other read; UrlResource would send HEAD.
        assertThat(wsdl.contentLength()).isEqualTo(adapted.length);
        wm.verify(0, headRequestedFor(urlEqualTo(WSDL_PATH_AND_QUERY)));
    }

    static Stream<Arguments> keyResolutions() {
        return Stream.of(
                Arguments.of("http key", "./{base}/shop/ShopService?xsd=1", "{base}/shop/ShopService?xsd=1"),
                Arguments.of("https key keeps its scheme", "./https://localhost:1/shop/Types.xsd",
                        "https://localhost:1/shop/Types.xsd"),
                Arguments.of("key with an upper-case scheme", "./HTTP://localhost:1/shop/Redefined.xsd",
                        "http://localhost:1/shop/Redefined.xsd"),
                Arguments.of("dot-relative schema", "./Nested.xsd", "{base}/shop/Nested.xsd"),
                Arguments.of("dot-relative path with a non-http first segment", "./file:/etc/hosts",
                        "{base}/shop/file:/etc/hosts"));
    }

    // A key resolves back to exactly the URL it was made from, whichever document holds it; "./" followed by
    // anything but an http or https URL stays an ordinary relative location.
    @ParameterizedTest(name = "{0}")
    @MethodSource("keyResolutions")
    void createRelativeResolvesAKeyToItsUrlAndAnyOtherDotLocationRelatively(String description, String location,
                                                                            String expectedUrl) throws IOException {
        Resource wsdl = client().wsdlResource();

        Resource schema = wsdl.createRelative(location.replace("{base}", wm.baseUrl()));

        assertThat(schema.getURL().toString()).isEqualTo(expectedUrl.replace("{base}", wm.baseUrl()));
        assertThat(schema.getClass()).isEqualTo(wsdl.getClass());
    }

    static Stream<Arguments> documentsLeftUnchanged() {
        String absoluteImport = "<xs:import namespace=\"urn:a\" schemaLocation=\"http://localhost:1/a.xsd\"/>";
        return Stream.of(
                Arguments.of("not well-formed",
                        "<xs:schema xmlns:xs=\"" + XSD_NS + "\">" + absoluteImport + "</xs:element>"),
                Arguments.of("DOCTYPE, refused by the hardened parser", "<!DOCTYPE xs:schema [<!ENTITY e \"x\">]>"
                        + "<xs:schema xmlns:xs=\"" + XSD_NS + "\">" + absoluteImport + "</xs:schema>"),
                Arguments.of("unbound namespace prefix", "<xs:schema>" + absoluteImport + "</xs:schema>"),
                Arguments.of("absolute URLs outside the four reference kinds only",
                        "<?xml version='1.0'?>\n"
                                + "<xs:schema xmlns:xs=\"" + XSD_NS + "\" xmlns:x=\"urn:not-xml-schema\">\n"
                                + "  <x:import schemaLocation=\"http://localhost:1/a.xsd\"/>\n"
                                + "  <xs:include schemaLocation=\"Types.xsd\"/>\n"
                                + "  <xs:annotation><xs:appinfo source=\"http://localhost:1/doc\"/></xs:annotation>\n"
                                + "</xs:schema>\n"));
    }

    // Only a replaced location changes a document, and one the publisher must reject reaches it as sent.
    @ParameterizedTest(name = "{0}")
    @MethodSource("documentsLeftUnchanged")
    void documentsWithNothingToReplaceAreReadWithTheirExactBytes(String description, String document)
            throws IOException {
        byte[] bytes = document.getBytes(UTF_8);
        stubXml(WSDL_PATH_AND_QUERY, bytes);
        Resource wsdl = client().wsdlResource();

        assertThat(read(wsdl)).isEqualTo(bytes);
        assertThat(wsdl.contentLength()).isEqualTo(bytes.length);
    }

    /** A schema of {@code namespace} holding {@code references} followed by one string element {@code element}. */
    private static String schemaDocument(String namespace, String references, String element) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<xs:schema xmlns:xs=\"" + XSD_NS + "\" targetNamespace=\"" + namespace + "\">" + references
                + "<xs:element name=\"" + element + "\" type=\"xs:string\"/></xs:schema>";
    }

    /**
     * Every document the proxy serves, keyed by its decoded query, fetched from {@code ?wsdl} on by following each
     * reference of each served document as a client of the proxy would. Each must answer 200, and each reference
     * must point at the proxy, never at the upstream.
     */
    private static Map<String, byte[]> crawlThroughPublisher(WsdlQueryPublisher publisher) throws Exception {
        Map<String, byte[]> servedByQuery = new LinkedHashMap<>();
        Deque<String> toFetch = new ArrayDeque<>(List.of(PROXY_URL + "?wsdl"));
        while (!toFetch.isEmpty()) {
            String url = toFetch.poll();
            String query = URLDecoder.decode(URI.create(url).getRawQuery(), UTF_8);
            if (servedByQuery.containsKey(query)) {
                continue;
            }
            MockHttpServletResponse response = fetchThroughPublisher(publisher, url);
            assertThat(response.getStatus()).as("GET %s", url).isEqualTo(200);
            servedByQuery.put(query, response.getContentAsByteArray());
            for (String location : referenceLocations(parseXml(response.getContentAsByteArray()))) {
                assertThat(location).as("reference served in %s", url).startsWith(PROXY_URL + "?xsd=");
                toFetch.add(location);
            }
        }
        return servedByQuery;
    }

    /** The decoded query of each reference location in a served document, in document order. */
    private static List<String> referencedQueries(byte[] served) throws Exception {
        List<String> queries = new ArrayList<>();
        for (String location : referenceLocations(parseXml(served))) {
            queries.add(URLDecoder.decode(URI.create(location).getRawQuery(), UTF_8));
        }
        return queries;
    }

    // Two absolutely imported branches each include their own "Types.xsd". Spelled as written, the two
    // includes would be one key for two documents, which WsdlQueryPublisher refuses at start-up; keyed by the URL each
    // denotes, every document of both branches is published under its own key.
    @Test
    void relativeIncludesOfAbsolutelyImportedBranchesArePublishedAsTheirOwnDocuments() throws Exception {
        String wsdlPathAndQuery = "/shop/Branches?wsdl";
        String branchA = wm.baseUrl() + "/a/A.xsd";
        String branchB = wm.baseUrl() + "/b/B.xsd";
        String typesA = wm.baseUrl() + "/a/Types.xsd";
        String typesB = wm.baseUrl() + "/b/Types.xsd";
        String wsdl = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<wsdl:definitions xmlns:wsdl=\"" + WSDL_NS + "\" xmlns:soap=\"" + SOAP_NS + "\" xmlns:xsd=\""
                + XSD_NS + "\" xmlns:tns=\"urn:shop\" targetNamespace=\"urn:shop\">"
                + "<wsdl:types><xsd:schema>"
                + "<xsd:import namespace=\"urn:a\" schemaLocation=\"" + branchA + "\"/>"
                + "<xsd:import namespace=\"urn:b\" schemaLocation=\"" + branchB + "\"/>"
                + "</xsd:schema></wsdl:types>"
                + "<wsdl:service name=\"ShopService\"><wsdl:port name=\"ShopServicePort\" binding=\"tns:ShopBinding\">"
                + "<soap:address location=\"" + wm.baseUrl() + UPSTREAM_PATH + "\"/></wsdl:port></wsdl:service>"
                + "</wsdl:definitions>";
        String schemaA = schemaDocument("urn:a", "<xs:include schemaLocation=\"Types.xsd\"/>", "a");
        String schemaB = schemaDocument("urn:b", "<xs:include schemaLocation=\"Types.xsd\"/>", "b");
        String schemaTypesA = schemaDocument("urn:a", "", "branchAType");
        String schemaTypesB = schemaDocument("urn:b", "", "branchBType");
        stubXml(wsdlPathAndQuery, wsdl.getBytes(UTF_8));
        stubXml("/a/A.xsd", schemaA.getBytes(UTF_8));
        stubXml("/b/B.xsd", schemaB.getBytes(UTF_8));
        stubXml("/a/Types.xsd", schemaTypesA.getBytes(UTF_8));
        stubXml("/b/Types.xsd", schemaTypesB.getBytes(UTF_8));
        Map<String, String> upstreamByQuery = Map.of(
                "wsdl", wsdl,
                "xsd=./" + branchA, schemaA,
                "xsd=./" + branchB, schemaB,
                "xsd=./" + typesA, schemaTypesA,
                "xsd=./" + typesB, schemaTypesB);

        WsdlQueryPublisher publisher = new WsdlQueryPublisher()
                .register(client(wm.baseUrl() + wsdlPathAndQuery).wsdlResource(), "/");

        assertThat(publisher.registrations().get(0).schemas().keySet())
                .containsExactlyInAnyOrder("./" + branchA, "./" + branchB, "./" + typesA, "./" + typesB);
        Map<String, byte[]> servedByQuery = crawlThroughPublisher(publisher);
        assertThat(servedByQuery.keySet()).containsExactlyInAnyOrderElementsOf(upstreamByQuery.keySet());
        servedByQuery.forEach((query, served) -> {
            Diff diff = diffIgnoringLocations(upstreamByQuery.get(query), served);
            assertThat(diff.hasDifferences()).as("?%s differs from the upstream document: %s", query, diff).isFalse();
        });
        assertThat(referencedQueries(servedByQuery.get("xsd=./" + branchA))).containsExactly("xsd=./" + typesA);
        assertThat(referencedQueries(servedByQuery.get("xsd=./" + branchB))).containsExactly("xsd=./" + typesB);
    }

    static Stream<Arguments> adaptationModes() {
        return Stream.of(
                Arguments.of("the WSDL itself", List.of(), false),
                Arguments.of("reached through a relative location", List.of("B.xsd"), false),
                Arguments.of("reached through an absolute location", List.of("{base}/b/B.xsd"), true),
                Arguments.of("reached through a key", List.of("./{base}/b/B.xsd"), true),
                Arguments.of("reached through a relative location from an absolutely reached document",
                        List.of("{base}/b/B.xsd", "B.xsd"), true));
    }

    // A document reached through relative locations only keeps its relative references as written, CXF's
    // ?xsd=<location> form; one reached through an absolute location or a key, and every document it reaches, has each
    // scheme-less reference replaced by the key of the URL it denotes. Blank and non-http locations stay as written in
    // both, as the publisher follows neither.
    @ParameterizedTest(name = "{0}")
    @MethodSource("adaptationModes")
    void relativeReferencesStayAsWrittenOnlyInDocumentsReachedThroughRelativeLocations(String description,
                                                                                       List<String> route,
                                                                                       boolean resolved)
            throws Exception {
        String documentUrl = wm.baseUrl() + "/b/B.xsd";
        String absolute = wm.baseUrl() + "/abs/Abs.xsd";
        String keyLookalike = "./http://localhost:1/x.xsd";
        String document = schemaDocument("urn:b",
                "<xs:include schemaLocation=\"Types.xsd\"/>"
                        + "<xs:import namespace=\"urn:common\" schemaLocation=\"../common/Common.xsd\"/>"
                        + "<xs:import namespace=\"urn:net\" schemaLocation=\"//localhost:1/net/Net.xsd\"/>"
                        + "<xs:import namespace=\"urn:abs\" schemaLocation=\"" + absolute + "\"/>"
                        + "<xs:include schemaLocation=\"" + keyLookalike + "\"/>"
                        + "<xs:import namespace=\"urn:none\" schemaLocation=\"\"/>"
                        + "<xs:import namespace=\"urn:other\" schemaLocation=\"urn:example:not-http\"/>",
                "b");
        stubXml("/b/B.xsd", document.getBytes(UTF_8));
        Resource resource = client(documentUrl).wsdlResource();
        for (String location : route) {
            resource = resource.createRelative(location.replace("{base}", wm.baseUrl()));
        }

        byte[] adapted = read(resource);

        String lookalikeKey = "./" + URI.create(documentUrl).resolve(keyLookalike);
        List<String> expected = resolved
                ? List.of("./" + wm.baseUrl() + "/b/Types.xsd", "./" + wm.baseUrl() + "/common/Common.xsd",
                        "./http://localhost:1/net/Net.xsd", "./" + absolute, lookalikeKey, "", "urn:example:not-http")
                : List.of("Types.xsd", "../common/Common.xsd", "//localhost:1/net/Net.xsd", "./" + absolute,
                        lookalikeKey, "", "urn:example:not-http");
        assertThat(resource.getURL().toString()).isEqualTo(documentUrl);
        assertThat(referenceLocations(parseXml(adapted))).containsExactlyElementsOf(expected);
        Diff diff = diffIgnoringLocations(document, adapted);
        assertThat(diff.hasDifferences()).as("adapted document: %s", diff).isFalse();
        assertThat(resource.contentLength()).isEqualTo(adapted.length);
    }

    // A schema the WSDL reaches both through a relative and through an absolute location is one document to
    // the publisher, read once in the mode of the reference followed first; the closure registers in either order and
    // every key serves that one reading.
    @ParameterizedTest(name = "relative location first: {0}")
    @ValueSource(booleans = {true, false})
    void documentReachedThroughARelativeAndAnAbsoluteLocationIsPublishedOnce(boolean relativeFirst) throws Exception {
        String schemaUrl = wm.baseUrl() + "/shop/a/A.xsd";
        String typesUrl = wm.baseUrl() + "/shop/a/Types.xsd";
        String relativeImport = "<xsd:import namespace=\"urn:a\" schemaLocation=\"a/A.xsd\"/>";
        String absoluteImport = "<xsd:import namespace=\"urn:a\" schemaLocation=\"" + schemaUrl + "\"/>";
        String wsdl = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<wsdl:definitions xmlns:wsdl=\"" + WSDL_NS + "\" xmlns:xsd=\"" + XSD_NS + "\""
                + " targetNamespace=\"urn:shop\"><wsdl:types><xsd:schema>"
                + (relativeFirst ? relativeImport + absoluteImport : absoluteImport + relativeImport)
                + "</xsd:schema></wsdl:types></wsdl:definitions>";
        String schema = schemaDocument("urn:a", "<xs:include schemaLocation=\"Types.xsd\"/>", "a");
        String types = schemaDocument("urn:a", "", "aType");
        stubXml(WSDL_PATH_AND_QUERY, wsdl.getBytes(UTF_8));
        stubXml("/shop/a/A.xsd", schema.getBytes(UTF_8));
        stubXml("/shop/a/Types.xsd", types.getBytes(UTF_8));
        // Read as written through the relative location, the include keeps its spelling; read resolved, it is a key.
        String typesKey = relativeFirst ? "Types.xsd" : "./" + typesUrl;
        Map<String, String> upstreamByQuery = Map.of(
                "wsdl", wsdl,
                "xsd=a/A.xsd", schema,
                "xsd=./" + schemaUrl, schema,
                "xsd=" + typesKey, types);

        WsdlQueryPublisher publisher = new WsdlQueryPublisher().register(client().wsdlResource(), "/");

        assertThat(publisher.registrations().get(0).schemas().keySet())
                .containsExactlyInAnyOrder("a/A.xsd", "./" + schemaUrl, typesKey);
        Map<String, byte[]> servedByQuery = crawlThroughPublisher(publisher);
        assertThat(servedByQuery.keySet()).containsExactlyInAnyOrderElementsOf(upstreamByQuery.keySet());
        servedByQuery.forEach((query, served) -> {
            Diff diff = diffIgnoringLocations(upstreamByQuery.get(query), served);
            assertThat(diff.hasDifferences()).as("?%s differs from the upstream document: %s", query, diff).isFalse();
        });
        assertThat(servedByQuery.get("xsd=a/A.xsd")).isEqualTo(servedByQuery.get("xsd=./" + schemaUrl));
    }
}
