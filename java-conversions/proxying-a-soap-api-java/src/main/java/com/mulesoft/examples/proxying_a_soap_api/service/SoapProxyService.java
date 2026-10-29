/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_soap_api.service;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.proxying_a_soap_api.client.ShopServiceClient;
import com.mulesoft.examples.proxying_a_soap_api.mapper.ProxyHeaderMapper;

/**
 * Orchestration of the original flow {@code main} ({@code soap-api-proxy.xml:5-12}), in its processor order:
 * <ol>
 *   <li>{@code cxf:proxy-service payload="envelope"} ({@code :7}): the caller's envelope is taken as it arrived. The
 *       proxy service does not validate it against the WSDL; its {@code wsdlLocation} only feeds {@code ?wsdl}, which
 *       {@code WsdlQueryPublisher} serves outside this class;</li>
 *   <li>{@code flow-ref copy-headers} ({@code :8}, sub-flow {@code :13-21}): {@link ProxyHeaderMapper#toUpstream} on
 *       the caller's headers;</li>
 *   <li>{@code cxf:proxy-client payload="envelope"} ({@code :9}): the CXF SOAP 1.1 outbound defaults, a SOAP
 *       Content-Type and a {@code SOAPAction} header, applied to the mapped headers; the envelope bytes pass
 *       unchanged;</li>
 *   <li>{@code http:request method="POST" path="shop/ShopService"} ({@code :10}): one {@link ShopServiceClient#post},
 *       followed by the requester's default success-status validator (0..399);</li>
 *   <li>{@code flow-ref copy-headers} ({@code :11}): {@link ProxyHeaderMapper#toCaller} on the upstream response
 *       headers.</li>
 * </ol>
 * The listener ({@code :6}, every method) is bound by the controller. The flow declares no logger and no exception
 * strategy, so nothing here logs, catches, retries or falls back: a client failure such as
 * {@link org.springframework.web.client.ResourceAccessException} propagates unchanged to the platform-default 500.
 * The proxy adds no authentication: the caller's {@code Authorization} header and any security header inside the
 * envelope reach the upstream as given.
 *
 * <p>Mule 3.8 platform semantics the flow relied on, each a Tier 2A verification point that a captured fixture
 * overrides:
 * <ul>
 *   <li>The proxy client's SOAP 1.1 defaults: a caller Content-Type other than {@code text/xml} or
 *       {@code application/soap+xml} is replaced by {@link #UPSTREAM_SOAP11_CONTENT_TYPE}, and a {@code text/xml}
 *       request without {@code SOAPAction} is given an empty one ({@code ""}).</li>
 *   <li>The second {@code copy-headers} removes {@code http.status} with the other {@code http.*} properties, so the
 *       listener answers 200 for every upstream status below 400.</li>
 *   <li>The default success-status validator fails every upstream status of 400 or above with
 *       {@link #VALIDATOR_FAILURE_MESSAGE}, before the second {@code copy-headers} runs, so no response header is
 *       mapped on that path. CXF may instead have answered such a status with a {@code soap:Server} fault.</li>
 * </ul>
 *
 * <p>The service is stateless and thread-safe; it never modifies the headers or body it is given.
 */
@Service
public class SoapProxyService {

    /**
     * Content-Type the CXF proxy client sends for a SOAP 1.1 envelope. Written as a string, because
     * {@link HttpHeaders#setContentType(MediaType)} would render it without the space after {@code ;}.
     */
    public static final String UPSTREAM_SOAP11_CONTENT_TYPE = "text/xml; charset=UTF-8";

    /**
     * Message of the Mule 3.8 requester's default success-status validator for a failed upstream status; the
     * {@code %d} is the upstream status code. The payload type is the one Mule named for an unparsed response.
     */
    public static final String VALIDATOR_FAILURE_MESSAGE =
            "Response code %d mapped as failure. Message payload is of type: BufferInputStream";

    private static final String SOAP_ACTION = "SOAPAction";

