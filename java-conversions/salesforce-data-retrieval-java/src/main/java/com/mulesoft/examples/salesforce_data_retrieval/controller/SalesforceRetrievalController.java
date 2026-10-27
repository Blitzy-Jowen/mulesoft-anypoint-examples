/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_data_retrieval.controller;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.mulesoft.examples.salesforce_data_retrieval.service.SalesforceRetrievalService;

/**
 * HTTP triggers of the two flows of {@code salesforce-id-retrieval.xml}, both on the
 * {@code HTTP_Listener_Configuration} listener ({@code 0.0.0.0:${http.port}}, xml:5), whose host and port are set in
 * {@code application.yml}:
 * <ul>
 *   <li>{@code showFormFlow}, {@code allowedMethods="GET" path="/"} (xml:8): {@link #showForm()};</li>
 *   <li>{@code salesforceDataRetrievalFlow}, {@code allowedMethods="POST" path="/"} (xml:26): {@link #query}.</li>
 * </ul>
 *
 * <p>The class only binds the request and returns the service's result. The Salesforce calls, the identifier
 * checks, the SOQL, the option list, the record text and the {@code Result: ...} log line belong to
 * {@link SalesforceRetrievalService}.
 *
 * <p>Neither handler method catches anything; each failure is answered by the first advice in order that claims it:
 * <ol>
 *   <li>{@code UpstreamExceptionHandler} (SC-9, highest precedence): an authentication failure that survived the
 *       re-login and retry → 502, throttling → 429, a timeout or connectivity failure → 503;</li>
 *   <li>{@code SalesforceRetrievalExceptionHandler}, scoped to this class: any other failure of {@link #query}
 *       becomes the original catch exception strategy's 200 {@code Invalid Salesforce query.} (xml:37-39), and any
 *       other failure of {@link #showForm()}, whose flow declares no strategy, the generic 500;</li>
 *   <li>{@code GlobalExceptionHandler} (SC-1, lowest precedence): the no-listener 404 for every other path, the
 *       405 for every method other than GET and POST on {@code /}, and the 500.</li>
 * </ol>
 * The one exception is a form body the Mule listener could not decode, which it answered itself before any flow ran;
 * {@link #rejectUndecodableForm} reproduces that 400.
 *
 * <p>No mapping declares {@code consumes} or {@code produces}: the listeners accepted any request Content-Type and
 * ignored {@code Accept}, so neither a 415 nor a 406 can arise. Each response presets its Content-Type, which makes
 * Spring write the body without consulting {@code Accept}. Stateless and thread-safe.
 *
 * <p>Two listener behaviours are left to captured fixtures rather than reproduced: the Mule listener compared
 * {@code allowedMethods} ignoring case, so it ran the flows for a lowercase {@code get} or {@code post}, which get the
 * 405 here; and it decoded the body of every request, so a GET carrying an undecodable form body got its 400, while
 * {@link #showForm()} never reads a body.
 */
@RestController
public class SalesforceRetrievalController {

    /** Content-Type of the form page, the {@code object-to-string-transformer mimeType="text/html"} of xml:21. */
    static final MediaType TEXT_HTML_UTF8 = new MediaType("text", "html", StandardCharsets.UTF_8);

    /** Content-Type of the query result text. */
    static final MediaType TEXT_PLAIN_UTF8 = new MediaType("text", "plain", StandardCharsets.UTF_8);

    /**
     * Body of the 400 the Mule 3.8 listener sent for a form body it could not URL-decode:
     * {@code HttpRequestToMuleEvent} raised {@code HttpRequestParsingException} with this message, and
     * {@code DefaultHttpListener} answered it with {@code BAD_REQUEST} before the flow ran.
     */
    static final String UNDECODABLE_FORM_MESSAGE = "Cannot decode x-www-form-urlencoded payload";

    /**
     * Text whose presence in the Content-Type made the Mule listener ({@code GrizzlyHttpRequestAdapter#getEntity})
     * treat the body as multipart, matched case-sensitively as there.
     */
    private static final String MULTIPART = "multipart";

    private static final String PAIR_SEPARATOR = "&";

    private static final char NAME_VALUE_SEPARATOR = '=';

    private final SalesforceRetrievalService service;

