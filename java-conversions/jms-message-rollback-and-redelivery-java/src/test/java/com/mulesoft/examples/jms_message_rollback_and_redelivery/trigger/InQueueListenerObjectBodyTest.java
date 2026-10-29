/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.jms_message_rollback_and_redelivery.trigger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.InvalidClassException;
import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageEOFException;
import jakarta.jms.MessageFormatException;
import jakarta.jms.ObjectMessage;
import jakarta.jms.Session;
import jakarta.jms.StreamMessage;
import jakarta.jms.TextMessage;

import org.apache.activemq.artemis.core.server.MessageReference;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.ServerConsumer;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.core.server.impl.AckReason;
import org.apache.activemq.artemis.core.server.plugin.ActiveMQServerMessagePlugin;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.core.transaction.Transaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisConfigurationCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jms.connection.SingleConnectionFactory;
import org.springframework.test.annotation.DirtiesContext;

import com.mulesoft.examples.jms_message_rollback_and_redelivery.exception.MyException;
import com.mulesoft.examples.jms_message_rollback_and_redelivery.service.RedeliveryService;

/**
 * Broker test for the object message bodies {@link InQueueListener} reads itself under its deserialization filter,
 * on Boot's embedded in-VM Artemis broker with the address settings of queue {@code in} ({@code redelivery-delay} 0,
 * {@code max-delivery-attempts} 6).
 *
 * <p>A body holding a class outside the filter, the {@code readObject} sentinel alone or inside a list, or the
 * SerialDOS graph of nested sets, is consumed by the catch branch on its first delivery, without that class's
 * {@code readObject} running and without a publication. Bodies of permitted types, an {@code Integer}, a
 * {@code Date}, lists of the values a stream message holds and one {@code String} above Artemis's 100 KiB
 * {@code min-large-message-size}, which the broker therefore handles as a large message, still go through the
 * original's five deliveries and reach {@code topic1} unchanged: the {@code Integer} and the {@code Date} as object
 * messages, the lists as stream messages and the {@code String} as a text message. A broker plugin records the
 * delivery count of every acknowledgement on queue {@code in}, which the listener's session sends for every delivery
 * it processed, whether the transaction then commits or rolls back, and whether each dispatch from it was a large
 * message.
 *
 * <p>Both redelivery delays are scaled down from 5000/2000 ms to
 * {@value #INITIAL_DELAY_MS}/{@value #REDELIVERY_DELAY_MS} ms, so five deliveries take well under a second; the
 * timing itself is measured by
 * {@code JmsMessageRollbackAndRedeliveryTest} and {@code InQueueListenerBrokerTest}.
 */
@SpringBootTest(properties = {
        "spring.artemis.mode=embedded",
        "spring.artemis.embedded.queues=" + InQueueListenerObjectBodyTest.QUEUE,
        "spring.artemis.embedded.topics=" + InQueueListenerObjectBodyTest.TOPIC,
        "spring.artemis.embedded.data-directory=target/in-queue-listener-object-body-broker",
        "redelivery.initial-redelivery-delay=" + InQueueListenerObjectBodyTest.INITIAL_DELAY_MS,
        "redelivery.redelivery-delay=" + InQueueListenerObjectBodyTest.REDELIVERY_DELAY_MS,
        // The listener container, a class of the config package, logs each rollback at WARN with its stack trace.
        "logging.level.com.mulesoft.examples.jms_message_rollback_and_redelivery.config=ERROR"
})
@DirtiesContext
class InQueueListenerObjectBodyTest {

    static final String QUEUE = "in";

    static final String TOPIC = "topic1";

    /** Scaled {@code redelivery.initial-redelivery-delay}, the pause after delivery 1. */
    static final long INITIAL_DELAY_MS = 250;

    /** Scaled {@code redelivery.redelivery-delay}, the pause after deliveries 2 to 4. */
    static final long REDELIVERY_DELAY_MS = 100;

    /** Longest wait for a publication on {@code topic1} or for the handling of a delivery. */
    private static final long RECEIVE_TIMEOUT_MS = 15_000;

    /**
     * How long {@code topic1} must stay silent after a rejected body was consumed: four times the scaled time from
     * a first delivery to a fifth (250 + 3 × 100 ms), so a wrongly redelivered message would have been published.
     */
    private static final long NO_PUBLICATION_WAIT_MS = 4 * (INITIAL_DELAY_MS + 3 * REDELIVERY_DELAY_MS);

    /** Characters of the large body: about 300 KB serialized, above the 100 KiB large-message threshold. */
    private static final int LARGE_BODY_CHARS = 300_000;

    /** Delivery counts of the acknowledgements on queue {@code in}, in the order the broker received them. */
    private static final List<Integer> ACKNOWLEDGED_DELIVERIES = new CopyOnWriteArrayList<>();

    /** For each dispatch from queue {@code in}, whether the broker held the message as a large message. */
    private static final List<Boolean> DISPATCHED_LARGE = new CopyOnWriteArrayList<>();

