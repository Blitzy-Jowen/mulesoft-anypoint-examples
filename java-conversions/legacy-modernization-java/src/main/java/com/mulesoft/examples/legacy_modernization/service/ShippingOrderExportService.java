/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.legacy_modernization.service;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.legacy_modernization.mapper.ShippingOrderCsvMapper;
import com.mulesoft.examples.legacy_modernization.model.ShippingOrderConfirmation;

/**
 * The "Async - Legacy Fulfillment Service" scope of the original {@code Fulfillment_LegacySystemModernization}
 * flow: after the fulfillment component returns its {@link ShippingOrderConfirmation}, the confirmation is mapped
 * to CSV and written to the legacy fulfillment system's drop directory, without holding up the SOAP response.
 *
 * <p>The steps keep the scope's processor order: the "Map Fullfilment order to CSV" DataWeave script
 * ({@link ShippingOrderCsvMapper}), the {@code byte-array-to-string-transformer} (the mapper already returns text),
 * then the {@code file:outbound-endpoint} ({@link FileOutboundWriter}) with
 * {@code outputPattern="ShippingOrder-#[server.dateTime.format('dd-MM-yy_HH-mm-ss.SSS')].txt"} under
 * {@code fulfillment.output-dir} ({@code src/main/resources/Output} by default). The file name is taken from the
 * clock when the file is written, and a same-named file is overwritten.
 *
 * <p>{@link #export(ShippingOrderConfirmation)} runs on Boot's {@code applicationTaskExecutor}, whose threads are
 * named {@code legacy-fulfillment-async-}; a failure is logged at ERROR and never reaches the caller, as Mule's
 * default exception strategy handled a failure inside an {@code async} scope.
 *
 * <p>Instances hold no mutable state and are safe to share between threads.
 */
@Service
public class ShippingOrderExportService {

    private static final Logger log = LoggerFactory.getLogger(ShippingOrderExportService.class);

    /** The pattern of the original {@code server.dateTime.format('dd-MM-yy_HH-mm-ss.SSS')}. */
    static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("dd-MM-yy_HH-mm-ss.SSS");

    private static final String FILE_PREFIX = "ShippingOrder-";
    private static final String FILE_SUFFIX = ".txt";

    private final ShippingOrderCsvMapper mapper;
    private final FileOutboundWriter writer;
    private final String outputDir;
    private final Clock clock;

    /**
     * Creates the service on the system clock in the default time zone, the server-local time Mule's
     * {@code server.dateTime} gave the file name.
     *
     * @param mapper    the owner of the CSV DataWeave script
     * @param writer    the {@code file:outbound-endpoint} replacement
     * @param outputDir the endpoint's {@code path}, absolute or relative to the working directory
     */
    @Autowired
    public ShippingOrderExportService(ShippingOrderCsvMapper mapper, FileOutboundWriter writer,
            @Value("${fulfillment.output-dir}") String outputDir) {
        this(mapper, writer, outputDir, Clock.systemDefaultZone());
    }

    /**
     * Creates the service on the given clock, so tests fix the file name's timestamp.
     *
     * @param mapper    the owner of the CSV DataWeave script
     * @param writer    the {@code file:outbound-endpoint} replacement
     * @param outputDir the endpoint's {@code path}, absolute or relative to the working directory
     * @param clock     the source of the file name's timestamp, read in its own time zone
     */
    ShippingOrderExportService(ShippingOrderCsvMapper mapper, FileOutboundWriter writer, String outputDir,
            Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.outputDir = Objects.requireNonNull(outputDir, "outputDir");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Exports the confirmation to the legacy fulfillment system on the asynchronous executor. Called through the
     * Spring proxy, it returns at once; every failure is logged at ERROR and swallowed, so the SOAP response that
     * follows the call is never affected. Success is not logged: the original scope holds no {@code logger}.
     *
     * @param confirmation the confirmation the fulfillment service returned
     */
    @Async
    public void export(ShippingOrderConfirmation confirmation) {
        try {
            exportNow(confirmation);
        } catch (Exception e) {
            log.error("Exception while exporting shipping order to the legacy fulfillment system", e);
        }
    }

    /**
     * Runs the scope's steps on the calling thread: maps the confirmation to CSV, then writes the text as UTF-8 to
     * {@code <outputDir>/ShippingOrder-<dd-MM-yy_HH-mm-ss.SSS>.txt}, creating the directory when missing and
     * overwriting a same-named file. The CSV is written exactly as the mapper returns it, so an order without
     * items yields an empty file.
     *
     * @param confirmation the confirmation the fulfillment service returned
     * @return the written file
     * @throws IllegalArgumentException when a part the CSV script navigates is {@code null}; no file is written
     * @throws Exception                when the file cannot be written: the {@link java.io.IOException}
     *                                  {@link FileOutboundWriter} reports the failure with, passed on as thrown
     */
    Path exportNow(ShippingOrderConfirmation confirmation) throws Exception {
        String content = mapper.toCsv(confirmation);
        String name = FILE_PREFIX + LocalDateTime.now(clock).format(FILE_TIMESTAMP) + FILE_SUFFIX;
        return writer.write(outputDir, name, content);
    }
}
