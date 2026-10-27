/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.cache_scope_with_fibonacci;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * Spring Boot entry point of the converted Mule example {@code cache-scope-with-fibonacci} (Apache License 2.0, see
 * the project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, which loaded the flows of
 * {@code cache-scope.xml} into the Mule runtime; this class starts the application as a standalone process on
 * Undertow, with the listener port and address taken from {@code application.yml}.
 *
 * <p>{@link EnableCaching} replaces the flow's {@code ee:cache} scope and its {@code ee:object-store-caching-strategy}
 * with Spring Cache over Caffeine. It is required even though no method is annotated {@code @Cacheable}: Boot creates
 * the {@code CacheManager} that {@code service/FibonacciCacheService} reads the cache {@code fibonacci} from only
 * when caching is enabled. The cache itself is configured in {@code application.yml}.
 */
@SpringBootApplication
@EnableCaching
public class CacheScopeWithFibonacciApplication {

    public static void main(String[] args) {
        SpringApplication.run(CacheScopeWithFibonacciApplication.class, args);
    }
}
