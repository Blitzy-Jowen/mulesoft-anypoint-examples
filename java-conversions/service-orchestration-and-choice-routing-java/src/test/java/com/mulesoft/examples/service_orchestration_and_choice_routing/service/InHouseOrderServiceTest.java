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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClientException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.OrderItem;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.Status;

/**
 * Unit test of {@link InHouseOrderService}, the {@code inhouseOrder} flow of
 * {@code service-orchestration-and-choice-routing/src/main/app/fulfillment.xml} (lines 98-129): the cached price
 * request ({@code ProductPrice} answers {@code "159"}), the {@code orders} insert, {@code totalPrice = price *
 * payload.quantity}, the session variable {@code totalValue += totalPrice}, the Groovy {@code ACCEPTED} receipt, and the
 * {@code rollback-exception-strategy maxRedeliveryAttempts="3"} whose logger writes {@code #[payload]} at INFO before
 * the rollback and whose {@code on-redelivery-attempts-exceeded} runs {@code defaultErrorHandler}.
 *
 * <p>All collaborators are Mockito mocks and the service is built directly, so no Spring context, broker or database
 * starts; the class-level {@code @Transactional(MANDATORY)} needs a proxy and is therefore inert here. A rethrown
 * exception is what makes the listener's JTA transaction roll back, so every failure test asserts that the original
 * instance escapes.
 *
 * <p>{@code totalValue} is a String in MEL, so {@code +} concatenates: {@code "0"} and a total price of 318 give
 * {@code "0318"}. The log oracle is a Logback {@link ListAppender} on the service's logger, set to {@code ALL} for each
 * test and restored afterwards.
 */
@ExtendWith(MockitoExtension.class)
class InHouseOrderServiceTest {

    private static final int MAX_REDELIVERY_ATTEMPTS = 3;

    private static final String PRICE = "159";

    @Mock
    private PriceCacheService priceCache;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ObjectProvider<OrderOrchestrationService> orchestrationProvider;

    @Mock
    private OrderOrchestrationService orchestration;

    private InHouseOrderService service;

    private OrderItem item;

    private Logger serviceLogger;

    private Level previousLevel;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        service = new InHouseOrderService(priceCache, jdbcTemplate, orchestrationProvider, MAX_REDELIVERY_ATTEMPTS);
        item = item();

