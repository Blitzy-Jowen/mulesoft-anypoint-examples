/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_json_data_to_a_jms_queue.controller;

import java.io.IOException;
import java.util.Set;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.undertow.servlet.handlers.ServletRequestContext;
import io.undertow.util.Headers;

import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.mulesoft.examples.sending_json_data_to_a_jms_queue.service.SalesQueueService;

/**
 * The HTTP listener of flow {@code json-to-jmsFlow}
 * (sending-json-data-to-a-jms-queue/src/main/app/json-to-jms.xml).
 *
 * <p>Source mapping:
 * <ul>
 *   <li>line 6, {@code http:listener path="sales" allowedMethods="POST"}: this class, mapped to {@code POST /sales}
 *       with no {@code consumes}, {@code produces}, parameter or header condition, because the Mule listener
 *       accepted any {@code Content-Type} and ignored {@code Accept}; the listener matched the method ignoring
 *       case, which {@code config.ListenerMethodFilter} reproduces ahead of this mapping;</li>
 *   <li>line 8, {@code byte-array-to-string-transformer}: {@link SalesQueueService#send(byte[], String)}, which
 *       this class hands the raw body bytes and the raw {@code Content-Type} header;</li>
 *   <li>lines 9-10, the one-way {@code jms:outbound-endpoint queue="sales"} and the {@code logger}:
 *       {@link SalesQueueService#send(String)}.</li>
 * </ul>
 * The body is never parsed or validated: the example's own sample body is not valid JSON and is forwarded
 * untouched. The response is 200 with the payload the flow ends with, which is the request body, so the response
 * bytes equal the request bytes for every body valid in its charset. Its {@code Content-Type} is the one the
 * listener derived from the message: the request's lower-cased {@code type/subtype} with
 * {@code "; charset="} and the charset the body was decoded with, or no header when the request has no
 * {@code Content-Type}; see {@link SalesQueueService#responseContentType(String)}.
 *
 * <p>Plan defaults, each a verification point for fixtures captured from the original application:
 * <ul>
 *   <li>a {@code Content-Type} that cannot be parsed, or that names a charset the JVM cannot use, is decoded as
 *       UTF-8; the response carries no {@code Content-Type} for the first, and the request's type with
 *       {@code charset=UTF-8} for the second;</li>
 *   <li>an empty body is sent to the queue as the empty string and answered with an empty body;</li>
 *   <li>an {@code application/x-www-form-urlencoded} body, which curl's {@code -d} sends, is passed through as raw
 *       bytes, whereas the Mule listener turned such a body into a parameter map;</li>
 *   <li>{@code TRACE} and {@code OPTIONS} are answered with the listener's 405 like every other method but
 *       {@code POST}; see {@link #rejectUnsupportedMethod(HttpServletRequest)}.</li>
 * </ul>
 * Other methods, unserved paths ({@code /sales/} included) and a failed send reach the generic handler of the
 * exception package, which answers 405, 404 and 500 as the Mule listener and runtime did.
 */
@RestController
public class SalesController {

    private static final String PATH = "/sales";

    private static final String ALLOWED_METHOD = "POST";

    private final SalesQueueService salesQueueService;

    /**
     * @param salesQueueService decodes the body, sends it to queue {@code sales}, logs the message and encodes the
     *                          response body
     */
    public SalesController(SalesQueueService salesQueueService) {
        this.salesQueueService = salesQueueService;
    }

    /**
     * Sends the request body to queue {@code sales} and echoes it with status 200.
     *
     * <p>The body is read from the servlet stream rather than through {@code @RequestBody}, because Spring's
     * argument resolution breaks byte fidelity: it rejects an unparseable {@code Content-Type} before this method
     * runs, and it rebuilds an {@code application/x-www-form-urlencoded} body from the parsed request parameters.
     * No request parameter is read before the stream, which would consume a form body.
     *
     * <p>The raw body bytes and the raw {@code Content-Type} header go to
     * {@link SalesQueueService#send(byte[], String)}, which decodes, sends and logs, and returns the response body
     * bytes. The response is written to the servlet response directly, so content negotiation can never reject it.
     * Its {@code Content-Type} is the value {@link SalesQueueService#responseContentType(String)} builds, written
     * byte for byte, or no header at all when that value is {@code null}. The body is written as the bytes the
     * service returns, already encoded with the charset that header names; a writer would re-encode the text with
     * the servlet response's own charset.
     *
     * @param request  the {@code POST /sales} request
     * @param response the response the echoed payload is written to
     * @throws IOException when reading the request body or writing the response fails
     * @throws org.springframework.jms.JmsException when the send fails; it propagates to the generic 500 handler
     */
    @RequestMapping(path = PATH, method = RequestMethod.POST)
    public void postSales(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // getContentType() is the raw header; getCharacterEncoding() is not used, because Boot's
        // CharacterEncodingFilter forces the request encoding to UTF-8.
        String rawContentType = request.getContentType();
        byte[] body = request.getInputStream().readAllBytes();

        byte[] out = salesQueueService.send(body, rawContentType);
        String contentType = salesQueueService.responseContentType(rawContentType);

        response.setStatus(HttpServletResponse.SC_OK);
        if (contentType != null) {
            writeContentType(response, contentType);
        }
        response.setContentLength(out.length);
        ServletOutputStream stream = response.getOutputStream();
        stream.write(out);
        stream.flush();
    }

    /**
     * Rejects {@code OPTIONS} and {@code TRACE} on {@code /sales} with the listener's 405, which the generic handler
     * of the exception package writes as {@code Method not allowed for endpoint: /sales}.
     *
     * <p>The listener allowed {@code POST} only. Without this mapping Spring MVC would answer {@code OPTIONS} itself
     * with 200 and an {@code Allow} header. {@code TRACE} reaches the dispatcher, and so this method, only because
     * {@code application.yml} sets {@code spring.mvc.dispatch-trace-request: true}; otherwise the servlet's own
     * {@code TRACE} echo would answer it.
     *
     * @param request the rejected request, whose method is reported
     * @throws HttpRequestMethodNotSupportedException always
     */
    @RequestMapping(path = PATH, method = {RequestMethod.OPTIONS, RequestMethod.TRACE})
    public void rejectUnsupportedMethod(HttpServletRequest request) throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(request.getMethod(), Set.of(ALLOWED_METHOD));
    }

    /**
     * Writes {@code contentType} as the response's {@code Content-Type} header, byte for byte.
     *
     * <p>Undertow's {@code HttpServletResponse.setContentType}, and {@code setHeader("Content-Type", ...)}, which
     * delegates to it, rewrite the value as {@code type;charset=X}, dropping the space the original listener wrote
     * after the {@code ;}. The header is therefore put on the Undertow exchange behind the current request, which
     * sends it verbatim. Without an Undertow request on the calling thread (another servlet container, MockMvc)
     * the servlet API is used instead.
     */
    private static void writeContentType(HttpServletResponse response, String contentType) {
        ServletRequestContext context = ServletRequestContext.current();
        if (context == null) {
            response.setContentType(contentType);
        } else {
            context.getExchange().getResponseHeaders().put(Headers.CONTENT_TYPE, contentType);
        }
    }
}
