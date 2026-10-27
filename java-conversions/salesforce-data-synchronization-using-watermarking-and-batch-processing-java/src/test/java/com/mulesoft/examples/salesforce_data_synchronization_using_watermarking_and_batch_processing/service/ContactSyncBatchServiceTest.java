/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.service;

import static com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.service.ContactSyncBatchService.JOB_NAME;
import static com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.service.ContactSyncBatchService.LOG_PREFIX;
import static com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.service.ContactSyncBatchService.QUERY_PREFIX;
import static com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.service.ContactSyncBatchService.SELECTOR_FIELD;
import static com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.service.ContactSyncBatchService.STEP_NAME;
import static com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.service.ContactSyncBatchService.WATERMARK_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.client.SalesforcePartnerClient;
import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.client.UpstreamSystem;
import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.exception.UpstreamUnavailableException;
import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.model.BatchJobResult;
import com.sforce.ws.ConnectionException;

/**
 * Unit test of {@link ContactSyncBatchService}, one run of the poll of flow {@code triggerFlow} and its batch job
 * {@code watermarkingBatch} ({@code watermarking.xml}), with the real SC-7 {@link BatchJobRunner}, a real SC-8
 * {@link WatermarkStore} kept under the test's {@link TempDir} and a Mockito-mocked {@link SalesforcePartnerClient}; no
 * Spring context, network or credentials.
 *
 * <p>The original {@code SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingIT} only checked that the job
 * succeeded. This test fixes every branch of the poll: the {@code YESTERDAY} default and the stored watermark in the
 * {@code sfdc:query} text, the {@code Batch_Step} INFO line per record ({@code Contact recently modified: #[payload]},
 * asserted on the service's own logger), the {@code MAX} selector over {@code LastModifiedDate} applied only after the
 * job ran, an empty poll leaving the watermark untouched, and a failed query propagating unchanged with the watermark
 * as it was.
 *
 * <p>Every poll makes exactly one query: retries belong only to the SC-9 executor inside the client, and timeouts and
 * connectivity failures are never retried (AAP 0.4.8).
 */
class ContactSyncBatchServiceTest {

    private static final String YESTERDAY = "YESTERDAY";

    private static final String JULY_3 = "2014-07-03T10:00:00.000Z";

    /** The {@code LastModifiedDate} of the README's sample log line. */
    private static final String JULY_4 = "2014-07-04T06:16:54.000Z";

    private static final String JULY_5 = "2014-07-05T10:00:00.000Z";

    private static final String JULY_7 = "2014-07-07T10:00:00.000Z";

    private static final Instant JULY_7_INSTANT = Instant.parse(JULY_7);

    private static final String ID_1 = "003000000000001AAA";

    private static final String ID_2 = "003000000000002AAA";

    private static final String ID_3 = "0032000001DpkrEAAR";

    private static final String ID_4 = "003000000000004AAA";

    @TempDir
    Path tempDir;

    private SalesforcePartnerClient client;

    private WatermarkStore store;

    private ContactSyncBatchService service;

    private Logger serviceLogger;

    private Level previousLevel;

    private boolean previousAdditive;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        client = mock(SalesforcePartnerClient.class);
        store = new WatermarkStore(storeFile());
        service = new ContactSyncBatchService(client, new BatchJobRunner(), store, YESTERDAY);

