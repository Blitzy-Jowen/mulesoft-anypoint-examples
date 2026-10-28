package com.mulesoft.examples.service_orchestration_and_choice_routing.controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import jakarta.annotation.PreDestroy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import com.mulesoft.examples.service_orchestration_and_choice_routing.service.AjaxChannelBroker;

/**
 * Bayeux 1.0 long-polling endpoint {@code /ajax/cometd} of the Mule 3.8 Ajax transport, shared component SC-12: it
 * stands in for the Jetty cometd servlet that the Mule {@code AjaxConnector} mounted at {@code /ajax/*} on the root of
 * the Ajax listener, and speaks the protocol of the copied Mule JavaScript ({@code mule-resource/js/_mule.js} over
 * {@code dojox.cometd}).
 *
 * <p>Requests: GET or POST with the form parameter {@code message} holding a JSON array of Bayeux messages (a single
 * JSON object is taken as a batch of one), as {@code dojox.cometd} sends every handshake, connect and batch. The
 * optional {@code jsonp} parameter names the callback of the advertised {@code callback-polling} transport. Messages
 * are handled in order by {@code channel}:
 * <ul>
 *   <li>{@code /meta/handshake} registers a client with {@link AjaxChannelBroker#handshake()};</li>
 *   <li>any other message must carry the {@code clientId} of a known client, otherwise it is answered
 *       {@code 402::Unknown client} with the advice to handshake again;</li>
 *   <li>{@code /meta/connect}, {@code /meta/subscribe}, {@code /meta/unsubscribe} and {@code /meta/disconnect} act on
 *       the broker; any other {@code /meta/*} channel is answered {@code 404::Unknown meta channel};</li>
 *   <li>every other channel is a publication handed to {@link AjaxChannelBroker#onPublication}, which unwraps the
 *       {@code mule.rpc} envelope {@code {data, replyTo}} itself. Its acknowledgement carries no {@code data}, because
 *       {@code dojox.cometd} dispatches every incoming message with {@code data} to the page's subscribers.</li>
 * </ul>
 *
 * <p>Responses: HTTP 200 with a plain JSON array, the replies in request order followed, for a connect, by the
 * client's deliveries ({@code channel}, {@code data}, {@code id}), none when a held connect is answered by the
 * request's own timeout. The copied client parses it with {@code eval("(" + text + ")")} and negotiates no comment
 * filtering, so the array is never wrapped in a comment. Without {@code jsonp} the Content-Type is
 * {@code text/json;charset=UTF-8}, the Jetty cometd servlet's; with it the body is {@code <jsonp>(<array>)} as
 * {@code text/javascript;charset=UTF-8}. Bayeux errors are always in-band. A request whose {@code message} is
 * absent, is not JSON, carries anything but whitespace after its single JSON value, or is neither an object nor an
 * array of objects is answered HTTP 400 with the body {@code []}. That Content-Type and the 400 framing are Tier 2A
 * verification points.
 *
 * <p>Connection phase: the first connect after a handshake is answered at once with whatever is queued, as Jetty
 * answered it, because {@code dojox.cometd} flushes the subscriptions and publications queued at page load only after
 * that first successful connect. Every later connect claims the client's deliveries when its request arrives,
 * superseding every older connect of the client, and is held, on a wait thread rather than a request thread, until a
 * delivery is queued for the client, a newer connect supersedes it or {@code ajax.connect-timeout-ms} passes. The wait
 * takes nothing from the client's queue: only the answer that owns the response drains it, and only while the
 * connect's claim is still current, because the broker checks the claim and takes the queue in one step under the
 * client's lock, so a superseded connect's response carries no delivery. Deliveries that response then refuses go
 * back to the front of the queue, while a request that times out or ends first retires its wait and leaves every
 * delivery for the client's next connect, so each delivery is accepted by exactly one response. Deliveries a
 * response has accepted are lost when the client has meanwhile gone away, as with Jetty. The held requests use Servlet
 * async processing, which works because SC-10's {@code PortRoutingFilter} is registered for REQUEST dispatches only and
 * the ASYNC re-dispatch of {@code /ajax/cometd} reaches {@code dispatcherServlet} through its {@code /} mapping.
 *
 * <p>Scope: channel, subscription, queue and reply state belongs to {@link AjaxChannelBroker}; this class only parses
 * requests and frames responses, and keeps nothing but the connection-phase bookkeeping, a delivery-id counter and the
 * wait executor. Confining the endpoint to the Ajax listener is SC-10's route for the Ajax port, so there is no port
 * logic here, and the docroot and {@code mule-resource/js/**} are served by {@code config/AjaxResourceConfig}. The
 * class names no project value, so every project serving the Mule Ajax transport carries a copy that differs from this
 * one in the {@code package} line and the package root of the {@link AjaxChannelBroker} import alone.
 */
