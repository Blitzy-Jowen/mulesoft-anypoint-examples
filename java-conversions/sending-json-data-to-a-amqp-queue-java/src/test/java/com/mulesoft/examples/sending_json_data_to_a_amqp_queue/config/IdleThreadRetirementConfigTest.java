/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_json_data_to_a_amqp_queue.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.qpid.server.SystemLauncher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;
import org.xnio.Options;
import org.xnio.XnioWorker;

import com.mulesoft.examples.sending_json_data_to_a_amqp_queue.support.HttpTestClient;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.impl.ConsumerWorkService;

import io.undertow.Undertow;

/**
 * Tests {@link IdleThreadRetirementConfig}. The Undertow cases start a real Undertow on a random loopback port with
 * Boot's servlet web server auto-configuration and a servlet that holds each request until
 * {@value #CONCURRENT_REQUESTS} requests are in it at once. The AMQP cases load
 * {@code src/main/resources/application.yml}, Boot's RabbitMQ auto-configuration and {@link AmqpConfig} as the
 * application does; the dispatch case runs against an embedded Qpid Broker-J configured by
 * {@code src/test/resources/config.json}.
 */
@DisplayName("IdleThreadRetirementConfig")
class IdleThreadRetirementConfigTest {

    /** Requests the rendezvous servlet holds until all of them have arrived. */
    private static final int CONCURRENT_REQUESTS = 10;

    /** How long a request waits in the rendezvous servlet for the others; below ST-1's 10 s read timeout. */
    private static final long RENDEZVOUS_TIMEOUT_MILLIS = 8_000L;

    /** Keep-alive of the short-lived variants, so a retirement is observed without Mule's 60 s wait. */
    private static final int SHORT_KEEP_ALIVE_MILLIS = 1_000;

    private static final String LOOPBACK = "127.0.0.1";

    private static final String RENDEZVOUS_PATH = "/rendezvous";

    private final WebApplicationContextRunner webRunner =
            new WebApplicationContextRunner(AnnotationConfigServletWebServerApplicationContext::new)
                    .withConfiguration(AutoConfigurations.of(ServletWebServerFactoryAutoConfiguration.class))
                    .withUserConfiguration(RendezvousServletConfig.class)
                    .withPropertyValues("server.port=0", "server.address=" + LOOPBACK);

    private final ApplicationContextRunner amqpRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
            .withUserConfiguration(AmqpConfig.class, IdleThreadRetirementConfig.class);

