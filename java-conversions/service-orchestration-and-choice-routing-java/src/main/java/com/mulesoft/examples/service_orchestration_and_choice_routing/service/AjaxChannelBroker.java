package com.mulesoft.examples.service_orchestration_and_choice_routing.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 *       handler that has not returned within it produces no reply, and its late result is discarded.</li>
 *   <li>{@code ajax.cache-messages-channels} (default empty) &mdash; the outbound channels declared with
 *       {@code cacheMessages="true"}: a message published while nobody subscribes is held for the first
 *       subscriber.</li>
 *   <li>{@code ajax.max-interval-ms} (default 30000) &mdash; the Bayeux {@code maxInterval} the Mule connector leaves
 *       at its default: a client without a held connect that has not been heard from for this long is removed, as
 *       the embedded cometd server removed it.</li>
 * </ul>
 *
 * <p>Bayeux framing (message ids, {@code successful}, {@code advice}, {@code /meta/*} dispatch and JSON) belongs to
 * the controller; this class works with client IDs, channel names and plain values only. All state is in memory and
 * lost on restart, as the connector's was. Every public method is thread-safe.
 */
@Service
public class AjaxChannelBroker {

    /**
     * Messages a cached channel holds for its first subscriber: Mule's default {@code messageCacheSize} for an Ajax
     * outbound endpoint.
     */
    static final int MAX_HELD_MESSAGES = 500;

    private static final Logger LOGGER = LoggerFactory.getLogger(AjaxChannelBroker.class);

    private static final String DATA_KEY = "data";
    private static final String REPLY_TO_KEY = "replyTo";

    private final boolean disableReplyTo;
    private final long responseTimeoutMs;
    private final Set<String> cacheMessagesChannels;
    private final long maxIntervalNanos;
    private final long sweepIntervalNanos;
    private final ExecutorService executor;

    private final Map<String, ClientSession> clients = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> subscribersByChannel = new ConcurrentHashMap<>();
    private final Map<String, InboundHandler> inboundHandlers = new ConcurrentHashMap<>();

    /**
     * Messages held per cached channel, in publish order. Guarded by {@link #holdLock}, which also makes the
     * "nobody subscribed" check of a publication atomic with a subscription on the same cached channel.
     */
    private final Map<String, List<Object>> heldMessages = new HashMap<>();
    private final Object holdLock = new Object();

    private final AtomicLong nextSweepNanos;

    /**
     * Reads the {@code ajax.*} configuration and starts the handler executor.
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
        this.executor = Executors.newCachedThreadPool(daemonThreads());
    }

    /**
     * Handles the publications of one inbound channel, as the flow behind an Ajax inbound endpoint did.
     */
    @FunctionalInterface
    public interface InboundHandler {

        /**
         * Processes one publication.
         *
         * @param data the publication's payload: the {@code data} of an rpc envelope, otherwise the published value
         * @return the reply for the publication's {@code replyTo} channel, or {@code null} for no reply
         * @throws Exception any failure; it is logged and produces no reply
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
     * The time an inbound handler is given before its publication is answered with no reply
     * ({@code ajax.response-timeout-ms}).
     *
     * @return the timeout in milliseconds
     */
    public long responseTimeoutMs() {
        return responseTimeoutMs;
    }

    /**
     * Stops the handler executor, interrupting running handlers, and releases every waiting
     * {@link #awaitMessages(String, long)} call with an empty result.
     */
    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        clients.values().forEach(ClientSession::close);
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
     * @param clientId a known client ID
     * @param channel the exact channel name
     * @throws IllegalArgumentException when the client is unknown
     */
    public void subscribe(String clientId, String channel) {
        Objects.requireNonNull(channel, "channel");
        ClientSession session = requireSession(clientId);
        session.touch();
        if (!cacheMessagesChannels.contains(channel)) {
            addSubscriber(clientId, session, channel);
            return;
        }
        // Held messages go to the first subscriber at once. Mule's dispatcher flushed its cache only with the next
        // message published after a subscription; delivering on subscribe ends the hold as soon as someone listens.
        synchronized (holdLock) {
            addSubscriber(clientId, session, channel);
            List<Object> held = heldMessages.remove(channel);
            if (held != null) {
                held.forEach(data -> session.enqueue(new Delivery(channel, data)));
            }
        }
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
        ClientSession session = clients.get(clientId);
        if (session != null) {
            session.touch();
            session.channels.remove(channel);
        }
        removeSubscriber(clientId, channel);
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
        ClientSession session = clients.get(clientId);
        if (session != null) {
            remove(clientId, session);
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
        session.touch();
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
     *       other value is the payload itself, with no reply channel. The handler runs on the broker's executor
     *       within {@code ajax.response-timeout-ms}; a non-null result is published on the reply channel unless
     *       replies are disabled. A failure or timeout is logged at INFO and produces no reply. The publication
     *       reaches the handler only, never the channel's subscribers.</li>
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
        LOGGER.debug("Ajax publication from client {} dispatched to the handler of channel {}", clientId, channel);
        if (data instanceof Map<?, ?> envelope && envelope.containsKey(REPLY_TO_KEY)) {
            Object replyTo = envelope.get(REPLY_TO_KEY);
            dispatch(channel, handler, envelope.get(DATA_KEY), replyTo instanceof String replyChannel ? replyChannel : null);
        } else {
            dispatch(channel, handler, data, null);
        }
    }

    /**
     * Publishes a value on a channel: queues it for every subscribed client and wakes each one's waiting connect.
     * With nobody subscribed, a cached channel holds the value for its first subscriber (at most
     * {@value #MAX_HELD_MESSAGES} values; one arriving at a full hold is dropped and logged at WARN, as the
     * connector's bounded cache refused it), and any other channel drops it.
     *
     * @param channel the exact channel name
     * @param data the value
     */
    public void publish(String channel, Object data) {
        Objects.requireNonNull(channel, "channel");
        expireIdleClients();
        if (!cacheMessagesChannels.contains(channel)) {
            if (!deliver(channel, data)) {
                LOGGER.debug("No subscriber on Ajax channel {}; message dropped", channel);
            }
            return;
        }
        synchronized (holdLock) {
            if (!deliver(channel, data)) {
                hold(channel, data);
            }
        }
    }

    private void dispatch(String channel, InboundHandler handler, Object payload, String replyChannel) {
        CompletableFuture<Object> invocation;
        try {
            invocation = CompletableFuture.supplyAsync(() -> invoke(handler, payload), executor);
        } catch (RejectedExecutionException rejected) {
            // A cached thread pool rejects work only once shutdown() has run.
            LOGGER.info("Ajax handler for channel {} failed: {}", channel, "the broker has been shut down");
            return;
        }
        invocation.orTimeout(responseTimeoutMs, TimeUnit.MILLISECONDS)
                .whenComplete((result, failure) -> complete(channel, replyChannel, result, failure));
    }

    private static Object invoke(InboundHandler handler, Object payload) {
        try {
            return handler.handle(payload);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new CompletionException(failure);
        }
    }

    private void complete(String channel, String replyChannel, Object result, Throwable failure) {
        if (failure instanceof TimeoutException) {
            // orTimeout completes the invocation with a bare TimeoutException; a handler's own exceptions always
            // arrive wrapped in a CompletionException, so they are never mistaken for the response timeout.
            LOGGER.info("Ajax handler for channel {} did not respond within {} ms", channel, responseTimeoutMs);
            return;
        }
        if (failure != null) {
            Throwable cause = unwrap(failure);
            LOGGER.info("Ajax handler for channel {} failed: {}", channel, describe(cause));
            LOGGER.debug("Ajax handler failure on channel {}", channel, cause);
            return;
        }
        if (result != null && !disableReplyTo && replyChannel != null) {
            publish(replyChannel, result);
        }
    }

    private boolean deliver(String channel, Object data) {
        Set<String> subscriberIds = subscribersByChannel.get(channel);
        if (subscriberIds == null) {
            return false;
        }
        Delivery delivery = new Delivery(channel, data);
        boolean delivered = false;
        for (String subscriberId : subscriberIds) {
            ClientSession session = clients.get(subscriberId);
            if (session == null) {
                // A subscription added while its client was being removed.
                removeSubscriber(subscriberId, channel);
            } else {
                session.enqueue(delivery);
                delivered = true;
            }
        }
        return delivered;
    }

    private void hold(String channel, Object data) {
        List<Object> held = heldMessages.computeIfAbsent(channel, key -> new ArrayList<>());
        if (held.size() >= MAX_HELD_MESSAGES) {
            LOGGER.warn("Ajax channel {} already holds {} messages for its first subscriber; message dropped",
                    channel, MAX_HELD_MESSAGES);
            return;
        }
        held.add(data);
    }

    private void addSubscriber(String clientId, ClientSession session, String channel) {
        session.channels.add(channel);
        subscribersByChannel.compute(channel, (key, subscriberIds) -> {
            Set<String> ids = subscriberIds != null ? subscriberIds : ConcurrentHashMap.newKeySet();
            ids.add(clientId);
            return ids;
        });
    }

    private void removeSubscriber(String clientId, String channel) {
        subscribersByChannel.computeIfPresent(channel, (key, subscriberIds) -> {
            subscriberIds.remove(clientId);
            return subscriberIds.isEmpty() ? null : subscriberIds;
        });
    }

    private void remove(String clientId, ClientSession session) {
        if (clients.remove(clientId, session)) {
            session.close();
            session.channels.forEach(channel -> removeSubscriber(clientId, channel));
        }
    }

    /**
     * Removes clients idle for longer than {@code ajax.max-interval-ms}, at most once per half interval, so the
     * queue of a client that went away without {@code /meta/disconnect} stops growing.
     */
    private void expireIdleClients() {
        long now = System.nanoTime();
        long due = nextSweepNanos.get();
        if (now - due < 0 || !nextSweepNanos.compareAndSet(due, now + sweepIntervalNanos)) {
            return;
        }
        clients.forEach((clientId, session) -> {
            if (session.isIdleLongerThan(maxIntervalNanos, now)) {
                LOGGER.debug("Ajax client {} expired: no connect within the max interval", clientId);
                remove(clientId, session);
            }
        });
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

    private static Throwable unwrap(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String describe(Throwable failure) {
        return failure.getMessage() != null ? failure.getMessage() : failure.getClass().getName();
    }

    private static ThreadFactory daemonThreads() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "ajax-channel-broker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** One client's subscriptions, queued deliveries and waiting connect. */
    private static final class ClientSession {

        private final Set<String> channels = ConcurrentHashMap.newKeySet();
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition changed = lock.newCondition();
        private final ArrayDeque<Delivery> queue = new ArrayDeque<>();
        private volatile long lastActivityNanos = System.nanoTime();
        private long waiterGeneration;
        private int waiters;
        private boolean closed;

        void touch() {
            lastActivityNanos = System.nanoTime();
        }

        void enqueue(Delivery delivery) {
            lock.lock();
            try {
                if (!closed) {
                    queue.add(delivery);
                    changed.signalAll();
                }
            } finally {
                lock.unlock();
            }
        }

        List<Delivery> drain() {
            lock.lock();
            try {
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

        boolean isIdleLongerThan(long intervalNanos, long now) {
            lock.lock();
            try {
                return waiters == 0 && now - lastActivityNanos > intervalNanos;
            } finally {
                lock.unlock();
            }
        }

        void close() {
            lock.lock();
            try {
                closed = true;
                queue.clear();
                changed.signalAll();
            } finally {
                lock.unlock();
            }
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
