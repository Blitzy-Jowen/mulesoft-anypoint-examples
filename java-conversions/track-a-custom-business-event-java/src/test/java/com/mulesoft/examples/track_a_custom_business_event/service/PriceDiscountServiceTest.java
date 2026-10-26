package com.mulesoft.examples.track_a_custom_business_event.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.core.io.JsonEOFException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

/**
 * Unit test of {@link PriceDiscountService}, the Java form of flow {@code custom-business-eventsFlow1} in
 * {@code track-a-custom-business-event/src/main/app/custom-business-events.xml} (lines 6-24): the JSON body read
 * into a map, the custom event {@code Price} with its {@code item name}, {@code item units} and
 * {@code item price per unit} metadata, the JRuby discount table (shoes 0.15, jeans 0.2, jackets 0.3, no
 * {@code else}) applied as {@code price * (1 - discount)}, and the tracking transaction id
 * {@code custom-business-event-example-transaction}.
 *
 * <p>Mockito with a mocked {@link BusinessEventTracker} and no stubbing: each test asserts the returned text or the
 * raised exception, the event metadata, and the order and count of tracker calls. Request bodies are the ported
 * {@code message.json} (shoes, 2 units, price 10) read unchanged, variants of it made by replacing one value, or
 * small JSON literals. Expected texts are literals, so the tests pin the rendering rather than whatever the
 * service computes.
 */
@ExtendWith(MockitoExtension.class)
class PriceDiscountServiceTest {

    private static final String NIL_DISCOUNT = "nil can't be coerced into Fixnum";

    private static final String SHOES = "\"shoes\"";

    private static final String PRICE_10 = "\"item price per unit\": 10";

    @Mock
    private BusinessEventTracker tracker;

    @Captor
    private ArgumentCaptor<Map<String, String>> metadataCaptor;

    private PriceDiscountService service;

    @BeforeEach
    void createService() {
        service = new PriceDiscountService(tracker);
    }

    @Test
    void constantsAreTheFlowValues() {
        assertEquals(0.15, PriceDiscountService.SHOES_DISCOUNT);
        assertEquals(0.2, PriceDiscountService.JEANS_DISCOUNT);
        assertEquals(0.3, PriceDiscountService.JACKETS_DISCOUNT);
        assertEquals("Price", PriceDiscountService.EVENT_NAME);
        assertEquals("custom-business-event-example-transaction", PriceDiscountService.TRANSACTION_ID);
        assertEquals("item name", PriceDiscountService.ITEM_NAME);
        assertEquals("item units", PriceDiscountService.ITEM_UNITS);
        assertEquals("item price per unit", PriceDiscountService.ITEM_PRICE_PER_UNIT);
    }

    @ParameterizedTest(name = "{0} -> \"{2}\"")
    @MethodSource("discountedRequests")
    void discountedPriceIsReturnedAndTheTransactionCompleted(String description, byte[] body, String expected,
            List<String> expectedMetadata) {
        assertEquals(expected, service.processPriceRequest(body));

        InOrder order = inOrder(tracker);
        order.verify(tracker).startTransaction();
        order.verify(tracker).trackCustomEvent(eq("Price"), metadataCaptor.capture());
        order.verify(tracker).setTransactionId("custom-business-event-example-transaction");
        order.verify(tracker).endTransaction();
        order.verifyNoMoreInteractions();
        verifyNoMoreInteractions(tracker);
        assertPriceMetadata(expectedMetadata, metadataCaptor.getValue());
    }

