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
 *   <li>Catch branch ({@code !exception.causedBy(org.exceptions.MyException)}, line 40): logs and returns
 *       normally, so the listener container commits the session and the message is consumed.</li>
 *   <li>Rollback branch ({@code exception.causedBy(org.exceptions.MyException)}, line 44): logs and rethrows
 *       the {@link MyException}, so the transacted listener container rolls the session back and the broker
 *       redelivers.</li>
 * </ul>
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
     * in its cause chain it takes the catch branch and the method returns normally (commit); with one it takes
     * the rollback branch and that {@code MyException} is rethrown (rollback and redelivery).
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
     *   <li>Catch branch, when no {@link MyException} is in the failure's cause chain: {@link #catchBranch()}, so
     *       the method returns normally and the listener's session commits.</li>
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
            catchBranch();
        } else {
            throw rollbackBranch(cause);
        }
    }

    /**
     * The {@code catch-exception-strategy} branch ({@code jms-redelivery.xml:40-42}), taken when
     * {@code !exception.causedBy(org.exceptions.MyException)}: logs {@link #CATCH_MESSAGE} and returns normally,
     * so the listener's session commits and the message is consumed.
     */
    private void catchBranch() {
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
}
