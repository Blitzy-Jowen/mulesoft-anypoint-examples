/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_json_data_to_a_jms_queue.controller;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
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
 *       accepted any {@code Content-Type} and ignored {@code Accept};</li>
 *   <li>line 8, {@code byte-array-to-string-transformer}: the decoding of the raw body bytes with the request's
 *       charset, or UTF-8, the application encoding of {@code mule-deploy.properties}, when the request names
 *       none or names one that cannot be used;</li>
 *   <li>lines 9-10, the one-way {@code jms:outbound-endpoint queue="sales"} and the {@code logger}:
 *       {@link SalesQueueService#send(String)}.</li>
 * </ul>
 * The body is never parsed or validated: the example's own sample body is not valid JSON and is forwarded
 * untouched. The response is 200 with the payload the flow ends with, which is the request body, so the response
 * bytes equal the request bytes for every body valid in its charset.
 *
 * <p>Plan defaults, each a verification point for fixtures captured from the original application:
 * <ul>
 *   <li>a request without {@code Content-Type} is decoded as UTF-8 and answered with
 *       {@code text/plain;charset=UTF-8};</li>
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

    /** Content-Type of a response to a request that names none; renders as {@code text/plain;charset=UTF-8}. */
    private static final MediaType DEFAULT_CONTENT_TYPE = new MediaType("text", "plain", StandardCharsets.UTF_8);

    private final SalesQueueService salesQueueService;

    /**
     * @param salesQueueService sends the decoded body to queue {@code sales} and logs the message
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
     * <p>The response is written to the servlet response directly, so content negotiation can never reject it.
     * Its {@code Content-Type} is the request's, unchanged, or {@link #DEFAULT_CONTENT_TYPE} when the request has
     * none. The body is the queued payload encoded with the charset it was decoded with, written as bytes, because
     * the writer would fail on an echoed charset name the JVM does not support.
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
        Charset charset = resolveCharset(rawContentType);
        byte[] body = request.getInputStream().readAllBytes();

        String result = salesQueueService.send(new String(body, charset));

        byte[] out = result.getBytes(charset);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(isBlank(rawContentType) ? DEFAULT_CONTENT_TYPE.toString() : rawContentType);
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
     * Returns the charset {@code rawContentType} names, or UTF-8, the application encoding of the original, when
     * the header is absent or blank, names no charset, cannot be parsed, or names a charset that is malformed or
     * unsupported.
     */
    private static Charset resolveCharset(String rawContentType) {
        if (isBlank(rawContentType)) {
            return StandardCharsets.UTF_8;
        }
        try {
            Charset charset = MediaType.parseMediaType(rawContentType).getCharset();
            return charset != null ? charset : StandardCharsets.UTF_8;
        } catch (IllegalArgumentException unusable) {
            // One catch covers every failure: InvalidMediaTypeException (an unparseable header, or a charset the
            // parser cannot resolve) and Charset.forName's IllegalCharsetNameException and
            // UnsupportedCharsetException all extend IllegalArgumentException, so a multi-catch of them would not
            // compile.
            return StandardCharsets.UTF_8;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
