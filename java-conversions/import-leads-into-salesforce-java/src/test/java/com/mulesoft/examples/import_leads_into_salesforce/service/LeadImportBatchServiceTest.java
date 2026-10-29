/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_leads_into_salesforce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.mulesoft.examples.import_leads_into_salesforce.client.SalesforcePartnerClient;
import com.mulesoft.examples.import_leads_into_salesforce.client.UpstreamSystem;
import com.mulesoft.examples.import_leads_into_salesforce.exception.UpstreamUnavailableException;
import com.mulesoft.examples.import_leads_into_salesforce.mapper.DwCsvReader;
import com.mulesoft.examples.import_leads_into_salesforce.mapper.LeadMapper;
import com.mulesoft.examples.import_leads_into_salesforce.model.BatchJobResult;
import com.mulesoft.examples.import_leads_into_salesforce.model.BatchJobResult.Status;
import com.mulesoft.examples.import_leads_into_salesforce.service.BatchJobRunner.AcceptPolicy;
import com.mulesoft.examples.import_leads_into_salesforce.service.BatchJobRunner.JobDefinition;
import com.mulesoft.examples.import_leads_into_salesforce.service.BatchJobRunner.Step;
import com.sforce.ws.ConnectionException;

/**
 * Unit test of {@link LeadImportBatchService}, the batch job {@code CreateLeadsBatch} of
 * {@code import-leads-into-salesforce.xml} (lines 8-46), with the real {@link DwCsvReader}, {@link LeadMapper} and
 * SC-7 {@link BatchJobRunner} and a Mockito-mocked {@link SalesforcePartnerClient}; no Spring context, network or
 * credentials.
 *
 * <p>The original {@code ImportLeadsToSfdcIT} dropped {@code leads.csv} and checked one field of each created Lead
 * ({@code FirstName} {@code Ishmael} for {@code faucibus@egetmetus.org}, {@code LastName} {@code Burks} for
 * {@code sem.egestas@mollis.org}). This test fixes every branch of the job: the {@code LeadExistsStep} lookup and its
 * escaped SOQL, the {@code LeadInsertStep} accept expression and 200-record commit groups, the
 * {@code LogFailuresStep} for records failed by a lookup or by a commit, {@code max-failed-records} 1000, and the
 * three INFO logger messages, asserted on the service's own logger.
 *
 * <p>A vendor failure is a record failure, never a service exception, and every lookup and create is made exactly
 * once: retries belong only to the SC-9 executor inside the client (AAP 0.4.8).
 */
class LeadImportBatchServiceTest {

    private static final String HEADER = "Company,FirstName,LastName,Birthday,Email";

    private static final String EMAIL1 = "faucibus@egetmetus.org";

    private static final String EMAIL2 = "sem.egestas@mollis.org";

    /** {@code #[payload]} of the first {@code leads.csv} record, as the original loggers printed it. */
    private static final String ISHMAEL =
            "{Company=Aliquam Tincidunt Nunc LLC, Email=faucibus@egetmetus.org, FirstName=Ishmael, LastName=Alexander}";

    /** {@code #[payload]} of the second {@code leads.csv} record. */
    private static final String BURKS =
            "{Company=Eu Odio PC, Email=sem.egestas@mollis.org, FirstName=Cole, LastName=Burks}";

    private SalesforcePartnerClient client;

    private LeadImportBatchService service;

    private Logger serviceLogger;

    private Level previousLevel;

