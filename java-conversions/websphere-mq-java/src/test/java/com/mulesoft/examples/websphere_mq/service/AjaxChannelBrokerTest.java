package com.mulesoft.examples.websphere_mq.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Unit tests of {@link AjaxChannelBroker} on neutral channels: {@code /test/in} (inbound, with a handler),
 * {@code /test/out} (outbound) and {@code /test/cached} (outbound, held for the first subscriber where configured).
 *
 * <p>No Spring context and no network: each broker is built from a {@link MockEnvironment} and shut down after the
 * test. Asynchronous outcomes are awaited through {@link AjaxChannelBroker#awaitMessages(String, long)}, latches or
 * Awaitility; a check that nothing arrives listens for {@link #QUIET_MS}.
 *
 * <p>The broker's log is captured at DEBUG with a Logback {@link ListAppender}, and the logger's level is restored
 * after each test. Every check of the captured events also asserts that none breaks a line or carries a throwable.
 * Logback is the logging backend every Spring Boot project carries through its starters, so this test is copied
 * between the projects sharing the broker with only its {@code package} line changed.
 */
class AjaxChannelBrokerTest {

    private static final String INBOUND = "/test/in";
    private static final String OUTBOUND = "/test/out";
    private static final String CACHED = "/test/cached";

    /** A channel nobody subscribes to, whose publications only run the idle-client sweep. */
    private static final String TICK = "/test/tick";

    /** Upper bound for anything the broker completes asynchronously, generous for a loaded build host. */
    private static final long WAIT_MS = 10_000;

    /** How long a check that no message arrives keeps listening. */
    private static final long QUIET_MS = 300;

    /** {@code ajax.response-timeout-ms} for brokers whose handlers are expected to answer. */
    private static final String HANDLER_TIMEOUT_MS = "2000";

    /** The characters a log viewer may break a line at; no formatted message may contain one. */
    private static final char[] LINE_BREAKS = {'\r', '\n', '\u0085', '\u2028', '\u2029'};

    /** Stands for payload content a handler's exception text may repeat; no logged event may contain it. */
    private static final String SECRET = "payload-secret-sentinel";

    /** A channel name a client could publish on to forge a log line. */
    private static final String FORGED_CHANNEL = "/x\nFORGED";

    private static final String FORGED_CHANNEL_RENDERING = "/x\\u000aFORGED";

    /** A client ID a client could send to forge a log line. */
    private static final String FORGED_CLIENT = "client\r\nFORGED";

    private static final String FORGED_CLIENT_RENDERING = "client\\u000d\\u000aFORGED";

    private final List<AjaxChannelBroker> brokers = new ArrayList<>();

    private Logger brokerLogger;

    private Level previousLevel;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureBrokerLog() {
        brokerLogger = (Logger) LoggerFactory.getLogger(AjaxChannelBroker.class);
        previousLevel = brokerLogger.getLevel();
        brokerLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        brokerLogger.addAppender(appender);
    }

    @AfterEach
    void shutDownBrokers() {
        try {
            brokers.forEach(AjaxChannelBroker::shutdown);
        } finally {
            brokerLogger.detachAppender(appender);
            appender.stop();
            brokerLogger.setLevel(previousLevel);
        }
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
                // Ignoring the timeout's interrupt, the handler returns a result long after the timeout.
                awaitIgnoringInterrupts(release);
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
    void handlerBlockedPastTheResponseTimeoutIsInterruptedAndSendsNoReply() throws Exception {
        AjaxChannelBroker broker = broker(new MockEnvironment().withProperty("ajax.response-timeout-ms", "100"));
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        broker.registerInbound(INBOUND, data -> {
            try {
                never.await(WAIT_MS, TimeUnit.MILLISECONDS);
                return "late:" + data;
            } catch (InterruptedException expected) {
                interrupted.countDown();
                throw expected;
            }
        });
        String client = broker.handshake();
        String replyTo = replyChannel(client);
        broker.subscribe(client, replyTo);

        try {
            broker.onPublication(client, INBOUND, rpc("hello", replyTo));

            assertThat(interrupted.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(broker.awaitMessages(client, QUIET_MS)).isEmpty();
        } finally {
            never.countDown();
        }
        // The timeout is logged once; the exception the interrupted handler threw afterwards is discarded.
        String timeoutLine = "Ajax handler for channel " + INBOUND + " did not respond within 100 ms";
        await().atMost(Duration.ofMillis(WAIT_MS)).until(() -> loggedMessages(Level.INFO).contains(timeoutLine));
        assertThat(broker.awaitMessages(client, QUIET_MS)).isEmpty();
        assertThat(loggedMessages(Level.INFO)).containsExactly(timeoutLine);
        assertThat(loggedMessages(Level.ERROR)).isEmpty();
    }

    @Test
    void atMostMaxRunningHandlersRunAndCallsStillWaitingAtTheirTimeoutNeverStart() throws Exception {
        int maxRunning = AjaxChannelBroker.MAX_RUNNING_HANDLERS;
        int waiting = 4;
        // A response timeout the later probe call can meet even on a loaded host.
        AjaxChannelBroker broker = broker(handlerEnvironment());
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger invocations = new AtomicInteger();
        Set<String> threadNames = ConcurrentHashMap.newKeySet();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch probed = new CountDownLatch(1);
        broker.registerInbound(INBOUND, data -> {
            if ("probe".equals(data)) {
                probed.countDown();
                return null;
            }
            invocations.incrementAndGet();
            threadNames.add(Thread.currentThread().getName());
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                awaitIgnoringInterrupts(release);
                return "late:" + data;
            } finally {
                running.decrementAndGet();
            }
        });
        String client = broker.handshake();
        String replyTo = replyChannel(client);
        broker.subscribe(client, replyTo);
        String timeoutLine = "Ajax handler for channel " + INBOUND + " did not respond within " + HANDLER_TIMEOUT_MS
                + " ms";

        try {
            IntStream.range(0, maxRunning + waiting)
                    .forEach(i -> broker.onPublication(client, INBOUND, rpc(i, replyTo)));

            // Every call times out: the running ones are interrupted, the waiting ones cancelled before they start.
            await().atMost(Duration.ofMillis(WAIT_MS)).pollInterval(Duration.ofMillis(20)).until(() ->
                    loggedMessages(Level.INFO).stream().filter(timeoutLine::equals).count() == maxRunning + waiting);
            // Ignoring the interrupt, the running handlers keep their threads until they return.
            assertThat(running).hasValue(maxRunning);
        } finally {
            release.countDown();
        }
        await().atMost(Duration.ofMillis(WAIT_MS)).until(() -> running.get() == 0);

        // A later call runs on a freed thread, behind any call still in the queue.
        broker.onPublication(client, INBOUND, rpc("probe", replyTo));

        assertThat(probed.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(broker.awaitMessages(client, QUIET_MS)).isEmpty();
        assertThat(invocations).hasValue(maxRunning);
        assertThat(peak).hasValue(maxRunning);
        assertThat(threadNames).hasSize(maxRunning).allMatch(name -> name.matches("ajax-channel-broker-\\d+"));
    }

    @Test
    void publicationFindingEveryHandlerThreadBusyAndTheQueueFullIsRefusedAndNeverRetried() throws Exception {
        int admitted = AjaxChannelBroker.MAX_RUNNING_HANDLERS + AjaxChannelBroker.MAX_QUEUED_HANDLER_CALLS;
        AjaxChannelBroker broker = broker(new MockEnvironment().withProperty("ajax.response-timeout-ms", "60000"));
        AtomicInteger invocations = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        broker.registerInbound(INBOUND, data -> {
            invocations.incrementAndGet();
            release.await(WAIT_MS, TimeUnit.MILLISECONDS);
            return null;
        });
        String client = broker.handshake();

        try {
            IntStream.range(0, admitted + 1).forEach(i -> broker.onPublication(client, INBOUND, "call-" + i));

            assertThat(loggedMessages(Level.INFO)).containsExactly("Ajax handler for channel " + INBOUND
                    + " failed: all handler threads are busy and the waiting queue is full");
        } finally {
            release.countDown();
        }
        await().atMost(Duration.ofMillis(WAIT_MS)).until(() -> invocations.get() == admitted);
        assertThat(broker.awaitMessages(client, QUIET_MS)).isEmpty();
        assertThat(invocations).hasValue(admitted);
    }

    @Test
    void handlerFailureIsLoggedByTypeAndThrowSiteOnly() throws Exception {
        AjaxChannelBroker broker = broker(handlerEnvironment());
        broker.registerInbound(INBOUND, data -> {
            throw sensitiveFailure();
        });
        String client = broker.handshake();
        String replyTo = replyChannel(client);
        broker.subscribe(client, replyTo);

        broker.onPublication(client, INBOUND, rpc(SECRET, replyTo));

        await().atMost(Duration.ofMillis(WAIT_MS)).until(() -> loggedMessages(Level.DEBUG).stream()
                .anyMatch(message -> message.startsWith("Ajax handler failure on channel ")));
        assertThat(broker.awaitMessages(client, QUIET_MS)).isEmpty();
        String throwSite = Pattern.quote(AjaxChannelBrokerTest.class.getName()
                + ".sensitiveFailure(AjaxChannelBrokerTest.java:") + "\\d+\\)";
        assertThat(loggedMessages(Level.ERROR)).singleElement().asString().matches(
                Pattern.quote("Ajax handler for channel " + INBOUND + " failed: java.lang.IllegalStateException at ")
                        + throwSite + Pattern.quote(" <- java.io.IOException"));
        assertThat(loggedMessages(Level.INFO)).isEmpty();
        assertThat(loggedMessages(Level.DEBUG)).filteredOn(message -> message.startsWith("Ajax handler failure"))
                .singleElement().asString().matches(
                        Pattern.quote("Ajax handler failure on channel " + INBOUND
                                + ": java.lang.IllegalStateException at ") + throwSite
                                + Pattern.quote(" <- java.io.IOException at ") + throwSite);
        assertThat(loggedEvents()).allSatisfy(event -> assertThat(event.getFormattedMessage())
                .doesNotContain(SECRET, "forged-by-", "IllegalArgumentException"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("handlerFailureDescriptions")
    void handlerFailureIsDescribedByTypeIdentifiersOnly(String description, Exception failure, String errorDescription,
            String debugDescription) {
        AjaxChannelBroker broker = broker(handlerEnvironment());
        broker.registerInbound(INBOUND, data -> {
            throw failure;
        });

        broker.onPublication(null, INBOUND, "payload");

        String debugLine = "Ajax handler failure on channel " + INBOUND + ": " + debugDescription;
        await().atMost(Duration.ofMillis(WAIT_MS)).until(() -> loggedMessages(Level.DEBUG).contains(debugLine));
        assertThat(loggedMessages(Level.ERROR))
                .containsExactly("Ajax handler for channel " + INBOUND + " failed: " + errorDescription);
        assertThat(loggedMessages(Level.INFO)).isEmpty();
        assertThat(loggedEvents()).allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(SECRET));
    }

    static Stream<Arguments> handlerFailureDescriptions() {
        IllegalStateException circularOuter = withStackTrace(new IllegalStateException(SECRET));
        IOException circularInner = withStackTrace(new IOException(SECRET));
        circularOuter.initCause(circularInner);
        circularInner.initCause(circularOuter);
        IllegalStateException withCauseSites = withStackTrace(
                new IllegalStateException(SECRET, withStackTrace(new IOException(SECRET),
                        new StackTraceElement("a.b.Reader", "read", "Reader.java", 7))),
                new StackTraceElement("a.b.Parser", "next", "Parser.java", 42));
        String evilFrame =
                "java.lang.IllegalStateException at a.b.Evil\\u000aINFO forged.next\\u000d(Evil\\u2028.java:7)";
        String type = "java.lang.RuntimeException";
        String cause = " <- java.lang.RuntimeException";
        return Stream.of(
                Arguments.of("checked exception without stack trace", withStackTrace(new IOException(SECRET)),
                        "java.io.IOException", "java.io.IOException"),
                Arguments.of("source frame with line", withStackTrace(new IllegalStateException(SECRET),
                                new StackTraceElement("a.b.Parser", "next", "Parser.java", 42)),
                        "java.lang.IllegalStateException at a.b.Parser.next(Parser.java:42)",
                        "java.lang.IllegalStateException at a.b.Parser.next(Parser.java:42)"),
                Arguments.of("source frame without line", withStackTrace(new IllegalStateException(SECRET),
                                new StackTraceElement("a.b.Parser", "next", "Parser.java", -1)),
                        "java.lang.IllegalStateException at a.b.Parser.next(Parser.java)",
                        "java.lang.IllegalStateException at a.b.Parser.next(Parser.java)"),
                Arguments.of("frame without source file", withStackTrace(new IllegalStateException(SECRET),
                                new StackTraceElement("a.b.Parser", "next", null, 42)),
                        "java.lang.IllegalStateException at a.b.Parser.next(Unknown Source)",
                        "java.lang.IllegalStateException at a.b.Parser.next(Unknown Source)"),
                Arguments.of("native frame", withStackTrace(new IllegalStateException(SECRET),
                                new StackTraceElement("a.b.Parser", "read", "Parser.java", -2)),
                        "java.lang.IllegalStateException at a.b.Parser.read(Native Method)",
                        "java.lang.IllegalStateException at a.b.Parser.read(Native Method)"),
                Arguments.of("frame names with line breaks", withStackTrace(new IllegalStateException(SECRET),
                                new StackTraceElement("a.b.Evil\nINFO forged", "next\r", "Evil\u2028.java", 7)),
                        evilFrame, evilFrame),
                Arguments.of("cause throw sites only at DEBUG", withCauseSites,
                        "java.lang.IllegalStateException at a.b.Parser.next(Parser.java:42) <- java.io.IOException",
                        "java.lang.IllegalStateException at a.b.Parser.next(Parser.java:42)"
                                + " <- java.io.IOException at a.b.Reader.read(Reader.java:7)"),
                Arguments.of("circular cause chain", circularOuter,
                        "java.lang.IllegalStateException <- java.io.IOException <- [circular]",
                        "java.lang.IllegalStateException <- java.io.IOException <- [circular]"),
                Arguments.of("ten causes, all named", causeChain(11), type + cause.repeat(10),
                        type + cause.repeat(10)),
                Arguments.of("eleven causes, cut after ten", causeChain(12), type + cause.repeat(10) + " <- ...",
                        type + cause.repeat(10) + " <- ..."),
                Arguments.of("stack trace and cause that throw when read", new UnreadableException(),
                        UnreadableException.class.getName() + " at [unavailable] <- [unavailable]",
                        UnreadableException.class.getName() + " at [unavailable] <- [unavailable]"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("forgedIdentifierDiagnostics")
    void identifiersAreEscapedAndBoundedOnEveryDiagnostic(String description, Map<String, String> properties,
            BrokerScenario scenario, Level level, String expected) throws Exception {
        AjaxChannelBroker broker = broker(environment(properties));

        scenario.run(broker);

        await().atMost(Duration.ofMillis(WAIT_MS)).until(() -> loggedMessages(level).contains(expected));
        assertThat(loggedEvents()).allSatisfy(event -> assertThat(event.getFormattedMessage())
                .doesNotContain(SECRET)
                .doesNotContain(FORGED_CHANNEL)
                .doesNotContain(FORGED_CLIENT));
    }

    static Stream<Arguments> forgedIdentifierDiagnostics() {
        String timeout = "ajax.response-timeout-ms";
        AjaxChannelBroker.InboundHandler failing = data -> {
            throw withStackTrace(new IllegalStateException(SECRET + "\nINFO forged-by-message"));
        };
        String controls = "/c\t\\\u0000\u007f\u0085\u2028\u2029\u202e\uD83D\uDE00\uD800";
        String controlsRendering = "/c\\u0009\\u005c\\u0000\\u007f\\u0085\\u2028\\u2029\\u202e\uD83D\uDE00\\ud800";
        return Stream.of(
                Arguments.of("dispatch", Map.of(), (BrokerScenario) broker -> {
                    broker.registerInbound(FORGED_CHANNEL, data -> null);
                    broker.onPublication(FORGED_CLIENT, FORGED_CHANNEL, SECRET);
                }, Level.DEBUG, "Ajax publication from client " + FORGED_CLIENT_RENDERING
                        + " dispatched to the handler of channel " + FORGED_CHANNEL_RENDERING),
                Arguments.of("drop on an unknown channel", Map.of(),
                        (BrokerScenario) broker -> broker.onPublication(FORGED_CLIENT, FORGED_CHANNEL, SECRET),
                        Level.DEBUG, "No subscriber on Ajax channel " + FORGED_CHANNEL_RENDERING + "; message dropped"),
                Arguments.of("timeout", Map.of(timeout, "100"), (BrokerScenario) broker -> {
                    // Blocks on a latch nobody releases; the 100 ms response timeout interrupts it.
                    CountDownLatch never = new CountDownLatch(1);
                    broker.registerInbound(FORGED_CHANNEL, data -> {
                        never.await(WAIT_MS, TimeUnit.MILLISECONDS);
                        return null;
                    });
                    broker.onPublication(FORGED_CLIENT, FORGED_CHANNEL, SECRET);
                }, Level.INFO, "Ajax handler for channel " + FORGED_CHANNEL_RENDERING
                        + " did not respond within 100 ms"),
                Arguments.of("failure", Map.of(timeout, HANDLER_TIMEOUT_MS), (BrokerScenario) broker -> {
                    broker.registerInbound(FORGED_CHANNEL, failing);
                    broker.onPublication(FORGED_CLIENT, FORGED_CHANNEL, SECRET);
                }, Level.ERROR, "Ajax handler for channel " + FORGED_CHANNEL_RENDERING
                        + " failed: java.lang.IllegalStateException"),
                Arguments.of("failure detail", Map.of(timeout, HANDLER_TIMEOUT_MS), (BrokerScenario) broker -> {
                    broker.registerInbound(FORGED_CHANNEL, failing);
                    broker.onPublication(FORGED_CLIENT, FORGED_CHANNEL, SECRET);
                }, Level.DEBUG, "Ajax handler failure on channel " + FORGED_CHANNEL_RENDERING
                        + ": java.lang.IllegalStateException"),
                Arguments.of("refusal after shutdown", Map.of(), (BrokerScenario) broker -> {
                    broker.registerInbound(FORGED_CHANNEL, data -> null);
                    broker.shutdown();
                    broker.onPublication(FORGED_CLIENT, FORGED_CHANNEL, SECRET);
                }, Level.INFO, "Ajax handler for channel " + FORGED_CHANNEL_RENDERING
                        + " failed: the broker has been shut down"),
                Arguments.of("full hold", Map.of("ajax.cache-messages-channels[0]", FORGED_CHANNEL),
                        (BrokerScenario) broker -> IntStream.rangeClosed(0, AjaxChannelBroker.MAX_HELD_MESSAGES)
                                .forEach(i -> broker.publish(FORGED_CHANNEL, SECRET)),
                        Level.WARN, "Ajax channel " + FORGED_CHANNEL_RENDERING + " already holds "
                                + AjaxChannelBroker.MAX_HELD_MESSAGES
                                + " messages for its first subscriber; message dropped"),
                Arguments.of("control, separator, format and unpaired surrogate characters", Map.of(),
                        (BrokerScenario) broker -> broker.publish(controls, SECRET),
                        Level.DEBUG, "No subscriber on Ajax channel " + controlsRendering + "; message dropped"),
                Arguments.of("over-long channel", Map.of(),
                        (BrokerScenario) broker -> broker.publish("/" + "a".repeat(300), SECRET),
                        Level.DEBUG, "No subscriber on Ajax channel /" + "a".repeat(255)
                                + "...[truncated]; message dropped"),
                Arguments.of("over-long channel cut before an escape", Map.of(),
                        (BrokerScenario) broker -> broker.publish("/" + "\n".repeat(100), SECRET),
                        Level.DEBUG, "No subscriber on Ajax channel /" + "\\u000a".repeat(42)
                                + "...[truncated]; message dropped"));
    }

    @Test
    void escapingLeavesChannelsAndReplyChannelsUnchangedAsRoutingKeys() throws Exception {
        AjaxChannelBroker broker = broker(handlerEnvironment());
        broker.registerInbound(FORGED_CHANNEL, data -> "echo:" + data);
        String client = broker.handshake();
        String replyTo = FORGED_CHANNEL + "#" + client;
        broker.subscribe(client, FORGED_CHANNEL + "/out");
        broker.subscribe(client, replyTo);

        broker.publish(FORGED_CHANNEL + "/out", "m1");

        assertThat(broker.drain(client)).containsExactly(new AjaxChannelBroker.Delivery(FORGED_CHANNEL + "/out", "m1"));

        broker.onPublication(client, FORGED_CHANNEL, rpc("hello", replyTo));

        assertThat(broker.awaitMessages(client, WAIT_MS))
                .containsExactly(new AjaxChannelBroker.Delivery(replyTo, "echo:hello"));
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
        assertThat(broker.drain((String) null)).isEmpty();
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("currentDeliveryWaits")
    void awaitDeliveriesEndsCurrentAndTakesNothing(String description, boolean published, long timeoutMs) {
        AjaxChannelBroker broker = broker(new MockEnvironment());
        String client = broker.handshake();
        broker.subscribe(client, OUTBOUND);
        if (published) {
            broker.publish(OUTBOUND, "m1");
        }
        AjaxChannelBroker.ConnectClaim claim = broker.claimConnect(client);

        assertThat(assertTimeoutPreemptively(Duration.ofMillis(WAIT_MS / 2),
                () -> broker.awaitDeliveries(claim, timeoutMs))).isTrue();
        assertThat(broker.drain(claim)).isEqualTo(published
                ? List.of(new AjaxChannelBroker.Delivery(OUTBOUND, "m1"))
                : List.of());
    }

    static Stream<Arguments> currentDeliveryWaits() {
        return Stream.of(
                Arguments.of("deliveries already queued", true, WAIT_MS),
                Arguments.of("the time passed", false, 50L),
                Arguments.of("no time to wait", false, 0L));
    }

    @ParameterizedTest(name = "released by {0}")
    @MethodSource("staleDeliveryWaitReleases")
    void awaitDeliveriesEndsStaleWhenReleasedBeforeAnyDelivery(String description, ClientAction release)
            throws Exception {
        AjaxChannelBroker broker = broker(new MockEnvironment());
        String client = broker.handshake();
        broker.subscribe(client, OUTBOUND);
        DeliveryWait older = startAwaitingDeliveries(broker, broker.claimConnect(client), WAIT_MS);

        release.apply(broker, client);

        assertThat(older.result()).isFalse();
    }

    static Stream<Arguments> staleDeliveryWaitReleases() {
        return Stream.of(
                Arguments.of("a newer connect", (ClientAction) AjaxChannelBroker::claimConnect),
                Arguments.of("a newer awaitMessages call",
                        (ClientAction) (broker, clientId) -> startWaiting(broker, clientId, WAIT_MS)),
                Arguments.of("disconnect", (ClientAction) AjaxChannelBroker::disconnect),
                Arguments.of("shutdown", (ClientAction) (broker, clientId) -> broker.shutdown()));
    }

    @Test
    void connectSupersededAfterItsWaitEndedCurrentTakesNothingAndTheNewerConnectTakesTheDelivery()
            throws Exception {
        AjaxChannelBroker broker = broker(new MockEnvironment());
        String client = broker.handshake();
        broker.subscribe(client, OUTBOUND);
        AjaxChannelBroker.ConnectClaim older = broker.claimConnect(client);
        assertThat(broker.awaitDeliveries(older, 0)).as("the older connect's wait ended current").isTrue();

        AjaxChannelBroker.ConnectClaim newer = broker.claimConnect(client);
        broker.publish(OUTBOUND, "m1");

        assertThat(broker.drain(older)).as("the superseded connect takes nothing").isEmpty();
        assertThat(broker.drain(newer)).containsExactly(new AjaxChannelBroker.Delivery(OUTBOUND, "m1"));
        assertThat(broker.drain(newer)).isEmpty();
    }

    @ParameterizedTest(name = "removed by {0}")
    @MethodSource("claimedClientRemovals")
    void claimOfARemovedClientWaitsForNothingAndTakesNothing(String description, ClientAction removal) {
        AjaxChannelBroker broker = broker(new MockEnvironment());
        String client = broker.handshake();
        broker.subscribe(client, OUTBOUND);
        AjaxChannelBroker.ConnectClaim claim = broker.claimConnect(client);
        broker.publish(OUTBOUND, "m1");

        removal.apply(broker, client);

        assertThat(assertTimeoutPreemptively(Duration.ofMillis(WAIT_MS / 2),
                () -> broker.awaitDeliveries(claim, WAIT_MS))).isFalse();
        assertThat(broker.drain(claim)).isEmpty();
    }

    static Stream<Arguments> claimedClientRemovals() {
        return Stream.of(
                Arguments.of("disconnect", (ClientAction) AjaxChannelBroker::disconnect),
                Arguments.of("shutdown", (ClientAction) (broker, clientId) -> broker.shutdown()));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "00000000000000000000000000000000")
    void claimOfAnUnknownClientEndsStaleAtOnceAndTakesNothing(String clientId) {
        AjaxChannelBroker broker = broker(new MockEnvironment());
        AjaxChannelBroker.ConnectClaim claim = broker.claimConnect(clientId);

        assertThat(assertTimeoutPreemptively(Duration.ofMillis(WAIT_MS / 2),
                () -> broker.awaitDeliveries(claim, WAIT_MS))).isFalse();
        assertThat(broker.drain(claim)).isEmpty();
        assertThat(broker.isKnownClient(clientId)).isFalse();
    }

    @Test
    void nullClaimIsRejected() {
        AjaxChannelBroker broker = broker(new MockEnvironment());

        assertThatThrownBy(() -> broker.awaitDeliveries(null, 0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> broker.drain((AjaxChannelBroker.ConnectClaim) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void interruptedDeliveryWaitThrowsInterruptedExceptionAndKeepsTheClient() throws Exception {
        AjaxChannelBroker broker = broker(new MockEnvironment());
        String client = broker.handshake();
        DeliveryWait wait = startAwaitingDeliveries(broker, broker.claimConnect(client), WAIT_MS);

        wait.thread().interrupt();

        assertThatThrownBy(wait::result)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(InterruptedException.class);
        assertThat(broker.isKnownClient(client)).isTrue();
    }

    @Test
    void restoredDeliveriesWakeTheWaitingConnectAndComeFirstInTheirOrder() throws Exception {
        AjaxChannelBroker broker = broker(new MockEnvironment());
        String client = broker.handshake();
        broker.subscribe(client, OUTBOUND);
        broker.publish(OUTBOUND, "m1");
        List<AjaxChannelBroker.Delivery> undelivered = broker.drain(client);
        AjaxChannelBroker.ConnectClaim claim = broker.claimConnect(client);
        DeliveryWait waiting = startAwaitingDeliveries(broker, claim, WAIT_MS);

        broker.restore(client, undelivered);

        assertThat(waiting.result()).isTrue();
        broker.publish(OUTBOUND, "m2");
        undelivered = broker.drain(claim);
        broker.publish(OUTBOUND, "m3");

        broker.restore(client, undelivered);

        assertThat(broker.drain(client)).containsExactly(
                new AjaxChannelBroker.Delivery(OUTBOUND, "m1"),
                new AjaxChannelBroker.Delivery(OUTBOUND, "m2"),
                new AjaxChannelBroker.Delivery(OUTBOUND, "m3"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("clientsWithoutAQueue")
    void restoreForAClientWithoutAQueueDropsTheDeliveries(String description, ClientSupplier supplier) {
        AjaxChannelBroker broker = broker(new MockEnvironment());
        String clientId = supplier.get(broker);

        assertThatCode(() -> broker.restore(clientId, List.of(new AjaxChannelBroker.Delivery(OUTBOUND, "m1"))))
                .doesNotThrowAnyException();

        assertThat(broker.isKnownClient(clientId)).isFalse();
        assertThat(broker.drain(clientId)).isEmpty();
        assertThat(loggedMessages(Level.WARN)).isEmpty();
    }

    static Stream<Arguments> clientsWithoutAQueue() {
        return Stream.of(
                Arguments.of("null client", (ClientSupplier) broker -> null),
                Arguments.of("never registered", (ClientSupplier) broker -> "0".repeat(32)),
                Arguments.of("disconnected", (ClientSupplier) broker -> {
                    String clientId = broker.handshake();
                    broker.disconnect(clientId);
                    return clientId;
                }));
    }

    @Test
    void restoreOfNothingLeavesTheQueueUnchangedAndNullIsRejected() {
        AjaxChannelBroker broker = broker(new MockEnvironment());
        String client = broker.handshake();
        broker.subscribe(client, OUTBOUND);
        broker.publish(OUTBOUND, "m1");

        broker.restore(client, List.of());

        assertThatThrownBy(() -> broker.restore(client, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> broker.restore(client, Arrays.asList((AjaxChannelBroker.Delivery) null)))
                .isInstanceOf(NullPointerException.class);
        assertThat(broker.drain(client)).containsExactly(new AjaxChannelBroker.Delivery(OUTBOUND, "m1"));
    }

    @ParameterizedTest(name = "{0} restored onto a queue one below its bound")
    @CsvSource({"1, true", "2, false"})
    void restoreIsBoundedByTheQueueAndAnOverflowRemovesTheClient(int restored, boolean kept) {
        AjaxChannelBroker broker = broker(new MockEnvironment());
        String client = broker.handshake();
        broker.subscribe(client, OUTBOUND);
        IntStream.range(0, AjaxChannelBroker.MAX_QUEUED_DELIVERIES - 1).forEach(i -> broker.publish(OUTBOUND, i));
        List<AjaxChannelBroker.Delivery> undelivered = IntStream.range(0, restored)
                .mapToObj(i -> new AjaxChannelBroker.Delivery(OUTBOUND, "restored-" + i))
                .toList();

        broker.restore(client, undelivered);

        assertThat(broker.isKnownClient(client)).isEqualTo(kept);
        if (kept) {
            assertThat(broker.drain(client)).hasSize(AjaxChannelBroker.MAX_QUEUED_DELIVERIES)
                    .startsWith(new AjaxChannelBroker.Delivery(OUTBOUND, "restored-0"));
            assertThat(loggedMessages(Level.WARN)).isEmpty();
        } else {
            assertThat(broker.drain(client)).isEmpty();
            assertThat(loggedMessages(Level.WARN)).containsExactly(overflowWarning(client));
        }
    }

    @ParameterizedTest(name = "connect held by {0}")
    @MethodSource("heldConnects")
    void idleClientExpiresWhileClientWithHeldConnectStaysKnown(String description, HeldConnectStarter heldConnect,
            Object releasedWith) throws Exception {
        AjaxChannelBroker broker = broker(new MockEnvironment().withProperty("ajax.max-interval-ms", "50"));
        // The idle client comes first, so no sweep runs between the waiting client's handshake and its connect.
        String idle = broker.handshake();
        String waiting = broker.handshake();
        Future<?> held = heldConnect.start(broker, waiting);

        // Publishing runs the idle-client sweep, at most once per half max interval.
        await().atMost(Duration.ofMillis(WAIT_MS)).pollInterval(Duration.ofMillis(20)).until(() -> {
            broker.publish(OUTBOUND, "tick");
            return !broker.isKnownClient(idle);
        });

        assertThat(broker.isKnownClient(waiting)).isTrue();

        broker.disconnect(waiting);

        assertThat(held.get(WAIT_MS, TimeUnit.MILLISECONDS)).isEqualTo(releasedWith);
    }

    static Stream<Arguments> heldConnects() {
        return Stream.of(
                Arguments.of("awaitMessages",
                        (HeldConnectStarter) (broker, clientId) -> startWaiting(broker, clientId, WAIT_MS).task(),
                        List.of()),
                Arguments.of("awaitDeliveries",
                        (HeldConnectStarter) (broker, clientId) ->
                                startAwaitingDeliveries(broker, broker.claimConnect(clientId), WAIT_MS).task(),
                        false));
    }

    @ParameterizedTest(name = "removed by {0}")
    @MethodSource("clientRemovals")
    void removedClientCannotTakeTheHoldWhichReachesTheNextSubscriberInOrder(String description,
            Map<String, String> properties, ClientAction removal) {
        AjaxChannelBroker broker = broker(environment(properties));
        String removed = broker.handshake();
        broker.subscribe(removed, OUTBOUND);
        broker.publish(CACHED, "m1");

        removal.apply(broker, removed);

        assertThat(broker.isKnownClient(removed)).isFalse();
        broker.publish(CACHED, "m2");
        assertThatThrownBy(() -> broker.subscribe(removed, CACHED)).isInstanceOf(IllegalArgumentException.class);
        broker.publish(CACHED, "m3");
        String live = broker.handshake();
        broker.subscribe(live, CACHED);

        assertThat(broker.drain(live)).containsExactly(
                new AjaxChannelBroker.Delivery(CACHED, "m1"),
                new AjaxChannelBroker.Delivery(CACHED, "m2"),
                new AjaxChannelBroker.Delivery(CACHED, "m3"));
        assertThat(broker.drain(removed)).isEmpty();
    }

    static Stream<Arguments> clientRemovals() {
        return Stream.of(
                Arguments.of("disconnect", Map.of("ajax.cache-messages-channels", CACHED),
                        (ClientAction) AjaxChannelBroker::disconnect),
                Arguments.of("idle expiry",
                        Map.of("ajax.cache-messages-channels", CACHED, "ajax.max-interval-ms", "50"),
                        (ClientAction) (broker, clientId) -> await().atMost(Duration.ofMillis(WAIT_MS))
                                .pollInterval(Duration.ofMillis(20))
                                .until(() -> {
                                    broker.publish(TICK, "tick");
                                    return !broker.isKnownClient(clientId);
                                })));
    }

    @ParameterizedTest(name = "kept active by {0}")
    @MethodSource("clientActivities")
    void activeClientSurvivesTheSweepsThatExpireASilentOne(String description, ClientAction activity) {
        // Wide enough that a stalled test thread on a loaded host cannot let the active client's activity lapse.
        AjaxChannelBroker broker = broker(new MockEnvironment().withProperty("ajax.max-interval-ms", "500"));
        String silent = broker.handshake();
        String active = broker.handshake();

        // Each sweep runs in the publication right after the active client's latest activity.
        await().atMost(Duration.ofMillis(WAIT_MS)).pollInterval(Duration.ofMillis(20)).until(() -> {
            activity.apply(broker, active);
            broker.publish(TICK, "tick");
            return !broker.isKnownClient(silent);
        });

        assertThat(broker.isKnownClient(active)).isTrue();
    }

    static Stream<Arguments> clientActivities() {
        return Stream.of(
                Arguments.of("drain", (ClientAction) AjaxChannelBroker::drain),
                Arguments.of("connect claim", (ClientAction) AjaxChannelBroker::claimConnect),
                Arguments.of("subscribe", (ClientAction) (broker, clientId) -> broker.subscribe(clientId, OUTBOUND)),
                Arguments.of("unsubscribe",
                        (ClientAction) (broker, clientId) -> broker.unsubscribe(clientId, OUTBOUND)),
                Arguments.of("publication",
                        (ClientAction) (broker, clientId) -> broker.onPublication(clientId, TICK, "ping")));
    }

    @Test
    void racingSubscribeDisconnectAndCachedPublishLeaveNoSubscriptionForARemovedClient() throws Exception {
        AjaxChannelBroker broker = broker(environment(Map.of("ajax.cache-messages-channels", CACHED)));
        int clientsPerThread = 200;
        int messagesPerPublisher = 100;
        BlockingQueue<String> toDisconnect = new LinkedBlockingQueue<>();
        Queue<String> issued = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService racers = Executors.newFixedThreadPool(6);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int thread = 0; thread < 2; thread++) {
                tasks.add(racers.submit(() -> {
                    start.await();
                    for (int i = 0; i < clientsPerThread; i++) {
                        String clientId = broker.handshake();
                        issued.add(clientId);
                        toDisconnect.add(clientId);
                        try {
                            broker.subscribe(clientId, CACHED);
                        } catch (IllegalArgumentException disconnectedFirst) {
                            // The disconnect won the race; the client must then be gone for good.
                            assertThat(broker.isKnownClient(clientId)).isFalse();
                        }
                    }
                    return null;
                }));
                tasks.add(racers.submit(() -> {
                    start.await();
                    for (int i = 0; i < clientsPerThread; i++) {
                        broker.disconnect(toDisconnect.poll(WAIT_MS, TimeUnit.MILLISECONDS));
                    }
                    return null;
                }));
                int publisher = thread;
                tasks.add(racers.submit(() -> {
                    start.await();
                    for (int i = 0; i < messagesPerPublisher; i++) {
                        broker.publish(CACHED, "race-" + publisher + "-" + i);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) {
                // A publication meeting a subscription of a removed client would fail here.
                task.get(WAIT_MS, TimeUnit.MILLISECONDS);
            }
        } finally {
            racers.shutdownNow();
        }

        assertThat(issued).hasSize(2 * clientsPerThread).noneMatch(broker::isKnownClient);
        broker.publish(CACHED, "after-1");
        broker.publish(CACHED, "after-2");
        String live = broker.handshake();
        broker.subscribe(live, CACHED);

        List<AjaxChannelBroker.Delivery> received = broker.drain(live);
        // Whatever the race left held comes first, each publisher's messages in publish order, then the later ones.
        assertThat(received).endsWith(
                new AjaxChannelBroker.Delivery(CACHED, "after-1"),
                new AjaxChannelBroker.Delivery(CACHED, "after-2"));
        List<AjaxChannelBroker.Delivery> leftByRace = received.subList(0, received.size() - 2);
        for (int publisher = 0; publisher < 2; publisher++) {
            String prefix = "race-" + publisher + "-";
            assertThat(leftByRace.stream()
                    .map(delivery -> (String) delivery.data())
                    .filter(data -> data.startsWith(prefix))
                    .map(data -> Integer.valueOf(data.substring(prefix.length())))
                    .toList()).isSorted();
        }
        assertThat(leftByRace).allSatisfy(delivery -> assertThat((String) delivery.data()).startsWith("race-"));
    }

    @Test
    void subscriberThatNeverDrainsIsRemovedByTheDeliveryBeyondItsQueueBound() throws Exception {
        AjaxChannelBroker broker = broker(new MockEnvironment());
        int max = AjaxChannelBroker.MAX_QUEUED_DELIVERIES;
        String hoarder = broker.handshake();
        String reader = broker.handshake();
        broker.subscribe(hoarder, OUTBOUND);
        broker.subscribe(reader, OUTBOUND);
        List<AjaxChannelBroker.Delivery> read = new ArrayList<>();

        for (int i = 1; i <= max; i++) {
            broker.publish(OUTBOUND, i);
            // The hoarder keeps publishing, so inactivity alone would never remove it; it never connects or drains.
            broker.onPublication(hoarder, TICK, "ping");
            read.addAll(broker.drain(reader));
        }

        assertThat(broker.isKnownClient(hoarder)).isTrue();
        assertThat(loggedMessages(Level.WARN)).isEmpty();

        broker.publish(OUTBOUND, max + 1);

        assertThat(broker.isKnownClient(hoarder)).isFalse();
        assertThat(broker.drain(hoarder)).isEmpty();
        assertThat(assertTimeoutPreemptively(Duration.ofMillis(WAIT_MS / 2),
                () -> broker.awaitMessages(hoarder, WAIT_MS))).isEmpty();
        assertThat(loggedMessages(Level.WARN)).containsExactly(overflowWarning(hoarder));
        read.addAll(broker.drain(reader));
        assertThat(read).containsExactlyElementsOf(IntStream.rangeClosed(1, max + 1)
                .mapToObj(i -> new AjaxChannelBroker.Delivery(OUTBOUND, i))
                .toList());

        // The removed client's subscription went with it.
        broker.publish(OUTBOUND, "later");

        assertThat(broker.drain(reader)).containsExactly(new AjaxChannelBroker.Delivery(OUTBOUND, "later"));
        assertThat(broker.isKnownClient(reader)).isTrue();
    }

    @Test
    void cachedChannelWhoseOnlySubscriberOverflowedHoldsAgain() {
        AjaxChannelBroker broker = broker(environment(Map.of("ajax.cache-messages-channels", CACHED)));
        int max = AjaxChannelBroker.MAX_QUEUED_DELIVERIES;
        String hoarder = broker.handshake();
        broker.subscribe(hoarder, CACHED);
        IntStream.rangeClosed(1, max).forEach(i -> broker.publish(CACHED, i));

        broker.publish(CACHED, "overflowing");
        broker.publish(CACHED, "next");

        assertThat(broker.isKnownClient(hoarder)).isFalse();
        String subscriber = broker.handshake();
        broker.subscribe(subscriber, CACHED);
        assertThat(broker.drain(subscriber)).containsExactly(
                new AjaxChannelBroker.Delivery(CACHED, "overflowing"),
                new AjaxChannelBroker.Delivery(CACHED, "next"));
    }

    @Test
    void heldTransferFillingTheQueueExactlyIsAccepted() {
        AjaxChannelBroker broker = broker(environment(Map.of("ajax.cache-messages-channels", CACHED)));
        int alreadyQueued = AjaxChannelBroker.MAX_QUEUED_DELIVERIES - 2;
        broker.publish(CACHED, "h1");
        broker.publish(CACHED, "h2");
        String client = broker.handshake();
        broker.subscribe(client, OUTBOUND);
        IntStream.range(0, alreadyQueued).forEach(i -> broker.publish(OUTBOUND, i));

        broker.subscribe(client, CACHED);

        List<AjaxChannelBroker.Delivery> deliveries = broker.drain(client);
        assertThat(deliveries).hasSize(AjaxChannelBroker.MAX_QUEUED_DELIVERIES).endsWith(
                new AjaxChannelBroker.Delivery(CACHED, "h1"),
                new AjaxChannelBroker.Delivery(CACHED, "h2"));
        assertThat(broker.isKnownClient(client)).isTrue();
    }

    @Test
    void heldTransferBeyondTheQueueBoundRemovesTheClientAndKeepsTheHold() {
        AjaxChannelBroker broker = broker(environment(Map.of("ajax.cache-messages-channels", CACHED)));
        int alreadyQueued = AjaxChannelBroker.MAX_QUEUED_DELIVERIES - 1;
        broker.publish(CACHED, "h1");
        broker.publish(CACHED, "h2");
        String client = broker.handshake();
        broker.subscribe(client, OUTBOUND);
        IntStream.range(0, alreadyQueued).forEach(i -> broker.publish(OUTBOUND, i));

        assertThatThrownBy(() -> broker.subscribe(client, CACHED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(AjaxChannelBroker.MAX_QUEUED_DELIVERIES));

        assertThat(broker.isKnownClient(client)).isFalse();
        assertThat(broker.drain(client)).isEmpty();
        assertThat(loggedMessages(Level.WARN)).containsExactly(overflowWarning(client));
        String next = broker.handshake();
        broker.subscribe(next, CACHED);
        broker.subscribe(next, OUTBOUND);
        assertThat(broker.drain(next)).containsExactly(
                new AjaxChannelBroker.Delivery(CACHED, "h1"),
                new AjaxChannelBroker.Delivery(CACHED, "h2"));

        // The removed client's earlier subscription went with it.
        broker.publish(OUTBOUND, "later");

        assertThat(broker.drain(next)).containsExactly(new AjaxChannelBroker.Delivery(OUTBOUND, "later"));
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

    /**
     * Starts {@link AjaxChannelBroker#awaitDeliveries(AjaxChannelBroker.ConnectClaim, long)} on its own thread and
     * returns once the call is waiting (or has already returned).
     *
     * @param broker the broker
     * @param claim the connect's claim
     * @param timeoutMs the wait's timeout
     * @return the waiting call and its thread
     */
    private static DeliveryWait startAwaitingDeliveries(AjaxChannelBroker broker,
            AjaxChannelBroker.ConnectClaim claim, long timeoutMs) {
        FutureTask<Boolean> task = new FutureTask<>(() -> broker.awaitDeliveries(claim, timeoutMs));
        Thread thread = new Thread(task, "await-deliveries");
        thread.setDaemon(true);
        thread.start();
        await().atMost(Duration.ofMillis(WAIT_MS)).pollInterval(Duration.ofMillis(5))
                .until(() -> task.isDone() || thread.getState() == Thread.State.TIMED_WAITING);
        return new DeliveryWait(task, thread);
    }

    /** A connect waiting on its own thread without taking the deliveries. */
    private record DeliveryWait(FutureTask<Boolean> task, Thread thread) {

        boolean result() throws Exception {
            return task.get(WAIT_MS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Waits up to {@link #WAIT_MS} for a latch the way a handler that does not honour interruption blocks: an
     * interrupt is swallowed and the wait goes on.
     */
    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS);
        while (latch.getCount() > 0 && deadline - System.nanoTime() > 0) {
            try {
                latch.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            } catch (InterruptedException ignored) {
                // Deliberately swallowed: this handler keeps its thread whatever the broker does.
            }
        }
    }

    /** Something done to or by one client of a broker. */
    @FunctionalInterface
    interface ClientAction {

        void apply(AjaxChannelBroker broker, String clientId);
    }

    /** Starts a held connect of one client on its own thread and returns it once it is waiting. */
    @FunctionalInterface
    interface HeldConnectStarter {

        Future<?> start(AjaxChannelBroker broker, String clientId);
    }

    /** Yields a client ID of a broker, setting up whatever state the ID needs. */
    @FunctionalInterface
    interface ClientSupplier {

        String get(AjaxChannelBroker broker);
    }

    /** Steps that make a broker log one diagnostic. */
    @FunctionalInterface
    interface BrokerScenario {

        void run(AjaxChannelBroker broker) throws Exception;
    }

    /**
     * A handler failure whose message, cause message and suppressed message carry payload content and line breaks.
     */
    private static IllegalStateException sensitiveFailure() {
        IllegalStateException failure = new IllegalStateException(
                "Rejected " + SECRET + "\r\nINFO forged-by-message",
                new IOException("Upstream answered " + SECRET + "\nERROR forged-by-cause"));
        failure.addSuppressed(new IllegalArgumentException("Suppressed " + SECRET + "\nINFO forged-by-suppressed"));
        return failure;
    }

    /** Builds {@code length} runtime exceptions, each the cause of the one before, with empty stack traces. */
    private static Exception causeChain(int length) {
        RuntimeException outermost = withStackTrace(new RuntimeException(SECRET));
        RuntimeException current = outermost;
        for (int i = 1; i < length; i++) {
            RuntimeException cause = withStackTrace(new RuntimeException(SECRET));
            current.initCause(cause);
            current = cause;
        }
        return outermost;
    }

    private static <T extends Throwable> T withStackTrace(T failure, StackTraceElement... frames) {
        failure.setStackTrace(frames);
        return failure;
    }

    /** An exception whose stack trace and cause cannot be read, as a hostile or broken subclass may behave. */
    static final class UnreadableException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        UnreadableException() {
            super(SECRET);
        }

        @Override
        public StackTraceElement[] getStackTrace() {
            throw new IllegalStateException(SECRET);
        }

        @Override
        public synchronized Throwable getCause() {
            throw new IllegalStateException(SECRET);
        }
    }

    /** The WARN the broker logs when it removes a client whose queue overflowed. */
    private static String overflowWarning(String clientId) {
        return "Ajax client " + clientId + " exceeded " + AjaxChannelBroker.MAX_QUEUED_DELIVERIES
                + " queued deliveries without a connect; client removed with its subscriptions";
    }

    /**
     * Returns every broker event captured so far, after checking that none breaks a line or carries a throwable,
     * which Logback would print with its message, causes and suppressed exceptions.
     */
    private List<ILoggingEvent> loggedEvents() {
        List<ILoggingEvent> events;
        // Handler threads log concurrently; ListAppender appends while holding its own monitor.
        synchronized (appender) {
            events = new ArrayList<>(appender.list);
        }
        for (ILoggingEvent event : events) {
            String message = event.getFormattedMessage();
            for (char lineBreak : LINE_BREAKS) {
                assertThat(message.indexOf(lineBreak))
                        .as("line break U+%04X in %s", (int) lineBreak, message)
                        .isEqualTo(-1);
            }
            assertThat(event.getThrowableProxy()).as("throwable attached to %s", message).isNull();
        }
        return events;
    }

    /** The formatted messages of the captured broker events at one level, in logging order. */
    private List<String> loggedMessages(Level level) {
        return loggedEvents().stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
