/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_ms_dynamics.exception;

import java.util.Objects;

import com.mulesoft.examples.import_contacts_into_ms_dynamics.client.UpstreamSystem;

/**
 * A vendor call failed because the vendor could not be reached in time (shared component SC-9).
 *
 * <p>{@code client/UpstreamCallExecutor} throws it when the project's {@code UpstreamFailureClassifier} returns
 * {@code UNAVAILABLE}, that is when the cause chain of the failure holds a timeout
 * ({@link java.net.SocketTimeoutException}, {@link java.net.http.HttpTimeoutException} or Netty's
 * {@code ReadTimeoutException}), a connectivity failure ({@link java.net.ConnectException},
 * {@link java.net.UnknownHostException}, {@link java.net.NoRouteToHostException} or any other
 * {@link java.net.SocketException}, such as a connection reset) or a connection the transport found closed or reset
 * ({@link java.nio.channels.ClosedChannelException}, Reactor Netty's {@code PrematureCloseException} and
 * {@code AbortedException}, Netty's {@code PrematureChannelClosureException} and {@code NativeIoException}). The
 * original failure is kept as the cause.
 *
 * <p>The call is never retried: a timed-out or interrupted request may already have been processed by the
 * vendor, so repeating it could submit a write twice, for example a Salesforce {@code sfdc:create}, a Workday
 * {@code Put_Customer}, a Dynamics contact create or a NetSuite customer create.
 *
 * <p>In HTTP-triggered projects {@code UpstreamExceptionHandler} answers it with 503 and the body
 * {@code { "message": "<message>", "upstream": "<system>" }}.
 *
 * <p>The class depends on the JDK and {@link UpstreamSystem} alone so that every connector project can copy it
 * with only its {@code package} line changed.
 */
public class UpstreamUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UpstreamSystem system;

    /**
     * Creates the exception.
     *
     * @param system  the vendor that could not be reached; its display name is the {@code upstream} value
     *                callers see
     * @param message the upstream message reported to the caller
     * @param cause   the timeout or connectivity failure the classifier recognized
     * @throws NullPointerException if {@code system} is {@code null}
     */
    public UpstreamUnavailableException(UpstreamSystem system, String message, Throwable cause) {
        super(message, cause);
        this.system = Objects.requireNonNull(system, "system");
    }

    /**
     * @return the vendor that could not be reached
     */
    public UpstreamSystem getSystem() {
        return system;
    }
}
