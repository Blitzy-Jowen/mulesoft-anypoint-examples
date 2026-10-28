package com.mulesoft.examples.import_leads_into_salesforce.service;

import com.mulesoft.examples.import_leads_into_salesforce.model.BatchJobResult;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs a batch job with the record semantics of a Mule 3.8 {@code batch:job}.
 *
 * <p>SC-7 shared component, reference copy. Every other consumer copies this file changing only the {@code package}
 * line and the {@code BatchJobResult} import; nothing in it is specific to the job of any one project.
 *
 * <p>Reproduced Mule 3.8 batch semantics:
 * <ul>
 *   <li>{@code batch:input}: the input phase yields the collection whose elements become the records; an exception it
 *       throws ends the run before any record exists, so no result is produced and on-complete does not run;</li>
 *   <li>{@code batch:process-records}: steps run in declaration order, each step taking every record before the next
 *       step starts;</li>
 *   <li>{@code accept-policy} ({@code NO_FAILURES} by default, or {@code ONLY_FAILURES}) is checked first, then
 *       {@code accept-expression} (always true by default);</li>
 *   <li>a record failing in a step keeps the exception under that step's name and is offered to later steps only
 *       through their accept policy;</li>
 *   <li>{@code batch:commit size} hands the accepted records' payloads to the commit in groups of {@code size}, the
 *       last group smaller; a failing commit fails every record of its group;</li>
 *   <li>{@code max-failed-records} (0 by default, {@code -1} unlimited) stops the job as soon as the failed-record
 *       count exceeds it, with status {@code FAILED_PROCESS_RECORDS};</li>
 *   <li>{@code batch:on-complete} receives the {@link BatchJobResult} with the loaded, processed, successful and failed
 *       counts.</li>
 * </ul>
 *
 * <p>Counts: a record is processed once it has failed, or once its handling in the job's last step has finished: the
 * step did not accept it, or accepted it and every processor passed with no commit declared, or committed the group
 * holding it. Successful records are the processed records that have not failed. A job that runs to the end processes
 * every loaded record. A stopped job leaves every other record unprocessed, neither successful nor failed: a record
 * that passed the earlier steps but never finished the last one, one the stop left in an uncommitted commit group, and
 * one no step reached.
 *
 * <p>Records are processed sequentially on the calling thread. The original {@code batch:threading-profile}
 * ({@code poolExhaustedAction="WAIT"}) only throttled a worker pool, so it is deliberately not reproduced: with no pool
 * there is nothing to exhaust, and record and step order stay deterministic.
 *
 * <p>The runner holds no state between runs; every run keeps its own state, so one instance serves concurrent callers.
 */
@Service
public class BatchJobRunner {

    private static final Logger LOG = LoggerFactory.getLogger(BatchJobRunner.class);

    /** Causes named after the outermost exception in a logged failure before the chain is cut short. */
    private static final int CAUSE_LOG_LIMIT = 5;

    /** Most characters of one class, method or file name copied into a logged failure. */
    private static final int NAME_LOG_LIMIT = 256;

    private static final String TRUNCATION_LOG_MARKER = "...[truncated]";

    /** The final result of a run: instance id, job name, status, then the record counts; never a payload. */
    private static final String FINISHED_LOG_FORMAT = "Finished job instance '{}' of job '{}' with status {}: "
            + "loaded {}, processed {}, successful {}, failed {}, unprocessed {}";

