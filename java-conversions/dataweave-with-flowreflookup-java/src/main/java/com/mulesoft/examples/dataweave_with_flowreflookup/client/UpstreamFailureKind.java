/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.dataweave_with_flowreflookup.client;

import java.util.Objects;
import java.util.Optional;

/**
 * How a failed vendor call is classified (shared component SC-9): the result a vendor's
 * {@code UpstreamFailureClassifier} returns for a throwable, which decides what {@code UpstreamCallExecutor}
 * does next.
 *
 * <p>The class depends on the JDK alone so that every connector project can copy it with only its
 * {@code package} line changed.
 *
 * @param category   the failure category; never {@code null}
 * @param retryAfter the vendor's {@code Retry-After} header value, exactly as received, for a
 *                   {@link Category#RATE_LIMIT} failure; {@code null} when the vendor sent none and for every
 *                   other category
 */
public record UpstreamFailureKind(Category category, String retryAfter) {

    /**
     * The failure categories, one per behaviour of the executor.
     */
    public enum Category {

        /**
         * The vendor rejected the credentials or session (for example an expired token): the executor
         * re-authenticates and retries the call once; a second authentication failure ends the attempt with an
         * {@code UpstreamAuthenticationException} (502).
         */
        AUTHENTICATION,

        /**
         * The vendor throttled the call: never retried; the executor raises an {@code UpstreamRateLimitException}
         * (429) carrying the vendor's {@code Retry-After} value when one was sent.
         */
        RATE_LIMIT,

        /**
         * A timeout or connectivity failure: never retried, so no write is ever submitted twice; the executor
         * raises an {@code UpstreamUnavailableException} (503).
         */
        UNAVAILABLE,

        /**
         * Any other failure: the executor rethrows the original throwable unchanged, so the example's own
         * exception branch still receives the vendor type.
         */
        OTHER
    }

    // The enum constants are always written as Category.X inside this record: the static fields below share
    // their simple names and would otherwise shadow them.

    /** An authentication or session-expiry failure. */
    public static final UpstreamFailureKind AUTHENTICATION = new UpstreamFailureKind(Category.AUTHENTICATION, null);

    /** A timeout or connectivity failure. */
    public static final UpstreamFailureKind UNAVAILABLE = new UpstreamFailureKind(Category.UNAVAILABLE, null);

    /** A failure outside the three handled categories, rethrown unchanged. */
    public static final UpstreamFailureKind OTHER = new UpstreamFailureKind(Category.OTHER, null);

    /**
     * Validates the components; {@code retryAfter} is stored exactly as given, {@code null} included.
     *
     * @param category   the failure category
     * @param retryAfter the vendor's {@code Retry-After} header value, or {@code null}
     * @throws NullPointerException if {@code category} is {@code null}
     */
    public UpstreamFailureKind {
        Objects.requireNonNull(category, "category");
    }

    /**
     * Returns a rate-limit classification.
     *
     * @param retryAfterOrNull the vendor's {@code Retry-After} header value, or {@code null} when the vendor sent
     *                         none (Salesforce never sends one)
     * @return a {@link Category#RATE_LIMIT} kind carrying {@code retryAfterOrNull} unchanged
     */
    public static UpstreamFailureKind rateLimit(String retryAfterOrNull) {
        return new UpstreamFailureKind(Category.RATE_LIMIT, retryAfterOrNull);
    }

    /**
     * Returns the {@code Retry-After} value worth copying to the caller.
     *
     * @return the stored value, or empty when it is {@code null} or blank
     */
    public Optional<String> retryAfterValue() {
        return Optional.ofNullable(retryAfter).filter(v -> !v.isBlank());
    }
}
