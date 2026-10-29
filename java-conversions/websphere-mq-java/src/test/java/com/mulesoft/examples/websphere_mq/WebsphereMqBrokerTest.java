/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.websphere_mq;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.JMSSecurityException;
import jakarta.jms.Session;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.config.SimpleJmsListenerEndpoint;
import org.springframework.jms.connection.UserCredentialsConnectionFactoryAdapter;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.AbstractMessageListenerContainer;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.filter.FormContentFilter;

import com.mulesoft.examples.websphere_mq.client.MqQueueSender;
import com.mulesoft.examples.websphere_mq.config.ContentSecurityPolicyFilter;
import com.mulesoft.examples.websphere_mq.config.IbmMqConfig;
import com.mulesoft.examples.websphere_mq.support.HttpTestClient;

/**
 * End-to-end test of the three flows of {@code websphere-mq/src/main/app/websphere-mq.xml} on an embedded Artemis
 * broker standing in for the IBM MQ queue manager, driven over the wire as the original page
 * {@code docroot/index.html} drives the Mule Ajax transport.
 *
 * <ul>
 *   <li>{@link #ajaxEnqueueIsProcessedAndDeliveredOnDequeueWithoutRpcReply(String)} plays the page: Bayeux handshake,
 *       subscription to {@code /services/wmqExample/dequeue} (index.html:25), then {@code mule.rpc} on
 *       {@code /services/wmqExample/enqueue} without a callback (index.html:55), which per the copied
 *       {@code mule-resource/js/_mule.js} subscribes to {@code <channel>#<clientId>} and publishes
 *       {@code {data, replyTo}}. The text crosses flows {@code Input} (Ajax → queue {@code in}),
 *       {@code MessageProcessor} (queue {@code in} → append {@code " - processed"} → queue {@code out}) and
 *       {@code Output} (queue {@code out} → dequeue channel), and must arrive exactly once as the delivery's
 *       {@code data}, which the page shows as {@code message.data} (index.html:37-47). An empty or whitespace-only
 *       text is processed like any other, as the original flow processes every payload ({@code ""} arrives as
 *       {@code " - processed"}), rather than being rejected on queue {@code in}. Nothing may arrive on the
 *       {@code replyTo} channel: the connector's {@code disableReplyTo="true"} (websphere-mq.xml:7), carried as
 *       {@code ajax.disable-reply-to: true}, and flow {@code Input}'s deletion of {@code MULE_REPLYTO}
 *       (websphere-mq.xml:10-12) leave the rpc unanswered.</li>
 *   <li>{@link #failureAfterTheOutSendRollsBackTheSendAndRedeliversTheInMessage()} plays the same page exchange
 *       with a failure injected after the first real send to queue {@code out}, and proves on the embedded broker's
 *       queue counters that flow {@code MessageProcessor} is one transaction: queue {@code in} ({@code ALWAYS_BEGIN})
 *       and the send to queue {@code out} ({@code JOIN_IF_POSSIBLE}, websphere-mq.xml:16-22). The first output is
 *       rolled back together with the receive, so the input is redelivered rather than sent again, exactly one
 *       consume from {@code in} and one message on {@code out} commit, and the processed text reaches the dequeue
 *       channel once, with nothing on {@code replyTo}.</li>
 *   <li>{@link #inQueueListenerRunsOnTheSessionTransactedContainerTheTemplateJoins()} checks the wiring that
 *       transaction rests on in the running application: the {@code in} listener container is session-transacted
 *       with four consumers, exposes its session to the listener, has no transaction manager and shares its
 *       {@code ConnectionFactory} instance with the session-transacted {@code jmsTemplate}; the {@code out}
 *       container acknowledges on receipt without a transaction; and the
 *       {@link IbmMqConfig#TRANSACTED_LISTENER_FACTORY} bean builds every container that way.</li>
 *   <li>The docroot page, the Mule Ajax JavaScript and the listener's no-listener 404 are fetched from the same
 *       running application. Every response that yields the page ({@code /services/wmqExample/} forwarded to its
 *       welcome file, {@code index.html} by name, {@code HEAD}) carries {@link ContentSecurityPolicyFilter#POLICY}
 *       exactly once, the page bytes unchanged; the script, the 302 of {@code /services/wmqExample} and the 404
 *       carry no policy.</li>
 *   <li>The SC-12 endpoint's hostile-input limits are checked on the same running Undertow server: a form body of
 *       300000 bytes is refused with 413 and {@code []}, whether it declares its length or is sent chunked; Undertow
 *       drains the rest of the chunked body refused part-way, so the client receives the whole 413 and its kept-alive
 *       connection then carries a handshake, which is answered, as the round-trip test's handshake is; a form of
 *       300000 declared bytes sent with {@code PUT}, {@code PATCH} or {@code DELETE} gets SC-1's whole 405 while
 *       only its first bytes have been written, because the context holds no {@link FormContentFilter} that would
 *       read the body before dispatch; and a client publication on the dequeue channel, which only flow
 *       {@code Output} publishes on, is denied in-band with {@code 403::Publish denied} and reaches no
 *       subscriber.</li>
 *   <li>The IBM MQ factory configuration, which the embedded broker replaces at run time, is verified without a
 *       queue manager: {@link IbmMqConfig#mqConnectionFactory} against a {@link MockEnvironment}; the
 *       {@code connectionFactory} bean's condition, its {@code wmq.host} requirement and the start-up check of
 *       {@code validateConnections="true"} ({@link IbmMqConfig.ConnectionValidator}) with an
 *       {@link ApplicationContextRunner}, mock factories and a closed loopback port. No queue manager is ever
 *       reached, and every other host is a reserved {@code .test} name. The placement of the IBM client's log and
 *       trace files by {@link IbmMqConfig#applyClientDiagnosticsDirectory} is verified on the system properties it
 *       sets, in {@code target/mqclient} or a {@link TempDir}.</li>
 * </ul>
 *
 * <p>Profile {@code test} switches the IBM MQ factory off ({@code wmq.enabled: false}), removes the 15 s processing
 * wait and holds each Bayeux connect for at most 1000 ms, below ST-1's 10 s read timeout. No queue manager, external
 * broker or credential is involved.
 */
