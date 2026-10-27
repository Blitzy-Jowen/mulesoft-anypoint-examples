/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.jms_message_rollback_and_redelivery.service;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.jms_message_rollback_and_redelivery.client.TopicPublisher;
import com.mulesoft.examples.jms_message_rollback_and_redelivery.exception.MyException;

/**
 * Body of flow {@code JMSRedeliver} and its {@code choice-exception-strategy}
 * ({@code jms-redelivery.xml:26-47}): the choice on {@code JMSXDeliveryCount}, the send to topic
 * {@code topic1}, and the two exception branches.
 *
 * <ul>
 *   <li>Catch branch ({@code !exception.causedBy(org.exceptions.MyException)}, line 40): logs the failure at
 *       ERROR, then its INFO line, and returns normally, so the listener container commits the session and the
 *       message is consumed.</li>
 *   <li>Rollback branch ({@code exception.causedBy(org.exceptions.MyException)}, line 44): logs and rethrows
 *       the {@link MyException}, so the transacted listener container rolls the session back and the broker
 *       redelivers.</li>
 * </ul>
 *
 * <p>The catch branch's ERROR line records the failure it consumes, as Mule's catch exception strategy logged the
 * exception it handled: the branch name, then the exception's class with the frame that threw it and the classes
 * of its causes, for example {@code Catch Exception Strategy: jakarta.jms.MessageFormatException at
 * org.apache.activemq.artemis.jms.client.ActiveMQObjectMessage.getBodyInternal(ActiveMQObjectMessage.java:169)} for
 * an object message whose class is not on the classpath. The exception is never handed to the logger and no
 * message, cause message, suppressed exception or stack trace is written, because a message can quote the
 * delivered payload or a class name the sender chose. Every logged name is bounded in length and its
 * control, line-separator and format characters, unpaired surrogates and reverse solidus are escaped, so the event
 * stays on one line.
 *
 * <p>The class holds no transaction of its own: the session belongs to the listener container, and
 * {@link TopicPublisher} sends on it, as {@code ALWAYS_JOIN} did. The pause before a rollback belongs to
 * {@code trigger/RedeliveryDelay}.
 */
@Service
public class RedeliveryService {

    private static final Logger LOG = LoggerFactory.getLogger(RedeliveryService.class);

    /** Logger text of the {@code when} branch, {@code jms-redelivery.xml:28}. */
    public static final String SUCCESS_MESSAGE = "Transaction without errors";

    /** Logger text of the catch branch, {@code jms-redelivery.xml:41}, misspelling included. */
    public static final String CATCH_MESSAGE = "Entered catch exception strategy. The transaction is commited.";

    /** Logger text of the rollback branch, {@code jms-redelivery.xml:45}. */
    public static final String ROLLBACK_MESSAGE =
            "Entered rollback exception strategy. The message rolls back to its original state for reprocessing.";

    /** Longest logged class, method or file name, in characters after escaping. */
    private static final int NAME_LOG_LIMIT = 256;

    /** Number of causes named after the branch's exception before the chain is cut short. */
    private static final int CAUSE_LOG_LIMIT = 10;

    private final TopicPublisher topicPublisher;

    /**
     * @param topicPublisher the publisher to topic {@code topic1}, joining the listener's session
     */
    public RedeliveryService(TopicPublisher topicPublisher) {
        this.topicPublisher = topicPublisher;
    }