    /**
     * @param service the processor logic of both flows
     */
    public SalesforceRetrievalController(SalesforceRetrievalService service) {
        this.service = service;
    }

    /**
     * {@code showFormFlow} (xml:7-24): 200 {@code text/html;charset=UTF-8} with the query form, offering one option
     * per object Salesforce describes. The request body, if any, is not read.
     *
     * @return the rendered page
     * @throws Exception the service's failure, unchanged, for the advices
     */
    @GetMapping("/")
    public ResponseEntity<String> showForm() throws Exception {
        return ResponseEntity.ok().contentType(TEXT_HTML_UTF8).body(service.showForm());
    }

    /**
     * {@code salesforceDataRetrievalFlow} (xml:25-40): hands the posted form to
     * {@link SalesforceRetrievalService#query} and answers 200 {@code text/plain;charset=UTF-8} with the record text.
     *
     * <p>The form is the request body alone, as the Mule payload was: the query string never contributes, so
     * {@code POST /?object=user} with no form body reaches the service with an empty map. The body is read off the
     * servlet input stream (see {@link #parseForm}); servlet parameters are never consulted, because they merge the
     * query string into the form.
     *
     * @param request the query request
     * @return the record text
     * @throws Exception the service's or the binding's failure, unchanged, for the advices; a form body that cannot
     *                   be URL-decoded leaves as {@link UndecodableFormException} for {@link #rejectUndecodableForm}
     */
    @PostMapping("/")
    public ResponseEntity<String> query(HttpServletRequest request) throws Exception {
        Map<String, String> form = parseForm(request.getHeader(HttpHeaders.CONTENT_TYPE), request);
        return ResponseEntity.ok().contentType(TEXT_PLAIN_UTF8).body(service.query(form));
    }

