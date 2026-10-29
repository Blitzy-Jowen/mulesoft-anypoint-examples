/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.service;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.client.BoxOAuthClient;

/**
 * The {@code /oauth} flow {@code httpclientcredentialsFlow} of
 * {@code oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml},
 * lines 31-38.
 *
 * <ul>
 *   <li>Line 32, {@code http:listener path="oauth"}: bound by {@code controller/OAuthController}, which passes
 *       the request body here and returns this class's result.</li>
 *   <li>Line 33, {@code logger message="#[payload]" level="INFO"}: the request body logged at INFO as text,
 *       {@code {NullPayload}} when the request carried none, as Mule 3 renders an empty payload.</li>
 *   <li>Line 34, {@code http:request} GET {@code path="/"} on {@code HTTP_Request_Configuration}: sent by
 *       {@link BoxOAuthClient#authorize()}, which owns the client-credentials token, its one refresh-and-retry and
 *       the mapping of a status of 400 or above to a failure.</li>
 *   <li>Lines 35-37, {@code rollback-exception-strategy} with {@code logger level="INFO" message="#[exception]"}:
 *       the failure is logged at INFO and then propagates, because a strategy that only logs leaves the response
 *       to the platform default, the generic 500 {@code { "message": "<exception message>" }} answer. The flow
 *       declares no transaction, so there is nothing to roll back.</li>
 * </ul>
 */
@Service
public class BoxClientCredentialsService {

    private static final Logger log = LoggerFactory.getLogger(BoxClientCredentialsService.class);

    /** How Mule 3 renders {@code #[payload]} when the request carried no body. */
    private static final String NULL_PAYLOAD = "{NullPayload}";

    private final BoxOAuthClient boxOAuthClient;

    /**
     * Creates the service.
     *
     * @param boxOAuthClient the outbound Box adapter of line 34
     */
    public BoxClientCredentialsService(BoxOAuthClient boxOAuthClient) {
        this.boxOAuthClient = boxOAuthClient;
    }

    /**
     * Runs one {@code /oauth} request through the flow.
     *
     * @param body the inbound request body, or {@code null} when the request carried none
     * @return 200 with the Box response body (empty when Box sent none) and only the Box Content-Type
     * @throws RuntimeException the client's failure, the same instance, after it is logged at INFO: a
     *         {@link org.springframework.web.client.RestClientResponseException}
     *         {@code Response code <code> mapped as failure.} for a final status of 400 or above, an
     *         {@link IllegalStateException} for a missing client credential or a failed token exchange, or a
     *         {@link org.springframework.web.client.ResourceAccessException} on an I/O failure or timeout
     * @throws IllegalStateException wrapping a checked exception, with its message and the exception as cause
     */
    public ResponseEntity<byte[]> authorize(byte[] body) {
        // Line 33.
        String text = (body == null || body.length == 0) ? NULL_PAYLOAD : new String(body, StandardCharsets.UTF_8);
        log.info("{}", text);
        try {
            // Line 34.
            ResponseEntity<byte[]> upstream = boxOAuthClient.authorize();
            return toResponse(upstream);
        } catch (Exception e) {
            // Lines 35-37: #[exception] at INFO, then the exception escapes to the platform-default 500. The
            // strategy must not end the flow with a response of its own.
            log.info("{}", e.toString());
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            // authorize() declares no checked exception, so this line is reached only when a mock throws one,
            // e.g. Mockito doAnswer(inv -> { throw new java.io.IOException("x"); }).
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * Builds the listener's response from the requester's result. The Mule listener answers 200 unless the
     * flow sets {@code http.status}, which this flow does not, and the requester's inbound headers do not reach
     * the listener's response; only the payload and its MIME type do.
     */
    private static ResponseEntity<byte[]> toResponse(ResponseEntity<byte[]> upstream) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.OK);
        // The raw header string, not getContentType(): it keeps the upstream bytes and cannot fail on a
        // malformed media type.
        String contentType = upstream.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        if (contentType != null && !contentType.isBlank()) {
            response.header(HttpHeaders.CONTENT_TYPE, contentType);
        }
        byte[] upstreamBody = upstream.getBody();
        return response.body(upstreamBody == null ? new byte[0] : upstreamBody);
    }
}
