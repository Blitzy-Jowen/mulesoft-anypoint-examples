/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_json_data_to_a_amqp_queue.controller;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;

import com.mulesoft.examples.sending_json_data_to_a_amqp_queue.exception.ErrorResponse;
import com.mulesoft.examples.sending_json_data_to_a_amqp_queue.service.SalesPublisherService;
import com.mulesoft.examples.sending_json_data_to_a_amqp_queue.support.HttpTestClient;

/**
 * Wire-level test of {@link SalesController}, the {@code http:listener allowedMethods="POST" path="/"} of flow
 * {@code json-to-rabbitmqFlow} (sending-json-data-to-a-amqp-queue/src/main/app/json-to-rabbitmq.xml:6-9): a
 * {@code POST /} body is published request-response and the AMQP reply is the response, while other methods,
 * unserved paths and a failed publish get the listener's and runtime's default error responses.
 *
 * <p>The whole application runs on Undertow (random port) and requests go through ST-1 {@link HttpTestClient}, so the
 * raw status line with its reason phrase, the headers and the body bytes are asserted as the listener writes them.
 * {@link SalesPublisherService} is a mock, reset after every case, so no AMQP broker is needed: the application
 * declares no exchange, queue or listener container, and the broker connection is opened lazily on a real publish
 * only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SalesControllerTest {

    private static final String TEXT_PLAIN = "text/plain";

    /** The body the original test posts ({@code REPLY_OK} in SendingJsonDataToAmqpQueueIT.java). */
    private static final String ORIGINAL_TEST_BODY = "test";

    private static final String METHOD_NOT_ALLOWED_ROOT = "Method not allowed for endpoint: /";

    @LocalServerPort
    private int port;

    @MockBean
    private SalesPublisherService service;

    @Test
    void postReturnsReplyBytesWithoutContentType() {
        when(service.publish(any())).thenReturn("reply".getBytes(UTF_8));

        HttpTestClient.Response response = client().post("/", TEXT_PLAIN, ORIGINAL_TEST_BODY);

        assertStatusLine(response, 200, "OK");
        assertEquals("reply", response.bodyAsString(), response::toString);
        // The original listener answered with the AMQP reply, which declared no content type.
        assertNull(response.header("Content-Type"), response::toString);
        assertEquals("5", response.header("Content-Length"), response::toString);
        ArgumentCaptor<byte[]> published = ArgumentCaptor.forClass(byte[].class);
        verify(service).publish(published.capture());
        assertArrayEquals(ORIGINAL_TEST_BODY.getBytes(UTF_8), published.getValue());
    }

    @Test
    void postWithoutReplyReturnsEmptyOk() {
        // null is what the service returns when no reply arrives within the endpoint's 10000 ms responseTimeout.
        when(service.publish(any())).thenReturn(null);

        HttpTestClient.Response response = client().post("/", TEXT_PLAIN, ORIGINAL_TEST_BODY);

        assertStatusLine(response, 200, "OK");
        assertEquals(0, response.body().length, response::toString);
        verify(service).publish(any());
    }

    @Test
    void emptyPostPassesEmptyArrayNotNull() {
        when(service.publish(any())).thenReturn(null);

        // An empty String body is sent with Content-Length: 0.
        HttpTestClient.Response response = client().post("/", TEXT_PLAIN, "");

        assertStatusLine(response, 200, "OK");
        assertEquals(0, response.body().length, response::toString);
        ArgumentCaptor<byte[]> published = ArgumentCaptor.forClass(byte[].class);
        verify(service).publish(published.capture());
        assertNotNull(published.getValue(), "an absent body must reach the service as an empty array");
        assertEquals(0, published.getValue().length);
    }

    @Test
    void getIsMethodNotAllowed() {
        HttpTestClient.Response response = client().get("/");

        assertStatusLine(response, 405, "Method Not Allowed");
        assertContentTypeCompatible(response, MediaType.TEXT_PLAIN);
        assertEquals(METHOD_NOT_ALLOWED_ROOT, response.bodyAsString(), response::toString);
        verifyNoInteractions(service);
    }

    @Test
    void optionsIsMethodNotAllowed() {
        // Without the controller's explicit OPTIONS mapping Spring MVC would answer 200 with an Allow header.
        HttpTestClient.Response response = client().send("OPTIONS", "/", Map.of(), null);

        assertStatusLine(response, 405, "Method Not Allowed");
        assertContentTypeCompatible(response, MediaType.TEXT_PLAIN);
        assertEquals(METHOD_NOT_ALLOWED_ROOT, response.bodyAsString(), response::toString);
        verifyNoInteractions(service);
    }

    @Test
    void unknownPathIsNoListener() {
        HttpTestClient.Response response = client().post("/sales", TEXT_PLAIN, ORIGINAL_TEST_BODY);

        assertStatusLine(response, 404, "Not Found");
        assertContentTypeCompatible(response, MediaType.TEXT_PLAIN);
        assertEquals("No listener for endpoint: /sales", response.bodyAsString(), response::toString);
        verifyNoInteractions(service);
    }

    @Test
    void brokerFailureIsJson500() {
        AmqpConnectException ex = new AmqpConnectException(new ConnectException("Connection refused"));
        // Spring AMQP's AmqpException(Throwable) takes the cause's toString() as its message.
        assertEquals("java.net.ConnectException: Connection refused", ex.getMessage());
        when(service.publish(any())).thenThrow(ex);

        HttpTestClient.Response response = client().post("/", TEXT_PLAIN, ORIGINAL_TEST_BODY);

        assertStatusLine(response, 500, "Internal Server Error");
        assertContentTypeCompatible(response, MediaType.APPLICATION_JSON);
        assertEquals(ErrorResponse.json(ex.getMessage()), response.bodyAsString(), response::toString);
        verify(service).publish(any());
    }

    private HttpTestClient client() {
        return HttpTestClient.localhost(port);
    }

    private static void assertStatusLine(HttpTestClient.Response response, int status, String reason) {
        assertEquals(status, response.status(), response::toString);
        assertEquals(reason, response.reason(), response::toString);
    }

    private static void assertContentTypeCompatible(HttpTestClient.Response response, MediaType expected) {
        String contentType = response.header("Content-Type");
        assertNotNull(contentType, response::toString);
        assertTrue(MediaType.parseMediaType(contentType).isCompatibleWith(expected),
                () -> "Content-Type " + contentType + " is not compatible with " + expected + "\n" + response);
    }
}
