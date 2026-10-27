/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_salesforce.trigger;

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

import com.mulesoft.examples.import_contacts_into_salesforce.client.UpstreamSystem;
import com.mulesoft.examples.import_contacts_into_salesforce.exception.UpstreamUnavailableException;
import com.mulesoft.examples.import_contacts_into_salesforce.service.ContactImportService;

/**
 * Trigger test of {@link ContactsFileTrigger}, the replacement of the {@code file:inbound-endpoint} of flow
 * {@code Contacts_to_SFDC} in {@code import-contacts-into-salesforce/src/main/app/contacts-to-SFDC.xml} (l.9):
 * {@code path="src/main/resources/input" moveToDirectory="src/main/resources/output" pollingFrequency="10000"}. The
 * original has no HTTP trigger, so this test stands in for its endpoint test: the original {@code contacts.csv}
 * dropped into the input directory is handed byte for byte to {@link ContactImportService} by one poll and then moved
 * to the move directory under its own name.
 *
 * <p>The flow declares no transaction and no exception strategy, so SC-5 {@link FileInboundPoller} logs a failed
 * import at ERROR and moves the file exactly as after a success. The import is therefore attempted once per file and
 * never retried: a Salesforce timeout cannot make the poll submit {@code sfdc:create} twice.
 *
 * <p>No Spring context is started; the wired path is covered by {@code ImportContactsToSfdcTest}. Only the import
 * service is mocked: the trigger and its SC-5 poller are real. Every directory lives under {@link TempDir}, so
 * {@code src/main/resources/input} and {@code src/main/resources/output} are never touched, and polls run on demand
 * through the single-run method, never by waiting.
 */
@ExtendWith(MockitoExtension.class)
class ContactsFileTriggerTest {

    private static final String CONTACTS_CSV = "contacts.csv";

    /** Size of the original {@code contacts.csv}: header and two rows, LF separated, no trailing LF. */
    private static final int CONTACTS_CSV_LENGTH = 113;

    @TempDir
    Path root;

    @Mock
    ContactImportService contactImportService;

    private Path input;

    private Path output;

    private ContactsFileTrigger trigger;

    private Logger pollerLogger;

    private ListAppender<ILoggingEvent> logged;

    @BeforeEach
    void createTriggerOverTempDirectories() throws IOException {
        input = Files.createDirectories(root.resolve("input"));
        output = Files.createDirectories(root.resolve("output"));
        trigger = new ContactsFileTrigger(contactImportService, input.toString(), output.toString());

        pollerLogger = (Logger) LoggerFactory.getLogger(FileInboundPoller.class.getName());
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
    @DisplayName("contacts.csv -> handed byte for byte to the import once, moved to the output directory")
    void pollOnceImportsContactsCsvAndMovesItToOutput() throws Exception {
        dropContacts();

        int count = trigger.pollOnce();

        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        verify(contactImportService, times(1)).importContacts(captor.capture());
        assertArrayEquals(contactsCsv(), captor.getValue());
        verifyNoMoreInteractions(contactImportService);
        assertMovedToOutput();
        assertEquals(1, count);
        assertTrue(errorEvents().isEmpty(), () -> "unexpected ERROR events: " + errorEvents());
    }

    @Test
    @DisplayName("Salesforce read timeout -> logged at ERROR, not retried, file still moved")
    void upstreamUnavailableFailureIsLoggedNotRetriedAndFileStillMoved() throws Exception {
        assertImportFailureIsLoggedOnceAndFileMoved(new UpstreamUnavailableException(UpstreamSystem.SALESFORCE,
                "Read timed out", new SocketTimeoutException("Read timed out")));
    }

    @Test
    @DisplayName("Any other import failure -> logged at ERROR, not retried, file still moved")
    void plainRuntimeFailureIsLoggedAndFileStillMoved() throws Exception {
        assertImportFailureIsLoggedOnceAndFileMoved(new RuntimeException("boom"));
    }

    @Test
    @DisplayName("Empty input directory -> nothing handed over, nothing moved")
    void emptyInputDirectoryReturnsZeroAndCallsNothing() throws IOException {
        assertEquals(0, trigger.pollOnce());

        verifyNoInteractions(contactImportService);
        assertEquals(List.of(), filesIn(output));
        assertEquals(input, trigger.inputDirectory());
        assertEquals(output, trigger.moveToDirectory());
    }

    @Test
    @DisplayName("poll() is scheduled with the original 10000 ms fixed delay and runs the same single poll")
    void pollIsScheduledAtOriginalPollingFrequency() throws Exception {
        Method poll = ContactsFileTrigger.class.getMethod("poll");
        Scheduled scheduled = poll.getAnnotation(Scheduled.class);
        assertNotNull(scheduled);
        assertEquals("${contacts.polling-frequency:10000}", scheduled.fixedDelayString());
        assertEquals("", scheduled.fixedRateString());
        assertEquals("", scheduled.cron());
        assertEquals(int.class, ContactsFileTrigger.class.getMethod("pollOnce").getReturnType());

        dropContacts();

        trigger.poll();

        verify(contactImportService, times(1)).importContacts(any());
        verifyNoMoreInteractions(contactImportService);
        assertMovedToOutput();
    }

    /**
     * Drops {@code contacts.csv}, lets the import fail with {@code failure} and asserts the default-strategy outcome:
     * the poll does not throw, the import was attempted exactly once, the file was moved rather than kept or deleted,
     * and the failure was logged at ERROR naming the file and the exception type. The poll's count after a handled
     * failure is the poller's own contract and is asserted in {@code FileInboundPollerTest}.
     */
    private void assertImportFailureIsLoggedOnceAndFileMoved(RuntimeException failure) throws Exception {
        doThrow(failure).when(contactImportService).importContacts(any());
        dropContacts();

        assertDoesNotThrow(() -> trigger.pollOnce());

        verify(contactImportService, times(1)).importContacts(any());
        verifyNoMoreInteractions(contactImportService);
        assertMovedToOutput();
        List<ILoggingEvent> errors = errorEvents();
        assertTrue(errors.stream().anyMatch(event -> event.getFormattedMessage().contains(CONTACTS_CSV)
                        && event.getFormattedMessage().contains(failure.getClass().getName())),
                () -> "no ERROR event naming " + CONTACTS_CSV + " and " + failure.getClass().getName() + ": "
                        + logged.list);
    }

    /** Places the original {@code contacts.csv} in the input directory, as the original IT dispatched it there. */
    private void dropContacts() throws IOException {
        Files.write(input.resolve(CONTACTS_CSV), contactsCsv());
    }

    /** Asserts the output directory holds the original {@code contacts.csv} alone and the input directory is empty. */
    private void assertMovedToOutput() throws IOException {
        Path moved = output.resolve(CONTACTS_CSV);
        assertTrue(Files.isRegularFile(moved), () -> moved + " was not created");
        assertArrayEquals(contactsCsv(), Files.readAllBytes(moved));
        assertEquals(List.of(moved), filesIn(output));
        assertEquals(List.of(), filesIn(input), "files left in input");
    }

    private List<ILoggingEvent> errorEvents() {
        return logged.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
    }

    /** The main-resources {@code contacts.csv}, a byte copy of the original example's input. */
    private byte[] contactsCsv() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/" + CONTACTS_CSV)) {
            assertNotNull(in, "classpath resource /" + CONTACTS_CSV);
            byte[] csv = in.readAllBytes();
            assertEquals(CONTACTS_CSV_LENGTH, csv.length, "size of /" + CONTACTS_CSV);
            return csv;
        }
    }

    private static List<Path> filesIn(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(Files::isRegularFile).toList();
        }
    }
}
