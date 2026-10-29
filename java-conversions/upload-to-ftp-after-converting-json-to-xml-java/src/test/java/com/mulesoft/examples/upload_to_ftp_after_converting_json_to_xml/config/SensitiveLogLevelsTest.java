/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.config;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.io.InputStream;
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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockftpserver.fake.FakeFtpServer;
import org.mockftpserver.fake.UserAccount;
import org.mockftpserver.fake.filesystem.DirectoryEntry;
import org.mockftpserver.fake.filesystem.FileEntry;
import org.mockftpserver.fake.filesystem.FileSystemEntry;
import org.mockftpserver.fake.filesystem.UnixFakeFileSystem;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.read.ListAppender;

import com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.client.FtpUploadClient;
import com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.support.HttpTestClient;

/**
 * Tests the {@code logging.level} caps of {@code application.yml}, which keep the employees' personal data out of the
 * log when the root level is overridden to DEBUG or TRACE. {@code upload-to-ftp.xml} has no logger, so the original
 * logged neither the posted employees JSON nor the XML it uploaded at any level. Below INFO, the Spring MVC method
 * processors write the request and response bodies, as the decimal values of their bytes, and the handled exception
 * of a malformed payload whose parser message quotes it.
 *
 * <p>Two layers:
 * <ul>
 *   <li>Each cap bound from the classpath {@code application.yml} exactly as Spring Boot binds
 *       {@code logging.level}.</li>
 *   <li>The log itself: the whole application runs on a random port and uploads to an in-process MockFtpServer,
 *       logging in with sentinel FTP credentials. The Logback root is set to TRACE and the bound levels are applied
 *       through Spring Boot's own {@link LoggingSystem}, as the application does at start-up with
 *       {@code logging.level.root=TRACE}; then an employee is uploaded and a malformed payload rejected. A Logback
 *       {@link ListAppender} on the root names every logger that wrote a sentinel, outside the application's own
 *       packages and the MockFtpServer stand-in: none. The control setup clears the caps, so each capped category
 *       inherits the root's TRACE, and proves that the oracle sees the leak: exactly the capped loggers that take
 *       part in the two requests write the sentinels.</li>
 * </ul>
 *
 * <p>The sentinels are dummy values: none is a credential and none identifies anyone. While the root is at TRACE its
 * own appenders are detached, so that output stays out of the build log. After each test the appender is detached,
 * the root's appenders are attached again and every logger level the test changed is restored.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SensitiveLogLevelsTest {

    private static final String MVC_METHOD_PROCESSORS = "org.springframework.web.servlet.mvc.method.annotation.";

    private static final String BODY_READER = MVC_METHOD_PROCESSORS + "RequestResponseBodyMethodProcessor";

    private static final String BODY_WRITER = MVC_METHOD_PROCESSORS + "HttpEntityMethodProcessor";

    private static final String EXCEPTION_RESOLVER = MVC_METHOD_PROCESSORS + "ExceptionHandlerExceptionResolver";

    private static final String HANDLER_METHOD = "org.springframework.web.method.HandlerMethod";

    /** Unpinned; its TRACE line closes every request, after the handler and the error handling returned. */
    private static final String REQUEST_CONTEXT_FILTER =
            "org.springframework.boot.web.servlet.filter.OrderedRequestContextFilter";

    private static final String REQUEST_END = "Cleared thread-bound request context";

    /** The application's own loggers, outside what the caps govern. */
    private static final String APPLICATION_LOGGERS = "com.mulesoft.examples.";

    /** The in-process FTP server, which stands in for the remote one and is no part of the application. */
    private static final String STAND_IN_LOGGERS = "org.mockftpserver.";

    /** Dummy {@code ftp.user}, the stand-in's only account. */
    private static final String FTP_USER = "sentinel_ftp_user";

    /** Dummy {@code ftp.password} of that account. */
    private static final String FTP_PASSWORD = "sentinel_ftp_password";

    /** The original endpoint's {@code path="/Upload/"}, which the client requires to exist. */
    private static final String UPLOAD_DIRECTORY = "/Upload";

    private static final String UPLOADED_FILE = UPLOAD_DIRECTORY + "/" + FtpUploadClient.OUTPUT_PATTERN;

    private static final String NAME = "Sentinelname";

    private static final String LAST_NAME = "Sentinellastname";

    private static final String STREET = "Sentinelstreet";

    private static final String ZIP_CODE = "Sentinelzip";

    /** The unquoted token of the malformed payload, which the parser's message quotes. */
    private static final String MALFORMED_TOKEN = "Sentineltoken";

    private static final String EMPLOYEES_JSON = "{\"employees\": {\"employee\": [{\"name\": \"" + NAME
            + "\",\"lastName\": \"" + LAST_NAME + "\",\"addresses\": {\"address\": [{\"street\": \"" + STREET
            + "\",\"zipCode\": \"" + ZIP_CODE + "\"}]}}]}}";

    private static final String MALFORMED_JSON = "{\"employees\": " + MALFORMED_TOKEN + "}";

    /** Every value that must stay out of the framework loggers' output. */
    private static final List<String> SENTINELS =
            List.of(FTP_USER, FTP_PASSWORD, NAME, LAST_NAME, STREET, ZIP_CODE, MALFORMED_TOKEN);

    /** {@link #SENTINELS} and the decimal renderings Spring logs a {@code byte[]} body with. */
    private static final List<String> SENTINEL_RENDERINGS = Stream.concat(SENTINELS.stream(),
            SENTINELS.stream().map(SensitiveLogLevelsTest::decimalBytes)).toList();

    /** {@link FakeFtpServer#start()} binds its socket on a background thread. */
    private static final Duration FTP_START_TIMEOUT = Duration.ofSeconds(5);

    /** Longest wait for the request threads' last log line. */
    private static final Duration REQUEST_END_TIMEOUT = Duration.ofSeconds(10);

    private static final FakeFtpServer FTP_SERVER = new FakeFtpServer();

    @LocalServerPort
    private int port;

    private final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

    private final LoggingSystem loggingSystem = LoggingSystem.get(getClass().getClassLoader());

    /** The Logback level each logger the test changed had before, {@code null} meaning inherited. */
    private final Map<Logger, Level> previousLevels = new LinkedHashMap<>();

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    /** The root logger's own appenders, detached while the root is at TRACE. */
    private final List<Appender<ILoggingEvent>> detachedAppenders = new ArrayList<>();

    @DynamicPropertySource
    static void ftpProperties(DynamicPropertyRegistry registry) {
        int ftpPort = startFtpServer();
        registry.add("ftp.port", () -> ftpPort);
        registry.add("ftp.user", () -> FTP_USER);
        registry.add("ftp.password", () -> FTP_PASSWORD);
    }

    @AfterAll
    static void stopFtpServer() {
        FTP_SERVER.stop();
    }

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
            BODY_READER + ", INFO",
            BODY_WRITER + ", INFO",
            EXCEPTION_RESOLVER + ", INFO",
            HANDLER_METHOD + ", INFO",
            "org.springframework.web.HttpLogging, INFO"
    })
    void applicationYmlCapsSensitiveLogger(String logger, LogLevel level) throws IOException {
        assertThat(applicationYmlLevels()).as("logging.level in application.yml").containsEntry(logger, level);
    }

    static Stream<Arguments> levelSetups() {
        return Stream.of(
                arguments(named("application.yml levels", true), Set.of()),
                arguments(named("no levels (control)", false),
                        Set.of(BODY_READER, BODY_WRITER, EXCEPTION_RESOLVER, HANDLER_METHOD)));
    }

    /**
     * With the root at TRACE and the {@code application.yml} levels applied, or cleared for the control, an employee
     * is uploaded and a malformed payload rejected exactly as without any override, and exactly the
     * {@code expectedWriters} loggers write a sentinel: none with the levels applied.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("levelSetups")
    void rootTraceLogCarriesSentinelsOnlyThroughLoggersLeftBelowInfo(boolean levelsApplied,
            Set<String> expectedWriters) throws IOException {
        FTP_SERVER.getFileSystem().delete(UPLOADED_FILE);
        captureAtRootTrace(levelsApplied);

        HttpTestClient.Response uploaded = HttpTestClient.localhost(port).post("/", "application/json", EMPLOYEES_JSON);
        HttpTestClient.Response rejected = HttpTestClient.localhost(port).post("/", "application/json", MALFORMED_JSON);
        List<ILoggingEvent> events = eventsOnceRequestsEnded(2);

        assertThat(uploaded.statusLine()).as(uploaded.toString()).isEqualTo("HTTP/1.1 200 OK");
        assertThat(uploaded.bodyAsString()).contains(NAME, LAST_NAME, STREET, ZIP_CODE);
        assertThat(uploadedBytes()).as("uploaded %s", UPLOADED_FILE).isEqualTo(uploaded.body());
        assertThat(rejected.statusLine()).as(rejected.toString()).isEqualTo("HTTP/1.1 500 Internal Server Error");
        assertThat(rejected.bodyAsString()).contains("Unrecognized token '" + MALFORMED_TOKEN + "'");
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
     * Waits until the unpinned request-context filter has closed {@code requests} requests at TRACE, which also shows
     * that the capture receives TRACE events, then returns every event captured so far.
     */
    private List<ILoggingEvent> eventsOnceRequestsEnded(int requests) {
        await().atMost(REQUEST_END_TIMEOUT).until(() -> capturedEvents().stream()
                .filter(event -> event.getLevel() == Level.TRACE
                        && REQUEST_CONTEXT_FILTER.equals(event.getLoggerName())
                        && event.getFormattedMessage().startsWith(REQUEST_END))
                .count() >= requests);
        return capturedEvents();
    }

    private List<ILoggingEvent> capturedEvents() {
        synchronized (appender) {
            // ListAppender appends under this lock, and the request threads may still be logging.
            return new ArrayList<>(appender.list);
        }
    }

    /** Names the loggers outside the application and the stand-in whose events carry a sentinel. */
    private static Set<String> sentinelWriters(List<ILoggingEvent> events) {
        return events.stream()
                .filter(event -> !event.getLoggerName().startsWith(APPLICATION_LOGGERS)
                        && !event.getLoggerName().startsWith(STAND_IN_LOGGERS))
                .filter(event -> loggedTexts(event).stream()
                        .anyMatch(text -> SENTINEL_RENDERINGS.stream().anyMatch(text::contains)))
                .map(ILoggingEvent::getLoggerName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** The bytes the stand-in holds as the uploaded file. */
    private static byte[] uploadedBytes() throws IOException {
        FileSystemEntry entry = FTP_SERVER.getFileSystem().getEntry(UPLOADED_FILE);
        assertThat(entry).as("uploaded file %s", UPLOADED_FILE).isInstanceOf(FileEntry.class);
        try (InputStream in = ((FileEntry) entry).createInputStream()) {
            return in.readAllBytes();
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

    /**
     * Starts the FTP server once, before the application context that needs its port is built, and returns that
     * port.
     */
    private static synchronized int startFtpServer() {
        if (!FTP_SERVER.isStarted()) {
            UnixFakeFileSystem fileSystem = new UnixFakeFileSystem();
            // The root "/" exists already; adding it as well would fail.
            fileSystem.add(new DirectoryEntry(UPLOAD_DIRECTORY));
            FTP_SERVER.setFileSystem(fileSystem);
            FTP_SERVER.addUserAccount(new UserAccount(FTP_USER, FTP_PASSWORD, "/"));
            FTP_SERVER.setServerControlPort(0);
            FTP_SERVER.start();
            await().atMost(FTP_START_TIMEOUT)
                    .pollInterval(Duration.ofMillis(10))
                    .until(() -> FTP_SERVER.isStarted() && FTP_SERVER.getServerControlPort() > 0);
        }
        return FTP_SERVER.getServerControlPort();
    }
}