@RestController
public class BayeuxLongPollingController {

    /** The endpoint path below the Ajax listener's root, where {@code AjaxConnector} mounted {@code /ajax/*}. */
    static final String COMETD_PATH = "/ajax/cometd";

    /**
     * Connects held at once, each on its own wait thread. A connect arriving while this many are held is answered at
     * once with what is queued, so a flood of clients cannot exhaust the JVM's threads; the client simply polls again.
     */
    static final int MAX_HELD_CONNECTS = 512;

    /**
     * Time a held connect is given beyond {@code ajax.connect-timeout-ms} before the request's own safety-net timeout
     * retires its wait and answers it with its replies alone, leaving the client's queued deliveries for its next
     * connect.
     */
    static final long HOLD_GRACE_MS = 5000;

    private static final Logger LOGGER = LoggerFactory.getLogger(BayeuxLongPollingController.class);

    private static final String META_PREFIX = "/meta/";
    private static final String HANDSHAKE = "/meta/handshake";
    private static final String CONNECT = "/meta/connect";
    private static final String SUBSCRIBE = "/meta/subscribe";
    private static final String UNSUBSCRIBE = "/meta/unsubscribe";
    private static final String DISCONNECT = "/meta/disconnect";

    private static final String CHANNEL = "channel";
    private static final String CLIENT_ID = "clientId";
    private static final String ID = "id";
    private static final String DATA = "data";
    private static final String SUBSCRIPTION = "subscription";
    private static final String SUCCESSFUL = "successful";
    private static final String ERROR = "error";
    private static final String ADVICE = "advice";

    private static final String BAYEUX_VERSION = "1.0";
    private static final String MINIMUM_VERSION = "0.9";
    private static final List<String> CONNECTION_TYPES = List.of("long-polling", "callback-polling");

    private static final String UNKNOWN_CLIENT = "402::Unknown client";
    private static final String UNKNOWN_META_CHANNEL = "404::Unknown meta channel";
    private static final String MISSING_CHANNEL = "400::Missing channel";
    private static final String MISSING_SUBSCRIPTION = "400::Missing subscription";
    private static final String INVALID_SUBSCRIPTION = "400::Invalid subscription";

    private static final MediaType JSON_CONTENT_TYPE = MediaType.parseMediaType("text/json;charset=UTF-8");
    private static final MediaType JSONP_CONTENT_TYPE = MediaType.parseMediaType("text/javascript;charset=UTF-8");

    /** Advice of a reply that sends the client back to the handshake. */
    private static final Map<String, Object> HANDSHAKE_ADVICE = orderedMap("reconnect", "handshake", "interval", 0);

    /**
     * A dotted JavaScript function reference, such as {@code dojo.io.script.jsonp_dojoIoScript1._jsonpCallback} or
     * its {@code parent.} form. The callback is written into a script response, so nothing else is accepted.
     */
    private static final Pattern JSONP_CALLBACK =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    private static final int MAX_JSONP_CALLBACK_LENGTH = 256;

    /** Line terminators of older JavaScript engines that a JSON string may carry raw; see {@link #frame}. */
    private static final char LINE_SEPARATOR = '\u2028';
    private static final char PARAGRAPH_SEPARATOR = '\u2029';

    private static final long WAIT_THREAD_KEEP_ALIVE_SECONDS = 60;
    private static final int LOG_TEXT_LIMIT = 256;

