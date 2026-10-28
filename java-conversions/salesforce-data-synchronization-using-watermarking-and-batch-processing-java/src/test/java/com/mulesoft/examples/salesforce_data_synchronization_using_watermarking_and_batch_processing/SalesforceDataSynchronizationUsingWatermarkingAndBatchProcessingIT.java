/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.client.SalesforcePartnerClient;
import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.model.BatchJobResult;
import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.service.ContactSyncBatchService;
import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.service.WatermarkStore;
import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.trigger.ContactPollScheduler;
import com.sforce.soap.partner.DeleteResult;
import com.sforce.soap.partner.SaveResult;

/**
 * Tier 2B live-sandbox test, run by failsafe ({@code mvn -B verify}): the original
 * {@code SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingIT} against the real Salesforce org configured
 * in the git-ignored {@code src/test/resources/application-it.yml} (template {@code application-it.yml.example}).
 *
 * <p>The original's pieces map as follows:
 * <ul>
 *   <li>{@code stopFlowSchedulers("triggerFlow")} in {@code @Before}: {@code poll.initial-delay} of one hour holds the
 *       scheduled poll back for the whole run;</li>
 *   <li>{@code runSchedulersOnce("triggerFlow")}, {@code waitForPollToRun()} and
 *       {@code BatchTestHelper.awaitJobTermination(TIMEOUT_SEC * 1000, 500)}: one
 *       {@link ContactPollScheduler#pollOnce()}, awaited for at most 120 s, checked every 500 ms;</li>
 *   <li>{@code assertJobWasSuccessful()}: {@link BatchJobResult#isSuccessful()} and no failed record;</li>
 *   <li>{@code sfdc.user}, {@code sfdc.password}, {@code sfdc.securityToken} of {@code mule.test.properties}: the
 *       profile {@code it} makes Spring Boot load {@code sfdc.username}, {@code sfdc.password} and
 *       {@code sfdc.securityToken} from {@code application-it.yml} over the empty defaults of
 *       {@code application.yml}.</li>
 * </ul>
 *
 * <p>The original relied on whatever Contacts the org had modified since {@code YESTERDAY}. This test first creates
 * one Contact with a unique {@code LastName} ({@code WatermarkIT<millis>}), so the poll is known to select at least
 * one record, then also asserts what the flow does with it: the step logs it at INFO as
 * {@code Contact recently modified: <record>}, and the watermark {@code timestamp} moves from its default to a value
 * no earlier than the Contact's {@code LastModifiedDate}. The Contact is deleted in {@link #tearDown()}; the poll
 * itself only reads.
 *
 * <p>The class is skipped, never failed, while {@code application-it.yml} or one of {@code sfdc.username},
 * {@code sfdc.password}, {@code sfdc.securityToken} is missing, blank, still {@code TODO} or an unresolvable
 * placeholder ({@link #missingCredential()}); then no Spring context starts and Salesforce is never called. A skipped
 * run is not Tier 2B evidence. Logs name test record IDs only, never a credential.
 */
