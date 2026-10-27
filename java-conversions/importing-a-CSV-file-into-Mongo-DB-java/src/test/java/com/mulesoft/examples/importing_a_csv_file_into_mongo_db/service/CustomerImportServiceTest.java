/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_a_csv_file_into_mongo_db.service;

import static com.mulesoft.examples.importing_a_csv_file_into_mongo_db.service.CustomerImportService.COLLECTION;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.mulesoft.examples.importing_a_csv_file_into_mongo_db.mapper.CustomerDocumentMapper;
import com.mulesoft.examples.importing_a_csv_file_into_mongo_db.mapper.DwCsvReader;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Unit test of {@link CustomerImportService}, the body of {@code csv-to-mongodbFlow1} of {@code csv-to-mongodb.xml}:
 * CSV to map, the {@code saving objects:} log, the collection check, the create-or-log choice and one save per record.
 *
 * <p>The service runs over the real {@link DwCsvReader} and {@link CustomerDocumentMapper} and a Mockito
 * {@link MongoTemplate} under strict stubs, so the documents handed to the save are the ones the flow's script
 * produced. Both choice branches are exercised, as are an input without data records and the propagation of a
 * failed collection check or save, which the original flow, declaring no exception strategy, never caught.
 *
 * <p>The service logger is captured with a Logback {@link ListAppender} at INFO, detached and restored after each
 * test.
 */
@ExtendWith(MockitoExtension.class)
class CustomerImportServiceTest {

    private static final String SAVING_JOHN =
            "saving objects: [{firstname=John, surname=Doe, phone=096548763, email=john.doe@texasComp.com}]";

    private static final String ALREADY_EXISTS = "Customer Collection already exists.";

    private static final String HEADER = "firstname,surname,phone,email";

    @Mock
    private MongoTemplate mongoTemplate;

    private CustomerImportService service;

    private Logger serviceLogger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        service = new CustomerImportService(mongoTemplate, new DwCsvReader(), new CustomerDocumentMapper());

