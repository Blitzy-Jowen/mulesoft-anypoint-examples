package com.mulesoft.examples.foreach_processing_and_choice_routing.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.mulesoft.examples.foreach_processing_and_choice_routing.ForeachProcessingAndChoiceRoutingApplication;
import com.mulesoft.examples.foreach_processing_and_choice_routing.exception.LoanBrokerExceptionHandler;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.CreditProfile;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.Customer;

import jakarta.servlet.http.MappingMatch;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletMapping;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.soap.SoapVersion;
import org.springframework.ws.soap.client.SoapFaultClientException;
import org.springframework.ws.test.client.MockWebServiceServer;
import org.springframework.ws.test.client.RequestMatchers;
import org.springframework.ws.test.client.ResponseCreator;
import org.springframework.ws.test.client.ResponseCreators;
import org.springframework.ws.transport.WebServiceMessageSender;
import org.springframework.ws.transport.http.HttpUrlConnectionMessageSender;
import org.springframework.xml.transform.StringSource;
import org.w3c.dom.Document;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.ComparisonType;
import org.xmlunit.diff.Diff;
import org.xmlunit.diff.DifferenceEvaluators;

/**
 * Tests {@link CreditAgencyClient}, the replacement of sub-flow {@code lookupCustomerCreditProfile}'s
 * {@code cxf:jaxws-client operation="getCreditProfile"} followed by an {@code http:request} POST to
 * {@code path="/*"} with {@code responseTimeout="10000"}, sent through {@code HTTP_Request_Configuration_1}
 * (host {@code 0.0.0.0}, port {@code 18080}, basePath {@code /mule/TheCreditAgencyService}).
 * <p>
 * Each nested class runs the client inside the application's own context, so the shared {@link WebServiceTemplate}
 * of {@code config.WebServiceConfig} is the one under test: {@link MockedTransport} checks the target URI, the
 * request's wire shape, the reply parsing and fault propagation over a mocked transport, an empty SOAP Body failing in
 * the client with the documented {@link NullPointerException}, and a {@code getCreditProfileResponse} without
 * {@code return} yielding a {@code null} profile. It also runs the loan flow through {@code LoanBrokerController} for
 * those two replies: an empty Body ends in the catch branch's {@code Error processing loan request} before any bank is
 * asked; a {@code null} profile still sends each bank a request without a credit profile and answers the lowest
 * quote. {@link WireMockTransport} sends the same two replies from a WireMock credit agency through the production
 * HTTP sender, asserting the same two outcomes and that exactly one POST reached
 * {@code /mule/TheCreditAgencyService/*}, with the empty {@code SOAPAction} and a {@code text/xml} Content-Type.
 * {@link SocketTimeout} lets the production sender call a peer that never answers.
 */
class CreditAgencyClientTest {

    private static final String NS = "http://creditagency.loanbroker.example.mule.org/";

    private static final String EXPECTED_URI = "http://0.0.0.0:18080/mule/TheCreditAgencyService/*";

    /**
     * The document/literal-wrapped request the CXF client sent: {@code arg0} and its children unqualified, and the
     * customer's properties in JAXB's default name order.
     */
    private static final String EXPECTED_REQUEST = "<ns2:getCreditProfile xmlns:ns2=\"" + NS + "\">"
            + "<arg0><name>Muley</name><ssn>1234</ssn></arg0>"
            + "</ns2:getCreditProfile>";

    private static final String RESPONSE = "<ns2:getCreditProfileResponse xmlns:ns2=\"" + NS + "\">"
            + "<return><creditHistory>10</creditHistory><creditScore>650</creditScore></return>"
            + "</ns2:getCreditProfileResponse>";

    /** A valid {@code getCreditProfileResponse} wrapper without its {@code return} child. */
    private static final String RESPONSE_WITHOUT_RETURN = "<ns2:getCreditProfileResponse xmlns:ns2=\"" + NS + "\"/>";

    /** A SOAP 1.1 envelope whose Body has no child, so the reply carries no payload to unmarshal. */
    private static final String EMPTY_BODY_ENVELOPE =
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body/></soap:Envelope>";

    /** A SOAP 1.1 envelope whose Body carries {@link #RESPONSE_WITHOUT_RETURN}, as a credit agency sends it. */
    private static final String ENVELOPE_WITHOUT_RETURN =
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
            + RESPONSE_WITHOUT_RETURN + "</soap:Body></soap:Envelope>";

