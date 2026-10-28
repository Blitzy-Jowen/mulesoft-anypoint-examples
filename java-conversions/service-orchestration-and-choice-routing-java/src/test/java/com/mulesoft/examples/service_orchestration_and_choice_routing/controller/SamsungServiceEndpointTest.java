package com.mulesoft.examples.service_orchestration_and_choice_routing.controller;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

import com.mulesoft.examples.service_orchestration_and_choice_routing.ServiceOrchestrationAndChoiceRoutingApplication;
import com.mulesoft.examples.service_orchestration_and_choice_routing.support.HttpTestClient;
import com.mulesoft.examples.service_orchestration_and_choice_routing.support.HttpTestClient.Response;
import com.mulesoft.examples.service_orchestration_and_choice_routing.support.PortInUseRetryingContextLoader;

/**
 * Endpoint test of {@link SamsungServiceEndpoint}, the mock Samsung SOAP listener of the original
 * {@code samsungService} flow ({@code fulfillment.xml:150-155}): {@code http:listener allowedMethods="POST"
 * path="samsung/orders"} on 9090, an INFO logger {@code aaaa}, and {@code cxf:jaxws-service} over
 * {@code SamsungService} with the {@code SamsungServiceImpl} component.
 *
 * <p>The whole application runs on Undertow, every listener on its own free port, and each request goes over a raw
 * socket through ST-1 {@link HttpTestClient}, so status, Content-Type and body are asserted as the listener writes
 * them. The happy path runs the real {@code SamsungServiceImpl}; the error cases are the listener's own answers (the
 * 405 of {@code allowedMethods}, the {@code soap:Client} faults of an unreadable body and of an operation outside the
 * Samsung service, AAP 0.4.7) and the no-listener 404 of the same path on another port (SC-10). Exact envelope bytes
 * are {@code OrderProcessingEndpointTest}'s to assert; here the response is read through XPath, which also proves the
 * wire shape: {@code purchaseResponse} in {@value #SAMSUNG_NAMESPACE} with unqualified children, as the original
 * {@code package-info}'s {@code @XmlSchema} without {@code elementFormDefault} produced.
 *
 * <p>Port numbers come from the operating system ({@code ServerSocket(0)}), never a fixed original port, and are
 * drawn for each load attempt of the context: a drawn port is free when drawn, not when bound, so when another
 * process takes one in between, {@link PortInUseRetryingContextLoader} loads the context again on newly drawn ports,
 * and the tests read the ports of the loaded context from it. The embedded Artemis server id and the in-memory Derby
 * database are unique to this class, so its context can live beside the contexts of other test classes in the same
 * JVM.
 */
