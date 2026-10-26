package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.model.BatchJobResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Tests shared component SC-7, {@link BatchJobRunner} with {@link BatchJobResult}: the Mule 3.8 batch semantics the
 * runner reproduces (accept policy and accept expression, record variables, per-step failure capture, commit grouping,
 * {@code max-failed-records}, on-complete and the result counts), through the runner's public API only.
 *
 * <p>Every consumer of SC-7 carries this class with only its {@code package} line and its {@code BatchJobResult}
 * import changed, as it carries the runner, so it depends on the JDK, JUnit, AssertJ, SLF4J, Logback, the runner and
 * {@link BatchJobResult} alone. Payloads are JDK types, and nothing waits on a clock or a network.
 */
class BatchJobRunnerTest {

    /** The size of the only {@code batch:commit} the SC-7 originals declare. */
    private static final int COMMIT_SIZE = 200;

    /** The commit of step definitions the argument rules reject; no job ever runs them. */
    private static final BatchJobRunner.CommitProcessor<Integer> NEVER_COMMITTED = payloads -> {
        throw new AssertionError("A rejected step definition must never commit");
    };

    private final BatchJobRunner runner = new BatchJobRunner();

    private Logger logger;

    private Level previousLevel;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(BatchJobRunner.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }

    static Stream<Arguments> commitGroupings() {
        return Stream.of(
                Arguments.of(450, List.of(200, 200, 50)),
                Arguments.of(400, List.of(200, 200)));
    }

    @ParameterizedTest(name = "{0} records are committed in groups of {1}")
    @MethodSource("commitGroupings")
    void commitHandsOverGroupsOfTheCommitSizeWithASmallerLastGroup(int recordCount, List<Integer> groupSizes)
            throws Exception {
        List<List<Integer>> groups = new ArrayList<>();
        BatchJobRunner.JobDefinition<Integer> job = BatchJobRunner.JobDefinition.<Integer>builder("commitJob")
                .step(BatchJobRunner.Step.<Integer>builder("insert")
                        .commit(COMMIT_SIZE, payloads -> groups.add(List.copyOf(payloads)))
                        .build())
                .build();

        BatchJobResult result = runner.run(job, () -> payloads(recordCount));

        assertThat(groups.stream().map(List::size).toList()).containsExactlyElementsOf(groupSizes);
        assertThat(groups.stream().flatMap(List::stream).toList()).isEqualTo(payloads(recordCount));
        assertCounts(result, recordCount, recordCount, 0);
        assertThat(result.status()).isEqualTo(BatchJobResult.Status.SUCCESSFUL);
        assertWellFormed(result);
    }

    @Test
    void commitReceivesOnlyTheRecordsItsStepAccepted() throws Exception {
        List<Integer> processed = new ArrayList<>();
        List<List<Integer>> groups = new ArrayList<>();
        BatchJobRunner.JobDefinition<Integer> job = BatchJobRunner.JobDefinition.<Integer>builder("acceptedJob")
                .maxFailedRecords(-1)
                .step(failingStep("validate", payload -> payload == 4))
                .step(BatchJobRunner.Step.<Integer>builder("insert")
                        .acceptExpression(record -> record.getPayload() % 2 == 0)
                        .processor(record -> processed.add(record.getPayload()))
                        .commit(3, payloads -> groups.add(List.copyOf(payloads)))
                        .build())
                .build();

        BatchJobResult result = runner.run(job, () -> payloads(10));

        // 4 is refused by the accept policy, having failed; the odd records by the accept expression.
        assertThat(processed).containsExactly(0, 2, 6, 8);
        assertThat(groups).containsExactly(List.of(0, 2, 6), List.of(8));
        assertCounts(result, 10, 10, 1);
        assertThat(result.status()).isEqualTo(BatchJobResult.Status.SUCCESSFUL);
        assertWellFormed(result);
    }

    @Test
    void processorsRunBeforeTheCommitWhateverTheDeclarationOrder() throws Exception {
        List<String> events = new ArrayList<>();
        BatchJobRunner.JobDefinition<String> job = BatchJobRunner.JobDefinition.<String>builder("orderJob")
                .step(BatchJobRunner.Step.<String>builder("insert")
                        .commit(2, payloads -> events.add("commit " + payloads))
                        .processor(record -> events.add("first " + record.getPayload()))
                        .processor(record -> events.add("second " + record.getPayload()))
                        .build())
                .build();

        BatchJobResult result = runner.run(job, () -> List.of("a", "b", "c"));

        assertThat(events).containsExactly(
                "first a", "second a", "first b", "second b", "commit [a, b]",
                "first c", "second c", "commit [c]");
        assertCounts(result, 3, 3, 0);
        assertWellFormed(result);
    }

