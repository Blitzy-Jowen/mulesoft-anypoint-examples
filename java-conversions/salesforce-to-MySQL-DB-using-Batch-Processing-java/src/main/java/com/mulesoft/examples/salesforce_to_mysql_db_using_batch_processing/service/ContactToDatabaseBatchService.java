package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.service;

import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.client.ContactRepository;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.client.SalesforcePartnerClient;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.mapper.ContactRowMapper;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.model.BatchJobResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * One run of the original flow {@code triggerFlow} and its batch job {@code salesforce-to-database-Batch}
 * ({@code salesforce-to-database.xml}): read the {@code timestamp} watermark, query the Contacts Salesforce modified
 * after it, log the watermark, run every Contact through the job's two steps, which insert or update its row in the
 * {@code contact} table, then, only after a successful job, move the watermark to the latest {@code LastModifiedDate}
 * the query returned.
 *
 * <p>The 60 s poll schedule belongs to {@code trigger/ContactPollScheduler}; this class performs one poll per
 * {@link #synchronize()} call. The original declares no exception strategy, so nothing is caught here: every
 * Salesforce call runs through the SC-9 executor inside {@link SalesforcePartnerClient}, which classifies and logs a
 * failure, and the failure propagates to the trigger with the watermark unchanged. A database failure of one record
 * fails only that record, as {@link BatchJobRunner} records it, and stops the job; the watermark then stays as it was,
 * so the next scheduled poll selects the failed and unprocessed Contacts again. Nothing is retried within a poll.
 *
 * <p>The original {@code batch:threading-profile} is not carried: records are processed in order on the calling
 * thread, as {@link BatchJobRunner} documents.
 */
@Service
public class ContactToDatabaseBatchService {

    private static final Logger log = LoggerFactory.getLogger(ContactToDatabaseBatchService.class);

    /** The watermark {@code variable} of the original poll. */
    public static final String WATERMARK_KEY = "timestamp";

    /** The format of the watermark's {@code default-expression}, which is also Salesforce's UTC date-time form. */
    public static final String WATERMARK_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";

    /** The original {@code batch:job} name. */
    public static final String JOB_NAME = "salesforce-to-database-Batch";

    /** The first original {@code batch:step}: map the record, then look its email up in the database. */
    public static final String QUERY_STEP = "queryExistingContactInDbStep";

    /** The second original {@code batch:step}: insert or update the row. */
    public static final String UPSERT_STEP = "upsertContactInDbStep";

    /** Record variable {@code recordVars['exists']}: whether the lookup found a row. */
    public static final String EXISTS_VAR = "exists";

    /** Record variable {@code recordVars['dbRecord']}: the rows the lookup returned. */
    public static final String DB_RECORD_VAR = "dbRecord";

    /** The record field the watermark's {@code MAX} selector reads ({@code #[payload.LastModifiedDate]}). */
    public static final String SELECTOR_FIELD = "LastModifiedDate";

    /** The original {@code sfdc:query} text up to the watermark it appends; its {@code dsql:} marker is dropped. */
    public static final String QUERY_PREFIX =
            "SELECT Email,FirstName,LastModifiedDate,LastName FROM Contact WHERE LastModifiedDate > ";

    /** The text the on-complete logger writes. */
    public static final String ON_COMPLETE_MESSAGE = "Batch sf->db has finished";

    /** Formats the default watermark, and selector values that do not arrive as text, in UTC. */
    private static final DateTimeFormatter WATERMARK_FORMAT =
            DateTimeFormatter.ofPattern(WATERMARK_PATTERN).withZone(ZoneOffset.UTC);

    /** How far the default watermark lies before now: the original's {@code System.currentTimeMillis() - 10000}. */
    private static final long DEFAULT_WATERMARK_LAG_SECONDS = 10;

    private final SalesforcePartnerClient salesforcePartnerClient;

    private final BatchJobRunner batchJobRunner;

    private final WatermarkStore watermarkStore;

    private final ContactRowMapper contactRowMapper;

    private final ContactRepository contactRepository;

    private final Clock clock;

    private final BatchJobRunner.JobDefinition<Map<String, Object>> job;

    /**
     * Creates the service and its immutable job definition, reused by every poll.
     *
     * @param salesforcePartnerClient the SC-15 Salesforce client
     * @param batchJobRunner          the SC-7 batch runner
     * @param watermarkStore          the SC-8 watermark store
     * @param contactRowMapper        the mapper of the step's DataWeave script
     * @param contactRepository       the only access path to the {@code contact} table
     * @param clockProvider           supplies the clock of the default watermark; the system UTC clock when no
     *                                {@link Clock} bean exists, as in production
     * @throws NullPointerException if an argument is {@code null}
     */
    public ContactToDatabaseBatchService(SalesforcePartnerClient salesforcePartnerClient,
            BatchJobRunner batchJobRunner, WatermarkStore watermarkStore, ContactRowMapper contactRowMapper,
            ContactRepository contactRepository, ObjectProvider<Clock> clockProvider) {
        this.salesforcePartnerClient =
                Objects.requireNonNull(salesforcePartnerClient, "salesforcePartnerClient must not be null");
        this.batchJobRunner = Objects.requireNonNull(batchJobRunner, "batchJobRunner must not be null");
        this.watermarkStore = Objects.requireNonNull(watermarkStore, "watermarkStore must not be null");
        this.contactRowMapper = Objects.requireNonNull(contactRowMapper, "contactRowMapper must not be null");
        this.contactRepository = Objects.requireNonNull(contactRepository, "contactRepository must not be null");
        this.clock = Objects.requireNonNull(clockProvider, "clockProvider must not be null")
                .getIfAvailable(Clock::systemUTC);
        // The original job declares neither max-failed-records nor an accept policy, expression or batch:commit, so
        // the Mule defaults apply: 0 tolerated failures, NO_FAILURES, every record accepted, no commit.
        this.job = BatchJobRunner.JobDefinition.<Map<String, Object>>builder(JOB_NAME)
                .maxFailedRecords(0)
                .step(BatchJobRunner.Step.<Map<String, Object>>builder(QUERY_STEP)
                        .acceptPolicy(BatchJobRunner.AcceptPolicy.NO_FAILURES)
                        .processor(this::queryExisting)
                        .build())
                .step(BatchJobRunner.Step.<Map<String, Object>>builder(UPSERT_STEP)
                        .acceptPolicy(BatchJobRunner.AcceptPolicy.NO_FAILURES)
                        .processor(this::upsert)
                        .build())
                .onComplete(result -> log.info(ON_COMPLETE_MESSAGE))
                .build();
    }

    /**
     * Builds the original query for a watermark value.
     *
     * <p>The value is appended unquoted, as the original DSQL did: it is a SOQL date-time literal such as
     * {@code 2015-10-10T10:00:00.123Z}, and it comes only from the default expression or from a
     * {@code LastModifiedDate} Salesforce returned, never from a caller, so there is nothing to escape.
     *
     * @param timestamp the watermark value
     * @return {@code QUERY_PREFIX + timestamp}
     * @throws NullPointerException if {@code timestamp} is {@code null}
     */
    public static String buildQuery(String timestamp) {
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        return QUERY_PREFIX + timestamp;
    }

    /**
     * Performs one poll: watermark, query, watermark logger, batch job, then, after a successful job, the
     * {@code MAX} watermark update.
     *
     * <p>Calls are serialized: the original scheduler never overlapped two runs of its poll, and a poll requested on
     * demand while a scheduled one runs must neither read the watermark before the running poll has moved it nor
     * move it back afterwards.
     *
     * @return the result of the batch job run; unless it is {@code SUCCESSFUL} without a failed record, the watermark
     *         was left as it was
     * @throws Exception the SC-9 upstream exception or the original Salesforce exception of the query, unchanged,
     *                   with the watermark left as it was
     */
    public synchronized BatchJobResult synchronize() throws Exception {
        String timestamp = watermarkStore.retrieve(WATERMARK_KEY, this::defaultWatermark);
        List<Map<String, Object>> records = salesforcePartnerClient.query(buildQuery(timestamp));
        log.info("{}", timestamp);

        BatchJobResult result = batchJobRunner.run(job, () -> records);

        // The watermark moves only after successful processing: a job that stopped or failed a record keeps the
        // stored value, so the next scheduled poll selects this poll's records again instead of skipping those it
        // did not write. The candidates come from the polled records, because step 1 replaces each record's payload.
        // An empty query selects nothing and leaves the watermark untouched.
        if (result.isSuccessful() && result.failedRecords() == 0) {
            watermarkStore.updateMax(WATERMARK_KEY, selectorValues(records));
        }
        return result;
    }

    /**
     * The watermark's {@code default-expression}: now minus 10 s, formatted {@value #WATERMARK_PATTERN} in UTC.
     * Evaluated on every poll while no watermark is stored, and never stored itself.
     */
    private String defaultWatermark() {
        return WATERMARK_FORMAT.format(clock.instant().minusSeconds(DEFAULT_WATERMARK_LAG_SECONDS));
    }

    /**
     * Step {@code queryExistingContactInDbStep}: the DataWeave mapping to the database row, then the enricher's
     * lookup by email, whose result sets {@code recordVars['exists']} ({@code payload.size() > 0}) and
     * {@code recordVars['dbRecord']} ({@code payload}). Any exception fails the record in this step.
     */
    private void queryExisting(BatchJobRunner.BatchRecord<Map<String, Object>> record) {
        Map<String, Object> row = contactRowMapper.map(record.getPayload());
        record.setPayload(row);
        List<Map<String, Object>> rows = contactRepository.findByEmail(asString(row.get(ContactRowMapper.EMAIL)));
        record.recordVars().put(EXISTS_VAR, Boolean.valueOf(!rows.isEmpty()));
        record.recordVars().put(DB_RECORD_VAR, rows);
    }

    /**
     * Step {@code upsertContactInDbStep}: the original choice, its conditions in declaration order.
     *
     * <p>The {@code otherwise} branch is kept as declared although no record reaches it through the job: step 1
     * always sets both record variables, and a record that failed there is not offered to this step under
     * {@code NO_FAILURES}. It is package-visible so that branch can be exercised directly. Any repository exception
     * fails the record in this step.
     *
     * @param record the record, its payload the row step 1 mapped
     */
    void upsert(BatchJobRunner.BatchRecord<Map<String, Object>> record) {
        Map<String, Object> row = record.getPayload();
        Object exists = record.recordVars().get(EXISTS_VAR);
        Object dbRecord = record.recordVars().get(DB_RECORD_VAR);
        if (Boolean.FALSE.equals(exists)) {
            contactRepository.insert(asString(row.get(ContactRowMapper.FIRST_NAME)),
                    asString(row.get(ContactRowMapper.LAST_NAME)), asString(row.get(ContactRowMapper.EMAIL)));
        } else if (Boolean.TRUE.equals(exists) && dbRecord != null) {
            contactRepository.update(asString(row.get(ContactRowMapper.FIRST_NAME)),
                    asString(row.get(ContactRowMapper.LAST_NAME)), asString(row.get(ContactRowMapper.EMAIL)));
        } else {
            log.info("Error with {} contact", asString(row.get(ContactRowMapper.EMAIL)));
        }
    }

    /** Collects the poll's selector values, skipping records without a {@code LastModifiedDate}. */
    private static List<String> selectorValues(List<Map<String, Object>> records) {
        List<String> values = new ArrayList<>(records.size());
        for (Map<String, Object> record : records) {
            Object value = record == null ? null : record.get(SELECTOR_FIELD);
            if (value != null) {
                values.add(render(value));
            }
        }
        return values;
    }

    /**
     * Renders a selector value as the store compares it and the next query appends it: text unchanged, a
     * {@link Calendar} or {@link Date} in Salesforce's UTC form, anything else by {@link Object#toString()}.
     */
    private static String render(Object value) {
        if (value instanceof Calendar calendar) {
            return WATERMARK_FORMAT.format(calendar.toInstant());
        }
        if (value instanceof Date date) {
            // Not date.toInstant(): java.sql.Date throws UnsupportedOperationException there.
            return WATERMARK_FORMAT.format(Instant.ofEpochMilli(date.getTime()));
        }
        return value.toString();
    }

    /** MEL's view of a row value as text: {@code null} stays {@code null}. */
    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
