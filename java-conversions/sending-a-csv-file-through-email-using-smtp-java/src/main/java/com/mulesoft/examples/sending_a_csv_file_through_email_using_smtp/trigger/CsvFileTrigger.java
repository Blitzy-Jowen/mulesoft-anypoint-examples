/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.trigger;

import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;

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

    /** The configuration key of the polling frequency. */
    static final String FREQUENCY_KEY = "csv-email.polling-frequency";

    /** The polling frequency placeholder: {@link #FREQUENCY_KEY}, defaulting to Mule's 1000 ms. */
    static final String FREQUENCY = "${" + FREQUENCY_KEY + ":1000}";

    private final FileInboundPoller poller;

    /**
     * Creates the trigger.
     *
     * @param csvEmailService  the flow body each polled file is handed to
     * @param inputDir         the endpoint's {@code path}; a relative path resolves against the working directory,
     *                         as Mule resolved it against the application directory
     * @param pollingFrequency the resolved {@link #FREQUENCY} the scheduled poll runs at
     * @throws IllegalArgumentException if {@code pollingFrequency} is unreadable, zero or negative, so that start-up
     *                                  fails with a message naming {@link #FREQUENCY_KEY} and the value
     */
    public CsvFileTrigger(CsvEmailService csvEmailService,
                          @Value("${csv-email.input-dir}") String inputDir,
                          @Value(FREQUENCY) String pollingFrequency) {
        requirePositiveFrequency(FREQUENCY_KEY, pollingFrequency);
        // No move directory: the endpoint declares no moveToDirectory, so the poller deletes each processed file.
        // The mailed order lines process returns are not needed here and are discarded.
        this.poller = new FileInboundPoller(Path.of(inputDir), null,
                file -> csvEmailService.process(file.content()));
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