    private final AjaxChannelBroker broker;
    private final ObjectMapper objectMapper;

    /**
     * Reads the {@code message} parameter with {@link #objectMapper}'s configuration plus
     * {@code FAIL_ON_TRAILING_TOKENS}, so input after the single JSON value refuses the batch. It is derived rather
     * than enabled on the injected mapper, which Spring shares with the rest of the application.
     */
    private final ObjectReader messageReader;

    private final long connectTimeoutMs;
    private final long holdTimeoutMs;

    /** Advice of the handshake and connect replies: reconnect at once, hold for {@code ajax.connect-timeout-ms}. */
    private final Map<String, Object> retryAdvice;

    /**
     * Runs the blocking {@link AjaxChannelBroker#awaitDeliveries} calls of held connects, one daemon thread
     * ({@code bayeux-connect-<n>}) per held connect, created on demand and retired after 60 s idle, as a cached pool
     * does. Not Spring's {@code applicationTaskExecutor}, whose bounded core pool would queue held connects.
     */
    private final ExecutorService waitExecutor;

    /**
     * Bayeux connection-phase bookkeeping only: the IDs of clients that have handshaken but not yet connected, whose
     * first connect is therefore answered at once.
     */
    private final Set<String> awaitingFirstConnect = ConcurrentHashMap.newKeySet();

    private final AtomicLong deliveryIds = new AtomicLong();

    /**
     * Creates the endpoint.
     *
     * @param broker the channel state of the Ajax transport
     * @param objectMapper Spring's JSON mapper, which writes the responses and, through a reader derived from it that
     *        refuses input after the single JSON value, parses the messages; the mapper itself is not modified
     * @param connectTimeoutMs {@code ajax.connect-timeout-ms}: how long a connect is held waiting for deliveries, also
     *        advised to the client as the Bayeux {@code timeout}
     * @throws IllegalArgumentException when {@code ajax.connect-timeout-ms} is not positive, which would turn every
     *         held connect into an immediate answer and the client into a busy poll
     */
    public BayeuxLongPollingController(AjaxChannelBroker broker, ObjectMapper objectMapper,
            @Value("${ajax.connect-timeout-ms:30000}") long connectTimeoutMs) {
        this.broker = Objects.requireNonNull(broker, "broker");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.messageReader = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        if (connectTimeoutMs <= 0) {
            throw new IllegalArgumentException("ajax.connect-timeout-ms must be positive, was " + connectTimeoutMs);
        }
        this.connectTimeoutMs = connectTimeoutMs;
        this.holdTimeoutMs = connectTimeoutMs > Long.MAX_VALUE - HOLD_GRACE_MS
                ? Long.MAX_VALUE : connectTimeoutMs + HOLD_GRACE_MS;
        this.retryAdvice = orderedMap("reconnect", "retry", "interval", 0, "timeout", connectTimeoutMs);
        // A cached pool capped at MAX_HELD_CONNECTS: no queue, so a connect beyond the cap is refused, never parked.
        this.waitExecutor = new ThreadPoolExecutor(0, MAX_HELD_CONNECTS, WAIT_THREAD_KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS, new SynchronousQueue<>(), waitThreads());
    }

    /** Stops the wait threads; each held connect they release is answered with its replies alone. */
    @PreDestroy
    public void shutdown() {
        waitExecutor.shutdownNow();
    }

