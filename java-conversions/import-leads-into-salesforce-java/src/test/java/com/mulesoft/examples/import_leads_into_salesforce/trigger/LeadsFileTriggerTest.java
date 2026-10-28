/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_leads_into_salesforce.trigger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.mulesoft.examples.import_leads_into_salesforce.client.UpstreamSystem;
import com.mulesoft.examples.import_leads_into_salesforce.exception.UpstreamUnavailableException;
import com.mulesoft.examples.import_leads_into_salesforce.service.LeadImportBatchService;

/**
 * Trigger test of {@link LeadsFileTrigger}, the replacement of the {@code file:inbound-endpoint} in the
 * {@code batch:input} of job {@code CreateLeadsBatch} in
 * {@code import-leads-into-salesforce/src/main/app/import-leads-into-salesforce.xml} (l.11):
 * {@code path="src/main/resources/input" moveToDirectory="src/main/resources/output" pollingFrequency="10000"}. The
 * original has no HTTP trigger, so this test stands in for its endpoint test: the original {@code leads.csv} dropped
 * into the input directory is handed byte for byte to {@link LeadImportBatchService} by one poll and then moved to
 * the move directory under its own name.
 *
 * <p>The job's input endpoint declares no transaction, so SC-5 {@link FileInboundPoller} logs a failed import at
 * ERROR and moves the file exactly as after a success. The import is therefore attempted once per file and never
 * retried: a Salesforce timeout cannot make the poll submit {@code sfdc:create} twice.
 *
 * <p>No Spring context is started; the wired path is covered by {@code ImportLeadsToSfdcTest}. Only the batch service
 * is mocked: the trigger and its SC-5 poller are real. Every directory lives under {@link TempDir}, so
 * {@code src/main/resources/input} and {@code src/main/resources/output} are never touched, and polls run on demand
 * through the single-run method, never by waiting.
 */
@ExtendWith(MockitoExtension.class)
class LeadsFileTriggerTest {

    private static final String LEADS_CSV = "leads.csv";

    /** Size of the original {@code leads.csv}: header and two rows, CR separated, no trailing CR. */
    private static final int LEADS_CSV_LENGTH = 176;

    /**
     * The project's package root. Capturing there rather than on the poller's own logger lets every event of the
     * trigger and its poller reach the appender through additivity, whichever class logs it.
     */
    private static final String PACKAGE_ROOT_LOGGER = "com.mulesoft.examples.import_leads_into_salesforce";

    @TempDir
    Path root;

    @Mock
    LeadImportBatchService leadImportBatchService;

    private Path input;

    private Path output;

    private LeadsFileTrigger trigger;

    private Logger packageLogger;

    private ListAppender<ILoggingEvent> logged;

    @BeforeEach
    void createTriggerOverTempDirectories() throws IOException {
        input = Files.createDirectories(root.resolve("input"));
        output = Files.createDirectories(root.resolve("output"));
        trigger = new LeadsFileTrigger(leadImportBatchService, input.toString(), output.toString());

        packageLogger = (Logger) LoggerFactory.getLogger(PACKAGE_ROOT_LOGGER);
        logged = new ListAppender<>();
        logged.start();
        packageLogger.addAppender(logged);
    }

    @AfterEach
    void detachLogCapture() {
        packageLogger.detachAppender(logged);
        logged.stop();
    }

    @Test
    @DisplayName("leads.csv -> handed byte for byte to the batch job once, moved to the output directory")
    void leadsCsvIsImportedAndMovedToOutputDirectory() throws Exception {
        dropLeads();

        int count = trigger.pollOnce();

        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        verify(leadImportBatchService, times(1)).importLeads(captor.capture());
        assertArrayEquals(leadsCsv(), captor.getValue());
        verifyNoMoreInteractions(leadImportBatchService);
        assertMovedToOutput();
        assertEquals(1, count);
        assertTrue(errorEvents().isEmpty(), () -> "unexpected ERROR events: " + errorEvents());
    }

    @Test
    @DisplayName("Salesforce read timeout -> logged at ERROR, not retried, file still moved")
    void upstreamUnavailableIsLoggedNotRetriedAndFileStillMoved() throws Exception {
        assertImportFailureIsLoggedOnceAndFileMoved(new UpstreamUnavailableException(UpstreamSystem.SALESFORCE,
                "Read timed out", new SocketTimeoutException("Read timed out")));
    }