    @Test
    void noFailuresIsTheDefaultPolicyAndOnlyFailuresTakesOnlyFailedRecords() throws Exception {
        List<Integer> withoutFailures = new ArrayList<>();
        List<Integer> withFailures = new ArrayList<>();
        BatchJobRunner.Step<Integer> defaultPolicyStep = BatchJobRunner.Step.<Integer>builder("noFailures")
                .processor(record -> withoutFailures.add(record.getPayload()))
                .build();
        BatchJobRunner.JobDefinition<Integer> job = BatchJobRunner.JobDefinition.<Integer>builder("policyJob")
                .maxFailedRecords(-1)
                .step(failingStep("validate", payload -> payload == 1 || payload == 3))
                .step(defaultPolicyStep)
                .step(BatchJobRunner.Step.<Integer>builder("onlyFailures")
                        .acceptPolicy(BatchJobRunner.AcceptPolicy.ONLY_FAILURES)
                        .processor(record -> withFailures.add(record.getPayload()))
                        .build())
                .build();

        BatchJobResult result = runner.run(job, () -> payloads(5));

        assertThat(defaultPolicyStep.acceptPolicy()).isEqualTo(BatchJobRunner.AcceptPolicy.NO_FAILURES);
        assertThat(withoutFailures).containsExactly(0, 2, 4);
        assertThat(withFailures).containsExactly(1, 3);
        assertCounts(result, 5, 5, 2);
        assertThat(result.status()).isEqualTo(BatchJobResult.Status.SUCCESSFUL);
        assertWellFormed(result);
    }

    @Test
    void acceptExpressionIsEvaluatedOnlyForRecordsTheAcceptPolicyAdmits() throws Exception {
        List<Integer> evaluatedWithoutFailures = new ArrayList<>();
        List<Integer> evaluatedWithFailures = new ArrayList<>();
        List<Integer> processed = new ArrayList<>();
        BatchJobRunner.JobDefinition<Integer> job = BatchJobRunner.JobDefinition.<Integer>builder("expressionJob")
                .maxFailedRecords(-1)
                .step(failingStep("validate", payload -> payload == 2))
                .step(BatchJobRunner.Step.<Integer>builder("filter")
                        .acceptExpression(record -> {
                            evaluatedWithoutFailures.add(record.getPayload());
                            return record.getPayload() != 3;
                        })
                        .processor(record -> processed.add(record.getPayload()))
                        .build())
                .step(BatchJobRunner.Step.<Integer>builder("failures")
                        .acceptPolicy(BatchJobRunner.AcceptPolicy.ONLY_FAILURES)
                        .acceptExpression(record -> evaluatedWithFailures.add(record.getPayload()))
                        .build())
                .build();

        BatchJobResult result = runner.run(job, () -> payloads(5));

        assertThat(evaluatedWithoutFailures).containsExactly(0, 1, 3, 4);
        assertThat(processed).containsExactly(0, 1, 4);
        assertThat(evaluatedWithFailures).containsExactly(2);
        assertCounts(result, 5, 5, 1);
        assertWellFormed(result);
    }

    @Test
    void recordVariablesBelongToTheirRecordAndReachLaterSteps() throws Exception {
        Set<String> existing = Set.of("known@example.com");
        List<List<String>> inserted = new ArrayList<>();
        List<Map<String, Object>> variablesSeenLater = new ArrayList<>();
        BatchJobRunner.JobDefinition<String> job = BatchJobRunner.JobDefinition.<String>builder("variablesJob")
                .step(BatchJobRunner.Step.<String>builder("exists")
                        .processor(record -> record.recordVars().put("exists", existing.contains(record.getPayload())))
                        .build())
                .step(BatchJobRunner.Step.<String>builder("insert")
                        .acceptExpression(record -> !((Boolean) record.recordVars().get("exists")))
                        .commit(COMMIT_SIZE, payloads -> inserted.add(List.copyOf(payloads)))
                        .build())
                .step(BatchJobRunner.Step.<String>builder("audit")
                        .processor(record -> variablesSeenLater.add(Map.copyOf(record.recordVars())))
                        .build())
                .build();

        BatchJobResult result = runner.run(job,
                () -> List.of("first@example.com", "known@example.com", "second@example.com"));

        assertThat(inserted).containsExactly(List.of("first@example.com", "second@example.com"));
        assertThat(variablesSeenLater).containsExactly(
                Map.<String, Object>of("exists", false),
                Map.<String, Object>of("exists", true),
                Map.<String, Object>of("exists", false));
        assertCounts(result, 3, 3, 0);
        assertWellFormed(result);
    }

