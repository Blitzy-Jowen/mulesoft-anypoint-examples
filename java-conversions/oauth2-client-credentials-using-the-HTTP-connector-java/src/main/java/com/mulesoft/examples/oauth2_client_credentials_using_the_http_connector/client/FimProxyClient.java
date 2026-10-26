/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Outbound adapter of the {@code /FIM/*} flow {@code http-client-credentialsFlow} in
 * {@code oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml}.
 *
 * <ul>
 *   <li>Line 29, {@code HTTP_Request_Configuration1}: host {@code tfim01.demos.ibm.com}, port 443, HTTPS and no
 *       TLS context, so the JVM's default trust applies. The base URL is the {@code fim.base-url} key.</li>
 *   <li>Line 41, {@code http:request}: the inbound {@code http.request.uri} (path and query string) is the
 *       outbound path, and the inbound {@code http.method} is the outbound method; both are forwarded
 *       verbatim.</li>
 *   <li>Line 43, {@code uri-param client_id=${client.id}}: not handled here. {@code service/FimProxyService}
 *       substitutes the {@code {client_id}} placeholder before calling {@link #send}, so this class receives
 *       the final URI.</li>
 * </ul>
 *
 * <p>Connect and response timeouts are 10 s, the Mule requester's default response timeout; the response
 * timeout bounds the whole response, headers and body, and a timed-out request is never retried. A response
 * outside the requester's default success range 0..399 fails with Mule 3.8's response-validator message
 * {@code Response code <code> mapped as failure.}, carried by a {@link RestClientResponseException}.
 */
@Component
public class FimProxyClient {

    /** First status code outside the Mule 3.8 requester's default success status range {@code 0..399}. */
    private static final int FIRST_FAILURE_STATUS = 400;

    /** The Mule HTTP requester's default response timeout. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final String baseUrl;

    /** Connect timeout, and the bound on each whole response from sending the request to its last body byte. */
    private final Duration timeout;

    private final RestClient restClient;

    /**
     * Creates the client the application uses, with the 10 s Mule default timeouts.
     *
     * @param baseUrl scheme, host and port of the FIM server, without a trailing slash
     */
    @Autowired
    public FimProxyClient(@Value("${fim.base-url:https://tfim01.demos.ibm.com:443}") String baseUrl) {
        this(baseUrl, DEFAULT_TIMEOUT);
    }

    /**
     * Creates a client for {@code baseUrl} with the given connect and response timeout. Building the JDK client
     * opens no connection, so construction never contacts a host.
     *
     * @param baseUrl scheme, host and port of the FIM server, without a trailing slash; the raw URI passed to
     *                {@link #send} always begins with {@code /FIM/} and is appended unchanged
     * @param timeout connect timeout, and the time within which each request's whole response, headers and
     *                body, must arrive
     */
    public FimProxyClient(String baseUrl, Duration timeout) {
        this.baseUrl = baseUrl;
        this.timeout = timeout;
        // HTTP/1.1 as Mule's requester speaks it: the JDK default (HTTP/2) would add Upgrade: h2c and
        // HTTP2-Settings headers on http URIs. Redirects are followed, as http:request does by default.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /**
     * Forwards one request to the FIM server.
     *
     * @param method      the HTTP method token, sent as given
     * @param rawUri      path and query string, percent-encoding included, appended to the base URL verbatim
     * @param contentType the Content-Type to send, or {@code null} to send none
     * @param body        the request body, or {@code null} to send none
     * @return the upstream status, headers and body; the body is {@code null} when the upstream sent none
     * @throws RestClientResponseException when the upstream status is 400 or above, with the message
     *         {@code Response code <code> mapped as failure.} and the upstream status, headers and body
     * @throws org.springframework.web.client.ResourceAccessException on an I/O failure, including a connect
     *         timeout and a response, headers or body, not complete within the timeout (its cause is then a
     *         {@link HttpTimeoutException}); nothing is retried after one
     */
    public ResponseEntity<byte[]> send(String method, String rawUri, MediaType contentType, byte[] body) {
        // URI.create, not a URI template or builder: the caller's encoding must reach the wire byte for byte.
        RestClient.RequestBodySpec request = restClient.method(HttpMethod.valueOf(method))
                .uri(URI.create(baseUrl + rawUri));
        if (contentType != null) {
            request = request.contentType(contentType);
        }
        if (body != null) {
            // The bytes are written directly rather than through ByteArrayHttpMessageConverter, which would
            // add Content-Type: application/octet-stream when the caller supplied none. Mule's requester sends
            // no Content-Type for a message without a MIME type, so only a supplied Content-Type is sent.
            // The explicit length keeps the request non-chunked, as the converter's Content-Length did.
            StreamingHttpOutputMessage.Body requestBody = outputStream -> outputStream.write(body);
            request = request.contentLength(body.length).body(requestBody);
        }
        // Taken before the request is sent, so the headers and the body share the one response timeout.
        long deadline = System.nanoTime() + timeout.toNanos();
        // exchange bypasses RestClient's default status handlers and adds no converter-derived Accept header.
        return request.exchange((clientRequest, clientResponse) -> {
            byte[] bytes = readBody(clientResponse.getBody(), deadline, timeout);
            HttpHeaders headers = new HttpHeaders();
            headers.addAll(clientResponse.getHeaders());
            HttpStatusCode statusCode = clientResponse.getStatusCode();
            if (statusCode.value() >= FIRST_FAILURE_STATUS) {
                String statusText = clientResponse.getStatusText();
                throw new RestClientResponseException(
                        "Response code " + statusCode.value() + " mapped as failure.",
                        statusCode, statusText == null ? "" : statusText, headers, bytes, charsetOf(headers));
            }
            return ResponseEntity.status(statusCode).headers(headers).body(bytes.length == 0 ? null : bytes);
        });
    }

    /**
     * Reads the whole response body, failing once {@code deadline} (a {@link System#nanoTime()} value) passes.
     *
     * <p>The request factory's read timeout ends when the response headers arrive; the JDK client then streams
     * the body with no timeout of its own, so a server that sends headers and stalls its body would block this
     * read indefinitely. When the deadline passes first, the stream is closed from the timer thread, which
     * unblocks the read, and the read fails with an {@link HttpTimeoutException}.
     *
     * @throws HttpTimeoutException when the body is not complete by the deadline
     * @throws IOException          on any other I/O failure while reading
     */
    private static byte[] readBody(InputStream body, long deadline, Duration timeout) throws IOException {
        long remainingMillis = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, deadline - System.nanoTime()));
        CompletableFuture<Void> expiry = new CompletableFuture<Void>()
                .completeOnTimeout(null, remainingMillis, TimeUnit.MILLISECONDS);
        expiry.thenRun(() -> {
            try {
                body.close();
            } catch (IOException ignored) {
                // The stream is being abandoned; the reading thread reports the timeout.
            }
        });
        try {
            return body.readAllBytes();
        } catch (IOException ex) {
            if (expiry.isDone() && !expiry.isCompletedExceptionally()) {
                HttpTimeoutException timedOut = new HttpTimeoutException(
                        "Response not received within " + timeout.toMillis() + " ms");
                timedOut.initCause(ex);
                throw timedOut;
            }
            throw ex;
        } finally {
            expiry.cancel(false);
        }
    }

    /**
     * Returns the charset of the response Content-Type, or {@code null} when there is none or it cannot be
     * parsed, so a malformed upstream header never replaces the mapped-as-failure exception.
     */
    private static Charset charsetOf(HttpHeaders headers) {
        try {
            MediaType responseType = headers.getContentType();
            return responseType != null ? responseType.getCharset() : null;
        } catch (InvalidMediaTypeException ex) {
            return null;
        }
    }
}
