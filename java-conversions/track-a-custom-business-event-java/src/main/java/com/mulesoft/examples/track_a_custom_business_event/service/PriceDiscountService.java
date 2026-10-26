package com.mulesoft.examples.track_a_custom_business_event.service;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Service;

/**
 * Flow {@code custom-business-eventsFlow1} after its HTTP listener
 * (track-a-custom-business-event/src/main/app/custom-business-events.xml:6-24), in processor order:
 * <ol>
 *   <li>{@code json-to-object-transformer returnClass="java.util.HashMap"};</li>
 *   <li>{@code tracking:custom-event event-name="Price"} with the metadata {@code item name}, {@code item units}
 *       and {@code item price per unit};</li>
 *   <li>the JRuby transformer
 *       {@code case $payload['item name'] when "shoes" $discount = 0.15 when "jeans" $discount = 0.2
 *       when "jackets" $discount = 0.3 end;
 *       $payload['item price per unit'] = $payload['item price per unit'] * (1 - $discount)};</li>
 *   <li>{@code object-to-string-transformer};</li>
 *   <li>{@code tracking:transaction id="custom-business-event-example-transaction"}.</li>
 * </ol>
 *
 * <p>The script is reimplemented without a script engine, keeping JRuby's evaluation order and result types:
 * the item name selects the discount by exact string equality, as {@code case/when} compares with
 * {@code String#===}; {@code (1 - $discount)} is evaluated before {@code *} is dispatched, so an item outside the
 * table fails on the nil discount whatever the price holds; a numeric price (JRuby Fixnum, Bignum or Float) times
 * the Float factor is a Float, returned as a {@link Double} and rendered by {@link Double#toString(double)}
 * ({@code 8.5} for shoes at 10); a String price is repeated zero times, because {@code String#*} truncates the
 * factor to 0; any other price has no {@code *} method.
 *
 * <p>Failures propagate unchanged, as the original flow declares no exception strategy: unparseable or non-object
 * JSON raises {@link IllegalArgumentException}, a script failure raises {@link IllegalStateException} carrying
 * the JRuby error text. The business event is recorded before the discount is computed, so it is logged even when
 * the script fails; the transaction id is set only after the whole flow succeeds. The platform-default 500 returns
 * the exception's message, so a body that cannot be read fails with a fixed text, {@code Payload is not valid JSON}
 * or {@code JSON payload is not an object}, instead of Jackson's diagnostic, which can quote request bytes and name
 * internal types.
 *
 * <p>The service is stateless and thread-safe: the payload, the discount and the result are local to each call,
 * and the {@link ObjectMapper} is only read after construction.
 */
@Service
public class PriceDiscountService {

    /** Discount rate of item {@code shoes}. */
    public static final double SHOES_DISCOUNT = 0.15;

    /** Discount rate of item {@code jeans}. */
    public static final double JEANS_DISCOUNT = 0.2;

    /** Discount rate of item {@code jackets}. */
    public static final double JACKETS_DISCOUNT = 0.3;

    /** Name of the custom business event recorded for every parsed request. */
    public static final String EVENT_NAME = "Price";

    /** Business transaction id set once the flow has completed. */
    public static final String TRANSACTION_ID = "custom-business-event-example-transaction";

    /** Payload key of the item name, which selects the discount. */
    public static final String ITEM_NAME = "item name";

    /** Payload key of the number of units, recorded as event metadata only. */
    public static final String ITEM_UNITS = "item units";

    /** Payload key of the unit price the discount applies to. */
    public static final String ITEM_PRICE_PER_UNIT = "item price per unit";

    private static final String NIL_DISCOUNT_MESSAGE = "nil can't be coerced into Fixnum";

    private static final String NO_MULTIPLY_METHOD_PREFIX = "undefined method `*' for ";

    private static final String NIL_DESCRIPTION = "nil:NilClass";

    private static final String NOT_AN_OBJECT_MESSAGE = "JSON payload is not an object";

    private static final String MALFORMED_JSON_MESSAGE = "Payload is not valid JSON";

    private final BusinessEventTracker tracker;

