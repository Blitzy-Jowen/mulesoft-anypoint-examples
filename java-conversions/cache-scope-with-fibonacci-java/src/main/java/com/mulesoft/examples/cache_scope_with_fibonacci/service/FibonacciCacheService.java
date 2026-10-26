/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.cache_scope_with_fibonacci.service;

import java.math.BigDecimal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.cache_scope_with_fibonacci.model.FibonacciResult;

/**
 * Replaces the {@code calculateFibonacci} flow, its {@code ee:cache} scope with the object-store caching strategy,
 * the MEL global function {@code fibonacciRequest} that called the flow recursively over VM, and the
 * {@code FibonacciResponseGenerator} that answered cache hits.
 *
 * <p>Results live in the Spring cache {@value #CACHE_NAME}, keyed by the raw {@code n} string exactly as the
 * strategy's key expression {@code #[message.inboundProperties['http.query.params'].n]} produced it, so
 * {@code "05"} and {@code "5"} are different entries.
 *
 * <p>The cache is read and written programmatically: Spring's caching annotations would be bypassed by the
 * recursive self-invocation and cannot report cost 0 on a hit, and the value-loader form of {@code Cache.get}
 * fails on Caffeine because a load may not recursively load into the same map. Concurrent requests may compute the same key twice,
 * as the original object-store strategy allowed.
 */
@Service
public class FibonacciCacheService {

    private static final Logger LOG = LoggerFactory.getLogger(FibonacciCacheService.class);

    /** Name of the cache that replaces the {@code Caching_Strategy} object store. */
    public static final String CACHE_NAME = "fibonacci";

    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private final Cache cache;

    /**
     * @param cacheManager the application's cache manager, which must hold the cache {@value #CACHE_NAME}
     * @throws IllegalStateException when the cache {@value #CACHE_NAME} is not configured
     */
    public FibonacciCacheService(CacheManager cacheManager) {
        this.cache = cacheManager.getCache(CACHE_NAME);
        if (this.cache == null) {
            throw new IllegalStateException("Cache '" + CACHE_NAME + "' is not configured");
        }
    }

    /**
     * Runs one invocation of the {@code calculateFibonacci} flow.
     *
     * @param key              the raw {@code n} string; it is the cache key, exactly as the key expression
     *                         {@code #[message.inboundProperties['http.query.params'].n]} yields it
     * @param useCache         the cache scope's {@code filterExpression}
     *                         {@code #[groovy:message.getInboundProperty('nocache') == null]}: false when the
     *                         invoking message carried a {@code nocache} property, which is the HTTP header on the
     *                         top-level call or the property {@code fibonacciRequest} sets on a child request
     * @param childrenUseCache the MEL {@code cached = message.inboundProperties['http.query.params'].nocache == null}:
     *                         false only when this invocation's query parameters held {@code nocache}
     * @return the computed result, or the cached result with cost 0 on a cache hit
     * @throws IllegalArgumentException when {@code key} is null, not a number, or a fraction of at least 2
     * @throws ArithmeticException      when {@code key} is an integer outside the {@code int} range
     */
    public FibonacciResult calculate(String key, boolean useCache, boolean childrenUseCache) {
        // Caffeine rejects null keys with a NullPointerException; a null key is reported by the parse below.
        if (useCache && key != null) {
            FibonacciResult stored = cache.get(key, FibonacciResult.class);
            if (stored != null) {
                // FibonacciResponseGenerator: "Creates a cached response for the fibonacci calculation" by
                // setting the outbound cost to 0. The scope's logger does not run on a hit.
                return stored.cached();
            }
        }

        // The cache scope's logger message="aaa: #[]": the empty MEL expression renders null.
        LOG.info("aaa: null");

        if (key == null) {
            throw new IllegalArgumentException("Invalid Fibonacci key: null");
        }
        BigDecimal value;
        try {
            value = new BigDecimal(key);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid Fibonacci key: " + key, e);
        }

        FibonacciResult result;
        if (value.compareTo(TWO) < 0) {
            // n < 2: the payload is n exactly as received, with cost 1.
            result = new FibonacciResult(key, 1);
        } else {
            // fibonacciRequest formats each child's n with Integer.toString, which cannot take a fraction; the
            // original's failure for such a key is a Tier 2A verification point.
            if (value.stripTrailingZeros().scale() > 0) {
                throw new IllegalArgumentException("Fibonacci key must be an integer: " + key);
            }
            int n = value.intValueExact();
            // fibonacciRequest gives each child query parameters holding only n, and sets the nocache property on
            // the child only when this invocation's query held nocache. So a child's cache filter is this
            // invocation's childrenUseCache, and the child's own childrenUseCache is always true.
            // n-1 must run before n-2: on a cold cache the n-1 recursion stores key n-2, so the second call is a
            // hit at cost 0, which yields cost n + 1 for every n >= 2 (Fibonacci(10) costs 11).
            FibonacciResult fib1 = calculate(Integer.toString(n - 1), childrenUseCache, true);
            FibonacciResult fib2 = calculate(Integer.toString(n - 2), childrenUseCache, true);
            result = new FibonacciResult(
                    Long.toString(Long.parseLong(fib1.value()) + Long.parseLong(fib2.value())),
                    fib1.cost() + fib2.cost() + 1);
        }

        // A scope whose filter rejects the message runs its processors without caching; an accepted one stores
        // the computed response with its real cost.
        if (useCache) {
            cache.put(key, result);
        }
        return result;
    }
}
