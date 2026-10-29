package com.mulesoft.examples.service_orchestration_and_choice_routing.service;

import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import java.util.concurrent.atomic.AtomicReference;
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
 * <p>Clients reach channels only the way the copied pages use them. A channel whose name contains {@code #} is the
 * private reply channel of the client whose ID follows its last {@code #}, and only that client may subscribe to it;
 * every other channel is public. A client publishes only on an inbound channel, one with a registered handler, and
 * an rpc envelope's {@code replyTo} is honoured only when it is the publishing client's own reply channel of that
 * inbound channel, {@code <channel>#<clientId>}: any other {@code replyTo} still runs the handler, whose result then
 * goes nowhere. Everything delivered to subscribers, replies included, is published by the server through
 * {@link #publish(String, Object)}. Callers stay anonymous: these rules bind the client IDs the handshake issues to
 * their own reply channels and add no authentication.
 *
 * <p>Every allocation a client can cause is bounded, and each bound is checked before anything is allocated for the
 * request it refuses:
 * <ul>
 *   <li>at most {@value #MAX_CLIENTS} clients are registered at once; a handshake finding that many after idle
 *       clients have expired is refused;</li>
 *   <li>a subscription names channels of at most {@value #MAX_CHANNEL_NAME_LENGTH} characters, one client subscribes
 *       to at most {@value #MAX_SUBSCRIPTIONS_PER_CLIENT} channels, and all clients together hold at most
 *       {@value #MAX_SUBSCRIPTIONS} subscriptions; a channel the client already subscribes to is not counted again,
 *       and a subscription naming several channels is refused whole;</li>
 *   <li>a client holds at most {@value #MAX_QUEUED_DELIVERIES} deliveries of at most
 *       {@value #MAX_QUEUED_BYTES_PER_CLIENT} estimated bytes between two connects; the delivery that would exceed
 *       either removes the client with its subscriptions and queue, as a client that stopped connecting, logged at
 *       WARN, while every other subscriber still receives it;</li>
 *   <li>a cached channel holds at most {@value #MAX_HELD_MESSAGES} messages of at most {@value #MAX_HELD_BYTES}
 *       estimated bytes for its first subscriber, so a full hold always fits a fresh subscriber's queue;</li>
 *   <li>at most {@value #MAX_RUNNING_HANDLERS} inbound handlers run at once and at most
 *       {@value #MAX_QUEUED_HANDLER_CALLS} publications wait for one; a publication beyond that is refused, logged
 *       at INFO and answered with no reply, and never retried;</li>
 *   <li>the client queues, the cached-channel holds and the payloads of dispatched handler calls, waiting or
 *       running, retain at most {@value #MAX_RETAINED_BYTES} estimated bytes together, estimated as
 *       {@link #estimateRetainedBytes(Object, long)} describes: a server publication that would exceed it is dropped
 *       whole and logged at WARN, and a client publication that would exceed it is refused. A handler call's payload
 *       counts until its handler returns or throws, even when its timeout or a shutdown has cancelled it meanwhile,
 *       and a call that never starts counts until it is cancelled or refused.</li>
 * </ul>
 * A refused handshake, subscription or publication throws a {@link ChannelRefusedException} naming its
 * {@link RefusalReason}, which the controller answers in-band. Idle expiry ({@code ajax.max-interval-ms}) releases
 * each client that stops connecting and is no longer heard from, with its subscriptions and queue.
 *
 * <p>The rules and bounds differ from the original transport for hostile input only. Its Jetty cometd 6.1.26 server
 * let any client subscribe and publish to any channel outside {@code /meta/**} and set no client, subscription or
 * byte quota; the copied pages subscribe to public channels and to their own reply channels, publish {@code mule.rpc}
 * envelopes naming their own reply channel on the configured inbound channels, and stay far inside every bound.
 *
 * <p>Diagnostics never carry a handler exception's message or the exception itself, only its type and throw-site
 * identifiers, and every logged channel name or client ID is escaped and bounded, because both can come from a
 * client and could otherwise forge log lines.
 *
 * <p>Bayeux framing (message ids, {@code successful}, {@code advice}, {@code /meta/*} dispatch and JSON) belongs to
 * the controller; this class works with client IDs, channel names and plain values only. All state is in memory and
 * lost on restart, as the connector's was. Every public method is thread-safe, and a client's lifecycle
 * (subscription, held-message transfer, publication to it, restored deliveries, disconnect, overflow and idle
 * expiry) is one atomic sequence of steps under a single lock.
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

    /**
     * Clients registered at once. A copied page opens one client per browser tab, and a client that stops
     * connecting expires after {@code ajax.max-interval-ms}, so a demonstration stays far below it; it bounds the
     * sessions repeated handshakes can create.
     */
    static final int MAX_CLIENTS = 1000;

    /** Longest channel name a client may subscribe to, in characters; the copied pages' channels are far shorter. */
    static final int MAX_CHANNEL_NAME_LENGTH = 256;

    /** Distinct channels one client may subscribe to; a copied page subscribes to a handful. */
    static final int MAX_SUBSCRIPTIONS_PER_CLIENT = 64;

    /** Subscriptions of all clients together. */
    static final int MAX_SUBSCRIPTIONS = 16384;

    /** Estimated bytes the deliveries queued for one client may retain between two connects: 4 MiB. */
    static final long MAX_QUEUED_BYTES_PER_CLIENT = 4L * 1024 * 1024;

    /**
     * Estimated bytes a cached channel may hold for its first subscriber. It equals
     * {@link #MAX_QUEUED_BYTES_PER_CLIENT}, as {@link #MAX_HELD_MESSAGES} equals {@link #MAX_QUEUED_DELIVERIES}, so a
     * full hold always fits a fresh subscriber's queue.
     */
    static final long MAX_HELD_BYTES = MAX_QUEUED_BYTES_PER_CLIENT;

    /**
     * Estimated bytes the client queues, the cached-channel holds and the payloads of dispatched handler calls may
     * retain together: 64 MiB.
     */
    static final long MAX_RETAINED_BYTES = 64L * 1024 * 1024;

    /** Estimated bytes of {@code null}, of a reference to a value already counted, and of a list or array slot. */
    private static final long REFERENCE_BYTES = 8;

    /** Estimated bytes of a String or other character sequence besides its characters: headers and fields. */
    private static final long TEXT_BYTES = 40;

    /** Estimated bytes per character of a String or other character sequence: one UTF-16 unit. */
    private static final long BYTES_PER_CHAR = 2;

    /** Estimated bytes of a number, boolean or character value. */
    private static final long SCALAR_BYTES = 24;

    /** Estimated bytes of a map, collection or array besides its elements. */
    private static final long CONTAINER_BYTES = 48;

    /** Estimated bytes of one map entry besides its key and value: the entry object and its table slot. */
    private static final long MAP_ENTRY_BYTES = 40;

    /** Estimated bytes per element of a primitive array: the widest primitive. */
    private static final long PRIMITIVE_ELEMENT_BYTES = 8;

    /** Estimated bytes of any other value, which the broker never looks into. */
    private static final long OTHER_BYTES = 64;

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

    /**
     * The number of entries of {@link #clients}, at most {@value #MAX_CLIENTS}: {@link #handshake()} takes a slot
     * before it creates a session, and the removal that takes a session out of {@link #clients} gives the slot back.
     */
    private final AtomicInteger clientCount = new AtomicInteger();

    /** The estimated bytes retained by client queues, cached-channel holds and dispatched handler payloads. */
    private final RetainedBytes retained = new RetainedBytes(MAX_RETAINED_BYTES);

    /** The subscriber IDs of each channel. Guarded by {@link #stateLock}. */
    private final Map<String, Set<String>> subscribersByChannel = new HashMap<>();

    /** All clients' subscriptions together, at most {@value #MAX_SUBSCRIPTIONS}. Guarded by {@link #stateLock}. */
    private int subscriptionCount;

    /** Messages held per cached channel, in publish order. Guarded by {@link #stateLock}. */
    private final Map<String, Hold> heldMessages = new HashMap<>();

    /**
     * Guards {@link #subscribersByChannel}, {@link #subscriptionCount}, every session's channel set and
     * {@link #heldMessages}, and is held by every client removal (disconnect, idle expiry, queue overflow, shutdown),
     * every publication and every restore, so that a subscription, a held-message transfer and a removal can never
     * interleave.
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
     * bound its blocking I/O and honour interruption; one that ignores interruption keeps its thread and its
     * payload's reserved bytes until it returns, and while every thread or the retained bytes are held that way
     * further publications wait or are refused.
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

    /** Why the broker refused a client's handshake, subscription or publication. */
    public enum RefusalReason {

        /** A handshake found {@value AjaxChannelBroker#MAX_CLIENTS} clients registered. */
        CLIENT_LIMIT,

        /** A subscription named the private reply channel of another client. */
        FOREIGN_PRIVATE_CHANNEL,

        /** A subscription named a channel longer than {@value AjaxChannelBroker#MAX_CHANNEL_NAME_LENGTH} characters. */
        CHANNEL_NAME_TOO_LONG,

        /**
         * A subscription would have given the client more than {@value AjaxChannelBroker#MAX_SUBSCRIPTIONS_PER_CLIENT}
         * channels, or all clients more than {@value AjaxChannelBroker#MAX_SUBSCRIPTIONS} subscriptions.
         */
        SUBSCRIPTION_LIMIT,

        /** A client published on a channel without an inbound handler. */
        NOT_INBOUND,

        /**
         * A client publication's payload would have taken the retained bytes past
         * {@value AjaxChannelBroker#MAX_RETAINED_BYTES}.
         */
        RETAINED_BYTES
    }

    /**
     * A client request the broker refused by one of its channel rules or bounds, before allocating anything for it.
     * Its message names the rule only, never a client-supplied value, and it carries no stack trace: refusals are
     * routine under hostile load, and the controller needs only the {@link #reason()} to answer in-band.
     */
    public static final class ChannelRefusedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** Why the request was refused. */
        private final RefusalReason reason;

        /**
         * A refusal minted by the broker alone.
         *
         * @param reason why the request was refused
         * @param message the rule that refused it, free of client-supplied text
         */
        ChannelRefusedException(RefusalReason reason, String message) {
            super(message, null, false, false);
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        /**
         * Why the request was refused.
         *
         * @return the reason, never {@code null}
         */
        public RefusalReason reason() {
            return reason;
        }
    }

    /**
     * The claim one held {@code /meta/connect} holds on its client's deliveries, from {@link #claimConnect(String)}.
     * It stays current until the client's next claim or its removal, and only a current claim can take deliveries.
     * It is opaque: only the broker mints and checks claims.
     */
    public static final class ConnectClaim {

        /** The claim of a {@code null} or unknown client. It is never current, so it waits for and takes nothing. */
        private static final ConnectClaim UNCLAIMED = new ConnectClaim(null, 0L);

        /** The session the claim was minted on; {@code null} only for {@link #UNCLAIMED}. */
        private final ClientSession session;

        /** The session's claim counter when the claim was minted. */
        private final long generation;

        /**
         * A claim minted by a session alone, under its lock.
         *
         * @param session the session the claim is minted on, {@code null} only for {@link #UNCLAIMED}
         * @param generation the session's claim counter for this claim
         */
        private ConnectClaim(ClientSession session, long generation) {
            this.session = session;
            this.generation = generation;
        }
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
     * empty result and each waiting {@link #awaitDeliveries(ConnectClaim, long)} call with {@code false}. A later
     * publication on an inbound channel is refused and logged at INFO. The dropped calls' payload bytes are
     * released at once; a running handler's stay reserved until it returns or throws.
     */
    @PreDestroy
    public void shutdown() {
        // A waiting call never runs; cancelling it completes it, which releases its reserved payload bytes.
        for (Runnable waiting : executor.shutdownNow()) {
            if (waiting instanceof Future<?> call) {
                call.cancel(false);
            }
        }
        timeouts.shutdownNow();
        synchronized (stateLock) {
            clients.forEach(this::removeClient);
        }
    }

    /**
     * Registers a new client, once idle clients have expired, unless {@value #MAX_CLIENTS} clients are registered.
     *
     * @return the new client's ID, 32 lower-case hexadecimal characters
     * @throws ChannelRefusedException with {@link RefusalReason#CLIENT_LIMIT} when {@value #MAX_CLIENTS} clients are
     *         registered; no session is created for the refused handshake
     */
    public String handshake() {
        expireIdleClients();
        if (!takeClientSlot()) {
            throw new ChannelRefusedException(RefusalReason.CLIENT_LIMIT,
                    "Ajax handshake refused: " + MAX_CLIENTS + " clients are registered");
        }
        ClientSession session = new ClientSession(retained);
        String clientId;
        do {
            clientId = UUID.randomUUID().toString().replace("-", "");
        } while (clients.putIfAbsent(clientId, session) != null);
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
     * Subscribes a client to one channel, as {@link #subscribe(String, List)} does.
     *
     * @param clientId a known client ID
     * @param channel the exact channel name
     * @throws NullPointerException when {@code channel} is {@code null}
     * @throws IllegalArgumentException when the client is unknown or removed by the held messages, see
     *         {@link #subscribe(String, List)}
     * @throws ChannelRefusedException when the subscription is refused, see {@link #subscribe(String, List)}
     */
    public void subscribe(String clientId, String channel) {
        Objects.requireNonNull(channel, "channel");
        subscribe(clientId, List.of(channel));
    }

    /**
     * Subscribes a client to channels. The messages held on the cached ones among them are delivered to this
     * subscriber in channel order, each channel's in publish order, and those holds are cleared.
     *
     * <p>The channels are admitted whole before any is subscribed: each must be at most
     * {@value #MAX_CHANNEL_NAME_LENGTH} characters long and either public or the client's own private reply channel
     * (its name ends in {@code #<clientId>} after its last {@code #}), and the channels the client does not subscribe
     * to yet must fit both {@value #MAX_SUBSCRIPTIONS_PER_CLIENT} channels per client and
     * {@value #MAX_SUBSCRIPTIONS} subscriptions in all. A channel named twice, or already subscribed, counts once.
     *
     * <p>The client lookup, the admission, the subscriptions and the transfer are one atomic step, so a client removed
     * concurrently either loses the subscriptions with the rest of its state or is reported unknown here, and held
     * messages are never handed to a removed client. The holds are cleared only once the client's queue has accepted
     * all of them.
     *
     * @param clientId a known client ID
     * @param channels the exact channel names
     * @throws NullPointerException when {@code channels} or one of its elements is {@code null}
     * @throws IllegalArgumentException when the client is unknown, or when the held messages would take its queue past
     *         {@value #MAX_QUEUED_DELIVERIES} deliveries or {@value #MAX_QUEUED_BYTES_PER_CLIENT} estimated bytes, in
     *         which case the client is removed and the channels keep holding the messages for their next subscriber
     * @throws ChannelRefusedException with {@link RefusalReason#CHANNEL_NAME_TOO_LONG},
     *         {@link RefusalReason#FOREIGN_PRIVATE_CHANNEL} or {@link RefusalReason#SUBSCRIPTION_LIMIT} when a channel
     *         is not admitted; the client then subscribes to none of them and stays registered
     */
    public void subscribe(String clientId, List<String> channels) {
        Objects.requireNonNull(channels, "channels");
        channels.forEach(channel -> Objects.requireNonNull(channel, "channel"));
        Set<String> requested = new LinkedHashSet<>(channels);
        EnqueueOutcome overflow;
        synchronized (stateLock) {
            ClientSession session = requireSession(clientId);
            session.touch();
            admit(clientId, session, requested);
            List<QueuedDelivery> transfer = new ArrayList<>(0);
            for (String channel : requested) {
                addSubscriber(clientId, session, channel);
                Hold hold = heldMessages.get(channel);
                if (hold != null) {
                    hold.messages.forEach(held ->
                            transfer.add(new QueuedDelivery(new Delivery(channel, held.data()), held.bytes())));
                }
            }
            if (transfer.isEmpty()) {
                return;
            }
            // Held messages go to the first subscriber at once. Mule's dispatcher flushed its cache only with the next
            // message published after a subscription; delivering on subscribe ends the hold as soon as someone listens.
            // Their bytes stay reserved: the hold's share becomes the queue's.
            overflow = session.enqueue(transfer);
            if (overflow == EnqueueOutcome.ACCEPTED) {
                requested.forEach(heldMessages::remove);
                return;
            }
            // The queue cannot take every hold, so the session has closed itself; the client goes with all its
            // subscriptions, these included, and the holds stay intact for the next subscriber.
            removeClient(clientId, session);
        }
        logOverflow(clientId, overflow);
        throw new IllegalArgumentException("Ajax client " + clientId + " was removed: the messages held on its new"
                + " channels would exceed " + describeQueueBound(overflow));
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
                if (session.channels.remove(channel)) {
                    subscriptionCount--;
                    removeSubscriber(clientId, channel);
                }
            }
        }
    }

    /**
     * Removes a client with its subscriptions and queued deliveries, and releases its waiting
     * {@link #awaitMessages(String, long)} call with an empty result or its waiting
     * {@link #awaitDeliveries(ConnectClaim, long)} call with {@code false}. An unknown client is ignored.
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
     * Returns and removes every delivery queued for a client, without regard to any connect's claim. A held connect
     * takes its deliveries with {@link #drain(ConnectClaim)} instead, which takes nothing once its claim is superseded.
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
     * <p>Bayeux allows one outstanding connect per client, so the call claims the client's deliveries as it starts,
     * as {@link #claimConnect(String)} does: a newer call or claim for the same client supersedes it and releases it
     * with an empty list, and the deliveries go to the newest. Whether the call is still the newest and the taking of
     * the queue are one step under the client's lock, so a superseded call never returns a delivery.
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
     * Claims a client's deliveries for one held {@code /meta/connect}, as its request arrives. Bayeux allows one
     * outstanding connect per client, so the claim supersedes every older claim and every waiting
     * {@link #awaitMessages(String, long)} call of the client: an older claim's waiting
     * {@link #awaitDeliveries(ConnectClaim, long)} ends at once with {@code false}, and its
     * {@link #drain(ConnectClaim)} takes nothing from then on. Records activity of the client.
     *
     * @param clientId the client ID
     * @return the connect's claim; for a {@code null} or unknown client, a claim that is never current
     */
    public ConnectClaim claimConnect(String clientId) {
        ClientSession session = clientId == null ? null : clients.get(clientId);
        if (session == null) {
            return ConnectClaim.UNCLAIMED;
        }
        return session.claim();
    }

    /**
     * Waits for deliveries for a held connect without taking any: returns once a delivery is queued, the claim is
     * superseded, the client is removed or {@code timeoutMs} passes, and leaves the queue to
     * {@link #drain(ConnectClaim)}, so the caller takes the deliveries only once it knows its response can still carry
     * them. While it waits, the client counts as having a held connect, which idle expiry spares.
     *
     * @param claim the connect's claim from {@link #claimConnect(String)}
     * @param timeoutMs the longest wait in milliseconds; zero or less does not wait
     * @return {@code true} when the claim was still current as the wait ended, with deliveries queued or the time
     *         passed; {@code false} when a newer claim superseded it, the client was disconnected or removed, or the
     *         claim is that of a {@code null} or unknown client. Either way, {@link #drain(ConnectClaim)} checks the
     *         claim again as it takes the deliveries
     * @throws NullPointerException when {@code claim} is {@code null}
     * @throws InterruptedException when the waiting thread is interrupted
     */
    public boolean awaitDeliveries(ConnectClaim claim, long timeoutMs) throws InterruptedException {
        Objects.requireNonNull(claim, "claim");
        if (claim.session == null) {
            return false;
        }
        return claim.session.awaitClaimed(claim.generation, timeoutMs);
    }

    /**
     * Returns and removes every delivery queued for a client while the held connect's claim is still current.
     * Whether the claim is current and the taking of the queue are one step under the client's lock, so a connect
     * superseded at any moment before, even one whose wait ended current, takes nothing and leaves every delivery for
     * the newer connect. Records activity of the client when the claim is current.
     *
     * @param claim the connect's claim from {@link #claimConnect(String)}
     * @return the deliveries in queue order; empty when nothing is queued, when a newer claim superseded this one,
     *         when the client was disconnected or removed, or for the claim of a {@code null} or unknown client
     * @throws NullPointerException when {@code claim} is {@code null}
     */
    public List<Delivery> drain(ConnectClaim claim) {
        Objects.requireNonNull(claim, "claim");
        if (claim.session == null) {
            return List.of();
        }
        return claim.session.drainClaimed(claim.generation);
    }

    /**
     * Puts back deliveries that were drained for a response that could not carry them: they go to the front of the
     * client's queue in their order, so its next connect receives them ahead of everything queued since, and its
     * waiting connect is woken. Deliveries for an unknown, disconnected or removed client are dropped, as anything
     * queued for it was. Draining released their bytes, so they are estimated and reserved again: when the queue
     * cannot take them all within {@value #MAX_QUEUED_DELIVERIES} deliveries and
     * {@value #MAX_QUEUED_BYTES_PER_CLIENT} estimated bytes, the client is removed with its subscriptions and queue and
     * logged at WARN, as a publication overflowing it would; when the retained bytes cannot take them within
     * {@value #MAX_RETAINED_BYTES}, they are dropped and logged at WARN, and the client keeps its queue.
     *
     * @param clientId the client ID; {@code null} is ignored
     * @param deliveries the deliveries in the order they were drained; an empty list is ignored
     * @throws NullPointerException when {@code deliveries} or one of its elements is {@code null}
     */
    public void restore(String clientId, List<Delivery> deliveries) {
        Objects.requireNonNull(deliveries, "deliveries");
        deliveries.forEach(delivery -> Objects.requireNonNull(delivery, "delivery"));
        if (clientId == null || deliveries.isEmpty()) {
            return;
        }
        List<QueuedDelivery> restored = new ArrayList<>(deliveries.size());
        long bytes = 0;
        for (Delivery delivery : deliveries) {
            long estimate = estimateRetainedBytes(delivery.data(), MAX_RETAINED_BYTES);
            restored.add(new QueuedDelivery(delivery, estimate));
            bytes = saturatedAdd(bytes, estimate);
        }
        EnqueueOutcome outcome;
        synchronized (stateLock) {
            ClientSession session = clients.get(clientId);
            if (session == null) {
                return;
            }
            if (!retained.tryReserve(bytes)) {
                outcome = null;
            } else {
                outcome = session.requeue(restored);
                if (outcome == EnqueueOutcome.ACCEPTED) {
                    return;
                }
                retained.release(bytes);
                if (!outcome.overflowed()) {
                    return;
                }
                // The queue cannot take them, so the session has closed itself; the client goes with all its state.
                removeClient(clientId, session);
            }
        }
        if (outcome == null) {
            LOGGER.warn("Ajax client {}: {} undelivered deliveries dropped; they would take the retained messages and"
                    + " payloads past {} estimated bytes", loggable(clientId), deliveries.size(), MAX_RETAINED_BYTES);
        } else {
            logOverflow(clientId, outcome);
        }
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
     * Accepts a client publication on an inbound channel, in the order received, without blocking the caller.
     *
     * <p>A {@code Map} carrying the key {@code replyTo} is an rpc envelope whose payload is its {@code data}; any other
     * value is the payload itself, with no reply channel. The envelope's {@code replyTo} is the reply channel only
     * when it is a String naming the publishing client's own reply channel of this inbound channel,
     * {@code <channel>#<clientId>}, as {@code mule.rpc} sets it; a String naming any other channel is logged at WARN
     * and the handler runs without a reply channel, as for a {@code replyTo} that is not a String. The payload's
     * estimated bytes stay reserved until the handler returns or throws, even after the call has timed out, or, for
     * a call that never starts, until it is cancelled or refused. The handler runs on the broker's
     * bounded executor within {@code ajax.response-timeout-ms} (see {@link InboundHandler}); a non-null result is
     * published on the reply channel unless replies are disabled. A failure is logged at ERROR; a timeout or a
     * refusal because the executor is saturated or shut down is logged at INFO. None of them produces a reply, and
     * the publication is never retried. The publication reaches the handler only, never the channel's subscribers.
     *
     * @param clientId the publishing client
     * @param channel the exact channel name
     * @param data the published value
     * @throws ChannelRefusedException with {@link RefusalReason#NOT_INBOUND} when the channel has no inbound handler,
     *         or with {@link RefusalReason#RETAINED_BYTES} when the payload would take the retained bytes past
     *         {@value #MAX_RETAINED_BYTES}; the publication then reaches nobody. Either way it counts as activity of
     *         the client
     */
    public void onPublication(String clientId, String channel, Object data) {
        Objects.requireNonNull(channel, "channel");
        ClientSession publisher = clientId == null ? null : clients.get(clientId);
        if (publisher != null) {
            publisher.touch();
        }
        InboundHandler handler = inboundHandlers.get(channel);
        if (handler == null) {
            // Only the server publishes on outbound, reply and unconfigured channels, through publish().
            throw new ChannelRefusedException(RefusalReason.NOT_INBOUND,
                    "Ajax publication refused: the channel has no inbound handler");
        }
        Object payload = data;
        Object replyTo = null;
        if (data instanceof Map<?, ?> envelope && envelope.containsKey(REPLY_TO_KEY)) {
            payload = envelope.get(DATA_KEY);
            replyTo = envelope.get(REPLY_TO_KEY);
        }
        long bytes = estimateRetainedBytes(payload, MAX_RETAINED_BYTES);
        if (!retained.tryReserve(bytes)) {
            throw new ChannelRefusedException(RefusalReason.RETAINED_BYTES, "Ajax publication refused: its payload"
                    + " would take the retained messages and payloads past " + MAX_RETAINED_BYTES + " estimated bytes");
        }
        String replyChannel = null;
        if (replyTo instanceof String requested) {
            if (publisher != null && requested.equals(channel + '#' + clientId)) {
                replyChannel = requested;
            } else {
                LOGGER.warn("Ajax rpc publication from client {} on channel {} names a reply channel other than its"
                        + " own; the handler runs without a reply", loggable(clientId), loggable(channel));
            }
        }
        LOGGER.debug("Ajax publication from client {} dispatched to the handler of channel {}", loggable(clientId),
                loggable(channel));
        dispatch(channel, handler, payload, replyChannel, bytes);
    }

    /**
     * Publishes a value on a channel: queues it for every subscribed client and wakes each one's waiting connect.
     * A subscriber whose queue already holds {@value #MAX_QUEUED_DELIVERIES} deliveries, or would pass
     * {@value #MAX_QUEUED_BYTES_PER_CLIENT} estimated bytes with this one, does not get it: that client is removed with
     * its subscriptions and queue and logged at WARN, and the other subscribers still receive the value. When no
     * subscriber took the value, a cached channel holds it for its next subscriber (at most
     * {@value #MAX_HELD_MESSAGES} values of at most {@value #MAX_HELD_BYTES} estimated bytes; one arriving at a full
     * hold is dropped and logged at WARN, as the connector's bounded cache refused it), and any other channel drops it.
     * A value whose estimated bytes, counted once per subscriber, or once for the hold, would take the retained bytes
     * past {@value #MAX_RETAINED_BYTES} is dropped whole, reaching no subscriber, and logged at WARN.
     *
     * <p>This is the server's own publication, which every outbound and reply message takes; clients publish through
     * {@link #onPublication(String, String, Object)} only.
     *
     * @param channel the exact channel name
     * @param data the value
     */
    public void publish(String channel, Object data) {
        Objects.requireNonNull(channel, "channel");
        expireIdleClients();
        boolean cached = cacheMessagesChannels.contains(channel);
        long bytes = estimateRetainedBytes(data, MAX_RETAINED_BYTES);
        Map<String, EnqueueOutcome> overflowed = new LinkedHashMap<>();
        PublishOutcome outcome;
        // The "nobody took it" check and the hold are atomic with every subscription on the same channel.
        synchronized (stateLock) {
            outcome = deliver(channel, data, bytes, overflowed);
            if (outcome == PublishOutcome.UNDELIVERED && cached) {
                outcome = hold(channel, data, bytes);
            }
        }
        overflowed.forEach(AjaxChannelBroker::logOverflow);
        switch (outcome) {
            case DELIVERED, HELD -> {
                // Taken by a subscriber or kept for the next one.
            }
            case UNDELIVERED -> LOGGER.debug("No subscriber on Ajax channel {}; message dropped", loggable(channel));
            case HOLD_FULL -> LOGGER.warn("Ajax channel {} already holds {} messages for its first subscriber;"
                    + " message dropped", loggable(channel), MAX_HELD_MESSAGES);
            case HOLD_BYTES_FULL -> LOGGER.warn("Ajax channel {} would hold more than {} estimated bytes for its first"
                    + " subscriber; message dropped", loggable(channel), MAX_HELD_BYTES);
            case OVER_BUDGET -> LOGGER.warn("Ajax publication on channel {} dropped: it would take the retained"
                    + " messages and payloads past {} estimated bytes", loggable(channel), MAX_RETAINED_BYTES);
        }
    }

    /**
     * Hands one publication to its handler on {@link #executor}, with its response timeout armed on
     * {@link #timeouts} first, so no call can run without one. A refused publication is logged and never retried.
     * The call owns the {@code bytes} reserved for its payload: a call whose handler starts releases them when the
     * handler returns or throws, even after its timeout or a shutdown, and a call that never starts releases them as
     * it is cancelled or refused.
     */
    private void dispatch(String channel, InboundHandler handler, Object payload, String replyChannel, long bytes) {
        HandlerCall call = new HandlerCall(channel, new HandlerInvocation(handler, payload, bytes), replyChannel);
        try {
            call.timeout = timeouts.schedule(call::expire, responseTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException rejected) {
            // The timeout scheduler's queue is unbounded, so it refuses work only once shutdown() has run. The call
            // never runs; cancelling it completes it, which releases its payload bytes.
            call.cancel(false);
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
     * Logs a handler failure at ERROR, as Mule's default exception strategy reported an exception ending a flow, and
     * its throw sites at DEBUG, by its type identifiers alone. Neither line carries the exception's message, its
     * causes' messages, its suppressed exceptions or the exception itself: handlers receive client payloads, which
     * their exception texts can repeat, and those texts can carry line breaks that forge log lines.
     */
    private static void logFailure(String channel, Throwable failure) {
        String safeChannel = loggable(channel);
        LOGGER.error("Ajax handler for channel {} failed: {}", safeChannel, describe(failure, false));
        LOGGER.debug("Ajax handler failure on channel {}: {}", safeChannel, describe(failure, true));
    }

    /**
     * Queues a value for every subscriber of a channel and removes each subscriber whose queue overflowed. The
     * value's estimated bytes are reserved once per subscriber before any is queued, so the value reaches all of them
     * or, when the retained bytes cannot take that, none; a subscriber that does not take it gives its share back.
     * The caller holds {@link #stateLock}, so every subscriber is a known, open client.
     *
     * @param bytes the value's estimated bytes
     * @param overflowed receives the IDs of the clients removed because their queue overflowed, with the bound each
     *        passed, for logging once the lock is released
     * @return {@link PublishOutcome#DELIVERED} when at least one subscriber took the value,
     *         {@link PublishOutcome#OVER_BUDGET} when it was dropped whole, otherwise
     *         {@link PublishOutcome#UNDELIVERED}
     */
    private PublishOutcome deliver(String channel, Object data, long bytes, Map<String, EnqueueOutcome> overflowed) {
        Set<String> subscriberIds = subscribersByChannel.get(channel);
        if (subscriberIds == null) {
            return PublishOutcome.UNDELIVERED;
        }
        // At most MAX_CLIENTS subscribers, each counted at an estimate that stops soon after MAX_RETAINED_BYTES, so
        // the product stays far below Long.MAX_VALUE.
        if (!retained.tryReserve(bytes * subscriberIds.size())) {
            return PublishOutcome.OVER_BUDGET;
        }
        List<QueuedDelivery> delivery = List.of(new QueuedDelivery(new Delivery(channel, data), bytes));
        boolean delivered = false;
        for (String subscriberId : subscriberIds) {
            EnqueueOutcome outcome = clients.get(subscriberId).enqueue(delivery);
            if (outcome == EnqueueOutcome.ACCEPTED) {
                delivered = true;
                continue;
            }
            retained.release(bytes);
            if (outcome.overflowed()) {
                overflowed.put(subscriberId, outcome);
            }
        }
        // Removed after the loop, because a removal changes the subscriber set being iterated.
        overflowed.keySet().forEach(clientId -> removeClient(clientId, clients.get(clientId)));
        return delivered ? PublishOutcome.DELIVERED : PublishOutcome.UNDELIVERED;
    }

    /**
     * Adds a value to a cached channel's hold, reserving its estimated bytes. The caller holds {@link #stateLock}.
     *
     * @param bytes the value's estimated bytes
     * @return {@link PublishOutcome#HELD}, or why the value was not added
     */
    private PublishOutcome hold(String channel, Object data, long bytes) {
        Hold hold = heldMessages.get(channel);
        if (hold != null && hold.messages.size() >= MAX_HELD_MESSAGES) {
            return PublishOutcome.HOLD_FULL;
        }
        if (bytes > MAX_HELD_BYTES - (hold == null ? 0L : hold.bytes)) {
            return PublishOutcome.HOLD_BYTES_FULL;
        }
        if (!retained.tryReserve(bytes)) {
            return PublishOutcome.OVER_BUDGET;
        }
        if (hold == null) {
            hold = new Hold();
            heldMessages.put(channel, hold);
        }
        hold.messages.add(new Retained(data, bytes));
        hold.bytes += bytes;
        return PublishOutcome.HELD;
    }

    /**
     * Checks that a client may subscribe to every requested channel, before any is subscribed. The caller holds
     * {@link #stateLock}.
     *
     * @param requested the distinct channel names
     * @throws ChannelRefusedException when a channel is too long or another client's private reply channel, or when
     *         the channels the client does not subscribe to yet exceed either subscription bound
     */
    private void admit(String clientId, ClientSession session, Set<String> requested) {
        int added = 0;
        for (String channel : requested) {
            if (channel.length() > MAX_CHANNEL_NAME_LENGTH) {
                throw new ChannelRefusedException(RefusalReason.CHANNEL_NAME_TOO_LONG, "Ajax subscription refused: a"
                        + " channel name is longer than " + MAX_CHANNEL_NAME_LENGTH + " characters");
            }
            if (!isPublicOrOwnedBy(channel, clientId)) {
                throw new ChannelRefusedException(RefusalReason.FOREIGN_PRIVATE_CHANNEL,
                        "Ajax subscription refused: a channel is the private reply channel of another client");
            }
            if (!session.channels.contains(channel)) {
                added++;
            }
        }
        if (added > MAX_SUBSCRIPTIONS_PER_CLIENT - session.channels.size()) {
            throw new ChannelRefusedException(RefusalReason.SUBSCRIPTION_LIMIT, "Ajax subscription refused: a client"
                    + " subscribes to at most " + MAX_SUBSCRIPTIONS_PER_CLIENT + " channels");
        }
        if (added > MAX_SUBSCRIPTIONS - subscriptionCount) {
            throw new ChannelRefusedException(RefusalReason.SUBSCRIPTION_LIMIT, "Ajax subscription refused: all"
                    + " clients together hold at most " + MAX_SUBSCRIPTIONS + " subscriptions");
        }
    }

    /**
     * Whether a channel is public, having no {@code #}, or the private reply channel of {@code clientId}: the text
     * after its last {@code #} is that ID.
     */
    private static boolean isPublicOrOwnedBy(String channel, String clientId) {
        int owner = channel.lastIndexOf('#') + 1;
        return owner == 0 || (channel.length() - owner == clientId.length() && channel.startsWith(clientId, owner));
    }

    /** Records a subscription on both sides, unless it exists. The caller holds {@link #stateLock}. */
    private void addSubscriber(String clientId, ClientSession session, String channel) {
        if (session.channels.add(channel)) {
            subscriptionCount++;
            subscribersByChannel.computeIfAbsent(channel, key -> new HashSet<>()).add(clientId);
        }
    }

    /** Drops a client from a channel's subscriber set. The caller holds {@link #stateLock}. */
    private void removeSubscriber(String clientId, String channel) {
        subscribersByChannel.computeIfPresent(channel, (key, subscriberIds) -> {
            subscriberIds.remove(clientId);
            return subscriberIds.isEmpty() ? null : subscriberIds;
        });
    }

    /**
     * Closes a session, releasing its waiting connect, its queue and the queue's bytes, and forgets the client with
     * all its subscriptions, giving back its client slot. The caller holds {@link #stateLock}, so no other thread can
     * observe the client half removed; a second removal of the same session changes nothing.
     */
    private void removeClient(String clientId, ClientSession session) {
        session.close();
        if (clients.remove(clientId, session)) {
            clientCount.decrementAndGet();
        }
        subscriptionCount -= session.channels.size();
        session.channels.forEach(channel -> removeSubscriber(clientId, channel));
        session.channels.clear();
    }

    /**
     * Takes one of the {@value #MAX_CLIENTS} client slots for a handshake, never more under any concurrency.
     *
     * @return {@code false} when every slot is taken
     */
    private boolean takeClientSlot() {
        int registered;
        do {
            registered = clientCount.get();
            if (registered >= MAX_CLIENTS) {
                return false;
            }
        } while (!clientCount.compareAndSet(registered, registered + 1));
        return true;
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
    private static void logOverflow(String clientId, EnqueueOutcome overflow) {
        LOGGER.warn("Ajax client {} exceeded {} without a connect; client removed with its subscriptions",
                loggable(clientId), describeQueueBound(overflow));
    }

    /** The queue bound an overflow passed, as diagnostics name it. */
    private static String describeQueueBound(EnqueueOutcome overflow) {
        return overflow == EnqueueOutcome.OVERFLOW_BYTES
                ? MAX_QUEUED_BYTES_PER_CLIENT + " estimated bytes of queued deliveries"
                : MAX_QUEUED_DELIVERIES + " queued deliveries";
    }

    /** The sum of two non-negative byte counts, or {@link Long#MAX_VALUE} when it would not fit. */
    private static long saturatedAdd(long first, long second) {
        long sum = first + second;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }

    /**
     * Estimates the heap a value keeps alive while the broker retains it, without serializing it. A String or other
     * character sequence counts {@value #TEXT_BYTES} bytes plus {@value #BYTES_PER_CHAR} per character; a number,
     * boolean or character {@value #SCALAR_BYTES}; {@code null} {@value #REFERENCE_BYTES}; a map
     * {@value #CONTAINER_BYTES} plus {@value #MAP_ENTRY_BYTES} per entry plus its keys and values; a collection or
     * object array {@value #CONTAINER_BYTES} plus {@value #REFERENCE_BYTES} per element plus its elements; a primitive
     * array {@value #CONTAINER_BYTES} plus {@value #PRIMITIVE_ELEMENT_BYTES} per element; any other value
     * {@value #OTHER_BYTES}. A map, collection or array met again, a value nested in itself included, counts as one
     * reference, so the walk ends on any graph.
     *
     * <p>The walk keeps its own stack of open containers rather than recursing, so nesting depth cannot exhaust the
     * thread's stack, and it stops as soon as the running total exceeds {@code limit}, so a value far beyond the
     * bound being checked costs no more than the bound to refuse.
     *
     * @param value the value; any type, {@code null} included
     * @param limit the bound being checked
     * @return the estimate; once it exceeds {@code limit}, a partial total that still exceeds it
     */
    static long estimateRetainedBytes(Object value, long limit) {
        Set<Object> containers = null;
        Deque<Iterator<?>> open = null;
        long total = 0;
        Object current = value;
        while (true) {
            Iterator<?> children = null;
            long bytes;
            if (current == null) {
                bytes = REFERENCE_BYTES;
            } else if (current instanceof CharSequence text) {
                bytes = TEXT_BYTES + BYTES_PER_CHAR * text.length();
            } else if (current instanceof Number || current instanceof Boolean || current instanceof Character) {
                bytes = SCALAR_BYTES;
            } else if (current instanceof Map<?, ?> || current instanceof Collection<?>
                    || current.getClass().isArray()) {
                if (containers == null) {
                    containers = Collections.newSetFromMap(new IdentityHashMap<>());
                }
                if (!containers.add(current)) {
                    bytes = REFERENCE_BYTES;
                } else if (current instanceof Map<?, ?> map) {
                    bytes = CONTAINER_BYTES + MAP_ENTRY_BYTES * map.size();
                    children = new MapContents(map);
                } else if (current instanceof Collection<?> collection) {
                    bytes = CONTAINER_BYTES + REFERENCE_BYTES * collection.size();
                    children = collection.iterator();
                } else if (current instanceof Object[] array) {
                    bytes = CONTAINER_BYTES + REFERENCE_BYTES * array.length;
                    children = Arrays.asList(array).iterator();
                } else {
                    bytes = CONTAINER_BYTES + PRIMITIVE_ELEMENT_BYTES * Array.getLength(current);
                }
            } else {
                bytes = OTHER_BYTES;
            }
            total = saturatedAdd(total, bytes);
            if (total > limit) {
                return total;
            }
            if (children != null) {
                if (open == null) {
                    open = new ArrayDeque<>();
                }
                open.push(children);
            }
            Iterator<?> next = nextOpen(open);
            if (next == null) {
                return total;
            }
            current = next.next();
        }
    }

    /**
     * The innermost open container that still has an element to visit, after closing every exhausted one, or
     * {@code null} when the walk of {@link #estimateRetainedBytes(Object, long)} is complete.
     */
    private static Iterator<?> nextOpen(Deque<Iterator<?>> open) {
        if (open == null) {
            return null;
        }
        while (!open.isEmpty()) {
            Iterator<?> innermost = open.peek();
            if (innermost.hasNext()) {
                return innermost;
            }
            open.pop();
        }
        return null;
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
     * The payload bytes reserved for it are released exactly once by its {@link HandlerInvocation}: when the handler
     * returns or throws, even long after a timeout or shutdown cancelled or interrupted it, or, for a call that never
     * starts, as the cancellation or refusal completes it.
     */
    private final class HandlerCall extends FutureTask<Object> {

        private final String channel;
        private final String replyChannel;

        /** The handler invocation this call runs, which owns the payload's reserved bytes. */
        private final HandlerInvocation invocation;

        /**
         * The armed response timeout. Set before the call is handed to the executor, so a completion always sees
         * it; only the timeout's own run can find it still unset.
         */
        private volatile Future<?> timeout;

        /** A call running {@code invocation}, answered on {@code replyChannel} when that is set. */
        HandlerCall(String channel, HandlerInvocation invocation, String replyChannel) {
            super(invocation);
            this.channel = channel;
            this.replyChannel = replyChannel;
            this.invocation = invocation;
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
         * Runs once, when the call completes, fails or is cancelled: ends the invocation if it never started, which
         * releases the payload's reserved bytes, disarms the timeout, then replies or logs the failure. A cancelled
         * call stops here; whatever its interrupted handler returns or throws later is discarded, because the call's
         * outcome is already fixed, and that handler's payload bytes stay reserved until it actually exits.
         */
        @Override
        protected void done() {
            invocation.endUnstarted();
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

    /**
     * The handler invocation one {@link HandlerCall} runs, owning the estimated bytes reserved for its payload. Its
     * {@link CallPhase} decides without a race whether the handler ever runs: the executor thread takes it from
     * {@code WAITING} to {@code RUNNING} before invoking the handler, while {@link HandlerCall#done()} takes a call
     * completed before that from {@code WAITING} to {@code ENDED}, and only one of the two can succeed. The handler's
     * own thread releases the bytes once the handler has returned or thrown, however long after a timeout or shutdown
     * that is; {@link #endUnstarted()} releases them for a call that never started. Either way they are released
     * exactly once, and the invocation then no longer references the payload.
     */
    private final class HandlerInvocation implements Callable<Object> {

        private final InboundHandler handler;

        /** The estimated payload bytes reserved until the handler exits or the call ends without starting. */
        private final long payloadBytes;

        private final AtomicReference<CallPhase> phase = new AtomicReference<>(CallPhase.WAITING);

        /**
         * The payload, until the invocation starts or ends without starting. Only the thread whose transition out of
         * {@code WAITING} succeeded reads or clears it.
         */
        private Object payload;

        /** An invocation of {@code handler} with {@code payload}, owning the {@code payloadBytes} reserved for it. */
        HandlerInvocation(InboundHandler handler, Object payload, long payloadBytes) {
            this.handler = handler;
            this.payload = payload;
            this.payloadBytes = payloadBytes;
        }

        /**
         * Invokes the handler unless the call has already ended without starting, and releases the payload's
         * reserved bytes once the handler has returned or thrown, whether or not the call was cancelled meanwhile.
         *
         * @return the handler's result, or {@code null} when the call ended before it could start; the result of a
         *         call cancelled meanwhile is discarded by its {@link HandlerCall}
         * @throws Exception whatever the handler throws
         */
        @Override
        public Object call() throws Exception {
            if (!phase.compareAndSet(CallPhase.WAITING, CallPhase.RUNNING)) {
                // Cancelled or refused just before it started: done() has ended it and released its bytes.
                return null;
            }
            Object argument = payload;
            payload = null;
            try {
                return handler.handle(argument);
            } finally {
                phase.set(CallPhase.ENDED);
                retained.release(payloadBytes);
            }
        }

        /**
         * Ends the invocation of a call completed before its handler started, releasing the payload's reserved
         * bytes; once the handler has started this does nothing, and its own thread releases them as it exits.
         */
        void endUnstarted() {
            if (phase.compareAndSet(CallPhase.WAITING, CallPhase.ENDED)) {
                payload = null;
                retained.release(payloadBytes);
            }
        }
    }

    /** Where one {@link HandlerInvocation} stands; it moves only forward. */
    private enum CallPhase {

        /** Not started: the call waits for a handler thread, or has not yet been handed to the executor. */
        WAITING,

        /** The handler has been invoked and has not yet returned or thrown. */
        RUNNING,

        /** The handler has returned or thrown, or the call ended without it ever starting. */
        ENDED
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
        OVERFLOW,

        /**
         * Nothing was queued: the deliveries would have taken the queue past {@link #MAX_QUEUED_BYTES_PER_CLIENT}
         * estimated bytes, so the session has closed itself as for {@link #OVERFLOW}. The broker must remove the
         * client.
         */
        OVERFLOW_BYTES;

        /** Whether the session closed itself on a queue bound, so that the broker must remove the client. */
        boolean overflowed() {
            return this == OVERFLOW || this == OVERFLOW_BYTES;
        }
    }

    /** What became of one server publication. */
    private enum PublishOutcome {

        /** At least one subscriber took it. */
        DELIVERED,

        /** No subscriber took it and the channel holds nothing: dropped. */
        UNDELIVERED,

        /** Nobody took it, and the cached channel holds it for its next subscriber. */
        HELD,

        /** Nobody took it, and the cached channel already holds {@link #MAX_HELD_MESSAGES} messages: dropped. */
        HOLD_FULL,

        /** Nobody took it, and the cached channel's hold would pass {@link #MAX_HELD_BYTES}: dropped. */
        HOLD_BYTES_FULL,

        /** The retained bytes could not take it for every subscriber, or for the hold: dropped whole. */
        OVER_BUDGET
    }

    /**
     * The estimated bytes retained by the client queues, the cached-channel holds and the payloads of dispatched
     * handler calls, never more than its limit. Lock-free, so sessions update it under their own lock without taking
     * another.
     */
    private static final class RetainedBytes {

        private final long limit;
        private final AtomicLong reserved = new AtomicLong();

        RetainedBytes(long limit) {
            this.limit = limit;
        }

        /**
         * Reserves bytes when they fit the limit, atomically with every concurrent reservation.
         *
         * @param bytes non-negative estimated bytes
         * @return {@code false}, reserving nothing, when they would take the total past the limit
         */
        boolean tryReserve(long bytes) {
            long current;
            do {
                current = reserved.get();
                if (bytes > limit - current) {
                    return false;
                }
            } while (!reserved.compareAndSet(current, current + bytes));
            return true;
        }

        /**
         * Releases bytes reserved earlier.
         *
         * @param bytes the non-negative estimated bytes, each released once
         */
        void release(long bytes) {
            if (bytes > 0) {
                reserved.addAndGet(-bytes);
            }
        }
    }

    /**
     * One cached channel's held messages, in publish order, and their estimated bytes, which stay reserved until a
     * subscriber's queue takes them. Guarded by the broker's {@code stateLock}.
     */
    private static final class Hold {

        private final List<Retained> messages = new ArrayList<>();
        private long bytes;
    }

    /**
     * One held message.
     *
     * @param data the published value
     * @param bytes its estimated bytes
     */
    private record Retained(Object data, long bytes) {
    }

    /**
     * One queued delivery with its estimated bytes, which a {@link Delivery} does not carry, so that the
     * queue's bytes are released exactly as they were reserved.
     *
     * @param delivery the delivery handed to the client
     * @param bytes its estimated bytes
     */
    private record QueuedDelivery(Delivery delivery, long bytes) {
    }

    /** Visits a map's keys and values, each entry's key first, for {@link #estimateRetainedBytes(Object, long)}. */
    private static final class MapContents implements Iterator<Object> {

        private final Iterator<? extends Map.Entry<?, ?>> entries;
        private Object value;
        private boolean valueNext;

        MapContents(Map<?, ?> map) {
            this.entries = map.entrySet().iterator();
        }

        @Override
        public boolean hasNext() {
            return valueNext || entries.hasNext();
        }

        @Override
        public Object next() {
            if (valueNext) {
                valueNext = false;
                Object next = value;
                value = null;
                return next;
            }
            Map.Entry<?, ?> entry = entries.next();
            value = entry.getValue();
            valueNext = true;
            return entry.getKey();
        }
    }

    /**
     * One client's subscriptions, queued deliveries and waiting connect.
     *
     * <p>{@link #channels} is guarded by the broker's {@code stateLock}; everything else by the session's own lock,
     * which every activity takes, so activity is ordered with {@link #closeIfIdle(long)}: it either happens first and
     * keeps the session open, or finds it closed. The queue's estimated bytes are released to the broker's lock-free
     * {@link RetainedBytes} as deliveries leave the queue, drained or discarded on close.
     */
    private static final class ClientSession {

        /** The channels this client subscribes to. Guarded by the broker's {@code stateLock}. */
        private final Set<String> channels = new HashSet<>();
        private final RetainedBytes retained;
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition changed = lock.newCondition();
        private final ArrayDeque<QueuedDelivery> queue = new ArrayDeque<>();

        /** The estimated bytes of {@link #queue}, at most {@link #MAX_QUEUED_BYTES_PER_CLIENT}. */
        private long queuedBytes;
        private long lastActivityNanos = System.nanoTime();
        private long waiterGeneration;
        private int waiters;
        private boolean closed;

        /**
         * A session releasing its queue's bytes to {@code retained}.
         *
         * @param retained the broker's retained-byte count, which reserved those bytes
         */
        ClientSession(RetainedBytes retained) {
            this.retained = retained;
        }

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
         * Queues deliveries all together or not at all, and wakes the waiting connect. Their bytes, reserved by the
         * caller, become the queue's when they are queued and stay the caller's otherwise.
         *
         * @param deliveries the deliveries with their estimated bytes, in order
         * @return {@link EnqueueOutcome#OVERFLOW} or {@link EnqueueOutcome#OVERFLOW_BYTES} after closing the session
         *         when the queue cannot take all of them within {@link #MAX_QUEUED_DELIVERIES} deliveries or
         *         {@link #MAX_QUEUED_BYTES_PER_CLIENT} estimated bytes
         */
        EnqueueOutcome enqueue(List<QueuedDelivery> deliveries) {
            return offer(deliveries, false);
        }

        /**
         * Puts deliveries back at the front of the queue, in their order and all together or not at all, ahead of
         * everything queued since they were taken, and wakes the waiting connect, as {@link #enqueue(List)} does.
         *
         * @param deliveries the deliveries with their estimated bytes, in order
         * @return {@link EnqueueOutcome#OVERFLOW} or {@link EnqueueOutcome#OVERFLOW_BYTES} after closing the session
         *         when the queue cannot take all of them within its bounds
         */
        EnqueueOutcome requeue(List<QueuedDelivery> deliveries) {
            return offer(deliveries, true);
        }

        /** {@link #enqueue(List)} at the back of the queue, or {@link #requeue(List)} at its front. */
        private EnqueueOutcome offer(List<QueuedDelivery> deliveries, boolean atFront) {
            lock.lock();
            try {
                if (closed) {
                    return EnqueueOutcome.CLOSED;
                }
                if (queue.size() + deliveries.size() > MAX_QUEUED_DELIVERIES) {
                    closeLocked();
                    return EnqueueOutcome.OVERFLOW;
                }
                long offered = 0;
                for (QueuedDelivery delivery : deliveries) {
                    offered = saturatedAdd(offered, delivery.bytes());
                }
                if (offered > MAX_QUEUED_BYTES_PER_CLIENT - queuedBytes) {
                    closeLocked();
                    return EnqueueOutcome.OVERFLOW_BYTES;
                }
                if (atFront) {
                    ListIterator<QueuedDelivery> back = deliveries.listIterator(deliveries.size());
                    while (back.hasPrevious()) {
                        queue.addFirst(back.previous());
                    }
                } else {
                    queue.addAll(deliveries);
                }
                queuedBytes += offered;
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

        /**
         * Claims the deliveries, waits as {@link #awaitClaimed(long, long)} does, then returns and removes the queue
         * unless the claim was superseded or the session closed: all under one hold of the session's lock.
         */
        List<Delivery> await(long timeoutMs) throws InterruptedException {
            lock.lock();
            try {
                long generation = claimLocked();
                return waitLocked(generation, timeoutMs) ? drainLocked() : List.of();
            } finally {
                lock.unlock();
            }
        }

        /**
         * Mints a claim that supersedes every older one, as {@link #claimLocked()} does.
         *
         * @return the new claim, current until the next claim or the session's close
         */
        ConnectClaim claim() {
            lock.lock();
            try {
                return new ConnectClaim(this, claimLocked());
            } finally {
                lock.unlock();
            }
        }

        /**
         * Waits until a delivery is queued, the claim is superseded, the session closes or {@code timeoutMs} passes,
         * taking nothing from the queue.
         *
         * @param generation the claim's generation
         * @param timeoutMs the longest wait in milliseconds; zero or less does not wait
         * @return whether the claim is still current: the session is open and no newer claim has been minted
         * @throws InterruptedException when the waiting thread is interrupted
         */
        boolean awaitClaimed(long generation, long timeoutMs) throws InterruptedException {
            lock.lock();
            try {
                return waitLocked(generation, timeoutMs);
            } finally {
                lock.unlock();
            }
        }

        /**
         * Returns and removes every queued delivery, recording the activity, when the claim is still current;
         * otherwise takes nothing. The check and the removal are one step under the session's lock.
         *
         * @param generation the claim's generation
         * @return the deliveries in queue order; empty when nothing is queued or the claim is no longer current
         */
        List<Delivery> drainClaimed(long generation) {
            lock.lock();
            try {
                if (!isCurrentLocked(generation)) {
                    return List.of();
                }
                lastActivityNanos = System.nanoTime();
                return drainLocked();
            } finally {
                lock.unlock();
            }
        }

        /**
         * Mints the next claim generation, for a caller holding the session's lock: every older claim is superseded
         * from now on and its waiting connect woken, so it ends stale at once. Records the activity.
         *
         * @return the new claim's generation
         */
        private long claimLocked() {
            long generation = ++waiterGeneration;
            changed.signalAll();
            lastActivityNanos = System.nanoTime();
            return generation;
        }

        /**
         * The wait of one claimed connect, for a caller holding the session's lock. It counts as a waiter until it
         * ends, so the idle sweep spares the session meanwhile.
         *
         * @param generation the claim's generation
         * @param timeoutMs the longest wait in milliseconds; zero or less does not wait
         * @return whether the claim is still current: the session is open and no newer claim has been minted
         * @throws InterruptedException when the waiting thread is interrupted
         */
        private boolean waitLocked(long generation, long timeoutMs) throws InterruptedException {
            waiters++;
            lastActivityNanos = System.nanoTime();
            try {
                long remaining = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
                while (queue.isEmpty() && isCurrentLocked(generation) && remaining > 0) {
                    remaining = changed.awaitNanos(remaining);
                }
            } finally {
                waiters--;
                lastActivityNanos = System.nanoTime();
            }
            return isCurrentLocked(generation);
        }

        /**
         * Whether a claim is current, for a caller holding the session's lock.
         *
         * @param generation the claim's generation
         * @return whether the session is open and no newer claim has been minted
         */
        private boolean isCurrentLocked(long generation) {
            return !closed && generation == waiterGeneration;
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

        /** {@link #close()} for a caller already holding the session's lock; releases the discarded queue's bytes. */
        private void closeLocked() {
            closed = true;
            queue.clear();
            releaseQueuedBytesLocked();
            changed.signalAll();
        }

        /** Takes every queued delivery, releasing their bytes, for a caller holding the session's lock. */
        private List<Delivery> drainLocked() {
            if (queue.isEmpty()) {
                return List.of();
            }
            List<Delivery> deliveries = new ArrayList<>(queue.size());
            for (QueuedDelivery queued : queue) {
                deliveries.add(queued.delivery());
            }
            queue.clear();
            releaseQueuedBytesLocked();
            return deliveries;
        }

        /** Releases the bytes of deliveries that have left the queue, for a caller holding the session's lock. */
        private void releaseQueuedBytesLocked() {
            retained.release(queuedBytes);
            queuedBytes = 0;
        }
    }
}
