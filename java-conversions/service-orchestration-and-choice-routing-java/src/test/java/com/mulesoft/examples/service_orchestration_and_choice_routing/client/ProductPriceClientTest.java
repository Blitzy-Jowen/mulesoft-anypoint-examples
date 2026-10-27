/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

/**
 * Unit test of {@link ProductPriceClient}, the replacement of the in-house order flow's
 * {@code http:request method="GET" path="api/prices/#[payload.productId]" port="9999"} and the
 * {@code object-to-string-transformer} after it
 * (service-orchestration-and-choice-routing/src/main/app/fulfillment.xml:105-106).
 *
 * <p>A WireMock server on a dynamic localhost port stands in for the price listener. The original price resource
 * (com/mulesoft/se/orders/ProductPrice.java) answered {@code GET /prices/{productId}} with the {@code text/plain}
 * body {@code 159}, but its 9999 listener accepts POST only (fulfillment.xml:145), so in the running application
 * this GET is answered 405 and must throw, as it did in the original; that case is covered explicitly. Every failure
 * case, a connection reset or a close without any response before the response head included, also proves the
 * adapter sends exactly one request, because the original request had no retry.
 *
 * <p>No Spring context is started: the client is built directly with its two constructor arguments.
 */
class ProductPriceClientTest {

    private static final String PRODUCT_ID = "AX02";

    private static final String PRICE_PATH = "/api/prices/" + PRODUCT_ID;

    private static final int TIMEOUT_MS = 5000;

    /** Read timeout of the timeout case, far below the stub's delay so the case fails fast and deterministically. */
    private static final int SHORT_TIMEOUT_MS = 300;

    private static final int RESPONSE_DELAY_MS = 2000;

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(WireMockConfiguration.wireMockConfig().dynamicPort())
            .build();

    private ProductPriceClient client;

    @BeforeEach
    void createClient() {
        client = clientWithTimeout(TIMEOUT_MS);
    }

    @Test
    void getPriceReturnsBodyText() {
        wm.stubFor(get(urlEqualTo(PRICE_PATH))
                .willReturn(ok("159").withHeader("Content-Type", "text/plain")));

        assertEquals("159", client.getPrice(PRODUCT_ID));

        assertSingleRequest();
    }

    @Test
    void methodNotAllowedThrowsWithStatus405() {
        // The listener's method-not-allowed answer, which the POST-only 9999 listener gives the original GET.
        wm.stubFor(get(urlEqualTo(PRICE_PATH))
                .willReturn(aResponse()
                        .withStatus(405)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("Method not allowed for endpoint: " + PRICE_PATH)));

        RestClientResponseException thrown =
                assertThrows(RestClientResponseException.class, () -> client.getPrice(PRODUCT_ID));

        assertEquals(405, thrown.getStatusCode().value());
        assertEquals("Method not allowed for endpoint: " + PRICE_PATH, thrown.getResponseBodyAsString());
        assertSingleRequest();
    }

    @Test
    void serverErrorThrowsWithStatus500() {
        wm.stubFor(get(urlEqualTo(PRICE_PATH))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("boom")));

        RestClientResponseException thrown =
                assertThrows(RestClientResponseException.class, () -> client.getPrice(PRODUCT_ID));

        assertEquals(500, thrown.getStatusCode().value());
        assertEquals("boom", thrown.getResponseBodyAsString());
        assertSingleRequest();
    }

    @Test
    void readTimeoutPropagatesWithoutRetry() {
        wm.stubFor(get(urlEqualTo(PRICE_PATH))
                .willReturn(ok("159")
                        .withHeader("Content-Type", "text/plain")
                        .withFixedDelay(RESPONSE_DELAY_MS)));
        ProductPriceClient shortTimeoutClient = clientWithTimeout(SHORT_TIMEOUT_MS);

        ResourceAccessException thrown =
                assertThrows(ResourceAccessException.class, () -> shortTimeoutClient.getPrice(PRODUCT_ID));

        assertTrue(thrown.contains(SocketTimeoutException.class),
                () -> "expected a SocketTimeoutException in the cause chain of " + thrown);
        assertSingleRequest();
    }

    @ParameterizedTest
    @EnumSource(value = Fault.class, names = {"CONNECTION_RESET_BY_PEER", "EMPTY_RESPONSE"})
    void connectionFailureBeforeResponsePropagatesWithoutRetry(Fault fault) {
        // A reset, or a close without any response, before the response head: HttpURLConnection re-sends such a GET.
        wm.stubFor(get(urlEqualTo(PRICE_PATH)).willReturn(aResponse().withFault(fault)));

        assertThrows(ResourceAccessException.class, () -> client.getPrice(PRODUCT_ID));

        assertSingleRequest();
    }

    @Test
    void emptyBodyReturnsEmptyString() {
        wm.stubFor(get(urlEqualTo(PRICE_PATH)).willReturn(aResponse().withStatus(200)));

        assertEquals("", client.getPrice(PRODUCT_ID));

        assertSingleRequest();
    }

    @Test
    void bodyDecodedWithResponseCharset() {
        String price = "159 \u00a3";
        wm.stubFor(get(urlEqualTo(PRICE_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain; charset=ISO-8859-1")
                        .withBody(price.getBytes(StandardCharsets.ISO_8859_1))));

        assertEquals(price, client.getPrice(PRODUCT_ID));
    }

    private static ProductPriceClient clientWithTimeout(int timeoutMs) {
        return new ProductPriceClient("http://localhost:" + wm.getPort() + "/api/prices/{productId}", timeoutMs);
    }

    /** Exactly one GET reached the price path and no other request reached the server: no retry was made. */
    private static void assertSingleRequest() {
        wm.verify(1, getRequestedFor(urlEqualTo(PRICE_PATH)));
        assertThat(wm.getAllServeEvents()).hasSize(1);
    }
}
