/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.scatter_gather_flow_control.config;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;
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
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;

import com.mulesoft.examples.scatter_gather_flow_control.client.AggregationReportMailer;
import com.mulesoft.examples.scatter_gather_flow_control.support.HttpTestClient;

/**
 * Tests the {@code logging.level} cap of {@code application.yml}, which keeps the aggregated contacts and the report
 * mail's addresses out of the log when the root level is overridden to DEBUG or TRACE. {@code scatter-gather.xml} has
 * no logger, so the original logged neither the contacts nor the mail at any level. Below INFO, Angus Mail's SMTP
 * transport writes the sender and recipient addresses, and its protocol trace the whole SMTP dialogue with the
 * message's headers and the CSV report of names and e-mail addresses.
 *
 * <p>The {@code /scatterGather} response needs no cap: the controller writes the constant
 * {@link AggregationReportMailer#BODY_TEXT} straight to the servlet response, past the Spring MVC body writers.
 *
 * <p>Two layers:
 * <ul>
 *   <li>The cap bound from the classpath {@code application.yml} exactly as Spring Boot binds
 *       {@code logging.level}.</li>
 *   <li>The log itself: the whole application runs on a random port and mails through an in-process GreenMail SMTP
 *       server, logging in with a sentinel account and addressing sentinel sender and recipient addresses. The Logback
 *       root is set to TRACE and the bound levels are applied through Spring Boot's own {@link LoggingSystem}, as the
 *       application does at start-up with {@code logging.level.root=TRACE}; Spring Boot holds the
 *       {@code java.util.logging} loggers of Angus Mail at the same levels. Then the flow runs once. A Logback
 *       {@link ListAppender} on the root names every logger that wrote a sentinel, outside the application's own
 *       packages and the GreenMail stand-in: none. The control setup clears the cap, so its category inherits the
 *       root's TRACE, and proves that the oracle sees the leak: exactly Angus Mail's SMTP loggers write the
 *       sentinels.</li>
 * </ul>
 *
 * <p>The contacts are the fixed values of the flow's two routes; the other sentinels are dummy values: none is a
 * credential and none identifies anyone. While the root is at TRACE its own appenders are detached, so that output
 * stays out of the build log. After each test the appender is detached, the root's appenders are attached again and
 * every logger level the test changed is restored.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SensitiveLogLevelsTest {

    private static final String ANGUS_MAIL = "org.eclipse.angus.mail";

    /** Unpinned; its TRACE line closes every request, after the handler and the error handling returned. */
    private static final String REQUEST_CONTEXT_FILTER =
            "org.springframework.boot.web.servlet.filter.OrderedRequestContextFilter";

    private static final String REQUEST_END = "Cleared thread-bound request context";

    /** The application's own loggers, outside what the cap governs. */
    private static final String APPLICATION_LOGGERS = "com.mulesoft.examples.";

    /** The in-process SMTP server, which stands in for the remote one and is no part of the application. */
    private static final String STAND_IN_LOGGERS = "com.icegreen.greenmail.";

    /** Dummy {@code smtp.user}, the stand-in's only account. */
    private static final String SMTP_USER = "sentinel.smtp.account@localhost";

    /** Dummy {@code smtp.password} of that account. */
    private static final String SMTP_PASSWORD = "sentinel_smtp_password";

    /** Dummy {@code mail.from}. */
    private static final String MAIL_FROM = "sentinel.sender@localhost";

    /** Dummy {@code mail.to}. */
    private static final String MAIL_TO = "sentinel.recipient@localhost";

    /** The e-mail addresses of the contacts the flow's routes return, which the CSV report lists. */
    private static final List<String> CONTACT_EMAILS = List.of("vlado@email.com", "michal@email.com",
            "peter@email.com");

    /** Every value that must stay out of the framework loggers' output. */
    private static final List<String> SENTINELS = Stream.concat(
            Stream.of(SMTP_USER, SMTP_PASSWORD, MAIL_FROM, MAIL_TO), CONTACT_EMAILS.stream()).toList();

    /** Longest wait for the request thread's last log line and for the mail. */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort())
            .withConfiguration(GreenMailConfiguration.aConfig().withUser(SMTP_USER, SMTP_USER, SMTP_PASSWORD))
            .withPerMethodLifecycle(false);

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
    static void mail(DynamicPropertyRegistry registry) {
        registry.add("smtp.port", () -> greenMail.getSmtp().getPort());
        registry.add("smtp.user", () -> SMTP_USER);
        registry.add("smtp.password", () -> SMTP_PASSWORD);
        registry.add("mail.from", () -> MAIL_FROM);
        registry.add("mail.to", () -> MAIL_TO);
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
    @CsvSource(ANGUS_MAIL + ", INFO")
    void applicationYmlCapsSensitiveLogger(String logger, LogLevel level) throws IOException {
        assertThat(applicationYmlLevels()).as("logging.level in application.yml").containsEntry(logger, level);
    }

    static Stream<Arguments> levelSetups() {
        return Stream.of(
                arguments(named("application.yml levels", true), Set.of()),
                arguments(named("no levels (control)", false),
                        Set.of(ANGUS_MAIL + ".smtp", ANGUS_MAIL + ".smtp.protocol")));
    }

    /**
     * With the root at TRACE and the {@code application.yml} levels applied, or cleared for the control, the flow
     * answers and mails its report exactly as without any override, and exactly the {@code expectedWriters} loggers
     * write a sentinel: none with the levels applied.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("levelSetups")
    void rootTraceLogCarriesSentinelsOnlyThroughLoggersLeftBelowInfo(boolean levelsApplied,
            Set<String> expectedWriters) throws Exception {
        greenMail.purgeEmailFromAllMailboxes();
        captureAtRootTrace(levelsApplied);

        HttpTestClient.Response response =
                HttpTestClient.localhost(port).send("POST", "/scatterGather", Map.of(), null);
        List<ILoggingEvent> events = eventsOnceRequestEnded();

        assertThat(response.statusLine()).as(response.toString()).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.bodyAsString()).isEqualTo(AggregationReportMailer.BODY_TEXT);
        await().atMost(TIMEOUT).until(() -> greenMail.getReceivedMessages().length == 1);
        MimeMessage mail = greenMail.getReceivedMessages()[0];
        assertThat(mail.getFrom()[0].toString()).isEqualTo(MAIL_FROM);
        assertThat(mail.getAllRecipients()[0].toString()).isEqualTo(MAIL_TO);
        assertThat(rawText(mail)).as("the mailed report").contains(CONTACT_EMAILS);
        assertThat(sentinelWriters(events)).as("loggers that wrote a sentinel")
                .containsExactlyInAnyOrderElementsOf(expectedWriters);
    }

    /**
     * Detaches the root's appenders, attaches {@link #appender} instead, sets the root to TRACE and applies the
     * {@code application.yml} levels, or clears them so that their categories inherit TRACE.
     */
    private void captureAtRootTrace(boolean levelsApplied) throws IOException {
        // Angus Mail's java.util.logging records reach Logback as at application start-up.
        assertThat(SLF4JBridgeHandler.isInstalled()).as("SLF4JBridgeHandler installed").isTrue();
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
        await().atMost(TIMEOUT).until(() -> capturedEvents().stream()
                .anyMatch(event -> event.getLevel() == Level.TRACE
                        && REQUEST_CONTEXT_FILTER.equals(event.getLoggerName())
                        && event.getFormattedMessage().startsWith(REQUEST_END)));
        return capturedEvents();
    }

    private List<ILoggingEvent> capturedEvents() {
        synchronized (appender) {
            // ListAppender appends under this lock, and the request and route threads may still be logging.
            return new ArrayList<>(appender.list);
        }
    }

    /** Names the loggers outside the application and the stand-in whose events carry a sentinel. */
    private static Set<String> sentinelWriters(List<ILoggingEvent> events) {
        return events.stream()
                .filter(event -> !event.getLoggerName().startsWith(APPLICATION_LOGGERS)
                        && !event.getLoggerName().startsWith(STAND_IN_LOGGERS))
                .filter(event -> loggedTexts(event).stream()
                        .anyMatch(text -> SENTINELS.stream().anyMatch(text::contains)))
                .map(ILoggingEvent::getLoggerName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** The received message as it went over the wire. */
    private static String rawText(MimeMessage mail) throws IOException, MessagingException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        mail.writeTo(out);
        return out.toString(UTF_8);
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
}