    /**
     * Handles one Bayeux request. Methods other than GET and POST reach SC-1's 405
     * {@code Method not allowed for endpoint: /ajax/cometd}.
     *
     * @param message the JSON array of Bayeux messages, or a single message object
     * @param jsonp the callback-polling callback, or {@code null} for a plain JSON response
     * @return the response, already set unless the request holds a connect
     */
    @RequestMapping(path = COMETD_PATH, method = {RequestMethod.GET, RequestMethod.POST})
    public DeferredResult<ResponseEntity<String>> cometd(
            @RequestParam(name = "message", required = false) String message,
            @RequestParam(name = "jsonp", required = false) String jsonp) {
        if (jsonp != null && !isFunctionReference(jsonp)) {
            // Only a function reference may be reflected into a script response; anything else gets a plain-JSON 400.
            LOGGER.warn("Rejected Bayeux request: the jsonp parameter is not a JavaScript function reference");
            return completed(frame(HttpStatus.BAD_REQUEST, List.of(), null));
        }
        List<JsonNode> batch = parseBatch(message);
        if (batch == null) {
            return completed(frame(HttpStatus.BAD_REQUEST, List.of(), jsonp));
        }
        BatchState state = new BatchState();
        List<Object> replies = new ArrayList<>(batch.size());
        for (JsonNode bayeuxMessage : batch) {
            replies.add(handle(bayeuxMessage, state));
        }
        if (state.connectClientId == null) {
            return completed(frame(HttpStatus.OK, replies, jsonp));
        }
        if (!state.holdConnect) {
            return completed(frame(HttpStatus.OK, withDeliveries(replies, broker.drain(state.connectClientId)), jsonp));
        }
        return hold(state.connectClientId, Collections.unmodifiableList(replies), jsonp);
    }

    /** Handles one Bayeux message and returns its reply; a connect is recorded in {@code state}. */
    private Map<String, Object> handle(JsonNode message, BatchState state) {
        String channel = text(message.get(CHANNEL));
        if (channel == null) {
            Map<String, Object> reply = new LinkedHashMap<>();
            return failed(reply, MISSING_CHANNEL, message);
        }
        if (HANDSHAKE.equals(channel)) {
            return handshake(message);
        }
        String clientId = text(message.get(CLIENT_ID));
        if (!broker.isKnownClient(clientId)) {
            return unknownClient(channel, message);
        }
        return switch (channel) {
            case CONNECT -> connect(clientId, message, state);
            case SUBSCRIBE -> subscription(channel, clientId, message, true);
            case UNSUBSCRIBE -> subscription(channel, clientId, message, false);
            case DISCONNECT -> disconnect(clientId, message);
            default -> channel.startsWith(META_PREFIX) ? unknownMeta(channel, message)
                    : publish(channel, clientId, message);
        };
    }

    /**
     * Registers a new client, which is now awaiting its first connect, and forgets the pending first connects of
     * clients the broker no longer knows, so the bookkeeping cannot outgrow the broker's clients.
     */
    private Map<String, Object> handshake(JsonNode message) {
        String clientId = broker.handshake();
        awaitingFirstConnect.removeIf(pending -> !broker.isKnownClient(pending));
        awaitingFirstConnect.add(clientId);
        Map<String, Object> reply = reply(HANDSHAKE);
        reply.put("version", BAYEUX_VERSION);
        reply.put("minimumVersion", MINIMUM_VERSION);
        reply.put("supportedConnectionTypes", CONNECTION_TYPES);
        reply.put(CLIENT_ID, clientId);
        reply.put(SUCCESSFUL, Boolean.TRUE);
        reply.put(ADVICE, retryAdvice);
        return withId(reply, message);
    }

    /** Acknowledges a connect; the response is held unless it is the client's first connect. */
    private Map<String, Object> connect(String clientId, JsonNode message, BatchState state) {
        state.connectClientId = clientId;
        state.holdConnect = !awaitingFirstConnect.remove(clientId);
        Map<String, Object> reply = reply(CONNECT);
        reply.put(CLIENT_ID, clientId);
        reply.put(SUCCESSFUL, Boolean.TRUE);
        reply.put(ADVICE, retryAdvice);
        return withId(reply, message);
    }

    /**
     * Subscribes or unsubscribes the client to the channel the message names, or to each channel of an array of
     * channel names, and echoes {@code subscription} exactly as sent, which is how the client matches the reply.
     */
    private Map<String, Object> subscription(String channel, String clientId, JsonNode message, boolean subscribe) {
        JsonNode subscription = message.get(SUBSCRIPTION);
        Map<String, Object> reply = reply(channel);
        reply.put(CLIENT_ID, clientId);
        if (subscription == null || subscription.isNull()) {
            return failed(reply, MISSING_SUBSCRIPTION, message);
        }
        reply.put(SUBSCRIPTION, subscription);
        List<String> channels = channelNames(subscription);
        if (channels == null) {
            return failed(reply, INVALID_SUBSCRIPTION, message);
        }
        if (subscribe) {
            try {
                for (String subscribed : channels) {
                    broker.subscribe(clientId, subscribed);
                }
            } catch (IllegalArgumentException removed) {
                // The broker refuses a subscription only for a client it no longer has: removed concurrently, or
                // removed because the messages held on the channel overflowed its queue.
                return unknownClient(channel, message);
            }
        } else {
            channels.forEach(unsubscribed -> broker.unsubscribe(clientId, unsubscribed));
        }
        reply.put(SUCCESSFUL, Boolean.TRUE);
        return withId(reply, message);
    }

