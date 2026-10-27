/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.using_transactional_scope_in_jms_to_database.trigger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.jms.ConnectionFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.client.OrderRepository;
import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.config.TransactionConfig;
import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.service.TestComponent;

/**
 * Trigger test of {@link OrderQueueListener} with {@code jdbc.url} blank, as when the environment variable
 * {@code JDBC_URL} is not supplied: the whole application context of profile {@code test}, with its embedded Artemis
 * broker, but with {@code jdbc.url} overridden to empty and the profile's {@code order.sql} initialization switched
 * off ({@code spring.sql.init.mode=never}, as in {@code application.yml}), since there is no database to run it on.
 *
 * <p>The context starts with one WARN naming the key. Each delivery of {@code message.xml} is converted and logged,
 * then fails where the transactional scope opens its database transaction, before the insert: the container rolls
 * the session back and its error handler logs the refusal naming {@code jdbc.url} and {@code JDBC_URL}. Deliveries
 * 1 to 3 fail that way, the 4th exceeds the connector's {@code maxRedelivery} of 2 and is consumed, and nothing
 * reaches the insert, the component or queue {@code out}. Log events are read from Logback list appenders on the
 * loggers of {@link TransactionConfig} and {@link OrderQueueListener}; the startup WARN, written before any appender
 * can be attached, is read from the captured output. The properties make this class's context its own, so the
 * broker and the captured startup are this class's alone.
 */
@SpringBootTest(properties = {"jdbc.url=", "spring.sql.init.mode=never"})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class OrderQueueListenerUnconfiguredDatabaseTest {

    private static final String IN = "in";

    private static final String OUT = "out";

    /** The single startup warning of {@code config/DataSourceConfig}, at WARN. */
    private static final Pattern STARTUP_WARNING = Pattern.compile("WARN[^\\r\\n]*" + Pattern.quote(
            "jdbc.url (environment variable JDBC_URL) is not configured: the application starts without a database "
                    + "connection, and every database call fails until it is supplied"));

    /**
     * The container error handler's line for a delivery refused a connection: the refusal's type and message, then
     * its throw site, whose line number is not fixed here.
     */
    private static final Pattern REFUSED_LINE = Pattern.compile(Pattern.quote("JMS listener failed: "
            + "SQLNonTransientConnectionException: MySQL JDBC URL (jdbc.url, environment variable JDBC_URL) is not "
            + "configured at com.mulesoft.examples.using_transactional_scope_in_jms_to_database.config."
            + "UnconfiguredDataSource.getConnection:") + "\\d+");

    /** The flow's INFO logger {@code "XXXXXXX order: #[payload]"} for {@code message.xml}. */
    private static final String INFO_LINE = "XXXXXXX order: Order [item_id=1, item_units=2, customer_id=1]";

    /** The redelivery-exceeded ERROR, written once, on the 4th and last delivery. */
    private static final String EXCEEDED_MARKER = "which exceeds the maxRedelivery setting of 2";

    private static final Duration MAX_WAIT = Duration.ofSeconds(30);

    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    @SpyBean
    private TestComponent testComponent;

    @SpyBean
    private OrderRepository orderRepository;

    /** The application template; only sends to {@code in}. */
    @Autowired
    private JmsTemplate jmsTemplate;

    @Autowired
    private ConnectionFactory connectionFactory;

    private final ListAppender<ILoggingEvent> transactionLog = new ListAppender<>();

    private final ListAppender<ILoggingEvent> listenerLog = new ListAppender<>();

    @BeforeEach
    void attachAppenders() {
        attach(TransactionConfig.class, transactionLog);
        attach(OrderQueueListener.class, listenerLog);
    }

    @AfterEach
    void detachAppenders() {
        detach(TransactionConfig.class, transactionLog);
        detach(OrderQueueListener.class, listenerLog);
    }

    @Test
    void contextStartsAndEachDeliveryFailsNamingJdbcUrl(CapturedOutput output) throws Exception {
        assertEquals(1, matches(STARTUP_WARNING, output.getAll()), "one startup WARN names the unset key");

        jmsTemplate.convertAndSend(IN, new ClassPathResource("message.xml").getContentAsString(StandardCharsets.UTF_8));

        await().atMost(MAX_WAIT).pollInterval(POLL_INTERVAL).until(() -> messages(listenerLog, Level.ERROR).stream()
                .anyMatch(line -> line.contains(EXCEEDED_MARKER)));

        List<String> failures = messages(transactionLog, Level.ERROR);
        assertEquals(3, failures.size(), "deliveries 1 to 3 each roll back once: " + failures);
        failures.forEach(line -> assertTrue(REFUSED_LINE.matcher(line).matches(), line));
        assertEquals(3, messages(listenerLog, Level.INFO).stream().filter(INFO_LINE::equals).count());
        assertEquals(1, messages(listenerLog, Level.ERROR).size());
        verify(orderRepository, never()).insert(any());
        verify(testComponent, never()).onCall(any());
        JmsTemplate drain = new JmsTemplate(connectionFactory);
        drain.setReceiveTimeout(1000);
        assertNull(drain.receive(OUT), "nothing reaches out");
    }

    private static void attach(Class<?> owner, ListAppender<ILoggingEvent> appender) {
        appender.start();
        ((Logger) LoggerFactory.getLogger(owner)).addAppender(appender);
    }

    private static void detach(Class<?> owner, ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(owner)).detachAppender(appender);
        appender.stop();
    }

    /**
     * The formatted messages the appender has received at {@code level}. The copy is taken under the appender's
     * lock, which {@code doAppend} holds while the listener thread adds an event.
     */
    private static List<String> messages(ListAppender<ILoggingEvent> appender, Level level) {
        List<ILoggingEvent> events;
        synchronized (appender) {
            events = new ArrayList<>(appender.list);
        }
        return events.stream().filter(event -> event.getLevel() == level).map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static int matches(Pattern pattern, String text) {
        int count = 0;
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
