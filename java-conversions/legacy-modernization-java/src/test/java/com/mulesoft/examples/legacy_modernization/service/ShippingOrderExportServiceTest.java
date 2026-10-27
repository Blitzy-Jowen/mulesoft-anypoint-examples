/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.legacy_modernization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.mulesoft.examples.legacy_modernization.mapper.DwCsvWriter;
import com.mulesoft.examples.legacy_modernization.mapper.ShippingOrderCsvMapper;
import com.mulesoft.examples.legacy_modernization.model.Address;
import com.mulesoft.examples.legacy_modernization.model.Order;
import com.mulesoft.examples.legacy_modernization.model.OrderItem;
import com.mulesoft.examples.legacy_modernization.model.ShippingOrder;
import com.mulesoft.examples.legacy_modernization.model.ShippingOrderConfirmation;

/**
 * Unit test of {@link ShippingOrderExportService}, the "Async - Legacy Fulfillment Service" scope of the original
 * {@code FufillmentWebService.xml}: the confirmation mapped to CSV by the "Map Fullfilment order to CSV" script and
 * written by the {@code file:outbound-endpoint} as
 * {@code ShippingOrder-#[server.dateTime.format('dd-MM-yy_HH-mm-ss.SSS')].txt}, a same-named file overwritten, and a
 * failure inside the scope logged at ERROR and never reaching the caller.
 *
 * <p>The fixed clock names the file {@code ShippingOrder-04-03-15_05-06-07.089.txt}; the three-item expectation is
 * the confirmation of the original test request {@code message.xml}, in the DataWeave 1.0 CSV policy (comma
 * separator, header row, LF after every line, the last included). {@link ShippingOrderExportService#export} is
 * called directly, without the Spring proxy, so it runs on the test thread.
 *
 * <p>The collaborators are real: {@link ShippingOrderCsvMapper} over {@link DwCsvWriter} maps the confirmation and
 * {@link FileOutboundWriter} writes into a {@link TempDir}, never into {@code src/main/resources/Output}. No Spring
 * context or mock is used.
 */
