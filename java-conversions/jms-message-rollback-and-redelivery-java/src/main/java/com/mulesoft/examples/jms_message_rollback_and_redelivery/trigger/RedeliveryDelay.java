/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.jms_message_rollback_and_redelivery.trigger;

import com.mulesoft.examples.jms_message_rollback_and_redelivery.config.RedeliveryProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Pause before a failed delivery of queue {@code in} is rolled back, reproducing the client-side timing of
 * the original ActiveMQ {@code RedeliveryPolicy} ({@code jms-redelivery.xml}, lines 6-12):
 * {@code initialRedeliveryDelay} 5000 ms, {@code redeliveryDelay} 2000 ms, {@code maximumRedeliveries} 5,
 * no exponential back-off.
 *
 * <p>Artemis has no separate initial redelivery delay, so address {@code in} runs with
 * {@code redelivery-delay} 0 and {@code max-delivery-attempts} 6, and {@code InQueueListener} calls
 * {@link #pauseBeforeRollback(int)} while the failing transacted session is still open, before it rethrows
 * and the container rolls back. Delivery 2 therefore starts 5000 ms after delivery 1 failed and every later
 * delivery 2000 ms after the previous one. As with ActiveMQ's default blocking redelivery, the consumer that
 * holds the failing message takes no other message during the pause; the other consumers keep running.
 *
 * <p>The pause logs nothing at INFO: the original logs nothing for it, and the flow's three INFO messages
 * belong to {@code service/RedeliveryService}.
 */
@Component
public class RedeliveryDelay {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedeliveryDelay.class);

    /** Blocks the calling thread; injectable so tests can record the requested pauses without waiting. */
    @FunctionalInterface
    public interface Sleeper {

        /**
         * Blocks the calling thread.
         *
         * @param millis the pause in milliseconds
         * @throws InterruptedException when the thread is interrupted during the pause
         */
        void sleep(long millis) throws InterruptedException;
    }

    private final RedeliveryProperties properties;

    private final Sleeper sleeper;

    /**
     * Creates the delay on the bound {@code redelivery.*} settings, pausing with {@link Thread#sleep(long)}.
     *
     * @param properties the redelivery timing, registered by {@code @ConfigurationPropertiesScan}
     */
    @Autowired
    public RedeliveryDelay(RedeliveryProperties properties) {
        this(properties, Thread::sleep);
    }

    /**
     * Creates the delay with an explicit sleeper.
     *
     * @param properties the redelivery timing
     * @param sleeper    performs each pause
     */
    public RedeliveryDelay(RedeliveryProperties properties, Sleeper sleeper) {
        this.properties = properties;
        this.sleeper = sleeper;
    }

    /**
     * Returns the pause that precedes the redelivery of a failed delivery. There is no back-off multiplier,
     * because the original sets {@code useExponentialBackOff} to {@code false}.
     *
     * @param deliveryCount the failed delivery's {@code JMSXDeliveryCount}, counted from 1
     * @return {@code initialRedeliveryDelay} for the first delivery, {@code redeliveryDelay} for every later one
     */
    public long delayFor(int deliveryCount) {
        return deliveryCount <= 1 ? properties.getInitialRedeliveryDelay() : properties.getRedeliveryDelay();
    }

    /**
     * Pauses the calling listener thread before its failed delivery is rolled back. Returns at once when the
     * delivery count exceeds {@code maximumRedeliveries}, because no redelivery follows, and when the
     * configured delay is not positive.
     *
     * <p>An interrupt ends the pause early: the thread's interrupt flag is restored and the method returns
     * normally, so the caller still rethrows its own exception unchanged and the rollback proceeds.
     *
     * @param deliveryCount the failed delivery's {@code JMSXDeliveryCount}, counted from 1
     */
    public void pauseBeforeRollback(int deliveryCount) {
        if (deliveryCount > properties.getMaximumRedeliveries()) {
            return;
        }
        long millis = delayFor(deliveryCount);
        // A negative value would make Thread.sleep throw and replace the caller's exception.
        if (millis <= 0) {
            return;
        }
        LOGGER.debug("Pausing {} ms before rollback of delivery {}", millis, deliveryCount);
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.debug("Pause before rollback of delivery {} interrupted", deliveryCount);
        }
    }
}