    @Test
    void replacedPayloadIsSeenByLaterProcessorsTheCommitAndLaterSteps() throws Exception {
        List<String> seenByNextProcessor = new ArrayList<>();
        List<List<String>> committed = new ArrayList<>();
        List<String> seenByLaterStep = new ArrayList<>();
        BatchJobRunner.JobDefinition<String> job = BatchJobRunner.JobDefinition.<String>builder("payloadJob")
                .step(BatchJobRunner.Step.<String>builder("transform")
                        .processor(record -> record.setPayload(record.getPayload().toUpperCase(Locale.ROOT)))
                        .processor(record -> seenByNextProcessor.add(record.getPayload()))
                        .commit(COMMIT_SIZE, payloads -> committed.add(List.copyOf(payloads)))
                        .build())
                .step(BatchJobRunner.Step.<String>builder("later")
                        .processor(record -> seenByLaterStep.add(record.getPayload()))
                        .build())
                .build();

        BatchJobResult result = runner.run(job, () -> List.of("a", "b"));

        assertThat(seenByNextProcessor).containsExactly("A", "B");
        assertThat(committed).containsExactly(List.of("A", "B"));
        assertThat(seenByLaterStep).containsExactly("A", "B");
        assertCounts(result, 2, 2, 0);
        assertWellFormed(result);
    }

    @Test
    void failureIsKeptUnderTheNameOfTheStepItHappenedIn() throws Exception {
        IllegalArgumentException expressionFailure = new IllegalArgumentException("expression failed");
        IOException processorFailure = new IOException("processor failed");
        IllegalStateException retryFailure = new IllegalStateException("retry failed");
        List<BatchJobRunner.BatchRecord<Integer>> failed = new ArrayList<>();
        BatchJobRunner.JobDefinition<Integer> job = BatchJobRunner.JobDefinition.<Integer>builder("failureJob")
                .maxFailedRecords(-1)
                .step(BatchJobRunner.Step.<Integer>builder("validate")
                        .acceptExpression(record -> {
                            if (record.getPayload() == 1) {
                                throw expressionFailure;
                            }
                            return true;
                        })
                        .processor(record -> {
                            if (record.getPayload() == 2) {
                                throw processorFailure;
                            }
                        })
                        .build())
                .step(BatchJobRunner.Step.<Integer>builder("retry")
                        .acceptPolicy(BatchJobRunner.AcceptPolicy.ONLY_FAILURES)
                        .processor(record -> {
                            if (record.getPayload() == 2) {
                                throw retryFailure;
                            }
                        })
                        .build())
                .step(BatchJobRunner.Step.<Integer>builder("inspect")
                        .acceptPolicy(BatchJobRunner.AcceptPolicy.ONLY_FAILURES)
                        .processor(failed::add)
                        .build())
                .build();

        BatchJobResult result = runner.run(job, () -> payloads(4));

        assertThat(failed).extracting(BatchJobRunner.BatchRecord::getPayload).containsExactly(1, 2);
        BatchJobRunner.BatchRecord<Integer> expressionFailed = failed.get(0);
        assertThat(expressionFailed.isFailed()).isTrue();
        assertThat(expressionFailed.failures()).containsOnlyKeys("validate");
        assertThat(expressionFailed.failures().get("validate")).isSameAs(expressionFailure);
        BatchJobRunner.BatchRecord<Integer> failedTwice = failed.get(1);
        assertThat(failedTwice.isFailed()).isTrue();
        assertThat(failedTwice.failures().keySet()).containsExactly("validate", "retry");
        assertThat(failedTwice.failures().get("validate")).isSameAs(processorFailure);
        assertThat(failedTwice.failures().get("retry")).isSameAs(retryFailure);
        // A record that fails in two steps is still one failed record.
        assertCounts(result, 4, 4, 2);
        assertWellFormed(result);
    }

