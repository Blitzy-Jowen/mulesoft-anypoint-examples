/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.trigger;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.service.SharepointImportService;

/**
 * The inbound endpoint of the original flow {@code importing-a-csv-file-into-ms-sharepointFlow}
 * ({@code importing-a-csv-file-into-ms-sharepoint.xml}, l.7):
 * {@code <file:inbound-endpoint path="src/main/resources/input" moveToDirectory="src/main/resources/output"
 * pollingFrequency="10000"/>}.
 *
 * <p>Every {@code csv-import.polling-frequency} milliseconds the input directory is polled and each file's bytes are
 * handed unchanged to {@link SharepointImportService#importFile(byte[])}. Reading, the in-process mark, the ERROR log
 * of a failed import and the move to {@code csv-import.move-to-dir} after success and after failure all belong to
 * {@link FileInboundPoller}, which reproduces the default Mule file connector the original relies on.
 */
@Component
public class CsvFileTrigger {

    private final FileInboundPoller poller;

    /**
     * Creates the trigger.
     *
     * @param importService the flow body each polled file is handed to
     * @param inputDir      the endpoint's {@code path}
     * @param moveToDir     the endpoint's {@code moveToDirectory}
     */
    public CsvFileTrigger(SharepointImportService importService,
                          @Value("${csv-import.input-dir}") String inputDir,
                          @Value("${csv-import.move-to-dir}") String moveToDir) {
        this.poller = new FileInboundPoller(Path.of(inputDir), Path.of(moveToDir),
                file -> importService.importFile(file.content()));
    }

    /**
     * Scheduled poll. A fixed delay keeps the original polling frequency as the pause between the end of one poll
     * and the start of the next.
     */
    @Scheduled(fixedDelayString = "${csv-import.polling-frequency:10000}")
    public void poll() {
        pollOnce();
    }

    /**
     * Runs one poll on demand. It shares the scheduled poll's {@link FileInboundPoller}, so a file in process in one
     * run is never handed over by the other.
     *
     * @return the number of files handed to {@link SharepointImportService}, counting a file whose import failed
     */
    public int pollOnce() {
        return poller.pollOnce();
    }
}
