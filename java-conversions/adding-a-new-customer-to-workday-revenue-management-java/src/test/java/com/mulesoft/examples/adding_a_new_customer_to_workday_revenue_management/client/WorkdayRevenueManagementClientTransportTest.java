/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.soap.client.SoapFaultClientException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.config.WorkdayProperties;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.exception.UpstreamUnavailableException;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.CustomerWWSDataType;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.ObjectFactory;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.PutCustomerRequestType;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.PutCustomerResponseType;

import jakarta.xml.bind.JAXBElement;

/**
 * Transport behaviour of {@link WorkdayRevenueManagementClient#putCustomer}: what reaches the wire, how often, and
 * which exception each upstream outcome raises.
 *
 * <p>A connection reset, an end of stream and a read timeout after Workday has read the whole {@code Put_Customer}
 * request must leave exactly that one request on the wire: the JDK re-sends a buffered POST once on a reset or end of
 * stream, so the client streams every request instead. These cases run against a raw loopback {@link ServerSocket}
 * that handles its connections concurrently, counts each complete request it reads and never answers, so a re-sent
 * request is counted even while the first connection is still held open. Success, HTTP 401, HTTP 429 and a SOAP
 * fault run against WireMock, which also proves the request still carries its {@code Content-Length} header and the
 * {@code PasswordText} UsernameToken.
 *
 * <p>The executor is given {@link UnavailableOrOtherClassifier}, so a timeout or connectivity failure becomes an
 * {@link UpstreamUnavailableException} and every other failure escapes unchanged, exactly as the client raised it.
 * Every value is fake and every port is ephemeral.
 */
final class WorkdayRevenueManagementClientTransportTest {

    private static final String WD_NS = "urn:com.workday/bsvc";

    private static final String SOAP_11_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    private static final String WSSE_NS =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";

    private static final String PASSWORD_TEXT_TYPE =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordText";

    private static final String PATH = "/ccx/service/test_pt1/Revenue_Management/v25.0";

    private static final String USER = "testuser";

    private static final String TENANT = "test_pt1";

    private static final String PASSWORD = "test-password";

    private static final String CUSTOMER_NAME = "John Transport";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

    private static final String PUT_CUSTOMER_RESPONSE = "<env:Envelope xmlns:env=\"" + SOAP_11_NS + "\">"
            + "<env:Body>"
            + "<wd:Put_Customer_Response xmlns:wd=\"" + WD_NS + "\" wd:version=\"v25.0\">"
            + "<wd:Customer_Reference wd:Descriptor=\"" + CUSTOMER_NAME + "\">"
            + "<wd:ID wd:type=\"WID\">0123456789abcdef</wd:ID>"
            + "</wd:Customer_Reference>"
            + "</wd:Put_Customer_Response>"
            + "</env:Body>"
            + "</env:Envelope>";

    private static final String VALIDATION_FAULT_STRING =
            "Validation error occurred. Invalid ID value. 'NOPE' is not a valid ID value for type = 'Customer_ID'";

    private static final String VALIDATION_FAULT = "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"" + SOAP_11_NS + "\">"
            + "<SOAP-ENV:Body>"
            + "<SOAP-ENV:Fault xmlns:wd=\"" + WD_NS + "\">"
            + "<faultcode>SOAP-ENV:Client.validationError</faultcode>"
            + "<faultstring>" + VALIDATION_FAULT_STRING + "</faultstring>"
            + "<detail><wd:Validation_Fault><wd:Validation_Error>"
            + "<wd:Message>Invalid ID value. 'NOPE' is not a valid ID value for type = 'Customer_ID'</wd:Message>"
            + "<wd:Xpath>/ns0:Put_Customer_Request[1]/ns0:Customer_Reference[1]/ns0:ID[1]</wd:Xpath>"
            + "</wd:Validation_Error></wd:Validation_Fault></detail>"
            + "</SOAP-ENV:Fault>"
            + "</SOAP-ENV:Body>"
            + "</SOAP-ENV:Envelope>";

    @RegisterExtension
    static final WireMockExtension WORKDAY = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    /** Read by the client at call time, so each case points the shared client at its own server. */
    private static final WorkdayProperties PROPERTIES = new WorkdayProperties();

    /** Shared by every case: its JAXB context, created on first use, spans ~900 generated classes. */
    private static WorkdayRevenueManagementClient client;