    private static final String BANK_NS = "http://bank.loanbroker.example.mule.org/";

    private static final String BANK_1_TARGET = "http://0.0.0.0:10080/mule/TheBank1/*";

    private static final String BANK_2_TARGET = "http://0.0.0.0:20080/mule/TheBank2/*";

    /** The bank request the loan flow builds from a {@code null} credit profile: {@code arg0} without children. */
    private static final String BANK_REQUEST_WITHOUT_PROFILE = "<ns2:getLoanQuote xmlns:ns2=\"" + BANK_NS + "\">"
            + "<arg0/></ns2:getLoanQuote>";

    private static final String BANK_1_RESPONSE = "<ns2:getLoanQuoteResponse xmlns:ns2=\"" + BANK_NS + "\"><return>"
            + "<bankName>Bank #1</bankName><interestRate>2.5</interestRate>"
            + "</return></ns2:getLoanQuoteResponse>";

    private static final String BANK_2_RESPONSE = "<ns2:getLoanQuoteResponse xmlns:ns2=\"" + BANK_NS + "\"><return>"
            + "<bankName>Bank #2</bankName><interestRate>1.5</interestRate>"
            + "</return></ns2:getLoanQuoteResponse>";

    /**
     * The original test's request: {@link #EXPECTED_REQUEST}'s customer, and {@code amount=20000}, which routes the
     * flow to Bank 1, then Bank 2.
     */
    private static final String LOAN_QUERY = "name=Muley&amount=20000&term=48&ssn=1234";

    /** {@code loanbroker.response-timeout} of {@code application.yml}, the original {@code responseTimeout}. */
    private static final Duration CONFIGURED_RESPONSE_TIMEOUT = Duration.ofMillis(10_000);

    /**
     * Asserts that {@code actual} is identical to {@code expected} apart from namespace prefixes and whitespace.
     * <p>
     * The identical check is deliberate: XMLUnit classes a different child order as merely similar, so a similarity
     * check (and Spring WS's {@code RequestMatchers.payload}) would accept {@code ssn} before {@code name}. Namespace
     * URIs stay strict, so a qualified child fails; only the prefix is tolerated. The payload is first copied into a
     * document of its own, because the SOAP body child is an element inside the envelope's document, which XMLUnit
     * would compare against the expected document's node type.
     */
    static void assertXmlIdentical(String expected, Source actual) {
        Diff diff = DiffBuilder.compare(Input.fromString(expected))
                .withTest(Input.fromDocument(toDocument(actual)))
                .ignoreWhitespace()
                .withDifferenceEvaluator(DifferenceEvaluators.chain(DifferenceEvaluators.Default,
                        DifferenceEvaluators.downgradeDifferencesToEqual(ComparisonType.NAMESPACE_PREFIX)))
                .checkForIdentical()
                .build();
        assertFalse(diff.hasDifferences(), diff::fullDescription);
    }

    private static Document toDocument(Source source) {
        DOMResult result = new DOMResult();
        try {
            TransformerFactory.newInstance().newTransformer().transform(source, result);
        }
        catch (TransformerException ex) {
            throw new AssertionError("The payload could not be read as XML", ex);
        }
        return (Document) result.getNode();
    }

    /** Whether {@code type} occurs in the cause chain of {@code throwable}; a cyclic chain is walked once. */
    static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = throwable.getCause(); cause != null && visited.add(cause); cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    /** {@code Class.method} of the frame that threw {@code throwable}. */
    static String thrownBy(Throwable throwable) {
        StackTraceElement frame = throwable.getStackTrace()[0];
        return frame.getClassName() + "." + frame.getMethodName();
    }

    /**
     * The client over a mocked transport. The context is created fresh, so the template is guaranteed to still hold
     * the production sender when it is captured: {@link MockWebServiceServer} replaces the template's senders, and a
     * cached context could already carry another test's mock. Each test restores the production sender afterwards.
     * MockMvc drives the loan flow through {@code LoanBrokerController} and its exception handler over the same
     * mocked transport; nothing binds a port.
     */
    @Nested
    @SpringBootTest(classes = ForeachProcessingAndChoiceRoutingApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.MOCK)
    @AutoConfigureMockMvc
    @DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
    class MockedTransport {

        @Autowired
        private CreditAgencyClient client;

        @Autowired
        private WebServiceTemplate webServiceTemplate;

        @Autowired
        private MockMvc mockMvc;

        /** {@code server.port}, the main listener's port, which the loan-broker requests are received on. */
        @Value("${server.port}")
        private int mainListenerPort;

