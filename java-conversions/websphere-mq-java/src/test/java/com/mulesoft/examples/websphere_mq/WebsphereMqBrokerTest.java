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
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
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
 *   <li>{@link #ajaxEnqueueIsProcessedAndDeliveredOnDequeueWithoutRpcReply()} plays the page: Bayeux handshake,
 *       subscription to {@code /services/wmqExample/dequeue} (index.html:25), then {@code mule.rpc} on
 *       {@code /services/wmqExample/enqueue} without a callback (index.html:55), which per the copied
 *       {@code mule-resource/js/_mule.js} subscribes to {@code <channel>#<clientId>} and publishes
 *       {@code {data, replyTo}}. The text crosses flows {@code Input} (Ajax → queue {@code in}),
 *       {@code MessageProcessor} (queue {@code in} → append {@code " - processed"} → queue {@code out}) and
 *       {@code Output} (queue {@code out} → dequeue channel), and must arrive exactly once as the delivery's
 *       {@code data}, which the page shows as {@code message.data} (index.html:37-47). Nothing may arrive on the
 *       {@code replyTo} channel: the connector's {@code disableReplyTo="true"} (websphere-mq.xml:7), carried as
 *       {@code ajax.disable-reply-to: true}, and flow {@code Input}'s deletion of {@code MULE_REPLYTO}
 *       (websphere-mq.xml:10-12) leave the rpc unanswered.</li>
 *   <li>The docroot page, the Mule Ajax JavaScript and the listener's no-listener 404 are fetched from the same
 *       running application.</li>
 *   <li>The IBM MQ factory configuration, which the embedded broker replaces at run time, is verified by property
 *       binding alone: {@link IbmMqConfig#mqConnectionFactory} against a {@link MockEnvironment}, and the
 *       {@code connectionFactory} bean's condition with an {@link ApplicationContextRunner}. No MQ connection is
 *       ever opened, and every host is a reserved {@code .test} name.</li>
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

    private static final String PROCESSED = SENT + " - processed";

    /**
     * Prefix of the JVM-wide IBM client properties {@link IbmMqConfig#mqConnectionFactory} sets; they are restored
     * after each test so that no other test class in the surefire JVM inherits them.
     */
    private static final String IBM_CLIENT_PROPERTY_PREFIX = "com.ibm.mq.cfg.";

    private static final String MQ_HOST = "mq.example.test";

    private static final String MQ_CHANNEL = "DEV.APP.SVRCONN";

    private static final String MQ_QUEUE_MANAGER = "QM1";

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

    @Test
    void ajaxEnqueueIsProcessedAndDeliveredOnDequeueWithoutRpcReply() throws IOException {
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
        rpc.putObject("data").put("data", SENT).put("replyTo", replyTo);
        assertSuccessful(reply(bayeux(batch(rpc)), ENQUEUE));

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).pollInSameThread()
                .until(() -> {
                    deliveries.addAll(connect(clientId));
                    return deliveries.stream().anyMatch(WebsphereMqBrokerTest::isProcessedDequeueDelivery);
                });
        // One more connect collects whatever was published after the processed text, a late rpc reply included.
        deliveries.addAll(connect(clientId));

        assertThat(deliveries)
                .filteredOn(delivery -> DEQUEUE.equals(channel(delivery)))
                .extracting(delivery -> delivery.get("data"))
                .as("data delivered on %s among %s", DEQUEUE, deliveries)
                .containsExactly(TextNode.valueOf(PROCESSED));
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

    @Test
    void connectionFactoryBeanRequiresEnabledFlagAndHost() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(IbmMqConfig.class);

        runner.withPropertyValues("wmq.enabled=true", "wmq.host=" + MQ_HOST, "wmq.port=1414",
                        "wmq.channel=" + MQ_CHANNEL, "wmq.queue=" + MQ_QUEUE_MANAGER)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasBean("connectionFactory");
                    assertThat(context.getBean("connectionFactory")).isInstanceOf(MQConnectionFactory.class);
                    assertJmsBeansUse(context, "connectionFactory");
                });

        ApplicationContextRunner withOtherFactory = runner.withBean("testConnectionFactory", ConnectionFactory.class,
                () -> mock(ConnectionFactory.class));
        withOtherFactory.withPropertyValues("wmq.enabled=false", "wmq.host=" + MQ_HOST)
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean("connectionFactory");
                    assertJmsBeansUse(context, "testConnectionFactory");
                });
        withOtherFactory.withPropertyValues("wmq.enabled=true", "wmq.host=")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean("connectionFactory");
                    assertJmsBeansUse(context, "testConnectionFactory");
                });
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

    private static boolean isProcessedDequeueDelivery(JsonNode delivery) {
        return DEQUEUE.equals(channel(delivery)) && TextNode.valueOf(PROCESSED).equals(delivery.get("data"));
    }

    private static byte[] classpathBytes(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        }
    }

    private static Map<String, String> ibmClientProperties() {
        Map<String, String> properties = new HashMap<>();
        for (String name : System.getProperties().stringPropertyNames()) {
            if (name.startsWith(IBM_CLIENT_PROPERTY_PREFIX)) {
                properties.put(name, System.getProperty(name));
            }
        }
        return properties;
    }
}
