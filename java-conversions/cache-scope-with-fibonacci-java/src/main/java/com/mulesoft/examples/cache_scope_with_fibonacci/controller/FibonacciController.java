/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.cache_scope_with_fibonacci.controller;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.mulesoft.examples.cache_scope_with_fibonacci.service.FibonacciService;

/**
 * Replaces the HTTP listener of flow {@code cache-exampleFlow1}
 * (cache-scope-with-fibonacci/src/main/app/cache-scope.xml:40,44) and the Mule HTTP transport's conversion of the
 * flow result into the response. It only binds the request and writes the reply; the favicon filter, the
 * {@code n} limit and the calculation belong to {@link FibonacciService}.
 *
 * <p>The listener's payload is built from the request as the Mule 3.8 listener built it (see
 * {@link #listenerPayload}): {@code null} for a request without a body or with a multipart body, the decoded
 * parameter map for a form-urlencoded body, and otherwise the body as text.
 *
 * <p>The listener declares no {@code allowedMethods}, so every method runs the flow: {@code OPTIONS} reaches
 * {@link #fibonacciOptions}, every other method {@link #fibonacci}, and both answer alike. Host {@code localhost}
 * and port {@code ${http.port}} come from {@code application.yml}. A form-urlencoded body the listener cannot
 * decode is answered here, before the flow runs, with the listener's own 400 (see {@link #rejectUndecodableForm}).
 * No other exception is caught here: the flow has no exception strategy, so they reach the generic 500 of
 * {@code exception/GlobalExceptionHandler}.
 */
@RestController
public class FibonacciController {

    /** Separator of the name/value pairs of a form-urlencoded body. */
    private static final String PAIR_SEPARATOR = "&";

    /** Separator of a form pair's name from its value; the first occurrence in the pair splits it. */
    private static final String NAME_VALUE_SEPARATOR = "=";

    /** The text whose presence in {@code Content-Type} made the Mule listener parse the body as multipart. */
    private static final String MULTIPART = "multipart";

    /** Message of the Mule listener's failure to decode a form-urlencoded body. */
    private static final String FORM_DECODING_FAILURE = "Cannot decode x-www-form-urlencoded payload";

    private final FibonacciService fibonacciService;

    /**
     * @param fibonacciService the port of the flow's processors after its listener
     */
    public FibonacciController(FibonacciService fibonacciService) {
        this.fibonacciService = fibonacciService;
    }

    /**
     * Serves {@code /fibonacci} for every method except {@code OPTIONS}, which {@link #fibonacciOptions} hands
     * here.
     *
     * <p>Input: the listener's payload, which {@link #listenerPayload} builds from the body (the payload the favicon
     * wildcard-filter tests), the query parameters {@code n} and {@code nocache} (the original
     * {@code http.query.params}) and the {@code nocache} request header (the inbound property the cache scope's
     * filter reads). Output: status 200, the {@code cost} header when the reply carries a cost (the original
     * outbound property {@code cost}), and the reply text as UTF-8 bytes with an explicit Content-Length. No
     * Content-Type is set, because the original reply carried none (a Tier 2A verification point).
     *
     * <p>The payload is built first, as the Mule listener built the message before the flow ran: a form-urlencoded
     * body it cannot decode is answered with the committed 400 of {@link #rejectUndecodableForm}, and the flow does
     * not run. Otherwise nothing is written before the service returns, so every exception still finds an
     * uncommitted response for the 500 of {@code GlobalExceptionHandler}.
     *
     * @param request  the HTTP request
     * @param response the HTTP response, written completely by this method
     * @throws IOException when the request body cannot be read or the response cannot be written
     */
    @RequestMapping("/fibonacci")
    public void fibonacci(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Object payload;
        try {
            payload = listenerPayload(request);
        } catch (UndecodableFormException e) {
            rejectUndecodableForm(response, e);
            return;
        }

        // Parsed from the raw query string: the servlet parameter API would also merge the fields of a
        // form-encoded body, which belong to the payload alone.
        Map<String, String> query = parseQuery(request.getQueryString());
        String n = query.get("n");
        boolean nocacheQuery = query.get("nocache") != null;
        boolean nocacheHeader = request.getHeader("nocache") != null;

        FibonacciService.FibonacciReply reply = fibonacciService.handle(n, payload, nocacheHeader, nocacheQuery);

        response.setStatus(HttpServletResponse.SC_OK);
        if (reply.cost() != null) {
            response.setHeader("cost", String.valueOf(reply.cost()));
        }
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        response.setContentLength(bytes.length);
        ServletOutputStream out = response.getOutputStream();
        out.write(bytes);
        out.flush();
    }

    /**
     * Serves {@code OPTIONS /fibonacci} like every other method. A mapping without methods deliberately does not
     * match {@code OPTIONS}, which Spring MVC would otherwise answer itself with an {@code Allow} header and no
     * body; the Mule listener ran the flow for {@code OPTIONS} too.
     *
     * @param request  the HTTP request
     * @param response the HTTP response, written completely by {@link #fibonacci}
     * @throws IOException when the request body cannot be read or the response cannot be written
     */
    @RequestMapping(value = "/fibonacci", method = RequestMethod.OPTIONS)
    public void fibonacciOptions(HttpServletRequest request, HttpServletResponse response) throws IOException {
        fibonacci(request, response);
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
     * Builds the payload the Mule 3.8 listener handed the flow, as {@code GrizzlyHttpRequestAdapter#getEntity} and
     * {@code HttpRequestToMuleEvent#transform} built it, in this order:
     * <ol>
     *   <li>a {@code Content-Type} containing {@code multipart}: {@code null}, the original's {@code NullPayload},
     *       because the listener moved the parts to inbound attachments; the body is not read;</li>
     *   <li>an empty body: {@code null}, the {@code NullPayload} of an empty entity;</li>
     *   <li>{@code application/x-www-form-urlencoded}: the parameter map {@link #formParameters} decodes;</li>
     *   <li>any other body: its text in the charset the {@code Content-Type} declares, UTF-8 when it declares
     *       none.</li>
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
     * {@link #fibonacci} answers it with the listener's 400.
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
     * Decodes a raw query string into its parameters, in order of first appearance.
     *
     * <p>Segments are separated by {@code &}, and empty segments are skipped. Each segment splits at its first
     * {@code =}: the value is everything after it, further {@code =} included. A bare name without {@code =} maps to
     * null. Names and values are URL-decoded as UTF-8. When a name occurs more than once the last occurrence wins,
     * as Mule's {@code ParameterMap.get} returned it, so a later bare name yields null.
     *
     * @param rawQuery the undecoded query string, or null when the request has none
     * @return the parameters; empty for a null or empty query string
     * @throws IllegalArgumentException when a name or value holds a malformed percent escape
     */
    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return parameters;
        }
        for (String segment : rawQuery.split("&")) {
            if (segment.isEmpty()) {
                continue;
            }
            int separator = segment.indexOf('=');
            String name;
            String value;
            if (separator < 0) {
                name = URLDecoder.decode(segment, StandardCharsets.UTF_8);
                value = null;
            } else {
                name = URLDecoder.decode(segment.substring(0, separator), StandardCharsets.UTF_8);
                value = URLDecoder.decode(segment.substring(separator + 1), StandardCharsets.UTF_8);
            }
            // Mule's ParameterMap kept every value of a name and its get returned the last, so each occurrence
            // replaces the previous one, a bare name's null included: "?n=5&n" yields null, "?n&n=5" yields "5".
            parameters.put(name, value);
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
