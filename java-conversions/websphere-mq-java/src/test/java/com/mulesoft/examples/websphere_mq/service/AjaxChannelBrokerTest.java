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
import java.util.Collections;
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
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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

    /** 2 MiB, the estimated retained size of {@link #TWO_MIB_TEXT}. */
    private static final long TWO_MIB = 2L * 1024 * 1024;

    /**
     * Text the broker estimates at exactly {@link #TWO_MIB} ({@code 40 + 2 * 1048556} bytes): two fill a client's
     * queue or a cached channel's hold to its byte bound, and 32 fill the broker's retained bytes. Every use shares
     * this one instance, so the test retains 2 MiB however often it is published.
     */
    private static final String TWO_MIB_TEXT = "x".repeat(1_048_556);

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
                Arguments.of("drop on a channel without subscriber", Map.of(),
                        (BrokerScenario) broker -> broker.publish(FORGED_CHANNEL, SECRET),
                        Level.DEBUG, "No subscriber on Ajax channel " + FORGED_CHANNEL_RENDERING + "; message dropped"),
                Arguments.of("rpc naming a reply channel other than its own", Map.of(), (BrokerScenario) broker -> {
                    broker.registerInbound(FORGED_CHANNEL, data -> null);
                    broker.onPublication(FORGED_CLIENT, FORGED_CHANNEL,
                            rpc(SECRET, FORGED_CHANNEL + "#" + FORGED_CLIENT));
                }, Level.WARN, "Ajax rpc publication from client " + FORGED_CLIENT_RENDERING + " on channel "
                        + FORGED_CHANNEL_RENDERING + " names a reply channel other than its own; the handler runs"
                        + " without a reply"),
                Arguments.of("hold beyond its byte bound", Map.of("ajax.cache-messages-channels[0]", FORGED_CHANNEL),
                        (BrokerScenario) broker -> IntStream.range(0, 3)
                                .forEach(i -> broker.publish(FORGED_CHANNEL, TWO_MIB_TEXT)),
                        Level.WARN, "Ajax channel " + FORGED_CHANNEL_RENDERING + " would hold more than "
                                + AjaxChannelBroker.MAX_HELD_BYTES
                                + " estimated bytes for its first subscriber; message dropped"),
                Arguments.of("publication beyond the retained bytes", Map.of(), (BrokerScenario) broker -> {
                    fillRetainedBytes(broker, FORGED_CHANNEL);
                    broker.publish(FORGED_CHANNEL, SECRET);
                }, Level.WARN, "Ajax publication on channel " + FORGED_CHANNEL_RENDERING + " dropped: it would take"
                        + " the retained messages and payloads past " + AjaxChannelBroker.MAX_RETAINED_BYTES
                        + " estimated bytes"),
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("channelsWithoutInboundHandler")
    void clientPublicationOnAChannelWithoutInboundHandlerIsRefusedAndReachesNobody(String description,
            ChannelOf channelOf, boolean publicChannel) throws Exception {
        AjaxChannelBroker broker = broker(handlerEnvironment().withProperty("ajax.cache-messages-channels", CACHED));
        AtomicInteger handled = new AtomicInteger();
        broker.registerInbound(INBOUND, data -> "echo:" + handled.incrementAndGet());
        String publisher = broker.handshake();
        String subscriber = broker.handshake();
        String channel = channelOf.name(subscriber);
        broker.subscribe(subscriber, channel);
        if (publicChannel) {
            broker.subscribe(publisher, channel);
        }

        assertRefused(() -> broker.onPublication(publisher, channel, Map.of("text", "forged", "count", 2)),
                AjaxChannelBroker.RefusalReason.NOT_INBOUND);

        assertThat(broker.awaitMessages(subscriber, QUIET_MS)).isEmpty();
        assertThat(broker.drain(publisher)).isEmpty();
        assertThat(handled).hasValue(0);
        assertThat(broker.isKnownClient(publisher)).as("a refusal keeps the publisher").isTrue();
        // The server's own publication on the same channel still reaches the subscriber.
        broker.publish(channel, "server");
        assertThat(broker.drain(subscriber)).containsExactly(new AjaxChannelBroker.Delivery(channel, "server"));
    }

    static Stream<Arguments> channelsWithoutInboundHandler() {
        return Stream.of(
                Arguments.of("outbound channel", (ChannelOf) subscriber -> OUTBOUND, true),
                Arguments.of("cached outbound channel", (ChannelOf) subscriber -> CACHED, true),
                Arguments.of("another client's reply channel", (ChannelOf) AjaxChannelBrokerTest::replyChannel, false),
                Arguments.of("unconfigured channel", (ChannelOf) subscriber -> "/test/unconfigured", true));
    }

    @Test
    void refusedClientPublicationOnACachedChannelIsNotHeldWhileTheServersPublicationIs() {
        AjaxChannelBroker broker = broker(environment(Map.of("ajax.cache-messages-channels", CACHED)));
        String publisher = broker.handshake();

        assertRefused(() -> broker.onPublication(publisher, CACHED, "forged"),
                AjaxChannelBroker.RefusalReason.NOT_INBOUND);
        broker.publish(CACHED, "server");

        String subscriber = broker.handshake();
        broker.subscribe(subscriber, CACHED);
        assertThat(broker.drain(subscriber)).containsExactly(new AjaxChannelBroker.Delivery(CACHED, "server"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("privateChannelForms")
    void onlyTheClientNamedAfterTheLastHashSubscribesToAPrivateChannel(String description, ClientPair channelOf,
            boolean firstAdmitted, boolean secondAdmitted) {
        AjaxChannelBroker broker = broker(new MockEnvironment());
        String first = broker.handshake();
        String second = broker.handshake();
        String channel = channelOf.name(first, second);

        AjaxChannelBroker.RefusalReason foreign = AjaxChannelBroker.RefusalReason.FOREIGN_PRIVATE_CHANNEL;

        assertSubscription(broker, first, channel, firstAdmitted, foreign);
        assertSubscription(broker, second, channel, secondAdmitted, foreign);

        broker.publish(channel, "reply");
        assertThat(broker.drain(first)).isEqualTo(firstAdmitted
                ? List.of(new AjaxChannelBroker.Delivery(channel, "reply")) : List.of());
        assertThat(broker.drain(second)).isEqualTo(secondAdmitted
                ? List.of(new AjaxChannelBroker.Delivery(channel, "reply")) : List.of());
        assertThat(List.of(first, second)).as("a refusal keeps the client").allMatch(broker::isKnownClient);
    }

    static Stream<Arguments> privateChannelForms() {
        return Stream.of(
                Arguments.of("the second client's reply channel",
                        (ClientPair) (first, second) -> INBOUND + "#" + second, false, true),
                Arguments.of("owner after the last of two hashes", (ClientPair) (first, second) ->
                        "/test/a#" + first + "#" + second, false, true),
                Arguments.of("an empty owner", (ClientPair) (first, second) -> INBOUND + "#", false, false),
                Arguments.of("an owner that only starts with the ID", (ClientPair) (first, second) ->
                        INBOUND + "#" + second + "0", false, false),
                Arguments.of("a public channel", (ClientPair) (first, second) -> OUTBOUND, true, true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unownedReplyChannels")
    void rpcNamingAReplyChannelOtherThanThePublishersOwnRunsTheHandlerWithoutReply(String description,
            ClientPair replyToOf, boolean subscribedBySecond) throws Exception {
        AjaxChannelBroker broker = broker(handlerEnvironment().withProperty("ajax.disable-reply-to", "false"));
        BlockingQueue<Object> handled = new LinkedBlockingQueue<>();
        broker.registerInbound(INBOUND, data -> {
            handled.add(data);
            return "echo:" + data;
        });
        String first = broker.handshake();
        String second = broker.handshake();
        String foreign = replyToOf.name(first, second);
        broker.subscribe(subscribedBySecond ? second : first, foreign);
        broker.subscribe(first, replyChannel(first));

        broker.onPublication(first, INBOUND, rpc("stolen", foreign));

        assertThat(handled.poll(WAIT_MS, TimeUnit.MILLISECONDS)).as("the handler still runs").isEqualTo("stolen");
        assertThat(broker.awaitMessages(subscribedBySecond ? second : first, QUIET_MS)).isEmpty();
        assertThat(loggedMessages(Level.WARN)).containsExactly("Ajax rpc publication from client " + first
                + " on channel " + INBOUND + " names a reply channel other than its own; the handler runs without a"
                + " reply");

        // The publisher's own reply channel still receives its reply.
        broker.onPublication(first, INBOUND, rpc("own", replyChannel(first)));

        assertThat(broker.awaitMessages(first, WAIT_MS))
                .containsExactly(new AjaxChannelBroker.Delivery(replyChannel(first), "echo:own"));
        assertThat(broker.drain(second)).isEmpty();
    }

    static Stream<Arguments> unownedReplyChannels() {
        return Stream.of(
                Arguments.of("another client's reply channel of the channel",
                        (ClientPair) (first, second) -> replyChannel(second), true),
                Arguments.of("a public outbound channel", (ClientPair) (first, second) -> OUTBOUND, true),
                Arguments.of("the publisher's reply channel of another channel",
                        (ClientPair) (first, second) -> OUTBOUND + "#" + first, false));
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
        broker.registerInbound(INBOUND, data -> null);
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
                        (ClientAction) (broker, clientId) -> broker.onPublication(clientId, INBOUND, "ping")),
                Arguments.of("refused publication", (ClientAction) (broker, clientId) ->
                        assertRefused(() -> broker.onPublication(clientId, TICK, "ping"),
                                AjaxChannelBroker.RefusalReason.NOT_INBOUND)));
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
        broker.registerInbound(INBOUND, data -> null);
        int max = AjaxChannelBroker.MAX_QUEUED_DELIVERIES;
        String hoarder = broker.handshake();
        String reader = broker.handshake();
        broker.subscribe(hoarder, OUTBOUND);
        broker.subscribe(reader, OUTBOUND);
        List<AjaxChannelBroker.Delivery> read = new ArrayList<>();

        for (int i = 1; i <= max; i++) {
            broker.publish(OUTBOUND, i);
            // The hoarder keeps publishing, so inactivity alone would never remove it; it never connects or drains.
            broker.onPublication(hoarder, INBOUND, "ping");
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

    @ParameterizedTest(name = "slot freed by {0}")
    @MethodSource("clientSlotReleases")
    void handshakeBeyondMaxClientsIsRefusedUntilAClientIsRemoved(String description, Map<String, String> properties,
            ClientAction removal, boolean oneSlotFreed) {
        AjaxChannelBroker broker = broker(environment(properties));
        List<String> registered = IntStream.range(0, AjaxChannelBroker.MAX_CLIENTS)
                .mapToObj(i -> broker.handshake())
                .toList();

        assertRefused(broker::handshake, AjaxChannelBroker.RefusalReason.CLIENT_LIMIT);
        assertThat(registered).doesNotHaveDuplicates().allMatch(broker::isKnownClient);

        removal.apply(broker, registered.get(0));

        assertThat(broker.isKnownClient(broker.handshake())).isTrue();
        assertThat(broker.isKnownClient(registered.get(0))).isFalse();
        if (oneSlotFreed) {
            assertRefused(broker::handshake, AjaxChannelBroker.RefusalReason.CLIENT_LIMIT);
        }
    }

    static Stream<Arguments> clientSlotReleases() {
        return Stream.of(
                Arguments.of("disconnect", Map.of(), (ClientAction) AjaxChannelBroker::disconnect, true),
                Arguments.of("queue overflow", Map.of(), (ClientAction) (broker, clientId) -> {
                    broker.subscribe(clientId, OUTBOUND);
                    IntStream.rangeClosed(0, AjaxChannelBroker.MAX_QUEUED_DELIVERIES)
                            .forEach(i -> broker.publish(OUTBOUND, i));
                }, true),
                // Every client goes idle together; the next handshake expires them before it counts the clients.
                Arguments.of("idle expiry", Map.of("ajax.max-interval-ms", "3000"), (ClientAction) (broker, clientId) ->
                        await().pollDelay(Duration.ofMillis(3_100)).until(() -> true), false));
    }

    @Test
    void concurrentHandshakesNeverRegisterMoreThanMaxClients() throws Exception {
        AjaxChannelBroker broker = broker(new MockEnvironment());
        int threads = 8;
        int attemptsPerThread = AjaxChannelBroker.MAX_CLIENTS / 4;
        Queue<String> admitted = new ConcurrentLinkedQueue<>();
        AtomicInteger refused = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService racers = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                tasks.add(racers.submit(() -> {
                    start.await();
                    for (int i = 0; i < attemptsPerThread; i++) {
                        try {
                            admitted.add(broker.handshake());
                        } catch (AjaxChannelBroker.ChannelRefusedException limit) {
                            assertThat(limit.reason()).isEqualTo(AjaxChannelBroker.RefusalReason.CLIENT_LIMIT);
                            refused.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) {
                task.get(WAIT_MS, TimeUnit.MILLISECONDS);
            }
        } finally {
            racers.shutdownNow();
        }

        assertThat(admitted).hasSize(AjaxChannelBroker.MAX_CLIENTS).doesNotHaveDuplicates()
                .allMatch(broker::isKnownClient);
        assertThat(refused).hasValue(threads * attemptsPerThread - AjaxChannelBroker.MAX_CLIENTS);
    }

    @ParameterizedTest(name = "{0} characters admitted: {1}")
    @CsvSource({"256, true", "257, false"})
    void subscriptionNamesChannelsOfAtMostMaxChannelNameLengthCharacters(int length, boolean admitted) {
        AjaxChannelBroker broker = broker(new MockEnvironment());
        String client = broker.handshake();
        String channel = "/" + "c".repeat(length - 1);

        assertSubscription(broker, client, channel, admitted, AjaxChannelBroker.RefusalReason.CHANNEL_NAME_TOO_LONG);

        broker.publish(channel, "m");
        assertThat(broker.drain(client))
                .isEqualTo(admitted ? List.of(new AjaxChannelBroker.Delivery(channel, "m")) : List.of());
    }

    @Test
    void clientSubscribesToAtMostMaxSubscriptionsPerClientDistinctChannels() {
        AjaxChannelBroker broker = broker(new MockEnvironment());
        String client = broker.handshake();
        int max = AjaxChannelBroker.MAX_SUBSCRIPTIONS_PER_CLIENT;
        IntStream.range(0, max).forEach(i -> broker.subscribe(client, numbered(i)));

        // Channels already subscribed, or named twice, are not counted again.
        broker.subscribe(client, List.of(numbered(0), numbered(0), numbered(max - 1)));
        assertRefused(() -> broker.subscribe(client, numbered(max)),
                AjaxChannelBroker.RefusalReason.SUBSCRIPTION_LIMIT);

        broker.unsubscribe(client, numbered(0));
        broker.subscribe(client, numbered(max));
        broker.publish(numbered(max), "m");
        assertThat(broker.drain(client)).containsExactly(new AjaxChannelBroker.Delivery(numbered(max), "m"));
        assertThat(broker.isKnownClient(client)).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("subscriptionsRefusedWhole")
    void subscriptionNamingSeveralChannelsIsRefusedWholeAndSubscribesToNone(String description,
            ChannelsOf channelsOf, AjaxChannelBroker.RefusalReason reason) {
        AjaxChannelBroker broker = broker(new MockEnvironment());
        String client = broker.handshake();
        String other = broker.handshake();
        IntStream.range(0, AjaxChannelBroker.MAX_SUBSCRIPTIONS_PER_CLIENT - 1)
                .forEach(i -> broker.subscribe(client, numbered(i)));
        List<String> channels = channelsOf.names(other);

        assertRefused(() -> broker.subscribe(client, channels), reason);

        channels.forEach(channel -> broker.publish(channel, "m"));
        assertThat(broker.drain(client)).as("subscribed to none of %s", channels).isEmpty();
        assertThat(broker.isKnownClient(client)).isTrue();
        // The refused request took none of the client's subscriptions: its last one still fits.
        broker.subscribe(client, OUTBOUND);
    }

    static Stream<Arguments> subscriptionsRefusedWhole() {
        return Stream.of(
                Arguments.of("another client's reply channel among public ones",
                        (ChannelsOf) other -> List.of(OUTBOUND, replyChannel(other)),
                        AjaxChannelBroker.RefusalReason.FOREIGN_PRIVATE_CHANNEL),
                Arguments.of("an over-long channel among public ones",
                        (ChannelsOf) other -> List.of(OUTBOUND,
                                "/" + "c".repeat(AjaxChannelBroker.MAX_CHANNEL_NAME_LENGTH)),
                        AjaxChannelBroker.RefusalReason.CHANNEL_NAME_TOO_LONG),
                Arguments.of("two new channels where one place is left",
                        (ChannelsOf) other -> List.of(OUTBOUND, CACHED),
                        AjaxChannelBroker.RefusalReason.SUBSCRIPTION_LIMIT));
    }

    @Test
    void allClientsTogetherHoldAtMostMaxSubscriptions() {
        AjaxChannelBroker broker = broker(new MockEnvironment());
        int perClient = AjaxChannelBroker.MAX_SUBSCRIPTIONS_PER_CLIENT;
        List<String> channels = IntStream.range(0, perClient).mapToObj(AjaxChannelBrokerTest::numbered).toList();
        List<String> full = IntStream.range(0, AjaxChannelBroker.MAX_SUBSCRIPTIONS / perClient)
                .mapToObj(i -> {
                    String client = broker.handshake();
                    broker.subscribe(client, channels);
                    return client;
                })
                .toList();
        String late = broker.handshake();

        assertRefused(() -> broker.subscribe(late, OUTBOUND), AjaxChannelBroker.RefusalReason.SUBSCRIPTION_LIMIT);
        // A subscription the client already holds is not counted again.
        broker.subscribe(full.get(0), channels.get(0));

        broker.disconnect(full.get(0));
        broker.subscribe(late, OUTBOUND);
        broker.subscribe(late, channels.subList(1, perClient));
        String later = broker.handshake();
        assertRefused(() -> broker.subscribe(later, OUTBOUND), AjaxChannelBroker.RefusalReason.SUBSCRIPTION_LIMIT);
        broker.unsubscribe(late, OUTBOUND);
        broker.subscribe(later, OUTBOUND);
        broker.publish(OUTBOUND, "m");
        assertThat(broker.drain(later)).containsExactly(new AjaxChannelBroker.Delivery(OUTBOUND, "m"));
        assertThat(broker.drain(late)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("retainedSizeEstimates")
    void retainedBytesAreEstimatedFromTheValueWithoutSerializingIt(String description, Object value, long expected) {
        assertThat(AjaxChannelBroker.estimateRetainedBytes(value, Long.MAX_VALUE)).isEqualTo(expected);
    }

    static Stream<Arguments> retainedSizeEstimates() {
        List<Object> selfContaining = new ArrayList<>();
        selfContaining.add(selfContaining);
        Map<String, Object> shared = Map.of();
        return Stream.of(
                Arguments.of("null", null, 8L),
                Arguments.of("empty text", "", 40L),
                Arguments.of("text, two bytes per character", "abc", 46L),
                Arguments.of("other character sequence", new StringBuilder("ab"), 44L),
                Arguments.of("number", 7, 24L),
                Arguments.of("boolean", true, 24L),
                Arguments.of("character", 'c', 24L),
                Arguments.of("map: entries, keys and values", Map.of("k", "v"), 48L + 40 + 42 + 42),
                Arguments.of("list: slots and elements", List.of("a", 1), 48L + 16 + 42 + 24),
                Arguments.of("object array", new Object[] {"a"}, 48L + 8 + 42),
                Arguments.of("primitive array", new int[3], 48L + 24),
                Arguments.of("any other value", new Object(), 64L),
                Arguments.of("list containing itself", selfContaining, 48L + 8 + 8),
                Arguments.of("one map referenced twice", List.of(shared, shared), 48L + 16 + 48 + 8),
                Arguments.of("the two-MiB test text", TWO_MIB_TEXT, TWO_MIB));
    }

    @Test
    void estimateWalksDeepNestingWithoutRecursionAndStopsOnceItPassesTheLimit() {
        int depth = 100_000;
        List<Object> outermost = new ArrayList<>();
        List<Object> current = outermost;
        for (int i = 0; i < depth; i++) {
            List<Object> inner = new ArrayList<>();
            current.add(inner);
            current = inner;
        }
        List<String> texts = Collections.nCopies(1000, "t".repeat(1000));

        assertThat(AjaxChannelBroker.estimateRetainedBytes(outermost, Long.MAX_VALUE)).isEqualTo(depth * 56L + 48);
        // The list and its first text pass 10000 bytes; the other 999 texts are never visited.
        assertThat(AjaxChannelBroker.estimateRetainedBytes(texts, 10_000)).isEqualTo(48L + 8 * 1000 + 2040);
    }

    @Test
    void deliveryBeyondTheQueueByteBoundRemovesOnlyThatClient() {
        AjaxChannelBroker broker = broker(new MockEnvironment());
        String hoarder = broker.handshake();
        String reader = broker.handshake();
        broker.subscribe(hoarder, OUTBOUND);
        broker.subscribe(reader, OUTBOUND);
        List<AjaxChannelBroker.Delivery> read = new ArrayList<>();

        broker.publish(OUTBOUND, TWO_MIB_TEXT);
        read.addAll(broker.drain(reader));
        broker.publish(OUTBOUND, TWO_MIB_TEXT);
        read.addAll(broker.drain(reader));

        assertThat(broker.isKnownClient(hoarder)).as("a queue at exactly its byte bound is kept").isTrue();
        assertThat(loggedMessages(Level.WARN)).isEmpty();

        broker.publish(OUTBOUND, 1);

        assertThat(broker.isKnownClient(hoarder)).isFalse();
        assertThat(broker.drain(hoarder)).isEmpty();
        assertThat(loggedMessages(Level.WARN)).containsExactly(byteOverflowWarning(hoarder));
        read.addAll(broker.drain(reader));
        assertThat(read).extracting(AjaxChannelBroker.Delivery::data).containsExactly(TWO_MIB_TEXT, TWO_MIB_TEXT, 1);
        assertThat(broker.isKnownClient(reader)).isTrue();
    }

    @ParameterizedTest(name = "{0} two-MiB deliveries restored onto a queue holding a small one")
    @CsvSource({"1, true", "2, false"})
    void restoreIsBoundedByTheQueueBytesAndAnOverflowRemovesTheClient(int restored, boolean kept) {
        AjaxChannelBroker broker = broker(new MockEnvironment());
        String client = broker.handshake();
        broker.subscribe(client, OUTBOUND);
        broker.publish(OUTBOUND, TWO_MIB_TEXT);
        broker.publish(OUTBOUND, TWO_MIB_TEXT);
        List<AjaxChannelBroker.Delivery> undelivered = broker.drain(client).subList(0, restored);
        broker.publish(OUTBOUND, "small");

        broker.restore(client, undelivered);

        assertThat(broker.isKnownClient(client)).isEqualTo(kept);
        if (kept) {
            assertThat(broker.drain(client)).extracting(AjaxChannelBroker.Delivery::data)
                    .containsExactly(TWO_MIB_TEXT, "small");
            assertThat(loggedMessages(Level.WARN)).isEmpty();
        } else {
            assertThat(broker.drain(client)).isEmpty();
            assertThat(loggedMessages(Level.WARN)).containsExactly(byteOverflowWarning(client));
        }
    }

    @ParameterizedTest(name = "on a cached channel: {0}")
    @ValueSource(booleans = {false, true})
    void serverPublicationBeyondTheRetainedBytesIsDroppedWholeUntilBytesAreReleased(boolean cachedChannel) {
        AjaxChannelBroker broker = broker(environment(Map.of("ajax.cache-messages-channels", CACHED)));
        String channel = cachedChannel ? CACHED : TICK;
        List<String> fillers = fillRetainedBytes(broker, OUTBOUND);
        String receiver = broker.handshake();
        if (!cachedChannel) {
            broker.subscribe(receiver, channel);
        }

        broker.publish(channel, "dropped");

        assertThat(loggedMessages(Level.WARN)).containsExactly("Ajax publication on channel " + channel
                + " dropped: it would take the retained messages and payloads past "
                + AjaxChannelBroker.MAX_RETAINED_BYTES + " estimated bytes");
        assertThat(fillers).as("the drop removes nobody").allMatch(broker::isKnownClient);

        broker.drain(fillers.get(0));
        broker.publish(channel, "kept");

        if (cachedChannel) {
            broker.subscribe(receiver, channel);
        }
        assertThat(broker.drain(receiver)).containsExactly(new AjaxChannelBroker.Delivery(channel, "kept"));
    }

    @ParameterizedTest(name = "released as the calls {0}")
    @MethodSource("handlerCallEndings")
    void clientPublicationBeyondTheRetainedBytesIsRefusedUntilItsHandlerCallsEnd(String description,
            String responseTimeoutMs, boolean releaseHandlers) throws Exception {
        AjaxChannelBroker broker = broker(new MockEnvironment()
                .withProperty("ajax.response-timeout-ms", responseTimeoutMs));
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch probed = new CountDownLatch(1);
        broker.registerInbound(INBOUND, data -> {
            if ("probe".equals(data)) {
                probed.countDown();
            } else {
                release.await(WAIT_MS, TimeUnit.MILLISECONDS);
            }
            return null;
        });
        String client = broker.handshake();
        int fitting = (int) (AjaxChannelBroker.MAX_RETAINED_BYTES / TWO_MIB);

        try {
            IntStream.range(0, fitting).forEach(i -> broker.onPublication(client, INBOUND, TWO_MIB_TEXT));
            assertRefused(() -> broker.onPublication(client, INBOUND, "probe"),
                    AjaxChannelBroker.RefusalReason.RETAINED_BYTES);
            if (releaseHandlers) {
                release.countDown();
            }

            await().atMost(Duration.ofMillis(WAIT_MS)).pollInterval(Duration.ofMillis(50)).ignoreExceptions()
                    .until(() -> {
                        broker.onPublication(client, INBOUND, "probe");
                        return true;
                    });
            assertThat(probed.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
        } finally {
            release.countDown();
        }
    }

    static Stream<Arguments> handlerCallEndings() {
        return Stream.of(
                Arguments.of("complete", "60000", true),
                Arguments.of("time out", "1000", false));
    }

    @ParameterizedTest(name = "probed by a {0}")
    @MethodSource("retainedBytesProbes")
    void handlerIgnoringItsTimeoutKeepsItsPayloadBytesReservedUntilItReturns(String description,
            RetainedBytesProbe probe) throws Exception {
        AjaxChannelBroker broker = broker(handlerEnvironment());
        AtomicInteger running = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        broker.registerInbound(INBOUND, data -> {
            running.incrementAndGet();
            started.countDown();
            try {
                // Ignoring the timeout's interrupt, the handler keeps its payload long after the timeout.
                awaitIgnoringInterrupts(release);
                return null;
            } finally {
                running.decrementAndGet();
            }
        });
        String client = broker.handshake();
        BooleanSupplier fits = probe.prepare(broker, client);
        // Draining one full queue leaves room for exactly the two two-MiB payloads published next.
        broker.drain(fillRetainedBytes(broker, OUTBOUND).get(0));
        String timeoutLine = "Ajax handler for channel " + INBOUND + " did not respond within " + HANDLER_TIMEOUT_MS
                + " ms";

        try {
            broker.onPublication(client, INBOUND, TWO_MIB_TEXT);
            broker.onPublication(client, INBOUND, TWO_MIB_TEXT);
            assertThat(started.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
            await().atMost(Duration.ofMillis(WAIT_MS)).pollInterval(Duration.ofMillis(20)).until(() ->
                    loggedMessages(Level.INFO).stream().filter(timeoutLine::equals).count() == 2);

            assertThat(fits.getAsBoolean()).as("probe while the timed-out handlers still hold their payloads")
                    .isFalse();
            assertThat(running).as("handlers still blocked past their timeout").hasValue(2);
        } finally {
            release.countDown();
        }
        // Released, the handlers return and only then give their payloads' bytes back.
        await().atMost(Duration.ofMillis(WAIT_MS)).pollInterval(Duration.ofMillis(20)).until(fits::getAsBoolean);
    }

    @ParameterizedTest(name = "probed by a {0}")
    @MethodSource("retainedBytesProbes")
    void callCancelledWhileWaitingForAHandlerThreadReleasesItsPayloadBytesAtItsTimeout(String description,
            RetainedBytesProbe probe) throws Exception {
        int maxRunning = AjaxChannelBroker.MAX_RUNNING_HANDLERS;
        AjaxChannelBroker broker = broker(handlerEnvironment());
        AtomicInteger running = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(maxRunning);
        CountDownLatch release = new CountDownLatch(1);
        broker.registerInbound(INBOUND, data -> {
            running.incrementAndGet();
            started.countDown();
            try {
                awaitIgnoringInterrupts(release);
                return null;
            } finally {
                running.decrementAndGet();
            }
        });
        String client = broker.handshake();
        BooleanSupplier fits = probe.prepare(broker, client);
        broker.drain(fillRetainedBytes(broker, OUTBOUND).get(0));
        String timeoutLine = "Ajax handler for channel " + INBOUND + " did not respond within " + HANDLER_TIMEOUT_MS
                + " ms";

        try {
            // Number payloads of 24 estimated bytes take every handler thread; the two-MiB payload waits for one.
            IntStream.range(0, maxRunning).forEach(i -> broker.onPublication(client, INBOUND, i));
            assertThat(started.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
            broker.onPublication(client, INBOUND, TWO_MIB_TEXT);
            assertThat(fits.getAsBoolean()).as("probe while the waiting call holds its payload").isFalse();

            await().atMost(Duration.ofMillis(WAIT_MS)).pollInterval(Duration.ofMillis(20)).until(() ->
                    loggedMessages(Level.INFO).stream().filter(timeoutLine::equals).count() == maxRunning + 1);

            assertThat(fits.getAsBoolean()).as("probe right after the waiting call's timeout").isTrue();
            assertThat(running).as("every handler thread still blocked").hasValue(maxRunning);
        } finally {
            release.countDown();
        }
        await().atMost(Duration.ofMillis(WAIT_MS)).until(() -> running.get() == 0);
    }

    static Stream<Arguments> retainedBytesProbes() {
        RetainedBytesProbe clientPublication = (broker, client) -> () -> {
            try {
                broker.onPublication(client, INBOUND, TWO_MIB_TEXT);
                return true;
            } catch (AjaxChannelBroker.ChannelRefusedException refused) {
                assertThat(refused.reason()).isEqualTo(AjaxChannelBroker.RefusalReason.RETAINED_BYTES);
                return false;
            }
        };
        RetainedBytesProbe serverPublication = (broker, client) -> {
            String receiver = broker.handshake();
            broker.subscribe(receiver, numbered(0));
            return () -> {
                broker.publish(numbered(0), TWO_MIB_TEXT);
                return !broker.drain(receiver).isEmpty();
            };
        };
        return Stream.of(
                Arguments.of("client publication", clientPublication),
                Arguments.of("server publication", serverPublication));
    }

    @Test
    void shutdownReleasesWaitingCallsAtOnceAndRunningCallsWhenTheirHandlersReturn() throws Exception {
        int maxRunning = AjaxChannelBroker.MAX_RUNNING_HANDLERS;
        // No response timeout fires during the test, so only the shutdown ends the calls.
        AjaxChannelBroker broker = broker(new MockEnvironment().withProperty("ajax.response-timeout-ms", "60000"));
        AtomicInteger running = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(maxRunning);
        CountDownLatch release = new CountDownLatch(1);
        broker.registerInbound(INBOUND, data -> {
            running.incrementAndGet();
            started.countDown();
            try {
                // Ignoring the shutdown's interrupt, the handler keeps its payload until released.
                awaitIgnoringInterrupts(release);
                return null;
            } finally {
                running.decrementAndGet();
            }
        });
        String client = broker.handshake();
        long freeWhileHandlersRun = AjaxChannelBroker.MAX_RETAINED_BYTES - maxRunning * TWO_MIB;
        Object fillingTheRest = payloadEstimatedAt(freeWhileHandlersRun);
        Object beyondTheRest = payloadEstimatedAt(freeWhileHandlersRun + 8);
        Object fillingTheBound = payloadEstimatedAt(AjaxChannelBroker.MAX_RETAINED_BYTES);
        String shutDownLine = "Ajax handler for channel " + INBOUND + " failed: the broker has been shut down";

        try {
            // Sixteen two-MiB calls run and sixteen wait: together they retain exactly the bound.
            IntStream.range(0, 2 * maxRunning).forEach(i -> broker.onPublication(client, INBOUND, TWO_MIB_TEXT));
            assertThat(started.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
            assertRefused(() -> broker.onPublication(client, INBOUND, "x"),
                    AjaxChannelBroker.RefusalReason.RETAINED_BYTES);

            broker.shutdown();

            // The dropped waiting calls have released their payloads; the still running handlers keep theirs.
            assertRefused(() -> broker.onPublication(client, INBOUND, beyondTheRest),
                    AjaxChannelBroker.RefusalReason.RETAINED_BYTES);
            assertThatCode(() -> broker.onPublication(client, INBOUND, fillingTheRest)).doesNotThrowAnyException();
            assertThat(running).hasValue(maxRunning);
        } finally {
            release.countDown();
        }
        await().atMost(Duration.ofMillis(WAIT_MS)).until(() -> running.get() == 0);
        await().atMost(Duration.ofMillis(WAIT_MS)).pollInterval(Duration.ofMillis(20)).ignoreExceptions()
                .until(() -> {
                    broker.onPublication(client, INBOUND, fillingTheBound);
                    return true;
                });
        // Both publications admitted after the shutdown were refused by it, and released their bytes at once.
        assertThat(loggedMessages(Level.INFO)).containsExactly(shutDownLine, shutDownLine);
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

    /** The {@code n}-th of a family of distinct public channels. */
    private static String numbered(int n) {
        return "/test/c" + n;
    }

    /**
     * Subscribes as many new clients to {@code channel} as {@link AjaxChannelBroker#MAX_RETAINED_BYTES} has room for
     * full queues, and publishes {@link #TWO_MIB_TEXT} there twice: every one of their queues then holds exactly
     * {@link AjaxChannelBroker#MAX_QUEUED_BYTES_PER_CLIENT}, and the broker retains exactly its retained-byte bound.
     *
     * @return the filling clients; draining one releases one queue's bytes
     */
    private static List<String> fillRetainedBytes(AjaxChannelBroker broker, String channel) {
        int fillers = (int) (AjaxChannelBroker.MAX_RETAINED_BYTES / AjaxChannelBroker.MAX_QUEUED_BYTES_PER_CLIENT);
        List<String> clients = IntStream.range(0, fillers).mapToObj(i -> broker.handshake()).toList();
        clients.forEach(client -> broker.subscribe(client, channel));
        broker.publish(channel, TWO_MIB_TEXT);
        broker.publish(channel, TWO_MIB_TEXT);
        return clients;
    }

    /**
     * A payload the broker estimates at exactly {@code bytes}: a primitive array, which counts 48 bytes plus 8 per
     * element whatever its element type, so a {@code boolean[]} keeps only an eighth of its estimate alive.
     */
    private static Object payloadEstimatedAt(long bytes) {
        boolean[] payload = new boolean[Math.toIntExact((bytes - 48) / 8)];
        assertThat(AjaxChannelBroker.estimateRetainedBytes(payload, Long.MAX_VALUE)).isEqualTo(bytes);
        return payload;
    }

    /** Asserts that {@code call} is refused by the broker for {@code reason}. */
    private static void assertRefused(ThrowingCallable call, AjaxChannelBroker.RefusalReason reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AjaxChannelBroker.ChannelRefusedException.class,
                refused -> assertThat(refused.reason()).isEqualTo(reason));
    }

    /** Asserts that subscribing a client to a channel succeeds when {@code admitted}, else is refused for reason. */
    private static void assertSubscription(AjaxChannelBroker broker, String clientId, String channel,
            boolean admitted, AjaxChannelBroker.RefusalReason reason) {
        if (admitted) {
            assertThatCode(() -> broker.subscribe(clientId, channel)).doesNotThrowAnyException();
        } else {
            assertRefused(() -> broker.subscribe(clientId, channel), reason);
        }
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

    /** Names a channel after one client of a broker. */
    @FunctionalInterface
    interface ChannelOf {

        String name(String clientId);
    }

    /** Names a channel after two clients of a broker. */
    @FunctionalInterface
    interface ClientPair {

        String name(String first, String second);
    }

    /** Names the channels of one subscription after another client of a broker. */
    @FunctionalInterface
    interface ChannelsOf {

        List<String> names(String otherClientId);
    }

    /** A publication of {@link #TWO_MIB_TEXT} that fits only while the retained bytes have room for it. */
    @FunctionalInterface
    interface RetainedBytesProbe {

        /**
         * Sets the probe up on a broker that retains nothing yet.
         *
         * @return publishes once and tells whether the publication fitted; one that did not was refused or dropped
         *         and left nothing behind
         */
        BooleanSupplier prepare(AjaxChannelBroker broker, String clientId);
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

    /** The WARN the broker logs when it removes a client whose queue overflowed its byte bound. */
    private static String byteOverflowWarning(String clientId) {
        return "Ajax client " + clientId + " exceeded " + AjaxChannelBroker.MAX_QUEUED_BYTES_PER_CLIENT
                + " estimated bytes of queued deliveries without a connect; client removed with its subscriptions";
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