    /**
     * Processes one delivery of a message from queue {@code in} ({@code jms-redelivery.xml:26-47}).
     *
     * <p>On the fifth delivery the message is logged as processed and published to {@code topic1}; any other
     * delivery throws {@link MyException}, as the flow's {@code test:component} does. A failure is then
     * handled by the choice-exception strategy, {@link #handleFailure(Exception)}: without a {@code MyException}
     * in its cause chain it takes the catch branch, which logs the failure at ERROR, and the method returns
     * normally (commit); with one it takes the rollback branch and that {@code MyException} is rethrown (rollback
     * and redelivery).
     *
     * <p>Delivery counts after a crash: the listener container ({@code JmsConfig.ConsumerClosingListenerContainer})
     * closes the receive's consumer before the listener runs, so a delivery pausing before its rollback is held
     * only by its session's transaction, to which the message's acknowledgement belongs. When the application dies
     * without closing its connection (a hard kill) during that pause, the Artemis broker rolls the transaction back
     * as it clears the dead connection, the message returns to queue {@code in} with its delivery count unchanged,
     * and the next delivery continues the count. Artemis does not take back the count of a delivery made to a
     * failed client, so should it hand the returned message to a consumer of the dead connection it has not cleared
     * yet, the next {@code deliveryCount} can skip values. A count past 5 is rolled back like every count but 5, as
     * the original choice does; once the count reaches the address's {@code max-delivery-attempts} of 6, the broker
     * moves the message to the dead-letter address without it being published. A graceful shutdown waits for the
     * pause and keeps the counts exact.
     *
     * @param payload       the message body, published unchanged
     * @param deliveryCount the message's {@code JMSXDeliveryCount}, starting at 1
     * @throws MyException the exception found in the failure's cause chain, when the rollback branch runs
     */
    public void process(Object payload, int deliveryCount) throws MyException {
        try {
            if (deliveryCount == 5) {
                LOG.info(SUCCESS_MESSAGE);
            } else {
                throw new MyException();
            }
            topicPublisher.publish(payload);
        } catch (Exception e) {
            handleFailure(e);
        }
    }

    /**
     * The flow's {@code choice-exception-strategy} ({@code jms-redelivery.xml:39-47}): it dispatches the failure to
     * one handler method per branch, evaluating the branches' {@code when} conditions in declaration order. It
     * handles every failure of the flow, including one raised while the inbound message is read, before
     * {@link #process(Object, int)} runs, as Mule's inbound JMS transformer ran inside the flow.
     *
     * <ul>
     *   <li>Catch branch, when no {@link MyException} is in the failure's cause chain:
     *       {@link #catchBranch(Exception)}, which logs the failure's type identifiers at ERROR and then the
     *       branch's INFO line, so the method returns normally and the listener's session commits.</li>
     *   <li>Rollback branch otherwise: {@link #rollbackBranch(MyException)}, and the {@code MyException} it hands
     *       back is rethrown, so the listener's session rolls back and the broker redelivers.</li>
     * </ul>
     *
     * @param failure the exception the flow raised
     * @throws MyException the exception found in the failure's cause chain, when the rollback branch runs
     */
    public void handleFailure(Exception failure) throws MyException {
        MyException cause = findMyException(failure);
        if (cause == null) {
            catchBranch(failure);
        } else {
            throw rollbackBranch(cause);
        }
    }

    /**
     * The {@code catch-exception-strategy} branch "Catch Exception Strategy" ({@code jms-redelivery.xml:40-42}),
     * taken when {@code !exception.causedBy(org.exceptions.MyException)}. It logs the failure's type, throw site
     * and cause types at ERROR, never its message, then logs {@link #CATCH_MESSAGE} at INFO and returns normally,
     * so the listener's session commits and the message is consumed.
     *
     * @param failure the exception the flow raised, which has no {@link MyException} in its cause chain
     */
    private void catchBranch(Exception failure) {
        LOG.error("Catch Exception Strategy: {}", describe(failure));
        LOG.info(CATCH_MESSAGE);
    }

    /**
     * The {@code rollback-exception-strategy} branch ({@code jms-redelivery.xml:44-46}), taken when
     * {@code exception.causedBy(org.exceptions.MyException)}: logs {@link #ROLLBACK_MESSAGE} and hands back that
     * {@code MyException}, which {@link #handleFailure(Exception)} rethrows, so the listener's session rolls back
     * and the broker redelivers. The exception is returned rather than thrown here so that the rethrow stays in
     * the strategy's own body.
     *
     * @param cause the {@link MyException} found in the failure's cause chain
     * @return {@code cause}, the exception to rethrow out of the transacted listener
     */
    private MyException rollbackBranch(MyException cause) {
        LOG.info(ROLLBACK_MESSAGE);
        return cause;
    }