    @Test
    void failingCommitFailsEveryRecordOfItsGroup() throws Exception {
        IllegalStateException commitFailure = new IllegalStateException("commit failed");
        List<List<Integer>> committed = new ArrayList<>();
        List<BatchJobRunner.BatchRecord<Integer>> failed = new ArrayList<>();
        BatchJobRunner.JobDefinition<Integer> job = BatchJobRunner.JobDefinition.<Integer>builder("commitFailureJob")
                .maxFailedRecords(-1)
                .step(BatchJobRunner.Step.<Integer>builder("write")
                        .commit(2, payloads -> {
                            if (payloads.contains(2)) {
                                throw commitFailure;
                            }
                            committed.add(List.copyOf(payloads));
                        })
                        .build())
                .step(BatchJobRunner.Step.<Integer>builder("inspect")
                        .acceptPolicy(BatchJobRunner.AcceptPolicy.ONLY_FAILURES)
                        .processor(failed::add)
                        .build())
                .build();

        BatchJobResult result = runner.run(job, () -> payloads(5));

        assertThat(committed).containsExactly(List.of(0, 1), List.of(4));
        assertThat(failed).extracting(BatchJobRunner.BatchRecord::getPayload).containsExactly(2, 3);
        assertThat(failed).allSatisfy(record -> {
            assertThat(record.failures()).containsOnlyKeys("write");
            assertThat(record.failures().get("write")).isSameAs(commitFailure);
        });
        assertCounts(result, 5, 5, 2);
        assertThat(result.status()).isEqualTo(BatchJobResult.Status.SUCCESSFUL);
        assertWellFormed(result);
    }

    @Test
    void defaultMaxFailedRecordsOfZeroStopsTheJobAtTheFirstFailedRecord() throws Exception {
        List<Integer> processed = new ArrayList<>();
        List<List<Integer>> committed = new ArrayList<>();
        List<Integer> seenByLaterStep = new ArrayList<>();
        AtomicInteger onCompleteCalls = new AtomicInteger();
        AtomicReference<BatchJobResult> completed = new AtomicReference<>();
        BatchJobRunner.JobDefinition<Integer> job = BatchJobRunner.JobDefinition.<Integer>builder("stopJob")
                .step(BatchJobRunner.Step.<Integer>builder("insert")
                        .processor(record -> {
                            processed.add(record.getPayload());
                            if (record.getPayload() == 2) {
                                throw new IllegalStateException("record 2 failed");
                            }
                        })
                        .commit(10, payloads -> committed.add(List.copyOf(payloads)))
                        .build())
                .step(BatchJobRunner.Step.<Integer>builder("later")
                        .processor(record -> seenByLaterStep.add(record.getPayload()))
                        .build())
                .onComplete(jobResult -> {
                    onCompleteCalls.incrementAndGet();
                    completed.set(jobResult);
                })
                .build();

        BatchJobResult result = runner.run(job, () -> payloads(5));

        assertThat(job.maxFailedRecords()).isZero();
        assertThat(processed).containsExactly(0, 1, 2);
        assertThat(committed).isEmpty();
        assertThat(seenByLaterStep).isEmpty();
        assertThat(onCompleteCalls).hasValue(1);
        assertThat(completed.get()).isSameAs(result);
        assertThat(result.status()).isEqualTo(BatchJobResult.Status.FAILED_PROCESS_RECORDS);
        assertCounts(result, 5, 3, 1);
        assertWellFormed(result);
    }

    @Test
    void maxFailedRecordsOfMinusOneNeverStopsTheJob() throws Exception {
        List<Integer> seenByLaterStep = new ArrayList<>();
        BatchJobRunner.JobDefinition<Integer> job = BatchJobRunner.JobDefinition.<Integer>builder("unlimitedJob")
                .maxFailedRecords(-1)
                .step(failingStep("validate", payload -> true))
                .step(BatchJobRunner.Step.<Integer>builder("failures")
                        .acceptPolicy(BatchJobRunner.AcceptPolicy.ONLY_FAILURES)
                        .processor(record -> seenByLaterStep.add(record.getPayload()))
                        .build())
                .build();

        BatchJobResult result = runner.run(job, () -> payloads(10));

        assertThat(seenByLaterStep).isEqualTo(payloads(10));
        assertThat(result.status()).isEqualTo(BatchJobResult.Status.SUCCESSFUL);
        assertCounts(result, 10, 10, 10);
        assertWellFormed(result);
    }

