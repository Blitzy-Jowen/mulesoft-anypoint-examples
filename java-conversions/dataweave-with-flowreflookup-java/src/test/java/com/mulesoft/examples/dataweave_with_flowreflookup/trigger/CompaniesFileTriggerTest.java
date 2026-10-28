/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.dataweave_with_flowreflookup.trigger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.mulesoft.examples.dataweave_with_flowreflookup.client.UpstreamSystem;
import com.mulesoft.examples.dataweave_with_flowreflookup.exception.UpstreamUnavailableException;
import com.mulesoft.examples.dataweave_with_flowreflookup.service.AccountImportService;

/**
 * Trigger test of {@link CompaniesFileTrigger}, the replacement of the {@code file:inbound-endpoint} of flow
 * {@code CreateNewSalesforceAccountFlow} in {@code dataweave-with-flowreflookup/src/main/app/dataweave-with-flowref.xml}
 * (l.7), which polls its {@code path} every {@code pollingFrequency="10000"} ms and moves each file to its
 * {@code moveToDirectory}. The original has no HTTP trigger, so this test stands in for its endpoint test: the original {@code companies.csv}
 * dropped into the input directory is handed byte for byte to {@link AccountImportService} by one poll and then moved
 * to the move directory under its own name.
 *
 * <p>The flow declares no transaction and no exception strategy, so SC-5 {@link FileInboundPoller} logs a failed
 * import at ERROR and moves the file exactly as after a success. The import is therefore attempted once per file and
 * never retried: a Salesforce timeout cannot make the poll submit the Account {@code sfdc:create} twice.
 *
 * <p>No Spring context is started. Only the import service is mocked: the trigger and its SC-5 poller are real. Every
 * directory lives under {@link TempDir}, so the project's own input and output directories are never touched, and
 * polls run on demand through the single-run method, never by waiting.
 */
@ExtendWith(MockitoExtension.class)
class CompaniesFileTriggerTest {

    private static final String COMPANIES_CSV = "companies.csv";

    /** Size of the original {@code companies.csv}: header and two rows, LF separated, no trailing LF. */
    private static final int COMPANIES_CSV_LENGTH = 196;

    /** MD5 of the original {@code companies.csv}, so a rewritten or re-encoded copy fails the fixture guard. */
    private static final String COMPANIES_CSV_MD5 = "c0db4f966ef6364ec508f455185749f7";

    @TempDir
    Path tempDir;

    @Mock
    AccountImportService accountImportService;

    private Path inputDir;

    private Path outputDir;

    private CompaniesFileTrigger trigger;

    private Logger pollerLogger;

    private ListAppender<ILoggingEvent> logged;

    @BeforeEach
    void createTriggerOverTempDirectories() throws IOException {
        inputDir = Files.createDirectories(tempDir.resolve("input"));
        // Left absent on purpose: the poller creates the move directory, as Mule's file connector did.
        outputDir = tempDir.resolve("output");
        trigger = new CompaniesFileTrigger(accountImportService, inputDir.toString(), outputDir.toString(), "10000");

        pollerLogger = (Logger) LoggerFactory.getLogger(FileInboundPoller.class);
        logged = new ListAppender<>();
        logged.start();
        pollerLogger.addAppender(logged);
    }

    @AfterEach
    void detachLogCapture() {
        pollerLogger.detachAppender(logged);
        logged.stop();
    }

    @Test
    @DisplayName("companies.csv -> handed byte for byte to the import once, moved to the output directory")
    void pollOnceImportsCompaniesCsvAndMovesFile() throws Exception {
        dropCompaniesCsv();

        int count = trigger.pollOnce();

        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        verify(accountImportService, times(1)).importAccounts(captor.capture());
        assertArrayEquals(companiesCsv(), captor.getValue());
        verifyNoMoreInteractions(accountImportService);
        assertMovedToOutput();
        assertEquals(1, count);
        assertTrue(errorEvents().isEmpty(), () -> "unexpected ERROR events: " + errorEvents());
    }

    @Test
    @DisplayName("Salesforce connect timeout -> logged at ERROR, not retried, file still moved")
    void upstreamUnavailableIsLoggedNotRetriedAndFileStillMoved() throws Exception {
        assertImportFailureIsLoggedOnceAndFileMoved(new UpstreamUnavailableException(UpstreamSystem.SALESFORCE,
                "connect timed out", new SocketTimeoutException("connect timed out")));
    }

    @Test
    @DisplayName("Any other import failure -> logged at ERROR, not retried, file still moved")
    void unexpectedRuntimeExceptionIsLoggedNotRetriedAndFileStillMoved() throws Exception {
        assertImportFailureIsLoggedOnceAndFileMoved(
                new RuntimeException("INVALID_TYPE: sObject type 'Account' is not supported"));
    }

    @Test
    @DisplayName("Empty input directory -> nothing handed over, nothing moved")
    void emptyInputDirectoryReturnsZeroAndCallsNothing() throws IOException {
        assertEquals(0, trigger.pollOnce());

        verifyNoInteractions(accountImportService);
        assertTrue(Files.isDirectory(outputDir), "move directory not created by the poll");
        assertEquals(List.of(), filesIn(outputDir));
        assertEquals(inputDir, trigger.inputDirectory());
        assertEquals(outputDir, trigger.moveToDirectory());
        assertTrue(errorEvents().isEmpty(), () -> "unexpected ERROR events: " + errorEvents());
    }

