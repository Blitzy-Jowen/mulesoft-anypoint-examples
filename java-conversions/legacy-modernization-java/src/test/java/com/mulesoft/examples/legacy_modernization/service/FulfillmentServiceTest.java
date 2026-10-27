/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.legacy_modernization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.mulesoft.examples.legacy_modernization.model.Address;
import com.mulesoft.examples.legacy_modernization.model.Order;
import com.mulesoft.examples.legacy_modernization.model.OrderItem;
import com.mulesoft.examples.legacy_modernization.model.ShippingOrder;
import com.mulesoft.examples.legacy_modernization.model.ShippingOrderConfirmation;

/**
 * Unit test of {@link FulfillmentService}, the port of the original {@code org.ordermgmt.FulfillmentImpl} that the
 * "Process Order" {@code component} of {@code FufillmentWebService.xml} invokes: the inputs are wrapped in a
 * {@link ShippingOrder}, confirmed with status {@code true}, kept in the two public fields and the confirmation is
 * returned.
 *
 * <p>The inputs are the values of the original test request {@code message.xml}. Billing and shipping addresses
 * carry equal values but are distinct instances, so identity assertions tell them apart. The service has no
 * collaborators, so no mock is used; only the prototype-scope check starts a plain Spring context.
 */
class FulfillmentServiceTest {

    private static final String SHIPPING_ID = "1234";

    private Address billingAddress;
    private Address shippingAddress;
    private Order order;

    @BeforeEach
    void createRequestValues() {
        billingAddress = messageXmlAddress();
        shippingAddress = messageXmlAddress();
        order = new Order(List.of(
                new OrderItem("1234", 500),
                new OrderItem("6789", 1500),
                new OrderItem("9998", 5000)));
    }

    @Test
    void putShippingOrderReturnsReceivedConfirmation() {
        ShippingOrderConfirmation confirmation =
                new FulfillmentService().putShippingOrder(SHIPPING_ID, billingAddress, shippingAddress, order);

        assertThat(confirmation).isNotNull();
        assertThat(confirmation.getOrderReceivedStatus()).isTrue();
        assertThat(confirmation.getShippingOrder()).isNotNull();
    }

    @Test
    void confirmationEchoesInputs() {
        ShippingOrder shippingOrder = new FulfillmentService()
                .putShippingOrder(SHIPPING_ID, billingAddress, shippingAddress, order)
                .getShippingOrder();

        assertThat(shippingOrder.getShippingId()).isEqualTo(SHIPPING_ID);
        assertThat(shippingOrder.getBillingAddress()).isSameAs(billingAddress);
        assertThat(shippingOrder.getShippingAddress()).isSameAs(shippingAddress);
        assertThat(shippingOrder.getBillingAddress()).isNotSameAs(shippingOrder.getShippingAddress());
        assertThat(shippingOrder.getOrder()).isSameAs(order);
        assertThat(shippingOrder.getOrder().getOrderItemList())
                .extracting(OrderItem::getMerchantSKU, OrderItem::getQuantity)
                .containsExactly(tuple("1234", 500), tuple("6789", 1500), tuple("9998", 5000));
    }

    @Test
    void publicFieldsHoldReturnedInstances() {
        FulfillmentService service = new FulfillmentService();

        ShippingOrderConfirmation confirmation =
                service.putShippingOrder(SHIPPING_ID, billingAddress, shippingAddress, order);

        assertThat(service.shippingOrderConfirmation).isSameAs(confirmation);
        assertThat(service.shippingOrder).isSameAs(confirmation.getShippingOrder());
    }

    @Test
    void implementsPlainInterface() {
        IFulfillment fulfillment = new FulfillmentService();

        ShippingOrderConfirmation confirmation =
                fulfillment.putShippingOrder(SHIPPING_ID, billingAddress, shippingAddress, order);

        assertThat(confirmation.getOrderReceivedStatus()).isTrue();
        assertThat(confirmation.getShippingOrder().getShippingId()).isEqualTo(SHIPPING_ID);
        assertThat(confirmation.getShippingOrder().getBillingAddress()).isSameAs(billingAddress);
        assertThat(confirmation.getShippingOrder().getShippingAddress()).isSameAs(shippingAddress);
        assertThat(confirmation.getShippingOrder().getOrder()).isSameAs(order);
        // The original interface carried @WebService/@WebResult/@WebParam; the port carries no annotation at all.
        assertThat(IFulfillment.class.isInterface()).isTrue();
        assertThat(IFulfillment.class.getAnnotations()).isEmpty();
    }

    @Test
    void isPrototypeScoped() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(FulfillmentService.class)) {
            String[] beanNames = context.getBeanNamesForType(FulfillmentService.class);
            assertThat(beanNames).hasSize(1);
            assertThat(context.getBeanFactory().getBeanDefinition(beanNames[0]).isPrototype()).isTrue();

            FulfillmentService first = context.getBean(FulfillmentService.class);
            FulfillmentService second = context.getBean(FulfillmentService.class);
            assertThat(first).isNotSameAs(second);

            // A call on one instance leaves the per-call fields of another untouched, as Mule's fresh component
            // instance per message did.
            first.putShippingOrder(SHIPPING_ID, billingAddress, shippingAddress, order);
            assertThat(first.shippingOrderConfirmation).isNotNull();
            assertThat(second.shippingOrder).isNull();
            assertThat(second.shippingOrderConfirmation).isNull();
        }
    }

    private static Address messageXmlAddress() {
        return new Address("Mulesoft", "77 Geary St", "Level 4", "San Francisco", "CA", "USA", "94108");
    }
}
