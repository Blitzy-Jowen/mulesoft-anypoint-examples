/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_rest_api.config;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.xnio.Options;
import org.xnio.XnioWorker;
import org.xnio.management.XnioWorkerMXBean;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.mulesoft.examples.proxying_a_rest_api.ProxyingARestApiApplication;
import com.mulesoft.examples.proxying_a_rest_api.support.HttpTestClient;

import io.undertow.Undertow;

/**
 * Tests {@link ListenerWorkerThreadingConfig}: the running listener's XNIO worker pool has one core thread, a 60 s
 * keep-alive and Undertow's default maximum, as the original listener's default threading profile had; concurrent
 * requests still run in parallel on workers started above the core; and those workers are retired once idle for the
 * keep-alive, observed with a keep-alive shortened on the running worker for that case alone.
 *
 * <p>Every case boots the application with its own {@code application.yml} on Undertow (random port on IPv4
 * loopback) against a WireMock upstream, sends requests through ST-1 {@link HttpTestClient}, and reads the pool of
 * the web server's own worker through its {@link XnioWorkerMXBean} and {@link XnioWorker#getOption}.
 */
@SpringBootTest(
        classes = ProxyingARestApiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Pins the listener to the address ST-1 connects to, whatever server.address the project sets.
        properties = "server.address=" + ListenerWorkerThreadingConfigTest.LOOPBACK)
@ActiveProfiles("test")
class ListenerWorkerThreadingConfigTest {

    /** IPv4 loopback literal: {@code localhost} may resolve to {@code ::1}, where nothing listens. */
    static final String LOOPBACK = "127.0.0.1";

    /** The original listener's {@code maxThreadsIdle}: Mule 3.8's default threading profile. */
    private static final int MULE_MAX_THREADS_IDLE = 1;

    /** The original listener's {@code threadTTL} in milliseconds: Mule 3.8's default threading profile. */
    private static final int MULE_THREAD_TTL_MILLIS = 60_000;

    private static final int CONCURRENT_REQUESTS = 10;

    /** How long the upstream holds every slow request, and with it the worker that forwards it. */
    private static final int UPSTREAM_DELAY_MILLIS = 2_000;

    /**
     * Keep-alive the retirement case gives the running worker for its duration, so it observes retirement without
     * waiting the production 60 s; the production value is restored afterwards.
     */
    private static final int SHORT_KEEP_ALIVE_MILLIS = 500;

    private static final long SAMPLE_INTERVAL_MILLIS = 5;

    private static final String UPSTREAM_BODY = "ok";

    @RegisterExtension
    static WireMockExtension upstream = WireMockExtension.newInstance()
            // Container threads well above the burst, so the upstream never serialises the requests itself.
            .options(wireMockConfig().dynamicPort().bindAddress(LOOPBACK).containerThreads(4 * CONCURRENT_REQUESTS))
            .build();

    @LocalServerPort
    int port;

    @Autowired
    ServletWebServerApplicationContext context;

    @DynamicPropertySource
    static void upstreamUrl(DynamicPropertyRegistry registry) {
        registry.add("proxy.upstream-url", () -> "http://" + LOOPBACK + ":" + upstream.getPort());
    }

    /**
     * Stubs a fast and a slow upstream path, then sends one request to the fast one, so the first-request
     * initialisation of the dispatcher and the upstream client is not part of any measured burst.
     */
    @BeforeEach
    void stubUpstreamAndWarmUp() {
        upstream.stubFor(get(urlEqualTo("/fast")).willReturn(ok(UPSTREAM_BODY)));
        upstream.stubFor(get(urlPathMatching("/slow/.*"))
                .willReturn(ok(UPSTREAM_BODY).withFixedDelay(UPSTREAM_DELAY_MILLIS)));

        HttpTestClient.Response warmUp = new HttpTestClient(LOOPBACK, port).get("/fast");

        assertThat(warmUp.statusLine()).as(warmUp::toString).isEqualTo("HTTP/1.1 200 OK");
        upstream.resetRequests();
    }

    @Test
    void runningWorkerHasOneCoreThreadASixtySecondKeepAliveAndUndertowsDefaultMaximum() throws IOException {
        XnioWorker worker = worker();
        XnioWorkerMXBean pool = worker.getMXBean();
        int undertowDefaultMaximum = (int) ReflectionTestUtils.getField(Undertow.builder(), "workerThreads");

        assertThat(pool.getCoreWorkerPoolSize()).isEqualTo(MULE_MAX_THREADS_IDLE);
        assertThat(worker.getOption(Options.WORKER_TASK_CORE_THREADS)).isEqualTo(MULE_MAX_THREADS_IDLE);
        assertThat(worker.getOption(Options.WORKER_TASK_KEEPALIVE)).isEqualTo(MULE_THREAD_TTL_MILLIS);
        assertThat(pool.getMaxWorkerPoolSize()).isEqualTo(undertowDefaultMaximum);
        assertThat(worker.getOption(Options.WORKER_TASK_MAX_THREADS)).isEqualTo(undertowDefaultMaximum);
        assertThat(undertowDefaultMaximum).isGreaterThanOrEqualTo(CONCURRENT_REQUESTS);
    }

