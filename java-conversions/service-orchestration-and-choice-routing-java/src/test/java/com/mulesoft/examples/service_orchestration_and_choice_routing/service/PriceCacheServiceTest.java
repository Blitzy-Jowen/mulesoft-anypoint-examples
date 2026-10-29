/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.client.RestClientException;

import com.mulesoft.examples.service_orchestration_and_choice_routing.client.ProductPriceClient;

/**
 * Unit test of {@link PriceCacheService}, the {@code Cache the Price} scope of the {@code inhouseOrder} flow
 * (service-orchestration-and-choice-routing/src/main/app/fulfillment.xml:16-18,103-107): an {@code ee:cache} over an
 * in-memory object store keyed by {@code #[payload.productId]} around {@code GET api/prices/#[payload.productId]}.
 *
 * <p>A plain Spring TestContext with {@code @EnableCaching} applies the real caching proxy to the real service while
 * the price client is a Mockito mock, so the Boot application, its listeners, Derby, Artemis and Narayana are not
 * started and no request leaves the JVM. Each test starts from an empty cache and a reset mock.
 */
@SpringJUnitConfig(PriceCacheServiceTest.Config.class)
class PriceCacheServiceTest {

    @Configuration
    @EnableCaching
    static class Config {

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(PriceCacheService.CACHE_NAME);
        }

        @Bean
        ProductPriceClient productPriceClient() {
            return Mockito.mock(ProductPriceClient.class);
        }

        @Bean
        PriceCacheService priceCacheService(ProductPriceClient productPriceClient) {
            return new PriceCacheService(productPriceClient);
        }
    }

    @Autowired
    private PriceCacheService service;

    @Autowired
    private ProductPriceClient client;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void resetClientAndCache() {
        Mockito.reset(client);
        pricesCache().clear();
    }

    @Test
    void sameProductIdHitsClientOnce() {
        when(client.getPrice("AX02")).thenReturn("159");

        assertThat(service.price("AX02")).isEqualTo("159");
        assertThat(service.price("AX02")).isEqualTo("159");

        verify(client, times(1)).getPrice("AX02");
        verifyNoMoreInteractions(client);
    }

    @Test
    void differentProductIdCallsClientAgain() {
        // Distinct prices show that a cached entry is never served for another product ID.
        when(client.getPrice("AX02")).thenReturn("159");
        when(client.getPrice("i9000")).thenReturn("199");

        assertThat(service.price("AX02")).isEqualTo("159");
        assertThat(service.price("i9000")).isEqualTo("199");
        verify(client, times(1)).getPrice("AX02");
        verify(client, times(1)).getPrice("i9000");

        assertThat(service.price("AX02")).isEqualTo("159");
        verify(client, times(1)).getPrice("AX02");
        verifyNoMoreInteractions(client);
    }

    @Test
    void clientFailurePropagatesAndIsNotCached() {
        RestClientException failure = new RestClientException("down");
        when(client.getPrice("23")).thenThrow(failure).thenReturn("159");

        assertThatThrownBy(() -> service.price("23"))
                .isSameAs(failure)
                .isInstanceOf(RestClientException.class)
                .hasMessage("down");
        assertThat(pricesCache().get("23")).isNull();

        assertThat(service.price("23")).isEqualTo("159");
        verify(client, times(2)).getPrice("23");

        assertThat(service.price("23")).isEqualTo("159");
        verify(client, times(2)).getPrice("23");
        verifyNoMoreInteractions(client);
    }

    @Test
    void cachedValueIsStoredUnderProductId() {
        when(client.getPrice("AX02")).thenReturn("159");

        service.price("AX02");

        Cache.ValueWrapper cached = cacheManager.getCache("prices").get("AX02");
        assertThat(cached).isNotNull();
        assertThat(cached.get()).isEqualTo("159");
    }

    @Test
    void nullProductIdIsRejectedBeforeTheClientIsCalled() {
        // The cache key is the product ID alone, and a cache key cannot be null.
        assertThatThrownBy(() -> service.price(null)).isInstanceOf(IllegalArgumentException.class);

        verify(client, never()).getPrice(any());
    }

    private Cache pricesCache() {
        Cache cache = cacheManager.getCache(PriceCacheService.CACHE_NAME);
        assertThat(cache).isNotNull();
        return cache;
    }
}
