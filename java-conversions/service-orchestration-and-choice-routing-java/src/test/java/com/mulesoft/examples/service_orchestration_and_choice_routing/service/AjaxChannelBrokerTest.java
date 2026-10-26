package com.mulesoft.examples.service_orchestration_and_choice_routing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Unit tests of {@link AjaxChannelBroker} on neutral channels: {@code /test/in} (inbound, with a handler),
 * {@code /test/out} (outbound) and {@code /test/cached} (outbound, held for the first subscriber where configured).
 *
 * <p>No Spring context and no network: each broker is built from a {@link MockEnvironment} and shut down after the
 * test. Asynchronous outcomes are awaited through {@link AjaxChannelBroker#awaitMessages(String, long)}, latches or
 * Awaitility; a check that nothing arrives listens for {@link #QUIET_MS}.
 */
class AjaxChannelBrokerTest {

    private static final String INBOUND = "/test/in";
    private static final String OUTBOUND = "/test/out";
    private static final String CACHED = "/test/cached";

    /** Upper bound for anything the broker completes asynchronously, generous for a loaded build host. */
    private static final long WAIT_MS = 10_000;

    /** How long a check that no message arrives keeps listening. */
    private static final long QUIET_MS = 300;

    /** {@code ajax.response-timeout-ms} for brokers whose handlers are expected to answer. */
    private static final String HANDLER_TIMEOUT_MS = "2000";

    private final List<AjaxChannelBroker> brokers = new ArrayList<>();

    @AfterEach
    void shutDownBrokers() {
        brokers.forEach(AjaxChannelBroker::shutdown);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("replyAndTimeoutSettings")
    void readsReplyAndTimeoutSettings(String description, Map<String, String> properties, boolean replyToDisabled,
            long responseTimeoutMs) {
        AjaxChannelBroker broker = broker(environment(properties));

        assertThat(broker.isReplyToDisabled()).isEqualTo(replyToDisabled);
        assertThat(broker.responseTimeoutMs()).isEqualTo(responseTimeoutMs);
    }

    static Stream<Arguments> replyAndTimeoutSettings() {
        return Stream.of(
                Arguments.of("defaults when nothing configured", Map.of(), false, 10000L),
                Arguments.of("replies explicitly enabled", Map.of("ajax.disable-reply-to", "false"), false, 10000L),
                Arguments.of("replies disabled and timeout set",
                        Map.of("ajax.disable-reply-to", "true", "ajax.response-timeout-ms", "2000"), true, 2000L));
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource({
        "ajax.response-timeout-ms, 0",
        "ajax.response-timeout-ms, -1",
        "ajax.max-interval-ms, 0",
        "ajax.max-interval-ms, -1"
    })
    void rejectsNonPositiveDurations(String key, String value) {
        MockEnvironment environment = new MockEnvironment().withProperty(key, value);

        assertThatThrownBy(() -> broker(environment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(key);
    }

    @Test
    void handshakeIssuesDistinctKnownClientIds() {
        AjaxChannelBroker broker = broker(new MockEnvironment());

        String first = broker.handshake();
        String second = broker.handshake();

        assertThat(first).isNotEqualTo(second);
        assertThat(List.of(first, second)).allSatisfy(clientId -> {
            assertThat(clientId).matches("[0-9a-f]{32}");
            assertThat(broker.isKnownClient(clientId)).isTrue();
        });
        assertThat(broker.isKnownClient("0".repeat(32))).isFalse();
        assertThat(broker.isKnownClient(null)).isFalse();
    }

    @Test
    void inboundResultReachesReplyToWhenRepliesEnabled() throws Exception {
        AjaxChannelBroker broker = broker(handlerEnvironment().withProperty("ajax.disable-reply-to", "false"));
        broker.registerInbound(INBOUND, data -> "echo:" + data);
        String client = broker.handshake();
        String channelSubscriber = broker.handshake();
        String replyTo = replyChannel(client);
        broker.subscribe(client, replyTo);
        broker.subscribe(channelSubscriber, INBOUND);

        broker.onPublication(client, INBOUND, rpc("hello", replyTo));

        assertThat(broker.awaitMessages(client, WAIT_MS))
                .containsExactly(new AjaxChannelBroker.Delivery(replyTo, "echo:hello"));
        assertThat(broker.drain(client)).isEmpty();
        // An inbound publication reaches the handler only, never the channel's subscribers.
        assertThat(broker.drain(channelSubscriber)).isEmpty();
    }

    @Test
    void noReplyWhenReplyToDisabled() throws Exception {
        AjaxChannelBroker broker = broker(handlerEnvironment().withProperty("ajax.disable-reply-to", "true"));
        CountDownLatch handled = new CountDownLatch(1);
        broker.registerInbound(INBOUND, data -> {
            try {
                return "echo:" + data;
            } finally {
                handled.countDown();
            }
        });
        String client = broker.handshake();
        String replyTo = replyChannel(client);
        broker.subscribe(client, replyTo);
        broker.subscribe(client, OUTBOUND);

        broker.onPublication(client, INBOUND, rpc("hello", replyTo));

        assertThat(handled.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(broker.awaitMessages(client, QUIET_MS)).isEmpty();

        broker.publish(OUTBOUND, "outbound message");

        assertThat(broker.awaitMessages(client, WAIT_MS))
                .containsExactly(new AjaxChannelBroker.Delivery(OUTBOUND, "outbound message"));
        assertThat(broker.drain(client)).isEmpty();
    }

    @ParameterizedTest(name = "handler {0}")
    @MethodSource("handlersWithoutResult")
    void handlerFailureOrNullResultSendsNoReply(String description, AjaxChannelBroker.InboundHandler behaviour)
            throws Exception {
        AjaxChannelBroker broker = broker(handlerEnvironment());
        CountDownLatch failed = new CountDownLatch(1);
        broker.registerInbound(INBOUND, data -> {
            if ("good".equals(data)) {
                return "echo:" + data;
            }
            try {
                return behaviour.handle(data);
            } finally {
                failed.countDown();
            }
        });
        String client = broker.handshake();
        String replyTo = replyChannel(client);
        broker.subscribe(client, replyTo);

        broker.onPublication(client, INBOUND, rpc("bad", replyTo));

        assertThat(failed.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(broker.awaitMessages(client, QUIET_MS)).isEmpty();

        // The broker keeps serving the channel after a publication that produced no reply.
        broker.onPublication(client, INBOUND, rpc("good", replyTo));

        assertThat(broker.awaitMessages(client, WAIT_MS))
                .containsExactly(new AjaxChannelBroker.Delivery(replyTo, "echo:good"));
    }

    static Stream<Arguments> handlersWithoutResult() {
        return Stream.of(
                Arguments.of("returns null", (AjaxChannelBroker.InboundHandler) data -> null),
                Arguments.of("throws a checked exception", (AjaxChannelBroker.InboundHandler) data -> {
                    throw new IOException("handler failed");
                }),
                Arguments.of("throws an unchecked exception", (AjaxChannelBroker.InboundHandler) data -> {
                    throw new IllegalStateException("handler failed");
                }),
                Arguments.of("throws an exception without a message", (AjaxChannelBroker.InboundHandler) data -> {
                    throw new IllegalStateException();
                }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("publicationsWithoutReplyChannel")
    void publicationWithoutStringReplyToReachesHandlerWithoutReply(String description, Object published,
            Object expectedPayload) throws Exception {
        AjaxChannelBroker broker = broker(handlerEnvironment());
        CompletableFuture<Object> received = new CompletableFuture<>();
        broker.registerInbound(INBOUND, data -> {
            received.complete(data);
            return "echo";
        });
        String client = broker.handshake();
        broker.subscribe(client, replyChannel(client));

        broker.onPublication(client, INBOUND, published);

        assertThat(received.get(WAIT_MS, TimeUnit.MILLISECONDS)).isEqualTo(expectedPayload);
        assertThat(broker.awaitMessages(client, QUIET_MS)).isEmpty();
    }

    static Stream<Arguments> publicationsWithoutReplyChannel() {
        Map<String, Object> withoutReplyTo = Map.of("data", "hello");
        return Stream.of(
                Arguments.of("plain value", "hello", "hello"),
                Arguments.of("map without replyTo is the payload itself", withoutReplyTo, withoutReplyTo),
                Arguments.of("envelope whose replyTo is not a String", Map.of("data", "hello", "replyTo", 42),
                        "hello"));
    }

    @Test
    void slowHandlerGetsNoReplyAndItsLateResultIsDiscarded() throws Exception {
        AjaxChannelBroker broker = broker(new MockEnvironment().withProperty("ajax.response-timeout-ms", "100"));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        broker.registerInbound(INBOUND, data -> {
            started.countDown();
            try {
                release.await(WAIT_MS, TimeUnit.MILLISECONDS);
                return "late:" + data;
            } finally {
                finished.countDown();
            }
        });
        String client = broker.handshake();
        String replyTo = replyChannel(client);
        broker.subscribe(client, replyTo);

        try {
            broker.onPublication(client, INBOUND, rpc("hello", replyTo));

            assertThat(started.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
            // Twenty times the response timeout: the timeout has passed while the handler is still blocked.
            assertThat(broker.awaitMessages(client, 2000)).isEmpty();
        } finally {
            release.countDown();
        }

        assertThat(finished.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(broker.awaitMessages(client, QUIET_MS)).isEmpty();
    }

    @Test
    void outboundPublishReachesEverySubscriber() throws Exception {
        AjaxChannelBroker broker = broker(new MockEnvironment());
        String first = broker.handshake();
        String second = broker.handshake();
        String bystander = broker.handshake();
        String wildcard = broker.handshake();
        broker.subscribe(first, OUTBOUND);
        broker.subscribe(second, OUTBOUND);
        // Channels match exactly; Bayeux wildcards are not supported.
        broker.subscribe(wildcard, "/test/*");

        broker.publish(OUTBOUND, "m1");

        List<AjaxChannelBroker.Delivery> expected = List.of(new AjaxChannelBroker.Delivery(OUTBOUND, "m1"));
        assertThat(broker.awaitMessages(first, WAIT_MS)).isEqualTo(expected);
        assertThat(broker.awaitMessages(second, WAIT_MS)).isEqualTo(expected);
        assertThat(broker.drain(first)).isEmpty();
        assertThat(broker.drain(second)).isEmpty();
        assertThat(broker.drain(bystander)).isEmpty();
        assertThat(broker.drain(wildcard)).isEmpty();
    }

    @Test
    void publicationOnChannelWithoutHandlerReachesEverySubscriberPublisherIncluded() {
        AjaxChannelBroker broker = broker(new MockEnvironment());
        String publisher = broker.handshake();
        String subscriber = broker.handshake();
        broker.subscribe(publisher, OUTBOUND);
        broker.subscribe(subscriber, OUTBOUND);
        Map<String, Object> data = Map.of("text", "hi", "count", 2);

        broker.onPublication(publisher, OUTBOUND, data);

        List<AjaxChannelBroker.Delivery> expected = List.of(new AjaxChannelBroker.Delivery(OUTBOUND, data));
        assertThat(broker.drain(publisher)).isEqualTo(expected);
        assertThat(broker.drain(subscriber)).isEqualTo(expected);
        assertThat(broker.drain(subscriber)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cachedChannelSettings")
    void cachedChannelHoldsMessagesUntilFirstSubscriber(String description, Map<String, String> properties) {
        AjaxChannelBroker broker = broker(environment(properties));

        broker.publish(CACHED, "m1");
        broker.publish(CACHED, "m2");
        broker.publish(OUTBOUND, "lost");
        String first = broker.handshake();
        broker.subscribe(first, CACHED);
        broker.subscribe(first, OUTBOUND);

        assertThat(broker.drain(first)).containsExactly(
                new AjaxChannelBroker.Delivery(CACHED, "m1"),
                new AjaxChannelBroker.Delivery(CACHED, "m2"));

        // The hold ends with its first subscriber; later messages go to every subscriber at once.
        String later = broker.handshake();
        broker.subscribe(later, CACHED);
        assertThat(broker.drain(later)).isEmpty();

        broker.publish(CACHED, "m3");

        List<AjaxChannelBroker.Delivery> expected = List.of(new AjaxChannelBroker.Delivery(CACHED, "m3"));
        assertThat(broker.drain(first)).isEqualTo(expected);
        assertThat(broker.drain(later)).isEqualTo(expected);
    }

    static Stream<Arguments> cachedChannelSettings() {
        return Stream.of(
                Arguments.of("comma-separated", Map.of("ajax.cache-messages-channels", CACHED)),
                Arguments.of("indexed", Map.of("ajax.cache-messages-channels[0]", CACHED)),
                Arguments.of("comma-separated with a blank entry",
                        Map.of("ajax.cache-messages-channels", " , " + CACHED)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("uncachedSettings")
    void uncachedChannelDropsMessagesPublishedWithoutSubscriber(String description, Map<String, String> properties) {
        AjaxChannelBroker broker = broker(environment(properties));

        broker.publish(CACHED, "lost");
        String client = broker.handshake();
        broker.subscribe(client, CACHED);

        assertThat(broker.drain(client)).isEmpty();
    }

    static Stream<Arguments> uncachedSettings() {
        return Stream.of(
                Arguments.of("nothing configured", Map.of()),
                // An empty YAML list reaches the environment as an empty string.
                Arguments.of("empty list", Map.of("ajax.cache-messages-channels", "")));
    }

    @Test
    void cachedChannelHoldsAtMostMaxHeldMessagesAndDropsTheRest() {
        AjaxChannelBroker broker = broker(environment(Map.of("ajax.cache-messages-channels", CACHED)));
        int max = AjaxChannelBroker.MAX_HELD_MESSAGES;

        IntStream.rangeClosed(1, max + 1).forEach(i -> broker.publish(CACHED, i));
        String client = broker.handshake();
        broker.subscribe(client, CACHED);

        assertThat(broker.drain(client)).containsExactlyElementsOf(IntStream.rangeClosed(1, max)
                .mapToObj(i -> new AjaxChannelBroker.Delivery(CACHED, i))
                .toList());
    }

    @Test
    void unsubscribeStopsDelivery() {
        AjaxChannelBroker broker = broker(new MockEnvironment());
        String staying = broker.handshake();
        String leaving = broker.handshake();
        broker.subscribe(staying, OUTBOUND);
        broker.subscribe(leaving, OUTBOUND);

        broker.unsubscribe(leaving, OUTBOUND);
        broker.publish(OUTBOUND, "m1");

        assertThat(broker.drain(leaving)).isEmpty();
        assertThat(broker.drain(staying)).containsExactly(new AjaxChannelBroker.Delivery(OUTBOUND, "m1"));

        broker.unsubscribe(staying, OUTBOUND);
        broker.publish(OUTBOUND, "m2");

        assertThat(broker.drain(staying)).isEmpty();
        assertThatCode(() -> {
            broker.unsubscribe(null, OUTBOUND);
            broker.unsubscribe(staying, null);
            broker.unsubscribe("0".repeat(32), OUTBOUND);
            broker.unsubscribe(staying, "/test/never-subscribed");
        }).doesNotThrowAnyException();
    }

    @Test
    void disconnectForgetsClient() throws Exception {
        AjaxChannelBroker broker = broker(new MockEnvironment());
        String client = broker.handshake();
        String other = broker.handshake();
        broker.subscribe(client, OUTBOUND);
        broker.subscribe(other, OUTBOUND);
        Waiter waiter = startWaiting(broker, client, WAIT_MS);

        broker.disconnect(client);

        assertThat(waiter.result()).isEmpty();
        assertThat(broker.isKnownClient(client)).isFalse();

        broker.publish(OUTBOUND, "m1");

        assertThat(broker.drain(client)).isEmpty();
        assertThat(assertTimeoutPreemptively(Duration.ofMillis(WAIT_MS / 2),
                () -> broker.awaitMessages(client, WAIT_MS))).isEmpty();
        assertThat(broker.drain(other)).containsExactly(new AjaxChannelBroker.Delivery(OUTBOUND, "m1"));
        assertThatThrownBy(() -> broker.subscribe(client, OUTBOUND)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> {
            broker.disconnect(client);
            broker.disconnect(null);
        }).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "00000000000000000000000000000000")
    void subscribeRejectsUnknownClient(String clientId) {
        AjaxChannelBroker broker = broker(new MockEnvironment());

        assertThatThrownBy(() -> broker.subscribe(clientId, OUTBOUND)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void connectReturnsQueuedDeliveriesAtOnceAndOtherwiseTimesOutEmpty() throws Exception {
        AjaxChannelBroker broker = broker(new MockEnvironment());
        String client = broker.handshake();
        broker.subscribe(client, OUTBOUND);

        broker.publish(OUTBOUND, "m1");
        broker.publish(OUTBOUND, "m2");

        assertThat(assertTimeoutPreemptively(Duration.ofMillis(WAIT_MS / 2),
                () -> broker.awaitMessages(client, WAIT_MS))).containsExactly(
                        new AjaxChannelBroker.Delivery(OUTBOUND, "m1"),
                        new AjaxChannelBroker.Delivery(OUTBOUND, "m2"));
        assertThat(broker.awaitMessages(client, 0)).isEmpty();
        assertThat(broker.awaitMessages(client, -1)).isEmpty();
        assertThat(broker.awaitMessages(client, 50)).isEmpty();
        assertThat(broker.awaitMessages(null, 50)).isEmpty();
        assertThat(broker.drain(null)).isEmpty();
    }

    @Test
    void heldConnectIsReleasedByAPublication() throws Exception {
        AjaxChannelBroker broker = broker(new MockEnvironment());
        String client = broker.handshake();
        broker.subscribe(client, OUTBOUND);
        Waiter waiter = startWaiting(broker, client, WAIT_MS);

        broker.publish(OUTBOUND, "m1");

        assertThat(waiter.result()).containsExactly(new AjaxChannelBroker.Delivery(OUTBOUND, "m1"));
    }

    @Test
    void newerConnectReleasesTheOlderOneEmptyAndReceivesTheDeliveries() throws Exception {
        AjaxChannelBroker broker = broker(new MockEnvironment());
        String client = broker.handshake();
        broker.subscribe(client, OUTBOUND);
        Waiter older = startWaiting(broker, client, WAIT_MS);

        Waiter newer = startWaiting(broker, client, WAIT_MS);

        assertThat(older.result()).isEmpty();

        broker.publish(OUTBOUND, "m1");

        assertThat(newer.result()).containsExactly(new AjaxChannelBroker.Delivery(OUTBOUND, "m1"));
    }

    @Test
    void interruptedConnectThrowsInterruptedException() {
        AjaxChannelBroker broker = broker(new MockEnvironment());
        String client = broker.handshake();
        Waiter waiter = startWaiting(broker, client, WAIT_MS);

        waiter.thread().interrupt();

        assertThatThrownBy(waiter::result)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(InterruptedException.class);
    }

    @Test
    void idleClientExpiresWhileClientWithHeldConnectStaysKnown() throws Exception {
        AjaxChannelBroker broker = broker(new MockEnvironment().withProperty("ajax.max-interval-ms", "50"));
        // The idle client comes first, so no sweep runs between the waiting client's handshake and its connect.
        String idle = broker.handshake();
        String waiting = broker.handshake();
        Waiter waiter = startWaiting(broker, waiting, WAIT_MS);

        // Publishing runs the idle-client sweep, at most once per half max interval.
        await().atMost(Duration.ofMillis(WAIT_MS)).pollInterval(Duration.ofMillis(20)).until(() -> {
            broker.publish(OUTBOUND, "tick");
            return !broker.isKnownClient(idle);
        });

        assertThat(broker.isKnownClient(waiting)).isTrue();

        broker.disconnect(waiting);

        assertThat(waiter.result()).isEmpty();
    }

    @Test
    void shutdownReleasesConnectsInterruptsHandlersAndRefusesLaterPublications() throws Exception {
        AjaxChannelBroker broker = broker(handlerEnvironment());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        AtomicBoolean invokedAfterShutdown = new AtomicBoolean();
        broker.registerInbound(INBOUND, data -> {
            if (!"block".equals(data)) {
                invokedAfterShutdown.set(true);
                return null;
            }
            started.countDown();
            try {
                never.await(WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException expected) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return null;
        });
        String client = broker.handshake();
        Waiter waiter = startWaiting(broker, client, WAIT_MS);

        try {
            broker.onPublication(client, INBOUND, "block");
            assertThat(started.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();

            broker.shutdown();

            assertThat(interrupted.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(waiter.result()).isEmpty();
            assertThatCode(() -> broker.onPublication(client, INBOUND, "after")).doesNotThrowAnyException();
            assertThat(invokedAfterShutdown).isFalse();
        } finally {
            never.countDown();
        }
    }

    @Test
    void registerInboundRejectsSecondHandlerAndNullArguments() {
        AjaxChannelBroker broker = broker(new MockEnvironment());
        AjaxChannelBroker.InboundHandler handler = data -> data;
        broker.registerInbound(INBOUND, handler);

        assertThatThrownBy(() -> broker.registerInbound(INBOUND, handler))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(INBOUND);
        assertThatThrownBy(() -> broker.registerInbound(null, handler)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> broker.registerInbound(OUTBOUND, null)).isInstanceOf(NullPointerException.class);
    }

    private AjaxChannelBroker broker(MockEnvironment environment) {
        AjaxChannelBroker broker = new AjaxChannelBroker(environment);
        brokers.add(broker);
        return broker;
    }

    private static MockEnvironment environment(Map<String, String> properties) {
        MockEnvironment environment = new MockEnvironment();
        properties.forEach(environment::setProperty);
        return environment;
    }

    private static MockEnvironment handlerEnvironment() {
        return new MockEnvironment().withProperty("ajax.response-timeout-ms", HANDLER_TIMEOUT_MS);
    }

    /** The reply channel {@code mule.rpc} subscribes to and names in its envelope: {@code <channel>#<clientId>}. */
    private static String replyChannel(String clientId) {
        return INBOUND + "#" + clientId;
    }

    /** The publication {@code mule.rpc} sends: {@code {"data": <data>, "replyTo": <replyTo>}}. */
    private static Map<String, Object> rpc(Object data, String replyTo) {
        return Map.of("data", data, "replyTo", replyTo);
    }

    /**
     * Starts {@link AjaxChannelBroker#awaitMessages(String, long)} on its own thread and returns once the call is
     * waiting (or has already returned).
     */
    private static Waiter startWaiting(AjaxChannelBroker broker, String clientId, long timeoutMs) {
        FutureTask<List<AjaxChannelBroker.Delivery>> task =
                new FutureTask<>(() -> broker.awaitMessages(clientId, timeoutMs));
        Thread thread = new Thread(task, "await-messages-" + clientId);
        thread.setDaemon(true);
        thread.start();
        await().atMost(Duration.ofMillis(WAIT_MS)).pollInterval(Duration.ofMillis(5))
                .until(() -> task.isDone() || thread.getState() == Thread.State.TIMED_WAITING);
        return new Waiter(task, thread);
    }

    /** A connect waiting on its own thread. */
    private record Waiter(FutureTask<List<AjaxChannelBroker.Delivery>> task, Thread thread) {

        List<AjaxChannelBroker.Delivery> result() throws Exception {
            return task.get(WAIT_MS, TimeUnit.MILLISECONDS);
        }
    }
}
