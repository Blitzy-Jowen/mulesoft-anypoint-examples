/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.client.SalesforcePartnerClient;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.model.BatchJobResult;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.service.ContactToDatabaseBatchService;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.support.SfdcObjectBuilder;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.trigger.ContactPollScheduler;
import com.sforce.soap.partner.DeleteResult;
import com.sforce.soap.partner.SaveResult;

/**
 * Tier 2B live-sandbox test, run by failsafe ({@code mvn -B verify}): the original
 * {@code SalesforceToMySQLDBUsingBatchProcessingIT} against the real Salesforce org configured in the git-ignored
 * {@code src/test/resources/application-it.yml} (template {@code application-it.yml.example}), with the in-memory H2
 * database in MySQL mode of profile {@code test} in place of the original's live MySQL server.
 *
 * <p>The original's pieces map as follows:
 * <ul>
 *   <li>{@code sfdc.user}, {@code sfdc.password}, {@code sfdc.securityToken} of {@code mule.test.properties}: profile
 *       {@code it} makes Spring Boot load {@code sfdc.username}, {@code sfdc.password} and {@code sfdc.securityToken}
 *       from {@code application-it.yml} over the empty defaults of {@code application.yml};</li>
 *   <li>{@code MySQLDbCreator} and the {@code database.*} keys: profile {@code test}, whose H2 database is created per
 *       Spring context from the unchanged {@code contact.sql};</li>
 *   <li>{@code stopFlowSchedulers("triggerFlow")}: {@code poll.initial-delay} of one hour (profile {@code test} and the
 *       failsafe system property) holds the scheduled poll back for the whole run;</li>
 *   <li>{@code insertSalesforceContactSubFlow} ({@code sfdc:upsert} of {@code LastName} and {@code Email}): one
 *       {@link SalesforcePartnerClient#create(String, List)} of those two fields, keeping the returned {@code Id};</li>
 *   <li>{@code runSchedulersOnce("triggerFlow")}, {@code waitForPollToRun()} and
 *       {@code BatchTestHelper.awaitJobTermination(TIMEOUT_SEC * 1000, 500)}: one
 *       {@link ContactPollScheduler#pollOnce()}, awaited for at most {@value #TIMEOUT_SEC} s, checked every 500 ms; the
 *       call is synchronous, so the original's extra five-second sleep has no counterpart;</li>
 *   <li>{@code assertJobWasSuccessful()}: {@link BatchJobResult#isSuccessful()} and no failed record;</li>
 *   <li>the {@code selectContactFromDB}, {@code deleteContactFromDB} and {@code deleteContactFromSalesforce} sub-flows:
 *       bound SQL by {@code last_name} through {@link JdbcTemplate} in place of the dynamic queries, and
 *       {@link SalesforcePartnerClient#delete(List)} by {@code Id}.</li>
 * </ul>
 *
 * <p>Exactly one Contact is written to Salesforce per run, with a unique {@code LastName} and {@code Email}, and it is
 * deleted in {@link #tearDown()} together with its {@code contact} row. No retry is added: every Salesforce call runs
 * through the SC-9 executor inside {@link SalesforcePartnerClient}.
 *
 * <p>The class is skipped, never failed, while {@code application-it.yml} or one of {@code sfdc.username},
 * {@code sfdc.password}, {@code sfdc.securityToken} is missing, blank, still {@code TODO} or an unresolvable
 * placeholder ({@link #missingCredential()}); then no Spring context starts and Salesforce is never called. A skipped
 * run is not Tier 2B evidence. Logs name test data only, never a credential.
 */
