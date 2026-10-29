/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.filtering_a_message.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Optional;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Logic of filteringFlow1 (filtering.xml:9-17), processor for statement and in the original order:
 *
 * <ul>
 *   <li>{@code and-filter} (lines 9-12): the payload-type filter accepted only the HTTP listener's request
 *       stream, which the listener handed over for a non-empty body that is neither multipart nor
 *       form-urlencoded. Here that payload is the body text, so only a non-empty {@link String} passes; the
 *       {@code null} payload of a request without a body or with a multipart body, and the parameter
 *       {@link java.util.Map} of a form-urlencoded body, are rejected. The message-property filter
 *       {@code http.method=post} with {@code caseSensitive="false"} accepts the method {@code post} in any
 *       case;</li>
 *   <li>{@code json-to-object-transformer returnClass="java.util.HashMap"} (line 14): the body is read into
 *       a {@link HashMap}, the concrete type the original produced;</li>
 *   <li>{@code custom-filter} (line 15): {@link FreeMembershipDiscountFilter#accept};</li>
 *   <li>{@code set-payload} (line 16) and the INFO {@code logger} of {@code #[payload]} (line 17).</li>
 * </ul>
 *
 * <p>Neither filter declares {@code throwOnUnaccepted}, so a rejection is not an error: it ends the flow with
 * an empty payload, returned here as {@link Optional#empty()}. The flow declares no exception strategy, so a
 * body that is not a JSON object and a payload the custom filter fails on both reach the platform-default 500.
 * That 500 returns the exception's message, so each failure carries a fixed text instead of Jackson's
 * diagnostic, which can quote request bytes and name internal types, or the JDK's description of a failed
 * dereference: {@value #MALFORMED_JSON_MESSAGE}, {@value #NOT_AN_OBJECT_MESSAGE} or
 * {@value #FILTER_INPUT_MESSAGE}. The original exception is kept as the cause.
 *
 * <p>The class keeps no state of its own, so one instance serves concurrent requests.
 */
@Service
public class DiscountService {

    /** The payload {@code set-payload} assigns when the discount is granted, byte for byte. */
    public static final String GRANTED_MESSAGE = "the discount was granted.";

    /** Message of the failure for a body that is not readable JSON: malformed, truncated or not decodable. */
    static final String MALFORMED_JSON_MESSAGE = "Payload is not valid JSON";

    /** Message of the failure for a body that is blank or whose JSON value is not an object. */
    static final String NOT_AN_OBJECT_MESSAGE = "JSON payload is not an object";

    /** Message of the failure for a JSON object the custom filter cannot evaluate. */
    static final String FILTER_INPUT_MESSAGE =
            "JSON payload does not hold the membership, months and purchases values the discount filter requires";

    private static final Logger log = LoggerFactory.getLogger(DiscountService.class);

    /** The value the message-property filter compares the inbound {@code http.method} property with. */
    private static final String ACCEPTED_METHOD = "post";

    private static final TypeReference<HashMap<String, Object>> HASH_MAP_TYPE =
            new TypeReference<HashMap<String, Object>>() {
            };

    /**
     * A plain mapper, with no modules and Jackson's default features, reads JSON as the original transformer did:
     * integers as {@code Integer}, {@code Long} or {@code BigInteger}, decimals as {@code Double}, nested objects
     * as {@code LinkedHashMap} and arrays as {@code ArrayList}. Only source inclusion is disabled, so the location
     * in a parser diagnostic, kept as the cause of each failure, never quotes the request bytes. The application's
     * shared mapper is left untouched.
     */
    private final ObjectMapper objectMapper =
            JsonMapper.builder().disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).build();

    private final FreeMembershipDiscountFilter freeMembershipDiscountFilter;

    /**
     * Creates the service over the ported custom filter.
     *
     * @param freeMembershipDiscountFilter the free-membership discount rule of the {@code custom-filter}
     */
    public DiscountService(FreeMembershipDiscountFilter freeMembershipDiscountFilter) {
        this.freeMembershipDiscountFilter = freeMembershipDiscountFilter;
    }

    /**
     * Runs one request through the flow's filters.
     *
     * @param httpMethod the request method, the original's inbound {@code http.method} property
     * @param payload    the listener's payload: the body text, the parameter {@link java.util.Map} of a
     *                   form-urlencoded body, or {@code null} when the request carries no body or a multipart one
     * @return {@link Optional#of} {@value #GRANTED_MESSAGE} when both filters accept the request, otherwise
     *         {@link Optional#empty()}, which the caller answers with an empty 200
     * @throws UncheckedIOException     when the body text is not readable JSON ({@value #MALFORMED_JSON_MESSAGE})
     *                                  or is blank or a JSON value other than an object
     *                                  ({@value #NOT_AN_OBJECT_MESSAGE}), as the original transformer failed on
     *                                  them
     * @throws ClassCastException       when the body text is the JSON literal {@code null}
     *                                  ({@value #NOT_AN_OBJECT_MESSAGE}), as the original filter's cast of the
     *                                  null payload to {@code Map} failed
     * @throws IllegalArgumentException when the custom filter fails on the object, because a value it reads is
     *                                  missing, {@code null} or not an integer ({@value #FILTER_INPUT_MESSAGE})
     */
    public Optional<String> applyDiscountFilters(String httpMethod, Object payload) {
        if (!(payload instanceof String body) || body.isEmpty() || !ACCEPTED_METHOD.equalsIgnoreCase(httpMethod)) {
            return Optional.empty();
        }

        HashMap<String, Object> map = toPayload(body);

        boolean accepted;
        try {
            accepted = freeMembershipDiscountFilter.accept(map);
        } catch (NullPointerException | NumberFormatException e) {
            // A fixed message, never the JDK's: a helpful NullPointerException names internal types and variables,
            // and a NumberFormatException quotes the request value.
            throw new IllegalArgumentException(FILTER_INPUT_MESSAGE, e);
        }
        if (!accepted) {
            return Optional.empty();
        }

        String granted = GRANTED_MESSAGE;
        log.info(granted);
        return Optional.of(granted);
    }

    /**
     * Converts the body text to the flow's payload map (line 14). Every failure carries a fixed message; Jackson's
     * exception, when there is one, is its cause.
     */
    private HashMap<String, Object> toPayload(String body) {
        HashMap<String, Object> map;
        try {
            map = objectMapper.readValue(body, HASH_MAP_TYPE);
        } catch (MismatchedInputException e) {
            // A blank body and an array, string, number or boolean root.
            throw new UncheckedIOException(NOT_AN_OBJECT_MESSAGE, e);
        } catch (IOException e) {
            // Syntax errors, truncated input and exceeded parser limits.
            throw new UncheckedIOException(MALFORMED_JSON_MESSAGE, e);
        }
        if (map == null) {
            throw new ClassCastException(NOT_AN_OBJECT_MESSAGE);
        }
        return map;
    }
}