    private boolean previousAdditive;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() throws Exception {
        client = mock(SalesforcePartnerClient.class);
        when(client.query(anyString())).thenReturn(List.of());
        when(client.create(anyString(), anyList())).thenReturn(List.of());
        service = new LeadImportBatchService(new DwCsvReader(), new LeadMapper(), client, new BatchJobRunner());

        serviceLogger = (Logger) LoggerFactory.getLogger(LeadImportBatchService.class);
        previousLevel = serviceLogger.getLevel();
        previousAdditive = serviceLogger.isAdditive();
        serviceLogger.setLevel(Level.INFO);
        // The appender records every line; detaching the console keeps the 1000-record runs out of the build log.
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
    void bothLeadsOfTheClasspathFileAreNewAndCreatedInOneCommit() throws Exception {
        BatchJobResult result = service.importLeads(leadsCsv());

        // LeadExistsStep takes every record before LeadInsertStep commits its group.
        InOrder order = inOrder(client);
        order.verify(client).query(soql(EMAIL1));
        order.verify(client).query(soql(EMAIL2));
        order.verify(client).create(eq("Lead"), anyList());
        List<List<Map<String, Object>>> groups = createdGroups(1);
        verifyNoMoreInteractions(client);

        assertThat(groups.get(0)).hasSize(2);
        // Entries are compared in order: the four keys of the script, Birthday dropped.
        assertIshmael(groups.get(0).get(0));
        assertBurks(groups.get(0).get(1));
        assertCounts(result, Status.SUCCESSFUL, 2, 2, 2, 0);
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.jobInstanceId()).isNotBlank();
        assertThat(infoLines()).containsExactly(
                "Got Record " + ISHMAEL + ", it exists false",
                "Got Record " + BURKS + ", it exists false",
                "2 Loaded Records 0 Failed Records");
    }

