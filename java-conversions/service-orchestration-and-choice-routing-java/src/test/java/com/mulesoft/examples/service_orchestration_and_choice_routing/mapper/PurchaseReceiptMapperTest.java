/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.PurchaseReceipt;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.Status;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.samsung.OrderResponse;

/**
 * The {@code samsungOrder} script "OrderResponse to PurchaseReceipt" ({@code fulfillment.xml}, header line 68),
 * reimplemented by {@link PurchaseReceiptMapper}: {@code id} from the response's {@code id}, {@code status} from
 * its {@code result} and {@code totalPrice} from its {@code price}.
 *
 * <p>The accepted inputs are the answer of the ported {@code SamsungServiceImpl} for one item (id {@code 1},
 * {@code ACCEPTED}, price {@code 2550}, rendered {@code 2550.0} in the original's {@code reply.xml}), the Studio
 * sample {@code OrderResponse_1.dwl}, and a {@code REJECTED} result with a fractional price, which shows that the
 * status and the price are read from the response rather than fixed.
 *
 * <p>The mapper catches nothing: each malformed response must fail with the JDK exception its Javadoc declares,
 * because the calling service's catch branch, not the mapper, produces the {@code REJECTED} receipt. Exception
 * types are asserted exactly, since {@link NumberFormatException} is itself an {@link IllegalArgumentException}.
 */
class PurchaseReceiptMapperTest {

    private final PurchaseReceiptMapper mapper = new PurchaseReceiptMapper();

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "Samsung service answer for one item, 1,   ACCEPTED, 2550, ACCEPTED, 2550.0",
        "Studio sample OrderResponse_1,       123, ACCEPTED, 450,  ACCEPTED, 450.0",
        "rejected result with decimal price,  7,   REJECTED, 4.15, REJECTED, 4.15"
    })
    void mapsIdResultAndPrice(String scenario, String id, String result, String price,
            Status expectedStatus, float expectedTotalPrice) {
        PurchaseReceipt receipt = mapper.map(response(id, result, price));

        assertThat(receipt.getId()).isEqualTo(id);
        assertThat(receipt.getStatus()).isSameAs(expectedStatus);
        assertThat(receipt.getTotalPrice()).isEqualTo(expectedTotalPrice);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(nullValues = "null", value = {
        "unknown result,                BOGUS,    2550, java.lang.IllegalArgumentException",
        "result in another letter case, accepted, 2550, java.lang.IllegalArgumentException",
        "null result,                   null,     2550, java.lang.NullPointerException",
        "non-numeric price,             ACCEPTED, abc,  java.lang.NumberFormatException",
        "null price,                    ACCEPTED, null, java.lang.NullPointerException"
    })
    void malformedResponseFails(String scenario, String result, String price,
            Class<? extends RuntimeException> expectedFailure) {
        OrderResponse response = response("1", result, price);

        assertThatThrownBy(() -> mapper.map(response)).isExactlyInstanceOf(expectedFailure);
    }

    @Test
    void nullResponseFails() {
        assertThatThrownBy(() -> mapper.map(null)).isExactlyInstanceOf(NullPointerException.class);
    }

    private static OrderResponse response(String id, String result, String price) {
        OrderResponse response = new OrderResponse();
        response.setId(id);
        response.setResult(result);
        response.setPrice(price);
        return response;
    }
}
