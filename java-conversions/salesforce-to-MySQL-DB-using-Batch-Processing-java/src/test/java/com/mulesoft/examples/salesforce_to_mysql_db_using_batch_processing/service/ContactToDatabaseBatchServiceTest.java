package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.client.ContactRepository;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.client.SalesforcePartnerClient;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.client.UpstreamSystem;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.exception.UpstreamUnavailableException;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.mapper.ContactRowMapper;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.model.BatchJobResult;

import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Tests {@link ContactToDatabaseBatchService}, the original flow {@code triggerFlow} and its batch job
 * {@code salesforce-to-database-Batch} ({@code salesforce-to-database.xml}): the watermark default and the stored
 * watermark in the query, the INSERT, UPDATE and {@code otherwise} branches of step {@code upsertContactInDbStep}, the
 * {@code MAX} watermark update after a successful job, record failures under {@code max-failed-records} 0 with the
 * counts of the stopped job, the unchanged watermark after an empty poll, a failed query or a failed job, the next poll
 * selecting again what a failed job left, the logger texts and the system-clock default.
 *
 * <p>Salesforce and the database are Mockito mocks; the batch runner, the row mapper and the watermark store are the
 * project's real classes, the store kept in a file under the test's {@link TempDir}. No Spring context is started:
 * the clock reaches the service through a bare {@link DefaultListableBeanFactory}. The service logger is captured
 * with a Logback {@link ListAppender} at INFO, detached and restored after each test.
 */
@ExtendWith(MockitoExtension.class)
class ContactToDatabaseBatchServiceTest {

    /** The instant of the fixed clock; the default watermark lies 10 s before it. */
    private static final Instant NOW = Instant.parse("2015-10-10T10:00:10.123Z");

    /** {@code now - 10 s} in the watermark's {@code yyyy-MM-dd'T'HH:mm:ss.SSS'Z'} UTC form. */
    private static final String DEFAULT_WATERMARK = "2015-10-10T10:00:00.123Z";

    /** A watermark a previous poll stored. */
    private static final String STORED_WATERMARK = "2015-01-01T00:00:00.000Z";

    private static final String KEY = "timestamp";

    private static final String JOB_NAME = "salesforce-to-database-Batch";

    private static final String ON_COMPLETE = "Batch sf->db has finished";

    private static final String SOQL_PREFIX =
            "SELECT Email,FirstName,LastModifiedDate,LastName FROM Contact WHERE LastModifiedDate > ";

    private static final String JOHN_EMAIL = "john.doe@example.com";

    private static final String JANE_EMAIL = "jane@example.com";

    @Mock
    private SalesforcePartnerClient salesforcePartnerClient;

    @Mock
    private ContactRepository contactRepository;

    @TempDir
    Path tempDir;

    private WatermarkStore watermarkStore;

    private ContactToDatabaseBatchService service;

    private Logger logger;

    private Level previousLevel;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        watermarkStore = new WatermarkStore(tempDir.resolve("watermark.properties"));
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("clock", Clock.fixed(NOW, ZoneOffset.UTC));
        service = newService(beanFactory);

        logger = (Logger) LoggerFactory.getLogger(ContactToDatabaseBatchService.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }

    static Stream<Arguments> emptyPolls() {
        return Stream.of(
                Arguments.of(null, "SELECT Email,FirstName,LastModifiedDate,LastName FROM Contact "
                        + "WHERE LastModifiedDate > 2015-10-10T10:00:00.123Z"),
                Arguments.of(STORED_WATERMARK, "SELECT Email,FirstName,LastModifiedDate,LastName FROM Contact "
                        + "WHERE LastModifiedDate > 2015-01-01T00:00:00.000Z"));
    }

    @ParameterizedTest(name = "stored watermark {0}")
    @MethodSource("emptyPolls")
    void emptyPollQueriesFromTheResolvedWatermarkAndLeavesTheStoreAsItWas(String storedWatermark, String expectedSoql)
            throws Exception {
        if (storedWatermark != null) {
            watermarkStore.store(KEY, storedWatermark);
        }
        pollReturns(List.of());

        BatchJobResult result = service.synchronize();

        assertThat(capturedSoql()).isEqualTo(expectedSoql);
        assertThat(watermarkStore.current(KEY)).isEqualTo(Optional.ofNullable(storedWatermark));
        assertThat(result.jobName()).isEqualTo(JOB_NAME);
        assertThat(result.status()).isEqualTo(BatchJobResult.Status.SUCCESSFUL);
        assertThat(result.loadedRecords()).isZero();
        assertThat(result.failedRecords()).isZero();
        verifyNoInteractions(contactRepository);
    }

