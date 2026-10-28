/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_a_csv_file_into_mongo_db.trigger;

import static com.mulesoft.examples.importing_a_csv_file_into_mongo_db.service.CustomerImportService.COLLECTION;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mulesoft.examples.importing_a_csv_file_into_mongo_db.mapper.CustomerDocumentMapper;
import com.mulesoft.examples.importing_a_csv_file_into_mongo_db.mapper.DwCsvReader;
import com.mulesoft.examples.importing_a_csv_file_into_mongo_db.service.CustomerImportService;

import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;

/**
 * Trigger test of {@link CsvFileTrigger}, the replacement of the only inbound endpoint of flow
 * {@code csv-to-mongodbFlow1} in {@code importing-a-CSV-file-into-Mongo-DB/src/main/app/csv-to-mongodb.xml} (l.9),
 * {@code <file:inbound-endpoint path="src/main/resources/input" responseTimeout="10000"/>}. The original has no HTTP
 * listener, so this test stands in for its endpoint test (AAP 0.8.2): one happy path, two failure cases (an
 * unreachable MongoDB, and an import that throws for another reason) and a malformed CSV, which {@link DwCsvReader}
 * reads leniently by its documented rules rather than rejecting it.
 *
 * <p>The endpoint declares no {@code file:connector}, {@code pollingFrequency} or {@code moveToDirectory}, so the
 * Mule defaults hold: a poll every 1000 ms, and every handed file deleted once the flow returns, after a completed
 * flow and after one the default exception strategy ended alike. SC-5 {@link FileInboundPoller} reproduces that, so
 * a failure never escapes the poll and the file is never handed over a second time.
 *
 * <p>No Spring context is started. The file-disposition cases mock {@link CustomerImportService}; the flow body is
 * covered by {@code service/CustomerImportServiceTest} and the ported {@code ImportCSVfileIntoMongoDBTest}. The
 * malformed-CSV case runs the real service, {@link DwCsvReader} and {@link CustomerDocumentMapper} over a
 * {@link MongoTemplate} on a {@link MongoClient} connected to mongo-java-server, an in-memory MongoDB wire-protocol
 * server bound to a free loopback port, started before the class and shut down after it, and asserts the documents
 * the server stores. The input directory is a {@link TempDir}, so {@code src/main/resources/input} and the classpath
 * {@code input.csv} are never touched, and polls run on demand through the single-run method, never by waiting for
 * the schedule.
 */
class CsvFileTriggerTest {

    /** The name the original IT dispatched the file under ({@code outputPattern} {@code input.csv}). */
    private static final String INPUT_CSV = "input.csv";

    /**
     * The original's {@code src/main/resources/input.csv}: {@code firstname,surname,phone,email}, CRLF, then
     * {@code John,Doe,096548763,john.doe@texasComp.com} with no trailing line break.
     */
    private static final byte[] INPUT = classpath("/" + INPUT_CSV);

    /** The {@code csv-import.polling-frequency} {@code application.yml} sets: Mule's default of 1000 ms. */
    private static final String POLLING_FREQUENCY = "1000";

    /** Database of the malformed-CSV case: the one the original test counted in. */
    private static final String DB_NAME = "customers";

    /**
     * The sample's header and CRLF line ends, then one record for each shape {@link DwCsvReader} reads leniently
     * rather than rejecting: a short row; a long row; characters after a closing quote; and, with no line end after
     * it, a quoted field that is never closed, so it runs to the end of the input, separators included.
     */
    private static final String MALFORMED_CSV = "firstname,surname,phone,email\r\n"
            + "Jane,Roe\r\n"
            + "Max,Mustermann,0301234567,max@example.com,Berlin,10115\r\n"
            + "\"Ann\"ie,Lee,0401234567,annie@example.com\r\n"
            + "Bob,\"Smith,0501234567,bob@example.com";

    private static MongoServer mongoServer;

    private static MongoClient mongoClient;

    private static MongoTemplate mongoTemplate;

    @TempDir
    Path inputDir;

    private CustomerImportService customerImportService;

    private CsvFileTrigger trigger;

    @BeforeAll
    static void originalInputIsIntact() {
        assertEquals(72, INPUT.length, "size of the original input.csv");
    }

    @BeforeAll
    static void startMongoServer() {
        mongoServer = new MongoServer(new MemoryBackend());
        mongoClient = MongoClients.create(mongoServer.bindAndGetConnectionString());
        mongoTemplate = new MongoTemplate(mongoClient, DB_NAME);
    }

