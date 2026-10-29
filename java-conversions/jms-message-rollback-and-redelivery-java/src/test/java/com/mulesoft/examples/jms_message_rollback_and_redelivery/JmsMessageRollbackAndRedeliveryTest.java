/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.jms_message_rollback_and_redelivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisConfigurationCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jms.connection.SingleConnectionFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.annotation.DirtiesContext;

import com.mulesoft.examples.jms_message_rollback_and_redelivery.service.RedeliveryService;

/**
 * Port of the original {@code JmsMessageRollbackAndRedeliveryIT}: a message dispatched to queue {@code in} is
 * rolled back and redelivered until its fifth delivery, which publishes it to topic {@code topic1}
 * ({@code jms-redelivery.xml}, flow {@code JMSRedeliver}).
 *
 * <p>The original started an ActiveMQ {@code BrokerService} on {@code tcp://localhost:61616}; here Boot's embedded
 * in-VM Artemis broker replaces it, so no TCP port is opened. Beyond the original assertion on the published
 * payload, the test asserts that the listener saw the delivery counts 1 to 5 and that each redelivery started no
 * earlier than the original {@code RedeliveryPolicy} allowed: 5000 ms after the first delivery and 2000 ms after
 * each later one.
 */
@SpringBootTest(properties = {
        "spring.artemis.mode=embedded",
        "spring.artemis.embedded.queues=in",
        "spring.artemis.embedded.topics=topic1"
})
@DirtiesContext
class JmsMessageRollbackAndRedeliveryTest {

    private static final String MESSAGE = "Message123";

    /** The original request timeout on {@code jms://topic:topic1}. */
    private static final long RECEIVE_TIMEOUT_MS = 30000;

    /** Minimum gap before deliveries 2, 3, 4 and 5: the original initial and subsequent redelivery delays. */
    private static final long[] MINIMUM_GAPS_MS = {5000, 2000, 2000, 2000};

    /**
     * Address settings of queue {@code in} on the embedded broker.
     *
     * <p>Artemis has no separate initial redelivery delay, so the broker redelivers at once
     * ({@code redelivery-delay} 0) and the listener's pause before each rollback supplies the 5000/2000 ms
     * (AAP 0.7.3). {@code max-delivery-attempts} 6 is the original's one delivery plus {@code maximumRedeliveries}
     * 5. The JMS queue {@code in} is the Artemis address {@code in}, so the key matches it exactly.
     */
    @TestConfiguration
    static class RedeliveryAddressSettings {

        @Bean
        ArtemisConfigurationCustomizer inQueueRedeliverySettings() {
            return configuration -> configuration.addAddressSetting("in", new AddressSettings()
                    .setRedeliveryDelay(0L)
                    .setMaxDeliveryAttempts(6));
        }
    }

    /**
     * One call of the flow body by the listener.
     *
     * @param count    the delivery's {@code JMSXDeliveryCount}
     * @param nanoTime {@link System#nanoTime()} when the listener handed the delivery to the flow
     */
    private record Delivery(int count, long nanoTime) {
    }

    @SpyBean
    private RedeliveryService redeliveryService;

    @Autowired
    private JmsTemplate jmsTemplate;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Test
    void redeliverJMSMessageTest() throws Exception {
        List<Delivery> deliveries = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            deliveries.add(new Delivery(invocation.<Integer>getArgument(1), System.nanoTime()));
            return invocation.callRealMethod();
        }).when(redeliveryService).process(any(), anyInt());

        // A dedicated connection on the raw Artemis factory: Boot's CachingConnectionFactory shares one connection
        // and keeps consumers cached after close.
        ConnectionFactory target = connectionFactory instanceof SingleConnectionFactory single
                ? single.getTargetConnectionFactory()
                : connectionFactory;
        Connection connection = target.createConnection();
        try {
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            // The subscription is non-durable, so it must exist before the fifth delivery publishes.
            MessageConsumer consumer = session.createConsumer(session.createTopic("topic1"));

            jmsTemplate.convertAndSend("in", MESSAGE);

            Message response = consumer.receive(RECEIVE_TIMEOUT_MS);
            assertNotNull(response, "no message on topic1 within " + RECEIVE_TIMEOUT_MS + " ms");
            assertInstanceOf(TextMessage.class, response);
            assertEquals(MESSAGE, ((TextMessage) response).getText());

            // The fifth call is recorded before its publication commits, so the list is complete here.
            assertEquals(List.of(1, 2, 3, 4, 5), deliveries.stream().map(Delivery::count).toList());
            for (int i = 0; i < MINIMUM_GAPS_MS.length; i++) {
                long gapNanos = deliveries.get(i + 1).nanoTime() - deliveries.get(i).nanoTime();
                int gap = i;
                assertTrue(gapNanos >= TimeUnit.MILLISECONDS.toNanos(MINIMUM_GAPS_MS[gap]),
                        () -> "gap " + gap + ": delivery " + (gap + 2) + " started "
                                + TimeUnit.NANOSECONDS.toMillis(gapNanos) + " ms after delivery " + (gap + 1)
                                + ", expected at least " + MINIMUM_GAPS_MS[gap] + " ms");
            }
        } finally {
            connection.close();
        }
    }
}
