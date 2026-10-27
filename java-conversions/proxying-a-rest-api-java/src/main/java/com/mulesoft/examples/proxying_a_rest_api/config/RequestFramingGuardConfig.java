/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_rest_api.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.AbstractServerConnection;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.web.embedded.undertow.UndertowBuilderCustomizer;
import org.springframework.boot.web.embedded.undertow.UndertowDeploymentInfoCustomizer;
import org.springframework.boot.web.server.Ssl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.util.unit.DataSize;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.Xnio;
import org.xnio.XnioWorker;

/**
 * Puts the request-framing guard in front of Undertow's HTTP/1.1 parser: the listener's Undertow server runs on a
 * {@link RequestFramingWorker}, which installs a {@link RequestFramingConduit} on every connection it accepts, so a
 * request head with a bare CR or LF, a control character in a header value or ambiguous body framing is answered
 * with Undertow's own empty {@code 400 Bad Request} and never reaches a handler or the upstream.
 *
 * <p>The Spring Boot 3.2.12 dependency management resolves Undertow 2.3.17.Final, whose parser ends a header line at
 * a bare CR or LF, so {@code X-Test: a<CR>Authorization: Bearer x} would reach the upstream as a second
 * {@code Authorization} header; no Undertow option changes that, and the strict parser that refuses it ships only
 * from Undertow 2.3.25.Final on.
 *
 * <p>The delegate worker is created with the option map Undertow uses for the worker it would otherwise create itself
 * ({@code Undertow.start()}): I/O threads and worker threads from {@code server.undertow.threads.io} and
 * {@code server.undertow.threads.worker}, or Undertow's defaults (the available processors, at least 2, and eight
 * worker threads per I/O thread), connection high and low water 1000000, as many core as maximum worker threads,
 * {@code TCP_NODELAY} and {@code CORK}, followed by {@link ListenerWorkerThreadingConfig#workerOptions()}. Undertow
 * ignores worker options set on its builder once a worker is given, which is why those options are applied here.
 * The head limit is {@code server.max-http-request-header-size} when positive, otherwise Undertow's 1 MiB default:
 * the value Spring Boot gives Undertow's {@code MAX_HEADER_SIZE}.
 *
 * <p>Undertow never shuts down a worker it was given, so every worker created here is stopped when the application
 * context closes, after the web server has stopped, the way Undertow stops its own worker under the zero shutdown
 * timeout Spring Boot sets: shut down, and shut down at once unless already terminated.
 *
 * <p>The guard reads plain HTTP/1.x. The project's listener is plain HTTP; a configuration enabling TLS
 * ({@code server.ssl}) or HTTP/2 ({@code server.http2.enabled}) is refused at start-up, because the guard would then
 * read encrypted bytes or HTTP/2 frames as an HTTP/1.1 request and refuse every connection.
 *
 * <p>The guard also carries each request's header values as the client sent them to the proxy, because Undertow
 * 2.3.17's parser turns every run of SP and HTAB in a value, a single HTAB included, into one SP, and no option turns
 * that off. The conduit keeps the fields of every head it releases ({@link SentHeaderFields}), and a
 * {@link SentHeaderFieldsHandler}, the outermost handler of the deployment's initial chain, attaches the next of them
 * to each exchange, where {@link ForwardedRequestHeaders} reads them.
 */
@Configuration(proxyBeanMethods = false)
public class RequestFramingGuardConfig {

    /** Connection high and low water of the worker Undertow creates for itself. */
    static final int CONNECTION_WATER_MARK = 1_000_000;

    /** Worker threads per I/O thread in Undertow's default worker. */
    static final int WORKER_THREADS_PER_IO_THREAD = 8;

    /**
     * Order of {@link #sentHeaderFieldsCustomizer()} among the deployment customizers: the last. Spring Boot applies
     * them in this order and Undertow wraps the initial handlers in the order they are added, the last outermost.
     */
    static final int SENT_HEADER_FIELDS_ORDER = Ordered.LOWEST_PRECEDENCE;

    /**
     * Wraps the deployment's initial handler chain in a {@link SentHeaderFieldsHandler}. Registered last
     * ({@link #SENT_HEADER_FIELDS_ORDER}), so it is the outermost initial handler and runs for every exchange, the
     * ones {@link OriginFormTargetGuard} answers itself included, and each exchange takes its own head's fields.
     *
     * @return the customizer Spring Boot applies to the deployment of the embedded Undertow server
     */
    @Bean
    @Order(SENT_HEADER_FIELDS_ORDER)
    public UndertowDeploymentInfoCustomizer sentHeaderFieldsCustomizer() {
        return deploymentInfo -> deploymentInfo.addInitialHandlerChainWrapper(SentHeaderFieldsHandler::new);
    }

    /**
     * @param serverProperties the {@code server.*} configuration Spring Boot gives the embedded Undertow server
     * @return the customizer that sets a {@link RequestFramingWorker} on the Undertow builder and stops every worker
     *         it created when the context closes
     * @throws IllegalStateException if TLS or HTTP/2 is enabled for the server
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public UndertowBuilderCustomizer requestFramingGuardCustomizer(ServerProperties serverProperties) {
        return new GuardedWorkerCustomizer(serverProperties);
    }

    /** Sets a new guarded worker on every Undertow builder it customizes, and stops them all when destroyed. */
    static final class GuardedWorkerCustomizer implements UndertowBuilderCustomizer, DisposableBean {

        private final int ioThreads;

        private final int workerThreads;

