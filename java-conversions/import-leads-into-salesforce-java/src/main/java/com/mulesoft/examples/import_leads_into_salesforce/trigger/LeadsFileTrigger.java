/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_leads_into_salesforce.trigger;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mulesoft.examples.import_leads_into_salesforce.service.LeadImportBatchService;

/**
 * The inbound endpoint in the {@code batch:input} of job {@code CreateLeadsBatch}
 * ({@code import-leads-into-salesforce.xml}, l.11):
 * {@code <file:inbound-endpoint moveToDirectory="src/main/resources/output" path="src/main/resources/input"
 * pollingFrequency="10000" responseTimeout="10000"/>}.
 *
 * <p>Every poll hands each file's bytes unchanged to {@link LeadImportBatchService#importLeads(byte[])}, which runs
 * one instance of the batch job. Reading, the in-process mark, the move to {@code leads.move-to-dir} under the file's
 * own name after success and after failure, and the ERROR log of a failed run all belong to {@link FileInboundPoller},
 * which reproduces the default Mule file connector the original relies on; a Salesforce failure inside a step fails
 * its records in the job, a failure of the input phase is logged by the poller, neither is retried, and the file is
 * still moved. The endpoint's {@code responseTimeout} is not carried: a one-way file endpoint sends no reply.
 */
@Component
public class LeadsFileTrigger {

    private final FileInboundPoller poller;

    /**
     * Creates the trigger.
     *
     * @param leadImportBatchService the batch job each polled file is handed to
     * @param inputDir               the endpoint's {@code path}; a relative path resolves against the working
     *                               directory, as Mule resolved it against the application directory
     * @param moveToDir              the endpoint's {@code moveToDirectory}, resolved the same way
     */
    public LeadsFileTrigger(LeadImportBatchService leadImportBatchService,
                            @Value("${leads.input-dir}") String inputDir,
                            @Value("${leads.move-to-dir}") String moveToDir) {
        // The job's result (its record counts) went nowhere in the original, so it is discarded here.
        this.poller = new FileInboundPoller(Path.of(inputDir), Path.of(moveToDir),
                file -> leadImportBatchService.importLeads(file.content()));
    }

    /**
     * Scheduled poll. A fixed delay keeps the polling frequency as the pause between the end of one poll and the
     * start of the next, as Mule's polling receiver waited.
     */
    @Scheduled(fixedDelayString = "${leads.polling-frequency:10000}")
    public void poll() {
        pollOnce();
    }

    /**
     * Runs one poll on demand. It shares the scheduled poll's {@link FileInboundPoller}, so a file in process in one
     * run is never handed over by the other.
     *
     * @return the number of files handed to {@link LeadImportBatchService}, counting a file whose import failed
     */
    public int pollOnce() {
        return poller.pollOnce();
    }

    /**
     * Returns the directory this trigger polls.
     *
     * @return the input directory as configured
     */
    public Path inputDirectory() {
        return poller.inputDirectory();
    }

    /**
     * Returns the directory processed files are moved to.
     *
     * @return the move directory as configured
     */
    public Path moveToDirectory() {
        return poller.moveToDirectory().orElseThrow();
    }
}
