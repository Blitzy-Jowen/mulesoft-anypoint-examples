/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_rest_api.controller;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.undertow.servlet.handlers.ServletRequestContext;
import io.undertow.util.HttpString;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.mulesoft.examples.proxying_a_rest_api.config.ForwardedRequestHeaders;
import com.mulesoft.examples.proxying_a_rest_api.service.RestProxyService;

/**
 * The listener of the original flow {@code rest-api-proxy}: {@code http:listener path="/*"} without
 * {@code allowedMethods} ({@code proxying-a-rest-api.xml:12}) on {@code http:listener-config host="0.0.0.0"
 * port="${http.port}"} ({@code :3}), so every path and every method is accepted.
 *
 * <p>The class only binds the caller's request and writes the answer back. Reading the caller's headers as sent
 * belongs to {@link ForwardedRequestHeaders}; header copying, the upstream call and the treatment of the upstream
 * status belong to {@link RestProxyService}. The request is read raw, never as
 * parameters or parts, so form and multipart bodies, the percent-encoding of path and query, and the caller's
 * {@code Authorization} header reach the service exactly as sent. The body is handed over unread, as the request's
 * input stream with its declared length, so it reaches the upstream as a stream, as the Mule listener's stream
 * payload did, and is never held in memory whole. The handlers write the response themselves
 * instead of returning a value, so Spring MVC adds no {@code Content-Type} from {@code Accept} negotiation,
 * answers no conditional request with 304 from the upstream {@code ETag} or {@code Last-Modified}, and does not
 * fail on a {@code Content-Type} or {@code Accept} it cannot parse. Any exception from the service, such as the
 * {@link IllegalStateException} for an upstream status of 400 or above or the
 * {@link org.springframework.web.client.ResourceAccessException} for a timeout or connect failure, leaves before
 * anything is written and is answered by the generic 500 advice.
 *
 * <p>Every method is forwarded: {@code PATCH}, {@code PROPFIND} and the other methods the Servlet API does not
 * name, {@code OPTIONS} through its own mapping, a CORS pre-flight ({@code OPTIONS} with {@code Origin} and
 * {@code Access-Control-Request-Method}) like every other {@code OPTIONS}, because
 * {@link com.mulesoft.examples.proxying_a_rest_api.config.PreflightForwardingConfig} keeps Spring MVC's CORS
 * processing from answering it, and {@code TRACE}, which {@code spring.mvc.dispatch-trace-request} dispatches here.
 * Known differences from the Mule listener, for the README:
 * <ul>
 *   <li>the response is written with a fixed {@code Content-Length}, never chunked;</li>
 *   <li>multipart forwarding is a Tier 2A verification point;</li>
 *   <li>a request header value reaches the upstream without its leading and trailing SP and HTAB, and an obs-fold
 *       in it as one SP. Every other byte arrives as sent, inner runs of SP and HTAB included, so a Digest
 *       {@code username="John  Smith"} or a value {@code a<HTAB>b} is forwarded unchanged:
 *       {@link ForwardedRequestHeaders} puts back the values the request-framing guard recorded before Undertow's
 *       parser turned every such run into one SP. Several {@code Cookie} lines are forwarded as the one
 *       {@code Cookie} value Undertow joins them into, with {@code ; } (Tier 2A verification point);</li>
 *   <li>a request whose head holds a CR not followed by LF, an LF not preceded by CR, a control character other
 *       than HTAB in a header value, whitespace before a header colon, or more than one or conflicting body framing
 *       headers is answered with an empty 400 by
 *       {@link com.mulesoft.examples.proxying_a_rest_api.config.RequestFramingGuardConfig}'s guard before Undertow
 *       parses it, so none of them is forwarded (Tier 2A verification point).</li>
 * </ul>
 */
@RestController
public class RestProxyController {

    private final RestProxyService restProxyService;

    /**
     * @param restProxyService the orchestration of the original flow
     */
    public RestProxyController(RestProxyService restProxyService) {
        this.restProxyService = restProxyService;
    }

    /**
     * Forwards a request of any method. A mapping without methods matches every method except a plain
     * {@code OPTIONS}, which Spring MVC deliberately keeps out of it and {@link #options} receives. A CORS
     * pre-flight is matched by its {@code Access-Control-Request-Method} instead, so it arrives here unless that
     * header names {@code OPTIONS}, and it is forwarded as the {@code OPTIONS} request it is.
     *
     * @param request  the caller's request, read raw; its body is passed on as its input stream and
     *                 {@link HttpServletRequest#getContentLengthLong() declared length}, unread
     * @param response written with the status, headers and body bytes {@link RestProxyService#forward} returns
     * @throws IOException when the request body stream cannot be opened or the response cannot be written
     */
    @RequestMapping("/**")
    public void proxy(HttpServletRequest request, HttpServletResponse response) throws IOException {
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        String rawPath = request.getRequestURI();
        String rawQuery = request.getQueryString();
        HttpHeaders headers = ForwardedRequestHeaders.from(request);

        ResponseEntity<byte[]> result = restProxyService.forward(method, rawPath, rawQuery, headers,
                request.getInputStream(), request.getContentLengthLong());

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
     * @throws IOException when the request body stream cannot be opened or the response cannot be written
     */
    @RequestMapping(path = "/**", method = RequestMethod.OPTIONS)
    public void options(HttpServletRequest request, HttpServletResponse response) throws IOException {
        proxy(request, response);
    }

    /**
     * Adds one upstream header to the response with its value unchanged. The Servlet API routes a
     * {@code Content-Type} header through {@code setContentType}, which Undertow re-serialises
     * ({@code text/html; charset=UTF-8} becomes {@code text/html;charset=UTF-8}), so on Undertow that header is put
     * on the exchange directly, keeping the bytes the upstream sent, which the parity fixtures compare. Without an
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
