package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.trigger;

import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.model.BatchJobResult;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.service.ContactToDatabaseBatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The poll of the original flow {@code triggerFlow} ({@code salesforce-to-database.xml}, l.60-65):
 * {@code <poll><fixed-frequency-scheduler frequency="60" timeUnit="SECONDS"/>}, which fires from start-up.
 *
 * <p>Each poll is one {@link ContactToDatabaseBatchService#synchronize()} call; the watermark, the Salesforce query
 * and the batch job {@code salesforce-to-database-Batch} belong to that service. The flow's
 * {@code processingStrategy="synchronous"} is kept by Spring's single-threaded scheduler, so two scheduled polls never
 * overlap.
 */
@Component
public class ContactPollScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContactPollScheduler.class);

    private final ContactToDatabaseBatchService contactToDatabaseBatchService;

    /**
     * Creates the trigger.
     *
     * @param contactToDatabaseBatchService the service that performs one poll of {@code triggerFlow}
     */
    public ContactPollScheduler(ContactToDatabaseBatchService contactToDatabaseBatchService) {
        // Unchecked by design: constructor injection never passes null, and the shape matches the other watermark poll
        // trigger (salesforce-data-synchronization project), whose only differences are the service and the frequency.
        this.contactToDatabaseBatchService = contactToDatabaseBatchService;
    }

    /**
     * Scheduled poll. A fixed rate keeps the polling frequency as the period between poll starts, as the original
     * {@code fixed-frequency-scheduler} did.
     *
     * <p>A failed poll is logged at ERROR and swallowed, as Mule's default exception strategy handled a failed poll,
     * so the next poll still fires. Upstream failures were already classified and logged by the SC-9 executor; they
     * are neither retried nor mapped here.
     */
    @Scheduled(fixedRateString = "${poll.frequency:60000}", initialDelayString = "${poll.initial-delay:0}")
    public void poll() {
        try {
            pollOnce();
        } catch (Exception e) {
            LOGGER.error("Poll of triggerFlow failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Runs one poll on demand, in place of the original test's {@code runSchedulersOnce("triggerFlow")}.
     *
     * @return the result of the batch job run, as {@link ContactToDatabaseBatchService#synchronize()} returned it
     * @throws Exception the failure of the poll, unchanged, with the watermark left as it was
     */
    public BatchJobResult pollOnce() throws Exception {
        return contactToDatabaseBatchService.synchronize();
    }
}
