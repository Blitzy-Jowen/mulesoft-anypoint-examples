/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_json_data_to_a_amqp_queue;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.qpid.server.SystemLauncher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.mulesoft.examples.sending_json_data_to_a_amqp_queue.support.HttpTestClient;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.ShutdownSignalException;

/**
 * JUnit 5 port of the original {@code SendingJsonDataToAmqpQueueIT}: {@code POST /} carrying {@code test} reaches
 * {@code sales_queue} through {@code sales_exchange}, and the queued body equals the sent body.
 *
 * <p>The original embedded Qpid broker is replaced by an in-memory Qpid Broker-J 9.2.1 started from
 * {@code src/test/resources/config.json} on a free port, its work files in a temporary directory. Before each test
 * a client connection declares what the original test declared and the original README has the user create: the
 * topic exchange {@code sales_exchange}, the durable queue {@code sales_queue} and their {@code #} binding, which
 * also matches the application's empty routing key. The application itself only checks both passively.
 *
 * <p>Two cases go beyond the original's single assertion, because its client only dispatched the request and never
 * read the response: a responder that answers on the request's {@code reply_to}, and a missing exchange, which the
 * application must report and must not create.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // Pins the listener to the address the HTTP clients connect to, whatever server.address the project sets.
        "server.address=" + SendingJsonDataToAmqpQueueTest.LOOPBACK,
        // The embedded broker's operational log (connections, channels, declarations) at INFO is noise here.
        "logging.level.qpid.message=WARN"})
@ActiveProfiles("test")
// The context's connection factory points at a broker that is shut down after this class.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SendingJsonDataToAmqpQueueTest {

    /** IPv4 loopback literal: {@code localhost} may resolve to {@code ::1}, where nothing listens. */
    static final String LOOPBACK = "127.0.0.1";

    private static final Logger log = LoggerFactory.getLogger(SendingJsonDataToAmqpQueueTest.class);

    private static final String EXCHANGE_NAME = "sales_exchange";

    private static final String QUEUE_NAME = "sales_queue";

    private static final String REPLY_OK = "test";

    /** The body the responder of {@link #replyIsReturned()} answers with. */
    private static final String REPLY_BODY = "reply";

    /** The application's 10000 ms {@code amqp.response-timeout}, less a margin for timer granularity. */
    private static final long NO_REPLY_MIN_MILLIS = 9_500L;

    /** Well under the reply timeout: an answered request must not wait for it. */
    private static final long REPLY_MAX_MILLIS = 9_000L;

    /** How long the request that gets no reply may take in all, comfortably above the 10 s reply wait. */
    private static final Duration NO_REPLY_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** The embedded broker's AMQP port, chosen before the application context is built from it. */
    private static final int AMQP_PORT;

    static {
        try (ServerSocket socket = new ServerSocket(0)) {
            AMQP_PORT = socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("No free port for the embedded AMQP broker", e);
        }
    }

    @TempDir
    static Path qpidWorkDir;

    private static SystemLauncher broker;

    /** The application's connection factory, reset before the broker stops so that its connection closes normally. */
    private static CachingConnectionFactory applicationConnectionFactory;

    @LocalServerPort
    int httpPort;

    @Autowired
    CachingConnectionFactory connectionFactory;

    @Value("${spring.rabbitmq.username}")
    String brokerUsername;

    @Value("${spring.rabbitmq.password}")
    String brokerPassword;

    private Connection connection;

    private Channel channel;

    @BeforeAll
    static void startBroker() throws Exception {
        URL configuration = SendingJsonDataToAmqpQueueTest.class.getResource("/config.json");
        assertNotNull(configuration, "src/test/resources/config.json is not on the test classpath");
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("type", "Memory");
        attributes.put("initialConfigurationLocation", configuration.toExternalForm());
        attributes.put("startupLoggedToSystemOut", false);
        attributes.put("context", Map.of(
                "qpid.amqp_port", String.valueOf(AMQP_PORT),
                "qpid.work_dir", qpidWorkDir.toString()));
        // The no-argument launcher's listener does nothing, so Spring Boot's logging setup stays untouched.
        broker = new SystemLauncher();
        broker.startup(attributes);
    }

    @AfterAll
    static void stopBroker() {
        if (applicationConnectionFactory != null) {
            applicationConnectionFactory.resetConnection();
        }
        if (broker != null) {
            broker.shutdown();
        }
    }

    @DynamicPropertySource
    static void brokerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.port", () -> AMQP_PORT);
        registry.add("amqp.host", () -> LOOPBACK);
        registry.add("spring.rabbitmq.host", () -> LOOPBACK);
    }

    @BeforeEach
    void declareSalesDestination() throws Exception {
        applicationConnectionFactory = connectionFactory;
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(LOOPBACK);
        factory.setPort(AMQP_PORT);
        factory.setUsername(brokerUsername);
        factory.setPassword(brokerPassword);
        factory.setVirtualHost("/");
        connection = factory.newConnection("sending-json-data-to-a-amqp-queue-test");
        channel = connection.createChannel();
        channel.exchangeDeclare(EXCHANGE_NAME, "topic");
        channel.queueDeclare(QUEUE_NAME, true, false, false, null);
        channel.queueBind(QUEUE_NAME, EXCHANGE_NAME, "#");
    }

    @AfterEach
    void deleteSalesDestination() throws Exception {
        if (connection == null) {
            return;
        }
        // Each deletion gets a channel of its own: Broker-J closes the channel with 404 NOT_FOUND when the target
        // is already gone, as the exchange is after missingExchangeIsNotCreated.
        onFreshChannel("delete exchange " + EXCHANGE_NAME, fresh -> fresh.exchangeDelete(EXCHANGE_NAME));
        onFreshChannel("delete queue " + QUEUE_NAME, fresh -> fresh.queueDelete(QUEUE_NAME));
        closeQuietly(channel);
        if (connection.isOpen()) {
            connection.close();
        }
    }

    @Test
    @DisplayName("POST / puts the sent body on sales_queue and, with no reply, answers 200 with an empty body after"
            + " the 10 s reply timeout")
    void testFlow() throws Exception {
        AtomicReference<String> messageBody = new AtomicReference<>();
        // ST-1's HttpTestClient reads with a 10 s socket timeout, and this request is answered only after the
        // flow's 10 s reply wait, so this one request goes through java.net.http.HttpClient. It asserts no reason
        // phrase, the one thing only ST-1 can read.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(HttpTestClient.TIMEOUT_MILLIS))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + LOOPBACK + ":" + httpPort + "/"))
                .timeout(NO_REPLY_REQUEST_TIMEOUT)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(REPLY_OK, StandardCharsets.UTF_8))
                .build();

        long start = System.nanoTime();
        CompletableFuture<HttpResponse<byte[]>> pending =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());

        // No reply is published, so the application waits for its reply timeout.
        channel.basicConsume(QUEUE_NAME, false, "myConsumerTag", new DefaultConsumer(channel) {
            @Override
            public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties,
                    byte[] body) {
                messageBody.set(new String(body, StandardCharsets.UTF_8));
                log.info("Received from {}: {}", QUEUE_NAME, messageBody.get());
            }
        });

        await().atMost(Duration.ofSeconds(15)).until(() -> messageBody.get() != null);
        assertNotNull(messageBody.get());
        assertEquals(REPLY_OK, messageBody.get());

        HttpResponse<byte[]> response = pending.get(NO_REPLY_REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        long elapsedMillis = millisSince(start);
        assertEquals(200, response.statusCode());
        assertEquals(0, response.body().length,
                () -> "expected an empty body, got: " + new String(response.body(), StandardCharsets.UTF_8));
        assertTrue(elapsedMillis >= NO_REPLY_MIN_MILLIS,
                () -> "answered after " + elapsedMillis + " ms, before the 10000 ms reply timeout");
    }

    @Test
    @DisplayName("POST / answers 200 with the reply a responder publishes to the request's reply_to")
    void replyIsReturned() throws Exception {
        AtomicReference<String> replyTo = new AtomicReference<>();
        channel.basicConsume(QUEUE_NAME, false, "replyingConsumer", new DefaultConsumer(channel) {
            @Override
            public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties,
                    byte[] body) throws IOException {
                replyTo.set(properties.getReplyTo());
                // The application returns only a reply that carries its request's correlation id.
                AMQP.BasicProperties replyProperties = new AMQP.BasicProperties.Builder()
                        .correlationId(properties.getCorrelationId())
                        .build();
                getChannel().basicPublish("", properties.getReplyTo(), replyProperties,
                        REPLY_BODY.getBytes(StandardCharsets.UTF_8));
                getChannel().basicAck(envelope.getDeliveryTag(), false);
                log.info("Answered the request on {} through reply_to {}", QUEUE_NAME, properties.getReplyTo());
            }
        });

        long start = System.nanoTime();
        HttpTestClient.Response response = httpClient().post("/", "text/plain", REPLY_OK);
        long elapsedMillis = millisSince(start);

        assertEquals(200, response.status(), response::toString);
        assertEquals(REPLY_BODY, response.bodyAsString());
        assertTrue(elapsedMillis < REPLY_MAX_MILLIS,
                () -> "answered after " + elapsedMillis + " ms, as if no reply had arrived");
        assertNotNull(replyTo.get(), "the request reached sales_queue without a reply_to");
    }

    @Test
    @DisplayName("POST / with sales_exchange missing answers the JSON 500 and leaves the exchange uncreated")
    void missingExchangeIsNotCreated() throws Exception {
        Channel deleting = connection.createChannel();
        try {
            deleting.exchangeDelete(EXCHANGE_NAME);
        } finally {
            closeQuietly(deleting);
        }

        HttpTestClient.Response response = httpClient().post("/", "text/plain", REPLY_OK);

        assertEquals(500, response.status(), response::toString);
        String contentType = response.header("Content-Type");
        assertNotNull(contentType, response::toString);
        assertTrue(MediaType.parseMediaType(contentType).isCompatibleWith(MediaType.APPLICATION_JSON),
                () -> "Content-Type " + contentType + " is not JSON");
        assertTrue(response.bodyAsString().startsWith("{ \"message\": "), response::toString);

        Channel checking = connection.createChannel();
        try {
            assertThrows(IOException.class, () -> checking.exchangeDeclarePassive(EXCHANGE_NAME),
                    "the application declared " + EXCHANGE_NAME + " instead of only checking it");
        } finally {
            closeQuietly(checking);
        }
    }

    private HttpTestClient httpClient() {
        return new HttpTestClient(LOOPBACK, httpPort);
    }

    /**
     * Runs {@code operation} on a channel of its own and ignores its failure, which this class expects only when the
     * target no longer exists.
     */
    private void onFreshChannel(String description, ChannelOperation operation) {
        Channel fresh = null;
        try {
            fresh = connection.createChannel();
            operation.apply(fresh);
        } catch (IOException | ShutdownSignalException e) {
            log.debug("Ignored failure to {}: {}", description, e.toString());
        } finally {
            closeQuietly(fresh);
        }
    }

    /** Closes {@code target} unless it is absent or the broker already closed it. */
    private static void closeQuietly(Channel target) {
        if (target == null || !target.isOpen()) {
            return;
        }
        try {
            target.close();
        } catch (IOException | TimeoutException | ShutdownSignalException e) {
            log.debug("Ignored failure to close channel {}: {}", target.getChannelNumber(), e.toString());
        }
    }

    private static long millisSince(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /** One broker operation on a channel. */
    @FunctionalInterface
    private interface ChannelOperation {

        void apply(Channel channel) throws IOException;
    }
}
