/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
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

import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.SalesforceToMySQLDBUsingBatchProcessingApplication;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.client.SalesforcePartnerClient;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.mapper.ContactRowMapper;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.model.BatchJobResult;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.support.SfdcObjectBuilder;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.trigger.ContactPollScheduler;

/**
 * Tests the {@code logging.level} cap of {@code application.yml}, which keeps the contacts' names and e-mail addresses
 * out of the log when the root level is overridden to DEBUG or TRACE. The original {@code salesforce-to-database.xml}
 * logs a contact's e-mail address only on the error branch of step {@code upsertContactInDbStep}
 * ({@code Error with <email> contact}). Below INFO, Spring JDBC writes every bound statement parameter: the e-mail
 * address of each lookup and the names and address of each INSERT and UPDATE.
 *
 * <p>Two layers:
 * <ul>
 *   <li>The cap bound from the classpath {@code application.yml} exactly as Spring Boot binds
 *       {@code logging.level}.</li>
 *   <li>The log itself: the application runs over the {@code test} profile's H2 database with the Salesforce client
 *       mocked. The Logback root is set to TRACE and the bound levels are applied through Spring Boot's own
 *       {@link LoggingSystem}, as the application does at start-up with {@code logging.level.root=TRACE}; then one
 *       poll inserts a new contact and updates an existing one, and the step meets a record that selects its error
 *       branch, which no record reaches through the job and is therefore run on the step directly. The original
 *       loggers write their lines, the error branch's e-mail address included, while a Logback {@link ListAppender}
 *       on the root names every logger outside the application's own packages that wrote a sentinel: none. The
 *       control setup clears the cap, so its category inherits the root's TRACE, and proves that the oracle sees the
 *       leak.</li>
 * </ul>
 *
 * <p>The sentinels are dummy names and addresses in an unresolvable domain that identify no one. While the root is at
 * TRACE its own appenders are detached, so that output stays out of the build log. After each test the sentinel rows
 * are deleted, the appender is detached, the root's appenders are attached again and every logger level the test
 * changed is restored.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = SalesforceToMySQLDBUsingBatchProcessingApplication.class)
@ActiveProfiles("test")
class SensitiveLogLevelsTest {

    private static final String STATEMENT_CREATOR = "org.springframework.jdbc.core.StatementCreatorUtils";

    /** Unpinned; writes each statement at DEBUG without its values, which shows the capture receives DEBUG. */
    private static final String JDBC_TEMPLATE = "org.springframework.jdbc.core.JdbcTemplate";

    /** The application's own loggers, outside what the cap governs. */
    private static final String APPLICATION_LOGGERS = "com.mulesoft.examples.";

    private static final String SERVICE_LOGGER = ContactToDatabaseBatchService.class.getName();

    private static final String NEW_EMAIL = "sentinel.new@example.invalid";

    private static final String NEW_FIRST_NAME = "Sentinelnewfirst";

    private static final String NEW_LAST_NAME = "Sentinelnewlast";

    private static final String EXISTING_EMAIL = "sentinel.existing@example.invalid";

    private static final String EXISTING_FIRST_NAME = "Sentinelexistingfirst";

    private static final String EXISTING_LAST_NAME = "Sentinelexistinglast";

    private static final String ERROR_EMAIL = "sentinel.error@example.invalid";

    private static final String ERROR_FIRST_NAME = "Sentinelerrorfirst";

    private static final String ERROR_LAST_NAME = "Sentinelerrorlast";

    /** Every value that must stay out of the framework loggers' output. */
    private static final List<String> SENTINELS = List.of(NEW_EMAIL, NEW_FIRST_NAME, NEW_LAST_NAME, EXISTING_EMAIL,
            EXISTING_FIRST_NAME, EXISTING_LAST_NAME, ERROR_EMAIL, ERROR_FIRST_NAME, ERROR_LAST_NAME);

    /** Salesforce's UTC form of {@code LastModifiedDate}, which the watermark {@code MAX} selector reads. */
    private static final DateTimeFormatter SALESFORCE_DATE_TIME =
            DateTimeFormatter.ofPattern(ContactToDatabaseBatchService.WATERMARK_PATTERN).withZone(ZoneOffset.UTC);

    /** Holds this class's watermark store file, which the JUnit-managed directory keeps out of shared locations. */
    @TempDir
    static Path storeDir;

    @MockBean
    private SalesforcePartnerClient salesforcePartnerClient;

    @Autowired
    private ContactPollScheduler contactPollScheduler;

    @Autowired
    private ContactToDatabaseBatchService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

    private final LoggingSystem loggingSystem = LoggingSystem.get(getClass().getClassLoader());

    /** The Logback level each logger the test changed had before, {@code null} meaning inherited. */
    private final Map<Logger, Level> previousLevels = new LinkedHashMap<>();

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    /** The root logger's own appenders, detached while the root is at TRACE. */
    private final List<Appender<ILoggingEvent>> detachedAppenders = new ArrayList<>();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("watermark.store-path", () -> storeDir.resolve("watermark.properties").toString());
    }

    @AfterEach
    void restoreLoggingAndData() {
        Logger root = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(appender);
        appender.stop();
        detachedAppenders.forEach(root::addAppender);
        previousLevels.forEach(Logger::setLevel);
        for (String email : List.of(NEW_EMAIL, EXISTING_EMAIL, ERROR_EMAIL)) {
            jdbcTemplate.update("delete from contact where email = ?", email);
        }
    }

    /**
     * {@code application.yml} holds {@code logger} at {@code level}, bound the way Spring Boot applies
     * {@code logging.level}, so a root DEBUG or TRACE override, or {@code --debug}/{@code --trace}, does not reach it.
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(STATEMENT_CREATOR + ", INFO")
    void applicationYmlCapsSensitiveLogger(String logger, LogLevel level) throws IOException {
        assertThat(applicationYmlLevels()).as("logging.level in application.yml").containsEntry(logger, level);
    }

    static Stream<Arguments> levelSetups() {
        return Stream.of(
                arguments(named("application.yml levels", true), Set.of()),
                arguments(named("no levels (control)", false), Set.of(STATEMENT_CREATOR)));
    }

    /**
     * With the root at TRACE and the {@code application.yml} levels applied, or cleared for the control, the poll
     * inserts and updates exactly as without any override, the original loggers write their lines, the error
     * branch's e-mail address included, and exactly the {@code expectedWriters} loggers write a sentinel: none with
     * the levels applied.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("levelSetups")
    void rootTraceLogCarriesSentinelsOnlyThroughLoggersLeftBelowInfo(boolean levelsApplied,
            Set<String> expectedWriters) throws Exception {
        jdbcTemplate.update("insert into contact (first_name, last_name, email) values (?, ?, ?)",
                "Previousfirst", "Previouslast", EXISTING_EMAIL);
        String lastModified = SALESFORCE_DATE_TIME.format(Instant.now());
        when(salesforcePartnerClient.query(anyString())).thenReturn(new ArrayList<>(List.of(
                salesforceContact(NEW_EMAIL, NEW_FIRST_NAME, NEW_LAST_NAME, lastModified),
                salesforceContact(EXISTING_EMAIL, EXISTING_FIRST_NAME, EXISTING_LAST_NAME, lastModified))));
        BatchJobRunner.BatchRecord<Map<String, Object>> errorRecord = new BatchJobRunner.BatchRecord<>(
                new ContactRowMapper().map(salesforceContact(ERROR_EMAIL, ERROR_FIRST_NAME, ERROR_LAST_NAME,
                        lastModified)));
        captureAtRootTrace(levelsApplied);

        BatchJobResult result = contactPollScheduler.pollOnce();
        // Step 1 never ran for this record, so "exists" is unset and step 2 takes its otherwise branch.
        service.upsert(errorRecord);
        List<ILoggingEvent> events = capturedEvents();

        assertThat(result.isSuccessful()).as("%s", result).isTrue();
        assertThat(result.loadedRecords()).as("%s", result).isEqualTo(2);
        assertThat(row(NEW_EMAIL)).containsEntry("first_name", NEW_FIRST_NAME)
                .containsEntry("last_name", NEW_LAST_NAME);
        assertThat(row(EXISTING_EMAIL)).containsEntry("first_name", EXISTING_FIRST_NAME)
                .containsEntry("last_name", EXISTING_LAST_NAME);
        assertThat(jdbcTemplate.queryForObject("select count(*) from contact where email = ?", Integer.class,
                ERROR_EMAIL)).as("rows of the error branch's contact").isZero();
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(salesforcePartnerClient).query(query.capture());
        String watermark = query.getValue().substring(ContactToDatabaseBatchService.QUERY_PREFIX.length());
        assertThat(messagesOf(events, SERVICE_LOGGER, Level.INFO))
                .as("the original loggers' lines: the watermark, the on-complete line and the error branch's line")
                .containsExactly(watermark, ContactToDatabaseBatchService.ON_COMPLETE_MESSAGE,
                        "Error with " + ERROR_EMAIL + " contact");
        assertThat(events).as("the unpinned JdbcTemplate's DEBUG statement lines")
                .anyMatch(event -> event.getLevel() == Level.DEBUG && JDBC_TEMPLATE.equals(event.getLoggerName())
                        && event.getFormattedMessage().startsWith("Executing prepared SQL"));
        assertThat(sentinelWriters(events)).as("loggers that wrote a sentinel")
                .containsExactlyInAnyOrderElementsOf(expectedWriters);
    }

    /** A Contact as the Partner API returns it for the query's fields. */
    private static Map<String, Object> salesforceContact(String email, String firstName, String lastName,
            String lastModified) {
        return SfdcObjectBuilder.aContact()
                .with("type", "Contact")
                .with("Email", email)
                .with("FirstName", firstName)
                .with("LastModifiedDate", lastModified)
                .with("LastName", lastName)
                .build();
    }

    private Map<String, Object> row(String email) {
        return jdbcTemplate.queryForMap("select first_name, last_name from contact where email = ?", email);
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

    private List<ILoggingEvent> capturedEvents() {
        synchronized (appender) {
            // ListAppender appends under this lock.
            return new ArrayList<>(appender.list);
        }
    }

    private static List<String> messagesOf(List<ILoggingEvent> events, String logger, Level level) {
        return events.stream()
                .filter(event -> logger.equals(event.getLoggerName()) && event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** Names the loggers outside the application whose events carry a sentinel. */
    private static Set<String> sentinelWriters(List<ILoggingEvent> events) {
        return events.stream()
                .filter(event -> !event.getLoggerName().startsWith(APPLICATION_LOGGERS))
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
