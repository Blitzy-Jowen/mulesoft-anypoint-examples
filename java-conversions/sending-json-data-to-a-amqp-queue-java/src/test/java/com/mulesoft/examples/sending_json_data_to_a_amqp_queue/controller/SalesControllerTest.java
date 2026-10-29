/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_json_data_to_a_amqp_queue.controller;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.web.filter.FormContentFilter;

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
 *
 * <p>A large form body on {@code PUT}, {@code PATCH} or {@code DELETE} is written to a raw socket in two parts, so the
 * 405 is shown to arrive before the body has been sent: the context holds no {@link FormContentFilter} to read it
 * first.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SalesControllerTest {

    private static final String TEXT_PLAIN = "text/plain";

    private static final String APPLICATION_JSON = "application/json";

    /** The body the original test posts ({@code REPLY_OK} in SendingJsonDataToAmqpQueueIT.java). */
    private static final String ORIGINAL_TEST_BODY = "test";

    private static final String METHOD_NOT_ALLOWED_ROOT = "Method not allowed for endpoint: /";

    /** A form body large enough that reading it whole before dispatch keeps the response back measurably. */
    private static final int LARGE_FORM_BYTES = 300_000;

    /** Bytes of a large form body written before its response is read; the rest is written only afterwards. */
    private static final int LARGE_FORM_PREFIX_BYTES = 1024;

    @LocalServerPort
    private int port;

    @MockBean
    private SalesPublisherService service;

    /** The running application's context, inspected for the servlet filters it registers. */
    @Autowired
    private ApplicationContext applicationContext;

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
        assertContentTypeAndBody(response, TEXT_PLAIN, METHOD_NOT_ALLOWED_ROOT);
        verifyNoInteractions(service);
    }

    @Test
    void optionsIsMethodNotAllowed() {
        // Without the controller's explicit OPTIONS mapping Spring MVC would answer 200 with an Allow header.
        HttpTestClient.Response response = client().send("OPTIONS", "/", Map.of(), null);

        assertStatusLine(response, 405, "Method Not Allowed");
        assertContentTypeAndBody(response, TEXT_PLAIN, METHOD_NOT_ALLOWED_ROOT);
        verifyNoInteractions(service);
    }

    /**
     * The listener checked the method before it read the body, so a large form body on a method outside
     * {@code allowedMethods} is never read: a form declaring {@value #LARGE_FORM_BYTES} bytes, of which only the first
     * {@value #LARGE_FORM_PREFIX_BYTES} are written, gets the whole 405 before the rest is sent, and nothing is
     * published. Boot's form-content filter, which {@code spring.mvc.formcontent.filter.enabled: false} switches off,
     * would read the whole body into memory before dispatch, so no response would arrive until the last byte had been
     * sent.
     *
     * @param method a method whose form body Boot's form-content filter would read
     * @throws IOException when the connection fails
     */
    @ParameterizedTest
    @ValueSource(strings = {"PUT", "PATCH", "DELETE"})
    void largeFormOnOtherMethodIsMethodNotAllowedBeforeItsBodyIsSent(String method) throws IOException {
        byte[] form = ("sale=" + "x".repeat(LARGE_FORM_BYTES - "sale=".length())).getBytes(UTF_8);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), HttpTestClient.TIMEOUT_MILLIS);
            socket.setSoTimeout(HttpTestClient.TIMEOUT_MILLIS);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            out.write((method + " / HTTP/1.1\r\nHost: localhost:" + port
                    + "\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: " + form.length
                    + "\r\n\r\n").getBytes(US_ASCII));
            out.write(form, 0, LARGE_FORM_PREFIX_BYTES);
            out.flush();

            RawResponse refused;
            try {
                refused = readResponse(in);
            } catch (SocketTimeoutException timeout) {
                refused = fail(String.format("No response to %s / within %d ms while %d of its %d form bytes were "
                        + "sent: the body was buffered before dispatch", method, HttpTestClient.TIMEOUT_MILLIS,
                        LARGE_FORM_PREFIX_BYTES, form.length), timeout);
            }
            writeRemainderIgnoringFailure(out, form, LARGE_FORM_PREFIX_BYTES);

            assertEquals("HTTP/1.1 405 Method Not Allowed", refused.statusLine(), refused::toString);
            assertEquals(List.of(TEXT_PLAIN), refused.headers("Content-Type"), refused::toString);
            assertArrayEquals(METHOD_NOT_ALLOWED_ROOT.getBytes(UTF_8), refused.body(), refused::toString);
        }
        verifyNoInteractions(service);
    }

    /**
     * The application registers no {@link FormContentFilter}, the filter that reads a {@code PUT}, {@code PATCH} or
     * {@code DELETE} form body before dispatch, because {@code spring.mvc.formcontent.filter.enabled} is
     * {@code false}.
     */
    @Test
    void contextHoldsNoFormContentFilter() {
        assertArrayEquals(new String[0], applicationContext.getBeanNamesForType(FormContentFilter.class),
                "FormContentFilter beans");
    }

    @Test
    void unknownPathIsNoListener() {
        HttpTestClient.Response response = client().post("/sales", TEXT_PLAIN, ORIGINAL_TEST_BODY);

        assertStatusLine(response, 404, "Not Found");
        assertContentTypeAndBody(response, TEXT_PLAIN, "No listener for endpoint: /sales");
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
        assertContentTypeAndBody(response, APPLICATION_JSON,
                "{ \"message\": \"java.net.ConnectException: Connection refused\" }");
        verify(service).publish(any());
    }

    private HttpTestClient client() {
        return HttpTestClient.localhost(port);
    }

    private static void assertStatusLine(HttpTestClient.Response response, int status, String reason) {
        assertEquals(status, response.status(), response::toString);
        assertEquals(reason, response.reason(), response::toString);
    }

    /**
     * Asserts that the response carries exactly one {@code Content-Type} header, whose value is exactly
     * {@code contentType} as the listener writes it (no added parameter such as a charset), and that the body bytes
     * are exactly the UTF-8 encoding of {@code body}.
     */
    private static void assertContentTypeAndBody(HttpTestClient.Response response, String contentType, String body) {
        assertEquals(List.of(contentType), response.headers("Content-Type"), response::toString);
        assertArrayEquals(body.getBytes(UTF_8), response.body(), response::toString);
    }

    /**
     * Writes the bytes of {@code body} from {@code offset} to its end once the response has been read, so the server
     * can finish the exchange; a failed write is ignored, because the server may close a connection whose body it
     * refused unread.
     */
    private static void writeRemainderIgnoringFailure(OutputStream out, byte[] body, int offset) {
        try {
            out.write(body, offset, body.length - offset);
            out.flush();
        } catch (IOException closedByServer) {
            // The response is already read; the server need not take the rest of a body it never reads.
        }
    }

    /**
     * Reads one response off the connection: its status line, its header lines and the {@code Content-Length} bytes
     * of its body.
     */
    private static RawResponse readResponse(InputStream in) throws IOException {
        String statusLine = readLine(in);
        List<String> headerLines = new ArrayList<>();
        for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
            headerLines.add(line);
        }
        RawResponse head = new RawResponse(statusLine, headerLines, new byte[0]);
        List<String> contentLength = head.headers("Content-Length");
        assertEquals(1, contentLength.size(), head::toString);
        int length = Integer.parseInt(contentLength.get(0));
        byte[] body = in.readNBytes(length);
        assertEquals(length, body.length, head::toString);
        return new RawResponse(statusLine, headerLines, body);
    }

    /** One line of a response head without its CRLF; fails when the connection ends first. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int c = in.read(); c != '\n'; c = in.read()) {
            if (c < 0) {
                throw new EOFException("connection closed in a response head after \"" + line + "\"");
            }
            if (c != '\r') {
                line.append((char) c);
            }
        }
        return line.toString();
    }

    /**
     * A response read off a raw connection.
     *
     * @param statusLine  the status line, such as {@code HTTP/1.1 405 Method Not Allowed}
     * @param headerLines the header lines in arrival order
     * @param body        the body bytes
     */
    private record RawResponse(String statusLine, List<String> headerLines, byte[] body) {

        /** The trimmed values, in arrival order, of the header lines named {@code name}, compared ignoring case. */
        List<String> headers(String name) {
            List<String> values = new ArrayList<>();
            for (String line : headerLines) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                    values.add(line.substring(colon + 1).trim());
                }
            }
            return values;
        }

        @Override
        public String toString() {
            return statusLine + " " + headerLines + " " + new String(body, UTF_8);
        }
    }
}
