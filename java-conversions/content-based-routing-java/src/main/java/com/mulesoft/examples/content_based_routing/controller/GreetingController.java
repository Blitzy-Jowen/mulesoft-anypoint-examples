/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.content_based_routing.controller;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.mulesoft.examples.content_based_routing.service.LanguageRoutingService;

import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP trigger of flow {@code content-based-routingFlow}
 * (content-based-routing/src/main/app/content-based-routing.xml:5): the listener on {@code /}, whose filter,
 * routing choice and loggers run in {@link LanguageRoutingService}.
 *
 * <p>The controller only binds input and writes output. The listener's payload is built from the request as the
 * Mule 3.8 listener built it (see {@link #listenerPayload}): {@code null} for a request without a body or with a
 * multipart body, the decoded parameter map for a form-urlencoded body, and otherwise the body as text. The flow
 * variable {@code language} is the decoded {@code language} query parameter as the Mule listener exposed it in
 * {@code http.query.params}.
 *
 * <p>Every reply of the flow is a 200 with a {@code Content-Length} and no {@code Content-Type}: the flow's
 * {@code set-payload} processors declare no MIME type, and the Mule listener sent no Content-Type for such a
 * payload. The response is written to the servlet response directly, so no message converter adds one.
 *
 * <p>The listener declares no {@code allowedMethods}, so every method is served. The match is exact: any other
 * path, including the icon path browsers request, falls through to the no-listener 404 of
 * {@code GlobalExceptionHandler}. A form-urlencoded body the listener cannot decode is answered here, before the
 * flow runs, with the listener's own 400 (see {@link #rejectUndecodableForm}); every other exception reaches the
 * 500 of {@code GlobalExceptionHandler} and none is handled here.
 */
@RestController
public class GreetingController {

    private static final String LANGUAGE_PARAMETER = "language";

    private static final String PAIR_SEPARATOR = "&";

    private static final String NAME_VALUE_SEPARATOR = "=";

    /** The text whose presence in {@code Content-Type} made the Mule listener parse the body as multipart. */
    private static final String MULTIPART = "multipart";

    /** Message of the Mule listener's failure to decode a form-urlencoded body. */
    private static final String FORM_DECODING_FAILURE = "Cannot decode x-www-form-urlencoded payload";

    private final LanguageRoutingService languageRoutingService;

    public GreetingController(LanguageRoutingService languageRoutingService) {
        this.languageRoutingService = languageRoutingService;
    }

    /**
     * Answers {@code /} for every method except {@code OPTIONS}; a {@code HEAD} response carries the headers without
     * the body.
     *
     * @param request  the request, whose body is the listener's payload and whose raw query string supplies
     *                 {@code language}
     * @param response the response the reply is written to
     * @throws IOException when reading the request body or writing the reply fails
     */
    @RequestMapping("/")
    public void greeting(HttpServletRequest request, HttpServletResponse response) throws IOException {
        respond(request, response);
    }

    /**
     * Answers {@code OPTIONS /} like every other method. A mapping without methods deliberately does not match
     * {@code OPTIONS}, which Spring MVC would otherwise answer itself with an {@code Allow} header and no body; the
     * Mule listener ran the flow for {@code OPTIONS} too.
     *
     * @param request  the request, whose body is the listener's payload and whose raw query string supplies
     *                 {@code language}
     * @param response the response the reply is written to
     * @throws IOException when reading the request body or writing the reply fails
     */
    @RequestMapping(value = "/", method = RequestMethod.OPTIONS)
    public void greetingOptions(HttpServletRequest request, HttpServletResponse response) throws IOException {
        respond(request, response);
    }

    /**
     * Runs the flow and writes its result: the reply as UTF-8 bytes with their {@code Content-Length}, or, when the
     * favicon filter rejects the payload, a committed empty 200, as the Mule listener answered the flow's null
     * payload. Committing the empty reply matters: {@code FrameworkServlet} completes an uncommitted {@code TRACE}
     * reply with the servlet's {@code message/http} echo of the request and an uncommitted {@code OPTIONS} reply
     * with an {@code Allow} header. A form-urlencoded body the listener cannot decode is answered with the committed
     * 400 of {@link #rejectUndecodableForm}, and the flow does not run. Otherwise nothing is written before the
     * service returns, so every exception still finds an uncommitted response for the 500 of
     * {@code GlobalExceptionHandler}.
     *
     * @param request  the request, whose body is the listener's payload and whose raw query string supplies
     *                 {@code language}
     * @param response the response the reply is written to
     * @throws IOException when reading the request body or writing the reply fails
     */
    private void respond(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Object payload;
        try {
            payload = listenerPayload(request);
        } catch (UndecodableFormException e) {
            rejectUndecodableForm(response, e);
            return;
        }
        Optional<String> reply = languageRoutingService.reply(payload, languageFrom(request.getQueryString()));
        response.setStatus(HttpServletResponse.SC_OK);
        if (reply.isEmpty()) {
            response.setContentLength(0);
            response.flushBuffer();
            return;
        }
        byte[] bytes = reply.get().getBytes(StandardCharsets.UTF_8);
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
     * {@link #respond} answers it with the listener's 400.
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
     * Reads {@code language} from the raw query string the way the Mule 3.8 listener built and read
     * {@code http.query.params}: {@code org.mule.module.http.internal.HttpParser#decodeQueryString} splits the
     * string on {@code &} and each pair on its first {@code =}, URL-decodes name and value as UTF-8, and gives a
     * pair without {@code =} a {@code null} value; {@code org.mule.module.http.internal.ParameterMap#get} then
     * returns {@code values.getLast()}, so of a repeated name the last pair wins.
     *
     * <p>{@code @RequestParam} and {@link HttpServletRequest#getParameter} are not used: the first joins repeated
     * values with commas, the second returns the first value and also reads form fields of the body, so either
     * could select a different routing branch than the original.
     *
     * <p>Undertow already rejects a query string holding a malformed percent escape with an empty 400 while parsing
     * the request, so none reaches this method; should one arrive, the {@link IllegalArgumentException} of
     * {@link URLDecoder} is left to the 500 of {@code GlobalExceptionHandler} rather than caught here.
     *
     * @param queryString the raw, undecoded query string, or {@code null} when the request has none
     * @return the decoded value of the last {@code language} pair: {@code ""} for {@code language=}, {@code null}
     *         for a bare {@code language} or when no pair is named {@code language}
     * @throws IllegalArgumentException if a name, or the value of a {@code language} pair, holds a malformed
     *                                  percent escape
     */
    private static String languageFrom(String queryString) {
        if (queryString == null || queryString.isEmpty()) {
            return null;
        }
        String language = null;
        for (String pair : queryString.split(PAIR_SEPARATOR)) {
            if (pair.isEmpty()) {
                continue;
            }
            int separator = pair.indexOf(NAME_VALUE_SEPARATOR);
            String rawName = separator < 0 ? pair : pair.substring(0, separator);
            if (LANGUAGE_PARAMETER.equals(URLDecoder.decode(rawName, StandardCharsets.UTF_8))) {
                language = separator < 0
                        ? null
                        : URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            }
        }
        return language;
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
