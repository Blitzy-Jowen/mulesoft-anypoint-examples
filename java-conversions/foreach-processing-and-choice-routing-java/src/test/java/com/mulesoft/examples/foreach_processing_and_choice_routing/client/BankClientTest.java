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

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.xml.transform.Source;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.mulesoft.examples.foreach_processing_and_choice_routing.ForeachProcessingAndChoiceRoutingApplication;
import com.mulesoft.examples.foreach_processing_and_choice_routing.exception.LoanBrokerExceptionHandler;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.CreditProfile;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.LoanBrokerQuoteRequest;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.LoanQuote;
import com.mulesoft.examples.foreach_processing_and_choice_routing.service.LoanBrokerService;

import jakarta.servlet.http.MappingMatch;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletMapping;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
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
import org.springframework.xml.transform.StringSource;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.ComparisonType;
import org.xmlunit.diff.Diff;
import org.xmlunit.diff.DifferenceEvaluators;

/**
 * {@link BankClient} inside the application context, as the loan broker's {@code lookupLoanQuote} uses it: the
 * replacement of the original sub-flow's {@code cxf:jaxws-client operation="getLoanQuote"} followed by
 * {@code http:request method="POST" path="/*" responseTimeout="10000"} against the bank URI of
 * {@code HTTP_Request_Configuration_2} ({@code loanbroker-simple.xml}, lines 6 and 96-111).
 * <p>
 * {@link MockedTransport} replaces the shared template's sender with Spring WS's mock server and asserts the wire
 * contract: the SOAP 1.1 {@code getLoanQuote} request of {@code http://bank.loanbroker.example.mule.org/} with its
 * unqualified {@code arg0} and only the credit profile set, children in JAXB's default property-name order, sent to
 * {@code <bankUri>/*} for each of the five bank URIs; the reply's {@code return} quote; a SOAP fault surfacing
 * unwrapped; an empty SOAP Body failing in the client with the documented {@link NullPointerException}; and a
 * {@code getLoanQuoteResponse} without {@code return} yielding a {@code null} quote. It also runs the loan flow through
 * {@code LoanBrokerController} for those two replies: an empty Body from Bank 1 ends in the catch branch's
 * {@code Error processing loan request} without Bank 2 being asked; a {@code null} quote from Bank 1 leaves Bank 2's
 * quote the lowest; a {@code null} quote after Bank 1's valid one ends in the catch branch.
 * {@link WireMockTransport} sends the same two replies from a WireMock bank through the production HTTP sender,
 * asserting the same two outcomes and that exactly one POST reached {@code /mule/TheBank1/*}, with the empty
 * {@code SOAPAction} and a {@code text/xml} Content-Type.
 * {@link SocketTimeout} keeps the real HTTP sender and proves a bank that never answers ends the call at the
 * configured {@code loanbroker.response-timeout}. Each nested class owns its context; no test opens a fixed port.
 */
class BankClientTest {

    private static final String NS = "http://bank.loanbroker.example.mule.org/";

    private static final String BANK_1 = "http://0.0.0.0:10080/mule/TheBank1";

    private static final String BANK_1_TARGET = BANK_1 + "/*";

    /** The request CXF's JAXB databinding wrote: only {@code creditProfile}, its children sorted by property name. */
    private static final String EXPECTED_REQUEST = "<ns2:getLoanQuote xmlns:ns2=\"" + NS + "\"><arg0>"
            + "<creditProfile><creditHistory>10</creditHistory><creditScore>650</creditScore></creditProfile>"
            + "</arg0></ns2:getLoanQuote>";

    private static final String RESPONSE = "<ns2:getLoanQuoteResponse xmlns:ns2=\"" + NS + "\"><return>"
            + "<bankName>Bank #1</bankName><interestRate>2.5</interestRate>"
            + "</return></ns2:getLoanQuoteResponse>";

    /** Bank 2's quote, lower than {@link #RESPONSE}'s, so it is the lowest whenever it is compared with Bank 1's. */
    private static final String BANK_2_RESPONSE = "<ns2:getLoanQuoteResponse xmlns:ns2=\"" + NS + "\"><return>"
            + "<bankName>Bank #2</bankName><interestRate>1.5</interestRate>"
            + "</return></ns2:getLoanQuoteResponse>";

    /** A valid {@code getLoanQuoteResponse} wrapper without its {@code return} child. */
    private static final String RESPONSE_WITHOUT_RETURN = "<ns2:getLoanQuoteResponse xmlns:ns2=\"" + NS + "\"/>";

    /** A SOAP 1.1 envelope whose Body has no child, so the reply carries no payload to unmarshal. */
    private static final String EMPTY_BODY_ENVELOPE =
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body/></soap:Envelope>";

