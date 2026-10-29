/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_json_data_to_a_jms_queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.Message;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jms.core.JmsTemplate;

import com.mulesoft.examples.sending_json_data_to_a_jms_queue.support.HttpTestClient;

/**
 * Port of the original example's {@code SendingJsonDataToJMSQueueIT}, keeping its one test and its comparison
 * semantics: the {@code POST /sales} answer is present, carries a payload and has status {@code "200"}, and a
 * message with a payload can be taken from queue {@code sales} within 1500 ms.
 *
 * <p>The whole application runs on a random port with its embedded broker, replacing {@code FunctionalTestCase},
 * {@code MuleClient} and {@code DynamicPort}. The request goes over the wire through the project's ST-1 client and
 * the queue is read through the application's own {@link ConnectionFactory}, as the original read
 * {@code jms://sales} through its Mule context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class SendingJsonDataToJMSQueueTest {

    private static final String SALES = "sales";

    /** The original request body: the example's {@code message.json}, sent unchanged although it is not valid JSON. */
    private static String MESSAGE;

    @LocalServerPort
    int port;

    @Autowired
    ConnectionFactory connectionFactory;

    @BeforeAll
    static void init() throws IOException {
        try (InputStream in = SendingJsonDataToJMSQueueTest.class.getResourceAsStream("/message.json")) {
            assertNotNull(in, "test resource /message.json is missing from the test classpath");
            MESSAGE = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Empties queue {@code sales} so that only the message this test causes can satisfy its receive; other test
     * classes sharing this context may leave messages behind. A local template is used so the application's
     * {@link JmsTemplate} bean keeps its own settings.
     */
    @BeforeEach
    void drainSalesQueue() {
        JmsTemplate drain = new JmsTemplate(connectionFactory);
        drain.setReceiveTimeout(100);
        while (drain.receive(SALES) != null) {
            // discard the leftover message
        }
    }

    @Test
    public void httpGetToFlowUrlSentMessage() throws Exception {
        HttpTestClient.Response result = HttpTestClient.localhost(port).post("/sales", "application/json", MESSAGE);
        assertNotNull(result);
        assertFalse(result.body().length == 0, "response carries no payload");
        assertEquals("200", String.valueOf(result.status()));

        JmsTemplate receiver = new JmsTemplate(connectionFactory);
        receiver.setReceiveTimeout(1500);
        Message response = receiver.receive(SALES);
        assertNotNull(response);
        assertNotNull(receiver.getMessageConverter().fromMessage(response));
    }
}
