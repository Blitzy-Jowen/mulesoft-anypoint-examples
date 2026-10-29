/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.using_transactional_scope_in_jms_to_database;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import jakarta.jms.ConnectionFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Port of the original {@code UsingTransactionalScopeInJMStoDatabaseIT}: {@code message.xml} sent to queue
 * {@code in} is inserted and then rolled back with the message on every delivery, so once the connector's
 * {@code maxRedelivery} of 2 is exceeded the {@code orders} table is still empty.
 *
 * <p>The embedded Artemis broker and the per-context H2 database of profile {@code test} replace the live
 * ActiveMQ broker and the MySQL database {@code MySQLDbCreator} created, and the count query below replaces the
 * {@code selectOrders} sub-flow of {@code testflows/test-flows.xml}. The class declares no mock or spy bean, so
 * it runs on a context of its own, with an empty table and an empty queue {@code out}.
 */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class UsingTransactionalScopeInJMStoDatabaseTest {

    /**
     * The original README's log of the delivery that exceeds {@code maxRedelivery}; its message ID and payload
     * type differ per broker, so only the fixed part is matched.
     */
    private static final String REDELIVERY_EXCEEDED = "has been redelivered 3 times on endpoint \"jms://in\", "
            + "which exceeds the maxRedelivery setting of 2 on the connector \"Active_MQ\"";

    @Autowired
    private JmsTemplate jmsTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Test
    void sendJMStoTransactionScopeTest(CapturedOutput output) throws Exception {
        String message;
        try (InputStream in = new ClassPathResource("message.xml").getInputStream()) {
            message = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        int baseline = output.getAll().length();
        jmsTemplate.convertAndSend("in", message);

        // Deliveries 1 to 3 roll back; the 4th is logged and consumed, after which no transaction is in flight.
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> output.getAll().substring(baseline).contains(REDELIVERY_EXCEEDED));

        // The quoted alias keeps the column label of the original MySQL result; H2 would report COUNT(*).
        assertEquals("[{count(*)=0}]",
                jdbcTemplate.queryForList("select count(*) as \"count(*)\" from orders").toString());

        // A template of the test's own, so the application bean's receive timeout stays untouched.
        JmsTemplate outReader = new JmsTemplate(connectionFactory);
        outReader.setReceiveTimeout(1000);
        assertNull(outReader.receive("out"));
    }
}