    @Test
    void leadWhoseEmailAlreadyExistsIsNotCreatedAgain() throws Exception {
        when(client.query(soql(EMAIL1))).thenReturn(List.<Map<String, Object>>of(Map.of("Id", "00Q000000000001AAA")));

        BatchJobResult result = service.importLeads(leadsCsv());

        verify(client, times(1)).query(soql(EMAIL1));
        verify(client, times(1)).query(soql(EMAIL2));
        List<List<Map<String, Object>>> groups = createdGroups(1);
        verifyNoMoreInteractions(client);

        assertThat(groups.get(0)).hasSize(1);
        assertBurks(groups.get(0).get(0));
        // A skipped record is not a failed one: the accept expression rejected it, nothing failed.
        assertCounts(result, Status.SUCCESSFUL, 2, 2, 2, 0);
        assertThat(infoLines()).containsExactly(
                "Got Record " + BURKS + ", it exists false",
                "2 Loaded Records 0 Failed Records");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vendorFailures")
    void failedLookupFailsOnlyItsRecordAndIsNotRetried(String description, Exception failure) throws Exception {
        when(client.query(soql(EMAIL1))).thenThrow(failure);

        // The failure becomes a record failure, so the run returns normally.
        BatchJobResult result = service.importLeads(leadsCsv());

        verify(client, times(1)).query(soql(EMAIL1));
        verify(client, times(1)).query(soql(EMAIL2));
        List<List<Map<String, Object>>> groups = createdGroups(1);
        verifyNoMoreInteractions(client);

        assertThat(groups.get(0)).hasSize(1);
        assertBurks(groups.get(0).get(0));
        // One failure is within max-failed-records 1000, so the job still completes successfully.
        assertCounts(result, Status.SUCCESSFUL, 2, 2, 1, 1);
        assertThat(infoLines()).containsExactly(
                "Got Record " + BURKS + ", it exists false",
                "Got Failure " + ISHMAEL,
                "2 Loaded Records 1 Failed Records");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vendorFailures")
    void failedCreateFailsEveryRecordOfTheGroupAndIsNotRetried(String description, Exception failure)
            throws Exception {
        when(client.create(anyString(), anyList())).thenThrow(failure);

        BatchJobResult result = service.importLeads(leadsCsv());

        verify(client, times(1)).query(soql(EMAIL1));
        verify(client, times(1)).query(soql(EMAIL2));
        List<List<Map<String, Object>>> groups = createdGroups(1);
        verifyNoMoreInteractions(client);

        assertThat(groups.get(0)).hasSize(2);
        assertIshmael(groups.get(0).get(0));
        assertBurks(groups.get(0).get(1));
        assertCounts(result, Status.SUCCESSFUL, 2, 2, 0, 2);
        assertThat(infoLines()).containsExactly(
                "Got Record " + ISHMAEL + ", it exists false",
                "Got Record " + BURKS + ", it exists false",
                "Got Failure " + ISHMAEL,
                "Got Failure " + BURKS,
                "2 Loaded Records 2 Failed Records");
    }

    static Stream<Arguments> vendorFailures() {
        return Stream.of(
                Arguments.of("timeout classified by SC-9",
                        new UpstreamUnavailableException(UpstreamSystem.SALESFORCE, "Read timed out",
                                new SocketTimeoutException("Read timed out"))),
                Arguments.of("other vendor failure, the checked WSC exception",
                        new ConnectionException("Salesforce rejected the request")));
    }

    @Test
    void moreThanCommitSizeNewLeadsAreCreatedInGroupsOfTwoHundredWithASmallerLastGroup() throws Exception {
        BatchJobResult result = service.importLeads(csv(generatedRows(201)));

        verify(client, times(201)).query(anyString());
        List<List<Map<String, Object>>> groups = createdGroups(2);
        verifyNoMoreInteractions(client);

        assertThat(groups).extracting(List::size).containsExactly(200, 1);
        assertThat(groups.stream().flatMap(List::stream).map(lead -> lead.get("Email")).toList())
                .isEqualTo(IntStream.rangeClosed(1, 201).mapToObj(i -> "lead" + i + "@example.com").toList());
        assertThat(groups.get(1).get(0)).containsExactly(
                entry("Company", "Company201"),
                entry("Email", "lead201@example.com"),
                entry("FirstName", "First201"),
                entry("LastName", "Last201"));
        assertCounts(result, Status.SUCCESSFUL, 201, 201, 201, 0);
        assertThat(infoLines()).hasSize(202).endsWith("201 Loaded Records 0 Failed Records");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("soqlLiterals")
    void emailIsPlacedEscapedInsideTheSoqlStringLiteral(String description, String row, String email, String soql)
            throws Exception {
        service.importLeads(csv(List.of(row)));

        verify(client, times(1)).query(soql);
        List<List<Map<String, Object>>> groups = createdGroups(1);
        verifyNoMoreInteractions(client);

        // The created record keeps the address as read; only the query literal is escaped.
        assertThat(groups.get(0)).singleElement().satisfies(lead -> assertThat(lead).containsEntry("Email", email));
    }

    static Stream<Arguments> soqlLiterals() {
        return Stream.of(
                Arguments.of("legitimate address unchanged",
                        "Acme,Olive,Hara,01/01/2000,olive.hara@example.com",
                        "olive.hara@example.com",
                        "SELECT Id FROM Lead WHERE Email = 'olive.hara@example.com'"),
                // The CSV reader takes a backslash as an escape, so the row writes \\ for the one backslash read.
                Arguments.of("quote and backslash escaped",
                        "Acme,Olive,O'Hara,01/01/2000,o'hara\\\\test@example.com",
                        "o'hara\\test@example.com",
                        "SELECT Id FROM Lead WHERE Email = 'o\\'hara\\\\test@example.com'"),
                Arguments.of("backslash escaped before the quote, so \\' cannot end the literal",
                        "Acme,Olive,Hara,01/01/2000,a\\\\'b@example.com",
                        "a\\'b@example.com",
                        "SELECT Id FROM Lead WHERE Email = 'a\\\\\\'b@example.com'"),
                Arguments.of("missing Email column rendered null, as MEL interpolation did",
                        "Acme,Olive,Hara,01/01/2000",
                        null,
                        "SELECT Id FROM Lead WHERE Email = 'null'"));
    }

    @Test
    void headerOnlyFileLoadsNoRecordAndNeverCallsSalesforce() throws Exception {
        BatchJobResult result = service.importLeads(csv(List.of()));

        // No lookup runs and the runner never commits an empty group.
        verifyNoInteractions(client);
        assertCounts(result, Status.SUCCESSFUL, 0, 0, 0, 0);
        assertThat(infoLines()).containsExactly("0 Loaded Records 0 Failed Records");
    }

    @ParameterizedTest(name = "{0} failed lookups end the job with {1}")
    @CsvSource({"1000, SUCCESSFUL, 1000", "1001, FAILED_PROCESS_RECORDS, 0"})
    void jobStopsOnlyOnceMoreThanMaxFailedRecordsHaveFailed(int records, Status status, int loggedFailures)
            throws Exception {
        when(client.query(anyString())).thenThrow(new UpstreamUnavailableException(UpstreamSystem.SALESFORCE,
                "Read timed out", new SocketTimeoutException("Read timed out")));

        BatchJobResult result = service.importLeads(csv(generatedRows(records)));

        verify(client, times(records)).query(anyString());
        verifyNoMoreInteractions(client);
        assertCounts(result, status, records, records, 0, records);
        // A stopped job never reaches LogFailuresStep; on-complete still runs.
        List<String> lines = infoLines();
        assertThat(lines).filteredOn(line -> line.startsWith("Got Failure ")).hasSize(loggedFailures);
        assertThat(lines).noneMatch(line -> line.startsWith("Got Record "));
        assertThat(lines).endsWith(records + " Loaded Records " + records + " Failed Records");
    }

    @Test
    void constantsCarryTheOriginalJobConfiguration() {
        assertThat(LeadImportBatchService.JOB_NAME).isEqualTo("CreateLeadsBatch");
        assertThat(LeadImportBatchService.MAX_FAILED_RECORDS).isEqualTo(1000L);
        assertThat(LeadImportBatchService.COMMIT_SIZE).isEqualTo(200);
        assertThat(LeadImportBatchService.LEAD_EXISTS_STEP).isEqualTo("LeadExistsStep");
        assertThat(LeadImportBatchService.LEAD_INSERT_STEP).isEqualTo("LeadInsertStep");
        assertThat(LeadImportBatchService.LOG_FAILURES_STEP).isEqualTo("LogFailuresStep");
        assertThat(LeadImportBatchService.EXISTS_VAR).isEqualTo("exists");
        assertThat(LeadImportBatchService.LEAD_TYPE).isEqualTo("Lead");
    }

    @Test
    void jobDefinitionIsBuiltOnceWithTheOriginalStepsInOrder() {
        JobDefinition<Map<String, Object>> job = service.jobDefinition();

        assertThat(service.jobDefinition()).isSameAs(job);
        assertThat(job.name()).isEqualTo("CreateLeadsBatch");
        assertThat(job.maxFailedRecords()).isEqualTo(1000L);
        List<Step<Map<String, Object>>> steps = job.steps();
        assertThat(steps).extracting(step -> step.name())
                .containsExactly("LeadExistsStep", "LeadInsertStep", "LogFailuresStep");
        assertThat(steps).extracting(step -> step.acceptPolicy())
                .containsExactly(AcceptPolicy.NO_FAILURES, AcceptPolicy.NO_FAILURES, AcceptPolicy.ONLY_FAILURES);
        assertThat(steps).extracting(step -> step.hasCommit()).containsExactly(false, true, false);
        assertThat(steps.get(1).commitSize()).isEqualTo(200);
        assertThat(job.onComplete()).isPresent();
    }

    @Test
    void nullFileIsRejectedBeforeAnyRecordExists() {
        assertThatNullPointerException().isThrownBy(() -> service.importLeads(null)).withMessage("csv");

        verifyNoInteractions(client);
        assertThat(infoLines()).isEmpty();
    }

    @ParameterizedTest(name = "null {0}")
    @MethodSource("collaboratorsWithOneNull")
    void constructorRejectsEveryNullCollaborator(String name, DwCsvReader reader, LeadMapper mapper,
            SalesforcePartnerClient partnerClient, BatchJobRunner runner) {
        assertThatNullPointerException()
                .isThrownBy(() -> new LeadImportBatchService(reader, mapper, partnerClient, runner))
                .withMessage(name);
    }

    static Stream<Arguments> collaboratorsWithOneNull() {
        DwCsvReader reader = new DwCsvReader();
        LeadMapper mapper = new LeadMapper();
        SalesforcePartnerClient partnerClient = mock(SalesforcePartnerClient.class);
        BatchJobRunner runner = new BatchJobRunner();
        return Stream.of(
                Arguments.of("dwCsvReader", null, mapper, partnerClient, runner),
                Arguments.of("leadMapper", reader, null, partnerClient, runner),
                Arguments.of("salesforcePartnerClient", reader, mapper, null, runner),
                Arguments.of("batchJobRunner", reader, mapper, partnerClient, null));
    }

    private static void assertIshmael(Map<String, Object> lead) {
        assertThat(lead).containsExactly(
                entry("Company", "Aliquam Tincidunt Nunc LLC"),
                entry("Email", EMAIL1),
                entry("FirstName", "Ishmael"),
                entry("LastName", "Alexander"));
    }

    private static void assertBurks(Map<String, Object> lead) {
        assertThat(lead).containsExactly(
                entry("Company", "Eu Odio PC"),
                entry("Email", EMAIL2),
                entry("FirstName", "Cole"),
                entry("LastName", "Burks"));
    }

    private static void assertCounts(BatchJobResult result, Status status, long loaded, long processed,
            long successful, long failed) {
        assertThat(result.jobName()).isEqualTo("CreateLeadsBatch");
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.loadedRecords()).isEqualTo(loaded);
        assertThat(result.processedRecords()).isEqualTo(processed);
        assertThat(result.successfulRecords()).isEqualTo(successful);
        assertThat(result.failedRecords()).isEqualTo(failed);
    }

    /** Verifies the number of {@code create} calls of type {@code Lead} and returns their record groups in order. */
    private List<List<Map<String, Object>>> createdGroups(int calls) throws Exception {
        ArgumentCaptor<List<Map<String, Object>>> records = recordsCaptor();
        verify(client, times(calls)).create(eq("Lead"), records.capture());
        return records.getAllValues();
    }

    /** The formatted messages of the service logger's INFO events, in logging order. */
    private List<String> infoLines() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** The query {@code LeadExistsStep} sends for an address that is already escaped. */
    private static String soql(String escapedEmail) {
        return "SELECT Id FROM Lead WHERE Email = '" + escapedEmail + "'";
    }

    /** {@code leads.csv} as the file poller reads it: the original bytes, CR record separators. */
    private static byte[] leadsCsv() throws IOException {
        try (InputStream in = LeadImportBatchServiceTest.class.getResourceAsStream("/leads.csv")) {
            if (in == null) {
                throw new IllegalStateException("Classpath resource /leads.csv is missing");
            }
            return in.readAllBytes();
        }
    }

    /** A CSV file with the {@code leads.csv} header followed by {@code rows}, LF-separated, UTF-8 encoded. */
    private static byte[] csv(List<String> rows) {
        return rows.stream()
                .map(row -> "\n" + row)
                .collect(Collectors.joining("", HEADER, ""))
                .getBytes(StandardCharsets.UTF_8);
    }

    /** {@code count} rows of distinct new leads, numbered from 1. */
    private static List<String> generatedRows(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> "Company" + i + ",First" + i + ",Last" + i + ",01/01/2000,lead" + i + "@example.com")
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<Map<String, Object>>> recordsCaptor() {
        return ArgumentCaptor.forClass((Class<List<Map<String, Object>>>) (Class<?>) List.class);
    }
}

