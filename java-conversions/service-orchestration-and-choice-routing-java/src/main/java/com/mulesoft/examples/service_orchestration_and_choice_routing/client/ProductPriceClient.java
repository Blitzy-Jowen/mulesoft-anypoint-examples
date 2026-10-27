/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.client;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Outbound adapter of the price service, replacing the in-house order flow's
 * {@code http:request method="GET" path="api/prices/#[payload.productId]" port="9999"} and the
 * {@code object-to-string-transformer} after it ({@code fulfillment.xml:105-106}), sent through
 * {@code HTTP_Request_Configuration} ({@code mule-config.xml:4}), which sets no {@code responseTimeout} and so uses
 * Mule's default of 10000 ms for both connecting and reading.
 *
 * <p>The request goes to this application's own price listener on port 9999, which accepts POST only
 * ({@code fulfillment.xml:145}). The GET is kept as the original sends it, so the listener answers 405 and
 * {@link #getPrice(String)} throws, which sends the in-house order to its rollback branch exactly as the original
 * did. This is a Tier 2A verification point recorded in the project README; switching to POST would change the
 * behaviour being reproduced.
 *
 * <p>Caching by product ID and the price arithmetic belong to the services that call this adapter; it neither logs
 * nor retries, as the original request had no logger and no retry.
 */
@Component
public class ProductPriceClient {

    private final String urlTemplate;

    private final RestClient restClient;

    /**
     * Creates the adapter.
     *
     * @param urlTemplate price URL with the {@code {productId}} URI-template variable, from {@code orders.price.url};
     *                    the default is the value {@code application.yml} resolves with {@code ports.prices} 9999
     * @param timeoutMs connect and read timeout in milliseconds, from {@code orders.price.timeout-ms}
     */
    public ProductPriceClient(
            @Value("${orders.price.url:http://localhost:9999/api/prices/{productId}}") String urlTemplate,
            @Value("${orders.price.timeout-ms:10000}") int timeoutMs) {
        this.urlTemplate = urlTemplate;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /**
     * Requests the price of a product and returns the response body as text, as {@code object-to-string} did.
     *
     * <p>The product ID is expanded into the path with URI-variable encoding. A status below 400 is a success, as in
     * the Mule HTTP requester's default success range 0-399, and the body is decoded with the charset of the
     * response {@code Content-Type}, UTF-8 when it names none. An absent or empty body yields {@code ""}. The text is
     * returned untrimmed and unparsed.
     *
     * @param productId the product ID placed into the price URL
     * @return the response body as text
     * @throws org.springframework.web.client.RestClientResponseException when the price service answers with a status
     *         of 400 or above
     * @throws org.springframework.web.client.ResourceAccessException on a connection failure or timeout
     */
    public String getPrice(String productId) {
        ResponseEntity<byte[]> response = restClient.get()
                .uri(urlTemplate, productId)
                .retrieve()
                .toEntity(byte[].class);
        byte[] body = response.getBody();
        if (body == null || body.length == 0) {
            return "";
        }
        return new String(body, charsetOf(response.getHeaders().getContentType()));
    }

    private static Charset charsetOf(MediaType contentType) {
        if (contentType == null || contentType.getCharset() == null) {
            return StandardCharsets.UTF_8;
        }
        return contentType.getCharset();
    }
}
