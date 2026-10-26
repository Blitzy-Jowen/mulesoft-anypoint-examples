/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_data_retrieval.client;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.mulesoft.examples.salesforce_data_retrieval.client.UpstreamFailureKind.Category;
import com.mulesoft.examples.salesforce_data_retrieval.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.salesforce_data_retrieval.exception.UpstreamRateLimitException;
import com.mulesoft.examples.salesforce_data_retrieval.exception.UpstreamUnavailableException;

/**
 * Runs every vendor call of a connector project and applies the approved connector failure handling (shared
 * component SC-9). This class is the only place those additions exist; no client adapter retries, wraps or
 * translates a vendor failure itself.
 *
 * <p>A failure of the call, or of its re-authentication step, is handed to the {@link UpstreamFailureClassifier}
 * registered for the call's {@link UpstreamSystem}, and the resulting {@link UpstreamFailureKind} decides:
 * <table>
 *   <caption>Behaviour per failure category</caption>
 *   <tr><th>Category</th><th>Behaviour</th><th>Raised</th></tr>
 *   <tr><td>{@code AUTHENTICATION}</td>
 *       <td>run {@code reauthenticate}, then retry the call once; an authentication failure of either step ends
 *           the attempt, any other failure of either step is handled by the rows below; without a
 *           {@code reauthenticate} step the attempt ends at once</td>
 *       <td>{@link UpstreamAuthenticationException} (502 in HTTP-triggered projects)</td></tr>
 *   <tr><td>{@code RATE_LIMIT}</td>
 *       <td>no retry</td>
 *       <td>{@link UpstreamRateLimitException} carrying the vendor's {@code Retry-After} (429)</td></tr>
 *   <tr><td>{@code UNAVAILABLE}</td>
 *       <td>no retry, ever: a timed-out or interrupted request may already have been processed, and repeating it
 *           could submit a write twice</td>
 *       <td>{@link UpstreamUnavailableException} (503)</td></tr>
 *   <tr><td>{@code OTHER}</td>
 *       <td>the original throwable is rethrown, the same instance and unwrapped, so the example's own exception
 *           branch still receives the vendor type</td>
 *       <td>the vendor's exception</td></tr>
 * </table>
 *
 * <p>Each of the three upstream exceptions is preceded by one ERROR log line naming the upstream, the category and
 * the upstream's message, with the vendor failure's stack trace; file- and poll-triggered projects, which have no
 * caller to answer, rely on that line. An upstream exception thrown by a callback, for example by a nested
 * executor, passes through unclassified. {@link Error}s are never caught. A caught {@link InterruptedException}
 * restores the thread's interrupt flag before it is classified.
 *
 * <p>The executor is stateless apart from its immutable classifier registry, so one instance serves concurrent
 * calls.
 *
 * <p>This is the reference copy. Every other connector project carries an identical copy in which only the
 * {@code package} line differs, so the class depends on nothing but the JDK, SLF4J, Spring's {@link Component} and
 * its SC-9 siblings.
 */
