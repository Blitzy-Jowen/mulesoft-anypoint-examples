/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.authenticating_salesforce_using_oauth2.support;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A loopback HTTP/1.1 server that answers with scripted raw bytes, for the transport failures WireMock cannot
 * produce deterministically: an answer whose status line and headers arrive and whose body then stalls or is reset.
 *
 * <p>Each accepted connection has its request read in full and recorded, and is then answered with the next
 * {@link Reply}: its bytes are written exactly as given and followed by its {@link Ending}. A connection beyond the
 * script is recorded and reset without an answer, so a retry is counted and cannot succeed. The server binds an
 * ephemeral port on {@code 127.0.0.1} and serves one connection at a time on a daemon thread. {@link #close()} releases
 * every connection still held and reports any connection the server failed to serve.
 */
public final class LoopbackHttpStub implements AutoCloseable {

    /** How long reading a request may wait for its next bytes, in milliseconds. */
    private static final int REQUEST_READ_TIMEOUT_MILLIS = 10_000;

    /** How long {@link #close()} waits for the serving thread to end, in milliseconds. */
    private static final long SHUTDOWN_WAIT_MILLIS = 5_000;

    private static final byte[] END_OF_HEAD = {'\r', '\n', '\r', '\n'};

    /** How a connection ends once its reply has been written. */
    public enum Ending {

        /** Closed normally: the client reads the end of the stream after the reply. */
        CLOSE,

        /** Held open and silent until {@link #close()}: the client's next read waits until its read timeout. */
        STALL,

        /** Reset with {@code SO_LINGER} 0: the client's next read fails with a {@code SocketException}. */
        RESET
    }

    /**
     * One scripted answer.
     *
     * @param response the raw response, status line, headers and any body bytes included, written as UTF-8
     * @param ending   what follows the response on the connection
     */
    public record Reply(String response, Ending ending) {

        /**
         * @throws NullPointerException if {@code response} or {@code ending} is {@code null}
         */
        public Reply {
            Objects.requireNonNull(response, "response");
            Objects.requireNonNull(ending, "ending");
        }
    }

    private final ServerSocket serverSocket;

    private final List<Reply> replies;

    private final List<String> requests = new CopyOnWriteArrayList<>();

    private final List<Socket> connections = new CopyOnWriteArrayList<>();

    private final List<IOException> serveFailures = new CopyOnWriteArrayList<>();

    private final Thread server;

    private LoopbackHttpStub(ServerSocket serverSocket, List<Reply> replies) {
        this.serverSocket = serverSocket;
        this.replies = replies;
        this.server = new Thread(this::serve, "loopback-http-stub-" + serverSocket.getLocalPort());
        this.server.setDaemon(true);
    }

    /**
     * Binds an ephemeral loopback port and starts answering connections with {@code replies}, in order.
     *
     * @param replies the answers of the first connections, one each
     * @return the running server
     * @throws IOException if the port cannot be bound
     */
    public static LoopbackHttpStub start(Reply... replies) throws IOException {
        List<Reply> script = List.of(replies);
        ServerSocket serverSocket = new ServerSocket();
        try {
            serverSocket.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0));
        } catch (IOException | RuntimeException e) {
            serverSocket.close();
            throw e;
        }
        LoopbackHttpStub stub = new LoopbackHttpStub(serverSocket, script);
        stub.server.start();
        return stub;
    }

    /**
     * @return {@code http://127.0.0.1:<port>}, without a trailing {@code /}
     */
    public String baseUrl() {
        return "http://" + serverSocket.getInetAddress().getHostAddress() + ":" + serverSocket.getLocalPort();
    }

    /**
     * @return every request received so far, in arrival order: its request line, headers and body as UTF-8 text
     */
    public List<String> requests() {
        return List.copyOf(requests);
    }

    /**
     * Stops accepting, releases every connection still open and waits for the serving thread to end.
     *
     * @throws IOException the first failure to serve a connection or to release a socket, with any later ones
     *                     suppressed
     */
    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            serverSocket.close();
        } catch (IOException e) {
            failure = e;
        }
        for (Socket connection : connections) {
            try {
                connection.close();
            } catch (IOException e) {
                failure = accumulate(failure, e);
            }
        }
        try {
            server.join(SHUTDOWN_WAIT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (IOException served : serveFailures) {
            failure = accumulate(failure, served);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static IOException accumulate(IOException first, IOException next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    /** Accepts connections until the server socket is closed, answering each with the next reply of the script. */
    private void serve() {
        int next = 0;
        while (!serverSocket.isClosed()) {
            Socket connection;
            try {
                connection = serverSocket.accept();
            } catch (IOException closed) {
                // close() closed the server socket; nothing is left to accept.
                return;
            }
            connections.add(connection);
            Reply reply = next < replies.size() ? replies.get(next) : null;
            next++;
            try {
                answer(connection, reply);
            } catch (IOException e) {
                serveFailures.add(e);
                reset(connection);
            }
        }
    }

    private void answer(Socket connection, Reply reply) throws IOException {
        connection.setSoTimeout(REQUEST_READ_TIMEOUT_MILLIS);
        requests.add(readRequest(connection.getInputStream()));
        if (reply == null) {
            reset(connection);
            return;
        }
        OutputStream out = connection.getOutputStream();
        out.write(reply.response().getBytes(StandardCharsets.UTF_8));
        out.flush();
        // A STALL connection stays open and silent in connections until close() releases it.
        if (reply.ending() == Ending.CLOSE) {
            connection.close();
        } else if (reply.ending() == Ending.RESET) {
            reset(connection);
        }
    }

    /** Closes a connection with a TCP reset instead of the normal close sequence. */
    private void reset(Socket connection) {
        try {
            connection.setSoLinger(true, 0);
            connection.close();
        } catch (IOException e) {
            serveFailures.add(e);
        }
    }

    /**
     * Reads one request: its head up to the empty line, then the {@code Content-Length} bytes of its body.
     *
     * @throws IOException the connection ended early, or the request framed its body in a way this stub does not
     *                     read (chunked)
     */
    private static String readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < END_OF_HEAD.length) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("The connection ended before the request head did");
            }
            request.write(b);
            matched = b == END_OF_HEAD[matched] ? matched + 1 : (b == END_OF_HEAD[0] ? 1 : 0);
        }
        int contentLength = 0;
        for (String line : request.toString(StandardCharsets.UTF_8).split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (name.equals("content-length")) {
                contentLength = Integer.parseInt(value);
            } else if (name.equals("transfer-encoding")) {
                throw new IOException("Unsupported request framing: Transfer-Encoding " + value);
            }
        }
        byte[] body = in.readNBytes(contentLength);
        if (body.length < contentLength) {
            throw new EOFException("The connection ended after " + body.length + " of " + contentLength
                    + " request body bytes");
        }
        request.write(body);
        return request.toString(StandardCharsets.UTF_8);
    }
}
