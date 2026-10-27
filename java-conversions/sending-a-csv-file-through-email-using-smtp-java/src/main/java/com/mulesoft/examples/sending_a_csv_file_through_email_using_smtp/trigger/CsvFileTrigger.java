/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.trigger;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.service.CsvEmailService;

/**
 * The inbound endpoint of flow {@code csv-to-smtpFlow} ({@code csv-to-smtp.xml}, l.5):
 * {@code <file:inbound-endpoint path="..." responseTimeout="10000"/>}, whose {@code path} is the default of
 * {@code csv-email.input-dir}.
 *
 * <p>The endpoint declares no {@code pollingFrequency}, so Mule's default of 1000 ms applies, which
 * {@code csv-email.polling-frequency} keeps. It declares no {@code moveToDirectory}, so each processed file is
 * deleted. {@code responseTimeout} has no effect on a one-way file source and is not carried. Every poll hands each
 * file's bytes unchanged to {@link CsvEmailService#process(byte[])}. Reading, the in-process mark, the deletion after
 * success and after failure, and the ERROR log of a failed flow all belong to {@link FileInboundPoller}, which
 * reproduces the default Mule file connector the original relies on.
 */
@Component
public class CsvFileTrigger {

    private final FileInboundPoller poller;

    /**
     * Creates the trigger.
     *
     * @param csvEmailService the flow body each polled file is handed to
     * @param inputDir        the endpoint's {@code path}; a relative path resolves against the working directory,
     *                        as Mule resolved it against the application directory
     */
    public CsvFileTrigger(CsvEmailService csvEmailService,
                          @Value("${csv-email.input-dir}") String inputDir) {
        // No move directory: the endpoint declares no moveToDirectory, so the poller deletes each processed file.
        // The mailed order lines process returns are not needed here and are discarded.
        this.poller = new FileInboundPoller(Path.of(inputDir), null,
                file -> csvEmailService.process(file.content()));
    }

    /**
     * Scheduled poll. A fixed delay keeps the polling frequency as the pause between the end of one poll and the
     * start of the next, as Mule's polling receiver waited.
     */
    @Scheduled(fixedDelayString = "${csv-email.polling-frequency:1000}")
    public void poll() {
        pollOnce();
    }

    /**
     * Runs one poll on demand. It shares the scheduled poll's {@link FileInboundPoller}, so a file in process in one
     * run is never handed over by the other.
     *
     * @return the number of files handed to {@link CsvEmailService}, counting a file whose processing failed
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
}
