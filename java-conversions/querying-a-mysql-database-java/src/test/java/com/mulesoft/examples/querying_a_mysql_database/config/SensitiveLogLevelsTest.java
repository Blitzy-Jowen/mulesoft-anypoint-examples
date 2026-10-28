/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.querying_a_mysql_database.config;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.read.ListAppender;

import com.mulesoft.examples.querying_a_mysql_database.support.HttpTestClient;

/**
 * Tests the {@code logging.level} caps of {@code application.yml}, which keep the employees' names out of the log
 * when the root level is overridden to DEBUG or TRACE. {@code database-to-json.xml} has no logger, so the original
 * logged neither the queried last name nor the first names it returned at any level. Below INFO, the
 * {@code DispatcherServlet} writes the request line with its {@code lastname} query string, Spring JDBC every bound
 * statement parameter, and the Spring MVC method processors the JSON response body; for a query that does not
 * percent-decode, Undertow's {@code io.undertow.request.io} writes the parse failure, whose exception quotes the value.
 *
 * <p>Two layers:
 * <ul>
 *   <li>Each cap bound from the classpath {@code application.yml} exactly as Spring Boot binds
 *       {@code logging.level}.</li>
 *   <li>The log itself: the whole application runs on a random port over the {@code test} profile's H2 database,
 *       which holds one sentinel employee for the duration of each test. The Logback root is set to TRACE and the
 *       bound levels are applied through Spring Boot's own {@link LoggingSystem}, as the application does at
 *       start-up with {@code logging.level.root=TRACE}; then the employee is queried by last name. A Logback
 *       {@link ListAppender} on the root names every logger outside the application's own packages that wrote a
 *       sentinel: none. The control setup clears the caps, so each capped category inherits the root's TRACE, and
 *       proves that the oracle sees the leak: exactly the capped loggers that take part in the query write the
 *       sentinels. A second request sends the last name with an undecodable escape, raw, so that Undertow's request
 *       parsing rejects it before any servlet runs; there the control shows {@code io.undertow.request.io} alone
 *       writing it.</li>
 * </ul>
 *
 * <p>The sentinels are dummy names that identify no one. While the root is at TRACE its own appenders are detached,
 * so that output stays out of the build log. After each test the sentinel employee is deleted, the appender is
 * detached, the root's appenders are attached again and every logger level the test changed is restored.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SensitiveLogLevelsTest {

    private static final String DISPATCHER_SERVLET = "org.springframework.web.servlet.DispatcherServlet";

    private static final String STATEMENT_CREATOR = "org.springframework.jdbc.core.StatementCreatorUtils";

    private static final String BODY_WRITER =
            "org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor";

    private static final String HANDLER_METHOD = "org.springframework.web.method.HandlerMethod";

    /** Undertow's request I/O logger; its DEBUG line for a request that fails to parse carries the exception. */
    private static final String REQUEST_PARSER = "io.undertow.request.io";

    /** Unpinned; its TRACE line closes every request, after the handler and the error handling returned. */
    private static final String REQUEST_CONTEXT_FILTER =
            "org.springframework.boot.web.servlet.filter.OrderedRequestContextFilter";

    private static final String REQUEST_END = "Cleared thread-bound request context";

    /** Unpinned parent of {@link #REQUEST_PARSER}; its TRACE line opens each connection, before any parsing. */
    private static final String UNDERTOW_REQUEST = "io.undertow.request";

    private static final String CONNECTION_OPENED = "Opened connection with";

    /** Unpinned; its TRACE lines name each channel listener XNIO invokes, a closed connection's close listener last. */
    private static final String XNIO_LISTENER = "org.xnio.listener";

    private static final String CONNECTION_CLOSED =
            "Invoking listener io.undertow.server.AbstractServerConnection$CloseSetter";

    /** The application's own loggers, outside what the caps govern. */
    private static final String APPLICATION_LOGGERS = "com.mulesoft.examples.";

    /** Employee number of the sentinel row, above the 1011..1112 of {@code import.sql}. */
    private static final int SENTINEL_EMPLOYEE_NO = 9901;

    /** The sentinel employee's first name, at most the 14 characters of {@code employees.first_name}. */
    private static final String FIRST_NAME = "Sentinelfirst";

    /** The sentinel employee's last name, at most the 16 characters of {@code employees.last_name}. */
    private static final String LAST_NAME = "Sentinellast";

    /** {@link #LAST_NAME} followed by {@code %ZZ}, which is not valid percent-encoding, so the query cannot decode. */
    private static final String UNDECODABLE_LAST_NAME = LAST_NAME + "%ZZ";

    /** Every value that must stay out of the framework loggers' output. */
    private static final List<String> SENTINELS = List.of(FIRST_NAME, LAST_NAME);

    /** {@link #SENTINELS} and the decimal renderings Spring logs a {@code byte[]} body with. */
    private static final List<String> SENTINEL_RENDERINGS = Stream.concat(SENTINELS.stream(),
            SENTINELS.stream().map(SensitiveLogLevelsTest::decimalBytes)).toList();

    /** Longest wait for the request thread's last log line. */
    private static final Duration REQUEST_END_TIMEOUT = Duration.ofSeconds(10);

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

    private final LoggingSystem loggingSystem = LoggingSystem.get(getClass().getClassLoader());

    /** The Logback level each logger the test changed had before, {@code null} meaning inherited. */
    private final Map<Logger, Level> previousLevels = new LinkedHashMap<>();

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    /** The root logger's own appenders, detached while the root is at TRACE. */
    private final List<Appender<ILoggingEvent>> detachedAppenders = new ArrayList<>();

    @AfterEach
    void restoreLoggingAndData() {
        Logger root = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(appender);
        appender.stop();
        detachedAppenders.forEach(root::addAppender);
        previousLevels.forEach(Logger::setLevel);
        // The context, and with it the database, may be shared with other test classes.
        jdbcTemplate.update("delete from employees where no = ?", SENTINEL_EMPLOYEE_NO);
    }

    /**
     * {@code application.yml} holds {@code logger} at {@code level}, bound the way Spring Boot applies
     * {@code logging.level}, so a root DEBUG or TRACE override, or {@code --debug}/{@code --trace}, does not reach it.
     * {@code DispatcherServlet} is held at WARN, because its only INFO lines are the servlet start-up pair, which a
     * root WARN must silence.
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            DISPATCHER_SERVLET + ", WARN",
            STATEMENT_CREATOR + ", INFO",
            BODY_WRITER + ", INFO",
            HANDLER_METHOD + ", INFO",
            "org.springframework.web.HttpLogging, INFO",
            REQUEST_PARSER + ", INFO"
    })
    void applicationYmlCapsSensitiveLogger(String logger, LogLevel level) throws IOException {
        assertThat(applicationYmlLevels()).as("logging.level in application.yml").containsEntry(logger, level);
    }

    static Stream<Arguments> levelSetups() {
        return Stream.of(
                arguments(named("application.yml levels", true), Set.of()),
                arguments(named("no levels (control)", false),
                        Set.of(DISPATCHER_SERVLET, STATEMENT_CREATOR, BODY_WRITER)));
    }

    /**
     * With the root at TRACE and the {@code application.yml} levels applied, or cleared for the control, the
     * sentinel employee is found by last name exactly as without any override, and exactly the
     * {@code expectedWriters} loggers write a sentinel: none with the levels applied.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("levelSetups")
    void rootTraceLogCarriesSentinelsOnlyThroughLoggersLeftBelowInfo(boolean levelsApplied,
            Set<String> expectedWriters) throws IOException {
        jdbcTemplate.update("insert into employees (no, dob, first_name, last_name, gender, hire_date)"
                + " values (?, '1980-01-01', ?, ?, 'F', '2000-01-01')", SENTINEL_EMPLOYEE_NO, FIRST_NAME, LAST_NAME);
        captureAtRootTrace(levelsApplied);

        HttpTestClient.Response response =
                HttpTestClient.localhost(port).send("GET", "/?lastname=" + LAST_NAME, Map.of(), new byte[0]);
        List<ILoggingEvent> events = eventsOnceRequestEnded();

        assertThat(response.statusLine()).as(response.toString()).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.header("Content-Type")).startsWith("application/json");
        assertThat(response.bodyAsString()).isEqualTo("[{\"first_name\":\"" + FIRST_NAME + "\"}]");
        assertThat(sentinelWriters(events)).as("loggers that wrote a sentinel")
                .containsExactlyInAnyOrderElementsOf(expectedWriters);
    }

    static Stream<Arguments> parseFailureLevelSetups() {
        return Stream.of(
                arguments(named("application.yml levels", true), Set.of()),
                arguments(named("no levels (control)", false), Set.of(REQUEST_PARSER)));
    }

    /**
     * With the root at TRACE and the {@code application.yml} levels applied, or cleared for the control, a
     * {@code lastname} that is not valid percent-encoding fails Undertow's request parsing, which answers
     * {@code 400 Bad Request} with an empty body before any servlet runs, and exactly the {@code expectedWriters}
     * loggers write the sentinel: none with the levels applied. The parse failure's DEBUG line quotes the value in its
     * exception's message alone, which {@link #loggedTexts} reads.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("parseFailureLevelSetups")
    void undecodableLastNameReachesTheLogOnlyThroughLoggersLeftBelowInfo(boolean levelsApplied,
            Set<String> expectedWriters) throws IOException {
        captureAtRootTrace(levelsApplied);

        HttpTestClient.Response response = HttpTestClient.localhost(port)
                .send("GET", "/?lastname=" + UNDECODABLE_LAST_NAME, Map.of(), new byte[0]);
        List<ILoggingEvent> events = eventsOnceConnectionClosed();

        assertThat(response.statusLine()).as(response.toString()).isEqualTo("HTTP/1.1 400 Bad Request");
        assertThat(response.body()).as(response.toString()).isEmpty();
        assertThat(sentinelWriters(events)).as("loggers that wrote a sentinel")
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

    /**
     * Waits until the unpinned request-context filter has closed the request at TRACE, which also shows that the
     * capture receives TRACE events, then returns every event captured so far.
     */
    private List<ILoggingEvent> eventsOnceRequestEnded() {
        await().atMost(REQUEST_END_TIMEOUT).until(() -> capturedEvents().stream()
                .anyMatch(event -> event.getLevel() == Level.TRACE
                        && REQUEST_CONTEXT_FILTER.equals(event.getLoggerName())
                        && event.getFormattedMessage().startsWith(REQUEST_END)));
        return capturedEvents();
    }

    /**
     * Waits until XNIO has invoked the close listener of the connection that the unpinned {@code io.undertow.request}
     * last reported opened at TRACE, which ends the exchange of a request that fails to parse and also shows that the
     * capture receives Undertow's TRACE events, then returns every event captured so far.
     */
    private List<ILoggingEvent> eventsOnceConnectionClosed() {
        await().atMost(REQUEST_END_TIMEOUT).until(() -> {
            List<ILoggingEvent> events = capturedEvents();
            int opened = -1;
            for (int i = 0; i < events.size(); i++) {
                if (isTrace(events.get(i), UNDERTOW_REQUEST, CONNECTION_OPENED)) {
                    opened = i;
                }
            }
            return opened >= 0 && events.subList(opened + 1, events.size()).stream()
                    .anyMatch(event -> isTrace(event, XNIO_LISTENER, CONNECTION_CLOSED));
        });
        return capturedEvents();
    }

    /** Whether {@code event} is a TRACE event of {@code logger} whose message starts with {@code messageStart}. */
    private static boolean isTrace(ILoggingEvent event, String logger, String messageStart) {
        return event.getLevel() == Level.TRACE && logger.equals(event.getLoggerName())
                && event.getFormattedMessage().startsWith(messageStart);
    }

    private List<ILoggingEvent> capturedEvents() {
        synchronized (appender) {
            // ListAppender appends under this lock, and the request thread may still be logging.
            return new ArrayList<>(appender.list);
        }
    }

    /** Names the loggers outside the application whose events carry a sentinel. */
    private static Set<String> sentinelWriters(List<ILoggingEvent> events) {
        return events.stream()
                .filter(event -> !event.getLoggerName().startsWith(APPLICATION_LOGGERS))
                .filter(event -> loggedTexts(event).stream()
                        .anyMatch(text -> SENTINEL_RENDERINGS.stream().anyMatch(text::contains)))
                .map(ILoggingEvent::getLoggerName)
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

    /** {@code text}'s UTF-8 bytes as {@code ObjectUtils.nullSafeToString} lists them: decimal, comma and space. */
    private static String decimalBytes(String text) {
        StringJoiner joined = new StringJoiner(", ");
        for (byte b : text.getBytes(UTF_8)) {
            joined.add(Byte.toString(b));
        }
        return joined.toString();
    }
}
