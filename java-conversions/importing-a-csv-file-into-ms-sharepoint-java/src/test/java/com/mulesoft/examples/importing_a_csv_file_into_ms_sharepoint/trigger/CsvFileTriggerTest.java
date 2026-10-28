/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.trigger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.HttpClientErrorException;

import com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.client.SharepointRestClient;
import com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.config.SharepointProperties;
import com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.service.SharepointImportService;

/**
 * Trigger test of {@link CsvFileTrigger}, the replacement of the {@code file:inbound-endpoint} of flow
 * {@code importing-a-csv-file-into-ms-sharepointFlow} ({@code importing-a-csv-file-into-ms-sharepoint.xml}, l.7:
 * {@code path="src/main/resources/input" moveToDirectory="src/main/resources/output" pollingFrequency="10000"}). The
 * original has no HTTP trigger, so this test stands in for its endpoint test (AAP 0.8.2): a file dropped into the
 * input directory is picked up by one poll, uploaded through the real {@link SharepointImportService} and moved.
 *
 * <p>The flow keeps the polled bytes as they are (l.8), creates {@code /Shared Documents/<csvImportFolder>} (l.10) and
 * adds the bytes there as {@code contacts.csv} with overwrite (l.12), whatever the polled file is called. It declares
 * no exception strategy, so a failed SharePoint step ends the flow through Mule's default strategy and SC-5
 * {@link FileInboundPoller} still moves the file: it is never retried and the poll never throws.
 *
 * <p>No Spring context is started; the end-to-end port of the original IT, over a WireMock SharePoint, is
 * {@code ImportingCsvFileIntoMSSharepointTest}. Only {@link SharepointRestClient} is mocked, so no request leaves the
 * JVM and the loopback URLs below are never contacted. Every directory lives under {@link TempDir}, one file is
 * dropped per test because SC-5 lists files unsorted, and polls run through the single-run method, never by waiting.
 */
class CsvFileTriggerTest {

    private static final String FOLDER = "/Shared Documents/test-folder";

    private static final String FILE_URL = "/Shared Documents/test-folder/contacts.csv";

    private static final String CONTACTS_CSV = "contacts.csv";

    /** The {@code csv-import.polling-frequency} {@code application.yml} sets: the original's 10000 ms. */
    private static final String POLLING_FREQUENCY = "10000";

    @TempDir
    Path tmp;

    private Path in;

    private Path out;

    private SharepointRestClient mockClient;

    private SharepointRestClient.SharepointSession session;

    private byte[] originalBytes;

    private CsvFileTrigger trigger;

    @BeforeEach
    void createTriggerOverTheRealImportService() throws IOException {
        in = Files.createDirectories(tmp.resolve("input"));
        out = Files.createDirectories(tmp.resolve("output"));
        mockClient = mock(SharepointRestClient.class);
        session = new SharepointRestClient.SharepointSession("rtFa=a; FedAuth=b", "digest");
        SharepointProperties props = new SharepointProperties("user@example.onmicrosoft.com", "pw",
                "http://127.0.0.1:9", "test-folder", "http://127.0.0.1:9/extSTS.srf");
        SharepointImportService service = new SharepointImportService(mockClient, props);
        trigger = new CsvFileTrigger(service, in.toString(), out.toString(), POLLING_FREQUENCY);
        originalBytes = originalContacts();
    }

    /**
     * Polled files and the bytes each must reach SharePoint with. The first case carries the original IT's assertion
     * ({@code ImportingCsvFileIntoMSSharepointIT}, l.104) that the uploaded content equals the local
     * {@code contacts.csv}; the others show that the target name stays {@code contacts.csv} whatever the input is
     * called, and that the content is sent as read, never parsed, empty and non-text content included.
     */
    static Stream<Arguments> polledFiles() throws IOException {
        byte[] contacts = originalContacts();
        return Stream.of(
                Arguments.of(CONTACTS_CSV, Named.of("the original contacts.csv", contacts)),
                Arguments.of("customers.csv", Named.of("the original contacts.csv", contacts)),
                Arguments.of("empty.csv", Named.of("no bytes", new byte[0])),
                Arguments.of("image.bin", Named.of("binary bytes 00 FF 10", new byte[] {0, (byte) 0xFF, 0x10})));
    }

    @ParameterizedTest(name = "{0} holding {1} -> uploaded unchanged as contacts.csv, moved under its own name")
    @MethodSource("polledFiles")
    void uploadsPolledBytesAsContactsCsvAndMovesTheFile(String inputName, byte[] content) throws IOException {
        when(mockClient.signIn()).thenReturn(session);
        Files.write(in.resolve(inputName), content);

        assertThat(trigger.pollOnce()).isEqualTo(1);

        InOrder inOrder = inOrder(mockClient);
        inOrder.verify(mockClient).signIn();
        inOrder.verify(mockClient).createFolder(session, FOLDER);
        inOrder.verify(mockClient).addFile(eq(session), eq(FILE_URL), aryEq(content), eq(true));
        verifyNoMoreInteractions(mockClient);
        assertThat(out.resolve(inputName)).hasBinaryContent(content);
        // Only the polled file reaches the move directory, under its own name: the remote name never leaks into it.
        assertThat(fileNames(out)).containsExactly(inputName);
        assertThat(fileNames(in)).isEmpty();
    }

