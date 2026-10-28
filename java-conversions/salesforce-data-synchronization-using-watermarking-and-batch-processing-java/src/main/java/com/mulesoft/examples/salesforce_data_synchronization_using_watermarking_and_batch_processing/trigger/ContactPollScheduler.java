package com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.trigger;

import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.model.BatchJobResult;
import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.service.ContactSyncBatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The poll of the original flow {@code triggerFlow} ({@code watermarking.xml}, l.15-17):
 * {@code <poll><fixed-frequency-scheduler frequency="10" timeUnit="SECONDS"/>}, which fires from start-up.
 *
 * <p>Each poll is one {@link ContactSyncBatchService#synchronize()} call; the watermark, the Salesforce query and the
 * batch job {@code watermarkingBatch} belong to that service. The flow's {@code processingStrategy="synchronous"} is
 * kept by Spring's single-threaded scheduler, so two scheduled polls never overlap.
 */
@Component
public class ContactPollScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContactPollScheduler.class);

    private final ContactSyncBatchService contactSyncBatchService;

    /**
     * Creates the trigger.
     *
     * @param contactSyncBatchService the service that performs one poll of {@code triggerFlow}
     */
    public ContactPollScheduler(ContactSyncBatchService contactSyncBatchService) {
        this.contactSyncBatchService = contactSyncBatchService;
    }

    /**
     * Scheduled poll. A fixed rate keeps the polling frequency as the period between poll starts, as the original
     * {@code fixed-frequency-scheduler} did.
     *
     * <p>A failed poll is logged at ERROR and swallowed, as Mule's default exception strategy handled a failed poll,
     * so the next poll still fires. Upstream failures were already classified and logged by the SC-9 executor; they
     * are neither retried nor mapped here.
     */
    @Scheduled(fixedRateString = "${poll.frequency:10000}", initialDelayString = "${poll.initial-delay:0}")
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
     * @return the result of the batch job run, as {@link ContactSyncBatchService#synchronize()} returned it
     * @throws Exception the failure of the poll, unchanged, with the watermark left as it was
     */
    public BatchJobResult pollOnce() throws Exception {
        return contactSyncBatchService.synchronize();
    }
}
