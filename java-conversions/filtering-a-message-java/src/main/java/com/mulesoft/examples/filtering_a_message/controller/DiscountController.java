/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.filtering_a_message.controller;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.mulesoft.examples.filtering_a_message.service.DiscountService;

import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP trigger of flow {@code filteringFlow1} (filtering-a-message/src/main/app/filtering.xml:3-7): the listener on
 * {@code /} with {@code allowedMethods="POST"} and its {@code response-builder statusCode="200"}. The filters, the
 * JSON conversion and the logger of the flow run in {@link DiscountService}; this class only binds the request and
 * writes the response.
 *
 * <ul>
 *   <li>Accepted request: 200 {@code the discount was granted.} with its {@code Content-Length} and no
 *       {@code Content-Type}: the response builder sets only the status and the flow's {@code set-payload}
 *       declares no MIME type, and the Mule listener sent no Content-Type for such a payload.</li>
 *   <li>Rejected by a filter: a committed 200 with an empty body and no Content-Type. Neither filter throws on
 *       rejection, so the flow ends with no payload, as {@code FilteringMessageIT.java:62-67} asserts for its second
 *       message.</li>
 *   <li>A form-urlencoded body the listener cannot decode: the listener's own 400, written before the flow runs
 *       (see {@link #rejectUndecodableForm}).</li>
 *   <li>Every other exception propagates unchanged to {@code GlobalExceptionHandler}, because the flow declares no
 *       exception strategy.</li>
 * </ul>
 * These replies are written to the servlet response directly, so no message converter adds a Content-Type.
 *
 * <p>No mapping declares {@code consumes} or {@code produces}: the listener accepted any Content-Type, or none,
 * and ignored {@code Accept}, leaving the payload check to the filters. The flow mapping declares no method, so
 * that {@link #grantDiscount} can compare the request method with {@code allowedMethods} ignoring case, as the Mule
 * listener did; every other method on {@code /}, {@code OPTIONS} through its own rejecting mapping, reaches the 405
 * and every other path the no-listener 404 of {@code GlobalExceptionHandler}.
 */
@RestController
public class DiscountController {

    /**
     * The listener's {@code allowedMethods}. Mule 3.8's {@code MethodRequestMatcher} lowercased both the request
     * method and this value, so {@code post} and {@code Post} are admitted too.
     */
    private static final String LISTENER_METHOD = "POST";

    private static final String PAIR_SEPARATOR = "&";

    private static final String NAME_VALUE_SEPARATOR = "=";

    /** The text whose presence in {@code Content-Type} made the Mule listener parse the body as multipart. */
    private static final String MULTIPART = "multipart";

    /** Message of the Mule listener's failure to decode a form-urlencoded body. */
    private static final String FORM_DECODING_FAILURE = "Cannot decode x-www-form-urlencoded payload";

    private final DiscountService discountService;

    public DiscountController(DiscountService discountService) {
        this.discountService = discountService;
    }

    /**
     * Runs a {@code POST /}, in any letter case, through the flow.
     *
     * <p>The method check comes first, and the body is bound only after it, so a rejected method is answered with
     * the 405 whatever its body or {@code Content-Type}. A form-urlencoded body the listener cannot decode is answered
     * with the committed 400 of {@link #rejectUndecodableForm}, and the flow does not run. Otherwise nothing is
     * written before the service returns, so every exception still finds an uncommitted response for
     * {@code GlobalExceptionHandler}.
     *
     * @param request  the request, whose method and body are read
     * @param response the response the reply is written to
     * @throws HttpRequestMethodNotSupportedException when the method is not {@code POST} in some letter case;
     *                                                answered with SC-1's 405
     * @throws IOException                            when reading the request body or writing the reply fails
     */
    @RequestMapping("/")
    public void grantDiscount(HttpServletRequest request, HttpServletResponse response)
            throws HttpRequestMethodNotSupportedException, IOException {
        if (!LISTENER_METHOD.equalsIgnoreCase(request.getMethod())) {
            throw new HttpRequestMethodNotSupportedException(request.getMethod(), List.of(LISTENER_METHOD));
        }
        Object payload;
        try {
            payload = listenerPayload(request);
        } catch (UndecodableFormException e) {
            rejectUndecodableForm(response, e);
            return;
        }
        Optional<String> outcome = discountService.applyDiscountFilters(request.getMethod(), payload);
        response.setStatus(HttpServletResponse.SC_OK);
        if (outcome.isEmpty()) {
            // Committed, so FrameworkServlet cannot complete the empty reply with anything of its own.
            response.setContentLength(0);
            response.flushBuffer();
            return;
        }
        byte[] bytes = outcome.get().getBytes(StandardCharsets.UTF_8);
        response.setContentLength(bytes.length);
        ServletOutputStream stream = response.getOutputStream();
        stream.write(bytes);
        stream.flush();
    }

    /**
     * Answers a form-urlencoded body the listener could not decode as the Mule 3.8 listener did before running the
     * flow: {@code DefaultHttpListener} sent {@code BAD_REQUEST} with the failure's message as a byte entity, so the
     * reply is a 400 with Undertow's standard reason phrase {@code Bad Request}, the message's bytes with their
     * {@code Content-Length}, and no {@code Content-Type}. It is written to the servlet response and committed here,
     * so no message converter adds a Content-Type and {@code FrameworkServlet}'s {@code TRACE} and {@code OPTIONS}
     * fallbacks add nothing.
     *
     * @param response the response the 400 is written to
     * @param failure  the decoding failure, whose message is the body
     * @throws IOException when writing the reply fails
     */
    private static void rejectUndecodableForm(HttpServletResponse response, UndecodableFormException failure)
            throws IOException {
        byte[] bytes = failure.getMessage().getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentLength(bytes.length);
        ServletOutputStream stream = response.getOutputStream();
        stream.write(bytes);
        stream.flush();
    }

    /**
     * Rejects {@code OPTIONS /} as the listener's {@code allowedMethods="POST"} did. A mapping without methods does
     * not match {@code OPTIONS}, so without this one Spring MVC would answer it itself, with 200 and an
     * {@code Allow} header.
     *
     * @return never returns normally
     * @throws HttpRequestMethodNotSupportedException always, answered with SC-1's 405
     */
    @RequestMapping(path = "/", method = RequestMethod.OPTIONS)
    public ResponseEntity<String> rejectOptions() throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(RequestMethod.OPTIONS.name(), List.of(LISTENER_METHOD));
    }

    /**
     * Builds the payload the Mule 3.8 listener handed the flow, as {@code GrizzlyHttpRequestAdapter#getEntity} and
     * {@code HttpRequestToMuleEvent#transform} built it, in this order:
     * <ol>
     *   <li>a {@code Content-Type} containing {@code multipart}: {@code null}, the original's {@code NullPayload},
     *       because the listener moved the parts to inbound attachments; the body is not read;</li>
     *   <li>an empty body: {@code null}, the {@code NullPayload} of an empty entity;</li>
     *   <li>{@code application/x-www-form-urlencoded}: the parameter map {@link #formParameters} decodes;</li>
     *   <li>any other body: its text in the charset the {@code Content-Type} declares, UTF-8 when it declares
     *       none; this stands for the request stream the payload-type filter accepted.</li>
     * </ol>
     *
     * <p>The body is read from the servlet stream rather than through {@code @RequestBody}, which rebuilds a form
     * {@code POST} body from the container's parsed parameters. A non-empty {@code Content-Type} that cannot be
     * parsed, an unknown charset included, raises {@link InvalidMediaTypeException} with the text request-body
     * binding reported, which reaches the 500 of {@code GlobalExceptionHandler}.
     *
     * @param request the request whose headers and body are read
     * @return {@code null}, a {@link Map} of form parameters, or the body text
     * @throws IOException              when reading the request body fails
     * @throws UndecodableFormException when a form-urlencoded body holds a malformed percent escape
     */
    private static Object listenerPayload(HttpServletRequest request) throws IOException, UndecodableFormException {
        String contentTypeHeader = request.getHeader(HttpHeaders.CONTENT_TYPE);
        if (contentTypeHeader != null && contentTypeHeader.contains(MULTIPART)) {
            return null;
        }
        byte[] body = request.getInputStream().readAllBytes();
        if (body.length == 0) {
            return null;
        }
        MediaType contentType = StringUtils.hasLength(contentTypeHeader)
                ? MediaType.parseMediaType(contentTypeHeader)
                : null;
        Charset charset = contentType != null && contentType.getCharset() != null
                ? contentType.getCharset()
                : StandardCharsets.UTF_8;
        if (contentType != null && MediaType.APPLICATION_FORM_URLENCODED.equalsTypeAndSubtype(contentType)) {
            // The listener read the form text with the JVM default charset, here the application encoding UTF-8,
            // and applied the declared charset only when URL-decoding it.
            return formParameters(new String(body, StandardCharsets.UTF_8), charset);
        }
        return new String(body, charset);
    }

    /**
     * Decodes a form-urlencoded body as Mule 3.8's {@code org.mule.module.http.internal.HttpParser#decodeString}
     * filled its {@code ParameterMap}: a blank body gives an empty map; otherwise the body is split on {@code &}
     * ({@link String#split} semantics, so a leading empty pair is kept as the name {@code ""} and trailing ones are
     * dropped) and each pair on its first {@code =}, name and value are URL-decoded with {@code charset}, a pair
     * without {@code =} gets a {@code null} value, and a repeated name keeps its first position and its last value,
     * the value the map returned for that name.
     *
     * <p>A malformed percent escape fails with the listener's own message, {@value #FORM_DECODING_FAILURE}, as the
     * {@code HttpRequestParsingException} that Mule's {@code HttpRequestToMuleEvent} raised before the flow ran;
     * {@link #grantDiscount} answers it with the listener's 400.
     *
     * @param body    the form text
     * @param charset the charset the {@code Content-Type} declares, UTF-8 when it declares none
     * @return the decoded parameters in first-occurrence order
     * @throws UndecodableFormException when a name or value holds a malformed percent escape
     */
    private static Map<String, String> formParameters(String body, Charset charset) throws UndecodableFormException {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (body.isBlank()) {
            return parameters;
        }
        try {
            for (String pair : body.split(PAIR_SEPARATOR)) {
                int separator = pair.indexOf(NAME_VALUE_SEPARATOR);
                if (separator < 0) {
                    parameters.put(URLDecoder.decode(pair, charset), null);
                } else {
                    parameters.put(URLDecoder.decode(pair.substring(0, separator), charset),
                            URLDecoder.decode(pair.substring(separator + 1), charset));
                }
            }
        } catch (IllegalArgumentException e) {
            throw new UndecodableFormException(e);
        }
        return parameters;
    }

    /**
     * The Mule 3.8 listener's {@code HttpRequestParsingException} for a form-urlencoded body it cannot decode, raised
     * while the payload is bound and answered by {@link #rejectUndecodableForm}. It is checked and private so that
     * no other {@link IllegalArgumentException} of binding, such as the {@link InvalidMediaTypeException} of an
     * unparseable {@code Content-Type}, can be mistaken for it: those still reach the 500 of
     * {@code GlobalExceptionHandler}.
     */
    private static final class UndecodableFormException extends Exception {

        /** The exception carries no state beyond its message and cause. */
        private static final long serialVersionUID = 1L;

        /**
         * @param cause the {@link URLDecoder} failure on a malformed percent escape
         */
        UndecodableFormException(IllegalArgumentException cause) {
            super(FORM_DECODING_FAILURE, cause);
        }
    }
}
