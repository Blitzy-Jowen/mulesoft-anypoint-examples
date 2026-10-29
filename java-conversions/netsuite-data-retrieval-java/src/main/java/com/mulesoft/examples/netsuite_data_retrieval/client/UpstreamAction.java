/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.netsuite_data_retrieval.client;

/**
 * A step run against an upstream system that returns no value (shared component SC-9).
 *
 * <p>{@code UpstreamCallExecutor} takes it in two roles: as the {@code reauthenticate} step it runs once after
 * an authentication failure, before retrying the call (a session re-login, a token-cache eviction), and as
 * the vendor call itself where that call is void ({@code UpstreamCallExecutor.run}).
 *
 * <p>{@link #run()} may throw any checked exception, so implementations let the vendor's exception
 * propagate as thrown and never wrap it: the executor classifies that exact throwable and, when it falls in
 * none of the approved failure modes, rethrows the same instance to the caller's original exception branch.
 *
 * <p>The interface depends on the JDK only and is copied unchanged, apart from its {@code package} line,
 * into every connector project.
 */
@FunctionalInterface
public interface UpstreamAction {

    /**
     * Runs the step.
     *
     * @throws Exception the vendor's own exception, unwrapped
     */
    void run() throws Exception;
}
