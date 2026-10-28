/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_ms_dynamics.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.mulesoft.examples.import_contacts_into_ms_dynamics.client.UpstreamSystem;
import com.mulesoft.examples.import_contacts_into_ms_dynamics.exception.UpstreamUnavailableException;
import com.mulesoft.examples.import_contacts_into_ms_dynamics.service.DynamicsContactImportService;

/**
 * Trigger test of {@link ContactsFileTrigger}, the replacement of the {@code file:inbound-endpoint} of flow
 * {@code crm-create-account} in {@code import-contacts-into-ms-dynamics/src/main/app/import-contacts-into-ms-dynamics.xml}
 * (l.7): {@code path="src/main/resources/input" moveToDirectory="src/main/resources/output" pollingFrequency="10000"}.
 * The original has no HTTP trigger, so this test stands in for its endpoint test: the original {@code contacts.csv}
 * dropped into the input directory is handed byte for byte to {@link DynamicsContactImportService} by one poll and
 * then moved to the move directory under its own name.
 *
 * <p>The flow declares no transaction and no exception strategy, so SC-5 {@link FileInboundPoller} logs a failed
 * import at ERROR and moves the file exactly as after a success. The import is therefore attempted once per file and
 * never retried: a Dataverse timeout or connectivity failure cannot make a later poll submit the contact creates
 * again.
 *
 * <p>No Spring context is started. Only the import service is mocked: the trigger and its SC-5 poller are real.
 * Every directory lives under {@link TempDir}, so {@code src/main/resources/input} and
 * {@code src/main/resources/output} are never touched, and polls run on demand through the single-run method, never
 * by waiting.
 */
@ExtendWith(MockitoExtension.class)
class ContactsFileTriggerTest {

    private static final String CONTACTS_CSV = "contacts.csv";

    /** Size of the original {@code contacts.csv}: header and two rows, LF separated, with a trailing LF. */
    private static final int CONTACTS_CSV_LENGTH = 114;

    @TempDir
    Path tempDir;

    @Mock
    DynamicsContactImportService service;

    private Path in;

    private Path out;

    private ContactsFileTrigger trigger;

    private Logger pollerLogger;

    private ListAppender<ILoggingEvent> logged;

