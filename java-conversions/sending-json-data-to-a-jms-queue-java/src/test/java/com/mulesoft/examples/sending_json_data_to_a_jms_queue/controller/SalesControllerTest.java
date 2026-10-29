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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.DeliveryMode;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockReset;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jms.UncategorizedJmsException;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.web.filter.FormContentFilter;

import com.mulesoft.examples.sending_json_data_to_a_jms_queue.service.SalesQueueService;
import com.mulesoft.examples.sending_json_data_to_a_jms_queue.support.HttpTestClient;

/**
 * Wire-level test of {@link SalesController}, the {@code http:listener path="sales" allowedMethods="POST"} of flow
 * {@code json-to-jmsFlow} (sending-json-data-to-a-jms-queue/src/main/app/json-to-jms.xml:6-10): a {@code POST} body
 * is sent to queue {@code sales} as a {@code TextMessage} and echoed with 200, while other methods, unserved paths
 * and a failed send get the listener's and runtime's default error responses. A form-urlencoded entity is queued and
 * echoed as the rendering of the parameter map the listener decoded from it, and one whose {@code Content-Type} or
 * body the listener rejected gets the listener's 400 or 500.
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
 *
 * <p>A large form body on {@code PUT}, {@code PATCH} or {@code DELETE} is written to a raw socket in two parts, so the
 * 405 is shown to arrive before the body has been sent: the context holds no {@link FormContentFilter} to read it
 * first.
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

    /** The media type curl's {@code -d} sends, which the Mule listener decoded into a parameter map. */
    private static final String FORM = "application/x-www-form-urlencoded";

    /** Separator of the head lines, and of the head from the body, on the HTTP/1.1 wire. */
    private static final String CRLF = "\r\n";

    /** How long the original test waited for the message on {@code jms://sales}. */
    private static final long EXPECTED_MESSAGE_TIMEOUT_MILLIS = 1_500L;

    /**
     * How long a read of queue {@code sales} waits before it counts the queue as empty. The send runs on the request
     * thread before the response is written, so a message the request produced is already queued when it is read.
     */
    private static final long EMPTY_QUEUE_TIMEOUT_MILLIS = 100L;

    /** Size of the example's sample body, which is deliberately not valid JSON and is never parsed. */
    private static final int MESSAGE_JSON_LENGTH = 73;

    /** A form body large enough that reading it whole before dispatch keeps the response back measurably. */
    private static final int LARGE_FORM_BYTES = 300_000;

    /** Bytes of a large form body written before its response is read; the rest is written only afterwards. */
    private static final int LARGE_FORM_PREFIX_BYTES = 1024;

    @LocalServerPort
    private int port;

    @Autowired
    private ConnectionFactory connectionFactory;

    /** The running application's context, inspected for the servlet filters it registers. */
    @Autowired
    private ApplicationContext applicationContext;

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

    /**
     * The other methods outside {@code allowedMethods="POST"}, an unknown token included, keep the listener's 405 on
     * {@code /sales}, as {@code GET} and {@code OPTIONS} above do, with nothing sent; a {@code HEAD} reply carries
     * the headers without the body.
     *
     * @param method the request method; {@code PUT} carries a JSON body
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"PUT", "DELETE", "PATCH", "HEAD", "postx"})
    void otherMethodOnSalesIsMethodNotAllowed(String method) {
        HttpTestClient.Response response = send(method, PATH);

        assertStatusLine(response, 405, "Method Not Allowed");
        assertMediaType(singleContentType(response), MediaType.TEXT_PLAIN);
        assertArrayEquals(bodyUnlessHead(method, "Method not allowed for endpoint: " + PATH), response.body(),
                response::toString);
        verifyNoInteractions(salesQueueService);
        assertNothingQueued();
    }

    /**
     * The listener checked the method before it read the body, so a large form body on a method outside
     * {@code allowedMethods} is never read: a form declaring {@value #LARGE_FORM_BYTES} bytes, of which only the first
     * {@value #LARGE_FORM_PREFIX_BYTES} are written, gets the whole 405 before the rest is sent, and nothing reaches
     * the transformer, the queue or the logger. Boot's form-content filter, which
     * {@code spring.mvc.formcontent.filter.enabled: false} switches off, would read the whole body into memory before
     * dispatch, so no response would arrive until the last byte had been sent.
     *
     * @param method a method whose form body Boot's form-content filter would read
     * @throws IOException when the connection fails
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"PUT", "PATCH", "DELETE"})
    void largeFormOnOtherMethodIsMethodNotAllowedBeforeItsBodyIsSent(String method) throws IOException {
        byte[] form = ("sale=" + "x".repeat(LARGE_FORM_BYTES - "sale=".length())).getBytes(StandardCharsets.US_ASCII);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(LOOPBACK, port), HttpTestClient.TIMEOUT_MILLIS);
            socket.setSoTimeout(HttpTestClient.TIMEOUT_MILLIS);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            out.write((method + " " + PATH + " HTTP/1.1" + CRLF
                    + "Host: " + LOOPBACK + ":" + port + CRLF
                    + "Content-Type: " + FORM + CRLF
                    + "Content-Length: " + form.length + CRLF
                    + CRLF).getBytes(StandardCharsets.US_ASCII));
            out.write(form, 0, LARGE_FORM_PREFIX_BYTES);
            out.flush();

            byte[] refused;
            try {
                refused = readResponse(in);
            } catch (SocketTimeoutException timeout) {
                refused = fail(String.format("No response to %s %s within %d ms while %d of its %d form bytes were "
                        + "sent: the body was buffered before dispatch", method, PATH, HttpTestClient.TIMEOUT_MILLIS,
                        LARGE_FORM_PREFIX_BYTES, form.length), timeout);
            }
            writeRemainderIgnoringFailure(out, form, LARGE_FORM_PREFIX_BYTES);

            String text = new String(refused, StandardCharsets.ISO_8859_1);
            assertTrue(text.startsWith("HTTP/1.1 405 Method Not Allowed" + CRLF), text);
            RawResponse response = RawResponse.parse(refused);
            List<String> contentTypes = response.headers("Content-Type");
            assertEquals(1, contentTypes.size(), response::toString);
            assertMediaType(contentTypes.get(0), MediaType.TEXT_PLAIN);
            assertArrayEquals(("Method not allowed for endpoint: " + PATH).getBytes(StandardCharsets.UTF_8),
                    response.body(), response::toString);
        }
        verifyNoInteractions(salesQueueService);
        assertNothingQueued();
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

    /**
     * A path carrying {@code ;} segment parameters is not the listener's path: the Mule listener compared the raw
     * path, query removed, literally before it checked the method, so every method, {@code post} in any letter case
     * and unknown tokens included, gets the no-listener 404 naming the path with its parameters, and nothing reaches
     * the transformer, the queue or the logger ({@code config/ListenerPathConfig}). A {@code HEAD} reply carries the
     * headers without the body.
     *
     * @param method the request method; {@code POST} in any letter case carries a JSON body
     * @param path   {@code /sales} followed by its segment parameters
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("pathParameterRequests")
    void pathWithSegmentParametersHasNoListener(String method, String path) {
        HttpTestClient.Response response = send(method, path);

        assertEquals("HTTP/1.1 404 Not Found", response.statusLine(), response::toString);
        assertMediaType(singleContentType(response), MediaType.TEXT_PLAIN);
        assertArrayEquals(bodyUnlessHead(method, "No listener for endpoint: " + path), response.body(),
                response::toString);
        verifyNoInteractions(salesQueueService);
        assertNothingQueued();
    }

    static Stream<Arguments> pathParameterRequests() {
        return Stream.of("POST", "post", "GET", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE", "postx")
                .flatMap(method -> Stream.of(PATH + ";x=1", PATH + ";jsessionid=ABC", PATH + ";")
                        .map(path -> Arguments.of(method, path)));
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

    /**
     * The Mule 3.8 listener decoded a form-urlencoded entity into a parameter map, which the
     * {@code byte-array-to-string-transformer} rendered as text in the map's entry-set hash order; that rendering,
     * the output of the real Mule 3.8.0 classes for this body, is what is queued and echoed. The response keeps the
     * request's type with the JVM's default charset, which also encodes the body.
     */
    @Test
    void formBodyIsQueuedAndEchoedAsParameterMapRendering() throws Exception {
        String rendering = "{QTY=1, ITEM_ID=001, PRICE=20, ITEM_NAME=Shirt}";
        byte[] expectedBody = rendering.getBytes(Charset.defaultCharset());

        HttpTestClient.Response response = post(PATH, FORM,
                "ITEM_ID=001&ITEM_NAME=Shirt&QTY=1&PRICE=20".getBytes(StandardCharsets.US_ASCII));

        assertStatusLine(response, 200, "OK");
        assertArrayEquals(expectedBody, response.body(), response::toString);
        assertMediaType(singleContentType(response),
                new MediaType("application", "x-www-form-urlencoded", Charset.defaultCharset()));
        assertEquals(String.valueOf(expectedBody.length), response.header("Content-Length"), response::toString);
        assertEquals(rendering, receiveExpected().getText());
        assertNothingQueued();
    }

    /**
     * A form entity the Mule listener rejected before the flow ran, with the status, reason phrase and body of its
     * response, as the real Guava 18.0 {@code MediaType} and Mule 3.8.0 classes answer: a malformed percent escape
     * and a {@code Content-Type} the listener could not parse or whose one charset it could not use give 400 with
     * the failure's message, empty for {@code charset=""}; two different charsets give 500
     * {@code Server encountered a problem}.
     */
    static Stream<Arguments> formsTheListenerRejected() {
        String pairs = "a=1&b=2";
        return Stream.of(
                Arguments.of(FORM, "a=%zz", 400, "Bad Request", "Cannot decode x-www-form-urlencoded payload"),
                Arguments.of(FORM + "; charset=bogus", pairs, 400, "Bad Request", "bogus"),
                Arguments.of(FORM + "; bad", pairs, 400, "Bad Request",
                        "Could not parse 'application/x-www-form-urlencoded; bad'"),
                Arguments.of(FORM + "; charset=\"\"", pairs, 400, "Bad Request", ""),
                Arguments.of(FORM + "; charset=utf-8; charset=iso-8859-1", pairs, 500, "Internal Server Error",
                        "Server encountered a problem"));
    }

    /**
     * The listener's rejection carries no {@code Content-Type} and is written before the flow runs, so nothing
     * reaches the transformer, the queue or the logger.
     */
    @ParameterizedTest(name = "{index}: [{1}] with Content-Type [{0}]")
    @MethodSource("formsTheListenerRejected")
    void formTheListenerRejectedIsAnsweredBeforeTheFlowRuns(String contentType, String body, int status,
            String reason, String expectedBody) {
        HttpTestClient.Response response = post(PATH, contentType, body.getBytes(StandardCharsets.US_ASCII));

        assertStatusLine(response, status, reason);
        assertArrayEquals(expectedBody.getBytes(StandardCharsets.US_ASCII), response.body(), response::toString);
        assertEquals(String.valueOf(expectedBody.length()), response.header("Content-Length"), response::toString);
        assertEquals(List.of(), response.headers("Content-Type"), response::toString);
        verify(salesQueueService, never()).send(any(String.class));
        assertNothingQueued();
    }

    /**
     * A form {@code Content-Type} and the charset of the response {@code Content-Type}: the JVM's default charset
     * for a header naming none, and UTF-8, the byte path's plan default, for an unusable one.
     */
    static Stream<Arguments> emptyFormContentTypes() {
        return Stream.of(
                Arguments.of(FORM, Charset.defaultCharset()),
                Arguments.of(FORM + "; charset=bogus", StandardCharsets.UTF_8));
    }

    /**
     * A form with {@code Content-Length: 0} had no entity in the Mule listener, which therefore never parsed its
     * {@code Content-Type}, so it keeps the empty payload even when its charset is unusable.
     */
    @ParameterizedTest(name = "{index}: Content-Type [{0}]")
    @MethodSource("emptyFormContentTypes")
    void emptyFormBodyIsQueuedAsEmptyText(String contentType, Charset responseCharset) throws Exception {
        HttpTestClient.Response response = post(PATH, contentType, new byte[0]);

        assertStatusLine(response, 200, "OK");
        assertEquals(0, response.body().length, response::toString);
        assertEquals("0", response.header("Content-Length"), response::toString);
        assertMediaType(singleContentType(response),
                new MediaType("application", "x-www-form-urlencoded", responseCharset));
        assertEquals("", receiveExpected().getText());
        verify(salesQueueService, never()).sendForm(any(), any());
        assertNothingQueued();
    }

    /**
     * A chunked form body was an entity in the Mule listener even with no byte in it, so it decoded to the empty
     * parameter map, rendered <code>{}</code>.
     */
    @Test
    void chunkedEmptyFormBodyIsQueuedAsEmptyMapRendering() throws Exception {
        RawResponse response = postChunkedEmptyBody(FORM);

        assertEquals(200, response.status(), response::toString);
        assertEquals("OK", response.reason(), response::toString);
        assertArrayEquals("{}".getBytes(Charset.defaultCharset()), response.body(), response::toString);
        List<String> contentTypes = response.headers("Content-Type");
        assertEquals(1, contentTypes.size(), response::toString);
        assertMediaType(contentTypes.get(0),
                new MediaType("application", "x-www-form-urlencoded", Charset.defaultCharset()));
        assertEquals("{}", receiveExpected().getText());
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

    /**
     * {@code method} on {@code path}: {@code POST} in any letter case and {@code PUT} with the JSON body
     * <code>{"sale": 1}</code> as {@code application/json}, every other method without a body or caller header.
     */
    private HttpTestClient.Response send(String method, String path) {
        if (method.equalsIgnoreCase("POST") || method.equals("PUT")) {
            return client().send(method, path, Map.of("Content-Type", "application/json"),
                    "{\"sale\": 1}".getBytes(StandardCharsets.UTF_8));
        }
        return client().send(method, path, null, null);
    }

    /** The UTF-8 bytes of {@code text}, or none for {@code HEAD}, whose reply carries no body. */
    private static byte[] bodyUnlessHead(String method, String text) {
        return method.equals("HEAD") ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * {@code POST /sales} with {@code contentType} and a zero-byte chunked body (only the last chunk
     * {@code 0\r\n\r\n}), written on a plain socket: ST-1 frames every body it sends with {@code Content-Length},
     * which would make the body a non-chunked empty one. The request asks for {@code Connection: close}, so the
     * response is read to the end of the stream; its body is the {@code Content-Length} bytes after the head, and
     * any other framing fails the test.
     */
    private RawResponse postChunkedEmptyBody(String contentType) throws IOException {
        String request = "POST " + PATH + " HTTP/1.1" + CRLF
                + "Host: " + LOOPBACK + ":" + port + CRLF
                + "Content-Type: " + contentType + CRLF
                + "Transfer-Encoding: chunked" + CRLF
                + "Connection: close" + CRLF
                + CRLF
                + "0" + CRLF + CRLF;
        byte[] raw;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(LOOPBACK, port), HttpTestClient.TIMEOUT_MILLIS);
            socket.setSoTimeout(HttpTestClient.TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            raw = socket.getInputStream().readAllBytes();
        }
        return RawResponse.parse(raw);
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
     * Reads one response off a connection that stays open, without waiting for its end: the head up to the blank
     * line, then the {@code Content-Length} bytes of the body. Returns them with their CRLFs, as
     * {@link RawResponse#parse(byte[])} takes them; a connection that ends first fails the read.
     */
    private static byte[] readResponse(InputStream in) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        List<String> contentLengths = new ArrayList<>();
        String line = readLine(in);
        while (!line.isEmpty()) {
            raw.writeBytes((line + CRLF).getBytes(StandardCharsets.ISO_8859_1));
            line = readLine(in);
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                contentLengths.add(line.substring(colon + 1).trim());
            }
        }
        raw.writeBytes(CRLF.getBytes(StandardCharsets.US_ASCII));
        String head = raw.toString(StandardCharsets.ISO_8859_1);
        assertEquals(1, contentLengths.size(), () -> "response not framed by one Content-Length: " + head);
        int length = Integer.parseInt(contentLengths.get(0));
        byte[] body = in.readNBytes(length);
        if (body.length < length) {
            throw new EOFException("connection closed after " + body.length + " of " + length + " body bytes: " + head);
        }
        raw.writeBytes(body);
        return raw.toByteArray();
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

    /**
     * A response read from a raw socket by {@link #postChunkedEmptyBody(String)} or {@link #readResponse(InputStream)}:
     * the code and reason phrase of its status line, its header lines in arrival order, and its {@code Content-Length}
     * body.
     */
    private record RawResponse(int status, String reason, List<Map.Entry<String, String>> headerLines, byte[] body) {

        private static final byte[] HEAD_END = (CRLF + CRLF).getBytes(StandardCharsets.US_ASCII);

        /** Parses a complete response framed by {@code Content-Length}; any other shape fails the test. */
        static RawResponse parse(byte[] raw) {
            String text = new String(raw, StandardCharsets.ISO_8859_1);
            int headEnd = indexOf(raw, HEAD_END);
            assertTrue(headEnd >= 0, () -> "response has no end of head: " + text);
            String[] lines = new String(raw, 0, headEnd, StandardCharsets.ISO_8859_1).split(CRLF, -1);
            String[] statusLine = lines[0].split(" ", 3);
            assertEquals(3, statusLine.length, () -> "malformed status line: " + lines[0]);
            List<Map.Entry<String, String>> headers = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                int colon = line.indexOf(':');
                assertTrue(colon > 0, () -> "malformed header line: " + line);
                headers.add(Map.entry(line.substring(0, colon), line.substring(colon + 1).trim()));
            }
            List<String> lengths = values(headers, "Content-Length");
            assertEquals(1, lengths.size(), () -> "response not framed by one Content-Length: " + text);
            int bodyStart = headEnd + HEAD_END.length;
            assertEquals(bodyStart + Integer.parseInt(lengths.get(0)), raw.length,
                    () -> "body length differs from Content-Length: " + text);
            return new RawResponse(Integer.parseInt(statusLine[1]), statusLine[2], List.copyOf(headers),
                    Arrays.copyOfRange(raw, bodyStart, raw.length));
        }

        /** The values of every header line named {@code name}, ignoring letter case, in arrival order. */
        List<String> headers(String name) {
            return values(headerLines, name);
        }

        @Override
        public String toString() {
            return status + " " + reason + " " + headerLines + " " + new String(body, StandardCharsets.ISO_8859_1);
        }

        private static List<String> values(List<Map.Entry<String, String>> headers, String name) {
            return headers.stream().filter(header -> header.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue).toList();
        }

        private static int indexOf(byte[] data, byte[] target) {
            for (int start = 0; start + target.length <= data.length; start++) {
                if (Arrays.equals(data, start, start + target.length, target, 0, target.length)) {
                    return start;
                }
            }
            return -1;
        }
    }
}