        private WebServiceMessageSender[] productionSenders;

        private MockWebServiceServer server;

        @BeforeEach
        void installMockTransport() {
            productionSenders = webServiceTemplate.getMessageSenders().clone();
            server = MockWebServiceServer.createServer(webServiceTemplate);
        }

        @AfterEach
        void restoreProductionTransport() {
            webServiceTemplate.setMessageSenders(productionSenders);
        }

        @Test
        void productionSenderUsesConfiguredTenSecondTimeouts() {
            assertThat(productionSenders).hasSize(1);
            WebServiceMessageSender sender = productionSenders[0];
            assertThat(sender).isInstanceOf(HttpUrlConnectionMessageSender.class);
            // Spring WS 4.1.4 exposes setters only for these two fields.
            assertThat(ReflectionTestUtils.getField(sender, "connectionTimeout"))
                    .isEqualTo(CONFIGURED_RESPONSE_TIMEOUT);
            assertThat(ReflectionTestUtils.getField(sender, "readTimeout")).isEqualTo(CONFIGURED_RESPONSE_TIMEOUT);
        }

        @Test
        void sendsGetCreditProfileToCreditAgencyAndParsesProfile() {
            server.expect(RequestMatchers.connectionTo(EXPECTED_URI))
                    .andExpect((uri, message) -> {
                        assertThat(((SoapMessage) message).getVersion()).isEqualTo(SoapVersion.SOAP_11);
                        assertXmlIdentical(EXPECTED_REQUEST, message.getPayloadSource());
                    })
                    .andRespond(ResponseCreators.withPayload(new StringSource(RESPONSE)));

            CreditProfile profile = client.getCreditProfile(new Customer("Muley", 1234));

            assertThat(profile.getCreditScore()).isEqualTo(650);
            assertThat(profile.getCreditHistory()).isEqualTo(10);
            server.verify();
        }

        @Test
        void soapFaultPropagatesUnwrapped() {
            server.expect(RequestMatchers.connectionTo(EXPECTED_URI))
                    .andRespond(ResponseCreators.withServerOrReceiverFault("Credit agency failure", Locale.ENGLISH));

            SoapFaultClientException fault = assertThrows(SoapFaultClientException.class,
                    () -> client.getCreditProfile(new Customer("Muley", 1234)));

            assertThat(fault.getClass()).isEqualTo(SoapFaultClientException.class);
            assertThat(fault.getFaultStringOrReason()).isEqualTo("Credit agency failure");
            assertThat(fault.getFaultCode().getNamespaceURI())
                    .isEqualTo(SoapVersion.SOAP_11.getEnvelopeNamespaceUri());
            assertThat(fault.getFaultCode().getLocalPart()).isEqualTo("Server");
            server.verify();
        }

        @Test
        void emptySoapBodyPropagatesNullPointerException() {
            expectCreditAgency(ResponseCreators.withSoapEnvelope(new StringSource(EMPTY_BODY_ENVELOPE)));

            NullPointerException failure = assertThrows(NullPointerException.class,
                    () -> client.getCreditProfile(new Customer("Muley", 1234)));

            // Thrown by the client itself: the marshaller found no payload, so there is no response to unwrap.
            assertThat(thrownBy(failure)).isEqualTo(CreditAgencyClient.class.getName() + ".getCreditProfile");
            server.verify();
        }

        @Test
        void responseWrapperWithoutReturnYieldsNullProfile() {
            expectCreditAgency(ResponseCreators.withPayload(new StringSource(RESPONSE_WITHOUT_RETURN)));

            CreditProfile profile = client.getCreditProfile(new Customer("Muley", 1234));

            assertThat(profile).isNull();
            server.verify();
        }

        /**
         * An empty Body from the credit agency fails the flow before any bank is asked, and the flow's catch branch
         * answers. Only the credit agency is scripted: a bank call would also end in the catch branch, but resolved as
         * the mock server's unexpected-connection {@link AssertionError} wrapped in a {@code ServletException}, so the
         * resolved exception is what proves no further call was made.
         */
        @Test
        void emptySoapBodyEndsLoanFlowInCatchBranchBeforeAnyBank() throws Exception {
            expectCreditAgency(ResponseCreators.withSoapEnvelope(new StringSource(EMPTY_BODY_ENVELOPE)));

            MvcResult result = requestLoan();

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .isEqualTo(LoanBrokerExceptionHandler.ERROR_PAYLOAD);
            assertThat(result.getResolvedException()).isExactlyInstanceOf(NullPointerException.class);
            assertThat(thrownBy(result.getResolvedException()))
                    .isEqualTo(CreditAgencyClient.class.getName() + ".getCreditProfile");
            server.verify();
        }