@Component
public class UpstreamCallExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(UpstreamCallExecutor.class);

    private final Map<UpstreamSystem, UpstreamFailureClassifier> classifiers;

    /**
     * Registers one classifier per upstream system.
     *
     * @param classifiers every {@link UpstreamFailureClassifier} bean of the application; {@code null} or empty
     *                    registers none
     * @throws NullPointerException  if the list holds {@code null} or a classifier's {@code system()} is
     *                               {@code null}
     * @throws IllegalStateException if two classifiers serve the same system
     */
    public UpstreamCallExecutor(List<UpstreamFailureClassifier> classifiers) {
        Map<UpstreamSystem, UpstreamFailureClassifier> bySystem = new EnumMap<>(UpstreamSystem.class);
        if (classifiers != null) {
            for (UpstreamFailureClassifier classifier : classifiers) {
                Objects.requireNonNull(classifier, "classifiers must not contain null");
                UpstreamSystem system = Objects.requireNonNull(classifier.system(),
                        () -> classifier.getClass().getName() + ".system() returned null");
                UpstreamFailureClassifier existing = bySystem.putIfAbsent(system, classifier);
                if (existing != null) {
                    throw new IllegalStateException("Duplicate UpstreamFailureClassifier for " + system + ": "
                            + existing.getClass().getName() + " and " + classifier.getClass().getName());
                }
            }
        }
        this.classifiers = Collections.unmodifiableMap(bySystem);
    }

    /**
     * Performs a vendor call under the failure handling described on the class.
     *
     * @param system         the upstream the call reaches; a classifier must be registered for it
     * @param call           the vendor call; invoked once, or twice when the first attempt fails for
     *                       authentication and {@code reauthenticate} succeeds
     * @param reauthenticate the step that renews the session or token before the single retry; {@code null}
     *                       when the vendor has none, which makes an authentication failure final
     * @param <T>            the type of the value the call returns
     * @return the value of the first successful attempt
     * @throws UpstreamAuthenticationException authentication still failed after re-authentication, or could not
     *                                         be renewed
     * @throws UpstreamRateLimitException      the vendor throttled the call or the re-authentication
     * @throws UpstreamUnavailableException    the vendor timed out or could not be reached
     * @throws IllegalStateException           no classifier is registered for {@code system}; nothing is invoked
     * @throws Exception                       the vendor's own exception, unchanged, for any other failure
     */
    public <T> T call(UpstreamSystem system, UpstreamCall<T> call, UpstreamAction reauthenticate) throws Exception {
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(call, "call");
        UpstreamFailureClassifier classifier = classifierFor(system);
        try {
            return call.call();
        } catch (UpstreamAuthenticationException | UpstreamRateLimitException | UpstreamUnavailableException e) {
            throw e;
        } catch (Exception failure) {
            UpstreamFailureKind kind = classify(classifier, failure);
            if (kind.category() != Category.AUTHENTICATION) {
                throw translate(system, classifier, kind, failure);
            }
            return reauthenticateAndRetry(system, classifier, call, reauthenticate, failure);
        }
    }

    /**
     * Performs a vendor call that returns no value, with exactly the semantics of
     * {@link #call(UpstreamSystem, UpstreamCall, UpstreamAction)}.
     *
     * @param system         the upstream the action reaches; a classifier must be registered for it
     * @param action         the vendor call
     * @param reauthenticate the step that renews the session or token before the single retry, or {@code null}
     * @throws Exception as {@link #call(UpstreamSystem, UpstreamCall, UpstreamAction)} throws
     */
    public void run(UpstreamSystem system, UpstreamAction action, UpstreamAction reauthenticate) throws Exception {
        Objects.requireNonNull(action, "action");
        call(system, () -> {
            action.run();
            return null;
        }, reauthenticate);
    }

    private UpstreamFailureClassifier classifierFor(UpstreamSystem system) {
        UpstreamFailureClassifier classifier = classifiers.get(system);
        if (classifier == null) {
            throw new IllegalStateException("No UpstreamFailureClassifier registered for " + system);
        }
        return classifier;
    }

    /**
     * Handles a first attempt rejected for authentication: one re-authentication, one retry, and no further
     * attempt whatever either step yields.
     */
    private <T> T reauthenticateAndRetry(UpstreamSystem system, UpstreamFailureClassifier classifier,
            UpstreamCall<T> call, UpstreamAction reauthenticate, Exception firstFailure) throws Exception {
        if (reauthenticate == null) {
            throw authenticationFailure(system, classifier, firstFailure);
        }
        LOG.warn("Upstream {} rejected the session [{}]; re-authenticating and retrying once",
                system.displayName(), Category.AUTHENTICATION);
        try {
            reauthenticate.run();
        } catch (UpstreamAuthenticationException | UpstreamRateLimitException | UpstreamUnavailableException e) {
            throw e;
        } catch (Exception reauthenticationFailure) {
            throw translate(system, classifier, classify(classifier, reauthenticationFailure),
                    reauthenticationFailure);
        }
        try {
            return call.call();
        } catch (UpstreamAuthenticationException | UpstreamRateLimitException | UpstreamUnavailableException e) {
            throw e;
        } catch (Exception retryFailure) {
            throw translate(system, classifier, classify(classifier, retryFailure), retryFailure);
        }
    }

    /**
     * Classifies a caught failure, first restoring the interrupt flag an {@link InterruptedException} cleared.
     * A classifier that breaks its contract by returning {@code null} is read as {@code OTHER}, so the vendor
     * failure is rethrown rather than lost.
     */
    private static UpstreamFailureKind classify(UpstreamFailureClassifier classifier, Exception failure) {
        restoreInterrupt(failure);
        UpstreamFailureKind kind = classifier.classify(failure);
        return kind != null ? kind : UpstreamFailureKind.OTHER;
    }

    private static void restoreInterrupt(Throwable failure) {
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Returns the exception to throw for a classified failure: one of the three upstream exceptions, or for
     * {@code OTHER} the failure itself, unwrapped and unlogged, for the caller's original exception branch.
     */
    private static Exception translate(UpstreamSystem system, UpstreamFailureClassifier classifier,
            UpstreamFailureKind kind, Exception failure) {
        return switch (kind.category()) {
            case AUTHENTICATION -> authenticationFailure(system, classifier, failure);
            case RATE_LIMIT -> new UpstreamRateLimitException(system,
                    logFailure(system, classifier, Category.RATE_LIMIT, failure), kind.retryAfter(), failure);
            case UNAVAILABLE -> new UpstreamUnavailableException(system,
                    logFailure(system, classifier, Category.UNAVAILABLE, failure), failure);
            case OTHER -> failure;
        };
    }

    private static UpstreamAuthenticationException authenticationFailure(UpstreamSystem system,
            UpstreamFailureClassifier classifier, Exception failure) {
        return new UpstreamAuthenticationException(system,
                logFailure(system, classifier, Category.AUTHENTICATION, failure), failure);
    }

    /**
     * Writes the ERROR line that precedes every upstream exception and returns the upstream's message. The
     * message is the vendor's fault text, never a credential.
     */
    private static String logFailure(UpstreamSystem system, UpstreamFailureClassifier classifier, Category category,
            Exception failure) {
        String message = classifier.upstreamMessage(failure);
        LOG.error("Upstream {} call failed [{}]: {}", system.displayName(), category, message, failure);
        return message;
    }
}
