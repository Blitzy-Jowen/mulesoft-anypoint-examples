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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.client.SalesforcePartnerClient;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.model.BatchJobResult;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.service.ContactToDatabaseBatchService;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.service.WatermarkStore;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.support.SfdcObjectBuilder;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.trigger.ContactPollScheduler;

/**
 * Port of the original example's {@code SalesforceToMySQLDBUsingBatchProcessingIT} functional test, keeping its
 * single test {@code testMainFlow}: a Contact modified in Salesforce is picked up by one poll of {@code triggerFlow},
 * the batch job {@code salesforce-to-database-Batch} it executes terminates successfully, and the contact is then
 * found in the {@code contact} table under its {@code last_name}.
 *
 * <p>The original ran {@code salesforce-to-database.xml} in an embedded Mule runtime against a live Salesforce
 * organization and a live MySQL server. Here the whole application context runs without a web server on profile
 * {@code test}: an H2 database in MySQL mode, created from the unchanged {@code contact.sql}, replaces MySQL and the
 * original {@code MySQLDbCreator}, and a Mockito {@link SalesforcePartnerClient} replaces the sandbox, answering the
 * flow's {@code sfdc:query} with the Contact the original inserted. Everything between the poll and that call (the
 * trigger, the SC-8 watermark store, the query construction, the SC-7 batch runner, the job's two steps, the
 * DataWeave mapping and the repository) is the production code, so the test needs no credential and nothing leaves
 * the JVM. The same assertions run against a live sandbox in the failsafe
 * {@code SalesforceToMySQLDBUsingBatchProcessingIT}.
 *
 * <p>Replacements of the original's Mule test machinery:
 * <ul>
 *   <li>{@code runSchedulersOnce("triggerFlow")} becomes {@link ContactPollScheduler#pollOnce()}, the trigger's
 *       single-run method; {@code stopFlowSchedulers} becomes the scheduled first poll that profile {@code test} holds
 *       back for an hour;</li>
 *   <li>{@code waitForPollToRun()} and {@code BatchTestHelper.awaitJobTermination(TIMEOUT_SEC * 1000, 500)} become
 *       one Awaitility wait, with the same limit and poll interval, on the poll that runs the job to completion, and
 *       {@code assertJobWasSuccessful()} becomes {@link BatchJobResult#isSuccessful()} of the job it returns; the
 *       original's further {@code Thread.sleep(5000)} is dropped, because the job has completed when the poll
 *       returns;</li>
 *   <li>{@code insertSalesforceContactSubFlow} becomes the stubbed query result, carrying the
 *       {@code LastModifiedDate} Salesforce would have assigned on insert;</li>
 *   <li>the test flows {@code selectContactFromDB} and {@code deleteContactFromDB}, which spliced
 *       {@code #[payload.LastName]} into dynamic SQL, become the same statements with a bind parameter;
 *       {@code deleteContactFromSalesforce} is dropped, because nothing was created in Salesforce.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = SalesforceToMySQLDBUsingBatchProcessingApplication.class)
@ActiveProfiles("test")
public class SalesforceToMySQLDBUsingBatchProcessingTest {

    private static final Logger log = LoggerFactory.getLogger(SalesforceToMySQLDBUsingBatchProcessingTest.class);

    private static final String CONTACT_NAME = "Contact Test Name";

    /** The original's wait limit for the batch job, in seconds ({@code AbstractTemplateTestCase.TIMEOUT_SEC}). */
    private static final int TIMEOUT_SEC = 120;

    /** The original's poll interval while waiting for the batch job ({@code awaitJobTermination(..., 500)}). */
    private static final Duration JOB_POLL_INTERVAL = Duration.ofMillis(500);

    /** The flow whose poll the original ran once ({@code AbstractTemplateTestCase.POLL_FLOW_NAME}). */
    private static final String POLL_FLOW_NAME = "triggerFlow";

    /** Test flow {@code selectContactFromDB}, with {@code '#[payload.LastName]'} as a bind parameter. */
    private static final String SELECT_BY_LAST_NAME =
            "select first_name, last_name, email from contact where last_name = ?";

    /** Test flow {@code deleteContactFromDB}, with {@code '#[payload.LastName]'} as a bind parameter. */
    private static final String DELETE_BY_LAST_NAME = "delete from contact where last_name = ?";

    /** Salesforce's UTC form of {@code LastModifiedDate}, which the watermark {@code MAX} selector reads. */
    private static final DateTimeFormatter SALESFORCE_DATE_TIME =
            DateTimeFormatter.ofPattern(ContactToDatabaseBatchService.WATERMARK_PATTERN).withZone(ZoneOffset.UTC);

    /**
     * Holds this class's watermark store file, so the poll always starts from the default watermark (now - 10 s)
     * rather than a value another run left in surefire's {@code target/test-watermark}.
     *
     * <p>A JUnit-managed directory rather than {@code Files.createTempDirectory}: runtime files of this project's
     * tests never stay behind in the host's shared temporary directory, and JUnit removes this one after the class.
     * {@link #properties} reads it only when the context starts, after JUnit has injected it.
     */
    @TempDir
    static Path storeDir;

    @MockBean
    SalesforcePartnerClient salesforcePartnerClient;

    @Autowired
    ContactPollScheduler contactPollScheduler;

    @Autowired
    WatermarkStore watermarkStore;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private Map<String, Object> contact;

    /** Outranks the surefire system property {@code watermark.store-path} of {@code pom.xml}. */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("watermark.store-path", () -> storeDir.resolve("watermark.properties").toString());
    }

    @BeforeEach
    public void setUp() throws Exception {
        // prepare test data
        contact = createSalesforceContact();
        insertSalesforceContact(contact);
    }

    @AfterEach
    public void tearDown() {
        if (contact != null) {
            // delete previously created Contact from DB by matching last name
            int result = jdbcTemplate.update(DELETE_BY_LAST_NAME, contact.get("LastName"));
            log.info("deleteContactFromDB result: " + result);
        }
    }

    @Test
    public void testMainFlow() throws Exception {
        // Run poll and wait for it to run, together with the batch job it executes
        CompletableFuture<BatchJobResult> poll = CompletableFuture.supplyAsync(() -> {
            try {
                return contactPollScheduler.pollOnce();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
        await().atMost(Duration.ofSeconds(TIMEOUT_SEC)).pollInterval(JOB_POLL_INTERVAL).until(poll::isDone);
        BatchJobResult result = poll.get();
        assertTrue(result.isSuccessful(),
                () -> "The batch job executed by the poll of " + POLL_FLOW_NAME + " should be successful: " + result);
        assertEquals(1, result.loadedRecords(), () -> "loaded records of " + result);

        // Execute selectContactFromDB
        List<Map<String, Object>> payload = jdbcTemplate.queryForList(SELECT_BY_LAST_NAME, contact.get("LastName"));

        // print result
        for (Map<String, Object> acc : payload) {
            log.info("selectContactFromDB response: " + acc);
        }

        // Contact previously created in Salesforce should be present in database
        assertEquals(1, payload.size(), "The Contact should have been sync");
        assertEquals(contact.get("LastName"), payload.get(0).get("last_name"), "The Contact name should match");

        // One query, from the default watermark, which selects the Contact; no other Salesforce call, no retry
        ArgumentCaptor<String> soql = ArgumentCaptor.forClass(String.class);
        verify(salesforcePartnerClient).query(soql.capture());
        verifyNoMoreInteractions(salesforcePartnerClient);
        String query = soql.getValue();
        assertTrue(query.startsWith(ContactToDatabaseBatchService.QUERY_PREFIX), () -> "Unexpected query: " + query);
        Instant watermark = Instant.parse(query.substring(ContactToDatabaseBatchService.QUERY_PREFIX.length()));
        Instant lastModified = Instant.parse((String) contact.get("LastModifiedDate"));
        assertTrue(watermark.isBefore(lastModified),
                () -> "The default watermark " + watermark + " should select the Contact modified at " + lastModified);

        // The MAX selector over payload.LastModifiedDate
        assertEquals(Optional.of(contact.get("LastModifiedDate")),
                watermarkStore.current(ContactToDatabaseBatchService.WATERMARK_KEY));
    }

    /**
     * Stands in for the original's {@code insertSalesforceContactSubFlow}: stores the {@code LastModifiedDate}
     * Salesforce assigns on insert into the Contact, as the original did, and answers the poll's query with the
     * Contact as the Partner API returns it for the selected fields. The original inserted only {@code LastName} and
     * {@code Email}, so {@code FirstName} comes back nil.
     */
    private void insertSalesforceContact(Map<String, Object> contact) throws Exception {
        contact.put("LastModifiedDate", SALESFORCE_DATE_TIME.format(Instant.now()));
        Map<String, Object> queried = SfdcObjectBuilder.aContact()
                .with("type", "Contact")
                .with("Email", contact.get("Email"))
                .with("FirstName", null)
                .with("LastModifiedDate", contact.get("LastModifiedDate"))
                .with("LastName", contact.get("LastName"))
                .build();
        when(salesforcePartnerClient.query(anyString())).thenReturn(List.of(queried));
    }

    private Map<String, Object> createSalesforceContact() {
        long millis = System.currentTimeMillis();
        Map<String, Object> contact = SfdcObjectBuilder.aContact()
                .with("LastName", CONTACT_NAME + millis)
                .with("Email", "Contact" + millis + "@test.com")
                .build();
        log.info("SFDC contact: " + contact);
        return contact;
    }
}