        serviceLogger = (Logger) LoggerFactory.getLogger(InHouseOrderService.class);
        previousLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.ALL);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void restoreLogger() {
        serviceLogger.detachAppender(appender);
        appender.stop();
        serviceLogger.setLevel(previousLevel);
    }

    @Test
    void processInsertsOrderAndAcceptsWithTotalPrice() {
        when(priceCache.price("23")).thenReturn(PRICE);

        InHouseOrderService.Result result = service.process(item, "0");

        assertThat(InHouseOrderService.INSERT_ORDER).isEqualTo(
                "insert into orders (product_id, name, manufacturer, quantity, price) values (?, ?, ?, ?, ?)");
        verify(jdbcTemplate, times(1)).update(InHouseOrderService.INSERT_ORDER, "23", "P3123", "Philips", 2, PRICE);
        verifyNoMoreInteractions(jdbcTemplate);
        assertThat(result.receipt().getStatus()).isSameAs(Status.ACCEPTED);
        assertThat(result.receipt().getTotalPrice()).isEqualTo(318.0f);
        assertThat(result.receipt().getId()).isNull();
        assertThat(result.totalValue()).isEqualTo("0318");
        verifyNoInteractions(orchestrationProvider, orchestration);
        assertThat(appender.list).isEmpty();
    }

    /** An absent {@code totalValue} property contributes nothing to the concatenation. */
    @ParameterizedTest(name = "totalValue {0} -> {1}")
    @CsvSource({ "02550, 02550318", ", 318" })
    void processConcatenatesOntoExistingTotalValue(String totalValue, String expected) {
        when(priceCache.price("23")).thenReturn(PRICE);

        InHouseOrderService.Result result = service.process(item, totalValue);

        assertThat(result.totalValue()).isEqualTo(expected);
        assertThat(result.receipt().getTotalPrice()).isEqualTo(318.0f);
    }

    /** The price listener accepts POST only, so in the running application every lookup ends here with a 405. */
    @Test
    void priceLookupFailureIsLoggedAndRethrown() {
        RestClientException failure = new RestClientException("405");
        when(priceCache.price("23")).thenThrow(failure);

        assertThatThrownBy(() -> service.process(item, "0")).isSameAs(failure);

        verifyNoInteractions(jdbcTemplate);
        assertPayloadLogged(String.valueOf(item));
    }

    @Test
    void insertFailureIsRethrown() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("db down");
        when(priceCache.price("23")).thenReturn(PRICE);
        when(jdbcTemplate.update(InHouseOrderService.INSERT_ORDER, "23", "P3123", "Philips", 2, PRICE))
                .thenThrow(failure);

        assertThatThrownBy(() -> service.process(item, "0")).isSameAs(failure);

        assertPayloadLogged(String.valueOf(item));
    }

    /** The insert has already run; the rethrow rolls it back with the consumed request. */
    @Test
    void nonNumericPriceFailsAfterInsertAndIsRethrown() {
        when(priceCache.price("23")).thenReturn("n/a");

        assertThatThrownBy(() -> service.process(item, "0")).isInstanceOf(NumberFormatException.class);

        verify(jdbcTemplate).update(InHouseOrderService.INSERT_ORDER, "23", "P3123", "Philips", 2, "n/a");
        assertPayloadLogged(String.valueOf(item));
    }

    @Test
    void nullItemIsLoggedAndRethrown() {
        assertThatThrownBy(() -> service.process(null, "0")).isInstanceOf(NullPointerException.class);

        verifyNoInteractions(priceCache, jdbcTemplate);
        assertPayloadLogged("null");
    }

    /** Redeliveries so far are {@code JMSXDeliveryCount - 1}; exhaustion means they exceed the configured limit. */
    @ParameterizedTest(name = "max {0}, delivery {1} -> exhausted {2}")
    @CsvSource({ "3, 1, false", "3, 4, false", "3, 5, true", "3, 6, true", "1, 2, false", "1, 3, true" })
    void redeliveryExhaustion(int maxRedeliveryAttempts, int deliveryCount, boolean exhausted) {
        InHouseOrderService limited = new InHouseOrderService(priceCache, jdbcTemplate, orchestrationProvider,
                maxRedeliveryAttempts);

        assertThat(limited.isRedeliveryExhausted(deliveryCount)).isEqualTo(exhausted);
    }

    @Test
    void receiveAfterExhaustionLogsAndConsumes() {
        when(orchestrationProvider.getObject()).thenReturn(orchestration);

        assertThat(service.receive(item, "0", 5)).isNull();

        verify(orchestration).logError(item);
        verifyNoInteractions(priceCache, jdbcTemplate);
        assertThat(appender.list).isEmpty();
    }

    @ParameterizedTest(name = "delivery {0}")
    @ValueSource(ints = { 1, 4 })
    void receiveWithinRedeliveryBudgetProcesses(int deliveryCount) {
        when(priceCache.price("23")).thenReturn(PRICE);

        InHouseOrderService.Result received = service.receive(item, "0", deliveryCount);
        InHouseOrderService.Result processed = service.process(item, "0");

        assertThat(received).isNotNull();
        assertThat(received.totalValue()).isEqualTo(processed.totalValue()).isEqualTo("0318");
        assertThat(received.receipt()).usingRecursiveComparison().isEqualTo(processed.receipt());
        assertThat(received.receipt().getStatus()).isSameAs(Status.ACCEPTED);
        verify(jdbcTemplate, times(2)).update(InHouseOrderService.INSERT_ORDER, "23", "P3123", "Philips", 2, PRICE);
        verifyNoInteractions(orchestrationProvider, orchestration);
    }

    /** Within the budget a failure is rethrown so the broker redelivers; {@code defaultErrorHandler} does not run. */
    @Test
    void failureWithinRedeliveryBudgetIsRethrownForRedelivery() {
        RestClientException failure = new RestClientException("405");
        when(priceCache.price("23")).thenThrow(failure);

        assertThatThrownBy(() -> service.receive(item, "0", 4)).isSameAs(failure);

        verifyNoInteractions(orchestrationProvider, orchestration, jdbcTemplate);
        assertPayloadLogged(String.valueOf(item));
    }

    /** The JMS property {@code OrderOrchestrationService} and {@code trigger/InHouseOrderListener} share. */
    @Test
    void totalValuePropertyName() {
        assertThat(InHouseOrderService.TOTAL_VALUE_PROPERTY).isEqualTo("totalValue");
    }

    @Test
    void constructorRejectsMissingCollaborators() {
        assertThatThrownBy(
                () -> new InHouseOrderService(null, jdbcTemplate, orchestrationProvider, MAX_REDELIVERY_ATTEMPTS))
                .isInstanceOf(NullPointerException.class).hasMessage("priceCacheService");
        assertThatThrownBy(
                () -> new InHouseOrderService(priceCache, null, orchestrationProvider, MAX_REDELIVERY_ATTEMPTS))
                .isInstanceOf(NullPointerException.class).hasMessage("jdbcTemplate");
        assertThatThrownBy(() -> new InHouseOrderService(priceCache, jdbcTemplate, null, MAX_REDELIVERY_ATTEMPTS))
                .isInstanceOf(NullPointerException.class).hasMessage("orderOrchestrationService");
    }

    private void assertPayloadLogged(String payload) {
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).isEqualTo(payload);
        });
    }

    /** An in-house item: manufacturer other than Samsung, priced 159 by the price service. */
    private static OrderItem item() {
        OrderItem orderItem = new OrderItem();
        orderItem.setProductId("23");
        orderItem.setName("P3123");
        orderItem.setManufacturer("Philips");
        orderItem.setQuantity(2);
        return orderItem;
    }

}