    /**
     * Address settings of queue {@code in}, as {@code JmsMessageRollbackAndRedeliveryTest} sets them, and the plugin
     * that records acknowledgements and dispatches on it.
     */
    @TestConfiguration
    static class BrokerSettings {

        @Bean
        ArtemisConfigurationCustomizer inQueueSettingsAndRecorder() {
            return configuration -> {
                configuration.addAddressSetting(QUEUE, new AddressSettings()
                        .setRedeliveryDelay(0L)
                        .setMaxDeliveryAttempts(6));
                configuration.registerBrokerPlugin(new ActiveMQServerMessagePlugin() {
                    @Override
                    public void afterDeliver(ServerConsumer consumer, MessageReference reference) {
                        if (onQueue(reference)) {
                            DISPATCHED_LARGE.add(reference.getMessage().isLargeMessage());
                        }
                    }

                    @Override
                    public void messageAcknowledged(Transaction tx, MessageReference reference, AckReason reason,
                                                    ServerConsumer consumer) {
                        if (onQueue(reference) && reason == AckReason.NORMAL) {
                            ACKNOWLEDGED_DELIVERIES.add(reference.getDeliveryCount());
                        }
                    }
                });
            };
        }

        private static boolean onQueue(MessageReference reference) {
            return QUEUE.equals(reference.getQueue().getName().toString());
        }
    }

    @SpyBean
    private RedeliveryService redeliveryService;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Autowired
    private EmbeddedActiveMQ broker;

    private Connection connection;

    private Session session;

    private MessageConsumer topic;

    @BeforeEach
    void connect() throws Exception {
        ACKNOWLEDGED_DELIVERIES.clear();
        DISPATCHED_LARGE.clear();
        ReadObjectSentinel.READ.set(false);
        // A dedicated connection on the raw Artemis factory: Boot's CachingConnectionFactory shares one connection
        // and keeps consumers cached after close.
        ConnectionFactory target = connectionFactory instanceof SingleConnectionFactory single
                ? single.getTargetConnectionFactory()
                : connectionFactory;
        connection = target.createConnection();
        session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        // The subscription is non-durable, so it must exist before a fifth delivery publishes.
        topic = session.createConsumer(session.createTopic(TOPIC));
        connection.start();
    }

    @AfterEach
    void disconnect() throws Exception {
        ReadObjectSentinel.READ.set(false);
        if (connection != null) {
            connection.close();
        }
    }

    private void sendObject(Serializable object) throws Exception {
        session.createProducer(session.createQueue(QUEUE)).send(session.createObjectMessage(object));
    }

    private Queue inQueue() {
        return broker.getActiveMQServer().locateQueue(QUEUE);
    }

