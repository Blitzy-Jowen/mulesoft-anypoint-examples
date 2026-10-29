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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.client.WebServiceTransportException;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.soap.client.SoapFaultClientException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.mulesoft.examples.service_orchestration_and_choice_routing.client.SamsungServiceClient;
import com.mulesoft.examples.service_orchestration_and_choice_routing.mapper.OrderRequestMapper;
import com.mulesoft.examples.service_orchestration_and_choice_routing.mapper.PurchaseReceiptMapper;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.OrderItem;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.PurchaseReceipt;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.Status;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.samsung.OrderRequest;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.samsung.OrderResponse;

/**
 * Unit test of {@link SamsungOrderService}, the {@code samsungOrder} flow of
 * {@code service-orchestration-and-choice-routing/src/main/app/fulfillment.xml} (lines 49-93): "OrderItem to
 * OrderRequest", the {@code samsungWebServiceClient} call, the {@code http.status=200} filter with
 * {@code throwOnUnaccepted}, {@code totalValue = #[totalValue + payload.price]}, "OrderResponse to PurchaseReceipt",
 * and the {@code catch-exception-strategy} whose Groovy transformer answers every failure with a {@code REJECTED}
 * receipt of total price {@code 0} and no id.
 *
 * <p>The two mappers are the real ones, so the test also fixes how the scripts they own feed the flow; the Samsung
 * client is a Mockito mock, so no Spring context starts and nothing leaves the JVM. The accepted reply is the one the
 * ported {@code SamsungServiceImpl} gives for the {@code message.xml} item (id {@code 1}, {@code ACCEPTED}, price
 * {@code 2550}), which the original's {@code reply.xml} renders as {@code <purchaseReceipt><id>1</id>
 * <status>ACCEPTED</status><totalPrice>2550.0</totalPrice></purchaseReceipt>}.
 *
 * <p>{@code totalValue} is a string in MEL and so is the Samsung price, so {@code +} concatenates: {@code "0"} and
 * {@code "2550"} give {@code "02550"}, the value the audit row stores. The service updates it before the receipt
 * mapping and keeps the update when that mapping fails; every earlier failure leaves it unchanged.
 *
 * <p>The log oracle is a Logback {@link ListAppender} on the service's logger, set to {@code ALL} for each test and
 * restored afterwards. The catch branch must log the exception's type chain only, never its text and never the
 * exception itself, because a fault string or a mapping error can quote the caller's order data.
 */
@ExtendWith(MockitoExtension.class)
class SamsungOrderServiceTest {

    private static final String FAILURE_LOG_PREFIX = "Samsung order failed, answering with a REJECTED purchase receipt: ";

    @Mock
    private SamsungServiceClient client;

    private SamsungOrderService service;

    private Logger serviceLogger;

    private Level previousLevel;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        service = new SamsungOrderService(new OrderRequestMapper(), client, new PurchaseReceiptMapper());

        serviceLogger = (Logger) LoggerFactory.getLogger(SamsungOrderService.class);
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
    void acceptedResponseYieldsReceiptAndConcatenatesTotalValue() {
        when(client.purchase(any())).thenReturn(ResponseEntity.ok(response("1", "ACCEPTED", "2550")));
        AtomicReference<String> totalValue = new AtomicReference<>("0");

        PurchaseReceipt receipt = service.order(item(), totalValue);

        assertThat(receipt.getId()).isEqualTo("1");
        assertThat(receipt.getStatus()).isSameAs(Status.ACCEPTED);
        assertThat(receipt.getTotalPrice()).isEqualTo(2550.0f);
        assertThat(totalValue.get()).isEqualTo("02550");
        verify(client, times(1)).purchase(any());
        assertThat(appender.list).isEmpty();
    }

    @Test
    void requestCarriesItemNameAndQuantity() {
        when(client.purchase(any())).thenReturn(ResponseEntity.ok(response("1", "ACCEPTED", "2550")));
        ArgumentCaptor<OrderRequest> sent = ArgumentCaptor.forClass(OrderRequest.class);

        service.order(item(), new AtomicReference<>("0"));

        verify(client).purchase(sent.capture());
        assertThat(sent.getValue().getName()).isEqualTo("s-1");
        assertThat(sent.getValue().getQuantity()).isEqualTo(1);
    }