    /** A SOAP 1.1 envelope whose Body carries {@link #RESPONSE_WITHOUT_RETURN}, as a bank sends it. */
    private static final String ENVELOPE_WITHOUT_RETURN =
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
            + RESPONSE_WITHOUT_RETURN + "</soap:Body></soap:Envelope>";

    private static final String BANK_2_TARGET = "http://0.0.0.0:20080/mule/TheBank2/*";

    /** Where the loan flow asks for the credit profile before any bank: {@code CreditAgencyClient}'s target. */
    private static final String CREDIT_AGENCY_TARGET = "http://0.0.0.0:18080/mule/TheCreditAgencyService/*";

    /** The credit agency's reply carrying the profile {@link #EXPECTED_REQUEST} sends each bank. */
    private static final String CREDIT_PROFILE_RESPONSE =
            "<ns2:getCreditProfileResponse xmlns:ns2=\"http://creditagency.loanbroker.example.mule.org/\"><return>"
            + "<creditHistory>10</creditHistory><creditScore>650</creditScore>"
            + "</return></ns2:getCreditProfileResponse>";

    /** The original test's request; {@code amount=20000} routes the flow to Bank 1, then Bank 2. */
    private static final String LOAN_QUERY = "name=Muley&amount=20000&term=48&ssn=1234";

    /**
     * The request the loan broker sends each bank: a credit profile and nothing else, as the original expression
     * component built it ({@code loanbroker-simple.xml}, lines 100-104).
     */
    static LoanBrokerQuoteRequest request() {
        CreditProfile creditProfile = new CreditProfile();
        creditProfile.setCreditScore(650);
        creditProfile.setCreditHistory(10);
        LoanBrokerQuoteRequest request = new LoanBrokerQuoteRequest();
        request.setCreditProfile(creditProfile);
        return request;
    }

    /**
     * Asserts {@code actual} is identical to {@code expected} apart from namespace prefixes. An identical rather than
     * a similar comparison, because a similar one (as {@code RequestMatchers.payload} makes) accepts reordered
     * children; namespace URIs, element order and absent elements all stay strict.
     */
    static void assertXmlIdentical(String expected, Source actual) {
        Diff diff = DiffBuilder.compare(Input.fromString(expected))
                .withTest(Input.from(actual))
                .ignoreWhitespace()
                .withDifferenceEvaluator(DifferenceEvaluators.chain(DifferenceEvaluators.Default,
                        DifferenceEvaluators.downgradeDifferencesToEqual(ComparisonType.NAMESPACE_PREFIX)))
                .checkForIdentical()
                .build();
        assertFalse(diff.hasDifferences(), diff::fullDescription);
    }

    /** Whether {@code failure} or any throwable in its cause chain is a {@code type}. */
    static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    /** {@code Class.method} of the frame that threw {@code failure}. */
    static String thrownBy(Throwable failure) {
        StackTraceElement frame = failure.getStackTrace()[0];
        return frame.getClassName() + "." + frame.getMethodName();
    }

    /**
     * The shared outbound template with its sender swapped for {@link MockWebServiceServer}. The context is created
     * fresh, and each test restores the production senders afterwards, so no other test class sharing a cached
     * context ever sees the mock sender. MockMvc drives the loan flow through {@code LoanBrokerController} and its
     * exception handler over the same mocked transport; nothing binds a port.
     */
    @Nested
    @SpringBootTest(classes = ForeachProcessingAndChoiceRoutingApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.MOCK)
    @AutoConfigureMockMvc
    @DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
    class MockedTransport {

        @Autowired
        BankClient client;

        @Autowired
        WebServiceTemplate webServiceTemplate;

        @Autowired
        MockMvc mockMvc;

        /** {@code server.port}, the main listener's port, which the loan-broker requests are received on. */
        @Value("${server.port}")
        int mainListenerPort;

        WebServiceMessageSender[] productionSenders;

        MockWebServiceServer server;

        @BeforeEach
        void installMockServer() {
            productionSenders = webServiceTemplate.getMessageSenders().clone();
            server = MockWebServiceServer.createServer(webServiceTemplate);
        }

        @AfterEach
        void restoreProductionSenders() {
            webServiceTemplate.setMessageSenders(productionSenders);
        }