    @Test
    @DisplayName("the running Undertow worker pool gets core size 1 and a 60000 ms keep-alive, and keeps Undertow's"
            + " maximum")
    void undertowWorkerPoolGetsMuleCoreSizeAndKeepAlive() {
        AtomicReference<Integer> undertowMaximum = new AtomicReference<>();
        webRunner.run(context -> {
            assertThat(context).hasNotFailed();
            XnioWorker worker = worker(context);
            // Undertow's own pool: core size equal to the maximum, so no worker ever retires.
            assertThat(worker.getOption(Options.WORKER_TASK_CORE_THREADS))
                    .isEqualTo(worker.getOption(Options.WORKER_TASK_MAX_THREADS));
            undertowMaximum.set(worker.getOption(Options.WORKER_TASK_MAX_THREADS));
        });

        webRunner.withUserConfiguration(IdleThreadRetirementConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            XnioWorker worker = worker(context);
            assertThat(worker.getOption(Options.WORKER_TASK_CORE_THREADS)).isEqualTo(1);
            assertThat(worker.getOption(Options.WORKER_TASK_KEEPALIVE)).isEqualTo(60_000);
            assertThat(worker.getOption(Options.WORKER_TASK_MAX_THREADS))
                    .isEqualTo(undertowMaximum.get())
                    .isEqualTo(worker.getIoThreadCount() * 8);
        });
    }

    @Test
    @DisplayName("with core size 1, ten concurrent slow requests still run at the same time on ten workers")
    void lowerCoreSizeDoesNotSerialiseConcurrentRequests() {
        webRunner.withUserConfiguration(IdleThreadRetirementConfig.class).run(context -> {
            assertThat(context).hasNotFailed();

            List<HttpTestClient.Response> responses = sendConcurrently(port(context));

            assertThat(responses).allSatisfy(response ->
                    assertThat(response.status()).as(response::toString).isEqualTo(200));
            assertThat(responses).extracting(HttpTestClient.Response::bodyAsString)
                    .allSatisfy(thread -> assertThat(thread).startsWith("XNIO-").contains(" task-"))
                    .doesNotHaveDuplicates();
        });
    }

    @Test
    @DisplayName("Undertow workers above the core size retire after the keep-alive, and the pool grows again")
    void undertowWorkersAboveTheCoreSizeRetire() {
        webRunner.withUserConfiguration(ShortKeepAliveWorkerConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            XnioWorker worker = worker(context);
            assertThat(worker.getOption(Options.WORKER_TASK_KEEPALIVE)).isEqualTo(SHORT_KEEP_ALIVE_MILLIS);

            for (int round = 1; round <= 2; round++) {
                String afterRound = "after round " + round;
                List<HttpTestClient.Response> responses = sendConcurrently(port(context));

                assertThat(responses).as(afterRound).allSatisfy(response ->
                        assertThat(response.status()).as(response::toString).isEqualTo(200));
                assertThat(responses).as("workers that ran at once, " + afterRound)
                        .extracting(HttpTestClient.Response::bodyAsString).doesNotHaveDuplicates();
                // Workers that time out at the same instant may all retire, so the pool can end below its core size.
                await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
                        .untilAsserted(() -> assertThat(worker.getMXBean().getWorkerPoolSize())
                                .as("worker threads " + afterRound).isLessThanOrEqualTo(1));
            }
        });
    }

    @Test
    @DisplayName("Boot's connection factory gets a consumer pool of amqp-client's own size with an unbounded queue,"
            + " whose threads retire after 60 s")
    void connectionFactoryGetsARetiringConsumerPool() {
        amqpRunner.run(context -> {
            assertThat(context).hasNotFailed();
            CachingConnectionFactory connectionFactory = context.getBean(CachingConnectionFactory.class);
            ThreadPoolExecutor pool = consumerPool(connectionFactory);

            // The size of the fixed pool amqp-client creates for a connection given no executor.
            Object threads = ReflectionTestUtils.getField(ConsumerWorkService.class, "DEFAULT_NUM_THREADS");
            assertThat(pool.getCorePoolSize()).isEqualTo(threads);
            assertThat(pool.getMaximumPoolSize()).isEqualTo(threads);
            assertThat(pool.allowsCoreThreadTimeOut()).isTrue();
            assertThat(pool.getKeepAliveTime(TimeUnit.MILLISECONDS)).isEqualTo(60_000L);
            assertThat(pool.getQueue()).isInstanceOf(LinkedBlockingQueue.class);
            assertThat(pool.getQueue().remainingCapacity()).isEqualTo(Integer.MAX_VALUE);
            assertThat(pool.getPoolSize()).as("threads started before any delivery").isZero();
            assertThat(consumerPool(connectionFactory.getPublisherConnectionFactory())).isSameAs(pool);
        });
    }

    @Test
    @DisplayName("Boot keeps its applicationTaskExecutor, the spring.rabbitmq.* settings and the lazy connection")
    void connectionFactoryKeepsEverythingElse() throws IOException {
        amqpRunner.withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
                .withPropertyValues("spring.rabbitmq.host=" + LOOPBACK, "spring.rabbitmq.port=" + unusedPort())
                .run(context -> {
                    assertThat(context).as("started with no broker listening").hasNotFailed();
                    assertThat(context.getBeansOfType(Executor.class)).containsOnlyKeys("applicationTaskExecutor");
                    com.rabbitmq.client.ConnectionFactory nativeFactory =
                            context.getBean(CachingConnectionFactory.class).getRabbitConnectionFactory();
                    assertThat(nativeFactory.getConnectionTimeout()).isEqualTo(10000);
                    assertThat(nativeFactory.getChannelRpcTimeout()).isEqualTo(10000);
                    assertThat(nativeFactory.isAutomaticRecoveryEnabled()).isFalse();
                });
    }

    @Test
    @DisplayName("the consumer pool is shut down when the context closes, after the connection factory")
    void consumerPoolIsShutDownAfterTheConnectionFactory() {
        AtomicReference<ThreadPoolExecutor> pool = new AtomicReference<>();
        amqpRunner.run(context -> {
            assertThat(context).hasNotFailed();
            pool.set(consumerPool(context.getBean(CachingConnectionFactory.class)));
            assertThat(context.getSourceApplicationContext().getBeanFactory()
                    .getDependentBeans("amqpConsumerExecutorInstaller"))
                    .as("beans the context destroys before the pool's owner")
                    .contains("rabbitConnectionFactory");
            assertThat(pool.get().isShutdown()).isFalse();
        });
        assertThat(pool.get().isShutdown()).isTrue();
    }

    @Test
    @DisplayName("consumer pool threads run to its size at once, queue the rest and retire after the keep-alive")
    void consumerPoolThreadsRetireAfterTheKeepAlive() throws InterruptedException {
        int threads = 3;
        ThreadPoolExecutor pool = IdleThreadRetirementConfig.consumerExecutor(threads, SHORT_KEEP_ALIVE_MILLIS);
        try {
            CountDownLatch running = new CountDownLatch(threads + 1);
            CountDownLatch release = new CountDownLatch(1);
            List<String> names = new CopyOnWriteArrayList<>();
            for (int i = 0; i < threads + 1; i++) {
                pool.execute(() -> {
                    names.add(Thread.currentThread().getName());
                    running.countDown();
                    awaitQuietly(release);
                });
            }
            await().atMost(Duration.ofSeconds(10)).until(() -> running.getCount() == 1);
            assertThat(pool.getPoolSize()).isEqualTo(threads);
            assertThat(pool.getQueue()).as("the task beyond the pool size waits instead of being rejected")
                    .hasSize(1);

            release.countDown();
            assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
                    .until(() -> pool.getPoolSize() == 0);
            assertThat(names).hasSize(threads + 1)
                    .allSatisfy(name -> assertThat(name).startsWith("amqp-consumer-"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("amqp-client dispatches deliveries on the consumer pool, whose threads end when the context closes")
    void amqpClientDispatchesDeliveriesOnTheConsumerPool(@TempDir Path workDir) throws Exception {
        int port = unusedPort();
        SystemLauncher broker = startBroker(port, workDir);
        try {
            AtomicReference<ThreadPoolExecutor> pool = new AtomicReference<>();
            amqpRunner.withPropertyValues("spring.rabbitmq.host=" + LOOPBACK, "spring.rabbitmq.port=" + port,
                            "spring.rabbitmq.username=guest", "spring.rabbitmq.password=guest")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        CachingConnectionFactory connectionFactory = context.getBean(CachingConnectionFactory.class);
                        pool.set(consumerPool(connectionFactory));

                        String deliveryThread = deliveryThreadName(connectionFactory);

                        assertThat(deliveryThread).startsWith("amqp-consumer-");
                        assertThat(pool.get().getPoolSize()).isPositive();
                    });
            assertThat(pool.get().awaitTermination(10, TimeUnit.SECONDS)).as("pool threads ended").isTrue();
        } finally {
            broker.shutdown();
        }
    }

    /**
     * Publishes one message to a server-named queue through {@code connectionFactory} and returns the name of the
     * thread amqp-client delivered it on.
     */
    private static String deliveryThreadName(CachingConnectionFactory connectionFactory) throws Exception {
        AtomicReference<String> deliveryThread = new AtomicReference<>();
        CountDownLatch delivered = new CountDownLatch(1);
        try (Connection connection = connectionFactory.createConnection()) {
            Channel channel = connection.createChannel(false);
            String queue = channel.queueDeclare().getQueue();
            String consumerTag = channel.basicConsume(queue, true, new DefaultConsumer(channel) {
                @Override
                public void handleDelivery(String tag, Envelope envelope, AMQP.BasicProperties properties,
                                           byte[] body) {
                    deliveryThread.set(Thread.currentThread().getName());
                    delivered.countDown();
                }
            });
            channel.basicPublish("", queue, null, "test".getBytes(StandardCharsets.UTF_8));
            assertThat(delivered.await(10, TimeUnit.SECONDS)).as("message delivered").isTrue();
            channel.basicCancel(consumerTag);
            channel.close();
        }
        return deliveryThread.get();
    }

    /** Sends {@value #CONCURRENT_REQUESTS} requests to the rendezvous servlet at once and returns every response. */
    private static List<HttpTestClient.Response> sendConcurrently(int port) throws Exception {
        ExecutorService clients = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        try {
            List<Future<HttpTestClient.Response>> pending = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
                pending.add(clients.submit(() -> new HttpTestClient(LOOPBACK, port).get(RENDEZVOUS_PATH)));
            }
            List<HttpTestClient.Response> responses = new ArrayList<>();
            for (Future<HttpTestClient.Response> response : pending) {
                responses.add(response.get(30, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            clients.shutdownNow();
        }
    }

    /** The XNIO worker of the context's running Undertow. */
    private static XnioWorker worker(AssertableWebApplicationContext context) {
        Object webServer = context.getSourceApplicationContext(ServletWebServerApplicationContext.class)
                .getWebServer();
        Undertow undertow = (Undertow) ReflectionTestUtils.getField(webServer, "undertow");
        assertThat(undertow).as("started Undertow").isNotNull();
        return undertow.getWorker();
    }

    private static int port(AssertableWebApplicationContext context) {
        return context.getSourceApplicationContext(ServletWebServerApplicationContext.class).getWebServer().getPort();
    }

    /** The executor {@code connectionFactory} passes to amqp-client, required to be a {@link ThreadPoolExecutor}. */
    private static ThreadPoolExecutor consumerPool(Object connectionFactory) {
        Object executor = ReflectionTestUtils.getField(connectionFactory, "executorService");
        assertThat(executor).isInstanceOf(ThreadPoolExecutor.class);
        return (ThreadPoolExecutor) executor;
    }

    /** A loopback port nothing listened on a moment ago. */
    private static int unusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    /** Starts an in-memory Qpid Broker-J with {@code config.json} on {@code port}, its work files in {@code workDir}. */
    private static SystemLauncher startBroker(int port, Path workDir) throws Exception {
        Map<String, Object> brokerContext = new HashMap<>();
        brokerContext.put("qpid.amqp_port", String.valueOf(port));
        brokerContext.put("qpid.work_dir", workDir.toString());
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("type", "Memory");
        attributes.put("initialConfigurationLocation",
                IdleThreadRetirementConfigTest.class.getResource("/config.json").toExternalForm());
        attributes.put("startupLoggedToSystemOut", false);
        attributes.put("context", brokerContext);
        SystemLauncher launcher = new SystemLauncher();
        launcher.startup(attributes);
        return launcher;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Registers {@link RendezvousServlet} at {@value #RENDEZVOUS_PATH}. */
    @Configuration(proxyBeanMethods = false)
    static class RendezvousServletConfig {

        @Bean
        ServletRegistrationBean<RendezvousServlet> rendezvousServlet() {
            return new ServletRegistrationBean<>(new RendezvousServlet(new CyclicBarrier(CONCURRENT_REQUESTS)),
                    RENDEZVOUS_PATH);
        }
    }

    /** The Undertow worker retirement of {@link IdleThreadRetirementConfig} with a short keep-alive. */
    @Configuration(proxyBeanMethods = false)
    static class ShortKeepAliveWorkerConfig {

        @Bean
        WebServerFactoryCustomizer<UndertowServletWebServerFactory> shortKeepAliveWorkerRetirement() {
            return IdleThreadRetirementConfig.workerRetirementCustomizer(SHORT_KEEP_ALIVE_MILLIS);
        }
    }

    /**
     * Holds each {@code GET} until {@value #CONCURRENT_REQUESTS} requests are inside it at the same time, then answers
     * 200 with the name of the thread that ran it. Requests that never meet within
     * {@value #RENDEZVOUS_TIMEOUT_MILLIS} ms, because the server ran them one after another, get 503.
     */
    static final class RendezvousServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        private final transient CyclicBarrier rendezvous;

        RendezvousServlet(CyclicBarrier rendezvous) {
            this.rendezvous = rendezvous;
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            try {
                rendezvous.await(RENDEZVOUS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                response.sendError(503, "interrupted");
                return;
            } catch (BrokenBarrierException | TimeoutException e) {
                response.sendError(503, "the requests did not run at the same time");
                return;
            }
            response.setContentType("text/plain");
            response.getOutputStream().write(Thread.currentThread().getName().getBytes(StandardCharsets.UTF_8));
        }
    }
}
