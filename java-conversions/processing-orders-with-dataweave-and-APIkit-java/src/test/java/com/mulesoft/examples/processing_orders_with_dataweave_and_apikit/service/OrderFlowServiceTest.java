/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.web.client.ResourceAccessException;
import org.w3c.dom.Document;
import org.xml.sax.SAXParseException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.client.CurrencyClient;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.mapper.DwCsvWriter;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.mapper.DwJsonWriter;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.mapper.OrderMapper;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.mapper.OrderReportMapper;

/**
 * Unit test of {@link OrderFlowService}, the replacement of flow {@code OrderFlow} of
 * {@code processing-orders-with-dataweave-and-APIkit/src/main/app/books.xml} (l.7-43).
 *
 * <p>The bundled {@code orders.xml} and {@code currency.json} must produce the original's committed
 * {@code orders.json} and {@code report.csv} byte for byte, carried unchanged as {@code expected/orders.json} and
 * {@code expected/report.csv}; their lengths and SHA-256 values are those of the originals, so a mismatch points at
 * the service, a mapper or a writer, never at the expectation. The processors run in the flow's order (currency
 * enrichment, one parse read by both scripts, {@code orders.json}, then {@code report.csv}), and every failure
 * reaches the caller unchanged with no file written, or, after a failed {@code orders.json} write, with
 * {@code report.csv} not attempted.
 *
 * <p>No Spring context is started: the Currency API client is a mock returning the tree it would parse, the
 * mappers and their DataWeave-format writers are real and spied on to observe the statement order, and the SC-6
 * writer is a spy writing for real into a per-test temporary directory, so {@code src/main/resources/output} is
 * never touched.
 */
class OrderFlowServiceTest {

    private static final String ORDERS_JSON = "orders.json";

    private static final String REPORT_CSV = "report.csv";

    private static final int EXPECTED_ORDERS_JSON_LENGTH = 793;

    private static final String EXPECTED_ORDERS_JSON_SHA256 =
            "0784d83836248b1c080655606990288f794693889fb54d41b04e6af8a4990610";

    private static final int EXPECTED_REPORT_CSV_LENGTH = 68;

    private static final String EXPECTED_REPORT_CSV_SHA256 =
            "a714697949e651545156ed6df4731e73c2dd4f73bda6b9fcac6eed8c5c806fc7";

    /**
     * The configuration {@link CurrencyClient} parses the Currency API response with: floating-point numbers as
     * {@link java.math.BigDecimal} with their trailing zeros kept, so {@code 0.92 * 30} keeps scale 2 and renders
     * {@code 27.60}.
     */
    private static final JsonMapper CLIENT_EQUIVALENT_MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    @TempDir
    Path out;

    private CurrencyClient client;

    private OrderMapper orderMapper;

    private OrderReportMapper orderReportMapper;

    private FileOutboundWriter writer;

    private OrderFlowService service;

    private JsonNode currencies;

    private byte[] ordersXml;

    @BeforeEach
    void createServiceWithCollaborators() throws IOException {
        currencies = CLIENT_EQUIVALENT_MAPPER.readTree(resource("currency.json"));
        ordersXml = resource("orders.xml");
        client = mock(CurrencyClient.class);
        orderMapper = spy(new OrderMapper(new DwJsonWriter()));
        orderReportMapper = spy(new OrderReportMapper(new DwCsvWriter()));
        writer = spy(new FileOutboundWriter());
        service = new OrderFlowService(client, orderMapper, orderReportMapper, writer, out.toString());
    }

    @Test
    @DisplayName("orders.xml -> the original orders.json and report.csv, byte for byte, and no other file")
    void writesTheOriginalOutputsByteForByte() throws Exception {
        when(client.getCurrencies()).thenReturn(currencies);
        byte[] expectedOrders = resource("expected/" + ORDERS_JSON);
        byte[] expectedReport = resource("expected/" + REPORT_CSV);
        assertThat(expectedOrders).hasSize(EXPECTED_ORDERS_JSON_LENGTH);
        assertThat(expectedReport).hasSize(EXPECTED_REPORT_CSV_LENGTH);

        service.process(ordersXml);

        byte[] orders = Files.readAllBytes(out.resolve(ORDERS_JSON));
        byte[] report = Files.readAllBytes(out.resolve(REPORT_CSV));
        assertThat(orders).isEqualTo(expectedOrders);
        assertThat(report).isEqualTo(expectedReport);
        assertThat(sha256(orders)).isEqualTo(EXPECTED_ORDERS_JSON_SHA256);
        assertThat(sha256(report)).isEqualTo(EXPECTED_REPORT_CSV_SHA256);
        assertThat(new String(orders, StandardCharsets.UTF_8))
                .contains("\"price\": 27.60", "\"price\": 262.80", "\"price\": 19.80")
                .contains("\"price\": 27.5908", "\"price\": 262.7124", "\"price\": 19.7934");
        assertThat(new String(report, StandardCharsets.UTF_8)).endsWith("\n4,149.93,37.4825\n");
        assertThat(outputFileNames()).containsExactlyInAnyOrder(ORDERS_JSON, REPORT_CSV);
    }

