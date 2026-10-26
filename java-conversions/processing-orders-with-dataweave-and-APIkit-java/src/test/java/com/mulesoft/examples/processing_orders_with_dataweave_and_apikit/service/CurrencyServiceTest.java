/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.mapper.ParseTemplateRenderer;

/**
 * Unit test of {@link CurrencyService}, the replacement of the flow {@code get:/currencies:currency-config}
 * ({@code <parse-template location="currency.json"/>}) whose result {@code GET /api/currencies} serves and the
 * order flow's currency enrichment reads.
 *
 * <p>{@code currency.json} holds no {@code #[...]} expression, so the rendered text must be the original resource
 * byte for byte, including its asymmetric spacing and missing trailing newline. The expected length and SHA-256 are
 * those of {@code processing-orders-with-dataweave-and-APIkit/src/main/resources/currency.json}; a mismatch means the
 * copied resource or the renderer changed bytes, never that the expectation is wrong.
 */
class CurrencyServiceTest {

    private static final String TEMPLATE = "currency.json";

    /** Size of the original {@code currency.json}. */
    private static final int ORIGINAL_LENGTH = 134;

    /** SHA-256 of the original {@code currency.json}. */
    private static final String ORIGINAL_SHA256 = "e3e8706144f2b8caf08300a2a80354a2d74cbc6b892a09b86032fc088d7a71cd";

    private final CurrencyService service = new CurrencyService(new ParseTemplateRenderer());

    @Test
    void returnsTheClasspathTemplateByteForByte() throws IOException {
        byte[] rendered = service.getCurrencies().getBytes(StandardCharsets.UTF_8);

        assertThat(rendered).isEqualTo(classpathTemplateBytes());
    }

    @Test
    void returnsTheOriginalCurrencyDocument() throws NoSuchAlgorithmException {
        byte[] rendered = service.getCurrencies().getBytes(StandardCharsets.UTF_8);

        assertThat(rendered).hasSize(ORIGINAL_LENGTH);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rendered)))
                .isEqualTo(ORIGINAL_SHA256);
    }

    @Test
    void keepsTheOriginalRatiosAndSpacing() {
        assertThat(service.getCurrencies())
                .contains("{\"currency\": \"EUR\", \"ratio\":0.92}")
                .contains("\"ARS\", \"ratio\":8.76")
                .contains("\"GBP\", \"ratio\":0.66");
    }

    @Test
    void returnsTheSameDocumentOnEveryCall() {
        String first = service.getCurrencies();
        String second = service.getCurrencies();

        assertThat(second).isEqualTo(first);
    }

    private static byte[] classpathTemplateBytes() throws IOException {
        try (InputStream in = CurrencyServiceTest.class.getClassLoader().getResourceAsStream(TEMPLATE)) {
            assertThat(in).as("classpath resource %s", TEMPLATE).isNotNull();
            return in.readAllBytes();
        }
    }
}
