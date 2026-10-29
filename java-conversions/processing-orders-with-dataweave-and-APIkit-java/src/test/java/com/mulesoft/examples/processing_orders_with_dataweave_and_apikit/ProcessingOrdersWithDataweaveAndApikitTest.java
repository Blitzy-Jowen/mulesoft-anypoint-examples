/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Port of the original {@code ProcessingOrdersWithDataweaveAndApikitIT}: the classpath {@code orders.xml} is
 * dropped into the input directory of flow {@code OrderFlow}, and the {@code orders.json} and {@code report.csv} the
 * flow writes are compared with the committed expected files with all whitespace removed, as the original compared
 * them. Byte-level checks of the same outputs live in the mapper, service and trigger tests.
 *
 * <p>Nothing is mocked: the scheduled {@code OrderFileTrigger} polls the directory, and the order flow fetches the
 * currencies from this application's own {@code GET /api/currencies} on the random port, as the original flow
 * called back on its own listener. The input and output directories sit under a {@link TempDir}, so the test never
 * touches {@code src/main/resources/input} or {@code src/main/resources/output}. The context is closed after the
 * class so its scheduled poller stops before the directory is removed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProcessingOrdersWithDataweaveAndApikitTest {

    private static final Logger log = LoggerFactory.getLogger(ProcessingOrdersWithDataweaveAndApikitTest.class);

    /** The original slept 5000 ms before reading the outputs; the same budget bounds the wait here. */
    private static final Duration PROCESSING_TIMEOUT = Duration.ofSeconds(5);

    private static final String ORDERS_XML = "orders.xml";

    @TempDir
    static Path base;

    /** Points the flow's endpoints at this class's directories, overriding the surefire defaults under target/. */
    @DynamicPropertySource
    static void orderDirectories(DynamicPropertyRegistry registry) {
        registry.add("orders.input-dir", () -> base.resolve("input").toString());
        registry.add("orders.output-dir", () -> base.resolve("output").toString());
    }

    @Test
    void processOrders() throws Exception {
        String orders = classpathResource(ORDERS_XML);
        log.info("Testing application");
        dispatch(orders);

        log.info("Reading expected result files");
        String expectedFileOrdersOutput = classpathResource("expected/orders.json");
        String expectedFileReportOutput = classpathResource("expected/report.csv");

        Path output = base.resolve("output");
        Path ordersJson = output.resolve("orders.json");
        Path reportCsv = output.resolve("report.csv");
        Path input = base.resolve("input").resolve(ORDERS_XML);

        log.info("Reading resulting files");
        await().atMost(PROCESSING_TIMEOUT).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            assertTrue(Files.exists(ordersJson), "orders.json has not been written");
            assertTrue(Files.exists(reportCsv), "report.csv has not been written");
            String resultFileOrdersOutput = Files.readString(ordersJson, UTF_8);
            String resultFileReportOutput = Files.readString(reportCsv, UTF_8);

            assertEquals(expectedFileOrdersOutput.replaceAll("\\s", ""), resultFileOrdersOutput.replaceAll("\\s", ""));
            assertEquals(expectedFileReportOutput.replaceAll("\\s", ""), resultFileReportOutput.replaceAll("\\s", ""));
            // No moveToDirectory on the inbound endpoint: the processed file is deleted.
            assertFalse(Files.exists(input), "orders.xml is still in the input directory");
        });
    }

    /**
     * Replaces the original's {@code client.dispatch("file://./src/main/resources/input", message)} with
     * {@code outputPattern} {@code orders.xml}. The file is written outside the polled directory and moved in
     * atomically, so the running poller never reads a partially written file.
     */
    private static void dispatch(String content) throws IOException {
        Path staging = Files.createDirectories(base.resolve("staging")).resolve(ORDERS_XML);
        Files.writeString(staging, content, UTF_8);
        Path inputDir = Files.createDirectories(base.resolve("input"));
        Files.move(staging, inputDir.resolve(ORDERS_XML), StandardCopyOption.ATOMIC_MOVE);
    }

    private static String classpathResource(String location) throws IOException {
        try (InputStream in = new ClassPathResource(location).getInputStream()) {
            return new String(in.readAllBytes(), UTF_8);
        }
    }
}
