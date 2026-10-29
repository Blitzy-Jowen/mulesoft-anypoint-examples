/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_leads_into_salesforce.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.import_leads_into_salesforce.client.SalesforcePartnerClient;
import com.mulesoft.examples.import_leads_into_salesforce.mapper.DwCsvReader;
import com.mulesoft.examples.import_leads_into_salesforce.mapper.LeadMapper;
import com.mulesoft.examples.import_leads_into_salesforce.model.BatchJobResult;
import com.mulesoft.examples.import_leads_into_salesforce.service.BatchJobRunner.AcceptPolicy;
import com.mulesoft.examples.import_leads_into_salesforce.service.BatchJobRunner.BatchRecord;
import com.mulesoft.examples.import_leads_into_salesforce.service.BatchJobRunner.JobDefinition;
import com.mulesoft.examples.import_leads_into_salesforce.service.BatchJobRunner.Step;

/**
 * The batch job {@code CreateLeadsBatch} of {@code import-leads-into-salesforce.xml} (lines 8-46), run on the SC-7
 * {@link BatchJobRunner}: every processor after the {@code file:inbound-endpoint} of line 11, which
 * {@code trigger/LeadsFileTrigger} replaces.
 *
 * <p>{@link #importLeads(byte[])} runs one job instance over one polled CSV file:
 * <ul>
 *   <li>{@code batch:input} (lines 10-24): the CSV is read by the SC-4 {@link DwCsvReader} and mapped by
 *       {@link LeadMapper}, the owner of the {@code Transform CSV to Maps} script (lines 12-21); each map is one
 *       record;</li>
 *   <li>{@value #LEAD_EXISTS_STEP} (lines 26-30): {@code SELECT Id FROM Lead WHERE Email = '<Email>'} sets the record
 *       variable {@value #EXISTS_VAR} to whether Salesforce returned a row;</li>
 *   <li>{@value #LEAD_INSERT_STEP} (lines 31-38): records whose {@value #EXISTS_VAR} is not true are logged and handed,
 *       {@value #COMMIT_SIZE} at a time, to one {@code create} of type {@value #LEAD_TYPE};</li>
 *   <li>{@value #LOG_FAILURES_STEP} (lines 39-41): every record that failed in an earlier step is logged;</li>
 *   <li>{@code batch:on-complete} (lines 43-45): the loaded and failed record counts are logged.</li>
 * </ul>
 *
 * <p>The flow declares no exception strategy, so nothing is caught here. A Salesforce failure inside a step fails the
 * record, or the whole commit group, through the runner and reaches {@value #LOG_FAILURES_STEP}; the SC-9 executor
 * inside {@link SalesforcePartnerClient} has already classified it, re-logged in and retried once on a rejected
 * session, which Salesforce refused before processing, and logged a rate limit, timeout or connectivity failure at
 * ERROR without retrying it, so a {@code Lead} create Salesforce may already have processed is never submitted
 * again. A failure of the input phase propagates unchanged to the file poller.
 *
 * <p>The job definition is built once and is immutable, and every run keeps its state inside the runner, so one
 * instance serves concurrent callers.
 */
@Service
public class LeadImportBatchService {

    /** The {@code batch:job name} (line 8). */
    public static final String JOB_NAME = "CreateLeadsBatch";

    /** The {@code max-failed-records} of the job (line 8): the job stops once more records than this have failed. */
    public static final long MAX_FAILED_RECORDS = 1000;

    /** The {@code batch:commit size} of {@value #LEAD_INSERT_STEP} (line 33). */
    public static final int COMMIT_SIZE = 200;

    /** The step that looks each lead up by e-mail address (line 26). */
    public static final String LEAD_EXISTS_STEP = "LeadExistsStep";

    /** The step that creates the leads not found (line 31). */
    public static final String LEAD_INSERT_STEP = "LeadInsertStep";

    /** The step that logs the records failed in an earlier step (line 39). */
    public static final String LOG_FAILURES_STEP = "LogFailuresStep";

    /** The record variable the enricher of {@value #LEAD_EXISTS_STEP} sets, {@code recordVars['exists']} (line 27). */
    public static final String EXISTS_VAR = "exists";

    /** The sObject type of the {@code sfdc:create} (line 34). */
    public static final String LEAD_TYPE = "Lead";

    private static final Logger LOG = LoggerFactory.getLogger(LeadImportBatchService.class);

    private final DwCsvReader dwCsvReader;
    private final LeadMapper leadMapper;
    private final SalesforcePartnerClient salesforcePartnerClient;
    private final BatchJobRunner batchJobRunner;
    private final JobDefinition<Map<String, Object>> jobDefinition;

