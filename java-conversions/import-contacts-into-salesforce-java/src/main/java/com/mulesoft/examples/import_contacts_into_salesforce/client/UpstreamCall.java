/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_salesforce.client;

/**
 * One vendor call that returns a value, handed to
 * {@code UpstreamCallExecutor.call(UpstreamSystem, UpstreamCall, UpstreamAction)}.
 *
 * <p>Shared component SC-9: the interface depends on the JDK only and is copied unchanged, apart from its
 * {@code package} line, into every connector project.
 *
 * <p>{@link #call()} declares {@code throws Exception} so that checked vendor exceptions (for example the
 * Salesforce WSC {@code ConnectionException}, {@code ApiFault} and {@code LoginFault}, or Spring WS
 * {@code WebServiceIOException}) reach the executor as they were thrown. An implementation must not wrap
 * them: the executor classifies the original throwable and, for a failure that is none of the approved
 * failure modes, rethrows that same instance, so the project's original exception branch still sees the
 * vendor type.
 *
 * <p>The executor may invoke {@link #call()} twice: once, and once more after a successful
 * re-authentication when the first attempt was rejected for authentication. An implementation should
 * therefore perform the vendor call and nothing else, so that a retry repeats no other side effect.
 *
 * @param <T> the type of the value the vendor call returns
 */
@FunctionalInterface
public interface UpstreamCall<T> {

    /**
     * Performs the vendor call.
     *
     * @return the value the vendor call returned
     * @throws Exception any exception the vendor call raised, unwrapped
     */
    T call() throws Exception;
}