    /**
     * Mule's {@code exception.causedBy(org.exceptions.MyException)}: the first {@link MyException} in the cause
     * chain of {@code t}, {@code t} itself included, or {@code null}. The visited set ends the walk on a cause
     * chain that loops back on itself.
     */
    private static MyException findMyException(Throwable t) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = t;
        while (current != null && visited.add(current)) {
            if (current instanceof MyException my) {
                return my;
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * Returns the type identifiers of {@code ex} without any of its text: its class name and throw-site frame, then
     * the class name of each cause, separated by {@code " <- "}. The walk stops at a cause already named, marked
     * {@code [circular]}, and after {@link #CAUSE_LOG_LIMIT} causes, marked {@code ...}, so cyclic and very deep
     * chains terminate. Suppressed exceptions are not named.
     */
    private static String describe(Throwable ex) {
        StringBuilder out = new StringBuilder(128).append(sanitize(ex.getClass().getName()));
        appendThrowSite(out, ex);
        Set<Throwable> named = Collections.newSetFromMap(new IdentityHashMap<>());
        named.add(ex);
        int causes = 0;
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            out.append(" <- ");
            if (causes == CAUSE_LOG_LIMIT) {
                out.append("...");
                break;
            }
            if (!named.add(cause)) {
                out.append("[circular]");
                break;
            }
            out.append(sanitize(cause.getClass().getName()));
            causes++;
        }
        return out.toString();
    }

    /**
     * Appends {@code " at <class>.<method>(<file>:<line>)"} for the frame that threw {@code ex}, in the form
     * {@link StackTraceElement#toString()} uses but without its class loader and module prefix, or nothing when the
     * exception has no stack trace.
     */
    private static void appendThrowSite(StringBuilder out, Throwable ex) {
        StackTraceElement[] trace = ex.getStackTrace();
        if (trace == null || trace.length == 0 || trace[0] == null) {
            return;
        }
        StackTraceElement frame = trace[0];
        out.append(" at ").append(sanitize(frame.getClassName())).append('.')
                .append(sanitize(frame.getMethodName())).append('(');
        if (frame.isNativeMethod()) {
            out.append("Native Method");
        } else if (frame.getFileName() == null) {
            out.append("Unknown Source");
        } else {
            out.append(sanitize(frame.getFileName()));
            if (frame.getLineNumber() >= 0) {
                out.append(':').append(frame.getLineNumber());
            }
        }
        out.append(')');
    }

    /**
     * Makes a name safe to log on one line: ISO control characters, line and paragraph separators, format
     * characters, unpaired surrogates and the reverse solidus are each written as {@code \}{@code u} and four
     * lower-case hex digits per UTF-16 unit, and a value longer than {@link #NAME_LOG_LIMIT} characters after
     * escaping is cut before the first character or escape that would exceed it, then marked
     * {@code ...[truncated]}. {@code null} is written as {@code (null)}.
     */
    private static String sanitize(String value) {
        if (value == null) {
            return "(null)";
        }
        StringBuilder out = new StringBuilder(Math.min(value.length(), NAME_LOG_LIMIT));
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            int units = Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            boolean escape = codePoint == '\\' || Character.isISOControl(codePoint)
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                    || type == Character.FORMAT || type == Character.SURROGATE;
            if (out.length() + (escape ? 6 * units : units) > NAME_LOG_LIMIT) {
                return out.append("...[truncated]").toString();
            }
            for (int unit = i; unit < i + units; unit++) {
                char c = value.charAt(unit);
                if (escape) {
                    out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                } else {
                    out.append(c);
                }
            }
            i += units;
        }
        return out.toString();
    }
}