    /** Removes the client with its subscriptions and queue; a held connect of it is released empty. */
    private Map<String, Object> disconnect(String clientId, JsonNode message) {
        broker.disconnect(clientId);
        awaitingFirstConnect.remove(clientId);
        Map<String, Object> reply = reply(DISCONNECT);
        reply.put(CLIENT_ID, clientId);
        reply.put(SUCCESSFUL, Boolean.TRUE);
        return withId(reply, message);
    }

    /**
     * Hands a publication to the broker. The acknowledgement deliberately carries no {@code data}: the client would
     * dispatch it to the page's subscribers as if it were a message.
     */
    private Map<String, Object> publish(String channel, String clientId, JsonNode message) {
        Map<String, Object> reply = reply(channel);
        try {
            JsonNode data = message.get(DATA);
            broker.onPublication(clientId, channel, data == null ? null : objectMapper.treeToValue(data, Object.class));
            reply.put(SUCCESSFUL, Boolean.TRUE);
        } catch (JsonProcessingException | RuntimeException failure) {
            LOGGER.warn("Bayeux publication on channel {} failed: {}", loggable(channel), failure.getClass().getName());
            reply.put(SUCCESSFUL, Boolean.FALSE);
            reply.put(ERROR, failure.getMessage() != null ? failure.getMessage() : failure.getClass().getName());
        }
        return withId(reply, message);
    }

    private Map<String, Object> unknownMeta(String channel, JsonNode message) {
        return failed(reply(channel), UNKNOWN_META_CHANNEL, message);
    }

    /** The answer to a message of a client the broker does not know: handshake again. Such a connect is never held. */
    private Map<String, Object> unknownClient(String channel, JsonNode message) {
        Map<String, Object> reply = reply(channel);
        JsonNode subscription = message.get(SUBSCRIPTION);
        if (subscription != null) {
            reply.put(SUBSCRIPTION, subscription);
        }
        reply.put(SUCCESSFUL, Boolean.FALSE);
        reply.put(ERROR, UNKNOWN_CLIENT);
        reply.put(ADVICE, HANDSHAKE_ADVICE);
        return withId(reply, message);
    }

    /**
     * Claims the client's deliveries for this connect, superseding every older connect of the client, and holds the
     * connect until the broker has a delivery queued for the client, a newer connect supersedes it or
     * {@code ajax.connect-timeout-ms} passes, then answers with the replies and, while the claim is still current, the
     * deliveries. The request's own timeout, which the wait normally precedes by {@link #HOLD_GRACE_MS}, and the end of
     * the request by an error or its completion retire the wait instead, leaving every queued delivery for the client's
     * next connect; a timeout answers with the replies alone. {@link HeldConnect} makes these the one owner of the
     * response.
     */
    private DeferredResult<ResponseEntity<String>> hold(String clientId, List<Object> replies, String jsonp) {
        DeferredResult<ResponseEntity<String>> result = new DeferredResult<>(holdTimeoutMs);
        // Claimed on the request thread, so supersession follows the order the requests arrive in, not the order their
        // wait threads start in.
        HeldConnect held = new HeldConnect(clientId, broker.claimConnect(clientId), replies, jsonp, result);
        result.onTimeout(held::expire);
        result.onError(failure -> held.retire());
        result.onCompletion(held::retire);
        held.start();
        return result;
    }

