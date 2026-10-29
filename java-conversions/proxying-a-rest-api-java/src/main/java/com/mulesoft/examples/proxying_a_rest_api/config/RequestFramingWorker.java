/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_rest_api.config;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.ChannelListener;
import org.xnio.ChannelListeners;
import org.xnio.IoUtils;
import org.xnio.LocalSocketAddress;
import org.xnio.ManagementRegistration;
import org.xnio.Option;
import org.xnio.OptionMap;
import org.xnio.StreamConnection;
import org.xnio.XnioExecutor;
import org.xnio.XnioIoThread;
import org.xnio.XnioWorker;
import org.xnio.channels.AcceptingChannel;
import org.xnio.channels.MulticastMessageChannel;
import org.xnio.conduits.ConduitStreamSourceChannel;
import org.xnio.management.XnioServerMXBean;
import org.xnio.management.XnioWorkerMXBean;

/**
 * The XNIO worker the listener's Undertow server runs on: every stream server it creates installs a
 * {@link RequestFramingConduit} on each connection it accepts, before Undertow's HTTP open listener reads a byte.
 * Setting a worker on the Undertow builder is the one hook Undertow offers ahead of its request parser.
 *
 * <p>Everything else is the delegate's, an ordinary XNIO worker this one wraps: the I/O threads, the task pool Undertow
 * dispatches requests to, its options ({@code WORKER_TASK_CORE_THREADS}, {@code WORKER_TASK_MAX_THREADS},
 * {@code WORKER_TASK_KEEPALIVE}, read and set on the delegate's pool), its management bean, its name and its life
 * cycle. This worker is built on the delegate as its external executor, so it starts no thread of its own, and its
 * shutdown methods shut the delegate down. Undertow never shuts down a worker it was given;
 * {@link RequestFramingGuardConfig} does when the application context closes.
 */
final class RequestFramingWorker extends XnioWorker {

    private static final Logger LOG = LoggerFactory.getLogger(RequestFramingWorker.class);

    private final XnioWorker delegate;

    private final int maxHeadBytes;

    /**
     * @param delegate     the worker that owns the threads, sockets and task pool
     * @param maxHeadBytes the most bytes a request head may hold on an accepted connection
     */
    RequestFramingWorker(XnioWorker delegate, int maxHeadBytes) {
        super(delegate.getXnio().createWorkerBuilder()
                .setWorkerName(delegate.getName())
                .setExternalExecutorService(delegate));
        if (maxHeadBytes <= 0) {
            throw new IllegalArgumentException("maxHeadBytes must be positive: " + maxHeadBytes);
        }
        this.delegate = delegate;
        this.maxHeadBytes = maxHeadBytes;
    }

    /** @return the worker that owns the threads, sockets and task pool */
    XnioWorker delegate() {
        return delegate;
    }

    @Override
    protected AcceptingChannel<StreamConnection> createTcpConnectionServer(
            InetSocketAddress bindAddress, ChannelListener<? super AcceptingChannel<StreamConnection>> acceptListener,
            OptionMap optionMap) throws IOException {
        return guard(delegate.createStreamConnectionServer(bindAddress, null, optionMap), acceptListener);
    }

    @Override
    protected AcceptingChannel<StreamConnection> createLocalStreamConnectionServer(
            LocalSocketAddress bindAddress, ChannelListener<? super AcceptingChannel<StreamConnection>> acceptListener,
            OptionMap optionMap) throws IOException {
        return guard(delegate.createStreamConnectionServer(bindAddress, null, optionMap), acceptListener);
    }

    private AcceptingChannel<StreamConnection> guard(AcceptingChannel<StreamConnection> server,
            ChannelListener<? super AcceptingChannel<StreamConnection>> acceptListener) {
        GuardedServer guarded = new GuardedServer(server, maxHeadBytes);
        guarded.getAcceptSetter().set(acceptListener);
        return guarded;
    }