    @BeforeAll
    static void createClient() {
        PROPERTIES.setHostname("localhost");
        PROPERTIES.setTenant(TENANT);
        PROPERTIES.setUser(USER);
        PROPERTIES.setPassword(PASSWORD);
        PROPERTIES.setConnectTimeout(CONNECT_TIMEOUT);
        PROPERTIES.setReadTimeout(READ_TIMEOUT);
        client = new WorkdayRevenueManagementClient(PROPERTIES,
                new UpstreamCallExecutor(List.of(new UnavailableOrOtherClassifier())));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(HangUp.class)
    void aFailureAfterWorkdayReadThePutCustomerIsNeverReSent(HangUp hangUp) throws Exception {
        List<ReceivedRequest> received;
        UpstreamUnavailableException failure;
        try (UnansweringServer server = new UnansweringServer(hangUp)) {
            PROPERTIES.setEndpoint(server.endpoint());

            failure = assertThrows(UpstreamUnavailableException.class, () -> client.putCustomer(putCustomerRequest()));

            received = server.closeAndAwaitRequests();
            assertThat(server.errors()).isEmpty();
        }

        assertThat(received).hasSize(1);
        ReceivedRequest request = received.get(0);
        assertThat(request.requestLine()).isEqualTo("POST " + PATH + " HTTP/1.1");
        assertThat(request.header("Transfer-Encoding")).isNull();
        assertThat(request.header("Content-Length")).isEqualTo(Integer.toString(request.body().length));
        assertThat(rootOfBody(parse(request.body())).getLocalName()).isEqualTo("Put_Customer_Request");

        assertThat(failure.getSystem()).isEqualTo(UpstreamSystem.WORKDAY);
        assertThat(failure.getCause()).isInstanceOf(WebServiceIOException.class);
        assertThat(causeChain(failure)).hasAtLeastOneElementOfType(hangUp.expectedCause);
    }

    @Test
    void putCustomerStreamsTheRequestWithItsContentLengthAndUnwrapsTheResponse() throws Exception {
        WORKDAY.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/xml;charset=UTF-8")
                .withBody(PUT_CUSTOMER_RESPONSE)));
        PROPERTIES.setEndpoint(WORKDAY.baseUrl() + PATH);

        PutCustomerResponseType response = client.putCustomer(putCustomerRequest());

        assertThat(response.getCustomerReference().getDescriptor()).isEqualTo(CUSTOMER_NAME);
        List<LoggedRequest> requests = WORKDAY.findAll(postRequestedFor(urlEqualTo(PATH)));
        assertThat(requests).hasSize(1);
        LoggedRequest request = requests.get(0);
        assertThat(request.containsHeader("Transfer-Encoding")).isFalse();
        assertThat(request.getHeader("Content-Length")).isEqualTo(Integer.toString(request.getBody().length));
        assertThat(request.getHeader("Accept-Encoding")).isEqualTo("gzip");
        assertThat(request.getHeader("SOAPAction")).isEqualTo("\"\"");
        assertThat(request.getHeader("Content-Type")).startsWith("text/xml");