    // A private mapper with Jackson's defaults, so Boot's Jackson properties never change how the body is read:
    // integers become Integer, Long or BigInteger by size and decimals become Double, as Mule's JSON transformer
    // produced them. Only source inclusion is disabled, so the location in a parser diagnostic, and in the cause
    // chained to each parse failure, never quotes the request bytes.
    private final ObjectMapper objectMapper =
            JsonMapper.builder().disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).build();

    /**
     * @param tracker records the business event and the tracking transaction of each request
     */
    public PriceDiscountService(BusinessEventTracker tracker) {
        this.tracker = tracker;
    }

    /**
     * Processes one request body as the flow did and returns the discounted unit price as text.
     *
     * <p>The tracking transaction is opened before the body is read and closed on every outcome.
     *
     * @param body the raw request body, expected to be a JSON object; {@code null} when the request carried none
     * @return the discounted unit price as {@link String#valueOf(Object)} renders it, e.g. {@code 8.5}, or the
     *         empty string when the price is a JSON string
     * @throws IllegalArgumentException when the body is malformed, truncated or not decodable
     *                                  ({@code Payload is not valid JSON}), or {@code null}, empty or a JSON
     *                                  value other than an object ({@code JSON payload is not an object})
     * @throws IllegalStateException    when the item name is not {@code shoes}, {@code jeans} or {@code jackets}
     *                                  ({@code nil can't be coerced into Fixnum}), or when the price is absent,
     *                                  {@code null} or neither a number nor a string
     *                                  ({@code undefined method `*' for ...})
     */
    public String processPriceRequest(byte[] body) {
        tracker.startTransaction();
        try {
            HashMap<String, Object> payload = parse(body);
            tracker.trackCustomEvent(EVENT_NAME, metaData(payload));

            double factor = 1 - discountFor(payload.get(ITEM_NAME));
            Object discountedPrice = applyDiscount(payload.get(ITEM_PRICE_PER_UNIT), factor);
            // The script assigns the result back into the payload map, and that assigned value becomes the payload.
            payload.put(ITEM_PRICE_PER_UNIT, discountedPrice);
            String text = String.valueOf(discountedPrice);

            tracker.setTransactionId(TRANSACTION_ID);
            return text;
        } finally {
            tracker.endTransaction();
        }
    }

    /**
     * Reads the body into a {@link HashMap}, the type the original transformer returned, whose iteration order
     * and {@code toString()} the rest of the flow saw.
     *
     * <p>A {@code null} body is read as an empty one, so it fails like a request without content. Every failure
     * carries a fixed message; Jackson's exception, when there is one, is its cause.
     */
    @SuppressWarnings("unchecked")
    private HashMap<String, Object> parse(byte[] body) {
        byte[] bytes = body == null ? new byte[0] : body;
        HashMap<String, Object> payload;
        try {
            payload = objectMapper.readValue(bytes, HashMap.class);
        } catch (MismatchedInputException e) {
            // Fixed messages, never Jackson's: these messages reach the 500 body, and Jackson's can quote request
            // bytes and name internal types such as java.util.HashMap. This one covers an empty body and an
            // array, string, number or boolean root.
            throw new IllegalArgumentException(NOT_AN_OBJECT_MESSAGE, e);
        } catch (IOException e) {
            // Syntax errors, truncated input, undecodable bytes and exceeded parser limits.
            throw new IllegalArgumentException(MALFORMED_JSON_MESSAGE, e);
        }
        if (payload == null) {
            throw new IllegalArgumentException(NOT_AN_OBJECT_MESSAGE);
        }
        return payload;
    }

    /**
     * Builds the {@code Price} event metadata in declaration order; each value is the payload value as text, and
     * {@code "null"} for an absent key, as the Groovy expressions {@code payload."<key>"} yielded {@code null}.
     */
    private static Map<String, String> metaData(Map<String, Object> payload) {
        Map<String, String> metaData = new LinkedHashMap<>();
        metaData.put(ITEM_NAME, String.valueOf(payload.get(ITEM_NAME)));
        metaData.put(ITEM_UNITS, String.valueOf(payload.get(ITEM_UNITS)));
        metaData.put(ITEM_PRICE_PER_UNIT, String.valueOf(payload.get(ITEM_PRICE_PER_UNIT)));
        return metaData;
    }

    /**
     * Returns the discount the script's {@code case} assigns to {@code itemName}. The script has no {@code else},
     * so any other name, a missing one or a non-string leaves {@code $discount} nil and {@code 1 - nil} fails.
     *
     * <p>Every call starts from no discount: the JRuby global {@code $discount} is not carried from one request to
     * the next, so an unknown item fails the same way whatever was requested before.
     */
    private static double discountFor(Object itemName) {
        if ("shoes".equals(itemName)) {
            return SHOES_DISCOUNT;
        }
        if ("jeans".equals(itemName)) {
            return JEANS_DISCOUNT;
        }
        if ("jackets".equals(itemName)) {
            return JACKETS_DISCOUNT;
        }
        throw new IllegalStateException(NIL_DISCOUNT_MESSAGE);
    }

    /**
     * Applies {@code price * factor} with the receiver types JRuby sees for the parsed JSON value.
     *
     * @return a {@link Double} for a numeric price (never {@code price - price * discount}), the empty string for
     *         a string price
     */
    private static Object applyDiscount(Object price, double factor) {
        if (price instanceof Number number) {
            return number.doubleValue() * factor;
        }
        if (price instanceof String) {
            return "";
        }
        if (price == null) {
            throw new IllegalStateException(NO_MULTIPLY_METHOD_PREFIX + NIL_DESCRIPTION);
        }
        throw new IllegalStateException(NO_MULTIPLY_METHOD_PREFIX + price);
    }
}
