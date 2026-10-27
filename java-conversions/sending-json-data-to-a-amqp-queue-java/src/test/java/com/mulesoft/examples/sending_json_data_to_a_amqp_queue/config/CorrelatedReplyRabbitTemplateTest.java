/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_json_data_to_a_amqp_queue.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import org.apache.qpid.server.SystemLauncher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpMessageReturnedException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.PublisherCallbackChannel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import com.mulesoft.examples.sending_json_data_to_a_amqp_queue.support.HttpTestClient;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.ShutdownSignalException;

/**
 * Regression tests of {@link CorrelatedReplyRabbitTemplate} on a real AMQP 0-9-1 broker, an embedded Qpid Broker-J
 * 9.2.1 started from {@code src/test/resources/config.json}. The application runs on a random port, and
 * {@code POST /} is driven through ST-1's {@link HttpTestClient}, so each case goes through the controller, the
 * service and the template exactly as a caller's request does.
 *
 * <p>A responder on a separate client connection plays both the legitimate service and an attacker: it takes the
 * request off {@code sales_queue} and publishes forged messages to the request's {@code reply_to} through the
 * default exchange, as any client allowed to publish on the broker can. The reply timeout is
 * {@value #REPLY_TIMEOUT_MILLIS} ms here, inside ST-1's 10 s read timeout. The remaining cases call a template of
 * their own directly, for the parts of the exchange {@code POST /} cannot reach: the converter and after-receive
 * post-processors, rejected request properties, an abnormal channel shutdown and the broker's return of a mandatory
 * request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // Pins the listener to the address ST-1 connects to, whatever server.address the project sets.
        "server.address=" + CorrelatedReplyRabbitTemplateTest.LOOPBACK,
        "amqp.response-timeout=" + CorrelatedReplyRabbitTemplateTest.REPLY_TIMEOUT_MILLIS,
        // The embedded broker's operational log (connections, channels, declarations) at INFO is noise here.
        "logging.level.qpid.message=WARN"})
@ActiveProfiles("test")
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("CorrelatedReplyRabbitTemplate on an embedded broker")
class CorrelatedReplyRabbitTemplateTest {

    /** IPv4 loopback literal: {@code localhost} may resolve to {@code ::1}, where nothing listens. */
    static final String LOOPBACK = "127.0.0.1";

    /** The application's reply timeout in this class, in milliseconds. */
    static final long REPLY_TIMEOUT_MILLIS = 3000L;

    /** How long after the forged message the responder publishes the correlated reply, in milliseconds. */
    private static final long CORRELATED_REPLY_DELAY_MILLIS = 1000L;

    /** How long the responder waits for a request to reach {@code sales_queue}, in seconds. */
    private static final long REQUEST_WAIT_SECONDS = 10L;

    private static final String SALES_EXCHANGE = "sales_exchange";

    private static final String SALES_QUEUE = "sales_queue";

    private static final String CORRELATED_BODY = "CORRELATED-REPLY";

    private static final String FORGED_BODY_PREFIX = "FORGED-REPLY-";

    @TempDir
    static Path qpidWorkDir;

    private static SystemLauncher broker;

    private static int amqpPort;

    /** Boot's connection factory, reset before the broker stops so that its connection closes normally. */
    private static CachingConnectionFactory applicationConnectionFactory;

    @LocalServerPort
    int httpPort;

    @Autowired
    CachingConnectionFactory connectionFactory;

    @Value("${spring.rabbitmq.username}")
    String brokerUsername;

    @Value("${spring.rabbitmq.password}")
    String brokerPassword;

    private final BlockingQueue<Request> requests = new LinkedBlockingQueue<>();

    private final List<CachingConnectionFactory> dedicatedFactories = new ArrayList<>();

    private Connection clientConnection;

    private Channel replyChannel;

    private ExecutorService responderExecutor;

    @BeforeAll
    static void startBroker() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            amqpPort = socket.getLocalPort();
        }
        URL configuration = Objects.requireNonNull(CorrelatedReplyRabbitTemplateTest.class.getResource("/config.json"),
                "src/test/resources/config.json is not on the test classpath");
        Map<String, Object> context = new HashMap<>();
        context.put("qpid.amqp_port", String.valueOf(amqpPort));
        context.put("qpid.work_dir", qpidWorkDir.toString());
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("type", "Memory");
        attributes.put("initialConfigurationLocation", configuration.toExternalForm());
        attributes.put("startupLoggedToSystemOut", false);
        attributes.put("context", context);
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
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> amqpPort);
        registry.add("amqp.host", () -> "localhost");
    }

    /**
     * Declares what the original README has the user create, a direct {@code sales_exchange} and a
     * {@code sales_queue} bound with the empty routing key, empties the queue, and starts the responder's consumer.
     */
    @BeforeEach
    void connectResponder() throws Exception {
        applicationConnectionFactory = connectionFactory;
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        factory.setPort(amqpPort);
        factory.setUsername(brokerUsername);
        factory.setPassword(brokerPassword);
        clientConnection = factory.newConnection("correlation-test-responder");
        Channel declarations = clientConnection.createChannel();
        declarations.exchangeDeclare(SALES_EXCHANGE, BuiltinExchangeType.DIRECT);
        declarations.queueDeclare(SALES_QUEUE, false, false, false, null);
        declarations.queueBind(SALES_QUEUE, SALES_EXCHANGE, "");
        declarations.queuePurge(SALES_QUEUE);
        declarations.close();
        Channel requestChannel = clientConnection.createChannel();
        requestChannel.basicConsume(SALES_QUEUE, true,
                (consumerTag, delivery) -> requests.add(new Request(delivery.getProperties(), delivery.getBody())),
                consumerTag -> { });
        replyChannel = clientConnection.createChannel();
        responderExecutor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void disconnectResponder() throws IOException {
        if (responderExecutor != null) {
            responderExecutor.shutdownNow();
        }
        dedicatedFactories.forEach(CachingConnectionFactory::destroy);
        if (clientConnection != null) {
            clientConnection.close();
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Forgery.class)
    @DisplayName("a forged message published to reply_to first is discarded, and the correlated reply is returned")
    void forgedMessageBeforeTheCorrelatedReplyIsNeverReturned(Forgery forgery) throws Exception {
        CompletableFuture<Responded> responder =
                answerNextRequest(Reply.forged(forgery), Reply.correlated(CORRELATED_REPLY_DELAY_MILLIS));

        long start = System.nanoTime();
        HttpTestClient.Response response = post("{\"ITEM_ID\": \"" + forgery + "\"}");
        long elapsedMillis = millisSince(start);

        responder.get(REQUEST_WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.bodyAsString()).isEqualTo(CORRELATED_BODY).doesNotContain(FORGED_BODY_PREFIX);
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(CORRELATED_REPLY_DELAY_MILLIS);
    }

    @Test
    @DisplayName("with forged messages only, each is discarded with one WARN line naming only the reply queue, and"
            + " the caller gets the empty no-reply 200 once the reply timeout has passed")
    void forgedMessagesAloneEndInTheNoReplyResponse(CapturedOutput output) throws Exception {
        Reply[] forgeries = new Reply[Forgery.values().length];
        for (Forgery forgery : Forgery.values()) {
            forgeries[forgery.ordinal()] = Reply.forged(forgery);
        }
        CompletableFuture<Responded> responder = answerNextRequest(forgeries);

        long start = System.nanoTime();
        HttpTestClient.Response response = post("{\"ITEM_ID\": \"forged-only\"}");
        long elapsedMillis = millisSince(start);

        Responded responded = responder.get(REQUEST_WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEmpty();
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(REPLY_TIMEOUT_MILLIS);

        String warning = "Discarded a message on temporary reply queue " + responded.request().properties().getReplyTo()
                + ": its correlation id does not match the request's";
        assertThat(output.getOut().split(Pattern.quote(warning), -1)).hasSize(forgeries.length + 1);
        assertThat(output.getOut()).doesNotContain(FORGED_BODY_PREFIX);
        for (String forgedCorrelationId : responded.publishedCorrelationIds()) {
            if (forgedCorrelationId != null && !forgedCorrelationId.isEmpty()) {
                assertThat(output.getOut()).doesNotContain(forgedCorrelationId);
            }
        }
    }

    @Test
    @DisplayName("every request carries a random UUID correlation id of its own and otherwise the original's"
            + " properties")
    void everyRequestCarriesItsOwnRandomCorrelationId() throws Exception {
        List<Request> received = new ArrayList<>();
        for (String body : List.of("{\"ITEM_ID\": 1}", "{\"ITEM_ID\": 2}")) {
            CompletableFuture<Responded> responder = answerNextRequest(Reply.correlated(0L));

            HttpTestClient.Response response = post(body);

            Request request = responder.get(REQUEST_WAIT_SECONDS, TimeUnit.SECONDS).request();
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.bodyAsString()).isEqualTo(CORRELATED_BODY);
            assertThat(new String(request.body(), StandardCharsets.UTF_8)).isEqualTo(body);
            received.add(request);
        }

        for (Request request : received) {
            AMQP.BasicProperties properties = request.properties();
            String correlationId = properties.getCorrelationId();
            assertThat(correlationId).isNotNull();
            UUID parsed = UUID.fromString(correlationId);
            assertThat(parsed.toString()).isEqualTo(correlationId);
            assertThat(parsed.version()).isEqualTo(4);
            assertThat(properties.getReplyTo()).isNotBlank();
            assertThat(properties.getDeliveryMode()).isEqualTo(2);
            assertThat(properties.getContentType()).isNull();
            assertThat(properties.getPriority()).isNull();
        }
        assertThat(received.get(0).properties().getCorrelationId())
                .isNotEqualTo(received.get(1).properties().getCorrelationId());
        assertThat(received.get(0).properties().getReplyTo()).isNotEqualTo(received.get(1).properties().getReplyTo());
    }

    @Test
    @DisplayName("the correlated reply goes through the template's properties converter and after-receive"
            + " post-processors")
    void correlatedReplyGoesThroughTheConverterAndThePostProcessors() throws Exception {
        CorrelatedReplyRabbitTemplate template = dedicatedTemplate(false, REPLY_TIMEOUT_MILLIS);
        template.setAfterReceivePostProcessors(message -> {
            message.getMessageProperties().setHeader("x-post-processed", "yes");
            return message;
        });
        CompletableFuture<Responded> responder =
                answerNextRequest(Reply.forged(Forgery.MISMATCHED_ID), Reply.correlated(0L));

        Message reply = template.sendAndReceive(SALES_EXCHANGE, "", MessageBuilder.withBody(bytes("direct")).build());

        Request request = responder.get(REQUEST_WAIT_SECONDS, TimeUnit.SECONDS).request();
        assertThat(reply).isNotNull();
        assertThat(new String(reply.getBody(), StandardCharsets.UTF_8)).isEqualTo(CORRELATED_BODY);
        MessageProperties properties = reply.getMessageProperties();
        assertThat(properties.getCorrelationId()).isEqualTo(request.properties().getCorrelationId());
        assertThat(properties.getReceivedRoutingKey()).isEqualTo(request.properties().getReplyTo());
        assertThat((String) properties.getHeader("x-post-processed")).isEqualTo("yes");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(PresetProperty.class)
    @DisplayName("a request that already carries a replyTo or a correlationId is rejected and nothing is published")
    void requestWithAPresetReplyPropertyIsRejected(PresetProperty preset) throws InterruptedException {
        CorrelatedReplyRabbitTemplate template = dedicatedTemplate(false, REPLY_TIMEOUT_MILLIS);
        Message request = MessageBuilder.withBody(bytes("preset")).build();
        preset.setter.accept(request.getMessageProperties());

        assertThatExceptionOfType(AmqpException.class)
                .isThrownBy(() -> template.sendAndReceive(SALES_EXCHANGE, "", request))
                .withRootCauseInstanceOf(IllegalArgumentException.class);

        assertThat(requests.poll(500L, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @DisplayName("an abnormal channel shutdown during the wait fails the call at once")
    void abnormalChannelShutdownFailsTheWait() {
        CorrelatedReplyRabbitTemplate template = dedicatedTemplate(false, 10_000L);
        Message request = MessageBuilder.withBody(bytes("no exchange")).build();

        long start = System.nanoTime();
        assertThatExceptionOfType(AmqpException.class)
                .isThrownBy(() -> template.sendAndReceive("no_such_exchange", "", request))
                .withRootCauseInstanceOf(ShutdownSignalException.class);

        assertThat(millisSince(start)).isLessThan(5_000L);
    }

    @Test
    @DisplayName("an interrupted wait fails the call and leaves the caller's interrupt flag set")
    void interruptedWaitKeepsTheInterruptFlag() throws Exception {
        CorrelatedReplyRabbitTemplate template = dedicatedTemplate(false, 10_000L);
        CompletableFuture<Responded> responder = answerNextRequest();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptedAfterwards = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                template.sendAndReceive(SALES_EXCHANGE, "", MessageBuilder.withBody(bytes("interrupted")).build());
            } catch (RuntimeException expected) {
                failure.set(expected);
            }
            interruptedAfterwards.set(Thread.currentThread().isInterrupted());
        }, "interrupted-caller");
        caller.start();

        // The request has reached sales_queue, so the caller has published it and is waiting for the reply.
        responder.get(REQUEST_WAIT_SECONDS, TimeUnit.SECONDS);
        caller.interrupt();
        caller.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(caller.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOf(AmqpException.class).hasRootCauseInstanceOf(InterruptedException.class);
        assertThat(interruptedAfterwards).isTrue();
    }

    @Test
    @DisplayName("a mandatory request the broker returns fails the wait with AmqpMessageReturnedException")
    void returnedMandatoryRequestFailsTheWait() {
        CorrelatedReplyRabbitTemplate template = dedicatedTemplate(true, 10_000L);
        template.setMandatory(true);
        Message request = MessageBuilder.withBody(bytes("unroutable")).build();

        long start = System.nanoTime();
        assertThatExceptionOfType(AmqpMessageReturnedException.class)
                .isThrownBy(() -> template.sendAndReceive(SALES_EXCHANGE, "unrouted", request))
                .satisfies(failure -> {
                    ReturnedMessage returned = failure.getReturned();
                    assertThat(returned.getReplyCode()).isEqualTo(AMQP.NO_ROUTE);
                    assertThat(returned.getExchange()).isEqualTo(SALES_EXCHANGE);
                    assertThat(returned.getRoutingKey()).isEqualTo("unrouted");
                    assertThat(new String(returned.getMessage().getBody(), StandardCharsets.UTF_8))
                            .isEqualTo("unroutable");
                    assertThat(returned.getMessage().getMessageProperties().getHeaders()).doesNotContainKeys(
                            CorrelatedReplyRabbitTemplate.RETURN_CORRELATION_HEADER,
                            PublisherCallbackChannel.RETURN_LISTENER_CORRELATION_KEY);
                });

        assertThat(millisSince(start)).isLessThan(5_000L);
    }

    @Test
    @DisplayName("with a returns callback, a returned mandatory request goes to the callback and the caller times out")
    void returnsCallbackReceivesTheReturnedRequest() {
        CorrelatedReplyRabbitTemplate template = dedicatedTemplate(true, 500L);
        template.setMandatory(true);
        AtomicReference<ReturnedMessage> returned = new AtomicReference<>();
        template.setReturnsCallback(returned::set);

        Message reply = template.sendAndReceive(SALES_EXCHANGE, "unrouted",
                MessageBuilder.withBody(bytes("unroutable")).build());

        assertThat(reply).isNull();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(returned.get()).isNotNull());
        assertThat(returned.get().getReplyCode()).isEqualTo(AMQP.NO_ROUTE);
        assertThat(returned.get().getMessage().getMessageProperties().getHeaders())
                .doesNotContainKey(CorrelatedReplyRabbitTemplate.RETURN_CORRELATION_HEADER);
    }

    @Test
    @DisplayName("the reply timeout and the no-local flag start at RabbitTemplate's defaults and follow its setters")
    void replySettingsFollowRabbitTemplate() {
        CorrelatedReplyRabbitTemplate template = new CorrelatedReplyRabbitTemplate();
        assertThat(ReflectionTestUtils.getField(template, "replyTimeoutMillis"))
                .isEqualTo(ReflectionTestUtils.getField(template, "replyTimeout"))
                .isEqualTo(CorrelatedReplyRabbitTemplate.DEFAULT_REPLY_TIMEOUT_MILLIS);
        assertThat(ReflectionTestUtils.getField(template, "replyConsumerNoLocal"))
                .isEqualTo(ReflectionTestUtils.getField(template, "noLocalReplyConsumer"))
                .isEqualTo(false);

        template.setReplyTimeout(1234L);
        template.setNoLocalReplyConsumer(true);

        assertThat(ReflectionTestUtils.getField(template, "replyTimeoutMillis"))
                .isEqualTo(ReflectionTestUtils.getField(template, "replyTimeout"))
                .isEqualTo(1234L);
        assertThat(ReflectionTestUtils.getField(template, "replyConsumerNoLocal"))
                .isEqualTo(ReflectionTestUtils.getField(template, "noLocalReplyConsumer"))
                .isEqualTo(true);
    }

    /**
     * Takes the next request off {@code sales_queue} on the responder's thread and publishes the given messages to
     * its {@code reply_to} through the default exchange, in order, each after its delay.
     *
     * @param replies the messages to publish
     * @return completes with the request and the correlation ids published, or exceptionally when no request
     *         arrived in time or a publish failed
     */
    private CompletableFuture<Responded> answerNextRequest(Reply... replies) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Request request = requests.poll(REQUEST_WAIT_SECONDS, TimeUnit.SECONDS);
                if (request == null) {
                    throw new IllegalStateException("No request reached " + SALES_QUEUE + " within "
                            + REQUEST_WAIT_SECONDS + " s");
                }
                List<String> publishedCorrelationIds = new ArrayList<>();
                for (Reply reply : replies) {
                    Thread.sleep(reply.delayMillis());
                    String correlationId = reply.correlationIdOf().apply(request.properties().getCorrelationId());
                    publishedCorrelationIds.add(correlationId);
                    AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                            .correlationId(correlationId)
                            .build();
                    replyChannel.basicPublish("", request.properties().getReplyTo(), properties,
                            bytes(reply.body()));
                }
                return new Responded(request, publishedCorrelationIds);
            } catch (IOException publishFailed) {
                throw new UncheckedIOException(publishFailed);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("The responder was interrupted", interrupted);
            }
        }, responderExecutor);
    }

    /**
     * A template of its own on a connection factory of its own, destroyed after the test.
     *
     * @param publisherReturns   whether the factory's channels deliver the broker's returns to the template
     * @param replyTimeoutMillis the template's reply timeout
     * @return the template, with Spring AMQP's defaults otherwise
     */
    private CorrelatedReplyRabbitTemplate dedicatedTemplate(boolean publisherReturns, long replyTimeoutMillis) {
        CachingConnectionFactory factory = new CachingConnectionFactory("localhost", amqpPort);
        factory.setUsername(brokerUsername);
        factory.setPassword(brokerPassword);
        factory.setPublisherReturns(publisherReturns);
        dedicatedFactories.add(factory);
        CorrelatedReplyRabbitTemplate template = new CorrelatedReplyRabbitTemplate(factory);
        template.setReplyTimeout(replyTimeoutMillis);
        return template;
    }

    private HttpTestClient.Response post(String body) {
        return new HttpTestClient(LOOPBACK, httpPort).post("/", "application/json", body);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static long millisSince(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /**
     * How a forged message's correlation id is derived from the request's, each a near miss or none at all. A
     * request without a correlation id, which only a template that sets none would send, is read as {@code "null"},
     * so that such a template fails on the returned forged body rather than inside the responder.
     */
    enum Forgery {
        MISMATCHED_ID(requestId -> UUID.randomUUID().toString()),
        ABSENT_ID(requestId -> null),
        EMPTY_ID(requestId -> ""),
        EXTENDED_ID(requestId -> requestId + "0"),
        TRUNCATED_ID(requestId -> String.valueOf(requestId).substring(0, String.valueOf(requestId).length() - 1));

        private final UnaryOperator<String> correlationIdOf;

        Forgery(UnaryOperator<String> correlationIdOf) {
            this.correlationIdOf = correlationIdOf;
        }
    }

    /** A reply property the template sets itself, preset on the request. */
    enum PresetProperty {
        REPLY_TO(properties -> properties.setReplyTo("preset-reply-queue")),
        CORRELATION_ID(properties -> properties.setCorrelationId("preset-correlation-id"));

        private final Consumer<MessageProperties> setter;

        PresetProperty(Consumer<MessageProperties> setter) {
            this.setter = setter;
        }
    }

    /**
     * One message the responder publishes to the request's {@code reply_to}.
     *
     * @param delayMillis     the pause before it is published, in milliseconds
     * @param correlationIdOf derives its correlation id, or {@code null} for none, from the request's
     * @param body            its body
     */
    private record Reply(long delayMillis, UnaryOperator<String> correlationIdOf, String body) {

        static Reply correlated(long delayMillis) {
            return new Reply(delayMillis, UnaryOperator.identity(), CORRELATED_BODY);
        }

        static Reply forged(Forgery forgery) {
            return new Reply(0L, forgery.correlationIdOf, FORGED_BODY_PREFIX + forgery.name());
        }
    }

    /**
     * A request as the responder took it off {@code sales_queue}.
     *
     * @param properties its AMQP properties
     * @param body       its body
     */
    private record Request(AMQP.BasicProperties properties, byte[] body) {
    }

    /**
     * What the responder did for one request.
     *
     * @param request                 the request it answered
     * @param publishedCorrelationIds the correlation id of every message it published, in order
     */
    private record Responded(Request request, List<String> publishedCorrelationIds) {
    }
}
