/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.implementing_a_choice_exception_strategy.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Service;

/**
 * Reproduces the processors of flow {@code choice-error-handlingFlow1} in
 * {@code implementing-a-choice-exception-strategy/src/main/app/choice-error-handling.xml}, lines 8-14, in
 * statement order:
 *
 * <ul>
 *   <li>{@code json-to-object-transformer returnClass="java.util.HashMap"} (line 8) is {@link #toPayload};</li>
 *   <li>{@code custom-filter class="org.mule.examples.InputDataValidator"} (line 9), the flow variable
 *       {@code order} (line 10), {@code set-payload #[payload['email']]} (line 12), the email
 *       {@code regex-filter} (line 13) and the success {@code set-payload} (line 14) are {@link #validate}.</li>
 * </ul>
 *
 * <p>Neither filter declares {@code throwOnUnaccepted}, so a rejection is not an error: {@link #validate}
 * returns {@link Optional#empty()} and the caller answers it with an empty 200. The validator's
 * {@link NullPointerException} and {@link IllegalArgumentException} propagate unwrapped, because they select
 * the "Missing input data" and "Invalid input data" branches of the choice exception strategy. Unreadable
 * JSON raises neither, so it reaches the platform-default 500 as it did in the original. That 500 returns the
 * exception's message, so {@link #toPayload} replaces Jackson's diagnostic, which can quote request bytes and
 * name internal types, with a fixed text: {@code Payload is not valid JSON} or {@code JSON payload is not an
 * object}.
 *
 * <p>The email {@code regex-filter} is a scan that returns, for every text, what {@code Matcher.find()} returns
 * for the line-13 pattern, in time linear in the text's length. The pattern's own backtracking search takes time
 * quadratic in the length of a text holding no address and overflows its stack on a long dotted local part, such
 * as {@code a.} repeated a few thousand times before {@code a@b.c}; the scan gives such a text the pattern's
 * answer too.
 *
 * <p>The payload is a real {@link HashMap}, the type the original transformer produced, because its
 * {@code toString()} reaches callers through the error bodies {@code Missing input data: #[payload]} and
 * {@code Invalid input data: #[payload]}. On JDK 17 a {@code HashMap} holding the sample keys iterates
 * {@code item price per unit}, {@code membership}, {@code item name}, {@code item units}, {@code email}, so
 * {@code message.json} renders as
 * {@code {item price per unit=1, membership=free, item name=aa, item units=10, email=aaa@aaa.aa}} and
 * {@code message_wrong.json} as {@code {item price per unit=1, membership=free, item name=aa, item units=10}}.
 * Replacing the map with a {@code LinkedHashMap}, or reordering its keys, would change those bodies.
 *
 * <p>The class keeps no per-request state, so one instance serves concurrent requests.
 */
@Service
public class InputValidationService {

    /** Payload the flow sets once both filters accept the request (line 14). */
    public static final String SUCCESS_MESSAGE = "Input data validation passed.";

    /**
     * The characters other than {@code a-z} and {@code 0-9} in the local-part class
     * {@code [a-z0-9!#$%&'*+/=?^_`{|}~-]} of the line-13 {@code regex-filter} pattern ({@code &amp;} decoded);
     * {@code .} and {@code @} are not among them.
     */
    private static final String LOCAL_PART_SPECIALS = "!#$%&'*+/=?^_`{|}~-";

    /** How Mule rendered a null payload when the {@code regex-filter} read it as a string. */
    private static final String NULL_PAYLOAD_TEXT = "{NullPayload}";

    /** Message of the failure for a body that is not readable JSON: malformed, truncated or not decodable. */
    private static final String MALFORMED_JSON_MESSAGE = "Payload is not valid JSON";

    /** Message of the failure for a body that is empty or whose JSON value is not an object. */
    private static final String NOT_AN_OBJECT_MESSAGE = "JSON payload is not an object";

    private static final TypeReference<HashMap<String, Object>> HASH_MAP_TYPE =
            new TypeReference<HashMap<String, Object>>() {
            };

    private final InputDataValidator inputDataValidator;

