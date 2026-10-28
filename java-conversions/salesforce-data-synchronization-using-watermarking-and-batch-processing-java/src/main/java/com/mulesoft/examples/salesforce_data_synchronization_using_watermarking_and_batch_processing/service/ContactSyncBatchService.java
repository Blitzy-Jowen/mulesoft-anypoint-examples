package com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.service;

import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.client.SalesforcePartnerClient;
import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.model.BatchJobResult;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * One run of the original flow {@code triggerFlow} and its batch job {@code watermarkingBatch}
 * ({@code watermarking.xml}): read the {@code timestamp} watermark, query the Contacts Salesforce modified after it,
 * run every Contact through the job's single step {@code Batch_Step}, which logs it, then, only after a successful job,
 * move the watermark to the latest {@code LastModifiedDate} the query returned. A job that stopped or failed a record
 * leaves the watermark as it was, so the next scheduled poll selects the same Contacts again; nothing is retried.
 *
 * <p>The 10 s poll schedule belongs to {@code trigger/ContactPollScheduler}; this class performs one poll per
 * {@link #synchronize()} call. The original declares no exception strategy, so nothing is caught here: every Salesforce
 * call runs through the SC-9 executor inside {@link SalesforcePartnerClient}, which classifies and logs a failure, and
 * the failure propagates to the trigger with the watermark unchanged.
 */
@Service
public class ContactSyncBatchService {

    private static final Logger log = LoggerFactory.getLogger(ContactSyncBatchService.class);

    /** The watermark {@code variable} of the original poll. */
    public static final String WATERMARK_KEY = "timestamp";

    /** The original {@code batch:job} name. */
    public static final String JOB_NAME = "watermarkingBatch";

    /** The original {@code batch:step} name. */
    public static final String STEP_NAME = "Batch_Step";

    /** The record field the watermark's {@code MAX} selector reads ({@code #[payload.LastModifiedDate]}). */
    public static final String SELECTOR_FIELD = "LastModifiedDate";

    /** The original {@code sfdc:query} text up to the watermark it appends. */
    public static final String QUERY_PREFIX =
            "SELECT Id,Name,LastModifiedDate FROM Contact WHERE LastModifiedDate > ";

    /** The text the step's logger writes before each record ({@code Contact recently modified: #[payload]}). */
    public static final String LOG_PREFIX = "Contact recently modified: ";

    /** Salesforce's {@code LastModifiedDate} form, for selector values that do not arrive as text. */
    private static final DateTimeFormatter SELECTOR_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final SalesforcePartnerClient salesforcePartnerClient;

    private final BatchJobRunner batchJobRunner;

    private final WatermarkStore watermarkStore;

    private final String defaultWatermark;

    private final BatchJobRunner.JobDefinition<Map<String, Object>> job;

    /**
     * Creates the service and its immutable job definition, reused by every poll.
     *
     * @param salesforcePartnerClient the SC-15 Salesforce client
     * @param batchJobRunner          the SC-7 batch runner
     * @param watermarkStore          the SC-8 watermark store
     * @param defaultWatermark        the watermark's {@code default-expression}, used while none is stored: a SOQL
     *                                date literal such as {@code YESTERDAY} or an ISO-8601 UTC date-time
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code defaultWatermark} is blank, which would leave the query without
     *                                  its comparison value
     */
    public ContactSyncBatchService(SalesforcePartnerClient salesforcePartnerClient, BatchJobRunner batchJobRunner,
            WatermarkStore watermarkStore, @Value("${watermark.default-value:YESTERDAY}") String defaultWatermark) {
        this.salesforcePartnerClient =
                Objects.requireNonNull(salesforcePartnerClient, "salesforcePartnerClient must not be null");
        this.batchJobRunner = Objects.requireNonNull(batchJobRunner, "batchJobRunner must not be null");
        this.watermarkStore = Objects.requireNonNull(watermarkStore, "watermarkStore must not be null");
        this.defaultWatermark = Objects.requireNonNull(defaultWatermark, "defaultWatermark must not be null");
        if (defaultWatermark.isBlank()) {
            throw new IllegalArgumentException("watermark.default-value must not be blank");
        }
        // The original job declares neither max-failed-records nor an accept policy or expression, so the SC-7
        // defaults apply: 0 tolerated failures, NO_FAILURES, every record accepted.
        // The step logs the record's own java.util.HashMap, the type the Mule connector produced, so the key order
        // is HashMap's on the running JDK. The README's sample line ({Name=..., LastModifiedDate=..., Id=...,
        // type=...}) shows JDK 7's HashMap order; JDK 8 and later order the same keys LastModifiedDate, Id, type,
        // Name, as Mule 3.8 on JDK 8 logged them. Re-sorting keys to match the README would not be a HashMap.
        this.job = BatchJobRunner.JobDefinition.<Map<String, Object>>builder(JOB_NAME)
                .step(BatchJobRunner.Step.<Map<String, Object>>builder(STEP_NAME)
                        .processor(record -> log.info("{}{}", LOG_PREFIX, record.getPayload()))
                        .build())
                .build();
    }

    /**
     * Builds the original query for a watermark value.
     *
     * <p>The value is appended unquoted, as the original DSQL did: it is a SOQL date literal or date-time, never a
     * string literal, and it comes only from configuration or from a {@code LastModifiedDate} Salesforce returned,
     * never from a caller, so there is nothing to escape.
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
     * Performs one poll: watermark, query, batch job, then, after a successful job, the {@code MAX} watermark update.
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
        String timestamp = watermarkStore.retrieve(WATERMARK_KEY, () -> defaultWatermark);
        String soql = buildQuery(timestamp);
        log.debug("Querying Salesforce: {}", soql);
        List<Map<String, Object>> records = salesforcePartnerClient.query(soql);

        BatchJobResult result = batchJobRunner.run(job, () -> records);

        // The watermark moves only after successful processing: a job that stopped or failed a record keeps the
        // stored value, so the next scheduled poll selects this poll's records again instead of skipping those it
        // did not process. An empty query selects nothing and leaves the watermark untouched.
        if (result.isSuccessful() && result.failedRecords() == 0) {
            watermarkStore.updateMax(WATERMARK_KEY, selectorValues(records));
        }
        return result;
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
     * Renders a selector value as the store compares it: text unchanged, a date-time type in Salesforce's UTC form,
     * anything else by {@link String#valueOf(Object)}.
     */
    private static String render(Object value) {
        if (value instanceof String text) {
            return text;
        }
        Instant instant = toInstant(value);
        return instant == null ? String.valueOf(value) : SELECTOR_FORMAT.format(instant);
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Calendar calendar) {
            return calendar.toInstant();
        }
        if (value instanceof Date date) {
            // Not date.toInstant(): java.sql.Date throws UnsupportedOperationException there.
            return Instant.ofEpochMilli(date.getTime());
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof ZonedDateTime zonedDateTime) {
            return zonedDateTime.toInstant();
        }
        return null;
    }
}
