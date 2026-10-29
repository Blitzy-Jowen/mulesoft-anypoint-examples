/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

/**
 * Unit test of {@link CurrencyClient}, the replacement of the order flow's currency enrichment in
 * {@code processing-orders-with-dataweave-and-APIkit/src/main/app/books.xml}: the {@code http:request-config}
 * (l.3-5: host {@code localhost}, port {@code ${http.port}}, basePath {@code /api/}) and the enriched
 * {@code http:request method="GET" path="/currencies"} (l.9-11).
 *
 * <p>A WireMock server on a dynamic loopback port stands in for the application's own Currency API and answers with
 * the carried {@code currency.json} (the classpath copy of the original resource), so the ratios the order mapping
 * multiplies are asserted as the exact decimals the original document writes. No Spring context is started: the
 * client is built directly from {@link RestClient#builder()} and a {@link MockEnvironment}, which holds no system
 * properties, so the port-resolution cases see only the properties each test sets.
 */
class CurrencyClientTest {

    private static final String CURRENCIES_PATH = "/api/currencies";

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(WireMockConfiguration.wireMockConfig().dynamicPort())
            .build();

    @Test
    void getCurrenciesReturnsCurrencyJsonWithExactDecimals() throws IOException {
        stubCurrencies();

        JsonNode result = clientFor(envWithHttpPort(wm.getPort())).getCurrencies();

        wm.verify(1, getRequestedFor(urlEqualTo(CURRENCIES_PATH))
                .withHeader("Accept", equalTo("application/json")));

        JsonNode usd = result.get("USD");
        assertNotNull(usd, "USD entry");
        assertTrue(usd.isArray(), "USD is an array");
        assertEquals(3, usd.size());
        assertEquals("EUR", usd.get(0).get("currency").asText());
        assertEquals("ARS", usd.get(1).get("currency").asText());
        assertEquals("GBP", usd.get(2).get("currency").asText());

        JsonNode ratio = usd.get(0).get("ratio");
        assertTrue(ratio.isBigDecimal(), "ratio is read as a BigDecimal node");
        assertEquals(new BigDecimal("0.92"), ratio.decimalValue());
        assertEquals(2, ratio.decimalValue().scale());
        assertEquals(new BigDecimal("8.76"), usd.get(1).get("ratio").decimalValue());
        assertEquals(new BigDecimal("0.66"), usd.get(2).get("ratio").decimalValue());
    }

    @Test
    void getCurrenciesKeepsTrailingZerosOfRatios() {
        // currency.json has no trailing zeros, so only these ratios show that none is stripped.
        wm.stubFor(get(urlEqualTo(CURRENCIES_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"USD\": [{\"currency\": \"EUR\", \"ratio\": 1.50},"
                                + " {\"currency\": \"ARS\", \"ratio\": 0.00},"
                                + " {\"currency\": \"GBP\", \"ratio\": 2.000}]}")));

        JsonNode usd = clientFor(envWithHttpPort(wm.getPort())).getCurrencies().get("USD");

        assertEquals(3, usd.size());
        String[] expected = {"1.50", "0.00", "2.000"};
        int[] expectedScales = {2, 2, 3};
        for (int i = 0; i < expected.length; i++) {
            JsonNode ratio = usd.get(i).get("ratio");
            assertTrue(ratio.isBigDecimal(), "ratio " + expected[i] + " is read as a BigDecimal node");
            // BigDecimal.equals compares the scale as well as the value.
            assertEquals(new BigDecimal(expected[i]), ratio.decimalValue());
            assertEquals(expectedScales[i], ratio.decimalValue().scale());
        }
    }

    @Test
    void localServerPortTakesPrecedenceOverHttpPort() throws IOException {
        stubCurrencies();
        try (Socket reserved = reserveRefusingPort()) {
            MockEnvironment env = new MockEnvironment()
                    .withProperty("local.server.port", String.valueOf(wm.getPort()))
                    .withProperty("http.port", String.valueOf(reserved.getLocalPort()));
            CurrencyClient client = clientFor(env);

            assertEquals(wm.getPort(), client.resolvePort());

            JsonNode result = client.getCurrencies();

            assertEquals(3, result.get("USD").size());
            wm.verify(1, getRequestedFor(urlEqualTo(CURRENCIES_PATH)));
        }
    }

    @Test
    void resolvePortDefaultsTo8081() {
        // getCurrencies() is not called: port 8081 may be in use on the build host.
        assertEquals(8081, clientFor(new MockEnvironment()).resolvePort());
    }

    @Test
    void serverErrorPropagatesRestClientResponseException() {
        wm.stubFor(get(urlEqualTo(CURRENCIES_PATH))
                .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));
        CurrencyClient client = clientFor(envWithHttpPort(wm.getPort()));

        RestClientResponseException ex = assertThrows(RestClientResponseException.class, client::getCurrencies);

        assertEquals(500, ex.getStatusCode().value());
        // The original http:request had no retry.
        wm.verify(1, getRequestedFor(urlEqualTo(CURRENCIES_PATH)));
    }

    @Test
    void connectionRefusedPropagatesResourceAccessException() throws IOException {
        try (Socket reserved = reserveRefusingPort()) {
            CurrencyClient client = clientFor(envWithHttpPort(reserved.getLocalPort()));

            ResourceAccessException thrown = assertThrows(ResourceAccessException.class, client::getCurrencies);

            assertTrue(thrown.contains(ConnectException.class),
                    () -> "expected a ConnectException in the cause chain of " + thrown);
        }
    }

    @Test
    void malformedJsonBecomesUncheckedIOException() {
        wm.stubFor(get(urlEqualTo(CURRENCIES_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"USD\": [")));
        CurrencyClient client = clientFor(envWithHttpPort(wm.getPort()));

        UncheckedIOException ex = assertThrows(UncheckedIOException.class, client::getCurrencies);

        assertInstanceOf(IOException.class, ex.getCause());
    }

    private static void stubCurrencies() throws IOException {
        wm.stubFor(get(urlEqualTo(CURRENCIES_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(currencyJsonBytes())));
    }

    private static CurrencyClient clientFor(MockEnvironment env) {
        return new CurrencyClient(RestClient.builder(), env);
    }

    private static MockEnvironment envWithHttpPort(int port) {
        return new MockEnvironment().withProperty("http.port", String.valueOf(port));
    }

    /** The carried {@code src/main/resources/currency.json}, byte for byte. */
    private static byte[] currencyJsonBytes() throws IOException {
        try (InputStream in = CurrencyClientTest.class.getResourceAsStream("/currency.json")) {
            assertNotNull(in, "currency.json on the classpath");
            return in.readAllBytes();
        }
    }

    /**
     * A loopback socket bound to an ephemeral port that never listens or connects: while it stays open a connection to
     * that port is refused at once rather than after the 10 s timeout, and with SO_REUSEADDR off no other socket can
     * bind the port meanwhile.
     */
    private static Socket reserveRefusingPort() throws IOException {
        Socket socket = new Socket();
        try {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            return socket;
        } catch (IOException | RuntimeException ex) {
            try {
                socket.close();
            } catch (IOException closeFailure) {
                ex.addSuppressed(closeFailure);
            }
            throw ex;
        }
    }
}
