/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.track_a_custom_business_event.config;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Hides a request charset that names no usable {@link Charset}, or that Undertow cannot read, so that the error a
 * request with such a {@code Content-Type} raises reaches the caller as {@code GlobalExceptionHandler} answers it.
 *
 * <p>{@code application.yml} disables Spring Boot's character-encoding filter, because the Mule listener took the
 * request charset from the {@code Content-Type} alone. The servlet request's {@code getCharacterEncoding()} is
 * therefore Undertow's reading of the raw {@code charset} parameter of the {@code Content-Type}, whatever it holds:
 * {@code bad[charset]} for {@code charset=bad[charset]}, the empty string for {@code charset=""}, and an
 * {@link IndexOutOfBoundsException} thrown by Undertow itself for a {@code charset=} without a value at the end of
 * the header, as in {@code application/json;charset=}. Spring MVC rejects such a {@code Content-Type} while it binds
 * the request body, with its own message such as
 * {@code Invalid mime type "application/json; charset=bad[charset]": bad[charset]}, and {@code GlobalExceptionHandler}
 * answers with its 500 {@code { "message": "<exception message>" }}, or with the no-listener 404 for a path no
 * handler serves. Writing either answer, however, decodes the last segment of the request path with the request's
 * character encoding, where Spring checks whether the response needs a {@code Content-Disposition} header, and
 * Spring's {@code UrlPathHelper} tolerates a charset that is unsupported ({@code UnsupportedCharsetException}) but
 * neither one whose name is illegal ({@code IllegalCharsetNameException}) nor a failure to read it. The advice's
 * answer then fails, Spring's default exception resolver answers 415 and Undertow sends its HTML error page instead.
 *
 * <p>A request whose charset is illegal, unsupported or unreadable is passed on as a view whose
 * {@code getCharacterEncoding()} returns {@code null}: the request declares no charset that can be used. Its headers,
 * {@code Content-Type} included, are untouched, so Spring rejects that {@code Content-Type} with the same message as
 * before and the answer of {@code GlobalExceptionHandler} is written. A request that declares a usable charset, or
 * none, is passed on as it is. The filter runs first, so every other filter and the dispatcher see the same request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCharsetFilter extends OncePerRequestFilter {

    /**
     * Continues the chain with {@code request}, or with a {@link NoCharsetRequest} view of it when it declares a
     * charset that is no usable charset or cannot be read.
     *
     * @param request  the current request
     * @param response the current response, passed on unchanged
     * @param chain    the rest of the filter chain
     * @throws ServletException when the rest of the chain fails with it
     * @throws IOException      when the rest of the chain fails with it
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (declaresUnusableCharset(request)) {
            chain.doFilter(new NoCharsetRequest(request), response);
        } else {
            chain.doFilter(request, response);
        }
    }

    /**
     * Whether {@code request} declares a character encoding that cannot be used.
     *
     * @param request the current request
     * @return {@code true} when the encoding is set but {@link #isUsable(String) unusable}, or when reading it fails
     *         with the {@link IndexOutOfBoundsException} Undertow throws for a {@code charset=} without a value at the
     *         end of the {@code Content-Type}; {@code false} when the request declares a usable encoding or none
     */
    private static boolean declaresUnusableCharset(HttpServletRequest request) {
        String encoding;
        try {
            encoding = request.getCharacterEncoding();
        } catch (IndexOutOfBoundsException unreadable) {
            return true;
        }
        return encoding != null && !isUsable(encoding);
    }

    /**
     * Whether {@code encoding} names a charset this JVM supports.
     *
     * @param encoding a charset name as the request declares it, never {@code null}
     * @return {@code false} when the name is illegal, the empty name included, or names no supported charset
     */
    private static boolean isUsable(String encoding) {
        try {
            return Charset.isSupported(encoding);
        } catch (IllegalCharsetNameException illegal) {
            return false;
        }
    }

    /**
     * A request that declares no character encoding: {@code getCharacterEncoding()} returns {@code null} until an
     * encoding is set through {@link #setCharacterEncoding(String)} and the wrapped request takes it, as the servlet
     * API lets a set encoding override the {@code Content-Type}'s; everything else is the wrapped request's. A set
     * the wrapped request ignores, as Undertow does once the body is being read (the servlet API gives such a set no
     * effect), leaves the wrapped request reporting the {@code Content-Type}'s unusable charset, or failing to read
     * it, so this view then still reports none.
     */
    static final class NoCharsetRequest extends HttpServletRequestWrapper {

        private boolean encodingSet;

        /**
         * @param request the request whose declared charset is hidden
         */
        NoCharsetRequest(HttpServletRequest request) {
            super(request);
        }

        /**
         * Reads the wrapped request's encoding once, and only after an encoding has been set on this view.
         *
         * @return {@code null} until an encoding has been set on this view; from then on the wrapped request's
         *         encoding when it is {@link #isUsable(String) usable}, and {@code null} when the wrapped request
         *         ignored the set and still reports an unusable charset, none, or fails to read it with an
         *         {@link IndexOutOfBoundsException}
         */
        @Override
        public String getCharacterEncoding() {
            if (!encodingSet) {
                return null;
            }
            String encoding;
            try {
                encoding = super.getCharacterEncoding();
            } catch (IndexOutOfBoundsException unreadable) {
                return null;
            }
            return encoding != null && isUsable(encoding) ? encoding : null;
        }

        /**
         * Sets the encoding on the wrapped request, whose encoding this view reports from then on while it is usable.
         * A wrapped request that ignores the set, as Undertow does once the body is being read, keeps reporting the
         * {@code Content-Type}'s unusable charset, so this view then still reports none.
         *
         * @param encoding the name of the encoding to use
         * @throws UnsupportedEncodingException when the wrapped request rejects {@code encoding}; this view then
         *                                      still reports none
         */
        @Override
        public void setCharacterEncoding(String encoding) throws UnsupportedEncodingException {
            super.setCharacterEncoding(encoding);
            encodingSet = true;
        }
    }
}
