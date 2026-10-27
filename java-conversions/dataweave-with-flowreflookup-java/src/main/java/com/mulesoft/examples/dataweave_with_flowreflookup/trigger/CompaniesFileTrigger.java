/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.dataweave_with_flowreflookup.trigger;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mulesoft.examples.dataweave_with_flowreflookup.service.AccountImportService;

/**
 * The inbound endpoint of flow {@code CreateNewSalesforceAccountFlow} ({@code dataweave-with-flowref.xml}, l.7):
 * {@code <file:inbound-endpoint moveToDirectory="src/main/resources/output" path="src/main/resources/input"
 * pollingFrequency="10000" responseTimeout="10000"/>}.
 *
 * <p>Every poll hands each file's bytes unchanged to {@link AccountImportService#importAccounts(byte[])}, which runs
 * the rest of the flow. Reading, the in-process mark, the move to {@code companies.move-to-dir} under the file's own
 * name after success and after failure, and the ERROR log of a failed flow all belong to {@link FileInboundPoller}
 * (SC-5), which reproduces the default Mule file connector the original relies on; a Salesforce failure is therefore
 * logged and never retried, and the file is still moved, so no Account {@code create} is ever repeated. The
 * endpoint's {@code responseTimeout} is not carried: a one-way file endpoint sends no reply.
 */
@Component
public class CompaniesFileTrigger {

    private final FileInboundPoller poller;

    /**
     * Creates the trigger.
     *
     * @param accountImportService the flow body each polled file is handed to
     * @param inputDir             the endpoint's {@code path}; a relative path resolves against the working
     *                             directory, as Mule resolved it against the application directory
     * @param moveToDir            the endpoint's {@code moveToDirectory}, resolved the same way
     */
    public CompaniesFileTrigger(AccountImportService accountImportService,
                                @Value("${companies.input-dir}") String inputDir,
                                @Value("${companies.move-to-dir}") String moveToDir) {
        // The flow's result (the Salesforce save results) went nowhere in the original, so it is discarded here.
        this.poller = new FileInboundPoller(Path.of(inputDir), Path.of(moveToDir),
                file -> accountImportService.importAccounts(file.content()));
    }

    /**
     * Scheduled poll. A fixed delay keeps the polling frequency as the pause between the end of one poll and the
     * start of the next, as Mule's polling receiver waited.
     */
    @Scheduled(fixedDelayString = "${companies.polling-frequency:10000}")
    public void poll() {
        pollOnce();
    }

    /**
     * Runs one poll on demand. It shares the scheduled poll's {@link FileInboundPoller}, so a file in process in one
     * run is never handed over by the other.
     *
     * @return the number of files handed to {@link AccountImportService}, counting a file whose import failed
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
