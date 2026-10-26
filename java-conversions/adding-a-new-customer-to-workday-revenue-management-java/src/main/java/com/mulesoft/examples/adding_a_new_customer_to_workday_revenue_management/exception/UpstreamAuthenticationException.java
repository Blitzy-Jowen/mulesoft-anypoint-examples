/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.exception;

import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.client.UpstreamSystem;

import java.util.Objects;

/**
 * An upstream system rejected the call's authentication, or its session or token expired, and the single
 * re-authentication and retry did not succeed (shared component SC-9).
 *
 * <p>{@code client/UpstreamCallExecutor} throws it once the vendor's classifier reports an authentication failure
 * for the re-authentication step or for the one retry, or when no re-authentication step exists. In HTTP-triggered
 * projects {@code UpstreamExceptionHandler} maps it to HTTP 502 with the body
 * {@code { "message": "<upstream message>", "upstream": "<system display name>" }}; in file- and poll-triggered
 * projects, which have no caller to answer, the failure is logged at ERROR with the upstream name.
 *
 * <p>The message is the upstream's own message and is reported to the caller as received, so it must never be
 * rewritten. The cause is the vendor failure that was classified.
 *
 * <p>The class depends on the JDK and {@link UpstreamSystem} alone so that every connector project can copy it
 * with only its {@code package} line changed.
 */
public class UpstreamAuthenticationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UpstreamSystem system;

    /**
     * Creates the exception.
     *
     * @param system  the upstream that rejected the authentication; never {@code null}
     * @param message the upstream's own failure message, unchanged
     * @param cause   the vendor failure that was classified as an authentication failure
     * @throws NullPointerException if {@code system} is {@code null}
     */
    public UpstreamAuthenticationException(UpstreamSystem system, String message, Throwable cause) {
        super(message, cause);
        this.system = Objects.requireNonNull(system, "system");
    }

    /**
     * @return the upstream that rejected the authentication, whose display name is the {@code upstream} value of
     *         the 502 body
     */
    public UpstreamSystem getSystem() {
        return system;
    }
}
