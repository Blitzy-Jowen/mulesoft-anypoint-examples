/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.websphere_mq.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test of {@link MqProcessingService}, which replaces
 * {@code <test:component appendString=" - processed" logMessageDetails="true" waitTime="15000"/>} of flow
 * {@code MessageProcessor} (websphere-mq/src/main/app/websphere-mq.xml:19).
 *
 * <p>Every wait goes through a recording {@link MqProcessingService.Sleeper}, so the 15000 ms wait is asserted
 * without the test thread ever blocking. Expected values are literals rather than
 * {@link MqProcessingService#APPEND_STRING}, so the tests pin the original component's bytes.
 */
class MqProcessingServiceTest {

    private final List<Long> requestedWaits = new ArrayList<>();

    private final MqProcessingService.Sleeper recordingSleeper = ms -> requestedWaits.add(ms);

    /** A failed assertion in the interrupt test must not leave the flag set for later tests on this thread. */
    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    void constantsMatchOriginalTestComponent() {
        assertThat(MqProcessingService.APPEND_STRING).isEqualTo(" - processed");
        assertThat(MqProcessingService.DEFAULT_WAIT_MS).isEqualTo(15000L);
    }

    @Test
    void defaultWaitIsPassedToSleeperExactlyOnce() {
        MqProcessingService service =
                new MqProcessingService(MqProcessingService.DEFAULT_WAIT_MS, recordingSleeper);

        assertThat(service.process("hello")).isEqualTo("hello - processed");
        assertThat(requestedWaits).containsExactly(15000L);
    }

    static Stream<Arguments> payloadsAndReplies() {
        return Stream.of(
                // WebsphereMQIT runs flow MessageProcessor with testEvent(""): the reply is the suffix alone.
                Arguments.of("", " - processed"),
                Arguments.of("test - processed", "test - processed - processed"),
                Arguments.of("Grüße\nline two ", "Grüße\nline two  - processed"));
    }

    @ParameterizedTest
    @MethodSource("payloadsAndReplies")
    void appendsProcessedSuffixToPayload(String payload, String expectedReply) {
        MqProcessingService service =
                new MqProcessingService(MqProcessingService.DEFAULT_WAIT_MS, recordingSleeper);

        assertThat(service.process(payload)).isEqualTo(expectedReply);
        assertThat(requestedWaits).containsExactly(15000L);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void nonPositiveWaitNeverCallsSleeper(long waitMs) {
        MqProcessingService service = new MqProcessingService(waitMs, recordingSleeper);

        assertThat(service.process("hello")).isEqualTo("hello - processed");
        assertThat(requestedWaits).isEmpty();
    }

    @Test
    void singleArgConstructorWithZeroWait() {
        // Zero keeps the Thread::sleep path of the production constructor from ever blocking the test.
        MqProcessingService service = new MqProcessingService(0L);

        assertThat(service.getWaitMs()).isZero();
        assertThat(service.process("x")).isEqualTo("x - processed");
    }

    @Test
    void exposesConfiguredWait() {
        assertThat(new MqProcessingService(MqProcessingService.DEFAULT_WAIT_MS, recordingSleeper).getWaitMs())
                .isEqualTo(15000L);
        assertThat(requestedWaits).isEmpty();
    }

    @Test
    void interruptedWaitRaisesIllegalStateAndRestoresFlag() {
        List<Long> attemptedWaits = new ArrayList<>();
        MqProcessingService service = new MqProcessingService(MqProcessingService.DEFAULT_WAIT_MS, ms -> {
            attemptedWaits.add(ms);
            throw new InterruptedException("test");
        });

        assertThatThrownBy(() -> service.process("hello"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MessageProcessor wait was interrupted")
                .hasCauseInstanceOf(InterruptedException.class);
        assertThat(attemptedWaits).containsExactly(15000L);
        // Thread.interrupted() both asserts that the service restored the flag and clears it.
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void nullPayloadThrowsNullPointerExceptionBeforeAnyWait() {
        MqProcessingService service =
                new MqProcessingService(MqProcessingService.DEFAULT_WAIT_MS, recordingSleeper);

        assertThatNullPointerException().isThrownBy(() -> service.process(null));
        assertThat(requestedWaits).isEmpty();
    }

    @Test
    void nullSleeperIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new MqProcessingService(MqProcessingService.DEFAULT_WAIT_MS, null))
                .withMessage("sleeper");
    }
}
