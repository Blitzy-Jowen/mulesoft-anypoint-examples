/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_leads_into_salesforce.trigger;

import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;

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

    /** The configuration key of the polling frequency. */
    static final String FREQUENCY_KEY = "leads.polling-frequency";

    /** The polling frequency placeholder: {@link #FREQUENCY_KEY}, defaulting to the original's 10000 ms. */
    static final String FREQUENCY = "${" + FREQUENCY_KEY + ":10000}";

    private final FileInboundPoller poller;

    /**
     * Creates the trigger.
     *
     * @param leadImportBatchService the batch job each polled file is handed to
     * @param inputDir               the endpoint's {@code path}; a relative path resolves against the working
     *                               directory, as Mule resolved it against the application directory
     * @param moveToDir              the endpoint's {@code moveToDirectory}, resolved the same way
     * @param pollingFrequency       the resolved {@link #FREQUENCY} the scheduled poll runs at
     * @throws IllegalArgumentException if {@code pollingFrequency} is unreadable, zero or negative, so that start-up
     *                                  fails with a message naming {@link #FREQUENCY_KEY} and the value
     */
    public LeadsFileTrigger(LeadImportBatchService leadImportBatchService,
                            @Value("${leads.input-dir}") String inputDir,
                            @Value("${leads.move-to-dir}") String moveToDir,
                            @Value(FREQUENCY) String pollingFrequency) {
        requirePositiveFrequency(FREQUENCY_KEY, pollingFrequency);
        // The job's result (its record counts) went nowhere in the original, so it is discarded here.
        this.poller = new FileInboundPoller(Path.of(inputDir), Path.of(moveToDir),
                file -> leadImportBatchService.importLeads(file.content()));
    }

    /**
     * Scheduled poll. A fixed delay keeps the polling frequency as the pause between the end of one poll and the
     * start of the next, as Mule's polling receiver waited.
     */
    @Scheduled(fixedDelayString = FREQUENCY)
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

    /**
     * Checks that a polling frequency is one {@link Scheduled#fixedDelayString()} can schedule. The check mirrors
     * how Spring 6.1 reads that string: an ISO-8601 duration when its first or second character is {@code P} or
     * {@code p}, otherwise a whole number of milliseconds, with no trimming. Spring 6.1.15 schedules
     * {@code "PT0.0001S"}, {@code "1"}, {@code "P1D"} and {@code "pt1s"}, but hands {@code "0"}, {@code "-5"},
     * {@code "PT0S"} and {@code "PT-1S"} to the executor, which rejects them with an
     * {@link IllegalArgumentException} that carries no message. Checking first makes start-up fail with a message
     * naming the key and the value.
     *
     * @param key   the configuration key the value was read from
     * @param value the resolved value
     * @throws IllegalArgumentException if the value is unreadable, zero or negative
     */
    static void requirePositiveFrequency(String key, String value) {
        Duration period;
        try {
            period = isDurationString(value) ? Duration.parse(value) : Duration.ofMillis(Long.parseLong(value));
        } catch (NumberFormatException | DateTimeParseException e) {
            throw invalidFrequency(key, value);
        }
        if (period.isZero() || period.isNegative()) {
            throw invalidFrequency(key, value);
        }
    }

    private static boolean isDurationString(String value) {
        return value != null && value.length() > 1 && (isP(value.charAt(0)) || isP(value.charAt(1)));
    }

    private static boolean isP(char c) {
        return c == 'P' || c == 'p';
    }

    private static IllegalArgumentException invalidFrequency(String key, String value) {
        return new IllegalArgumentException("Invalid " + key + " value \"" + value + "\": the polling frequency"
                + " must be a positive number of milliseconds or a positive ISO-8601 duration");
    }
}
