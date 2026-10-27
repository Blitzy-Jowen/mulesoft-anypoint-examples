/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_json_data_to_a_amqp_queue.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.xnio.Options;

/**
 * Lets idle threads retire as the Mule 3.8 runtime let them, so the thread count falls back after a burst of
 * requests instead of keeping every thread the burst started.
 *
 * <p>The HTTP listener ran its flows on a worker pool built from the default threading profile, which
 * {@code DefaultHttpListenerConfig} copies with {@code maxThreadsActive} 128: {@code maxThreadsIdle} 1 and
 * {@code threadTTL} 60000 ms, which {@code DefaultThreadPoolFactory} turns into a core size of 1 and a keep-alive of
 * 60 s. Undertow sets the core size of its XNIO worker pool to the pool's maximum, so none of its workers ever
 * retires. {@link #undertowWorkerRetirement()} gives the pool Mule's core size and keep-alive and leaves the maximum
 * at Undertow's default. Below its maximum the XNIO pool starts a thread for a task no idle worker can take instead
 * of queueing it, so the lower core size does not serialise concurrent requests.
 *
 * <p>amqp-client dispatches consumer callbacks, among them the reply each {@code sendAndReceive} receives on its
 * temporary reply queue, on an executor it creates per connection when it is given none: a fixed pool of one thread
 * per available processor, whose threads never retire. Boot's {@link CachingConnectionFactory} gives it none, so
 * {@link ConsumerExecutorInstaller} hands the factory a pool of the same size with an unbounded queue, whose
 * threads retire after the same 60 s.
 *
 * <p>Both keep-alives are Mule's 60 s and nothing shorter: threads retire as the original's did, no sooner.
 */
@Configuration(proxyBeanMethods = false)
public class IdleThreadRetirementConfig {

    /** Mule's default {@code threadTTL}: how long an idle thread above the core size lives, in milliseconds. */
    static final int THREAD_TTL_MILLIS = 60_000;

    /** Mule's default {@code maxThreadsIdle}, the core size of the HTTP listener's worker pool. */
    static final int WORKER_CORE_THREADS = 1;

    /**
     * amqp-client's {@code DEFAULT_NUM_THREADS}, the size of the consumer pool it would create itself: one thread per
     * processor, counted as amqp-client counts them, from the system property
     * {@code rabbitmq.amqp.client.availableProcessors} when it is set.
     */
    static final int CONSUMER_THREADS = Math.max(1, Integer.getInteger(
            "rabbitmq.amqp.client.availableProcessors", Runtime.getRuntime().availableProcessors()));

    /** Name prefix of the consumer dispatch threads, followed by a sequence number. */
    static final String CONSUMER_THREAD_NAME_PREFIX = "amqp-consumer-";

    /**
     * Gives the XNIO worker pool of the embedded Undertow Mule's core size of 1 and keep-alive of 60 s. Undertow
     * applies worker options after its own core-equals-maximum default, so these take precedence; the maximum is
     * left as Undertow sets it.
     *
     * @return the customizer Boot applies to its Undertow servlet web server factory
     */
    @Bean
    public WebServerFactoryCustomizer<UndertowServletWebServerFactory> undertowWorkerRetirement() {
        return workerRetirementCustomizer(THREAD_TTL_MILLIS);
    }

    /**
     * Installs the consumer dispatch pool on every {@link CachingConnectionFactory} bean, Boot's included, and shuts
     * the pool down when the context closes. Static, so the post-processor exists before the connection factory
     * is created. The pool is held by the post-processor rather than registered as an {@code Executor} bean, which
     * would make Boot back off its {@code applicationTaskExecutor}.
     *
     * @return the post-processor owning the pool
     */
    @Bean
    public static ConsumerExecutorInstaller amqpConsumerExecutorInstaller() {
        return new ConsumerExecutorInstaller(consumerExecutor(CONSUMER_THREADS, THREAD_TTL_MILLIS));
    }