    @Test
    void buildQueryAppendsTheWatermarkUnquotedToTheOriginalQuery() {
        assertThat(ContactToDatabaseBatchService.buildQuery(DEFAULT_WATERMARK)).isEqualTo(
                "SELECT Email,FirstName,LastModifiedDate,LastName FROM Contact "
                        + "WHERE LastModifiedDate > 2015-10-10T10:00:00.123Z");
        assertThatThrownBy(() -> ContactToDatabaseBatchService.buildQuery(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void contactWithoutRowIsInserted() throws Exception {
        pollReturns(List.of(contact(JOHN_EMAIL, "John", "Doe", "2015-10-10T10:00:05.000Z")));
        when(contactRepository.findByEmail(JOHN_EMAIL)).thenReturn(List.of());

        BatchJobResult result = service.synchronize();

        InOrder order = inOrder(contactRepository);
        order.verify(contactRepository).findByEmail(JOHN_EMAIL);
        order.verify(contactRepository).insert("John", "Doe", JOHN_EMAIL);
        verifyNoMoreInteractions(contactRepository);
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.jobName()).isEqualTo(JOB_NAME);
        assertThat(result.loadedRecords()).isEqualTo(1);
        assertThat(result.processedRecords()).isEqualTo(1);
        assertThat(result.successfulRecords()).isEqualTo(1);
        assertThat(result.failedRecords()).isZero();
    }

    @Test
    void contactWithRowIsUpdated() throws Exception {
        pollReturns(List.of(contact(JOHN_EMAIL, "John", "Doe", "2015-10-10T10:00:05.000Z")));
        when(contactRepository.findByEmail(JOHN_EMAIL))
                .thenReturn(List.of(Map.of("first_name", "Old", "last_name", "Name", "email", JOHN_EMAIL)));

        BatchJobResult result = service.synchronize();

        InOrder order = inOrder(contactRepository);
        order.verify(contactRepository).findByEmail(JOHN_EMAIL);
        order.verify(contactRepository).update("John", "Doe", JOHN_EMAIL);
        verifyNoMoreInteractions(contactRepository);
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.loadedRecords()).isEqualTo(1);
        assertThat(result.successfulRecords()).isEqualTo(1);
        assertThat(result.failedRecords()).isZero();
    }

    static Stream<Arguments> recordVariablesSelectingOtherwise() {
        Map<String, Object> existsNull = new HashMap<>();
        existsNull.put("exists", null);
        existsNull.put("dbRecord", List.of());
        Map<String, Object> dbRecordNull = new HashMap<>();
        dbRecordNull.put("exists", Boolean.TRUE);
        dbRecordNull.put("dbRecord", null);
        return Stream.of(
                Arguments.of("exists unset", Map.of()),
                Arguments.of("exists null", existsNull),
                Arguments.of("exists true, dbRecord unset", Map.of("exists", Boolean.TRUE)),
                Arguments.of("exists true, dbRecord null", dbRecordNull));
    }

    /** The branch no record reaches through the job, as the service documents; exercised on the step directly. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("recordVariablesSelectingOtherwise")
    void otherwiseBranchLogsTheErrorWithoutTouchingTheDatabase(String description, Map<String, Object> recordVars) {
        BatchJobRunner.BatchRecord<Map<String, Object>> record = new BatchJobRunner.BatchRecord<>(
                new ContactRowMapper().map(contact(JANE_EMAIL, "Jane", "Roe", "2015-10-10T10:00:06.000Z")));
        record.recordVars().putAll(recordVars);

        service.upsert(record);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(appender.list.get(0).getFormattedMessage()).isEqualTo("Error with jane@example.com contact");
        verifyNoInteractions(contactRepository);
    }

    @Test
    void watermarkMovesToTheLatestLastModifiedDateOfThePoll() throws Exception {
        pollReturns(List.of(
                contact(JOHN_EMAIL, "John", "Doe", "2015-10-10T10:00:07.000Z"),
                contact(JANE_EMAIL, "Jane", "Roe", "2015-10-10T10:00:05.000Z")));
        when(contactRepository.findByEmail(anyString())).thenReturn(List.of());

        BatchJobResult result = service.synchronize();

        assertThat(result.isSuccessful()).isTrue();
        verify(contactRepository).insert("John", "Doe", JOHN_EMAIL);
        verify(contactRepository).insert("Jane", "Roe", JANE_EMAIL);
        assertThat(watermarkStore.current(KEY)).isEqualTo(Optional.of("2015-10-10T10:00:07.000Z"));
    }

    static Stream<Arguments> selectorValues() {
        long millis = Instant.parse("2015-10-10T10:00:07.456Z").toEpochMilli();
        Calendar calendar = new GregorianCalendar(TimeZone.getTimeZone("GMT+02:00"));
        calendar.setTimeInMillis(millis);
        return Stream.of(
                Arguments.of("text", "2015-10-10T10:00:07.456Z", "2015-10-10T10:00:07.456Z"),
                Arguments.of("Calendar in GMT+02:00", calendar, "2015-10-10T10:00:07.456Z"),
                Arguments.of("java.util.Date", new Date(millis), "2015-10-10T10:00:07.456Z"),
                Arguments.of("java.sql.Date", new java.sql.Date(millis), "2015-10-10T10:00:07.456Z"),
                Arguments.of("null", null, STORED_WATERMARK));
    }

    @ParameterizedTest(name = "LastModifiedDate as {0}")
    @MethodSource("selectorValues")
    void selectorValueIsStoredInSalesforceUtcFormAndANullOneIsSkipped(String description, Object lastModifiedDate,
            String expectedWatermark) throws Exception {
        watermarkStore.store(KEY, STORED_WATERMARK);
        pollReturns(List.of(contact(JOHN_EMAIL, "John", "Doe", lastModifiedDate)));
        when(contactRepository.findByEmail(JOHN_EMAIL)).thenReturn(List.of());

        BatchJobResult result = service.synchronize();

        assertThat(result.isSuccessful()).isTrue();
        verify(contactRepository).insert("John", "Doe", JOHN_EMAIL);
        assertThat(watermarkStore.current(KEY)).isEqualTo(Optional.of(expectedWatermark));
    }

    static Stream<Arguments> queryFailures() {
        return Stream.of(
                Arguments.of(new UpstreamUnavailableException(UpstreamSystem.SALESFORCE, "Read timed out",
                        new SocketTimeoutException("Read timed out"))),
                Arguments.of(new RuntimeException("boom")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("queryFailures")
    void queryFailurePropagatesUnchangedAfterOneCallAndLeavesTheWatermark(Exception failure) throws Exception {
        watermarkStore.store(KEY, STORED_WATERMARK);
        when(salesforcePartnerClient.query(anyString())).thenThrow(failure);

        assertThatThrownBy(service::synchronize).isSameAs(failure);

        assertThat(capturedSoql()).isEqualTo(SOQL_PREFIX + STORED_WATERMARK);
        assertThat(watermarkStore.current(KEY)).isEqualTo(Optional.of(STORED_WATERMARK));
        verifyNoInteractions(contactRepository);
        // The watermark logger follows the query, and the job, with its on-complete logger, never starts.
        assertThat(appender.list).isEmpty();
    }

    @Test
    void insertFailureFailsTheRecordAndStopsTheJobAndKeepsTheStoredWatermark() throws Exception {
        watermarkStore.store(KEY, STORED_WATERMARK);
        pollReturns(List.of(
                contact(JOHN_EMAIL, "John", "Doe", "2015-10-10T10:00:05.000Z"),
                contact(JANE_EMAIL, "Jane", "Roe", "2015-10-10T10:00:07.000Z")));
        when(contactRepository.findByEmail(anyString())).thenReturn(List.of());
        when(contactRepository.insert("John", "Doe", JOHN_EMAIL))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        BatchJobResult result = service.synchronize();

        // max-failed-records 0: the first failed record stops the job, so Jane, looked up in step 1, is never
        // offered to step 2; she is neither processed nor successful.
        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.status()).isEqualTo(BatchJobResult.Status.FAILED_PROCESS_RECORDS);
        assertThat(result.loadedRecords()).isEqualTo(2);
        assertThat(result.processedRecords()).isEqualTo(1);
        assertThat(result.successfulRecords()).isZero();
        assertThat(result.failedRecords()).isEqualTo(1);
        verify(contactRepository).findByEmail(JOHN_EMAIL);
        verify(contactRepository).findByEmail(JANE_EMAIL);
        verify(contactRepository, times(1)).insert(any(), any(), any());
        verifyNoMoreInteractions(contactRepository);
        assertThat(capturedSoql()).isEqualTo(SOQL_PREFIX + STORED_WATERMARK);
        // The failed job keeps the stored watermark, so the next poll selects John and Jane again.
        assertThat(watermarkStore.current(KEY)).isEqualTo(Optional.of(STORED_WATERMARK));
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly(STORED_WATERMARK, ON_COMPLETE);
    }

    @Test
    void nextPollAfterAFailedJobQueriesFromTheSameWatermarkAndAdvancesOnceTheJobSucceeds() throws Exception {
        watermarkStore.store(KEY, STORED_WATERMARK);
        when(salesforcePartnerClient.query(anyString()))
                .thenReturn(new ArrayList<>(List.of(
                        contact(JOHN_EMAIL, "John", "Doe", "2015-10-10T10:00:05.000Z"),
                        contact(JANE_EMAIL, "Jane", "Roe", "2015-10-10T10:00:07.000Z"))))
                .thenReturn(new ArrayList<>(List.of(
                        contact(JOHN_EMAIL, "John", "Doe", "2015-10-10T10:00:05.000Z"),
                        contact(JANE_EMAIL, "Jane", "Roe", "2015-10-10T10:00:07.000Z"))));
        when(contactRepository.findByEmail(anyString())).thenReturn(List.of());
        when(contactRepository.insert("John", "Doe", JOHN_EMAIL))
                .thenThrow(new DataAccessResourceFailureException("db down"))
                .thenReturn(1);

        BatchJobResult failed = service.synchronize();

        assertThat(failed.status()).isEqualTo(BatchJobResult.Status.FAILED_PROCESS_RECORDS);
        assertThat(failed.successfulRecords()).isZero();
        assertThat(failed.failedRecords()).isEqualTo(1);
        assertThat(watermarkStore.current(KEY)).isEqualTo(Optional.of(STORED_WATERMARK));

        BatchJobResult recovered = service.synchronize();

        // No retry inside the failed poll: the next scheduled poll queries from the kept watermark, re-selects John,
        // whose insert failed, and Jane, whom the stopped job never wrote, and moves the watermark to the MAX.
        ArgumentCaptor<String> soql = ArgumentCaptor.forClass(String.class);
        verify(salesforcePartnerClient, times(2)).query(soql.capture());
        assertThat(soql.getAllValues()).containsExactly(SOQL_PREFIX + STORED_WATERMARK, SOQL_PREFIX + STORED_WATERMARK);
        assertThat(recovered.isSuccessful()).isTrue();
        assertThat(recovered.processedRecords()).isEqualTo(2);
        assertThat(recovered.successfulRecords()).isEqualTo(2);
        assertThat(recovered.failedRecords()).isZero();
        verify(contactRepository, times(2)).insert("John", "Doe", JOHN_EMAIL);
        verify(contactRepository, times(1)).insert("Jane", "Roe", JANE_EMAIL);
        assertThat(watermarkStore.current(KEY)).isEqualTo(Optional.of("2015-10-10T10:00:07.000Z"));
    }

    @Test
    void lookupFailureFailsTheRecordInStepOneWithoutInsertOrUpdate() throws Exception {
        pollReturns(List.of(contact(JOHN_EMAIL, "John", "Doe", "2015-10-10T10:00:05.000Z")));
        when(contactRepository.findByEmail(JOHN_EMAIL)).thenThrow(new DataAccessResourceFailureException("db down"));

        BatchJobResult result = service.synchronize();

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.status()).isEqualTo(BatchJobResult.Status.FAILED_PROCESS_RECORDS);
        assertThat(result.loadedRecords()).isEqualTo(1);
        assertThat(result.processedRecords()).isEqualTo(1);
        assertThat(result.successfulRecords()).isZero();
        assertThat(result.failedRecords()).isEqualTo(1);
        verify(contactRepository).findByEmail(JOHN_EMAIL);
        verifyNoMoreInteractions(contactRepository);
        // No watermark was stored before, and the failed job stores none: the next poll starts from the default.
        assertThat(watermarkStore.current(KEY)).isEmpty();
        assertThat(watermarkStore.storePath()).doesNotExist();
    }

    @Test
    void logsTheWatermarkThenTheOnCompleteText() throws Exception {
        pollReturns(List.of(contact(JOHN_EMAIL, "John", "Doe", "2015-10-10T10:00:05.000Z")));
        when(contactRepository.findByEmail(JOHN_EMAIL)).thenReturn(List.of());

        service.synchronize();

        assertThat(appender.list).extracting(ILoggingEvent::getLevel).containsOnly(Level.INFO);
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("2015-10-10T10:00:00.123Z", "Batch sf->db has finished");
    }

    @Test
    void constantsCarryTheOriginalNamesAndTexts() {
        assertThat(ContactToDatabaseBatchService.WATERMARK_KEY).isEqualTo("timestamp");
        assertThat(ContactToDatabaseBatchService.WATERMARK_PATTERN).isEqualTo("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        assertThat(ContactToDatabaseBatchService.JOB_NAME).isEqualTo("salesforce-to-database-Batch");
        assertThat(ContactToDatabaseBatchService.QUERY_STEP).isEqualTo("queryExistingContactInDbStep");
        assertThat(ContactToDatabaseBatchService.UPSERT_STEP).isEqualTo("upsertContactInDbStep");
        assertThat(ContactToDatabaseBatchService.EXISTS_VAR).isEqualTo("exists");
        assertThat(ContactToDatabaseBatchService.DB_RECORD_VAR).isEqualTo("dbRecord");
        assertThat(ContactToDatabaseBatchService.SELECTOR_FIELD).isEqualTo("LastModifiedDate");
        assertThat(ContactToDatabaseBatchService.QUERY_PREFIX)
                .isEqualTo("SELECT Email,FirstName,LastModifiedDate,LastName FROM Contact WHERE LastModifiedDate > ");
        assertThat(ContactToDatabaseBatchService.ON_COMPLETE_MESSAGE).isEqualTo("Batch sf->db has finished");
    }

    @Test
    void withoutAClockBeanTheDefaultWatermarkComesFromTheSystemClock() throws Exception {
        ContactToDatabaseBatchService systemClockService = newService(new DefaultListableBeanFactory());
        pollReturns(List.of());

        Instant before = Instant.now();
        systemClockService.synchronize();
        Instant after = Instant.now();

        String soql = capturedSoql();
        assertThat(soql).startsWith(SOQL_PREFIX);
        String watermark = soql.substring(SOQL_PREFIX.length());
        assertThat(watermark).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
        assertThat(Instant.parse(watermark)).isBetween(before.minusSeconds(12), after.minusSeconds(8));
        assertThat(watermarkStore.current(KEY)).isEmpty();
    }

    private ContactToDatabaseBatchService newService(DefaultListableBeanFactory beanFactory) {
        return new ContactToDatabaseBatchService(salesforcePartnerClient, new BatchJobRunner(), watermarkStore,
                new ContactRowMapper(), contactRepository, beanFactory.getBeanProvider(Clock.class));
    }

    /** Stubs the poll's query to return the records, in a mutable list as the client returns it. */
    private void pollReturns(List<Map<String, Object>> records) throws Exception {
        when(salesforcePartnerClient.query(anyString())).thenReturn(new ArrayList<>(records));
    }

    /** Verifies that exactly one query ran and returns its SOQL. */
    private String capturedSoql() throws Exception {
        ArgumentCaptor<String> soql = ArgumentCaptor.forClass(String.class);
        verify(salesforcePartnerClient).query(soql.capture());
        return soql.getValue();
    }

    /** A Contact record as {@link SalesforcePartnerClient#query} returns it: a {@link HashMap} by field name. */
    private static Map<String, Object> contact(String email, String firstName, String lastName,
            Object lastModifiedDate) {
        Map<String, Object> record = new HashMap<>();
        record.put("type", "Contact");
        record.put("Email", email);
        record.put("FirstName", firstName);
        record.put("LastName", lastName);
        record.put("LastModifiedDate", lastModifiedDate);
        return record;
    }
}
