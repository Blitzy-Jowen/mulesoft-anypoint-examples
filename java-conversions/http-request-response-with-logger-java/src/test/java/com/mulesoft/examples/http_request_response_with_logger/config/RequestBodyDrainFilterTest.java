/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_request_response_with_logger.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Tests {@link RequestBodyDrainFilter} in two layers.
 *
 * <ul>
 *   <li>Wire layer: the application on Undertow at a random port, sent a body-less {@code GET} and a {@code POST}
 *       with a body and {@code Connection: close} pipelined in one socket write, on a path the listener serves
 *       and, where the listener leaves paths unserved, on one it does not serve. Without the filter Undertow closes
 *       such a connection with no response at all whenever the handler leaves the second body unread; the Mule
 *       listener answered both requests.</li>
 *   <li>Unit layer: the filter around a {@link MockFilterChain}, checking that the unread body is drained after the
 *       chain and that draining never changes the chain's outcome: a chain exception propagates as thrown even
 *       when draining fails, a drain failure never fails a completed request, and a body read through
 *       {@code getReader()} or owned by asynchronous processing is left alone.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestBodyDrainFilterTest {

    /** Connect timeout and socket read timeout of the pipelining exchange, in milliseconds. */
    private static final int TIMEOUT_MILLIS = 10_000;

    private static final Pattern STATUS_LINE = Pattern.compile("HTTP/1\\.1 (\\d{3})(?: .*)?");

    private static final byte[] CRLF = {'\r', '\n'};

    private static final byte[] HEAD_TERMINATOR = {'\r', '\n', '\r', '\n'};

    @LocalServerPort
    private int port;

    @ParameterizedTest(name = "{0} -> {1}, {2}")
    @CsvSource({
            "/message, 200, 200"
    })
    void pipelinedRequestsBehindABodilessRequestAreAllAnswered(String path, int firstStatus, int secondStatus)
            throws IOException {
        String pipelined = "GET " + path + " HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "\r\n"
                + "POST " + path + " HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Content-Length: 5\r\n"
                + "Connection: close\r\n"
                + "\r\n"
                + "hello";

        byte[] received = exchange(pipelined.getBytes(StandardCharsets.US_ASCII));

        assertEquals(List.of(firstStatus, secondStatus), statusCodes(received),
                () -> "responses received: " + new String(received, StandardCharsets.ISO_8859_1));
    }

    @ParameterizedTest(name = "body {0} bytes, handler reads {1}")
    @CsvSource({
            "0,     0",
            "5,     0",
            "5,     2",
            "5,     5",
            "20000, 0",
            "20000, 9000"
    })
    void unreadBodyIsDrainedAfterTheChain(int bodyLength, int readByHandler) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/");
        request.setContent(new byte[bodyLength]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = chain((req, res) -> {
            req.getInputStream().readNBytes(readByHandler);
            res.setStatus(HttpServletResponse.SC_ACCEPTED);
            res.getWriter().write("handled");
        });

        new RequestBodyDrainFilter().doFilter(request, response, chain);

        assertNotNull(chain.getRequest(), "the chain ran");
        assertTrue(request.getInputStream().isFinished(), "the body was read to its end");
        assertEquals(0, request.getInputStream().available());
        assertEquals(HttpServletResponse.SC_ACCEPTED, response.getStatus());
        assertEquals("handled", response.getContentAsString());
    }

    @ParameterizedTest(name = "{0} from the chain, {1} failing")
    @MethodSource("chainFailures")
    void chainExceptionPropagatesUnchangedWhenDrainingFails(Exception chainFailure, DrainFailure drainFailure) {
        HttpServletRequest request = drainFailure.wrap(new MockHttpServletRequest("POST", "/"));
        MockFilterChain chain = chain((req, res) -> rethrow(chainFailure));

        Exception thrown = assertThrows(Exception.class,
                () -> new RequestBodyDrainFilter().doFilter(request, new MockHttpServletResponse(), chain));

        assertSame(chainFailure, thrown);
        assertEquals(0, thrown.getSuppressed().length, "nothing from the drain is attached");
    }

    static Stream<Arguments> chainFailures() {
        return Stream.of(DrainFailure.values()).flatMap(drainFailure -> Stream.of(
                Arguments.of(new IOException("chain I/O failure"), drainFailure),
                Arguments.of(new ServletException("chain servlet failure"), drainFailure),
                Arguments.of(new IllegalStateException("chain runtime failure"), drainFailure)));
    }

    @ParameterizedTest
    @EnumSource(DrainFailure.class)
    void drainFailureNeverFailsACompletedRequest(DrainFailure drainFailure) {
        HttpServletRequest request = drainFailure.wrap(new MockHttpServletRequest("POST", "/"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = chain((req, res) -> res.setStatus(HttpServletResponse.SC_OK));

        assertDoesNotThrow(() -> new RequestBodyDrainFilter().doFilter(request, response, chain));

        assertNotNull(chain.getRequest(), "the chain ran");
        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
    }

    @Test
    void bodyReadThroughTheReaderIsLeftAlone() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/");
        request.setContent("hello".getBytes(StandardCharsets.UTF_8));
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = chain((req, res) -> {
            assertEquals('h', req.getReader().read());
            res.setStatus(HttpServletResponse.SC_OK);
        });

        assertDoesNotThrow(() -> new RequestBodyDrainFilter().doFilter(request, response, chain));

        assertEquals("ello", request.getReader().readLine(), "the rest of the body is still the reader's");
        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
    }

    @Test
    void bodyOfAnAsynchronousRequestIsLeftToItsProcessing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/");
        request.setContent("hello".getBytes(StandardCharsets.UTF_8));
        request.setAsyncSupported(true);
        MockFilterChain chain = chain((req, res) -> req.startAsync());

        new RequestBodyDrainFilter().doFilter(request, new MockHttpServletResponse(), chain);

        assertTrue(request.isAsyncStarted());
        assertFalse(request.getInputStream().isFinished(), "the drain did not read the stream");
        assertEquals(5, request.getInputStream().available());
    }

    /**
     * Where draining fails: opening the body stream, reading from a connection the client reset, or reading from a
     * stream that refuses blocking reads.
     */
    enum DrainFailure {

        OPEN {
            @Override
            HttpServletRequest wrap(MockHttpServletRequest request) {
                return new HttpServletRequestWrapper(request) {
                    @Override
                    public ServletInputStream getInputStream() throws IOException {
                        throw new IOException("Connection reset by peer");
                    }
                };
            }
        },

        READ_RESET {
            @Override
            HttpServletRequest wrap(MockHttpServletRequest request) {
                return withBody(request, new FailingServletInputStream(new IOException("Connection reset by peer")));
            }
        },

        READ_REFUSED {
            @Override
            HttpServletRequest wrap(MockHttpServletRequest request) {
                return withBody(request, new FailingServletInputStream(new IllegalStateException("Stream not ready")));
            }
        };

        abstract HttpServletRequest wrap(MockHttpServletRequest request);

        private static HttpServletRequest withBody(MockHttpServletRequest request, ServletInputStream body) {
            return new HttpServletRequestWrapper(request) {
                @Override
                public ServletInputStream getInputStream() {
                    return body;
                }
            };
        }
    }

    /** A body stream whose every read fails with one exception, an {@link IOException} or a runtime exception. */
    private static final class FailingServletInputStream extends ServletInputStream {

        private final Exception failure;

        FailingServletInputStream(Exception failure) {
            if (!(failure instanceof IOException || failure instanceof RuntimeException)) {
                throw new IllegalArgumentException("a read can only fail with an IOException or a runtime exception");
            }
            this.failure = failure;
        }

        @Override
        public int read() throws IOException {
            if (failure instanceof IOException ioException) {
                throw ioException;
            }
            throw (RuntimeException) failure;
        }

        @Override
        public boolean isFinished() {
            return false;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException("blocking test stream");
        }
    }

    /** The work of the servlet at the end of a {@link MockFilterChain}. */
    @FunctionalInterface
    private interface Handler {
        void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException;
    }

    private static MockFilterChain chain(Handler handler) {
        return new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response)
                    throws ServletException, IOException {
                handler.handle(request, response);
            }
        });
    }

    private static void rethrow(Exception failure) throws IOException, ServletException {
        if (failure instanceof IOException ioException) {
            throw ioException;
        }
        if (failure instanceof ServletException servletException) {
            throw servletException;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new IllegalArgumentException("unsupported chain failure type: " + failure.getClass().getName());
    }

    /** Writes {@code requests} in one write and returns every byte the server sends until it closes. */
    private byte[] exchange(byte[] requests) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), TIMEOUT_MILLIS);
            socket.setSoTimeout(TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write(requests);
            out.flush();
            return socket.getInputStream().readAllBytes();
        }
    }

    /**
     * Splits the bytes a server sent on one connection into its responses, framed by {@code Content-Length}, by
     * chunked transfer coding, or by the connection's end, and returns their status codes in arrival order.
     */
    private static List<Integer> statusCodes(byte[] received) {
        List<Integer> codes = new ArrayList<>();
        int offset = 0;
        while (offset < received.length) {
            int headEnd = indexOf(received, HEAD_TERMINATOR, offset);
            assertTrue(headEnd >= 0, () -> "incomplete response head after " + codes.size() + " responses");
            List<String> head = Arrays.asList(
                    new String(received, offset, headEnd - offset, StandardCharsets.ISO_8859_1).split("\r\n"));
            Matcher statusLine = STATUS_LINE.matcher(head.get(0));
            assertTrue(statusLine.matches(), () -> "malformed status line: " + head.get(0));
            codes.add(Integer.parseInt(statusLine.group(1)));
            offset = headEnd + HEAD_TERMINATOR.length;
            String contentLength = header(head, "Content-Length");
            if (contentLength != null) {
                offset += Integer.parseInt(contentLength);
                assertTrue(offset <= received.length, "body shorter than its Content-Length");
            } else if ("chunked".equalsIgnoreCase(header(head, "Transfer-Encoding"))) {
                offset = endOfChunkedBody(received, offset);
            } else {
                offset = received.length;
            }
        }
        return codes;
    }

    private static int endOfChunkedBody(byte[] received, int offset) {
        int position = offset;
        while (true) {
            int lineEnd = indexOf(received, CRLF, position);
            assertTrue(lineEnd >= 0, "incomplete chunk size line");
            String sizeLine = new String(received, position, lineEnd - position, StandardCharsets.ISO_8859_1);
            int extension = sizeLine.indexOf(';');
            int size = Integer.parseInt((extension < 0 ? sizeLine : sizeLine.substring(0, extension)).trim(), 16);
            position = lineEnd + CRLF.length;
            if (size == 0) {
                return endOfTrailer(received, position);
            }
            position += size + CRLF.length;
            assertTrue(position <= received.length, "chunk shorter than its size");
        }
    }

    private static int endOfTrailer(byte[] received, int offset) {
        int position = offset;
        while (true) {
            int lineEnd = indexOf(received, CRLF, position);
            assertTrue(lineEnd >= 0, "incomplete chunked trailer");
            if (lineEnd == position) {
                return lineEnd + CRLF.length;
            }
            position = lineEnd + CRLF.length;
        }
    }

    private static String header(List<String> head, String name) {
        for (String line : head.subList(1, head.size())) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private static int indexOf(byte[] data, byte[] target, int from) {
        for (int i = from; i <= data.length - target.length; i++) {
            if (Arrays.equals(data, i, i + target.length, target, 0, target.length)) {
                return i;
            }
        }
        return -1;
    }
}