        @Test
        void sendsGetLoanQuoteToBankAndParsesQuote() {
            URI bankUri = URI.create(BANK_1);
            server.expect(RequestMatchers.connectionTo(BANK_1_TARGET))
                    .andExpect((uri, message) -> {
                        assertThat(((SoapMessage) message).getVersion()).isEqualTo(SoapVersion.SOAP_11);
                        assertXmlIdentical(EXPECTED_REQUEST, message.getPayloadSource());
                    })
                    .andRespond(ResponseCreators.withPayload(new StringSource(RESPONSE)));

            LoanQuote quote = client.getLoanQuote(bankUri, request());

            assertThat(quote.getBankName()).isEqualTo("Bank #1");
            assertThat(quote.getInterestRate()).isEqualTo(2.5);
            server.verify();
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "http://0.0.0.0:10080/mule/TheBank1, http://0.0.0.0:10080/mule/TheBank1/*",
                "http://0.0.0.0:20080/mule/TheBank2, http://0.0.0.0:20080/mule/TheBank2/*",
                "http://0.0.0.0:30080/mule/TheBank3, http://0.0.0.0:30080/mule/TheBank3/*",
                "http://0.0.0.0:40080/mule/TheBank4, http://0.0.0.0:40080/mule/TheBank4/*",
                "http://0.0.0.0:50080/mule/TheBank5, http://0.0.0.0:50080/mule/TheBank5/*"
        })
        void targetsEachBankAtItsUriWithTrailingWildcard(String bankUri, String expectedTarget) {
            server.expect(RequestMatchers.connectionTo(expectedTarget))
                    .andRespond(ResponseCreators.withPayload(new StringSource(RESPONSE)));

            LoanQuote quote = client.getLoanQuote(URI.create(bankUri), request());

            assertThat(quote).isNotNull();
            server.verify();
        }

        @Test
        void soapFaultPropagatesUnwrapped() {
            server.expect(RequestMatchers.connectionTo(BANK_1_TARGET))
                    .andRespond(ResponseCreators.withServerOrReceiverFault("Bank failure", Locale.ENGLISH));

            SoapFaultClientException fault = assertThrows(SoapFaultClientException.class,
                    () -> client.getLoanQuote(URI.create(BANK_1), request()));

            assertThat(fault).isExactlyInstanceOf(SoapFaultClientException.class);
            assertThat(fault.getFaultStringOrReason()).isEqualTo("Bank failure");
            assertThat(fault.getFaultCode().getLocalPart()).isEqualTo("Server");
            server.verify();
        }

        @Test
        void emptySoapBodyPropagatesNullPointerException() {
            expectBank(BANK_1_TARGET, ResponseCreators.withSoapEnvelope(new StringSource(EMPTY_BODY_ENVELOPE)));

            NullPointerException failure = assertThrows(NullPointerException.class,
                    () -> client.getLoanQuote(URI.create(BANK_1), request()));

            // Thrown by the client itself: the marshaller found no payload, so there is no response to unwrap.
            assertThat(thrownBy(failure)).isEqualTo(BankClient.class.getName() + ".getLoanQuote");
            server.verify();
        }

        @Test
        void responseWrapperWithoutReturnYieldsNullQuote() {
            expectBank(BANK_1_TARGET, ResponseCreators.withPayload(new StringSource(RESPONSE_WITHOUT_RETURN)));

            LoanQuote quote = client.getLoanQuote(URI.create(BANK_1), request());

            assertThat(quote).isNull();
            server.verify();
        }

        /**
         * An empty Body from Bank 1 fails the {@code foreach} at its first bank, so Bank 2 is never asked and the
         * flow's catch branch answers. Only the credit agency and Bank 1 are scripted: a call to Bank 2 would also end
         * in the catch branch, but resolved as the mock server's unexpected-connection {@link AssertionError} wrapped
         * in a {@code ServletException}, so the resolved exception is what proves no further call was made.
         */
        @Test
        void emptySoapBodyFromFirstBankEndsLoanFlowInCatchBranchWithoutAskingSecondBank() throws Exception {
            expectCreditProfile();
            expectBank(BANK_1_TARGET, ResponseCreators.withSoapEnvelope(new StringSource(EMPTY_BODY_ENVELOPE)));

            MvcResult result = requestLoan();

            assertCatchBranchAnswered(result.getResponse());
            assertThat(result.getResolvedException()).isExactlyInstanceOf(NullPointerException.class);
            assertThat(thrownBy(result.getResolvedException()))
                    .isEqualTo(BankClient.class.getName() + ".getLoanQuote");
            server.verify();
        }