        /**
         * A {@code null} credit profile is not an error to the flow: each bank is still asked, with a request that
         * carries no credit profile, and the lowest quote is answered.
         */
        @Test
        void responseWrapperWithoutReturnSendsBanksRequestsWithoutCreditProfile() throws Exception {
            expectCreditAgency(ResponseCreators.withPayload(new StringSource(RESPONSE_WITHOUT_RETURN)));
            expectBankWithoutProfile(BANK_1_TARGET, BANK_1_RESPONSE);
            expectBankWithoutProfile(BANK_2_TARGET, BANK_2_RESPONSE);

            MvcResult result = requestLoan();

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .isEqualTo("Bank #2, rate: 1.5");
            assertThat(result.getResolvedException()).isNull();
            server.verify();
        }

        /** Scripts the next call as {@link #EXPECTED_REQUEST} to the credit agency, answered by {@code response}. */
        private void expectCreditAgency(ResponseCreator response) {
            server.expect(RequestMatchers.connectionTo(EXPECTED_URI))
                    .andExpect((uri, message) -> assertXmlIdentical(EXPECTED_REQUEST, message.getPayloadSource()))
                    .andRespond(response);
        }

        /** Scripts the next call as the profile-less bank request to {@code target}, answered with {@code reply}. */
        private void expectBankWithoutProfile(String target, String reply) {
            server.expect(RequestMatchers.connectionTo(target))
                    .andExpect((uri, message) ->
                            assertXmlIdentical(BANK_REQUEST_WITHOUT_PROFILE, message.getPayloadSource()))
                    .andRespond(ResponseCreators.withPayload(new StringSource(reply)));
        }

        /**
         * Sends {@link #LOAN_QUERY} as the main listener's container hands it to the filters: received on
         * {@code server.port} and mapped to {@code dispatcherServlet} as the default servlet, the route
         * {@code PortRoutingFilter} passes down the chain unchanged to {@code LoanBrokerController}.
         */
        private MvcResult requestLoan() throws Exception {
            return mockMvc.perform(MockMvcRequestBuilders.get("/?" + LOAN_QUERY).with(request -> {
                request.setLocalPort(mainListenerPort);
                request.setHttpServletMapping(
                        new MockHttpServletMapping("", "/", "dispatcherServlet", MappingMatch.DEFAULT));
                return request;
            })).andReturn();
        }
    }

    /**
     * The production sender posting over real HTTP to a WireMock credit agency that answers 200 with a SOAP 1.1
     * envelope carrying no credit profile. The client reads the agency's port when the context is created, so the
     * server is started by whichever of {@link #properties} and {@link #startCreditAgency()} runs first, and the
     * context is discarded after the class. The server is this class's own, stopped by its own {@code @AfterAll}
     * rather than by a registered {@code WireMockExtension}, which another container ending could stop.
     */
    @Nested
    @SpringBootTest(classes = ForeachProcessingAndChoiceRoutingApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.MOCK)
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class WireMockTransport {

        /** The path of {@link CreditAgencyClient}'s target: the original basePath followed by {@code path="/*"}. */
        private static final String TARGET_PATH = "/mule/TheCreditAgencyService/*";

        private static WireMockServer creditAgency;

        @Autowired
        private CreditAgencyClient client;

        /** Points the client at the WireMock credit agency; the MOCK environment binds no listener of its own. */
        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            int port = startedCreditAgency().port();
            registry.add("loanbroker.outbound-host", () -> "localhost");
            registry.add("ports.credit-agency", () -> port);
        }

        @BeforeAll
        static void startCreditAgency() {
            startedCreditAgency();
        }

