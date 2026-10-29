/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_request_response_with_logger.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.mulesoft.examples.http_request_response_with_logger.service.EchoService;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP trigger of flow {@code EchoFlow} (http-request-response-with-logger/src/main/app/echo.xml:5): the
 * {@code http:listener path="/*"}, whose payload, the request path, is set and logged by {@link EchoService}.
 *
 * <p>The listener declares no {@code allowedMethods}, so every path and every method is echoed. No handler
 * declares {@code produces}, {@code consumes}, parameters, headers or a request body: any {@code Accept},
 * {@code Content-Type} or body the caller sends is ignored rather than rejected with 406, 415 or 400, as the
 * original flow read none of them.
 *
 * <p>The echoed value is {@link HttpServletRequest#getRequestURI()}, which on Undertow is the request path as
 * sent, without the query string and without percent-decoding, the same value as Mule's
 * {@code http.request.path} inbound property; the context path is empty, so nothing is stripped.
 *
 * <p>The reply carries a {@code Content-Length} and no {@code Content-Type}: the flow's {@code set-payload}
 * declares no MIME type, and the Mule listener sent no Content-Type for such a payload. It is written to the servlet
 * response directly, so no message converter adds one.
 *
 * <p>Two details are Mule platform defaults the flow does not state, and are Tier 2A verification points that a
 * captured fixture of the original overrides: the retention of percent-encoding in the echoed path
 * ({@code /hello%20world}), and the root path {@code /} being served and echoed as {@code /}.
 */
@RestController
public class EchoController {

    private final EchoService echoService;

    public EchoController(EchoService echoService) {
        this.echoService = echoService;
    }

    /**
     * Echoes the request path for every method except {@code OPTIONS}; a {@code HEAD} response carries the
     * headers without the body. {@code TRACE} reaches this mapping only because {@code application.yml} sets
     * {@code spring.mvc.dispatch-trace-request}; without it the servlet answers {@code TRACE} itself.
     *
     * @param request  the inbound request, read for its raw path only
     * @param response the response the request path is written to, as 200 with its UTF-8 bytes
     * @throws IOException when writing the response fails
     */
    @RequestMapping("/**")
    public void echo(HttpServletRequest request, HttpServletResponse response) throws IOException {
        echoResponse(request, response);
    }

    /**
     * Echoes the request path for {@code OPTIONS} like every other method. A mapping without methods deliberately
     * does not match {@code OPTIONS}, which Spring MVC would otherwise answer itself with an {@code Allow} header
     * and no body; the Mule listener ran the flow for {@code OPTIONS} too.
     *
     * @param request  the inbound request, read for its raw path only
     * @param response the response the request path is written to, as 200 with its UTF-8 bytes
     * @throws IOException when writing the response fails
     */
    @RequestMapping(value = "/**", method = RequestMethod.OPTIONS)
    public void echoOptions(HttpServletRequest request, HttpServletResponse response) throws IOException {
        echoResponse(request, response);
    }

    /**
     * Writes 200 with the payload's UTF-8 bytes and their {@code Content-Length}. The payload is obtained before the
     * response is touched, so a failure still finds an uncommitted response for the 500 of
     * {@code GlobalExceptionHandler}.
     */
    private void echoResponse(HttpServletRequest request, HttpServletResponse response) throws IOException {
        byte[] bytes = echoService.echo(request.getRequestURI()).getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentLength(bytes.length);
        ServletOutputStream stream = response.getOutputStream();
        stream.write(bytes);
        stream.flush();
    }
}
