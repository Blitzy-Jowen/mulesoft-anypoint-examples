/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_json_data_to_a_jms_queue.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.undertow.servlet.handlers.ServletRequestContext;
import io.undertow.util.Headers;

import org.springframework.http.HttpHeaders;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.mulesoft.examples.sending_json_data_to_a_jms_queue.exception.UndecodableFormException;
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
 *   <li>line 8, {@code byte-array-to-string-transformer}: the listener's payload. For an
 *       {@code application/x-www-form-urlencoded} entity, which curl's {@code -d} sends, that is the parameter map
 *       the listener decoded, rendered as text by {@link SalesQueueService#sendForm(byte[], String)}; for any other
 *       request the raw body bytes, decoded by {@link SalesQueueService#send(byte[], String)}. This class hands
 *       each of them the raw body bytes and the raw {@code Content-Type} header;</li>
 *   <li>lines 9-10, the one-way {@code jms:outbound-endpoint queue="sales"} and the {@code logger}:
 *       {@link SalesQueueService#send(String)}.</li>
 * </ul>
 * A body other than a form is never parsed or validated: the example's own sample body is not valid JSON and is
 * forwarded untouched. The response is 200 with the payload the flow ends with: for such a body that is the request
 * body, so the response bytes equal the request bytes for every body valid in its charset; for a form it is the
 * map's rendering, such as <code>{a=1, b=2}</code>, which is also the text queued. Its {@code Content-Type} is the
 * one the listener derived from the message: the request's lower-cased {@code type/subtype} with
 * {@code "; charset="} and the charset the response body is encoded with, or no header when the request has no
 * {@code Content-Type}; see {@link SalesQueueService#responseContentType(String)}.
 *
 * <p>A form entity the listener rejected before the flow runs is answered with the listener's own response: 400
 * with the parser's message for a {@code Content-Type} the listener could not parse or whose one charset it could
 * not use, 500 {@code Server encountered a problem} for a {@code Content-Type} naming two different charsets, and
 * 400 {@code Cannot decode x-www-form-urlencoded payload} for a body holding a malformed percent escape; see
 * {@link #rejectUndecodableForm(UndecodableFormException, HttpServletResponse)}.
 *
 * <p>Plan defaults, each a verification point for fixtures captured from the original application:
 * <ul>
 *   <li>for a body other than a form entity, a {@code Content-Type} that cannot be parsed, or that names a charset
 *       the JVM cannot use, is decoded as UTF-8; the response carries no {@code Content-Type} for the first, and
 *       the request's type with {@code charset=UTF-8} for the second. A form entity with such a header gets the
 *       listener's rejection above;</li>
 *   <li>an empty body is sent to the queue as the empty string and answered with an empty body;</li>
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

    /** The transfer coding under which the Mule listener read a body as an entity whatever its length. */
    private static final String CHUNKED = "chunked";

    private final SalesQueueService salesQueueService;

    /**
     * @param salesQueueService decodes the body, sends it to queue {@code sales}, logs the message and encodes the
     *                          response body
     */
    public SalesController(SalesQueueService salesQueueService) {
        this.salesQueueService = salesQueueService;
    }

    /**
     * Sends the flow's payload to queue {@code sales} and echoes it with status 200.
     *
     * <p>The body is read from the servlet stream rather than through {@code @RequestBody}, because Spring's
     * argument resolution breaks byte fidelity: it rejects an unparseable {@code Content-Type} before this method
     * runs, and it rebuilds an {@code application/x-www-form-urlencoded} body from the parsed request parameters.
     * No request parameter is read before the stream, which would consume a form body.
     *
     * <p>The payload is the one the Mule 3.8 listener built. When the request has an entity, a non-empty body or
     * one sent with a {@code Transfer-Encoding} whose last coding is {@code chunked}, and its {@code Content-Type}
     * is a form type by {@link SalesQueueService#isFormUrlEncoded(String)}, the raw body bytes and the raw
     * {@code Content-Type} go to {@link SalesQueueService#sendForm(byte[], String)}, which decodes the parameter
     * map, renders it, sends and logs the rendering, and returns the response body bytes. A zero-byte chunked form
     * is such an entity and is rendered <code>{}</code>. Every other request, a form whose {@code Content-Length}
     * is 0 included, goes to {@link SalesQueueService#send(byte[], String)}, which decodes the bytes as text, sends
     * and logs them, and returns the response body bytes. A form entity the listener rejected, for its
     * {@code Content-Type} or for its body, leaves this method with {@link UndecodableFormException} before anything
     * is sent or written.
     *
     * <p>The response is written to the servlet response directly, so content negotiation can never reject it.
     * Its {@code Content-Type} is the value {@link SalesQueueService#responseContentType(String)} builds, written
     * byte for byte, or no header at all when that value is {@code null}. The body is written as the bytes the
     * service returns, already encoded with the charset that header names; a writer would re-encode the text with
     * the servlet response's own charset.
     *
     * @param request  the {@code POST /sales} request
     * @param response the response the echoed payload is written to
     * @throws IOException when reading the request body or writing the response fails
     * @throws UndecodableFormException when the listener rejected a form entity's {@code Content-Type} or body; it
     *         reaches {@link #rejectUndecodableForm(UndecodableFormException, HttpServletResponse)}
     * @throws org.springframework.jms.JmsException when the send fails; it propagates to the generic 500 handler
     */
    @RequestMapping(path = PATH, method = RequestMethod.POST)
    public void postSales(HttpServletRequest request, HttpServletResponse response)
            throws IOException, UndecodableFormException {
        // getContentType() is the raw header; getCharacterEncoding() is not used, because Boot's
        // CharacterEncodingFilter forces the request encoding to UTF-8.
        String rawContentType = request.getContentType();
        byte[] body = request.getInputStream().readAllBytes();

        // The Mule listener had an entity when the body was chunked or its Content-Length was positive; only an
        // entity of a form type became a parameter map, and an empty one stayed the empty payload.
        boolean entityPresent = body.length > 0 || isChunked(request);
        byte[] out = entityPresent && salesQueueService.isFormUrlEncoded(rawContentType)
                ? salesQueueService.sendForm(body, rawContentType)
                : salesQueueService.send(body, rawContentType);
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
     * Answers a form-urlencoded entity the listener rejected as the Mule 3.8 listener did before running the flow:
     * {@code DefaultHttpListener} sent {@code BAD_REQUEST} for a parsing failure, and {@code INTERNAL_SERVER_ERROR}
     * for a runtime failure, with the message as a byte entity. The reply therefore has the failure's
     * {@linkplain UndecodableFormException#getStatus() status} with Undertow's standard reason phrase,
     * {@code Bad Request} or {@code Internal Server Error}, the message's UTF-8 bytes with their
     * {@code Content-Length}, and no {@code Content-Type}. An empty message, which a {@code charset=""} parameter
     * gives, is an empty body with {@code Content-Length: 0}.
     *
     * <p>The reply is written to the servlet response directly, so no message converter adds a
     * {@code Content-Type}. This handler is declared on the controller, so Spring MVC prefers it to every
     * {@code @ControllerAdvice}, the generic 500 handler of the exception package included.
     *
     * @param failure  the listener's rejection, whose status is the response status and whose message is the body
     * @param response the response the rejection is written to
     * @throws IOException when writing the reply fails
     */
    @ExceptionHandler(UndecodableFormException.class)
    public void rejectUndecodableForm(UndecodableFormException failure, HttpServletResponse response)
            throws IOException {
        byte[] bytes = failure.getMessage().getBytes(StandardCharsets.UTF_8);
        response.setStatus(failure.getStatus());
        response.setContentLength(bytes.length);
        ServletOutputStream stream = response.getOutputStream();
        stream.write(bytes);
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
     * Tells whether the request body was sent with chunked transfer coding: the last coding of the last
     * {@code Transfer-Encoding} header, without its parameters and ignoring letter case, is {@code chunked}. The
     * Mule listener treated such a body as an entity even when it carried no byte.
     */
    private static boolean isChunked(HttpServletRequest request) {
        Enumeration<String> headers = request.getHeaders(HttpHeaders.TRANSFER_ENCODING);
        if (headers == null) {
            return false;
        }
        List<String> values = Collections.list(headers);
        if (values.isEmpty()) {
            return false;
        }
        String lastHeader = values.get(values.size() - 1);
        String coding = lastHeader.substring(lastHeader.lastIndexOf(',') + 1);
        int parameters = coding.indexOf(';');
        return CHUNKED.equalsIgnoreCase((parameters < 0 ? coding : coding.substring(0, parameters)).trim());
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