@ExtendWith(SalesforceToMySQLDBUsingBatchProcessingIT.LiveCredentialsGate.class)
@SpringBootTest(classes = SalesforceToMySQLDBUsingBatchProcessingApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test", "it"})
class SalesforceToMySQLDBUsingBatchProcessingIT {

    private static final Logger LOG = LoggerFactory.getLogger(SalesforceToMySQLDBUsingBatchProcessingIT.class);

    private static final String CONTACT_NAME = "Contact Test Name";

    /** The original's {@code TIMEOUT_SEC}, the bound of {@code awaitJobTermination}. */
    private static final int TIMEOUT_SEC = 120;

    /** The original's {@code awaitJobTermination} check interval. */
    private static final Duration JOB_CHECK_INTERVAL = Duration.ofMillis(500);

    /** The original's {@code POLL_FLOW_NAME}: the flow whose poll {@link ContactPollScheduler} replaces. */
    private static final String POLL_FLOW_NAME = "triggerFlow";

    private static final String CONTACT = "Contact";

    private static final String LAST_NAME = "LastName";

    private static final String EMAIL = "Email";

    private static final String ID = "Id";

    /** The credentials file, as Spring Boot loads it for profile {@code it}: from the test classpath. */
    private static final String IT_CONFIG = "application-it.yml";

    /** Exactly the keys of {@code application-it.yml.example}; {@code sfdc.auth-endpoint} is optional. */
    private static final List<String> REQUIRED_KEYS = List.of("sfdc.username", "sfdc.password", "sfdc.securityToken");

    private static final String TODO = "TODO";

    /** The original {@code selectContactFromDB} query, its value bound instead of concatenated. */
    private static final String SELECT_BY_LAST_NAME =
            "select first_name, last_name, email from contact where last_name = ?";

    /** The original {@code deleteContactFromDB} statement, its value bound instead of concatenated. */
    private static final String DELETE_BY_LAST_NAME = "delete from contact where last_name = ?";

    /*
     * A fresh, empty watermark store per run, registered over failsafe's target/it-watermark/watermark.properties, so
     * the poll starts from the default watermark (now - 10 s, UTC) and selects the Contact created moments before it,
     * never from a value an earlier run stored. @TempDir rather than Files.createTempDirectory: JUnit deletes it
     * afterwards, so no store file is left behind in the shared temp area.
     */
    @TempDir
    static Path watermarkDir;

    @Autowired
    private SalesforcePartnerClient salesforcePartnerClient;

    @Autowired
    private ContactPollScheduler contactPollScheduler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** The Contact this test created: {@code LastName}, {@code Email} and, once Salesforce returned it, {@code Id}. */
    private Map<String, Object> contact;

    /** The {@code Id} Salesforce returned for {@link #contact}, deleted in {@link #tearDown()}. */
    private String contactId;

    /** Whether the create call timed out, so Salesforce may have created the Contact without returning its ID. */
    private boolean createOutcomeUnknown;

    /*
     * The same check LiveCredentialsGate makes. The gate is what reports the class as skipped; this assumption still
     * stops the class before any test instance, Spring context or Salesforce login exists when the gate does not run,
     * for example when junit.jupiter.conditions.deactivate switches conditions off.
     */
    @BeforeAll
    static void requireSandboxCredentials() {
        String missing = missingCredential();
        Assumptions.assumeTrue(missing == null, missing);
    }

    /**
     * Points the watermark store at this run's empty directory, over the failsafe system property. The supplier is
     * read while the context starts, after JUnit has injected {@link #watermarkDir}. No {@code sfdc.*} value is
     * registered: those come from {@code application-it.yml} alone.
     */
    @DynamicPropertySource
    static void isolateWatermark(DynamicPropertyRegistry registry) {
        registry.add("watermark.store-path", () -> watermarkDir.resolve("watermark.properties").toString());
    }

    @Test
    void testMainFlow() throws Exception {
        contact = createSalesforceContact();
        insertSalesforceContact(contact);

        BatchJobResult result = runPollOnce();
        assertTrue(result.isSuccessful(), () -> "The batch job should have been successful: " + result);
        assertEquals(0, result.failedRecords(), () -> "The batch job should have no failed record: " + result);

        List<Map<String, Object>> payload = jdbcTemplate.queryForList(SELECT_BY_LAST_NAME, contact.get(LAST_NAME));
        for (Map<String, Object> row : payload) {
            LOG.info("selectContactFromDB response: {}", row);
        }

        // Contact previously created in Salesforce should be present in database
        assertEquals(1, payload.size(), "The Contact should have been sync");
        assertEquals(contact.get(LAST_NAME), payload.get(0).get("last_name"), "The Contact name should match");
    }

    /**
     * Deletes the Contact's {@code contact} row, then the Contact itself. Each step is guarded and logs its own
     * failure at WARN instead of throwing, so the cleanup never hides the test's own outcome and a failed row delete
     * still lets the Salesforce delete run. When the create call timed out, the Contact is first looked up by its
     * unique {@code LastName}, because Salesforce may have created it without the test receiving its ID.
     */
    @AfterEach
    void tearDown() {
        if (contact == null) {
            return;
        }
        deleteContactFromDB();
        if (contactId == null && createOutcomeUnknown) {
            contactId = findContactIdByLastName();
        }
        if (contactId != null) {
            deleteContactFromSalesforce();
        }
    }

    private Map<String, Object> createSalesforceContact() {
        long millis = System.currentTimeMillis();
        Map<String, Object> created = SfdcObjectBuilder.aContact()
                .with(LAST_NAME, CONTACT_NAME + millis)
                .with(EMAIL, "Contact" + millis + "@test.com")
                .build();
        LOG.info("SFDC contact: {}", created);
        return created;
    }

    /**
     * Creates the Contact with the original sub-flow's two fields and stores its {@code Id}, recorded as soon as
     * Salesforce returns one so that {@link #tearDown()} deletes it even when a later assertion fails.
     */
    private void insertSalesforceContact(Map<String, Object> salesforceContact) throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(LAST_NAME, salesforceContact.get(LAST_NAME));
        fields.put(EMAIL, salesforceContact.get(EMAIL));

        List<SaveResult> results;
        try {
            results = salesforcePartnerClient.create(CONTACT, List.of(fields));
        } catch (Exception e) {
            createOutcomeUnknown = hasCause(e, SocketTimeoutException.class);
            throw e;
        }
        assertEquals(1, results.size(), "The Contact create should have returned one save result");
        SaveResult saved = results.get(0);
        String id = saved.getId();
        if (id != null && !id.isBlank()) {
            contactId = id;
            salesforceContact.put(ID, id);
        }
        LOG.info("response from insertSalesforceContactSubFlow: success={}, id={}", saved.isSuccess(), id);
        assertTrue(saved.isSuccess(), () -> "The Contact create should have succeeded: " + errors(saved.getErrors()));
        assertNotNull(id, "The created Contact should have an Id");
        assertFalse(id.isBlank(), "The created Contact should have an Id");
    }

    /**
     * Runs one poll on its own thread and waits for it as the original waited for the batch job to terminate.
     *
     * @return the poll's batch job result
     * @throws Exception the poll's own failure, unwrapped
     */
    private BatchJobResult runPollOnce() throws Exception {
        ExecutorService pollThread = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "sf-to-mysql-it-poll");
            thread.setDaemon(true);
            return thread;
        });
        try {
            CompletableFuture<BatchJobResult> poll = CompletableFuture.supplyAsync(() -> {
                try {
                    return contactPollScheduler.pollOnce();
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, pollThread);
            await("one poll of " + POLL_FLOW_NAME + " and its batch job " + ContactToDatabaseBatchService.JOB_NAME)
                    .atMost(Duration.ofSeconds(TIMEOUT_SEC))
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

    private void deleteContactFromDB() {
        try {
            int deleted = jdbcTemplate.update(DELETE_BY_LAST_NAME, contact.get(LAST_NAME));
            LOG.info("deleteContactFromDB result: {}", deleted);
        } catch (RuntimeException e) {
            LOG.warn("Could not delete the contact row with last_name {}", contact.get(LAST_NAME), e);
        }
    }

    private void deleteContactFromSalesforce() {
        try {
            List<DeleteResult> results = salesforcePartnerClient.delete(List.of(contactId));
            DeleteResult result = results.isEmpty() ? null : results.get(0);
            LOG.info("Remove Contact from Salesforce result: {}", result == null ? "no delete result"
                    : "id=" + result.getId() + ", success=" + result.isSuccess());
            if (result == null || !result.isSuccess()) {
                LOG.warn("Could not delete Salesforce Contact {}: {}", contactId,
                        result == null ? "no delete result" : errors(result.getErrors()));
            }
        } catch (Exception e) {
            LOG.warn("Could not delete Salesforce Contact {}", contactId, e);
        }
    }

    /** Looks up the Id of the Contact carrying this run's unique {@code LastName}, or {@code null} if none exists. */
    private String findContactIdByLastName() {
        Object lastName = contact.get(LAST_NAME);
        try {
            List<Map<String, Object>> rows = salesforcePartnerClient.query(
                    "SELECT Id FROM Contact WHERE LastName = '" + escapeSoql(String.valueOf(lastName)) + "'");
            Object id = rows.isEmpty() ? null : rows.get(0).get(ID);
            return id == null ? null : id.toString();
        } catch (Exception e) {
            LOG.warn("Could not look up the Salesforce Contact with LastName {} for deletion", lastName, e);
            return null;
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
     * {@link #REQUIRED_KEYS} with a value that is neither blank nor {@code TODO}. The reason names a key, never a
     * value.
     *
     * <p>The file is read from the test classpath rather than as {@code src/test/resources/application-it.yml} on
     * disk, because the classpath copy is the one Spring Boot loads for profile {@code it}: the check and the context
     * therefore always judge the same file, whatever the working directory.
     */
    static String missingCredential() {
        ClassPathResource credentials = new ClassPathResource(IT_CONFIG);
        if (!credentials.exists()) {
            return "src/test/resources/" + IT_CONFIG
                    + " not supplied (copy application-it.yml.example); Tier 2B live Salesforce test skipped";
        }
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(credentials);
        Properties properties = yaml.getObject();
        StandardEnvironment environment = new StandardEnvironment();
        for (String key : REQUIRED_KEYS) {
            String value = resolve(environment, properties == null ? null : properties.getProperty(key));
            if (value == null || value.isBlank() || value.trim().toUpperCase(Locale.ROOT).startsWith(TODO)) {
                return key + " missing, blank or TODO in " + IT_CONFIG + "; Tier 2B live Salesforce test skipped";
            }
        }
        return null;
    }

    /**
     * Resolves {@code ${...}} placeholders against system properties and environment variables, as Spring Boot will
     * when it loads the file; a placeholder without a value or default yields {@code null}, because the context would
     * fail to start on it.
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
