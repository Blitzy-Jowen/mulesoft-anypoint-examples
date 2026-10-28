package com.mulesoft.examples.foreach_processing_and_choice_routing.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.xml.transform.Source;

import com.mulesoft.examples.foreach_processing_and_choice_routing.ForeachProcessingAndChoiceRoutingApplication;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.CreditProfile;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.LoanBrokerQuoteRequest;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.LoanQuote;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.soap.SoapVersion;
import org.springframework.ws.soap.client.SoapFaultClientException;
import org.springframework.ws.test.client.MockWebServiceServer;
import org.springframework.ws.test.client.RequestMatchers;
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
 * {@code <bankUri>/*} for each of the five bank URIs; the reply's {@code return} quote; and a SOAP fault surfacing
 * unwrapped. {@link SocketTimeout} keeps the real HTTP sender and proves a bank that never answers ends the call at
 * the configured {@code loanbroker.response-timeout}. Each nested class owns its context; no test opens a fixed port.
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

    /**
     * The shared outbound template with its sender swapped for {@link MockWebServiceServer}. The context is created
     * fresh, and each test restores the production senders afterwards, so no other test class sharing a cached
     * context ever sees the mock sender.
     */
    @Nested
    @SpringBootTest(classes = ForeachProcessingAndChoiceRoutingApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.MOCK)
    @DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
    class MockedTransport {

        @Autowired
        BankClient client;

        @Autowired
        WebServiceTemplate webServiceTemplate;

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
