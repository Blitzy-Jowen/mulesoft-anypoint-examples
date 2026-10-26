/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_rest_api.service;

import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.proxying_a_rest_api.client.BoxProxyClient;
import com.mulesoft.examples.proxying_a_rest_api.mapper.ProxyHeaderMapper;

/**
 * Orchestration of the original flow {@code rest-api-proxy} ({@code proxying-a-rest-api.xml:11-20}), in its
 * processor order:
 * <ol>
 *   <li>{@code flow-ref copy-headers} ({@code :13}): {@link ProxyHeaderMapper#toUpstream} on the caller's headers;</li>
 *   <li>{@code http:request} ({@code :14-18}): one {@link BoxProxyClient#exchange} with the caller's method, raw
 *       path and raw query, response unparsed ({@code parseResponse="false"});</li>
 *   <li>the requester's default success-status validator;</li>
 *   <li>{@code flow-ref copy-headers} ({@code :19}): {@link ProxyHeaderMapper#toCaller} on the upstream
 *       response headers.</li>
 * </ol>
 * The listener ({@code :12}) is bound by the controller. The flow declares no logger and no exception strategy, so
 * nothing here logs, catches, retries or falls back: a client failure such as
 * {@link org.springframework.web.client.ResourceAccessException} propagates unchanged to the platform-default 500.
 *
 * <p>Mule 3.8 platform semantics the flow relied on, each a Tier 2A verification point that a captured fixture
 * overrides:
 * <ul>
 *   <li>{@code sendBodyMode} AUTO: GET, HEAD and OPTIONS are sent without an entity; every other method sends the
 *       request bytes, an empty body included.</li>
 *   <li>The second {@code copy-headers} removes {@code http.status} with the other {@code http.*} properties, so the
 *       listener answers 200 for every upstream status below 400: 201, a 3xx the requester did not follow and 304
 *       alike.</li>
 *   <li>The default success-status validator fails every upstream status of 400 or above with
 *       {@link #VALIDATOR_FAILURE_MESSAGE}, before the second {@code copy-headers} runs, so no response header is
 *       mapped on that path. The original README's claim that API errors pass through is contradicted by the flow,
 *       and the flow's runtime behaviour governs.</li>
 * </ul>
 *
 * <p>The service is stateless and thread-safe; it never modifies the headers or body it is given.
 */
@Service
public class RestProxyService {

    /**
     * Message of the Mule 3.8 requester's default success-status validator for a failed upstream status; the
     * {@code %d} is the upstream status code. The payload type is the one Mule named for an unparsed response.
     */
    public static final String VALIDATOR_FAILURE_MESSAGE =
            "Response code %d mapped as failure. Message payload is of type: BufferInputStream";

    /** Methods Mule's {@code sendBodyMode} AUTO sends without an entity. */
    private static final Set<HttpMethod> BODILESS_METHODS =
            Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);

    private final ProxyHeaderMapper proxyHeaderMapper;

    private final BoxProxyClient boxProxyClient;

    /**
     * @param proxyHeaderMapper the SC-13 header copying used on both legs
     * @param boxProxyClient    the outbound adapter to the proxied API
     */
    public RestProxyService(ProxyHeaderMapper proxyHeaderMapper, BoxProxyClient boxProxyClient) {
        this.proxyHeaderMapper = proxyHeaderMapper;
        this.boxProxyClient = boxProxyClient;
    }

    /**
     * Forwards one caller request to the upstream API and returns what the original listener answered.
     *
     * @param method   the caller's request method, any token
     * @param rawPath  the caller's request path, still percent-encoded; passed through untouched
     * @param rawQuery the caller's query string without {@code ?}, still percent-encoded, or {@code null};
     *                 passed through untouched
     * @param inbound  the caller's request headers, filtered by {@link ProxyHeaderMapper#toUpstream}
     * @param body     the caller's request bytes, or {@code null}; dropped for GET, HEAD and OPTIONS, otherwise
     *                 forwarded as given, {@code null} as an empty body
     * @return 200 with the upstream response headers filtered by {@link ProxyHeaderMapper#toCaller} and the upstream
     *         body bytes unparsed, an empty array when the upstream sent none
     * @throws IllegalStateException when the upstream status is 400 or above, with {@link #VALIDATOR_FAILURE_MESSAGE}
     *                               formatted with that status
     */
    public ResponseEntity<byte[]> forward(HttpMethod method, String rawPath, String rawQuery,
                                          HttpHeaders inbound, byte[] body) {
        HttpHeaders outbound = proxyHeaderMapper.toUpstream(inbound);
        byte[] bodyOrNull = BODILESS_METHODS.contains(method) ? null : (body != null ? body : new byte[0]);

        ResponseEntity<byte[]> upstream = boxProxyClient.exchange(method, rawPath, rawQuery, outbound, bodyOrNull);

        int status = upstream.getStatusCode().value();
        if (status >= 400) {
            throw new IllegalStateException(String.format(VALIDATOR_FAILURE_MESSAGE, status));
        }

        byte[] responseBody = upstream.getBody();
        return ResponseEntity.status(HttpStatus.OK)
                .headers(proxyHeaderMapper.toCaller(upstream.getHeaders()))
                .body(responseBody != null ? responseBody : new byte[0]);
    }
}
