/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.cache_scope_with_fibonacci.service;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;

import com.mulesoft.examples.cache_scope_with_fibonacci.model.FibonacciResult;

/**
 * Reproduces flow {@code cache-exampleFlow1} (cache-scope-with-fibonacci/src/main/app/cache-scope.xml:43-59)
 * after its HTTP listener: the favicon message filter, the {@code n < 20} choice, the call to the
 * {@code calculateFibonacci} flow and the composition of the reply text.
 */
@Service
public class FibonacciService {

    /** Pattern of the flow's {@code wildcard-filter}; without a {@code *} it matches only this exact text. */
    static final String FAVICON = "/favicon.ico";

    /** Payload of the choice's {@code otherwise} branch. */
    static final String TOO_HIGH = "ERROR: n must be less than 20";

    /** Upper bound (exclusive) of the choice condition {@code http.query.params.n < 20}. */
    private static final BigDecimal LIMIT = BigDecimal.valueOf(20);

    private final FibonacciCacheService fibonacciCacheService;

    /**
     * @param fibonacciCacheService the port of the {@code calculateFibonacci} flow and its cache scope
     */
    public FibonacciService(FibonacciCacheService fibonacciCacheService) {
        this.fibonacciCacheService = fibonacciCacheService;
    }

    /**
     * Reply of one {@code /fibonacci} request.
     *
     * @param body the exact response body; never null, {@code ""} for an empty body
     * @param cost the value of the original outbound property {@code cost}, which the Mule HTTP listener emitted
     *             as the response header {@code cost} (cache-scope.xml:53,70,76); null means no {@code cost}
     *             header, as on the error and filtered paths, where nothing set the property
     */
    public record FibonacciReply(String body, Integer cost) {
    }

    /**
     * Runs the flow for one request.
     *
     * @param n             the raw query parameter {@code n}, or null when absent
     * @param payload       the listener's payload, one of three kinds: {@code null} for a request without a body
     *                      or with a multipart body, the original's {@code NullPayload}; a {@link java.util.Map} of
     *                      the decoded parameters of a form-urlencoded body, the original's {@code ParameterMap};
     *                      or the text of any other body. Only the text {@value #FAVICON} fails the favicon
     *                      filter: a map, even one naming that path, and {@code null} pass it, as the original's
     *                      map and null payloads did
     * @param nocacheHeader true when the request carried a {@code nocache} HTTP header, which Mule 3 exposed as the
     *                      inbound property read by the {@code ee:cache} filter (cache-scope.xml:63)
     * @param nocacheQuery  true when the query string carried a {@code nocache} parameter with any value, the MEL
     *                      {@code http.query.params.nocache != null} (cache-scope.xml:72)
     * @return the reply body and the {@code cost} header value
     * @throws IllegalArgumentException when {@code n} is present but not a number
     */
    public FibonacciReply handle(String n, Object payload, boolean nocacheHeader, boolean nocacheQuery) {
        // The wildcard-filter tests the message payload, not the request path; only a text payload can equal the
        // pattern. A rejected message ends the flow with no payload: 200 with an empty body. Tier 2A verification
        // point.
        if (FAVICON.equals(payload)) {
            return new FibonacciReply("", null);
        }

        // A missing n fails the choice condition and takes the otherwise branch.
        if (n == null) {
            return new FibonacciReply(TOO_HIGH, null);
        }

        // MEL coerces the query string numerically for "< 20"; how the original failed on a non-numeric n is a
        // Tier 2A verification point. The exception propagates to the generic 500 handler.
        BigDecimal value;
        try {
            value = new BigDecimal(n);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid value for n: " + n, e);
        }

        if (value.compareTo(LIMIT) >= 0) {
            return new FibonacciReply(TOO_HIGH, null);
        }

        // The flow-ref keeps the HTTP inbound properties: the top-level cache filter sees the nocache header,
        // and the children see the nocache query parameter.
        FibonacciResult result = fibonacciCacheService.calculate(n, !nocacheHeader, !nocacheQuery);
        return new FibonacciReply("Fibonacci(" + n + ") = " + result.value() + "\nCOST: " + result.cost(),
                result.cost());
    }
}
