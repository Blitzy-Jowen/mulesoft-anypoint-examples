/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.config;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

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
import org.springframework.mail.MailAuthenticationException;
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
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;

import com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.service.CsvEmailService;
import com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.trigger.CsvFileTrigger;

/**
 * Tests the {@code logging.level} cap of {@code application.yml}, which keeps the SMTP account and the mail out of the
 * log when the root level is overridden to DEBUG or TRACE. The original {@code csv-to-smtp.xml} logs only the order
 * lines it mails ({@code Processing objects: <payload>}). Below INFO, Angus Mail's SMTP transport writes the SMTP
 * account and the sender and recipient addresses, and its protocol trace the whole SMTP dialogue with the message's
 * headers and the order lines.
 *
 * <p>Two layers:
 * <ul>
 *   <li>The cap bound from the classpath {@code application.yml} exactly as Spring Boot binds
 *       {@code logging.level}.</li>
 *   <li>The log itself: the application runs against an in-process GreenMail SMTP server, logging in with a sentinel
 *       account and mailing to sentinel sender and recipient addresses under a sentinel subject, and an order in the
 *       polled CSV file carries a sentinel name. The Logback root is set to TRACE and the bound levels are applied
 *       through Spring Boot's own {@link LoggingSystem}, as the application does at start-up with
 *       {@code logging.level.root=TRACE}; Spring Boot holds the {@code java.util.logging} loggers of Angus Mail at the
 *       same levels. Then {@link CsvFileTrigger} polls the file once, on the send path, and on the authentication
 *       failure path, where the server rejects the configured password. The original logger writes its
 *       {@code Processing objects:} line, the order included, on both paths, while a Logback {@link ListAppender} on
 *       the root names every logger that wrote a sentinel, outside the application's own packages and the GreenMail
 *       stand-in: none. The control setups clear the cap, so its category inherits the root's TRACE, and prove that
 *       the oracle sees the leak: exactly Angus Mail's SMTP loggers write the sentinels.</li>
 * </ul>
 *
 * <p>The sentinels are dummy values: none is a credential and none identifies anyone. While the root is at TRACE its
 * own appenders are detached, so that output stays out of the build log. After each test the appender is detached,
 * the root's appenders are attached again, every logger level the test changed is restored and the server accepts
 * the configured password again. The polling frequency is raised to a day, so after the empty start-up poll only the
 * test's own polls read the input directory.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SensitiveLogLevelsTest {

    private static final String ANGUS_MAIL = "org.eclipse.angus.mail";

    /** The application's own loggers, outside what the cap governs. */
    private static final String APPLICATION_LOGGERS = "com.mulesoft.examples.";

    /** The in-process SMTP server, which stands in for the remote one and is no part of the application. */
    private static final String STAND_IN_LOGGERS = "com.icegreen.greenmail.";

    /** Dummy {@code smtp.user}, the stand-in's only account; GreenMail's login is the address itself. */
    private static final String SMTP_USER = "sentinel.smtp.account@localhost";

    /** Dummy {@code smtp.password} of that account. */
    private static final String SMTP_PASSWORD = "sentinel_smtp_password";

    /** The password the stand-in holds for the account on the authentication failure path. */
    private static final String SERVER_SIDE_OTHER_PASSWORD = "other_server_side_password";

    /** Dummy {@code mail.from}. */
    private static final String MAIL_FROM = "sentinel.sender@localhost";

    /** Dummy {@code mail.to}. */
    private static final String MAIL_TO = "sentinel.recipient@localhost";

    /** Dummy {@code mail.subject}. */
    private static final String SUBJECT = "Sentinel subject line";

    /** The name of the file's one order, which the original's {@code Processing objects:} line carries. */
    private static final String ORDER_NAME = "Sentinelorder";

    private static final String INPUT_CSV = "orderId,name,units,pricePerUnit\n7," + ORDER_NAME + ",1.0,3";

    /** The order lines {@code OrderLinesMapper} produces for {@link #INPUT_CSV}: the logged payload and mail body. */
    private static final String ORDER_LINES =
            "[{name=" + ORDER_NAME + ", orderId=7, pricePerUnit=3, units=1, totalPrice=3}]";

    private static final String INPUT_FILE_NAME = "input.csv";

    /** Every value that must stay out of the framework loggers' output. */
    private static final List<String> SENTINELS =
            List.of(SMTP_USER, SMTP_PASSWORD, MAIL_FROM, MAIL_TO, SUBJECT, ORDER_NAME);

    private static final long DELIVERY_WAIT_MS = 5000;

    /** One day: after the empty start-up poll, the scheduled poll never runs while this class does. */
    private static final String POLLING_FREQUENCY_MS = "86400000";

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort())
            .withConfiguration(GreenMailConfiguration.aConfig().withUser(SMTP_USER, SMTP_USER, SMTP_PASSWORD))
            .withPerMethodLifecycle(false);

    /** The polled {@code csv-email.input-dir}. */
    @TempDir
    static Path inputDir;

    /** Where each input file is written before it is moved into {@link #inputDir} in one step. */
    @TempDir
    static Path stagingDir;

    @Autowired
    private CsvFileTrigger trigger;

    private final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

    private final LoggingSystem loggingSystem = LoggingSystem.get(getClass().getClassLoader());

    /** The Logback level each logger the test changed had before, {@code null} meaning inherited. */
    private final Map<Logger, Level> previousLevels = new LinkedHashMap<>();

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    /** The root logger's own appenders, detached while the root is at TRACE. */
    private final List<Appender<ILoggingEvent>> detachedAppenders = new ArrayList<>();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("smtp.port", () -> GREEN_MAIL.getSmtp().getPort());
        registry.add("smtp.user", () -> SMTP_USER);
        registry.add("smtp.password", () -> SMTP_PASSWORD);
        registry.add("mail.from", () -> MAIL_FROM);
        registry.add("mail.to", () -> MAIL_TO);
        registry.add("mail.subject", () -> SUBJECT);
        registry.add("csv-email.input-dir", () -> inputDir.toString());
        registry.add("csv-email.polling-frequency", () -> POLLING_FREQUENCY_MS);
    }

    @AfterEach
    void restoreLoggingAndAccount() {
        Logger root = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(appender);
        appender.stop();
        detachedAppenders.forEach(root::addAppender);
        previousLevels.forEach(Logger::setLevel);
        account().setPassword(SMTP_PASSWORD);
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
                arguments(named("send", true), named("application.yml levels", true), Set.of()),
                arguments(named("send", true), named("no levels (control)", false),
                        Set.of(ANGUS_MAIL + ".smtp", ANGUS_MAIL + ".smtp.protocol")),
                arguments(named("authentication failure", false), named("application.yml levels", true), Set.of()),
                arguments(named("authentication failure", false), named("no levels (control)", false),
                        Set.of(ANGUS_MAIL + ".smtp")));
    }

    /**
     * With the root at TRACE and the {@code application.yml} levels applied, or cleared for the control, one poll
     * consumes the file and mails its order lines, or on the authentication failure path is refused by the server,
     * exactly as without any override; the original logger writes its {@code Processing objects:} line on both paths,
     * and exactly the {@code expectedWriters} loggers write a sentinel: none with the levels applied.
     */
    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("levelSetups")
    void rootTraceLogCarriesSentinelsOnlyThroughLoggersLeftBelowInfo(boolean passwordAccepted, boolean levelsApplied,
            Set<String> expectedWriters) throws Exception {
        GREEN_MAIL.purgeEmailFromAllMailboxes();
        account().setPassword(passwordAccepted ? SMTP_PASSWORD : SERVER_SIDE_OTHER_PASSWORD);
        placeInputFile();
        captureAtRootTrace(levelsApplied);

        int polled = trigger.pollOnce();
        List<ILoggingEvent> events = capturedEvents();

        assertThat(polled).as("files handed to the flow").isEqualTo(1);
        assertThat(inputDir.resolve(INPUT_FILE_NAME)).as("the polled file is consumed").doesNotExist();
        assertThat(events).as("the original logger's Processing objects: line")
                .anyMatch(event -> event.getLevel() == Level.INFO
                        && CsvEmailService.class.getName().equals(event.getLoggerName())
                        && event.getFormattedMessage().equals("Processing objects: " + ORDER_LINES));
        if (passwordAccepted) {
            assertThat(GREEN_MAIL.waitForIncomingEmail(DELIVERY_WAIT_MS, 1)).as("GreenMail received the mail").isTrue();
            MimeMessage mail = GREEN_MAIL.getReceivedMessages()[0];
            assertThat(((InternetAddress) mail.getFrom()[0]).getAddress()).isEqualTo(MAIL_FROM);
            assertThat(((InternetAddress) mail.getAllRecipients()[0]).getAddress()).isEqualTo(MAIL_TO);
            assertThat(mail.getSubject()).isEqualTo(SUBJECT);
            assertThat(String.valueOf(mail.getContent()).trim()).isEqualTo(ORDER_LINES);
        } else {
            assertThat(GREEN_MAIL.getReceivedMessages()).as("mails received").isEmpty();
            assertThat(events).as("the poller's ERROR line for the refused login")
                    .anyMatch(event -> event.getLevel() == Level.ERROR
                            && event.getFormattedMessage().contains(MailAuthenticationException.class.getName()));
        }
        assertThat(sentinelWriters(events)).as("loggers that wrote a sentinel")
                .containsExactlyInAnyOrderElementsOf(expectedWriters);
    }

    /** The stand-in's account the application logs in with. */
    private static GreenMailUser account() {
        return GREEN_MAIL.getUserManager().getUser(SMTP_USER);
    }

    /** Writes {@link #INPUT_CSV} beside the input directory and moves it in, so no poll reads a partial file. */
    private static void placeInputFile() throws IOException {
        Path staged = Files.writeString(stagingDir.resolve(INPUT_FILE_NAME), INPUT_CSV, UTF_8);
        Files.move(staged, inputDir.resolve(INPUT_FILE_NAME), StandardCopyOption.ATOMIC_MOVE);
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
}