        serviceLogger = (Logger) LoggerFactory.getLogger(ContactSyncBatchService.class);
        previousLevel = serviceLogger.getLevel();
        previousAdditive = serviceLogger.isAdditive();
        serviceLogger.setLevel(Level.INFO);
        // The appender records every line; detaching the console keeps the per-record lines out of the build log.
        serviceLogger.setAdditive(false);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(appender);
        appender.stop();
        serviceLogger.setAdditive(previousAdditive);
        serviceLogger.setLevel(previousLevel);
    }

    @Test
    void firstPollQueriesContactsModifiedSinceTheDefaultWatermark() throws Exception {
        stubQuery();

        service.synchronize();

        assertThat(capturedQueries(1)).singleElement()
                .isEqualTo("SELECT Id,Name,LastModifiedDate FROM Contact WHERE LastModifiedDate > YESTERDAY");
        verifyNoMoreInteractions(client);
    }

    @Test
    void storedWatermarkIsAppendedToTheQueryVerbatim() throws Exception {
        store.store(WATERMARK_KEY, JULY_4);
        stubQuery();

        service.synchronize();

        // Unquoted and unformatted, as the original DSQL appended #[flowVars['timestamp']].
        assertThat(capturedQueries(1)).singleElement().isEqualTo(QUERY_PREFIX + "2014-07-04T06:16:54.000Z");
        verifyNoMoreInteractions(client);
    }

    @Test
    void batchStepLogsEachContactAtInfoInRecordOrder() throws Exception {
        Map<String, Object> jane = contact(ID_1, "Jane Doe", JULY_4);
        Map<String, Object> john = contact(ID_2, "John Smith", JULY_5);
        Map<String, Object> avi = contact(ID_3, "Avi123 Green", JULY_3);
        stubQuery(jane, john, avi);

        BatchJobResult result = service.synchronize();

        // #[payload] is the record's own java.util.HashMap, so each expected line takes that map's toString(); the
        // key order is HashMap's on the running JDK and is never hard-coded here.
        assertThat(stepLines()).containsExactly(LOG_PREFIX + jane, LOG_PREFIX + john, LOG_PREFIX + avi);
        assertThat(appender.list)
                .filteredOn(event -> event.getFormattedMessage().startsWith(LOG_PREFIX))
                .hasSize(3)
                .allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.INFO));
        assertSuccessful(result, 3);
        verify(client, times(1)).query(anyString());
        verifyNoMoreInteractions(client);
    }

    @Test
    void watermarkMovesToTheLatestLastModifiedDateOnlyAfterTheJobRan() throws Exception {
        WatermarkStore spyStore = spy(new WatermarkStore(storeFile()));
        ContactSyncBatchService spiedService =
                new ContactSyncBatchService(client, new BatchJobRunner(), spyStore, YESTERDAY);
        stubQuery(contact(ID_1, "Jane Doe", JULY_5), contact(ID_2, "John Smith", JULY_3),
                contact(ID_3, "Avi123 Green", JULY_7), contact(ID_4, "No Date", null));

        BatchJobResult result = spiedService.synchronize();

        // The latest instant wins, not the last record; the record without LastModifiedDate selects nothing and
        // still goes through the step without failing.
        assertThat(store.current(WATERMARK_KEY)).isEqualTo(Optional.of(JULY_7));
        assertSuccessful(result, 4);
        assertThat(stepLines()).hasSize(4);

        InOrder order = inOrder(spyStore, client);
        order.verify(spyStore).retrieve(eq(WATERMARK_KEY), any());
        order.verify(client).query(QUERY_PREFIX + YESTERDAY);
        order.verify(spyStore).updateMax(WATERMARK_KEY, List.of(JULY_5, JULY_3, JULY_7));
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest(name = "stored before: {0}")
    @NullSource
    @ValueSource(strings = JULY_4)
    void emptyPollLeavesTheWatermarkAndItsFileUntouched(String storedBefore) throws Exception {
        if (storedBefore != null) {
            store.store(WATERMARK_KEY, storedBefore);
        }
        Map<String, String> filesBefore = directorySnapshot();
        stubQuery();

        BatchJobResult result = service.synchronize();

        assertSuccessful(result, 0);
        assertThat(store.current(WATERMARK_KEY)).isEqualTo(Optional.ofNullable(storedBefore));
        // No store file is created for an absent watermark, and a stored one is not rewritten.
        assertThat(directorySnapshot()).isEqualTo(filesBefore);
        assertThat(stepLines()).isEmpty();
        verify(client, times(1)).query(anyString());
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("queryFailures")
    void failedQueryPropagatesUnchangedWithoutRetryAndKeepsTheWatermark(String description, Exception failure,
            String storedBefore) throws Exception {
        if (storedBefore != null) {
            store.store(WATERMARK_KEY, storedBefore);
        }
        Map<String, String> filesBefore = directorySnapshot();
        when(client.query(anyString())).thenThrow(failure);

        assertThatThrownBy(service::synchronize).isSameAs(failure);

        verify(client, times(1)).query(anyString());
        verifyNoMoreInteractions(client);
        assertThat(store.current(WATERMARK_KEY)).isEqualTo(Optional.ofNullable(storedBefore));
        assertThat(directorySnapshot()).isEqualTo(filesBefore);
        assertThat(stepLines()).isEmpty();
    }

    /**
     * An SC-9 unavailable failure (a read timeout, never retried) and an OTHER-class vendor failure, each with and
     * without a stored watermark.
     */
    static Stream<Arguments> queryFailures() {
        return Stream.of(null, JULY_4).flatMap(storedBefore -> Stream.of(
                Arguments.of("UpstreamUnavailableException (read timeout), stored before: " + storedBefore,
                        new UpstreamUnavailableException(UpstreamSystem.SALESFORCE, "Read timed out",
                                new SocketTimeoutException("Read timed out")),
                        storedBefore),
                Arguments.of("ConnectionException MALFORMED_QUERY, stored before: " + storedBefore,
                        new ConnectionException("MALFORMED_QUERY"), storedBefore)));
    }

    @Test
    void customDefaultIsUsedWhileNoWatermarkIsStoredAndIsNeverPersisted() throws Exception {
        ContactSyncBatchService customDefault =
                new ContactSyncBatchService(client, new BatchJobRunner(), store, "2014-01-01T00:00:00.000Z");
        stubQuery();

        customDefault.synchronize();
        customDefault.synchronize();

        // The default is evaluated again on every poll that finds no stored value.
        assertThat(capturedQueries(2)).containsExactly(
                "SELECT Id,Name,LastModifiedDate FROM Contact WHERE LastModifiedDate > 2014-01-01T00:00:00.000Z",
                "SELECT Id,Name,LastModifiedDate FROM Contact WHERE LastModifiedDate > 2014-01-01T00:00:00.000Z");
        assertThat(store.current(WATERMARK_KEY)).isEmpty();
        assertThat(store.storePath()).doesNotExist();
    }

    @Test
    void nextPollQueriesFromTheAdvancedWatermark() throws Exception {
        when(client.query(anyString()))
                .thenReturn(records(contact(ID_1, "Jane Doe", JULY_4), contact(ID_3, "Avi123 Green", JULY_7)))
                .thenReturn(records());

        service.synchronize();
        BatchJobResult second = service.synchronize();

        assertThat(capturedQueries(2)).containsExactly(QUERY_PREFIX + YESTERDAY, QUERY_PREFIX + JULY_7);
        assertSuccessful(second, 0);
        assertThat(store.current(WATERMARK_KEY)).isEqualTo(Optional.of(JULY_7));
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonTextSelectorValues")
    void nonTextLastModifiedDateIsStoredInSalesforceUtcForm(String description, Object lastModified)
            throws Exception {
        Map<String, Object> record = contact(ID_1, "Jane Doe", null);
        record.put(SELECTOR_FIELD, lastModified);
        stubQuery(record);

        BatchJobResult result = service.synchronize();

        assertThat(store.current(WATERMARK_KEY)).isEqualTo(Optional.of(JULY_7));
        assertSuccessful(result, 1);
        assertThat(stepLines()).containsExactly(LOG_PREFIX + record);
    }

    /** One value per type the selector renders, each denoting {@link #JULY_7}. */
    static Stream<Arguments> nonTextSelectorValues() {
        Calendar calendar = new GregorianCalendar(TimeZone.getTimeZone("America/Los_Angeles"));
        calendar.setTimeInMillis(JULY_7_INSTANT.toEpochMilli());
        return Stream.of(
                Arguments.of("Calendar in America/Los_Angeles", calendar),
                Arguments.of("java.util.Date", Date.from(JULY_7_INSTANT)),
                // java.sql.Date.toInstant() throws, so it must be read through getTime().
                Arguments.of("java.sql.Date", new java.sql.Date(JULY_7_INSTANT.toEpochMilli())),
                Arguments.of("Instant", JULY_7_INSTANT),
                Arguments.of("OffsetDateTime at +02:00",
                        OffsetDateTime.ofInstant(JULY_7_INSTANT, ZoneOffset.ofHours(2))),
                Arguments.of("ZonedDateTime in America/Los_Angeles",
                        JULY_7_INSTANT.atZone(ZoneId.of("America/Los_Angeles"))),
                Arguments.of("other type, rendered by String.valueOf", new StringBuilder(JULY_7)));
    }

    @Test
    void nullRecordGoesThroughTheStepAndSelectsNothing() throws Exception {
        Map<String, Object> jane = contact(ID_1, "Jane Doe", JULY_4);
        stubQuery(null, jane);

        BatchJobResult result = service.synchronize();

        assertThat(stepLines()).containsExactly(LOG_PREFIX + "null", LOG_PREFIX + jane);
        assertSuccessful(result, 2);
        assertThat(store.current(WATERMARK_KEY)).isEqualTo(Optional.of(JULY_4));
    }

    @Test
    void unparseableLastModifiedDateFailsThePollAfterTheStepAndKeepsTheWatermark() throws Exception {
        store.store(WATERMARK_KEY, JULY_4);
        Map<String, Object> jane = contact(ID_1, "Jane Doe", "07/07/2014");
        stubQuery(jane);

        assertThatIllegalArgumentException().isThrownBy(service::synchronize)
                .withMessageContaining("'" + WATERMARK_KEY + "'")
                .withMessageContaining("'07/07/2014'");

        // The record was already processed: the selector is applied only once the job has run.
        assertThat(stepLines()).containsExactly(LOG_PREFIX + jane);
        assertThat(store.current(WATERMARK_KEY)).isEqualTo(Optional.of(JULY_4));
        verify(client, times(1)).query(anyString());
        verifyNoMoreInteractions(client);
    }

    @Test
    void constantsCarryTheOriginalNamesAndQuery() {
        assertThat(WATERMARK_KEY).isEqualTo("timestamp");
        assertThat(JOB_NAME).isEqualTo("watermarkingBatch");
        assertThat(STEP_NAME).isEqualTo("Batch_Step");
        assertThat(SELECTOR_FIELD).isEqualTo("LastModifiedDate");
        assertThat(QUERY_PREFIX).isEqualTo("SELECT Id,Name,LastModifiedDate FROM Contact WHERE LastModifiedDate > ");
        assertThat(LOG_PREFIX).isEqualTo("Contact recently modified: ");
    }

    @Test
    void buildQueryAppendsTheTimestampToThePrefix() {
        assertThat(ContactSyncBatchService.buildQuery("X")).isEqualTo(QUERY_PREFIX + "X");
        assertThatNullPointerException().isThrownBy(() -> ContactSyncBatchService.buildQuery(null))
                .withMessage("timestamp must not be null");
    }

    @Test
    void constructorRejectsMissingCollaboratorsAndABlankDefault() {
        BatchJobRunner runner = new BatchJobRunner();

        assertThatNullPointerException()
                .isThrownBy(() -> new ContactSyncBatchService(null, runner, store, YESTERDAY))
                .withMessage("salesforcePartnerClient must not be null");
        assertThatNullPointerException()
                .isThrownBy(() -> new ContactSyncBatchService(client, null, store, YESTERDAY))
                .withMessage("batchJobRunner must not be null");
        assertThatNullPointerException()
                .isThrownBy(() -> new ContactSyncBatchService(client, runner, null, YESTERDAY))
                .withMessage("watermarkStore must not be null");
        assertThatNullPointerException()
                .isThrownBy(() -> new ContactSyncBatchService(client, runner, store, null))
                .withMessage("defaultWatermark must not be null");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContactSyncBatchService(client, runner, store, " "))
                .withMessage("watermark.default-value must not be blank");
    }

    private Path storeFile() {
        return tempDir.resolve("watermark.properties");
    }

    /** A Contact as {@code SalesforcePartnerClient.toMap} returns it; {@code lastModified} {@code null} omits it. */
    private static Map<String, Object> contact(String id, String name, String lastModified) {
        Map<String, Object> record = new HashMap<>();
        record.put("type", "Contact");
        record.put("Id", id);
        record.put("Name", name);
        if (lastModified != null) {
            record.put(SELECTOR_FIELD, lastModified);
        }
        return record;
    }

    /** A mutable record list, as the client returns; elements may be {@code null}. */
    @SafeVarargs
    private static List<Map<String, Object>> records(Map<String, Object>... contacts) {
        return new ArrayList<>(Arrays.asList(contacts));
    }

    /** Makes every query return {@code contacts}, in that order. */
    @SafeVarargs
    private void stubQuery(Map<String, Object>... contacts) throws Exception {
        when(client.query(anyString())).thenReturn(records(contacts));
    }

    /** Verifies {@code polls} queries were made and returns their SOQL text in call order. */
    private List<String> capturedQueries(int polls) throws Exception {
        ArgumentCaptor<String> soql = ArgumentCaptor.forClass(String.class);
        verify(client, times(polls)).query(soql.capture());
        return soql.getAllValues();
    }

    /** The {@code Batch_Step} lines: INFO events of the service's logger that start with {@code LOG_PREFIX}. */
    private List<String> stepLines() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith(LOG_PREFIX))
                .toList();
    }

    private static void assertSuccessful(BatchJobResult result, long records) {
        assertThat(result.jobName()).isEqualTo(JOB_NAME);
        assertThat(result.jobInstanceId()).isNotBlank();
        assertThat(result.status()).isEqualTo(BatchJobResult.Status.SUCCESSFUL);
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.loadedRecords()).isEqualTo(records);
        assertThat(result.processedRecords()).isEqualTo(records);
        assertThat(result.successfulRecords()).isEqualTo(records);
        assertThat(result.failedRecords()).isZero();
    }

    /** File name to content of every file under the temporary directory, so a created or rewritten file shows. */
    private Map<String, String> directorySnapshot() throws IOException {
        Map<String, String> snapshot = new TreeMap<>();
        try (Stream<Path> files = Files.list(tempDir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                snapshot.put(file.getFileName().toString(), Files.readString(file));
            }
        }
        return snapshot;
    }
}
