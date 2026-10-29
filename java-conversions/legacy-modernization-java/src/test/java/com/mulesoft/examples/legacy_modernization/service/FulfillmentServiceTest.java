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
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.mockito.exceptions.base.MockitoException;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.mulesoft.examples.legacy_modernization.model.Address;
import com.mulesoft.examples.legacy_modernization.model.Order;
import com.mulesoft.examples.legacy_modernization.model.OrderItem;
import com.mulesoft.examples.legacy_modernization.model.ShippingOrder;
import com.mulesoft.examples.legacy_modernization.model.ShippingOrderConfirmation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Unit test of {@link FulfillmentService}, the port of the original {@code org.ordermgmt.FulfillmentImpl} that the
 * "Process Order" {@code component} of {@code FufillmentWebService.xml} invokes: the inputs are wrapped in a
 * {@link ShippingOrder}, confirmed with status {@code true}, kept in the two public fields and the confirmation is
 * returned.
 *
 * <p>The inputs are the values of the original test request {@code message.xml}. Billing and shipping addresses
 * carry equal values but are distinct instances, so identity assertions tell them apart. The service has no
 * collaborators; only the failure-path check intercepts the confirmation's constructor with Mockito, and only the
 * prototype-scope check starts a plain Spring context.
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

    @Test
    void failedConfirmationIsSwallowedAndLoggedWithTypeAndThrowSiteOnly() {
        String shippingId = "SHIP-MARKER-4711";
        String street = shippingAddress.getLine1();
        FulfillmentService service = new FulfillmentService();
        Logger logger = (Logger) LoggerFactory.getLogger(FulfillmentService.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.ALL);
        logger.addAppender(appender);

        ShippingOrderConfirmation confirmation;
        // The shipping order is built, then the confirmation's constructor fails with request values in its text;
        // Mockito surfaces that failure as a MockitoException whose cause carries the text.
        try (MockedConstruction<ShippingOrderConfirmation> construction = Mockito.mockConstruction(
                ShippingOrderConfirmation.class, (mock, context) -> {
                    throw new IllegalStateException("Cannot confirm " + shippingId + " shipped to " + street);
                })) {
            confirmation = service.putShippingOrder(shippingId, billingAddress, shippingAddress, order);
            assertThat(construction.constructed()).isEmpty();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
        }

        assertThat(confirmation).isNull();
        assertThat(service.shippingOrderConfirmation).isNull();
        assertThat(service.shippingOrder).isNotNull();
        assertThat(service.shippingOrder.getShippingId()).isEqualTo(shippingId);
        assertThat(service.shippingOrder.getBillingAddress()).isSameAs(billingAddress);
        assertThat(service.shippingOrder.getShippingAddress()).isSameAs(shippingAddress);
        assertThat(service.shippingOrder.getOrder()).isSameAs(order);
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            // The whole message is the type and the first frame, so no exception text of the wrapper or its cause
            // reaches the log.
            assertThat(event.getFormattedMessage())
                    .matches("putShippingOrder failed: " + Pattern.quote(MockitoException.class.getName())
                            + " thrown at " + Pattern.quote(ShippingOrderConfirmation.class.getName() + ".<init>(")
                            + "[^()]*\\)")
                    .doesNotContain(shippingId, street);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    private static Address messageXmlAddress() {
        return new Address("Mulesoft", "77 Geary St", "Level 4", "San Francisco", "CA", "USA", "94108");
    }
}