    /**
     * Builds the payload the Mule 3.8.0 HTTP listener handed the POST flow, which the flow's MEL read as a map
     * ({@code payload.field}, {@code payload.object}, ...). The steps follow {@code GrizzlyHttpRequestAdapter#getEntity},
     * {@code HttpRequestToMuleEvent#transform} and {@code HttpParser#decodeString}:
     * <ol>
     *   <li>No Content-Type, a blank one, or one containing {@code multipart}: an empty map. Mule handed the flow no
     *       map here (a stream or {@code NullPayload}), so its MEL failed; the empty map fails the service the same
     *       way, on its absent {@code field}.</li>
     *   <li>The Content-Type is parsed with {@link MediaType#parseMediaType}. An unparseable value, or a charset the
     *       JVM does not know, raises {@code InvalidMediaTypeException} inside {@link #query}, which the project
     *       advice answers with {@code Invalid Salesforce query.}; the Mule listener's own 400 carried Guava parser
     *       text that cannot be reproduced byte for byte, so that reply is a captured-fixture verification point.</li>
     *   <li>A type and subtype other than {@code application/x-www-form-urlencoded}, compared ignoring case: an empty
     *       map, and the body is not read.</li>
     *   <li>The body bytes are read as UTF-8 text, standing for the JVM default charset Mule's {@code IOUtils} used,
     *       and a blank text gives an empty map.</li>
     *   <li>The text is split on {@code &} with {@link String#split} semantics (interior empty pairs kept, trailing
     *       ones dropped) and each pair at its first {@code =}. Name and value are URL-decoded ({@code +} becomes a
     *       space) with the Content-Type's charset, UTF-8 when it names none.</li>
     *   <li>A name without {@code =} maps to {@code null}, as {@code addParam(queryParams, pair, null, encoding)} did,
     *       so a bare {@code field} fails the service as {@code payload.field.isEmpty()} failed the original. A
     *       repeated name keeps its first position and its last value, because Mule's {@code ParameterMap#get}
     *       returned {@code values.getLast()}.</li>
     * </ol>
     * A malformed percent escape raises {@link UndecodableFormException}, the listener's
     * {@code HttpRequestParsingException}.
     *
     * @param contentType the request's {@code Content-Type} header, or {@code null}
     * @param request     the request whose body is read
     * @return the decoded form in first-occurrence order; empty when the body is not a form
     * @throws IOException when reading the body fails
     */
    static Map<String, String> parseForm(String contentType, HttpServletRequest request) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        if (!StringUtils.hasText(contentType) || contentType.contains(MULTIPART)) {
            return form;
        }
        MediaType mediaType = MediaType.parseMediaType(contentType);
        if (!mediaType.equalsTypeAndSubtype(MediaType.APPLICATION_FORM_URLENCODED)) {
            return form;
        }
        Charset charset = mediaType.getCharset() != null ? mediaType.getCharset() : StandardCharsets.UTF_8;
        String text = new String(StreamUtils.copyToByteArray(request.getInputStream()), StandardCharsets.UTF_8);
        if (!StringUtils.hasText(text)) {
            return form;
        }
        try {
            for (String pair : text.split(PAIR_SEPARATOR)) {
                int idx = pair.indexOf(NAME_VALUE_SEPARATOR);
                String name = idx >= 0 ? pair.substring(0, idx) : pair;
                String raw = idx >= 0 ? pair.substring(idx + 1) : null;
                form.put(URLDecoder.decode(name, charset), raw == null ? null : URLDecoder.decode(raw, charset));
            }
        } catch (IllegalArgumentException ex) {
            throw new UndecodableFormException(ex);
        }
        return form;
    }

    /**
     * Answers a form body the listener could not decode as {@code DefaultHttpListener} did, before the flow ran:
     * 400 with the standard reason phrase {@code Bad Request}, {@link #UNDECODABLE_FORM_MESSAGE} as the body with its
     * {@code Content-Length}, and no Content-Type. The service is never called. Declared on this class, so it is
     * tried before every advice; the project advice would otherwise turn the failure into
     * {@code Invalid Salesforce query.}. The body is written to the servlet stream and committed, so no message
     * converter adds a Content-Type.
     *
     * @param ex       the decoding failure
     * @param response the response the 400 is written to
     * @throws IOException when writing the reply fails
     */
    @ExceptionHandler(UndecodableFormException.class)
    public void rejectUndecodableForm(UndecodableFormException ex, HttpServletResponse response) throws IOException {
        byte[] body = ex.getMessage().getBytes(StandardCharsets.US_ASCII);
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }

    /**
     * The Mule 3.8 listener's {@code HttpRequestParsingException} for a form body holding a malformed percent escape.
     * It extends {@link IllegalArgumentException}, the type {@link URLDecoder} raises, and only {@link #parseForm}
     * throws it, so {@link #rejectUndecodableForm} answers no other failure.
     */
    static final class UndecodableFormException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        /**
         * @param cause the {@link URLDecoder} failure
         */
        UndecodableFormException(IllegalArgumentException cause) {
            super(UNDECODABLE_FORM_MESSAGE, cause);
        }
    }

    /**
     * Rejects {@code HEAD} and {@code OPTIONS} on {@code /}, which neither original listener accepted, with the 405
     * of {@code GlobalExceptionHandler}, {@code Method not allowed for endpoint: /}. Without this mapping Spring MVC
     * would answer {@code HEAD} through {@link SalesforceRetrievalController#showForm()}, calling Salesforce, and
     * {@code OPTIONS} itself, both with 200. An explicit {@code HEAD} mapping outranks the implicit {@code HEAD} match
     * of the GET mapping, and Boot dispatches {@code OPTIONS} to handlers. Every other method outside GET and POST is
     * rejected by the handler mapping itself.
     *
     * <p>The mapping lives on its own controller because a method of {@link SalesforceRetrievalController} would be
     * answered by {@code SalesforceRetrievalExceptionHandler}, scoped to that class, with a 500. This class is not
     * assignable to it, so SC-1 answers. Component scanning registers it like any top-level controller.
     */
    @RestController
    public static class MethodNotAllowedGuard {

        /** The methods the two {@code /} listeners accept together. */
        private static final List<String> LISTENER_METHODS = List.of("GET", "POST");

        /**
         * @param request the rejected request, whose method is reported
         * @throws HttpRequestMethodNotSupportedException always
         */
        @RequestMapping(value = "/", method = {RequestMethod.HEAD, RequestMethod.OPTIONS})
        public void reject(HttpServletRequest request) throws HttpRequestMethodNotSupportedException {
            throw new HttpRequestMethodNotSupportedException(request.getMethod(), LISTENER_METHODS);
        }
    }
}
