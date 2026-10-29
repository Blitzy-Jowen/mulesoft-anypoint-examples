/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_expression_language_basics.config;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.io.TempDir;
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
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.read.ListAppender;

import com.mulesoft.examples.mule_expression_language_basics.support.HttpTestClient;

/**
 * Tests the {@code logging.level} caps of {@code application.yml}, which keep the callers' names, ages and posted
 * bodies out of the log when the root level is overridden to DEBUG or TRACE. {@code greeting.xml} declares no logger,
 * so the original logged none of them at any level. Below INFO, the {@code DispatcherServlet} writes the request line
 * with its {@code username} and {@code age} query string; when a flow fails, {@code HandlerMethod} writes the
 * exception handler's arguments, {@code ExceptionHandlerExceptionResolver} the resolved exception and
 * {@code HttpEntityMethodProcessor} the SC-1 500 body, each quoting the failure's message, which carries a
 * non-numeric age or the text a parser quotes from a malformed body; for a query that does not percent-decode,
 * Undertow's {@code io.undertow.request.io} writes the parse failure, whose exception quotes the value.
 *
 * <p>Two layers:
 * <ul>
 *   <li>Each cap bound from the classpath {@code application.yml} exactly as Spring Boot binds
 *       {@code logging.level}.</li>
 *   <li>The log itself: the whole application runs on a random port with {@code greeting.output-dir} under a JUnit
 *       temporary directory. The Logback root is set to TRACE and the bound levels are applied through Spring Boot's
 *       own {@link LoggingSystem}, as the application does at start-up with {@code logging.level.root=TRACE}; then
 *       one request carrying the markers is sent through ST-1 {@link HttpTestClient}: the query-driven flows
 *       {@code /greet1} to {@code /greet4} with a marker username and age, {@code /greet3} with a non-numeric marker
 *       age that fails the flow, and {@code /greet5} and {@code /greet6} with well-formed and malformed bodies. The
 *       reply's status line, {@code Content-Type} and body bytes, and the file the flow writes, are exactly those
 *       documented without any override. A Logback {@link ListAppender} on the root names every logger outside the
 *       application's own packages that wrote a marker in its message, its arguments or its throwable chain: none.
 *       The control setup clears the caps, so each capped category inherits the root's TRACE, and proves that the
 *       oracle sees the leak: exactly the capped loggers that take part in the request write a marker. A last
 *       request sends the username with an undecodable escape, raw, so that Undertow's request parsing rejects it
 *       before any servlet runs; there the control shows {@code io.undertow.request.io} alone writing it.</li>
 * </ul>
 *
 * <p>The markers are dummy values that identify no one. While the root is at TRACE its own appenders are detached,
 * so that output stays out of the build log. After each test the appender is detached, the root's appenders are
 * attached again, every logger level the test changed is restored and the output directory is removed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SensitiveLogLevelsTest {

    private static final String DISPATCHER_SERVLET = "org.springframework.web.servlet.DispatcherServlet";

    private static final String HANDLER_METHOD = "org.springframework.web.method.HandlerMethod";

    private static final String EXCEPTION_RESOLVER =
            "org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver";

    private static final String BODY_WRITER =
            "org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor";

    /** Undertow's request I/O logger; its DEBUG line for a request that fails to parse carries the exception. */
    private static final String REQUEST_PARSER = "io.undertow.request.io";

    /** The loggers that write the failure's message when a flow fails with SC-1's 500. */
    private static final Set<String> FAILURE_WRITERS = Set.of(HANDLER_METHOD, EXCEPTION_RESOLVER, BODY_WRITER);

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

    /** The marker username. */
    private static final String NAME = "Sentinelname";

    /** The marker age, a plain number over 18 for MEL and, compared as text with {@code "18"}, for DataWeave. */
    private static final String AGE = "4207193";

    /** The marker age MEL cannot compare with 18, which fails {@code /greet3}. */
    private static final String NON_NUMERIC_AGE = "Sentinelage";

    /** {@link #NAME} followed by {@code %ZZ}, which is not valid percent-encoding, so the query cannot decode. */
    private static final String UNDECODABLE_NAME = NAME + "%ZZ";

    /** Every value that must stay out of the framework loggers' output. */
    private static final List<String> MARKERS = List.of(NAME, AGE, NON_NUMERIC_AGE);

    /** {@link #MARKERS} and the decimal renderings Spring logs a {@code byte[]} with. */
    private static final List<String> MARKER_RENDERINGS = Stream.concat(MARKERS.stream(),
            MARKERS.stream().map(SensitiveLogLevelsTest::decimalBytes)).toList();

    private static final String OK = "HTTP/1.1 200 OK";

    private static final String SERVER_ERROR = "HTTP/1.1 500 Internal Server Error";

    private static final String HELLO = "Hello " + NAME;

    /** The line {@code /greet3}, {@code /greet5} and {@code /greet6} write for the marker username and age. */
    private static final String LINE = NAME + ", " + AGE + ", true";

    private static final String QUERY = "?username=" + NAME + "&age=" + AGE;

    private static final String XML = "<user><username>" + NAME + "</username><age>" + AGE + "</age></user>";

    private static final String JSON = "{\"username\": \"" + NAME + "\", \"age\": " + AGE + "}";

    /** {@link #XML} without its end tag: the parser's message names no character data. */
    private static final String TRUNCATED_XML = "<user><username>" + NAME + "</username><age>" + AGE + "</age>";

    /** {@link #XML} with the username as an undeclared entity reference, which the parser's message names. */
    private static final String UNDECLARED_ENTITY_XML =
            "<user><username>&" + NAME + ";</username><age>" + AGE + "</age></user>";

    /** {@link #JSON} without its closing brace: Jackson's message quotes the document. */
    private static final String TRUNCATED_JSON = "{\"username\": \"" + NAME + "\", \"age\": " + AGE;

    private static final String DIR_NAME = "Path_of_your_choice";

    /** Longest wait for the exchange's last log line. */
    private static final Duration EXCHANGE_END_TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    static Path tempDir;

    @LocalServerPort
    private int port;

    private final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

    private final LoggingSystem loggingSystem = LoggingSystem.get(getClass().getClassLoader());

    /** The Logback level each logger the test changed had before, {@code null} meaning inherited. */
    private final Map<Logger, Level> previousLevels = new LinkedHashMap<>();

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    /** The root logger's own appenders, detached while the root is at TRACE. */
    private final List<Appender<ILoggingEvent>> detachedAppenders = new ArrayList<>();

    /**
     * Binds the flows' output directory to {@link #outputDir()}, outranking the surefire system property. The
     * supplier is read when the application context refreshes, after JUnit has injected the static {@link #tempDir}.
     */
    @DynamicPropertySource
    static void registerOutputDir(DynamicPropertyRegistry registry) {
        registry.add("greeting.output-dir", () -> outputDir().toString());
    }

    @BeforeEach
    void emptyOutputDir() throws IOException {
        FileSystemUtils.deleteRecursively(outputDir());
    }

    @AfterEach
    void restoreLoggingAndOutput() throws IOException {
        Logger root = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(appender);
        appender.stop();
        detachedAppenders.forEach(root::addAppender);
        previousLevels.forEach(Logger::setLevel);
        FileSystemUtils.deleteRecursively(outputDir());
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
            HANDLER_METHOD + ", INFO",
            EXCEPTION_RESOLVER + ", INFO",
            BODY_WRITER + ", INFO",
            REQUEST_PARSER + ", INFO"
    })
    void applicationYmlCapsSensitiveLogger(String logger, LogLevel level) throws IOException {
        assertThat(applicationYmlLevels()).as("logging.level in application.yml").containsEntry(logger, level);
    }

    /**
     * One request a flow answers, and what the listener answers it with whatever the logging levels.
     *
     * @param method         the request method
     * @param target         the request target, query included, sent byte for byte
     * @param body           the UTF-8 body, sent as {@code text/plain} like the original test's payloads, or
     *                       {@code null} for none
     * @param statusLine     the expected status line
     * @param contentType    the expected {@code Content-Type}, or {@code null} for none
     * @param reply          the expected body
     * @param file           the expected content of the single file the call writes, or {@code null} when it writes
     *                       none
     * @param controlWriters the loggers that write a marker of this request when the caps are cleared
     */
    private record Exchange(String method, String target, String body, String statusLine, String contentType,
            String reply, String file, Set<String> controlWriters) {
    }

    private static Stream<Named<Exchange>> exchanges() {
        return Stream.of(
                named("GET /greet1", new Exchange("GET", "/greet1" + QUERY, null, OK, null, HELLO, null,
                        Set.of(DISPATCHER_SERVLET))),
                named("GET /greet2", new Exchange("GET", "/greet2" + QUERY, null, OK, null, HELLO, null,
                        Set.of(DISPATCHER_SERVLET))),
                named("GET /greet3", new Exchange("GET", "/greet3" + QUERY, null, OK, null, HELLO, LINE,
                        Set.of(DISPATCHER_SERVLET))),
                named("GET /greet4", new Exchange("GET", "/greet4" + QUERY, null, OK, null, HELLO,
                        NAME + "," + AGE + ",true\n", Set.of(DISPATCHER_SERVLET))),
                named("GET /greet3 non-numeric age", new Exchange("GET",
                        "/greet3?username=" + NAME + "&age=" + NON_NUMERIC_AGE, null, SERVER_ERROR,
                        MediaType.APPLICATION_JSON_VALUE,
                        "{ \"message\": \"uncomparable values <<" + NON_NUMERIC_AGE + ">> and <<18>>\" }", null,
                        union(Set.of(DISPATCHER_SERVLET), FAILURE_WRITERS))),
                named("POST /greet5 XML", new Exchange("POST", "/greet5", XML, OK, null, HELLO, LINE, Set.of())),
                named("POST /greet6 JSON", new Exchange("POST", "/greet6", JSON, OK, null, HELLO, LINE, Set.of())),
                named("POST /greet5 truncated XML", new Exchange("POST", "/greet5", TRUNCATED_XML, SERVER_ERROR,
                        MediaType.APPLICATION_JSON_VALUE,
                        "{ \"message\": \"XML document structures must start and end within the same entity.\" }",
                        null, Set.of())),
                named("POST /greet5 XML with an undeclared entity", new Exchange("POST", "/greet5",
                        UNDECLARED_ENTITY_XML, SERVER_ERROR, MediaType.APPLICATION_JSON_VALUE,
                        "{ \"message\": \"The entity \\\"" + NAME + "\\\" was referenced, but not declared.\" }",
                        null, FAILURE_WRITERS)),
                named("POST /greet6 truncated JSON", new Exchange("POST", "/greet6", TRUNCATED_JSON, SERVER_ERROR,
                        MediaType.APPLICATION_JSON_VALUE,
                        "{ \"message\": \"Unexpected end-of-input: expected close marker for Object (start marker at"
                                + " [Source: (byte[])\\\"" + TRUNCATED_JSON.replace("\"", "\\\"")
                                + "\\\"; line: 1, column: 1])\\n at [Source: (byte[])\\\""
                                + TRUNCATED_JSON.replace("\"", "\\\"") + "\\\"; line: 1, column: "
                                + (TRUNCATED_JSON.length() + 1) + "]\" }",
                        null, FAILURE_WRITERS)));
    }

    static Stream<Arguments> exchangesAndLevelSetups() {
        return exchanges().flatMap(exchange -> Stream.of(
                arguments(exchange, named("application.yml levels", true), Set.of()),
                arguments(exchange, named("no levels (control)", false), exchange.getPayload().controlWriters())));
    }

    /**
     * With the root at TRACE and the {@code application.yml} levels applied, or cleared for the control, the flow
     * answers {@code exchange} exactly as without any override, and exactly the {@code expectedWriters} loggers write
     * a marker: none with the levels applied.
     */
    @ParameterizedTest(name = "{0} with {1}")
    @MethodSource("exchangesAndLevelSetups")
    void rootTraceLogCarriesMarkersOnlyThroughLoggersLeftBelowInfo(Exchange exchange, boolean levelsApplied,
            Set<String> expectedWriters) throws IOException {
        captureAtRootTrace(levelsApplied);

        HttpTestClient.Response response = client().send(exchange.method(), exchange.target(),
                exchange.body() == null ? Map.of() : Map.of("Content-Type", MediaType.TEXT_PLAIN_VALUE),
                exchange.body() == null ? null : exchange.body().getBytes(UTF_8));
        List<ILoggingEvent> events = eventsOnceExchangeEnded(true);

        assertThat(response.statusLine()).as(response.toString()).isEqualTo(exchange.statusLine());
        assertThat(response.header("Content-Type")).as(response.toString()).isEqualTo(exchange.contentType());
        assertThat(response.body()).as(response.toString()).isEqualTo(exchange.reply().getBytes(UTF_8));
        assertThat(outputFiles()).as("files written by " + response)
                .isEqualTo(exchange.file() == null ? List.of() : List.of(exchange.file()));
        assertThat(markerWriters(events)).as(() -> "loggers that wrote a marker: " + markerReport(events))
                .containsExactlyInAnyOrderElementsOf(expectedWriters);
    }

    static Stream<Arguments> parseFailureLevelSetups() {
        return Stream.of(
                arguments(named("application.yml levels", true), Set.of()),
                arguments(named("no levels (control)", false), Set.of(REQUEST_PARSER)));
    }

    /**
     * With the root at TRACE and the {@code application.yml} levels applied, or cleared for the control, a
     * {@code username} that is not valid percent-encoding fails Undertow's request parsing, which answers
     * {@code 400 Bad Request} with an empty body before any servlet runs, so no flow runs and no file is written,
     * and exactly the {@code expectedWriters} loggers write the marker: none with the levels applied. The parse
     * failure's DEBUG line quotes the value in its exception's message alone, which {@link #loggedTexts} reads.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("parseFailureLevelSetups")
    void undecodableUsernameReachesTheLogOnlyThroughLoggersLeftBelowInfo(boolean levelsApplied,
            Set<String> expectedWriters) throws IOException {
        captureAtRootTrace(levelsApplied);

        HttpTestClient.Response response = client().send("GET", "/greet1?username=" + UNDECODABLE_NAME, Map.of(),
                null);
        List<ILoggingEvent> events = eventsOnceExchangeEnded(false);

        assertThat(response.statusLine()).as(response.toString()).isEqualTo("HTTP/1.1 400 Bad Request");
        assertThat(response.body()).as(response.toString()).isEmpty();
        assertThat(outputFiles()).as("files written by " + response).isEmpty();
        assertThat(markerWriters(events)).as(() -> "loggers that wrote a marker: " + markerReport(events))
                .containsExactlyInAnyOrderElementsOf(expectedWriters);
    }

    private HttpTestClient client() {
        return new HttpTestClient("127.0.0.1", port);
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
     * Waits until XNIO has invoked the close listener of the connection that the unpinned {@code io.undertow.request}
     * last reported opened at TRACE, which ends the exchange, and, for a request that reached the servlet, until the
     * unpinned request-context filter has closed the request at TRACE, after the handler and the error handling
     * returned. Both also show that the capture receives TRACE events. Returns every event captured so far.
     */
    private List<ILoggingEvent> eventsOnceExchangeEnded(boolean dispatched) {
        await().atMost(EXCHANGE_END_TIMEOUT).until(() -> {
            List<ILoggingEvent> events = capturedEvents();
            int opened = -1;
            for (int i = 0; i < events.size(); i++) {
                if (isTrace(events.get(i), UNDERTOW_REQUEST, CONNECTION_OPENED)) {
                    opened = i;
                }
            }
            return opened >= 0
                    && events.subList(opened + 1, events.size()).stream()
                            .anyMatch(event -> isTrace(event, XNIO_LISTENER, CONNECTION_CLOSED))
                    && (!dispatched || events.stream()
                            .anyMatch(event -> isTrace(event, REQUEST_CONTEXT_FILTER, REQUEST_END)));
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
            // ListAppender appends under this lock, and the request and I/O threads may still be logging.
            return new ArrayList<>(appender.list);
        }
    }

    /** Names the loggers outside the application whose events carry a marker. */
    private static Set<String> markerWriters(List<ILoggingEvent> events) {
        return events.stream()
                .filter(SensitiveLogLevelsTest::carriesMarker)
                .map(ILoggingEvent::getLoggerName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** Each event {@link #markerWriters} counts, as logger, level and the first text carrying a marker. */
    private static String markerReport(List<ILoggingEvent> events) {
        return events.stream()
                .filter(SensitiveLogLevelsTest::carriesMarker)
                .map(event -> event.getLoggerName() + " " + event.getLevel() + ": " + loggedTexts(event).stream()
                        .filter(SensitiveLogLevelsTest::containsMarker).findFirst().orElse(""))
                .collect(Collectors.joining(System.lineSeparator(), System.lineSeparator(), ""));
    }

    private static boolean carriesMarker(ILoggingEvent event) {
        return !event.getLoggerName().startsWith(APPLICATION_LOGGERS)
                && loggedTexts(event).stream().anyMatch(SensitiveLogLevelsTest::containsMarker);
    }

    private static boolean containsMarker(String text) {
        return MARKER_RENDERINGS.stream().anyMatch(text::contains);
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

    /** The UTF-8 content of every entry of the output directory, none when it does not exist. */
    private static List<String> outputFiles() throws IOException {
        Path dir = outputDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> listing = Files.list(dir)) {
            List<String> contents = new ArrayList<>();
            for (Path entry : listing.toList()) {
                contents.add(Files.readString(entry, UTF_8));
            }
            return contents;
        }
    }

    private static Path outputDir() {
        return tempDir.resolve(DIR_NAME);
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        return Stream.concat(first.stream(), second.stream()).collect(Collectors.toUnmodifiableSet());
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
