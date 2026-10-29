/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.login_form_using_the_http_connector.exception;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.undertow.servlet.handlers.ServletRequestContext;
import io.undertow.util.Headers;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.mulesoft.examples.login_form_using_the_http_connector.mapper.ParseTemplateRenderer;
import com.mulesoft.examples.login_form_using_the_http_connector.service.LoginService;

/**
 * The {@code catch-exception-strategy} of flow {@code DoLoginFlow}
 * ({@code login-form-using-the-http-connector.xml}:27-30): a rejected or failed {@code POST /login} is
 * answered with status 403 ({@code set-property http.status=403}, xml:28) and {@code login/loginFailure.html}
 * rendered with the posted username ({@code parse-template}, xml:29).
 *
 * <p>The branch set no Content-Type itself (only the success path does, xml:25), so the Mule 3.8 listener derived
 * one from the message's data type, which still held the request's media type and charset: the page goes out
 * with the request's {@code type/subtype; charset=<charset>} and is encoded in that charset (see
 * {@link #handleLoginFailed}). It set no {@code http.reason} either, so the status line carries an empty reason
 * phrase: {@code HTTP/1.1 403 }.
 *
 * <p>The advice runs ahead of {@link GlobalExceptionHandler} and claims {@link LoginService.LoginFailedException}
 * alone, the only failure the original branch caught; every other exception, such as a {@code /requesterLogin}
 * upstream failure, still reaches the generic 500.
 */
@RestControllerAdvice
@Order(0)
public class LoginExceptionHandler {

    /** Template of the catch branch (xml:29). */
    static final String FAILURE_TEMPLATE = "login/loginFailure.html";

    /** The only expression of {@code loginFailure.html} (line 13). */
    static final String USERNAME_EXPRESSION = "#[payload.username]";

    /** Form field holding the username; the payload was still the posted form map in the catch branch. */
    static final String USERNAME_FIELD = "username";

    private final ParseTemplateRenderer renderer;

    /**
     * @param renderer SC-14 template renderer
     */
    public LoginExceptionHandler(ParseTemplateRenderer renderer) {
        this.renderer = renderer;
    }

    /**
     * Writes 403 with an empty reason phrase ({@code HTTP/1.1 403 }, see {@link #omitReasonPhrase}) and the
     * rendered failure page. A missing username renders as the text {@code null}, as MEL rendered a null
     * {@code payload.username}.
     *
     * <p>The Content-Type and the page's encoding follow the request's {@code Content-Type}, as the Mule 3.8
     * listener derived them for a branch that set none:
     * <ul>
     *   <li>a media type → {@code <type>/<subtype>; charset=<charset>}, type and subtype lower-cased, other
     *       parameters dropped, the charset being the one the request named, under its canonical name, or the
     *       listener's default {@code UTF-8} when it named none; the page is encoded in that charset, so
     *       {@code charset=ISO-8859-1} yields one byte per Latin-1 character;</li>
     *   <li>no {@code Content-Type}, or {@code *}/{@code *} → no Content-Type; the page is encoded in the charset
     *       a {@code *}/{@code *} request named, otherwise UTF-8;</li>
     *   <li>a value that is not a media type, or names a charset the JVM cannot encode → no Content-Type and a
     *       UTF-8 page. The original listener rejected such requests before the flow ran; this keeps the answer
     *       deterministic.</li>
     * </ul>
     *
     * <p>The page is rendered and encoded before the response is touched, so a rendering failure leaves the
     * response uncommitted for the next resolver.
     *
     * @param ex       the rejected or failed login, carrying the posted form
     * @param request  the rejected request, whose {@code Content-Type} shapes the response's
     * @param response the response the page is written to
     * @throws IOException if the body cannot be written
     */
    @ExceptionHandler(LoginService.LoginFailedException.class)
    public void handleLoginFailed(LoginService.LoginFailedException ex, HttpServletRequest request,
                                  HttpServletResponse response) throws IOException {
        Map<String, String> form = ex.form();
        Object username = form == null ? null : form.get(USERNAME_FIELD);
        Map<String, Object> values = new HashMap<>();
        values.put(USERNAME_EXPRESSION, String.valueOf(username));
        String body = renderer.render(FAILURE_TEMPLATE, values);
        PageEncoding encoding = PageEncoding.of(request.getContentType());
        byte[] bytes = body.getBytes(encoding.charset());

        // setStatus, not sendError, so the container's error page cannot replace the template. The servlet
        // response is written directly rather than returned as a ResponseEntity, which would either negotiate a
        // Content-Type the listener never sent or route this one through setContentType. The body goes through
        // the output stream, never getWriter(), which would add a charset of its own.
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        omitReasonPhrase();
        if (encoding.contentType() != null) {
            setContentTypeVerbatim(response, encoding.contentType());
        }
        response.setContentLength(bytes.length);
        ServletOutputStream out = response.getOutputStream();
        out.write(bytes);
        out.flush();
    }

