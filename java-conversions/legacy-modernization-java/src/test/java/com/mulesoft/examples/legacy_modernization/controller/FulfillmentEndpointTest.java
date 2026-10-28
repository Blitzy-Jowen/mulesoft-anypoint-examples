/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.legacy_modernization.controller;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import com.mulesoft.examples.legacy_modernization.service.FileOutboundWriter;
import com.mulesoft.examples.legacy_modernization.service.IFulfillment;
import com.mulesoft.examples.legacy_modernization.support.HttpTestClient;

/**
 * Wire-level test of {@link FulfillmentEndpoint}, the converted {@code Fulfillment_LegacySystemModernization} flow
 * of the original {@code FufillmentWebService.xml}: the POST-only listener on {@code /OrderFulfillment}, the
 * {@code cxf:jaxws-service} answering {@code IFulfillment.putShippingOrder}, and the {@code async} scope writing the
 * legacy fulfillment CSV as {@code ShippingOrder-<dd-MM-yy_HH-mm-ss.SSS>.txt}.
 *
 * <p>Every request goes through the raw-socket {@link HttpTestClient}, so status line, headers and body bytes are
 * asserted as Undertow sent them. The SOAP boundary, fault and envelope styling and the no-listener handler run
 * as configured; the only test doubles are a spy on {@link FileOutboundWriter}, which records the thread of each
 * export write, and, in {@link WhenFulfillmentFails}, a mock {@link IFulfillment}. Exports go to a {@link TempDir},
 * never into {@code src/main/resources/Output}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FulfillmentEndpointTest {

    private static final String LISTENER_PATH = "/OrderFulfillment";

    private static final String SOAP11_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    /** The {@code applicationTaskExecutor} thread prefix of {@code spring.task.execution.thread-name-prefix}. */
    private static final String ASYNC_THREAD_PREFIX = "legacy-fulfillment-async-";

    /** The original {@code outputPattern="ShippingOrder-#[server.dateTime.format('dd-MM-yy_HH-mm-ss.SSS')].txt"}. */
    private static final Pattern SHIPPING_ORDER_FILE =
            Pattern.compile("ShippingOrder-\\d{2}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.\\d{3}\\.txt");

    /**
     * The "Map Fullfilment order to CSV" output for {@code message.xml}: header row, comma separator, the streets as
     * {@code line1 ++ ' ' ++ line2}, and an LF after every line, the last included.
     */
    private static final String EXPECTED_CSV = ""
            + "MSKU,QTY,BillingAddressName,BillingAddressStreet,BillingAddrCity,BillingAddrState,BillingAddrCountry,"
            + "BillingAddrZipCode,ShippingAddrName,ShippingAddrStreet,ShippingAddrCity,ShippingAddrState,"
            + "ShippingAddrCountry,ShippingAddrZipCode,ShippingId\n"
            + "1234,500,Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,"
            + "Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,1234\n"
            + "6789,1500,Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,"
            + "Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,1234\n"
            + "9998,5000,Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,"
            + "Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,1234\n";

    private static final String UNKNOWN_OPERATION_ENVELOPE = "<soapenv:Envelope"
            + " xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:ord=\"http://ordermgmt.org/\">"
            + "<soapenv:Header/><soapenv:Body><ord:unknownOperation/></soapenv:Body></soapenv:Envelope>";

    /** Names of the threads that called {@link FileOutboundWriter#write}, recorded by the spy's answer. */
    private static final List<String> EXPORT_THREADS = new CopyOnWriteArrayList<>();

    @TempDir
    static Path outputDir;

    @SpyBean
    FileOutboundWriter fileOutboundWriter;

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void outputDirectory(DynamicPropertyRegistry registry) {
        // Supersedes the surefire fulfillment.output-dir system property; read lazily, once @TempDir is injected.
        registry.add("fulfillment.output-dir", () -> outputDir.toString());
    }

    // JUnit also runs this on the outer instance before each @Nested test, so it touches static state only.
    @BeforeEach
    void clearExports() throws IOException {
        try (Stream<Path> entries = Files.list(outputDir)) {
            for (Path entry : entries.toList()) {
                FileSystemUtils.deleteRecursively(entry);
            }
        }
        EXPORT_THREADS.clear();
    }

    @Test
    void postShippingOrderReturnsCxfReplyAndExportsCsvAsynchronously() throws Exception {
        Answer<Object> recordThread = invocation -> {
            EXPORT_THREADS.add(Thread.currentThread().getName());
            return invocation.callRealMethod();
        };
        doAnswer(recordThread).when(fileOutboundWriter).write(any(Path.class), anyString(), any(byte[].class));
        doAnswer(recordThread).when(fileOutboundWriter).write(any(Path.class), anyString(), anyString());
        doAnswer(recordThread).when(fileOutboundWriter).write(anyString(), anyString(), any(byte[].class));
        doAnswer(recordThread).when(fileOutboundWriter).write(anyString(), anyString(), anyString());

        HttpTestClient.Response response = postXml(HttpTestClient.localhost(port), LISTENER_PATH,
                resource("message.xml"));

        assertThat(response.status()).as("%s", response).isEqualTo(200);
        assertThat(contentType(response).toLowerCase(Locale.ROOT)).startsWith("text/xml");
        assertThat(response.bodyAsString()).isEqualTo(new String(resource("reply.xml"), UTF_8));

        // The content is checked inside the wait, so a file still being written is retried rather than failed.
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50)).untilAsserted(() -> {
            List<Path> files = shippingOrderFiles();
            assertThat(files).hasSize(1);
            assertThat(outputEntries()).containsExactlyElementsOf(files);
            assertThat(Files.readString(files.get(0), UTF_8)).isEqualTo(EXPECTED_CSV);
        });
        assertThat(EXPORT_THREADS).isNotEmpty()
                .allSatisfy(thread -> assertThat(thread).startsWith(ASYNC_THREAD_PREFIX));
    }

    // The original listener accepts POST only, so it never published ?wsdl: every GET gets the listener's 405.
    @ParameterizedTest
    @ValueSource(strings = {"/OrderFulfillment", "/OrderFulfillment?wsdl"})
    void getIsRejectedAsMethodNotAllowed(String target) {
        HttpTestClient.Response response = HttpTestClient.localhost(port).get(target);

        assertThat(response.status()).as("%s", response).isEqualTo(405);
        assertThat(contentType(response)).startsWith("text/plain");
        assertThat(response.bodyAsString()).isEqualTo("Method not allowed for endpoint: /OrderFulfillment");
    }

    // The parser's faultstring is a Tier 2A verification point, so only the fault code is asserted.
    @ParameterizedTest
    @MethodSource("unreadableEnvelopes")
    void unreadableEnvelopeBecomesSoapClientFault(byte[] body) {
        HttpTestClient.Response response = postXml(HttpTestClient.localhost(port), LISTENER_PATH, body);

        assertThat(response.status()).as("%s", response).isEqualTo(500);
        String text = response.bodyAsString();
        assertThat(text).contains("<soap:Envelope", "<faultcode>soap:Client</faultcode>")
                .containsPattern("<soap:Fault[\\s>]");
        assertThat(fault(text).code()).isEqualTo("soap:Client");
    }

    static Stream<Arguments> unreadableEnvelopes() throws IOException {
        byte[] message = resource("message.xml");
        return Stream.of(
                Arguments.of(Named.of("truncated envelope", Arrays.copyOf(message, message.length / 2))),
                Arguments.of(Named.of("empty body", new byte[0])));
    }

    @Test
    void unknownOperationBecomesSoapClientFault() {
        HttpTestClient.Response response = postXml(HttpTestClient.localhost(port), LISTENER_PATH,
                UNKNOWN_OPERATION_ENVELOPE.getBytes(UTF_8));

        assertThat(response.status()).as("%s", response).isEqualTo(500);
        String text = response.bodyAsString();
        assertThat(text).contains("<faultcode>soap:Client</faultcode>");
        Fault fault = fault(text);
        assertThat(fault.code()).isEqualTo("soap:Client");
        assertThat(fault.string()).contains("was not recognized");
    }

    // The listener matches its path exactly, so a sub-path is no SOAP request but an unserved path.
    @Test
    void postToSubPathIsNoListener() throws IOException {
        HttpTestClient.Response response = postXml(HttpTestClient.localhost(port), "/OrderFulfillment/extra",
                resource("message.xml"));

        assertThat(response.status()).as("%s", response).isEqualTo(404);
        assertThat(contentType(response)).startsWith("text/plain");
        assertThat(response.bodyAsString()).isEqualTo("No listener for endpoint: /OrderFulfillment/extra");
    }

    @Test
    void getUnknownPathIsNoListener() {
        HttpTestClient.Response response = HttpTestClient.localhost(port).get("/unknown");

        assertThat(response.status()).as("%s", response).isEqualTo(404);
        assertThat(contentType(response)).startsWith("text/plain");
        assertThat(response.bodyAsString()).isEqualTo("No listener for endpoint: /unknown");
    }

    /** The fulfillment component failing: the exception escapes the endpoint and the export never starts. */
    @Nested
    class WhenFulfillmentFails {

        @MockBean
        IFulfillment fulfillment;

        @LocalServerPort
        int port;

        @Test
        void endpointExceptionBecomesSoapServerFault() throws IOException {
            when(fulfillment.putShippingOrder(any(), any(), any(), any())).thenThrow(new RuntimeException("boom"));

            HttpTestClient.Response response = postXml(HttpTestClient.localhost(port), LISTENER_PATH,
                    resource("message.xml"));

            assertThat(response.status()).as("%s", response).isEqualTo(500);
            String text = response.bodyAsString();
            assertThat(text).contains("<faultcode>soap:Server</faultcode>", "<faultstring>boom</faultstring>");
            Fault fault = fault(text);
            assertThat(fault.code()).isEqualTo("soap:Server");
            assertThat(fault.string()).isEqualTo("boom");
            verify(fulfillment).putShippingOrder(eq("1234"), any(), any(), any());
            assertThat(shippingOrderFiles()).isEmpty();
        }
    }

    private static byte[] resource(String name) throws IOException {
        return new ClassPathResource(name).getContentAsByteArray();
    }

    // No SOAPAction header: the original test sent none. Host, Content-Length and Connection come from the client.
    private static HttpTestClient.Response postXml(HttpTestClient client, String path, byte[] body) {
        return client.send("POST", path, Map.of("Content-Type", "text/xml;charset=UTF-8"), body);
    }

    private static String contentType(HttpTestClient.Response response) {
        String contentType = response.header("Content-Type");
        assertThat(contentType).as("Content-Type of %s", response).isNotNull();
        return contentType;
    }

    /** The unqualified {@code faultcode} and {@code faultstring} of {@code soap:Envelope/soap:Body/soap:Fault}. */
    private static Fault fault(String body) {
        Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setNamespaceAware(true);
            document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body.getBytes(UTF_8)));
        } catch (Exception e) {
            throw new AssertionError("Response body is not well-formed XML: " + body, e);
        }
        Element envelope = document.getDocumentElement();
        assertThat(envelope.getNamespaceURI()).as("envelope namespace").isEqualTo(SOAP11_NS);
        assertThat(envelope.getLocalName()).as("envelope element").isEqualTo("Envelope");
        Element fault = child(child(envelope, SOAP11_NS, "Body"), SOAP11_NS, "Fault");
        return new Fault(child(fault, null, "faultcode").getTextContent(),
                child(fault, null, "faultstring").getTextContent());
    }

    private static Element child(Element parent, String namespace, String localName) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && Objects.equals(namespace, element.getNamespaceURI())
                    && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        throw new AssertionError("No {" + (namespace == null ? "" : namespace) + "}" + localName + " child in <"
                + parent.getNodeName() + ">");
    }

    private static List<Path> shippingOrderFiles() throws IOException {
        return outputEntries().stream()
                .filter(entry -> SHIPPING_ORDER_FILE.matcher(entry.getFileName().toString()).matches())
                .toList();
    }

    private static List<Path> outputEntries() throws IOException {
        try (Stream<Path> entries = Files.list(outputDir)) {
            return entries.sorted().toList();
        }
    }

    private record Fault(String code, String string) {
    }
}