        /** The credit agency, started on a dynamic port by the first call. */
        private static WireMockServer startedCreditAgency() {
            if (creditAgency == null) {
                WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());
                server.start();
                creditAgency = server;
            }
            return creditAgency;
        }

        @AfterAll
        static void stopCreditAgency() {
            WireMockServer server = creditAgency;
            creditAgency = null;
            if (server != null) {
                server.stop();
            }
        }

        /** Forgets the previous test's stubs and journaled requests. */
        @BeforeEach
        void resetCreditAgency() {
            creditAgency.resetAll();
        }

        @Test
        void emptySoapBodyOverHttpPropagatesNullPointerException() {
            stubCreditAgency(EMPTY_BODY_ENVELOPE);

            NullPointerException failure = assertThrows(NullPointerException.class,
                    () -> client.getCreditProfile(new Customer("Muley", 1234)));

            // Thrown by the client itself: the marshaller found no payload, so there is no response to unwrap.
            assertThat(thrownBy(failure)).isEqualTo(CreditAgencyClient.class.getName() + ".getCreditProfile");
            verifySingleSoapPost();
        }

        @Test
        void responseWrapperWithoutReturnOverHttpYieldsNullProfile() {
            stubCreditAgency(ENVELOPE_WITHOUT_RETURN);

            CreditProfile profile = client.getCreditProfile(new Customer("Muley", 1234));

            assertThat(profile).isNull();
            verifySingleSoapPost();
        }

        /** Answers a POST to {@link #TARGET_PATH} with 200 and {@code envelope} as a SOAP 1.1 message. */
        private void stubCreditAgency(String envelope) {
            creditAgency.stubFor(post(urlEqualTo(TARGET_PATH)).willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/xml;charset=UTF-8")
                    .withBody(envelope)));
        }

        /**
         * Exactly one request reached the credit agency, and it was a POST to {@link #TARGET_PATH} with the empty
         * {@code SOAPAction} and a SOAP 1.1 {@code text/xml} Content-Type: nothing was re-sent.
         */
        private void verifySingleSoapPost() {
            creditAgency.verify(1, postRequestedFor(urlEqualTo(TARGET_PATH))
                    .withHeader("SOAPAction", equalTo("\"\""))
                    .withHeader("Content-Type", containing("text/xml")));
            creditAgency.verify(1, anyRequestedFor(anyUrl()));
        }
    }

    /**
     * The production sender calling a credit agency that accepts the connection and never answers. The peer's port
     * is fixed when the context is created, so this class holds exactly one test and its context is discarded
     * afterwards; the peer lives as long as the class.
     */
    @Nested
    @SpringBootTest(classes = ForeachProcessingAndChoiceRoutingApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.MOCK)
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class SocketTimeout {

        private static final long RESPONSE_TIMEOUT_MILLIS = 500;

        private static final List<Socket> HELD_CONNECTIONS = new CopyOnWriteArrayList<>();

        private static ServerSocket silentServer;

        @Autowired
        private CreditAgencyClient client;

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) throws IOException {
            if (silentServer == null || silentServer.isClosed()) {
                // The wildcard address, so "localhost" reaches it whether it resolves to IPv4 or IPv6.
                ServerSocket server = new ServerSocket(0);
                Thread acceptor = new Thread(() -> holdConnections(server), "silent-credit-agency");
                acceptor.setDaemon(true);
                acceptor.start();
                silentServer = server;
            }
            int port = silentServer.getLocalPort();
            registry.add("loanbroker.response-timeout", () -> String.valueOf(RESPONSE_TIMEOUT_MILLIS));
            registry.add("loanbroker.outbound-host", () -> "localhost");
            registry.add("ports.credit-agency", () -> port);
        }

        /** Accepts every connection and keeps it open without reading or writing, until the server is closed. */
        private static void holdConnections(ServerSocket server) {
            while (!server.isClosed()) {
                try {
                    HELD_CONNECTIONS.add(server.accept());
                }
                catch (IOException closed) {
                    return;
                }
            }
        }

        @AfterAll
        static void closeSilentServer() throws IOException {
            ServerSocket server = silentServer;
            try (server) {
                for (Socket connection : HELD_CONNECTIONS) {
                    connection.close();
                }
            }
            finally {
                HELD_CONNECTIONS.clear();
            }
        }

        @Test
        void readTimeoutSurfacesAsWebServiceIOException() {
            long start = System.nanoTime();

            WebServiceIOException failure = assertThrows(WebServiceIOException.class,
                    () -> client.getCreditProfile(new Customer("Muley", 1234)));

            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
            assertTrue(hasCause(failure, SocketTimeoutException.class),
                    () -> "Expected a SocketTimeoutException cause, but was " + failure);
            // At least most of the configured read timeout: the request was accepted and then waited on, not refused.
            assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(RESPONSE_TIMEOUT_MILLIS - 100));
            // Well below the 10 s default: the configured timeout was applied, and the request was not re-sent.
            assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
        }
    }
}
