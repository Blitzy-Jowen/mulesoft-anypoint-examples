package com.mulesoft.examples.service_orchestration_and_choice_routing.controller;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.mulesoft.examples.service_orchestration_and_choice_routing.service.AjaxChannelBroker;

/**
 * Unit tests of {@link BayeuxLongPollingController}, the Bayeux 1.0 long-polling endpoint of the Mule Ajax transport,
 * driven as the copied {@code dojox.cometd} client drives it: {@code application/x-www-form-urlencoded} requests whose
 * {@code message} parameter holds a JSON array of Bayeux messages, each answered with a plain JSON array.
 *
 * <p>Every test builds its own {@link AjaxChannelBroker} from a {@link MockEnvironment} and its own controller on
 * standalone {@link MockMvc}: no application context, no network port and no project advice, so only what the
 * controller and Spring MVC's default resolvers produce is asserted. The channels are neutral: {@value #ECHO} is an
 * inbound channel whose handler returns the publication's {@code data}, and {@value #OUT} is a plain application
 * channel. A held connect is observed through its {@link MvcResult}'s async result, never by sleeping.
 *
 * <p>The class names no project value, so every project serving the Mule Ajax transport carries it with only the
 * {@code package} line and the package root of the {@link AjaxChannelBroker} import changed.
 */
class BayeuxLongPollingControllerTest {

    private static final String PATH = "/ajax/cometd";

    /** Inbound channel; its handler answers each publication with the publication's {@code data}. */
    private static final String ECHO = "/test/echo";

    /** Application channel without a handler: publications reach its subscribers. */
    private static final String OUT = "/test/out";

    private static final String OTHER = "/test/other";

    private static final String HANDSHAKE_CHANNEL = "/meta/handshake";
    private static final String CONNECT_CHANNEL = "/meta/connect";
    private static final String SUBSCRIBE_CHANNEL = "/meta/subscribe";
    private static final String UNSUBSCRIBE_CHANNEL = "/meta/unsubscribe";
    private static final String DISCONNECT_CHANNEL = "/meta/disconnect";

    private static final String UNKNOWN_CLIENT = "402::Unknown client";

    /** Connect timeout of the tests that wait for it to pass. */
    private static final long SHORT_TIMEOUT_MS = 500;

    /** Connect timeout of the tests whose assertions must not race it. */
    private static final long LONG_TIMEOUT_MS = 10_000;

    /** Bound for an answer that must not wait for {@link #LONG_TIMEOUT_MS}, generous for a loaded build host. */
    private static final long PROMPT_MS = 3_000;

    /** How long a connect is watched to show that it is held. */
    private static final long HELD_PROBE_MS = 300;

    private static final MediaType JSON_TYPE = MediaType.parseMediaType("text/json");
    private static final MediaType JSONP_TYPE = MediaType.parseMediaType("text/javascript");

    /** The handshake {@code dojox.cometd} sends. */
    private static final String HANDSHAKE = "[{\"channel\":\"/meta/handshake\",\"version\":\"1.0\","
            + "\"minimumVersion\":\"0.9\",\"supportedConnectionTypes\":[\"long-polling\",\"callback-polling\"],"
            + "\"id\":\"0\"}]";

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<BayeuxLongPollingController> controllers = new ArrayList<>();
    private final AtomicInteger messageIds = new AtomicInteger();
    private AjaxChannelBroker broker;

    @BeforeEach
    void createBroker() {
        broker = new AjaxChannelBroker(new MockEnvironment()
                .withProperty("ajax.disable-reply-to", "false")
                .withProperty("ajax.response-timeout-ms", "5000"));
        broker.registerInbound(ECHO, data -> data);
    }

    @AfterEach
    void shutDown() {
        controllers.forEach(BayeuxLongPollingController::shutdown);
        broker.shutdown();
    }

