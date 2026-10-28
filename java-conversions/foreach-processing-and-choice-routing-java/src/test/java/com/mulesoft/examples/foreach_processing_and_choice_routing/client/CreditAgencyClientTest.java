package com.mulesoft.examples.foreach_processing_and_choice_routing.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
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

import com.mulesoft.examples.foreach_processing_and_choice_routing.ForeachProcessingAndChoiceRoutingApplication;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.CreditProfile;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.Customer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.soap.SoapVersion;
import org.springframework.ws.soap.client.SoapFaultClientException;
import org.springframework.ws.test.client.MockWebServiceServer;
import org.springframework.ws.test.client.RequestMatchers;
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
 * request's wire shape, the reply parsing and fault propagation over a mocked transport; {@link SocketTimeout} lets
 * the production sender call a peer that never answers.
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

    /**
     * The client over a mocked transport. The context is created fresh, so the template is guaranteed to still hold
     * the production sender when it is captured: {@link MockWebServiceServer} replaces the template's senders, and a
     * cached context could already carry another test's mock. Each test restores the production sender afterwards.
     */
    @Nested
    @SpringBootTest(classes = ForeachProcessingAndChoiceRoutingApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.MOCK)
    @DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
    class MockedTransport {

        @Autowired
        private CreditAgencyClient client;

        @Autowired
        private WebServiceTemplate webServiceTemplate;

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
