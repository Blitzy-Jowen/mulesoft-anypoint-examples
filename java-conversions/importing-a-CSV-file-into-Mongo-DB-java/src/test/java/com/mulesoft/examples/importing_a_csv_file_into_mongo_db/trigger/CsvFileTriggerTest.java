/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_a_csv_file_into_mongo_db.trigger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.annotation.Scheduled;

import com.mulesoft.examples.importing_a_csv_file_into_mongo_db.service.CustomerImportService;

/**
 * Trigger test of {@link CsvFileTrigger}, the replacement of the only inbound endpoint of flow
 * {@code csv-to-mongodbFlow1} in {@code importing-a-CSV-file-into-Mongo-DB/src/main/app/csv-to-mongodb.xml} (l.9),
 * {@code <file:inbound-endpoint path="src/main/resources/input" responseTimeout="10000"/>}. The original has no HTTP
 * listener, so this test stands in for its endpoint test (AAP 0.8.2): one happy path and two failure cases, an
 * unreachable MongoDB and a malformed CSV.
 *
 * <p>The endpoint declares no {@code file:connector}, {@code pollingFrequency} or {@code moveToDirectory}, so the
 * Mule defaults hold: a poll every 1000 ms, and every handed file deleted once the flow returns, after a completed
 * flow and after one the default exception strategy ended alike. SC-5 {@link FileInboundPoller} reproduces that, so
 * a failure never escapes the poll and the file is never handed over a second time.
 *
 * <p>No Spring context is started and {@link CustomerImportService} is mocked; the flow body is covered by
 * {@code service/CustomerImportServiceTest} and the ported {@code ImportCSVfileIntoMongoDBTest}. The input directory
 * is a {@link TempDir}, so {@code src/main/resources/input} and the classpath {@code input.csv} are never touched, and
 * polls run on demand through the single-run method, never by waiting for the schedule.
 */
class CsvFileTriggerTest {

    /** The name the original IT dispatched the file under ({@code outputPattern} {@code input.csv}). */
    private static final String INPUT_CSV = "input.csv";

    /**
     * The original's {@code src/main/resources/input.csv}: {@code firstname,surname,phone,email}, CRLF, then
     * {@code John,Doe,096548763,john.doe@texasComp.com} with no trailing line break.
     */
    private static final byte[] INPUT = classpath("/" + INPUT_CSV);

    @TempDir
    Path inputDir;

    private CustomerImportService customerImportService;

    private CsvFileTrigger trigger;

    @BeforeAll
    static void originalInputIsIntact() {
        assertEquals(72, INPUT.length, "size of the original input.csv");
    }

    @BeforeEach
    void createTrigger() {
        customerImportService = mock(CustomerImportService.class);
        trigger = new CsvFileTrigger(customerImportService, inputDir.toString());
    }

    @Test
    @DisplayName("input.csv dropped -> its exact bytes are imported once and the file is deleted")
    void importsDroppedCsvAndDeletesIt() throws IOException {
        Path file = dropInput();

        int count = trigger.pollOnce();

        assertEquals(1, count);
        ArgumentCaptor<byte[]> content = ArgumentCaptor.forClass(byte[].class);
        verify(customerImportService).importCsv(content.capture());
        // Byte for byte: the CRLF after the header is kept and no trailing line break is added.
        assertArrayEquals(INPUT, content.getValue());
        assertFalse(Files.exists(file), "no moveToDirectory, so the processed file is deleted");
        assertTrue(listing(inputDir).isEmpty());
        verifyNoMoreInteractions(customerImportService);
    }

    @Test
    @DisplayName("MongoDB unreachable -> the poll does not throw, the file is consumed and never retried")
    void mongoFailureConsumesFileWithoutRetry() throws IOException {
        doThrow(new DataAccessResourceFailureException("Timed out connecting to MongoDB"))
                .when(customerImportService).importCsv(any());
        Path file = dropInput();

        int count = assertDoesNotThrow(() -> trigger.pollOnce());

        assertConsumedWithoutRetry(count, file);
    }

    @Test
    @DisplayName("Malformed CSV -> the poll does not throw, the file is consumed and never retried")
    void malformedCsvConsumesFileWithoutRetry() throws IOException {
        // The path a DwCsvReader parse failure takes out of CustomerImportService.importCsv.
        doThrow(new IllegalArgumentException("malformed CSV"))
                .when(customerImportService).importCsv(any());
        Path file = dropInput();

        int count = assertDoesNotThrow(() -> trigger.pollOnce());

        assertConsumedWithoutRetry(count, file);
    }

    @Test
    @DisplayName("Empty input directory -> nothing is handed to the import")
    void emptyDirectoryPollsNothing() {
        assertEquals(0, trigger.pollOnce());

        assertEquals(inputDir, trigger.inputDirectory());
        verifyNoInteractions(customerImportService);
    }

    @Test
    @DisplayName("poll() runs the same single poll as pollOnce()")
    void pollDelegatesToPollOnce() throws IOException {
        Path file = dropInput();

        trigger.poll();

        ArgumentCaptor<byte[]> content = ArgumentCaptor.forClass(byte[].class);
        verify(customerImportService, times(1)).importCsv(content.capture());
        assertArrayEquals(INPUT, content.getValue());
        assertFalse(Files.exists(file), "no moveToDirectory, so the processed file is deleted");
    }

    @Test
    @DisplayName("poll() is scheduled at the Mule default file polling frequency of 1000 ms")
    void pollIsScheduledAtMuleDefaultFrequency() throws NoSuchMethodException {
        Scheduled scheduled = CsvFileTrigger.class.getMethod("poll").getAnnotation(Scheduled.class);

        assertNotNull(scheduled);
        // A fixed delay over application.yml's csv-import.polling-frequency, defaulting to Mule's 1000 ms.
        assertEquals("${csv-import.polling-frequency:1000}", scheduled.fixedDelayString());
    }

    /** Places a copy of the original {@code input.csv} in the input directory, as the original IT dispatched it. */
    private Path dropInput() throws IOException {
        return Files.write(inputDir.resolve(INPUT_CSV), INPUT);
    }

    /**
     * Asserts the disposition of a file whose import threw. {@link FileInboundPoller#pollOnce()} documents its result
     * as the number of files handed to the handler, counting a file whose handler threw, so the failed file counts
     * as 1; it is deleted exactly as after a success, so the next poll finds nothing and the import ran once.
     */
    private void assertConsumedWithoutRetry(int count, Path file) throws IOException {
        assertEquals(1, count);
        assertFalse(Files.exists(file), "a failed import still consumes the file");
        assertTrue(listing(inputDir).isEmpty());
        assertEquals(0, trigger.pollOnce());
        verify(customerImportService, times(1)).importCsv(any());
    }

    private static byte[] classpath(String location) {
        try (InputStream in = CsvFileTriggerTest.class.getResourceAsStream(location)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + location);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read classpath resource " + location, e);
        }
    }

    private static List<Path> listing(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.toList();
        }
    }
}