    /**
     * Runs one instance of {@code job}: input phase, process phase, then on-complete.
     *
     * <p>The final result, the one returned, is logged once after on-complete with its status and the loaded,
     * processed, successful, failed and unprocessed record counts: at DEBUG when the status is {@code SUCCESSFUL},
     * otherwise at WARN, for a job the process phase stopped ({@code FAILED_PROCESS_RECORDS}) and for one whose
     * on-complete threw ({@code FAILED_ON_COMPLETE}).
     *
     * <p>On-complete runs after the process phase ends or stops, and also after an empty input, which completes with
     * zero counts and status {@code SUCCESSFUL}; that empty-input behaviour is a Tier 2A verification point. An
     * exception thrown by on-complete is logged at ERROR by its type and throw site, never its message, and turns the
     * returned status into {@code FAILED_ON_COMPLETE}; it is not rethrown.
     *
     * @param job        the job to run
     * @param inputPhase produces the record payloads, in record order; elements may be {@code null}
     * @param <T>        the record payload type
     * @return the result of the run, the same value on-complete received unless on-complete threw
     * @throws NullPointerException if an argument is {@code null} or the input phase returns {@code null}
     * @throws Exception            the exception of the input phase, unchanged
     */
    public <T> BatchJobResult run(JobDefinition<T> job, Callable<? extends Collection<T>> inputPhase)
            throws Exception {
        Objects.requireNonNull(job, "job must not be null");
        Objects.requireNonNull(inputPhase, "inputPhase must not be null");

        String jobInstanceId = UUID.randomUUID().toString();
        LOG.debug("Created instance '{}' for batch job '{}'", jobInstanceId, job.name());

        long start = System.currentTimeMillis();
        Collection<T> input = Objects.requireNonNull(inputPhase.call(),
                () -> "Input phase of job '" + job.name() + "' returned null");
        JobInstance<T> instance = new JobInstance<>(job, jobInstanceId, input);
        LOG.debug("Input phase of job instance '{}' of job '{}' loaded {} records", jobInstanceId, job.name(),
                instance.records.size());

        instance.processRecords();
        // The wall clock may step back; BatchJobResult rejects a negative duration.
        long elapsed = Math.max(0L, System.currentTimeMillis() - start);
        BatchJobResult result = instance.result(elapsed);
        BatchJobResult finalResult = complete(job, result);
        Object[] finished = {jobInstanceId, job.name(), finalResult.status(), finalResult.loadedRecords(),
                finalResult.processedRecords(), finalResult.successfulRecords(), finalResult.failedRecords(),
                finalResult.loadedRecords() - finalResult.processedRecords()};
        if (finalResult.isSuccessful()) {
            LOG.debug(FINISHED_LOG_FORMAT, finished);
        } else {
            // A job that stopped, or whose on-complete threw, shows its final status and counts, the records it left
            // unprocessed included, at the default level.
            LOG.warn(FINISHED_LOG_FORMAT, finished);
        }
        return finalResult;
    }

    private static BatchJobResult complete(JobDefinition<?> job, BatchJobResult result) {
        Optional<Consumer<BatchJobResult>> onComplete = job.onComplete();
        if (onComplete.isEmpty()) {
            return result;
        }
        try {
            onComplete.get().accept(result);
            return result;
        } catch (RuntimeException ex) {
            LOG.error("Exception in on-complete phase of job instance '{}' of job '{}': {}", result.jobInstanceId(),
                    result.jobName(), describe(ex));
            return new BatchJobResult(result.jobName(), result.jobInstanceId(),
                    BatchJobResult.Status.FAILED_ON_COMPLETE, result.loadedRecords(), result.processedRecords(),
                    result.successfulRecords(), result.failedRecords(), result.elapsedTimeInMillis());
        }
    }

    /** The state of one run; created per {@link #run} call and never shared. */
    private static final class JobInstance<T> {

        private final JobDefinition<T> job;
        private final String jobInstanceId;
        private final List<BatchRecord<T>> records;
        private final Set<String> loggedFailures = new HashSet<>();
        private long completedCount;
        private long failedCount;
        private boolean stopped;

        JobInstance(JobDefinition<T> job, String jobInstanceId, Collection<T> payloads) {
            this.job = job;
            this.jobInstanceId = jobInstanceId;
            this.records = new ArrayList<>(payloads.size());
            for (T payload : payloads) {
                records.add(new BatchRecord<>(payload));
            }
        }