    /**
     * Object bodies the filter rejects: the sentinel, alone and as an element of a permitted list, and the SerialDOS
     * graph of nested sets, whose unfiltered read would take exponential time.
     */
    static Stream<Arguments> rejectedObjects() {
        return Stream.of(
                Arguments.of(Named.of("readObject sentinel", new ReadObjectSentinel())),
                Arguments.of(Named.of("ArrayList holding the readObject sentinel",
                        new ArrayList<>(List.of("a", new ReadObjectSentinel())))),
                Arguments.of(Named.of("nested HashSet of depth 27 (SerialDOS)", serialDos(27))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedObjects")
    @DisplayName("an object body holding a class outside the filter is consumed by the catch branch on delivery 1, "
            + "no rejected class's readObject runs and nothing is published")
    void rejectedObjectBodyIsConsumedWithoutPublication(Serializable object) throws Exception {
        sendObject(object);

        ArgumentCaptor<Exception> failure = ArgumentCaptor.forClass(Exception.class);
        verify(redeliveryService, timeout(RECEIVE_TIMEOUT_MS)).handleFailure(failure.capture());
        MessageFormatException format = assertInstanceOf(MessageFormatException.class, failure.getValue());
        assertEquals(InQueueListener.UNREADABLE_OBJECT_BODY, format.getMessage());
        InvalidClassException cause = assertInstanceOf(InvalidClassException.class, format.getCause());
        assertTrue(cause.getMessage().contains("filter status: REJECTED"),
                () -> "not rejected by the filter: " + cause);
        await().atMost(Duration.ofMillis(RECEIVE_TIMEOUT_MS))
                .until(() -> inQueue().getMessageCount() == 0 && inQueue().getDeliveringCount() == 0);

        assertNull(topic.receive(NO_PUBLICATION_WAIT_MS), "a rejected object body was published to " + TOPIC);
        assertEquals(List.of(1), ACKNOWLEDGED_DELIVERIES, "deliveries of the rejected object body");
        assertEquals(0, inQueue().getMessageCount(), "messages left on " + QUEUE);
        verify(redeliveryService, times(1)).handleFailure(any());
        verify(redeliveryService, never()).process(any(), anyInt());
        assertFalse(ReadObjectSentinel.READ.get(), "the sentinel's readObject ran");
    }

    /**
     * Permitted object bodies, each with the type of the message its fifth delivery is published as by
     * {@code client/TopicPublisher}: an object message for an {@code Integer} and a {@code Date}, a stream message
     * for a list.
     */
    static Stream<Arguments> permittedObjects() {
        return Stream.of(
                Arguments.of(Named.of("Integer 42", 42), ObjectMessage.class),
                Arguments.of(Named.of("java.util.Date(0)", new Date(0)), ObjectMessage.class),
                Arguments.of(Named.of("ArrayList of the strings a and b", new ArrayList<>(List.of("a", "b"))),
                        StreamMessage.class),
                Arguments.of(Named.of("ArrayList of primitive wrappers, a String, null and a byte[]",
                        new ArrayList<>(Arrays.asList(true, (byte) 3, (short) 4, 'c', 5, 6L, 7.5f, 8.5, "a", null,
                                new byte[] {1, 2}))), StreamMessage.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("permittedObjects")
    @DisplayName("a permitted object body reaches topic1 as the same value after five deliveries")
    void permittedObjectBodyIsPublishedOnFifthDelivery(Serializable object, Class<? extends Message> publishedType)
            throws Exception {
        sendObject(object);

        Message published = topic.receive(RECEIVE_TIMEOUT_MS);

        assertNotNull(published, "no message on " + TOPIC + " within " + RECEIVE_TIMEOUT_MS + " ms");
        assertEqualValue(object, publishedValue(assertInstanceOf(publishedType, published)));
        ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Integer> counts = ArgumentCaptor.forClass(Integer.class);
        verify(redeliveryService, times(5)).process(payloads.capture(), counts.capture());
        for (Object payload : payloads.getAllValues()) {
            assertEqualValue(object, payload);
        }
        assertEquals(List.of(1, 2, 3, 4, 5), counts.getAllValues());
        verify(redeliveryService, times(4)).handleFailure(any(MyException.class));
        verify(redeliveryService, times(4)).handleFailure(any());
        await().atMost(Duration.ofMillis(RECEIVE_TIMEOUT_MS))
                .until(() -> ACKNOWLEDGED_DELIVERIES.equals(List.of(1, 2, 3, 4, 5)));
    }

    @Test
    @DisplayName("a permitted object body above the large-message threshold reaches topic1 as the same String")
    void largeObjectBodyIsPublishedOnFifthDelivery() throws Exception {
        String large = "0123456789".repeat(LARGE_BODY_CHARS / 10);
        sendObject(large);

        Message published = topic.receive(RECEIVE_TIMEOUT_MS);

        assertNotNull(published, "no message on " + TOPIC + " within " + RECEIVE_TIMEOUT_MS + " ms");
        assertEquals(large, assertInstanceOf(TextMessage.class, published).getText());
        verify(redeliveryService, times(5)).process(eq(large), anyInt());
        assertFalse(DISPATCHED_LARGE.isEmpty(), "no dispatch from " + QUEUE + " was recorded");
        assertTrue(DISPATCHED_LARGE.stream().allMatch(Boolean::booleanValue),
                () -> "dispatches from " + QUEUE + " not held as a large message: " + DISPATCHED_LARGE);
    }

    /**
     * The value a publication on {@code topic1} carries: the object of an object message, or the values of a stream
     * message read to its end, {@code null} values included.
     */
    private static Object publishedValue(Message published) throws JMSException {
        if (published instanceof StreamMessage stream) {
            List<Object> values = new ArrayList<>();
            while (true) {
                try {
                    values.add(stream.readObject());
                } catch (MessageEOFException endOfStream) {
                    return values;
                }
            }
        }
        return assertInstanceOf(ObjectMessage.class, published).getObject();
    }

    /**
     * Asserts that {@code actual} is of the very class of {@code expected} and holds the same value, comparing list
     * elements deeply, so the {@code byte[]} of a list compares by content.
     */
    private static void assertEqualValue(Object expected, Object actual) {
        assertNotNull(actual, "null value");
        assertSame(expected.getClass(), actual.getClass());
        if (expected instanceof List<?> values) {
            assertArrayEquals(values.toArray(), ((List<?>) actual).toArray());
        } else {
            assertEquals(expected, actual);
        }
    }

    /**
     * The nested-set graph known as SerialDOS: two sets per level, each holding both sets of the next level, so the
     * graph has {@code 2 × depth} sets but deserializing it rehashes about {@code 2^depth} of them. Each set is
     * added while it is still small, so building and serializing it stay linear.
     */
    private static HashSet<Object> serialDos(int depth) {
        HashSet<Object> root = new HashSet<>();
        Set<Object> left = root;
        Set<Object> right = new HashSet<>();
        for (int level = 0; level < depth; level++) {
            Set<Object> nextLeft = new HashSet<>();
            Set<Object> nextRight = new HashSet<>();
            nextLeft.add("distinct");
            left.add(nextLeft);
            left.add(nextRight);
            right.add(nextLeft);
            right.add(nextRight);
            left = nextLeft;
            right = nextRight;
        }
        return root;
    }
}
