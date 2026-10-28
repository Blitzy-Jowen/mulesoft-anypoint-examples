package com.mulesoft.examples.hello_world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The fixture replay's quiet window (ST-2): once a JMS queue or the log stream has matched,
 * {@link FixtureParityTest.Replay#awaitQuietWindow} keeps comparing it for the window, so a late message or event
 * fails the scenario at once with the comparison's own mismatch, an interrupt ends the window as {@link Thread#sleep}
 * would, and a window its comparisons overrun fails as an assertion. The helper is called alone with checks built
 * here, so none of the replay state {@link FixtureParityTest} keeps for its own run is touched.
 */
class FixtureParityQuietWindowTest {

    private static final String LABEL = "jms-in-redelivered-until-fifth-delivery: logs";
    /** Far longer than any case below waits, so a window that ends early was visibly ended by its failure. */
    private static final Duration LONG_WINDOW = Duration.ofSeconds(10);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void stopScheduler() throws InterruptedException {
        scheduler.shutdownNow();
        assertTrue(scheduler.awaitTermination(5, TimeUnit.SECONDS), "the scheduler stopped");
        // A scheduled interrupt that outlived its case must not reach the next one.
        Thread.interrupted();
    }

    @Test
    void aCheckThatKeepsPassingHoldsForTheWholeWindowThenReturns() throws Exception {
        Duration window = Duration.ofMillis(300);
        AtomicInteger comparisons = new AtomicInteger();
        long started = System.nanoTime();

        FixtureParityTest.Replay.awaitQuietWindow(LABEL, window, comparisons::incrementAndGet);

        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        assertTrue(elapsed.compareTo(window) >= 0, "returned before the window ended: " + elapsed);
        assertTrue(elapsed.compareTo(window.plus(FixtureParityTest.QUIET_WINDOW_MARGIN)) < 0,
                "returned once the window had held, not at its bound: " + elapsed);
        assertTrue(comparisons.get() >= 2, "compared throughout the window, not once: " + comparisons.get());
    }

    @Test
    void aMismatchArrivingDuringTheWindowFailsAtOnceWithTheCheckOwnAssertion() {
        AssertionError mismatch = new AssertionError("jms-in-redelivered-until-fifth-delivery: the log stream of "
                + "[org.mule.api.processor.LoggerMessageProcessor] has a further event");
        AtomicBoolean lateEvent = new AtomicBoolean();
        AtomicInteger comparisons = new AtomicInteger();
        long started = System.nanoTime();

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> FixtureParityTest.Replay.awaitQuietWindow(LABEL, LONG_WINDOW, () -> {
                    if (lateEvent.get()) {
                        throw mismatch;
                    }
                    if (comparisons.incrementAndGet() == 1) {
                        // The late event is logged by another thread while the window is under way.
                        scheduler.schedule(() -> lateEvent.set(true), 150, TimeUnit.MILLISECONDS);
                    }
                }));

        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        assertSame(mismatch, thrown, "the scenario fails with the comparison's own mismatch");
        assertTrue(comparisons.get() >= 2, "the window had held before the late event: " + comparisons.get());
        assertTrue(elapsed.compareTo(LONG_WINDOW) < 0, "failed before the window would have ended: " + elapsed);
    }

    @Test
    void anInterruptDuringTheWindowEndsItAtTheNextComparisonWithInterruptedException() {
        Thread waiting = Thread.currentThread();
        AtomicInteger comparisons = new AtomicInteger();
        long started = System.nanoTime();

        InterruptedException thrown = assertThrows(InterruptedException.class,
                () -> FixtureParityTest.Replay.awaitQuietWindow(LABEL, LONG_WINDOW, () -> {
                    if (comparisons.incrementAndGet() == 1) {
                        scheduler.schedule(waiting::interrupt, 150, TimeUnit.MILLISECONDS);
                    }
                }));

        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        assertEquals(LABEL + " was interrupted during its " + LONG_WINDOW.toMillis() + " ms quiet window",
                thrown.getMessage());
        assertTrue(elapsed.compareTo(LONG_WINDOW) < 0, "ended before the window would have: " + elapsed);
        assertFalse(Thread.currentThread().isInterrupted(), "the interrupt status is cleared, as Thread.sleep does");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("firstComparisonFailures")
    void aCheckThatFailsAtTheFirstComparisonEndsTheWindowWithThatFailure(Throwable failure) {
        AtomicInteger comparisons = new AtomicInteger();

        Throwable thrown = assertThrows(failure.getClass(),
                () -> FixtureParityTest.Replay.awaitQuietWindow(LABEL, LONG_WINDOW, () -> {
                    comparisons.incrementAndGet();
                    if (failure instanceof Error error) {
                        throw error;
                    }
                    throw (Exception) failure;
                }));

        assertSame(failure, thrown, "the failure is thrown unwrapped");
        assertEquals(1, comparisons.get(), "nothing is compared after the failure");
    }

    static Stream<Throwable> firstComparisonFailures() {
        return Stream.of(
                new AssertionError("jms-in-redelivered-until-fifth-delivery: queue in holds 1 messages but the "
                        + "original left 0"),
                new IOException("the queue browser could not be opened"));
    }

    @Test
    void aWindowItsComparisonsOverrunFailsAsAnAssertionNamingWhatDidNotHold() {
        Duration window = Duration.ofMillis(100);
        Duration limit = window.plus(FixtureParityTest.QUIET_WINDOW_MARGIN);
        CountDownLatch neverReleased = new CountDownLatch(1);
        AtomicInteger comparisons = new AtomicInteger();

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> FixtureParityTest.Replay.awaitQuietWindow(LABEL, window, () -> {
                    if (comparisons.incrementAndGet() == 2) {
                        // A comparison that stalls past the window's bound, as a hung queue browser would.
                        assertFalse(neverReleased.await(limit.toMillis(), TimeUnit.MILLISECONDS));
                    }
                }));

        assertEquals(LABEL + " did not hold for the " + window.toMillis()
                + " ms quiet window: its comparisons did not complete within " + limit.toMillis() + " ms",
                thrown.getMessage());
        assertInstanceOf(ConditionTimeoutException.class, thrown.getCause());
    }
}
