/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test of {@link ProductPriceService}, the price lookup behind 9999 {@code /api/prices/{productId}}
 * ported from the Jersey resource
 * (service-orchestration-and-choice-routing/src/main/java/com/mulesoft/se/orders/ProductPrice.java), which
 * returns {@code "159"} whatever the product.
 *
 * <p>The expected value is a literal, so the tests pin the original reply text rather than re-deriving it.
 */
class ProductPriceServiceTest {

    private final ProductPriceService service = new ProductPriceService();

    @ParameterizedTest
    @ValueSource(strings = {"AX02", "i9000"})
    void returnsConstantPriceForAnyProduct(String productId) {
        assertEquals("159", service.getProductPrice(productId));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void returnsConstantPriceForNullAndEmpty(String productId) {
        // The original never reads productId, so a missing or empty one cannot fail the lookup.
        assertEquals("159", service.getProductPrice(productId));
    }
}
