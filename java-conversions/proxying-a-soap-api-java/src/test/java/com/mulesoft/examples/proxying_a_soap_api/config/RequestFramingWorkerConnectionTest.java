/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_soap_api.config;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.mock.web.MockHttpServletRequest;
import org.xnio.IoUtils;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.StreamConnection;
import org.xnio.channels.AcceptingChannel;
import org.xnio.conduits.ConduitStreamSourceChannel;
import org.xnio.conduits.StreamSourceConduit;

import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.AbstractServerConnection;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderValues;
import io.undertow.util.Headers;

/**
 * Tests the guard registered for each connection a {@link RequestFramingWorker} accepts, on a real Undertow server
 * (port 0 on IPv4 loopback) built with {@link RequestFramingGuardConfig.GuardedWorkerCustomizer} and
 * {@link RequestFramingGuardConfig.SentHeaderFieldsHandler}: with no option, and under each Undertow option that puts
 * a conduit of Undertow's own above the guard, every completed request of a keep-alive connection leaves no released
 * head waiting and keeps its header values as sent; the entry goes when either side closes the connection, while
 * Undertow's own close handling still runs; and an accepted connection that has already closed, or that the guard
 * cannot be installed or registered on, leaves no entry.
 */
class RequestFramingWorkerConnectionTest {

    /** IPv4 loopback literal: {@code localhost} may resolve to {@code ::1}, where nothing listens. */
    private static final String LOOPBACK = "127.0.0.1";

    /** Completed requests sent one after another on one keep-alive connection. */
    private static final int REQUESTS = 20;

    /** A credential with an inner double space, which Undertow's parser turns into one. */
    private static final String DIGEST = "Digest username=\"John  Smith\"";

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final byte[] OK = "ok".getBytes(ISO_8859_1);

    private RequestFramingGuardConfig.GuardedWorkerCustomizer customizer;

