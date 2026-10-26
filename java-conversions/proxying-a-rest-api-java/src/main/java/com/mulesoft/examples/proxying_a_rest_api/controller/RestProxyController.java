/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_rest_api.controller;

import java.io.IOException;
import java.util.Collections;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.undertow.servlet.handlers.ServletRequestContext;
import io.undertow.util.HttpString;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.mulesoft.examples.proxying_a_rest_api.service.RestProxyService;

/**
 * The listener of the original flow {@code rest-api-proxy}: {@code http:listener path="/*"} without
 * {@code allowedMethods} ({@code proxying-a-rest-api.xml:12}) on {@code http:listener-config host="0.0.0.0"
 * port="${http.port}"} ({@code :3}), so every path and every method is accepted.
 *
 * <p>The class only binds the caller's request and writes the answer back. Header copying, the upstream call and
 * the treatment of the upstream status belong to {@link RestProxyService}. The request is read raw, never as
 * parameters or parts, so form and multipart bodies, the percent-encoding of path and query, and the caller's
 * {@code Authorization} header reach the service exactly as sent. The handlers write the response themselves
 * instead of returning a value, so Spring MVC adds no {@code Content-Type} from {@code Accept} negotiation,
 * answers no conditional request with 304 from the upstream {@code ETag} or {@code Last-Modified}, and does not
 * fail on a {@code Content-Type} or {@code Accept} it cannot parse. Any exception from the service, such as the
 * {@link IllegalStateException} for an upstream status of 400 or above or the
 * {@link org.springframework.web.client.ResourceAccessException} for a timeout or connect failure, leaves before
 * anything is written and is answered by the generic 500 advice.
 *
 * <p>Every method is forwarded: {@code PATCH}, {@code PROPFIND} and the other methods the Servlet API does not
 * name, {@code OPTIONS} through its own mapping, and {@code TRACE}, which {@code spring.mvc.dispatch-trace-request}
 * dispatches here. Known differences from the Mule listener, for the README:
 * <ul>
 *   <li>a CORS pre-flight ({@code OPTIONS} with {@code Origin} and {@code Access-Control-Request-Method}) is
 *       answered by Spring MVC's CORS processing before any handler runs, with 403 {@code Invalid CORS request},
 *       and is not forwarded;</li>
 *   <li>the response is written with a fixed {@code Content-Length}, never chunked;</li>
 *   <li>multipart forwarding is a Tier 2A verification point;</li>
 *   <li>{@code DEBUG} logging of {@code org.springframework.web.servlet.DispatcherServlet} reads the request
 *       parameters to log them, which consumes a form body before it can be forwarded.</li>
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
     * Forwards a request of any method except {@code OPTIONS}: a mapping without methods matches every other
     * method, and Spring MVC deliberately keeps {@code OPTIONS} out of it.
     *
     * @param request  the caller's request, read raw
     * @param response written with the status, headers and body bytes {@link RestProxyService#forward} returns
     * @throws IOException when the request body cannot be read or the response cannot be written
     */
    @RequestMapping("/**")
    public void proxy(HttpServletRequest request, HttpServletResponse response) throws IOException {
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        String rawPath = request.getRequestURI();
        String rawQuery = request.getQueryString();
        HttpHeaders headers = new HttpHeaders();
        for (String name : Collections.list(request.getHeaderNames())) {
            for (String value : Collections.list(request.getHeaders(name))) {
                headers.add(name, value);
            }
        }
        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());

        ResponseEntity<byte[]> result = restProxyService.forward(method, rawPath, rawQuery, headers, body);

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
     * with an {@code Allow} header listing the methods mapped here.
     *
     * @param request  the caller's request, read raw
     * @param response written as by {@link #proxy}
     * @throws IOException when the request body cannot be read or the response cannot be written
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