    @Test
    @DisplayName("Any other import failure -> logged at ERROR, not retried, file still moved")
    void unexpectedServiceFailureIsLoggedAndFileStillMoved() throws Exception {
        assertImportFailureIsLoggedOnceAndFileMoved(new RuntimeException("boom"));
    }

    @Test
    @DisplayName("Empty input directory -> nothing handed over, nothing moved")
    void emptyInputDirectoryProcessesNothing() throws IOException {
        assertEquals(0, trigger.pollOnce());

        verifyNoInteractions(leadImportBatchService);
        assertEquals(List.of(), filesIn(output));
        assertEquals(input, trigger.inputDirectory());
        assertEquals(output, trigger.moveToDirectory());
    }

    @Test
    @DisplayName("poll() is scheduled with the original 10000 ms polling frequency as a configurable fixed delay")
    void pollIsScheduledWithConfiguredFixedDelay() throws NoSuchMethodException {
        Method poll = LeadsFileTrigger.class.getMethod("poll");
        Scheduled scheduled = poll.getAnnotation(Scheduled.class);
        assertNotNull(scheduled, "poll() carries no @Scheduled");
        assertEquals("${leads.polling-frequency:10000}", scheduled.fixedDelayString());
        assertEquals("", scheduled.fixedRateString());
        assertEquals("", scheduled.cron());
        assertEquals(int.class, LeadsFileTrigger.class.getMethod("pollOnce").getReturnType());
    }

    @Test
    @DisplayName("poll() runs the same single poll as pollOnce()")
    void pollProcessesDroppedFileLikePollOnce() throws Exception {
        dropLeads();

        trigger.poll();

        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        verify(leadImportBatchService, times(1)).importLeads(captor.capture());
        assertArrayEquals(leadsCsv(), captor.getValue());
        verifyNoMoreInteractions(leadImportBatchService);
        assertMovedToOutput();
    }

    /**
     * Drops {@code leads.csv}, lets the import fail with {@code failure} and asserts the default-strategy outcome:
     * the poll does not throw and still counts the file, the import was attempted exactly once, the file was moved
     * rather than kept or deleted, and the failure was logged at ERROR naming the file and the exception type.
     */
    private void assertImportFailureIsLoggedOnceAndFileMoved(RuntimeException failure) throws Exception {
        doThrow(failure).when(leadImportBatchService).importLeads(any());
        dropLeads();

        int count = assertDoesNotThrow(() -> trigger.pollOnce());

        verify(leadImportBatchService, times(1)).importLeads(any());
        verifyNoMoreInteractions(leadImportBatchService);
        assertMovedToOutput();
        assertEquals(1, count, "a file whose import failed is still counted as handed over");
        List<ILoggingEvent> errors = errorEvents();
        assertTrue(errors.stream().anyMatch(event -> event.getFormattedMessage().contains(LEADS_CSV)
                        && event.getFormattedMessage().contains(failure.getClass().getName())),
                () -> "no ERROR event naming " + LEADS_CSV + " and " + failure.getClass().getName() + ": "
                        + logged.list);
    }

    /** Places the original {@code leads.csv} in the input directory, as the original IT dispatched it there. */
    private void dropLeads() throws IOException {
        Files.write(input.resolve(LEADS_CSV), leadsCsv());
    }

    /** Asserts the output directory holds the original {@code leads.csv} alone and the input directory is empty. */
    private void assertMovedToOutput() throws IOException {
        Path moved = output.resolve(LEADS_CSV);
        assertTrue(Files.isRegularFile(moved), () -> moved + " was not created");
        assertArrayEquals(leadsCsv(), Files.readAllBytes(moved));
        assertEquals(List.of(moved), filesIn(output));
        assertEquals(List.of(), filesIn(input), "files left in input");
    }

    private List<ILoggingEvent> errorEvents() {
        return logged.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
    }

    /** The main-resources {@code leads.csv}, a byte copy of the original example's input. */
    private byte[] leadsCsv() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/" + LEADS_CSV)) {
            assertNotNull(in, "classpath resource /" + LEADS_CSV);
            byte[] csv = in.readAllBytes();
            assertEquals(LEADS_CSV_LENGTH, csv.length, "size of /" + LEADS_CSV);
            return csv;
        }
    }

    private static List<Path> filesIn(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(Files::isRegularFile).toList();
        }
    }
}
