/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.trigger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.ResourceAccessException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.client.CurrencyClient;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.mapper.DwCsvWriter;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.mapper.DwJsonWriter;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.mapper.OrderMapper;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.mapper.OrderReportMapper;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.service.FileOutboundWriter;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.service.OrderFlowService;

/**
 * Trigger test of {@link OrderFileTrigger}, the replacement of the {@code file:inbound-endpoint} of flow
 * {@code OrderFlow} in {@code processing-orders-with-dataweave-and-APIkit/src/main/app/books.xml} (l.7-43). The
 * original has no HTTP trigger for this flow, so this test stands in for its endpoint test (AAP 0.8.2): an orders
 * file dropped into the input directory is picked up by one poll, run through the currency enrichment, both
 * DataWeave scripts and both {@code file:outbound-endpoint}s, and consumed.
 *
 * <p>The endpoint declares no {@code moveToDirectory}, so SC-5 {@link FileInboundPoller} deletes every handed file,
 * after a completed flow and after a failed one alike, as Mule's default exception strategy ended the flow
 * normally. A failure therefore never escapes the poll and never leaves an output file behind.
 *
 * <p>No Spring context is started; the end-to-end Spring port of the original IT is
 * {@code ProcessingOrdersWithDataweaveAndApikitTest}. Only the Currency API client is mocked: the order flow, its
 * mappers, the SC-4 writers and the SC-6 file writer are real, so the byte assertions exercise them for real. Every
 * directory is a {@link TempDir}, so {@code src/main/resources/input} and {@code src/main/resources/output} are
 * never touched, and polls run on demand through the single-run method (AAP 0.3.2), never by waiting.
 */
class OrderFileTriggerTest {

    private static final String ORDERS_XML = "orders.xml";

    private static final String ORDERS_JSON = "orders.json";

    private static final String REPORT_CSV = "report.csv";

    /** The {@code orders.polling-frequency} {@code application.yml} sets: Mule's default of 1000 ms. */
    private static final String POLLING_FREQUENCY = "1000";

    /** The original's committed {@code report.csv}, spelled out to pin its bytes independently of the fixture. */
    private static final String ORIGINAL_REPORT =
            "totalNumberOfItems,totalAmount,averageSellingPrice\n4,149.93,37.4825\n";

    /**
     * Parses {@code currency.json} the way {@link CurrencyClient} parses the Currency API response: every ratio an
     * exact {@link java.math.BigDecimal} with its trailing zeros kept, so {@code 0.92 * 30} keeps scale 2 and renders
     * {@code 27.60}.
     */
    private static final JsonMapper EXACT_DECIMALS = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    @TempDir
    Path inDir;

    @TempDir
    Path outDir;

    private CurrencyClient currencyClient;

    private JsonNode currencies;

    private OrderFileTrigger trigger;

    @BeforeEach
    void createTriggerOverTheRealFlow() throws IOException {
        assertThat(inDir).isNotEqualTo(outDir);
        currencies = EXACT_DECIMALS.readTree(classpath("/currency.json"));
        currencyClient = mock(CurrencyClient.class);
        OrderFlowService flow = new OrderFlowService(currencyClient,
                new OrderMapper(new DwJsonWriter()), new OrderReportMapper(new DwCsvWriter()),
                new FileOutboundWriter(), outDir.toString());
        trigger = new OrderFileTrigger(flow, inDir.toString(), POLLING_FREQUENCY);
    }

    @Test
    @DisplayName("orders.xml -> the original orders.json and report.csv byte for byte, input deleted")
    void happyPathWritesOrdersJsonAndReportCsvAndDeletesInput() throws IOException {
        when(currencyClient.getCurrencies()).thenReturn(currencies);
        Path input = dropOrders();

        assertThat(trigger.pollOnce()).isEqualTo(1);

        assertOriginalOutputs();
        assertThat(input).doesNotExist();
        assertThat(listing(inDir)).isEmpty();
        verify(currencyClient, times(1)).getCurrencies();
    }

    @Test
    @DisplayName("Currency API unreachable -> the poll does not throw, the input is consumed, nothing is written")
    void currencyFailureConsumesInputAndWritesNothing() throws IOException {
        when(currencyClient.getCurrencies())
                .thenThrow(new ResourceAccessException("I/O error on GET request: Connection refused"));
        Path input = dropOrders();
        AtomicInteger handed = new AtomicInteger(-1);

        assertThatCode(() -> handed.set(trigger.pollOnce())).doesNotThrowAnyException();

        assertConsumedWithoutOutput(handed.get(), input);
        verify(currencyClient, times(1)).getCurrencies();
    }