    @Test
    void handshakeReturnsClientIdAndLongPolling() throws Exception {
        JsonNode reply = single(exchange(mvc(SHORT_TIMEOUT_MS), HANDSHAKE));

        assertThat(reply.path("channel").asText()).isEqualTo(HANDSHAKE_CHANNEL);
        assertThat(reply.path("successful").asBoolean()).as(reply.toString()).isTrue();
        assertThat(reply.path("clientId").asText()).isNotBlank();
        assertThat(broker.isKnownClient(reply.path("clientId").asText())).isTrue();
        assertThat(reply.path("version").asText()).isEqualTo("1.0");
        assertThat(texts(reply.path("supportedConnectionTypes"))).contains("long-polling");
        assertThat(reply.path("advice").path("reconnect").asText()).isEqualTo("retry");
        assertThat(reply.path("advice").path("interval").asInt(-1)).isZero();
        assertThat(reply.path("advice").path("timeout").asLong()).isEqualTo(SHORT_TIMEOUT_MS);
        assertThat(reply.path("id").asText()).isEqualTo("0");
    }

    @Test
    void responsesAreTextJsonUtf8() throws Exception {
        MockMvc mvc = mvc(SHORT_TIMEOUT_MS);
        MockHttpServletResponse response = finish(mvc, start(mvc, HANDSHAKE));

        assertThat(response.getStatus()).isEqualTo(200);
        assertContentType(response, JSON_TYPE);
        // A plain array: the copied client evaluates the text and negotiates no comment filtering.
        assertThat(response.getContentAsString(UTF_8)).startsWith("[").endsWith("]");
    }

    @Test
    void subscribeIsSuccessful() throws Exception {
        MockMvc mvc = mvc(SHORT_TIMEOUT_MS);
        String clientId = handshake(mvc);
        ObjectNode subscribe = subscribe(clientId, OUT);

        JsonNode reply = single(exchange(mvc, batch(subscribe)));

        assertThat(reply.path("channel").asText()).isEqualTo(SUBSCRIBE_CHANNEL);
        assertThat(reply.path("successful").asBoolean()).as(reply.toString()).isTrue();
        assertThat(reply.path("clientId").asText()).isEqualTo(clientId);
        assertThat(reply.path("subscription").asText()).isEqualTo(OUT);
        assertThat(reply.get("id")).isEqualTo(subscribe.get("id"));
    }

    @Test
    void firstConnectAfterHandshakeIsAnsweredAtOnce() throws Exception {
        MockMvc mvc = mvc(LONG_TIMEOUT_MS);
        String clientId = handshake(mvc);
        ObjectNode connect = connect(clientId);

        MvcResult started = start(mvc, batch(connect));
        assertAnsweredPromptly(started);
        ArrayNode reply = parse(finish(mvc, started));

        JsonNode connected = single(reply);
        assertThat(connected.path("channel").asText()).isEqualTo(CONNECT_CHANNEL);
        assertThat(connected.path("successful").asBoolean()).as(reply.toString()).isTrue();
        assertThat(connected.path("clientId").asText()).isEqualTo(clientId);
        assertThat(connected.path("advice").path("timeout").asLong()).isEqualTo(LONG_TIMEOUT_MS);
        assertThat(connected.get("id")).isEqualTo(connect.get("id"));
    }