        void processRecords() {
            List<Step<T>> steps = job.steps();
            for (int s = 0; s < steps.size(); s++) {
                Step<T> step = steps.get(s);
                LOG.debug("Starting step '{}' of job instance '{}' of job '{}'", step.name(), jobInstanceId,
                        job.name());
                processStep(step, s == steps.size() - 1);
                if (stopped) {
                    return;
                }
            }
        }

        private void processStep(Step<T> step, boolean lastStep) {
            List<BatchRecord<T>> commitGroup = new ArrayList<>();
            for (int i = 0; i < records.size() && !stopped; i++) {
                BatchRecord<T> record = records.get(i);
                if (!accepts(step, record)) {
                    completeIfLast(lastStep, List.of(record));
                } else if (process(step, record)) {
                    if (!step.hasCommit()) {
                        completeIfLast(lastStep, List.of(record));
                    } else {
                        commitGroup.add(record);
                        if (commitGroup.size() == step.commitSize()) {
                            commit(step, commitGroup, lastStep);
                        }
                    }
                }
            }
            // A stopped job leaves the pending group uncommitted, and its records neither failed nor completed.
            if (!stopped && !commitGroup.isEmpty()) {
                commit(step, commitGroup, lastStep);
            }
        }

        /**
         * Counts the records of {@code handled} that have not failed as completed when {@code lastStep} is set: their
         * handling in the job's last step has finished, so nothing remains to process them.
         */
        private void completeIfLast(boolean lastStep, List<BatchRecord<T>> handled) {
            if (!lastStep) {
                return;
            }
            for (BatchRecord<T> record : handled) {
                if (!record.isFailed()) {
                    completedCount++;
                }
            }
        }

        private boolean accepts(Step<T> step, BatchRecord<T> record) {
            boolean onlyFailures = step.acceptPolicy() == AcceptPolicy.ONLY_FAILURES;
            if (record.isFailed() != onlyFailures) {
                return false;
            }
            try {
                return step.acceptExpression().test(record);
            } catch (RuntimeException ex) {
                fail(step, ex, List.of(record));
                return false;
            }
        }

        private boolean process(Step<T> step, BatchRecord<T> record) {
            try {
                for (RecordProcessor<T> processor : step.processors()) {
                    processor.process(record);
                }
                return true;
            } catch (Exception ex) {
                fail(step, ex, List.of(record));
                return false;
            }
        }

        private void commit(Step<T> step, List<BatchRecord<T>> group, boolean lastStep) {
            List<T> payloads = group.stream().map(BatchRecord::getPayload).toList();
            try {
                step.commitProcessor().orElseThrow().commit(payloads);
                completeIfLast(lastStep, group);
            } catch (Exception ex) {
                fail(step, ex, group);
            }
            group.clear();
        }

        private void fail(Step<T> step, Exception ex, List<BatchRecord<T>> failedRecords) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (loggedFailures.add(step.name() + '\u0000' + ex.getClass().getName())) {
                LOG.error("Found exception processing record on step '{}' for job instance '{}' of job '{}': {}",
                        step.name(), jobInstanceId, job.name(), describe(ex));
            }
            for (BatchRecord<T> record : failedRecords) {
                if (!record.isFailed()) {
                    failedCount++;
                }
                record.failures().put(step.name(), ex);
            }
            long maxFailedRecords = job.maxFailedRecords();
            if (maxFailedRecords >= 0 && failedCount > maxFailedRecords) {
                stopped = true;
            }
        }

