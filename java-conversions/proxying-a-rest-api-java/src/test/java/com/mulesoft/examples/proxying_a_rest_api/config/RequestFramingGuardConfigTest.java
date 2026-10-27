/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_rest_api.config;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.web.server.Ssl;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.unit.DataSize;
import org.xnio.IoUtils;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.StreamConnection;
import org.xnio.XnioWorker;
import org.xnio.channels.AcceptingChannel;
import org.xnio.conduits.StreamSourceConduit;
import org.xnio.management.XnioServerMXBean;

import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.AbstractServerConnection;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.ServerConnection;

/**
 * Tests {@link RequestFramingGuardConfig} and {@link RequestFramingWorker} without a web server: the worker set on the
 * Undertow builder, the option map of its delegate and the head limit as {@code server.*} configures them, the
 * refusal of TLS and HTTP/2, the shutdown when the context destroys the customizer, a stream server whose accepted
 * connections carry the guard, the server MXBean registration, and the handler that attaches each exchange's header
 * fields as sent, over mocked Undertow connections.
 */
class RequestFramingGuardConfigTest {

    private RequestFramingGuardConfig.GuardedWorkerCustomizer customizer;

    @AfterEach
    void stopWorkers() {
        if (customizer != null) {
            customizer.destroy();
        }
    }

    @Test
    void builderGetsAGuardedWorkerWithUndertowsOptionsAndTheListenerIdlePolicy() throws IOException {
        customizer = customizer(new ServerProperties());
        Undertow.Builder builder = Undertow.builder();
        int defaultWorkerThreads = (int) ReflectionTestUtils.getField(builder, "workerThreads");
        int defaultIoThreads = (int) ReflectionTestUtils.getField(builder, "ioThreads");

        customizer.customize(builder);

        Object worker = ReflectionTestUtils.getField(builder, "worker");
        assertThat(worker).isInstanceOf(RequestFramingWorker.class);
        RequestFramingWorker guarded = (RequestFramingWorker) worker;
        assertThat(customizer.workers()).containsExactly(guarded);
        assertThat(guarded.getIoThreadCount()).isEqualTo(defaultIoThreads);
        assertThat(guarded.getOption(Options.WORKER_TASK_MAX_THREADS)).isEqualTo(defaultWorkerThreads);
        assertThat(guarded.getOption(Options.WORKER_TASK_CORE_THREADS))
                .isEqualTo(ListenerWorkerThreadingConfig.CORE_WORKER_THREADS);
        assertThat(guarded.getOption(Options.WORKER_TASK_KEEPALIVE))
                .isEqualTo(ListenerWorkerThreadingConfig.WORKER_KEEP_ALIVE_MILLIS);
        assertThat(guarded.getName()).isEqualTo(guarded.delegate().getName());
        assertThat(guarded.getMXBean()).isSameAs(guarded.delegate().getMXBean());
        OptionMap options = customizer.delegateOptions();
        assertThat(options.get(Options.CONNECTION_HIGH_WATER, 0)).isEqualTo(1_000_000);
        assertThat(options.get(Options.CONNECTION_LOW_WATER, 0)).isEqualTo(1_000_000);
        assertThat(options.get(Options.TCP_NODELAY, false)).isTrue();
        assertThat(options.get(Options.CORK, false)).isTrue();
    }

    @Test
    void configuredThreadCountsReachTheDelegate() throws IOException {
        ServerProperties properties = new ServerProperties();
        properties.getUndertow().getThreads().setIo(3);
        properties.getUndertow().getThreads().setWorker(5);
        customizer = customizer(properties);
        Undertow.Builder builder = Undertow.builder();

        customizer.customize(builder);

        XnioWorker worker = (XnioWorker) ReflectionTestUtils.getField(builder, "worker");
        assertThat(worker.getIoThreadCount()).isEqualTo(3);
        assertThat(worker.getOption(Options.WORKER_TASK_MAX_THREADS)).isEqualTo(5);
        assertThat(worker.getOption(Options.WORKER_TASK_CORE_THREADS))
                .isEqualTo(ListenerWorkerThreadingConfig.CORE_WORKER_THREADS);
    }

    /** {@code server.max-http-request-header-size} in bytes, or blank for none, and the head limit it gives. */
    @ParameterizedTest(name = "[{0}] -> {1}")
    @CsvSource(nullValues = "NONE", value = {
            "8192, 8192",
            "16384, 16384",
            "0, " + UndertowOptions.DEFAULT_MAX_HEADER_SIZE,
            "-1, " + UndertowOptions.DEFAULT_MAX_HEADER_SIZE,
            "NONE, " + UndertowOptions.DEFAULT_MAX_HEADER_SIZE})
    void headLimitIsTheOneSpringBootGivesUndertow(Long configuredBytes, int limit) {
        ServerProperties properties = new ServerProperties();
        properties.setMaxHttpRequestHeaderSize(configuredBytes == null ? null : DataSize.ofBytes(configuredBytes));

        assertThat(customizer(properties).maxHeadBytes()).isEqualTo(limit);
    }

