/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.client;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
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
 * so no vendor can retry them: a request that timed out or lost its connection may already have been processed,
 * and it is never submitted again. The only resubmission is the executor's single retry after an authentication
 * rejection, a request the vendor refused before processing it.
 *
 * <p>The interface depends on the JDK and its SC-9 siblings alone so that every connector project can copy it with
 * only its {@code package} line changed. In particular it has no Netty or Reactor Netty dependency: the read timeout
 * and the connection closures those transports raise beneath {@code WebClient} are recognised by class name
 * ({@link #NETTY_READ_TIMEOUT_EXCEPTION}, {@link #REACTOR_PREMATURE_CLOSE_EXCEPTION},
 * {@link #REACTOR_ABORTED_EXCEPTION}, {@link #NETTY_PREMATURE_CHANNEL_CLOSURE_EXCEPTION} and
 * {@link #NETTY_NATIVE_IO_EXCEPTION}).
 */
public interface UpstreamFailureClassifier {

    /**
     * Fully qualified name of Netty's {@code ReadTimeoutException}, the read timeout Reactor Netty raises beneath
     * {@code WebClient}. Matched by name against a throwable's class and superclasses, so projects without Netty on
     * the classpath compile and classify the same way.
     */
    String NETTY_READ_TIMEOUT_EXCEPTION = "io.netty.handler.timeout.ReadTimeoutException";

    /**
     * Fully qualified name of Reactor Netty's {@code PrematureCloseException}, the {@code IOException} (not a
     * {@code SocketException}, and often without any cause) that {@code WebClient} carries when the connection closes
     * before or during the response ("Connection prematurely closed BEFORE response", "... DURING response").
     * Matched by name, as {@link #NETTY_READ_TIMEOUT_EXCEPTION} is.
     */
    String REACTOR_PREMATURE_CLOSE_EXCEPTION = "reactor.netty.http.client.PrematureCloseException";

    /**
     * Fully qualified name of Reactor Netty's {@code AbortedException}, the {@code RuntimeException} raised when the
     * connection has closed before the request could be written ("Connection has been closed BEFORE send
     * operation"). Matched by name, as {@link #NETTY_READ_TIMEOUT_EXCEPTION} is.
     */
    String REACTOR_ABORTED_EXCEPTION = "reactor.netty.channel.AbortedException";

    /**
     * Fully qualified name of Netty's {@code PrematureChannelClosureException}, the {@code RuntimeException} its HTTP
     * client codec raises when the connection closes before the response headers have arrived ("Connection closed
     * before received headers"). Matched by name, as {@link #NETTY_READ_TIMEOUT_EXCEPTION} is.
     */
    String NETTY_PREMATURE_CHANNEL_CLOSURE_EXCEPTION = "io.netty.handler.codec.PrematureChannelClosureException";

    /**
     * Binary name, as {@link Class#getName()} returns it, of Netty's {@code Errors.NativeIoException}: the
     * {@code IOException} (not a {@code SocketException}) the native epoll and kqueue transports raise when a socket
     * operation fails, for example a connection reset by the peer ("recvAddress(..) failed: Connection reset by
     * peer"). Matched by name, as {@link #NETTY_READ_TIMEOUT_EXCEPTION} is.
     */
    String NETTY_NATIVE_IO_EXCEPTION = "io.netty.channel.unix.Errors$NativeIoException";

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
     * vendor wrote it, untrimmed. When no throwable in the chain carries a message, a neutral description that names
     * the upstream by its {@link UpstreamSystem#displayName()} is returned instead, so the caller never receives a
     * blank message and the caller-facing body never names an internal class:
     * <ul>
     *   <li>{@code "<upstream> did not respond in time"} when the chain holds a timeout: a
     *       {@link SocketTimeoutException}, an {@link HttpTimeoutException} (including
     *       {@code HttpConnectTimeoutException}) or Netty's {@code ReadTimeoutException}, matched by
     *       {@link #NETTY_READ_TIMEOUT_EXCEPTION} on its class or a superclass;</li>
     *   <li>{@code "<upstream> is unreachable"} for any other failure {@link #isUnavailable} accepts;</li>
     *   <li>{@code "<upstream> call failed"} for every other failure.</li>
     * </ul>
     * The description states no duration, because each vendor's timeouts are configurable. Should {@link #system()}
     * break its contract and return {@code null}, the upstream is named {@code "The upstream system"}.
     *
     * @param failure the classified throwable; never {@code null}
     * @return the first non-blank message in the cause chain, or the neutral description when there is none; never
     *         {@code null} or blank
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
        return neutralDescription(system(), failure);
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
     *       connect timeouts and a connection reset reported by the JDK's sockets;</li>
     *   <li>a connection the transport found closed or reset: {@link ClosedChannelException} (Netty's stackless
     *       variant included), Reactor Netty's {@code PrematureCloseException} and {@code AbortedException}, Netty's
     *       {@code PrematureChannelClosureException} and the native transports' {@code NativeIoException}, the last
     *       four matched by {@link #REACTOR_PREMATURE_CLOSE_EXCEPTION}, {@link #REACTOR_ABORTED_EXCEPTION},
     *       {@link #NETTY_PREMATURE_CHANNEL_CLOSURE_EXCEPTION} and {@link #NETTY_NATIVE_IO_EXCEPTION} on its class or
     *       a superclass.</li>
     * </ul>
     * No other type qualifies, whatever its message or simple name: a type is recognised by itself or by its fully
     * qualified name alone. The walk visits each throwable once, so it terminates on a cyclic cause chain.
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
                || throwable instanceof SocketException
                || throwable instanceof ClosedChannelException) {
            return true;
        }
        for (Class<?> type = throwable.getClass(); type != null; type = type.getSuperclass()) {
            if (isTransportFailureName(type.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether a fully qualified class name is one of the Netty and Reactor Netty failure types that
     * {@link #isUnavailable} recognises by name.
     *
     * @param className the name {@link Class#getName()} returned; never {@code null}
     * @return {@code true} for {@link #NETTY_READ_TIMEOUT_EXCEPTION}, {@link #REACTOR_PREMATURE_CLOSE_EXCEPTION},
     *         {@link #REACTOR_ABORTED_EXCEPTION}, {@link #NETTY_PREMATURE_CHANNEL_CLOSURE_EXCEPTION} and
     *         {@link #NETTY_NATIVE_IO_EXCEPTION}; {@code false} for every other name
     */
    private static boolean isTransportFailureName(String className) {
        return switch (className) {
            case NETTY_READ_TIMEOUT_EXCEPTION, REACTOR_PREMATURE_CLOSE_EXCEPTION, REACTOR_ABORTED_EXCEPTION,
                    NETTY_PREMATURE_CHANNEL_CLOSURE_EXCEPTION, NETTY_NATIVE_IO_EXCEPTION -> true;
            default -> false;
        };
    }

    /**
     * Describes a failure whose cause chain carries no message, naming the upstream and what went wrong but no
     * internal type, as {@link #upstreamMessage} documents.
     *
     * @param system  the upstream to name; {@code null} names it {@code "The upstream system"}
     * @param failure the classified throwable; never {@code null}
     * @return the timeout, unreachable or generic description of {@link #upstreamMessage}
     */
    private static String neutralDescription(UpstreamSystem system, Throwable failure) {
        String name = system == null ? "The upstream system" : system.displayName();
        if (holdsTimeout(failure)) {
            return name + " did not respond in time";
        }
        if (isUnavailable(failure)) {
            return name + " is unreachable";
        }
        return name + " call failed";
    }

    /**
     * Tells whether the throwable itself or any throwable in its cause chain is a timeout. The walk visits each
     * throwable once, so it terminates on a cyclic cause chain.
     *
     * @param failure the throwable to inspect; never {@code null}
     * @return {@code true} when the cause chain holds a timeout of {@link #upstreamMessage}
     */
    private static boolean holdsTimeout(Throwable failure) {
        Set<Throwable> visited = newIdentitySet();
        for (Throwable current = failure; current != null && visited.add(current); current = current.getCause()) {
            if (isTimeout(current)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tests one throwable, without its causes, against the timeout types of {@link #isUnavailable}.
     *
     * @param throwable the throwable to test; never {@code null}
     * @return {@code true} for a {@link SocketTimeoutException}, an {@link HttpTimeoutException} or a throwable whose
     *         class or a superclass is named {@link #NETTY_READ_TIMEOUT_EXCEPTION}
     */
    private static boolean isTimeout(Throwable throwable) {
        if (throwable instanceof SocketTimeoutException || throwable instanceof HttpTimeoutException) {
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
