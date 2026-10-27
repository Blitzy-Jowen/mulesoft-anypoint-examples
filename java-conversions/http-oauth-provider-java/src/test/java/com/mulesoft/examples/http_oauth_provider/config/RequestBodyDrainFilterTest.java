/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_oauth_provider.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import io.undertow.servlet.api.FilterMappingInfo;
import io.undertow.servlet.spec.ServletContextImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.http_oauth_provider.support.HttpTestClient;
import com.mulesoft.examples.http_oauth_provider.support.HttpTestClient.Response;

/**
 * Tests {@link RequestBodyDrainFilter} and its {@link RequestBodyDrainRegistration} in three layers.
 *
 * <ul>
 *   <li>Wire layer: the application on Undertow, the resource listener at a random port and the SC-10 provider
 *       listener at a port reserved on the loopback interface before the context starts, since
 *       {@link PortRoutingFilter} tells the listeners apart by a concrete local port. Each case sends a body-less
 *       {@code GET} and a {@code POST} with a 64-byte body and {@code Connection: close} pipelined in one socket
 *       write: to {@code /resources} with a valid {@code READ_RESOURCE} token and without one, to {@code /token} on
 *       the provider listener, and to paths the receiving listener does not serve, which the routing filter
 *       answers itself without running the chain behind it. Without a drain wrapping every one of those answers,
 *       Undertow closes such a connection with no response at all; the Mule listener answered both requests. Each
 *       pipelined response must carry the status and body bytes the request gets on its own.</li>
 *   <li>Registration layer: the running deployment maps the drain filter once per dispatcher type, for every
 *       dispatcher type, ahead of every other filter and so ahead of {@code portRoutingFilter}; and its
 *       registration sorts ahead of the routing filter's whichever of the two is listed first, so the order owes
 *       nothing to bean names or definition order.</li>
 *   <li>Unit layer: the filter around a {@link MockFilterChain}, checking that the unread body is drained after the
 *       chain and that draining never changes the chain's outcome: a chain exception propagates as thrown even
 *       when draining fails, a drain failure never fails a completed request, and a body read through
 *       {@code getReader()} or owned by asynchronous processing is left alone.</li>
 * </ul>
 *
 * <p>Every bearer token is issued by the real provider endpoints, {@code POST /authorize} with the demo owner and
 * {@code GET /token} with the demo client of profile {@code test}, through the project's ST-1
 * {@link HttpTestClient}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequestBodyDrainFilterTest {

    /** Connect timeout and socket read timeout of the pipelining exchange, in milliseconds. */
    private static final int TIMEOUT_MILLIS = 10_000;

    private static final Pattern STATUS_LINE = Pattern.compile("HTTP/1\\.1 (\\d{3})(?: .*)?");

    private static final byte[] CRLF = {'\r', '\n'};

    private static final byte[] HEAD_TERMINATOR = {'\r', '\n', '\r', '\n'};

    /** Body of the pipelined {@code POST}: 64 bytes that no handler on these paths reads. */
    private static final String UNREAD_BODY = "x".repeat(64);

    /** Servlet filter name of the drain filter: its component's bean name, as in every conversion carrying it. */
    private static final String DRAIN_FILTER = "requestBodyDrainFilter";

    /** Registration name {@link ListenerPorts} gives the SC-10 routing filter. */
    private static final String PORT_ROUTING_FILTER = "portRoutingFilter";

    private static final String FORM = "application/x-www-form-urlencoded";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static int providerPort;

    @LocalServerPort
    private int port;

    @Autowired
    private Environment environment;

    @Autowired
    private WebApplicationContext applicationContext;

    /** The listener a pipelined pair is sent to. */
    enum Listener {
        /** The main listener, at the random server port: {@code /resources} and {@code /redirect}. */
        RESOURCE,
        /** The SC-10 {@code provider} listener at {@code http.provider.port}: {@code /authorize} and {@code /token}. */
        PROVIDER
    }

    @DynamicPropertySource
    static void providerListenerPort(DynamicPropertyRegistry registry) {
        // The main listener takes the random server port; the additional SC-10 listener needs a concrete one.
        try (ServerSocket providerSocket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            providerPort = providerSocket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot reserve the provider listener port", e);
        }
        int oauthProvider = providerPort;
        registry.add("http.provider.port", () -> oauthProvider);
    }

    @ParameterizedTest(name = "{0} {1}, token {2} -> {3}, {3}")
    @MethodSource("pipelinedPairs")
    void pipelinedRequestsBehindABodilessRequestAreAllAnswered(Listener listener, String path, boolean withToken,
            int status, String body) throws IOException {
        String authorization = withToken ? "Authorization: Bearer " + accessToken() + "\r\n" : "";
        String pipelined = "GET " + path + " HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + authorization
                + "\r\n"
                + "POST " + path + " HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + authorization
                + "Content-Type: text/plain\r\n"
                + "Content-Length: " + UNREAD_BODY.length() + "\r\n"
                + "Connection: close\r\n"
                + "\r\n"
                + UNREAD_BODY;

        byte[] received = exchange(portOf(listener), pipelined.getBytes(StandardCharsets.US_ASCII));

        WireResponse expected = new WireResponse(status, environment.resolveRequiredPlaceholders(body));
        assertEquals(List.of(expected, expected), responses(received),
                () -> "responses received: " + new String(received, StandardCharsets.ISO_8859_1));
    }

    /**
     * Listener, path, whether both requests carry a valid {@code READ_RESOURCE} token, and the status and exact body
     * each request gets on its own; {@code ${...}} placeholders resolve against the application's environment,
     * because the payroll {@code uri} follows {@code http.listener.port}.
     */
    static Stream<Arguments> pipelinedPairs() {
        return Stream.of(
                Arguments.of(Listener.RESOURCE, "/resources", true, 200,
                        "{\"name\":\"payroll\",\"uri\":\"http://localhost:${http.listener.port}/resources/payroll\"}"),
                Arguments.of(Listener.RESOURCE, "/resources", false, 401, "{ \"message\": \"Access token missing\" }"),
                Arguments.of(Listener.RESOURCE, "/unknown", false, 404, "No listener for endpoint: /unknown"),
                Arguments.of(Listener.PROVIDER, "/resources", false, 404, "No listener for endpoint: /resources"),
                Arguments.of(Listener.PROVIDER, "/token", false, 400,
                        "{\"error\":\"invalid_request\",\"error_description\":\"Missing parameter: grant_type\"}"));
    }

    @Test
    void drainFilterIsMappedOnceAheadOfEveryOtherFilterForEveryDispatcherType() {
        List<FilterMappingInfo> mappings = ((ServletContextImpl) applicationContext.getServletContext())
                .getDeployment().getDeploymentInfo().getFilterMappings();

        EnumSet<DispatcherType> drained = EnumSet.noneOf(DispatcherType.class);
        for (DispatcherType dispatcherType : DispatcherType.values()) {
            List<String> chain = mappings.stream()
                    .filter(mapping -> mapping.getDispatcher() == dispatcherType)
                    .map(FilterMappingInfo::getFilterName)
                    .toList();
            if (chain.contains(DRAIN_FILTER)) {
                drained.add(dispatcherType);
                assertEquals(DRAIN_FILTER, chain.get(0), () -> dispatcherType + " filter chain: " + chain);
                assertEquals(1, Collections.frequency(chain, DRAIN_FILTER),
                        () -> dispatcherType + " filter chain: " + chain);
            }
        }
        assertEquals(EnumSet.allOf(DispatcherType.class), drained, "dispatcher types the drain filter is mapped for");

        List<String> requestChain = mappings.stream()
                .filter(mapping -> mapping.getDispatcher() == DispatcherType.REQUEST)
                .map(FilterMappingInfo::getFilterName)
                .toList();
        assertTrue(requestChain.contains(PORT_ROUTING_FILTER)
                        && requestChain.indexOf(DRAIN_FILTER) < requestChain.indexOf(PORT_ROUTING_FILTER),
                () -> "REQUEST filter chain: " + requestChain);
    }

    @Test
    void drainRegistrationSortsAheadOfPortRoutingRegistrationWhicheverIsListedFirst() {
        FilterRegistrationBean<RequestBodyDrainFilter> drain =
                new RequestBodyDrainRegistration().requestBodyDrainFilterRegistration(new RequestBodyDrainFilter());
        FilterRegistrationBean<PortRoutingFilter> routing = new ListenerPorts()
                .portRoutingFilterRegistration(new ListenerPorts.Properties(null, null, null, null));
        assertEquals(DRAIN_FILTER, drain.getFilterName());
        assertEquals(Ordered.HIGHEST_PRECEDENCE, drain.getOrder());
        assertEquals(drain.getOrder(), routing.getOrder(), "both registrations ask for the same order value");

        List<List<FilterRegistrationBean<?>>> listings = List.of(List.of(drain, routing), List.of(routing, drain));
        for (List<FilterRegistrationBean<?>> listed : listings) {
            List<FilterRegistrationBean<?>> sorted = new ArrayList<>(listed);
            AnnotationAwareOrderComparator.sort(sorted);

            assertSame(drain, sorted.get(0), () -> "sorted from " + listed);
            assertSame(routing, sorted.get(1), () -> "sorted from " + listed);
        }
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

    /** A response read off the wire: its status code and its body, decoded as UTF-8. */
    private record WireResponse(int status, String body) {
    }

    private int portOf(Listener listener) {
        return listener == Listener.PROVIDER ? providerPort : port;
    }

    /** Writes {@code requests} in one write to {@code listenerPort} and returns every byte sent until it closes. */
    private static byte[] exchange(int listenerPort, byte[] requests) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", listenerPort), TIMEOUT_MILLIS);
            socket.setSoTimeout(TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write(requests);
            out.flush();
            return socket.getInputStream().readAllBytes();
        }
    }

    /**
     * Splits the bytes a server sent on one connection into its responses, framed by {@code Content-Length}, by
     * chunked transfer coding, or by the connection's end, and returns them in arrival order.
     */
    private static List<WireResponse> responses(byte[] received) {
        List<WireResponse> responses = new ArrayList<>();
        int offset = 0;
        while (offset < received.length) {
            int headEnd = indexOf(received, HEAD_TERMINATOR, offset);
            assertTrue(headEnd >= 0, () -> "incomplete response head after " + responses.size() + " responses");
            List<String> head = Arrays.asList(
                    new String(received, offset, headEnd - offset, StandardCharsets.ISO_8859_1).split("\r\n"));
            Matcher statusLine = STATUS_LINE.matcher(head.get(0));
            assertTrue(statusLine.matches(), () -> "malformed status line: " + head.get(0));
            int status = Integer.parseInt(statusLine.group(1));
            offset = headEnd + HEAD_TERMINATOR.length;
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            String contentLength = header(head, "Content-Length");
            if (contentLength != null) {
                int end = offset + Integer.parseInt(contentLength);
                assertTrue(end <= received.length, "body shorter than its Content-Length");
                body.write(received, offset, end - offset);
                offset = end;
            } else if ("chunked".equalsIgnoreCase(header(head, "Transfer-Encoding"))) {
                offset = readChunkedBody(received, offset, body);
            } else {
                body.write(received, offset, received.length - offset);
                offset = received.length;
            }
            responses.add(new WireResponse(status, body.toString(StandardCharsets.UTF_8)));
        }
        return responses;
    }

    /** Copies the chunk data starting at {@code offset} into {@code body} and returns the offset after the body. */
    private static int readChunkedBody(byte[] received, int offset, ByteArrayOutputStream body) {
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
            assertTrue(position + size + CRLF.length <= received.length, "chunk shorter than its size");
            body.write(received, position, size);
            position += size + CRLF.length;
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

    /**
     * A fresh {@code READ_RESOURCE} access token: the demo owner logs in on the provider's {@code /authorize} form,
     * and the code its 302 carries is exchanged at {@code /token} by {@code GET}. The owner and client credentials
     * are the demo values profile {@code test} configures.
     */
    private String accessToken() throws IOException {
        HttpTestClient provider = HttpTestClient.localhost(providerPort);
        String redirectUri = "http://localhost:" + port + "/redirect";
        String clientId = environment.getRequiredProperty("oauth.provider.client-id");
        Response authorization = provider.post("/authorize", FORM, form(
                "response_type", "code",
                "client_id", clientId,
                "redirect_uri", redirectUri,
                "scope", "READ_RESOURCE",
                "state", "xyz",
                "username", environment.getRequiredProperty("oauth.provider.owner.username"),
                "password", environment.getRequiredProperty("oauth.provider.owner.password")));
        assertEquals(302, authorization.status(), authorization::toString);
        String prefix = redirectUri + "?code=";
        String location = authorization.header("Location");
        assertTrue(location != null && location.startsWith(prefix), () -> "Location: " + location);
        String rest = location.substring(prefix.length());
        int end = rest.indexOf('&');
        String code = URLDecoder.decode(end < 0 ? rest : rest.substring(0, end), StandardCharsets.UTF_8);

        Response token = provider.get("/token?" + form(
                "grant_type", "authorization_code",
                "client_id", clientId,
                "client_secret", environment.getRequiredProperty("oauth.provider.client-secret"),
                "code", code,
                "redirect_uri", redirectUri));
        assertEquals(200, token.status(), token::toString);
        JsonNode grant = JSON.readTree(token.body());
        assertEquals("READ_RESOURCE", grant.path("scope").textValue());
        String accessToken = grant.path("access_token").textValue();
        assertTrue(accessToken != null && !accessToken.isBlank(), () -> "token response: " + token);
        return accessToken;
    }

    /** {@code name=value} pairs joined by {@code &}, each value form-encoded in UTF-8. */
    private static String form(String... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("Name/value pairs expected, got " + kv.length + " arguments");
        }
        StringJoiner pairs = new StringJoiner("&");
        for (int i = 0; i < kv.length; i += 2) {
            pairs.add(kv[i] + "=" + URLEncoder.encode(kv[i + 1], StandardCharsets.UTF_8));
        }
        return pairs.toString();
    }
}
