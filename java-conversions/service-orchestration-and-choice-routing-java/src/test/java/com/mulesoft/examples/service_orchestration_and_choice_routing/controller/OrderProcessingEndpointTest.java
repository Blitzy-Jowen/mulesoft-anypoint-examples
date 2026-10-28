/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.controller;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.mulesoft.examples.service_orchestration_and_choice_routing.ServiceOrchestrationAndChoiceRoutingApplication;
import com.mulesoft.examples.service_orchestration_and_choice_routing.service.OrderOrchestrationService;
import com.mulesoft.examples.service_orchestration_and_choice_routing.support.HttpTestClient;

/**
 * Wire-level test of {@link OrderProcessingEndpoint}, the converted {@code orderService} flow of the original
 * {@code fulfillment.xml} (lines 28-48): the {@code http:listener allowedMethods="POST" path="orders"} on 1080 and the
 * {@code cxf:jaxws-service} answering {@code IProcessOrder.processOrder}.
 *
 * <p>The whole application runs, so every request passes the listener's real chain: SC-10's port routing,
 * SC-11's listener boundary, fault resolver and envelope styler, the listener-path guard, and
 * {@link OrderOrchestrationService} with its Samsung leg, which calls this application's own Samsung listener over
 * HTTP. Every request goes through the raw-socket {@link HttpTestClient}, so status, headers and body bytes are
 * asserted as Undertow sent them. The only test double is a spy on {@link OrderOrchestrationService}; Mockito resets
 * it after every test, so only {@link #serviceFailureIsServerFault()} changes its behaviour.
 *
 * <p>The ports are allocated here rather than left random: each additional listener must be bound to a concrete port,
 * because SC-10 identifies a listener by the local port that received the request, and the Samsung and SOAP client
 * URLs must name their listener's port before the context starts. The context gets its own in-memory Derby database
 * and its own embedded Artemis server id, so it never shares state with another context of the same test JVM.
 */
