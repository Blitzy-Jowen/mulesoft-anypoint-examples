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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.OrderItem;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.PurchaseReceipt;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.Status;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.samsung.OrderRequest;

/**
 * The {@code samsungOrder} script "OrderItem to OrderRequest" ({@code fulfillment.xml}, header line 53),
 * reimplemented by {@link OrderRequestMapper}: {@code name} and {@code quantity} are copied from the order item and
 * nothing else reaches the Samsung {@code purchase} request.
 *
 * <p>The mapped inputs are the Samsung item of the original's {@code message.xml} (here carrying the receipt the
 * orchestration later attaches, to show it is not copied), the Studio sample {@code OrderItem_2.dwl}, and an item
 * with nothing set, whose {@code null} name and zero quantity pass through as DataWeave passed them. These differ
 * only in their inputs, so they are one parameterized test.
 *
 * <p>A {@code null} item must fail with the {@link NullPointerException} the mapper declares, because the calling
 * service's catch branch, not the mapper, answers a failed transform with the {@code REJECTED} receipt.
 */
class OrderRequestMapperTest {

    private final OrderRequestMapper mapper = new OrderRequestMapper();

    static Stream<Arguments> orderItems() {
        return Stream.of(
                Arguments.of("message.xml Samsung item", messageXmlItem(), "s-1", 1),
                Arguments.of("Studio sample OrderItem_2", item("Samsung", "Galaxy", "i1900", null, 1), "Galaxy", 1),
                Arguments.of("item with nothing set", new OrderItem(), null, 0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("orderItems")
    void mapsNameAndQuantityFromTheItem(String scenario, OrderItem item, String expectedName, int expectedQuantity) {
        OrderRequest request = mapper.map(item);

        assertThat(request).isNotNull();
        assertThat(request.getName()).isEqualTo(expectedName);
        assertThat(request.getQuantity()).isEqualTo(expectedQuantity);
    }

    @Test
    void setsNoOtherField() {
        // JaCoCo's instrumentation adds a static synthetic field, so only instance fields declared in source count.
        String[] instanceFields = Arrays.stream(OrderRequest.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic() && !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName)
                .toArray(String[]::new);

        assertThat(OrderRequest.class.getSuperclass()).isEqualTo(Object.class);
        assertThat(instanceFields).containsExactlyInAnyOrder("name", "quantity");
    }

    @Test
    void doesNotModifyTheItem() {
        OrderItem item = messageXmlItem();
        PurchaseReceipt receipt = item.getPurchaseReceipt();

        mapper.map(item);

        assertThat(item.getManufacturer()).isEqualTo("Samsung");
        assertThat(item.getName()).isEqualTo("s-1");
        assertThat(item.getProductId()).isEqualTo("AX02");
        assertThat(item.getQuantity()).isEqualTo(1);
        assertThat(item.getPurchaseReceipt()).isSameAs(receipt);
        assertThat(receipt.getId()).isEqualTo("1");
        assertThat(receipt.getStatus()).isSameAs(Status.ACCEPTED);
        assertThat(receipt.getTotalPrice()).isEqualTo(2550.0f);
    }

    @Test
    void returnsANewRequestPerCall() {
        OrderItem item = messageXmlItem();

        OrderRequest first = mapper.map(item);
        OrderRequest second = mapper.map(item);

        assertThat(second).isNotSameAs(first);
        assertThat(second.getName()).isEqualTo(first.getName());
        assertThat(second.getQuantity()).isEqualTo(first.getQuantity());
    }

    @Test
    void nullItemThrows() {
        assertThatThrownBy(() -> mapper.map(null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("item");
    }

    /** The Samsung item of {@code message.xml}, with the receipt the ported Samsung service answers for it. */
    private static OrderItem messageXmlItem() {
        PurchaseReceipt receipt = new PurchaseReceipt();
        receipt.setId("1");
        receipt.setStatus(Status.ACCEPTED);
        receipt.setTotalPrice(2550.0f);
        return item("Samsung", "s-1", "AX02", receipt, 1);
    }

    private static OrderItem item(String manufacturer, String name, String productId, PurchaseReceipt receipt,
            int quantity) {
        OrderItem item = new OrderItem();
        item.setManufacturer(manufacturer);
        item.setName(name);
        item.setProductId(productId);
        item.setPurchaseReceipt(receipt);
        item.setQuantity(quantity);
        return item;
    }
}