        serviceLogger = (Logger) LoggerFactory.getLogger(CustomerImportService.class);
        previousLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(appender);
        appender.stop();
        serviceLogger.setLevel(previousLevel);
    }

    @Test
    @DisplayName("Absent collection: it is created, then the sample record is saved as one document")
    void collectionAbsent_createsCollectionThenSavesOneDocument() throws IOException {
        assertEquals("customers", COLLECTION);
        when(mongoTemplate.collectionExists(COLLECTION)).thenReturn(false);

        service.importCsv(inputCsv());

        ArgumentCaptor<Document> saved = ArgumentCaptor.forClass(Document.class);
        InOrder order = inOrder(mongoTemplate);
        order.verify(mongoTemplate).collectionExists(COLLECTION);
        order.verify(mongoTemplate).createCollection(COLLECTION);
        order.verify(mongoTemplate).save(saved.capture(), eq(COLLECTION));
        verify(mongoTemplate, times(1)).save(any(Document.class), eq(COLLECTION));
        verifyNoMoreInteractions(mongoTemplate);

        Document document = saved.getValue();
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("firstname", "John");
        expected.put("surname", "Doe");
        expected.put("phone", "096548763");
        expected.put("email", "john.doe@texasComp.com");
        assertEquals(new Document(expected), document);
        assertEquals(List.of("firstname", "surname", "phone", "email"), new ArrayList<>(document.keySet()));
        assertInstanceOf(String.class, document.get("phone"));
        assertEquals("096548763", document.get("phone"));
        assertFalse(document.containsKey("_class"));
        assertFalse(document.containsKey("email\r"));

        assertFalse(infoMessages(appender.list).contains(ALREADY_EXISTS));
    }

    @Test
    @DisplayName("The records are logged before the collection check runs")
    void logsSavingObjectsBeforeExistenceCheck() throws IOException {
        List<ILoggingEvent> loggedBeforeCheck = new ArrayList<>();
        when(mongoTemplate.collectionExists(COLLECTION)).thenAnswer(invocation -> {
            loggedBeforeCheck.addAll(appender.list);
            return false;
        });

        service.importCsv(inputCsv());

        assertEquals(List.of(SAVING_JOHN), infoMessages(loggedBeforeCheck));
    }

    @Test
    @DisplayName("Existing collection: the choice logs instead of creating, and the record is still saved")
    void collectionExists_logsAndDoesNotCreate() throws IOException {
        when(mongoTemplate.collectionExists(COLLECTION)).thenReturn(true);

        service.importCsv(inputCsv());

        verify(mongoTemplate, never()).createCollection(anyString());
        verify(mongoTemplate, times(1)).save(any(Document.class), eq(COLLECTION));
        assertEquals(List.of(SAVING_JOHN, ALREADY_EXISTS), infoMessages(appender.list));
    }

    @Test
    @DisplayName("Several records: one save each, in input order")
    void threeRows_savedOnceEachInInputOrder() {
        String csv = HEADER + "\n"
                + "Ann,Lee,111,ann@example.com\n"
                + "Bob,Ray,222,bob@example.com\n"
                + "Cid,Moe,333,cid@example.com\n";
        when(mongoTemplate.collectionExists(COLLECTION)).thenReturn(false);

        service.importCsv(csv.getBytes(UTF_8));

        verify(mongoTemplate, times(1)).createCollection(COLLECTION);
        ArgumentCaptor<Document> saved = ArgumentCaptor.forClass(Document.class);
        verify(mongoTemplate, times(3)).save(saved.capture(), eq(COLLECTION));
        List<Object> firstnames = new ArrayList<>();
        for (Document document : saved.getAllValues()) {
            firstnames.add(document.get("firstname"));
        }
        assertEquals(List.of("Ann", "Bob", "Cid"), firstnames);
        assertEquals(List.of("saving objects: ["
                + "{firstname=Ann, surname=Lee, phone=111, email=ann@example.com}, "
                + "{firstname=Bob, surname=Ray, phone=222, email=bob@example.com}, "
                + "{firstname=Cid, surname=Moe, phone=333, email=cid@example.com}]"), infoMessages(appender.list));
    }

    @Test
    @DisplayName("Header without records: the check and the choice still run, and nothing is saved")
    void headerOnly_noSave() {
        when(mongoTemplate.collectionExists(COLLECTION)).thenReturn(false);

        service.importCsv((HEADER + "\r\n").getBytes(UTF_8));

        verify(mongoTemplate, times(1)).collectionExists(COLLECTION);
        verify(mongoTemplate, times(1)).createCollection(COLLECTION);
        verify(mongoTemplate, never()).save(any(), anyString());
        assertEquals(List.of("saving objects: []"), infoMessages(appender.list));
    }

    @Test
    @DisplayName("A failed collection check propagates unchanged, after the records were logged")
    void collectionExistsFailure_propagatesUnchanged() throws IOException {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("mongo down");
        when(mongoTemplate.collectionExists(COLLECTION)).thenThrow(failure);
        byte[] content = inputCsv();

        DataAccessResourceFailureException thrown =
                assertThrows(DataAccessResourceFailureException.class, () -> service.importCsv(content));

        assertSame(failure, thrown);
        verify(mongoTemplate, never()).createCollection(anyString());
        verify(mongoTemplate, never()).save(any(), anyString());
        assertEquals(List.of(SAVING_JOHN), infoMessages(appender.list));
    }

    @Test
    @DisplayName("A failed save propagates unchanged and no later record is saved")
    void saveFailure_propagatesAndStopsLoop() {
        String csv = HEADER + "\n"
                + "Ann,Lee,111,ann@example.com\n"
                + "Bob,Ray,222,bob@example.com\n";
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("mongo down");
        when(mongoTemplate.collectionExists(COLLECTION)).thenReturn(true);
        doThrow(failure).when(mongoTemplate).save(any(Document.class), eq(COLLECTION));
        byte[] content = csv.getBytes(UTF_8);

        DataAccessResourceFailureException thrown =
                assertThrows(DataAccessResourceFailureException.class, () -> service.importCsv(content));

        assertSame(failure, thrown);
        verify(mongoTemplate, times(1)).save(any(Document.class), eq(COLLECTION));
    }

    /**
     * Reads the sample file the original example polls: 72 bytes, a CRLF after the header and no line end after the
     * one data record.
     */
    private static byte[] inputCsv() throws IOException {
        try (InputStream in = CustomerImportServiceTest.class.getResourceAsStream("/input.csv")) {
            assertNotNull(in, "classpath resource /input.csv");
            byte[] content = in.readAllBytes();
            assertEquals(72, content.length);
            assertTrue(new String(content, UTF_8).contains("\r\n"));
            return content;
        }
    }

    private static List<String> infoMessages(List<ILoggingEvent> events) {
        List<String> messages = new ArrayList<>();
        for (ILoggingEvent event : events) {
            if (event.getLevel() == Level.INFO) {
                messages.add(event.getFormattedMessage());
            }
        }
        return messages;
    }
}