@SpringBootTest(classes = ServiceOrchestrationAndChoiceRoutingApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ContextConfiguration(loader = PortInUseRetryingContextLoader.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class SamsungServiceEndpointTest {

    private static final String SAMSUNG_NAMESPACE = "http://samsung.se.mulesoft.com/";
    private static final String SOAP_ENVELOPE_NAMESPACE = "http://schemas.xmlsoap.org/soap/envelope/";

    private static final String SAMSUNG_PATH = "/samsung/orders";
    private static final String ORDERS_PATH = "/orders";

    /** The Content-Type a SOAP 1.1 client sends. */
    private static final Map<String, String> SOAP_HEADERS = Map.of("Content-Type", "text/xml; charset=UTF-8");

    /** The original test's {@code processOrder} request: an {@code IProcessOrder} operation, not a Samsung one. */
    private static final byte[] ORDER_MESSAGE = classpathBytes("/message.xml");

    /** CXF's text for a message part outside the service's WSDL, two spaces before the parenthesis included. */
    private static final String EXPECTED_UNKNOWN_PART_FAULT =
            "Message part {http://orders.se.mulesoft.com/}processOrder was not recognized.  (Does it exist in"
                    + " service WSDL?)";

    private static final int ARTEMIS_SERVER_ID;
    private static final String DERBY_DATABASE;

    static {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        ARTEMIS_SERVER_ID = random.nextInt(10_000, Integer.MAX_VALUE);
        DERBY_DATABASE = "memory:SamsungServiceEndpointTest_" + Long.toUnsignedString(random.nextLong());
    }

    /** The main listener of the loaded context, which serves the SOAP {@code /orders} path alone. */
    @LocalServerPort
    private int mainPort;

    /** The Samsung listener of the loaded context. */
    @Value("${ports.samsung}")
    private int samsungPort;

    /**
     * Moves every listener off its original port, onto distinct free ports drawn for this load attempt. The
     * {@code listeners.additional} entries read their ports from {@code ajax.port} and {@code ports.*}, so those keys
     * are overridden rather than the list, which Spring Boot binds from a single property source.
     * {@code orders.soap.url} names port 1080 literally and is therefore set too.
     */
    @DynamicPropertySource
    static void applicationProperties(DynamicPropertyRegistry registry) {
        Iterator<Integer> ports = distinctFreePorts(5).iterator();
        int main = ports.next();
        int ajax = ports.next();
        int populate = ports.next();
        int samsung = ports.next();
        int prices = ports.next();
        registry.add("server.port", () -> main);
        registry.add("ajax.port", () -> ajax);
        registry.add("ports.populate", () -> populate);
        registry.add("ports.samsung", () -> samsung);
        registry.add("ports.prices", () -> prices);
        registry.add("orders.samsung.url", () -> "http://localhost:" + samsung + SAMSUNG_PATH);
        registry.add("orders.price.url", () -> "http://localhost:" + prices + "/api/prices/{productId}");
        registry.add("orders.soap.url", () -> "http://localhost:" + main + ORDERS_PATH);
        registry.add("spring.datasource.xa.properties.databaseName", () -> DERBY_DATABASE);
        registry.add("spring.datasource.xa.properties.createDatabase", () -> "create");
        registry.add("spring.artemis.mode", () -> "embedded");
        registry.add("spring.artemis.embedded.persistent", () -> "false");
        registry.add("spring.artemis.embedded.server-id", () -> ARTEMIS_SERVER_ID);
    }

    @Test
    void purchaseReturnsAcceptedResponse(CapturedOutput output) {
        long loggedBefore = flowLoggerLines(output);

        Response response = postSoap(samsungPort, SAMSUNG_PATH, purchaseEnvelope(1).getBytes(UTF_8));

        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        assertThat(response.header("Content-Type")).as(response.toString()).startsWith("text/xml");
        assertThat(orderResponse(response.bodyAsString()))
                .isEqualTo(new OrderResponseFields("1", "ACCEPTED", "2550"));
        assertThat(flowLoggerLines(output)).as("INFO line 'aaaa' of the flow's logger").isGreaterThan(loggedBefore);
    }

    @ParameterizedTest(name = "quantity {0} costs {1}")
    @CsvSource({"0, 0", "3, 7650"})
    void purchaseMultipliesPriceByQuantity(int quantity, String expectedPrice) {
        Response response = postSoap(samsungPort, SAMSUNG_PATH, purchaseEnvelope(quantity).getBytes(UTF_8));

        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        assertThat(orderResponse(response.bodyAsString()))
                .isEqualTo(new OrderResponseFields("1", "ACCEPTED", expectedPrice));
    }

    @Test
    void getIsMethodNotAllowed() {
        Response response = HttpTestClient.localhost(samsungPort).get(SAMSUNG_PATH);

        assertThat(response.status()).as(response.toString()).isEqualTo(405);
        assertThat(response.header("Content-Type")).as(response.toString()).isEqualTo("text/plain");
        assertThat(response.bodyAsString()).isEqualTo("Method not allowed for endpoint: " + SAMSUNG_PATH);
    }

    @ParameterizedTest(name = "[{index}] body \"{0}\"")
    @ValueSource(strings = {
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body><unclosed>",
            "",
            "<foo/>"})
    void unreadableBodiesAreClientFaults(String body) {
        Response response = postSoap(samsungPort, SAMSUNG_PATH, body.getBytes(UTF_8));

        assertThat(response.status()).as(response.toString()).isEqualTo(500);
        Fault fault = fault(response.bodyAsString());
        assertThat(fault.code()).isEqualTo("soap:Client");
        assertThat(fault.string()).as("the parser's message").isNotBlank();
    }

    @Test
    void processOrderOnSamsungListenerIsClientFault() {
        Response response = postSoap(samsungPort, SAMSUNG_PATH, ORDER_MESSAGE);

        assertThat(response.status()).as(response.toString()).isEqualTo(500);
        assertThat(fault(response.bodyAsString())).isEqualTo(new Fault("soap:Client", EXPECTED_UNKNOWN_PART_FAULT));
    }

    @Test
    void samsungPathOnMainPortIsNoListener() {
        Response response = postSoap(mainPort, SAMSUNG_PATH, purchaseEnvelope(1).getBytes(UTF_8));

        assertThat(response.status()).as(response.toString()).isEqualTo(404);
        assertThat(response.header("Content-Type")).as(response.toString()).startsWith("text/plain");
        assertThat(response.bodyAsString()).isEqualTo("No listener for endpoint: " + SAMSUNG_PATH);
    }

    /** The text of {@code faultcode} and {@code faultstring}, unqualified children of a SOAP 1.1 {@code Fault}. */
    private record Fault(String code, String string) {
    }

    /** The text of {@code id}, {@code result} and {@code price} of the response's {@code orderResponse}. */
    private record OrderResponseFields(String id, String result, String price) {
    }

    /** A SOAP 1.1 {@code purchase} request for {@code quantity} items of product {@code s-1}. */
    private static String purchaseEnvelope(int quantity) {
        return "<soapenv:Envelope xmlns:soapenv=\"" + SOAP_ENVELOPE_NAMESPACE + "\">"
                + "<soapenv:Header/>"
                + "<soapenv:Body>"
                + "<sam:purchase xmlns:sam=\"" + SAMSUNG_NAMESPACE + "\">"
                + "<orderRequest><name>s-1</name><quantity>" + quantity + "</quantity></orderRequest>"
                + "</sam:purchase>"
                + "</soapenv:Body>"
                + "</soapenv:Envelope>";
    }

    private static Response postSoap(int port, String path, byte[] body) {
        return HttpTestClient.localhost(port).send("POST", path, SOAP_HEADERS, body);
    }

    /**
     * Reads the SOAP 1.1 fault of {@code body}; an unprefixed XPath step matches only an element in no namespace, so
     * the lookup also proves that {@code faultcode} and {@code faultstring} are unqualified.
     */
    private static Fault fault(String body) {
        Document document = parse(body);
        return new Fault(text(document, "/soap:Envelope/soap:Body/soap:Fault/faultcode"),
                text(document, "/soap:Envelope/soap:Body/soap:Fault/faultstring"));
    }

    /**
     * Reads the {@code orderResponse} of a {@code purchaseResponse} in {@value #SAMSUNG_NAMESPACE}; as in
     * {@link #fault}, the unprefixed steps match only unqualified children.
     */
    private static OrderResponseFields orderResponse(String body) {
        Document document = parse(body);
        String orderResponse = "/soap:Envelope/soap:Body/sam:purchaseResponse/orderResponse/";
        return new OrderResponseFields(text(document, orderResponse + "id"), text(document, orderResponse + "result"),
                text(document, orderResponse + "price"));
    }

    /** The text content of the one node {@code expression} selects; fails the test when it selects none. */
    private static String text(Document document, String expression) {
        try {
            XPath xpath = XPathFactory.newInstance().newXPath();
            xpath.setNamespaceContext(new EnvelopeNamespaces());
            Node node = (Node) xpath.evaluate(expression, document, XPathConstants.NODE);
            assertThat(node).as("node %s in response", expression).isNotNull();
            return node.getTextContent();
        } catch (XPathExpressionException e) {
            throw new IllegalStateException("Invalid XPath " + expression, e);
        }
    }

    /** Parses {@code body} namespace-aware, refusing any DOCTYPE so no external entity is ever resolved. */
    private static Document parse(String body) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(body.getBytes(UTF_8)));
        } catch (Exception e) {
            throw new AssertionError("Response body is not well-formed XML: " + body, e);
        }
    }

    /** Count of the original flow logger's INFO {@code aaaa} lines captured so far. */
    private static long flowLoggerLines(CapturedOutput output) {
        return output.getOut().lines()
                .filter(line -> line.contains(" INFO ") && line.contains(SamsungServiceEndpoint.class.getSimpleName())
                        && line.endsWith(": aaaa"))
                .count();
    }

    private static Set<Integer> distinctFreePorts(int count) {
        Set<Integer> ports = new LinkedHashSet<>();
        while (ports.size() < count) {
            ports.add(freePort());
        }
        return ports;
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("No free local port", e);
        }
    }

    private static byte[] classpathBytes(String resource) {
        try (InputStream in = SamsungServiceEndpointTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource " + resource);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read test resource " + resource, e);
        }
    }

    /** Prefix {@code soap} for the SOAP 1.1 envelope namespace and {@code sam} for {@value #SAMSUNG_NAMESPACE}. */
    private static final class EnvelopeNamespaces implements NamespaceContext {

        @Override
        public String getNamespaceURI(String prefix) {
            return switch (prefix) {
                case "soap" -> SOAP_ENVELOPE_NAMESPACE;
                case "sam" -> SAMSUNG_NAMESPACE;
                default -> XMLConstants.NULL_NS_URI;
            };
        }

        @Override
        public String getPrefix(String namespaceURI) {
            throw new UnsupportedOperationException("XPath evaluation needs prefix-to-URI lookups only");
        }

        @Override
        public Iterator<String> getPrefixes(String namespaceURI) {
            throw new UnsupportedOperationException("XPath evaluation needs prefix-to-URI lookups only");
        }
    }
}
