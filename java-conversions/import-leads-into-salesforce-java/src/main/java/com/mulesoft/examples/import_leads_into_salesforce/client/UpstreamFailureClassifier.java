/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_leads_into_salesforce.client;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/**
 * Classifies a failed vendor call for {@code UpstreamCallExecutor} (shared component SC-9, the approved connector
 * failure handling).
 *
 * <p>Each vendor supplies one implementation as a Spring {@code @Component} ({@code SalesforceFailureClassifier},
 * {@code NetsuiteFailureClassifier}, {@code DynamicsFailureClassifier}, {@code WorkdayFailureClassifier}); the
 * executor receives every classifier bean and selects the one whose {@link #system()} matches the call. The
 * returned {@link UpstreamFailureKind} decides the executor's next step: re-authenticate and retry once, fail with
 * 429 or 503 without any retry, or rethrow the original throwable unchanged.
 *
 * <p>Timeouts and connectivity failures are classified identically for every vendor, by {@link #isUnavailable},
 * so no vendor can retry them and no write is ever submitted twice.
 *
 * <p>The interface depends on the JDK and its SC-9 siblings alone so that every connector project can copy it with
 * only its {@code package} line changed. In particular it has no Netty dependency: Netty's read timeout is
 * recognised by class name ({@link #NETTY_READ_TIMEOUT_EXCEPTION}).
 */
public interface UpstreamFailureClassifier {

    /**
     * Fully qualified name of Netty's {@code ReadTimeoutException}, the read timeout Reactor Netty raises beneath
     * {@code WebClient}. Matched by name against a throwable's class and superclasses, so projects without Netty on
     * the classpath compile and classify the same way.
     */
    String NETTY_READ_TIMEOUT_EXCEPTION = "io.netty.handler.timeout.ReadTimeoutException";

    /**
     * Returns the upstream system this classifier serves.
     *
     * @return the one system whose failures this classifier understands; never {@code null}
     */
    UpstreamSystem system();

    /**
     * Classifies a throwable raised by a vendor call or by its re-authentication step.
     *
     * <p>Implementations first return {@link UpstreamFailureKind#UNAVAILABLE} when {@link #isUnavailable(Throwable)}
     * holds, even when the chain also carries an authentication or rate-limit signal: a timed-out or disconnected
     * request may already have been processed, so re-authenticating and retrying it could repeat a write. Only then
     * do they walk the {@link Throwable#getCause()} chain looking for the vendor's authentication and rate-limit
     * signals (for example a Salesforce {@code INVALID_SESSION_ID} fault, or an HTTP 401 or 429 with its
     * {@code Retry-After} value), and they return {@link UpstreamFailureKind#OTHER} for anything they do not
     * recognise. An implementation never throws: the executor relies on the result to decide between retrying,
     * failing and rethrowing the original throwable.
     *
     * @param failure the throwable exactly as the vendor call raised it; never {@code null}
     * @return the classification; never {@code null}, {@link UpstreamFailureKind#OTHER} when unrecognised
     */
    UpstreamFailureKind classify(Throwable failure);

    /**
     * Returns the upstream's own message for a failure, the {@code message} value of the 502, 429 and 503 bodies.
     *
     * <p>The cause chain is walked from {@code failure} itself, visiting each throwable once even when the chain
     * contains a cycle, and the first message that is neither {@code null} nor blank is returned exactly as the
     * vendor wrote it, untrimmed. When no throwable in the chain carries a message, the simple class name of
     * {@code failure} is returned (its full class name for an anonymous class, whose simple name is empty), so the
     * caller never receives a blank message.
     *
     * @param failure the classified throwable; never {@code null}
     * @return the first non-blank message in the cause chain, or the failure's class name when there is none
     * @throws NullPointerException if {@code failure} is {@code null}
     */
    default String upstreamMessage(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        Set<Throwable> visited = newIdentitySet();
        for (Throwable current = failure; current != null && visited.add(current); current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                return message;
            }
        }
        String simpleName = failure.getClass().getSimpleName();
        return simpleName.isEmpty() ? failure.getClass().getName() : simpleName;
    }

    /**
     * Tells whether a failure is a timeout or a connectivity failure, which is classified as unavailable for every
     * vendor and never retried.
     *
     * <p>The failure qualifies when the throwable itself or any throwable in its cause chain is one of:
     * <ul>
     *   <li>a timeout: {@link SocketTimeoutException}, {@link HttpTimeoutException} (including
     *       {@code HttpConnectTimeoutException}) or Netty's {@code ReadTimeoutException}, matched by
     *       {@link #NETTY_READ_TIMEOUT_EXCEPTION} on its class or a superclass;</li>
     *   <li>a connectivity failure: {@link UnknownHostException} (DNS failure) or any {@link SocketException},
     *       which covers {@code ConnectException} (connection refused), {@code NoRouteToHostException}, Netty's
     *       connect timeouts and a connection reset.</li>
     * </ul>
     * No other type qualifies. The walk visits each throwable once, so it terminates on a cyclic cause chain.
     *
     * @param failure the throwable to inspect; may be {@code null}
     * @return {@code true} when the cause chain holds a timeout or connectivity failure; {@code false} otherwise,
     *         and for {@code null}
     */
    static boolean isUnavailable(Throwable failure) {
        Set<Throwable> visited = newIdentitySet();
        for (Throwable current = failure; current != null && visited.add(current); current = current.getCause()) {
            if (isTimeoutOrConnectivityFailure(current)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tests one throwable, without its causes, against the unavailable types of {@link #isUnavailable}.
     *
     * @param throwable the throwable to test; never {@code null}
     * @return {@code true} when the throwable is itself a timeout or connectivity failure
     */
    private static boolean isTimeoutOrConnectivityFailure(Throwable throwable) {
        if (throwable instanceof SocketTimeoutException
                || throwable instanceof HttpTimeoutException
                || throwable instanceof UnknownHostException
                || throwable instanceof SocketException) {
            return true;
        }
        for (Class<?> type = throwable.getClass(); type != null; type = type.getSuperclass()) {
            if (NETTY_READ_TIMEOUT_EXCEPTION.equals(type.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates the visited set of a cause-chain walk. Identity, not {@code equals}, decides whether a throwable was
     * seen, because a vendor exception may override {@code equals} and a cause chain may loop back on itself.
     *
     * @return an empty, identity-based set
     */
    private static Set<Throwable> newIdentitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }
}
