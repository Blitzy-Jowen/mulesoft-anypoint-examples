/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.client.SalesforcePartnerClient;
import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.model.BatchJobResult;
import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.service.ContactSyncBatchService;
import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.service.WatermarkStore;
import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.trigger.ContactPollScheduler;

/**
 * Port of the original example's {@code SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingIT} functional
 * test, keeping its single test {@code testMainFlow}: run the poll of {@code triggerFlow} once, wait for the batch job
 * {@code watermarkingBatch} it executes to terminate, and assert that the job was successful.
 *
 * <p>The original ran {@code watermarking.xml} in an embedded Mule runtime against a live organization. Here the whole
 * application context runs without a web server, and a Mockito {@link SalesforcePartnerClient} stands in for
 * Salesforce: it answers the flow's {@code sfdc:query} with two recently modified Contacts, in the record shape the
 * Mule connector produced ({@code java.util.HashMap} with {@code Id}, {@code Name}, {@code LastModifiedDate} and
 * {@code type}, as the README's sample log line shows). Everything between the poll and that call (the trigger, the
 * SC-8 watermark store, the query construction, the SC-7 batch runner and the job's step) is the production code, so
 * no credential is needed and nothing leaves the JVM. The same assertion runs against a live sandbox in the failsafe
 * {@code SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingIT}.
 *
 * <p>Parts of the original that are not ported, because the mocked client and the on-demand poll make them
 * unnecessary:
 * <ul>
 *   <li>{@code runSchedulersOnce("triggerFlow")} becomes {@link ContactPollScheduler#pollOnce()}, the trigger's
 *       single-run method; {@code stopFlowSchedulers} becomes a scheduled first poll held back for an hour, so no
 *       scheduled poll runs during the test;</li>
 *   <li>{@code waitForPollToRun()} ({@code ListenerProbe} over a {@code PipelineSynchronizeListener} on the end of
 *       {@code triggerFlow}) and {@code BatchTestHelper.awaitJobTermination(TIMEOUT_SEC * 1000, 500)} become one
 *       Awaitility wait, with the same limit and poll interval, on the poll that runs the job to completion; and
 *       {@code assertJobWasSuccessful()} becomes {@link BatchJobResult#isSuccessful()} of the job it returns;</li>
 *   <li>loading {@code mule.test.properties} into the {@code sfdc.user}, {@code sfdc.password} and
 *       {@code sfdc.securityToken} system properties: the mocked client never logs in;</li>
 *   <li>the {@code http.port} {@code DynamicPort} and the {@code ./mappings} application home: the example has no
 *       HTTP listener and no mapping files.</li>
 * </ul>
 * Beyond the original's assertion, the record counts of the job, the query the default watermark selects and the
 * watermark the {@code MAX} selector stores are checked as well.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public class SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingTest {

    /** The original's wait limit for the batch job, in seconds ({@code AbstractTemplateTestCase.TIMEOUT_SEC}). */
    private static final int TIMEOUT_SEC = 120;

    /** The original's poll interval while waiting for the batch job ({@code awaitJobTermination(..., 500)}). */
    private static final Duration JOB_POLL_INTERVAL = Duration.ofMillis(500);

    /** The flow whose poll the original ran once ({@code AbstractTemplateTestCase.POLL_FLOW_NAME}). */
    private static final String POLL_FLOW_NAME = "triggerFlow";

    /** The watermark's {@code default-expression} ({@code watermark.default-value}). */
    private static final String DEFAULT_WATERMARK = "YESTERDAY";

    /** The README's sample Contact. */
    private static final String EARLIER_MODIFIED = "2014-07-04T06:16:54.000Z";

    /** The later of the two Contacts, so the {@code MAX} selector must pick it regardless of record order. */
    private static final String LATEST_MODIFIED = "2014-07-04T08:20:00.000Z";

    /**
     * Holds this class's watermark store file.
     *
     * <p>A JUnit-managed directory rather than {@code Files.createTempDirectory}: runtime files of this project's tests
     * never stay behind in the host's shared temporary directory, and JUnit removes this one after the class.
     */
    @TempDir
    static Path storeDir;

    @MockBean
    SalesforcePartnerClient salesforcePartnerClient;

    @Autowired
    ContactPollScheduler contactPollScheduler;

    @Autowired
    WatermarkStore watermarkStore;

    /**
     * Starts the poll from an empty watermark store and holds the scheduled first poll back for an hour. These
     * registrations outrank the surefire system properties of {@code pom.xml}, so no watermark another test left in
     * {@code target/test-watermark} is read. The suppliers are read while the context starts, after JUnit has injected
     * {@link #storeDir}.
     */
    @DynamicPropertySource
    static void watermarkProperties(DynamicPropertyRegistry registry) {
        registry.add("watermark.store-path", () -> storeDir.resolve("watermark.properties").toString());
        registry.add("poll.initial-delay", () -> "3600000");
    }

    @Test
    public void testMainFlow() throws Exception {
        List<Map<String, Object>> contacts = List.of(
                contact("0032000001DpkrEAAR", "Avi123 Green", EARLIER_MODIFIED),
                contact("0032000001DpkrFAAR", "Mule Tester", LATEST_MODIFIED));
        when(salesforcePartnerClient.query(anyString())).thenReturn(contacts);
        assertEquals(Optional.empty(), watermarkStore.current(ContactSyncBatchService.WATERMARK_KEY),
                "The poll should start without a stored watermark");

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
        assertEquals(ContactSyncBatchService.JOB_NAME, result.jobName());
        assertEquals(2, result.loadedRecords(), () -> "loaded records of " + result);
        assertEquals(2, result.processedRecords(), () -> "processed records of " + result);
        assertEquals(2, result.successfulRecords(), () -> "successful records of " + result);
        assertEquals(0, result.failedRecords(), () -> "failed records of " + result);

        // The MAX selector over payload.LastModifiedDate
        assertEquals(Optional.of(LATEST_MODIFIED), watermarkStore.current(ContactSyncBatchService.WATERMARK_KEY));

        // One query, from the default watermark, and no other Salesforce call
        verify(salesforcePartnerClient).query(ContactSyncBatchService.buildQuery(DEFAULT_WATERMARK));
        verifyNoMoreInteractions(salesforcePartnerClient);
    }

    /** A Contact as the Mule Salesforce connector returned it from {@code sfdc:query}. */
    private static Map<String, Object> contact(String id, String name, String lastModifiedDate) {
        Map<String, Object> contact = new HashMap<>();
        contact.put("Id", id);
        contact.put("Name", name);
        contact.put("LastModifiedDate", lastModifiedDate);
        contact.put("type", "Contact");
        return contact;
    }
}