    /** The empty SOAP 1.1 action: the two double-quote characters. */
    private static final String EMPTY_SOAP_ACTION = "\"\"";

    private static final MediaType SOAP12_XML = new MediaType("application", "soap+xml");

    private final ProxyHeaderMapper proxyHeaderMapper;

    private final ShopServiceClient shopServiceClient;

    /**
     * @param proxyHeaderMapper the SC-13 header copying used on both legs
     * @param shopServiceClient the outbound adapter to the proxied {@code ShopService}
     */
    public SoapProxyService(ProxyHeaderMapper proxyHeaderMapper, ShopServiceClient shopServiceClient) {
        this.proxyHeaderMapper = proxyHeaderMapper;
        this.shopServiceClient = shopServiceClient;
    }

    /**
     * Forwards one caller envelope to the upstream {@code ShopService} and returns what the original listener
     * answered.
     *
     * @param inbound the caller's request headers, filtered by {@link ProxyHeaderMapper#toUpstream}; never modified
     * @param body    the caller's envelope bytes, forwarded unparsed; {@code null} is sent as an empty body
     * @return 200 with the upstream response headers filtered by {@link ProxyHeaderMapper#toCaller} and the upstream
     *         body bytes unparsed, an empty array when the upstream sent none
     * @throws IllegalStateException when the upstream status is 400 or above, with {@link #VALIDATOR_FAILURE_MESSAGE}
     *                               formatted with that status
     */
    public ResponseEntity<byte[]> forward(HttpHeaders inbound, byte[] body) {
        HttpHeaders headers = proxyHeaderMapper.toUpstream(inbound);
        applyProxyClientDefaults(headers);

        ResponseEntity<byte[]> upstream = shopServiceClient.post(headers, body != null ? body : new byte[0]);

        int status = upstream.getStatusCode().value();
        if (status >= 400) {
            throw new IllegalStateException(String.format(VALIDATOR_FAILURE_MESSAGE, status));
        }

        byte[] responseBody = upstream.getBody();
        return ResponseEntity.status(HttpStatus.OK)
                .headers(proxyHeaderMapper.toCaller(upstream.getHeaders()))
                .body(responseBody != null ? responseBody : new byte[0]);
    }

    /**
     * Applies the CXF proxy client's SOAP 1.1 outbound defaults to the mapped request headers. A caller
     * Content-Type whose type and subtype are exactly {@code text/xml} or {@code application/soap+xml} is kept as
     * written, parameters included; any other, absent or unparseable one is replaced by
     * {@link #UPSTREAM_SOAP11_CONTENT_TYPE}. A {@code text/xml} request without {@code SOAPAction} (matched ignoring
     * case, as {@link HttpHeaders} does) gets the empty action; SOAP 1.2 carries its action in the Content-Type, so
     * nothing is added for it.
     */
    private static void applyProxyClientDefaults(HttpHeaders headers) {
        MediaType callerType = parseContentType(headers.getFirst(HttpHeaders.CONTENT_TYPE));
        boolean soap11;
        if (callerType != null && MediaType.TEXT_XML.equalsTypeAndSubtype(callerType)) {
            soap11 = true;
        } else if (callerType != null && SOAP12_XML.equalsTypeAndSubtype(callerType)) {
            soap11 = false;
        } else {
            headers.set(HttpHeaders.CONTENT_TYPE, UPSTREAM_SOAP11_CONTENT_TYPE);
            soap11 = true;
        }
        if (soap11 && !headers.containsKey(SOAP_ACTION)) {
            headers.set(SOAP_ACTION, EMPTY_SOAP_ACTION);
        }
    }

    /**
     * Parses a raw Content-Type value. {@link HttpHeaders#getContentType()} is not used because it throws on a
     * malformed value, which here only means the caller's type is replaced.
     *
     * @return the parsed type, or {@code null} when the value is absent, blank or malformed
     */
    private static MediaType parseContentType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return MediaType.parseMediaType(value);
        } catch (InvalidMediaTypeException ex) {
            return null;
        }
    }
}