    /**
     * A plain mapper, with no modules and Jackson's default features, reads JSON as the original transformer did:
     * integers as {@code Integer}, {@code Long} or {@code BigInteger}, decimals as {@code Double}, nested objects
     * as {@code LinkedHashMap} and arrays as {@code ArrayList}. Only source inclusion is disabled, so the location
     * in a parser diagnostic, and in the cause chained to each failure, never quotes the request bytes.
     */
    private final ObjectMapper objectMapper =
            JsonMapper.builder().disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).build();

    /**
     * @param inputDataValidator the ported {@code custom-filter} of line 9
     */
    public InputValidationService(InputDataValidator inputDataValidator) {
        this.inputDataValidator = inputDataValidator;
    }

    /**
     * Converts the request body to the flow's payload map (line 8).
     *
     * <p>Every failure carries a fixed message; Jackson's exception, when there is one, is its cause.
     *
     * @param body the raw request body; {@code null} is read as an empty body
     * @return the parsed object as a {@link HashMap}
     * @throws UncheckedIOException when the body is malformed, truncated or not decodable
     *                              ({@code Payload is not valid JSON}), or empty or a JSON value other than an
     *                              object ({@code JSON payload is not an object})
     * @throws ClassCastException   when the body is the JSON literal {@code null}
     *                              ({@code JSON payload is not an object}), as the original filter's cast of the
     *                              null payload to {@code Map} failed
     */
    public HashMap<String, Object> toPayload(byte[] body) {
        // Jackson rejects a null array with IllegalArgumentException, which would select the
        // "Invalid input data" branch; an empty array fails as unreadable input instead.
        byte[] bytes = body == null ? new byte[0] : body;
        HashMap<String, Object> payload;
        try {
            payload = objectMapper.readValue(bytes, HASH_MAP_TYPE);
        } catch (MismatchedInputException e) {
            // Fixed messages, never Jackson's: these messages reach the 500 body, and Jackson's can quote request
            // bytes and name internal types such as java.util.HashMap. This one covers an empty body and an
            // array, string, number or boolean root.
            throw new UncheckedIOException(NOT_AN_OBJECT_MESSAGE, e);
        } catch (IOException e) {
            // Syntax errors, truncated input, undecodable bytes and exceeded parser limits.
            throw new UncheckedIOException(MALFORMED_JSON_MESSAGE, e);
        }
        if (payload == null) {
            throw new ClassCastException(NOT_AN_OBJECT_MESSAGE);
        }
        return payload;
    }

    /**
     * Runs the flow's processors after the transformer (lines 9-14).
     *
     * @param payload the map {@link #toPayload} produced; the validator receives this same instance
     * @return {@link #SUCCESS_MESSAGE}, or {@link Optional#empty()} when the custom filter or the email
     *         {@code regex-filter} rejects the message
     * @throws NullPointerException     from the validator when the {@code email} key is missing or an item
     *                                  value is absent
     * @throws IllegalArgumentException from the validator when an item value is out of range or not an integer
     */
    public Optional<String> validate(HashMap<String, Object> payload) {
        if (!inputDataValidator.accept(payload)) {
            return Optional.empty();
        }

        // Flow variable "order" (line 10); nothing in the flow reads it.
        Map<String, Object> order = payload;

        Object email = payload.get("email");
        String tested = email == null ? NULL_PAYLOAD_TEXT : String.valueOf(email);
        // Mule's RegExFilter accepted a match anywhere in the text (find(), not matches()), and so does the scan.
        if (!containsEmailAddress(tested)) {
            return Optional.empty();
        }

        return Optional.of(SUCCESS_MESSAGE);
    }

    /**
     * Returns what {@code Matcher.find()} returns for the line-13 {@code regex-filter} pattern
     * {@code C+(?:\.C+)*@(?:L\.)+A(?:[a-z0-9  -]*A)?} on {@code text}, where {@code C} is the local-part class,
     * {@code A} is {@code [a-z0-9]} and {@code L} is the label {@code A(?:[a-z0-9-]*A)?}. It reads the text in one
     * left-to-right pass, without recursion and without a length limit. Every class is ASCII and case-sensitive, as
     * the pattern has no flags.
     *
     * <p>The text contains a match exactly when, at some {@code @} at index {@code i >= 1}, the character at
     * {@code i-1} is in {@code C} and the text after the {@code @} starts with an {@code L}, a {@code .} and an
     * {@code A}. Every match has that shape: its local part ends in a {@code C} character just before its
     * {@code @}, and its domain starts with a label, its dot and an {@code A}, the first character of the next
     * label or the final {@code A}. That shape is itself a match: a one-character local part, one label with its
     * dot, and the final {@code A} with the optional tail omitted. The tail class {@code [a-z0-9  -]}, with its two
     * spaces, only lengthens a match that already exists, so it never changes the result.
     *
     * <p>{@code .} is not in {@code [a-z0-9-]}, so the label before the dot is the whole maximal run of
     * {@code [a-z0-9-]} characters starting at {@code i+1}: the run must start and end with an {@code A}, and be
     * followed by a {@code .} and an {@code A}. {@code @} is not in that class either, so the runs after distinct
     * {@code @}s are disjoint, and the scan reads each character a bounded number of times.
     *
     * @param text the email value as the filter read it
     * @return {@code true} when the pattern occurs anywhere in {@code text}
     */
    private static boolean containsEmailAddress(String text) {
        int length = text.length();
        int at = text.indexOf('@', 1);
        while (at >= 0) {
            int runEnd = at + 1;
            while (runEnd < length && isLabelChar(text.charAt(runEnd))) {
                runEnd++;
            }
            if (isLocalPartChar(text.charAt(at - 1))
                    && runEnd > at + 1
                    && isAlphanumeric(text.charAt(at + 1))
                    && isAlphanumeric(text.charAt(runEnd - 1))
                    && runEnd + 1 < length
                    && text.charAt(runEnd) == '.'
                    && isAlphanumeric(text.charAt(runEnd + 1))) {
                return true;
            }
            // The run holds no '@', so the next one is at runEnd or beyond.
            at = text.indexOf('@', runEnd);
        }
        return false;
    }

    /** The pattern's local-part class {@code C}. */
    private static boolean isLocalPartChar(char c) {
        return isAlphanumeric(c) || LOCAL_PART_SPECIALS.indexOf(c) >= 0;
    }

    /** The pattern's label-body class {@code [a-z0-9-]}. */
    private static boolean isLabelChar(char c) {
        return isAlphanumeric(c) || c == '-';
    }

    /** The pattern's class {@code [a-z0-9]}, with which every label and the domain's last part start and end. */
    private static boolean isAlphanumeric(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }
}
