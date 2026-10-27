/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_ms_dynamics.trigger;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mulesoft.examples.import_contacts_into_ms_dynamics.service.DynamicsContactImportService;

/**
 * The inbound endpoint of flow {@code crm-create-account} ({@code import-contacts-into-ms-dynamics.xml}, l.7):
 * {@code <file:inbound-endpoint mimeType="application/csv" moveToDirectory="src/main/resources/output"
 * path="src/main/resources/input" pollingFrequency="10000" responseTimeout="10000"/>}, configured through
 * {@code contacts.input-dir}, {@code contacts.move-to-dir} and {@code contacts.polling-frequency}.
 *
 * <p>Every poll hands each file's bytes unchanged to {@link DynamicsContactImportService#importContacts(byte[])},
 * which runs the rest of the flow. Reading, the in-process mark, the move to {@code contacts.move-to-dir} under the
 * file's own name after success and after failure, and the ERROR log of a failed flow all belong to
 * {@link FileInboundPoller}, which reproduces the default Mule file connector the original relies on; a Dataverse
 * failure is therefore logged and never retried, and the file is still moved. The endpoint's {@code mimeType} and
 * {@code responseTimeout} are not carried: the service always reads the content as CSV, and a one-way file endpoint
 * sends no reply.
 */
@Component
public class ContactsFileTrigger {

    private final FileInboundPoller poller;

    /**
     * Creates the trigger.
     *
     * @param service   the flow body each polled file is handed to
     * @param inputDir  the endpoint's {@code path}; a relative path resolves against the working directory, as Mule
     *                  resolved it against the application directory
     * @param moveToDir the endpoint's {@code moveToDirectory}, resolved the same way
     */
    public ContactsFileTrigger(DynamicsContactImportService service,
                               @Value("${contacts.input-dir}") String inputDir,
                               @Value("${contacts.move-to-dir}") String moveToDir) {
        // The flow's result (the created contacts) went nowhere in the original, so the count is discarded here.
        this.poller = new FileInboundPoller(Path.of(inputDir), Path.of(moveToDir),
                file -> service.importContacts(file.content()));
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
     * @return the number of files handed to {@link DynamicsContactImportService}, counting a file whose import failed
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