        private final int maxHeadBytes;

        private final List<RequestFramingWorker> workers = new CopyOnWriteArrayList<>();

        GuardedWorkerCustomizer(ServerProperties serverProperties) {
            if (Ssl.isEnabled(serverProperties.getSsl())) {
                throw new IllegalStateException("The request-framing guard reads plain HTTP/1.1 and cannot run on a "
                        + "TLS listener: disable server.ssl");
            }
            if (serverProperties.getHttp2() != null && serverProperties.getHttp2().isEnabled()) {
                throw new IllegalStateException("The request-framing guard reads HTTP/1.1 only: disable "
                        + "server.http2.enabled");
            }
            ServerProperties.Undertow.Threads threads = serverProperties.getUndertow().getThreads();
            int defaultIoThreads = Math.max(Runtime.getRuntime().availableProcessors(), 2);
            this.ioThreads = threads.getIo() != null ? threads.getIo() : defaultIoThreads;
            this.workerThreads = threads.getWorker() != null
                    ? threads.getWorker()
                    : defaultIoThreads * WORKER_THREADS_PER_IO_THREAD;
            this.maxHeadBytes = maxHeadBytes(serverProperties.getMaxHttpRequestHeaderSize());
        }

        /**
         * Creates the delegate worker and sets a {@link RequestFramingWorker} over it on {@code builder}.
         *
         * @throws UncheckedIOException if XNIO cannot create the worker
         */
        @Override
        public void customize(Undertow.Builder builder) {
            XnioWorker delegate;
            try {
                delegate = Xnio.getInstance(Undertow.class.getClassLoader()).createWorker(delegateOptions());
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot create the XNIO worker of the listener", e);
            }
            RequestFramingWorker worker;
            try {
                worker = new RequestFramingWorker(delegate, maxHeadBytes);
            } catch (RuntimeException e) {
                delegate.shutdownNow();
                throw e;
            }
            workers.add(worker);
            builder.setWorker(worker);
        }

        /** Stops every worker created here; the web server has stopped by the time the context destroys beans. */
        @Override
        public void destroy() {
            for (RequestFramingWorker worker : workers) {
                worker.shutdown();
                try {
                    if (!worker.awaitTermination(0, TimeUnit.MILLISECONDS)) {
                        worker.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    worker.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            workers.clear();
        }

        /** The option map of Undertow's own worker, then the listener's thread idle policy. */
        OptionMap delegateOptions() {
            return OptionMap.builder()
                    .set(Options.WORKER_IO_THREADS, ioThreads)
                    .set(Options.CONNECTION_HIGH_WATER, CONNECTION_WATER_MARK)
                    .set(Options.CONNECTION_LOW_WATER, CONNECTION_WATER_MARK)
                    .set(Options.WORKER_TASK_CORE_THREADS, workerThreads)
                    .set(Options.WORKER_TASK_MAX_THREADS, workerThreads)
                    .set(Options.TCP_NODELAY, true)
                    .set(Options.CORK, true)
                    .addAll(ListenerWorkerThreadingConfig.workerOptions())
                    .getMap();
        }

        /** @return the head limit every accepted connection is guarded with */
        int maxHeadBytes() {
            return maxHeadBytes;
        }

        /** @return the workers created so far and not yet stopped */
        List<RequestFramingWorker> workers() {
            return List.copyOf(workers);
        }

        /** Spring Boot passes a positive size, truncated to {@code int}, to Undertow; anything else keeps 1 MiB. */
        private static int maxHeadBytes(DataSize configured) {
            int bytes = configured == null ? 0 : (int) configured.toBytes();
            return bytes > 0 ? bytes : UndertowOptions.DEFAULT_MAX_HEADER_SIZE;
        }
    }

    /**
     * Attaches to each exchange, under {@link SentHeaderFields#ATTACHMENT}, the header fields its request head was
     * sent with, then passes the exchange on unchanged.
     *
     * <p>The fields come from the connection's {@link RequestFramingConduit}, the source conduit Undertow's connection
     * was created on. Undertow parses the heads of a connection in the order the conduit released them and runs one
     * exchange per head, one after another; every head it does not run an exchange for (a parse failure, a missing or
     * repeated {@code Host}) is answered with 400 and closes the connection. Taking the next released head once per
     * exchange therefore gives each exchange its own head, also when Undertow runs a pipelined request on a worker
     * thread. An exchange whose connection was created on another conduit, because an Undertow option wrapped the
     * guard (an idle or read timeout, connector statistics), gets nothing, and its header values stay as Undertow
     * parsed them.
     */
    static final class SentHeaderFieldsHandler implements HttpHandler {

        private final HttpHandler next;

        /**
         * @param next the handler every exchange goes to
         */
        SentHeaderFieldsHandler(HttpHandler next) {
            this.next = next;
        }

        /**
         * @param exchange the request as Undertow parsed it
         * @throws Exception whatever the next handler throws
         */
        @Override
        public void handleRequest(HttpServerExchange exchange) throws Exception {
            if (exchange.getAttachment(SentHeaderFields.ATTACHMENT) == null
                    && exchange.getConnection() instanceof AbstractServerConnection connection
                    && connection.getOriginalSourceConduit() instanceof RequestFramingConduit guard) {
                SentHeaderFields head = guard.nextReleasedHead();
                if (head != null) {
                    exchange.putAttachment(SentHeaderFields.ATTACHMENT, head);
                }
            }
            next.handleRequest(exchange);
        }
    }
}
