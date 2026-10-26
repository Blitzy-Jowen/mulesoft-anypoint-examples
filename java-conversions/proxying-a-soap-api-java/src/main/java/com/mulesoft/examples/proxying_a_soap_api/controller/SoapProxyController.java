/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_soap_api.controller;

import java.io.IOException;
import java.util.Collections;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.undertow.servlet.handlers.ServletRequestContext;
import io.undertow.util.HttpString;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.mulesoft.examples.proxying_a_soap_api.service.SoapProxyService;

/**
 * The listener of the original flow {@code main}: {@code http:listener path="/"} without {@code allowedMethods}
 * ({@code soap-api-proxy.xml:6}) on {@code http:listener-config host="0.0.0.0" port="${http.port}"} ({@code :3}), so
 * every method is accepted at the root path, and only there: any other path, {@code /x/} included, is answered by the
 * generic 404 advice with {@code No listener for endpoint: <path>}.
 *
 * <p>The class only binds the caller's request and writes the answer back. The envelope handling of
 * {@code cxf:proxy-service} and {@code cxf:proxy-client}, the header copying, the upstream {@code POST} to
 * {@code shop/ShopService} and the treatment of the upstream status belong to {@link SoapProxyService}. The request is
 * read raw, never as parameters or parts, so the envelope bytes, the caller's {@code Authorization} header and any
 * WS-Security header inside the envelope reach the service exactly as sent. The query string is not read: the
 * upstream address is fixed by {@code proxy.upstream-url}, as the original {@code http:request} path was. The handlers
 * write the response themselves instead of returning a value, so Spring MVC adds no {@code Content-Type} from
 * {@code Accept} negotiation, answers no conditional request with 304 from the upstream {@code ETag} or
 * {@code Last-Modified}, and does not fail on a {@code Content-Type} or {@code Accept} it cannot parse. Any exception
 * from the service, such as the {@link IllegalStateException} for an upstream status of 400 or above or the
 * {@link org.springframework.web.client.ResourceAccessException} for a timeout or connect failure, leaves before
 * anything is written and is answered by the generic 500 advice.
 *
 * <p>{@code GET /?wsdl} and {@code GET /?xsd=<key>} never reach this class: the SC-11
 * {@link com.mulesoft.examples.proxying_a_soap_api.config.WsdlQueryPublisher} filter, which
 * {@code ProxyingASoapApiApplication} registers on {@code /*} at {@code Ordered.HIGHEST_PRECEDENCE + 10}, answers them
 * with the upstream ShopService WSDL read from {@code proxy.wsdl-location}, its {@code soap:address} rewritten to the
 * request URL, as {@code cxf:proxy-service} published it. Every other request to {@code /} is forwarded to the upstream
 * as the one {@code POST} the original sent: a {@code POST} with an envelope, the normal case; {@code GET},
 * {@code PUT}, {@code DELETE}, {@code PATCH}, {@code HEAD} and the methods the Servlet API does not name, without
 * those queries; {@code OPTIONS} through its own mapping, and a CORS pre-flight ({@code OPTIONS} with {@code Origin}
 * and {@code Access-Control-Request-Method}) like every other {@code OPTIONS}, because
 * {@link com.mulesoft.examples.proxying_a_soap_api.config.PreflightForwardingConfig} keeps Spring MVC's CORS
 * processing from answering it; {@code TRACE}, which {@code spring.mvc.dispatch-trace-request} dispatches here; and
 * {@code POST /?wsdl}, which the filter passes on. Known differences from the Mule listener, for the README:
 * <ul>
 *   <li>the response is written with a fixed {@code Content-Length}, never chunked;</li>
 *   <li>{@code POST /?wsdl} is forwarded rather than answered with the WSDL, a Tier 2A verification point;</li>
 *   <li>{@code DEBUG} logging of {@code org.springframework.web.servlet.DispatcherServlet} reads the request
 *       parameters to log them, which consumes a form body before it can be forwarded.</li>
 * </ul>
 */
@RestController
public class SoapProxyController {

    private final SoapProxyService soapProxyService;

    /**
     * @param soapProxyService the orchestration of the original flow
     */
    public SoapProxyController(SoapProxyService soapProxyService) {
        this.soapProxyService = soapProxyService;
    }

    /**
     * Forwards a request of any method. A mapping without methods matches every method except a plain
     * {@code OPTIONS}, which Spring MVC deliberately keeps out of it and {@link #options} receives. A CORS
     * pre-flight is matched by its {@code Access-Control-Request-Method} instead, so it arrives here unless that
     * header names {@code OPTIONS}, and it is forwarded like every other request.
     *
     * @param request  the caller's request, read raw
     * @param response written with the status, headers and body bytes {@link SoapProxyService#forward} returns
     * @throws IOException when the request body cannot be read or the response cannot be written
     */
    @RequestMapping("/")
    public void proxy(HttpServletRequest request, HttpServletResponse response) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        for (String name : Collections.list(request.getHeaderNames())) {
            for (String value : Collections.list(request.getHeaders(name))) {
                headers.add(name, value);
            }
        }
        byte[] body = request.getInputStream().readAllBytes();

        ResponseEntity<byte[]> result = soapProxyService.forward(headers, body);

        byte[] bytes = result.getBody() != null ? result.getBody() : new byte[0];
        response.setStatus(result.getStatusCode().value());
        result.getHeaders().forEach((name, values) -> {
            for (String value : values) {
                addHeader(response, name, value);
            }
        });
        response.setContentLengthLong(bytes.length);
        response.getOutputStream().write(bytes);
        // Committing here keeps FrameworkServlet's OPTIONS and TRACE post-processing from adding its own Allow
        // header or echo to the forwarded response.
        response.flushBuffer();
    }

    /**
     * Forwards an {@code OPTIONS} request. Without this mapping Spring MVC would answer {@code OPTIONS} itself
     * with an {@code Allow} header listing the methods mapped here. A CORS pre-flight arrives here only when its
     * {@code Access-Control-Request-Method} names {@code OPTIONS}; every other pre-flight reaches {@link #proxy}.
     *
     * @param request  the caller's request, read raw
     * @param response written as by {@link #proxy}
     * @throws IOException when the request body cannot be read or the response cannot be written
     */
    @RequestMapping(path = "/", method = RequestMethod.OPTIONS)
    public void options(HttpServletRequest request, HttpServletResponse response) throws IOException {
        proxy(request, response);
    }

    /**
     * Adds one upstream header to the response with its value unchanged. The Servlet API routes a
     * {@code Content-Type} header through {@code setContentType}, which Undertow re-serialises
     * ({@code text/xml; charset=UTF-8} becomes {@code text/xml;charset=UTF-8}), so on Undertow that header is put on
     * the exchange directly, keeping the bytes the upstream sent, which the parity fixtures compare. Without an
     * Undertow request on the calling thread (MockMvc, another container) the servlet response is used.
     */
    private static void addHeader(HttpServletResponse response, String name, String value) {
        if (HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name)) {
            ServletRequestContext context = ServletRequestContext.current();
            if (context != null) {
                context.getExchange().getResponseHeaders().add(HttpString.tryFromString(name), value);
                return;
            }
        }
        response.addHeader(name, value);
    }
}
