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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.FileSystemUtils;
import org.springframework.ws.server.endpoint.adapter.DefaultMethodEndpointAdapter;
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
 * never into {@code src/main/resources/Output}. The nested {@link UnderRootTrace} run covers the logger level
 * {@code application.yml} pins.
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

    /**
     * U+1F600 lies outside the Basic Multilingual Plane: CXF echoed it as its four UTF-8 bytes, where SAAJ alone
     * writes {@code &#128512;}, and Content-Length counts the bytes sent.
     */
    @Test
    void shippingIdOutsideTheBmpIsEchoedAsRawUtf8() throws IOException {
        String shippingId = "A\uD83D\uDE00B";
        String echoed = "<shippingId>" + shippingId + "</shippingId>";
        byte[] request = new String(resource("message.xml"), UTF_8)
                .replace("<shippingId>1234</shippingId>", echoed).getBytes(UTF_8);

        HttpTestClient.Response response = postXml(HttpTestClient.localhost(port), LISTENER_PATH, request);

        assertThat(response.status()).as("%s", response).isEqualTo(200);
        assertThat(response.body()).isEqualTo(new String(resource("reply.xml"), UTF_8)
                .replace("<shippingId>1234</shippingId>", echoed).getBytes(UTF_8));
        assertThat(response.header("Content-Length")).isEqualTo(Integer.toString(response.body().length));
        assertNoRequestHeaders(response);
        // The export runs on its own thread; waiting for it keeps its file out of the next test's directory.
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50)).untilAsserted(() -> {
            List<Path> files = shippingOrderFiles();
            assertThat(files).hasSize(1);
            assertThat(Files.readString(files.get(0), UTF_8)).contains("," + shippingId + "\n");
        });
    }

    // The original listener accepts POST only, so it never published ?wsdl: every GET gets the listener's 405.
    @ParameterizedTest
    @ValueSource(strings = {"/OrderFulfillment", "/OrderFulfillment?wsdl"})
    void getIsRejectedAsMethodNotAllowed(String target) {
        HttpTestClient.Response response = HttpTestClient.localhost(port).get(target);

        assertThat(response.status()).as("%s", response).isEqualTo(405);
        assertThat(contentType(response)).isEqualTo("text/plain");
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
        assertNoRequestHeaders(response);
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
            assertNoRequestHeaders(response);
            verify(fulfillment).putShippingOrder(eq("1234"), any(), any(), any());
            assertThat(shippingOrderFiles()).isEmpty();
        }
    }

    /** The shipping order of {@link LoggedShippingOrder} under {@code logging.level.root=TRACE}. */
    @Nested
    @TestPropertySource(properties = "logging.level.root=TRACE")
    @DirtiesContext
    class UnderRootTrace extends LoggedShippingOrder {
    }

    /**
     * Tests the logger level {@code application.yml} pins, which keeps the order's addresses out of the log under a
     * root {@code TRACE} level: {@code FufillmentWebService.xml} declares no logger.
     *
     * <p>The nested run keeps this class's configuration in a context of its own. The original test's
     * {@code putShippingOrder} is posted and answered with {@code reply.xml}, its CSV export is awaited, then the
     * exchange's own log events are asserted to hold the unpinned {@code DefaultMethodEndpointAdapter} {@code TRACE}
     * invocation line, which proves the capture sees that level, and the console output is asserted to hold none of
     * the billing or shipping address values. The run restores the logger levels it found and closes its context, so
     * later test classes do not run at {@code TRACE}.
     */
    @ExtendWith(OutputCaptureExtension.class)
    abstract static class LoggedShippingOrder {

        /** The address values of {@code message.xml}, billing and shipping alike, which must stay out of the log. */
        static final List<String> ADDRESS_DATA = List.of("77 Geary St", "Level 4", "San Francisco", "94108");

        /** How long lines written after the export get to arrive before the output is scanned for address data. */
        private static final Duration SETTLE = Duration.ofMillis(300);

        /** Most offending lines a failure reports; the capture holds the whole run, so it is never dumped. */
        private static final int REPORTED_LINES = 20;

        /** The logger levels set before this run's context started, by logger name. */
        private static final Map<String, Level> LEVELS_BEFORE = new HashMap<>();

        @LocalServerPort
        int port;

        @BeforeAll
        static void recordLevels() {
            LEVELS_BEFORE.clear();
            for (Logger logger : loggerContext().getLoggerList()) {
                if (logger.getLevel() != null) {
                    LEVELS_BEFORE.put(logger.getName(), logger.getLevel());
                }
            }
        }

        @AfterAll
        static void restoreLevels() {
            for (Logger logger : loggerContext().getLoggerList()) {
                Level before = LEVELS_BEFORE.get(logger.getName());
                // The root logger always has a level and cannot be given none.
                if (before != null || !org.slf4j.Logger.ROOT_LOGGER_NAME.equals(logger.getName())) {
                    logger.setLevel(before);
                }
            }
        }

        @Test
        void shippingOrderAnswersAndExportsWhileTheLogHoldsNoAddress(CapturedOutput output) throws Exception {
            byte[] message = resource("message.xml");
            assertThat(new String(message, UTF_8)).contains(ADDRESS_DATA);
            Logger root = loggerContext().getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            ListAppender<ILoggingEvent> events = new ListAppender<>();
            events.setContext(loggerContext());
            events.start();
            root.addAppender(events);
            HttpTestClient.Response response;
            try {
                response = postXml(HttpTestClient.localhost(port), LISTENER_PATH, message);
                await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50))
                        .untilAsserted(() -> assertThat(shippingOrderFiles()).hasSize(1));
            } finally {
                root.detachAppender(events);
                events.stop();
            }
            // The appender appends under its own lock, so reading under it sees every event the exchange wrote.
            List<ILoggingEvent> logged;
            synchronized (events) {
                logged = List.copyOf(events.list);
            }

            assertThat(response.status()).as("%s", response).isEqualTo(200);
            assertThat(response.bodyAsString()).isEqualTo(new String(resource("reply.xml"), UTF_8));

            ILoggingEvent invocation = logged.stream()
                    .filter(event -> event.getLevel() == Level.TRACE
                            && DefaultMethodEndpointAdapter.class.getName().equals(event.getLoggerName())
                            && event.getFormattedMessage().startsWith("Invoking [")
                            && event.getFormattedMessage().contains(FulfillmentEndpoint.class.getName()))
                    .findFirst()
                    .orElse(null);
            assertThat(invocation).as("the unpinned DefaultMethodEndpointAdapter TRACE line of the order").isNotNull();
            assertThat(output.getAll()).as("the console capture holds the TRACE line of the order")
                    .contains(invocation.getFormattedMessage());
            // Lines written after the export get SETTLE to arrive; the capture only grows, so one scan covers the wait.
            await().pollDelay(SETTLE).until(() -> true);
            assertThat(linesHoldingAddressData(output.getAll())).as("console lines holding the order's addresses")
                    .isEmpty();
        }

        /** The distinct lines of {@code output} holding an address value, at most {@link #REPORTED_LINES}. */
        private static List<String> linesHoldingAddressData(String output) {
            // The capture of a TRACE run is large, so it is split into lines only when it holds a value at all.
            if (ADDRESS_DATA.stream().noneMatch(output::contains)) {
                return List.of();
            }
            return output.lines()
                    .filter(line -> ADDRESS_DATA.stream().anyMatch(line::contains))
                    .distinct()
                    .limit(REPORTED_LINES)
                    .toList();
        }

        private static LoggerContext loggerContext() {
            return (LoggerContext) LoggerFactory.getILoggerFactory();
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

    /** SAAJ keeps {@code Accept} and {@code SOAPAction} in every message's MIME headers; CXF sent neither back. */
    private static void assertNoRequestHeaders(HttpTestClient.Response response) {
        assertThat(response.header("Accept")).as("Accept of %s", response).isNull();
        assertThat(response.header("SOAPAction")).as("SOAPAction of %s", response).isNull();
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
