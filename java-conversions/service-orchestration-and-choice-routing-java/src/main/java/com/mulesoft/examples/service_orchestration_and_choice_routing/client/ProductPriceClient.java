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

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
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
 * <p>Exactly one request is sent per call. The transport is Apache httpclient5 with automatic retries disabled, so a
 * timeout or a connection failure ends the call and the GET is never re-sent; connecting and reading are each
 * limited to 10 s by default. {@code SimpleClientHttpRequestFactory} is not used because its
 * {@code HttpURLConnection} silently re-sends a GET once after a connection reset or a premature EOF before the
 * response head, and the JDK's {@code java.net.http} client likewise re-sends idempotent requests.
 *
 * <p>Caching by product ID and the price arithmetic belong to the services that call this adapter; it neither logs
 * nor retries, and its transport re-sends nothing, as the original request had no logger and no retry.
 */
@Component
public class ProductPriceClient implements AutoCloseable {

    private final String urlTemplate;

    private final CloseableHttpClient httpClient;

    private final RestClient restClient;

    /**
     * Creates the adapter over a single-attempt transport: automatic retries are disabled, so each
     * {@link #getPrice(String)} call sends exactly one request, and a connection reset, a premature EOF, a refused
     * connection or a timeout fails it without a second attempt. {@code SimpleClientHttpRequestFactory} is not used
     * because its {@code HttpURLConnection} re-sends a GET after a reset or a premature EOF.
     *
     * @param urlTemplate price URL with the {@code {productId}} URI-template variable, from {@code orders.price.url};
     *                    the default is the value {@code application.yml} resolves with {@code ports.prices} 9999
     * @param timeoutMs connect and read timeout in milliseconds, from {@code orders.price.timeout-ms}; the default
     *                  10000 is Mule's default response timeout
     */
    public ProductPriceClient(
            @Value("${orders.price.url:http://localhost:9999/api/prices/{productId}}") String urlTemplate,
            @Value("${orders.price.timeout-ms:10000}") int timeoutMs) {
        this.urlTemplate = urlTemplate;
        Timeout limit = Timeout.ofMilliseconds(timeoutMs);
        // HttpURLConnection imposed no concurrency cap, so concurrent in-house orders must not queue behind
        // httpclient5's default of five connections per route.
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(limit)
                        .setSocketTimeout(limit)
                        // No retry, so stale-check every lease: a connection the server closed is never leased.
                        .setValidateAfterInactivity(TimeValue.ZERO_MILLISECONDS)
                        .build())
                .setMaxConnTotal(Integer.MAX_VALUE)
                .setMaxConnPerRoute(Integer.MAX_VALUE)
                .build();
        // No request is ever re-sent: automatic retries are off. The response timeout bounds the wait for the response
        // head and every wait for body bytes; on expiry the read fails with SocketTimeoutException and RestClient
        // throws ResourceAccessException. Content compression would add Accept-Encoding and decompress the body, and a
        // cookie store would replay one order's cookies to the next; HttpURLConnection did neither, so both are off.
        this.httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(limit)
                        .setConnectionRequestTimeout(limit)
                        .build())
                .disableAutomaticRetries()
                .disableContentCompression()
                .disableCookieManagement()
                .build();
        // No factory timeout is set, so the client's default request configuration above applies to every GET.
        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);
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

    /**
     * Closes every pooled connection, idle or in use, without a reset; a price request still in progress fails with
     * {@link org.springframework.web.client.ResourceAccessException}. Spring calls it when the application context
     * shuts down, as for every {@link AutoCloseable} bean; no request may start afterwards.
     */
    @Override
    public void close() {
        httpClient.close(CloseMode.GRACEFUL);
    }

    private static Charset charsetOf(MediaType contentType) {
        if (contentType == null || contentType.getCharset() == null) {
            return StandardCharsets.UTF_8;
        }
        return contentType.getCharset();
    }
}
