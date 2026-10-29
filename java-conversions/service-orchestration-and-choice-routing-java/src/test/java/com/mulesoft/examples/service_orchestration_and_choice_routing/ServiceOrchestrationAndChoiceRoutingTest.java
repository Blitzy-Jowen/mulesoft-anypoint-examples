/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.mulesoft.examples.service_orchestration_and_choice_routing.controller.SamsungServiceEndpoint;
import com.mulesoft.examples.service_orchestration_and_choice_routing.support.HttpTestClient;
import com.mulesoft.examples.service_orchestration_and_choice_routing.support.PortInUseRetryingContextLoader;

/**
 * Port of the original {@code ServiceOrchestrationAndChoiceRoutingIT}: the whole application runs on Undertow, as the
 * original ran {@code fulfillment.xml} and {@code mule-config.xml}, and both original assertions are kept with their
 * comparison semantics. The SOAP order reply must equal {@code reply.xml} exactly, and {@code /populate} must answer a
 * payload containing {@code db populated}. Requests go over the wire through ST-1 {@link HttpTestClient}.
 *
 * <p>The fixed original ports 1080, 8090, 8091, 9090 and 9999 are replaced by free ports, drawn for each load attempt
 * of the context and read back from the loaded context. A fresh in-memory Derby database and a non-persistent
 * embedded Artemis broker with a server id of its own give the class its own state, which replaces the original's
 * deletion of {@code ./muleEmbeddedDB} and {@code ./ObjectStore} before the run.
 *
 * <p>Beyond the original assertions, the class checks on the same running Undertow server the request-size limit of
 * the SC-12 Bayeux endpoint on the Ajax listener: a form body of 300000 bytes is refused with 413 and {@code []},
 * whether it declares its length or is sent chunked. Only a real server shows what follows a chunked body refused
 * part-way: Undertow drains the rest, so the client receives the whole 413 and its kept-alive connection then carries
 * the copied client's handshake, which is answered.
 */
