/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.websphere_mq.config;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.net.URLEncoder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.filter.OrderedRequestContextFilter;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.AppenderBase;

import com.mulesoft.examples.websphere_mq.WebsphereMqApplication;
import com.mulesoft.examples.websphere_mq.service.MqProcessingService;
import com.mulesoft.examples.websphere_mq.support.HttpTestClient;

/**
 * Tests the {@code logging.level} caps of {@code application.yml}, which keep the texts sent through the page out of
 * the log when the root level is overridden to DEBUG or TRACE. The original {@code websphere-mq.xml} declares no
 * logger; its only log line is the {@code test:component}'s {@code logMessageDetails}, which
 * {@link MqProcessingService} writes at INFO ({@code Message Received in service: MessageProcessor. Content is:
 * <text>}). Below INFO, Spring's servlet and handler loggers write the {@code message} query of a {@code GET} to
 * {@code /ajax/cometd} and the async result and response body a held {@code /meta/connect} is answered with; the
 * Bayeux handler takes the servlet request itself, so its arguments carry no message. Spring JMS writes the messages
 * it sends and the messages its {@code @JmsListener} methods receive; Undertow's request-IO logger writes a query it
 * cannot decode.
 *
 * <p>Two layers:
 * <ul>
 *   <li>Each cap bound from the classpath {@code application.yml} exactly as Spring Boot binds
 *       {@code logging.level}. This layer alone guards {@code JmsTemplate} and
 *       {@code MessagingMessageListenerAdapter}: their {@code Sending created message:} and {@code Processing [...]}
 *       DEBUG lines render the JMS message's {@code toString()}, which holds the text for IBM MQ's
 *       {@code JMSTextMessage} but not for the embedded Artemis broker's message, so the log layer cannot observe
 *       those two leaks.</li>
 *   <li>The log itself: the whole application runs on a random port against the {@code test} profile's embedded
 *       Artemis broker, which stands in for the IBM MQ queue manager. The Logback root is set to TRACE and the bound
 *       levels are applied through Spring Boot's own {@link LoggingSystem}, as the application does at start-up with
 *       {@code logging.level.root=TRACE}. Then the page's exchange is played over the wire through ST-1: handshake,
 *       subscription to {@code /services/wmqExample/dequeue}, one marker published on
 *       {@code /services/wmqExample/enqueue} as a {@code GET} with its {@code message} query and another as a form
 *       {@code POST}, a {@code GET} whose {@code message} query holds a third marker behind a malformed percent
 *       escape, which Undertow answers with {@code 400} before the application sees it, and held
 *       {@code /meta/connect} requests until both processed texts arrive. Each arrives exactly as
 *       {@code <marker> - processed}, {@link MqProcessingService} writes its INFO line with each marker, and a
 *       recording appender on the root names every logger that wrote a marker in its formatted message, its
 *       arguments or its throwable chain, outside the application's own packages and the Artemis stand-in: none.
 *       The control setup clears the caps, so their categories inherit the root's TRACE, and proves that the
 *       oracle sees the leak: exactly the three Spring web loggers, Spring Messaging's {@code HandlerMethod} and
 *       Undertow's {@code io.undertow.request.io} write a marker.</li>
 * </ul>
 *
 * <p>The markers are letters and digits only, so their URL-encoded, form-encoded and JSON forms are the markers
 * themselves, and each run draws its own. While the root is at TRACE its own appenders are detached, so that output
 * stays out of the build log. After each test the recording appender is detached, the root's appenders are attached
 * again and every logger level the test changed is restored. The context has its own embedded Artemis server id and
 * is closed after the class.
 */
