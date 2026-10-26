/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.scatter_gather_flow_control.service;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.stubbing.Answer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mail.MailSendException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.mulesoft.examples.scatter_gather_flow_control.client.AggregationReportMailer;
import com.mulesoft.examples.scatter_gather_flow_control.mapper.ContactsCsvMapper;
import com.mulesoft.examples.scatter_gather_flow_control.mapper.DwCsvWriter;
import com.mulesoft.examples.scatter_gather_flow_control.model.BranchResult;

/**
 * Unit test of {@link ScatterGatherService}, the port of flow {@code scatter-gatherFlow} of
 * {@code scatter-gather-flow-control/src/main/app/scatter-gather.xml}: routes {@code sourceA} (1 vlado, 2 michal) and
 * {@code sourceB} (1 peter, 2 vlado) run concurrently, {@link ContactMergeAggregationStrategy} merges them,
 * {@link ContactsCsvMapper} renders the CSV and {@link AggregationReportMailer} sends it.
 *
 * <p>The aggregation strategy and the CSV mapper are the real classes and the mailer is a Mockito mock, so no SMTP
 * server or network is involved. The one Spring context is the {@link ApplicationContextRunner} of the
 * executor-configuration test, which holds only {@link TaskExecutionAutoConfiguration} and the real
 * {@code application.yml}. The routes run on a two-thread executor of daemon threads named {@code sg-route-<n>},
 * except where a test supplies an executor that rejects routes. The service is a Mockito spy, because the original
 * routes never fail: {@code doThrow} and {@code doAnswer} reach the aggregation-failure branch, and
 * {@code doAnswer} with {@link org.mockito.invocation.InvocationOnMock#callRealMethod()} observes a route while
 * keeping its real payload. Every wait is bounded.
 */
class ScatterGatherServiceTest {

    /** The original's aggregation report: merged contacts carry no {@code Id}, so its column is blank. */
    private static final String EXPECTED_CSV =
            "Id,Name,Email\n,vlado,vlado@email.com\n,michal,michal@email.com\n,peter,peter@email.com\n";

    /** Message of the {@link IllegalStateException} the ported aggregation strategy raises. */
    private static final String EXPECTED_MESSAGE =
            "Data from at least one source was not able to be obtained correctly.";

    private static final String ROUTE_THREAD_PREFIX = "sg-route-";

    private static final long WAIT_SECONDS = 5;

    private ExecutorService routeExecutor;

    private AggregationReportMailer mailer;

    private ScatterGatherService service;

    @BeforeEach
    void setUp() {
        AtomicInteger threadNumber = new AtomicInteger();
        routeExecutor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, ROUTE_THREAD_PREFIX + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        mailer = mock(AggregationReportMailer.class);
        service = spy(new ScatterGatherService(new ContactMergeAggregationStrategy(),
                new ContactsCsvMapper(new DwCsvWriter()), mailer, routeExecutor));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        routeExecutor.shutdownNow();
        assertThat(routeExecutor.awaitTermination(WAIT_SECONDS, SECONDS)).isTrue();
    }

