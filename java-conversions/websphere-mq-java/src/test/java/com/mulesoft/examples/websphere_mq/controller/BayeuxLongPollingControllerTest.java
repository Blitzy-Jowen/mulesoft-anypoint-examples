package com.mulesoft.examples.websphere_mq.controller;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder;
import org.springframework.web.context.request.async.DeferredResult;

import com.mulesoft.examples.websphere_mq.service.AjaxChannelBroker;

/**
 * Unit tests of {@link BayeuxLongPollingController}, the Bayeux 1.0 long-polling endpoint of the Mule Ajax transport,
 * driven as the copied {@code dojox.cometd} client drives it: {@code application/x-www-form-urlencoded} requests whose
 * {@code message} parameter holds a JSON array of Bayeux messages, each answered with a plain JSON array.
 *
 * <p>Every test builds its own {@link AjaxChannelBroker} from a {@link MockEnvironment} and its own controller on
 * standalone {@link MockMvc}: no application context, no network port and no project advice, so only what the
 * controller and Spring MVC's default resolvers produce is asserted. The channels are neutral: {@value #ECHO} is an
 * inbound channel whose handler returns the publication's {@code data}, and {@value #OUT} is an application channel
 * without a handler, which only the server publishes on. A held connect is observed through its {@link MvcResult}'s
 * async result, never by sleeping. The race tests replace the broker with a {@link PausingBroker} that pauses a held
 * connect's wait thread on a latch, and play the container's timeout, error and completion through the request's
 * async listeners ({@link ContainerEvent}). A body without a declared length is played by a filter that hides the
 * length ({@link BodyFraming}); that Undertow drains the unread rest of a refused chunked body, so the client receives
 * the whole 413, is covered over the wire by each project's application test.
 *
 * <p>The class names no project value, so every project serving the Mule Ajax transport carries it with only the
 * {@code package} line and the package root of the {@link AjaxChannelBroker} import changed.
 */
class BayeuxLongPollingControllerTest {

    private static final String PATH = "/ajax/cometd";

    /** Inbound channel; its handler answers each publication with the publication's {@code data}. */
    private static final String ECHO = "/test/echo";

    /** Channel without a handler: the server's publications reach its subscribers; a client's are denied. */
    private static final String OUT = "/test/out";

    private static final String OTHER = "/test/other";

    /** Inbound channel registered by a test whose handler records each payload it receives. */
    private static final String RECORDED = "/test/recorded";

    /** The content type {@code dojox.cometd} posts with. */
    private static final MediaType FORM = MediaType.APPLICATION_FORM_URLENCODED;

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

    /** Clients a test registers at most while looking for the broker's client bound. */
    private static final int MAX_PROBED_CLIENTS = 100_000;

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
        broker = new AjaxChannelBroker(brokerEnvironment());
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
    void heldConnectTimingOutWhileItsAnswerIsDelayedLosesNoDelivery() throws Exception {
        PausingBroker pausing = pausingBroker();
        MockMvc mvc = mvc(LONG_TIMEOUT_MS);
        String clientId = handshake(mvc);
        exchange(mvc, batch(connect(clientId)));
        exchange(mvc, batch(subscribe(clientId, OUT)));
        pausing.pauseAfterNextDrain();
        ArrayNode timedOut;
        try {
            MvcResult started = start(mvc, batch(connect(clientId)));
            broker.publish(OUT, "raced");
            assertThat(pausing.paused.await(PROMPT_MS, TimeUnit.MILLISECONDS))
                    .as("the held connect's answer drained the delivery").isTrue();

            ContainerEvent.TIMEOUT.fire(started);
            timedOut = parse(finish(mvc, started));
        } finally {
            pausing.release.countDown();
        }
        ArrayNode next = exchange(mvc, batch(connect(clientId)));

        assertThat(find(timedOut, CONNECT_CHANNEL).path("successful").asBoolean()).as(timedOut.toString()).isTrue();
        // The timeout answered first, so the delayed answer's deliveries went back to the queue.
        List<JsonNode> received = new ArrayList<>(deliveries(timedOut));
        received.addAll(deliveries(next));
        assertThat(received).as(timedOut + " then " + next)
                .extracting(delivery -> delivery.path("data").asText())
                .containsExactly("raced");
    }

    @Test
    void heldConnectTimingOutBeforeItsWaitClaimsTheResponseRetiresTheWait() throws Exception {
        PausingBroker pausing = pausingBroker();
        MockMvc mvc = mvc(LONG_TIMEOUT_MS);
        String clientId = handshake(mvc);
        exchange(mvc, batch(connect(clientId)));
        exchange(mvc, batch(subscribe(clientId, OUT)));
        pausing.pauseAfterNextWait();
        ObjectNode connect = connect(clientId);
        JsonNode timedOut;
        try {
            MvcResult started = start(mvc, batch(connect));
            broker.publish(OUT, "queued");
            assertThat(pausing.paused.await(PROMPT_MS, TimeUnit.MILLISECONDS))
                    .as("the held connect's wait saw the delivery").isTrue();

            ContainerEvent.TIMEOUT.fire(started);
            timedOut = single(parse(finish(mvc, started)));

            assertThat(pausing.interruptedWhilePaused.await(PROMPT_MS, TimeUnit.MILLISECONDS))
                    .as("the timeout retired the paused wait").isTrue();
        } finally {
            pausing.release.countDown();
        }
        ArrayNode next = exchange(mvc, batch(connect(clientId)));

        assertThat(timedOut.path("channel").asText()).isEqualTo(CONNECT_CHANNEL);
        assertThat(timedOut.path("successful").asBoolean()).as(timedOut.toString()).isTrue();
        assertThat(timedOut.get("id")).isEqualTo(connect.get("id"));
        assertThat(deliveries(next)).as(next.toString())
                .extracting(delivery -> delivery.path("data").asText())
                .containsExactly("queued");
    }

