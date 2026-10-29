/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.using_transactional_scope_in_jms_to_database.service;

import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.client.OrderRepository;
import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.model.Order;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional scope of {@code transactions.xml}: the order insert, the "Throw an exception" component and
 * the send to queue {@code out}, run in the order the flow declares them.
 * <p>
 * The class is deliberately not {@code final} and has no interface: {@code @Transactional} is applied through a
 * CGLIB subclass proxy, and {@link #process} must be reached through that proxy (the listener injects this bean),
 * never by self-invocation.
 */
@Service
public class OrderTransactionService {

    private final OrderRepository orderRepository;

    private final TestComponent testComponent;

    private final JmsTemplate jmsTemplate;

    private final String outboundQueue;

    /**
     * @param orderRepository the database access path of the {@code db:insert}
     * @param testComponent   the component that runs between the insert and the send
     * @param jmsTemplate     the session-transacted template that sends on the listener's session
     * @param outboundQueue   the queue of the {@code jms:outbound-endpoint}, {@code out} in the original
     */
    public OrderTransactionService(OrderRepository orderRepository, TestComponent testComponent,
            JmsTemplate jmsTemplate, @Value("${jms.outbound-queue:out}") String outboundQueue) {
        this.orderRepository = orderRepository;
        this.testComponent = testComponent;
        this.jmsTemplate = jmsTemplate;
        this.outboundQueue = outboundQueue;
    }

    /**
     * Runs the {@code ee:multi-transactional action="ALWAYS_BEGIN"} scope of {@code transactions.xml} (lines
     * 12-21): insert the order into {@code orders} (lines 13-16), call the component (line 17), then send its result
     * to queue {@code out} (lines 18-20).
     * <p>
     * {@code REQUIRES_NEW} reproduces {@code ALWAYS_BEGIN}: a new database transaction is always started for the
     * scope. The send has the outbound endpoint's {@code JOIN_IF_POSSIBLE} behaviour: on the listener thread the
     * template uses the listener's transacted session and leaves its commit to the listener container, so the
     * message is sent only if the receive commits.
     * <p>
     * Any exception rolls the database transaction back ({@code rollbackFor = Exception.class}, because
     * {@code MyException} is checked and Spring would otherwise commit on it) and propagates unchanged to the
     * listener, whose container then rolls back the JMS session so the message is redelivered. Since the component
     * always throws, no order row is kept and nothing reaches {@code out}.
     *
     * @param order the order read from queue {@code in}
     * @throws Exception whatever the insert, the component or the send throws, unchanged; in practice the
     *         component's {@code MyException("exception customized")}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW /* ALWAYS_BEGIN */, rollbackFor = Exception.class)
    public void process(Order order) throws Exception {
        orderRepository.insert(order);
        Object result = testComponent.onCall(order);
        jmsTemplate.convertAndSend(outboundQueue, result);
    }
}