    @BeforeEach
    void createTriggerOverTempDirectories() throws IOException {
        in = Files.createDirectories(tempDir.resolve("input"));
        out = Files.createDirectories(tempDir.resolve("output"));
        trigger = new ContactsFileTrigger(service, in.toString(), out.toString());

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
    @DisplayName("contacts.csv -> handed byte for byte to the import once, moved to the output directory")
    void pollOnceImportsContactsCsvAndMovesFile() throws Exception {
        when(service.importContacts(any(byte[].class))).thenReturn(2);
        dropContacts();

        assertThat(trigger.pollOnce()).isEqualTo(1);

        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        verify(service, times(1)).importContacts(captor.capture());
        assertThat(captor.getValue()).isEqualTo(contactsCsv());
        verifyNoMoreInteractions(service);
        assertMovedToOutput();
        assertThat(errorEvents()).isEmpty();
    }

    /**
     * Lets the import fail with {@code failure} and asserts the default-strategy outcome: the poll does not throw and
     * counts the file as handed over, the import was attempted exactly once, the file was moved rather than kept or
     * deleted, the failure was logged at ERROR naming the file and the exception type, and a second poll finds
     * nothing to hand over.
     */
    @ParameterizedTest(name = "{0} -> logged at ERROR, not retried, file still moved")
    @MethodSource("importFailures")
    void importFailureIsLoggedNotRetriedAndFileStillMoved(String description, RuntimeException failure)
            throws Exception {
        when(service.importContacts(any(byte[].class))).thenThrow(failure);
        dropContacts();

        AtomicInteger handed = new AtomicInteger(-1);
        assertThatCode(() -> handed.set(trigger.pollOnce())).doesNotThrowAnyException();

        assertThat(handed).hasValue(1);
        verify(service, times(1)).importContacts(any());
        assertMovedToOutput();
        assertThat(errorEvents())
                .as("ERROR event naming %s and %s", CONTACTS_CSV, failure.getClass().getName())
                .anySatisfy(event -> assertThat(event.getFormattedMessage())
                        .contains(CONTACTS_CSV)
                        .contains(failure.getClass().getName()));

        assertThat(trigger.pollOnce()).isZero();
        verifyNoMoreInteractions(service);
        assertMovedToOutput();
    }

    /**
     * The two failure kinds a poll can meet: the SC-9 connectivity failure, which the client's executor never
     * retries, and any other import failure, which stands for malformed input and every other error of the flow.
     */
    static Stream<Arguments> importFailures() {
        return Stream.of(
                Arguments.of("Dataverse unreachable (UpstreamUnavailableException)",
                        new UpstreamUnavailableException(UpstreamSystem.MICROSOFT_DYNAMICS_365,
                                "Dataverse unreachable", new ConnectException())),
                Arguments.of("Unexpected import failure (RuntimeException)",
                        new RuntimeException("unexpected failure")));
    }

    @Test
    @DisplayName("Empty input directory -> nothing handed over, nothing moved")
    void emptyInputDirectoryReturnsZero() throws IOException {
        assertThat(trigger.pollOnce()).isZero();

        verifyNoInteractions(service);
        assertThat(filesIn(out)).isEmpty();
        assertThat(trigger.inputDirectory()).isEqualTo(in);
        assertThat(trigger.moveToDirectory()).isEqualTo(out);
    }

    @Test
    @DisplayName("poll() is scheduled with the original 10000 ms fixed delay and runs the same single poll")
    void pollIsScheduledWithOriginalPollingFrequency() throws Exception {
        Method poll = ContactsFileTrigger.class.getMethod("poll");
        Scheduled scheduled = poll.getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString()).isEqualTo("${contacts.polling-frequency:10000}");
        assertThat(scheduled.fixedRateString()).isEmpty();
        assertThat(scheduled.cron()).isEmpty();
        assertThat(ContactsFileTrigger.class.getMethod("pollOnce").getReturnType()).isEqualTo(int.class);

        dropContacts();

        trigger.poll();

        verify(service, times(1)).importContacts(any());
        verifyNoMoreInteractions(service);
        assertMovedToOutput();
    }

    /** Places the original {@code contacts.csv} in the input directory, as the original IT dispatched it there. */
    private void dropContacts() throws IOException {
        Files.write(in.resolve(CONTACTS_CSV), contactsCsv());
    }

    /** Asserts the output directory holds the original {@code contacts.csv} alone and the input directory is empty. */
    private void assertMovedToOutput() throws IOException {
        Path moved = out.resolve(CONTACTS_CSV);
        assertThat(moved).isRegularFile().hasBinaryContent(contactsCsv());
        assertThat(filesIn(out)).containsExactly(moved);
        assertThat(in.resolve(CONTACTS_CSV)).doesNotExist();
        assertThat(in).isEmptyDirectory();
    }

    private List<ILoggingEvent> errorEvents() {
        return logged.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
    }

    /** The main-resources {@code contacts.csv}, a byte copy of the original example's input. */
    private byte[] contactsCsv() throws IOException {
        try (InputStream csv = getClass().getResourceAsStream("/" + CONTACTS_CSV)) {
            assertThat(csv).as("classpath resource /%s", CONTACTS_CSV).isNotNull();
            byte[] bytes = csv.readAllBytes();
            assertThat(bytes).as("size of /%s", CONTACTS_CSV).hasSize(CONTACTS_CSV_LENGTH);
            return bytes;
        }
    }

    private static List<Path> filesIn(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(Files::isRegularFile).toList();
        }
    }
}
