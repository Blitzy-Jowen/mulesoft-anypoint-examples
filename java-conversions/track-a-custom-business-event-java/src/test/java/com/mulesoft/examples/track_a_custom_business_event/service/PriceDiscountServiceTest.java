package com.mulesoft.examples.track_a_custom_business_event.service;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
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

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
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
 *
 * <p>Each body is decoded with the charset its row declares, UTF-8 unless the row shows how the declared charset
 * governs: Latin-1 bytes read as ISO-8859-1, malformed bytes replaced, a byte-order mark kept as a character.
 * Nesting depth, number length and string length beyond Jackson's default read constraints are accepted, as the
 * original transformer accepted them; a used value nested 100000 deep reaches the metadata and the error message as
 * its {@code toString()} renders it, without exhausting the stack, its expected text built from the request text.
 *
 * <p>The platform-default 500 returns the exception's message, so an unreadable body fails with a fixed text:
 * bodies carrying a marker show that no request bytes and no Java or Jackson type name reach it, and that the
 * parser location chained as its cause quotes none of the body.
 */
@ExtendWith(MockitoExtension.class)
class PriceDiscountServiceTest {

    private static final String NIL_DISCOUNT = "nil can't be coerced into Fixnum";

    private static final String SHOES = "\"shoes\"";

    private static final String PRICE_10 = "\"item price per unit\": 10";

    private static final String NOT_AN_OBJECT = "JSON payload is not an object";

    private static final String NOT_VALID_JSON = "Payload is not valid JSON";

    /** A shoes request whose email holds {@code é}, which Latin-1 encodes as the single byte 0xE9. */
    private static final String CAFE_SHOES = "{\"email\":\"caf\u00e9@abc.sk\",\"item name\":\"shoes\","
            + "\"item units\":2,\"item price per unit\":10}";

    /** {@link #CAFE_SHOES} with the item name {@code shoés}, outside the discount table once decoded. */
    private static final String CAFE_SHOES_ACCENTED = CAFE_SHOES.replace("\"shoes\"", "\"sho\u00e9s\"");

    /** Text placed in request bodies to show whether any of their bytes reach a message. */
    private static final String MARKER = "BLITZY-MARKER-91c4";

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

