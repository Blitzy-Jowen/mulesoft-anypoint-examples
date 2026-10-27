/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.jms_message_rollback_and_redelivery.trigger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import jakarta.jms.Connection;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.remoting.impl.netty.NettyAcceptor;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.ActiveMQServers;
import org.apache.activemq.artemis.core.server.MessageReference;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.ServerConsumer;
import org.apache.activemq.artemis.core.server.impl.AckReason;
import org.apache.activemq.artemis.core.server.plugin.ActiveMQServerMessagePlugin;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.core.transaction.Transaction;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Broker test for {@link InQueueListener}: the redelivery gaps with several messages in flight, measured on an
 * embedded Artemis broker that the application reaches over TCP, as it reaches the external broker in production.
 *
 * <p>The broker carries the address settings queue {@code in} needs ({@code redelivery-delay} 0,
 * {@code max-delivery-attempts} 6), and a message plugin records every dispatch from that queue and every
 * acknowledgement on it. Bursts of three messages, which leave one of the four consumers idle, and bursts of four
 * and six, which fill and exceed them, are sent one after another, each once the previous one has reached
 * {@code topic1}. For every message the processed deliveries must carry the counts 1 to 5, each must start the
 * initial delay after the first one and the later delay after each other one, and the payload must reach
 * {@code topic1} once. A consumer that held credit while its listener paused before a rollback would be handed a
 * waiting message behind that pause, stretching that message's gap by up to a whole pause.
 *
 * <p>To keep the build short, the receive timeout and both delays are scaled by the same factor, from 1000 ms and
 * 5000/2000 ms to 250 ms and 1250/500 ms, and the tolerance scales with them. Each pause still spans five or two
 * receive timeouts of an idle consumer, so rollbacks keep drifting across the instants at which idle consumers'
 * receives time out, where the credit reset that ends a timed-out receive can strand a message dispatched at that
 * moment.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        // The listener container, a class of the config package, logs each rollback at WARN with its stack trace,
        // and the test drives about two hundred of them.
        properties = "logging.level.com.mulesoft.examples.jms_message_rollback_and_redelivery.config=ERROR")
class InQueueListenerBrokerTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(InQueueListenerBrokerTest.class);

    /** Scaled {@code spring.jms.listener.receive-timeout}, 1 s by default. */
    private static final long RECEIVE_TIMEOUT_MS = 250;

    /** Scaled {@code redelivery.initial-redelivery-delay}, the pause after delivery 1. */
    private static final long INITIAL_DELAY_MS = 1250;

    /** Scaled {@code redelivery.redelivery-delay}, the pause after deliveries 2 to 4. */
    private static final long REDELIVERY_DELAY_MS = 500;

    /** Allowed deviation of a measured gap as a share of the expected one: 500 ms and 200 ms. */
    private static final double TOLERANCE = 0.4;

    /** Messages per burst, sent in this order. */
    private static final int[] BURST_SIZES = {3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 4, 6};

    /** Deliveries of each message: four rolled back, the fifth published and committed. */
    private static final List<Integer> DELIVERY_COUNTS = List.of(1, 2, 3, 4, 5);

    /** The listener's consumers, {@code jms.concurrent-consumers}. */
    private static final int CONSUMERS = 4;

    /** Longest wait for the next publication of a burst; a burst of six needs about 6 s. */
    private static final long PUBLISH_TIMEOUT_MS = 30_000;

    private static final String QUEUE = "in";

    private static final String TOPIC = "topic1";

    /** String property naming a test message on the broker, where its body is not decoded. */
    private static final String TAG = "testTag";

    private static final String ACCEPTOR = "tcp";

    private static final Path BROKER_DIR = Path.of("target", "in-queue-listener-broker");

    /** Dispatches from queue {@code in} per message tag, in the order the broker made them. */
    private static final Map<String, List<Dispatch>> DISPATCHES = new ConcurrentHashMap<>();

    /** Acknowledgements on queue {@code in} per message tag, in the order the broker received them. */
    private static final Map<String, List<Acknowledgement>> ACKNOWLEDGEMENTS = new ConcurrentHashMap<>();

    private static ActiveMQServer broker;

    private static String brokerUrl;

    /**
     * One dispatch of a message from queue {@code in}.
     *
     * @param consumer      the receiving consumer as {@code <session ID>/<consumer ID>}; consumer IDs count per session
     * @param deliveryCount the delivery count the broker sent with it, {@code JMSXDeliveryCount} at the listener
     * @param nanoTime      {@link System#nanoTime()} when the broker dispatched it
     */
    private record Dispatch(String consumer, int deliveryCount, long nanoTime) {
    }

    /**
     * The acknowledgement of a dispatch, which the listener's session sends for every delivery it processed, whether
     * the transaction then commits or rolls back.
     *
     * @param consumer      the acknowledging consumer, as in {@link Dispatch}
     * @param deliveryCount the delivery count of the acknowledged dispatch
     */
    private record Acknowledgement(String consumer, int deliveryCount) {
    }

    private static String consumerKey(ServerConsumer consumer) {
        return consumer == null ? "none" : consumer.getSessionID() + "/" + consumer.getID();
    }

    private static String tagOnQueue(MessageReference reference) {
        String tag = reference.getMessage().getStringProperty(TAG);
        return tag != null && QUEUE.equals(reference.getQueue().getName().toString()) ? tag : null;
    }

    @BeforeAll
    static void startBroker() throws Exception {
        Configuration configuration = new ConfigurationImpl()
                .setPersistenceEnabled(false)
                .setSecurityEnabled(false)
                .setJMXManagementEnabled(false)
                .setBindingsDirectory(BROKER_DIR.resolve("bindings").toString())
                .setJournalDirectory(BROKER_DIR.resolve("journal").toString())
                .setPagingDirectory(BROKER_DIR.resolve("paging").toString())
                .setLargeMessagesDirectory(BROKER_DIR.resolve("large-messages").toString())
                .addAcceptorConfiguration(ACCEPTOR, "tcp://127.0.0.1:0");
        configuration.addAddressSetting(QUEUE, new AddressSettings()
                .setRedeliveryDelay(0L)
                .setMaxDeliveryAttempts(6));
        broker = ActiveMQServers.newActiveMQServer(configuration);
        broker.registerBrokerPlugin(new ActiveMQServerMessagePlugin() {
            @Override
            public void afterDeliver(ServerConsumer consumer, MessageReference reference) {
                String tag = tagOnQueue(reference);
                if (tag != null) {
                    DISPATCHES.computeIfAbsent(tag, key -> new CopyOnWriteArrayList<>()).add(new Dispatch(
                            consumerKey(consumer), reference.getDeliveryCount(), System.nanoTime()));
                }
            }

            @Override
            public void messageAcknowledged(Transaction tx, MessageReference reference, AckReason reason,
                                            ServerConsumer consumer) {
                String tag = tagOnQueue(reference);
                if (tag != null && reason == AckReason.NORMAL) {
                    ACKNOWLEDGEMENTS.computeIfAbsent(tag, key -> new CopyOnWriteArrayList<>())
                            .add(new Acknowledgement(consumerKey(consumer), reference.getDeliveryCount()));
                }
            }
        });
        broker.start();
        int port = ((NettyAcceptor) broker.getRemotingService().getAcceptor(ACCEPTOR)).getActualPort();
        brokerUrl = "tcp://127.0.0.1:" + port;
    }

    @AfterAll
    static void stopBroker() throws Exception {
        if (broker != null) {
            broker.stop();
        }
    }

    @DynamicPropertySource
    static void applicationProperties(DynamicPropertyRegistry registry) {
        // Native client over TCP; without the second key Boot would also start its own in-VM broker, because the
        // server classes are on the test classpath.
        registry.add("spring.artemis.mode", () -> "native");
        registry.add("spring.artemis.embedded.enabled", () -> "false");
        registry.add("spring.artemis.broker-url", () -> brokerUrl);
        registry.add("spring.jms.listener.receive-timeout", () -> RECEIVE_TIMEOUT_MS + "ms");
        registry.add("redelivery.initial-redelivery-delay", () -> INITIAL_DELAY_MS);
        registry.add("redelivery.redelivery-delay", () -> REDELIVERY_DELAY_MS);
    }

    @Test
    @DirtiesContext // closes the application, and with it the listener's connection, before the broker stops
    @DisplayName("in successive bursts of 3, 4 and 6 messages every message keeps the 1250/500 ms gaps and is "
            + "published once")
    void successiveBurstsKeepRedeliveryGaps() throws Exception {
        List<String> failures = new ArrayList<>();
        try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
             Connection connection = factory.createConnection()) {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageConsumer topic = session.createConsumer(session.createTopic(TOPIC));
            MessageProducer producer = session.createProducer(session.createQueue(QUEUE));
            connection.start();
            await().atMost(Duration.ofSeconds(30)).until(() -> consumerCount() == CONSUMERS);

            for (int burst = 1; burst <= BURST_SIZES.length; burst++) {
                List<String> sent = new ArrayList<>();
                for (int i = 1; i <= BURST_SIZES[burst - 1]; i++) {
                    String text = "burst" + burst + "-message" + i;
                    TextMessage message = session.createTextMessage(text);
                    message.setStringProperty(TAG, text);
                    producer.send(message);
                    sent.add(text);
                }
                Set<String> pending = new HashSet<>(sent);
                while (!pending.isEmpty()) {
                    Message published = topic.receive(PUBLISH_TIMEOUT_MS);
                    if (published == null) {
                        failures.add("not published within " + PUBLISH_TIMEOUT_MS + " ms: " + pending);
                        break;
                    }
                    String text = published instanceof TextMessage textMessage ? textMessage.getText() : null;
                    if (!pending.remove(text)) {
                        failures.add("unexpected or repeated publication: " + published);
                    }
                }
                sent.forEach(text -> checkDeliveries(text, failures));
            }
            Message extra = topic.receive(REDELIVERY_DELAY_MS);
            if (extra != null) {
                failures.add("unexpected or repeated publication: " + extra);
            }
        }
        assertTrue(failures.isEmpty(), () -> String.join(System.lineSeparator(), failures));
    }

    /**
     * Checks the deliveries of one published message: the listener processed counts 1 to 5, once each and in order,
     * and each processed delivery started within the tolerance of its delay after the previous one.
     *
     * <p>A processed delivery is the dispatch its consumer acknowledged. The broker may also dispatch a message to a
     * consumer that then returns it unprocessed, for example when the dispatch crosses the end of that consumer's
     * receive; the message goes back to the queue with its delivery count unchanged and is dispatched again. Such
     * returns are allowed, and the time one costs counts against the gap to the processed delivery.
     *
     * @param text     the message's text and tag
     * @param failures collects every deviation found
     */
    private static void checkDeliveries(String text, List<String> failures) {
        List<Dispatch> dispatches = DISPATCHES.getOrDefault(text, List.of());
        List<Acknowledgement> acknowledgements = ACKNOWLEDGEMENTS.getOrDefault(text, List.of());
        List<Integer> dispatchedCounts = dispatches.stream().map(Dispatch::deliveryCount).toList();
        List<Integer> processedCounts = acknowledgements.stream().map(Acknowledgement::deliveryCount).toList();
        if (!DELIVERY_COUNTS.equals(processedCounts)) {
            failures.add(text + ": processed delivery counts " + processedCounts + " (dispatched " + dispatchedCounts
                    + "), expected " + DELIVERY_COUNTS);
            return;
        }
        List<Long> starts = new ArrayList<>();
        for (Acknowledgement acknowledgement : acknowledgements) {
            Dispatch processed = null;
            for (Dispatch dispatch : dispatches) {
                if (dispatch.consumer().equals(acknowledgement.consumer())
                        && dispatch.deliveryCount() == acknowledgement.deliveryCount()) {
                    processed = dispatch;
                }
            }
            if (processed == null) {
                failures.add(text + ": delivery " + acknowledgement.deliveryCount() + " acknowledged by "
                        + acknowledgement.consumer() + ", which it was never dispatched to");
                return;
            }
            starts.add(processed.nanoTime());
        }
        List<Long> gaps = new ArrayList<>();
        for (int count = 1; count < DELIVERY_COUNTS.size(); count++) {
            long expected = count == 1 ? INITIAL_DELAY_MS : REDELIVERY_DELAY_MS;
            long gap = TimeUnit.NANOSECONDS.toMillis(starts.get(count) - starts.get(count - 1));
            gaps.add(gap);
            if (Math.abs(gap - expected) > TOLERANCE * expected) {
                failures.add(text + ": delivery " + (count + 1) + " started " + gap + " ms after delivery " + count
                        + ", expected " + expected + " ms ± " + Math.round(TOLERANCE * expected) + " ms");
            }
        }
        LOGGER.info("{}: dispatched with counts {}, processed deliveries {} ms apart", text, dispatchedCounts, gaps);
    }

    private static int consumerCount() {
        Queue queue = broker.locateQueue(QUEUE);
        return queue == null ? 0 : queue.getConsumerCount();
    }
}
