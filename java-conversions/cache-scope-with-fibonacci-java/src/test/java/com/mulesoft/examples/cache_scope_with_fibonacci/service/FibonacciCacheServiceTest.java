/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.cache_scope_with_fibonacci.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.test.util.ReflectionTestUtils;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.mulesoft.examples.cache_scope_with_fibonacci.model.FibonacciResult;

/**
 * {@link FibonacciCacheService} against the {@code calculateFibonacci} flow and its {@code ee:cache} scope
 * (cache-scope-with-fibonacci/src/main/app/cache-scope.xml:7-27,35,61-82) and the {@code FibonacciResponseGenerator}
 * that answered cache hits.
 *
 * <p>Cost model the expectations are derived from: {@code n < 2} echoes the raw key with cost 1; otherwise
 * {@code n-1} is computed before {@code n-2}, the value is their sum and the cost is their costs plus 1; a cache hit
 * costs 0, so a cold computation of {@code n >= 2} costs {@code n + 1}. {@code useCache = false} (the
 * {@code nocache} header) bypasses only this invocation's lookup and store; {@code childrenUseCache = false} (the
 * {@code nocache} query parameter) makes the two direct children bypass, while the grandchildren use the cache
 * again. Keys are the raw {@code n} strings.
 *
 * <p>The scope's logger runs once per computed (not cached) invocation, so log counts are compared as deltas
 * around each call and stay independent of other output in the same JVM.
 *
 * <p>Access to an entry is synchronized per key: a concurrent invocation for a key being computed waits and then
 * receives the stored result with cost 0, while an invocation whose cache filter rejects the message takes no lock.
 */
@ExtendWith(OutputCaptureExtension.class)
class FibonacciCacheServiceTest {

    /** The scope's {@code logger message="aaa: #[]"}, whose empty MEL expression renders null. */
    private static final String LOG_MARKER = "aaa: null";

    /** Bound on every wait of the concurrency tests, so a deadlock or a lock left held fails instead of hanging. */
    private static final long TIMEOUT_SECONDS = 10;

    /** How long the concurrency tests watch waiting invocations before releasing the one holding the key. */
    private static final long OBSERVATION_MILLIS = 200;

    /** Concurrent requests per concurrency test. */
    private static final int THREADS = 10;

    private Cache cache;

    private FibonacciCacheService service;

    @BeforeEach
    void setUp() {
        // A named cache makes the manager static, so it holds this cache only.
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(FibonacciCacheService.CACHE_NAME);
        cache = cacheManager.getCache(FibonacciCacheService.CACHE_NAME);
        service = new FibonacciCacheService(cacheManager);
    }

