/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.jms_message_rollback_and_redelivery.trigger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.mulesoft.examples.jms_message_rollback_and_redelivery.config.RedeliveryProperties;

/**
 * Unit tests for {@link RedeliveryDelay}, the pause that reproduces the original ActiveMQ {@code RedeliveryPolicy}
 * ({@code jms-redelivery.xml:6-12}: {@code initialRedeliveryDelay} 5000, {@code redeliveryDelay} 2000,
 * {@code maximumRedeliveries} 5, no exponential back-off) on Artemis, as AAP 0.7.3 sets out. A recording
 * {@link RedeliveryDelay.Sleeper} captures each requested pause, so the timing is asserted without waiting; these
 * are the gaps {@code JmsMessageRollbackAndRedeliveryTest} measures end to end.
 */
class RedeliveryDelayTest {

    private final List<Long> slept = new ArrayList<>();

    private final RedeliveryDelay.Sleeper sleeper = slept::add;

    private RedeliveryDelay delay;

    @BeforeEach
    void setUp() {
        delay = new RedeliveryDelay(new RedeliveryProperties(), sleeper);
    }

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    static Stream<Arguments> pausesPerDeliveryCount() {
        return Stream.of(
                Arguments.of(1, List.of(5000L)),
                Arguments.of(2, List.of(2000L)),
                Arguments.of(3, List.of(2000L)),
                Arguments.of(4, List.of(2000L)),
                Arguments.of(5, List.of(2000L)),
                Arguments.of(6, List.of()));
    }

    @ParameterizedTest(name = "JMSXDeliveryCount {0} pauses {1}")
    @MethodSource
    @DisplayName("default policy pauses 5000 ms after delivery 1, 2000 ms after deliveries 2-5, none beyond 5")
    void pausesPerDeliveryCount(int deliveryCount, List<Long> expected) {
        delay.pauseBeforeRollback(deliveryCount);

        assertEquals(expected, slept);
    }

    @Test
    @DisplayName("delayFor returns the initial delay for delivery 1 and the redelivery delay afterwards")
    void delayForDefaults() {
        assertEquals(5000L, delay.delayFor(1));
        assertEquals(2000L, delay.delayFor(2));
    }

    @Test
    @DisplayName("configured redelivery.* values replace the defaults")
    void customValues() {
        RedeliveryProperties props = new RedeliveryProperties();
        props.setInitialRedeliveryDelay(100);
        props.setRedeliveryDelay(50);
        props.setMaximumRedeliveries(2);
        RedeliveryDelay custom = new RedeliveryDelay(props, sleeper);

        assertEquals(100L, custom.delayFor(1));
        assertEquals(50L, custom.delayFor(2));

        custom.pauseBeforeRollback(3);
        assertEquals(List.of(), slept);

        custom.pauseBeforeRollback(2);
        assertEquals(List.of(50L), slept);
    }

    @ParameterizedTest(name = "redelivery delays of {0} ms request no pause")
    @ValueSource(longs = {0L, -1L})
    @DisplayName("a non-positive delay skips the pause instead of failing the rollback")
    void nonPositiveDelaySkipsPause(long millis) {
        RedeliveryProperties props = new RedeliveryProperties();
        props.setInitialRedeliveryDelay(millis);
        props.setRedeliveryDelay(millis);
        RedeliveryDelay custom = new RedeliveryDelay(props, sleeper);

        custom.pauseBeforeRollback(1);
        custom.pauseBeforeRollback(2);

        assertEquals(List.of(), slept);
    }

    @Test
    @DisplayName("an interrupted pause restores the interrupt flag and returns normally")
    void interruptRestoresFlagWithoutThrowing() {
        RedeliveryDelay interrupted = new RedeliveryDelay(new RedeliveryProperties(), millis -> {
            throw new InterruptedException("test");
        });
        try {
            assertDoesNotThrow(() -> interrupted.pauseBeforeRollback(1));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