    @AfterAll
    static void stopMongoServer() {
        try {
            if (mongoClient != null) {
                mongoClient.close();
            }
        } finally {
            if (mongoServer != null) {
                mongoServer.shutdownNow();
            }
        }
    }

    @BeforeEach
    void createTrigger() {
        customerImportService = mock(CustomerImportService.class);
        trigger = new CsvFileTrigger(customerImportService, inputDir.toString(), POLLING_FREQUENCY);
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
    @DisplayName("Import throws -> the poll does not throw, the file is consumed and never retried")
    void importFailureConsumesFileWithoutRetry() throws IOException {
        // An injected failure other than data access. It is not a parse failure: the dropped bytes are the valid
        // sample, and DwCsvReader rejects no malformed input either (malformedCsvIsImportedLenientlyAndConsumed).
        doThrow(new IllegalArgumentException("import failed"))
                .when(customerImportService).importCsv(any());
        Path file = dropInput();

        int count = assertDoesNotThrow(() -> trigger.pollOnce());

        assertConsumedWithoutRetry(count, file);
    }

    @Test
    @DisplayName("Malformed CSV -> imported leniently through the real reader and mapper, the file deleted and never"
            + " retried")
    void malformedCsvIsImportedLenientlyAndConsumed() throws IOException {
        CsvFileTrigger realImport = new CsvFileTrigger(
                new CustomerImportService(mongoTemplate, new DwCsvReader(), new CustomerDocumentMapper()),
                inputDir.toString(), POLLING_FREQUENCY);
        Path file = Files.write(inputDir.resolve(INPUT_CSV), MALFORMED_CSV.getBytes(StandardCharsets.UTF_8));
        assertFalse(mongoTemplate.collectionExists(COLLECTION), "the run starts on the collection-absent branch");

        int count = assertDoesNotThrow(() -> realImport.pollOnce());

        assertEquals(1, count);
        assertTrue(mongoTemplate.collectionExists(COLLECTION), "the absent collection is created");
        List<Document> stored = storedCustomers();
        for (Document customer : stored) {
            assertInstanceOf(ObjectId.class, customer.remove("_id"), "the driver's generated _id");
        }
        // Whole-document equality: a null below is a key stored with a BSON null, not an absent key, and no
        // document carries the long row's column_4 or column_5. The memory backend returns the save order.
        assertEquals(List.of(
                customer("Jane", "Roe", null, null),
                customer("Max", "Mustermann", "0301234567", "max@example.com"),
                customer("Annie", "Lee", "0401234567", "annie@example.com"),
                customer("Bob", "Smith,0501234567,bob@example.com", null, null)), stored);
        assertFalse(Files.exists(file), "no moveToDirectory, so the processed file is deleted");
        assertTrue(listing(inputDir).isEmpty());
        assertEquals(0, realImport.pollOnce());
        assertEquals(4, mongoTemplate.getCollection(COLLECTION).countDocuments(), "no record is saved twice");
        verifyNoInteractions(customerImportService);
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

    @ParameterizedTest(name = "csv-import.polling-frequency \"{0}\" -> start-up fails naming the key and the value")
    @ValueSource(strings = {"0", "-1", "PT0S", "PT-1S", "abc", ""})
    void unschedulableFrequencyFailsNamingKeyAndValue(String frequency) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new CsvFileTrigger(customerImportService, inputDir.toString(), frequency));

        assertTrue(e.getMessage().contains("csv-import.polling-frequency"), e.getMessage());
        assertTrue(e.getMessage().contains("\"" + frequency + "\""), e.getMessage());
    }

    @ParameterizedTest(name = "csv-import.polling-frequency \"{0}\" -> the trigger is created")
    @ValueSource(strings = {"1", "1000", "PT10S", "pt0.5s"})
    void schedulableFrequencyCreatesTheTrigger(String frequency) {
        CsvFileTrigger created = assertDoesNotThrow(
                () -> new CsvFileTrigger(customerImportService, inputDir.toString(), frequency));

        assertEquals(inputDir, created.inputDirectory());
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

    /** Reads the {@value CustomerImportService#COLLECTION} collection through the driver, as the server holds it. */
    private static List<Document> storedCustomers() {
        return mongoTemplate.getCollection(COLLECTION).find().into(new ArrayList<>());
    }

    /** The document {@link CustomerDocumentMapper} writes: its four keys in script order, a missing value null. */
    private static Document customer(String firstname, String surname, String phone, String email) {
        return new Document(CustomerDocumentMapper.FIRSTNAME, firstname)
                .append(CustomerDocumentMapper.SURNAME, surname)
                .append(CustomerDocumentMapper.PHONE, phone)
                .append(CustomerDocumentMapper.EMAIL, email);
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
