/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.controller;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.service.FimProxyService;

/**
 * HTTP trigger of flow {@code http-client-credentialsFlow}
 * (oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml:39-47).
 *
 * <ul>
 *   <li>Line 40, {@code http:listener path="/FIM/*"} on {@code HTTP_Listener_Configuration}, {@code 0.0.0.0:8081}
 *       (line 9): the listener declares no {@code allowedMethods}, so every method is accepted, and no mapping
 *       here declares {@code produces}, {@code consumes}, parameters or headers.</li>
 *   <li>Line 41, {@code http:request} with path {@code http.request.uri} and method {@code http.method}: the
 *       request URI with its query string, raw as sent on the request line (no percent-decoding, normalization
 *       or re-encoding), and the request method are passed to {@link FimProxyService} unchanged, together with
 *       the request's Content-Type and body. The Undertow settings of {@code application.yml} accept literal
 *       {@code {client_id}} braces in the request target and leave its percent-encoding intact, so
 *       {@code getRequestURI()} and {@code getQueryString()} carry the bytes Mule's {@code http.request.uri}
 *       carried.</li>
 *   <li>Line 43, {@code http:uri-param client_id=${client.id}}: applied by {@link FimProxyService}, which also
 *       owns the body rules and the upstream call. This class holds no transformation or routing logic and
 *       returns the service's response as it is.</li>
 * </ul>
 * The flow declares no exception strategy, so every failure reaches the generic 500
 * {@code { "message": "<exception message>" }} of {@code GlobalExceptionHandler}.
 *
 * <p>Tier 2A verification points, where the Spring MVC request handling may differ from the Mule listener:
 * <ol>
 *   <li>{@code /FIM/**} also matches the bare {@code /FIM} and {@code /FIM/}; how Mule's {@code /FIM/*} answered
 *       a bare {@code /FIM} is unverified.</li>
 *   <li>Form-encoded bodies of every method other than a query-less POST (next point) reach this class and are
 *       forwarded byte for byte, because {@code application.yml} disables Boot's {@code FormContentFilter},
 *       which would otherwise parse PUT, PATCH and DELETE form bodies before dispatch and leave them empty
 *       here.</li>
 *   <li>For a form-encoded POST without a query string, Spring's {@code ServletServerHttpRequest} rebuilds the
 *       body from the request parameters, re-encoded and in parameter-map order, so the forwarded form body may
 *       not be byte-identical to the inbound bytes.</li>
 *   <li>TRACE is forwarded like every other method, because {@code application.yml} sets
 *       {@code spring.mvc.dispatch-trace-request}; whether the Mule listener ran the flow for TRACE is
 *       unverified.</li>
 *   <li>A CORS preflight OPTIONS request (one carrying {@code Origin} and {@code Access-Control-Request-Method})
 *       is rejected by Spring's CORS handling with 403 {@code Invalid CORS request}, as no CORS configuration
 *       exists, and is not forwarded.</li>
 *   <li>A Content-Type that cannot be parsed ends in the 500 of {@code GlobalExceptionHandler} before the FIM
 *       server is called.</li>
 *   <li>An empty query ({@code /FIM/x?}) is forwarded without its {@code ?}, because the servlet API reports an
 *       empty query string as absent.</li>
 * </ol>
 */
@RestController
public class FimProxyController {

    private final FimProxyService fimProxyService;

    public FimProxyController(FimProxyService fimProxyService) {
        this.fimProxyService = fimProxyService;
    }

    /**
     * Forwards a {@code /FIM/**} request of any method but OPTIONS through {@link FimProxyService}.
     *
     * @param request the inbound request, whose method, raw URI and Content-Type are forwarded
     * @param body    the inbound body, or {@code null} when the request carried none
     * @return the service's response, unchanged
     */
    @RequestMapping("/FIM/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request,
                                        @RequestBody(required = false) byte[] body) {
        String query = request.getQueryString();
        String rawUri = query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
        String contentTypeHeader = request.getContentType();
        MediaType contentType = StringUtils.hasLength(contentTypeHeader)
                ? MediaType.parseMediaType(contentTypeHeader) : null;
        return fimProxyService.forward(request.getMethod(), rawUri, contentType, body);
    }

    /**
     * Forwards {@code OPTIONS /FIM/**} like every other method. A mapping without methods deliberately does not
     * match OPTIONS, which Spring MVC would otherwise answer itself with an {@code Allow} header; the Mule
     * listener ran the flow for OPTIONS too.
     *
     * @param request the inbound request
     * @param body    the inbound body, or {@code null} when the request carried none
     * @return the service's response, unchanged
     */
    @RequestMapping(path = "/FIM/**", method = RequestMethod.OPTIONS)
    public ResponseEntity<byte[]> proxyOptions(HttpServletRequest request,
                                               @RequestBody(required = false) byte[] body) {
        return proxy(request, body);
    }
}