    static Stream<Arguments> routes() {
        return Stream.of(
                Arguments.of("sourceA -> 1 vlado, 2 michal", route(ScatterGatherService::sourceA), expectedSourceA()),
                Arguments.of("sourceB -> 1 peter, 2 vlado", route(ScatterGatherService::sourceB), expectedSourceB()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    void routeBuildsOriginalContactsInNewMutableCollections(String scenario,
            Function<ScatterGatherService, List<Map<String, String>>> route, List<Map<String, String>> expected) {
        List<Map<String, String>> first = route.apply(service);
        List<Map<String, String>> second = route.apply(service);

        assertThat(first).isEqualTo(expected).isInstanceOf(ArrayList.class);
        assertThat(first).allSatisfy(contact -> assertThat(contact).isInstanceOf(HashMap.class));
        assertThat(second).isNotSameAs(first);

        // The MEL scripts built new mutable collections on every call, so a change to one payload reaches no other.
        first.get(0).put("Name", "changed");
        first.add(new HashMap<>());
        assertThat(second).isEqualTo(expected);
        assertThat(route.apply(service)).isEqualTo(expected);
    }

    @Test
    @DisplayName("scatter -> one successful result per route, route A first and route B second")
    void scatterReturnsOneSuccessPerRouteInRouteOrder() {
        List<BranchResult> results = service.scatter();

        assertThat(results).hasSize(2).allSatisfy(result -> {
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.exception()).isNull();
        });
        assertThat(results.get(0).value()).isEqualTo(expectedSourceA());
        assertThat(results.get(1).value()).isEqualTo(expectedSourceB());
        verify(service, times(1)).sourceA();
        verify(service, times(1)).sourceB();
        verifyNoInteractions(mailer);
    }

    @Test
    @DisplayName("scatter runs route A and route B at the same time on the route executor")
    void scatterRunsBothRoutesConcurrentlyOnTheExecutor() {
        CountDownLatch bothRoutesStarted = new CountDownLatch(2);
        Map<String, String> threadByRoute = new ConcurrentHashMap<>();
        Map<String, Boolean> overlappedByRoute = new ConcurrentHashMap<>();
        doAnswer(meetTheOtherRoute("A", bothRoutesStarted, threadByRoute, overlappedByRoute)).when(service).sourceA();
        doAnswer(meetTheOtherRoute("B", bothRoutesStarted, threadByRoute, overlappedByRoute)).when(service).sourceB();

        List<BranchResult> results = service.scatter();

        // A route run after the other would wait out the bounded latch alone and record false.
        assertThat(overlappedByRoute).containsOnly(Map.entry("A", true), Map.entry("B", true));
        assertThat(threadByRoute).containsOnlyKeys("A", "B");
        assertThat(threadByRoute.values()).allSatisfy(name -> assertThat(name).startsWith(ROUTE_THREAD_PREFIX));
        assertThat(threadByRoute.get("A")).isNotEqualTo(threadByRoute.get("B"));
        assertThat(threadByRoute.values()).doesNotContain(Thread.currentThread().getName());
        assertThat(results).extracting(BranchResult::value).containsExactly(expectedSourceA(), expectedSourceB());
    }

    static Stream<Arguments> oneFailingRoute() {
        return Stream.of(
                Arguments.of("route A fails -> A failed, B succeeded", route(ScatterGatherService::sourceA), 0),
                Arguments.of("route B fails -> A succeeded, B failed", route(ScatterGatherService::sourceB), 1));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("oneFailingRoute")
    void scatterRecordsFailingRouteWithItsOwnExceptionAndKeepsTheOther(String scenario,
            Function<ScatterGatherService, List<Map<String, String>>> failingRoute, int failingIndex) {
        IllegalArgumentException failure = new IllegalArgumentException("route down");
        failingRoute.apply(doThrow(failure).when(service));

        List<BranchResult> results = service.scatter();

        assertThat(results).hasSize(2);
        BranchResult failed = results.get(failingIndex);
        BranchResult succeeded = results.get(1 - failingIndex);
        assertThat(failed.isSuccess()).isFalse();
        assertThat(failed.value()).isNull();
        assertSame(failure, failed.exception());
        assertThat(succeeded.isSuccess()).isTrue();
        assertThat(succeeded.value()).isEqualTo(failingIndex == 0 ? expectedSourceB() : expectedSourceA());
    }

    static Stream<Arguments> routeThrowables() {
        IllegalStateException cause = new IllegalStateException("source unavailable");
        CompletionException withoutCause = new CompletionException("no cause", null);
        LinkageError error = new LinkageError("route A linkage");
        return Stream.of(
                Arguments.of("CompletionException wrapping ExecutionException -> the innermost cause",
                        new CompletionException(new ExecutionException(cause)), cause),
                Arguments.of("CompletionException without a cause -> kept as thrown", withoutCause, withoutCause),
                Arguments.of("an Error -> kept as thrown", error, error));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routeThrowables")
    void scatterRecordsTheUnwrappedThrowableOfAFailingRoute(String scenario, Throwable thrown, Throwable expected) {
        doAnswer(invocation -> {
            throw thrown;
        }).when(service).sourceA();

        List<BranchResult> results = service.scatter();

        assertThat(results.get(0).isSuccess()).isFalse();
        assertSame(expected, results.get(0).exception());
        assertThat(results.get(1).value()).isEqualTo(expectedSourceB());
    }

    @Test
    @DisplayName("process -> merged contacts mailed as the original CSV report, the mailer's payload returned")
    void processMailsMergedCsvReportAndReturnsMailerPayload() {
        when(mailer.send(anyString())).thenReturn(AggregationReportMailer.BODY_TEXT);

        String payload = service.process();

        assertThat(payload).isEqualTo("Please find attached your Aggregation Report");
        verify(mailer).send(EXPECTED_CSV);
        verifyNoMoreInteractions(mailer);
        verify(service, times(1)).sourceA();
        verify(service, times(1)).sourceB();
    }

    static Stream<Arguments> failingRoutes() {
        return Stream.of(
                Arguments.of("route A fails", List.of(route(ScatterGatherService::sourceA))),
                Arguments.of("route B fails", List.of(route(ScatterGatherService::sourceB))),
                Arguments.of("both routes fail",
                        List.of(route(ScatterGatherService::sourceA), route(ScatterGatherService::sourceB))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failingRoutes")
    void processWithFailingRouteThrowsIllegalStateAndSendsNoMail(String scenario,
            List<Function<ScatterGatherService, List<Map<String, String>>>> failingRoutes) {
        for (Function<ScatterGatherService, List<Map<String, String>>> failingRoute : failingRoutes) {
            failingRoute.apply(doThrow(new IllegalArgumentException("route down")).when(service));
        }

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> service.process());

        assertThat(thrown).hasMessage(EXPECTED_MESSAGE).hasNoCause();
        verifyNoInteractions(mailer);
    }

    @Test
    @DisplayName("process -> a failed report mail propagates unchanged, as the original flow has no exception strategy")
    void processPropagatesMailFailureUnchanged() {
        MailSendException mailFailure = new MailSendException("SMTP server unavailable");
        when(mailer.send(anyString())).thenThrow(mailFailure);

        MailSendException thrown = assertThrows(MailSendException.class, () -> service.process());

        assertSame(mailFailure, thrown);
        verify(mailer, times(1)).send(EXPECTED_CSV);
    }

    static Stream<Arguments> rejectingExecutors() {
        return Stream.of(
                Arguments.of("shut-down executor rejects every route -> both routes run on the calling thread",
                        executorCase(ScatterGatherServiceTest::rejectingEveryRoute), true, true,
                        RejectedExecutionException.class),
                Arguments.of("route A accepted, route B rejected -> only route B runs on the calling thread",
                        executorCase(ScatterGatherServiceTest::acceptingOnlyTheFirstRoute), false, true,
                        RejectedExecutionException.class),
                Arguments.of("saturated Spring pool -> TaskRejectedException, both routes run on the calling thread",
                        executorCase(ScatterGatherServiceTest::saturatedThreadPoolTaskExecutor), true, true,
                        TaskRejectedException.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectingExecutors")
    void scatterRunsARejectedRouteOnTheCallingThreadAndReturnsBothSuccesses(String scenario,
            Supplier<RejectingExecutor> executorCase, boolean routeARejected, boolean routeBRejected,
            Class<? extends RejectedExecutionException> rejectionType) throws Exception {
        RejectingExecutor rejectingExecutor = executorCase.get();
        try {
            List<RejectedExecutionException> rejections = new CopyOnWriteArrayList<>();
            Executor recordingRejections = command -> {
                try {
                    rejectingExecutor.executor().execute(command);
                } catch (RejectedExecutionException e) {
                    rejections.add(e);
                    throw e;
                }
            };
            ScatterGatherService rejectingService = spy(new ScatterGatherService(new ContactMergeAggregationStrategy(),
                    new ContactsCsvMapper(new DwCsvWriter()), mailer, recordingRejections));
            Map<String, String> threadByRoute = new ConcurrentHashMap<>();
            doAnswer(recordThread("A", threadByRoute)).when(rejectingService).sourceA();
            doAnswer(recordThread("B", threadByRoute)).when(rejectingService).sourceB();

            List<BranchResult> results = rejectingService.scatter();

            assertThat(results).hasSize(2).allSatisfy(result -> assertThat(result.isSuccess()).isTrue());
            assertThat(results).extracting(BranchResult::value).containsExactly(expectedSourceA(), expectedSourceB());
            assertThat(threadByRoute).containsOnlyKeys("A", "B");
            assertRouteThread("A", threadByRoute.get("A"), routeARejected);
            assertRouteThread("B", threadByRoute.get("B"), routeBRejected);
            // A rejected route runs once, on the calling thread, and is never also run by the executor.
            verify(rejectingService, times(1)).sourceA();
            verify(rejectingService, times(1)).sourceB();
            assertThat(rejections).hasSize((routeARejected ? 1 : 0) + (routeBRejected ? 1 : 0))
                    .allSatisfy(rejection -> assertThat(rejection).isExactlyInstanceOf(rejectionType));
            verifyNoInteractions(mailer);
        } finally {
            rejectingExecutor.release().close();
        }
    }

    @Test
    @DisplayName("process with every route rejected -> the routes run on the calling thread and the report is mailed")
    void processWithEveryRouteRejectedStillMailsTheCsvReport() {
        ScatterGatherService rejectingService = new ScatterGatherService(new ContactMergeAggregationStrategy(),
                new ContactsCsvMapper(new DwCsvWriter()), mailer, command -> {
                    throw new RejectedExecutionException("no route thread available");
                });
        when(mailer.send(anyString())).thenReturn(AggregationReportMailer.BODY_TEXT);

        String payload = rejectingService.process();

        assertThat(payload).isEqualTo("Please find attached your Aggregation Report");
        verify(mailer).send(EXPECTED_CSV);
        verifyNoMoreInteractions(mailer);
    }

    @Test
    @DisplayName("application.yml -> applicationTaskExecutor bounded like Mule 3.8's default threading profile")
    void applicationYmlBoundsTheApplicationTaskExecutorLikeMuleDefaultThreadingProfile() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).getBeanNames(Executor.class).containsExactly("applicationTaskExecutor");
                    assertThat(context).getBean("applicationTaskExecutor").isInstanceOf(ThreadPoolTaskExecutor.class);

                    ThreadPoolTaskExecutor executor =
                            context.getBean("applicationTaskExecutor", ThreadPoolTaskExecutor.class);
                    assertThat(executor.getCorePoolSize()).isEqualTo(1);
                    assertThat(executor.getMaxPoolSize()).isEqualTo(16);
                    assertThat(executor.getQueueCapacity()).isZero();
                    assertThat(executor.getKeepAliveSeconds()).isEqualTo(60);

                    // The pool the routes are handed to: no queue, so a route arriving while all 16 threads are busy
                    // is rejected at once and runs on its caller's thread.
                    ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
                    assertThat(pool.getCorePoolSize()).isEqualTo(1);
                    assertThat(pool.getMaximumPoolSize()).isEqualTo(16);
                    assertThat(pool.getKeepAliveTime(SECONDS)).isEqualTo(60);
                    assertThat(pool.getQueue()).isInstanceOf(SynchronousQueue.class);
                    assertThat(pool.allowsCoreThreadTimeOut()).isFalse();
                });
    }

    /**
     * Records the route's thread, then waits, bounded, until the other route has started too before running the real
     * route; {@code overlappedByRoute} holds whether that happened within the bound.
     */
    private static Answer<Object> meetTheOtherRoute(String route, CountDownLatch bothRoutesStarted,
            Map<String, String> threadByRoute, Map<String, Boolean> overlappedByRoute) {
        return invocation -> {
            threadByRoute.put(route, Thread.currentThread().getName());
            bothRoutesStarted.countDown();
            overlappedByRoute.put(route, bothRoutesStarted.await(WAIT_SECONDS, SECONDS));
            return invocation.callRealMethod();
        };
    }

    private static Function<ScatterGatherService, List<Map<String, String>>> route(
            Function<ScatterGatherService, List<Map<String, String>>> route) {
        return route;
    }

    /** Records the route's thread, then runs the real route. */
    private static Answer<Object> recordThread(String route, Map<String, String> threadByRoute) {
        return invocation -> {
            threadByRoute.put(route, Thread.currentThread().getName());
            return invocation.callRealMethod();
        };
    }

    /** A rejected route ran on the calling thread; an accepted one ran on one of the executor's route threads. */
    private static void assertRouteThread(String route, String thread, boolean rejected) {
        String callingThread = Thread.currentThread().getName();
        if (rejected) {
            assertThat(thread).as("rejected route %s", route).isEqualTo(callingThread);
        } else {
            assertThat(thread).as("accepted route %s", route)
                    .startsWith(ROUTE_THREAD_PREFIX)
                    .isNotEqualTo(callingThread);
        }
    }

    private static Supplier<RejectingExecutor> executorCase(Supplier<RejectingExecutor> executorCase) {
        return executorCase;
    }

    /** A JDK pool already shut down: it rejects every task with a plain {@link RejectedExecutionException}. */
    private static RejectingExecutor rejectingEveryRoute() {
        ExecutorService shutDown = Executors.newSingleThreadExecutor();
        shutDown.shutdown();
        return new RejectingExecutor(shutDown, () -> terminate(shutDown));
    }

    /** Runs the first task on its own route thread and rejects every later one, as a pool with one free thread does. */
    private static RejectingExecutor acceptingOnlyTheFirstRoute() {
        ExecutorService firstRouteThread = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, ROUTE_THREAD_PREFIX + "accepted");
            thread.setDaemon(true);
            return thread;
        });
        AtomicInteger submissions = new AtomicInteger();
        Executor acceptingFirst = command -> {
            if (submissions.incrementAndGet() > 1) {
                throw new RejectedExecutionException("no route thread available");
            }
            firstRouteThread.execute(command);
        };
        return new RejectingExecutor(acceptingFirst, () -> terminate(firstRouteThread));
    }

    /**
     * A real Spring pool of one thread and no queue whose only thread a blocking task holds, so it rejects every
     * route with {@link TaskRejectedException}. Its release frees the thread and shuts the pool down.
     */
    private static RejectingExecutor saturatedThreadPoolTaskExecutor() {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(1);
        pool.setMaxPoolSize(1);
        pool.setQueueCapacity(0);
        pool.setThreadNamePrefix(ROUTE_THREAD_PREFIX + "saturated-");
        pool.setDaemon(true);
        pool.initialize();
        CountDownLatch released = new CountDownLatch(1);
        // The pool's worker exists, busy with this task, as soon as execute returns; the bound ends the hold even if
        // the release never comes.
        pool.execute(() -> {
            try {
                released.await(WAIT_SECONDS, SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        return new RejectingExecutor(pool, () -> {
            released.countDown();
            pool.shutdown();
            assertThat(pool.getThreadPoolExecutor().awaitTermination(WAIT_SECONDS, SECONDS)).isTrue();
        });
    }

    private static void terminate(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(WAIT_SECONDS, SECONDS)).isTrue();
    }

    /** Route {@code sourceA} of the original flow. */
    private static List<Map<String, String>> expectedSourceA() {
        return List.of(contact("1", "vlado", "vlado@email.com"), contact("2", "michal", "michal@email.com"));
    }

    /** Route {@code sourceB} of the original flow. */
    private static List<Map<String, String>> expectedSourceB() {
        return List.of(contact("1", "peter", "peter@email.com"), contact("2", "vlado", "vlado@email.com"));
    }

    private static Map<String, String> contact(String id, String name, String email) {
        Map<String, String> contact = new HashMap<>();
        contact.put("Id", id);
        contact.put("Name", name);
        contact.put("Email", email);
        return contact;
    }

    /** An executor that rejects routes, and the release of what it holds, run once the test is over. */
    private record RejectingExecutor(Executor executor, AutoCloseable release) {
    }
}
