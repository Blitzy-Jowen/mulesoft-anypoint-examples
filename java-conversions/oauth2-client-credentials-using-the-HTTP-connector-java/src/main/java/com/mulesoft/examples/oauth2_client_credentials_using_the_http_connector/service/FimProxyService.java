/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

import com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.client.FimProxyClient;

/**
 * The {@code /FIM/*} flow {@code http-client-credentialsFlow} of
 * {@code oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml},
 * lines 39-47.
 *
 * <ul>
 *   <li>Line 40, {@code http:listener path="/FIM/*"}: bound by {@code controller/FimProxyController}, which
 *       passes the request method, the raw request URI, its Content-Type and its body here.</li>
 *   <li>Line 41, {@code http:request} on {@code HTTP_Request_Configuration1}: the inbound
 *       {@code http.request.uri} (path and query string) is the outbound path and the inbound
 *       {@code http.method} the outbound method, both sent through {@link FimProxyClient} unchanged. The
 *       requester's default {@code sendBodyMode} AUTO sends no body for GET, HEAD and OPTIONS.</li>
 *   <li>Line 43, {@code http:uri-param client_id=${client.id}}: fills every {@code {client_id}} placeholder of
 *       the outbound path with the per-user {@code client.id} key, percent-encoded exactly once as a URI-template
 *       variable (every character outside the RFC 3986 unreserved set {@code ALPHA DIGIT - . _ ~}, non-ASCII as
 *       UTF-8 octets), so the identifier stays one URI value; the rest of the raw URI is not re-encoded. While
 *       {@code client.id} is blank, a URI holding a placeholder fails with {@code client.id is not configured}
 *       before the FIM server is called, and a URI without one is forwarded unchanged. A uri-param is a
 *       path-template value, so it is never added as a query parameter.</li>
 *   <li>No exception strategy: every failure, including an upstream status of 400 or above, propagates to the
 *       generic 500 handler.</li>
 * </ul>
 *
 * <p>The flow has no logger, so this class logs nothing.
 */
@Service
public class FimProxyService {

    /** The uri-param placeholder the original {@code http:request-builder} resolves (line 43). */
    private static final String CLIENT_ID_TOKEN = "{client_id}";

    /** Methods for which the Mule requester's {@code sendBodyMode} AUTO sends no body. */
    private static final Set<String> BODYLESS_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final FimProxyClient fimProxyClient;

    private final String clientId;

    /**
     * Creates the service.
     *
     * @param fimProxyClient the outbound FIM adapter
     * @param clientId       the {@code client.id} key; {@code null} is treated as blank, and a blank value is
     *                       accepted here and refused only by a {@link #forward} that needs it
     */
    public FimProxyService(FimProxyClient fimProxyClient, @Value("${client.id:}") String clientId) {
        this.fimProxyClient = fimProxyClient;
        this.clientId = clientId == null ? "" : clientId;
    }

    /**
     * Forwards one {@code /FIM/*} request to the FIM server.
     *
     * @param method      the inbound HTTP method, forwarded as given
     * @param rawUri      the inbound path and query string, undecoded; only the literal {@code {client_id}}
     *                    placeholders are replaced, each by the {@code client.id} value percent-encoded once,
     *                    and every other byte is forwarded unchanged
     * @param contentType the inbound Content-Type, or {@code null} when the request carried none
     * @param body        the inbound body, or {@code null} when the request carried none
     * @return 200 with the upstream body (empty when the upstream sent none) and only the upstream Content-Type
     * @throws IllegalStateException with the message {@code client.id is not configured} when {@code rawUri}
     *         holds a {@code {client_id}} placeholder and {@code client.id} is blank; the FIM server is not called
     * @throws org.springframework.web.client.RestClientResponseException when the upstream answers 400 or above
     * @throws org.springframework.web.client.ResourceAccessException on an I/O failure or timeout
     */
    public ResponseEntity<byte[]> forward(String method, String rawUri, MediaType contentType, byte[] body) {
        String uri = rawUri;
        if (rawUri.contains(CLIENT_ID_TOKEN)) {
            if (clientId.isBlank()) {
                throw new IllegalStateException("client.id is not configured");
            }
            // Encoded exactly once, as a URI-template variable is, so a configured identifier holding '/', '?',
            // '#', '&', '%' or a space stays one URI value instead of adding delimiters or invalidating the URI.
            uri = rawUri.replace(CLIENT_ID_TOKEN, UriUtils.encode(clientId, StandardCharsets.UTF_8));
        }
        boolean bodyless = BODYLESS_METHODS.contains(method.toUpperCase(Locale.ROOT));
        ResponseEntity<byte[]> upstream = fimProxyClient.send(method, uri,
                bodyless ? null : contentType,
                bodyless ? null : body);
        return toResponse(upstream);
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
        String upstreamContentType = upstream.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        if (upstreamContentType != null && !upstreamContentType.isBlank()) {
            response.header(HttpHeaders.CONTENT_TYPE, upstreamContentType);
        }
        byte[] upstreamBody = upstream.getBody();
        return response.body(upstreamBody == null ? new byte[0] : upstreamBody);
    }
}