    @Test
    @DisplayName("Malformed XML -> the poll does not throw, the input is consumed, nothing is written")
    void malformedXmlConsumesInputAndWritesNothing() throws IOException {
        when(currencyClient.getCurrencies()).thenReturn(currencies);
        Path input = Files.write(inDir.resolve("broken.xml"), "<orders><item><price>30</price>".getBytes(UTF_8));
        AtomicInteger handed = new AtomicInteger(-1);

        assertThatCode(() -> handed.set(trigger.pollOnce())).doesNotThrowAnyException();

        assertConsumedWithoutOutput(handed.get(), input);
        // The enricher precedes the transform in books.xml, so the currencies are fetched before the parse fails.
        verify(currencyClient, times(1)).getCurrencies();
    }

    @Test
    @DisplayName("Empty input directory -> nothing handed over, no currency call, nothing written")
    void emptyInputDirectoryReturnsZero() throws IOException {
        assertThat(trigger.pollOnce()).isZero();

        assertThat(trigger.inputDirectory()).isEqualTo(inDir);
        verifyNoInteractions(currencyClient);
        assertThat(listing(outDir)).isEmpty();
    }

    @Test
    @DisplayName("poll() is scheduled at the Mule default 1000 ms and runs the same single poll")
    void pollIsScheduledWithOriginalDefaultFrequency() throws Exception {
        Scheduled scheduled = OrderFileTrigger.class.getMethod("poll").getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString()).isEqualTo("${orders.polling-frequency:1000}");

        when(currencyClient.getCurrencies()).thenReturn(currencies);
        Path input = dropOrders();

        trigger.poll();

        assertOriginalOutputs();
        assertThat(input).doesNotExist();
        verify(currencyClient, times(1)).getCurrencies();
    }

    @ParameterizedTest(name = "orders.polling-frequency \"{0}\" -> start-up fails naming the key and the value")
    @ValueSource(strings = {"0", "-1", "PT0S", "PT-1S", "abc", ""})
    void unschedulableFrequencyFailsNamingKeyAndValue(String frequency) {
        OrderFlowService flow = mock(OrderFlowService.class);

        assertThatThrownBy(() -> new OrderFileTrigger(flow, inDir.toString(), frequency))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orders.polling-frequency")
                .hasMessageContaining("\"" + frequency + "\"");
    }

    @ParameterizedTest(name = "orders.polling-frequency \"{0}\" -> the trigger is created")
    @ValueSource(strings = {"1", "1000", "PT10S", "pt0.5s"})
    void schedulableFrequencyCreatesTheTrigger(String frequency) {
        OrderFlowService flow = mock(OrderFlowService.class);
        AtomicReference<OrderFileTrigger> created = new AtomicReference<>();

        assertThatCode(() -> created.set(new OrderFileTrigger(flow, inDir.toString(), frequency)))
                .doesNotThrowAnyException();

        assertThat(created.get().inputDirectory()).isEqualTo(inDir);
        verifyNoInteractions(flow);
    }

    /** Places the original's {@code orders.xml} in the input directory, as the original IT dispatched it there. */
    private Path dropOrders() throws IOException {
        byte[] orders = classpath("/" + ORDERS_XML);
        assertThat(orders).hasSize(1185);
        return Files.write(inDir.resolve(ORDERS_XML), orders);
    }

    /** Asserts the output directory holds the original's two outputs, byte for byte, and nothing else. */
    private void assertOriginalOutputs() throws IOException {
        byte[] json = Files.readAllBytes(outDir.resolve(ORDERS_JSON));
        byte[] csv = Files.readAllBytes(outDir.resolve(REPORT_CSV));
        assertThat(json).isEqualTo(classpath("/expected/" + ORDERS_JSON));
        assertThat(csv).isEqualTo(classpath("/expected/" + REPORT_CSV));
        assertThat(csv).isEqualTo(ORIGINAL_REPORT.getBytes(UTF_8));
        assertThat(listing(outDir))
                .extracting(entry -> entry.getFileName().toString())
                .containsExactlyInAnyOrder(ORDERS_JSON, REPORT_CSV);
    }

    /**
     * Asserts the disposition of a file whose flow failed. {@link FileInboundPoller#pollOnce()} documents its result
     * as "the number of files handed to the handler in this poll, counting a file whose handler threw", so the
     * failed file still counts as 1; and it consumes the file exactly as after a success, so nothing is retried.
     */
    private void assertConsumedWithoutOutput(int handed, Path input) throws IOException {
        assertThat(handed).isEqualTo(1);
        assertThat(input).doesNotExist();
        assertThat(listing(inDir)).isEmpty();
        assertThat(listing(outDir)).isEmpty();
    }

    private static byte[] classpath(String location) throws IOException {
        try (InputStream in = OrderFileTriggerTest.class.getResourceAsStream(location)) {
            assertThat(in).as("classpath resource %s", location).isNotNull();
            return in.readAllBytes();
        }
    }

    private static List<Path> listing(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.toList();
        }
    }
}
