package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.model;

import java.util.Objects;

/**
 * Outcome of one {@code BatchJobRunner} run, mirroring the {@code BatchJobResult} that Mule 3.8 batch hands to
 * {@code batch:on-complete} as its payload: the on-complete callback and the caller read the record counts and the
 * final status from it.
 *
 * <p>SC-7 shared component; copy with only the package line changed.
 *
 * <p>The relationships between the counts belong to the runner, so this type checks only nullness and that no count
 * is negative: a run that stops early with {@link Status#FAILED_PROCESS_RECORDS} legitimately leaves
 * {@code processedRecords} below {@code loadedRecords}.
 *
 * @param jobName             the configured job name
 * @param jobInstanceId       a random UUID string per run, produced by the runner
 * @param status              the final state of the run
 * @param loadedRecords       the records the input phase produced
 * @param processedRecords    the records that failed or finished their handling in the job's last step; every loaded
 *                            record when the job ran to the end
 * @param successfulRecords   the processed records minus the failed records; a record a stopped job left unfinished
 *                            is neither successful nor failed
 * @param failedRecords       the records with at least one step failure
 * @param elapsedTimeInMillis the wall-clock duration of the run, in milliseconds
 */
public record BatchJobResult(String jobName, String jobInstanceId, Status status, long loadedRecords,
        long processedRecords, long successfulRecords, long failedRecords, long elapsedTimeInMillis) {

    /** Final state of a batch job run. */
    public enum Status {

        /** The job ran every record through every step and the on-complete callback returned normally. */
        SUCCESSFUL,

        /** Processing stopped because the failed-record count exceeded {@code maxFailedRecords}. */
        FAILED_PROCESS_RECORDS,

        /** The on-complete callback threw. */
        FAILED_ON_COMPLETE
    }

    /**
     * Creates the result of one run.
     *
     * @param jobName             the configured job name
     * @param jobInstanceId       a random UUID string per run, produced by the runner
     * @param status              the final state of the run
     * @param loadedRecords       the records the input phase produced
     * @param processedRecords    the records that failed or finished their handling in the job's last step; every
     *                            loaded record when the job ran to the end
     * @param successfulRecords   the processed records minus the failed records; a record a stopped job left
     *                            unfinished is neither successful nor failed
     * @param failedRecords       the records with at least one step failure
     * @param elapsedTimeInMillis the wall-clock duration of the run, in milliseconds
     * @throws NullPointerException     if {@code jobName}, {@code jobInstanceId} or {@code status} is {@code null}
     * @throws IllegalArgumentException if a count or {@code elapsedTimeInMillis} is negative
     */
    public BatchJobResult {
        Objects.requireNonNull(jobName, "jobName must not be null");
        Objects.requireNonNull(jobInstanceId, "jobInstanceId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        requireNonNegative("loadedRecords", loadedRecords);
        requireNonNegative("processedRecords", processedRecords);
        requireNonNegative("successfulRecords", successfulRecords);
        requireNonNegative("failedRecords", failedRecords);
        requireNonNegative("elapsedTimeInMillis", elapsedTimeInMillis);
    }

    /**
     * Tells whether the run ended in {@link Status#SUCCESSFUL}.
     *
     * @return {@code true} only when {@link #status()} is {@link Status#SUCCESSFUL}
     */
    public boolean isSuccessful() {
        return status == Status.SUCCESSFUL;
    }

    private static void requireNonNegative(String component, long value) {
        if (value < 0) {
            throw new IllegalArgumentException(component + " must not be negative: " + value);
        }
    }
}