    /** The replies followed by one message per delivery, each with its own server-assigned {@code id}. */
    private List<Object> withDeliveries(List<Object> replies, List<AjaxChannelBroker.Delivery> deliveries) {
        if (deliveries.isEmpty()) {
            return replies;
        }
        List<Object> messages = new ArrayList<>(replies.size() + deliveries.size());
        messages.addAll(replies);
        for (AjaxChannelBroker.Delivery delivery : deliveries) {
            Map<String, Object> deliveryMessage = new LinkedHashMap<>();
            deliveryMessage.put(CHANNEL, delivery.channel());
            deliveryMessage.put(DATA, delivery.data());
            deliveryMessage.put(ID, String.valueOf(deliveryIds.incrementAndGet()));
            messages.add(deliveryMessage);
        }
        return messages;
    }

    /**
     * Writes the messages as one JSON array, or as {@code <jsonp>(<array>)}.
     *
     * <p>U+2028 and U+2029, which Jackson leaves raw inside strings, are written as {@code \}{@code u2028} and
     * {@code \}{@code u2029}: the copied client evaluates the response as JavaScript, and engines before ES2019 end a
     * line there, breaking the whole response. Both forms are the same JSON.
     *
     * @return the response; HTTP 500 with {@code []} when a delivered value cannot be written as JSON
     */
    private ResponseEntity<String> frame(HttpStatus status, List<?> messages, String jsonp) {
        HttpStatus written = status;
        String json;
        try {
            json = escapeLineTerminators(objectMapper.writeValueAsString(messages));
        } catch (JsonProcessingException failure) {
            LOGGER.error("Bayeux response could not be written as JSON: {}", failure.getClass().getName());
            written = HttpStatus.INTERNAL_SERVER_ERROR;
            json = "[]";
        }
        if (jsonp == null) {
            return ResponseEntity.status(written).contentType(JSON_CONTENT_TYPE).body(json);
        }
        return ResponseEntity.status(written).contentType(JSONP_CONTENT_TYPE).body(jsonp + "(" + json + ")");
    }

    /**
     * The batch the {@code message} parameter holds, or {@code null}, logged at WARN, when it is absent, is not JSON,
     * carries anything but whitespace after its single JSON value, or is neither an object nor an array of objects.
     * An array with any element that is not an object, or followed by a second value or any other text, is refused
     * whole, so no message of a malformed batch is acted on.
     */
    private List<JsonNode> parseBatch(String message) {
        if (message == null) {
            LOGGER.warn("Rejected Bayeux request: no message parameter");
            return null;
        }
        JsonNode root;
        try {
            root = messageReader.readTree(message);
        } catch (JsonProcessingException unparseable) {
            LOGGER.warn("Rejected Bayeux request: the message parameter is not JSON ({})",
                    unparseable.getClass().getName());
            return null;
        }
        if (root != null && root.isObject()) {
            return List.of(root);
        }
        if (root == null || !root.isArray()) {
            LOGGER.warn("Rejected Bayeux request: the message parameter is neither a JSON object nor an array");
            return null;
        }
        List<JsonNode> batch = new ArrayList<>(root.size());
        for (JsonNode element : root) {
            if (!element.isObject()) {
                LOGGER.warn("Rejected Bayeux request: an element of the message array is not a JSON object");
                return null;
            }
            batch.add(element);
        }
        return batch;
    }

    /** The channel names of a {@code subscription}: one string, or a non-empty array of strings; otherwise null. */
    private static List<String> channelNames(JsonNode subscription) {
        if (subscription.isTextual()) {
            return List.of(subscription.asText());
        }
        if (!subscription.isArray() || subscription.isEmpty()) {
            return null;
        }
        List<String> channels = new ArrayList<>(subscription.size());
        for (JsonNode element : subscription) {
            if (!element.isTextual()) {
                return null;
            }
            channels.add(element.asText());
        }
        return channels;
    }

    private static Map<String, Object> reply(String channel) {
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put(CHANNEL, channel);
        return reply;
    }

    /** Completes an unsuccessful reply with its error and the message's {@code id}. */
    private static Map<String, Object> failed(Map<String, Object> reply, String error, JsonNode message) {
        reply.put(SUCCESSFUL, Boolean.FALSE);
        reply.put(ERROR, error);
        return withId(reply, message);
    }

