/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_rest_api.config;

import org.xnio.OptionMap;
import org.xnio.Options;

/**
 * Gives the listener's request worker threads the idle policy of the original listener, so workers started for a
 * burst of concurrent requests are retired once they have been idle long enough instead of being kept for the life
 * of the process.
 *
 * <p>The original {@code http:listener-config} declares no {@code worker-threading-profile}
 * ({@code proxying-a-rest-api.xml:3}), so Mule 3.8 gave it the default threading profile with
 * {@code maxThreadsActive} raised to 128 ({@code DefaultHttpListenerConfig} lines 142-146). That profile keeps
 * {@code maxThreadsIdle} 1 and {@code threadTTL} 60000 ms ({@code DefaultThreadingProfileConfig} lines 27 and 37),
 * and Mule builds the worker pool as a {@code ThreadPoolExecutor} with one core thread, a 60 s keep-alive and a
 * {@code SynchronousQueue} ({@code DefaultThreadPoolFactory} lines 34-41 and 116-118): it starts a thread for every
 * concurrent request up to the maximum and retires each thread above the first after 60 s idle.
 *
 * <p>Undertow instead creates its XNIO worker pool with as many core threads as maximum threads, so a worker, once
 * started, is never retired. {@link #workerOptions()} sets the pool's core size to 1 and its keep-alive to 60000 ms,
 * the original's values. The worker pool still starts a new thread for each request that finds every existing worker
 * busy, up to its maximum, before it queues any request, so concurrency is unchanged. The maximum stays at Undertow's
 * own default; this class does not set it. The listener runs on the worker {@link RequestFramingGuardConfig} creates
 * with Undertow's own option map, and Undertow ignores worker options set on its builder once a worker is given, so
 * that class applies these options to the worker it creates, after Undertow's core and maximum values, which they
 * override. When several idle workers time out at the same moment the pool may retire the last one as well; the next
 * request then starts a worker, as it would above the core.
 */
public final class ListenerWorkerThreadingConfig {

    /** Worker threads kept while idle: the original listener's {@code maxThreadsIdle}. */
    public static final int CORE_WORKER_THREADS = 1;

    /**
     * Milliseconds a worker above {@link #CORE_WORKER_THREADS} may stay idle before it is retired: the original
     * listener's {@code threadTTL}.
     */
    public static final int WORKER_KEEP_ALIVE_MILLIS = 60_000;

    private ListenerWorkerThreadingConfig() {
    }

    /**
     * The XNIO worker options {@link Options#WORKER_TASK_CORE_THREADS} set to {@link #CORE_WORKER_THREADS} and
     * {@link Options#WORKER_TASK_KEEPALIVE} set to {@link #WORKER_KEEP_ALIVE_MILLIS}.
     *
     * @return the options {@link RequestFramingGuardConfig} adds last to the option map of the listener's worker
     */
    public static OptionMap workerOptions() {
        return OptionMap.builder()
                .set(Options.WORKER_TASK_CORE_THREADS, CORE_WORKER_THREADS)
                .set(Options.WORKER_TASK_KEEPALIVE, WORKER_KEEP_ALIVE_MILLIS)
                .getMap();
    }
}
