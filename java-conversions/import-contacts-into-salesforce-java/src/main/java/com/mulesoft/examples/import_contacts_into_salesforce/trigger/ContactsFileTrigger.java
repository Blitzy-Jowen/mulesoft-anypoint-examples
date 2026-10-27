/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_salesforce.trigger;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mulesoft.examples.import_contacts_into_salesforce.service.ContactImportService;

/**
 * The inbound endpoint of flow {@code Contacts_to_SFDC} ({@code contacts-to-SFDC.xml}, l.9):
 * {@code <file:inbound-endpoint path="src/main/resources/input" moveToDirectory="src/main/resources/output"
 * pollingFrequency="10000" responseTimeout="10000"/>}.
 *
 * <p>Every poll hands each file's bytes unchanged to {@link ContactImportService#importContacts(byte[])}, which runs
 * the rest of the flow. Reading, the in-process mark, the move to {@code contacts.move-to-dir} under the file's own
 * name after success and after failure, and the ERROR log of a failed flow all belong to {@link FileInboundPoller},
 * which reproduces the default Mule file connector the original relies on; a Salesforce failure is therefore logged
 * and never retried, and the file is still moved. The endpoint's {@code responseTimeout} is not carried: a one-way
 * file endpoint sends no reply.
 */
@Component
public class ContactsFileTrigger {

    private final FileInboundPoller poller;

    /**
     * Creates the trigger.
     *
     * @param contactImportService the flow body each polled file is handed to
     * @param inputDir             the endpoint's {@code path}; a relative path resolves against the working
     *                             directory, as Mule resolved it against the application directory
     * @param moveToDir            the endpoint's {@code moveToDirectory}, resolved the same way
     */
    public ContactsFileTrigger(ContactImportService contactImportService,
                               @Value("${contacts.input-dir}") String inputDir,
                               @Value("${contacts.move-to-dir}") String moveToDir) {
        // The flow's result (the Salesforce save results) went nowhere in the original, so it is discarded here.
        this.poller = new FileInboundPoller(Path.of(inputDir), Path.of(moveToDir),
                file -> contactImportService.importContacts(file.content()));
    }

    /**
     * Scheduled poll. A fixed delay keeps the polling frequency as the pause between the end of one poll and the
     * start of the next, as Mule's polling receiver waited.
     */
    @Scheduled(fixedDelayString = "${contacts.polling-frequency:10000}")
    public void poll() {
        pollOnce();
    }

    /**
     * Runs one poll on demand. It shares the scheduled poll's {@link FileInboundPoller}, so a file in process in one
     * run is never handed over by the other.
     *
     * @return the number of files handed to {@link ContactImportService}, counting a file whose import failed
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