    static Stream<Arguments> discountedRequests() throws IOException {
        return Stream.of(
                Arguments.of("message.json (shoes at 10)", message(), "8.5", List.of("shoes", "2", "10")),
                Arguments.of("jeans at 10", messageWith(SHOES, "\"jeans\""), "8.0", List.of("jeans", "2", "10")),
                Arguments.of("jackets at 10", messageWith(SHOES, "\"jackets\""), "7.0",
                        List.of("jackets", "2", "10")),
                Arguments.of("jeans at decimal 12.5",
                        messageWith(SHOES, "\"jeans\"", PRICE_10, "\"item price per unit\": 12.5"), "10.0",
                        List.of("jeans", "2", "12.5")),
                Arguments.of("shoes at long 3000000000",
                        messageWith(PRICE_10, "\"item price per unit\": 3000000000"), "2.55E9",
                        List.of("shoes", "2", "3000000000")),
                Arguments.of("shoes at big integer 100000000000000000000",
                        messageWith(PRICE_10, "\"item price per unit\": 100000000000000000000"), "8.5E19",
                        List.of("shoes", "2", "100000000000000000000")),
                Arguments.of("shoes at string \"10\", repeated zero times",
                        messageWith(PRICE_10, "\"item price per unit\": \"10\""), "",
                        List.of("shoes", "2", "10")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scriptFailures")
    void scriptFailureRaisesTheJRubyErrorAfterTheEventIsTracked(String description, byte[] body,
            String expectedMessage, List<String> expectedMetadata) {
        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> service.processPriceRequest(body));

        assertEquals(expectedMessage, failure.getMessage());
        assertNull(failure.getCause());
        InOrder order = inOrder(tracker);
        order.verify(tracker).startTransaction();
        order.verify(tracker).trackCustomEvent(eq("Price"), metadataCaptor.capture());
        order.verify(tracker).endTransaction();
        order.verifyNoMoreInteractions();
        verify(tracker, never()).setTransactionId(any());
        verifyNoMoreInteractions(tracker);
        assertPriceMetadata(expectedMetadata, metadataCaptor.getValue());
    }

    static Stream<Arguments> scriptFailures() throws IOException {
        return Stream.of(
                // No when-branch matches, so $discount stays nil and (1 - nil) fails before any price is read.
                Arguments.of("unknown item hats", messageWith(SHOES, "\"hats\""), NIL_DISCOUNT,
                        List.of("hats", "2", "10")),
                Arguments.of("item name differing only in case", messageWith(SHOES, "\"Shoes\""), NIL_DISCOUNT,
                        List.of("Shoes", "2", "10")),
                Arguments.of("missing item name", json("{\"item units\": 2, \"item price per unit\": 10}"),
                        NIL_DISCOUNT, List.of("null", "2", "10")),
                Arguments.of("numeric item name",
                        json("{\"item name\": 5, \"item units\": 2, \"item price per unit\": 10}"), NIL_DISCOUNT,
                        List.of("5", "2", "10")),
                Arguments.of("unknown item with a string price",
                        messageWith(SHOES, "\"hats\"", PRICE_10, "\"item price per unit\": \"10\""), NIL_DISCOUNT,
                        List.of("hats", "2", "10")),
                Arguments.of("unknown item without units or price", json("{\"item name\": \"hats\"}"),
                        NIL_DISCOUNT, List.of("hats", "null", "null")),
                // A known item whose price has no * method.
                Arguments.of("null price", messageWith(PRICE_10, "\"item price per unit\": null"),
                        "undefined method `*' for nil:NilClass", List.of("shoes", "2", "null")),
                Arguments.of("missing price", json("{\"item name\": \"shoes\", \"item units\": 2}"),
                        "undefined method `*' for nil:NilClass", List.of("shoes", "2", "null")),
                Arguments.of("object price", messageWith(PRICE_10, "\"item price per unit\": {\"amount\": 10}"),
                        "undefined method `*' for {amount=10}", List.of("shoes", "2", "{amount=10}")),
                Arguments.of("array price", messageWith(PRICE_10, "\"item price per unit\": [10]"),
                        "undefined method `*' for [10]", List.of("shoes", "2", "[10]")),
                Arguments.of("true price", messageWith(PRICE_10, "\"item price per unit\": true"),
                        "undefined method `*' for true", List.of("shoes", "2", "true")),
                Arguments.of("false price", messageWith(PRICE_10, "\"item price per unit\": false"),
                        "undefined method `*' for false", List.of("shoes", "2", "false")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unreadableBodies")
    void unreadableBodyRaisesIllegalArgumentBeforeTheEvent(String description, byte[] body,
            Class<? extends Throwable> expectedCause, String expectedMessage) {
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> service.processPriceRequest(body));

        assertEquals(expectedMessage, failure.getMessage());
        if (expectedCause == null) {
            assertNull(failure.getCause());
        } else {
            assertNotNull(failure.getCause());
            assertEquals(expectedCause, failure.getCause().getClass());
            assertEquals(failure.getCause().getMessage(), failure.getMessage(), "the parser's message is kept");
        }
        InOrder order = inOrder(tracker);
        order.verify(tracker).startTransaction();
        order.verify(tracker).endTransaction();
        order.verifyNoMoreInteractions();
        verify(tracker, never()).trackCustomEvent(any(), any());
        verify(tracker, never()).setTransactionId(any());
        verifyNoMoreInteractions(tracker);
    }

    static Stream<Arguments> unreadableBodies() {
        return Stream.of(
                Arguments.of("malformed JSON", json("{\"item name\": "), JsonEOFException.class,
                        "Unexpected end-of-input within/between Object entries\n"
                                + " at [Source: (byte[])\"{\"item name\": \"; line: 1, column: 15]"),
                Arguments.of("empty body", new byte[0], MismatchedInputException.class,
                        "No content to map due to end-of-input\n at [Source: (byte[])\"\"; line: 1, column: 0]"),
                Arguments.of("JSON array", json("[1,2]"), MismatchedInputException.class,
                        "Cannot deserialize value of type `java.util.HashMap<java.lang.Object,java.lang.Object>`"
                                + " from Array value (token `JsonToken.START_ARRAY`)\n"
                                + " at [Source: (byte[])\"[1,2]\"; line: 1, column: 1]"),
                Arguments.of("JSON number", json("42"), MismatchedInputException.class,
                        "Cannot deserialize value of type `java.util.HashMap<java.lang.Object,java.lang.Object>`"
                                + " from Integer value (token `JsonToken.VALUE_NUMBER_INT`)\n"
                                + " at [Source: (byte[])\"42\"; line: 1, column: 1]"),
                Arguments.of("JSON null", json("null"), null, "JSON payload is not an object"),
                Arguments.of("no body", null, null, "argument \"src\" is null"));
    }

    @Test
    void eachRequestStartsWithoutADiscountWhateverWasRequestedBefore() throws IOException {
        byte[] hats = messageWith(SHOES, "\"hats\"");

        assertEquals("8.5", service.processPriceRequest(message()));
        IllegalStateException afterShoes =
                assertThrows(IllegalStateException.class, () -> service.processPriceRequest(hats));
        assertEquals(NIL_DISCOUNT, afterShoes.getMessage(), "the shoes discount is not carried to the next request");
        assertEquals("8.5", service.processPriceRequest(message()), "the failed request leaves no state behind");

        verify(tracker, times(3)).startTransaction();
        verify(tracker, times(3)).trackCustomEvent(eq("Price"), any());
        verify(tracker, times(2)).setTransactionId("custom-business-event-example-transaction");
        verify(tracker, times(3)).endTransaction();
        verifyNoMoreInteractions(tracker);
    }

    /** Asserts the {@code Price} metadata keys in the flow's declaration order and their values. */
    private static void assertPriceMetadata(List<String> expectedValues, Map<String, String> metadata) {
        assertEquals(List.of("item name", "item units", "item price per unit"), new ArrayList<>(metadata.keySet()));
        assertEquals(expectedValues, new ArrayList<>(metadata.values()));
    }

    /** The bytes of the ported {@code message.json}, unchanged. */
    private static byte[] message() throws IOException {
        try (InputStream in = PriceDiscountServiceTest.class.getResourceAsStream("/message.json")) {
            assertNotNull(in, "message.json is on the test classpath");
            return in.readAllBytes();
        }
    }

    /**
     * {@code message.json} with each {@code target} text replaced by the replacement that follows it, failing when
     * a target is absent so no variant silently equals the original.
     */
    private static byte[] messageWith(String... targetsAndReplacements) throws IOException {
        String text = new String(message(), StandardCharsets.UTF_8);
        for (int i = 0; i < targetsAndReplacements.length; i += 2) {
            String target = targetsAndReplacements[i];
            assertTrue(text.contains(target), () -> "message.json contains " + target);
            text = text.replace(target, targetsAndReplacements[i + 1]);
        }
        return json(text);
    }

    private static byte[] json(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