@SpringBootTest(classes = WebsphereMqApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // Two embedded Artemis servers with one id in a JVM share an in-VM acceptor; other cached test contexts of
        // this project run theirs with the default id and with 86 and 87.
        "spring.artemis.embedded.server-id=88",
        // The listener is pinned to IPv4 loopback and reached by that literal: "localhost" can resolve to ::1.
        "server.address=" + SensitiveLogLevelsTest.LOOPBACK})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SensitiveLogLevelsTest {

    /** IPv4 loopback literal the listener binds to and ST-1 connects to. */
    static final String LOOPBACK = "127.0.0.1";

    private static final String DISPATCHER_SERVLET = "org.springframework.web.servlet.DispatcherServlet";

    private static final String WEB_HANDLER_METHOD = "org.springframework.web.method.HandlerMethod";

    private static final String HANDLER_ADAPTER =
            "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter";

    private static final String ENTITY_PROCESSOR =
            "org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor";

    private static final String JMS_TEMPLATE = "org.springframework.jms.core.JmsTemplate";

    private static final String JMS_LISTENER_ADAPTER =
            "org.springframework.jms.listener.adapter.MessagingMessageListenerAdapter";

    private static final String MESSAGING_HANDLER_METHOD = "org.springframework.messaging.handler.HandlerMethod";

    private static final String UNDERTOW_REQUEST_IO = "io.undertow.request.io";

    /** The application's own loggers, outside what the caps govern. */
    private static final String APPLICATION_LOGGERS = "com.mulesoft.examples.";

    /** The embedded broker, which stands in for the IBM MQ queue manager and is no part of the application. */
    private static final String STAND_IN_LOGGERS = "org.apache.activemq.";

    private static final String SERVER_PATH = "/services/wmqExample";

    private static final String ENQUEUE = SERVER_PATH + "/enqueue";

    private static final String DEQUEUE = SERVER_PATH + "/dequeue";

    private static final String COMETD = "/ajax/cometd";

    /** The content type the copied {@code dojox.cometd} client posts its {@code message} parameter with. */
    private static final String FORM = "application/x-www-form-urlencoded";

    private static final String META_PREFIX = "/meta/";

    private static final String HANDSHAKE = "/meta/handshake";

    private static final String CONNECT = "/meta/connect";

    private static final String SUBSCRIBE = "/meta/subscribe";

    /** The original component's {@code appendString}, leading space included. */
    private static final String PROCESSED_SUFFIX = " - processed";

    /** The original {@code test:component} line of {@link MqProcessingService}, before the text. */
    private static final String CONTENT_IS = "Message Received in service: MessageProcessor. Content is: ";

    /** A percent sign followed by two characters that are not hexadecimal digits. */
    private static final String MALFORMED_ESCAPE = "%ZZ";

    /** Bound of the wait for the flows, as in {@code WebsphereMqBrokerTest}. */
    private static final Duration FLOW_TIMEOUT = Duration.ofSeconds(20);

    @LocalServerPort
    int port;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AtomicInteger messageIds = new AtomicInteger();

    private final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

    private final LoggingSystem loggingSystem = LoggingSystem.get(getClass().getClassLoader());

    /** The Logback level each logger the test changed had before, {@code null} meaning inherited. */
    private final Map<Logger, Level> previousLevels = new LinkedHashMap<>();

    private final RecordingAppender appender = new RecordingAppender();

    /** The root logger's own appenders, detached while the root is at TRACE. */
    private final List<Appender<ILoggingEvent>> detachedAppenders = new ArrayList<>();

    @AfterEach
    void restoreLogging() {
        Logger root = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(appender);
        appender.stop();
        detachedAppenders.forEach(root::addAppender);
        previousLevels.forEach(Logger::setLevel);
    }

    /**
     * {@code application.yml} holds {@code logger} at {@code level}, bound the way Spring Boot applies
     * {@code logging.level}, so a root DEBUG or TRACE override, or {@code --debug}/{@code --trace}, does not reach it.
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            DISPATCHER_SERVLET + ", WARN",
            WEB_HANDLER_METHOD + ", INFO",
            HANDLER_ADAPTER + ", INFO",
            ENTITY_PROCESSOR + ", INFO",
            JMS_TEMPLATE + ", INFO",
            JMS_LISTENER_ADAPTER + ", INFO",
            MESSAGING_HANDLER_METHOD + ", INFO",
            UNDERTOW_REQUEST_IO + ", INFO"})
    void applicationYmlCapsSensitiveLogger(String logger, LogLevel level) throws IOException {
        assertThat(applicationYmlLevels()).as("logging.level in application.yml").containsEntry(logger, level);
    }

    static Stream<Arguments> levelSetups() {
        return Stream.of(
                arguments(named("application.yml levels", true), Set.of()),
                arguments(named("no levels (control)", false),
                        Set.of(DISPATCHER_SERVLET, HANDLER_ADAPTER, ENTITY_PROCESSOR, MESSAGING_HANDLER_METHOD,
                                UNDERTOW_REQUEST_IO)));
    }

    /**
     * With the root at TRACE and the {@code application.yml} levels applied, or cleared for the control, the page's
     * exchange delivers each published marker exactly as {@code <marker> - processed}, the original
     * {@code test:component} line is written at INFO with each marker, and exactly the {@code expectedWriters}
     * loggers write a marker: none with the levels applied.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("levelSetups")
    void rootTraceLogCarriesMarkersOnlyThroughLoggersLeftBelowInfo(boolean levelsApplied,
            Set<String> expectedWriters) throws IOException {
        String run = UUID.randomUUID().toString().replace("-", "");
        String queried = "Queriedmarker" + run;
        String posted = "Postedmarker" + run;
        String malformed = "Malformedmarker" + run;
        captureAtRootTrace(levelsApplied);

        String clientId = handshake();
        String replyTo = ENQUEUE + "#" + clientId;
        // The first connect after a handshake is answered at once; every later one is held until a delivery arrives.
        List<JsonNode> deliveries = new ArrayList<>(connect(clientId));
        subscribe(clientId, DEQUEUE);
        assertSuccessful(reply(messages(getBayeux(batch(publication(clientId, replyTo, queried)))), ENQUEUE));
        assertSuccessful(reply(messages(postBayeux(batch(publication(clientId, replyTo, posted)))), ENQUEUE));
        HttpTestClient.Response rejected = http().get(COMETD + "?message=" + MALFORMED_ESCAPE + malformed);
        List<String> processed = List.of(queried + PROCESSED_SUFFIX, posted + PROCESSED_SUFFIX);
        await().atMost(FLOW_TIMEOUT).pollInterval(Duration.ofMillis(100)).pollInSameThread()
                .until(() -> {
                    deliveries.addAll(connect(clientId));
                    return dequeueData(deliveries).containsAll(processed);
                });
        // One more connect collects whatever was published after the processed texts.
        deliveries.addAll(connect(clientId));
        List<LoggedEvent> events = appender.events();

        assertThat(rejected.status()).as("the malformed escape answered by Undertow: %s", rejected).isEqualTo(400);
        assertThat(dequeueData(deliveries)).as("data delivered on %s among %s", DEQUEUE, deliveries)
                .containsExactlyInAnyOrderElementsOf(processed);
        assertThat(events).as("the unpinned OrderedRequestContextFilter TRACE line of a GET publication")
                .anyMatch(event -> event.level() == Level.TRACE
                        && OrderedRequestContextFilter.class.getName().equals(event.logger())
                        && event.message().startsWith("Bound request context to thread: ")
                        && event.message().contains(" GET " + COMETD));
        for (String marker : List.of(queried, posted)) {
            assertThat(events).as("the original test:component INFO line of %s", marker)
                    .anyMatch(event -> event.level() == Level.INFO
                            && MqProcessingService.class.getName().equals(event.logger())
                            && event.message().equals(CONTENT_IS + marker));
        }
        assertThat(markerWriters(events, List.of(queried, posted, malformed))).as("loggers that wrote a marker")
                .containsExactlyInAnyOrderElementsOf(expectedWriters);
    }

    /**
     * Detaches the root's appenders, attaches {@link #appender} instead, sets the root to TRACE and applies the
     * {@code application.yml} levels, or clears them so that their categories inherit TRACE.
     */
    private void captureAtRootTrace(boolean levelsApplied) throws IOException {
        Logger root = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        root.iteratorForAppenders().forEachRemaining(detachedAppenders::add);
        detachedAppenders.forEach(root::detachAppender);
        appender.setContext(loggerContext);
        appender.start();
        root.addAppender(appender);
        setLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.TRACE);
        // A category left out inherits the root's TRACE, whatever an earlier Spring Boot context in this JVM set.
        applicationYmlLevels().forEach((category, level) -> setLevel(category, levelsApplied ? level : null));
    }

    /** Names the loggers outside the application and the stand-in whose events carry one of {@code markers}. */
    private static Set<String> markerWriters(List<LoggedEvent> events, List<String> markers) {
        return events.stream()
                .filter(event -> !event.logger().startsWith(APPLICATION_LOGGERS))
                .filter(event -> event.texts().stream().anyMatch(text -> markers.stream().anyMatch(text::contains)))
                .map(LoggedEvent::logger)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** The {@code logging.level} map of the classpath {@code application.yml}, bound as Spring Boot binds it. */
    private static Map<String, LogLevel> applicationYmlLevels() throws IOException {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        return new Binder(ConfigurationPropertySources.from(documents))
                .bind("logging.level", Bindable.mapOf(String.class, LogLevel.class))
                .orElseGet(Map::of);
    }

    /**
     * Sets a level through Spring Boot's logging system, {@code null} meaning inherited, remembering the logger's
     * Logback level the first time.
     */
    private void setLevel(String name, LogLevel level) {
        Logger logger = LoggingSystem.ROOT_LOGGER_NAME.equals(name)
                ? loggerContext.getLogger(Logger.ROOT_LOGGER_NAME)
                : loggerContext.getLogger(name);
        previousLevels.putIfAbsent(logger, logger.getLevel());
        loggingSystem.setLogLevel(name, level);
    }

    /** Performs the page's Bayeux handshake and returns the {@code clientId} the server assigned. */
    private String handshake() throws IOException {
        ObjectNode handshake = message(HANDSHAKE).put("version", "1.0").put("minimumVersion", "0.9");
        handshake.putArray("supportedConnectionTypes").add("long-polling");
        JsonNode reply = reply(messages(postBayeux(batch(handshake))), HANDSHAKE);
        assertSuccessful(reply);
        String clientId = reply.path("clientId").asText();
        assertThat(clientId).as(reply.toString()).isNotBlank();
        return clientId;
    }

    private void subscribe(String clientId, String channel) throws IOException {
        ObjectNode subscribe = message(SUBSCRIBE).put("clientId", clientId).put("subscription", channel);
        JsonNode reply = reply(messages(postBayeux(batch(subscribe))), SUBSCRIBE);
        assertSuccessful(reply);
        assertThat(reply.path("subscription").asText()).as(reply.toString()).isEqualTo(channel);
    }

    /** Sends a {@code /meta/connect} and returns the deliveries its answer carries. */
    private List<JsonNode> connect(String clientId) throws IOException {
        ObjectNode connect = message(CONNECT).put("clientId", clientId).put("connectionType", "long-polling");
        JsonNode messages = messages(postBayeux(batch(connect)));
        assertSuccessful(reply(messages, CONNECT));
        List<JsonNode> deliveries = new ArrayList<>();
        for (JsonNode message : messages) {
            if (!channel(message).startsWith(META_PREFIX)) {
                deliveries.add(message);
            }
        }
        return deliveries;
    }

    /** The {@code mule.rpc} publication of {@code text} on the enqueue channel, as the page sends it. */
    private ObjectNode publication(String clientId, String replyTo, String text) {
        ObjectNode rpc = message(ENQUEUE).put("clientId", clientId);
        rpc.putObject("data").put("data", text).put("replyTo", replyTo);
        return rpc;
    }

    /** A Bayeux message on {@code channel} with the next message {@code id}. */
    private ObjectNode message(String channel) {
        return objectMapper.createObjectNode()
                .put("channel", channel)
                .put("id", String.valueOf(messageIds.incrementAndGet()));
    }

    /** The JSON array of one message, as {@code dojox.cometd} sends every request. */
    private String batch(ObjectNode message) throws IOException {
        return objectMapper.writeValueAsString(objectMapper.createArrayNode().add(message));
    }

    /** Posts a Bayeux batch as the form parameter {@code message}, as the copied client does, and expects HTTP 200. */
    private HttpTestClient.Response postBayeux(String jsonArray) {
        return expectOk(http().post(COMETD, FORM, "message=" + URLEncoder.encode(jsonArray, UTF_8)));
    }

    /** Sends a Bayeux batch as the query parameter {@code message} of a {@code GET} and expects HTTP 200. */
    private HttpTestClient.Response getBayeux(String jsonArray) {
        return expectOk(http().get(COMETD + "?message=" + URLEncoder.encode(jsonArray, UTF_8)));
    }

    private static HttpTestClient.Response expectOk(HttpTestClient.Response response) {
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        return response;
    }

    private JsonNode messages(HttpTestClient.Response response) throws IOException {
        JsonNode messages = objectMapper.readTree(response.body());
        assertThat(messages.isArray()).as(response.toString()).isTrue();
        return messages;
    }

    private HttpTestClient http() {
        return new HttpTestClient(LOOPBACK, port);
    }

    /** The first message of the answer on {@code channel}. */
    private static JsonNode reply(JsonNode messages, String channel) {
        for (JsonNode message : messages) {
            if (channel.equals(channel(message))) {
                return message;
            }
        }
        return fail("No message on channel %s in %s", channel, messages);
    }

    private static void assertSuccessful(JsonNode reply) {
        assertThat(reply.path("successful").isBoolean() && reply.path("successful").asBoolean())
                .as(reply.toString())
                .isTrue();
    }

    private static String channel(JsonNode message) {
        return message.path("channel").asText();
    }

    /** The {@code data} texts of the deliveries on the dequeue channel, in arrival order. */
    private static List<String> dequeueData(List<JsonNode> deliveries) {
        return deliveries.stream()
                .filter(delivery -> DEQUEUE.equals(channel(delivery)))
                .map(delivery -> delivery.path("data").asText())
                .toList();
    }

    /**
     * Every text an event renders: its formatted message, each argument, and the class name and message of its
     * throwable and of every cause and suppressed throwable.
     */
    private static List<String> loggedTexts(ILoggingEvent event) {
        List<String> texts = new ArrayList<>();
        texts.add(String.valueOf(event.getFormattedMessage()));
        Object[] arguments = event.getArgumentArray();
        if (arguments != null) {
            for (Object argument : arguments) {
                texts.add(String.valueOf(argument));
            }
        }
        addThrowableTexts(event.getThrowableProxy(), texts);
        return texts;
    }

    private static void addThrowableTexts(IThrowableProxy proxy, List<String> texts) {
        if (proxy == null || proxy.isCyclic()) {
            return;
        }
        texts.add(proxy.getClassName());
        texts.add(String.valueOf(proxy.getMessage()));
        addThrowableTexts(proxy.getCause(), texts);
        IThrowableProxy[] suppressed = proxy.getSuppressed();
        if (suppressed != null) {
            for (IThrowableProxy each : suppressed) {
                addThrowableTexts(each, texts);
            }
        }
    }

    /**
     * One log event as it was rendered when appended.
     *
     * @param logger  the logger's name
     * @param level   the event's level
     * @param message the formatted message
     * @param texts   every text the event renders, as {@link #loggedTexts(ILoggingEvent)} lists them
     */
    private record LoggedEvent(String logger, Level level, String message, List<String> texts) {
    }

    /**
     * Records every event outside the Artemis stand-in, rendered when it is appended, so that a mutable argument is
     * read as it was then; the stand-in, the bulk of a TRACE log, is never rendered or kept.
     */
    private static final class RecordingAppender extends AppenderBase<ILoggingEvent> {

        private final List<LoggedEvent> events = new ArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            if (event.getLoggerName().startsWith(STAND_IN_LOGGERS)) {
                return;
            }
            // AppenderBase calls this under the appender's own lock.
            events.add(new LoggedEvent(event.getLoggerName(), event.getLevel(),
                    String.valueOf(event.getFormattedMessage()), loggedTexts(event)));
        }

        /** The events recorded so far, read under the lock the appends take. */
        synchronized List<LoggedEvent> events() {
            return List.copyOf(events);
        }
    }
}