    @Test
    void connectSupersededAfterItsWaitEndedCarriesNoDeliveryAndTheNewerConnectReceivesItOnce() throws Exception {
        PausingBroker pausing = pausingBroker();
        MockMvc mvc = mvc(SHORT_TIMEOUT_MS);
        String clientId = handshake(mvc);
        exchange(mvc, batch(connect(clientId)));
        exchange(mvc, batch(subscribe(clientId, OUT)));
        pausing.pauseAfterNextWait();
        ArrayNode older;
        ArrayNode newer;
        try {
            MvcResult olderStarted = start(mvc, batch(connect(clientId)));
            assertThat(pausing.paused.await(LONG_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                    .as("the older connect's wait ended current by its timeout, before any delivery").isTrue();

            pausing.blockBeforeNextWait();
            MvcResult newerStarted = start(mvc, batch(connect(clientId)));
            assertThat(pausing.blockedBeforeWait.await(PROMPT_MS, TimeUnit.MILLISECONDS))
                    .as("the newer connect claimed on its request and its wait is kept from starting").isTrue();
            broker.publish(OUT, "contested");

            pausing.release.countDown();
            older = parse(finish(mvc, olderStarted));

            pausing.unblock.countDown();
            newer = parse(finish(mvc, newerStarted));
        } finally {
            pausing.release.countDown();
            pausing.unblock.countDown();
        }
        ArrayNode later = exchange(mvc, batch(connect(clientId)));

        JsonNode olderConnected = single(older);
        assertThat(olderConnected.path("channel").asText()).isEqualTo(CONNECT_CHANNEL);
        assertThat(olderConnected.path("successful").asBoolean()).as(older.toString()).isTrue();
        assertThat(find(newer, CONNECT_CHANNEL).path("successful").asBoolean()).as(newer.toString()).isTrue();
        assertThat(deliveries(newer)).as(newer.toString())
                .extracting(delivery -> delivery.path("data").asText())
                .containsExactly("contested");
        assertThat(deliveries(later)).as(later.toString()).isEmpty();
        assertThat(pausing.deliveringDrains).as("only the newer connect drained").hasValue(1);
    }

    @Test
    void connectBeyondTheHeldConnectBoundIsAnsweredAtOnceWithWhatIsQueued() throws Exception {
        BlockingBroker blocking = replaceBroker(new BlockingBroker(brokerEnvironment()));
        MockMvc mvc = mvc(LONG_TIMEOUT_MS);
        String clientId = handshake(mvc);
        exchange(mvc, batch(connect(clientId)));
        exchange(mvc, batch(subscribe(clientId, OUT)));
        try {
            // Each held connect keeps its wait thread until released, so these take every wait thread there is.
            for (int i = 0; i < BayeuxLongPollingController.MAX_HELD_CONNECTS; i++) {
                assertThat(start(mvc, batch(connect(clientId))).getRequest().isAsyncStarted()).isTrue();
            }
            broker.publish(OUT, "beyond the bound");

            MvcResult beyond = start(mvc, batch(connect(clientId)));
            assertAnsweredPromptly(beyond);
            ArrayNode reply = parse(finish(mvc, beyond));

            assertThat(find(reply, CONNECT_CHANNEL).path("successful").asBoolean()).as(reply.toString()).isTrue();
            assertThat(deliveries(reply)).as(reply.toString())
                    .extracting(delivery -> delivery.path("data").asText())
                    .containsExactly("beyond the bound");
        } finally {
            blocking.release.countDown();
        }
    }

    @ParameterizedTest(name = "request ended by its {0}")
    @EnumSource(value = ContainerEvent.class, names = {"ERROR", "COMPLETION"})
    void heldConnectWhoseRequestEndsRetiresItsWaitAndLeavesDeliveriesForTheNextConnect(ContainerEvent ending)
            throws Exception {
        PausingBroker pausing = pausingBroker();
        MockMvc mvc = mvc(LONG_TIMEOUT_MS);
        String clientId = handshake(mvc);
        exchange(mvc, batch(connect(clientId)));
        exchange(mvc, batch(subscribe(clientId, OUT)));
        MvcResult started = start(mvc, batch(connect(clientId)));
        assertThat(pausing.waitsStarted.await(PROMPT_MS, TimeUnit.MILLISECONDS)).isTrue();

        ending.fire(started);

        assertThat(pausing.waitsInterrupted.await(PROMPT_MS, TimeUnit.MILLISECONDS))
                .as("the ended request retired its wait").isTrue();
        broker.publish(OUT, "after the end");
        ArrayNode next = exchange(mvc, batch(connect(clientId)));

        assertThat(deliveries(next)).as(next.toString())
                .extracting(delivery -> delivery.path("data").asText())
                .containsExactly("after the end");
        assertThat(pausing.deliveringDrains).as("only the next connect drained").hasValue(1);
    }

    @Test
    void firstConnectDrainsQueuedDeliveriesWithTheirDataUnchanged() throws Exception {
        MockMvc mvc = mvc(LONG_TIMEOUT_MS);
        String clientId = handshake(mvc);
        exchange(mvc, batch(subscribe(clientId, OUT)));
        JsonNode data = mapper.readTree("{\"n\":1,\"list\":[true,null,\"s\"],\"text\":\"a\u2028b\u2029c\"}");
        broker.publish(OUT, mapper.treeToValue(data, Object.class));

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

    @ParameterizedTest(name = "batch followed by \"{0}\"")
    @ValueSource(strings = {" {\"ignored\":true}", " []", " garbage", " ]", " }"})
    void batchFollowedByFurtherInputIsRefusedWholeWithoutSideEffects(String suffix) throws Exception {
        HandshakeCountingBroker counting = replaceBroker(new HandshakeCountingBroker(brokerEnvironment()));
        MockMvc mvc = mvc(SHORT_TIMEOUT_MS);
        assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS))
                .as("the injected mapper is left unchanged").isFalse();
        String clientId = handshake(mvc);
        String replyTo = ECHO + "#" + clientId;
        exchange(mvc, batch(subscribe(clientId, replyTo)));
        ObjectNode rpc = mapper.createObjectNode().put("data", "published").put("replyTo", replyTo);
        String valid = batch(publish(clientId, ECHO, rpc), envelope(HANDSHAKE_CHANNEL, null));

        MockHttpServletResponse response = finish(mvc, start(mvc, valid + suffix));

        assertThat(response.getStatus()).isEqualTo(400);
        assertContentType(response, JSON_TYPE);
        assertThat(response.getContentAsString(UTF_8)).isEqualTo("[]");
        assertThat(counting.publications).as("the refused publication reached no handler").hasValue(0);
        assertThat(counting.handshakes).as("only the setup's handshake reached the broker").hasValue(1);

        // The batch alone is valid, so the suffix alone made the request malformed.
        ArrayNode accepted = exchange(mvc, valid);
        assertThat(find(accepted, ECHO).path("successful").asBoolean()).as(accepted.toString()).isTrue();
        assertThat(broker.awaitMessages(clientId, PROMPT_MS)).extracting(AjaxChannelBroker.Delivery::data)
                .containsExactly("published");
        assertThat(counting.publications).hasValue(1);
        assertThat(counting.handshakes).hasValue(2);
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
    void handshakeBeyondTheBrokersClientBoundIsDeniedInBandUntilAClientLeaves() throws Exception {
        MockMvc mvc = mvc(SHORT_TIMEOUT_MS);
        List<String> registered = registerClientsUntilRefused();
        ObjectNode handshake = envelope(HANDSHAKE_CHANNEL, null).put("version", "1.0");

        JsonNode denied = single(exchange(mvc, batch(handshake)));

        assertThat(denied.path("channel").asText()).isEqualTo(HANDSHAKE_CHANNEL);
        assertThat(denied.path("successful").asBoolean(true)).as(denied.toString()).isFalse();
        assertThat(denied.path("error").asText()).isEqualTo("403::Handshake denied");
        assertThat(denied.path("advice").path("reconnect").asText()).isEqualTo("handshake");
        assertThat(denied.path("advice").path("interval").asInt()).isEqualTo(1000);
        assertThat(denied.has("clientId")).as(denied.toString()).isFalse();
        assertThat(denied.get("id")).isEqualTo(handshake.get("id"));

        broker.disconnect(registered.get(0));

        assertThat(broker.isKnownClient(handshake(mvc))).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedSubscriptions")
    void subscriptionTheBrokerRefusesIsDeniedInBandAndSubscribesToNone(String description,
            SubscriptionOf subscriptionOf) throws Exception {
        MockMvc mvc = mvc(SHORT_TIMEOUT_MS);
        String clientId = handshake(mvc);
        String owner = handshake(mvc);
        JsonNode channels = subscriptionOf.of(owner);
        ObjectNode subscribe = subscribe(clientId, channels);

        JsonNode reply = single(exchange(mvc, batch(subscribe)));

        assertThat(reply.path("channel").asText()).isEqualTo(SUBSCRIBE_CHANNEL);
        assertThat(reply.path("successful").asBoolean(true)).as(reply.toString()).isFalse();
        assertThat(reply.path("error").asText()).isEqualTo("403::cannot subscribe");
        assertThat(reply.path("clientId").asText()).isEqualTo(clientId);
        assertThat(reply.get("subscription")).as("echoed so the client can match it").isEqualTo(channels);
        assertThat(reply.has("advice")).as("no handshake advice: " + reply).isFalse();
        assertThat(reply.get("id")).isEqualTo(subscribe.get("id"));
        assertThat(broker.isKnownClient(clientId)).as("a denied subscription keeps the client").isTrue();
        broker.publish(OUT, "m");
        broker.publish(ECHO + "#" + owner, "m");
        assertThat(broker.drain(clientId)).isEmpty();
        // The owner of the reply channel subscribes to it.
        JsonNode owned = single(exchange(mvc, batch(subscribe(owner, ECHO + "#" + owner))));
        assertThat(owned.path("successful").asBoolean()).as(owned.toString()).isTrue();
    }

    static Stream<Arguments> refusedSubscriptions() {
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        return Stream.of(
                Arguments.of("another client's reply channel",
                        (SubscriptionOf) owner -> nodes.textNode(ECHO + "#" + owner)),
                Arguments.of("a channel name beyond the broker's bound",
                        (SubscriptionOf) owner -> nodes.textNode("/" + "c".repeat(256))),
                Arguments.of("several channels, one of them another client's reply channel",
                        (SubscriptionOf) owner -> nodes.arrayNode().add(OUT).add(ECHO + "#" + owner)));
    }

    @Test
    void rpcNamingAnotherClientsReplyChannelRunsTheHandlerWhileOnlyAnOwnReplyChannelGetsTheReply() throws Exception {
        BlockingQueue<Object> handled = new LinkedBlockingQueue<>();
        broker.registerInbound(RECORDED, data -> {
            handled.add(data);
            return data;
        });
        MockMvc mvc = mvc(LONG_TIMEOUT_MS);
        String thief = handshake(mvc);
        String victim = handshake(mvc);
        exchange(mvc, batch(connect(thief)));
        exchange(mvc, batch(subscribe(victim, RECORDED + "#" + victim)));
        exchange(mvc, batch(subscribe(thief, RECORDED + "#" + thief)));

        ObjectNode stolen = mapper.createObjectNode().put("data", "stolen").put("replyTo", RECORDED + "#" + victim);
        JsonNode ack = single(exchange(mvc, batch(publish(thief, RECORDED, stolen))));

        assertThat(ack.path("successful").asBoolean()).as(ack.toString()).isTrue();
        assertThat(handled.poll(PROMPT_MS, TimeUnit.MILLISECONDS)).as("the handler still runs").isEqualTo("stolen");
        assertThat(broker.awaitMessages(victim, HELD_PROBE_MS)).as("the victim receives nothing").isEmpty();

        ObjectNode own = mapper.createObjectNode().put("data", "own").put("replyTo", RECORDED + "#" + thief);
        exchange(mvc, batch(publish(thief, RECORDED, own)));
        ArrayNode reply = exchange(mvc, batch(connect(thief)));

        assertThat(deliveries(reply)).as(reply.toString()).singleElement().satisfies(delivery -> {
            assertThat(delivery.path("channel").asText()).isEqualTo(RECORDED + "#" + thief);
            assertThat(delivery.path("data").asText()).isEqualTo("own");
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("channelsWithoutInboundHandler")
    void publicationOnAChannelWithoutInboundHandlerIsDeniedInBandAndReachesNobody(String description,
            ChannelOf channelOf) throws Exception {
        MockMvc mvc = mvc(SHORT_TIMEOUT_MS);
        String forger = handshake(mvc);
        String subscriber = handshake(mvc);
        String channel = channelOf.name(subscriber);
        JsonNode subscribed = single(exchange(mvc, batch(subscribe(subscriber, channel))));
        assertThat(subscribed.path("successful").asBoolean()).as(subscribed.toString()).isTrue();
        ObjectNode publication = publish(forger, channel, mapper.getNodeFactory().textNode("forged - processed"));

        JsonNode ack = single(exchange(mvc, batch(publication)));

        assertThat(ack.path("channel").asText()).isEqualTo(channel);
        assertThat(ack.path("successful").asBoolean(true)).as(ack.toString()).isFalse();
        assertThat(ack.path("error").asText()).isEqualTo("403::Publish denied");
        assertThat(ack.has("data")).as(ack.toString()).isFalse();
        assertThat(ack.get("id")).isEqualTo(publication.get("id"));
        assertThat(broker.drain(subscriber)).as("the forged publication reached no subscriber").isEmpty();
        assertThat(broker.isKnownClient(forger)).isTrue();
    }

    static Stream<Arguments> channelsWithoutInboundHandler() {
        return Stream.of(
                Arguments.of("outbound channel", (ChannelOf) subscriber -> OUT),
                Arguments.of("another client's reply channel", (ChannelOf) subscriber -> ECHO + "#" + subscriber),
                Arguments.of("unconfigured channel", (ChannelOf) subscriber -> "/test/unconfigured"));
    }

    @ParameterizedTest(name = "a form body of {1} bytes, {0}, is answered {2}")
    @MethodSource("bodyFramings")
    void requestBodyBeyondMaxRequestBytesIsRefused413BeforeItIsParsed(BodyFraming framing, int length, int status)
            throws Exception {
        HandshakeCountingBroker counting = replaceBroker(new HandshakeCountingBroker(brokerEnvironment()));
        MockMvc mvc = framing.mvc(controller(SHORT_TIMEOUT_MS));
        String prefix = "message=" + URLEncoder.encode(HANDSHAKE, UTF_8) + "&padding=";
        String form = prefix + "p".repeat(length - prefix.length());

        MockHttpServletResponse response = finish(mvc,
                mvc.perform(post(PATH).contentType(FORM).content(form)).andReturn());

        assertSizeOutcome(response, status, counting, 1);
    }

    static Stream<Arguments> bodyFramings() {
        return Stream.of(BodyFraming.values()).flatMap(framing -> bodyLengths()
                .map(lengthAndStatus -> Arguments.of(framing, lengthAndStatus.get()[0], lengthAndStatus.get()[1])));
    }

    @Test
    void formBodyIsDecodedByTheControllerTakingTheFirstMessageAndJsonp() throws Exception {
        // Built directly rather than through MockMvc, which would also parse the body into the request parameters:
        // here the parameters are empty, as Undertow's are once the controller has read the body.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.setContentType("Application/X-WWW-Form-URLEncoded; charset=UTF-8");
        String handshake = HANDSHAKE.replace(",", ", ");
        request.setContent(("padding=a+b%20c&message=" + URLEncoder.encode(handshake, UTF_8)
                + "&jsonp=cb&message=%5B%5D&jsonp=other&flag").getBytes(UTF_8));

        ResponseEntity<String> response = answer(controller(SHORT_TIMEOUT_MS), request);

        assertThat(response.getStatusCode().value()).as(response.toString()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.parseMediaType("text/javascript;charset=UTF-8"));
        String body = response.getBody();
        assertThat(body).startsWith("cb([").endsWith("])");
        JsonNode reply = single((ArrayNode) mapper.readTree(body.substring("cb(".length(), body.length() - 1)));
        assertThat(reply.path("channel").asText()).isEqualTo(HANDSHAKE_CHANNEL);
        assertThat(reply.path("successful").asBoolean()).as(body).isTrue();
        assertThat(broker.isKnownClient(reply.path("clientId").asText())).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("undecodableFormBodies")
    void formBodyThatCannotBeDecodedIsAnswered400AndNothingOfItIsActedOn(String description, String contentType,
            byte[] body) throws Exception {
        HandshakeCountingBroker counting = replaceBroker(new HandshakeCountingBroker(brokerEnvironment()));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.setContentType(contentType);
        request.setContent(body);

        ResponseEntity<String> response = answer(controller(SHORT_TIMEOUT_MS), request);

        assertThat(response.getStatusCode().value()).as(response.toString()).isEqualTo(400);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.parseMediaType("text/json;charset=UTF-8"));
        assertThat(response.getBody()).isEqualTo("[]");
        assertThat(counting.handshakes).as("the body's handshake reached no broker").hasValue(0);
    }

    static Stream<Arguments> undecodableFormBodies() {
        String form = "application/x-www-form-urlencoded";
        String handshake = "message=" + URLEncoder.encode(HANDSHAKE, UTF_8);
        return Stream.of(
                Arguments.of("a % followed by a non-hexadecimal digit", form,
                        (handshake + "&padding=%zz").getBytes(UTF_8)),
                Arguments.of("a % followed by one hexadecimal digit at the end", form,
                        (handshake + "&padding=%4").getBytes(UTF_8)),
                Arguments.of("a % in a parameter name", form, ("pad%g1=x&" + handshake).getBytes(UTF_8)),
                Arguments.of("a percent-encoded byte sequence that is not UTF-8", form,
                        (handshake + "&padding=%C3%28").getBytes(UTF_8)),
                Arguments.of("a raw byte that is not UTF-8", form,
                        (handshake + "&padding=\u00ff").getBytes(StandardCharsets.ISO_8859_1)),
                Arguments.of("bytes beyond the declared US-ASCII", form + ";charset=US-ASCII",
                        (handshake + "&padding=%C3%A9").getBytes(UTF_8)),
                Arguments.of("a character encoding the JVM does not support", form + ";charset=x-no-such-charset",
                        handshake.getBytes(UTF_8)));
    }

    @Test
    void queryStringParametersOfAPostComeBeforeItsFormBody() throws Exception {
        MockMvc mvc = mvc(SHORT_TIMEOUT_MS);

        MockHttpServletResponse response = finish(mvc, mvc.perform(post(PATH + "?message={m}", HANDSHAKE)
                .contentType(FORM).content("message=42&jsonp=cb")).andReturn());

        // The query string's handshake is handled; the body's jsonp still applies, as the query string has none.
        assertThat(response.getStatus()).as(response.getContentAsString(UTF_8)).isEqualTo(200);
        assertContentType(response, JSONP_TYPE);
        String body = response.getContentAsString(UTF_8);
        assertThat(body).startsWith("cb([").endsWith("])");
        JsonNode reply = single((ArrayNode) mapper.readTree(body.substring("cb(".length(), body.length() - 1)));
        assertThat(reply.path("successful").asBoolean()).as(body).isTrue();
        assertThat(broker.isKnownClient(reply.path("clientId").asText())).isTrue();
    }

    @Test
    void failureReadingTheFormBodyPropagatesUnchanged() throws Exception {
        BayeuxLongPollingController controller = controller(SHORT_TIMEOUT_MS);
        IOException failure = new IOException("Connection reset by peer");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContentLengthLong()).thenReturn(-1L);
        when(request.getContentType()).thenReturn(FORM.toString());
        when(request.getInputStream()).thenThrow(failure);

        assertThatThrownBy(() -> controller.cometd(request)).isSameAs(failure);
    }

    static Stream<Arguments> bodyLengths() {
        return Stream.of(
                Arguments.of(BayeuxLongPollingController.MAX_REQUEST_BYTES, 200),
                Arguments.of(BayeuxLongPollingController.MAX_REQUEST_BYTES + 1, 413));
    }

    @ParameterizedTest(name = "a message parameter of {0} characters is answered {1}")
    @MethodSource("bodyLengths")
    void messageParameterBeyondMaxRequestBytesCharactersIsRefused413(int length, int status) throws Exception {
        HandshakeCountingBroker counting = replaceBroker(new HandshakeCountingBroker(brokerEnvironment()));
        MockMvc mvc = mvc(SHORT_TIMEOUT_MS);
        // Whitespace after the single JSON value is allowed, so only the length decides.
        String message = HANDSHAKE + " ".repeat(length - HANDSHAKE.length());

        MockHttpServletResponse response = finish(mvc, mvc.perform(get(PATH).param("message", message)).andReturn());

        assertSizeOutcome(response, status, counting, 1);
    }

    @ParameterizedTest(name = "a batch of {0} messages is answered {1}")
    @MethodSource("batchSizes")
    void batchBeyondMaxBatchMessagesIsRefused413WithoutSideEffects(int size, int status) throws Exception {
        HandshakeCountingBroker counting = replaceBroker(new HandshakeCountingBroker(brokerEnvironment()));
        MockMvc mvc = mvc(SHORT_TIMEOUT_MS);
        ObjectNode[] handshakes = new ObjectNode[size];
        for (int i = 0; i < size; i++) {
            handshakes[i] = envelope(HANDSHAKE_CHANNEL, null);
        }

        MockHttpServletResponse response = finish(mvc, start(mvc, batch(handshakes)));

        assertSizeOutcome(response, status, counting, size);
        if (status == 200) {
            assertThat(parse(response)).hasSize(size);
        }
    }

    static Stream<Arguments> batchSizes() {
        return Stream.of(
                Arguments.of(BayeuxLongPollingController.MAX_BATCH_MESSAGES, 200),
                Arguments.of(BayeuxLongPollingController.MAX_BATCH_MESSAGES + 1, 413));
    }

    @ParameterizedTest(name = "{0} is answered {3}")
    @MethodSource("batchesCheckedElementByElement")
    void batchIsCountedWhileItIsParsedAndRefusedWithoutSideEffects(String description, int objects, String rest,
            int status) throws Exception {
        HandshakeCountingBroker counting = replaceBroker(new HandshakeCountingBroker(brokerEnvironment()));
        MockMvc mvc = mvc(SHORT_TIMEOUT_MS);
        ObjectNode[] handshakes = new ObjectNode[objects];
        for (int i = 0; i < objects; i++) {
            handshakes[i] = envelope(HANDSHAKE_CHANNEL, null);
        }
        String array = batch(handshakes);
        // The handshakes, then the element under test in place of the closing bracket.
        String message = array.substring(0, array.length() - 1) + "," + rest;

        MockHttpServletResponse response = finish(mvc, start(mvc, message));

        assertThat(response.getStatus()).as(response.getContentAsString(UTF_8)).isEqualTo(status);
        assertContentType(response, JSON_TYPE);
        assertThat(response.getContentAsString(UTF_8)).isEqualTo("[]");
        assertThat(counting.handshakes).as("nothing of the refused batch reached the broker").hasValue(0);
    }

    static Stream<Arguments> batchesCheckedElementByElement() {
        int limit = BayeuxLongPollingController.MAX_BATCH_MESSAGES;
        return Stream.of(
                Arguments.of("a 101st element that is not JSON", limit, "@]", 413),
                Arguments.of("a 101st element that is a 100000-character string", limit,
                        "\"" + "x".repeat(100_000) + "\"]", 413),
                Arguments.of("a 101st element left unterminated, the array never ended", limit, "{\"channel\":", 413),
                Arguments.of("a 100th element that is not an object", limit - 1, "42]", 400),
                Arguments.of("a 100th element that is not JSON", limit - 1, "@]", 400));
    }

    @ParameterizedTest(name = "a batch of {0} empty objects is answered {1}")
    @MethodSource("countedBatchSizes")
    void noMessageBeyondMaxBatchMessagesIsReadIntoATree(int size, int status) throws Exception {
        CountingNodeFactory nodes = new CountingNodeFactory();
        BayeuxLongPollingController controller =
                new BayeuxLongPollingController(broker, new ObjectMapper().setNodeFactory(nodes), SHORT_TIMEOUT_MS);
        controllers.add(controller);
        MockMvc mvc = mvc(controller);

        MockHttpServletResponse response = finish(mvc, start(mvc, "[" + "{},".repeat(size - 1) + "{}]"));

        assertThat(response.getStatus()).as(response.getContentAsString(UTF_8)).isEqualTo(status);
        assertThat(nodes.objects.get()).as("message trees built")
                .isLessThanOrEqualTo(BayeuxLongPollingController.MAX_BATCH_MESSAGES);
        if (status == 200) {
            assertThat(nodes.objects.get()).as("every message of a handled batch is counted").isEqualTo(size);
            assertThat(parse(response)).hasSize(size);
        }
    }

    static Stream<Arguments> countedBatchSizes() {
        return Stream.of(
                Arguments.of(BayeuxLongPollingController.MAX_BATCH_MESSAGES, 200),
                Arguments.of(1000, 413));
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

    private static MockEnvironment brokerEnvironment() {
        return new MockEnvironment()
                .withProperty("ajax.disable-reply-to", "false")
                .withProperty("ajax.response-timeout-ms", "5000");
    }

    /** Replaces the test's broker with a {@link PausingBroker}; called before the test creates its controller. */
    private PausingBroker pausingBroker() {
        return replaceBroker(new PausingBroker(brokerEnvironment()));
    }

    /** Replaces the test's broker with {@code replacement}; called before the test creates its controller. */
    private <T extends AjaxChannelBroker> T replaceBroker(T replacement) {
        broker.shutdown();
        replacement.registerInbound(ECHO, data -> data);
        broker = replacement;
        return replacement;
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

    /** Calls the controller with a request the test built itself and returns the answer, which must be set at once. */
    @SuppressWarnings("unchecked")
    private static ResponseEntity<String> answer(BayeuxLongPollingController controller, HttpServletRequest request)
            throws IOException {
        DeferredResult<ResponseEntity<String>> result = controller.cometd(request);
        assertThat(result.hasResult()).as("answered at once").isTrue();
        return (ResponseEntity<String>) result.getResult();
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

    /**
     * Asserts the plain-JSON answer to a request checked against a size limit: 413 with {@code []} when it was
     * refused, nothing of it having reached the broker, or {@code status} with {@code handshakes} handshakes having
     * reached the broker when it was handled.
     */
    private static void assertSizeOutcome(MockHttpServletResponse response, int status,
            HandshakeCountingBroker counting, int handshakes) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString(UTF_8)).isEqualTo(status);
        assertContentType(response, JSON_TYPE);
        if (status == 413) {
            assertThat(response.getContentAsString(UTF_8)).isEqualTo("[]");
            assertThat(counting.handshakes).as("nothing of the refused request reached the broker").hasValue(0);
        } else {
            assertThat(counting.handshakes).hasValue(handshakes);
        }
    }

    /** Registers clients on the broker directly until its client bound refuses one, and returns them. */
    private List<String> registerClientsUntilRefused() {
        List<String> registered = new ArrayList<>();
        while (registered.size() <= MAX_PROBED_CLIENTS) {
            try {
                registered.add(broker.handshake());
            } catch (AjaxChannelBroker.ChannelRefusedException refused) {
                assertThat(refused.reason()).isEqualTo(AjaxChannelBroker.RefusalReason.CLIENT_LIMIT);
                return registered;
            }
        }
        return fail("The broker registered more than " + MAX_PROBED_CLIENTS + " clients");
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

    /** The {@code subscription} of a {@code /meta/subscribe}, named after another client. */
    @FunctionalInterface
    interface SubscriptionOf {

        JsonNode of(String otherClientId);
    }

    /** Names a channel after one client. */
    @FunctionalInterface
    interface ChannelOf {

        String name(String clientId);
    }

    /** How a form body reaches the controller: with a declared length, or without one, as a chunked body arrives. */
    enum BodyFraming {

        /** With {@code Content-Length}, as MockMvc sends every body. */
        DECLARED,

        /** Without a declared length: a filter makes the request report the length -1, as a chunked request does. */
        CHUNKED;

        MockMvc mvc(BayeuxLongPollingController controller) {
            StandaloneMockMvcBuilder builder = MockMvcBuilders.standaloneSetup(controller);
            if (this == CHUNKED) {
                Filter undeclaredLength = (request, response, chain) -> chain.doFilter(
                        new HttpServletRequestWrapper((HttpServletRequest) request) {
                            @Override
                            public int getContentLength() {
                                return -1;
                            }

                            @Override
                            public long getContentLengthLong() {
                                return -1;
                            }
                        }, response);
                builder.addFilters(undeclaredLength);
            }
            return builder.build();
        }
    }

    /** What the container can do to a held request, played through the async listeners registered on it. */
    enum ContainerEvent {

        /** The request's own timeout. */
        TIMEOUT,

        /** An I/O failure ending the request, such as a client that went away. */
        ERROR,

        /** The end of the request. */
        COMPLETION;

        void fire(MvcResult started) throws IOException {
            MockAsyncContext context = (MockAsyncContext) started.getRequest().getAsyncContext();
            for (AsyncListener listener : new ArrayList<>(context.getListeners())) {
                switch (this) {
                    case TIMEOUT -> listener.onTimeout(
                            new AsyncEvent(context, context.getRequest(), context.getResponse()));
                    case ERROR -> listener.onError(new AsyncEvent(context, context.getRequest(),
                            context.getResponse(), new IOException("Connection reset by peer")));
                    case COMPLETION -> listener.onComplete(
                            new AsyncEvent(context, context.getRequest(), context.getResponse()));
                }
            }
        }
    }

    /**
     * A broker whose held-connect wait thread a race test can pause once, right after its wait ended current or
     * right after it drained deliveries, until the test releases it, and whose next wait a test can keep from starting
     * until it unblocks it. The pause notes an interrupt instead of obeying it, as a thread delayed between two steps
     * would. It also signals the first wait's start and its interruption, and counts the drains that took deliveries,
     * by a held connect's claim or by client.
     */
    static final class PausingBroker extends AjaxChannelBroker {

        final CountDownLatch waitsStarted = new CountDownLatch(1);
        final CountDownLatch waitsInterrupted = new CountDownLatch(1);
        final CountDownLatch paused = new CountDownLatch(1);
        final CountDownLatch interruptedWhilePaused = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch blockedBeforeWait = new CountDownLatch(1);
        final CountDownLatch unblock = new CountDownLatch(1);
        final AtomicInteger deliveringDrains = new AtomicInteger();
        private final AtomicBoolean pauseAfterWait = new AtomicBoolean();
        private final AtomicBoolean pauseAfterDrain = new AtomicBoolean();
        private final AtomicBoolean blockBeforeWait = new AtomicBoolean();

        PausingBroker(MockEnvironment environment) {
            super(environment);
        }

        void pauseAfterNextWait() {
            pauseAfterWait.set(true);
        }

        void pauseAfterNextDrain() {
            pauseAfterDrain.set(true);
        }

        void blockBeforeNextWait() {
            blockBeforeWait.set(true);
        }

        @Override
        public boolean awaitDeliveries(AjaxChannelBroker.ConnectClaim claim, long timeoutMs)
                throws InterruptedException {
            waitsStarted.countDown();
            if (blockBeforeWait.compareAndSet(true, false)) {
                blockedBeforeWait.countDown();
                unblock.await(LONG_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }
            boolean current;
            try {
                current = super.awaitDeliveries(claim, timeoutMs);
            } catch (InterruptedException interrupted) {
                waitsInterrupted.countDown();
                throw interrupted;
            }
            if (current && pauseAfterWait.compareAndSet(true, false)) {
                pause();
            }
            return current;
        }

        @Override
        public List<AjaxChannelBroker.Delivery> drain(String clientId) {
            return counted(super.drain(clientId));
        }

        @Override
        public List<AjaxChannelBroker.Delivery> drain(AjaxChannelBroker.ConnectClaim claim) {
            return counted(super.drain(claim));
        }

        /**
         * Counts a drain that took deliveries, pausing after it when the test asked to.
         *
         * @param drained what the drain took
         * @return {@code drained}
         */
        private List<AjaxChannelBroker.Delivery> counted(List<AjaxChannelBroker.Delivery> drained) {
            if (!drained.isEmpty()) {
                deliveringDrains.incrementAndGet();
                if (pauseAfterDrain.compareAndSet(true, false)) {
                    pause();
                }
            }
            return drained;
        }

        private void pause() {
            paused.countDown();
            boolean interrupted = false;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LONG_TIMEOUT_MS);
            while (release.getCount() > 0 && deadline - System.nanoTime() > 0) {
                try {
                    release.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                } catch (InterruptedException retired) {
                    interrupted = true;
                    interruptedWhilePaused.countDown();
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * A broker whose every held-connect wait first blocks until the test releases it (at most
     * {@link #LONG_TIMEOUT_MS}), so each held connect keeps its wait thread meanwhile.
     */
    static final class BlockingBroker extends AjaxChannelBroker {

        final CountDownLatch release = new CountDownLatch(1);

        BlockingBroker(MockEnvironment environment) {
            super(environment);
        }

        @Override
        public boolean awaitDeliveries(AjaxChannelBroker.ConnectClaim claim, long timeoutMs)
                throws InterruptedException {
            release.await(LONG_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return super.awaitDeliveries(claim, timeoutMs);
        }
    }

    /** A node factory that counts the object nodes built with it: one per message the controller reads into a tree. */
    static final class CountingNodeFactory extends JsonNodeFactory {

        private static final long serialVersionUID = 1L;

        final AtomicInteger objects = new AtomicInteger();

        @Override
        public ObjectNode objectNode() {
            objects.incrementAndGet();
            return super.objectNode();
        }
    }

    /**
     * A broker that counts the handshakes reaching {@link AjaxChannelBroker#handshake()} and the client publications
     * reaching {@link AjaxChannelBroker#onPublication(String, String, Object)}.
     */
    static final class HandshakeCountingBroker extends AjaxChannelBroker {

        final AtomicInteger handshakes = new AtomicInteger();
        final AtomicInteger publications = new AtomicInteger();

        HandshakeCountingBroker(MockEnvironment environment) {
            super(environment);
        }

        @Override
        public String handshake() {
            handshakes.incrementAndGet();
            return super.handshake();
        }

        @Override
        public void onPublication(String clientId, String channel, Object data) {
            publications.incrementAndGet();
            super.onPublication(clientId, channel, data);
        }
    }
}
