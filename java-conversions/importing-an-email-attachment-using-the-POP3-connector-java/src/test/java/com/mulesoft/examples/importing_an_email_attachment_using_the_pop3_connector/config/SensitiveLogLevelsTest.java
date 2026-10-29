/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.config;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
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
import com.icegreen.greenmail.util.ServerSetup;
import com.icegreen.greenmail.util.ServerSetupTest;

import com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.service.AttachmentImportService;
import com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.trigger.Pop3MailPoller;

/**
 * Tests the {@code logging.level} cap of {@code application.yml}, which keeps the polled mail out of the log when the
 * root level is overridden to DEBUG or TRACE. The original {@code pop-to-xml.xml} logs only the orders XML it builds
 * from the attachment ({@code Received: <xml>}). Below INFO, Angus Mail's POP3 protocol trace writes the whole POP3
 * dialogue: every {@code RETR} reply, with the message's headers (Return-Path, From, To, Subject) and the attachment's
 * content. Its authentication exchange is suppressed in that trace.
 *
 * <p>Two layers:
 * <ul>
 *   <li>The caps bound from the classpath {@code application.yml} exactly as Spring Boot binds
 *       {@code logging.level}.</li>
 *   <li>The log itself: the application runs against an in-process GreenMail server whose mailbox, its password, the
 *       mail's sender and subject and an order in its CSV attachment are sentinel values; the mail is delivered over
 *       GreenMail's SMTP before the capture starts. The Logback root is set to TRACE and the bound levels are applied
 *       through Spring Boot's own {@link LoggingSystem}, as the application does at start-up with
 *       {@code logging.level.root=TRACE}; Spring Boot holds the {@code java.util.logging} loggers of Angus Mail at the
 *       same levels. Then {@link Pop3MailPoller} polls once. The original logger writes its {@code Received:} line,
 *       the attachment's order included, while a Logback {@link ListAppender} on the root names every logger that
 *       wrote a sentinel, outside the application's own packages and the GreenMail stand-in: none. The control setup
 *       clears the caps, so their categories inherit the root's TRACE, and proves that the oracle sees the leak:
 *       exactly Angus Mail's POP3 protocol logger writes the sentinels.</li>
 * </ul>
 *
 * <p>The sentinels are dummy values: none is a credential and none identifies anyone. While the root is at TRACE its
 * own appenders are detached, so that output stays out of the build log. After each test the appender is detached,
 * the root's appenders are attached again and every logger level the test changed is restored. The surefire
 * {@code pop3.check-frequency} and the {@code test} profile hold the scheduled poll back, so only the test's own poll
 * reaches the mailbox.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SensitiveLogLevelsTest {

    private static final String ANGUS_MAIL = "org.eclipse.angus.mail";

    /** The application's own loggers, outside what the cap governs. */
    private static final String APPLICATION_LOGGERS = "com.mulesoft.examples.";

    /** The in-process mail server, which stands in for the remote one and is no part of the application. */
    private static final String STAND_IN_LOGGERS = "com.icegreen.greenmail.";

    private static final String HOST = "127.0.0.1";

    /** Dummy {@code pop3.user}, the polled mailbox; GreenMail's login is the address itself. */
    private static final String MAILBOX = "sentinel.mailbox@localhost";

    /** Dummy {@code pop3.password} of that mailbox. */
    private static final String MAILBOX_PASSWORD = "sentinel_mailbox_password";

    private static final String SENDER = "sentinel.sender@localhost";

    private static final String SUBJECT = "Sentinel subject line";

    /** The name of the attachment's one order, which the original's {@code Received:} line carries. */
    private static final String ORDER_NAME = "Sentinelorder";

    private static final String ATTACHMENT_CSV = "orderId,name,units,pricePerUnit\n7," + ORDER_NAME + ",1.0,3";

    /** Every value that must stay out of the framework loggers' output. */
    private static final List<String> SENTINELS = List.of(MAILBOX, MAILBOX_PASSWORD, SENDER, SUBJECT, ORDER_NAME);

    private static final long DELIVERY_WAIT_MS = 5000;

    private static final int MAIL_TIMEOUT_MS = 10_000;

    /** GreenMail's 2 s default start-up wait is too short for the POP3S TLS context on shared, loaded build hosts. */
    private static final long SERVER_STARTUP_TIMEOUT_MS = 30_000;

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(smtpAndPop3s())
            .withConfiguration(GreenMailConfiguration.aConfig().withUser(MAILBOX, MAILBOX, MAILBOX_PASSWORD))
            .withPerMethodLifecycle(false);

    /** Replaces the original's {@code ./src/test/resources/output}, the {@code file:outbound-endpoint} path. */
    @TempDir
    static Path outputDir;

    @Autowired
    private Pop3MailPoller poller;

    private final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

    private final LoggingSystem loggingSystem = LoggingSystem.get(getClass().getClassLoader());

    /** The Logback level each logger the test changed had before, {@code null} meaning inherited. */
    private final Map<Logger, Level> previousLevels = new LinkedHashMap<>();

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    /** The root logger's own appenders, detached while the root is at TRACE. */
    private final List<Appender<ILoggingEvent>> detachedAppenders = new ArrayList<>();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("pop3.host", () -> HOST);
        registry.add("pop3.port", () -> GREEN_MAIL.getPop3s().getPort());
        registry.add("pop3.user", () -> MAILBOX);
        registry.add("pop3.password", () -> MAILBOX_PASSWORD);
        registry.add("orders.output-dir", () -> outputDir.toString());
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
     * The application's own category stays at INFO, the level of the original logger.
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            ANGUS_MAIL + ", INFO",
            "com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector, INFO"})
    void applicationYmlCapsSensitiveLogger(String logger, LogLevel level) throws IOException {
        assertThat(applicationYmlLevels()).as("logging.level in application.yml").containsEntry(logger, level);
    }

    static Stream<Arguments> levelSetups() {
        return Stream.of(
                arguments(named("application.yml levels", true), Set.of()),
                arguments(named("no levels (control)", false), Set.of(ANGUS_MAIL + ".pop3.protocol")));
    }

    /**
     * With the root at TRACE and the {@code application.yml} levels applied, or cleared for the control, one poll
     * imports the mail's attachment exactly as without any override, the original logger writes its
     * {@code Received:} line, and exactly the {@code expectedWriters} loggers write a sentinel: none with the levels
     * applied.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("levelSetups")
    void rootTraceLogCarriesSentinelsOnlyThroughLoggersLeftBelowInfo(boolean levelsApplied,
            Set<String> expectedWriters) throws Exception {
        GREEN_MAIL.purgeEmailFromAllMailboxes();
        Files.deleteIfExists(outputDir.resolve("orders.xml"));
        sendMail();
        captureAtRootTrace(levelsApplied);

        int processed = poller.pollOnce();
        List<ILoggingEvent> events = capturedEvents();

        assertThat(processed).as("messages imported").isEqualTo(1);
        String xml = Files.readString(outputDir.resolve("orders.xml"), UTF_8);
        assertThat(xml).contains("<name>" + ORDER_NAME + "</name>");
        assertThat(events).as("the original logger's Received: line")
                .anyMatch(event -> event.getLevel() == Level.INFO
                        && AttachmentImportService.class.getName().equals(event.getLoggerName())
                        && event.getFormattedMessage().equals("Received: " + xml));
        assertThat(sentinelWriters(events)).as("loggers that wrote a sentinel")
                .containsExactlyInAnyOrderElementsOf(expectedWriters);
    }

    /** Delivers the mail with its CSV attachment over GreenMail's SMTP and waits until the mailbox holds it. */
    private static void sendMail() throws MessagingException {
        Properties properties = new Properties();
        properties.setProperty("mail.smtp.host", HOST);
        properties.setProperty("mail.smtp.port", String.valueOf(GREEN_MAIL.getSmtp().getPort()));
        properties.setProperty("mail.smtp.connectiontimeout", String.valueOf(MAIL_TIMEOUT_MS));
        properties.setProperty("mail.smtp.timeout", String.valueOf(MAIL_TIMEOUT_MS));
        MimeMessage message = new MimeMessage(Session.getInstance(properties));
        message.setFrom(new InternetAddress(SENDER));
        message.addRecipient(Message.RecipientType.TO, new InternetAddress(MAILBOX));
        message.setSubject(SUBJECT);
        MimeBodyPart attachmentPart = new MimeBodyPart();
        // The original test attached its CSV as application/octet-stream.
        attachmentPart.setDataHandler(new DataHandler(
                new ByteArrayDataSource(ATTACHMENT_CSV.getBytes(UTF_8), "application/octet-stream")));
        attachmentPart.setFileName("report");
        MimeMultipart multipart = new MimeMultipart();
        multipart.addBodyPart(attachmentPart);
        message.setContent(multipart);
        Transport.send(message);
        assertThat(GREEN_MAIL.waitForIncomingEmail(DELIVERY_WAIT_MS, 1)).as("GreenMail received the mail").isTrue();
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

    private List<ILoggingEvent> capturedEvents() {
        synchronized (appender) {
            // ListAppender appends under this lock, and GreenMail's threads may still be logging.
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

    /** GreenMail's SMTP and POP3S test setups on dynamic ports, each allowed {@link #SERVER_STARTUP_TIMEOUT_MS}. */
    private static ServerSetup[] smtpAndPop3s() {
        ServerSetup[] setups =
                ServerSetup.dynamicPort(new ServerSetup[] {ServerSetupTest.SMTP, ServerSetupTest.POP3S});
        for (ServerSetup setup : setups) {
            setup.setServerStartupTimeout(SERVER_STARTUP_TIMEOUT_MS);
        }
        return setups;
    }
}
