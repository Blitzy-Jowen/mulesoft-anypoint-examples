package com.mulesoft.examples.service_orchestration_and_choice_routing.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Channel state of the Mule 3.8 Ajax transport behind the Bayeux long-polling endpoint: the Bayeux clients, their
 * channel subscriptions and pending deliveries, the handlers of the inbound channels, and the replies sent to the
 * {@code replyTo} channel of an rpc publication.
 *
 * <p>The client contract is the copied {@code mule-resource/js/_mule.js}: {@code mule.rpc(channel, data, cb)}
 * subscribes to {@code <channel>#<clientId>}, publishes {@code {"data": <data>, "replyTo": "<channel>#<clientId>"}}
 * on {@code channel} and reads the reply's {@code data}; {@code mule.subscribe(channel, cb)} receives plain
 * publications. Channels match exactly; Bayeux wildcards are not supported because no copied page uses them.
 *
 * <p>Configuration, all optional:
 * <ul>
 *   <li>{@code ajax.disable-reply-to} (default {@code false}) &mdash; the connector's {@code disableReplyTo}: when
 *       {@code true}, an inbound handler's result is never published on the publication's {@code replyTo}.</li>
 *   <li>{@code ajax.response-timeout-ms} (default 10000) &mdash; the inbound endpoints' {@code responseTimeout}: a
 *       handler call that has not returned this long after its publication produces no reply and is cancelled: a
 *       running handler is interrupted, a waiting call never starts, and a late result is discarded.</li>
 *   <li>{@code ajax.cache-messages-channels} (default empty) &mdash; the outbound channels declared with
 *       {@code cacheMessages="true"}: a message published while nobody subscribes is held for the first
 *       subscriber.</li>
 *   <li>{@code ajax.max-interval-ms} (default 30000) &mdash; the Bayeux {@code maxInterval} the Mule connector leaves
 *       at its default: a client without a held connect that has not been heard from for this long is removed, as
 *       the embedded cometd server removed it.</li>
 * </ul>
 *
 * <p>Every resource a client or publication can occupy is bounded:
 * <ul>
 *   <li>a client holds at most {@value #MAX_QUEUED_DELIVERIES} deliveries between two connects; the delivery that
 *       would exceed that removes the client with its subscriptions and queue, as a client that stopped
 *       connecting, logged at WARN, while every other subscriber still receives it;</li>
 *   <li>a cached channel holds at most {@value #MAX_HELD_MESSAGES} messages for its first subscriber;</li>
 *   <li>at most {@value #MAX_RUNNING_HANDLERS} inbound handlers run at once and at most
 *       {@value #MAX_QUEUED_HANDLER_CALLS} publications wait for one; a publication beyond that is refused, logged
 *       at INFO and answered with no reply, and never retried.</li>
 * </ul>
 *
 * <p>Diagnostics never carry a handler exception's message or the exception itself, only its type and throw-site
 * identifiers, and every logged channel name or client ID is escaped and bounded, because both can come from a
 * client and could otherwise forge log lines.
 *
 * <p>Bayeux framing (message ids, {@code successful}, {@code advice}, {@code /meta/*} dispatch and JSON) belongs to
 * the controller; this class works with client IDs, channel names and plain values only. All state is in memory and
 * lost on restart, as the connector's was. Every public method is thread-safe, and a client's lifecycle
 * (subscription, held-message transfer, publication to it, disconnect, overflow and idle expiry) is one atomic
 * sequence of steps under a single lock.
 */
@Service
public class AjaxChannelBroker {

    /**
     * Messages a cached channel holds for its first subscriber: Mule's default {@code messageCacheSize} for an Ajax
     * outbound endpoint.
     */
    static final int MAX_HELD_MESSAGES = 500;

    /**
     * Deliveries one client may have queued between two connects. It is the bound Mule's connector applied to a
     * cached channel ({@link #MAX_HELD_MESSAGES}), so a full hold always fits into a fresh subscriber's queue.
     */
    static final int MAX_QUEUED_DELIVERIES = MAX_HELD_MESSAGES;

    /** Inbound handlers running at once: the {@code maxThreadsActive} of Mule 3's default threading profile. */
    static final int MAX_RUNNING_HANDLERS = 16;

    /**
     * Publications waiting for a free handler thread. The waiting queue is bounded, so a burst of publications to a
     * slow handler retains at most this many payloads; it is sized like the cached-channel hold.
     */
    static final int MAX_QUEUED_HANDLER_CALLS = 500;

    private static final Logger LOGGER = LoggerFactory.getLogger(AjaxChannelBroker.class);

    private static final String DATA_KEY = "data";
    private static final String REPLY_TO_KEY = "replyTo";

    /** How long an idle handler thread is kept: Mule's default {@code threadTTL} of 60 s. */
    private static final long HANDLER_THREAD_KEEP_ALIVE_SECONDS = 60;

    /** Why a publication was refused once {@link #shutdown()} has run. */
    private static final String SHUT_DOWN_REASON = "the broker has been shut down";

    /** Why a publication was refused while every handler thread is busy and the waiting queue is full. */
    private static final String HANDLERS_BUSY_REASON = "all handler threads are busy and the waiting queue is full";

    /** Longest logged channel name, client ID, or class, method or file name, in characters after escaping. */
    private static final int LOG_TEXT_LIMIT = 256;

    /** Number of causes named after the outermost exception before the chain is cut short. */
    private static final int CAUSE_LOG_LIMIT = 10;

    private static final String NULL_LOG_TOKEN = "(null)";

    private static final String TRUNCATION_LOG_MARKER = "...[truncated]";

    private static final String CAUSE_SEPARATOR = " <- ";

    private static final String MORE_CAUSES_MARKER = "...";

    private static final String CIRCULAR_CAUSE_MARKER = "[circular]";

    private static final String UNAVAILABLE_LOG_MARKER = "[unavailable]";

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private final boolean disableReplyTo;
    private final long responseTimeoutMs;
    private final Set<String> cacheMessagesChannels;
    private final long maxIntervalNanos;
    private final long sweepIntervalNanos;

    /** Runs the inbound handlers on at most {@value #MAX_RUNNING_HANDLERS} threads. */
    private final ThreadPoolExecutor executor;

    /** Fires the response timeouts that cancel handler calls. */
    private final ScheduledThreadPoolExecutor timeouts;

    /**
     * The known clients. Read without a lock; a client is added by {@link #handshake()} and removed only while
     * {@link #stateLock} is held.
     */
    private final Map<String, ClientSession> clients = new ConcurrentHashMap<>();
    private final Map<String, InboundHandler> inboundHandlers = new ConcurrentHashMap<>();

    /** The subscriber IDs of each channel. Guarded by {@link #stateLock}. */
    private final Map<String, Set<String>> subscribersByChannel = new HashMap<>();

    /** Messages held per cached channel, in publish order. Guarded by {@link #stateLock}. */
    private final Map<String, List<Object>> heldMessages = new HashMap<>();

    /**
     * Guards {@link #subscribersByChannel}, every session's channel set and {@link #heldMessages}, and is held by
     * every client removal (disconnect, idle expiry, queue overflow, shutdown) and every publication, so that a
     * subscription, a held-message transfer and a removal can never interleave.
     *
     * <p>Invariant while it is held: a session present in {@link #clients} is open, and a closed session has no
     * subscriptions. A removal therefore closes the session and drops it with all its subscriptions before
     * releasing the lock. Lock order is this lock, then a session's own lock; nothing calls back into the broker or
     * logs while holding a session's lock.
     */
    private final Object stateLock = new Object();

    private final AtomicLong nextSweepNanos;

    /**
     * Reads the {@code ajax.*} configuration and creates the handler executor and the response-timeout scheduler.
     *
     * @param environment the application environment (a {@code MockEnvironment} in tests)
     * @throws IllegalArgumentException when {@code ajax.response-timeout-ms} or {@code ajax.max-interval-ms} is not
     *         positive
     */
    public AjaxChannelBroker(Environment environment) {
        Objects.requireNonNull(environment, "environment");
        this.disableReplyTo = environment.getProperty("ajax.disable-reply-to", Boolean.class, Boolean.FALSE);
        this.responseTimeoutMs = positive("ajax.response-timeout-ms",
                environment.getProperty("ajax.response-timeout-ms", Long.class, 10000L));
        long maxIntervalMs = positive("ajax.max-interval-ms",
                environment.getProperty("ajax.max-interval-ms", Long.class, 30000L));
        // An empty YAML list arrives as an empty string, which binds to an empty list; blank entries name no channel.
        this.cacheMessagesChannels = Binder.get(environment)
                .bind("ajax.cache-messages-channels", Bindable.listOf(String.class))
                .orElse(List.of())
                .stream()
                .filter(channel -> channel != null && !channel.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        this.maxIntervalNanos = TimeUnit.MILLISECONDS.toNanos(maxIntervalMs);
        this.sweepIntervalNanos = Math.max(1L, maxIntervalNanos / 2);
        this.nextSweepNanos = new AtomicLong(System.nanoTime() + sweepIntervalNanos);
        // Idle threads end after 60 s, Mule's default threadTTL; a publication finding every thread busy and the
        // waiting queue full is refused (AbortPolicy) rather than run on the publishing thread.
        this.executor = new ThreadPoolExecutor(MAX_RUNNING_HANDLERS, MAX_RUNNING_HANDLERS,
                HANDLER_THREAD_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(MAX_QUEUED_HANDLER_CALLS), daemonThreads(),
                new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
        this.timeouts = new ScheduledThreadPoolExecutor(1,
                runnable -> daemonThread(runnable, "ajax-channel-broker-timeout"));
        // A call that completes in time cancels its timeout, which then leaves the queue at once.
        this.timeouts.setRemoveOnCancelPolicy(true);
    }

    /**
     * Handles the publications of one inbound channel, as the flow behind an Ajax inbound endpoint did.
     *
     * <p>Calls run on the broker's own threads, at most {@value AjaxChannelBroker#MAX_RUNNING_HANDLERS} at once;
     * at most {@value AjaxChannelBroker#MAX_QUEUED_HANDLER_CALLS} further publications wait for a thread, and one
     * arriving when that queue is full is refused, logged at INFO and answered with no reply. A call that has not
     * returned within {@code ajax.response-timeout-ms} of its publication is cancelled: a call still waiting never
     * starts, and a running one is interrupted and its result or failure discarded. A handler should therefore
     * bound its blocking I/O and honour interruption; one that ignores interruption keeps its thread until it
     * returns, and while every thread is held that way further publications wait or are refused.
     */
    @FunctionalInterface
    public interface InboundHandler {

        /**
         * Processes one publication.
         *
         * @param data the publication's payload: the {@code data} of an rpc envelope, otherwise the published value
         * @return the reply for the publication's {@code replyTo} channel, or {@code null} for no reply
         * @throws Exception any failure; it is logged by its type and throw site and produces no reply
         */
        Object handle(Object data) throws Exception;
    }

    /**
     * One message to hand to a client in a {@code /meta/connect} response.
     *
     * @param channel the channel the message was published on
     * @param data the published value exactly as published: a String, Map, List, Number, Boolean or {@code null}
     */
    public record Delivery(String channel, Object data) {
    }

    /**
     * Whether inbound results are kept off the {@code replyTo} channel ({@code ajax.disable-reply-to}).
     *
     * @return {@code true} when replies are disabled
     */
    public boolean isReplyToDisabled() {
        return disableReplyTo;
    }

    /**
     * The time an inbound handler call is given before it is cancelled and its publication answered with no reply
     * ({@code ajax.response-timeout-ms}).
     *
     * @return the timeout in milliseconds
     */
    public long responseTimeoutMs() {
        return responseTimeoutMs;
    }

    /**
     * Stops the handler executor, interrupting running handlers and dropping waiting calls, stops the response
     * timeouts, and removes every client, releasing each waiting {@link #awaitMessages(String, long)} call with an
     * empty result. A later publication on an inbound channel is refused and logged at INFO.
     */
    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        timeouts.shutdownNow();
        synchronized (stateLock) {
            clients.forEach(this::removeClient);
        }
    }

    /**
     * Registers a new client.
     *
     * @return the new client's ID, 32 lower-case hexadecimal characters
     */
    public String handshake() {
        expireIdleClients();
        String clientId;
        do {
            clientId = UUID.randomUUID().toString().replace("-", "");
        } while (clients.putIfAbsent(clientId, new ClientSession()) != null);
        return clientId;
    }

    /**
     * Whether a client is registered and has neither disconnected nor expired.
     *
     * @param clientId the client ID; {@code null} is never known
     * @return {@code true} for a registered client
     */
    public boolean isKnownClient(String clientId) {
        return clientId != null && clients.containsKey(clientId);
    }

    /**
     * Subscribes a client to a channel. When the channel is a cached channel holding messages, they are delivered to
     * this subscriber in publish order and the hold is cleared.
     *
     * <p>The client lookup, the subscription and the transfer are one atomic step, so a client removed concurrently
     * either loses the subscription with the rest of its state or is reported unknown here, and held messages are
     * never handed to a removed client. The hold is cleared only once the client's queue has accepted all of it.
     *
     * @param clientId a known client ID
     * @param channel the exact channel name
     * @throws IllegalArgumentException when the client is unknown, or when the held messages would take its queue past
     *         {@value #MAX_QUEUED_DELIVERIES} deliveries, in which case the client is removed and the channel keeps
     *         holding the messages for its next subscriber
     */
    public void subscribe(String clientId, String channel) {
        Objects.requireNonNull(channel, "channel");
        synchronized (stateLock) {
            ClientSession session = requireSession(clientId);
            session.touch();
            addSubscriber(clientId, session, channel);
            List<Object> held = heldMessages.get(channel);
            if (held == null) {
                return;
            }
            // Held messages go to the first subscriber at once. Mule's dispatcher flushed its cache only with the next
            // message published after a subscription; delivering on subscribe ends the hold as soon as someone listens.
            List<Delivery> deliveries = held.stream().map(data -> new Delivery(channel, data)).toList();
            if (session.enqueue(deliveries) == EnqueueOutcome.ACCEPTED) {
                heldMessages.remove(channel);
                return;
            }
            // The queue cannot take the whole hold, so the session has closed itself; the client goes with all its
            // subscriptions, this one included, and the hold stays intact for the next subscriber.
            removeClient(clientId, session);
        }
        logOverflow(clientId);
        throw new IllegalArgumentException("Ajax client " + clientId + " was removed: the messages held on its new"
                + " channel would exceed " + MAX_QUEUED_DELIVERIES + " queued deliveries");
    }

    /**
     * Removes a client's subscription. An unknown client or channel is ignored.
     *
     * @param clientId the client ID
     * @param channel the exact channel name
     */
    public void unsubscribe(String clientId, String channel) {
        if (clientId == null || channel == null) {
            return;
        }
        synchronized (stateLock) {
            ClientSession session = clients.get(clientId);
            if (session != null) {
                session.touch();
                session.channels.remove(channel);
                removeSubscriber(clientId, channel);
            }
        }
    }

    /**
     * Removes a client with its subscriptions and queued deliveries, and releases its waiting
     * {@link #awaitMessages(String, long)} call with an empty result. An unknown client is ignored.
     *
     * @param clientId the client ID
     */
    public void disconnect(String clientId) {
        if (clientId == null) {
            return;
        }
        synchronized (stateLock) {
            ClientSession session = clients.get(clientId);
            if (session != null) {
                removeClient(clientId, session);
            }
        }
    }

    /**
     * Returns and removes every delivery queued for a client.
     *
     * @param clientId the client ID
     * @return the deliveries in queue order; empty for an unknown client
     */
    public List<Delivery> drain(String clientId) {
        ClientSession session = clientId == null ? null : clients.get(clientId);
        if (session == null) {
            return List.of();
        }
        return session.drain();
    }

    /**
     * Waits for deliveries for a client, as a held {@code /meta/connect} does. Returns at once when deliveries are
     * queued; otherwise waits up to {@code timeoutMs} for the first one and then returns everything queued.
     *
     * <p>Bayeux allows one outstanding connect per client, so a newer call for the same client releases an older
     * waiting one with an empty list and the deliveries go to the newest.
     *
     * @param clientId the client ID
     * @param timeoutMs the longest wait in milliseconds; zero or less does not wait
     * @return the deliveries in queue order; empty on timeout, disconnect, supersession or for an unknown client
     * @throws InterruptedException when the waiting thread is interrupted
     */
    public List<Delivery> awaitMessages(String clientId, long timeoutMs) throws InterruptedException {
        ClientSession session = clientId == null ? null : clients.get(clientId);
        if (session == null) {
            return List.of();
        }
        return session.await(timeoutMs);
    }

    /**
     * Registers the handler of an inbound channel.
     *
     * @param channel the exact channel name
     * @param handler the handler for every publication on the channel
     * @throws NullPointerException when an argument is {@code null}
     * @throws IllegalStateException when the channel already has a handler
     */
    public void registerInbound(String channel, InboundHandler handler) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(handler, "handler");
        if (inboundHandlers.putIfAbsent(channel, handler) != null) {
            throw new IllegalStateException("An inbound handler is already registered for channel " + channel);
        }
    }

    /**
     * Accepts a client publication on an application channel, in the order received, without blocking the caller.
     *
     * <ul>
     *   <li>A channel with an inbound handler: a {@code Map} carrying the key {@code replyTo} is an rpc envelope whose
     *       payload is its {@code data} and whose reply channel is its {@code replyTo} when that is a String; any
     *       other value is the payload itself, with no reply channel. The handler runs on the broker's bounded
     *       executor within {@code ajax.response-timeout-ms} (see {@link InboundHandler}); a non-null result is
     *       published on the reply channel unless replies are disabled. A failure, a timeout or a refusal because
     *       the executor is saturated or shut down is logged at INFO and produces no reply, and the publication is
     *       never retried. The publication reaches the handler only, never the channel's subscribers.</li>
     *   <li>Any other channel: the value is published to every subscriber, the publisher included.</li>
     * </ul>
     *
     * @param clientId the publishing client
     * @param channel the exact channel name
     * @param data the published value
     */
    public void onPublication(String clientId, String channel, Object data) {
        Objects.requireNonNull(channel, "channel");
        ClientSession publisher = clientId == null ? null : clients.get(clientId);
        if (publisher != null) {
            publisher.touch();
        }
        InboundHandler handler = inboundHandlers.get(channel);
        if (handler == null) {
            publish(channel, data);
            return;
        }
        LOGGER.debug("Ajax publication from client {} dispatched to the handler of channel {}", loggable(clientId),
                loggable(channel));
        if (data instanceof Map<?, ?> envelope && envelope.containsKey(REPLY_TO_KEY)) {
            Object replyTo = envelope.get(REPLY_TO_KEY);
            dispatch(channel, handler, envelope.get(DATA_KEY), replyTo instanceof String replyChannel ? replyChannel : null);
        } else {
            dispatch(channel, handler, data, null);
        }
    }

    /**
     * Publishes a value on a channel: queues it for every subscribed client and wakes each one's waiting connect.
     * A subscriber whose queue already holds {@value #MAX_QUEUED_DELIVERIES} deliveries does not get it: that client
     * is removed with its subscriptions and queue and logged at WARN, and the other subscribers still receive the
     * value. When no subscriber took the value, a cached channel holds it for its next subscriber (at most
     * {@value #MAX_HELD_MESSAGES} values; one arriving at a full hold is dropped and logged at WARN, as the
     * connector's bounded cache refused it), and any other channel drops it.
     *
     * @param channel the exact channel name
     * @param data the value
     */
    public void publish(String channel, Object data) {
        Objects.requireNonNull(channel, "channel");
        expireIdleClients();
        boolean cached = cacheMessagesChannels.contains(channel);
        List<String> overflowed = new ArrayList<>(0);
        boolean delivered;
        boolean held = false;
        // The "nobody took it" check and the hold are atomic with every subscription on the same channel.
        synchronized (stateLock) {
            delivered = deliver(channel, data, overflowed);
            if (!delivered && cached) {
                held = hold(channel, data);
            }
        }
        overflowed.forEach(AjaxChannelBroker::logOverflow);
        if (delivered || held) {
            return;
        }
        if (cached) {
            LOGGER.warn("Ajax channel {} already holds {} messages for its first subscriber; message dropped",
                    loggable(channel), MAX_HELD_MESSAGES);
        } else {
            LOGGER.debug("No subscriber on Ajax channel {}; message dropped", loggable(channel));
        }
    }

    /**
     * Hands one publication to its handler on {@link #executor}, with its response timeout armed on
     * {@link #timeouts} first, so no call can run without one. A refused publication is logged and never retried.
     */
    private void dispatch(String channel, InboundHandler handler, Object payload, String replyChannel) {
        HandlerCall call = new HandlerCall(channel, handler, payload, replyChannel);
        try {
            call.timeout = timeouts.schedule(call::expire, responseTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException rejected) {
            // The timeout scheduler's queue is unbounded, so it refuses work only once shutdown() has run.
            LOGGER.info("Ajax handler for channel {} failed: {}", loggable(channel), SHUT_DOWN_REASON);
            return;
        }
        try {
            executor.execute(call);
        } catch (RejectedExecutionException rejected) {
            call.refuse(executor.isShutdown() ? SHUT_DOWN_REASON : HANDLERS_BUSY_REASON);
        }
    }

    /**
     * Logs a handler failure at INFO and DEBUG by its type identifiers alone. Neither line carries the exception's
     * message, its causes' messages, its suppressed exceptions or the exception itself: handlers receive client
     * payloads, which their exception texts can repeat, and those texts can carry line breaks that forge log lines.
     */
    private static void logFailure(String channel, Throwable failure) {
        String safeChannel = loggable(channel);
        LOGGER.info("Ajax handler for channel {} failed: {}", safeChannel, describe(failure, false));
        LOGGER.debug("Ajax handler failure on channel {}: {}", safeChannel, describe(failure, true));
    }

    /**
     * Queues a value for every subscriber of a channel and removes each subscriber whose queue was full. The caller
     * holds {@link #stateLock}, so every subscriber is a known, open client.
     *
     * @param overflowed receives the IDs of the clients removed because their queue was full, for logging once the
     *        lock is released
     * @return whether at least one subscriber accepted the value
     */
    private boolean deliver(String channel, Object data, List<String> overflowed) {
        Set<String> subscriberIds = subscribersByChannel.get(channel);
        if (subscriberIds == null) {
            return false;
        }
        List<Delivery> delivery = List.of(new Delivery(channel, data));
        boolean delivered = false;
        for (String subscriberId : subscriberIds) {
            EnqueueOutcome outcome = clients.get(subscriberId).enqueue(delivery);
            if (outcome == EnqueueOutcome.ACCEPTED) {
                delivered = true;
            } else if (outcome == EnqueueOutcome.OVERFLOW) {
                overflowed.add(subscriberId);
            }
        }
        // Removed after the loop, because a removal changes the subscriber set being iterated.
        overflowed.forEach(clientId -> removeClient(clientId, clients.get(clientId)));
        return delivered;
    }

    /**
     * Adds a value to a cached channel's hold. The caller holds {@link #stateLock}.
     *
     * @return {@code false} when the hold is full and the value was not added
     */
    private boolean hold(String channel, Object data) {
        List<Object> held = heldMessages.computeIfAbsent(channel, key -> new ArrayList<>());
        if (held.size() >= MAX_HELD_MESSAGES) {
            return false;
        }
        held.add(data);
        return true;
    }

    /** Records a subscription on both sides. The caller holds {@link #stateLock}. */
    private void addSubscriber(String clientId, ClientSession session, String channel) {
        session.channels.add(channel);
        subscribersByChannel.computeIfAbsent(channel, key -> new HashSet<>()).add(clientId);
    }

    /** Drops a client from a channel's subscriber set. The caller holds {@link #stateLock}. */
    private void removeSubscriber(String clientId, String channel) {
        subscribersByChannel.computeIfPresent(channel, (key, subscriberIds) -> {
            subscriberIds.remove(clientId);
            return subscriberIds.isEmpty() ? null : subscriberIds;
        });
    }

    /**
     * Closes a session, releasing its waiting connect and queue, and forgets the client with all its subscriptions.
     * The caller holds {@link #stateLock}, so no other thread can observe the client half removed.
     */
    private void removeClient(String clientId, ClientSession session) {
        session.close();
        clients.remove(clientId, session);
        session.channels.forEach(channel -> removeSubscriber(clientId, channel));
        session.channels.clear();
    }

    /**
     * Removes clients idle for longer than {@code ajax.max-interval-ms}, at most once per half interval, so the
     * queue of a client that went away without {@code /meta/disconnect} is released. Each session decides and closes
     * itself under its own lock, so activity racing with the sweep either keeps the client or finds it removed.
     */
    private void expireIdleClients() {
        long now = System.nanoTime();
        long due = nextSweepNanos.get();
        if (now - due < 0 || !nextSweepNanos.compareAndSet(due, now + sweepIntervalNanos)) {
            return;
        }
        List<String> expired = new ArrayList<>(0);
        synchronized (stateLock) {
            clients.forEach((clientId, session) -> {
                if (session.closeIfIdle(maxIntervalNanos)) {
                    removeClient(clientId, session);
                    expired.add(clientId);
                }
            });
        }
        expired.forEach(clientId ->
                LOGGER.debug("Ajax client {} expired: no connect within the max interval", loggable(clientId)));
    }

    /** Logs the removal of a client whose queue overflowed; called once {@link #stateLock} is released. */
    private static void logOverflow(String clientId) {
        LOGGER.warn("Ajax client {} exceeded {} queued deliveries without a connect; client removed with its"
                + " subscriptions", loggable(clientId), MAX_QUEUED_DELIVERIES);
    }

    private ClientSession requireSession(String clientId) {
        ClientSession session = clientId == null ? null : clients.get(clientId);
        if (session == null) {
            throw new IllegalArgumentException("Unknown Ajax client " + clientId);
        }
        return session;
    }

    private static long positive(String key, long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be positive, was " + value);
        }
        return value;
    }

    /**
     * Makes a channel name, client ID or class, method or file name safe to log on one line. Applied where a value
     * is logged only: channel names and client IDs stay unchanged as routing keys.
     *
     * <p>ISO control characters (C0, DEL and C1, so CR, LF, TAB, NUL and NEL included), line and paragraph
     * separators, format characters (bidirectional overrides and isolates, zero-width characters, the byte order
     * mark), unpaired surrogates and the reverse solidus are each written as {@code \}{@code u} and four lower-case
     * hex digits per UTF-16 unit; escaping the reverse solidus keeps an escape in the log distinguishable from the
     * same six characters in the value. Every other character, a valid surrogate pair included, is copied unchanged.
     *
     * @param value the value to log; {@code null} is written as {@code (null)}
     * @return the escaped value, cut before the first character or escape that would take it past
     *         {@link #LOG_TEXT_LIMIT} characters, never inside a surrogate pair or an escape, with
     *         {@code ...[truncated]} appended when cut
     */
    private static String loggable(String value) {
        if (value == null) {
            return NULL_LOG_TOKEN;
        }
        StringBuilder out =
                new StringBuilder(Math.min(value.length(), LOG_TEXT_LIMIT) + TRUNCATION_LOG_MARKER.length());
        int i = 0;
        while (i < value.length()) {
            int codePoint = value.codePointAt(i);
            int units = Character.charCount(codePoint);
            boolean escape = mustEscape(codePoint);
            if (out.length() + (escape ? 6 * units : units) > LOG_TEXT_LIMIT) {
                return out.append(TRUNCATION_LOG_MARKER).toString();
            }
            for (int unit = i; unit < i + units; unit++) {
                if (escape) {
                    appendUnicodeEscape(out, value.charAt(unit));
                } else {
                    out.append(value.charAt(unit));
                }
            }
            i += units;
        }
        return out.toString();
    }

    /** Whether {@link #loggable(String)} writes a code point as escapes rather than copying it. */
    private static boolean mustEscape(int codePoint) {
        if (codePoint == '\\' || Character.isISOControl(codePoint)) {
            return true;
        }
        int type = Character.getType(codePoint);
        return type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.FORMAT
                || type == Character.SURROGATE;
    }

    /** Appends {@code \}{@code u} and the four lower-case hex digits of one UTF-16 unit. */
    private static void appendUnicodeEscape(StringBuilder out, char c) {
        out.append("\\u")
                .append(HEX_DIGITS[(c >> 12) & 0xF])
                .append(HEX_DIGITS[(c >> 8) & 0xF])
                .append(HEX_DIGITS[(c >> 4) & 0xF])
                .append(HEX_DIGITS[c & 0xF]);
    }

    /**
     * Describes a handler failure by its type identifiers alone: its class name and throw-site frame, then the class
     * name of each cause, with each cause's throw-site frame too when {@code causeThrowSites} is set, separated by
     * {@code " <- "}, for example {@code java.lang.IllegalStateException at a.b.C.d(C.java:18) <- java.io.IOException}.
     * No message, localized message or {@code toString()} of any exception and nothing of its suppressed exceptions
     * is read. The walk stops at a cause already named, marked {@code [circular]}, and after {@link #CAUSE_LOG_LIMIT}
     * causes, marked {@code ...}, so cyclic and very deep chains terminate; a stack trace or cause that cannot be
     * read is marked {@code [unavailable]}. Never throws, so a hostile exception cannot end the thread logging it.
     */
    private static String describe(Throwable failure, boolean causeThrowSites) {
        StringBuilder out = new StringBuilder(128);
        out.append(loggable(failure.getClass().getName()));
        appendThrowSite(out, failure);
        Set<Throwable> named = Collections.newSetFromMap(new IdentityHashMap<>());
        named.add(failure);
        int causes = 0;
        Throwable current = failure;
        while (true) {
            Throwable cause;
            try {
                cause = current.getCause();
            } catch (RuntimeException unreadable) {
                out.append(CAUSE_SEPARATOR).append(UNAVAILABLE_LOG_MARKER);
                break;
            }
            if (cause == null) {
                break;
            }
            out.append(CAUSE_SEPARATOR);
            if (causes == CAUSE_LOG_LIMIT) {
                out.append(MORE_CAUSES_MARKER);
                break;
            }
            if (!named.add(cause)) {
                out.append(CIRCULAR_CAUSE_MARKER);
                break;
            }
            out.append(loggable(cause.getClass().getName()));
            if (causeThrowSites) {
                appendThrowSite(out, cause);
            }
            causes++;
            current = cause;
        }
        return out.toString();
    }

    /**
     * Appends {@code " at <class>.<method>(<file>:<line>)"} for the frame that threw {@code failure}, in the form
     * {@link StackTraceElement#toString()} uses but without its class loader and module prefix, nothing when the
     * exception has no stack trace, or {@code " at [unavailable]"} when its stack trace cannot be read.
     */
    private static void appendThrowSite(StringBuilder out, Throwable failure) {
        StackTraceElement frame;
        try {
            StackTraceElement[] trace = failure.getStackTrace();
            frame = trace == null || trace.length == 0 ? null : trace[0];
        } catch (RuntimeException unreadable) {
            out.append(" at ").append(UNAVAILABLE_LOG_MARKER);
            return;
        }
        if (frame == null) {
            return;
        }
        out.append(" at ")
                .append(loggable(frame.getClassName()))
                .append('.')
                .append(loggable(frame.getMethodName()))
                .append('(');
        if (frame.isNativeMethod()) {
            out.append("Native Method");
        } else if (frame.getFileName() == null) {
            out.append("Unknown Source");
        } else {
            out.append(loggable(frame.getFileName()));
            if (frame.getLineNumber() >= 0) {
                out.append(':').append(frame.getLineNumber());
            }
        }
        out.append(')');
    }

    /** Creates the handler threads, named {@code ajax-channel-broker-<n>}. */
    private static ThreadFactory daemonThreads() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> daemonThread(runnable, "ajax-channel-broker-" + counter.incrementAndGet());
    }

    /** A daemon thread, so the broker's threads never keep the JVM alive. */
    private static Thread daemonThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    /**
     * One publication's handler call. It runs on {@link #executor}; its response timeout on {@link #timeouts}
     * cancels it, interrupting a running handler or keeping a waiting call from ever starting; on completion it
     * publishes a non-null result on the reply channel or logs the failure. Exactly one of completion, timeout and
     * refusal wins the call's single state transition, so each call is answered or logged once and never retried.
     */
    private final class HandlerCall extends FutureTask<Object> {

        private final String channel;
        private final String replyChannel;

        /**
         * The armed response timeout. Set before the call is handed to the executor, so a completion always sees
         * it; only the timeout's own run can find it still unset.
         */
        private volatile Future<?> timeout;

        /** A call of {@code handler} with {@code payload}, answered on {@code replyChannel} when that is set. */
        HandlerCall(String channel, InboundHandler handler, Object payload, String replyChannel) {
            super(() -> handler.handle(payload));
            this.channel = channel;
            this.replyChannel = replyChannel;
        }

        /** The response timeout: cancels the call unless it has already completed, and logs that at INFO. */
        void expire() {
            if (cancel(true)) {
                // A cancelled call still waiting is taken off the queue at once, freeing its place.
                executor.remove(this);
                LOGGER.info("Ajax handler for channel {} did not respond within {} ms", loggable(channel),
                        responseTimeoutMs);
            }
        }

        /** The executor refused the call: it never runs, and the refusal is logged unless the timeout came first. */
        void refuse(String reason) {
            if (cancel(false)) {
                LOGGER.info("Ajax handler for channel {} failed: {}", loggable(channel), reason);
            }
        }

        /**
         * Runs once, when the call completes, fails or is cancelled: disarms the timeout, then replies or logs the
         * failure. A cancelled call stops here; whatever its interrupted handler returns or throws later is
         * discarded, because the call's outcome is already fixed.
         */
        @Override
        protected void done() {
            Future<?> armed = timeout;
            if (armed != null) {
                armed.cancel(false);
            }
            if (isCancelled()) {
                return;
            }
            try {
                Object result = get();
                if (result != null && !disableReplyTo && replyChannel != null) {
                    publish(replyChannel, result);
                }
            } catch (ExecutionException failed) {
                logFailure(channel, failed.getCause());
            } catch (InterruptedException unreachable) {
                // get() on a completed call returns at once without waiting, so it is never interrupted; the
                // interrupt status is kept should that ever change.
                Thread.currentThread().interrupt();
            }
        }
    }

    /** How a client's queue received deliveries offered to it. */
    private enum EnqueueOutcome {

        /** Every delivery was queued and the waiting connect woken. */
        ACCEPTED,

        /** Nothing was queued: the session had already been closed. */
        CLOSED,

        /**
         * Nothing was queued: the deliveries would have taken the queue past {@link #MAX_QUEUED_DELIVERIES}, so the
         * session has closed itself, releasing its queue and waiting connect. The broker must remove the client.
         */
        OVERFLOW
    }

    /**
     * One client's subscriptions, queued deliveries and waiting connect.
     *
     * <p>{@link #channels} is guarded by the broker's {@code stateLock}; everything else by the session's own lock,
     * which every activity takes, so activity is ordered with {@link #closeIfIdle(long)}: it either happens first and
     * keeps the session open, or finds it closed.
     */
    private static final class ClientSession {

        /** The channels this client subscribes to. Guarded by the broker's {@code stateLock}. */
        private final Set<String> channels = new HashSet<>();
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition changed = lock.newCondition();
        private final ArrayDeque<Delivery> queue = new ArrayDeque<>();
        private long lastActivityNanos = System.nanoTime();
        private long waiterGeneration;
        private int waiters;
        private boolean closed;

        /** Records activity of the client, postponing its idle expiry. */
        void touch() {
            lock.lock();
            try {
                lastActivityNanos = System.nanoTime();
            } finally {
                lock.unlock();
            }
        }

        /**
         * Queues deliveries all together or not at all, and wakes the waiting connect.
         *
         * @param deliveries the deliveries, in order
         * @return {@link EnqueueOutcome#OVERFLOW} after closing the session when the queue cannot take all of them
         *         within {@link #MAX_QUEUED_DELIVERIES}
         */
        EnqueueOutcome enqueue(List<Delivery> deliveries) {
            lock.lock();
            try {
                if (closed) {
                    return EnqueueOutcome.CLOSED;
                }
                if (queue.size() + deliveries.size() > MAX_QUEUED_DELIVERIES) {
                    closeLocked();
                    return EnqueueOutcome.OVERFLOW;
                }
                queue.addAll(deliveries);
                changed.signalAll();
                return EnqueueOutcome.ACCEPTED;
            } finally {
                lock.unlock();
            }
        }

        /** Returns and removes every queued delivery, recording the activity. */
        List<Delivery> drain() {
            lock.lock();
            try {
                lastActivityNanos = System.nanoTime();
                return drainLocked();
            } finally {
                lock.unlock();
            }
        }

        List<Delivery> await(long timeoutMs) throws InterruptedException {
            lock.lock();
            try {
                long generation = ++waiterGeneration;
                changed.signalAll();
                waiters++;
                lastActivityNanos = System.nanoTime();
                try {
                    long remaining = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
                    while (queue.isEmpty() && !closed && generation == waiterGeneration && remaining > 0) {
                        remaining = changed.awaitNanos(remaining);
                    }
                } finally {
                    waiters--;
                    lastActivityNanos = System.nanoTime();
                }
                if (closed || generation != waiterGeneration) {
                    return List.of();
                }
                return drainLocked();
            } finally {
                lock.unlock();
            }
        }

        /**
         * Closes the session when it has no waiting connect and no activity for longer than the interval. The clock is
         * read under the session's lock, after any activity that took the lock first, so the decision and the close
         * are one step that no activity can fall between.
         *
         * @param intervalNanos the longest allowed inactivity
         * @return whether the session was closed
         */
        boolean closeIfIdle(long intervalNanos) {
            lock.lock();
            try {
                if (waiters > 0 || System.nanoTime() - lastActivityNanos <= intervalNanos) {
                    return false;
                }
                closeLocked();
                return true;
            } finally {
                lock.unlock();
            }
        }

        /** Closes the session, discarding its queue and releasing its waiting connect with an empty result. */
        void close() {
            lock.lock();
            try {
                closeLocked();
            } finally {
                lock.unlock();
            }
        }

        /** {@link #close()} for a caller already holding the session's lock. */
        private void closeLocked() {
            closed = true;
            queue.clear();
            changed.signalAll();
        }

        private List<Delivery> drainLocked() {
            if (queue.isEmpty()) {
                return List.of();
            }
            List<Delivery> deliveries = new ArrayList<>(queue);
            queue.clear();
            return deliveries;
        }
    }
}
