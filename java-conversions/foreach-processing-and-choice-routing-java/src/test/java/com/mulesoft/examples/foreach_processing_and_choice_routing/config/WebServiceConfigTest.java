package com.mulesoft.examples.foreach_processing_and_choice_routing.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import com.mulesoft.examples.foreach_processing_and_choice_routing.client.BankClient;
import com.mulesoft.examples.foreach_processing_and_choice_routing.client.CreditAgencyClient;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.CreditProfile;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.Customer;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.LoanBrokerQuoteRequest;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.LoanQuote;
import com.mulesoft.examples.foreach_processing_and_choice_routing.service.LoanBrokerService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.client.WebServiceTransportException;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.client.SoapFaultClientException;
import org.springframework.ws.transport.WebServiceMessageSender;
import org.springframework.ws.transport.http.HttpUrlConnectionMessageSender;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Transport behaviour of the outbound {@link WebServiceConfig#webServiceTemplate} through the real credit-agency and
 * bank clients and the loan broker: what reaches the wire, how often, and which exception each upstream outcome
 * raises. The template is built exactly as the configuration builds it, with no Spring context.
 * <p>
 * A connection reset, an end of stream and a read timeout after the upstream has read a whole request must leave
 * exactly that one request on the wire, because the original sends each call once and the loan flow answers any
 * failure with its catch branch: the JDK re-sends a buffered POST once on a reset or end of stream, so the template
 * streams every request with its fixed length instead. These cases run against a raw loopback {@link ServerSocket}
 * that handles its connections concurrently, records each complete request it reads and hangs up without answering,
 * so a re-sent request is recorded even while the first connection is still held open. The loan broker is failed
 * both at its credit-agency lookup and, after the credit agency has answered, at its first bank.
 * <p>
 * The same server answers the success and error cases, which proves that each request is still framed by
 * {@code Content-Length}, never chunked, carries {@code SOAPAction: ""}, and that replies, SOAP faults and HTTP errors
 * are read as before. Every port is ephemeral.
 */
class WebServiceConfigTest {

    private static final String HOST = InetAddress.getLoopbackAddress().getHostAddress();

    private static final String SOAP_11_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    private static final String CREDIT_AGENCY_NS = "http://creditagency.loanbroker.example.mule.org/";

    private static final String BANK_NS = "http://bank.loanbroker.example.mule.org/";

    private static final String CREDIT_AGENCY_TARGET = "/mule/TheCreditAgencyService/*";

    private static final String BANK_1_TARGET = "/mule/TheBank1/*";

    private static final String BANK_2_TARGET = "/mule/TheBank2/*";

    private static final String TEXT_XML_UTF8 = "text/xml; charset=UTF-8";

    /** The configured {@code loanbroker.response-timeout}, the original {@code responseTimeout}. */
    private static final long RESPONSE_TIMEOUT_MILLIS = 10000;

    /** Short enough that a read-timeout case ends quickly, long enough for every loopback answer on a busy host. */
    private static final long FAILURE_TIMEOUT_MILLIS = 1000;

    private static final String NAME = "Muley";

    private static final int SSN = 1234;

    private static final int CREDIT_SCORE = 742;

    private static final int CREDIT_HISTORY = 7;

    private static final Answer CREDIT_PROFILE_ANSWER = Answer.ok(envelope(
            "<ns2:getCreditProfileResponse xmlns:ns2=\"" + CREDIT_AGENCY_NS + "\"><return>"
                    + "<creditHistory>" + CREDIT_HISTORY + "</creditHistory>"
                    + "<creditScore>" + CREDIT_SCORE + "</creditScore>"
                    + "</return></ns2:getCreditProfileResponse>"));

    private static final String FAULT_STRING = "credit agency unavailable";

    private static final Answer FAULT_ANSWER = new Answer(500, "Internal Server Error", TEXT_XML_UTF8, envelope(
            "<soap:Fault><faultcode>soap:Server</faultcode><faultstring>" + FAULT_STRING
                    + "</faultstring></soap:Fault>"));

    private static final Answer NOT_FOUND_ANSWER =
            new Answer(404, "Not Found", "text/plain; charset=UTF-8", "No listener for endpoint");

    static Stream<Arguments> hangUpsByCall() {
        return Arrays.stream(HangUp.values())
                .flatMap(hangUp -> Arrays.stream(Call.values()).map(call -> Arguments.of(hangUp, call)));
    }

    @ParameterizedTest(name = "{1} hung up by {0}")
    @MethodSource("hangUpsByCall")
    void aRequestTheUpstreamHangsUpOnIsSentOnceAndFailsWithWebServiceIOException(HangUp hangUp, Call call)
            throws Exception {
        List<ReceivedRequest> received;
        WebServiceIOException failure;
        try (LoopbackSoapServer server = new LoopbackSoapServer(call.answers, hangUp)) {
            Clients clients = Clients.of(productionTemplate(FAILURE_TIMEOUT_MILLIS), server.port());

            failure = assertThrows(WebServiceIOException.class, () -> call.invoke(clients));

            received = server.closeAndAwaitRequests();
            assertThat(server.errors()).isEmpty();
        }

        assertThat(received).extracting(ReceivedRequest::target).containsExactlyElementsOf(call.expectedTargets);
        assertThat(failure).isExactlyInstanceOf(WebServiceIOException.class);
        assertThat(causeChain(failure)).hasAtLeastOneElementOfType(hangUp.expectedCause);
    }

    @Test
    void everyRequestIsSentOnceWithItsContentLengthAndEveryReplyIsRead() throws Exception {
        Map<String, Answer> answers = Map.of(
                CREDIT_AGENCY_TARGET, CREDIT_PROFILE_ANSWER,
                BANK_1_TARGET, bankAnswer("Bank #1", "3.5"),
                BANK_2_TARGET, bankAnswer("Bank #2", "2.25"));
        List<ReceivedRequest> received;
        CreditProfile creditProfile;
        LoanQuote loanQuote;
        String lowestQuote;
        try (LoopbackSoapServer server = new LoopbackSoapServer(answers, null)) {
            Clients clients = Clients.of(productionTemplate(RESPONSE_TIMEOUT_MILLIS), server.port());

            creditProfile = clients.creditAgency().getCreditProfile(new Customer(NAME, SSN));
            loanQuote = clients.bank().getLoanQuote(clients.bank1(), quoteRequest());
            lowestQuote = clients.loanBroker().quote(NAME, Integer.toString(SSN), "20000", "48");

            received = server.closeAndAwaitRequests();
            assertThat(server.errors()).isEmpty();
        }

        assertThat(creditProfile.getCreditScore()).isEqualTo(CREDIT_SCORE);
        assertThat(creditProfile.getCreditHistory()).isEqualTo(CREDIT_HISTORY);
        assertThat(loanQuote.getBankName()).isEqualTo("Bank #1");
        assertThat(loanQuote.getInterestRate()).isEqualTo(3.5);
        assertThat(lowestQuote).isEqualTo("Bank #2, rate: 2.25");

        assertThat(received).extracting(ReceivedRequest::target).containsExactly(CREDIT_AGENCY_TARGET, BANK_1_TARGET,
                CREDIT_AGENCY_TARGET, BANK_1_TARGET, BANK_2_TARGET);
        for (ReceivedRequest request : received) {
            assertThat(request.requestLine()).isEqualTo("POST " + request.target() + " HTTP/1.1");
            assertThat(request.header("Transfer-Encoding")).isNull();
            assertThat(request.header("Content-Length")).isEqualTo(Integer.toString(request.body().length));
            assertThat(request.header("SOAPAction")).isEqualTo("\"\"");
            assertThat(request.header("Content-Type")).startsWith("text/xml");
            assertThat(request.header("Accept-Encoding")).isEqualTo("gzip");

            Element operation = rootOfBody(parse(request.body()));
            if (request.target().equals(CREDIT_AGENCY_TARGET)) {
                assertThat(operation.getNamespaceURI()).isEqualTo(CREDIT_AGENCY_NS);
                assertThat(operation.getLocalName()).isEqualTo("getCreditProfile");
                assertThat(operation.getElementsByTagName("name").item(0).getTextContent()).isEqualTo(NAME);
                assertThat(operation.getElementsByTagName("ssn").item(0).getTextContent())
                        .isEqualTo(Integer.toString(SSN));
            }
            else {
                assertThat(operation.getNamespaceURI()).isEqualTo(BANK_NS);
                assertThat(operation.getLocalName()).isEqualTo("getLoanQuote");
                assertThat(operation.getElementsByTagName("creditScore").item(0).getTextContent())
                        .isEqualTo(Integer.toString(CREDIT_SCORE));
            }
        }
    }

    static Stream<Arguments> errorAnswers() {
        return Stream.of(
                Arguments.of(FAULT_ANSWER, SoapFaultClientException.class, FAULT_STRING),
                Arguments.of(NOT_FOUND_ANSWER, WebServiceTransportException.class, "Not Found [404]"));
    }

    @ParameterizedTest(name = "HTTP {0}")
    @MethodSource("errorAnswers")
    void anErrorAnswerIsReadOnceAndSurfacesUnwrapped(Answer answer, Class<? extends Exception> expected,
            String message) throws Exception {
        List<ReceivedRequest> received;
        Exception failure;
        try (LoopbackSoapServer server = new LoopbackSoapServer(Map.of(CREDIT_AGENCY_TARGET, answer), null)) {
            Clients clients = Clients.of(productionTemplate(RESPONSE_TIMEOUT_MILLIS), server.port());

            failure = assertThrows(expected, () -> clients.creditAgency().getCreditProfile(new Customer(NAME, SSN)));

            received = server.closeAndAwaitRequests();
            assertThat(server.errors()).isEmpty();
        }

        assertThat(received).extracting(ReceivedRequest::target).containsExactly(CREDIT_AGENCY_TARGET);
        assertThat(failure).isExactlyInstanceOf(expected).hasMessage(message);
    }

    @Test
    void theTemplateHasOneHttpUrlConnectionSenderBoundedByTheResponseTimeout() throws Exception {
        WebServiceTemplate template = productionTemplate(RESPONSE_TIMEOUT_MILLIS);

        WebServiceMessageSender[] senders = template.getMessageSenders();
        assertThat(senders).hasSize(1);
        assertThat(senders[0]).isInstanceOf(HttpUrlConnectionMessageSender.class);
        assertThat(ReflectionTestUtils.getField(senders[0], "connectionTimeout"))
                .isEqualTo(Duration.ofMillis(RESPONSE_TIMEOUT_MILLIS));
        assertThat(ReflectionTestUtils.getField(senders[0], "readTimeout"))
                .isEqualTo(Duration.ofMillis(RESPONSE_TIMEOUT_MILLIS));
        assertThat(template.getDefaultUri()).isNull();
        assertThat(template.getInterceptors()).singleElement()
                .isInstanceOf(WebServiceConfig.SoapActionHeaderInterceptor.class);
    }

    /** The template as {@link WebServiceConfig} builds it, initialised as the container initialises the bean. */
    private static WebServiceTemplate productionTemplate(long responseTimeoutMillis) throws Exception {
        Jaxb2Marshaller marshaller = WebServiceConfig.loanBrokerMarshaller();
        marshaller.afterPropertiesSet();
        WebServiceConfig config = new WebServiceConfig(null, marshaller);
        WebServiceTemplate template = config.webServiceTemplate(config.messageFactory(), marshaller,
                responseTimeoutMillis);
        template.afterPropertiesSet();
        return template;
    }

    /** A bank request carrying only a credit profile, as the loan broker sends it. */
    private static LoanBrokerQuoteRequest quoteRequest() {
        CreditProfile creditProfile = new CreditProfile();
        creditProfile.setCreditScore(CREDIT_SCORE);
        creditProfile.setCreditHistory(CREDIT_HISTORY);
        LoanBrokerQuoteRequest request = new LoanBrokerQuoteRequest();
        request.setCreditProfile(creditProfile);
        return request;
    }

    private static Answer bankAnswer(String bankName, String interestRate) {
        return Answer.ok(envelope("<ns2:getLoanQuoteResponse xmlns:ns2=\"" + BANK_NS + "\"><return>"
                + "<bankName>" + bankName + "</bankName>"
                + "<interestRate>" + interestRate + "</interestRate>"
                + "</return></ns2:getLoanQuoteResponse>"));
    }

    private static String envelope(String bodyContent) {
        return "<soap:Envelope xmlns:soap=\"" + SOAP_11_NS + "\"><soap:Body>" + bodyContent
                + "</soap:Body></soap:Envelope>";
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

    /** The credit-agency and bank clients and the loan broker over one template, every port that of one server. */
    private record Clients(CreditAgencyClient creditAgency, BankClient bank, LoanBrokerService loanBroker, URI bank1) {

        static Clients of(WebServiceTemplate template, int port) {
            CreditAgencyClient creditAgency = new CreditAgencyClient(template, HOST, port);
            BankClient bank = new BankClient(template);
            LoanBrokerService loanBroker = new LoanBrokerService(creditAgency, bank, HOST, port, port, port, port,
                    port);
            return new Clients(creditAgency, bank, loanBroker,
                    URI.create("http://" + HOST + ":" + port + "/mule/TheBank1"));
        }
    }

    /** One outbound call, the answers its server gives before the hang-up, and the request targets it must send. */
    enum Call {

        /** {@code CreditAgencyClient.getCreditProfile}. */
        CREDIT_AGENCY(Map.of(), List.of(CREDIT_AGENCY_TARGET)),

        /** {@code BankClient.getLoanQuote} to Bank 1. */
        BANK(Map.of(), List.of(BANK_1_TARGET)),

        /** The loan broker's quote, failing at its credit-agency lookup, so no bank is asked. */
        LOAN_BROKER_AT_CREDIT_AGENCY(Map.of(), List.of(CREDIT_AGENCY_TARGET)),

        /** The loan broker's quote, failing at Bank 1 after the credit agency answered, so Bank 2 is never asked. */
        LOAN_BROKER_AT_BANK(Map.of(CREDIT_AGENCY_TARGET, CREDIT_PROFILE_ANSWER),
                List.of(CREDIT_AGENCY_TARGET, BANK_1_TARGET));

        final Map<String, Answer> answers;

        final List<String> expectedTargets;

        Call(Map<String, Answer> answers, List<String> expectedTargets) {
            this.answers = answers;
            this.expectedTargets = expectedTargets;
        }

        void invoke(Clients clients) {
            switch (this) {
                case CREDIT_AGENCY -> clients.creditAgency().getCreditProfile(new Customer(NAME, SSN));
                case BANK -> clients.bank().getLoanQuote(clients.bank1(), quoteRequest());
                case LOAN_BROKER_AT_CREDIT_AGENCY, LOAN_BROKER_AT_BANK ->
                        clients.loanBroker().quote(NAME, Integer.toString(SSN), "20000", "48");
                default -> throw new IllegalStateException("Unhandled call " + this);
            }
        }
    }

    /** How {@link LoopbackSoapServer} ends a connection after reading one complete request it has no answer for. */
    enum HangUp {

        /** Closes the socket normally: the client reads an end of stream instead of a status line. */
        END_OF_STREAM(SocketException.class),

        /** Closes with {@code SO_LINGER} 0, so the client's read fails with a connection reset. */
        CONNECTION_RESET(SocketException.class),

        /** Keeps the connection open without answering until well past the client's read timeout. */
        READ_TIMEOUT(SocketTimeoutException.class);

        final Class<? extends IOException> expectedCause;

        HangUp(Class<? extends IOException> expectedCause) {
            this.expectedCause = expectedCause;
        }
    }

    /** An HTTP/1.1 reply {@link LoopbackSoapServer} writes, always followed by closing the connection. */
    record Answer(int status, String reason, String contentType, String body) {

        static Answer ok(String envelope) {
            return new Answer(200, "OK", TEXT_XML_UTF8, envelope);
        }

        @Override
        public String toString() {
            return status + " " + reason;
        }
    }

    /** One complete HTTP/1.1 request as {@link LoopbackSoapServer} read it off the socket. */
    record ReceivedRequest(String requestLine, Map<String, String> headers, byte[] body) {

        /** @return the request target, for example {@code /mule/TheBank1/*} */
        String target() {
            String[] parts = requestLine.split(" ");
            return parts.length == 3 ? parts[1] : requestLine;
        }

        /** @return the header's value, matched case-insensitively, or {@code null} when absent */
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * A loopback HTTP/1.1 server on an ephemeral port that reads every request completely, headers and
     * {@code Content-Length} body, and records it. A request whose target has an {@link Answer} gets that answer and
     * {@code Connection: close}; any other is hung up on as its {@link HangUp} says, without a response, or recorded
     * as an error when the server has no hang-up. Connections are handled concurrently: the acceptor thread only
     * accepts, and each connection is read and ended on a handler thread of its own, so a request the client re-sent
     * on a new connection is recorded even while an earlier connection is still held open.
     */
    private static final class LoopbackSoapServer implements AutoCloseable {

        private static final int MAX_HEADER_BYTES = 64 * 1024;

        private final Map<String, Answer> answers;

        private final HangUp hangUp;

        private final ServerSocket serverSocket;

        private final Thread acceptor;

        /** One per accepted connection, in accept order; only the acceptor adds to it. */
        private final List<Thread> handlers = new CopyOnWriteArrayList<>();

        private final CountDownLatch closing = new CountDownLatch(1);

        private final List<ReceivedRequest> requests = new CopyOnWriteArrayList<>();

        private final List<Throwable> errors = new CopyOnWriteArrayList<>();

        LoopbackSoapServer(Map<String, Answer> answers, HangUp hangUp) throws IOException {
            this.answers = answers;
            this.hangUp = hangUp;
            this.serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            this.acceptor = daemon(this::acceptConnections, "loopback-soap-" + hangUp);
            this.acceptor.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        List<Throwable> errors() {
            return errors;
        }

        /**
         * Stops accepting, releases every connection still held, waits at most 10 s in all for the acceptor and every
         * connection handler to end, and returns every request read, in the order they were read.
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
                            "loopback-soap-" + hangUp + "-connection-" + (handlers.size() + 1));
                    handlers.add(handler);
                    handler.start();
                }
                catch (IOException ex) {
                    if (!serverSocket.isClosed()) {
                        errors.add(ex);
                    }
                }
            }
        }

        /** Reads one complete request off the connection, records it, then answers it or hangs up. */
        private void handleConnection(Socket connection) {
            try (Socket socket = connection) {
                socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(10));
                ReceivedRequest request = readRequest(socket.getInputStream());
                requests.add(request);
                Answer answer = answers.get(request.target());
                if (answer != null) {
                    answer(socket.getOutputStream(), answer);
                }
                else if (hangUp != null) {
                    hangUp(socket);
                }
                else {
                    errors.add(new AssertionError("no answer for " + request.requestLine()));
                }
            }
            catch (IOException ex) {
                if (!serverSocket.isClosed()) {
                    errors.add(ex);
                }
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                errors.add(ex);
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

        private static void answer(OutputStream out, Answer answer) throws IOException {
            byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
            String head = "HTTP/1.1 " + answer.status() + " " + answer.reason() + "\r\n"
                    + "Content-Type: " + answer.contentType() + "\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
        }

        private void hangUp(Socket socket) throws IOException, InterruptedException {
            switch (hangUp) {
                case END_OF_STREAM -> socket.shutdownOutput();
                case CONNECTION_RESET -> socket.setSoLinger(true, 0);
                case READ_TIMEOUT -> {
                    long holdMillis = FAILURE_TIMEOUT_MILLIS * 4;
                    if (!closing.await(holdMillis, TimeUnit.MILLISECONDS)) {
                        errors.add(new AssertionError("the client kept waiting past its read timeout"));
                    }
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
}
