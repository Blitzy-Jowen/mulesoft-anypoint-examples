/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.cache_scope_with_fibonacci.model;

/**
 * Result of one Fibonacci calculation, replacing the response message of the {@code calculateFibonacci} flow.
 *
 * <p>The value stays text because the flow's base case echoes the raw query parameter {@code n} unchanged, and
 * the recursive case reads each sub-result back with {@code Long.parseLong}.
 *
 * @param value the calculated payload: the raw {@code n} text when {@code n < 2}, otherwise the decimal sum of
 *              the two sub-results
 * @param cost  the flow's outbound {@code cost} property: 1 for a base case, otherwise the two sub-results' costs
 *              plus one
 */
public record FibonacciResult(String value, int cost) {

    /**
     * Port of {@code FibonacciResponseGenerator.create}: a response served from the cache reports cost 0.
     *
     * @return a copy of this result with the same value and cost 0
     */
    public FibonacciResult cached() {
        return new FibonacciResult(value, 0);
    }
}