    @ParameterizedTest(name = "{0} -> \"{3}\"")
    @MethodSource("discountedRequests")
    void discountedPriceIsReturnedAndTheTransactionCompleted(String description, byte[] body, Charset encoding,
            String expected, List<String> expectedMetadata) {
        assertEquals(expected, service.processPriceRequest(body, encoding));

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
                Arguments.of("message.json (shoes at 10)", message(), UTF_8, "8.5", List.of("shoes", "2", "10")),
                Arguments.of("jeans at 10", messageWith(SHOES, "\"jeans\""), UTF_8, "8.0",
                        List.of("jeans", "2", "10")),
                Arguments.of("jackets at 10", messageWith(SHOES, "\"jackets\""), UTF_8, "7.0",
                        List.of("jackets", "2", "10")),
                Arguments.of("jeans at decimal 12.5",
                        messageWith(SHOES, "\"jeans\"", PRICE_10, "\"item price per unit\": 12.5"), UTF_8, "10.0",
                        List.of("jeans", "2", "12.5")),
                Arguments.of("shoes at long 3000000000",
                        messageWith(PRICE_10, "\"item price per unit\": 3000000000"), UTF_8, "2.55E9",
                        List.of("shoes", "2", "3000000000")),
                Arguments.of("shoes at big integer 100000000000000000000",
                        messageWith(PRICE_10, "\"item price per unit\": 100000000000000000000"), UTF_8, "8.5E19",
                        List.of("shoes", "2", "100000000000000000000")),
                Arguments.of("shoes at string \"10\", repeated zero times",
                        messageWith(PRICE_10, "\"item price per unit\": \"10\""), UTF_8, "",
                        List.of("shoes", "2", "10")),
                // The declared charset decodes the body: Latin-1 bytes are not valid UTF-8.
                Arguments.of("Latin-1 body declared as ISO-8859-1", CAFE_SHOES.getBytes(ISO_8859_1), ISO_8859_1,
                        "8.5", List.of("shoes", "2", "10")),
                // Decoding replaces the malformed byte 0xE9 with U+FFFD instead of rejecting the body.
                Arguments.of("Latin-1 body read as UTF-8", CAFE_SHOES.getBytes(ISO_8859_1), UTF_8, "8.5",
                        List.of("shoes", "2", "10")),
                // No read limit: nesting, number length and string length beyond Jackson's default constraints.
                Arguments.of("arrays nested 1500 deep under an extra key", shoesWithExtra(nestedArrays(1_500)),
                        UTF_8, "8.5", List.of("shoes", "null", "10")),
                Arguments.of("arrays nested 100000 deep under an extra key", shoesWithExtra(nestedArrays(100_000)),
                        UTF_8, "8.5", List.of("shoes", "null", "10")),
                // A used value nested that deep is rendered as toString() renders it, without exhausting the stack.
                Arguments.of("units in arrays nested 100000 deep",
                        messageWith("\"item units\": 2", "\"item units\": " + nestedArrays(100_000, "0")), UTF_8,
                        "8.5", List.of("shoes", nestedArrays(100_000, "0"), "10")),
                Arguments.of("1001-digit integer price",
                        json("{\"item name\": \"shoes\", \"item price per unit\": " + "9".repeat(1_001) + "}"),
                        UTF_8, "Infinity", List.of("shoes", "null", "9".repeat(1_001))),
                Arguments.of("string of 20000001 characters under an extra key",
                        shoesWithExtra("\"" + "a".repeat(20_000_001) + "\""), UTF_8, "8.5",
                        List.of("shoes", "null", "10")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scriptFailures")
    void scriptFailureRaisesTheJRubyErrorAfterTheEventIsTracked(String description, byte[] body, Charset encoding,
            String expectedMessage, List<String> expectedMetadata) {
        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> service.processPriceRequest(body, encoding));

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
                Arguments.of("unknown item hats", messageWith(SHOES, "\"hats\""), UTF_8, NIL_DISCOUNT,
                        List.of("hats", "2", "10")),
                Arguments.of("item name differing only in case", messageWith(SHOES, "\"Shoes\""), UTF_8,
                        NIL_DISCOUNT, List.of("Shoes", "2", "10")),
                Arguments.of("missing item name", json("{\"item units\": 2, \"item price per unit\": 10}"), UTF_8,
                        NIL_DISCOUNT, List.of("null", "2", "10")),
                Arguments.of("numeric item name",
                        json("{\"item name\": 5, \"item units\": 2, \"item price per unit\": 10}"), UTF_8,
                        NIL_DISCOUNT, List.of("5", "2", "10")),
                Arguments.of("unknown item with a string price",
                        messageWith(SHOES, "\"hats\"", PRICE_10, "\"item price per unit\": \"10\""), UTF_8,
                        NIL_DISCOUNT, List.of("hats", "2", "10")),
                Arguments.of("unknown item without units or price", json("{\"item name\": \"hats\"}"), UTF_8,
                        NIL_DISCOUNT, List.of("hats", "null", "null")),
                // The metadata shows the item name as the declared charset decoded it.
                Arguments.of("accented item name in Latin-1 declared as ISO-8859-1",
                        CAFE_SHOES_ACCENTED.getBytes(ISO_8859_1), ISO_8859_1, NIL_DISCOUNT,
                        List.of("sho\u00e9s", "2", "10")),
                Arguments.of("accented item name in UTF-8 declared as ISO-8859-1",
                        CAFE_SHOES_ACCENTED.getBytes(UTF_8), ISO_8859_1, NIL_DISCOUNT,
                        List.of("sho\u00c3\u00a9s", "2", "10")),
                // A known item whose price has no * method.
                Arguments.of("null price", messageWith(PRICE_10, "\"item price per unit\": null"), UTF_8,
                        "undefined method `*' for nil:NilClass", List.of("shoes", "2", "null")),
                Arguments.of("missing price", json("{\"item name\": \"shoes\", \"item units\": 2}"), UTF_8,
                        "undefined method `*' for nil:NilClass", List.of("shoes", "2", "null")),
                Arguments.of("object price", messageWith(PRICE_10, "\"item price per unit\": {\"amount\": 10}"),
                        UTF_8, "undefined method `*' for {amount=10}", List.of("shoes", "2", "{amount=10}")),
                Arguments.of("array price", messageWith(PRICE_10, "\"item price per unit\": [10]"), UTF_8,
                        "undefined method `*' for [10]", List.of("shoes", "2", "[10]")),
                Arguments.of("true price", messageWith(PRICE_10, "\"item price per unit\": true"), UTF_8,
                        "undefined method `*' for true", List.of("shoes", "2", "true")),
                Arguments.of("false price", messageWith(PRICE_10, "\"item price per unit\": false"), UTF_8,
                        "undefined method `*' for false", List.of("shoes", "2", "false")),
                // Separators, leaf types, empty containers and each object's key order, as toString() renders them.
                Arguments.of("composite price",
                        messageWith(PRICE_10, "\"item price per unit\": [1, [2.5, {\"k\": [null, \"s\"]}], true, {}, "
                                + "[], {\"b\": 1, \"a\": 2}, 3000000000, 100000000000000000000]"), UTF_8,
                        "undefined method `*' for [1, [2.5, {k=[null, s]}], true, {}, [], {b=1, a=2}, 3000000000, "
                                + "100000000000000000000]",
                        List.of("shoes", "2", "[1, [2.5, {k=[null, s]}], true, {}, [], {b=1, a=2}, 3000000000, "
                                + "100000000000000000000]")),
                // Used values nested deeper than recursive rendering could reach, rendered without recursion.
                Arguments.of("array price nested 100000 deep",
                        messageWith(PRICE_10, "\"item price per unit\": " + nestedArrays(100_000, "0")), UTF_8,
                        "undefined method `*' for " + nestedArrays(100_000, "0"),
                        List.of("shoes", "2", nestedArrays(100_000, "0"))),
                Arguments.of("object price nested 100000 deep",
                        messageWith(PRICE_10, "\"item price per unit\": "
                                + "{\"a\":".repeat(100_000) + "0" + "}".repeat(100_000)), UTF_8,
                        "undefined method `*' for " + "{a=".repeat(100_000) + "0" + "}".repeat(100_000),
                        List.of("shoes", "2", "{a=".repeat(100_000) + "0" + "}".repeat(100_000))),
                Arguments.of("item name nested 100000 deep", messageWith(SHOES, nestedArrays(100_000, "0")), UTF_8,
                        NIL_DISCOUNT, List.of(nestedArrays(100_000, "0"), "2", "10")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unreadableBodies")
    void unreadableBodyRaisesIllegalArgumentBeforeTheEvent(String description, byte[] body,
            Class<? extends Throwable> expectedCause, String expectedMessage) {
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> service.processPriceRequest(body, UTF_8));

        assertEquals(expectedMessage, failure.getMessage());
        assertMessageDisclosesNothing(failure.getMessage());
        if (expectedCause == null) {
            assertNull(failure.getCause());
        } else {
            assertNotNull(failure.getCause());
            assertEquals(expectedCause, failure.getCause().getClass());
            assertLocationQuotesNoRequestBytes(failure.getCause());
        }
        InOrder order = inOrder(tracker);
        order.verify(tracker).startTransaction();
        order.verify(tracker).endTransaction();
        order.verifyNoMoreInteractions();
        verify(tracker, never()).trackCustomEvent(any(), any());
        verify(tracker, never()).setTransactionId(any());
        verifyNoMoreInteractions(tracker);
    }

    static Stream<Arguments> unreadableBodies() throws IOException {
        return Stream.of(
                Arguments.of("malformed JSON", json("{\"item name\": "), JsonEOFException.class, NOT_VALID_JSON),
                Arguments.of("malformed JSON with a marker",
                        json("{\"item name\": \"" + MARKER + "\", \"item units\": "), JsonEOFException.class,
                        NOT_VALID_JSON),
                Arguments.of("unquoted field name", json("{bad"), JsonParseException.class, NOT_VALID_JSON),
                Arguments.of("empty body", new byte[0], MismatchedInputException.class, NOT_AN_OBJECT),
                Arguments.of("JSON array", json("[1,2]"), MismatchedInputException.class, NOT_AN_OBJECT),
                Arguments.of("JSON array with a marker", json("[\"" + MARKER + "\"]"),
                        MismatchedInputException.class, NOT_AN_OBJECT),
                Arguments.of("JSON string with a marker", json("\"" + MARKER + "\""),
                        MismatchedInputException.class, NOT_AN_OBJECT),
                Arguments.of("JSON number", json("42"), MismatchedInputException.class, NOT_AN_OBJECT),
                Arguments.of("JSON null", json("null"), null, NOT_AN_OBJECT),
                Arguments.of("no body", null, MismatchedInputException.class, NOT_AN_OBJECT),
                // Decoded through a reader, a UTF-8 byte-order mark is the character U+FEFF ahead of the object.
                Arguments.of("UTF-8 byte-order mark before message.json",
                        concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, message()),
                        JsonParseException.class, NOT_VALID_JSON));
    }

    @Test
    void nullEncodingIsRejectedBeforeAnythingIsTracked() throws IOException {
        byte[] body = message();

        NullPointerException failure =
                assertThrows(NullPointerException.class, () -> service.processPriceRequest(body, null));

        assertEquals("encoding", failure.getMessage());
        verifyNoInteractions(tracker);
    }

    @Test
    void eachRequestStartsWithoutADiscountWhateverWasRequestedBefore() throws IOException {
        byte[] hats = messageWith(SHOES, "\"hats\"");

        assertEquals("8.5", service.processPriceRequest(message(), UTF_8));
        IllegalStateException afterShoes =
                assertThrows(IllegalStateException.class, () -> service.processPriceRequest(hats, UTF_8));
        assertEquals(NIL_DISCOUNT, afterShoes.getMessage(), "the shoes discount is not carried to the next request");
        assertEquals("8.5", service.processPriceRequest(message(), UTF_8),
                "the failed request leaves no state behind");

        verify(tracker, times(3)).startTransaction();
        verify(tracker, times(3)).trackCustomEvent(eq("Price"), any());
        verify(tracker, times(2)).setTransactionId("custom-business-event-example-transaction");
        verify(tracker, times(3)).endTransaction();
        verifyNoMoreInteractions(tracker);
    }

    /** Asserts that a message the 500 body returns quotes no request bytes and names no Java or Jackson type. */
    private static void assertMessageDisclosesNothing(String message) {
        for (String disclosure : List.of(MARKER, "java.", "HashMap", "Source")) {
            assertFalse(message.contains(disclosure), () -> "message reveals " + disclosure + ": " + message);
        }
    }

    /**
     * Asserts that the location Jackson appends to the chained cause's message quotes none of the body and does not
     * describe the source either, which holds only with source inclusion disabled; with it enabled the location
     * would name the reader the body is decoded through. The location alone is checked: for a string root Jackson's
     * own text quotes the string value, whatever the source setting.
     */
    private static void assertLocationQuotesNoRequestBytes(Throwable cause) {
        JsonProcessingException parserFailure = assertInstanceOf(JsonProcessingException.class, cause);
        String location = parserFailure.getMessage().substring(parserFailure.getOriginalMessage().length());
        assertTrue(location.contains("[Source: "), () -> "cause carries a location: " + location);
        assertFalse(location.contains(MARKER), () -> "location quotes the body: " + location);
        assertFalse(location.contains("Reader"), () -> "location describes the source: " + location);
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

    /** UTF-8 bytes of a shoes request at price 10, without units, carrying {@code value} under the extra key x. */
    private static byte[] shoesWithExtra(String value) {
        return json("{\"item name\": \"shoes\", \"item price per unit\": 10, \"x\": " + value + "}");
    }

    /** {@code depth} empty arrays nested in one another. */
    private static String nestedArrays(int depth) {
        return "[".repeat(depth) + "]".repeat(depth);
    }

    /**
     * {@code depth} arrays nested in one another around the JSON value {@code innermost}; the text is also how
     * {@code toString()} renders the parsed value when {@code innermost} renders as itself, as {@code 0} does.
     */
    private static String nestedArrays(int depth, String innermost) {
        return "[".repeat(depth) + innermost + "]".repeat(depth);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(first.length + second.length);
        bytes.writeBytes(first);
        bytes.writeBytes(second);
        return bytes.toByteArray();
    }
}
