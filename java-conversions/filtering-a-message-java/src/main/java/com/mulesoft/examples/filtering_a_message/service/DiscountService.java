/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.filtering_a_message.service;

import java.util.HashMap;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Logic of filteringFlow1 (filtering.xml:9-17), processor for statement and in the original order:
 *
 * <ul>
 *   <li>{@code and-filter} (lines 9-12): the payload-type filter accepted only the HTTP listener's request
 *       stream, which a request without a body never carried, so a {@code null} or empty body is rejected;
 *       the message-property filter {@code http.method=post} with {@code caseSensitive="false"} accepts the
 *       method {@code post} in any case;</li>
 *   <li>{@code json-to-object-transformer returnClass="java.util.HashMap"} (line 14): the body is read into
 *       a {@link HashMap}, the concrete type the original produced;</li>
 *   <li>{@code custom-filter} (line 15): {@link FreeMembershipDiscountFilter#accept};</li>
 *   <li>{@code set-payload} (line 16) and the INFO {@code logger} of {@code #[payload]} (line 17).</li>
 * </ul>
 *
 * <p>Neither filter declares {@code throwOnUnaccepted}, so a rejection is not an error: it ends the flow with
 * an empty payload, returned here as {@link Optional#empty()}. The flow declares no exception strategy, so a
 * body that is not a JSON object and every exception of the custom filter propagate unchanged.
 *
 * <p>The class keeps no state of its own, so one instance serves concurrent requests.
 */
@Service
public class DiscountService {

    /** The payload {@code set-payload} assigns when the discount is granted, byte for byte. */
    public static final String GRANTED_MESSAGE = "the discount was granted.";

    private static final Logger log = LoggerFactory.getLogger(DiscountService.class);

    /** The value the message-property filter compares the inbound {@code http.method} property with. */
    private static final String ACCEPTED_METHOD = "post";

    private final ObjectMapper objectMapper;

    private final FreeMembershipDiscountFilter freeMembershipDiscountFilter;

    /**
     * Creates the service over the application's Jackson mapper and the ported custom filter.
     *
     * @param objectMapper                 the Spring Boot auto-configured mapper that reads the request body
     * @param freeMembershipDiscountFilter the free-membership discount rule of the {@code custom-filter}
     */
    public DiscountService(ObjectMapper objectMapper, FreeMembershipDiscountFilter freeMembershipDiscountFilter) {
        this.objectMapper = objectMapper;
        this.freeMembershipDiscountFilter = freeMembershipDiscountFilter;
    }

    /**
     * Runs one request through the flow's filters.
     *
     * @param httpMethod the request method, the original's inbound {@code http.method} property
     * @param body       the raw request body, {@code null} when the request carries none
     * @return {@link Optional#of} {@value #GRANTED_MESSAGE} when both filters accept the request, otherwise
     *         {@link Optional#empty()}, which the caller answers with an empty 200
     * @throws JsonProcessingException when a non-empty body is not a JSON object, whitespace-only bodies and
     *                                 JSON arrays included, as the original transformer failed on them
     */
    public Optional<String> applyDiscountFilters(String httpMethod, String body) throws JsonProcessingException {
        boolean hasStreamPayload = body != null && !body.isEmpty();
        if (!hasStreamPayload || !ACCEPTED_METHOD.equalsIgnoreCase(httpMethod)) {
            return Optional.empty();
        }

        // A literal JSON null yields a null map; the filter then throws, failing the request as the original did.
        @SuppressWarnings("unchecked")
        HashMap<String, Object> map = objectMapper.readValue(body, HashMap.class);

        if (!freeMembershipDiscountFilter.accept(map)) {
            return Optional.empty();
        }

        String payload = GRANTED_MESSAGE;
        log.info(payload);
        return Optional.of(payload);
    }
}