        Document envelope = parse(request.getBody());
        Element body = rootOfBody(envelope);
        assertThat(body.getNamespaceURI()).isEqualTo(WD_NS);
        assertThat(body.getLocalName()).isEqualTo("Put_Customer_Request");
        assertThat(body.getElementsByTagNameNS(WD_NS, "Customer_Name").item(0).getTextContent())
                .isEqualTo(CUSTOMER_NAME);
        Element username = (Element) envelope.getElementsByTagNameNS(WSSE_NS, "Username").item(0);
        Element password = (Element) envelope.getElementsByTagNameNS(WSSE_NS, "Password").item(0);
        assertThat(username.getTextContent()).isEqualTo(USER + "@" + TENANT);
        assertThat(password.getAttribute("Type")).isEqualTo(PASSWORD_TEXT_TYPE);
        assertThat(password.getTextContent()).isEqualTo(PASSWORD);
    }

    static Stream<Arguments> httpErrorsWithoutAFault() {
        return Stream.of(
                Arguments.of(401, "Unauthorized", null),
                Arguments.of(429, "Too Many Requests", "30"));
    }

    @ParameterizedTest(name = "HTTP {0} with Retry-After {2}")
    @MethodSource("httpErrorsWithoutAFault")
    void anHttpErrorWithoutAFaultKeepsItsStatusAndRetryAfter(int status, String body, String retryAfter)
            throws Exception {
        ResponseDefinitionBuilder answer = aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "text/plain")
                .withBody(body);
        if (retryAfter != null) {
            answer = answer.withHeader("Retry-After", retryAfter);
        }
        WORKDAY.stubFor(post(urlEqualTo(PATH)).willReturn(answer));
        PROPERTIES.setEndpoint(WORKDAY.baseUrl() + PATH);

        WorkdayRevenueManagementClient.HttpStatusException failure =
                assertThrows(WorkdayRevenueManagementClient.HttpStatusException.class,
                        () -> client.putCustomer(putCustomerRequest()));

        assertThat(failure.statusCode()).isEqualTo(status);
        assertThat(failure.retryAfter()).isEqualTo(retryAfter);
        assertThat(failure).hasMessageContaining("[" + status + "]");
        assertThat(WORKDAY.findAll(postRequestedFor(urlEqualTo(PATH)))).hasSize(1);
    }

    @Test
    void aSoapFaultEscapesAsSoapFaultClientExceptionWithItsFaultString() throws Exception {
        WORKDAY.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(500)
                .withHeader("Content-Type", "text/xml;charset=UTF-8")
                .withBody(VALIDATION_FAULT)));
        PROPERTIES.setEndpoint(WORKDAY.baseUrl() + PATH);

        SoapFaultClientException failure =
                assertThrows(SoapFaultClientException.class, () -> client.putCustomer(putCustomerRequest()));

        assertThat(failure.getFaultStringOrReason()).isEqualTo(VALIDATION_FAULT_STRING);
        assertThat(failure.getFaultCode().getLocalPart()).isEqualTo("Client.validationError");
        assertThat(WORKDAY.findAll(postRequestedFor(urlEqualTo(PATH)))).hasSize(1);
    }

    /** A {@code Put_Customer_Request} built from the generated v25.0 classes. */
    private static JAXBElement<PutCustomerRequestType> putCustomerRequest() {
        ObjectFactory factory = new ObjectFactory();
        CustomerWWSDataType customerData = factory.createCustomerWWSDataType();
        customerData.setCustomerName(CUSTOMER_NAME);
        PutCustomerRequestType request = factory.createPutCustomerRequestType();
        request.setCustomerData(customerData);
        return factory.createPutCustomerRequest(request);
    }

    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    /** Returns the first element child of the envelope's SOAP 1.1 {@code Body}. */
    private static Element rootOfBody(Document envelope) {
        Node body = envelope.getElementsByTagNameNS(SOAP_11_NS, "Body").item(0);
        assertThat(body).as("SOAP 1.1 Body").isNotNull();
        for (Node child = body.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) {
                return element;
            }
        }
        throw new AssertionError("The SOAP Body carries no element");
    }

    private static List<Throwable> causeChain(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable current = failure; current != null && !chain.contains(current); current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }

    /** How {@link UnansweringServer} ends a connection after reading one complete request. */
    enum HangUp {

        /** Closes the socket normally: the client reads an end of stream instead of a status line. */
        END_OF_STREAM(SocketException.class),

        /** Closes with {@code SO_LINGER} 0, so the client's read fails with a connection reset. */
        CONNECTION_RESET(SocketException.class),

        /** Keeps the connection open without answering until the client's read timeout has passed. */
        READ_TIMEOUT(SocketTimeoutException.class);

        final Class<? extends IOException> expectedCause;

        HangUp(Class<? extends IOException> expectedCause) {
            this.expectedCause = expectedCause;
        }
    }

    /** One complete HTTP/1.1 request as {@link UnansweringServer} read it off the socket. */
    record ReceivedRequest(String requestLine, Map<String, String> headers, byte[] body) {

        /** @return the header's value, matched case-insensitively, or {@code null} when absent */
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * A loopback HTTP/1.1 server on an ephemeral port that reads every request completely, headers and
     * {@code Content-Length} body, records it, and then ends the connection as its {@link HangUp} says without ever
     * sending a response. Connections are handled concurrently: its acceptor thread only accepts, and each
     * connection is read and hung up on a handler thread of its own, so a request the client re-sent on a new
     * connection is recorded even while an earlier connection is still held open.
     */
    private static final class UnansweringServer implements AutoCloseable {

        private static final int MAX_HEADER_BYTES = 64 * 1024;

        private final HangUp hangUp;

        private final ServerSocket serverSocket;

        private final Thread acceptor;

        /** One per accepted connection, in accept order; only the acceptor adds to it. */
        private final List<Thread> handlers = new CopyOnWriteArrayList<>();

        private final CountDownLatch closing = new CountDownLatch(1);

        private final List<ReceivedRequest> requests = new CopyOnWriteArrayList<>();

        private final List<Throwable> errors = new CopyOnWriteArrayList<>();

        UnansweringServer(HangUp hangUp) throws IOException {
            this.hangUp = hangUp;
            this.serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            this.acceptor = daemon(this::acceptConnections, "unanswering-workday-" + hangUp);
            this.acceptor.start();
        }

        String endpoint() {
            return "http://" + serverSocket.getInetAddress().getHostAddress() + ":" + serverSocket.getLocalPort()
                    + PATH;
        }

        List<Throwable> errors() {
            return errors;
        }

        /**
         * Stops accepting, releases every connection still held, waits at most 10 s in all for the acceptor and every
         * connection handler to end, and returns every request read.
         */
        List<ReceivedRequest> closeAndAwaitRequests() throws IOException, InterruptedException {
            close();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            awaitEnd(acceptor, deadline);
            assertThat(acceptor.isAlive()).as("server thread ended").isFalse();
            for (Thread handler : handlers) {
                awaitEnd(handler, deadline);
            }
            assertThat(handlers).as("connection handler threads ended").noneMatch(Thread::isAlive);
            return List.copyOf(requests);
        }

        @Override
        public void close() throws IOException {
            closing.countDown();
            serverSocket.close();
        }

        /** Accepts until the server socket closes, starting one handler thread per connection. */
        private void acceptConnections() {
            while (!serverSocket.isClosed()) {
                try {
                    Socket socket = serverSocket.accept();
                    Thread handler = daemon(() -> handleConnection(socket),
                            "unanswering-workday-" + hangUp + "-connection-" + (handlers.size() + 1));
                    handlers.add(handler);
                    handler.start();
                } catch (IOException e) {
                    if (!serverSocket.isClosed()) {
                        errors.add(e);
                    }
                }
            }
        }

        /** Reads one complete request off the connection, records it, and then hangs up as {@link HangUp} says. */
        private void handleConnection(Socket connection) {
            try (Socket socket = connection) {
                socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(10));
                requests.add(readRequest(socket.getInputStream()));
                hangUp(socket);
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    errors.add(e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                errors.add(e);
            }
        }

        /** A daemon thread that records whatever it fails with in {@link #errors} instead of losing it. */
        private Thread daemon(Runnable task, String name) {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((failed, failure) -> errors.add(failure));
            return thread;
        }

        /** Waits for the thread to end, but never past the deadline, a {@link System#nanoTime()} value. */
        private static void awaitEnd(Thread thread, long deadline) throws InterruptedException {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMillis > 0) {
                thread.join(remainingMillis);
            }
        }

        private void hangUp(Socket socket) throws IOException, InterruptedException {
            switch (hangUp) {
                case END_OF_STREAM -> socket.shutdownOutput();
                case CONNECTION_RESET -> socket.setSoLinger(true, 0);
                case READ_TIMEOUT -> {
                    long holdMillis = READ_TIMEOUT.multipliedBy(4).toMillis();
                    if (closing.await(holdMillis, TimeUnit.MILLISECONDS)) {
                        return;
                    }
                    errors.add(new AssertionError("the client kept waiting past its read timeout"));
                }
                default -> throw new IllegalStateException("Unhandled hang-up " + hangUp);
            }
        }

        private static ReceivedRequest readRequest(InputStream in) throws IOException {
            String head = readHead(in);
            String[] lines = head.split("\r\n");
            Map<String, String> headers = new TreeMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) {
                    headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            lines[i].substring(colon + 1).trim());
                }
            }
            String contentLength = headers.get("content-length");
            byte[] body = contentLength == null ? new byte[0] : in.readNBytes(Integer.parseInt(contentLength));
            if (contentLength != null && body.length != Integer.parseInt(contentLength)) {
                throw new IOException("Request body ended after " + body.length + " of " + contentLength + " bytes");
            }
            return new ReceivedRequest(lines[0], headers, body);
        }

        /** Reads up to and including the blank line that ends the request head, and returns the head before it. */
        private static String readHead(InputStream in) throws IOException {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int matched = 0;
            byte[] terminator = {'\r', '\n', '\r', '\n'};
            while (matched < terminator.length) {
                int next = in.read();
                if (next == -1) {
                    throw new IOException("Request head ended after " + head.size() + " bytes");
                }
                head.write(next);
                if (head.size() > MAX_HEADER_BYTES) {
                    throw new IOException("Request head exceeds " + MAX_HEADER_BYTES + " bytes");
                }
                matched = next == terminator[matched] ? matched + 1 : (next == '\r' ? 1 : 0);
            }
            byte[] bytes = head.toByteArray();
            return new String(bytes, 0, bytes.length - terminator.length, StandardCharsets.ISO_8859_1);
        }
    }

    /**
     * Classifies Workday failures the way the executor needs to show transport behaviour: a timeout or connectivity
     * failure is unavailable, anything else is rethrown unchanged.
     */
    private static final class UnavailableOrOtherClassifier implements UpstreamFailureClassifier {

        @Override
        public UpstreamSystem system() {
            return UpstreamSystem.WORKDAY;
        }

        @Override
        public UpstreamFailureKind classify(Throwable failure) {
            return UpstreamFailureClassifier.isUnavailable(failure)
                    ? UpstreamFailureKind.UNAVAILABLE
                    : UpstreamFailureKind.OTHER;
        }
    }
}