    @Test
    void defaultHeadLimitIsSpringBootsEightKilobytes() {
        assertThat(customizer(new ServerProperties()).maxHeadBytes()).isEqualTo(8 * 1024);
    }

    @Test
    void tlsAndHttp2AreRefused() {
        ServerProperties tls = new ServerProperties();
        Ssl ssl = new Ssl();
        ssl.setEnabled(true);
        tls.setSsl(ssl);
        ServerProperties http2 = new ServerProperties();
        http2.getHttp2().setEnabled(true);

        assertThatThrownBy(() -> customizer(tls)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("server.ssl");
        assertThatThrownBy(() -> customizer(http2)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("server.http2.enabled");
    }

    @Test
    void destroyStopsEveryWorkerItCreated() throws InterruptedException {
        customizer = customizer(new ServerProperties());
        Undertow.Builder first = Undertow.builder();
        Undertow.Builder second = Undertow.builder();
        customizer.customize(first);
        customizer.customize(second);
        RequestFramingWorker one = (RequestFramingWorker) ReflectionTestUtils.getField(first, "worker");
        RequestFramingWorker two = (RequestFramingWorker) ReflectionTestUtils.getField(second, "worker");
        assertThat(one.delegate()).isNotSameAs(two.delegate());

        customizer.destroy();

        assertThat(one.isShutdown()).isTrue();
        assertThat(two.delegate().isShutdown()).isTrue();
        assertThat(one.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(two.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(customizer.workers()).isEmpty();
    }

    @Test
    void acceptedConnectionsCarryTheGuardAndListenersSeeOnlyTheGuardedServer() throws Exception {
        customizer = customizer(new ServerProperties());
        Undertow.Builder builder = Undertow.builder();
        customizer.customize(builder);
        XnioWorker worker = (XnioWorker) ReflectionTestUtils.getField(builder, "worker");
        BlockingQueue<Object> accepted = new LinkedBlockingQueue<>();
        BlockingQueue<Object> listenerChannels = new LinkedBlockingQueue<>();

        AcceptingChannel<StreamConnection> server = worker.createStreamConnectionServer(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                channel -> {
                    listenerChannels.add(channel);
                    try {
                        StreamConnection connection = channel.accept();
                        if (connection != null) {
                            accepted.add(connection.getSourceChannel().getConduit());
                            IoUtils.safeClose(connection);
                        }
                    } catch (IOException e) {
                        accepted.add(e);
                    }
                },
                OptionMap.EMPTY);
        try {
            server.resumeAccepts();
            assertThat(server).isInstanceOf(RequestFramingWorker.GuardedServer.class);
            assertThat(ReflectionUtils.findField(server.getClass(), "ssl")).isNull();
            InetSocketAddress address = server.getLocalAddress(InetSocketAddress.class);
            assertThat(address.getPort()).isPositive();
            assertThat(server.getWorker()).isSameAs(((RequestFramingWorker) worker).delegate());

            try (Socket client = new Socket(address.getAddress(), address.getPort())) {
                assertThat(client.isConnected()).isTrue();
                assertThat(accepted.poll(10, TimeUnit.SECONDS)).isInstanceOf(RequestFramingConduit.class);
            }
            assertThat(listenerChannels.poll(1, TimeUnit.SECONDS)).isSameAs(server);
        } finally {
            server.close();
        }
        assertThat(server.isOpen()).isFalse();
    }

    @Test
    void serverMXBeanIsRegisteredUnderXniosNameAndRemovedOnce() throws Exception {
        customizer = customizer(new ServerProperties());
        Undertow.Builder builder = Undertow.builder();
        customizer.customize(builder);
        RequestFramingWorker worker = (RequestFramingWorker) ReflectionTestUtils.getField(builder, "worker");
        String bindAddress = "127.0.0.1:" + System.nanoTime();
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName("org.xnio:type=Xnio,provider=" + ObjectName.quote("nio")
                + ",worker=" + ObjectName.quote(worker.getName()) + ",address=" + ObjectName.quote(bindAddress));

        var registration = worker.registerServerMXBean(new FixedServerMXBean(worker.getName(), bindAddress));

        assertThat(server.isRegistered(name)).isTrue();
        var duplicate = worker.registerServerMXBean(new FixedServerMXBean(worker.getName(), bindAddress));
        registration.close();
        assertThat(server.isRegistered(name)).isFalse();
        registration.close();
        duplicate.close();
        assertThat(server.isRegistered(name)).isFalse();
    }

    @Test
    void sentHeaderFieldsHandlerGivesEachExchangeOfAGuardedConnectionTheNextReleasedHead() throws Exception {
        RequestFramingConduit guard = guardHolding(
                "GET /1 HTTP/1.1\r\nX-A: a\t1\r\n\r\nGET /2 HTTP/1.1\r\nX-A: a  2\r\n\r\nGET /3 HTTP/1.1\r\n\r\n");
        AbstractServerConnection connection = mock(AbstractServerConnection.class);
        when(connection.getOriginalSourceConduit()).thenReturn(guard);
        List<HttpServerExchange> passedOn = new ArrayList<>();
        HttpHandler handler = new RequestFramingGuardConfig.SentHeaderFieldsHandler(passedOn::add);
        HttpServerExchange first = new HttpServerExchange(connection);
        HttpServerExchange second = new HttpServerExchange(connection);
        HttpServerExchange third = new HttpServerExchange(connection);
        HttpServerExchange fourth = new HttpServerExchange(connection);

        handler.handleRequest(first);
        // An exchange that already carries its fields keeps them and takes no other head.
        handler.handleRequest(first);
        handler.handleRequest(second);
        handler.handleRequest(third);
        handler.handleRequest(fourth);

        assertThat(RequestFramingValidatorTest.fields(first.getAttachment(SentHeaderFields.ATTACHMENT)))
                .containsExactly("X-A", "a\t1");
        assertThat(RequestFramingValidatorTest.fields(second.getAttachment(SentHeaderFields.ATTACHMENT)))
                .containsExactly("X-A", "a  2");
        assertThat(third.getAttachment(SentHeaderFields.ATTACHMENT)).isSameAs(SentHeaderFields.NONE);
        assertThat(fourth.getAttachment(SentHeaderFields.ATTACHMENT)).isNull();
        assertThat(passedOn).containsExactly(first, first, second, third, fourth);
    }

    @Test
    void sentHeaderFieldsHandlerAttachesNothingWithoutTheGuardAsTheConnectionsSourceConduit() throws Exception {
        AbstractServerConnection wrapped = mock(AbstractServerConnection.class);
        when(wrapped.getOriginalSourceConduit()).thenReturn(mock(StreamSourceConduit.class));
        List<HttpServerExchange> passedOn = new ArrayList<>();
        HttpHandler handler = new RequestFramingGuardConfig.SentHeaderFieldsHandler(passedOn::add);
        HttpServerExchange overWrappedGuard = new HttpServerExchange(wrapped);
        HttpServerExchange overOtherConnection = new HttpServerExchange(mock(ServerConnection.class));

        handler.handleRequest(overWrappedGuard);
        handler.handleRequest(overOtherConnection);

        assertThat(overWrappedGuard.getAttachment(SentHeaderFields.ATTACHMENT)).isNull();
        assertThat(overOtherConnection.getAttachment(SentHeaderFields.ATTACHMENT)).isNull();
        assertThat(passedOn).containsExactly(overWrappedGuard, overOtherConnection);
    }

    /** A guard over a socket that delivers {@code heads} in one read, read once so that every head is released. */
    private static RequestFramingConduit guardHolding(String heads) throws IOException {
        byte[] bytes = heads.getBytes(ISO_8859_1);
        StreamSourceConduit socket = mock(StreamSourceConduit.class);
        when(socket.read(any(ByteBuffer.class))).thenAnswer(invocation -> {
            invocation.<ByteBuffer>getArgument(0).put(bytes);
            return bytes.length;
        }).thenReturn(0);
        RequestFramingConduit guard = new RequestFramingConduit(socket, 8192);
        assertThat(guard.read(ByteBuffer.allocate(bytes.length))).isEqualTo(bytes.length);
        return guard;
    }

    private static RequestFramingGuardConfig.GuardedWorkerCustomizer customizer(ServerProperties properties) {
        return (RequestFramingGuardConfig.GuardedWorkerCustomizer)
                new RequestFramingGuardConfig().requestFramingGuardCustomizer(properties);
    }

    /** Server metrics with fixed values, enough for a registration under XNIO's name. */
    private record FixedServerMXBean(String workerName, String bindAddress) implements XnioServerMXBean {

        @Override
        public String getProviderName() {
            return "nio";
        }

        @Override
        public String getWorkerName() {
            return workerName;
        }

        @Override
        public String getBindAddress() {
            return bindAddress;
        }

        @Override
        public int getConnectionCount() {
            return 0;
        }

        @Override
        public int getConnectionLimitHighWater() {
            return 0;
        }

        @Override
        public int getConnectionLimitLowWater() {
            return 0;
        }
    }
}