    @Test
    void consecutiveSamsungItemsKeepConcatenating() {
        when(client.purchase(any())).thenReturn(ResponseEntity.ok(response("2", "ACCEPTED", "5100")));
        AtomicReference<String> totalValue = new AtomicReference<>("02550");

        PurchaseReceipt receipt = service.order(item(), totalValue);

        assertThat(receipt.getId()).isEqualTo("2");
        assertThat(receipt.getStatus()).isSameAs(Status.ACCEPTED);
        assertThat(receipt.getTotalPrice()).isEqualTo(5100.0f);
        assertThat(totalValue.get()).isEqualTo("025505100");
    }

    /** Only 200 passes the filter; 202 sits next to it and must still be rejected. */
    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = { 201, 202, 204, 500 })
    void non200StatusIsRejected(int status) {
        when(client.purchase(any())).thenReturn(ResponseEntity.status(status).body(response("1", "ACCEPTED", "2550")));
        AtomicReference<String> totalValue = new AtomicReference<>("0");

        PurchaseReceipt receipt = service.order(item(), totalValue);

        assertRejected(receipt);
        assertThat(totalValue.get()).isEqualTo("0");
        assertFailureLogged("java.lang.IllegalStateException");
    }

    static Stream<Arguments> repliesWithoutOrderResponse() {
        return Stream.of(
                arguments(named("200 without an order response", ResponseEntity.ok().<OrderResponse>build())),
                arguments(named("no reply at all", null)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("repliesWithoutOrderResponse")
    void replyWithoutOrderResponseIsRejected(ResponseEntity<OrderResponse> reply) {
        when(client.purchase(any())).thenReturn(reply);
        AtomicReference<String> totalValue = new AtomicReference<>("0");

        PurchaseReceipt receipt = service.order(item(), totalValue);

        assertRejected(receipt);
        assertThat(totalValue.get()).isEqualTo("0");
        assertFailureLogged("java.lang.IllegalStateException");
    }

    static Stream<Arguments> clientFailures() {
        return Stream.of(
                arguments(named("SOAP fault", (Supplier<RuntimeException>) SamsungOrderServiceTest::soapFault),
                        "org.springframework.ws.soap.client.SoapFaultClientException"),
                arguments(named("connection refused", (Supplier<RuntimeException>) () -> new WebServiceIOException(
                        "down", new ConnectException("refused"))),
                        "org.springframework.ws.client.WebServiceIOException <- java.net.ConnectException"),
                arguments(named("read timeout", (Supplier<RuntimeException>) () -> new WebServiceIOException(
                        "I/O error: Read timed out", new SocketTimeoutException("Read timed out"))),
                        "org.springframework.ws.client.WebServiceIOException <- java.net.SocketTimeoutException"),
                arguments(named("non-fault 405 reply", (Supplier<RuntimeException>) () ->
                        new WebServiceTransportException("Method Not Allowed [405]")),
                        "org.springframework.ws.client.WebServiceTransportException"),
                arguments(named("unexpected reply payload", (Supplier<RuntimeException>) () ->
                        new IllegalStateException("Unexpected Samsung purchase response payload: none")),
                        "java.lang.IllegalStateException"));
    }

    /**
     * Every failure of the call is answered by the catch branch: nothing escapes, nothing is retried, and the log
     * names the exception types without the fault string or the connection detail.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("clientFailures")
    void clientFailureIsRejected(Supplier<RuntimeException> failure, String loggedChain) {
        // Built before the stubbing starts: the SOAP fault stubs its own mocked message.
        RuntimeException thrown = failure.get();
        when(client.purchase(any())).thenThrow(thrown);
        AtomicReference<String> totalValue = new AtomicReference<>("0");

        PurchaseReceipt receipt = assertDoesNotThrow(() -> service.order(item(), totalValue));

        assertRejected(receipt);
        assertThat(totalValue.get()).isEqualTo("0");
        verify(client, times(1)).purchase(any());
        assertFailureLogged(loggedChain);
    }

    @Test
    void nullItemIsRejected() {
        AtomicReference<String> totalValue = new AtomicReference<>("0");

        PurchaseReceipt receipt = assertDoesNotThrow(() -> service.order(null, totalValue));

        assertRejected(receipt);
        assertThat(totalValue.get()).isEqualTo("0");
        verifyNoInteractions(client);
        assertFailureLogged("java.lang.NullPointerException");
    }

    /**
     * The price has already been appended when the receipt mapping fails, and the service documents that the update
     * survives the catch branch, as a session variable set earlier in the flow did.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "non-numeric price,    ACCEPTED, abc,  0abc,  java.lang.NumberFormatException",
        "unknown result,       UNKNOWN,  2550, 02550, java.lang.IllegalArgumentException",
        "result in lower case, accepted, 2550, 02550, java.lang.IllegalArgumentException"
    })
    void unparseableResponseIsRejected(String scenario, String result, String price, String expectedTotalValue,
            String loggedChain) {
        when(client.purchase(any())).thenReturn(ResponseEntity.ok(response("1", result, price)));
        AtomicReference<String> totalValue = new AtomicReference<>("0");

        PurchaseReceipt receipt = assertDoesNotThrow(() -> service.order(item(), totalValue));

        assertRejected(receipt);
        assertThat(totalValue.get()).isEqualTo(expectedTotalValue);
        assertFailureLogged(loggedChain);
    }

    @Test
    void nullTotalValueFailsBeforeAnyCall() {
        assertThatThrownBy(() -> service.order(item(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("totalValue");
        verifyNoInteractions(client);
        assertThat(appender.list).isEmpty();
    }

    @Test
    void constructorRejectsMissingCollaborators() {
        OrderRequestMapper requestMapper = new OrderRequestMapper();
        PurchaseReceiptMapper receiptMapper = new PurchaseReceiptMapper();

        assertThatThrownBy(() -> new SamsungOrderService(null, client, receiptMapper))
                .isInstanceOf(NullPointerException.class).hasMessage("orderRequestMapper");
        assertThatThrownBy(() -> new SamsungOrderService(requestMapper, null, receiptMapper))
                .isInstanceOf(NullPointerException.class).hasMessage("samsungServiceClient");
        assertThatThrownBy(() -> new SamsungOrderService(requestMapper, client, null))
                .isInstanceOf(NullPointerException.class).hasMessage("purchaseReceiptMapper");
    }

    /** Ten causes are named in full; the eleventh is cut short. */
    @ParameterizedTest(name = "{0} causes")
    @CsvSource({ "10, false", "11, true" })
    void loggedCauseChainIsBounded(int causeCount, boolean truncated) {
        Throwable cause = null;
        for (int i = 0; i < causeCount; i++) {
            cause = new IllegalArgumentException("customer data " + i, cause);
        }
        when(client.purchase(any())).thenThrow(new RuntimeException("order 12 for John Doe", cause));

        assertRejected(service.order(item(), new AtomicReference<>("0")));

        StringBuilder expected = new StringBuilder("java.lang.RuntimeException");
        for (int i = 0; i < Math.min(causeCount, 10); i++) {
            expected.append(" <- java.lang.IllegalArgumentException");
        }
        if (truncated) {
            expected.append(" <- ...");
        }
        assertFailureLogged(expected.toString());
    }

    @Test
    void circularCauseChainIsLoggedOnce() {
        RuntimeException outer = new RuntimeException("order 12 for John Doe");
        IllegalStateException inner = new IllegalStateException("inner");
        outer.initCause(inner);
        inner.initCause(outer);
        when(client.purchase(any())).thenThrow(outer);

        assertRejected(service.order(item(), new AtomicReference<>("0")));

        assertFailureLogged("java.lang.RuntimeException <- java.lang.IllegalStateException <- [circular]");
    }

    /** The {@code message.xml} item routed to Samsung. */
    private static OrderItem item() {
        OrderItem item = new OrderItem();
        item.setManufacturer("Samsung");
        item.setName("s-1");
        item.setProductId("AX02");
        item.setQuantity(1);
        return item;
    }

    private static OrderResponse response(String id, String result, String price) {
        OrderResponse response = new OrderResponse();
        response.setId(id);
        response.setResult(result);
        response.setPrice(price);
        return response;
    }

    /** A SOAP fault whose reason the catch branch must not log; the mocked message has no SOAP body. */
    private static SoapFaultClientException soapFault() {
        SoapMessage message = mock(SoapMessage.class);
        when(message.getFaultReason()).thenReturn("boom");
        SoapFaultClientException fault = new SoapFaultClientException(message);
        assertThat(fault.getMessage()).isEqualTo("boom");
        return fault;
    }

    /** The receipt of the {@code catch-exception-strategy}'s Groovy transformer. */
    private static void assertRejected(PurchaseReceipt receipt) {
        assertThat(receipt.getStatus()).isSameAs(Status.REJECTED);
        assertThat(receipt.getTotalPrice()).isEqualTo(0.0f);
        assertThat(receipt.getId()).isNull();
    }

    /** Exactly one ERROR event naming the type chain, with no message text and no attached exception. */
    private void assertFailureLogged(String typeChain) {
        List<ILoggingEvent> events = appender.list;
        assertThat(events).hasSize(1);
        ILoggingEvent event = events.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage()).isEqualTo(FAILURE_LOG_PREFIX + typeChain);
        assertThat(event.getThrowableProxy()).isNull();
    }
}
