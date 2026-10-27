/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.cache_scope_with_fibonacci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import com.mulesoft.examples.cache_scope_with_fibonacci.service.FibonacciCacheService;
import com.mulesoft.examples.cache_scope_with_fibonacci.support.HttpTestClient;

/**
 * Port of the original example's {@code CacheScopeWithFibonacciIT} functional test, keeping its two requests and
 * their exact-equality assertions; wire-level checks of status and headers live in
 * {@code controller/FibonacciControllerTest}.
 *
 * <p>The expected costs depend on the cache state: {@code COST: 11} holds only on a cold cache, and
 * {@code COST: 0} only because the first request stored keys {@code "0"} to {@code "10"}. Every Spring test class
 * on the default configuration shares this application context and so its {@code fibonacci} cache, so the cache is
 * cleared before each test rather than relying on class or method order.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CacheScopeWithFibonacciTest {

    private static final String MESSAGE = "10";
    private static final String MESSAGE1 = "5";

    @LocalServerPort
    private int port;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void clearFibonacciCache() {
        Cache cache = cacheManager.getCache(FibonacciCacheService.CACHE_NAME);
        assertNotNull(cache, "cache " + FibonacciCacheService.CACHE_NAME);
        cache.clear();
    }

    @Test
    void httpGetToFlowUrlSentMessage() {
        // The listener binds localhost (server.address), as the original listener config did.
        HttpTestClient client = HttpTestClient.localhost(port);

        HttpTestClient.Response response = client.send("GET", "/fibonacci?n=" + MESSAGE, Map.of(), new byte[0]);
        assertNotNull(response);
        // A Mule NullPayload result corresponds to an empty HTTP body.
        assertFalse(response.bodyAsString().isEmpty());
        assertEquals("Fibonacci(10) = 55\nCOST: 11", response.bodyAsString());

        response = client.send("GET", "/fibonacci?n=" + MESSAGE1, Map.of(), new byte[0]);
        assertNotNull(response);
        assertFalse(response.bodyAsString().isEmpty());
        assertEquals("Fibonacci(5) = 5\nCOST: 0", response.bodyAsString());
    }
}
