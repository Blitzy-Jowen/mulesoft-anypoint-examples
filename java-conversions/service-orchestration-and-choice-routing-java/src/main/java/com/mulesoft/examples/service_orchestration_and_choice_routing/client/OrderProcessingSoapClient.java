/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.client;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Outbound adapter of the SOAP order service on port 1080, replacing the two
 * {@code http:request method="POST" path="orders" port="1080"} elements of {@code mule-config.xml}, each followed by
 * an {@code object-to-string-transformer}, sent through {@code HTTP_Request_Configuration} ({@code mule-config.xml:4},
 * host {@code 0.0.0.0}) to this application's own {@code IProcessOrder} listener ({@code fulfillment.xml:29}, POST
 * only):
 * <ul>
 *   <li>{@link #postOrderRequest(String)}: the call of the Ajax {@code orderRequest} flow
 *       ({@code mule-config.xml:21}, {@code responseTimeout="10000000"}), between {@code XmlToSoapEnvelope.xsl} and
 *       {@code SoapEnvelopeToBodyChild.xslt}; timeout key {@code orders.soap.request-timeout-ms}.</li>
 *   <li>{@link #proxy(String)}: the call of the Ajax {@code orderProxy} flow ({@code mule-config.xml:40}), which sets
 *       no {@code responseTimeout} and so uses Mule's default of 10000 ms; timeout key
 *       {@code orders.soap.proxy-timeout-ms}.</li>
 * </ul>
 *
 * <p>Mule's {@code responseTimeout} bounds the whole exchange, so each call uses its value as both the connect and the
 * read timeout. The envelope is sent as its UTF-8 bytes, unparsed and untrimmed, with {@code Content-Type:
 * text/xml; charset=UTF-8}, the media type the SOAP 1.1 listener reads, and without a {@code SOAPAction} header: the
 * original requester sent none and the endpoint dispatches on the payload root.
 *
 * <p>Exactly one request is sent per call. The request body is a byte array of known length, so
 * {@code SimpleClientHttpRequestFactory} streams it in fixed-length mode, in which {@code HttpURLConnection} never
 * re-sends a POST after a failed exchange. The response status line is read before the exchange returns, so a
 * timeout while waiting for it fails the call as a connection failure does.
 *
 * <p>The XSLTs, the summary JSON and the Ajax reply belong to the service that calls this adapter; it neither logs,
 * catches nor retries, as the original requests had no logger and no retry.
 */
@Component
public class OrderProcessingSoapClient {

    private static final MediaType TEXT_XML_UTF8 = MediaType.parseMediaType("text/xml; charset=UTF-8");

    private final String url;

    private final RestClient requestClient;

    private final RestClient proxyClient;

    /**
     * @param url              the order service URL, from {@code orders.soap.url}; the default is the original's
     *                         {@code POST orders} on port 1080
     * @param requestTimeoutMs connect and read timeout in milliseconds of {@link #postOrderRequest(String)}, from
     *                         {@code orders.soap.request-timeout-ms}; the default 10000000 is the original's
     *                         {@code responseTimeout} ({@code mule-config.xml:21})
     * @param proxyTimeoutMs   connect and read timeout in milliseconds of {@link #proxy(String)}, from
     *                         {@code orders.soap.proxy-timeout-ms}; the default 10000 is Mule's default response
     *                         timeout ({@code mule-config.xml:40} sets none)
     */
    public OrderProcessingSoapClient(
            @Value("${orders.soap.url:http://localhost:1080/orders}") String url,
            @Value("${orders.soap.request-timeout-ms:10000000}") int requestTimeoutMs,
            @Value("${orders.soap.proxy-timeout-ms:10000}") int proxyTimeoutMs) {
        this.url = url;
        this.requestClient = restClient(requestTimeoutMs);
        this.proxyClient = restClient(proxyTimeoutMs);
    }

    /**
     * Posts the SOAP envelope built from an Ajax order request and returns the service's reply as text, as the
     * {@code orderRequest} flow did ({@code mule-config.xml:21-22}).
     *
     * @param envelope the SOAP 1.1 envelope to send unchanged
     * @return the response body decoded with the charset of its {@code Content-Type}, UTF-8 when it names none;
     *         {@code ""} when the response has no body
     * @throws NullPointerException when {@code envelope} is {@code null}; nothing is sent
     * @throws org.springframework.web.client.RestClientResponseException when the service answers with a status of
     *         400 or above, such as a SOAP fault over 500 or the listener's 405
     * @throws org.springframework.web.client.ResourceAccessException on a connection failure, or when no response
     *         status arrives within the timeout; nothing is re-sent
     */
    public String postOrderRequest(String envelope) {
        return post(requestClient, envelope);
    }

    /**
     * Forwards a SOAP envelope published on the Ajax {@code /orders/soap} channel and returns the service's reply as
     * text, as the {@code orderProxy} flow did ({@code mule-config.xml:40-41}).
     *
     * @param envelope the SOAP 1.1 envelope to send unchanged
     * @return the response body decoded with the charset of its {@code Content-Type}, UTF-8 when it names none;
     *         {@code ""} when the response has no body
     * @throws NullPointerException when {@code envelope} is {@code null}; nothing is sent
     * @throws org.springframework.web.client.RestClientResponseException when the service answers with a status of
     *         400 or above, such as a SOAP fault over 500 or the listener's 405
     * @throws org.springframework.web.client.ResourceAccessException on a connection failure, or when no response
     *         status arrives within the timeout; nothing is re-sent
     */
    public String proxy(String envelope) {
        return post(proxyClient, envelope);
    }

    /**
     * Sends one POST and decodes the reply. A status below 400 is a success, as in the Mule HTTP requester's default
     * success range 0-399; the default status handler of {@link RestClient} throws for every other status.
     */
    private String post(RestClient client, String envelope) {
        Objects.requireNonNull(envelope, "envelope");
        ResponseEntity<byte[]> response = client.post()
                .uri(url)
                .contentType(TEXT_XML_UTF8)
                .body(envelope.getBytes(StandardCharsets.UTF_8))
                .retrieve()
                .toEntity(byte[].class);
        byte[] body = response.getBody();
        if (body == null || body.length == 0) {
            return "";
        }
        return new String(body, charsetOf(response.getHeaders().getContentType()));
    }

    private static RestClient restClient(int timeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);
        return RestClient.builder()
                .requestFactory(requestFactory)
                .requestInterceptor(OrderProcessingSoapClient::readStatusLine)
                .build();
    }

    /**
     * Reads the response status line while the request is still executing. {@code HttpURLConnection} reads the
     * response lazily, and an {@link IOException} first met while {@link RestClient} extracts the response, such as
     * the read timeout of a slow order service, is reported as a generic
     * {@link org.springframework.web.client.RestClientException}. Read here, the same failure ends the exchange as
     * the {@link org.springframework.web.client.ResourceAccessException} carrying the {@code SocketTimeoutException}
     * that a connection failure also gives, so callers see one exception type for both. Nothing is re-sent.
     */
    private static ClientHttpResponse readStatusLine(HttpRequest request, byte[] body,
            ClientHttpRequestExecution execution) throws IOException {
        ClientHttpResponse response = execution.execute(request, body);
        response.getStatusCode();
        return response;
    }

    private static Charset charsetOf(MediaType contentType) {
        if (contentType == null || contentType.getCharset() == null) {
            return StandardCharsets.UTF_8;
        }
        return contentType.getCharset();
    }
}