    @Test
    @DisplayName("processor order: currencies, both scripts on one parse, then orders.json, then report.csv")
    void runsTheProcessorsInFlowOrder() throws Exception {
        when(client.getCurrencies()).thenReturn(currencies);
        String expectedJson = new String(resource("expected/" + ORDERS_JSON), StandardCharsets.UTF_8);
        String expectedCsv = new String(resource("expected/" + REPORT_CSV), StandardCharsets.UTF_8);

        service.process(ordersXml);

        ArgumentCaptor<Document> parsed = ArgumentCaptor.forClass(Document.class);
        InOrder inOrder = inOrder(client, orderMapper, orderReportMapper, writer);
        inOrder.verify(client).getCurrencies();
        inOrder.verify(orderMapper).toJson(parsed.capture(), same(currencies));
        inOrder.verify(orderReportMapper).toCsv(same(parsed.getValue()));
        inOrder.verify(writer).write(eq(out.toString()), eq(ORDERS_JSON), eq(expectedJson));
        inOrder.verify(writer).write(eq(out.toString()), eq(REPORT_CSV), eq(expectedCsv));
        verify(writer, times(2)).write(anyString(), anyString(), anyString());
        verifyNoMoreInteractions(client);
    }

    @Test
    @DisplayName("a failed currency call propagates unchanged and writes nothing")
    void propagatesTheCurrencyClientFailureUnchanged() {
        ResourceAccessException failure = new ResourceAccessException("I/O error on GET");
        when(client.getCurrencies()).thenThrow(failure);

        assertThatThrownBy(() -> service.process(ordersXml)).isSameAs(failure);

        verifyNoInteractions(orderMapper, orderReportMapper);
        assertNothingWritten();
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {"<orders><item>", "not xml", ""})
    @DisplayName("a payload that is not well-formed XML fails with the parser's SAXParseException and writes nothing")
    void propagatesTheParserFailureForMalformedXml(String payload) {
        when(client.getCurrencies()).thenReturn(currencies);

        assertThatThrownBy(() -> service.process(payload.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SAXParseException.class);

        verify(client).getCurrencies();
        verifyNoInteractions(orderMapper, orderReportMapper);
        assertNothingWritten();
    }

    @Test
    @DisplayName("a failed report script writes no orders.json either: both scripts run before any write")
    void writesNothingWhenTheReportScriptFails() throws Exception {
        when(client.getCurrencies()).thenReturn(currencies);

        // No item: the orders script yields {"orders": []}, while the report's average of no prices is undefined.
        assertThatThrownBy(() -> service.process("<orders/>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ArithmeticException.class);

        verify(orderMapper).toJson(any(Document.class), same(currencies));
        assertNothingWritten();
    }

    @Test
    @DisplayName("a failed orders.json write propagates unchanged and report.csv is not attempted")
    void skipsTheReportWhenTheOrdersWriteFails() throws Exception {
        when(client.getCurrencies()).thenReturn(currencies);
        IOException failure = new IOException("Streaming failed. Could not get output stream");
        doThrow(failure).when(writer).write(anyString(), eq(ORDERS_JSON), anyString());

        assertThatThrownBy(() -> service.process(ordersXml)).isSameAs(failure);

        verify(writer, never()).write(anyString(), eq(REPORT_CSV), anyString());
        assertThat(outputFileNames()).isEmpty();
    }

    @Test
    @DisplayName("a null payload is rejected before the currency call")
    void rejectsANullPayloadBeforeAnyProcessor() {
        assertThatNullPointerException().isThrownBy(() -> service.process(null)).withMessage("orderXml");

        verifyNoInteractions(client, orderMapper, orderReportMapper);
        assertNothingWritten();
    }

    /** No overload of the writer was called and the output directory is still empty. */
    private void assertNothingWritten() {
        try {
            verify(writer, never()).write(anyString(), anyString(), anyString());
            verify(writer, never()).write(anyString(), anyString(), any(byte[].class));
            verify(writer, never()).write(any(Path.class), anyString(), anyString());
            verify(writer, never()).write(any(Path.class), anyString(), any(byte[].class));
            assertThat(outputFileNames()).isEmpty();
        } catch (IOException e) {
            throw new AssertionError("Unable to inspect the output directory " + out, e);
        }
    }

    private List<String> outputFileNames() throws IOException {
        try (Stream<Path> files = Files.list(out)) {
            return files.map(path -> path.getFileName().toString()).toList();
        }
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = OrderFlowServiceTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as("classpath resource %s", name).isNotNull();
            return in.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
