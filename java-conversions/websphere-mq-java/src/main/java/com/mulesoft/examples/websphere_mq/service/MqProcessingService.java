/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.websphere_mq.service;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The processing step of flow {@code MessageProcessor}, replacing
 * {@code <test:component appendString=" - processed" logMessageDetails="true" waitTime="15000"/>}
 * ({@code websphere-mq.xml}, line 19).
 *
 * <p>Mule 3.8's {@code FunctionalTestComponent} logs the received payload, computes the reply
 * {@code payload + appendString}, sleeps {@code waitTime} ms and then returns the reply; {@link #process(String)}
 * keeps that order. The JMS transaction around it (WMQ {@code in} with {@code ALWAYS_BEGIN}, WMQ {@code out} with
 * {@code JOIN_IF_POSSIBLE}) belongs to {@code trigger/InQueueListener} and {@code client/MqQueueSender}.
 *
 * <p>The wait runs on the calling transacted listener thread and is deliberately not asynchronous: with the
 * listener's concurrency of 1, later messages on {@code in} queue behind it exactly as they did in the original.
 */
@Service
public class MqProcessingService {

    /** The component's {@code appendString}; the leading space is part of the original value. */
    public static final String APPEND_STRING = " - processed";

    /** The component's {@code waitTime} in milliseconds, the default of {@code wmq.processing.wait-ms}. */
    public static final long DEFAULT_WAIT_MS = 15000;

    private static final Logger log = LoggerFactory.getLogger(MqProcessingService.class);

    /** Blocks the calling thread; injectable so tests can record the requested wait without waiting. */
    @FunctionalInterface
    public interface Sleeper {

        /**
         * Blocks the calling thread.
         *
         * @param ms the wait in milliseconds
         * @throws InterruptedException when the thread is interrupted during the wait
         */
        void sleep(long ms) throws InterruptedException;
    }

    private final long waitMs;

    private final Sleeper sleeper;

    /**
     * Creates the service on the bound {@code wmq.processing.wait-ms}, waiting with {@link Thread#sleep(long)}.
     *
     * @param waitMs the wait before the reply is returned, in milliseconds
     */
    @Autowired
    public MqProcessingService(@Value("${wmq.processing.wait-ms:15000}") long waitMs) {
        this(waitMs, Thread::sleep);
    }

    /**
     * Creates the service with an explicit sleeper.
     *
     * @param waitMs  the wait before the reply is returned, in milliseconds; zero or less disables it
     * @param sleeper performs the wait
     * @throws NullPointerException when {@code sleeper} is {@code null}
     */
    public MqProcessingService(long waitMs, Sleeper sleeper) {
        this.waitMs = waitMs;
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /**
     * Returns the configured wait.
     *
     * @return the wait before the reply is returned, in milliseconds
     */
    public long getWaitMs() {
        return waitMs;
    }

    /**
     * Processes one message taken from queue {@code in}: logs it, computes the reply, waits, returns the reply.
     *
     * @param payload the text of the message
     * @return {@code payload} followed by {@value #APPEND_STRING}
     * @throws NullPointerException  when {@code payload} is {@code null}, before any wait, as the original
     *                               component failed on {@code data.toString()}
     * @throws IllegalStateException when the wait is interrupted; the exception leaves the transacted listener,
     *                               so the session rolls back and the broker redelivers the message
     */
    public String process(String payload) {
        log.info("Message Received in service: MessageProcessor. Content is: {}", payload);
        // toString() rather than plain concatenation, so a null payload fails here instead of yielding "null - processed".
        String reply = payload.toString() + APPEND_STRING;
        if (waitMs > 0) {
            try {
                sleeper.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("MessageProcessor wait was interrupted", e);
            }
        }
        return reply;
    }
}
