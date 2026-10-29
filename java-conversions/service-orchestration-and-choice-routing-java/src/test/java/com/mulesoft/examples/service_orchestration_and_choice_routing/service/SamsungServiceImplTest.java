/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.mulesoft.examples.service_orchestration_and_choice_routing.model.samsung.OrderRequest;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.samsung.OrderResponse;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.samsung.SamsungService;

/**
 * Unit test of {@link SamsungServiceImpl}, the Samsung service behind 9090 {@code /samsung/orders}, ported from
 * service-orchestration-and-choice-routing/src/main/java/com/mulesoft/se/samsung/SamsungServiceImpl.java, which
 * accepts every order with id {@code "1"}, result {@code "ACCEPTED"} and price {@code 2550 * quantity}.
 *
 * <p>The expected values are literals taken from the original, so the tests pin its reply text rather than
 * re-deriving it. The committed reply (src/test/resources/reply.xml) shows the receipt {@code 1 / ACCEPTED} that
 * quantity 1 produces; its {@code 2550.0} rendering comes from the receipt mapping, not from this service.
 */
class SamsungServiceImplTest {

    private final SamsungServiceImpl impl = new SamsungServiceImpl();

    @Test
    void quantityOneIsAcceptedAt2550() {
        OrderResponse response = impl.purchase(request("s-1", 1));

        assertAll(
                () -> assertEquals("1", response.getId()),
                () -> assertEquals("ACCEPTED", response.getResult()),
                () -> assertEquals("2550", response.getPrice()));
    }

    @ParameterizedTest(name = "quantity {0} -> price {1}")
    @CsvSource({
            "0, 0",
            "2, 5100",
            "5, 12750",
            "30, 76500",
            "-1, -2550"
    })
    void priceScalesWithQuantity(int quantity, String expectedPrice) {
        OrderResponse response = impl.purchase(request("s-1", quantity));

        assertAll(
                () -> assertEquals("1", response.getId()),
                () -> assertEquals("ACCEPTED", response.getResult()),
                () -> assertEquals(expectedPrice, response.getPrice()));
    }

    @Test
    void priceUsesIntArithmetic() {
        // 2550 * 1_000_000 exceeds Integer.MAX_VALUE; the original multiplied ints, so the price wraps.
        assertEquals("-1744967296", impl.purchase(request("s-1", 1_000_000)).getPrice());
    }

    @Test
    void nullRequestThrowsNullPointerException() {
        assertThrows(NullPointerException.class, () -> impl.purchase(null));
    }

    @Test
    void implementsSamsungServiceContract() {
        assertInstanceOf(SamsungService.class, impl);
    }

    private static OrderRequest request(String name, int quantity) {
        OrderRequest request = new OrderRequest();
        request.setName(name);
        request.setQuantity(quantity);
        return request;
    }
}
