/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.using_transactional_scope_in_jms_to_database.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.client.OrderRepository;
import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.exception.MyException;
import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.model.Order;

/**
 * Test of {@link OrderTransactionService}, the {@code ee:multi-transactional action="ALWAYS_BEGIN"} scope of
 * {@code transactions.xml} (lines 12-21): insert into {@code orders}, the "Throw an exception" component, then the
 * send to queue {@code out}.
 *
 * <p>The Spring cases run against the test profile's H2 database (created from {@code order.sql}) and embedded
 * Artemis broker, so the rollback and commit of the row and the message are observed, not assumed. The class-level
 * annotations and the spy set ({@link TestComponent}, {@link OrderRepository}) are the context key shared with
 * {@code trigger/OrderQueueListenerTest}; any other context-altering annotation here would start a second context.
 * The database is reached only through the context's {@link JdbcTemplate}: re-resolving {@code jdbc.url} would
 * yield another {@code ${random.uuid}}, an empty database without the {@code orders} table.
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderTransactionServiceTest {

    private static final String OUT_QUEUE = "out";

    private static final String IN_QUEUE = "in";

    private static final String COUNT_ORDERS = "select count(*) from orders";

    @SpyBean
    TestComponent testComponent;

    @SpyBean
    OrderRepository orderRepository;

    @Autowired
    OrderTransactionService service;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ConnectionFactory connectionFactory;

    @BeforeEach
    void isolate() {
        jdbcTemplate.update("delete from orders");
        drain(OUT_QUEUE);
        // The context's OrderQueueListener consumes "in"; a stray message left there by another test class in the
        // shared context would otherwise be processed while TestComponent is stubbed to succeed, committing an
        // unexpected row and out message.
        drain(IN_QUEUE);
    }

    @Test
    void processRollsBackInsertAndSendWhenTestComponentThrows() throws Exception {
        MyException ex = assertThrows(MyException.class, () -> service.process(order(1, 2, 3)));

        assertEquals("exception customized", ex.getMessage());
        // The insert ran inside the transaction and was rolled back, not skipped. The rollback of a checked
        // exception proves rollbackFor = Exception.class: without it Spring would commit the insert.
        verify(orderRepository).insert(any());
        assertEquals(0, jdbcTemplate.queryForObject(COUNT_ORDERS, Integer.class));
        assertNull(probe(1000).receive(OUT_QUEUE));
    }

    @Test
    void processCommitsRowAndSendsResultWhenTestComponentSucceeds() throws Exception {
        doReturn("result").when(testComponent).onCall(any());

        service.process(order(1, 2, 3));

        assertEquals(1, jdbcTemplate.queryForObject(COUNT_ORDERS, Integer.class));
        Map<String, Object> row = jdbcTemplate.queryForMap("select item_id, item_units, customer_id from orders");
        assertEquals(1, ((Number) row.get("item_id")).intValue());
        assertEquals(2, ((Number) row.get("item_units")).intValue());
        assertEquals(3, ((Number) row.get("customer_id")).intValue());
        // No JMS session is bound to this thread, so the session-transacted jmsTemplate registers its session
        // with the DataSourceTransactionManager transaction and commits it after the database commit.
        Message message = probe(2000).receive(OUT_QUEUE);
        TextMessage text = assertInstanceOf(TextMessage.class, message);
        assertEquals("result", text.getText());
    }

    @Test
    void processPropagatesRepositoryFailureWithoutCallingTestComponent() throws Exception {
        // A DataAccessException passes the repository's exception-translation proxy unchanged.
        doThrow(new DataIntegrityViolationException("boom")).when(orderRepository).insert(any());

        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class,
                () -> service.process(order(1, 2, 3)));

        assertEquals("boom", ex.getMessage());
        verify(testComponent, never()).onCall(any());
        assertEquals(0, jdbcTemplate.queryForObject(COUNT_ORDERS, Integer.class));
        assertNull(probe(500).receive(OUT_QUEUE));
    }

    @Test
    void processCallsInsertThenComponentThenSendInOrder() throws Exception {
        OrderRepository mockRepo = Mockito.mock(OrderRepository.class);
        TestComponent mockComponent = Mockito.mock(TestComponent.class);
        JmsTemplate mockJms = Mockito.mock(JmsTemplate.class);
        Order order = order(1, 2, 3);
        when(mockComponent.onCall(order)).thenReturn("result");

        new OrderTransactionService(mockRepo, mockComponent, mockJms, OUT_QUEUE).process(order);

        InOrder inOrder = inOrder(mockRepo, mockComponent, mockJms);
        inOrder.verify(mockRepo).insert(order);
        inOrder.verify(mockComponent).onCall(order);
        inOrder.verify(mockJms).convertAndSend(OUT_QUEUE, (Object) "result");
        verifyNoMoreInteractions(mockRepo, mockComponent, mockJms);
    }

    /**
     * A non-transacted, auto-acknowledging template over the context's native Artemis factory, used only to read
     * and drain queues; the application {@code jmsTemplate} bean is left untouched.
     *
     * @param receiveTimeoutMs how long a {@code receive} waits for a message
     * @return the probe template
     */
    private JmsTemplate probe(long receiveTimeoutMs) {
        JmsTemplate probe = new JmsTemplate(connectionFactory);
        probe.setReceiveTimeout(receiveTimeoutMs);
        return probe;
    }

    /** Consumes every message currently on {@code queue}, waiting 200 ms for each further one. */
    private void drain(String queue) {
        JmsTemplate drain = probe(200);
        while (drain.receive(queue) != null) {
            // each received message is acknowledged and discarded
        }
    }

    private static Order order(int itemId, int itemUnits, int customerId) {
        Order order = new Order();
        order.setItemId(itemId);
        order.setItemUnits(itemUnits);
        order.setCustomerId(customerId);
        return order;
    }
}