    @Test
    void maxFailedRecordsStopsTheJobWhenTheFailedCountExceedsIt() throws Exception {
        List<Integer> processed = new ArrayList<>();
        List<Integer> seenByLaterStep = new ArrayList<>();
        BatchJobRunner.JobDefinition<Integer> job = BatchJobRunner.JobDefinition.<Integer>builder("limitJob")
                .maxFailedRecords(2)
                .step(BatchJobRunner.Step.<Integer>builder("validate")
                        .processor(record -> {
                            processed.add(record.getPayload());
                            if (record.getPayload() % 2 == 1) {
                                throw new IllegalStateException("odd record failed");
                            }
                        })
                        .build())
                .step(BatchJobRunner.Step.<Integer>builder("later")
                        .processor(record -> seenByLaterStep.add(record.getPayload()))
                        .build())
                .build();

        BatchJobResult result = runner.run(job, () -> payloads(10));

        // Failures 1 and 3 are tolerated; failure 5 is the third and stops the job.
        assertThat(processed).containsExactly(0, 1, 2, 3, 4, 5);
        assertThat(seenByLaterStep).isEmpty();
        assertThat(result.status()).isEqualTo(BatchJobResult.Status.FAILED_PROCESS_RECORDS);
        assertCounts(result, 10, 6, 3);
        assertWellFormed(result);
    }

    @Test
    void failedCountEqualToMaxFailedRecordsCompletesSuccessfully() throws Exception {
        List<Integer> seenByLaterStep = new ArrayList<>();
        BatchJobRunner.JobDefinition<Integer> job = BatchJobRunner.JobDefinition.<Integer>builder("boundaryJob")
                .maxFailedRecords(2)
                .step(failingStep("validate", payload -> payload == 1 || payload == 3))
                .step(BatchJobRunner.Step.<Integer>builder("later")
                        .processor(record -> seenByLaterStep.add(record.getPayload()))
                        .build())
                .build();

        BatchJobResult result = runner.run(job, () -> payloads(10));

        assertThat(seenByLaterStep).containsExactly(0, 2, 4, 5, 6, 7, 8, 9);
        assertThat(result.status()).isEqualTo(BatchJobResult.Status.SUCCESSFUL);
        assertCounts(result, 10, 10, 2);
        assertWellFormed(result);
    }

    @Test
    void resultReportsJobNameCountsAndElapsedTime() throws Exception {
        BatchJobRunner.JobDefinition<Integer> job = BatchJobRunner.JobDefinition.<Integer>builder("countingJob")
                .maxFailedRecords(-1)
                .step(failingStep("validate", payload -> payload % 3 == 0))
                .step(BatchJobRunner.Step.<Integer>builder("later").build())
                .build();

        BatchJobResult result = runner.run(job, () -> payloads(7));

        assertThat(result.jobName()).isEqualTo("countingJob");
        assertThat(result.loadedRecords()).isEqualTo(7);
        assertThat(result.processedRecords()).isEqualTo(7);
        assertThat(result.successfulRecords()).isEqualTo(4);
        assertThat(result.failedRecords()).isEqualTo(3);
        assertThat(result.elapsedTimeInMillis()).isNotNegative();
        assertThat(result.status()).isEqualTo(BatchJobResult.Status.SUCCESSFUL);
        assertWellFormed(result);
    }

    @Test
    void onCompleteRunsExactlyOnceAfterProcessingWithTheReturnedResult() throws Exception {
        AtomicInteger processorCalls = new AtomicInteger();
        AtomicInteger onCompleteCalls = new AtomicInteger();
        AtomicInteger processorCallsAtCompletion = new AtomicInteger(-1);
        AtomicReference<BatchJobResult> completed = new AtomicReference<>();
        BatchJobRunner.JobDefinition<Integer> job = BatchJobRunner.JobDefinition.<Integer>builder("completeJob")
                .step(BatchJobRunner.Step.<Integer>builder("count")
                        .processor(record -> processorCalls.incrementAndGet())
                        .build())
                .onComplete(jobResult -> {
                    onCompleteCalls.incrementAndGet();
                    processorCallsAtCompletion.set(processorCalls.get());
                    completed.set(jobResult);
                })
                .build();

        BatchJobResult result = runner.run(job, () -> payloads(3));

        assertThat(onCompleteCalls).hasValue(1);
        assertThat(processorCallsAtCompletion).hasValue(3);
        assertThat(completed.get()).isSameAs(result);
        assertThat(result.status()).isEqualTo(BatchJobResult.Status.SUCCESSFUL);
        assertCounts(result, 3, 3, 0);
        assertWellFormed(result);
    }