    /**
     * Creates the service and its job definition, reused by every run.
     *
     * @param dwCsvReader             the DataWeave 1.0 CSV reader of the input phase
     * @param leadMapper              the owner of the {@code Transform CSV to Maps} script (lines 12-21)
     * @param salesforcePartnerClient the only path to Salesforce, the {@code sfdc:config} of line 4
     * @param batchJobRunner          the SC-7 runner that gives the job Mule's batch semantics
     * @throws NullPointerException if an argument is {@code null}
     */
    public LeadImportBatchService(DwCsvReader dwCsvReader, LeadMapper leadMapper,
            SalesforcePartnerClient salesforcePartnerClient, BatchJobRunner batchJobRunner) {
        this.dwCsvReader = Objects.requireNonNull(dwCsvReader, "dwCsvReader");
        this.leadMapper = Objects.requireNonNull(leadMapper, "leadMapper");
        this.salesforcePartnerClient = Objects.requireNonNull(salesforcePartnerClient, "salesforcePartnerClient");
        this.batchJobRunner = Objects.requireNonNull(batchJobRunner, "batchJobRunner");
        this.jobDefinition = JobDefinition.<Map<String, Object>>builder(JOB_NAME)
                .maxFailedRecords(MAX_FAILED_RECORDS)
                .step(Step.<Map<String, Object>>builder(LEAD_EXISTS_STEP)
                        .processor(this::findLead)
                        .build())
                .step(Step.<Map<String, Object>>builder(LEAD_INSERT_STEP)
                        .acceptExpression(record -> !Boolean.TRUE.equals(record.recordVars().get(EXISTS_VAR)))
                        .processor(record -> LOG.info("Got Record {}, it exists {}", record.getPayload(),
                                record.recordVars().get(EXISTS_VAR)))
                        .commit(COMMIT_SIZE, this::createLeads)
                        .build())
                .step(Step.<Map<String, Object>>builder(LOG_FAILURES_STEP)
                        .acceptPolicy(AcceptPolicy.ONLY_FAILURES)
                        .processor(record -> LOG.info("Got Failure {}", record.getPayload()))
                        .build())
                .onComplete(result -> LOG.info("{} Loaded Records {} Failed Records", result.loadedRecords(),
                        result.failedRecords()))
                .build();
    }

    /**
     * Imports one polled CSV file as Salesforce Leads by running one instance of {@value #JOB_NAME}.
     *
     * <p>The CSV is read and mapped inside the job's input phase (lines 10-24), so a file that cannot be read or
     * mapped ends the run before any record exists: no step and no on-complete runs, and the exception leaves this
     * method unchanged.
     *
     * @param csv the bytes of the polled file, UTF-8 encoded CSV with a header row
     * @return the runner's result of the run, unchanged: loaded, processed, successful and failed counts and status
     * @throws NullPointerException if {@code csv} is {@code null}
     * @throws Exception            the exception of the input phase, unchanged
     */
    public BatchJobResult importLeads(byte[] csv) throws Exception {
        Objects.requireNonNull(csv, "csv");
        return batchJobRunner.run(jobDefinition, () -> leadMapper.map(dwCsvReader.read(csv)));
    }

    /**
     * Returns the job definition, built once by the constructor.
     *
     * @return the immutable definition of {@value #JOB_NAME}
     */
    JobDefinition<Map<String, Object>> jobDefinition() {
        return jobDefinition;
    }

    /**
     * Escapes a value for the inside of a SOQL single-quoted string literal, without adding the quotes: each
     * backslash becomes {@code \\} first, then each single quote becomes {@code \'}, so an inserted escape is never
     * escaped again.
     *
     * <p>The original interpolated the e-mail address into the query unescaped (line 28). Escaping leaves the query of
     * every legitimate address unchanged and keeps a hostile one from ending the literal; this is the only intended
     * difference for such input. A {@code null} value renders as {@code null}, as MEL string interpolation did; that
     * rendering is a Tier 2A verification point.
     *
     * @param value the value placed between the quotes; may be {@code null}
     * @return {@link String#valueOf(Object)} of the value, escaped
     */
    static String escapeSoqlLiteral(Object value) {
        return String.valueOf(value).replace("\\", "\\\\").replace("'", "\\'");
    }

    /**
     * {@value #LEAD_EXISTS_STEP} (lines 26-30): the enricher's {@code payload.size() > 0} over the {@code sfdc:query}
     * result, stored in the record variable {@value #EXISTS_VAR}. A failing query fails the record in this step.
     */
    private void findLead(BatchRecord<Map<String, Object>> record) throws Exception {
        String soql = "SELECT Id FROM Lead WHERE Email = '"
                + escapeSoqlLiteral(record.getPayload().get(LeadMapper.EMAIL)) + "'";
        record.recordVars().put(EXISTS_VAR, !salesforcePartnerClient.query(soql).isEmpty());
    }

    /**
     * The {@code batch:commit} of {@value #LEAD_INSERT_STEP} (lines 33-37): one {@code create} for the group. The save
     * results are not inspected, because the original commit ignored them: a lead Salesforce rejects in its save result
     * does not fail its record, while a thrown exception fails every record of the group.
     */
    private void createLeads(List<Map<String, Object>> group) throws Exception {
        LOG.debug("Creating {} {} record(s) in Salesforce", group.size(), LEAD_TYPE);
        salesforcePartnerClient.create(LEAD_TYPE, group);
    }
}
