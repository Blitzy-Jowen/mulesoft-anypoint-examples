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

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.SimpleCacheManager;

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
 */
@ExtendWith(OutputCaptureExtension.class)
class FibonacciCacheServiceTest {

    /** The scope's {@code logger message="aaa: #[]"}, whose empty MEL expression renders null. */
    private static final String LOG_MARKER = "aaa: null";

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

    private boolean present(String key) {
        return cache.get(key) != null;
    }

    private static int logCount(CapturedOutput output) {
        String all = output.getAll();
        int count = 0;
        for (int at = all.indexOf(LOG_MARKER); at >= 0; at = all.indexOf(LOG_MARKER, at + LOG_MARKER.length())) {
            count++;
        }
        return count;
    }
}