    @Test
    @DisplayName("Folder create rejected with 403 -> no file add, the poll does not throw, the file is still moved")
    void folderCreateRejectedSkipsFileAddAndStillMovesTheFile() throws IOException {
        when(mockClient.signIn()).thenReturn(session);
        doThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders.EMPTY, null, null))
                .when(mockClient).createFolder(any(), anyString());
        Files.write(in.resolve(CONTACTS_CSV), originalBytes);

        int handed = assertDoesNotThrow(() -> trigger.pollOnce());

        // FileInboundPoller.pollOnce() counts a file whose handler threw: it was handed over, then consumed.
        assertThat(handed).isEqualTo(1);
        InOrder inOrder = inOrder(mockClient);
        inOrder.verify(mockClient).signIn();
        inOrder.verify(mockClient).createFolder(session, FOLDER);
        verify(mockClient, never()).addFile(any(), anyString(), any(), anyBoolean());
        verifyNoMoreInteractions(mockClient);
        assertMovedUnchanged(CONTACTS_CSV, originalBytes);
    }

    @Test
    @DisplayName("Sign-in fails -> no folder create, no file add, the poll does not throw, the file is still moved")
    void signInFailureSkipsEveryStepAndStillMovesTheFile() throws IOException {
        when(mockClient.signIn()).thenThrow(new IllegalStateException("sign-in failed"));
        Files.write(in.resolve(CONTACTS_CSV), originalBytes);

        int handed = assertDoesNotThrow(() -> trigger.pollOnce());

        assertThat(handed).isEqualTo(1);
        verify(mockClient).signIn();
        verifyNoMoreInteractions(mockClient);
        assertMovedUnchanged(CONTACTS_CSV, originalBytes);
    }

    @Test
    @DisplayName("Empty input directory -> nothing handed over, SharePoint never called, nothing moved")
    void emptyInputDirectoryReturnsZeroWithoutSharepointCalls() throws IOException {
        assertThat(trigger.pollOnce()).isZero();

        verifyNoInteractions(mockClient);
        assertThat(fileNames(out)).isEmpty();
    }

    @Test
    @DisplayName("poll(), the scheduled entry point, runs the same single poll")
    void pollDelegatesToTheSingleRunPoll() throws IOException {
        when(mockClient.signIn()).thenReturn(session);
        Files.write(in.resolve(CONTACTS_CSV), originalBytes);

        trigger.poll();

        verify(mockClient).addFile(eq(session), eq(FILE_URL), aryEq(originalBytes), eq(true));
        assertMovedUnchanged(CONTACTS_CSV, originalBytes);
    }

    @Test
    @DisplayName("poll() is scheduled with the original pollingFrequency of 10000 ms as its default")
    void pollIsScheduledWithTheOriginalPollingFrequency() throws NoSuchMethodException {
        Scheduled scheduled = CsvFileTrigger.class.getMethod("poll").getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString()).isEqualTo("${csv-import.polling-frequency:10000}");
    }

    @ParameterizedTest(name = "csv-import.polling-frequency \"{0}\" -> start-up fails naming the key and the value")
    @ValueSource(strings = {"0", "-1", "PT0S", "PT-1S", "abc", ""})
    void unschedulableFrequencyFailsNamingKeyAndValue(String frequency) {
        SharepointImportService importService = mock(SharepointImportService.class);

        assertThatThrownBy(() -> new CsvFileTrigger(importService, in.toString(), out.toString(), frequency))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("csv-import.polling-frequency")
                .hasMessageContaining("\"" + frequency + "\"");
    }

    @ParameterizedTest(name = "csv-import.polling-frequency \"{0}\" -> the trigger is created")
    @ValueSource(strings = {"1", "1000", "PT10S", "pt0.5s"})
    void schedulableFrequencyCreatesTheTrigger(String frequency) {
        SharepointImportService importService = mock(SharepointImportService.class);

        CsvFileTrigger created = assertDoesNotThrow(
                () -> new CsvFileTrigger(importService, in.toString(), out.toString(), frequency));

        assertThat(created.pollOnce()).isZero();
        verifyNoInteractions(importService);
    }

    /** Asserts the SC-5 disposition: the file sits in the move directory with its bytes unchanged, the input is empty. */
    private void assertMovedUnchanged(String name, byte[] content) throws IOException {
        assertThat(out.resolve(name)).hasBinaryContent(content);
        assertThat(fileNames(in)).isEmpty();
    }

    /**
     * The original input file, read from {@code src/main/resources/contacts.csv} on the classpath, so the test drops
     * exactly the bytes the original IT dispatched to the input directory.
     */
    private static byte[] originalContacts() throws IOException {
        byte[] contacts;
        try (InputStream resource = CsvFileTriggerTest.class.getResourceAsStream("/" + CONTACTS_CSV)) {
            assertThat(resource).as("classpath resource /%s", CONTACTS_CSV).isNotNull();
            contacts = resource.readAllBytes();
        }
        assertThat(contacts).hasSize(235);
        assertThat(new String(contacts, UTF_8)).startsWith("firstname;surname;phone;email");
        return contacts;
    }

    private static List<String> fileNames(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.map(entry -> entry.getFileName().toString()).toList();
        }
    }
}
