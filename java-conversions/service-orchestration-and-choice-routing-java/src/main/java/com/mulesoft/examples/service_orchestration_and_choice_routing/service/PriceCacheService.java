/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.service;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.service_orchestration_and_choice_routing.client.ProductPriceClient;

/**
 * The {@code Cache the Price} scope of the {@code inhouseOrder} flow ({@code fulfillment.xml:104-107}): an
 * {@code ee:cache} with {@code Caching_Strategy}, an object-store caching strategy over an in-memory store keyed by
 * {@code #[payload.productId]} ({@code fulfillment.xml:16-18}), around the price request and its
 * {@code object-to-string-transformer}.
 *
 * <p>The strategy becomes the Spring cache {@value #CACHE_NAME}, backed by Caffeine through {@code application.yml}
 * with no expiry or size bound, as the in-memory store configured none; entries live until the application stops.
 * The key is the product ID alone. A failed price request throws and stores nothing, so the next order for that
 * product calls the price service again, as Mule's cache keeps only responses that completed.
 *
 * <p>The cache applies through the Spring proxy, so callers are other beans ({@code InHouseOrderService}); this class
 * never calls {@link #price(String)} itself.
 */
@Service
public class PriceCacheService {

	/** Name of the cache standing for {@code Caching_Strategy}; declared in {@code spring.cache.cache-names}. */
	public static final String CACHE_NAME = "prices";

	private final ProductPriceClient productPriceClient;

	/**
	 * Creates the service.
	 *
	 * @param productPriceClient adapter of the price service request on port 9999
	 */
	public PriceCacheService(ProductPriceClient productPriceClient) {
		this.productPriceClient = productPriceClient;
	}

	/**
	 * Returns the price of a product, from the cache when an earlier request for the same product ID succeeded,
	 * otherwise from the price service.
	 *
	 * @param productId the product ID, the cache key as {@code #[payload.productId]} was
	 * @return the price service's response body as text
	 * @throws org.springframework.web.client.RestClientResponseException when the price service answers with a status
	 *         of 400 or above; nothing is cached
	 * @throws org.springframework.web.client.ResourceAccessException on a connection failure or timeout; nothing is
	 *         cached
	 * @throws IllegalArgumentException when {@code productId} is {@code null}, raised by the cache proxy before the
	 *         price service is called, because a cache key cannot be {@code null}
	 */
	@Cacheable(cacheNames = CACHE_NAME, key = "#productId")
	public String price(String productId) {
		return productPriceClient.getPrice(productId);
	}

}
