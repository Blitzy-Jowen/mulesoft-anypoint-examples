/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.websphere_mq;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jms.connection.UserCredentialsConnectionFactoryAdapter;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

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
 *   <li>The docroot page, the Mule Ajax JavaScript and the listener's no-listener 404 are fetched from the same
 *       running application.</li>
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

    @Test
    void servesWmqExamplePage() throws IOException {
        HttpTestClient.Response response = http().get(SERVER_PATH + "/");

        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        assertThat(response.header("Content-Type")).as(response.toString()).startsWith("text/html");
        assertThat(response.body()).isEqualTo(classpathBytes("static/services/wmqExample/index.html"));
    }

    @Test
    void servesMuleAjaxJavaScript() throws IOException {
        HttpTestClient.Response response = http().get(SERVER_PATH + "/mule-resource/js/mule.js");

        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(classpathBytes("mule-resource/js/mule.js"));
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
