/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.config;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Named.named;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.read.ListAppender;

import com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.controller.PersonController;
import com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.service.PersonService;
import com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.support.HttpTestClient;

/**
 * Tests the {@code logging.level} caps of {@code application.yml}, which keep person data out of the log when the root
 * level is overridden to DEBUG or TRACE, or {@code --debug} or {@code --trace} is given: the original flows log only
 * {@code Person id is <id>} at ERROR, while below INFO Spring MVC's method processors write the posted person's body
 * bytes and every person JSON the application answers with, and the handler-method and exception-resolver loggers the
 * message of a rejected person body, which quotes that body.
 *
 * <p>Two layers:
 * <ul>
 *   <li>The four loggers bound from the classpath {@code application.yml} exactly as Spring Boot binds
 *       {@code logging.level}, each held at INFO, and held there by the running context.</li>
 *   <li>The log itself, with the Logback root at TRACE and the bound levels applied through Spring Boot's own
 *       {@link LoggingSystem}, as the application applies them at start-up. Through ST-1 {@link HttpTestClient} the
 *       application on its random port stores a person carrying a dummy sentinel in every field, answers that person
 *       and every person, and rejects a person with an unknown property. A Logback {@link ListAppender} on the root
 *       then names every logger outside {@code com.mulesoft.examples} that wrote a sentinel, as text or in the decimal
 *       rendering Spring logs a byte array with: none with every level applied. Control setups prove that the oracle
 *       sees the leak: with none of the four levels applied all four loggers write sentinels, and without any one of
 *       them exactly that logger does. Every setup also asserts the original's own line, {@code Person id is <id>} at
 *       ERROR, and a TRACE request line of an unpinned logger, so the capture is known to see TRACE.</li>
 * </ul>
 *
 * <p>The sentinels are dummy values: none identifies anyone. While the root is at TRACE its own appenders are detached,
 * so that output stays out of the build log. After each test the appender is detached, the root's appenders are
 * attached again and every logger level the test changed is restored.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class SensitiveLogLevelsTest {

    private static final String HANDLER_METHOD = "org.springframework.web.method.HandlerMethod";

    private static final String MVC_METHOD_PROCESSORS = "org.springframework.web.servlet.mvc.method.annotation";

    private static final String BODY_READER = MVC_METHOD_PROCESSORS + ".RequestResponseBodyMethodProcessor";

    private static final String EXCEPTION_RESOLVER = MVC_METHOD_PROCESSORS + ".ExceptionHandlerExceptionResolver";

    private static final String BODY_WRITER = MVC_METHOD_PROCESSORS + ".HttpEntityMethodProcessor";

    /** The loggers {@code application.yml} holds at INFO. */
    private static final Set<String> CAPPED = Set.of(HANDLER_METHOD, BODY_READER, EXCEPTION_RESOLVER, BODY_WRITER);

    /** The prefix of the application's own loggers, the original's {@code logger} elements among them. */
    private static final String APPLICATION_LOGGERS = "com.mulesoft.examples.";

    private static final String DISPATCHER_SERVLET = "org.springframework.web.servlet.DispatcherServlet";

    /** Written by the request filter once the handling of a request has returned, after every MVC log line. */
    private static final String REQUEST_CONTEXT_CLEARED = "Cleared thread-bound request context";

    private static final String FIRSTNAME = "SentinelFirstname";

    private static final String LASTNAME = "SentinelLastname";

    private static final String STREET = "1 Sentinel Street";

    private static final String CITY = "Sentinelville";

    private static final String STATE = "SentinelState";

    private static final String ZIP_CODE = "SENTINEL-ZIP-7731";

    private static final String AGE = "918273645";

    /** An unknown property, which the person mapper rejects and names in its message. */
    private static final String UNKNOWN_FIELD = "SentinelUnknownField";

    private static final String UNKNOWN_VALUE = "SentinelUnknownValue";

    /** A person holding every sentinel, compact and in {@code Person}'s property order, as the service writes it. */
    private static final String PERSON_JSON = "{\"firstname\":\"" + FIRSTNAME + "\",\"lastname\":\"" + LASTNAME
            + "\",\"address\":{\"streetAddress\":\"" + STREET + "\",\"city\":\"" + CITY + "\",\"state\":\"" + STATE
            + "\",\"zipCode\":\"" + ZIP_CODE + "\"},\"age\":" + AGE + "}";

    /** {@link #PERSON_JSON} with the unknown property appended, which the message of its rejection quotes. */
    private static final String REJECTED_PERSON_JSON = PERSON_JSON.substring(0, PERSON_JSON.length() - 1)
            + ",\"" + UNKNOWN_FIELD + "\":\"" + UNKNOWN_VALUE + "\"}";

    /** Every value that must stay out of the framework's log lines. */
    private static final List<String> SENTINELS =
            List.of(FIRSTNAME, LASTNAME, STREET, CITY, STATE, ZIP_CODE, AGE, UNKNOWN_FIELD, UNKNOWN_VALUE);

    /** {@link #SENTINELS} as Spring renders a byte array in a log line: {@code 83, 101, 110, ...}. */
    private static final List<String> SENTINEL_BYTES =
            SENTINELS.stream().map(SensitiveLogLevelsTest::decimalBytes).toList();

    /** Requests each setup sends; each one's filter writes {@link #REQUEST_CONTEXT_CLEARED} once. */
    private static final int REQUESTS = 4;

    /** Longest wait for the request threads' last log lines after the final response. */
    private static final Duration LOG_WAIT = Duration.ofSeconds(10);

    /** Most cause levels of a logged exception searched for a sentinel. */
    private static final int CAUSE_DEPTH = 16;

    /** Most offending lines, and characters of each, a failure reports. */
    private static final int REPORTED_LINES = 10;

    private static final int REPORTED_CHARACTERS = 300;

    @LocalServerPort
    private int port;

    private final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

    private final LoggingSystem loggingSystem = LoggingSystem.get(getClass().getClassLoader());

    /** The Logback level each logger the test changed had before, {@code null} meaning inherited. */
    private final Map<Logger, Level> previousLevels = new LinkedHashMap<>();

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

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
     * {@code application.yml} holds {@code logger} at INFO, bound the way Spring Boot applies {@code logging.level},
     * and the running context, which Spring Boot started with that file, holds it there, so a root DEBUG or TRACE
     * override does not reach it.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {HANDLER_METHOD, BODY_READER, EXCEPTION_RESOLVER, BODY_WRITER})
    void applicationYmlHoldsPersonDataLoggerAtInfo(String logger) throws IOException {
        assertThat(applicationYmlLevels()).as("logging.level in application.yml").containsEntry(logger, LogLevel.INFO);
        assertThat(loggerContext.getLogger(logger).getLevel()).as("level of %s in the running context", logger)
                .isEqualTo(Level.INFO);
    }

    /** Every capped logger, then none, then all but one of them in turn: the loggers left below INFO. */
    static Stream<Named<Set<String>>> loggersLeftBelowInfo() {
        return Stream.concat(
                Stream.of(named("application.yml levels", Set.of()),
                        named("none of the levels (control)", CAPPED)),
                CAPPED.stream().sorted()
                        .map(logger -> named("all levels but " + logger + " (control)", Set.of(logger))));
    }

    /**
     * With the root at TRACE and every {@code application.yml} level applied except those of {@code leftBelowInfo},
     * which inherit TRACE, the person exchanges answer as the original flows did, the original's ERROR line is
     * written, and exactly the {@code leftBelowInfo} loggers, of all loggers outside the application's own, write a
     * sentinel: none with every level applied.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("loggersLeftBelowInfo")
    void rootTraceLogCarriesPersonDataOnlyThroughLoggersLeftBelowInfo(Set<String> leftBelowInfo) throws IOException {
        Map<String, LogLevel> levels = applicationYmlLevels();
        assertThat(levels.keySet().containsAll(leftBelowInfo))
                .as("logging.level in application.yml names %s", leftBelowInfo).isTrue();
        Logger root = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        root.iteratorForAppenders().forEachRemaining(detachedAppenders::add);
        detachedAppenders.forEach(root::detachAppender);
        appender.setContext(loggerContext);
        appender.start();
        root.addAppender(appender);
        setLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.TRACE);
        // A logger left out inherits the root's TRACE, whatever an earlier context in this JVM set for it.
        levels.forEach((logger, level) -> setLevel(logger, leftBelowInfo.contains(logger) ? null : level));

        int id = exchangePersons();

        // Undertow may deliver a response before the request thread has written its last lines.
        await().atMost(LOG_WAIT).until(() -> events().stream()
                .filter(event -> OrderedRequestContextFilter.class.getName().equals(event.getLoggerName())
                        && message(event).startsWith(REQUEST_CONTEXT_CLEARED))
                .count() >= REQUESTS);
        List<ILoggingEvent> events = events();

        List<ILoggingEvent> leaks = events.stream()
                .filter(event -> !event.getLoggerName().startsWith(APPLICATION_LOGGERS))
                .filter(SensitiveLogLevelsTest::carriesSentinel)
                .toList();
        Set<String> writers = leaks.stream()
                .map(ILoggingEvent::getLoggerName)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(writers).as(() -> "loggers outside " + APPLICATION_LOGGERS + "* that wrote person data, in: "
                        + report(leaks))
                .containsExactlyInAnyOrderElementsOf(leftBelowInfo);
        assertThat(events).as("the original's ERROR line for GET /person/%d", id)
                .anyMatch(event -> event.getLevel() == Level.ERROR
                        && PersonService.class.getName().equals(event.getLoggerName())
                        && ("Person id is " + id).equals(message(event)));
        assertThat(events).as("the unpinned DispatcherServlet TRACE request line of GET /person/%d", id)
                .anyMatch(event -> event.getLevel() == Level.TRACE
                        && DISPATCHER_SERVLET.equals(event.getLoggerName())
                        && message(event).startsWith("GET \"/person/" + id + "\""));
    }

    /**
     * Stores {@link #PERSON_JSON}, reads it back alone and in the collection, and posts {@link #REJECTED_PERSON_JSON},
     * asserting each response as the application answers it.
     *
     * @return the id the person was stored under
     */
    private int exchangePersons() {
        HttpTestClient client = HttpTestClient.localhost(port);

        HttpTestClient.Response stored = client.post("/person", "application/json", PERSON_JSON);
        assertThat(stored.statusLine()).as(stored.toString()).isEqualTo("HTTP/1.1 201 Created");
        assertThat(stored.bodyAsString()).isEqualTo(PersonService.STORE_SUCCESS_RESPONSE);
        String location = stored.header("Location");
        assertThat(location).as(stored.toString()).startsWith(PersonController.LOCATION_PREFIX);
        int id = Integer.parseInt(location.substring(PersonController.LOCATION_PREFIX.length()));

        HttpTestClient.Response person = client.get("/person/" + id);
        assertThat(person.statusLine()).as(person.toString()).isEqualTo("HTTP/1.1 200 OK");
        assertThat(person.bodyAsString()).isEqualTo(PERSON_JSON);

        HttpTestClient.Response persons = client.get("/person");
        assertThat(persons.statusLine()).as(persons.toString()).isEqualTo("HTTP/1.1 200 OK");
        assertThat(persons.bodyAsString()).contains(PERSON_JSON);

        HttpTestClient.Response rejected = client.post("/person", "application/json", REJECTED_PERSON_JSON);
        assertThat(rejected.statusLine()).as(rejected.toString()).isEqualTo("HTTP/1.1 500 Internal Server Error");
        assertThat(rejected.bodyAsString()).contains("Unrecognized field \\\"" + UNKNOWN_FIELD + "\\\"");
        return id;
    }

    /** A copy of the events captured so far, taken under the lock {@link ListAppender} appends under. */
    private List<ILoggingEvent> events() {
        synchronized (appender) {
            return new ArrayList<>(appender.list);
        }
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

    /** The event's formatted message, {@code "null"} for an event logged without one. */
    private static String message(ILoggingEvent event) {
        return String.valueOf(event.getFormattedMessage());
    }

    /** Whether the event's message, or the message of its exception or a cause, holds a sentinel. */
    private static boolean carriesSentinel(ILoggingEvent event) {
        StringBuilder text = new StringBuilder(message(event));
        IThrowableProxy thrown = event.getThrowableProxy();
        for (int depth = 0; thrown != null && depth < CAUSE_DEPTH; depth++) {
            text.append('\n').append(thrown.getMessage());
            thrown = thrown.getCause();
        }
        return SENTINELS.stream().anyMatch(sentinel -> text.indexOf(sentinel) >= 0)
                || SENTINEL_BYTES.stream().anyMatch(bytes -> text.indexOf(bytes) >= 0);
    }

    /** The first offending events as {@code LEVEL logger: message}, each cut short, for a readable failure. */
    private static String report(List<ILoggingEvent> leaks) {
        return leaks.stream()
                .limit(REPORTED_LINES)
                .map(event -> {
                    String line = event.getLevel() + " " + event.getLoggerName() + ": " + message(event);
                    return line.length() > REPORTED_CHARACTERS ? line.substring(0, REPORTED_CHARACTERS) + "..." : line;
                })
                .collect(Collectors.joining("\n", "[\n", "\n]"));
    }

    /** {@code text}'s UTF-8 bytes as {@code ObjectUtils.nullSafeToString} lists a byte array: decimal, comma, space. */
    private static String decimalBytes(String text) {
        StringJoiner joined = new StringJoiner(", ");
        for (byte b : text.getBytes(UTF_8)) {
            joined.add(Byte.toString(b));
        }
        return joined.toString();
    }
}
