package com.mulesoft.examples.websphere_mq.controller;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
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
import jakarta.servlet.http.HttpServletRequest;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import com.mulesoft.examples.websphere_mq.service.AjaxChannelBroker;

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
 *   <li>every other channel is a publication handed to {@link AjaxChannelBroker#onPublication}, which accepts it on
 *       an inbound channel only and unwraps the {@code mule.rpc} envelope {@code {data, replyTo}} itself. Its
 *       acknowledgement carries no {@code data}, because {@code dojox.cometd} dispatches every incoming message with
 *       {@code data} to the page's subscribers.</li>
 * </ul>
 *
 * <p>Request limits, each checked before any message of the request is acted on: a request declaring a body of more
 * than {@value #MAX_REQUEST_BYTES} bytes, a form body without a declared length (chunked) that passes
 * {@value #MAX_REQUEST_BYTES} bytes, a {@code message} longer than {@value #MAX_REQUEST_BYTES} characters and a batch
 * of more than {@value #MAX_BATCH_MESSAGES} messages are answered HTTP 413 with the body {@code []} as
 * {@code text/json;charset=UTF-8}, and no message of the request is acted on. {@value #MAX_REQUEST_BYTES} bytes is
 * the default {@code maxFormContentSize} of Jetty 9.0.7, the server that hosted the original Ajax connector, which
 * refused a larger form body. A declared length is refused before anything is read. An
 * {@code application/x-www-form-urlencoded} body is read by this handler itself, never by the servlet's parameter
 * parsing or by Spring MVC, which would read the whole body first: it reads at most one byte beyond the limit, and
 * refuses the body there, leaving the rest unread, which Undertow drains once the 413 is written, so the client still
 * receives it. The batch is counted while it is parsed, so no message beyond the limit is ever read into a tree.
 *
 * <p>Parameters: {@code message} and {@code jsonp} are taken from the query string first and from the form body
 * second, the servlet's parameter order; in the body, the first occurrence of each counts. The body is decoded as
 * {@code application/x-www-form-urlencoded} in the request's character encoding, or UTF-8 when it declares none; a
 * request of any other Content-Type is read through the servlet's parameters, which then hold its query string only.
 *
 * <p>Responses: HTTP 200 with a plain JSON array, the replies in request order followed, for a connect, by the
 * client's deliveries ({@code channel}, {@code data}, {@code id}), none when a held connect is answered by the
 * request's own timeout. The copied client parses it with {@code eval("(" + text + ")")} and negotiates no comment
 * filtering, so the array is never wrapped in a comment. Without {@code jsonp} the Content-Type is
 * {@code text/json;charset=UTF-8}, the Jetty cometd servlet's; with it the body is {@code <jsonp>(<array>)} as
 * {@code text/javascript;charset=UTF-8}. Bayeux errors are always in-band. A request whose {@code message} is
 * absent, is not JSON, carries anything but whitespace after its single JSON value, or is neither an object nor an
 * array of objects is answered HTTP 400 with the body {@code []}, as is a form body that cannot be decoded: a
 * {@code %} not followed by two hexadecimal digits, bytes not valid in its character encoding, or an encoding the JVM
 * does not support. That Content-Type and the 400 framing are Tier 2A verification points.
 *
 * <p>The broker's refusals are answered in-band with the denial texts of the original Jetty cometd 6.1.26 server and
 * logged at WARN: a handshake beyond {@link AjaxChannelBroker}'s client bound gets {@code 403::Handshake denied} with
 * the advice to handshake again after 1000 ms; a subscription naming another client's private reply channel, an
 * over-long channel name or more channels than the subscription bounds allow gets {@code 403::cannot subscribe}, and
 * subscribes to none of the channels it names, the client staying known; and a publication on a channel without an
 * inbound handler, or one whose payload the broker's retained-byte bound refuses, gets {@code 403::Publish denied}
 * and reaches nobody. Those limits and refusals differ from the original for hostile input only: every request the
 * copied pages send stays within them.
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

    /**
     * Largest request read: bytes of a declared or streamed body, and characters of the {@code message} parameter.
     * It is the default {@code maxFormContentSize} of Jetty 9.0.7, which hosted the original Ajax connector; every
     * exchange of the copied pages is a few kilobytes.
     */
    static final int MAX_REQUEST_BYTES = 200000;

    /**
     * Messages one request may carry. {@code dojox.cometd} batches a connect with the subscriptions and publications
     * a page queued meanwhile, a handful at a time.
     */
    static final int MAX_BATCH_MESSAGES = 100;

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

    /** Jetty cometd 6.1.26's answer to a handshake its policy refuses. */
    private static final String HANDSHAKE_DENIED = "403::Handshake denied";

    /** Jetty cometd 6.1.26's answer to a subscription its policy refuses. */
    private static final String CANNOT_SUBSCRIBE = "403::cannot subscribe";

    /** Jetty cometd 6.1.26's answer to a publication its policy refuses. */
    private static final String PUBLISH_DENIED = "403::Publish denied";

    private static final String MESSAGE_PARAMETER = "message";
    private static final String JSONP_PARAMETER = "jsonp";

    /** The Content-Type {@code dojox.cometd} posts with, whose body this handler reads and decodes itself. */
    private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";

    private static final MediaType JSON_CONTENT_TYPE = MediaType.parseMediaType("text/json;charset=UTF-8");
    private static final MediaType JSONP_CONTENT_TYPE = MediaType.parseMediaType("text/javascript;charset=UTF-8");

    /** Advice of a reply that sends the client back to the handshake. */
    private static final Map<String, Object> HANDSHAKE_ADVICE = orderedMap("reconnect", "handshake", "interval", 0);

    /** Advice of a denied handshake: handshake again, after a second. */
    private static final Map<String, Object> DENIED_HANDSHAKE_ADVICE =
            orderedMap("reconnect", "handshake", "interval", 1000);

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
     * Reads the {@code message} parameter with {@link #objectMapper}'s configuration: it creates the streaming parser
     * of the whole parameter and reads each message of the batch from it into a tree, one at a time. Input after the
     * single JSON value is refused by {@link #parseBatch} itself, which reads to the end of the input.
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
     * @param objectMapper Spring's JSON mapper, which writes the responses and, through a reader derived from it,
     *        parses the messages one at a time; the mapper itself is not modified
     * @param connectTimeoutMs {@code ajax.connect-timeout-ms}: how long a connect is held waiting for deliveries, also
     *        advised to the client as the Bayeux {@code timeout}
     * @throws IllegalArgumentException when {@code ajax.connect-timeout-ms} is not positive, which would turn every
     *         held connect into an immediate answer and the client into a busy poll
     */
    public BayeuxLongPollingController(AjaxChannelBroker broker, ObjectMapper objectMapper,
            @Value("${ajax.connect-timeout-ms:30000}") long connectTimeoutMs) {
        this.broker = Objects.requireNonNull(broker, "broker");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.messageReader = objectMapper.reader();
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
     * <p>The {@code message} parameter holds the JSON array of Bayeux messages, or a single message object; the
     * optional {@code jsonp} parameter names the callback-polling callback. Both are read here, from the query string
     * and then from a form body this method reads itself, at most {@value #MAX_REQUEST_BYTES} bytes of it: a longer
     * body is answered 413 without being read further, and Undertow drains its rest after the response, so no
     * oversized body is buffered or parsed. The batch is counted while it is parsed.
     *
     * @param request the request
     * @return the response, already set unless the request holds a connect
     * @throws IOException when the form body cannot be read from the connection, which Spring MVC's exception
     *         handling then answers, as it answers any other failure to read a request
     */
    @RequestMapping(path = COMETD_PATH, method = {RequestMethod.GET, RequestMethod.POST})
    public DeferredResult<ResponseEntity<String>> cometd(HttpServletRequest request) throws IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > MAX_REQUEST_BYTES) {
            LOGGER.warn("Rejected Bayeux request: its body of {} bytes exceeds {} bytes", declaredLength,
                    MAX_REQUEST_BYTES);
            return completed(tooLarge());
        }
        FormFields body = FormFields.NONE;
        if (isFormEncoded(request.getContentType())) {
            byte[] bytes = request.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
            if (bytes.length > MAX_REQUEST_BYTES) {
                // The rest of the body stays unread; Undertow drains it once the response is written.
                LOGGER.warn("Rejected Bayeux request: its body exceeds {} bytes", MAX_REQUEST_BYTES);
                return completed(tooLarge());
            }
            body = decodeForm(bytes, request.getCharacterEncoding());
            if (body == null) {
                return completed(frame(HttpStatus.BAD_REQUEST, List.of(), null));
            }
        }
        // Once the body has been read here, the servlet's parameters hold the query string only (standalone MockMvc
        // also keeps the parameters a test sets); the servlet's order puts them before the body's.
        String queryMessage = request.getParameter(MESSAGE_PARAMETER);
        String queryJsonp = request.getParameter(JSONP_PARAMETER);
        String message = queryMessage != null ? queryMessage : body.message();
        String jsonp = queryJsonp != null ? queryJsonp : body.jsonp();
        if (jsonp != null && !isFunctionReference(jsonp)) {
            // Only a function reference may be reflected into a script response; anything else gets a plain-JSON 400.
            LOGGER.warn("Rejected Bayeux request: the jsonp parameter is not a JavaScript function reference");
            return completed(frame(HttpStatus.BAD_REQUEST, List.of(), null));
        }
        if (message != null && message.length() > MAX_REQUEST_BYTES) {
            LOGGER.warn("Rejected Bayeux request: its message parameter exceeds {} characters", MAX_REQUEST_BYTES);
            return completed(tooLarge());
        }
        ParsedBatch parsed = parseBatch(message);
        if (parsed.outcome() == ParsedBatch.Outcome.MALFORMED) {
            return completed(frame(HttpStatus.BAD_REQUEST, List.of(), jsonp));
        }
        if (parsed.outcome() == ParsedBatch.Outcome.TOO_MANY) {
            return completed(tooLarge());
        }
        List<JsonNode> batch = parsed.messages();
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
     * clients the broker no longer knows, so the bookkeeping cannot outgrow the broker's clients. A handshake the
     * broker refuses for its client bound is denied in-band.
     */
    private Map<String, Object> handshake(JsonNode message) {
        String clientId;
        try {
            clientId = broker.handshake();
        } catch (AjaxChannelBroker.ChannelRefusedException refused) {
            LOGGER.warn("Bayeux handshake refused: {}", refused.reason());
            Map<String, Object> reply = reply(HANDSHAKE);
            reply.put(SUCCESSFUL, Boolean.FALSE);
            reply.put(ERROR, HANDSHAKE_DENIED);
            reply.put(ADVICE, DENIED_HANDSHAKE_ADVICE);
            return withId(reply, message);
        } finally {
            awaitingFirstConnect.removeIf(pending -> !broker.isKnownClient(pending));
        }
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
     * channel names, and echoes {@code subscription} exactly as sent, which is how the client matches the reply. The
     * broker admits a subscription's channels whole, so a refused one subscribes to none of them.
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
                broker.subscribe(clientId, channels);
            } catch (AjaxChannelBroker.ChannelRefusedException refused) {
                LOGGER.warn("Bayeux subscription of client {} refused: {}", loggable(clientId), refused.reason());
                return failed(reply, CANNOT_SUBSCRIBE, message);
            } catch (IllegalArgumentException removed) {
                // Otherwise the broker refuses a subscription only for a client it no longer has: removed
                // concurrently, or removed because the messages held on the channels overflowed its queue.
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
     * dispatch it to the page's subscribers as if it were a message. A publication the broker refuses, on a channel
     * without an inbound handler or beyond its retained-byte bound, is denied in-band.
     */
    private Map<String, Object> publish(String channel, String clientId, JsonNode message) {
        Map<String, Object> reply = reply(channel);
        try {
            JsonNode data = message.get(DATA);
            broker.onPublication(clientId, channel, data == null ? null : objectMapper.treeToValue(data, Object.class));
            reply.put(SUCCESSFUL, Boolean.TRUE);
        } catch (AjaxChannelBroker.ChannelRefusedException refused) {
            LOGGER.warn("Bayeux publication on channel {} refused: {}", loggable(channel), refused.reason());
            reply.put(SUCCESSFUL, Boolean.FALSE);
            reply.put(ERROR, PUBLISH_DENIED);
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
     * Reads the batch the {@code message} parameter holds from a streaming parser, one message at a time, so the batch
     * is counted while it is parsed and no message beyond {@value #MAX_BATCH_MESSAGES} is ever read into a tree. A
     * single JSON object is a batch of one; an array is read element by element.
     *
     * @return the batch; {@link ParsedBatch.Outcome#TOO_MANY} as soon as an element follows
     *         {@value #MAX_BATCH_MESSAGES} read ones, whatever it and the rest of the input hold; or
     *         {@link ParsedBatch.Outcome#MALFORMED} when the parameter is absent, is not JSON, carries anything but
     *         whitespace after its single JSON value, or is neither an object nor an array of objects. Both refusals,
     *         logged at WARN, refuse the batch whole, so no message of it is acted on
     */
    private ParsedBatch parseBatch(String message) {
        if (message == null) {
            LOGGER.warn("Rejected Bayeux request: no message parameter");
            return ParsedBatch.MALFORMED;
        }
        try (JsonParser parser = messageReader.createParser(message)) {
            JsonToken root = parser.nextToken();
            if (root == JsonToken.START_OBJECT) {
                JsonNode single = messageReader.readTree(parser);
                return parser.nextToken() == null ? ParsedBatch.of(List.of(single)) : trailingInput();
            }
            if (root != JsonToken.START_ARRAY) {
                LOGGER.warn("Rejected Bayeux request: the message parameter is neither a JSON object nor an array");
                return ParsedBatch.MALFORMED;
            }
            List<JsonNode> batch = new ArrayList<>();
            while (true) {
                boolean full = batch.size() == MAX_BATCH_MESSAGES;
                // A full batch looks past its last element itself, so an element following it is one too many even
                // when the parser could not read it.
                if (full && elementFollows(parser, message)) {
                    return tooManyMessages();
                }
                JsonToken element = parser.nextToken();
                if (element == JsonToken.END_ARRAY) {
                    break;
                }
                if (full) {
                    return tooManyMessages();
                }
                if (element != JsonToken.START_OBJECT) {
                    LOGGER.warn("Rejected Bayeux request: an element of the message array is not a JSON object");
                    return ParsedBatch.MALFORMED;
                }
                batch.add(messageReader.readTree(parser));
            }
            return parser.nextToken() == null ? ParsedBatch.of(batch) : trailingInput();
        } catch (IOException unparseable) {
            LOGGER.warn("Rejected Bayeux request: the message parameter is not JSON ({})",
                    unparseable.getClass().getName());
            return ParsedBatch.MALFORMED;
        }
    }

    /**
     * Whether another array element follows, in {@code message}, the element the parser has just read: the next
     * character other than JSON whitespace is a comma. Only the characters up to that one are looked at.
     */
    private static boolean elementFollows(JsonParser parser, String message) {
        long offset = parser.currentLocation().getCharOffset();
        if (offset < 0) {
            // No offset known: the parser's next token decides instead.
            return false;
        }
        for (long i = offset; i < message.length(); i++) {
            char c = message.charAt((int) i);
            if (c == ',') {
                return true;
            }
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return false;
            }
        }
        return false;
    }

    /** The refusal of a batch with more than {@value #MAX_BATCH_MESSAGES} messages. */
    private static ParsedBatch tooManyMessages() {
        LOGGER.warn("Rejected Bayeux request: its batch exceeds {} messages", MAX_BATCH_MESSAGES);
        return ParsedBatch.TOO_MANY;
    }

    /** The refusal of a {@code message} that carries a further JSON value after its single one. */
    private static ParsedBatch trailingInput() {
        LOGGER.warn("Rejected Bayeux request: the message parameter carries input after its single JSON value");
        return ParsedBatch.MALFORMED;
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

    /** The answer to a request beyond a size limit: HTTP 413 with {@code []} as plain JSON. */
    private ResponseEntity<String> tooLarge() {
        return frame(HttpStatus.PAYLOAD_TOO_LARGE, List.of(), null);
    }

    /**
     * Whether a request of this Content-Type carries a form body: it starts with
     * {@code application/x-www-form-urlencoded}, in any letter case and with any parameters. Undertow's form parser
     * reads the body of every Content-Type starting with that text in lower case, so each body it would read is read
     * here instead, under the size limit.
     */
    private static boolean isFormEncoded(String contentType) {
        return contentType != null
                && contentType.regionMatches(true, 0, FORM_CONTENT_TYPE, 0, FORM_CONTENT_TYPE.length());
    }

    /**
     * The first {@code message} and the first {@code jsonp} of an {@code application/x-www-form-urlencoded} body,
     * decoded as the servlet decodes form parameters: name/value pairs separated by {@code &}, a name ended by its
     * first {@code =} (a pair without one has the empty value), {@code +} for a space and {@code %XX} for a byte, and
     * the bytes read in {@code encoding}, or UTF-8 when the request declares none. Every pair is decoded, so a body
     * that is not valid form data is refused whole.
     *
     * @return the two values, either {@code null} when the body has none; {@code null}, logged at WARN without any of
     *         the request's text, when a {@code %} is not followed by two hexadecimal digits, bytes are not valid in
     *         the encoding or the JVM does not support the encoding
     */
    private static FormFields decodeForm(byte[] body, String encoding) {
        CharsetDecoder decoder;
        try {
            decoder = (encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding)).newDecoder();
        } catch (IllegalArgumentException unsupported) {
            LOGGER.warn("Rejected Bayeux request: the character encoding of its form body is not supported");
            return null;
        }
        String message = null;
        String jsonp = null;
        try {
            for (int start = 0; start < body.length; ) {
                int end = indexOf(body, (byte) '&', start, body.length);
                if (end > start) {
                    int equals = indexOf(body, (byte) '=', start, end);
                    String name = decodeComponent(body, start, equals, decoder);
                    String value = equals < end ? decodeComponent(body, equals + 1, end, decoder) : "";
                    if (name == null || value == null) {
                        LOGGER.warn("Rejected Bayeux request: its form body holds a % not followed by two "
                                + "hexadecimal digits");
                        return null;
                    }
                    if (message == null && MESSAGE_PARAMETER.equals(name)) {
                        message = value;
                    } else if (jsonp == null && JSONP_PARAMETER.equals(name)) {
                        jsonp = value;
                    }
                }
                start = end + 1;
            }
        } catch (CharacterCodingException invalid) {
            LOGGER.warn("Rejected Bayeux request: its form body holds bytes that are not valid in its character "
                    + "encoding ({})", invalid.getClass().getName());
            return null;
        }
        return new FormFields(message, jsonp);
    }

    /**
     * One name or value of a form body, {@code body[from, to)}, with {@code +} and {@code %XX} decoded.
     *
     * @return the text, or {@code null} when a {@code %} is not followed by two hexadecimal digits
     * @throws CharacterCodingException when the decoded bytes are not valid in the decoder's character encoding
     */
    private static String decodeComponent(byte[] body, int from, int to, CharsetDecoder decoder)
            throws CharacterCodingException {
        byte[] bytes = new byte[to - from];
        int length = 0;
        for (int i = from; i < to; i++) {
            byte b = body[i];
            if (b == '+') {
                bytes[length++] = ' ';
            } else if (b == '%') {
                int high = i + 2 < to ? Character.digit(body[i + 1], 16) : -1;
                int low = i + 2 < to ? Character.digit(body[i + 2], 16) : -1;
                if (high < 0 || low < 0) {
                    return null;
                }
                bytes[length++] = (byte) (high << 4 | low);
                i += 2;
            } else {
                bytes[length++] = b;
            }
        }
        // decode(ByteBuffer) resets the decoder and reports malformed and unmappable input, its default actions.
        return decoder.decode(ByteBuffer.wrap(bytes, 0, length)).toString();
    }

    /** The index of the first {@code target} in {@code bytes[from, to)}, or {@code to} when there is none. */
    private static int indexOf(byte[] bytes, byte target, int from, int to) {
        for (int i = from; i < to; i++) {
            if (bytes[i] == target) {
                return i;
            }
        }
        return to;
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

    /**
     * The {@code message} and {@code jsonp} values a form body holds, each {@code null} when it has none.
     *
     * @param message the body's first {@code message}
     * @param jsonp the body's first {@code jsonp}
     */
    private record FormFields(String message, String jsonp) {

        /** The values of a request without a form body. */
        private static final FormFields NONE = new FormFields(null, null);
    }

    /**
     * What a request's {@code message} holds: a batch to handle, or the reason the request is refused whole.
     *
     * @param outcome whether the batch is handled or refused, and why
     * @param messages the batch's messages in request order; empty unless {@code outcome} is {@link Outcome#BATCH}
     */
    private record ParsedBatch(Outcome outcome, List<JsonNode> messages) {

        private static final ParsedBatch MALFORMED = new ParsedBatch(Outcome.MALFORMED, List.of());
        private static final ParsedBatch TOO_MANY = new ParsedBatch(Outcome.TOO_MANY, List.of());

        private static ParsedBatch of(List<JsonNode> messages) {
            return new ParsedBatch(Outcome.BATCH, messages);
        }

        /** How {@link #parseBatch} read a request's {@code message}. */
        private enum Outcome {

            /** A batch of at most {@value BayeuxLongPollingController#MAX_BATCH_MESSAGES} messages, to handle. */
            BATCH,

            /** Not a JSON object or array of objects, answered HTTP 400. */
            MALFORMED,

            /** More than {@value BayeuxLongPollingController#MAX_BATCH_MESSAGES} messages, answered HTTP 413. */
            TOO_MANY
        }
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