@SpringBootTest(classes = ServiceOrchestrationAndChoiceRoutingApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderProcessingEndpointTest {

    private static final String ORDERS_PATH = "/orders";

    private static final String SOAP11_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    /** The request headers of every SOAP post; no {@code SOAPAction}, as the original test sent none. */
    private static final Map<String, String> SOAP_HEADERS = Map.of("Content-Type", "text/xml; charset=UTF-8");

    /** The original test's request, {@code src/test/resources/message.xml}: one Samsung item. */
    private static final byte[] MESSAGE = resource("/message.xml");

    /** The original test's expected reply, {@code src/test/resources/reply.xml}, compared exactly. */
    private static final String REPLY = new String(resource("/reply.xml"), UTF_8);

    /** A {@code SamsungService.purchase} request: a valid operation of this application, served on another path. */
    private static final String PURCHASE_ENVELOPE = "<soapenv:Envelope"
            + " xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\""
            + " xmlns:sam=\"http://samsung.se.mulesoft.com/\">"
            + "<soapenv:Header/><soapenv:Body><sam:purchase><orderRequest><name>s-1</name>"
            + "<quantity>1</quantity></orderRequest></sam:purchase></soapenv:Body></soapenv:Envelope>";

    /** CXF's text for a message part outside the service's WSDL, two spaces before the parenthesis included. */
    private static final String UNKNOWN_PURCHASE_FAULT = "Message part {http://samsung.se.mulesoft.com/}purchase"
            + " was not recognized.  (Does it exist in service WSDL?)";

    private static final int MAIN_PORT;
    private static final int AJAX_PORT;
    private static final int POPULATE_PORT;
    private static final int SAMSUNG_PORT;
    private static final int PRICES_PORT;

    /** Embedded Artemis server id of this context: the in-VM acceptor is registered per id in the JVM. */
    private static final int ARTEMIS_SERVER_ID;

    /** In-memory Derby database of this context; the suffix holds letters and digits only. */
    private static final String DERBY_DATABASE;

    static {
        int[] ports = distinctFreePorts(5);
        MAIN_PORT = ports[0];
        AJAX_PORT = ports[1];
        POPULATE_PORT = ports[2];
        SAMSUNG_PORT = ports[3];
        PRICES_PORT = ports[4];
        ARTEMIS_SERVER_ID = ThreadLocalRandom.current().nextInt(10_000, Integer.MAX_VALUE);
        DERBY_DATABASE = "memory:OrderProcessingEndpointTest_" + UUID.randomUUID().toString().replace("-", "");
    }

    @SpyBean
    OrderOrchestrationService orchestration;

    /**
     * Moves every original port onto an allocated one. The additional listeners' ports in {@code application.yml}
     * are placeholders over {@code ajax.port} and {@code ports.*}, so those keys are overridden and the
     * {@code listeners.additional} list, which Spring Boot binds from one property source only, stays as declared.
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> MAIN_PORT);
        registry.add("ajax.port", () -> AJAX_PORT);
        registry.add("ports.populate", () -> POPULATE_PORT);
        registry.add("ports.samsung", () -> SAMSUNG_PORT);
        registry.add("ports.prices", () -> PRICES_PORT);
        registry.add("orders.samsung.url", () -> "http://localhost:" + SAMSUNG_PORT + "/samsung/orders");
        registry.add("orders.price.url", () -> "http://localhost:" + PRICES_PORT + "/api/prices/{productId}");
        registry.add("orders.soap.url", () -> "http://localhost:" + MAIN_PORT + ORDERS_PATH);
        registry.add("spring.datasource.xa.properties.databaseName", () -> DERBY_DATABASE);
        registry.add("spring.datasource.xa.properties.createDatabase", () -> "create");
        registry.add("spring.artemis.mode", () -> "embedded");
        registry.add("spring.artemis.embedded.persistent", () -> "false");
        registry.add("spring.artemis.embedded.server-id", () -> ARTEMIS_SERVER_ID);
    }

    /** The original {@code orderTest}: status 200 and the payload exactly equal to {@code reply.xml}. */
    @Test
    void postMessageReturnsExactReply() {
        HttpTestClient.Response response = postOrders(MAIN_PORT, MESSAGE);

        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        assertThat(response.header("Content-Type")).startsWith("text/xml");
        assertThat(response.bodyAsString()).isEqualTo(REPLY);
    }

    @Test
    void getIsMethodNotAllowed() {
        HttpTestClient.Response response = HttpTestClient.localhost(MAIN_PORT).get(ORDERS_PATH);

        assertThat(response.status()).as(response.toString()).isEqualTo(405);
        assertThat(response.header("Content-Type")).startsWith("text/plain");
        assertThat(response.bodyAsString()).isEqualTo("Method not allowed for endpoint: " + ORDERS_PATH);
    }

    static Stream<Arguments> unreadableEnvelopes() {
        return Stream.of(
                Arguments.of(Named.of("malformed XML", ("<soap:Envelope xmlns:soap=\"" + SOAP11_NS
                        + "\"><soap:Body><unclosed>").getBytes(UTF_8))),
                Arguments.of(Named.of("empty body", new byte[0])),
                Arguments.of(Named.of("non-envelope root", "<foo/>".getBytes(UTF_8))));
    }

    /** A body that cannot be read as a SOAP 1.1 envelope is answered at the listener boundary. */
    @ParameterizedTest(name = "{0} is a soap:Client fault")
    @MethodSource("unreadableEnvelopes")
    void unreadableEnvelopeIsClientFault(byte[] body) throws Exception {
        HttpTestClient.Response response = postOrders(MAIN_PORT, body);

        assertThat(response.status()).as(response.toString()).isEqualTo(500);
        assertThat(response.header("Content-Type")).startsWith("text/xml");
        Fault fault = fault(response.bodyAsString());
        assertThat(fault.code()).isEqualTo("soap:Client");
        assertThat(fault.string()).isNotBlank();
    }

    /** One dispatcher servlet serves both SOAP listeners; the Samsung operation is still unknown on /orders. */
    @Test
    void samsungOperationOnOrdersListenerIsClientFault() throws Exception {
        HttpTestClient.Response response = postOrders(MAIN_PORT, PURCHASE_ENVELOPE.getBytes(UTF_8));

        assertThat(response.status()).as(response.toString()).isEqualTo(500);
        assertThat(response.header("Content-Type")).startsWith("text/xml");
        Fault fault = fault(response.bodyAsString());
        assertThat(fault.code()).isEqualTo("soap:Client");
        assertThat(fault.string()).isEqualTo(UNKNOWN_PURCHASE_FAULT);
    }

    /** The order listener exists on the main port only; another listener's port has no {@code /orders}. */
    @Test
    void ordersPathOnPopulatePortIsNoListener() {
        HttpTestClient.Response response = postOrders(POPULATE_PORT, MESSAGE);

        assertThat(response.status()).as(response.toString()).isEqualTo(404);
        assertThat(response.header("Content-Type")).startsWith("text/plain");
        assertThat(response.bodyAsString()).isEqualTo("No listener for endpoint: " + ORDERS_PATH);
    }

    /** An exception escaping the flow becomes a {@code soap:Server} fault carrying its message. */
    @Test
    void serviceFailureIsServerFault() throws Exception {
        doThrow(new IllegalStateException("boom")).when(orchestration).processOrder(any());

        HttpTestClient.Response response = postOrders(MAIN_PORT, MESSAGE);

        assertThat(response.status()).as(response.toString()).isEqualTo(500);
        assertThat(response.header("Content-Type")).startsWith("text/xml");
        Fault fault = fault(response.bodyAsString());
        assertThat(fault.code()).isEqualTo("soap:Server");
        assertThat(fault.string()).isEqualTo("boom");
    }

    private static HttpTestClient.Response postOrders(int port, byte[] body) {
        return HttpTestClient.localhost(port).send("POST", ORDERS_PATH, SOAP_HEADERS, body);
    }

    /** The {@code faultcode} and {@code faultstring} texts of a SOAP 1.1 fault. */
    private record Fault(String code, String string) {
    }

    /**
     * Reads the single SOAP 1.1 {@code Fault} of {@code body} and its unqualified {@code faultcode} and
     * {@code faultstring} children; a child that is absent reads as {@code null}.
     */
    private static Fault fault(String body) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body.getBytes(UTF_8)));
        NodeList faults = document.getElementsByTagNameNS(SOAP11_NS, "Fault");
        assertThat(faults.getLength()).as("SOAP 1.1 Fault elements in %s", body).isEqualTo(1);
        Element fault = (Element) faults.item(0);
        return new Fault(childText(fault, "faultcode"), childText(fault, "faultstring"));
    }

    /** The text of {@code parent}'s first child element named {@code localName} in no namespace, or {@code null}. */
    private static String childText(Element parent, String localName) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE && child.getNamespaceURI() == null
                    && localName.equals(child.getLocalName())) {
                return child.getTextContent();
            }
        }
        return null;
    }

    /** The bytes of a classpath resource, failing the class's initialisation when it is missing. */
    private static byte[] resource(String path) {
        try (InputStream in = OrderProcessingEndpointTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Test resource " + path + " is missing");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read test resource " + path, e);
        }
    }

    /** {@code count} distinct ports that were free when probed, in probing order. */
    private static int[] distinctFreePorts(int count) {
        Set<Integer> ports = new LinkedHashSet<>();
        while (ports.size() < count) {
            ports.add(freePort());
        }
        return ports.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot allocate a free port", e);
        }
    }
}
