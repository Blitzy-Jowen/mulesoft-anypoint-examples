/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.jms_message_rollback_and_redelivery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redelivery timing of the original ActiveMQ {@code RedeliveryPolicy} bean
 * ({@code jms-redelivery.xml}, lines 6-12), bound from the {@code redelivery.*} keys.
 * <p>
 * Artemis has no separate initial redelivery delay, so the broker's address settings for queue
 * {@code in} use {@code redelivery-delay} 0 and {@code max-delivery-attempts} 6, and
 * {@code trigger/RedeliveryDelay} pauses inside the failing transaction, before the rollback, for
 * the delays held here. Together they reproduce the original gaps: 5000 ms after the first failed
 * delivery and 2000 ms after each later one.
 * <p>
 * The original's {@code backOffMultiplier} 2 has no counterpart: it is inert because the original
 * sets {@code useExponentialBackOff} to {@code false}.
 * <p>
 * Registered by {@code @ConfigurationPropertiesScan} on the application class, not by component
 * scanning.
 */
@ConfigurationProperties(prefix = "redelivery")
public class RedeliveryProperties {

    /**
     * Pause in milliseconds before rolling back the first delivery ({@code JMSXDeliveryCount} 1).
     * Mirrors {@code initialRedeliveryDelay} 5000 ({@code jms-redelivery.xml}, line 8); read by
     * {@code trigger/RedeliveryDelay}.
     */
    private long initialRedeliveryDelay = 5000;

    /**
     * Pause in milliseconds before rolling back every later delivery. Mirrors
     * {@code redeliveryDelay} 2000 ({@code jms-redelivery.xml}, line 9); read by
     * {@code trigger/RedeliveryDelay}.
     */
    private long redeliveryDelay = 2000;

    /**
     * Number of redeliveries after the first delivery. Mirrors {@code maximumRedeliveries} 5
     * ({@code jms-redelivery.xml}, line 7) and matches the broker's {@code max-delivery-attempts} 6
     * (one delivery plus five redeliveries), which enforces the limit. {@code trigger/RedeliveryDelay}
     * reads it only to skip the pause when no redelivery follows; in the flow deliveries 1-4 fail and
     * delivery 5 succeeds.
     */
    private int maximumRedeliveries = 5;

    public long getInitialRedeliveryDelay() {
        return initialRedeliveryDelay;
    }

    public void setInitialRedeliveryDelay(long initialRedeliveryDelay) {
        this.initialRedeliveryDelay = initialRedeliveryDelay;
    }

    public long getRedeliveryDelay() {
        return redeliveryDelay;
    }

    public void setRedeliveryDelay(long redeliveryDelay) {
        this.redeliveryDelay = redeliveryDelay;
    }

    public int getMaximumRedeliveries() {
        return maximumRedeliveries;
    }

    public void setMaximumRedeliveries(int maximumRedeliveries) {
        this.maximumRedeliveries = maximumRedeliveries;
    }
}