    /**
     * An Undertow customizer setting the worker pool's core size to {@link #WORKER_CORE_THREADS} and its keep-alive
     * to {@code keepAliveMillis}.
     *
     * @param keepAliveMillis how long an idle worker above the core size lives, in milliseconds; positive
     * @return the customizer
     */
    static WebServerFactoryCustomizer<UndertowServletWebServerFactory> workerRetirementCustomizer(
            int keepAliveMillis) {
        return factory -> factory.addBuilderCustomizers(builder -> builder
                .setWorkerOption(Options.WORKER_TASK_CORE_THREADS, WORKER_CORE_THREADS)
                .setWorkerOption(Options.WORKER_TASK_KEEPALIVE, keepAliveMillis));
    }

    /**
     * A consumer dispatch pool with the capacity of amqp-client's own: {@code threads} threads at most and an
     * unbounded queue, so no task is ever rejected. Unlike amqp-client's pool, every thread retires once it has been
     * idle for {@code keepAliveMillis}, and the next task starts a new one.
     *
     * @param threads         the core and maximum number of threads; positive
     * @param keepAliveMillis how long an idle thread lives, in milliseconds; positive
     * @return the pool, with no thread started yet
     * @throws IllegalArgumentException if {@code threads} or {@code keepAliveMillis} is not positive
     */
    static ThreadPoolExecutor consumerExecutor(int threads, long keepAliveMillis) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(threads, threads, keepAliveMillis,
                TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                new CustomizableThreadFactory(CONSUMER_THREAD_NAME_PREFIX));
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /**
     * Hands one consumer dispatch pool to each {@link CachingConnectionFactory} before its initialization, so the
     * factory passes it to amqp-client with its first connection, and shuts the pool down after every such factory
     * has been destroyed and has closed its connections. Only the executor changes: the factory keeps every setting
     * Boot copied from {@code spring.rabbitmq.*} and still connects lazily, on first use.
     */
    public static final class ConsumerExecutorInstaller
            implements BeanPostProcessor, BeanFactoryAware, BeanNameAware, DisposableBean {

        private final ExecutorService executor;

        private ConfigurableBeanFactory beanFactory;

        private String beanName;

        /**
         * @param executor the pool to install and to shut down when the context closes
         */
        ConsumerExecutorInstaller(ExecutorService executor) {
            this.executor = executor;
        }

        /**
         * @param beanFactory the factory that owns this post-processor and the connection factories it processes
         * @throws IllegalArgumentException if {@code beanFactory} cannot record the destruction order this class
         *                                  relies on, which every application context's bean factory can
         */
        @Override
        public void setBeanFactory(BeanFactory beanFactory) {
            if (!(beanFactory instanceof ConfigurableBeanFactory configurable)) {
                throw new IllegalArgumentException("ConsumerExecutorInstaller needs a ConfigurableBeanFactory to"
                        + " order its shutdown after the connection factories, but got " + beanFactory);
            }
            this.beanFactory = configurable;
        }

        /**
         * @param name this post-processor's bean name
         */
        @Override
        public void setBeanName(String name) {
            this.beanName = name;
        }

        /**
         * Sets the pool as the executor of a {@link CachingConnectionFactory}, which also passes it to the factory's
         * publisher connection factory, and registers the factory as dependent on this bean, so the context destroys
         * the factory before it shuts the pool down. Other beans are returned untouched.
         *
         * @param bean the bean about to be initialized
         * @param name its bean name
         * @return {@code bean}
         */
        @Override
        public Object postProcessBeforeInitialization(Object bean, String name) {
            if (bean instanceof CachingConnectionFactory connectionFactory) {
                connectionFactory.setExecutor(executor);
                beanFactory.registerDependentBean(beanName, name);
            }
            return bean;
        }

        /**
         * Shuts the pool down once the connection factories are destroyed: callbacks already queued still run, and
         * idle threads end at once.
         */
        @Override
        public void destroy() {
            executor.shutdown();
        }
    }
}
