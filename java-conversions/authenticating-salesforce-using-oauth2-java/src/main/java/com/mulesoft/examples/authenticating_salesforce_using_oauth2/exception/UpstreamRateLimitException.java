/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.authenticating_salesforce_using_oauth2.exception;

import com.mulesoft.examples.authenticating_salesforce_using_oauth2.client.UpstreamSystem;

import java.util.Objects;

/**
 * An upstream throttled the call (shared component SC-9, the approved "rate limit / throttling" failure mode).
 *
 * <p>Thrown by {@code client/UpstreamCallExecutor} when the vendor's {@code UpstreamFailureClassifier} returns
 * {@code RATE_LIMIT}. The call is never retried: the vendor refused it, and repeating it would only be refused
 * again or, for a write, risk submitting it twice.
 *
 * <p>{@link #getRetryAfter()} carries the optional {@code Retry-After} value of the classifier's
 * {@code UpstreamFailureKind}, exactly as the vendor sent it (delta-seconds or an HTTP-date), neither trimmed nor
 * parsed. It is {@code null} for Salesforce, whose {@code REQUEST_LIMIT_EXCEEDED} fault carries none; NetSuite,
 * Microsoft Dynamics 365 and Workday HTTP 429 responses may supply it.
 *
 * <p>In HTTP-triggered projects {@code UpstreamExceptionHandler} maps it to 429, copying {@code Retry-After} to the
 * response when present, with the body {@code { "message": "<upstream message>", "upstream": "<system>" }}; in
 * file- and poll-triggered projects it is logged at ERROR. The message is the upstream's own and is not rewritten.
 *
 * <p>The class depends on the JDK and {@link UpstreamSystem} alone so that every connector project can copy it
 * with only its {@code package} line changed.
 */
public class UpstreamRateLimitException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UpstreamSystem system;

    private final String retryAfter;

    /**
     * Creates the exception for a throttled call.
     *
     * @param system     the upstream that throttled the call; never {@code null}
     * @param message    the upstream's own failure message
     * @param retryAfter the vendor's raw {@code Retry-After} header value, or {@code null} when it sent none
     * @param cause      the vendor throwable the classifier examined
     * @throws NullPointerException if {@code system} is {@code null}
     */
    public UpstreamRateLimitException(UpstreamSystem system, String message, String retryAfter, Throwable cause) {
        super(message, cause);
        this.system = Objects.requireNonNull(system, "system");
        this.retryAfter = retryAfter;
    }

    /**
     * @return the upstream that throttled the call, whose display name is the {@code upstream} value of the 429 body
     */
    public UpstreamSystem getSystem() {
        return system;
    }

    /**
     * @return the vendor's {@code Retry-After} value exactly as received, or {@code null} when the vendor sent none
     */
    public String getRetryAfter() {
        return retryAfter;
    }
}