        BatchJobResult result(long elapsedTimeInMillis) {
            // A job that ran to the end processed every record, including the case of a job without steps; a stopped
            // one only the records that failed or completed its last step.
            long processed = stopped ? failedCount + completedCount : records.size();
            BatchJobResult.Status status = stopped
                    ? BatchJobResult.Status.FAILED_PROCESS_RECORDS
                    : BatchJobResult.Status.SUCCESSFUL;
            return new BatchJobResult(job.name(), jobInstanceId, status, records.size(), processed,
                    processed - failedCount, failedCount, elapsedTimeInMillis);
        }
    }

    private static String requireName(String name, String kind) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(kind + " name must not be null or blank");
        }
        return name;
    }

    /**
     * Describes {@code ex} for the log by type and throw site only, on one line, for example
     * {@code java.lang.IllegalStateException at a.b.C.d(C.java:18) [suppressed: 1] <- java.io.IOException at
     * x.y.Z.w(Z.java:5)}.
     *
     * <p>The exception is never handed to the logger, and no message, localized message, {@code toString()} or stack
     * trace of it, its causes or its suppressed exceptions is written: exceptions thrown by accept expressions,
     * processors, commits and on-complete callbacks, vendor exceptions included, can carry record data such as names
     * and e-mail addresses, vendor payloads or credentials, and line breaks that forge log lines. The exception itself
     * stays available, unchanged, through {@link BatchRecord#failures()}.
     *
     * <p>The outermost exception is followed by at most {@value #CAUSE_LOG_LIMIT} causes and then {@code <- ...} when
     * more remain, which also ends a cyclic cause chain; suppressed exceptions are only counted. Each class, method and
     * file name is cut to {@value #NAME_LOG_LIMIT} characters, marked {@code ...[truncated]}, and each control, line
     * or paragraph separator, format or unpaired surrogate character in it is written as {@code ?}.
     *
     * @param ex the exception to describe
     * @return the one-line description
     */
    private static String describe(Throwable ex) {
        StringBuilder out = new StringBuilder(128);
        appendTypeAndThrowSite(out, ex);
        Throwable cause = ex.getCause();
        for (int depth = 0; cause != null && depth < CAUSE_LOG_LIMIT; depth++) {
            appendTypeAndThrowSite(out.append(" <- "), cause);
            cause = cause.getCause();
        }
        if (cause != null) {
            out.append(" <- ...");
        }
        return out.toString();
    }

    /**
     * Appends {@code "<exception class> at <class>.<method>(<file>:<line>)"}, the throwing frame in
     * {@link StackTraceElement#toString()} form without its class loader and module prefix, then
     * {@code " [suppressed: <count>]"} when the exception has suppressed exceptions.
     */
    private static void appendTypeAndThrowSite(StringBuilder out, Throwable ex) {
        out.append(logSafe(ex.getClass().getName())).append(" at ");
        StackTraceElement[] trace = ex.getStackTrace();
        if (trace == null || trace.length == 0 || trace[0] == null) {
            out.append("(no stack trace)");
        } else {
            StackTraceElement frame = trace[0];
            String file = frame.getFileName();
            out.append(logSafe(frame.getClassName())).append('.').append(logSafe(frame.getMethodName())).append('(')
                    .append(file == null ? "Unknown Source" : logSafe(file))
                    .append(file != null && frame.getLineNumber() >= 0 ? ":" + frame.getLineNumber() : "")
                    .append(')');
        }
        int suppressed = ex.getSuppressed().length;
        if (suppressed > 0) {
            out.append(" [suppressed: ").append(suppressed).append(']');
        }
    }

    /** Cuts {@code name} to {@link #NAME_LOG_LIMIT} characters and writes each character not log-safe as '?'. */
    private static String logSafe(String name) {
        boolean cut = name.length() > NAME_LOG_LIMIT;
        StringBuilder out = new StringBuilder(NAME_LOG_LIMIT + TRUNCATION_LOG_MARKER.length());
        (cut ? name.substring(0, NAME_LOG_LIMIT) : name).codePoints()
                .forEach(c -> out.appendCodePoint(isLogSafe(c) ? c : '?'));
        return cut ? out.append(TRUNCATION_LOG_MARKER).toString() : out.toString();
    }

    private static boolean isLogSafe(int codePoint) {
        int type = Character.getType(codePoint);
        return !Character.isISOControl(codePoint) && type != Character.LINE_SEPARATOR
                && type != Character.PARAGRAPH_SEPARATOR && type != Character.FORMAT && type != Character.SURROGATE;
    }

    /** Which records a step takes, by their failure state; Mule's {@code accept-policy}. */
    public enum AcceptPolicy {

        /** Only records that have not failed in any earlier step; the Mule default. */
        NO_FAILURES,

        /** Only records that failed in at least one earlier step. */
        ONLY_FAILURES
    }

    /**
     * One message processor of a step, applied to each record the step accepts.
     *
     * @param <T> the record payload type
     */
    @FunctionalInterface
    public interface RecordProcessor<T> {

        /**
         * Processes one record; the record may be changed through its payload and record variables.
         *
         * @param record the record the step accepted
         * @throws Exception any failure, which fails the record in this step
         */
        void process(BatchRecord<T> record) throws Exception;
    }

    /**
     * The processors inside a {@code batch:commit}, applied once per group of records.
     *
     * @param <T> the record payload type
     */
    @FunctionalInterface
    public interface CommitProcessor<T> {

        /**
         * Commits one group.
         *
         * @param payloads the current payloads of the group's records, in record order; unmodifiable
         * @throws Exception any failure, which fails every record of the group in this step
         */
        void commit(List<T> payloads) throws Exception;
    }

    /**
     * One record of a running job: its payload, its record variables and the failures it collected per step.
     *
     * <p>Not thread-safe; the runner visits records one at a time.
     *
     * @param <T> the record payload type
     */
    public static final class BatchRecord<T> {

        private final Map<String, Object> recordVars = new HashMap<>();
        private final Map<String, Throwable> failures = new LinkedHashMap<>();
        private T payload;

        /**
         * Creates a record that has not failed and has no record variables.
         *
         * @param payload the payload; may be {@code null}
         */
        public BatchRecord(T payload) {
            this.payload = payload;
        }

        /**
         * Returns the current payload.
         *
         * @return the payload, as last set
         */
        public T getPayload() {
            return payload;
        }

        /**
         * Replaces the payload; later processors, later steps and commits see the new value.
         *
         * @param payload the new payload; may be {@code null}
         */
        public void setPayload(T payload) {
            this.payload = payload;
        }

        /**
         * Returns the record variables, Mule's {@code recordVars}.
         *
         * @return the live, mutable map
         */
        public Map<String, Object> recordVars() {
            return recordVars;
        }

        /**
         * Tells whether the record failed in any step.
         *
         * @return {@code true} once a failure is recorded
         */
        public boolean isFailed() {
            return !failures.isEmpty();
        }

        /**
         * Returns the exceptions that failed this record, keyed by step name in the order they occurred.
         *
         * @return the live, mutable map
         */
        public Map<String, Throwable> failures() {
            return failures;
        }

        @Override
        public String toString() {
            return "BatchRecord{payload=" + payload + ", failed=" + isFailed() + "}";
        }
    }

    /**
     * One {@code batch:step}: accept policy, accept expression, processors and an optional commit. Immutable.
     *
     * @param <T> the record payload type
     */
    public static final class Step<T> {

        private final String name;
        private final AcceptPolicy acceptPolicy;
        private final Predicate<BatchRecord<T>> acceptExpression;
        private final List<RecordProcessor<T>> processors;
        private final int commitSize;
        private final CommitProcessor<T> commitProcessor;

        private Step(Builder<T> builder) {
            this.name = builder.name;
            this.acceptPolicy = builder.acceptPolicy;
            this.acceptExpression = builder.acceptExpression;
            this.processors = List.copyOf(builder.processors);
            this.commitSize = builder.commitSize;
            this.commitProcessor = builder.commitProcessor;
        }

        /**
         * Starts a step definition.
         *
         * @param name the step name, unique within its job
         * @param <T>  the record payload type
         * @return a builder with policy {@code NO_FAILURES}, an always-true accept expression and no commit
         * @throws IllegalArgumentException if {@code name} is {@code null} or blank
         */
        public static <T> Builder<T> builder(String name) {
            return new Builder<>(requireName(name, "Step"));
        }

        /**
         * Returns the step name.
         *
         * @return the name failures are recorded under
         */
        public String name() {
            return name;
        }

        /**
         * Returns the accept policy.
         *
         * @return the policy checked before the accept expression
         */
        public AcceptPolicy acceptPolicy() {
            return acceptPolicy;
        }

        /**
         * Returns the accept expression.
         *
         * @return the predicate evaluated for records the policy admits
         */
        public Predicate<BatchRecord<T>> acceptExpression() {
            return acceptExpression;
        }

        /**
         * Returns the processors.
         *
         * @return the processors in execution order; unmodifiable
         */
        public List<RecordProcessor<T>> processors() {
            return processors;
        }

        /**
         * Returns the commit group size.
         *
         * @return the size of each commit group, or 0 when the step has no commit
         */
        public int commitSize() {
            return commitSize;
        }

        /**
         * Returns the commit processor.
         *
         * @return the commit processor, or empty when the step has no commit
         */
        public Optional<CommitProcessor<T>> commitProcessor() {
            return Optional.ofNullable(commitProcessor);
        }

        /**
         * Tells whether the step has a commit.
         *
         * @return {@code true} when a commit was declared
         */
        public boolean hasCommit() {
            return commitProcessor != null;
        }

        /**
         * Builds a {@link Step}.
         *
         * @param <T> the record payload type
         */
        public static final class Builder<T> {

            private final String name;
            private final List<RecordProcessor<T>> processors = new ArrayList<>();
            private AcceptPolicy acceptPolicy = AcceptPolicy.NO_FAILURES;
            private Predicate<BatchRecord<T>> acceptExpression = record -> true;
            private int commitSize;
            private CommitProcessor<T> commitProcessor;

            private Builder(String name) {
                this.name = name;
            }

            /**
             * Sets the accept policy.
             *
             * @param acceptPolicy the policy; {@code NO_FAILURES} when not set
             * @return this builder
             * @throws NullPointerException if {@code acceptPolicy} is {@code null}
             */
            public Builder<T> acceptPolicy(AcceptPolicy acceptPolicy) {
                this.acceptPolicy = Objects.requireNonNull(acceptPolicy, "acceptPolicy must not be null");
                return this;
            }

            /**
             * Sets the accept expression; a {@code RuntimeException} it throws fails the record in this step.
             *
             * @param acceptExpression the predicate; always true when not set
             * @return this builder
             * @throws NullPointerException if {@code acceptExpression} is {@code null}
             */
            public Builder<T> acceptExpression(Predicate<BatchRecord<T>> acceptExpression) {
                this.acceptExpression = Objects.requireNonNull(acceptExpression, "acceptExpression must not be null");
                return this;
            }

            /**
             * Appends a processor; processors run in the order they are added.
             *
             * @param processor the processor
             * @return this builder
             * @throws NullPointerException if {@code processor} is {@code null}
             */
            public Builder<T> processor(RecordProcessor<T> processor) {
                processors.add(Objects.requireNonNull(processor, "processor must not be null"));
                return this;
            }

            /**
             * Declares the step's commit, which always runs after all processors, whatever the call order here.
             *
             * @param size            the group size; the last group may be smaller
             * @param commitProcessor the commit processor
             * @return this builder
             * @throws IllegalStateException    if a commit was already declared
             * @throws IllegalArgumentException if {@code size} is below 1
             * @throws NullPointerException     if {@code commitProcessor} is {@code null}
             */
            public Builder<T> commit(int size, CommitProcessor<T> commitProcessor) {
                if (this.commitProcessor != null) {
                    throw new IllegalStateException("Step '" + name + "' already declares a commit");
                }
                if (size < 1) {
                    throw new IllegalArgumentException(
                            "Commit size of step '" + name + "' must be at least 1: " + size);
                }
                this.commitProcessor = Objects.requireNonNull(commitProcessor, "commitProcessor must not be null");
                this.commitSize = size;
                return this;
            }

            /**
             * Builds the step.
             *
             * @return the immutable step
             */
            public Step<T> build() {
                return new Step<>(this);
            }
        }
    }

    /**
     * One {@code batch:job}: name, {@code max-failed-records}, ordered steps and an optional on-complete. Immutable.
     *
     * @param <T> the record payload type
     */
    public static final class JobDefinition<T> {

        private final String name;
        private final long maxFailedRecords;
        private final List<Step<T>> steps;
        private final Consumer<BatchJobResult> onComplete;

        private JobDefinition(Builder<T> builder) {
            this.name = builder.name;
            this.maxFailedRecords = builder.maxFailedRecords;
            this.steps = List.copyOf(builder.steps);
            this.onComplete = builder.onComplete;
        }

        /**
         * Starts a job definition.
         *
         * @param name the job name
         * @param <T>  the record payload type
         * @return a builder with {@code maxFailedRecords} 0, no steps and no on-complete
         * @throws IllegalArgumentException if {@code name} is {@code null} or blank
         */
        public static <T> Builder<T> builder(String name) {
            return new Builder<>(requireName(name, "Job"));
        }

        /**
         * Returns the job name.
         *
         * @return the name reported in the result
         */
        public String name() {
            return name;
        }

        /**
         * Returns the failed-record limit.
         *
         * @return the number of failed records tolerated before the job stops, or {@code -1} for no limit
         */
        public long maxFailedRecords() {
            return maxFailedRecords;
        }

        /**
         * Returns the steps.
         *
         * @return the steps in execution order; unmodifiable
         */
        public List<Step<T>> steps() {
            return steps;
        }

        /**
         * Returns the on-complete callback.
         *
         * @return the callback, or empty when none was set
         */
        public Optional<Consumer<BatchJobResult>> onComplete() {
            return Optional.ofNullable(onComplete);
        }

        /**
         * Builds a {@link JobDefinition}.
         *
         * @param <T> the record payload type
         */
        public static final class Builder<T> {

            private final String name;
            private final List<Step<T>> steps = new ArrayList<>();
            private long maxFailedRecords;
            private Consumer<BatchJobResult> onComplete;

            private Builder(String name) {
                this.name = name;
            }

            /**
             * Sets the failed-record limit; the job stops as soon as more records than this have failed.
             *
             * @param maxFailedRecords the limit; 0 when not set, {@code -1} for no limit
             * @return this builder
             * @throws IllegalArgumentException if {@code maxFailedRecords} is below {@code -1}
             */
            public Builder<T> maxFailedRecords(long maxFailedRecords) {
                if (maxFailedRecords < -1) {
                    throw new IllegalArgumentException("maxFailedRecords must be -1 or more: " + maxFailedRecords);
                }
                this.maxFailedRecords = maxFailedRecords;
                return this;
            }

            /**
             * Appends a step; steps run in the order they are added.
             *
             * @param step the step
             * @return this builder
             * @throws NullPointerException if {@code step} is {@code null}
             */
            public Builder<T> step(Step<T> step) {
                steps.add(Objects.requireNonNull(step, "step must not be null"));
                return this;
            }

            /**
             * Sets the on-complete callback, replacing any earlier one.
             *
             * @param onComplete receives the result of every run that passed the input phase
             * @return this builder
             * @throws NullPointerException if {@code onComplete} is {@code null}
             */
            public Builder<T> onComplete(Consumer<BatchJobResult> onComplete) {
                this.onComplete = Objects.requireNonNull(onComplete, "onComplete must not be null");
                return this;
            }

            /**
             * Builds the job.
             *
             * @return the immutable job
             * @throws IllegalArgumentException if two steps share a name, since failures are keyed by step name
             */
            public JobDefinition<T> build() {
                Set<String> stepNames = new HashSet<>();
                for (Step<T> step : steps) {
                    if (!stepNames.add(step.name())) {
                        throw new IllegalArgumentException(
                                "Job '" + name + "' declares step '" + step.name() + "' more than once");
                    }
                }
                return new JobDefinition<>(this);
            }
        }
    }
}