    @Test
    void concurrentRequestsRunInParallelOnWorkersStartedAboveTheCore() throws Exception {
        Burst burst = sendConcurrently(CONCURRENT_REQUESTS);

        // The pool started a thread above the core for each request that found the existing ones busy, and no
        // request waited in its queue. (The pool's busy-thread count is not used: the executor reports it as -1
        // unless active-count statistics are enabled.)
        assertThat(burst.peakPoolSize()).as(burst::toString).isGreaterThanOrEqualTo(CONCURRENT_REQUESTS);
        assertThat(burst.peakQueuedTasks()).as(burst::toString).isZero();
        // Serialised behind the core thread the burst would take CONCURRENT_REQUESTS upstream delays; in parallel
        // it takes about one.
        assertThat(burst.elapsedMillis()).as(burst::toString).isLessThan(2L * UPSTREAM_DELAY_MILLIS);
        upstream.verify(CONCURRENT_REQUESTS, anyRequestedFor(urlPathMatching("/slow/.*")));
    }

    @Test
    void workersAboveTheCoreAreRetiredOnceIdleForTheKeepAlive() throws Exception {
        XnioWorker worker = worker();
        XnioWorkerMXBean pool = worker.getMXBean();
        int productionKeepAlive = worker.getOption(Options.WORKER_TASK_KEEPALIVE);
        worker.setOption(Options.WORKER_TASK_KEEPALIVE, SHORT_KEEP_ALIVE_MILLIS);
        try {
            // At least one request per worker already started, so every one of them takes a request and waits
            // for the next one under the short keep-alive.
            int requests = Math.max(CONCURRENT_REQUESTS, pool.getWorkerPoolSize());
            Burst burst = sendConcurrently(requests);
            assertThat(burst.peakPoolSize()).as(burst::toString).isGreaterThanOrEqualTo(requests);

            // At most the core thread is left. When several workers time out together the executor may retire the
            // last one too; the next request then starts a worker again.
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50))
                    .untilAsserted(() -> assertThat(pool.getWorkerPoolSize())
                            .isLessThanOrEqualTo(MULE_MAX_THREADS_IDLE));
            assertThat(pool.getWorkerQueueSize()).isZero();

            HttpTestClient.Response afterRetirement = new HttpTestClient(LOOPBACK, port).get("/fast");
            assertThat(afterRetirement.statusLine()).as(afterRetirement::toString).isEqualTo("HTTP/1.1 200 OK");
            assertThat(afterRetirement.bodyAsString()).as(afterRetirement::toString).isEqualTo(UPSTREAM_BODY);
        } finally {
            worker.setOption(Options.WORKER_TASK_KEEPALIVE, productionKeepAlive);
        }
        assertThat(worker.getOption(Options.WORKER_TASK_KEEPALIVE)).isEqualTo(MULE_THREAD_TTL_MILLIS);
    }

    /**
     * Sends {@code requests} concurrent requests to the slow upstream path, each from its own thread, while sampling
     * the worker pool until all of them have answered, and asserts each was answered with the upstream's body.
     */
    private Burst sendConcurrently(int requests) throws Exception {
        XnioWorkerMXBean pool = worker().getMXBean();
        HttpTestClient client = new HttpTestClient(LOOPBACK, port);
        ExecutorService callers = Executors.newFixedThreadPool(requests);
        try {
            long start = System.nanoTime();
            List<Future<HttpTestClient.Response>> responses = new ArrayList<>(requests);
            for (int i = 0; i < requests; i++) {
                String path = "/slow/" + i;
                responses.add(callers.submit(() -> client.get(path)));
            }
            int peakPoolSize = 0;
            int peakQueuedTasks = 0;
            while (!responses.stream().allMatch(Future::isDone)) {
                peakPoolSize = Math.max(peakPoolSize, pool.getWorkerPoolSize());
                peakQueuedTasks = Math.max(peakQueuedTasks, pool.getWorkerQueueSize());
                Thread.sleep(SAMPLE_INTERVAL_MILLIS);
            }
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            for (Future<HttpTestClient.Response> future : responses) {
                HttpTestClient.Response response = future.get();
                assertThat(response.statusLine()).as(response::toString).isEqualTo("HTTP/1.1 200 OK");
                assertThat(response.bodyAsString()).as(response::toString).isEqualTo(UPSTREAM_BODY);
            }
            return new Burst(requests, elapsedMillis, peakPoolSize, peakQueuedTasks);
        } finally {
            callers.shutdownNow();
        }
    }

    /** The XNIO worker of this context's running Undertow server. */
    private XnioWorker worker() {
        Undertow undertow = (Undertow) ReflectionTestUtils.getField(context.getWebServer(), "undertow");
        assertThat(undertow).as("running Undertow server").isNotNull();
        return undertow.getWorker();
    }

    /** What one burst of concurrent requests measured: its wall time and the pool's peaks while it ran. */
    private record Burst(int requests, long elapsedMillis, int peakPoolSize, int peakQueuedTasks) {
    }
}