        /** A {@code null} quote from the first bank is replaced by the next bank's, as the original loop did. */
        @Test
        void responseWrapperWithoutReturnFromFirstBankLeavesSecondBanksQuoteAsLowest() throws Exception {
            expectCreditProfile();
            expectBank(BANK_1_TARGET, ResponseCreators.withPayload(new StringSource(RESPONSE_WITHOUT_RETURN)));
            expectBank(BANK_2_TARGET, ResponseCreators.withPayload(new StringSource(BANK_2_RESPONSE)));

            MvcResult result = requestLoan();

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .isEqualTo("Bank #2, rate: 1.5");
            assertThat(result.getResolvedException()).isNull();
            server.verify();
        }

        /**
         * A {@code null} quote after a valid one fails the lowest-quote comparison, which reads the {@code null}
         * quote's rate, and the flow's catch branch answers.
         */
        @Test
        void responseWrapperWithoutReturnAfterAValidQuoteEndsLoanFlowInCatchBranch() throws Exception {
            expectCreditProfile();
            expectBank(BANK_1_TARGET, ResponseCreators.withPayload(new StringSource(RESPONSE)));
            expectBank(BANK_2_TARGET, ResponseCreators.withPayload(new StringSource(RESPONSE_WITHOUT_RETURN)));

            MvcResult result = requestLoan();

            assertCatchBranchAnswered(result.getResponse());
            assertThat(result.getResolvedException()).isExactlyInstanceOf(NullPointerException.class);
            assertThat(thrownBy(result.getResolvedException()))
                    .isEqualTo(LoanBrokerService.class.getName() + ".findLowestLoanQuote");
            server.verify();
        }

        /** Scripts the loan flow's first call: the credit agency answering with the profile of {@link #request()}. */
        void expectCreditProfile() {
            server.expect(RequestMatchers.connectionTo(CREDIT_AGENCY_TARGET))
                    .andRespond(ResponseCreators.withPayload(new StringSource(CREDIT_PROFILE_RESPONSE)));
        }

        /** Scripts the next call as the {@link #EXPECTED_REQUEST} to {@code target}, answered by {@code response}. */
        void expectBank(String target, ResponseCreator response) {
            server.expect(RequestMatchers.connectionTo(target))
                    .andExpect((uri, message) -> assertXmlIdentical(EXPECTED_REQUEST, message.getPayloadSource()))
                    .andRespond(response);
        }

        /**
         * Sends {@link #LOAN_QUERY} as the main listener's container hands it to the filters: received on
         * {@code server.port} and mapped to {@code dispatcherServlet} as the default servlet, the route
         * {@code PortRoutingFilter} passes down the chain unchanged to {@code LoanBrokerController}.
         */
        MvcResult requestLoan() throws Exception {
            return mockMvc.perform(MockMvcRequestBuilders.get("/?" + LOAN_QUERY).with(request -> {
                request.setLocalPort(mainListenerPort);
                request.setHttpServletMapping(
                        new MockHttpServletMapping("", "/", "dispatcherServlet", MappingMatch.DEFAULT));
                return request;
            })).andReturn();
        }

