/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.filtering_a_message.controller;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.mulesoft.examples.filtering_a_message.service.DiscountService;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP trigger of flow {@code filteringFlow1} (filtering-a-message/src/main/app/filtering.xml:3-7): the listener on
 * {@code /} with {@code allowedMethods="POST"} and its {@code response-builder statusCode="200"}. The filters, the
 * JSON conversion and the logger of the flow run in {@link DiscountService}; this class only binds the request and
 * writes the response.
 *
 * <ul>
 *   <li>Accepted request: 200 {@code text/plain;charset=UTF-8} {@code the discount was granted.}; step 9 of the
 *       example README configures the response builder with Content Type {@code text/plain}.</li>
 *   <li>Rejected by a filter: 200 with an empty body and no Content-Type. Neither filter throws on rejection, so
 *       the flow ends with no payload, as {@code FilteringMessageIT.java:62-67} asserts for its second message.</li>
 *   <li>Every exception propagates unchanged to {@code GlobalExceptionHandler}, because the flow declares no
 *       exception strategy.</li>
 * </ul>
 *
 * <p>No mapping declares {@code consumes} or {@code produces}: the listener accepted any Content-Type, or none,
 * and ignored {@code Accept}, leaving the payload check to the filters. Only {@code POST} and the rejecting
 * {@code OPTIONS} mapping are declared, so every other method on {@code /} reaches the 405 and every other path the
 * no-listener 404 of {@code GlobalExceptionHandler}.
 */
@RestController
public class DiscountController {

    private static final MediaType TEXT_PLAIN_UTF8 = MediaType.parseMediaType("text/plain;charset=UTF-8");

    /**
     * The inbound {@code http.method} property the flow's message-property filter checks. The listener admits
     * {@code POST} alone, so it is the only value that property could carry.
     */
    private static final String LISTENER_METHOD = "POST";

    private final DiscountService discountService;

    public DiscountController(DiscountService discountService) {
        this.discountService = discountService;
    }

    /**
     * Runs a {@code POST /} through the flow.
     *
     * @param body the raw request body, {@code null} when the request carries none
     * @return 200 {@code text/plain;charset=UTF-8} with the granted message, or an empty 200 when a filter rejects
     * @throws JsonProcessingException when a non-empty body is not a JSON object; answered with SC-1's 500
     */
    @PostMapping("/")
    public ResponseEntity<String> grantDiscount(@RequestBody(required = false) String body)
            throws JsonProcessingException {
        Optional<String> outcome = discountService.applyDiscountFilters(LISTENER_METHOD, body);
        if (outcome.isPresent()) {
            return ResponseEntity.ok().contentType(TEXT_PLAIN_UTF8).body(outcome.get());
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Rejects {@code OPTIONS /} as the listener's {@code allowedMethods="POST"} did. Without this mapping Spring MVC
     * would answer {@code OPTIONS} on a POST-only path itself, with 200 and an {@code Allow} header.
     *
     * @return never returns normally
     * @throws HttpRequestMethodNotSupportedException always, answered with SC-1's 405
     */
    @RequestMapping(path = "/", method = RequestMethod.OPTIONS)
    public ResponseEntity<String> rejectOptions() throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(RequestMethod.OPTIONS.name(), List.of(LISTENER_METHOD));
    }
}