    @Test
    void rpcPublicationIsAckedAndEchoDeliveredOnReplyTo() throws Exception {
        MockMvc mvc = mvc(LONG_TIMEOUT_MS);
        String clientId = handshake(mvc);
        exchange(mvc, batch(connect(clientId)));
        String replyTo = ECHO + "#" + clientId;
        JsonNode subscribed = single(exchange(mvc, batch(subscribe(clientId, replyTo))));
        assertThat(subscribed.path("successful").asBoolean()).as(subscribed.toString()).isTrue();
        assertThat(subscribed.path("subscription").asText()).isEqualTo(replyTo);

        ObjectNode envelope = mapper.createObjectNode().put("data", "hello").put("replyTo", replyTo);
        ObjectNode publication = publish(clientId, ECHO, envelope);
        JsonNode ack = single(exchange(mvc, batch(publication)));

        assertThat(ack.path("channel").asText()).isEqualTo(ECHO);
        assertThat(ack.path("successful").asBoolean()).as(ack.toString()).isTrue();
        assertThat(ack.has("data")).as("an acknowledgement carries no data: " + ack).isFalse();
        assertThat(ack.get("id")).isEqualTo(publication.get("id"));

        ArrayNode reply = exchange(mvc, batch(connect(clientId)));

        assertThat(reply.get(0).path("channel").asText()).as("replies precede deliveries").isEqualTo(CONNECT_CHANNEL);
        assertThat(find(reply, CONNECT_CHANNEL).path("successful").asBoolean()).as(reply.toString()).isTrue();
        JsonNode echoed = find(reply, replyTo);
        assertThat(echoed.path("data").asText()).isEqualTo("hello");
        assertThat(echoed.path("id").isTextual()).as(reply.toString()).isTrue();
    }

    @Test
    void heldConnectIsReleasedByPublish() throws Exception {
        MockMvc mvc = mvc(LONG_TIMEOUT_MS);
        String clientId = handshake(mvc);
        exchange(mvc, batch(connect(clientId)));
        exchange(mvc, batch(subscribe(clientId, OUT)));

        MvcResult started = start(mvc, batch(connect(clientId)));
        assertHeld(started);

        long publishedAt = System.nanoTime();
        broker.publish(OUT, "pushed");
        ArrayNode reply = parse(finish(mvc, started));

        assertThat(elapsedMs(publishedAt)).as("released by the publication, not by the timeout")
                .isLessThan(PROMPT_MS);
        assertThat(find(reply, CONNECT_CHANNEL).path("successful").asBoolean()).as(reply.toString()).isTrue();
        assertThat(find(reply, OUT).path("data").asText()).isEqualTo("pushed");
        assertThat(deliveries(reply)).hasSize(1);
    }

    @Test
    void heldConnectWithoutMessagesEndsAfterTimeout() throws Exception {
        MockMvc mvc = mvc(SHORT_TIMEOUT_MS);
        String clientId = handshake(mvc);
        exchange(mvc, batch(connect(clientId)));
        ObjectNode connect = connect(clientId);

        long start = System.nanoTime();
        ArrayNode reply = exchange(mvc, batch(connect));
        long elapsed = elapsedMs(start);

        assertThat(elapsed).as("held for the connect timeout").isGreaterThanOrEqualTo(SHORT_TIMEOUT_MS - 100);
        JsonNode connected = single(reply);
        assertThat(connected.path("channel").asText()).isEqualTo(CONNECT_CHANNEL);
        assertThat(connected.path("successful").asBoolean()).as(reply.toString()).isTrue();
        assertThat(connected.get("id")).isEqualTo(connect.get("id"));
    }

    @Test
    void shutdownReleasesAHeldConnectWithItsReplyAlone() throws Exception {
        BayeuxLongPollingController controller = controller(LONG_TIMEOUT_MS);
        MockMvc mvc = mvc(controller);
        String clientId = handshake(mvc);
        exchange(mvc, batch(connect(clientId)));
        MvcResult started = start(mvc, batch(connect(clientId)));
        assertHeld(started);

        controller.shutdown();

        assertAnsweredPromptly(started);
        JsonNode connected = single(parse(finish(mvc, started)));
        assertThat(connected.path("channel").asText()).isEqualTo(CONNECT_CHANNEL);
        assertThat(connected.path("successful").asBoolean()).as(connected.toString()).isTrue();
    }