    /** Echoes the message's {@code id} unchanged, whatever its JSON type, when it has one. */
    private static Map<String, Object> withId(Map<String, Object> reply, JsonNode message) {
        JsonNode id = message.get(ID);
        if (id != null) {
            reply.put(ID, id);
        }
        return reply;
    }

    /** The node's string value, or {@code null} when it is absent or not a JSON string. */
    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static boolean isFunctionReference(String callback) {
        return callback.length() <= MAX_JSONP_CALLBACK_LENGTH && JSONP_CALLBACK.matcher(callback).matches();
    }

    private static String escapeLineTerminators(String json) {
        if (json.indexOf(LINE_SEPARATOR) < 0 && json.indexOf(PARAGRAPH_SEPARATOR) < 0) {
            return json;
        }
        return json.replace(String.valueOf(LINE_SEPARATOR), "\\u2028")
                .replace(String.valueOf(PARAGRAPH_SEPARATOR), "\\u2029");
    }

    private static DeferredResult<ResponseEntity<String>> completed(ResponseEntity<String> response) {
        DeferredResult<ResponseEntity<String>> result = new DeferredResult<>();
        result.setResult(response);
        return result;
    }

    /**
     * A client-supplied channel name made safe for a single log line: control, line-separator, format and surrogate
     * characters become {@code ?}, and the text is cut after {@value #LOG_TEXT_LIMIT} characters.
     */
    private static String loggable(String value) {
        StringBuilder out = new StringBuilder(Math.min(value.length(), LOG_TEXT_LIMIT));
        for (int i = 0; i < value.length(); i++) {
            if (out.length() == LOG_TEXT_LIMIT) {
                return out.append("...[truncated]").toString();
            }
            char c = value.charAt(i);
            int type = Character.getType(c);
            boolean unsafe = Character.isISOControl(c) || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR || type == Character.FORMAT
                    || type == Character.SURROGATE;
            out.append(unsafe ? '?' : c);
        }
        return out.toString();
    }

    /** An unmodifiable map of the given key/value pairs in the given order, as Jackson writes it. */
    private static Map<String, Object> orderedMap(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }

    /** Creates the wait threads: daemons, so a held connect never keeps the JVM alive. */
    private static ThreadFactory waitThreads() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "bayeux-connect-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** The connect of one request's batch, if any; the last connect of a batch decides. */
    private static final class BatchState {

        private String connectClientId;
        private boolean holdConnect;
    }

    /** Who owns a held connect's response: nobody yet, the wait thread's answer, or the request's own end. */
    private enum HoldState {

        /** The wait thread is waiting for the broker; nobody has taken the response yet. */
        WAITING,

        /** The wait thread has taken the response and answers it. */
        ANSWERING,

        /** The request timed out or ended first; the wait thread answers nothing and drains nothing. */
        RETIRED
    }

    /**
     * One held connect, the single owner of its response. It claimed the client's deliveries when its request
     * arrived, superseding every older connect of the client, and a newer connect's claim supersedes it in turn.
     * Three parties can end it: the wait thread, once the broker has a delivery queued for the client, a newer
     * connect supersedes it or the connect timeout passes; the request's own timeout; and the end of the request by an
     * error or its completion. Whoever moves the connect out of {@link HoldState#WAITING} first owns it:
     * <ul>
     *   <li>the wait thread takes it to answer, and drains the client's queue only then, only when its wait ended
     *       current and the response is still unset, and only while its claim on the deliveries is still current:
     *       the broker checks the claim and takes the queue in one step under the client's lock, so a connect
     *       superseded at any moment before takes nothing and its response carries no delivery. Should the response
     *       nevertheless refuse the answer, the drained deliveries go back to the front of the client's queue, so the
     *       next connect receives each of them exactly once;</li>
     *   <li>the request's timeout, an error or the completion retires it: the wait thread is interrupted and ends
     *       without draining, so everything queued stays for the next connect, and a timeout answers the request with
     *       its replies alone.</li>
     * </ul>
     * Retiring never blocks: the container may run those callbacks while holding the locks the wait thread's answer
     * needs, so they neither wait for the wait thread nor take a lock it holds.
     */
    private final class HeldConnect {

