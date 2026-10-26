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
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * JSON raises neither, so it reaches the platform-default 500 as it did in the original.
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
     * The {@code regex-filter} pattern of line 13 with {@code &amp;} decoded; the two spaces before the hyphen
     * in the last character class are the original's. No flags: Mule's filter matched case-sensitively.
     */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*@(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9  -]*[a-z0-9])?");

    /** How Mule rendered a null payload when the {@code regex-filter} read it as a string. */
    private static final String NULL_PAYLOAD_TEXT = "{NullPayload}";

    private static final TypeReference<HashMap<String, Object>> HASH_MAP_TYPE =
            new TypeReference<HashMap<String, Object>>() {
            };

    private final InputDataValidator inputDataValidator;

    /**
     * A plain mapper, with no modules or features, reads JSON as the original transformer did: integers as
     * {@code Integer}, {@code Long} or {@code BigInteger}, decimals as {@code Double}, nested objects as
     * {@code LinkedHashMap} and arrays as {@code ArrayList}.
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param inputDataValidator the ported {@code custom-filter} of line 9
     */
    public InputValidationService(InputDataValidator inputDataValidator) {
        this.inputDataValidator = inputDataValidator;
    }

    /**
     * Converts the request body to the flow's payload map (line 8).
     *
     * @param body the raw request body; {@code null} is read as an empty body
     * @return the parsed object as a {@link HashMap}
     * @throws UncheckedIOException when the body is empty, malformed, or a JSON value other than an object
     * @throws ClassCastException   when the body is the JSON literal {@code null}, as the original filter's
     *                              cast of the null payload to {@code Map} failed
     */
    public HashMap<String, Object> toPayload(byte[] body) {
        // Jackson rejects a null array with IllegalArgumentException, which would select the
        // "Invalid input data" branch; an empty array fails as unreadable input instead.
        byte[] bytes = body == null ? new byte[0] : body;
        HashMap<String, Object> payload;
        try {
            payload = objectMapper.readValue(bytes, HASH_MAP_TYPE);
        } catch (IOException e) {
            throw new UncheckedIOException(e.getMessage(), e);
        }
        if (payload == null) {
            throw new ClassCastException("payload is not a java.util.Map");
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
        // Mule's RegExFilter accepted a match anywhere in the text: find(), not matches().
        if (!EMAIL_PATTERN.matcher(tested).find()) {
            return Optional.empty();
        }

        return Optional.of(SUCCESS_MESSAGE);
    }
}