    @Test
    void firstConnectDrainsQueuedDeliveriesWithTheirDataUnchanged() throws Exception {
        MockMvc mvc = mvc(LONG_TIMEOUT_MS);
        String clientId = handshake(mvc);
        exchange(mvc, batch(subscribe(clientId, OUT)));
        JsonNode data = mapper.readTree("{\"n\":1,\"list\":[true,null,\"s\"],\"text\":\"a\u2028b\u2029c\"}");
        JsonNode ack = single(exchange(mvc, batch(publish(clientId, OUT, data))));
        assertThat(ack.path("successful").asBoolean()).as(ack.toString()).isTrue();

        MvcResult started = start(mvc, batch(connect(clientId)));
        assertAnsweredPromptly(started);
        MockHttpServletResponse response = finish(mvc, started);
        String body = response.getContentAsString(UTF_8);

        // The copied client evaluates the response as JavaScript, where older engines end a line at U+2028/U+2029.
        assertThat(body).contains("a\\u2028b\\u2029c").doesNotContain("\u2028").doesNotContain("\u2029");
        ArrayNode reply = parse(response);
        assertThat(reply).hasSize(2);
        assertThat(reply.get(0).path("channel").asText()).isEqualTo(CONNECT_CHANNEL);
        assertThat(find(reply, OUT).get("data")).isEqualTo(data);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "0123456789abcdef0123456789abcdef")
    void connectWithUnknownClientIsUnsuccessful(String clientId) throws Exception {
        MockMvc mvc = mvc(LONG_TIMEOUT_MS);
        ObjectNode connect = connect(clientId);

        MvcResult started = start(mvc, batch(connect));
        assertAnsweredPromptly(started);
        JsonNode reply = single(parse(finish(mvc, started)));

        assertUnknownClient(reply, CONNECT_CHANNEL, connect);
        assertThat(reply.has("subscription")).isFalse();
    }

    @Test
    void subscribeWithUnknownClientIsUnsuccessful() throws Exception {
        MockMvc mvc = mvc(SHORT_TIMEOUT_MS);
        ObjectNode subscribe = subscribe("0123456789abcdef0123456789abcdef", OUT);

        JsonNode reply = single(exchange(mvc, batch(subscribe)));

        assertUnknownClient(reply, SUBSCRIBE_CHANNEL, subscribe);
        assertThat(reply.path("subscription").asText()).as("echoed so the client can match it").isEqualTo(OUT);
    }

    @Test
    void unsubscribeAndDisconnectSucceedAndTheClientIsThenUnknown() throws Exception {
        MockMvc mvc = mvc(LONG_TIMEOUT_MS);
        String clientId = handshake(mvc);
        JsonNode channels = mapper.createArrayNode().add(OUT).add(OTHER);

        JsonNode subscribed = single(exchange(mvc, batch(subscribe(clientId, channels))));
        assertThat(subscribed.path("successful").asBoolean()).as(subscribed.toString()).isTrue();
        assertThat(subscribed.get("subscription")).isEqualTo(channels);

        ObjectNode unsubscribe = envelope(UNSUBSCRIBE_CHANNEL, clientId);
        unsubscribe.set("subscription", channels);
        JsonNode unsubscribed = single(exchange(mvc, batch(unsubscribe)));
        assertThat(unsubscribed.path("channel").asText()).isEqualTo(UNSUBSCRIBE_CHANNEL);
        assertThat(unsubscribed.path("successful").asBoolean()).as(unsubscribed.toString()).isTrue();
        assertThat(unsubscribed.get("subscription")).isEqualTo(channels);

        broker.publish(OUT, "missed");
        ArrayNode drained = exchange(mvc, batch(connect(clientId)));
        assertThat(deliveries(drained)).as("nothing reaches an unsubscribed client: " + drained).isEmpty();

        JsonNode disconnected = single(exchange(mvc, batch(envelope(DISCONNECT_CHANNEL, clientId))));
        assertThat(disconnected.path("channel").asText()).isEqualTo(DISCONNECT_CHANNEL);
        assertThat(disconnected.path("successful").asBoolean()).as(disconnected.toString()).isTrue();
        assertThat(broker.isKnownClient(clientId)).isFalse();

        ObjectNode connect = connect(clientId);
        assertUnknownClient(single(exchange(mvc, batch(connect))), CONNECT_CHANNEL, connect);
    }