@SpringBootTest(classes = WebsphereMqApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // Two embedded Artemis servers with one id in a JVM share an in-VM acceptor; another cached test context
        // of this project may run one with the default id.
        "spring.artemis.embedded.server-id=86",
        // The listener is pinned to IPv4 loopback and reached by that literal rather than through
        // HttpTestClient.localhost: "localhost" can resolve to ::1, where the IPv4-bound listener is absent.
        "server.address=" + WebsphereMqBrokerTest.LOOPBACK})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WebsphereMqBrokerTest {

    /** IPv4 loopback literal the listener binds to and ST-1 connects to. */
    static final String LOOPBACK = "127.0.0.1";

    /** Path of the original Ajax connector's {@code serverUrl}. */
    private static final String SERVER_PATH = "/services/wmqExample";

    private static final String ENQUEUE = SERVER_PATH + "/enqueue";

    private static final String DEQUEUE = SERVER_PATH + "/dequeue";

    private static final String COMETD = "/ajax/cometd";

    /** The content type the copied {@code dojox.cometd} client posts its {@code message} parameter with. */
    private static final String FORM = "application/x-www-form-urlencoded";

    private static final String META_PREFIX = "/meta/";

    private static final String HANDSHAKE = "/meta/handshake";

    private static final String CONNECT = "/meta/connect";

    private static final String SUBSCRIBE = "/meta/subscribe";

    private static final String SENT = "hello";

    /** The original component's {@code appendString}, leading space included. */
    private static final String PROCESSED_SUFFIX = " - processed";

    /** WMQ queue of flows {@code Input} and {@code MessageProcessor}, an embedded Artemis queue in profile test. */
    private static final String IN_QUEUE = "in";

    /** WMQ queue of flows {@code MessageProcessor} and {@code Output}, an embedded Artemis queue in profile test. */
    private static final String OUT_QUEUE = "out";

    /** Consumers per queue that the listener container factories configure: Mule 3.8's four receivers. */
    private static final int ORIGINAL_CONSUMERS = 4;

    /** Bound of each wait for the flows or the broker, as in the round-trip test. */
    private static final Duration FLOW_TIMEOUT = Duration.ofSeconds(20);

    /** A form body well beyond the Bayeux endpoint's 200000-byte limit. */
    private static final int OVERSIZED_BODY_BYTES = 300_000;

    /** Size of each chunk of a chunked request body. */
    private static final int CHUNK_BYTES = 8192;

    /** Bytes of an oversized form body written before its response is read; the rest is written only afterwards. */
    private static final int FORM_PREFIX_BYTES = 1024;

    /**
     * Prefixes of the JVM-wide IBM client properties {@link IbmMqConfig#mqConnectionFactory} sets (transport budgets,
     * then log and trace locations) and of the configuration-file location the diagnostics tests set; they are
     * restored after each test so that no other test class in the surefire JVM inherits them.
     */
    private static final List<String> IBM_CLIENT_PROPERTY_PREFIXES =
            List.of("com.ibm.mq.cfg.", "com.ibm.msg.client.commonservices.", "com.ibm.msg.client.config.");

    /** IBM client property naming its error log file. */
    private static final String IBM_LOG_OUTPUT = "com.ibm.msg.client.commonservices.log.outputName";

    /** IBM client property naming its trace file, beside which it keeps its {@code FFDC} directory. */
    private static final String IBM_TRACE_OUTPUT = "com.ibm.msg.client.commonservices.trace.outputName";

    /** IBM client property holding the URL of its configuration file. */
    private static final String IBM_CONFIG_LOCATION = "com.ibm.msg.client.config.location";

    /** The IBM client's own log file name, kept in the diagnostics directory. */
    private static final String IBM_LOG_FILE = "mqjms.log";

    /** The IBM client's own trace file name, kept in the diagnostics directory. */
    private static final String IBM_TRACE_FILE = "mqjavaclient_%PID%.trc";

    private static final String MQ_HOST = "mq.example.test";

    private static final String MQ_CHANNEL = "DEV.APP.SVRCONN";

    private static final String MQ_QUEUE_MANAGER = "QM1";

    /** Test-only user name; a diagnostic must never repeat it. */
    private static final String MQ_USERNAME = "user-sentinel-7c1e";

    /** Test-only password; a diagnostic must never repeat it. */
    private static final String MQ_PASSWORD = "password-sentinel-7c1e";

    /** Bean name of the stand-in {@code ConnectionFactory} the condition tests add beside {@link IbmMqConfig}. */
    private static final String OTHER_CONNECTION_FACTORY = "testConnectionFactory";

    @LocalServerPort
    int port;

    /**
     * The sender {@code InQueueListener} calls, as a spy that runs every real send; only the rollback test stubs it,
     * for its own processed text, and the stubbing is reset after each test.
     */
    @SpyBean
    MqQueueSender mqQueueSender;

    /** Boot's embedded Artemis server, whose queue counters show what the broker committed. */
    @Autowired
    EmbeddedActiveMQ embeddedActiveMq;

    /** Holds the containers of the two {@code @JmsListener} methods, as the running application started them. */
    @Autowired
    JmsListenerEndpointRegistry listenerEndpointRegistry;

    /** The application's template, which the send to {@code out} uses on the {@code in} listener thread. */
    @Autowired
    JmsTemplate jmsTemplate;

    /** The factory of the {@code in} listener container. */
    @Autowired
    @Qualifier(IbmMqConfig.TRANSACTED_LISTENER_FACTORY)
    DefaultJmsListenerContainerFactory transactedListenerContainerFactory;

    /** The running application's context, inspected for the servlet filters it registers. */
    @Autowired
    ApplicationContext applicationContext;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AtomicInteger messageIds = new AtomicInteger();

    private Map<String, String> ibmClientPropertiesBefore;

    @BeforeEach
    void rememberIbmClientProperties() {
        ibmClientPropertiesBefore = ibmClientProperties();
    }

    @AfterEach
    void restoreIbmClientProperties() {
        ibmClientProperties().keySet().stream()
                .filter(name -> !ibmClientPropertiesBefore.containsKey(name))
                .forEach(System::clearProperty);
        ibmClientPropertiesBefore.forEach(System::setProperty);
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {SENT, "", "   "})
    void ajaxEnqueueIsProcessedAndDeliveredOnDequeueWithoutRpcReply(String sent) throws IOException {
        String processed = sent + PROCESSED_SUFFIX;
        ObjectNode handshake = message(HANDSHAKE).put("version", "1.0").put("minimumVersion", "0.9");
        handshake.putArray("supportedConnectionTypes").add("long-polling");
        HttpTestClient.Response handshakeResponse = postBayeux(batch(handshake));
        assertThat(handshakeResponse.header("Content-Type")).as(handshakeResponse.toString()).startsWith("text/json");
        assertThat(handshakeResponse.headers(ContentSecurityPolicyFilter.HEADER_NAME))
                .as(handshakeResponse.toString()).isEmpty();
        JsonNode handshakeReply = reply(messages(handshakeResponse), HANDSHAKE);
        assertSuccessful(handshakeReply);
        String clientId = handshakeReply.path("clientId").asText();
        assertThat(clientId).as(handshakeReply.toString()).isNotBlank();
        String replyTo = ENQUEUE + "#" + clientId;

        // The first connect after a handshake is answered at once; every later one is held until a delivery arrives.
        List<JsonNode> deliveries = new ArrayList<>(connect(clientId));
        subscribe(clientId, DEQUEUE);
        subscribe(clientId, replyTo);

        ObjectNode rpc = message(ENQUEUE).put("clientId", clientId);
        rpc.putObject("data").put("data", sent).put("replyTo", replyTo);
        assertSuccessful(reply(bayeux(batch(rpc)), ENQUEUE));

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).pollInSameThread()
                .until(() -> {
                    deliveries.addAll(connect(clientId));
                    return deliveries.stream().anyMatch(delivery -> isDequeueDelivery(delivery, processed));
                });
        // One more connect collects whatever was published after the processed text, a late rpc reply included.
        deliveries.addAll(connect(clientId));

        assertThat(deliveries)
                .filteredOn(delivery -> DEQUEUE.equals(channel(delivery)))
                .extracting(delivery -> delivery.get("data"))
                .as("data delivered on %s among %s", DEQUEUE, deliveries)
                .containsExactly(TextNode.valueOf(processed));
        assertThat(deliveries)
                .extracting(WebsphereMqBrokerTest::channel)
                .as("channels of %s", deliveries)
                .doesNotContain(replyTo);
    }

    /**
     * Flow {@code MessageProcessor} is one transaction on the broker: a failure after the send to {@code out} has
     * really been made rolls back that send together with the receive from {@code in}, and the broker redelivers the
     * input. The page exchange is the round-trip test's; the spied sender fails once, after the real send of the
     * first attempt, and only for this test's unique text. The embedded broker's queue counters are cumulative over
     * the cached context and count a message only once its transaction has committed, so their changes from the
     * idle queues before the publication to the end show what committed: one message added to {@code in} although
     * it was processed twice, hence redelivered rather than sent again; one committed consume from {@code in}; and
     * one message added to {@code out}, consumed by flow {@code Output}, so the first attempt's output was rolled
     * back. The processed text arrives once on the dequeue channel, and nothing on {@code replyTo}.
     */
    @Test
    void failureAfterTheOutSendRollsBackTheSendAndRedeliversTheInMessage() throws IOException {
        String sent = "rollback-" + UUID.randomUUID();
        String processed = sent + PROCESSED_SUFFIX;
        AtomicInteger realSends = new AtomicInteger();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (realSends.incrementAndGet() == 1) {
                throw new IllegalStateException("injected failure after the send to out");
            }
            return result;
        }).when(mqQueueSender).sendToOut(processed);
        QueueCounters inBefore = idleCounters(IN_QUEUE);
        QueueCounters outBefore = idleCounters(OUT_QUEUE);
        String clientId = handshakeClient();
        String replyTo = ENQUEUE + "#" + clientId;
        List<JsonNode> deliveries = new ArrayList<>(connect(clientId));
        // Subscribed before the publication, so the dequeue channel delivers rather than caches the processed text.
        subscribe(clientId, DEQUEUE);
        subscribe(clientId, replyTo);

        ObjectNode rpc = message(ENQUEUE).put("clientId", clientId);
        rpc.putObject("data").put("data", sent).put("replyTo", replyTo);
        assertSuccessful(reply(bayeux(batch(rpc)), ENQUEUE));

        await().atMost(FLOW_TIMEOUT).pollInterval(Duration.ofMillis(100)).pollInSameThread()
                .untilAsserted(() -> {
                    deliveries.addAll(connect(clientId));
                    QueueCounters in = counters(IN_QUEUE);
                    QueueCounters out = counters(OUT_QUEUE);
                    assertThat(realSends).as("real sends of %s to %s", processed, OUT_QUEUE)
                            .hasValueGreaterThanOrEqualTo(2);
                    assertThat(in.acknowledged() - inBefore.acknowledged())
                            .as("committed consumes from %s: %s", IN_QUEUE, in).isPositive();
                    assertThat(out.acknowledged() - outBefore.acknowledged())
                            .as("consumes from %s: %s", OUT_QUEUE, out).isPositive();
                    assertThat(List.of(in, out)).as("queues %s and %s", IN_QUEUE, OUT_QUEUE)
                            .allMatch(QueueCounters::isIdle);
                    assertThat(deliveries).as("deliveries")
                            .anyMatch(delivery -> isDequeueDelivery(delivery, processed));
                });
        // One more connect collects whatever was published after the processed text, a second output included.
        deliveries.addAll(connect(clientId));
        QueueCounters inAfter = counters(IN_QUEUE);
        QueueCounters outAfter = counters(OUT_QUEUE);

        verify(mqQueueSender, times(2)).sendToOut(processed);
        assertThat(realSends).as("real sends of %s to %s, the first followed by the failure", processed, OUT_QUEUE)
                .hasValue(2);
        assertThat(inAfter.added() - inBefore.added())
                .as("messages added to %s (before %s, after %s): the enqueue, redelivered once rolled back",
                        IN_QUEUE, inBefore, inAfter)
                .isEqualTo(1);
        assertThat(inAfter.acknowledged() - inBefore.acknowledged())
                .as("committed consumes from %s (before %s, after %s)", IN_QUEUE, inBefore, inAfter)
                .isEqualTo(1);
        assertThat(outAfter.added() - outBefore.added())
                .as("messages committed to %s (before %s, after %s): the first send rolled back with the receive",
                        OUT_QUEUE, outBefore, outAfter)
                .isEqualTo(1);
        assertThat(outAfter.acknowledged() - outBefore.acknowledged())
                .as("consumes from %s (before %s, after %s)", OUT_QUEUE, outBefore, outAfter)
                .isEqualTo(1);
        assertThat(deliveries)
                .filteredOn(delivery -> DEQUEUE.equals(channel(delivery)))
                .extracting(delivery -> delivery.get("data"))
                .as("data delivered on %s among %s", DEQUEUE, deliveries)
                .containsExactly(TextNode.valueOf(processed));
        assertThat(deliveries)
                .extracting(WebsphereMqBrokerTest::channel)
                .as("channels of %s", deliveries)
                .doesNotContain(replyTo);
    }

    /**
     * The wiring the transaction of flow {@code MessageProcessor} rests on, as the running application holds it. The
     * container of the {@code in} listener is a {@link DefaultMessageListenerContainer} that is session-transacted
     * ({@code ALWAYS_BEGIN}), runs four consumers, exposes its session to the listener thread and has no transaction
     * manager, and its {@code ConnectionFactory} is the very instance of the session-transacted {@code jmsTemplate}:
     * together these make the send to {@code out} reuse the listener's session ({@code JOIN_IF_POSSIBLE},
     * websphere-mq.xml:16-22). The {@code out} container acknowledges on receipt without a transaction
     * (websphere-mq.xml:25). Every container the {@link IbmMqConfig#TRANSACTED_LISTENER_FACTORY} bean builds is
     * configured like the {@code in} one.
     */
    @Test
    void inQueueListenerRunsOnTheSessionTransactedContainerTheTemplateJoins() {
        DefaultMessageListenerContainer in = runningListenerContainer(IN_QUEUE);
        DefaultMessageListenerContainer out = runningListenerContainer(OUT_QUEUE);

        assertThat(jmsTemplate.isSessionTransacted()).as("jmsTemplate sessionTransacted").isTrue();
        assertJoinedByTheTemplate(IN_QUEUE + " container", in);
        assertThat(out.isSessionTransacted()).as("%s container sessionTransacted", OUT_QUEUE).isFalse();
        assertThat(out.getSessionAcknowledgeMode()).as("%s container acknowledge mode", OUT_QUEUE)
                .isEqualTo(Session.AUTO_ACKNOWLEDGE);
        assertThat(out.getConnectionFactory()).as("%s container connectionFactory", OUT_QUEUE)
                .isSameAs(jmsTemplate.getConnectionFactory());

        SimpleJmsListenerEndpoint endpoint = new SimpleJmsListenerEndpoint();
        endpoint.setId("transactedListenerContainerFactoryProbe");
        endpoint.setDestination(IN_QUEUE);
        endpoint.setMessageListener(message -> fail("A container built for inspection received %s", message));
        DefaultMessageListenerContainer built = transactedListenerContainerFactory.createListenerContainer(endpoint);
        assertThat(built.isRunning()).as("container built for inspection running").isFalse();
        assertJoinedByTheTemplate("container built by " + IbmMqConfig.TRANSACTED_LISTENER_FACTORY, built);
    }

    @Test
    void servesWmqExamplePage() throws IOException {
        HttpTestClient.Response response = http().get(SERVER_PATH + "/");

        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        assertThat(response.header("Content-Type")).as(response.toString()).startsWith("text/html");
        assertThat(response.headers(ContentSecurityPolicyFilter.HEADER_NAME)).as(response.toString())
                .containsExactly(ContentSecurityPolicyFilter.POLICY);
        assertThat(response.body()).isEqualTo(classpathBytes("static/services/wmqExample/index.html"));
    }

    /** The page requested by its file name, and HEAD of the docroot directory, carry the policy too. */
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"GET, " + SERVER_PATH + "/index.html", "HEAD, " + SERVER_PATH + "/"})
    void everyResponseYieldingThePageCarriesTheContentSecurityPolicy(String method, String path) {
        HttpTestClient.Response response = http().send(method, path, null, null);

        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        assertThat(response.header("Content-Type")).as(response.toString()).startsWith("text/html");
        assertThat(response.headers(ContentSecurityPolicyFilter.HEADER_NAME)).as(response.toString())
                .containsExactly(ContentSecurityPolicyFilter.POLICY);
    }

    /** The script, the redirect of the docroot directory without its trailing slash and the 404 carry no policy. */
    @ParameterizedTest(name = "GET {0} -> {1}")
    @CsvSource({SERVER_PATH + "/mule-resource/js/mule.js, 200", SERVER_PATH + ", 302", "/unknown, 404"})
    void responsesOtherThanThePageCarryNoContentSecurityPolicy(String path, int status) {
        HttpTestClient.Response response = http().get(path);

        assertThat(response.status()).as(response.toString()).isEqualTo(status);
        assertThat(response.headers(ContentSecurityPolicyFilter.HEADER_NAME)).as(response.toString()).isEmpty();
    }

    @Test
    void servesMuleAjaxJavaScript() throws IOException {
        HttpTestClient.Response response = http().get(SERVER_PATH + "/mule-resource/js/mule.js");

        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(classpathBytes("mule-resource/js/mule.js"));
    }

    @Test
    void oversizedBayeuxBodyDeclaringItsLengthIsRefused413() {
        HttpTestClient.Response response = http().post(COMETD, FORM, oversizedForm());

        assertThat(response.status()).as(response.toString()).isEqualTo(413);
        assertThat(response.header("Content-Type")).as(response.toString()).startsWith("text/json");
        assertThat(response.bodyAsString()).isEqualTo("[]");
    }

    @Test
    void oversizedChunkedBayeuxBodyIsAnswered413WholeAndItsConnectionThenCarriesAHandshake() throws Exception {
        try (Socket socket = new Socket(LOOPBACK, port)) {
            socket.setSoTimeout(HttpTestClient.TIMEOUT_MILLIS);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            AtomicReference<IOException> writeFailure = new AtomicReference<>();
            Thread writer = writeChunked(out, COMETD, oversizedForm().getBytes(US_ASCII), writeFailure);

            RawResponse refused = readResponse(in);
            writer.join(HttpTestClient.TIMEOUT_MILLIS);

            assertThat(refused.status()).as(refused.toString()).isEqualTo(413);
            assertThat(refused.header("Content-Type")).as(refused.toString()).isEqualTo("text/json;charset=UTF-8");
            assertThat(refused.body()).as(refused.toString()).isEqualTo("[]");
            // The server read the body to its end, after the 413, rather than closing the connection on it.
            assertThat(writer.isAlive()).as("the chunked body still being written").isFalse();
            assertThat(writeFailure.get()).as("failure writing the chunked body").isNull();

            ObjectNode handshake = message(HANDSHAKE).put("version", "1.0").put("minimumVersion", "0.9");
            byte[] form = ("message=" + URLEncoder.encode(batch(handshake), UTF_8)).getBytes(US_ASCII);
            out.write(("POST " + COMETD + " HTTP/1.1\r\nHost: " + LOOPBACK + ":" + port + "\r\nContent-Type: " + FORM
                    + "\r\nContent-Length: " + form.length + "\r\nConnection: close\r\n\r\n").getBytes(US_ASCII));
            out.write(form);
            out.flush();
            RawResponse answered = readResponse(in);

            assertThat(answered.status()).as(answered.toString()).isEqualTo(200);
            assertThat(answered.header("Content-Type")).as(answered.toString()).startsWith("text/json");
            assertSuccessful(reply(objectMapper.readTree(answered.body()), HANDSHAKE));
        }
    }

    /**
     * A method the endpoint does not accept never has its form body read: a form declaring 300000 bytes, beyond the
     * endpoint's 200000-byte bound, of which only the first {@value #FORM_PREFIX_BYTES} are written, gets SC-1's
     * whole 405 before the rest is sent, as the original Ajax connector answered without reading the body. Boot's
     * form-content filter, which {@code spring.mvc.formcontent.filter.enabled: false} switches off, would read the
     * whole body into memory before dispatch, so no response would arrive until the last byte had been sent.
     *
     * @param method a method outside the endpoint's {@code GET} and {@code POST}
     */
    @ParameterizedTest(name = "{0} " + COMETD)
    @ValueSource(strings = {"PUT", "PATCH", "DELETE"})
    void oversizedFormOnAMethodOutsideGetAndPostGets405BeforeItsBodyIsSent(String method) throws IOException {
        byte[] form = oversizedForm().getBytes(US_ASCII);
        try (Socket socket = new Socket(LOOPBACK, port)) {
            socket.setSoTimeout(HttpTestClient.TIMEOUT_MILLIS);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            out.write((method + " " + COMETD + " HTTP/1.1\r\nHost: " + LOOPBACK + ":" + port + "\r\nContent-Type: "
                    + FORM + "\r\nContent-Length: " + form.length + "\r\n\r\n").getBytes(US_ASCII));
            out.write(form, 0, FORM_PREFIX_BYTES);
            out.flush();

            RawResponse refused;
            try {
                refused = readResponse(in);
            } catch (SocketTimeoutException timeout) {
                refused = fail(String.format("No response to %s %s within %d ms while %d of its %d form bytes were "
                        + "sent: the body was buffered before dispatch", method, COMETD, HttpTestClient.TIMEOUT_MILLIS,
                        FORM_PREFIX_BYTES, form.length), timeout);
            }
            writeRemainderIgnoringFailure(out, form, FORM_PREFIX_BYTES);

            assertThat(refused.status()).as(refused.toString()).isEqualTo(405);
            assertThat(refused.header("Content-Type")).as(refused.toString()).isEqualTo("text/plain");
            assertThat(refused.body()).as(refused.toString()).isEqualTo("Method not allowed for endpoint: " + COMETD);
        }
    }

    /**
     * The application registers no {@link FormContentFilter}, the filter that reads a {@code PUT}, {@code PATCH} or
     * {@code DELETE} form body before dispatch, because {@code spring.mvc.formcontent.filter.enabled} is
     * {@code false}.
     */
    @Test
    void contextHoldsNoFormContentFilter() {
        assertThat(applicationContext.getBeanNamesForType(FormContentFilter.class)).as("FormContentFilter beans")
                .isEmpty();
    }

    @Test
    void forgedPublicationOnTheDequeueChannelIsDeniedAndReachesNoSubscriber() throws IOException {
        String forged = "forged" + PROCESSED_SUFFIX;
        String forger = handshakeClient();
        String subscriber = handshakeClient();
        List<JsonNode> deliveries = new ArrayList<>(connect(subscriber));
        subscribe(subscriber, DEQUEUE);

        ObjectNode publication = message(DEQUEUE).put("clientId", forger).put("data", forged);
        JsonNode ack = reply(bayeux(batch(publication)), DEQUEUE);

        assertThat(ack.path("successful").asBoolean(true)).as(ack.toString()).isFalse();
        assertThat(ack.path("error").asText()).as(ack.toString()).isEqualTo("403::Publish denied");
        // Held for the test profile's connect timeout unless a delivery arrives.
        deliveries.addAll(connect(subscriber));
        assertThat(deliveries).as("deliveries").noneMatch(delivery -> isDequeueDelivery(delivery, forged));
    }

    @Test
    void unknownPathReturnsNoListener404() {
        HttpTestClient.Response response = http().get("/unknown");

        assertThat(response.status()).as(response.toString()).isEqualTo(404);
        assertThat(response.header("Content-Type")).as(response.toString()).startsWith("text/plain");
        assertThat(response.bodyAsString()).isEqualTo("No listener for endpoint: /unknown");
    }

    @Test
    void mqConnectionFactoryBindsConfiguredProperties() throws JMSException {
        MQConnectionFactory factory = IbmMqConfig.mqConnectionFactory(mqEnvironment().withProperty("wmq.port", "1515"));

        assertThat(factory.getHostName()).isEqualTo(MQ_HOST);
        assertThat(factory.getPort()).isEqualTo(1515);
        assertThat(factory.getChannel()).isEqualTo(MQ_CHANNEL);
        assertThat(factory.getQueueManager()).isEqualTo(MQ_QUEUE_MANAGER);
        assertThat(factory.getTransportType()).isEqualTo(WMQConstants.WMQ_CM_CLIENT);
        assertThat(factory.getClientReconnectOptions()).isEqualTo(WMQConstants.WMQ_CLIENT_RECONNECT_DISABLED);
    }

    @Test
    void mqConnectionFactoryPortFallsBackTo1414() throws JMSException {
        assertThat(IbmMqConfig.mqConnectionFactory(mqEnvironment()).getPort()).isEqualTo(1414);
        assertThat(IbmMqConfig.mqConnectionFactory(mqEnvironment().withProperty("wmq.port", " ")).getPort())
                .isEqualTo(1414);
    }

    /**
     * Building the factory without {@code wmq.client.diagnostics-dir}, or with it blank, points the IBM client's log
     * and trace files, under the client's own names, into {@code target/mqclient} of the working directory instead of
     * the working directory itself, and creates that directory.
     */
    @ParameterizedTest(name = "wmq.client.diagnostics-dir=[{0}]")
    @NullSource
    @ValueSource(strings = {"", "   "})
    void mqConnectionFactoryPlacesClientDiagnosticsUnderTargetByDefault(String configured) throws JMSException {
        clearIbmClientDiagnosticsLocations();
        MockEnvironment env = mqEnvironment();
        if (configured != null) {
            env.setProperty(IbmMqConfig.CLIENT_DIAGNOSTICS_DIR_KEY, configured);
        }

        IbmMqConfig.mqConnectionFactory(env);

        Path directory = Path.of("target", "mqclient");
        assertThat(directory).isDirectory();
        assertThat(System.getProperty(IBM_LOG_OUTPUT)).isEqualTo(directory.resolve(IBM_LOG_FILE).toString());
        assertThat(System.getProperty(IBM_TRACE_OUTPUT)).isEqualTo(directory.resolve(IBM_TRACE_FILE).toString());
    }

    /** A configured directory, surrounding blanks trimmed, receives both files and is created with its parents. */
    @Test
    void clientDiagnosticsGoToTheConfiguredDirectory(@TempDir Path tempDir) {
        clearIbmClientDiagnosticsLocations();
        Path directory = tempDir.resolve("nested").resolve("mqclient");

        IbmMqConfig.applyClientDiagnosticsDirectory(diagnosticsEnvironment(" " + directory + " "));

        assertThat(directory).isDirectory();
        assertThat(System.getProperty(IBM_LOG_OUTPUT)).isEqualTo(directory.resolve(IBM_LOG_FILE).toString());
        assertThat(System.getProperty(IBM_TRACE_OUTPUT)).isEqualTo(directory.resolve(IBM_TRACE_FILE).toString());
    }

    /** A location the operator set as a system property is kept, while the other file is still placed. */
    @Test
    void clientDiagnosticsKeepALocationSetAsSystemProperty(@TempDir Path tempDir) {
        clearIbmClientDiagnosticsLocations();
        Path directory = tempDir.resolve("mqclient");
        String operatorLog = tempDir.resolve("operator").resolve(IBM_LOG_FILE).toString();
        System.setProperty(IBM_LOG_OUTPUT, operatorLog);

        IbmMqConfig.applyClientDiagnosticsDirectory(diagnosticsEnvironment(directory.toString()));

        assertThat(System.getProperty(IBM_LOG_OUTPUT)).isEqualTo(operatorLog);
        assertThat(System.getProperty(IBM_TRACE_OUTPUT)).isEqualTo(directory.resolve(IBM_TRACE_FILE).toString());
        assertThat(directory).isDirectory();
    }

    /**
     * A location defined in the IBM client's configuration file is kept too, because a system property would
     * override the file; with both locations kept, no system property is set and no directory is created.
     */
    @Test
    void clientDiagnosticsKeepALocationSetInTheIbmConfigurationFile(@TempDir Path tempDir) throws IOException {
        clearIbmClientDiagnosticsLocations();
        Path directory = tempDir.resolve("mqclient");
        String operatorLog = tempDir.resolve("operator").resolve(IBM_LOG_FILE).toString();
        System.setProperty(IBM_LOG_OUTPUT, operatorLog);
        Properties jmsConfig = new Properties();
        jmsConfig.setProperty(IBM_TRACE_OUTPUT, tempDir.resolve("operator").resolve("trace.trc").toString());
        Path configFile = tempDir.resolve("jms.config");
        try (OutputStream out = Files.newOutputStream(configFile)) {
            jmsConfig.store(out, null);
        }
        System.setProperty(IBM_CONFIG_LOCATION, configFile.toUri().toString());

        IbmMqConfig.applyClientDiagnosticsDirectory(diagnosticsEnvironment(directory.toString()));

        assertThat(System.getProperty(IBM_LOG_OUTPUT)).isEqualTo(operatorLog);
        assertThat(System.getProperty(IBM_TRACE_OUTPUT)).isNull();
        assertThat(directory).doesNotExist();
    }

    /** A configuration file the IBM client cannot read sets no location, so both files are placed. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"file:///websphere-mq-java-test-absent/jms.config", "not a URL"})
    void clientDiagnosticsIgnoreAnUnreadableIbmConfigurationFile(String location, @TempDir Path tempDir) {
        clearIbmClientDiagnosticsLocations();
        Path directory = tempDir.resolve("mqclient");
        System.setProperty(IBM_CONFIG_LOCATION, location);

        IbmMqConfig.applyClientDiagnosticsDirectory(diagnosticsEnvironment(directory.toString()));

        assertThat(System.getProperty(IBM_LOG_OUTPUT)).isEqualTo(directory.resolve(IBM_LOG_FILE).toString());
        assertThat(System.getProperty(IBM_TRACE_OUTPUT)).isEqualTo(directory.resolve(IBM_TRACE_FILE).toString());
    }

    /**
     * A name with {@code %}, the start of an IBM client file-name pattern, or one that is no path at all fails naming
     * the key, and sets no property.
     */
    @ParameterizedTest(name = "case {index}")   // the NUL case would put an invalid XML character into the report
    @ValueSource(strings = {"target/mq%PID%", "target/mq%t", "target/mq\u0000client"})
    void clientDiagnosticsRejectADirectoryNameTheClientCannotUse(String configured) {
        clearIbmClientDiagnosticsLocations();

        assertThatThrownBy(() -> IbmMqConfig.applyClientDiagnosticsDirectory(diagnosticsEnvironment(configured)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(IbmMqConfig.CLIENT_DIAGNOSTICS_DIR_KEY);
        assertThat(System.getProperty(IBM_LOG_OUTPUT)).isNull();
        assertThat(System.getProperty(IBM_TRACE_OUTPUT)).isNull();
    }

    /** A directory that cannot be created, here because a file holds its name, fails naming the key and sets nothing. */
    @Test
    void clientDiagnosticsRejectADirectoryThatCannotBeCreated(@TempDir Path tempDir) throws IOException {
        clearIbmClientDiagnosticsLocations();
        Path occupied = Files.createFile(tempDir.resolve("occupied"));

        assertThatThrownBy(() -> IbmMqConfig.applyClientDiagnosticsDirectory(
                        diagnosticsEnvironment(occupied.toString())))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining(IbmMqConfig.CLIENT_DIAGNOSTICS_DIR_KEY)
                .hasMessageContaining(occupied.toAbsolutePath().toString());
        assertThat(System.getProperty(IBM_LOG_OUTPUT)).isNull();
        assertThat(System.getProperty(IBM_TRACE_OUTPUT)).isNull();
    }

    /**
     * The start-up check validates the {@code connectionFactory} bean as the context finally holds it, before any
     * lifecycle bean starts, and the JMS beans use that same bean. A post-processor stands in for the queue manager:
     * the IBM MQ factory is replaced by a mock factory, or, with credentials, the credential adapter is kept and
     * pointed at the mock, so the connection is opened through the adapter with the configured user and password.
     */
    @ParameterizedTest(name = "credentials: {0}")
    @ValueSource(booleans = {false, true})
    void enabledConnectionFactoryIsValidatedBeforeLifecycleStart(boolean credentials) throws JMSException {
        ConnectionFactory queueManager = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        LifecycleProbe probe = new LifecycleProbe();
        AtomicBoolean openedAfterLifecycleStart = new AtomicBoolean();
        Answer<Connection> open = invocation -> {
            openedAfterLifecycleStart.set(probe.isRunning());
            return connection;
        };
        when(queueManager.createConnection()).thenAnswer(open);
        when(queueManager.createConnection(MQ_USERNAME, MQ_PASSWORD)).thenAnswer(open);
        AtomicReference<Object> configured = new AtomicReference<>();
        BeanPostProcessor standIn = new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!"connectionFactory".equals(beanName)) {
                    return bean;
                }
                configured.set(bean);
                if (bean instanceof UserCredentialsConnectionFactoryAdapter adapter) {
                    adapter.setTargetConnectionFactory(queueManager);
                    return adapter;
                }
                return queueManager;
            }
        };
        List<String> properties = new ArrayList<>(List.of("wmq.enabled=true", "wmq.host=" + MQ_HOST,
                "wmq.channel=" + MQ_CHANNEL, "wmq.queue=" + MQ_QUEUE_MANAGER));
        if (credentials) {
            properties.add("wmq.username=" + MQ_USERNAME);
            properties.add("wmq.password=" + MQ_PASSWORD);
        }

        new ApplicationContextRunner().withUserConfiguration(IbmMqConfig.class)
                .withBean("queueManagerStandIn", BeanPostProcessor.class, () -> standIn)
                .withBean("lifecycleProbe", LifecycleProbe.class, () -> probe)
                .withPropertyValues(properties.toArray(String[]::new))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(IbmMqConfig.ConnectionValidator.class);
                    assertThat(configured.get()).isInstanceOf(credentials
                            ? UserCredentialsConnectionFactoryAdapter.class : MQConnectionFactory.class);
                    assertThat(context.getBean("connectionFactory"))
                            .isSameAs(credentials ? configured.get() : queueManager);
                    assertJmsBeansUse(context, "connectionFactory");
                    if (credentials) {
                        verify(queueManager).createConnection(MQ_USERNAME, MQ_PASSWORD);
                    } else {
                        verify(queueManager).createConnection();
                    }
                    verify(connection).close();
                    verifyNoMoreInteractions(queueManager, connection);
                    assertThat(openedAfterLifecycleStart).isFalse();
                    assertThat(probe.isRunning()).isTrue();
                });
    }

    /**
     * An enabled IBM MQ factory whose queue manager cannot be reached fails the context in the start-up check, with
     * the client's {@link JMSException} as the cause and no configured value in the message. The port is a loopback
     * port the system handed out and released, so nothing listens on it.
     */
    @ParameterizedTest(name = "credentials: {0}")
    @ValueSource(booleans = {false, true})
    void enabledConnectionFactoryToUnreachableQueueManagerFailsStartUp(boolean credentials) throws IOException {
        int closedPort = closedLoopbackPort();
        List<String> properties = new ArrayList<>(List.of("wmq.enabled=true", "wmq.host=" + LOOPBACK,
                "wmq.port=" + closedPort, "wmq.channel=" + MQ_CHANNEL, "wmq.queue=" + MQ_QUEUE_MANAGER));
        if (credentials) {
            properties.add("wmq.username=" + MQ_USERNAME);
            properties.add("wmq.password=" + MQ_PASSWORD);
        }

        new ApplicationContextRunner().withUserConfiguration(IbmMqConfig.class)
                .withPropertyValues(properties.toArray(String[]::new))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .isInstanceOf(IllegalStateException.class)
                            .hasCauseInstanceOf(JMSException.class)
                            .hasMessageContaining("validateConnections")
                            .hasMessageContaining("wmq.host, wmq.port, wmq.channel and wmq.queue")
                            .hasMessageContaining("wmq.username and wmq.password")
                            .hasMessageNotContaining(LOOPBACK)
                            .hasMessageNotContaining(String.valueOf(closedPort))
                            .hasMessageNotContaining(MQ_CHANNEL)
                            .hasMessageNotContaining(MQ_QUEUE_MANAGER)
                            .hasMessageNotContaining(MQ_USERNAME)
                            .hasMessageNotContaining(MQ_PASSWORD);
                });
    }

    /** The check opens exactly one connection and closes it; a failing close is logged and does not fail it. */
    @ParameterizedTest(name = "close fails: {0}")
    @ValueSource(booleans = {false, true})
    void connectionValidatorOpensAndClosesExactlyOneConnection(boolean closeFails) throws JMSException {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        when(factory.createConnection()).thenReturn(connection);
        if (closeFails) {
            doThrow(new JMSException("close failed")).when(connection).close();
        }

        assertThatCode(() -> new IbmMqConfig.ConnectionValidator(factory).afterSingletonsInstantiated())
                .doesNotThrowAnyException();

        verify(factory).createConnection();
        verify(connection).close();
        verifyNoMoreInteractions(factory, connection);
    }

    /**
     * A connection that cannot be opened, whether the queue manager is unreachable or rejects the credentials the
     * adapter passes, fails the check once, never retried, with the client's exception as the cause.
     */
    @ParameterizedTest(name = "through the credential adapter: {0}")
    @ValueSource(booleans = {false, true})
    void connectionValidatorFailsOnceWhenNoConnectionOpens(boolean throughCredentialAdapter) throws JMSException {
        ConnectionFactory queueManager = mock(ConnectionFactory.class);
        JMSException refused = throughCredentialAdapter
                ? new JMSSecurityException("not authorized") : new JMSException("host not available");
        ConnectionFactory validated = queueManager;
        if (throughCredentialAdapter) {
            when(queueManager.createConnection(MQ_USERNAME, MQ_PASSWORD)).thenThrow(refused);
            UserCredentialsConnectionFactoryAdapter adapter = new UserCredentialsConnectionFactoryAdapter();
            adapter.setTargetConnectionFactory(queueManager);
            adapter.setUsername(MQ_USERNAME);
            adapter.setPassword(MQ_PASSWORD);
            validated = adapter;
        } else {
            when(queueManager.createConnection()).thenThrow(refused);
        }
        IbmMqConfig.ConnectionValidator validator = new IbmMqConfig.ConnectionValidator(validated);

        assertThatThrownBy(validator::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("validateConnections")
                .hasMessageNotContaining(MQ_USERNAME)
                .hasMessageNotContaining(MQ_PASSWORD)
                .cause().isSameAs(refused);

        if (throughCredentialAdapter) {
            verify(queueManager).createConnection(MQ_USERNAME, MQ_PASSWORD);
        } else {
            verify(queueManager).createConnection();
        }
        verifyNoMoreInteractions(queueManager);
    }

    @ParameterizedTest(name = "wmq.enabled={0}")
    @ValueSource(strings = {"false", "off", "no", "0"})
    void disabledFlagDefinesNoIbmMqConnectionFactory(String enabled) {
        withOtherConnectionFactory(new ApplicationContextRunner().withUserConfiguration(IbmMqConfig.class))
                .withPropertyValues("wmq.enabled=" + enabled, "wmq.host=" + MQ_HOST)
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .doesNotHaveBean("connectionFactory")
                            .doesNotHaveBean(IbmMqConfig.ConnectionValidator.class);
                    assertJmsBeansUse(context, OTHER_CONNECTION_FACTORY);
                });
    }

    /**
     * An enabled IBM MQ factory without a host fails the context on {@code wmq.host}, even where another
     * {@code ConnectionFactory} could stand in, before any IBM client property is set, and its message repeats no
     * configured value.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("enabledWithoutHost")
    void enabledConnectionFactoryWithoutHostFailsNamingTheKey(Map<String, Object> properties) {
        Map<String, Object> configured = new HashMap<>(properties);
        configured.put("wmq.channel", MQ_CHANNEL);
        configured.put("wmq.queue", MQ_QUEUE_MANAGER);
        configured.put("wmq.username", MQ_USERNAME);
        configured.put("wmq.password", MQ_PASSWORD);

        withOtherConnectionFactory(ibmMqConfigRunner(configured)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context).getFailure().rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("wmq.host")
                    .hasMessageContaining("WMQ_HOST")
                    .hasMessageNotContaining(MQ_CHANNEL)
                    .hasMessageNotContaining(MQ_QUEUE_MANAGER)
                    .hasMessageNotContaining(MQ_USERNAME)
                    .hasMessageNotContaining(MQ_PASSWORD);
        });
        assertThat(ibmClientProperties()).isEqualTo(ibmClientPropertiesBefore);
    }

    /** {@code wmq.host} empty or blank, with {@code wmq.enabled} absent or {@code true}. */
    static Stream<Map<String, Object>> enabledWithoutHost() {
        return Stream.of(
                Map.of("wmq.host", ""),
                Map.of("wmq.enabled", "true", "wmq.host", ""),
                Map.of("wmq.enabled", "true", "wmq.host", "   "),
                Map.of("wmq.enabled", "true", "wmq.host", "\t"));
    }

    /** A runner for {@link IbmMqConfig} alone over these properties, blank values kept as written. */
    private static ApplicationContextRunner ibmMqConfigRunner(Map<String, Object> properties) {
        return new ApplicationContextRunner()
                .withUserConfiguration(IbmMqConfig.class)
                .withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("wmq-case", properties)));
    }

    /** Adds a mock {@code ConnectionFactory} the JMS beans could use were the IBM MQ factory absent. */
    private static ApplicationContextRunner withOtherConnectionFactory(ApplicationContextRunner runner) {
        return runner.withBean(OTHER_CONNECTION_FACTORY, ConnectionFactory.class, () -> mock(ConnectionFactory.class));
    }

    /** A loopback port nothing listens on: one the system hands out, released again before it is returned. */
    private static int closedLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))) {
            return socket.getLocalPort();
        }
    }

    /**
     * A lifecycle bean of the earliest phase, standing for the JMS listener containers and the web server, which
     * start with the context's lifecycle beans: running only once the context has started them.
     */
    static final class LifecycleProbe implements SmartLifecycle {

        private volatile boolean running;

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public int getPhase() {
            return Integer.MIN_VALUE;
        }
    }

    /** Asserts the two listener container factories and the template exist, the template on the named factory. */
    private static void assertJmsBeansUse(AssertableApplicationContext context, String connectionFactoryName) {
        assertThat(context)
                .hasBean(IbmMqConfig.TRANSACTED_LISTENER_FACTORY)
                .hasBean(IbmMqConfig.AUTO_ACK_LISTENER_FACTORY)
                .hasBean("jmsTemplate");
        assertThat(context.getBean("jmsTemplate", JmsTemplate.class).getConnectionFactory())
                .isSameAs(context.getBean(connectionFactoryName));
    }

    /** The {@code wmq.*} keys of an IBM MQ client connection, without a port and without credentials. */
    private static MockEnvironment mqEnvironment() {
        return new MockEnvironment()
                .withProperty("wmq.host", MQ_HOST)
                .withProperty("wmq.channel", MQ_CHANNEL)
                .withProperty("wmq.queue", MQ_QUEUE_MANAGER);
    }

    /** An environment holding only {@code wmq.client.diagnostics-dir}. */
    private static MockEnvironment diagnosticsEnvironment(String directory) {
        return new MockEnvironment().withProperty(IbmMqConfig.CLIENT_DIAGNOSTICS_DIR_KEY, directory);
    }

    /**
     * Clears the IBM client's log and trace locations and its configuration-file location, so a diagnostics test
     * starts as a JVM without operator settings; {@link #restoreIbmClientProperties()} puts back any that were set.
     */
    private static void clearIbmClientDiagnosticsLocations() {
        System.clearProperty(IBM_LOG_OUTPUT);
        System.clearProperty(IBM_TRACE_OUTPUT);
        System.clearProperty(IBM_CONFIG_LOCATION);
    }

    /** Sends a {@code /meta/connect} and returns the deliveries its answer carries. */
    private List<JsonNode> connect(String clientId) throws IOException {
        ObjectNode connect = message(CONNECT).put("clientId", clientId).put("connectionType", "long-polling");
        JsonNode messages = bayeux(batch(connect));
        assertSuccessful(reply(messages, CONNECT));
        List<JsonNode> deliveries = new ArrayList<>();
        for (JsonNode message : messages) {
            if (!channel(message).startsWith(META_PREFIX)) {
                deliveries.add(message);
            }
        }
        return deliveries;
    }

    private void subscribe(String clientId, String channel) throws IOException {
        ObjectNode subscribe = message(SUBSCRIBE).put("clientId", clientId).put("subscription", channel);
        JsonNode reply = reply(bayeux(batch(subscribe)), SUBSCRIBE);
        assertSuccessful(reply);
        assertThat(reply.path("subscription").asText()).as(reply.toString()).isEqualTo(channel);
    }

    /** Performs the page's Bayeux handshake and returns the {@code clientId} the server assigned. */
    private String handshakeClient() throws IOException {
        ObjectNode handshake = message(HANDSHAKE).put("version", "1.0").put("minimumVersion", "0.9");
        handshake.putArray("supportedConnectionTypes").add("long-polling");
        JsonNode handshakeReply = reply(bayeux(batch(handshake)), HANDSHAKE);
        assertSuccessful(handshakeReply);
        String clientId = handshakeReply.path("clientId").asText();
        assertThat(clientId).as(handshakeReply.toString()).isNotBlank();
        return clientId;
    }

    /**
     * The one running {@code @JmsListener} container of the registry that consumes {@code destination}, which must be
     * a {@link DefaultMessageListenerContainer}.
     */
    private DefaultMessageListenerContainer runningListenerContainer(String destination) {
        List<AbstractMessageListenerContainer> containers = listenerEndpointRegistry.getListenerContainers().stream()
                .filter(AbstractMessageListenerContainer.class::isInstance)
                .map(AbstractMessageListenerContainer.class::cast)
                .filter(container -> destination.equals(container.getDestinationName()))
                .toList();
        assertThat(containers).as("listener containers of %s", destination).hasSize(1);
        AbstractMessageListenerContainer container = containers.get(0);
        assertThat(container.isRunning()).as("%s container running", destination).isTrue();
        assertThat(container).as("%s container", destination).isInstanceOf(DefaultMessageListenerContainer.class);
        return (DefaultMessageListenerContainer) container;
    }

    /**
     * Asserts that a send of {@link #jmsTemplate} on the thread of a listener of {@code container} joins the
     * listener's local transaction: the container's sessions are transacted and exposed to the listener, no
     * transaction manager gives the template a session of its own, and the template looks the session up under the
     * same {@code ConnectionFactory} instance. Also asserts the four consumers of the original.
     *
     * @param which     names the container in the assertion messages
     * @param container the container to check
     */
    private void assertJoinedByTheTemplate(String which, DefaultMessageListenerContainer container) {
        assertThat(container.isSessionTransacted()).as("%s sessionTransacted", which).isTrue();
        assertThat(container.isExposeListenerSession()).as("%s exposeListenerSession", which).isTrue();
        assertThat(ReflectionTestUtils.getField(container, "transactionManager"))
                .as("%s transactionManager", which).isNull();
        assertThat(container.getConnectionFactory()).as("%s connectionFactory", which)
                .isSameAs(jmsTemplate.getConnectionFactory());
        assertThat(container.getConcurrentConsumers()).as("%s concurrentConsumers", which)
                .isEqualTo(ORIGINAL_CONSUMERS);
        assertThat(container.getMaxConcurrentConsumers()).as("%s maxConcurrentConsumers", which)
                .isEqualTo(ORIGINAL_CONSUMERS);
    }

    /** The current counters of the embedded broker's queue {@code name}. */
    private QueueCounters counters(String name) {
        Queue queue = embeddedActiveMq.getActiveMQServer().locateQueue(SimpleString.toSimpleString(name));
        assertThat(queue).as("embedded Artemis queue %s", name).isNotNull();
        return new QueueCounters(queue.getMessagesAdded(), queue.getMessagesAcknowledged(), queue.getMessageCount(),
                queue.getDeliveringCount());
    }

    /**
     * Waits until queue {@code name} holds no message, neither waiting nor in delivery, so that no earlier test's
     * message is still on its way, and returns its counters then.
     */
    private QueueCounters idleCounters(String name) {
        return await().atMost(FLOW_TIMEOUT).pollInterval(Duration.ofMillis(100)).pollInSameThread()
                .until(() -> counters(name), QueueCounters::isIdle);
    }

    /**
     * Counters of one embedded Artemis queue. {@code added} and {@code acknowledged} are cumulative and count a
     * message only once the transaction that sent or consumed it has committed; {@code messageCount} includes the
     * messages in delivery, which {@code delivering} counts.
     *
     * @param added        messages added to the queue
     * @param acknowledged messages consumed from the queue
     * @param messageCount messages on the queue
     * @param delivering   messages delivered to a consumer and not yet acknowledged
     */
    private record QueueCounters(long added, long acknowledged, long messageCount, int delivering) {

        /** Whether the queue holds no message, neither waiting nor in delivery. */
        boolean isIdle() {
            return messageCount == 0 && delivering == 0;
        }
    }

    /** A Bayeux message on {@code channel} with the next message {@code id}. */
    private ObjectNode message(String channel) {
        return objectMapper.createObjectNode()
                .put("channel", channel)
                .put("id", String.valueOf(messageIds.incrementAndGet()));
    }

    /** The JSON array of one message, as {@code dojox.cometd} sends every request. */
    private String batch(ObjectNode message) throws IOException {
        return objectMapper.writeValueAsString(objectMapper.createArrayNode().add(message));
    }

    private JsonNode bayeux(String jsonArray) throws IOException {
        return messages(postBayeux(jsonArray));
    }

    /** Posts a Bayeux batch as the form parameter {@code message}, as the copied client does, and expects HTTP 200. */
    private HttpTestClient.Response postBayeux(String jsonArray) {
        HttpTestClient.Response response =
                http().post(COMETD, FORM, "message=" + URLEncoder.encode(jsonArray, UTF_8));
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        return response;
    }

    private JsonNode messages(HttpTestClient.Response response) throws IOException {
        JsonNode messages = objectMapper.readTree(response.body());
        assertThat(messages.isArray()).as(response.toString()).isTrue();
        return messages;
    }

    private HttpTestClient http() {
        return new HttpTestClient(LOOPBACK, port);
    }

    /** A form body of {@link #OVERSIZED_BODY_BYTES} bytes, which the endpoint must never parse. */
    private static String oversizedForm() {
        return "message=" + "x".repeat(OVERSIZED_BODY_BYTES - "message=".length());
    }

    /**
     * Starts writing a {@code POST} of {@code body} with {@code Transfer-Encoding: chunked}, which declares no length,
     * in chunks of {@link #CHUNK_BYTES}, on a connection kept alive. The request is written on a thread of its own, so
     * the caller reads a response the server sends before the body ends; a write that fails, as when the server
     * closes the connection on the body, ends the writer and is recorded in {@code failure}.
     *
     * @return the started writer thread
     */
    private Thread writeChunked(OutputStream out, String path, byte[] body, AtomicReference<IOException> failure) {
        String head = "POST " + path + " HTTP/1.1\r\nHost: " + LOOPBACK + ":" + port + "\r\nContent-Type: " + FORM
                + "\r\nTransfer-Encoding: chunked\r\n\r\n";
        Thread writer = new Thread(() -> {
            try {
                out.write(head.getBytes(US_ASCII));
                for (int offset = 0; offset < body.length; offset += CHUNK_BYTES) {
                    int length = Math.min(CHUNK_BYTES, body.length - offset);
                    out.write((Integer.toHexString(length) + "\r\n").getBytes(US_ASCII));
                    out.write(body, offset, length);
                    out.write("\r\n".getBytes(US_ASCII));
                }
                out.write("0\r\n\r\n".getBytes(US_ASCII));
                out.flush();
            } catch (IOException writeFailure) {
                failure.set(writeFailure);
            }
        }, "chunked-body-writer");
        writer.setDaemon(true);
        writer.start();
        return writer;
    }

    /**
     * Writes the bytes of {@code body} from {@code offset} to its end once the response has been read, so the server
     * can finish the exchange; a failed write is ignored, because the server may close a connection whose body it
     * refused unread.
     */
    private static void writeRemainderIgnoringFailure(OutputStream out, byte[] body, int offset) {
        try {
            out.write(body, offset, body.length - offset);
            out.flush();
        } catch (IOException closedByServer) {
            // The response is already read; the server need not take the rest of a body it never reads.
        }
    }

    /**
     * Reads one response off the connection: its status line, its headers and the {@code Content-Length} bytes of its
     * body, leaving the connection positioned at the next response.
     */
    private static RawResponse readResponse(InputStream in) throws IOException {
        String statusLine = readLine(in);
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
            int colon = line.indexOf(':');
            assertThat(colon).as("header line %s", line).isPositive();
            headers.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        String contentLength = headers.get("Content-Length");
        assertThat(contentLength).as("Content-Length of %s %s", statusLine, headers).isNotNull();
        int length = Integer.parseInt(contentLength);
        byte[] body = in.readNBytes(length);
        assertThat(body).as("body of %s %s", statusLine, headers).hasSize(length);
        return new RawResponse(statusLine, headers, new String(body, UTF_8));
    }

    /** One line of a response head without its CRLF; fails when the connection ends first. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int c = in.read(); c != '\n'; c = in.read()) {
            if (c < 0) {
                throw new EOFException("connection closed in a response head after \"" + line + "\"");
            }
            if (c != '\r') {
                line.append((char) c);
            }
        }
        return line.toString();
    }

    /**
     * A response read off a raw connection.
     *
     * @param statusLine the status line, such as {@code HTTP/1.1 413 Request Entity Too Large}
     * @param headers    the headers, names compared case-insensitively
     * @param body       the body as UTF-8 text
     */
    private record RawResponse(String statusLine, Map<String, String> headers, String body) {

        int status() {
            return Integer.parseInt(statusLine.split(" ", 3)[1]);
        }

        String header(String name) {
            return headers.get(name);
        }
    }

    /** The first message of the answer on {@code channel}. */
    private static JsonNode reply(JsonNode messages, String channel) {
        for (JsonNode message : messages) {
            if (channel.equals(channel(message))) {
                return message;
            }
        }
        return fail("No message on channel %s in %s", channel, messages);
    }

    private static void assertSuccessful(JsonNode reply) {
        assertThat(reply.path("successful").isBoolean() && reply.path("successful").asBoolean())
                .as(reply.toString())
                .isTrue();
    }

    private static String channel(JsonNode message) {
        return message.path("channel").asText();
    }

    /** Whether {@code delivery} carries {@code data} on the dequeue channel. */
    private static boolean isDequeueDelivery(JsonNode delivery, String data) {
        return DEQUEUE.equals(channel(delivery)) && TextNode.valueOf(data).equals(delivery.get("data"));
    }

    private static byte[] classpathBytes(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        }
    }

    private static Map<String, String> ibmClientProperties() {
        Map<String, String> properties = new HashMap<>();
        for (String name : System.getProperties().stringPropertyNames()) {
            if (IBM_CLIENT_PROPERTY_PREFIXES.stream().anyMatch(name::startsWith)) {
                properties.put(name, System.getProperty(name));
            }
        }
        return properties;
    }

    /**
     * The same application with {@code ajax.server-path} written with a trailing slash, a form
     * {@code AjaxResourceConfig} accepts: the page must still be served at {@code /services/wmqExample/}, and the
     * end-to-end exchange must still run on the original channels {@code /services/wmqExample/enqueue} and
     * {@code /services/wmqExample/dequeue} (websphere-mq.xml:9,26), which the page uses (index.html:25,55) and
     * {@code ajax.cache-messages-channels} lists. Its own embedded Artemis server id keeps its broker apart from the
     * enclosing context's, which stays open while this class runs.
     */
    @Nested
    @TestPropertySource(properties = {
            "ajax.server-path=" + SERVER_PATH + "/",
            "spring.artemis.embedded.server-id=87"})
    class TrailingSlashServerPath {

        @LocalServerPort
        int trailingSlashPort;

        /** Points the enclosing helpers, which reach the listener through {@code port}, at this context. */
        @BeforeEach
        void useThisContextsListener() {
            port = trailingSlashPort;
        }

        @Test
        void servesThePageAndKeepsTheOriginalChannels() throws IOException {
            servesWmqExamplePage();
            ajaxEnqueueIsProcessedAndDeliveredOnDequeueWithoutRpcReply(SENT);
        }
    }
}
