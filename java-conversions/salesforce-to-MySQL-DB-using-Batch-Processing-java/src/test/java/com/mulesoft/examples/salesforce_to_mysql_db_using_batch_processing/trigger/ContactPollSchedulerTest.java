/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;

import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.SalesforceToMySQLDBUsingBatchProcessingApplication;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.client.SalesforcePartnerClient;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.client.UpstreamSystem;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.exception.UpstreamUnavailableException;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.model.BatchJobResult;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.service.ContactToDatabaseBatchService;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.service.WatermarkStore;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.support.SfdcObjectBuilder;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Trigger test of {@link ContactPollScheduler}, the replacement of the original flow {@code triggerFlow}'s
 * {@code <poll>} with its 60 s {@code fixed-frequency-scheduler} and {@code timestamp} watermark
 * ({@code salesforce-to-database.xml}, l.60-68). The original has no HTTP listener, so this test stands in for an
 * endpoint test.
 *
 * <p>The original IT stopped the flow's scheduler and drove it with {@code runSchedulersOnce("triggerFlow")}; here
 * {@link ContactPollScheduler#pollOnce()} is that single run. The outer tests call it, and the scheduled
 * {@link ContactPollScheduler#poll()}, on a plain instance over a mocked service. {@link PollOnceAgainstH2} then runs
 * the real poll in the application context, with Salesforce mocked and the {@code contact} table in H2 (MySQL mode),
 * for one happy path and two failure cases.
 */
class ContactPollSchedulerTest {

    private static final String JOB_NAME = "salesforce-to-database-Batch";

    private static final String FAILED_POLL_PREFIX = "Poll of triggerFlow failed: ";

    private ContactToDatabaseBatchService batchService;

    private ContactPollScheduler scheduler;

    private Logger schedulerLogger;

    private boolean previousAdditive;

    private ListAppender<ILoggingEvent> appender;

    // The appender is attached per test rather than once: Spring Boot resets the Logback context whenever an
    // application context starts, which detaches every appender added earlier.
    @BeforeEach
    void setUp() {
        batchService = mock(ContactToDatabaseBatchService.class);
        scheduler = new ContactPollScheduler(batchService);

        schedulerLogger = (Logger) LoggerFactory.getLogger(ContactPollScheduler.class);
        previousAdditive = schedulerLogger.isAdditive();
        // The appender records every line; detaching the console keeps the expected failure stack traces out of the
        // build log.
        schedulerLogger.setAdditive(false);
        appender = new ListAppender<>();
        appender.start();
        schedulerLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        schedulerLogger.detachAppender(appender);
        appender.stop();
        schedulerLogger.setAdditive(previousAdditive);
    }

    /**
     * Failures {@link ContactToDatabaseBatchService#synchronize()} can raise: a checked exception (its
     * {@code throws Exception} admits the original WSC exceptions), the SC-9 unavailable classification of a
     * Salesforce timeout, and an unchecked failure.
     */
    static Stream<Named<Exception>> pollFailures() {
        return Stream.of(
                Named.of("checked exception", new IOException("boom")),
                Named.of("upstream unavailable", new UpstreamUnavailableException(UpstreamSystem.SALESFORCE,
                        "Read timed out", new SocketTimeoutException("Read timed out"))),
                Named.of("runtime exception", new RuntimeException("unexpected failure")));
    }

    @Test
    void pollOnceReturnsTheResultOfSynchronizeUnchanged() throws Exception {
        BatchJobResult result = new BatchJobResult(JOB_NAME, UUID.randomUUID().toString(),
                BatchJobResult.Status.SUCCESSFUL, 1, 1, 1, 0, 0);
        when(batchService.synchronize()).thenReturn(result);

        assertThat(scheduler.pollOnce()).isSameAs(result);

        verify(batchService, times(1)).synchronize();
        verifyNoMoreInteractions(batchService);
        assertThat(appender.list).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("pollFailures")
    void pollOncePropagatesSynchronizeFailureUnchanged(Exception failure) throws Exception {
        when(batchService.synchronize()).thenThrow(failure);

        // Neither wrapped nor swallowed: the on-demand run hands its caller the exception instance itself.
        assertThatThrownBy(scheduler::pollOnce).isSameAs(failure);

        verify(batchService, times(1)).synchronize();
        verifyNoMoreInteractions(batchService);
        // Its caller receives the failure, so pollOnce does not log it as a failed poll.
        assertThat(appender.list).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("pollFailures")
    void pollSwallowsAndLogsTheFailure(Exception failure) throws Exception {
        BatchJobResult nextResult = new BatchJobResult(JOB_NAME, UUID.randomUUID().toString(),
                BatchJobResult.Status.SUCCESSFUL, 0, 0, 0, 0, 0);
        when(batchService.synchronize()).thenThrow(failure).thenReturn(nextResult);

        // A poll-triggered flow has no caller to answer (AAP 0.4.8): the failure is logged and not rethrown.
        assertThatCode(scheduler::poll).doesNotThrowAnyException();

        List<ILoggingEvent> errors = errorEvents();
        assertThat(errors).hasSize(1);
        ILoggingEvent event = errors.get(0);
        assertThat(event.getFormattedMessage()).isEqualTo(FAILED_POLL_PREFIX + failure.getMessage());
        assertThat(event.getThrowableProxy()).isNotNull();
        assertThat(event.getThrowableProxy().getClassName()).isEqualTo(failure.getClass().getName());
        assertThat(event.getThrowableProxy().getMessage()).isEqualTo(failure.getMessage());

        // The failed poll leaves the trigger usable: the next poll runs the job again and logs nothing.
        assertThatCode(scheduler::poll).doesNotThrowAnyException();
        verify(batchService, times(2)).synchronize();
        verifyNoMoreInteractions(batchService);
        assertThat(appender.list).hasSize(1);
    }

    @Test
    void pollIsScheduledAtTheOriginalSixtySecondFrequency() throws Exception {
        Scheduled scheduled = ContactPollScheduler.class.getMethod("poll").getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        // A fixed rate, as the original fixed-frequency-scheduler measured its period between poll starts.
        assertThat(scheduled.fixedRateString()).isEqualTo("${poll.frequency:60000}");
        assertThat(scheduled.initialDelayString()).isEqualTo("${poll.initial-delay:0}");
        assertThat(scheduled.fixedDelay()).isEqualTo(-1L);
        assertThat(scheduled.fixedDelayString()).isEmpty();
        assertThat(scheduled.cron()).isEmpty();

        // Production invocation stays on poll(); pollOnce() is the on-demand single run.
        assertThat(ContactPollScheduler.class.getMethod("pollOnce").getAnnotation(Scheduled.class)).isNull();
        assertThat(Arrays.stream(ContactPollScheduler.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Scheduled.class))
                .map(Method::getName))
                .containsExactly("poll");

        // The packaged configuration keeps the original frequency="60" timeUnit="SECONDS" and polls from start-up.
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties configuration = yaml.getObject();
        assertThat(configuration).isNotNull();
        assertThat(configuration.getProperty("poll.frequency")).isEqualTo("60000");
        assertThat(configuration.getProperty("poll.initial-delay")).isEqualTo("0");
    }

    private List<ILoggingEvent> errorEvents() {
        return appender.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
    }

    /**
     * One poll in the application context: the real trigger, batch service, SC-7 runner, SC-8 watermark store,
     * mapper and repository, with only Salesforce mocked and the {@code contact} table in the profile's H2 database.
     * The scheduled {@code poll()} never fires: the {@code test} profile delays it by an hour, so each case calls
     * {@code pollOnce()} itself.
     */
    @Nested
    @SpringBootTest(classes = SalesforceToMySQLDBUsingBatchProcessingApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.NONE)
    @ActiveProfiles("test")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class PollOnceAgainstH2 {

        /**
         * This class's own watermark directory, so its polls neither read nor move the watermark the surefire-wide
         * {@code target/test-watermark} store holds for other test classes. It is uniquely named by
         * {@link Files#createTempDirectory}, as a JUnit {@code @TempDir} would be, and deleted after the class.
         */
        private static final Path WATERMARK_DIR = createWatermarkDirectory();

        private static final Path WATERMARK_FILE = WATERMARK_DIR.resolve("watermark.properties");

        private static final String WATERMARK_KEY = ContactToDatabaseBatchService.WATERMARK_KEY;

        private static final String COUNT_CONTACTS = "SELECT COUNT(*) FROM contact";

        @MockBean
        SalesforcePartnerClient partnerClient;

        @Autowired
        JdbcTemplate jdbcTemplate;

        @Autowired
        WatermarkStore watermarkStore;

        @Autowired
        ContactPollScheduler contactPollScheduler;

        @Autowired
        Environment environment;

        // Dynamic properties outrank surefire's watermark.store-path system property.
        @DynamicPropertySource
        static void watermarkStorePath(DynamicPropertyRegistry registry) {
            registry.add("watermark.store-path", WATERMARK_FILE::toString);
        }

        @AfterAll
        static void deleteWatermarkDirectory() throws IOException {
            FileSystemUtils.deleteRecursively(WATERMARK_DIR);
        }

        private static Path createWatermarkDirectory() {
            try {
                return Files.createTempDirectory("contact-poll-scheduler-test");
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot create the watermark directory of " + PollOnceAgainstH2.class,
                        e);
            }
        }

        // The cached context keeps one H2 database for every case of this class.
        @BeforeEach
        void emptyContactTable() {
            jdbcTemplate.update("DELETE FROM contact");
        }

        @Test
        void contextKeepsTheWatermarkInItsOwnDirectory() {
            assertThat(environment.getProperty("watermark.store-path")).isEqualTo(WATERMARK_FILE.toString());
            assertThat(watermarkStore.storePath()).isEqualTo(WATERMARK_FILE.toAbsolutePath().normalize());
        }

        @Test
        void pollOnceInsertsPolledContact() throws Exception {
            Map<String, Object> contact = SfdcObjectBuilder.aContact()
                    .with("Email", "john.doe@example.com")
                    .with("FirstName", "John")
                    .with("LastName", "Doe")
                    .with("LastModifiedDate", "2015-10-10T10:10:10.000Z")
                    .build();
            when(partnerClient.query(anyString())).thenReturn(new ArrayList<>(List.of(contact)));

            BatchJobResult result = contactPollScheduler.pollOnce();

            assertThat(result.isSuccessful()).isTrue();
            assertThat(result.jobName()).isEqualTo(JOB_NAME);
            assertThat(result.loadedRecords()).isEqualTo(1);
            assertThat(result.successfulRecords()).isEqualTo(1);
            assertThat(result.failedRecords()).isZero();

            List<Map<String, Object>> rows =
                    jdbcTemplate.queryForList("SELECT first_name, last_name, email FROM contact");
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0))
                    .containsEntry("first_name", "John")
                    .containsEntry("last_name", "Doe")
                    .containsEntry("email", "john.doe@example.com");

            // One query, the original's, and the MAX selector moved the watermark to the polled LastModifiedDate.
            ArgumentCaptor<String> soql = ArgumentCaptor.forClass(String.class);
            verify(partnerClient, times(1)).query(soql.capture());
            verifyNoMoreInteractions(partnerClient);
            assertThat(soql.getValue()).startsWith(ContactToDatabaseBatchService.QUERY_PREFIX);
            assertThat(watermarkStore.current(WATERMARK_KEY)).contains("2015-10-10T10:10:10.000Z");
        }

        @Test
        void pollOnceRecordsContactWithoutEmailAsFailed() throws Exception {
            Map<String, Object> contact = SfdcObjectBuilder.aContact()
                    .with("FirstName", "Jane")
                    .with("LastName", "Roe")
                    .with("LastModifiedDate", "2015-10-11T10:10:10.000Z")
                    .build();
            when(partnerClient.query(anyString())).thenReturn(new ArrayList<>(List.of(contact)));

            // The insert violates email NOT NULL; with the default max-failed-records 0 the job stops on that record.
            BatchJobResult result = contactPollScheduler.pollOnce();

            assertThat(result.isSuccessful()).isFalse();
            assertThat(result.status()).isEqualTo(BatchJobResult.Status.FAILED_PROCESS_RECORDS);
            assertThat(result.loadedRecords()).isEqualTo(1);
            assertThat(result.successfulRecords()).isZero();
            assertThat(result.failedRecords()).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(COUNT_CONTACTS, Integer.class)).isZero();
        }

        @Test
        void pollOnceUpstreamFailureLeavesWatermarkUnchanged() throws Exception {
            String storedWatermark = "2015-01-01T00:00:00.000Z";
            watermarkStore.store(WATERMARK_KEY, storedWatermark);
            Optional<String> before = watermarkStore.current(WATERMARK_KEY);
            UpstreamUnavailableException unavailable = new UpstreamUnavailableException(UpstreamSystem.SALESFORCE,
                    "Salesforce unavailable", new ConnectException("Connection refused"));
            when(partnerClient.query(anyString())).thenThrow(unavailable);

            assertThatThrownBy(contactPollScheduler::pollOnce).isSameAs(unavailable);

            // SC-8: the watermark moves only after a completed poll.
            assertThat(watermarkStore.current(WATERMARK_KEY)).isEqualTo(before).contains(storedWatermark);
            assertThat(jdbcTemplate.queryForObject(COUNT_CONTACTS, Integer.class)).isZero();
            // Exactly one query, built from the stored watermark: a connectivity failure is never retried.
            verify(partnerClient, times(1)).query(anyString());
            verify(partnerClient).query(ContactToDatabaseBatchService.buildQuery(storedWatermark));
            verifyNoMoreInteractions(partnerClient);
        }
    }
}