    static Stream<Arguments> invalidMessagesOfAKnownClient() {
        return Stream.of(
                Arguments.of("/meta/ping", null, "404::Unknown meta channel"),
                Arguments.of(SUBSCRIBE_CHANNEL, null, "400::Missing subscription"),
                Arguments.of(SUBSCRIBE_CHANNEL, "42", "400::Invalid subscription"),
                Arguments.of(SUBSCRIBE_CHANNEL, "[]", "400::Invalid subscription"),
                Arguments.of(UNSUBSCRIBE_CHANNEL, "[\"/test/out\",7]", "400::Invalid subscription"),
                Arguments.of(null, null, "400::Missing channel"));
    }

    @ParameterizedTest
    @MethodSource("invalidMessagesOfAKnownClient")
    void invalidMessageIsAnsweredUnsuccessfulInBand(String channel, String subscriptionJson, String error)
            throws Exception {
        MockMvc mvc = mvc(SHORT_TIMEOUT_MS);
        String clientId = handshake(mvc);
        ObjectNode message = envelope(channel, clientId);
        if (subscriptionJson != null) {
            message.set("subscription", mapper.readTree(subscriptionJson));
        }

        JsonNode reply = single(exchange(mvc, batch(message)));

        assertThat(reply.path("successful").asBoolean()).as(reply.toString()).isFalse();
        assertThat(reply.path("error").asText()).isEqualTo(error);
        assertThat(reply.get("id")).isEqualTo(message.get("id"));
        if (subscriptionJson != null) {
            assertThat(reply.get("subscription")).isEqualTo(message.get("subscription"));
        }
        assertThat(broker.isKnownClient(clientId)).as("an in-band failure keeps the client").isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "other=1", "message=", "message=%7B", "message=42", "message=%22text%22",
            "message=null", "message=%5B1%5D", "message=%5B%7B%7D%2C2%5D"})
    void malformedRequestIsAnswered400WithAnEmptyArray(String form) throws Exception {
        MockMvc mvc = mvc(SHORT_TIMEOUT_MS);

        MockHttpServletResponse response = finish(mvc, mvc.perform(post(PATH)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).content(form)).andReturn());

        assertThat(response.getStatus()).isEqualTo(400);
        assertContentType(response, JSON_TYPE);
        assertThat(response.getContentAsString(UTF_8)).isEqualTo("[]");
    }

    @Test
    void singleMessageObjectIsABatchOfOneAndItsIdIsEchoedUnchanged() throws Exception {
        JsonNode reply = single(exchange(mvc(SHORT_TIMEOUT_MS), "{\"channel\":\"/meta/handshake\",\"id\":7}"));

        assertThat(reply.path("successful").asBoolean()).as(reply.toString()).isTrue();
        assertThat(reply.path("id").isInt()).as(reply.toString()).isTrue();
        assertThat(reply.path("id").asInt()).isEqualTo(7);
    }

    @Test
    void getWithTheMessageQueryParameterAnswersLikePost() throws Exception {
        MockMvc mvc = mvc(SHORT_TIMEOUT_MS);

        JsonNode reply = single(parse(finish(mvc, mvc.perform(get(PATH + "?message={m}", HANDSHAKE)).andReturn())));

        assertThat(reply.path("channel").asText()).isEqualTo(HANDSHAKE_CHANNEL);
        assertThat(reply.path("successful").asBoolean()).as(reply.toString()).isTrue();
        assertThat(broker.isKnownClient(reply.path("clientId").asText())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"cb", "dojo.io.script.jsonp_dojoIoScript1._jsonpCallback"})
    void callbackPollingWrapsTheArrayInTheCallback(String callback) throws Exception {
        MockMvc mvc = mvc(SHORT_TIMEOUT_MS);

        MockHttpServletResponse response = finish(mvc,
                mvc.perform(get(PATH + "?message={m}&jsonp={cb}", HANDSHAKE, callback)).andReturn());

        assertThat(response.getStatus()).isEqualTo(200);
        assertContentType(response, JSONP_TYPE);
        String body = response.getContentAsString(UTF_8);
        assertThat(body).startsWith(callback + "([").endsWith("])");
        JsonNode array = mapper.readTree(body.substring(callback.length() + 1, body.length() - 1));
        assertThat(array.isArray()).as(body).isTrue();
        assertThat(single((ArrayNode) array).path("successful").asBoolean()).as(body).isTrue();
    }

    @Test
    void callbackThatIsNotAFunctionReferenceIsRefused() throws Exception {
        MockMvc mvc = mvc(SHORT_TIMEOUT_MS);

        MockHttpServletResponse response = finish(mvc,
                mvc.perform(get(PATH + "?message={m}&jsonp={cb}", HANDSHAKE, "alert(1)//")).andReturn());

        assertThat(response.getStatus()).isEqualTo(400);
        assertContentType(response, JSON_TYPE);
        assertThat(response.getContentAsString(UTF_8)).isEqualTo("[]");
    }

    @Test
    void methodOtherThanGetOrPostIsRefused() throws Exception {
        MockMvc mvc = mvc(SHORT_TIMEOUT_MS);

        MockHttpServletResponse response = mvc.perform(put(PATH)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("message=" + URLEncoder.encode(HANDSHAKE, UTF_8))).andReturn().getResponse();

        // Standalone MockMvc carries no project advice: the listener's 405 body belongs to the project's own
        // exception-handler test, so only Spring's default status and Allow header are asserted here.
        assertThat(response.getStatus()).isEqualTo(405);
        assertThat(response.getHeader("Allow")).contains("GET").contains("POST");
    }

    private BayeuxLongPollingController controller(long connectTimeoutMs) {
        BayeuxLongPollingController controller = new BayeuxLongPollingController(broker, mapper, connectTimeoutMs);
        controllers.add(controller);
        return controller;
    }

    private MockMvc mvc(long connectTimeoutMs) {
        return mvc(controller(connectTimeoutMs));
    }

    private static MockMvc mvc(BayeuxLongPollingController controller) {
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    /** Posts the form body {@code dojox.cometd} sends; the answer may still be pending. */
    private MvcResult start(MockMvc mvc, String jsonArray) throws Exception {
        return mvc.perform(post(PATH)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("message=" + URLEncoder.encode(jsonArray, UTF_8))).andReturn();
    }

    /** The response of a started request, waiting for its async result when there is one. */
    private static MockHttpServletResponse finish(MockMvc mvc, MvcResult started) throws Exception {
        if (started.getRequest().isAsyncStarted()) {
            return mvc.perform(asyncDispatch(started)).andReturn().getResponse();
        }
        return started.getResponse();
    }

    /** Posts a batch and returns the answer, asserting its 200 plain-JSON framing. */
    private ArrayNode exchange(MockMvc mvc, String jsonArray) throws Exception {
        return parse(finish(mvc, start(mvc, jsonArray)));
    }

    private ArrayNode parse(MockHttpServletResponse response) throws Exception {
        String body = response.getContentAsString(UTF_8);
        assertThat(response.getStatus()).as(body).isEqualTo(200);
        assertContentType(response, JSON_TYPE);
        JsonNode tree = mapper.readTree(body);
        assertThat(tree.isArray()).as(body).isTrue();
        return (ArrayNode) tree;
    }

    private static void assertContentType(MockHttpServletResponse response, MediaType expected) {
        assertThat(response.getContentType()).as("Content-Type").isNotNull();
        MediaType actual = MediaType.parseMediaType(response.getContentType());
        assertThat(actual.equalsTypeAndSubtype(expected)).as(actual.toString()).isTrue();
        assertThat(actual.getCharset()).as(actual.toString()).isEqualTo(UTF_8);
    }

    private String handshake(MockMvc mvc) throws Exception {
        String clientId = find(exchange(mvc, HANDSHAKE), HANDSHAKE_CHANNEL).path("clientId").asText();
        assertThat(clientId).isNotBlank();
        return clientId;
    }

    private ObjectNode connect(String clientId) {
        return envelope(CONNECT_CHANNEL, clientId).put("connectionType", "long-polling");
    }

    private ObjectNode subscribe(String clientId, String channel) {
        return envelope(SUBSCRIBE_CHANNEL, clientId).put("subscription", channel);
    }

    private ObjectNode subscribe(String clientId, JsonNode channels) {
        ObjectNode message = envelope(SUBSCRIBE_CHANNEL, clientId);
        message.set("subscription", channels);
        return message;
    }

    private ObjectNode publish(String clientId, String channel, JsonNode data) {
        ObjectNode message = envelope(channel, clientId);
        message.set("data", data);
        return message;
    }

    /** A message on {@code channel} from {@code clientId}; a {@code null} argument leaves its field out. */
    private ObjectNode envelope(String channel, String clientId) {
        ObjectNode message = mapper.createObjectNode();
        if (channel != null) {
            message.put("channel", channel);
        }
        if (clientId != null) {
            message.put("clientId", clientId);
        }
        return message;
    }

    /**
     * The JSON array of the messages, each first given the next {@code id}, written last as {@code dojox.cometd}
     * writes it; the caller's nodes keep their {@code id} for comparison with the replies.
     */
    private String batch(ObjectNode... messages) throws Exception {
        ArrayNode array = mapper.createArrayNode();
        for (ObjectNode message : messages) {
            array.add(message.put("id", String.valueOf(messageIds.incrementAndGet())));
        }
        return mapper.writeValueAsString(array);
    }

    private static JsonNode find(ArrayNode reply, String channel) {
        for (JsonNode element : reply) {
            if (channel.equals(element.path("channel").asText(null))) {
                return element;
            }
        }
        return fail("No message on channel " + channel + " in " + reply);
    }

    private static JsonNode single(ArrayNode reply) {
        assertThat(reply).as(reply.toString()).hasSize(1);
        return reply.get(0);
    }

    /** The messages of a reply on application channels, i.e. everything but the {@code /meta/*} replies. */
    private static List<JsonNode> deliveries(ArrayNode reply) {
        List<JsonNode> deliveries = new ArrayList<>();
        reply.forEach(element -> {
            if (!element.path("channel").asText().startsWith("/meta/")) {
                deliveries.add(element);
            }
        });
        return deliveries;
    }

    private static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(element -> texts.add(element.asText()));
        return texts;
    }

    private static void assertUnknownClient(JsonNode reply, String channel, ObjectNode request) {
        assertThat(reply.path("channel").asText()).isEqualTo(channel);
        assertThat(reply.path("successful").asBoolean()).as(reply.toString()).isFalse();
        assertThat(reply.path("error").asText()).isEqualTo(UNKNOWN_CLIENT);
        assertThat(reply.path("advice").path("reconnect").asText()).isEqualTo("handshake");
        assertThat(reply.path("advice").path("interval").asInt(-1)).isZero();
        assertThat(reply.get("id")).isEqualTo(request.get("id"));
    }

    private static void assertHeld(MvcResult started) {
        assertThat(started.getRequest().isAsyncStarted()).isTrue();
        assertThatThrownBy(() -> started.getAsyncResult(HELD_PROBE_MS)).isInstanceOf(IllegalStateException.class);
    }

    private static void assertAnsweredPromptly(MvcResult started) {
        if (started.getRequest().isAsyncStarted()) {
            assertThatCode(() -> started.getAsyncResult(PROMPT_MS)).doesNotThrowAnyException();
        }
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