    @Override
    public MulticastMessageChannel createUdpServer(InetSocketAddress bindAddress,
            ChannelListener<? super MulticastMessageChannel> bindListener, OptionMap optionMap) throws IOException {
        return delegate.createUdpServer(bindAddress, bindListener, optionMap);
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    @Override
    public void awaitTermination() throws InterruptedException {
        delegate.awaitTermination();
    }

    @Override
    public XnioIoThread getIoThread(int hashCode) {
        return delegate.getIoThread(hashCode);
    }

    @Override
    protected XnioIoThread chooseThread() {
        return delegate.getIoThread();
    }

    @Override
    public int getIoThreadCount() {
        return delegate.getIoThreadCount();
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(command);
    }

    @Override
    public boolean supportsOption(Option<?> option) {
        return delegate.supportsOption(option);
    }

    @Override
    public <T> T getOption(Option<T> option) throws IOException {
        return delegate.getOption(option);
    }

    @Override
    public <T> T setOption(Option<T> option, T value) throws IOException {
        return delegate.setOption(option, value);
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public InetSocketAddress getBindAddress(InetAddress destination) {
        return delegate.getBindAddress(destination);
    }

    @Override
    public XnioWorkerMXBean getMXBean() {
        return delegate.getMXBean();
    }

    /**
     * Registers {@code metrics} in the platform MBean server under the name XNIO gives a server bean. Every server
     * this worker hands out is created and registered by the delegate, so XNIO never calls this here; it is
     * implemented the way XNIO registers one: a failed registration is logged at DEBUG and leaves nothing to remove.
     */
    @Override
    protected ManagementRegistration registerServerMXBean(XnioServerMXBean metrics) {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = null;
        try {
            name = new ObjectName("org.xnio:type=Xnio"
                    + ",provider=" + ObjectName.quote(metrics.getProviderName())
                    + ",worker=" + ObjectName.quote(metrics.getWorkerName())
                    + ",address=" + ObjectName.quote(metrics.getBindAddress()));
            server.registerMBean(metrics, name);
        } catch (JMException e) {
            LOG.debug("Server MXBean of {} not registered", metrics.getBindAddress(), e);
            name = null;
        }
        return new MXBeanRegistration(server, name);
    }

    /** Removes a registered server bean once; a registration that failed holds no name and removes nothing. */
    private static final class MXBeanRegistration implements ManagementRegistration {

        private final MBeanServer server;

        private final ObjectName name;

        private final AtomicBoolean removed = new AtomicBoolean();

        MXBeanRegistration(MBeanServer server, ObjectName name) {
            this.server = server;
            this.name = name;
        }

        @Override
        public void close() {
            if (name == null || removed.getAndSet(true)) {
                return;
            }
            try {
                server.unregisterMBean(name);
            } catch (JMException e) {
                LOG.debug("Server MXBean {} was already removed", name, e);
            }
        }
    }

    /**
     * A stream server of the delegate whose {@link #accept()} installs a {@link RequestFramingConduit} on each
     * connection; its listeners receive this channel, so no caller ever accepts from the unguarded server. Everything
     * else is the server's. It declares no field named {@code ssl}, so Spring Boot reports its port as {@code http}.
     */
    static final class GuardedServer implements AcceptingChannel<StreamConnection> {

        private final AcceptingChannel<StreamConnection> server;

        private final int maxHeadBytes;

        GuardedServer(AcceptingChannel<StreamConnection> server, int maxHeadBytes) {
            this.server = server;
            this.maxHeadBytes = maxHeadBytes;
        }

        /**
         * Accepts the next connection and puts the guard directly above its socket conduit. A connection the guard
         * cannot be installed on is closed rather than returned unguarded.
         */
        @Override
        public StreamConnection accept() throws IOException {
            StreamConnection connection = server.accept();
            if (connection == null) {
                return null;
            }
            try {
                ConduitStreamSourceChannel source = connection.getSourceChannel();
                source.setConduit(new RequestFramingConduit(source.getConduit(), maxHeadBytes));
                return connection;
            } catch (RuntimeException e) {
                IoUtils.safeClose(connection);
                throw e;
            }
        }

        @Override
        public ChannelListener.Setter<GuardedServer> getAcceptSetter() {
            return ChannelListeners.getDelegatingSetter(server.getAcceptSetter(), this);
        }

        @Override
        public ChannelListener.Setter<GuardedServer> getCloseSetter() {
            return ChannelListeners.getDelegatingSetter(server.getCloseSetter(), this);
        }

        @Override
        public SocketAddress getLocalAddress() {
            return server.getLocalAddress();
        }

        @Override
        public <A extends SocketAddress> A getLocalAddress(Class<A> type) {
            return server.getLocalAddress(type);
        }

        @Override
        public void suspendAccepts() {
            server.suspendAccepts();
        }

        @Override
        public void resumeAccepts() {
            server.resumeAccepts();
        }

        @Override
        public boolean isAcceptResumed() {
            return server.isAcceptResumed();
        }

        @Override
        @Deprecated
        public void wakeupAccepts() {
            server.wakeupAccepts();
        }

        @Override
        public void awaitAcceptable() throws IOException {
            server.awaitAcceptable();
        }

        @Override
        public void awaitAcceptable(long time, TimeUnit timeUnit) throws IOException {
            server.awaitAcceptable(time, timeUnit);
        }

        @Override
        @Deprecated
        public XnioExecutor getAcceptThread() {
            return server.getAcceptThread();
        }

        @Override
        public XnioWorker getWorker() {
            return server.getWorker();
        }

        @Override
        public XnioIoThread getIoThread() {
            return server.getIoThread();
        }

        @Override
        public void close() throws IOException {
            server.close();
        }

        @Override
        public boolean isOpen() {
            return server.isOpen();
        }

        @Override
        public boolean supportsOption(Option<?> option) {
            return server.supportsOption(option);
        }

        @Override
        public <T> T getOption(Option<T> option) throws IOException {
            return server.getOption(option);
        }

        @Override
        public <T> T setOption(Option<T> option, T value) throws IOException {
            return server.setOption(option, value);
        }
    }
}
