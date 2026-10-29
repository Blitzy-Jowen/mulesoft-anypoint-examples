/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.scatter_gather_flow_control.model;

import java.util.Objects;

/**
 * Outcome of one scatter-gather route: the payload it produced or the exception it raised. Replaces the
 * per-route event entries of Mule's {@code AggregationContext}.
 *
 * <p>The list a service hands to the aggregation strategy holds one entry per route in route order (route A
 * first, route B second), so an entry's index identifies its route as the event index did in Mule.
 */
public final class BranchResult {

    private final Object value;
    private final Throwable exception;

    private BranchResult(Object value, Throwable exception) {
        this.value = value;
        this.exception = exception;
    }

    /**
     * Records a route that completed.
     *
     * @param value the route's payload; may be {@code null}, which the aggregation strategy judges, not this class
     * @return a successful result carrying {@code value}
     */
    public static BranchResult success(Object value) {
        return new BranchResult(value, null);
    }

    /**
     * Records a route that raised an exception.
     *
     * @param exception the exception the route raised
     * @return a failed result carrying {@code exception} and no value
     * @throws NullPointerException if {@code exception} is {@code null}
     */
    public static BranchResult failure(Throwable exception) {
        return new BranchResult(null, Objects.requireNonNull(exception, "exception"));
    }

    /**
     * @return the route's payload, or {@code null} for a failed route
     */
    public Object value() {
        return value;
    }

    /**
     * @return the exception the route raised, or {@code null} for a successful route
     */
    public Throwable exception() {
        return exception;
    }

    /**
     * @return {@code true} when the route completed without an exception
     */
    public boolean isSuccess() {
        return exception == null;
    }

    @Override
    public String toString() {
        return isSuccess()
                ? "BranchResult[success, value=" + value + "]"
                : "BranchResult[failure, exception=" + exception + "]";
    }
}