    @Test
    void tenThenFive(CapturedOutput output) {
        int before = logCount(output);
        FibonacciResult ten = service.calculate("10", true, true);

        assertEquals(new FibonacciResult("55", 11), ten);
        for (int n = 0; n <= 10; n++) {
            assertTrue(present(Integer.toString(n)), "key " + n + " is cached");
        }
        assertEquals(11, logCount(output) - before, "each key from 0 to 10 is computed exactly once");

        int afterTen = logCount(output);
        FibonacciResult five = service.calculate("5", true, true);

        assertEquals(new FibonacciResult("5", 0), five);
        assertEquals(afterTen, logCount(output), "a cache hit does not run the scope's logger");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1"})
    void baseCaseEchoesTheKeyWithCostOne(String key) {
        assertEquals(new FibonacciResult(key, 1), service.calculate(key, true, true));
        assertTrue(present(key));
    }

    @Test
    void nineteenOnAColdCacheCostsTwenty() {
        assertEquals(new FibonacciResult("4181", 20), service.calculate("19", true, true));
    }

    @Test
    void topLevelBypassOnAnEmptyCacheStoresOnlyTheChildren() {
        // cold 2 costs 3, 1 is then a hit at cost 0, plus 1
        assertEquals(new FibonacciResult("2", 4), service.calculate("3", false, true));

        assertFalse(present("3"));
        assertTrue(present("2"));
        assertTrue(present("1"));
        assertTrue(present("0"));
    }

    @Test
    void topLevelBypassNeverReadsOrOverwritesAnExistingEntry() {
        FibonacciResult sentinel = new FibonacciResult("999", 42);
        cache.put("3", sentinel);

        assertEquals(new FibonacciResult("2", 4), service.calculate("3", false, true));
        assertEquals(sentinel, cache.get("3").get());
    }

    @Test
    void childrenBypassOnAnEmptyCacheSkipsOnlyTheDirectChildren() {
        // child 3 bypasses (4), child 2 bypasses with both grandchildren hits (1), plus 1
        assertEquals(new FibonacciResult("3", 6), service.calculate("4", true, false));

        assertTrue(present("4"));
        assertFalse(present("3"));
        assertTrue(present("2"));
        assertTrue(present("1"));
        assertTrue(present("0"));
    }

    @Test
    void cacheHitIgnoresTheChildrenFlag(CapturedOutput output) {
        service.calculate("5", true, true);

        int before = logCount(output);
        assertEquals(new FibonacciResult("5", 0), service.calculate("5", true, false));
        assertEquals(before, logCount(output));
    }

    @Test
    void keysAreTheRawStrings() {
        service.calculate("5", true, true);

        // "05" misses; its children "4" and "3" are hits
        assertEquals(new FibonacciResult("5", 1), service.calculate("05", true, true));
        assertTrue(present("5"));
        assertTrue(present("05"));
    }

    @Test
    void negativeKeyTakesTheBaseCaseAndEchoesTheRawKey() {
        assertEquals(new FibonacciResult("-3", 1), service.calculate("-3", true, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2.5", "abc"})
    void invalidKeyIsRejectedAndNotCached(String key) {
        assertThrows(IllegalArgumentException.class, () -> service.calculate(key, true, true));
        assertFalse(present(key));
    }

    @Test
    void nullKeyIsRejected() {
        // Caffeine rejects null keys, so the service must neither look up nor store one.
        assertThrows(IllegalArgumentException.class, () -> service.calculate(null, true, true));
    }

    @Test
    void missingFibonacciCacheFailsConstruction() {
        assertThrows(IllegalStateException.class, () -> new FibonacciCacheService(new CaffeineCacheManager("other")));

        SimpleCacheManager empty = new SimpleCacheManager();
        empty.setCaches(List.of());
        empty.afterPropertiesSet();
        assertThrows(IllegalStateException.class, () -> new FibonacciCacheService(empty));
    }

    @Test
    void cachedCopyReportsCostZeroAndLeavesTheOriginalUnchanged() {
        FibonacciResult computed = new FibonacciResult("55", 11);

        FibonacciResult cached = computed.cached();

        assertEquals("55", cached.value());
        assertEquals(0, cached.cost());
        assertEquals(11, computed.cost());
    }

    @Test
    void concurrentColdRequestsForOneKeyComputeItOnceAndTheOthersReceiveTheStoredResult(CapturedOutput output)
            throws Exception {
        GatedCache gated = new GatedCache("15");
        FibonacciCacheService gatedService = new FibonacciCacheService(cacheManagerOf(gated));
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            int before = logCount(output);
            Future<FibonacciResult> first = pool.submit(() -> gatedService.calculate("15", true, true));
            assertTrue(gated.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the first request looks up key 15");
            List<Future<FibonacciResult>> others = new ArrayList<>();
            for (int i = 1; i < THREADS; i++) {
                others.add(pool.submit(() -> gatedService.calculate("15", true, true)));
            }

            Thread.sleep(OBSERVATION_MILLIS);
            assertTrue(others.stream().noneMatch(Future::isDone), "the others wait while key 15 is being computed");
            gated.release.countDown();

            assertEquals(new FibonacciResult("610", 16), first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            for (Future<FibonacciResult> other : others) {
                assertEquals(new FibonacciResult("610", 0), other.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            assertEquals(16, logCount(output) - before, "each key from 0 to 15 is computed exactly once");
            assertEquals(new FibonacciResult("610", 16), gated.get("15", FibonacciResult.class));
            assertTrue(lockRegistry(gatedService).isEmpty(), "no lock entry outlives its invocations");
        } finally {
            gated.release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void bypassingInvocationNeitherWaitsForNorTakesTheKeysLock() throws Exception {
        GatedCache gated = new GatedCache("15");
        FibonacciCacheService gatedService = new FibonacciCacheService(cacheManagerOf(gated));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<FibonacciResult> holder = pool.submit(() -> gatedService.calculate("15", true, true));
            assertTrue(gated.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the holder looks up key 15");

            // cold 14 costs 15, 13 is then a hit at cost 0, plus 1; nothing is stored under 15
            Future<FibonacciResult> bypass = pool.submit(() -> gatedService.calculate("15", false, true));
            assertEquals(new FibonacciResult("610", 16), bypass.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertFalse(holder.isDone(), "the holder still holds key 15");
            gated.release.countDown();

            // the holder misses, and both children are hits stored by the bypassing invocation
            assertEquals(new FibonacciResult("610", 1), holder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertTrue(lockRegistry(gatedService).isEmpty(), "no lock entry outlives its invocations");
        } finally {
            gated.release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentRequestsForOverlappingKeysCompleteAndComputeEachKeyOnce(CapturedOutput output) throws Exception {
        // ascending then descending, so requests lock overlapping keys while others hold them
        List<Integer> numbers = new ArrayList<>();
        for (int n = 10; n <= 19; n++) {
            numbers.add(n);
        }
        for (int n = 19; n >= 10; n--) {
            numbers.add(n);
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(numbers.size());
        try {
            int before = logCount(output);
            List<Future<FibonacciResult>> results = new ArrayList<>();
            for (int n : numbers) {
                results.add(pool.submit(() -> {
                    start.await();
                    return service.calculate(Integer.toString(n), true, true);
                }));
            }
            start.countDown();

            for (int i = 0; i < numbers.size(); i++) {
                FibonacciResult result = results.get(i).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertEquals(fibonacci(numbers.get(i)), result.value(), "Fibonacci(" + numbers.get(i) + ")");
            }
            assertEquals(20, logCount(output) - before, "each key from 0 to 19 is computed exactly once");
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
        for (int n = 0; n <= 19; n++) {
            assertEquals(fibonacci(n), cache.get(Integer.toString(n), FibonacciResult.class).value(), "stored " + n);
        }
        assertTrue(lockRegistry(service).isEmpty(), "no lock entry outlives its invocations");
    }

    @ParameterizedTest
    @CsvSource({
        "abc, java.lang.IllegalArgumentException",
        "2.5, java.lang.IllegalArgumentException",
        "99999999999, java.lang.ArithmeticException"
    })
    void failedInvocationReleasesTheKeysLock(String key, Class<? extends Throwable> failure) throws Exception {
        assertThrows(failure, () -> service.calculate(key, true, true));
        assertTrue(lockRegistry(service).isEmpty(), "the failed invocation left no lock entry");

        // A lock left held would block another thread, whereas the failing thread itself could re-enter it.
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            Future<?> retry = other.submit(() -> assertThrows(failure, () -> service.calculate(key, true, true)));
            retry.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            other.shutdownNow();
        }
        assertFalse(present(key));
    }

    private boolean present(String key) {
        return cache.get(key) != null;
    }

    /** The service's per-key lock registry, read reflectively because the service exposes no view of it. */
    private static Map<?, ?> lockRegistry(FibonacciCacheService target) {
        return (Map<?, ?>) ReflectionTestUtils.getField(target, "keyLocks");
    }

    private static CacheManager cacheManagerOf(Cache fibonacciCache) {
        SimpleCacheManager cacheManager = new SimpleCacheManager();
        cacheManager.setCaches(List.of(fibonacciCache));
        cacheManager.afterPropertiesSet();
        return cacheManager;
    }

    /** The Fibonacci number of {@code n >= 0}, as the flow renders it. */
    private static String fibonacci(int n) {
        long current = 0;
        long next = 1;
        for (int i = 0; i < n; i++) {
            long sum = current + next;
            current = next;
            next = sum;
        }
        return Long.toString(current);
    }

    private static int logCount(CapturedOutput output) {
        String all = output.getAll();
        int count = 0;
        for (int at = all.indexOf(LOG_MARKER); at >= 0; at = all.indexOf(LOG_MARKER, at + LOG_MARKER.length())) {
            count++;
        }
        return count;
    }

    /**
     * The {@value FibonacciCacheService#CACHE_NAME} cache over Caffeine, whose first lookup of one key signals
     * {@code entered} and then waits for {@code release}, so a test can act while an invocation is inside that key's
     * lookup.
     */
    private static final class GatedCache extends CaffeineCache {

        private final String gatedKey;

        private final AtomicBoolean firstLookup = new AtomicBoolean(true);

        private final CountDownLatch entered = new CountDownLatch(1);

        private final CountDownLatch release = new CountDownLatch(1);

        GatedCache(String gatedKey) {
            super(FibonacciCacheService.CACHE_NAME, Caffeine.newBuilder().build());
            this.gatedKey = gatedKey;
        }

        @Override
        public <T> T get(Object key, Class<T> type) {
            if (gatedKey.equals(key) && firstLookup.compareAndSet(true, false)) {
                entered.countDown();
                try {
                    if (!release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("The lookup of key " + gatedKey + " was never released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted in the lookup of key " + gatedKey, e);
                }
            }
            return super.get(key, type);
        }
    }
}