        private final String clientId;

        /** The claim on the client's deliveries this connect took as its request arrived. */
        private final AjaxChannelBroker.ConnectClaim claim;

        private final List<Object> replies;
        private final String jsonp;
        private final DeferredResult<ResponseEntity<String>> result;
        private final AtomicReference<HoldState> state = new AtomicReference<>(HoldState.WAITING);

        /** The wait thread's task once submitted; cancelling it interrupts the wait. */
        private volatile Future<?> waitTask;

        /**
         * A connect waiting to be started.
         *
         * @param clientId the connecting client
         * @param claim the claim on the client's deliveries taken as the request arrived
         * @param replies the replies of the request's batch, the connect's included
         * @param jsonp the callback-polling callback, or {@code null}
         * @param result the request's pending response
         */
        HeldConnect(String clientId, AjaxChannelBroker.ConnectClaim claim, List<Object> replies, String jsonp,
                DeferredResult<ResponseEntity<String>> result) {
            this.clientId = clientId;
            this.claim = claim;
            this.replies = replies;
            this.jsonp = jsonp;
            this.result = result;
        }

        /**
         * Starts the wait on its own wait thread. When {@value BayeuxLongPollingController#MAX_HELD_CONNECTS} connects
         * are already held, the connect is answered at once with what is queued, so a flood of clients cannot exhaust
         * the JVM's threads.
         */
        void start() {
            Future<?> task;
            try {
                task = waitExecutor.submit(this::awaitAndAnswer);
            } catch (RejectedExecutionException saturated) {
                LOGGER.warn("{} Bayeux connects are already held; this connect is answered at once",
                        MAX_HELD_CONNECTS);
                answer(true);
                return;
            }
            waitTask = task;
            // A retirement that ran before the task was stored could not cancel it.
            if (state.get() == HoldState.RETIRED) {
                task.cancel(true);
            }
        }

        /**
         * The wait thread: waits without taking anything, then answers. An interrupted wait, by a retirement or by
         * {@link BayeuxLongPollingController#shutdown()}, is answered with the replies alone unless retired.
         */
        private void awaitAndAnswer() {
            boolean current;
            try {
                current = broker.awaitDeliveries(claim, connectTimeoutMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                current = false;
            }
            answer(current);
        }

        /**
         * Takes the response and answers it with the replies, followed by the client's deliveries when
         * {@code drain} is set, the response is still unset and the connect's claim is still current; deliveries the
         * response refuses are restored to the client's queue. Does nothing once the connect is retired.
         */
        private void answer(boolean drain) {
            if (!state.compareAndSet(HoldState.WAITING, HoldState.ANSWERING)) {
                return;
            }
            // The broker checks the claim and takes the queue in one step: a connect superseded since its wait ended
            // takes nothing, and the deliveries stay for the newer connect.
            List<AjaxChannelBroker.Delivery> deliveries =
                    drain && !result.isSetOrExpired() ? broker.drain(claim) : List.of();
            boolean accepted;
            try {
                accepted = result.setResult(frame(HttpStatus.OK, withDeliveries(replies, deliveries), jsonp));
            } catch (RuntimeException refused) {
                // The container may refuse a result for a request it has already ended.
                LOGGER.debug("Held Bayeux connect could not be answered: {}", refused.getClass().getName());
                accepted = false;
            }
            if (!accepted && !deliveries.isEmpty()) {
                broker.restore(clientId, deliveries);
            }
        }

        /** The request's own timeout: retires the connect and answers with the replies alone. */
        void expire() {
            retire();
            result.setResult(frame(HttpStatus.OK, replies, jsonp));
        }

        /** Retires the connect unless the wait thread has taken it to answer, interrupting the wait. Never blocks. */
        void retire() {
            if (state.compareAndSet(HoldState.WAITING, HoldState.RETIRED)) {
                Future<?> task = waitTask;
                if (task != null) {
                    task.cancel(true);
                }
            }
        }
    }
}