@ExtendWith(OutputCaptureExtension.class)
class ShippingOrderExportServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2015-03-04T05:06:07.089Z"), ZoneOffset.UTC);

    private static final String FIXED_FILE_NAME = "ShippingOrder-04-03-15_05-06-07.089.txt";

    /** The original {@code server.dateTime.format('dd-MM-yy_HH-mm-ss.SSS')}, written out independently. */
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("dd-MM-yy_HH-mm-ss.SSS");

    private static final Pattern FILE_NAME =
            Pattern.compile("ShippingOrder-(\\d{2}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.\\d{3})\\.txt");

    private static final String EXPECTED_CSV = "MSKU,QTY,BillingAddressName,BillingAddressStreet,BillingAddrCity,"
            + "BillingAddrState,BillingAddrCountry,BillingAddrZipCode,ShippingAddrName,ShippingAddrStreet,"
            + "ShippingAddrCity,ShippingAddrState,ShippingAddrCountry,ShippingAddrZipCode,ShippingId\n"
            + "1234,500,Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,"
            + "Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,1234\n"
            + "6789,1500,Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,"
            + "Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,1234\n"
            + "9998,5000,Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,"
            + "Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,1234\n";

    @TempDir
    Path outputDir;

    @Test
    @DisplayName("exportNow: fixed clock -> <outputDir>/ShippingOrder-04-03-15_05-06-07.089.txt holding the CSV")
    void exportNowWritesTimestampedCsv() throws Exception {
        Path written = service(FIXED_CLOCK).exportNow(confirmation("Level 4"));

        assertThat(written).isEqualTo(outputDir.resolve(FIXED_FILE_NAME));
        assertThat(Files.readAllBytes(written)).isEqualTo(EXPECTED_CSV.getBytes(StandardCharsets.UTF_8));
        assertThat(Files.readString(written, StandardCharsets.UTF_8))
                .doesNotContain("\r")
                .endsWith("\n");
        assertThat(filesIn(outputDir)).containsExactly(written);
    }

    @Test
    @DisplayName("exportNow twice at one instant -> one file holding only the second, shorter CSV")
    void exportNowAtSameInstantOverwrites() throws Exception {
        ShippingOrderExportService service = service(FIXED_CLOCK);

        Path first = service.exportNow(confirmation("Level 4 Suite 400"));
        assertThat(Files.readString(first, StandardCharsets.UTF_8)).contains("77 Geary St Level 4 Suite 400");
        Path second = service.exportNow(confirmation("Level 4"));

        assertThat(second).isEqualTo(first).isEqualTo(outputDir.resolve(FIXED_FILE_NAME));
        assertThat(Files.readAllBytes(second)).isEqualTo(EXPECTED_CSV.getBytes(StandardCharsets.UTF_8));
        assertThat(filesIn(outputDir)).containsExactly(second);
    }

    @Test
    @DisplayName("export: a mappable confirmation -> the same file on the calling thread, nothing logged")
    void exportWritesCsvAndLogsNothing(CapturedOutput output) throws Exception {
        service(FIXED_CLOCK).export(confirmation("Level 4"));

        Path written = outputDir.resolve(FIXED_FILE_NAME);
        assertThat(filesIn(outputDir)).containsExactly(written);
        assertThat(Files.readAllBytes(written)).isEqualTo(EXPECTED_CSV.getBytes(StandardCharsets.UTF_8));
        // The original async scope holds no logger, so a successful export writes nothing to the log.
        assertThat(output.getAll()).doesNotContain(ShippingOrderExportService.class.getSimpleName());
    }

    // Only the exception type is asserted: the original runtime's error text is a Tier 2A verification point, so
    // the mapper's message wording is not part of the contract this test pins.
    @ParameterizedTest(name = "{0}")
    @MethodSource("unmappableConfirmations")
    @DisplayName("export: a confirmation the CSV script cannot map -> no exception, no file, failure logged at ERROR")
    void exportSwallowsMappingFailureAndLogsError(ShippingOrderConfirmation confirmation, CapturedOutput output)
            throws IOException {
        ShippingOrderExportService service = service(FIXED_CLOCK);

        assertThatCode(() -> service.export(confirmation)).doesNotThrowAnyException();

        assertThat(filesIn(outputDir)).isEmpty();
        assertThat(output.getAll().lines()).as("an ERROR line from the service's logger")
                .anyMatch(line -> line.contains("ERROR")
                        && line.contains(ShippingOrderExportService.class.getSimpleName()));
        assertThat(output.getAll()).contains(IllegalArgumentException.class.getName());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unmappableConfirmations")
    @DisplayName("exportNow: a confirmation the CSV script cannot map -> IllegalArgumentException, no file written")
    void exportNowRejectsUnmappableConfirmation(ShippingOrderConfirmation confirmation) throws IOException {
        ShippingOrderExportService service = service(FIXED_CLOCK);

        assertThatThrownBy(() -> service.exportNow(confirmation)).isInstanceOf(IllegalArgumentException.class);

        assertThat(filesIn(outputDir)).isEmpty();
    }

    static Stream<Arguments> unmappableConfirmations() {
        return Stream.of(
                Arguments.of(Named.of("billingAddress.line2 null", confirmation(null))),
                Arguments.of(Named.of("confirmation null", null)));
    }

    @Test
    @DisplayName("public constructor: the file is named from the system clock in the default time zone")
    void publicConstructorNamesFileFromSystemDefaultZoneClock() throws Exception {
        ShippingOrderExportService service = new ShippingOrderExportService(
                new ShippingOrderCsvMapper(new DwCsvWriter()), new FileOutboundWriter(), outputDir.toString());

        LocalDateTime before = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        Path written = service.exportNow(confirmation("Level 4"));
        LocalDateTime after = LocalDateTime.now();

        assertThat(written.getParent()).isEqualTo(outputDir);
        Matcher name = FILE_NAME.matcher(written.getFileName().toString());
        assertThat(name.matches()).as("file name %s", written.getFileName()).isTrue();
        assertThat(LocalDateTime.parse(name.group(1), FILE_TIMESTAMP)).isBetween(before, after);
        assertThat(Files.readAllBytes(written)).isEqualTo(EXPECTED_CSV.getBytes(StandardCharsets.UTF_8));
        assertThat(filesIn(outputDir)).containsExactly(written);
    }

    private ShippingOrderExportService service(Clock clock) {
        return new ShippingOrderExportService(new ShippingOrderCsvMapper(new DwCsvWriter()), new FileOutboundWriter(),
                outputDir.toString(), clock);
    }

    // The confirmation of the original test request message.xml, with the billing address's line2 replaceable.
    private static ShippingOrderConfirmation confirmation(String billingLine2) {
        Address billing = new Address("Mulesoft", "77 Geary St", billingLine2, "San Francisco", "CA", "USA", "94108");
        Address shipping = new Address("Mulesoft", "77 Geary St", "Level 4", "San Francisco", "CA", "USA", "94108");
        Order order = new Order(new ArrayList<>(List.of(
                new OrderItem("1234", 500), new OrderItem("6789", 1500), new OrderItem("9998", 5000))));
        return new ShippingOrderConfirmation(new ShippingOrder("1234", billing, shipping, order), true);
    }

    private static List<Path> filesIn(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.toList();
        }
    }
}