    @Test
    void failingOnCompleteYieldsFailedOnCompleteAndIsNotRethrown() {
        IllegalStateException failure = new IllegalStateException("on-complete saw secret@example.com");
        AtomicReference<BatchJobResult> completed = new AtomicReference<>();
        AtomicReference<BatchJobResult> returned = new AtomicReference<>();
        BatchJobRunner.JobDefinition<Integer> job = BatchJobRunner.JobDefinition.<Integer>builder("onCompleteJob")
                .step(BatchJobRunner.Step.<Integer>builder("pass").build())
                .onComplete(jobResult -> {
                    completed.set(jobResult);
                    throw failure;
                })
                .build();

        assertThatCode(() -> returned.set(runner.run(job, () -> payloads(3)))).doesNotThrowAnyException();

        BatchJobResult result = returned.get();
        BatchJobResult received = completed.get();
        assertThat(result.status()).isEqualTo(BatchJobResult.Status.FAILED_ON_COMPLETE);
        assertThat(result.jobName()).isEqualTo("onCompleteJob");
        assertThat(result.jobInstanceId()).isEqualTo(received.jobInstanceId());
        assertThat(result.elapsedTimeInMillis()).isEqualTo(received.elapsedTimeInMillis());
        assertCounts(result, 3, 3, 0);
        assertWellFormed(result);
        assertThat(received.status()).isEqualTo(BatchJobResult.Status.SUCCESSFUL);
        assertWellFormed(received);
        List<ILoggingEvent> errors = errorEvents();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getFormattedMessage())
                .startsWith("Exception in on-complete phase of job instance '" + result.jobInstanceId()
                        + "' of job 'onCompleteJob': java.lang.IllegalStateException at ")
                .doesNotContain("secret@example.com");
        assertThat(errors.get(0).getThrowableProxy()).isNull();
    }

    @Test
    void inputPhaseExceptionPropagatesUnchangedAndNothingElseRuns() {
        IOException failure = new IOException("input unavailable");
        AtomicInteger processorCalls = new AtomicInteger();
        AtomicInteger onCompleteCalls = new AtomicInteger();
        BatchJobRunner.JobDefinition<Integer> job = BatchJobRunner.JobDefinition.<Integer>builder("inputJob")
                .step(BatchJobRunner.Step.<Integer>builder("count")
                        .processor(record -> processorCalls.incrementAndGet())
                        .build())
                .onComplete(jobResult -> onCompleteCalls.incrementAndGet())
                .build();

        assertThatThrownBy(() -> runner.run(job, () -> {
            throw failure;
        })).isSameAs(failure);

        assertThat(processorCalls).hasValue(0);
        assertThat(onCompleteCalls).hasValue(0);
    }

    @Test
    void emptyInputCompletesSuccessfullyWithZeroCountsAndNoCommit() throws Exception {
        AtomicInteger processorCalls = new AtomicInteger();
        AtomicInteger commitCalls = new AtomicInteger();
        AtomicInteger onCompleteCalls = new AtomicInteger();
        AtomicReference<BatchJobResult> completed = new AtomicReference<>();
        BatchJobRunner.JobDefinition<Integer> job = BatchJobRunner.JobDefinition.<Integer>builder("emptyJob")
                .step(BatchJobRunner.Step.<Integer>builder("insert")
                        .processor(record -> processorCalls.incrementAndGet())
                        .commit(COMMIT_SIZE, payloads -> commitCalls.incrementAndGet())
                        .build())
                .onComplete(jobResult -> {
                    onCompleteCalls.incrementAndGet();
                    completed.set(jobResult);
                })
                .build();

        BatchJobResult result = runner.run(job, () -> List.<Integer>of());

        assertThat(result.loadedRecords()).isZero();
        assertCounts(result, 0, 0, 0);
        assertThat(result.status()).isEqualTo(BatchJobResult.Status.SUCCESSFUL);
        assertThat(processorCalls).hasValue(0);
        assertThat(commitCalls).hasValue(0);
        assertThat(onCompleteCalls).hasValue(1);
        assertThat(completed.get()).isSameAs(result);
        assertWellFormed(result);
    }

    @Test
    void errorIsLoggedOncePerExceptionClassPerStepOfEachJobInstance() throws Exception {
        BatchJobRunner.JobDefinition<Integer> job = BatchJobRunner.JobDefinition.<Integer>builder("loggingJob")
                .maxFailedRecords(-1)
                .step(BatchJobRunner.Step.<Integer>builder("first")
                        .processor(record -> {
                            if (record.getPayload() < 3) {
                                throw new IllegalStateException("user" + record.getPayload() + "@example.com");
                            }
                            if (record.getPayload() < 5) {
                                throw new IllegalArgumentException("user" + record.getPayload() + "@example.com");
                            }
                        })
                        .build())
                .step(BatchJobRunner.Step.<Integer>builder("second")
                        .acceptPolicy(BatchJobRunner.AcceptPolicy.ONLY_FAILURES)
                        .processor(record -> {
                            throw new IllegalStateException("retry user" + record.getPayload() + "@example.com",
                                    new IOException("password=hunter2"));
                        })
                        .build())
                .build();

        BatchJobResult first = runner.run(job, () -> payloads(6));

        String prefix = "Found exception processing record on step '%s' for job instance '"
                + first.jobInstanceId() + "' of job 'loggingJob': %s at ";
        List<ILoggingEvent> errors = errorEvents();
        assertThat(errors).hasSize(3);
        assertThat(errors.get(0).getFormattedMessage())
                .startsWith(prefix.formatted("first", IllegalStateException.class.getName()));
        assertThat(errors.get(1).getFormattedMessage())
                .startsWith(prefix.formatted("first", IllegalArgumentException.class.getName()));
        assertThat(errors.get(2).getFormattedMessage())
                .startsWith(prefix.formatted("second", IllegalStateException.class.getName()))
                .contains(" <- " + IOException.class.getName() + " at ");
        assertThat(errors).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain("@example.com", "hunter2");
            assertThat(event.getThrowableProxy()).isNull();
        });
        assertCounts(first, 6, 6, 5);
        assertWellFormed(first);

        BatchJobResult second = runner.run(job, () -> payloads(6));

        assertThat(errorEvents()).hasSize(6);
        assertThat(errorEvents().subList(3, 6)).allSatisfy(
                event -> assertThat(event.getFormattedMessage()).contains("'" + second.jobInstanceId() + "'"));
        assertWellFormed(second);
    }

    @Test
    void jobInstanceIdIsARandomUuidThatDiffersPerRun() throws Exception {
        BatchJobRunner.JobDefinition<Integer> job = BatchJobRunner.JobDefinition.<Integer>builder("idJob").build();

        BatchJobResult first = runner.run(job, () -> payloads(1));
        BatchJobResult second = runner.run(job, () -> payloads(1));

        assertThat(List.of(first.jobInstanceId(), second.jobInstanceId())).allSatisfy(id -> {
            assertThat(UUID.fromString(id).toString()).isEqualTo(id);
            assertThat(UUID.fromString(id).version()).isEqualTo(4);
        });
        assertThat(first.jobInstanceId()).isNotEqualTo(second.jobInstanceId());
        // A job without steps still processes every loaded record.
        assertCounts(first, 1, 1, 0);
        assertWellFormed(first);
        assertWellFormed(second);
    }

    static Stream<Arguments> argumentRules() {
        BatchJobRunner.JobDefinition<Integer> job = BatchJobRunner.JobDefinition.<Integer>builder("rulesJob").build();
        return Stream.of(
                rule("run rejects a null job",
                        () -> new BatchJobRunner().<Integer>run(null, () -> List.of()),
                        NullPointerException.class, "job must not be null"),
                rule("run rejects a null input phase",
                        () -> new BatchJobRunner().run(job, null),
                        NullPointerException.class, "inputPhase must not be null"),
                rule("run rejects an input phase returning null",
                        () -> new BatchJobRunner().run(job, () -> null),
                        NullPointerException.class, "Input phase of job 'rulesJob' returned null"),
                rule("job name must not be null",
                        () -> BatchJobRunner.JobDefinition.<Integer>builder(null),
                        IllegalArgumentException.class, "Job name must not be null or blank"),
                rule("job name must not be blank",
                        () -> BatchJobRunner.JobDefinition.<Integer>builder(" "),
                        IllegalArgumentException.class, "Job name must not be null or blank"),
                rule("step name must not be null",
                        () -> BatchJobRunner.Step.<Integer>builder(null),
                        IllegalArgumentException.class, "Step name must not be null or blank"),
                rule("step name must not be blank",
                        () -> BatchJobRunner.Step.<Integer>builder("\t"),
                        IllegalArgumentException.class, "Step name must not be null or blank"),
                rule("accept policy must not be null",
                        () -> BatchJobRunner.Step.<Integer>builder("s").acceptPolicy(null),
                        NullPointerException.class, "acceptPolicy must not be null"),
                rule("accept expression must not be null",
                        () -> BatchJobRunner.Step.<Integer>builder("s").acceptExpression(null),
                        NullPointerException.class, "acceptExpression must not be null"),
                rule("processor must not be null",
                        () -> BatchJobRunner.Step.<Integer>builder("s").processor(null),
                        NullPointerException.class, "processor must not be null"),
                rule("a step declares at most one commit",
                        () -> BatchJobRunner.Step.<Integer>builder("s")
                                .commit(1, NEVER_COMMITTED)
                                .commit(1, NEVER_COMMITTED),
                        IllegalStateException.class, "Step 's' already declares a commit"),
                rule("commit size must be at least 1",
                        () -> BatchJobRunner.Step.<Integer>builder("s").commit(0, NEVER_COMMITTED),
                        IllegalArgumentException.class, "Commit size of step 's' must be at least 1: 0"),
                rule("commit processor must not be null",
                        () -> BatchJobRunner.Step.<Integer>builder("s").commit(1, null),
                        NullPointerException.class, "commitProcessor must not be null"),
                rule("max failed records must be -1 or more",
                        () -> BatchJobRunner.JobDefinition.<Integer>builder("j").maxFailedRecords(-2),
                        IllegalArgumentException.class, "maxFailedRecords must be -1 or more: -2"),
                rule("step must not be null",
                        () -> BatchJobRunner.JobDefinition.<Integer>builder("j").step(null),
                        NullPointerException.class, "step must not be null"),
                rule("on-complete must not be null",
                        () -> BatchJobRunner.JobDefinition.<Integer>builder("j").onComplete(null),
                        NullPointerException.class, "onComplete must not be null"),
                rule("step names are unique within a job",
                        () -> BatchJobRunner.JobDefinition.<Integer>builder("j")
                                .step(BatchJobRunner.Step.<Integer>builder("s").build())
                                .step(BatchJobRunner.Step.<Integer>builder("s").build())
                                .build(),
                        IllegalArgumentException.class, "Job 'j' declares step 's' more than once"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("argumentRules")
    void documentedArgumentRuleIsEnforced(String rule, ThrowingCallable call, Class<? extends Throwable> type,
                                          String message) {
        assertThatThrownBy(call).isExactlyInstanceOf(type).hasMessage(message);
    }

    private static Arguments rule(String rule, ThrowingCallable call, Class<? extends Throwable> type,
                                  String message) {
        return Arguments.of(rule, call, type, message);
    }

    /** A step named {@code name} whose only processor fails every record whose payload matches {@code fails}. */
    private static BatchJobRunner.Step<Integer> failingStep(String name, Predicate<Integer> fails) {
        return BatchJobRunner.Step.<Integer>builder(name)
                .processor(record -> {
                    if (fails.test(record.getPayload())) {
                        throw new IllegalStateException("record " + record.getPayload() + " failed");
                    }
                })
                .build();
    }

    private static List<Integer> payloads(int count) {
        return IntStream.range(0, count).boxed().toList();
    }

    private List<ILoggingEvent> errorEvents() {
        return appender.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
    }

    private static void assertCounts(BatchJobResult result, long loaded, long processed, long failed) {
        assertThat(result.loadedRecords()).as("loadedRecords").isEqualTo(loaded);
        assertThat(result.processedRecords()).as("processedRecords").isEqualTo(processed);
        assertThat(result.failedRecords()).as("failedRecords").isEqualTo(failed);
        assertThat(result.successfulRecords()).as("successfulRecords").isEqualTo(processed - failed);
    }

    /** Checks what holds for every result: the status flag, the successful-count identity and the duration. */
    private static void assertWellFormed(BatchJobResult result) {
        assertThat(result.isSuccessful()).as("isSuccessful()")
                .isEqualTo(result.status() == BatchJobResult.Status.SUCCESSFUL);
        assertThat(result.successfulRecords()).as("successfulRecords")
                .isEqualTo(result.processedRecords() - result.failedRecords());
        assertThat(result.elapsedTimeInMillis()).as("elapsedTimeInMillis").isNotNegative();
    }
}
