/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.legacy_modernization;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.mulesoft.examples.legacy_modernization.support.HttpTestClient;

/**
 * Port of the original example's {@code LegacyModernizationIT} functional test: the committed SOAP request
 * {@code message.xml} is posted to the {@code IFulfillment} listener {@code /OrderFulfillment}, and the reply must
 * equal the committed {@code reply.xml} exactly, as the original compared it. Nothing is mocked: the reply comes from
 * the endpoint, the JAXB bindings and the CXF envelope styler, and the flow's asynchronous CSV export runs for real.
 * Wire-level checks of status, headers, faults and the CSV content live in {@code controller/FulfillmentEndpointTest}.
 *
 * <p>The export directory is a {@link TempDir}, so the test writes nothing under {@code src/main/resources/Output} or
 * the surefire default under {@code target/}. The context is closed after the class, before that directory is
 * removed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LegacyModernizationTest {

    /** The original's {@code http://localhost:1080/OrderFulfillment}, on the random port. */
    private static final String LISTENER_PATH = "/OrderFulfillment";

    private static final String CONTENT_TYPE = "text/xml;charset=UTF-8";

    /** The original slept 5000 ms after the reply so the asynchronous export finished; that budget bounds the wait. */
    private static final Duration EXPORT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * {@code ShippingOrder-<dd-MM-yy_HH-mm-ss.SSS>.txt}. The writer fills a hidden {@code .<uuid>.tmp} file and renames
     * it into place, so a matching file is always complete.
     */
    private static final Pattern EXPORT_FILE_NAME = Pattern.compile("ShippingOrder-.*\\.txt");

    @TempDir
    static Path outputDir;

    /** Points the asynchronous export at this class's directory, overriding the surefire default under target/. */
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("fulfillment.output-dir", () -> outputDir.toAbsolutePath().toString());
    }

    @LocalServerPort
    private int port;

    private String message;
    private String reply;

    @BeforeEach
    void init() throws IOException {
        message = classpathResource("message.xml");
        reply = classpathResource("reply.xml");
    }

    @Test
    void testHTTPmessage() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", CONTENT_TYPE);
        HttpTestClient.Response response =
                HttpTestClient.localhost(port).send("POST", LISTENER_PATH, headers, message.getBytes(UTF_8));

        assertNotNull(response);
        // A Mule NullPayload result corresponds to an empty HTTP body.
        assertFalse(response.bodyAsString().isEmpty(), () -> "reply payload must not be empty: " + response);
        assertEquals(reply, response.bodyAsString(), () -> "unexpected reply: " + response);

        await().atMost(EXPORT_TIMEOUT).pollInterval(Duration.ofMillis(50)).until(() -> exportedFileCount() == 1);
    }

    private static long exportedFileCount() throws IOException {
        try (Stream<Path> files = Files.list(outputDir)) {
            return files.filter(file -> EXPORT_FILE_NAME.matcher(file.getFileName().toString()).matches()).count();
        }
    }

    private static String classpathResource(String location) throws IOException {
        try (InputStream in = new ClassPathResource(location).getInputStream()) {
            return new String(in.readAllBytes(), UTF_8);
        }
    }
}