    private Undertow server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
        if (customizer != null) {
            customizer.destroy();
        }
    }

    /**
     * How the listener is configured. Every option except {@link #PLAIN} puts a conduit of Undertow's above the guard.
     */
    enum ListenerOption {

        PLAIN(builder -> {
        }),
        STATISTICS(builder -> builder.setServerOption(UndertowOptions.ENABLE_STATISTICS, true)),
        IDLE_TIMEOUT(builder -> builder.setServerOption(UndertowOptions.IDLE_TIMEOUT, 30_000)),
        READ_TIMEOUT(builder -> builder.setSocketOption(Options.READ_TIMEOUT, 30_000));

        private final Consumer<Undertow.Builder> setting;

        ListenerOption(Consumer<Undertow.Builder> setting) {
            this.setting = setting;
        }
    }

    /** The side that closes the connection after its one request. */
    enum Closer {
        CLIENT,
        SERVER
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ListenerOption.class)
    void completedKeepAliveRequestsLeaveNoHeadWaitingAndKeepTheirValuesAsSent(ListenerOption option)
            throws IOException, InterruptedException {
        BlockingQueue<Served> served = new LinkedBlockingQueue<>();
        int port = start(option, exchange -> served.add(Served.of(exchange)));

        try (Socket socket = connect(port)) {
            Served first = null;
            for (int i = 0; i < REQUESTS; i++) {
                write(socket, "GET /" + i + " HTTP/1.1\r\nHost: localhost\r\nAuthorization: " + DIGEST + "\r\n\r\n");

                assertThat(readResponse(socket)).as("response %d", i).isEqualTo("HTTP/1.1 200 OK");
                Served exchange = served.poll(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                assertThat(exchange).as("exchange %d", i).isNotNull();
                assertThat(exchange.guard()).as("guard of exchange %d", i).isNotNull();
                assertThat(exchange.guard().waitingHeads()).as("heads waiting after request %d", i).isZero();
                assertThat(exchange.parsed()).as("value Undertow parsed").isEqualTo("Digest username=\"John Smith\"");
                assertThat(exchange.forwarded()).as("value forwarded for request %d", i).isEqualTo(DIGEST);
                if (first == null) {
                    first = exchange;
                }
                assertThat(exchange.channel()).isSameAs(first.channel());
                assertThat(exchange.guard()).isSameAs(first.guard());
            }
            if (option == ListenerOption.PLAIN) {
                assertThat(first.originalSourceConduit()).isSameAs(first.guard());
            } else {
                assertThat(first.originalSourceConduit()).as("conduit Undertow created its connection on")
                        .isNotInstanceOf(RequestFramingConduit.class);
            }
        }
    }

    /** Every listener option with either side closing the connection. */
    static Stream<Arguments> closings() {
        return Arrays.stream(ListenerOption.values())
                .flatMap(option -> Arrays.stream(Closer.values()).map(closer -> Arguments.of(option, closer)));
    }

    @ParameterizedTest(name = "{0}, closed by the {1}")
    @MethodSource("closings")
    void closingTheConnectionRemovesItsGuardWhileUndertowsCloseHandlingStillRuns(ListenerOption option,
                                                                                Closer closer)
            throws IOException, InterruptedException {
        BlockingQueue<Served> served = new LinkedBlockingQueue<>();
        CountDownLatch undertowClosed = new CountDownLatch(1);
        int port = start(option, exchange -> {
            exchange.getConnection().addCloseListener(connection -> undertowClosed.countDown());
            served.add(Served.of(exchange));
        });
        Served exchange;

        try (Socket socket = connect(port)) {
            write(socket, "GET / HTTP/1.1\r\nHost: localhost\r\n"
                    + (closer == Closer.SERVER ? "Connection: close\r\n" : "") + "\r\n");
            assertThat(readResponse(socket)).isEqualTo("HTTP/1.1 200 OK");
            exchange = served.poll(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(exchange).isNotNull();
            assertThat(exchange.guard()).as("guard registered while the connection is open").isNotNull();
            if (closer == Closer.SERVER) {
                assertThat(socket.getInputStream().read()).as("end of stream after the server closed").isEqualTo(-1);
            } else {
                assertThat(RequestFramingConduit.of(exchange.channel())).isSameAs(exchange.guard());
            }
        }

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(RequestFramingConduit.of(exchange.channel()))
                .as("guard registered after the connection closed").isNull());
        assertThat(undertowClosed.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .as("Undertow's close listeners ran").isTrue();
        assertThat(exchange.channel().isOpen()).isFalse();
    }

    /** XNIO runs a close listener set on a closed connection at once, so the entry put just before goes with it. */
    @Test
    void connectionThatHasAlreadyClosedIsForgottenAsSoonAsItIsAccepted() throws IOException, InterruptedException {
        customizer = customizer();
        Undertow.Builder builder = Undertow.builder();
        customizer.customize(builder);
        BlockingQueue<Object> closed = new LinkedBlockingQueue<>();
        AcceptingChannel<StreamConnection> raw = customizer.workers().get(0).delegate().createStreamConnectionServer(
                new InetSocketAddress(InetAddress.getByName(LOOPBACK), 0),
                channel -> {
                    try {
                        StreamConnection connection = channel.accept();
                        if (connection != null) {
                            IoUtils.safeClose(connection);
                            closed.add(connection);
                        }
                    } catch (IOException e) {
                        closed.add(e);
                    }
                },
                OptionMap.EMPTY);
        try {
            raw.resumeAccepts();
            InetSocketAddress address = raw.getLocalAddress(InetSocketAddress.class);
            Object accepted;
            try (Socket client = new Socket(address.getAddress(), address.getPort())) {
                assertThat(client.isConnected()).isTrue();
                accepted = closed.poll(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            }
            assertThat(accepted).isInstanceOf(StreamConnection.class);
            StreamConnection connection = (StreamConnection) accepted;
            assertThat(connection.isOpen()).isFalse();

            assertThat(new RequestFramingWorker.GuardedServer(serverReturning(connection), 8192).accept())
                    .isSameAs(connection);

            assertThat(connection.getSourceChannel().getConduit()).isInstanceOf(RequestFramingConduit.class);
            assertThat(RequestFramingConduit.of(connection)).isNull();
        } finally {
            raw.close();
        }
    }

    /** The step of {@code accept()} that fails: installing the guard, or setting the close listener once registered. */
    enum FailingStep {
        INSTALL,
        CLOSE_LISTENER
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(FailingStep.class)
    void connectionTheGuardCannotBeSetUpOnIsClosedAndLeavesNoEntry(FailingStep step) throws IOException {
        StreamConnection connection = mock(StreamConnection.class);
        ConduitStreamSourceChannel source = mock(ConduitStreamSourceChannel.class);
        when(connection.getSourceChannel()).thenReturn(source);
        when(source.getConduit()).thenReturn(mock(StreamSourceConduit.class));
        IllegalStateException failure = new IllegalStateException("refused by the test");
        if (step == FailingStep.INSTALL) {
            doThrow(failure).when(source).setConduit(any(StreamSourceConduit.class));
        } else {
            doThrow(failure).when(connection).setCloseListener(any());
        }
        RequestFramingWorker.GuardedServer server = new RequestFramingWorker.GuardedServer(
                serverReturning(connection), 8192);

        assertThatThrownBy(server::accept).isSameAs(failure);

        assertThat(RequestFramingConduit.of(connection)).isNull();
        verify(connection).close();
    }

    /**
     * Starts an Undertow server on a guarded worker with {@code option}, whose handler chain is the
     * {@link RequestFramingGuardConfig.SentHeaderFieldsHandler} in front of a handler that passes each exchange to
     * {@code observer} and answers {@code 200} with the body {@code ok}.
     *
     * @return the listener's port
     */
    private int start(ListenerOption option, Consumer<HttpServerExchange> observer) {
        customizer = customizer();
        Undertow.Builder builder = Undertow.builder().addHttpListener(0, LOOPBACK);
        option.setting.accept(builder);
        builder.setHandler(new RequestFramingGuardConfig.SentHeaderFieldsHandler(exchange -> {
            observer.accept(exchange);
            exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, OK.length);
            exchange.getResponseSender().send(new String(OK, ISO_8859_1));
        }));
        customizer.customize(builder);
        server = builder.build();
        server.start();
        return ((InetSocketAddress) server.getListenerInfo().get(0).getAddress()).getPort();
    }

    /** A customizer with two I/O and four worker threads, enough for one connection at a time. */
    private static RequestFramingGuardConfig.GuardedWorkerCustomizer customizer() {
        ServerProperties properties = new ServerProperties();
        properties.getUndertow().getThreads().setIo(2);
        properties.getUndertow().getThreads().setWorker(4);
        return (RequestFramingGuardConfig.GuardedWorkerCustomizer)
                new RequestFramingGuardConfig().requestFramingGuardCustomizer(properties);
    }

    /** A stream server whose one accepted connection is {@code connection}. */
    @SuppressWarnings("unchecked")
    private static AcceptingChannel<StreamConnection> serverReturning(StreamConnection connection) throws IOException {
        AcceptingChannel<StreamConnection> server = mock(AcceptingChannel.class);
        when(server.accept()).thenReturn(connection);
        return server;
    }

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(LOOPBACK, port), (int) TIMEOUT.toMillis());
        socket.setSoTimeout((int) TIMEOUT.toMillis());
        return socket;
    }

    private static void write(Socket socket, String bytes) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(bytes.getBytes(ISO_8859_1));
        out.flush();
    }

    /** Reads one response, its head and its {@code ok} body, and returns its status line. */
    private static String readResponse(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int b = in.read();
            if (b == -1) {
                throw new IOException("connection closed inside the response head");
            }
            head.write(b);
            matched = b == "\r\n\r\n".charAt(matched) ? matched + 1 : (b == '\r' ? 1 : 0);
        }
        assertThat(in.readNBytes(OK.length)).isEqualTo(OK);
        return head.toString(ISO_8859_1).split("\r\n")[0];
    }

    /**
     * What the handler saw of one exchange: the connection's channel, the guard registered for it, the conduit
     * Undertow created its connection on, the {@code Authorization} value Undertow parsed and the one
     * {@link ForwardedRequestHeaders} forwards.
     */
    private record Served(StreamConnection channel, RequestFramingConduit guard,
                          StreamSourceConduit originalSourceConduit, String parsed, String forwarded) {

        static Served of(HttpServerExchange exchange) {
            AbstractServerConnection connection = (AbstractServerConnection) exchange.getConnection();
            MockHttpServletRequest request = new MockHttpServletRequest();
            for (HeaderValues values : exchange.getRequestHeaders()) {
                for (String value : values) {
                    request.addHeader(values.getHeaderName().toString(), value);
                }
            }
            return new Served(connection.getChannel(), RequestFramingConduit.of(connection.getChannel()),
                    connection.getOriginalSourceConduit(),
                    exchange.getRequestHeaders().getFirst(Headers.AUTHORIZATION),
                    ForwardedRequestHeaders.from(request, exchange.getAttachment(SentHeaderFields.ATTACHMENT))
                            .getFirst("Authorization"));
        }
    }
}