        /** The flow's catch branch: 200 with exactly {@code Error processing loan request}. */
        void assertCatchBranchAnswered(MockHttpServletResponse response) throws Exception {
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                    .isEqualTo(LoanBrokerExceptionHandler.ERROR_PAYLOAD);
        }
    }

    /**
     * The production sender posting over real HTTP to a WireMock bank that answers 200 with a SOAP 1.1 envelope
     * carrying no quote. The bank URI is a call argument, so the context needs no property for it. The server is this
     * class's own, started and stopped by its own {@code @BeforeAll} and {@code @AfterAll} rather than by a registered
     * {@code WireMockExtension}, which another container ending could stop.
     */
    @Nested
    @SpringBootTest(classes = ForeachProcessingAndChoiceRoutingApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.MOCK)
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class WireMockTransport {

        /** The path of Bank 1's target: its URI's path followed by {@code path="/*"}. */
        static final String TARGET_PATH = "/mule/TheBank1/*";

        static WireMockServer bank;

        @Autowired
        BankClient client;

        @BeforeAll
        static void startBank() {
            bank = new WireMockServer(wireMockConfig().dynamicPort());
            bank.start();
        }

        @AfterAll
        static void stopBank() {
            WireMockServer server = bank;
            bank = null;
            if (server != null) {
                server.stop();
            }
        }

        /** Forgets the previous test's stubs and journaled requests. */
        @BeforeEach
        void resetBank() {
            bank.resetAll();
        }

        @Test
        void emptySoapBodyOverHttpPropagatesNullPointerException() {
            stubBank(EMPTY_BODY_ENVELOPE);

            NullPointerException failure = assertThrows(NullPointerException.class,
                    () -> client.getLoanQuote(bankUri(), request()));

            // Thrown by the client itself: the marshaller found no payload, so there is no response to unwrap.
            assertThat(thrownBy(failure)).isEqualTo(BankClient.class.getName() + ".getLoanQuote");
            verifySingleSoapPost();
        }

        @Test
        void responseWrapperWithoutReturnOverHttpYieldsNullQuote() {
            stubBank(ENVELOPE_WITHOUT_RETURN);

            LoanQuote quote = client.getLoanQuote(bankUri(), request());

            assertThat(quote).isNull();
            verifySingleSoapPost();
        }

        /** Bank 1's URI on the WireMock bank's port. */
        URI bankUri() {
            return URI.create("http://localhost:" + bank.port() + "/mule/TheBank1");
        }

        /** Answers a POST to {@link #TARGET_PATH} with 200 and {@code envelope} as a SOAP 1.1 message. */
        void stubBank(String envelope) {
            bank.stubFor(post(urlEqualTo(TARGET_PATH)).willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/xml;charset=UTF-8")
                    .withBody(envelope)));
        }

        /**
         * Exactly one request reached the bank, and it was a POST to {@link #TARGET_PATH} with the empty
         * {@code SOAPAction} and a SOAP 1.1 {@code text/xml} Content-Type: nothing was re-sent.
         */
        void verifySingleSoapPost() {
            bank.verify(1, postRequestedFor(urlEqualTo(TARGET_PATH))
                    .withHeader("SOAPAction", equalTo("\"\""))
                    .withHeader("Content-Type", containing("text/xml")));
            bank.verify(1, anyRequestedFor(anyUrl()));
        }
    }

    /**
     * The production HTTP sender against a bank that accepts the connection and never answers. A short
     * {@code loanbroker.response-timeout} keeps the case fast and tells it apart from the 10000 ms default.
     */
    @Nested
    @SpringBootTest(classes = ForeachProcessingAndChoiceRoutingApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.MOCK)
    @TestPropertySource(properties = "loanbroker.response-timeout=500")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class SocketTimeout {

        @Autowired
        BankClient client;

        ServerSocket silentServer;

        Thread acceptor;

        final List<Socket> acceptedSockets = new CopyOnWriteArrayList<>();

        @BeforeEach
        void startSilentBank() throws IOException {
            // Wildcard address, so "localhost" reaches it whether it resolves to IPv4 or IPv6.
            silentServer = new ServerSocket(0);
            acceptor = new Thread(() -> {
                try {
                    while (!silentServer.isClosed()) {
                        acceptedSockets.add(silentServer.accept());
                    }
                }
                catch (IOException closed) {
                    // The server socket was closed by stopSilentBank; nothing is left to accept.
                }
            }, "silent-bank-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        /**
         * Closes the server, waits for the acceptor to end, then closes every accepted socket. Each close is attempted
         * even when an earlier one fails; the first failure is thrown once all have been attempted, carrying each
         * later one as suppressed. Fields left unset by a failed {@link #startSilentBank()} are skipped.
         */
        @AfterEach
        void stopSilentBank() throws IOException, InterruptedException {
            IOException failure = null;
            if (silentServer != null) {
                failure = close(silentServer, failure);
            }
            if (acceptor != null) {
                // Joined before the sockets are closed, so none is accepted after the loop below has read the list.
                acceptor.join(Duration.ofSeconds(1).toMillis());
            }
            for (Socket socket : acceptedSockets) {
                failure = close(socket, failure);
            }
            if (failure != null) {
                throw failure;
            }
        }

        @Test
        void readTimeoutSurfacesAsWebServiceIOException() {
            URI bankUri = URI.create("http://localhost:" + silentServer.getLocalPort() + "/mule/TheBank1");
            long start = System.nanoTime();

            WebServiceIOException failure = assertThrows(WebServiceIOException.class,
                    () -> client.getLoanQuote(bankUri, request()));

            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
            assertThat(hasCause(failure, SocketTimeoutException.class))
                    .as("cause chain of %s", failure)
                    .isTrue();
            // At least one read-timeout period, so the connection was accepted rather than refused, and well under
            // the 10000 ms default, so the configured 500 ms applies.
            assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(400)).isLessThan(Duration.ofSeconds(5));
        }

        /**
         * Closes {@code resource} and returns the teardown's failure so far: {@code failure} unchanged when the close
         * succeeds, otherwise the close's {@link IOException} if it is the first, or else {@code failure} with that
         * exception added as suppressed.
         */
        private static IOException close(Closeable resource, IOException failure) {
            try {
                resource.close();
                return failure;
            }
            catch (IOException closeFailure) {
                if (failure == null) {
                    return closeFailure;
                }
                failure.addSuppressed(closeFailure);
                return failure;
            }
        }
    }
}