@SpringBootTest(classes = ServiceOrchestrationAndChoiceRoutingApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ContextConfiguration(loader = PortInUseRetryingContextLoader.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class ServiceOrchestrationAndChoiceRoutingTest {

    private static final String TEXT_XML_UTF8 = "text/xml; charset=UTF-8";

    /** The Bayeux endpoint on the Ajax listener. */
    private static final String COMETD = "/ajax/cometd";

    /** The content type the copied {@code dojox.cometd} client posts its {@code message} parameter with. */
    private static final String FORM = "application/x-www-form-urlencoded";

    /** The handshake {@code dojox.cometd} sends. */
    private static final String HANDSHAKE = "[{\"channel\":\"/meta/handshake\",\"version\":\"1.0\","
            + "\"minimumVersion\":\"0.9\",\"supportedConnectionTypes\":[\"long-polling\",\"callback-polling\"],"
            + "\"id\":\"0\"}]";

    /** A form body well beyond the Bayeux endpoint's 200000-byte limit. */
    private static final int OVERSIZED_BODY_BYTES = 300_000;

    /** Size of each chunk of a chunked request body. */
    private static final int CHUNK_BYTES = 8192;

    private static String MESSAGE;

    private static String REPLY;

    /** The main listener, which serves only the SOAP {@code /orders} path (originally port 1080). */
    @Value("${server.port}")
    private int mainPort;

    /** The {@code /populate} listener (originally port 8091). */
    @Value("${ports.populate}")
    private int populatePort;

    /** The Ajax listener serving the docroot and {@code /ajax/cometd} (originally port 8090). */
    @Value("${ajax.port}")
    private int ajaxPort;

    @BeforeAll
    static void loadMessages() throws IOException {
        MESSAGE = readClasspathResource("/message.xml");
        REPLY = readClasspathResource("/reply.xml");
    }

    /**
     * Binds every listener to a free port drawn for this load attempt and gives the class its own Derby database and
     * Artemis broker.
     *
     * <p>{@code application.yml} sets each additional listener's port through a placeholder ({@code ajax.port},
     * {@code ports.populate}, {@code ports.samsung}, {@code ports.prices}), so only those keys are overridden.
     * Overriding {@code listeners.additional[i].port} instead would make Spring Boot bind the whole list from this
     * property source alone and drop each entry's name and routes.
     */
    @DynamicPropertySource
    static void listenerPortsAndIsolatedStores(DynamicPropertyRegistry registry) throws IOException {
        int main;
        int ajax;
        int populate;
        int samsung;
        int prices;
        // All five sockets are open together so the ports are distinct; they close before the server binds them, and
        // when another process takes one in between, PortInUseRetryingContextLoader loads the context again, which
        // invokes this method again for newly drawn ports.
        try (ServerSocket mainSocket = new ServerSocket(0);
                ServerSocket ajaxSocket = new ServerSocket(0);
                ServerSocket populateSocket = new ServerSocket(0);
                ServerSocket samsungSocket = new ServerSocket(0);
                ServerSocket pricesSocket = new ServerSocket(0)) {
            main = mainSocket.getLocalPort();
            ajax = ajaxSocket.getLocalPort();
            populate = populateSocket.getLocalPort();
            samsung = samsungSocket.getLocalPort();
            prices = pricesSocket.getLocalPort();
        }
        registry.add("server.port", () -> main);
        registry.add("ajax.port", () -> ajax);
        registry.add("ports.populate", () -> populate);
        registry.add("ports.samsung", () -> samsung);
        registry.add("ports.prices", () -> prices);
        registry.add("orders.samsung.url", () -> "http://localhost:" + samsung + "/samsung/orders");
        registry.add("orders.price.url", () -> "http://localhost:" + prices + "/api/prices/{productId}");
        registry.add("orders.soap.url", () -> "http://localhost:" + main + "/orders");

        String databaseName = "memory:soacr-root-" + UUID.randomUUID();
        registry.add("spring.datasource.xa.properties.databaseName", () -> databaseName);
        registry.add("spring.datasource.xa.properties.createDatabase", () -> "create");

        // Boot numbers embedded Artemis servers from 0 upwards per JVM; an id from 1000 up stays clear of them, so
        // this class's in-VM acceptor never meets another cached context's broker.
        int artemisServerId = ThreadLocalRandom.current().nextInt(1_000, Integer.MAX_VALUE);
        registry.add("spring.artemis.embedded.persistent", () -> "false");
        registry.add("spring.artemis.embedded.server-id", () -> artemisServerId);
    }

    @Test
    void orderTest(CapturedOutput output) {
        // The order holds one Samsung item (s-1 / AX02, quantity 1): the flow purchases it from the in-process Samsung
        // endpoint on the samsung listener (receipt id 1, ACCEPTED, totalPrice 2550.0) and makes no in-house JMS or
        // price call. The @Async audit of totalValue "02550" runs after the reply is built and cannot change it.
        // No log line may carry the reply's customer values; the Samsung flow's INFO "aaaa" proves the log is captured.
        HttpTestClient.Response response = HttpTestClient.localhost(mainPort).post("/orders", TEXT_XML_UTF8, MESSAGE);

        assertEquals(200, response.status(), response::toString);
        assertEquals(REPLY, response.bodyAsString());
        String logged = output.getAll();
        assertTrue(logged.lines().anyMatch(line -> line.contains(SamsungServiceEndpoint.class.getSimpleName())
                && line.endsWith(": aaaa")), "The Samsung flow logger's INFO line 'aaaa' was not captured");
        for (String customerValue : List.of("Main Street 123", "John", "Doe")) {
            assertFalse(logged.contains(customerValue), () -> "Customer value '" + customerValue + "' reached the log");
        }
    }

    @Test
    void populateDatabase() {
        HttpTestClient.Response response = HttpTestClient.localhost(populatePort).get("/populate");

        assertNotNull(response);
        assertEquals(200, response.status(), response::toString);
        String payload = response.bodyAsString();
        assertFalse(payload.isEmpty(), response::toString);
        assertTrue(payload.contains("db populated"), response::toString);
    }

    @Test
    void oversizedBayeuxBodyDeclaringItsLengthIsRefused413() {
        HttpTestClient.Response response = HttpTestClient.localhost(ajaxPort).post(COMETD, FORM, oversizedForm());

        assertEquals(413, response.status(), response::toString);
        assertTrue(response.header("Content-Type").startsWith("text/json"), response::toString);
        assertEquals("[]", response.bodyAsString());
    }

    @Test
    void oversizedChunkedBayeuxBodyIsAnswered413WholeAndItsConnectionThenCarriesAHandshake() throws Exception {
        try (Socket socket = new Socket("localhost", ajaxPort)) {
            socket.setSoTimeout(HttpTestClient.TIMEOUT_MILLIS);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            AtomicReference<IOException> writeFailure = new AtomicReference<>();
            Thread writer = writeChunked(out, ajaxPort, COMETD, oversizedForm().getBytes(StandardCharsets.US_ASCII),
                    writeFailure);

            RawResponse refused = readResponse(in);
            writer.join(HttpTestClient.TIMEOUT_MILLIS);

            assertEquals(413, refused.status(), refused::toString);
            assertEquals("text/json;charset=UTF-8", refused.header("Content-Type"), refused::toString);
            assertEquals("[]", refused.body(), refused::toString);
            // The server read the body to its end, after the 413, rather than closing the connection on it.
            assertFalse(writer.isAlive(), "the chunked body was still being written");
            assertNull(writeFailure.get(), () -> "writing the chunked body failed: " + writeFailure.get());

            byte[] handshake = ("message=" + URLEncoder.encode(HANDSHAKE, StandardCharsets.UTF_8))
                    .getBytes(StandardCharsets.US_ASCII);
            out.write(("POST " + COMETD + " HTTP/1.1\r\nHost: localhost:" + ajaxPort + "\r\nContent-Type: " + FORM
                    + "\r\nContent-Length: " + handshake.length + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.write(handshake);
            out.flush();
            RawResponse answered = readResponse(in);

            assertEquals(200, answered.status(), answered::toString);
            assertTrue(answered.header("Content-Type").startsWith("text/json"), answered::toString);
            assertTrue(answered.body().contains("\"successful\":true"), answered::toString);
        }
    }

    @Test
    void bayeuxHandshakeOfTheCopiedClientIsAnswered() {
        HttpTestClient.Response response = HttpTestClient.localhost(ajaxPort)
                .post(COMETD, FORM, "message=" + URLEncoder.encode(HANDSHAKE, StandardCharsets.UTF_8));

        assertEquals(200, response.status(), response::toString);
        assertTrue(response.header("Content-Type").startsWith("text/json"), response::toString);
        assertTrue(response.bodyAsString().contains("\"successful\":true"), response::toString);
        assertTrue(response.bodyAsString().contains("\"clientId\":\""), response::toString);
    }

    /** A form body of {@link #OVERSIZED_BODY_BYTES} bytes, which the endpoint must never parse. */
    private static String oversizedForm() {
        return "message=" + "x".repeat(OVERSIZED_BODY_BYTES - "message=".length());
    }

    /**
     * Starts writing a {@code POST} of {@code body} with {@code Transfer-Encoding: chunked}, which declares no length,
     * in chunks of {@link #CHUNK_BYTES}, on a connection kept alive. The request is written on a thread of its own, so
     * the caller reads a response the server sends before the body ends; a write that fails, as when the server
     * closes the connection on the body, ends the writer and is recorded in {@code failure}.
     *
     * @return the started writer thread
     */
    private static Thread writeChunked(OutputStream out, int port, String path, byte[] body,
            AtomicReference<IOException> failure) {
        Thread writer = new Thread(() -> {
            try {
                String head = "POST " + path + " HTTP/1.1\r\nHost: localhost:" + port + "\r\nContent-Type: " + FORM
                        + "\r\nTransfer-Encoding: chunked\r\n\r\n";
                out.write(head.getBytes(StandardCharsets.US_ASCII));
                for (int offset = 0; offset < body.length; offset += CHUNK_BYTES) {
                    int length = Math.min(CHUNK_BYTES, body.length - offset);
                    out.write((Integer.toHexString(length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(body, offset, length);
                    out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                }
                out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (IOException writeFailure) {
                failure.set(writeFailure);
            }
        }, "chunked-body-writer");
        writer.setDaemon(true);
        writer.start();
        return writer;
    }

    /**
     * Reads one response off the connection: its status line, its headers and the {@code Content-Length} bytes of its
     * body, leaving the connection positioned at the next response.
     */
    private static RawResponse readResponse(InputStream in) throws IOException {
        String statusLine = readLine(in);
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
            int colon = line.indexOf(':');
            assertTrue(colon > 0, "malformed header line: " + line);
            headers.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        String contentLength = headers.get("Content-Length");
        assertNotNull(contentLength, () -> "no Content-Length in " + statusLine + " " + headers);
        int length = Integer.parseInt(contentLength);
        byte[] body = in.readNBytes(length);
        assertEquals(length, body.length, () -> "body cut short in " + statusLine + " " + headers);
        return new RawResponse(statusLine, headers, new String(body, StandardCharsets.UTF_8));
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
     * @param statusLine the status line, such as {@code HTTP/1.1 413 Request Entity Too Large}
     * @param headers the headers, names compared case-insensitively
     * @param body the body as UTF-8 text
     */
    private record RawResponse(String statusLine, Map<String, String> headers, String body) {

        int status() {
            return Integer.parseInt(statusLine.split(" ", 3)[1]);
        }

        String header(String name) {
            return headers.get(name);
        }
    }

    private static String readClasspathResource(String name) throws IOException {
        try (InputStream stream = ServiceOrchestrationAndChoiceRoutingTest.class.getResourceAsStream(name)) {
            assertNotNull(stream, () -> "Test resource " + name + " is missing from the classpath");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