    @Test
    @DisplayName("poll() is scheduled with the original 10000 ms fixed delay and runs the same single poll")
    void pollIsScheduledAtOriginalPollingFrequency() throws Exception {
        Method poll = CompaniesFileTrigger.class.getMethod("poll");
        Scheduled scheduled = poll.getAnnotation(Scheduled.class);
        assertNotNull(scheduled, "poll() is not @Scheduled");
        // Mule's pollingFrequency is the pause between polls, hence a fixed delay rather than a fixed rate.
        assertEquals("${companies.polling-frequency:10000}", scheduled.fixedDelayString());
        assertEquals("", scheduled.fixedRateString());
        assertEquals("", scheduled.cron());
        assertEquals(int.class, CompaniesFileTrigger.class.getMethod("pollOnce").getReturnType());

        dropCompaniesCsv();

        trigger.poll();

        verify(accountImportService, times(1)).importAccounts(any());
        verifyNoMoreInteractions(accountImportService);
        assertMovedToOutput();
    }

    @ParameterizedTest(name = "companies.polling-frequency \"{0}\" -> start-up fails naming the key and the value")
    @ValueSource(strings = {"0", "-1", "PT0S", "PT-1S", "abc", ""})
    void nonPositiveOrUnreadablePollingFrequencyFailsNamingKeyAndValue(String frequency) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new CompaniesFileTrigger(accountImportService, inputDir.toString(), outputDir.toString(),
                        frequency));

        String message = failure.getMessage();
        assertNotNull(message, "the failure carries no message");
        assertTrue(message.contains("companies.polling-frequency"), () -> "key not named: " + message);
        assertTrue(message.contains("\"" + frequency + "\""), () -> "value not named: " + message);
        verifyNoInteractions(accountImportService);
    }

    @ParameterizedTest(name = "companies.polling-frequency \"{0}\" -> accepted, as Spring schedules it")
    @ValueSource(strings = {"1", "10000", "PT10S", "pt0.5s"})
    void positivePollingFrequencyIsAccepted(String frequency) {
        CompaniesFileTrigger accepted = assertDoesNotThrow(
                () -> new CompaniesFileTrigger(accountImportService, inputDir.toString(), outputDir.toString(),
                        frequency));

        assertEquals(inputDir, accepted.inputDirectory());
        assertEquals(outputDir, accepted.moveToDirectory());
    }

    /**
     * Drops {@code companies.csv}, lets the import fail with {@code failure} and asserts the default-strategy outcome:
     * the poll does not throw and counts the file as handed over, the import was attempted exactly once, the file was
     * moved rather than kept or deleted, and the failure was logged at ERROR naming the file and the class of the
     * exception and of each cause, without the exception's message and without the exception as a throwable, because
     * a Salesforce message can repeat record fields.
     */
    private void assertImportFailureIsLoggedOnceAndFileMoved(RuntimeException failure) throws Exception {
        doThrow(failure).when(accountImportService).importAccounts(any());
        dropCompaniesCsv();

        int count = assertDoesNotThrow(trigger::pollOnce);

        assertEquals(1, count, "a file whose import failed still counts as handed over");
        verify(accountImportService, times(1)).importAccounts(any());
        verifyNoMoreInteractions(accountImportService);
        assertMovedToOutput();

        List<ILoggingEvent> errors = errorEvents();
        assertEquals(1, errors.size(), () -> "expected one ERROR event: " + logged.list);
        ILoggingEvent error = errors.get(0);
        String message = error.getFormattedMessage();
        assertTrue(message.contains(COMPANIES_CSV), () -> "ERROR event does not name " + COMPANIES_CSV + ": " + message);
        for (String className : classNamesInCauseChain(failure)) {
            assertTrue(message.contains(className), () -> "ERROR event does not name " + className + ": " + message);
        }
        assertFalse(message.contains(failure.getMessage()), () -> "ERROR event discloses the message: " + message);
        assertNull(error.getThrowableProxy(), "the exception was handed to the logger as a throwable");
    }

    /** Places the original {@code companies.csv} in the input directory, as the original IT dispatched it there. */
    private void dropCompaniesCsv() throws Exception {
        Files.write(inputDir.resolve(COMPANIES_CSV), companiesCsv());
    }

    /** Asserts the output directory holds the original {@code companies.csv} alone and the input directory is empty. */
    private void assertMovedToOutput() throws Exception {
        Path moved = outputDir.resolve(COMPANIES_CSV);
        assertTrue(Files.isRegularFile(moved), () -> moved + " was not created");
        assertArrayEquals(companiesCsv(), Files.readAllBytes(moved));
        assertEquals(List.of(moved), filesIn(outputDir));
        assertFalse(Files.exists(inputDir.resolve(COMPANIES_CSV)), "companies.csv left in the input directory");
        assertEquals(List.of(), filesIn(inputDir), "files left in input");
    }

    private List<ILoggingEvent> errorEvents() {
        return logged.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
    }

    /**
     * The main-resources {@code companies.csv}, a byte copy of the original example's input, guarded by size and MD5
     * so the fixture is never silently rewritten, trimmed or re-encoded.
     */
    private static byte[] companiesCsv() throws Exception {
        try (InputStream in = CompaniesFileTriggerTest.class.getResourceAsStream("/" + COMPANIES_CSV)) {
            assertNotNull(in, "classpath resource /" + COMPANIES_CSV);
            byte[] csv = in.readAllBytes();
            assertEquals(COMPANIES_CSV_LENGTH, csv.length, "size of /" + COMPANIES_CSV);
            assertEquals(COMPANIES_CSV_MD5, HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(csv)),
                    "MD5 of /" + COMPANIES_CSV);
            return csv;
        }
    }

    private static List<String> classNamesInCauseChain(Throwable failure) {
        List<String> names = new ArrayList<>();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            names.add(current.getClass().getName());
        }
        return names;
    }

    private static List<Path> filesIn(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(Files::isRegularFile).toList();
        }
    }
}