    /**
     * Sends the status line with an empty reason phrase. The branch set {@code http.status} without
     * {@code http.reason}, so the Mule 3.8 listener wrote {@code HTTP/1.1 403 }: code, one space, no phrase. Only
     * Undertow lets the phrase be set, through the exchange, which writes a non-null phrase verbatim and falls back
     * to the standard one ({@code Forbidden}) only for {@code null}. Called after the status is set and before any
     * byte is written. Without an Undertow request on the calling thread (MockMvc, another container) the
     * container's own phrase stays.
     */
    private static void omitReasonPhrase() {
        ServletRequestContext context = ServletRequestContext.current();
        if (context != null) {
            context.getExchange().setReasonPhrase("");
        }
    }

    /**
     * Sets {@code Content-Type} to exactly {@code value}. Undertow re-serialises a value passed to
     * {@code setContentType} or {@code setHeader}, turning {@code a; charset=UTF-8} into {@code a;charset=UTF-8},
     * so on Undertow the header is put on the exchange directly, keeping the space the Mule listener wrote.
     * Without an Undertow request on the calling thread (MockMvc, another container) the servlet response is used.
     *
     * @param response the response being written, not yet committed
     * @param value    the exact header value
     */
    private static void setContentTypeVerbatim(HttpServletResponse response, String value) {
        ServletRequestContext context = ServletRequestContext.current();
        if (context != null) {
            context.getExchange().getResponseHeaders().put(Headers.CONTENT_TYPE, value);
        } else {
            response.setContentType(value);
        }
    }

    /**
     * The failure page's {@code Content-Type} header value, {@code null} for none, and the charset its text is
     * encoded in, derived from the request's {@code Content-Type} as {@link #handleLoginFailed} describes.
     *
     * @param contentType the response's {@code Content-Type} value, or {@code null} to send none
     * @param charset     the charset the page is encoded in
     */
    private record PageEncoding(String contentType, Charset charset) {

        /** The listener's default encoding, used when the request names no charset. */
        private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

        /** No Content-Type and a UTF-8 page. */
        private static final PageEncoding NONE = new PageEncoding(null, DEFAULT_CHARSET);

        /**
         * @param requestContentType the request's {@code Content-Type} value, or {@code null} when it sent none
         * @return the page's Content-Type and charset
         */
        static PageEncoding of(String requestContentType) {
            if (requestContentType == null) {
                return NONE;
            }
            MediaType mediaType;
            Charset requested;
            try {
                mediaType = MediaType.parseMediaType(requestContentType);
                requested = mediaType.getCharset();
            } catch (IllegalArgumentException e) {
                // InvalidMediaTypeException, UnsupportedCharsetException and IllegalCharsetNameException all
                // extend IllegalArgumentException.
                return NONE;
            }
            Charset charset = requested == null ? DEFAULT_CHARSET : requested;
            if (!charset.canEncode()) {
                return NONE;
            }
            if (mediaType.isWildcardType() && mediaType.isWildcardSubtype()) {
                // The listener wrote no Content-Type for the "any" media type.
                return new PageEncoding(null, charset);
            }
            return new PageEncoding(mediaType.getType() + "/" + mediaType.getSubtype() + "; charset=" + charset.name(),
                    charset);
        }
    }
}