@ExtendWith(SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingIT.LiveCredentialsGate.class)
@SpringBootTest(classes = SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("it")
class SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingIT {

    private static final Logger LOG =
            LoggerFactory.getLogger(SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingIT.class);

    private static final String CONTACT = "Contact";

    private static final String FIRST_NAME = "Mule";

    private static final String LAST_NAME_PREFIX = "WatermarkIT";

    private static final String ID = "Id";

    /** The watermark's {@code default-expression}, which a poll that selected records must have replaced. */
    private static final String DEFAULT_WATERMARK = "YESTERDAY";

    /** The original's {@code TIMEOUT_SEC}, the bound of {@code awaitJobTermination}. */
    private static final Duration JOB_TIMEOUT = Duration.ofSeconds(120);

    /** The original's {@code awaitJobTermination} check interval. */
    private static final Duration JOB_CHECK_INTERVAL = Duration.ofMillis(500);

    /** One hour, the stand-in for the original's {@code stopFlowSchedulers}. */
    private static final String SCHEDULED_POLL_HOLD_BACK_MILLIS = "3600000";

    private static final String CREDENTIALS_FILE = "application-it.yml";

    /** Exactly the keys of {@code application-it.yml.example}; {@code sfdc.auth-endpoint} is optional. */
    private static final List<String> REQUIRED_KEYS = List.of("sfdc.username", "sfdc.password", "sfdc.securityToken");

    private static final String TODO = "TODO";

    /*
     * A fresh, empty watermark store per run, registered over failsafe's target/it-watermark/watermark.properties, so
     * the poll always starts from the default YESTERDAY and never from a value an earlier run stored. @TempDir rather
     * than Files.createTempDirectory: JUnit deletes it afterwards, so no store file is left in the shared temp area.
     */
    @TempDir
    static Path watermarkDir;

    @Autowired
    private ContactPollScheduler scheduler;

    @Autowired
    private SalesforcePartnerClient client;

    @Autowired
    private WatermarkStore watermarkStore;

    /** IDs of the Contacts this test created, deleted in {@link #tearDown()}. */
    private final List<String> createdIds = new ArrayList<>();

    /** The unique {@code LastName} of this run's Contact, once chosen. */
    private String lastName;

    /** Whether the create call timed out, so Salesforce may have created the Contact without returning its ID. */
    private boolean createOutcomeUnknown;

    private ch.qos.logback.classic.Logger batchServiceLogger;

    private ListAppender<ILoggingEvent> batchStepLog;

    /*
     * The same check LiveCredentialsGate makes. The gate is what reports the class as skipped; this assumption still
     * stops the class before any test instance, Spring context or Salesforce login exists when the gate does not run,
     * for example when junit.jupiter.conditions.deactivate switches conditions off.
     */
    @BeforeAll
    static void requireLiveCredentials() {
        String missing = missingCredential();
        Assumptions.assumeTrue(missing == null, missing);
    }

    /**
     * Points the watermark store at this run's empty directory and holds the scheduled poll back, both over the
     * failsafe system properties. The suppliers are read while the context starts, after JUnit has injected
     * {@link #watermarkDir}. No {@code sfdc.*} value is registered: those come from {@code application-it.yml} alone.
     */
    @DynamicPropertySource
    static void isolateWatermarkAndSchedule(DynamicPropertyRegistry registry) {
        registry.add("watermark.store-path", () -> watermarkDir.resolve("watermark.properties").toString());
        registry.add("poll.initial-delay", () -> SCHEDULED_POLL_HOLD_BACK_MILLIS);
    }

    /** Captures what the batch step {@code Batch_Step} logs, under the logger of {@link ContactSyncBatchService}. */
    @BeforeEach
    void captureBatchStepLog() {
        batchServiceLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ContactSyncBatchService.class);
        batchStepLog = new ListAppender<>();
        batchStepLog.start();
        batchServiceLogger.addAppender(batchStepLog);
    }

    @Test
    void testMainFlow() throws Exception {
        lastName = LAST_NAME_PREFIX + System.currentTimeMillis();
        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("FirstName", FIRST_NAME);
        contact.put("LastName", lastName);
        String id = createContact(contact);

        BatchJobResult result = runOnePoll();

        assertThat(result.isSuccessful()).as("batch job successful: %s", result).isTrue();
        assertThat(result.failedRecords()).as("failed records: %s", result).isZero();
        assertThat(result.loadedRecords()).as("records loaded, the created Contact among them: %s", result)
                .isPositive();

        assertThat(batchStepMessages())
                .as("INFO '%s<record>' logged for Contact %s", ContactSyncBatchService.LOG_PREFIX, id)
                .anyMatch(message -> message.contains(id));

        Instant contactLastModified = lastModifiedDate(id);
        Optional<String> stored = watermarkStore.current(ContactSyncBatchService.WATERMARK_KEY);
        assertThat(stored).as("watermark '%s' stored by the poll", ContactSyncBatchService.WATERMARK_KEY)
                .isPresent();
        assertThat(stored.get()).isNotEqualTo(DEFAULT_WATERMARK);
        assertThat(Instant.parse(stored.get()))
                .as("watermark %s against Contact LastModifiedDate %s", stored.get(), contactLastModified)
                .isAfterOrEqualTo(contactLastModified);
    }

    /**
     * Deletes every Contact this test created, one call per ID, so a failed delete is logged and the next ID is still
     * attempted; a failure is never thrown, as the cleanup must not hide the test's own outcome. When the create call
     * timed out, the Contact is first looked up by its unique {@code LastName}, because Salesforce may have created it
     * without the test receiving its ID. The ID list is always cleared and the log capture always detached.
     */
    @AfterEach
    void tearDown() {
        try {
            if (createOutcomeUnknown) {
                collectContactsByLastName();
            }
            for (String id : createdIds) {
                deleteContact(id);
            }
        } finally {
            createdIds.clear();
            if (batchServiceLogger != null) {
                batchServiceLogger.detachAppender(batchStepLog);
                batchStepLog.stop();
            }
        }
    }

    /**
     * Creates the Contact and records its ID as soon as Salesforce returns one, before anything is asserted.
     *
     * @return the new Contact's ID
     */
    private String createContact(Map<String, Object> contact) throws Exception {
        List<SaveResult> results;
        try {
            results = client.create(CONTACT, List.of(contact));
        } catch (Exception e) {
            createOutcomeUnknown = hasCause(e, SocketTimeoutException.class);
            throw e;
        }
        assertThat(results).as("save results of the Contact create").hasSize(1);
        SaveResult saved = results.get(0);
        String id = saved.getId();
        if (id != null && !id.isBlank()) {
            createdIds.add(id);
        }
        assertThat(saved.isSuccess()).as("Contact create succeeded: %s", errors(saved.getErrors())).isTrue();
        assertThat(id).as("ID of the created Contact").isNotBlank();
        LOG.info("Created Salesforce Contact {} ({})", id, lastName);
        return id;
    }

    /**
     * Runs one poll on its own thread and waits for it as the original waited for the batch job to terminate.
     *
     * @return the poll's batch job result
     * @throws Exception the poll's own failure, unwrapped
     */
    private BatchJobResult runOnePoll() throws Exception {
        ExecutorService pollThread = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "watermark-it-poll");
            thread.setDaemon(true);
            return thread;
        });
        try {
            CompletableFuture<BatchJobResult> poll = CompletableFuture.supplyAsync(() -> {
                try {
                    return scheduler.pollOnce();
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, pollThread);
            await("one poll of triggerFlow and its batch job watermarkingBatch")
                    .atMost(JOB_TIMEOUT)
                    .pollDelay(Duration.ZERO)
                    .pollInterval(JOB_CHECK_INTERVAL)
                    .until(poll::isDone);
            try {
                return poll.get();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception exception) {
                    throw exception;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw e;
            }
        } finally {
            pollThread.shutdownNow();
        }
    }

    /**
     * Returns the INFO messages {@code Batch_Step} logged. {@link ListAppender} appends under its own monitor, so the
     * list is copied under it.
     */
    private List<String> batchStepMessages() {
        List<ILoggingEvent> events;
        synchronized (batchStepLog) {
            events = List.copyOf(batchStepLog.list);
        }
        return events.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith(ContactSyncBatchService.LOG_PREFIX))
                .toList();
    }

    /** Reads the Contact's {@code LastModifiedDate}, the field the watermark's {@code MAX} selector reads. */
    private Instant lastModifiedDate(String id) throws Exception {
        List<Map<String, Object>> rows =
                client.query("SELECT Id,LastModifiedDate FROM Contact WHERE Id = '" + escapeSoql(id) + "'");
        assertThat(rows).as("Contact %s read back", id).hasSize(1);
        Object value = rows.get(0).get(ContactSyncBatchService.SELECTOR_FIELD);
        assertThat(value).as("LastModifiedDate of Contact %s", id).isNotNull();
        return value instanceof Calendar calendar ? calendar.toInstant() : Instant.parse(value.toString());
    }

    /** Adds the ID of every Contact carrying this run's {@code LastName} to {@link #createdIds}. */
    private void collectContactsByLastName() {
        try {
            for (Map<String, Object> row : client.query(
                    "SELECT Id FROM Contact WHERE LastName = '" + escapeSoql(lastName) + "'")) {
                Object id = row.get(ID);
                if (id != null && !createdIds.contains(id.toString())) {
                    createdIds.add(id.toString());
                }
            }
        } catch (Exception e) {
            LOG.warn("Could not look up Salesforce Contacts with LastName {} for deletion", lastName, e);
        }
    }

    private void deleteContact(String id) {
        try {
            List<DeleteResult> results = client.delete(List.of(id));
            DeleteResult result = results.isEmpty() ? null : results.get(0);
            if (result != null && result.isSuccess()) {
                LOG.info("Deleted Salesforce Contact {}", id);
            } else {
                LOG.warn("Could not delete Salesforce Contact {}: {}", id,
                        result == null ? "no delete result" : errors(result.getErrors()));
            }
        } catch (Exception e) {
            LOG.warn("Could not delete Salesforce Contact {}", id, e);
        }
    }

    /** Escapes a value for a SOQL string literal: the backslash first, then the single quote. */
    static String escapeSoql(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private static String errors(com.sforce.soap.partner.Error[] errors) {
        if (errors == null || errors.length == 0) {
            return "no error detail";
        }
        return Arrays.stream(errors)
                .map(error -> error.getStatusCode() + " " + error.getMessage())
                .collect(Collectors.joining("; "));
    }

    /**
     * Why the class cannot run live, or {@code null} when {@code application-it.yml} supplies every one of
     * {@link #REQUIRED_KEYS} with a value that is neither blank nor {@code TODO}. The file is read from the test
     * classpath, where Spring Boot reads it for the profile {@code it}, so the check and the context see the same
     * file. The reason names a key, never a value.
     */
    static String missingCredential() {
        ClassPathResource credentials = new ClassPathResource(CREDENTIALS_FILE);
        if (!credentials.exists()) {
            return "src/test/resources/" + CREDENTIALS_FILE
                    + " not supplied (copy application-it.yml.example); live Salesforce test skipped";
        }
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(credentials);
        Properties properties = yaml.getObject();
        StandardEnvironment environment = new StandardEnvironment();
        for (String key : REQUIRED_KEYS) {
            String value = resolve(environment, properties == null ? null : properties.getProperty(key));
            if (value == null || value.isBlank() || value.trim().toUpperCase(Locale.ROOT).startsWith(TODO)) {
                return key + " missing, blank or TODO in " + CREDENTIALS_FILE + "; live Salesforce test skipped";
            }
        }
        return null;
    }

    /**
     * Resolves {@code ${...}} placeholders against system properties and environment variables, as Spring Boot will
     * when it loads the file; a placeholder without a value or default yields {@code null}, because the context
     * would fail to start on it.
     */
    private static String resolve(StandardEnvironment environment, String value) {
        if (value == null) {
            return null;
        }
        try {
            return environment.resolveRequiredPlaceholders(value);
        } catch (IllegalArgumentException unresolvable) {
            return null;
        }
    }

    /**
     * Skips the class unless every live credential is supplied ({@link #missingCredential()}), deciding at class
     * level, before the test instance exists. The Spring context is loaded when the instance is prepared, so a
     * disabled class starts no application and attempts no Salesforce login.
     *
     * <p>A class condition rather than only the assumption in {@code @BeforeAll}: failsafe 3.1.2 counts a class whose
     * {@code @BeforeAll} aborts as neither run nor skipped, while each test of a class disabled here is reported
     * skipped with the reason, so the report shows that the live tier was skipped.
     */
    static final class LiveCredentialsGate implements ExecutionCondition {

        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            if (context.getTestMethod().isPresent()) {
                return ConditionEvaluationResult.enabled("decided for the class");
            }
            String missing = missingCredential();
            return missing == null
                    ? ConditionEvaluationResult.enabled("live Salesforce credentials supplied")
                    : ConditionEvaluationResult.disabled(missing);
        }
    }
}
