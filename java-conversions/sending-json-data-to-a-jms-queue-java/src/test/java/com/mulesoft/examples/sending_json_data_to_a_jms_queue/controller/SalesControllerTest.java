/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_json_data_to_a_jms_queue.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.DeliveryMode;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockReset;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jms.UncategorizedJmsException;
import org.springframework.jms.core.JmsTemplate;

import com.mulesoft.examples.sending_json_data_to_a_jms_queue.service.SalesQueueService;
import com.mulesoft.examples.sending_json_data_to_a_jms_queue.support.HttpTestClient;

/**
 * Wire-level test of {@link SalesController}, the {@code http:listener path="sales" allowedMethods="POST"} of flow
 * {@code json-to-jmsFlow} (sending-json-data-to-a-jms-queue/src/main/app/json-to-jms.xml:6-10): a {@code POST} body
 * is sent to queue {@code sales} as a {@code TextMessage} and echoed with 200, while other methods, unserved paths
 * and a failed send get the listener's and runtime's default error responses.
 *
 * <p>The whole application runs on Undertow (random port) with its embedded, non-persistent Artemis broker, and
 * requests go through ST-1 {@link HttpTestClient}, so the raw status line with its reason phrase, the headers and
 * the body bytes are asserted as the listener writes them. Queue {@code sales} is read back through a local
 * {@link JmsTemplate} over the application's connection factory, never through the application's own template, and
 * is drained before every case so the cached context carries no message from one case into the next.
 *
 * <p>The success {@code Content-Type} is the one the original listener derived from the request: the request's
 * {@code type/subtype} with the charset the body was decoded with (the JVM default when the request names none),
 * and no {@code Content-Type} at all when the request carries none. Media types are compared parsed, by type,
 * subtype and charset, not as raw header strings.
 *
 * <p>{@link SalesQueueService} is a spy: its real decoding, send and logging run in every case except the broker
 * failure, which stubs the send.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SalesControllerTest {

    /**
     * IPv4 loopback literal: the application binds {@code 0.0.0.0}, IPv4 only, and {@code localhost} may resolve to
     * {@code ::1}, where nothing listens.
     */
    private static final String LOOPBACK = "127.0.0.1";

    private static final String PATH = "/sales";

    private static final String QUEUE = "sales";

    /** How long the original test waited for the message on {@code jms://sales}. */
    private static final long EXPECTED_MESSAGE_TIMEOUT_MILLIS = 1_500L;

    /**
     * How long a read of queue {@code sales} waits before it counts the queue as empty. The send runs on the request
     * thread before the response is written, so a message the request produced is already queued when it is read.
     */
    private static final long EMPTY_QUEUE_TIMEOUT_MILLIS = 100L;

    /** Size of the example's sample body, which is deliberately not valid JSON and is never parsed. */
    private static final int MESSAGE_JSON_LENGTH = 73;

    @LocalServerPort
    private int port;

    @Autowired
    private ConnectionFactory connectionFactory;

    @SpyBean(reset = MockReset.AFTER)
    private SalesQueueService salesQueueService;

    @BeforeEach
    void drainQueue() {
        JmsTemplate drain = newTemplate(EMPTY_QUEUE_TIMEOUT_MILLIS);
        Message leftover;
        do {
            leftover = drain.receive(QUEUE);
        } while (leftover != null);
    }

    @Test
    void postSalesEchoesBodyAndQueuesTextMessage() throws Exception {
        byte[] message = messageJson();

        HttpTestClient.Response response = post(PATH, "application/json", message);

        assertStatusLine(response, 200, "OK");
        assertArrayEquals(message, response.body(), response::toString);
        assertMediaType(singleContentType(response),
                new MediaType("application", "json", Charset.defaultCharset()));
        assertEquals(String.valueOf(MESSAGE_JSON_LENGTH), response.header("Content-Length"), response::toString);
        TextMessage queued = receiveExpected();
        assertEquals(new String(message, Charset.defaultCharset()), queued.getText());
        assertEquals(DeliveryMode.NON_PERSISTENT, queued.getJMSDeliveryMode());
        assertNothingQueued();
    }

    @Test
    void getSalesIsMethodNotAllowed() {
        HttpTestClient.Response response = client().get(PATH);

        assertMethodNotAllowed(response);
        verifyNoInteractions(salesQueueService);
        assertNothingQueued();
    }

    @Test
    void optionsSalesIsMethodNotAllowed() {
        HttpTestClient.Response response = client().send("OPTIONS", PATH, null, null);

        assertMethodNotAllowed(response);
        verifyNoInteractions(salesQueueService);
        assertNothingQueued();
    }

    @Test
    void unknownPathHasNoListener() {
        byte[] body = "{\"sale\": 1}".getBytes(StandardCharsets.UTF_8);

        HttpTestClient.Response response = post("/unknown", "application/json", body);

        assertStatusLine(response, 404, "Not Found");
        assertMediaType(singleContentType(response), MediaType.TEXT_PLAIN);
        assertArrayEquals("No listener for endpoint: /unknown".getBytes(StandardCharsets.UTF_8), response.body(),
                response::toString);
        verifyNoInteractions(salesQueueService);
        assertNothingQueued();
    }

    @Test
    void brokerFailureReturnsJson500() throws Exception {
        doThrow(new UncategorizedJmsException("broker down")).when(salesQueueService).send(any(String.class));

        HttpTestClient.Response response = post(PATH, "application/json", messageJson());

        assertStatusLine(response, 500, "Internal Server Error");
        assertMediaType(singleContentType(response), MediaType.APPLICATION_JSON);
        assertArrayEquals("{ \"message\": \"broker down\" }".getBytes(StandardCharsets.UTF_8), response.body(),
                response::toString);
        assertNothingQueued();
    }

    @Test
    void charsetRoundTripIso88591() throws Exception {
        byte[] body = "Caf\u00e9".getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(4, body.length);
        assertEquals((byte) 0xE9, body[3]);

        HttpTestClient.Response response = post(PATH, "text/plain; charset=ISO-8859-1", body);

        assertStatusLine(response, 200, "OK");
        assertArrayEquals(body, response.body(), response::toString);
        assertMediaType(singleContentType(response), new MediaType("text", "plain", StandardCharsets.ISO_8859_1));
        assertEquals("Caf\u00e9", receiveExpected().getText());
        assertNothingQueued();
    }

    @Test
    void emptyBodyIsQueuedAsEmptyText() throws Exception {
        HttpTestClient.Response response = post(PATH, "application/json", new byte[0]);

        assertStatusLine(response, 200, "OK");
        assertEquals(0, response.body().length, response::toString);
        assertEquals("0", response.header("Content-Length"), response::toString);
        assertMediaType(singleContentType(response),
                new MediaType("application", "json", Charset.defaultCharset()));
        assertEquals("", receiveExpected().getText());
        assertNothingQueued();
    }

    /**
     * The original listener typed a request without {@code Content-Type} as <code>&#42;/&#42;</code> and wrote no
     * {@code Content-Type} for it, while the body is decoded as UTF-8, the application encoding; the non-ASCII
     * character proves that decoding whatever the JVM's default charset.
     */
    @Test
    void missingContentTypeIsDecodedAsUtf8AndAnsweredWithoutContentType() throws Exception {
        String text = "sale-without-content-type \u20ac";
        byte[] body = text.getBytes(StandardCharsets.UTF_8);

        HttpTestClient.Response response = post(PATH, null, body);

        assertStatusLine(response, 200, "OK");
        assertEquals(List.of(), response.headers("Content-Type"), response::toString);
        assertArrayEquals(body, response.body(), response::toString);
        assertEquals(text, receiveExpected().getText());
        assertNothingQueued();
    }

    private HttpTestClient client() {
        return new HttpTestClient(LOOPBACK, port);
    }

    /**
     * {@code POST} of {@code body} with {@code contentType} as the only caller header, or with none when it is
     * {@code null}; ST-1 adds {@code Host}, {@code Content-Length} and {@code Connection} itself.
     */
    private HttpTestClient.Response post(String path, String contentType, byte[] body) {
        Map<String, String> headers = contentType == null ? Map.of() : Map.of("Content-Type", contentType);
        return client().send("POST", path, headers, body);
    }

    private JmsTemplate newTemplate(long receiveTimeoutMillis) {
        JmsTemplate template = new JmsTemplate(connectionFactory);
        template.setReceiveTimeout(receiveTimeoutMillis);
        return template;
    }

    private TextMessage receiveExpected() {
        Message message = newTemplate(EXPECTED_MESSAGE_TIMEOUT_MILLIS).receive(QUEUE);
        assertNotNull(message, "no message on queue " + QUEUE + " within " + EXPECTED_MESSAGE_TIMEOUT_MILLIS + " ms");
        return assertInstanceOf(TextMessage.class, message);
    }

    private void assertNothingQueued() {
        assertNull(newTemplate(EMPTY_QUEUE_TIMEOUT_MILLIS).receive(QUEUE), "unexpected message on queue " + QUEUE);
    }

    /** The listener's 405 for every method but {@code POST}, with no {@code Allow} expectation either way. */
    private static void assertMethodNotAllowed(HttpTestClient.Response response) {
        assertStatusLine(response, 405, "Method Not Allowed");
        assertMediaType(singleContentType(response), MediaType.TEXT_PLAIN);
        assertArrayEquals("Method not allowed for endpoint: /sales".getBytes(StandardCharsets.UTF_8),
                response.body(), response::toString);
    }

    private static void assertStatusLine(HttpTestClient.Response response, int code, String reason) {
        assertEquals(code, response.status(), response::toString);
        assertEquals(reason, response.reason(), response::toString);
    }

    /** The response's one {@code Content-Type} value; the listener never writes the header twice. */
    private static String singleContentType(HttpTestClient.Response response) {
        List<String> values = response.headers("Content-Type");
        assertEquals(1, values.size(), response::toString);
        return values.get(0);
    }

    /** Compares type, subtype and charset; an expected value without charset requires the header to name none. */
    private static void assertMediaType(String actualHeader, MediaType expected) {
        MediaType actual = MediaType.parseMediaType(actualHeader);
        assertEquals(expected.getType(), actual.getType(), actualHeader);
        assertEquals(expected.getSubtype(), actual.getSubtype(), actualHeader);
        assertEquals(expected.getCharset(), actual.getCharset(), actualHeader);
    }

    private static byte[] messageJson() throws IOException {
        try (InputStream in = SalesControllerTest.class.getResourceAsStream("/message.json")) {
            assertNotNull(in, "test resource /message.json is missing");
            byte[] bytes = in.readAllBytes();
            assertEquals(MESSAGE_JSON_LENGTH, bytes.length);
            return bytes;
        }
    }
}
