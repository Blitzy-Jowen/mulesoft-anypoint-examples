/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Answers a request whose path holds a malformed percent-escape, a {@code %} not followed by two hexadecimal digits
 * as in {@code /FIM/x%zz}, {@code /FIM/x%} or {@code /oauth%zz}, with {@code 400 Bad Request} and an empty body,
 * before it is dispatched.
 *
 * <p>{@code server.undertow.decode-url} is off, so that the {@code /FIM/*} flow forwards the request URI as sent, and
 * Undertow therefore no longer rejects such a target itself. The {@code DispatcherServlet} then parses the path for
 * handler mapping, percent-decoding each segment, and that parse fails on the escape before any handler or
 * {@code @ControllerAdvice} runs: the failure leaves the servlet, and Undertow answers with its HTML 500 page. This
 * filter gives instead the listener rejection Undertow gives such a target when it percent-decodes the URL itself,
 * which a listener with URL decoding on, such as {@code mule-component-bindings-java}'s, answers: status 400,
 * {@code Content-Length: 0}, no {@code Content-Type} and no body. The status is set rather than sent through
 * {@code sendError}, which would make Undertow write an HTML error page.
 *
 * <p>The filter runs before every other filter, for every path and method, so such a request reaches no controller
 * and no upstream. Only the path is checked: nothing parses the query string before dispatch, and the {@code /FIM/*}
 * client percent-encodes a malformed escape in the query before forwarding it. Every other request passes to the
 * chain unchanged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InvalidPercentEncodingFilter extends OncePerRequestFilter {

    /**
     * Rejects the request when its path holds a malformed percent-escape, otherwise passes it on unchanged.
     *
     * @param request  the inbound request, whose undecoded {@link HttpServletRequest#getRequestURI()} is checked
     * @param response the response the rejection is written to
     * @param chain    the rest of the filter chain and the {@code DispatcherServlet}
     * @throws ServletException when a later filter or the servlet fails
     * @throws IOException      when the rejection cannot be written, or a later filter or the servlet fails
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!hasMalformedPercentEncoding(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentLength(0);
        // Committed here, so nothing after this filter can add a body or a header to the rejection.
        response.flushBuffer();
    }

    /** Whether {@code path} holds a {@code %} not followed by two ASCII hexadecimal digits. */
    private static boolean hasMalformedPercentEncoding(String path) {
        if (path == null) {
            return false;
        }
        for (int i = path.indexOf('%'); i >= 0; i = path.indexOf('%', i + 1)) {
            if (i + 2 >= path.length() || !isHexDigit(path.charAt(i + 1)) || !isHexDigit(path.charAt(i + 2))) {
                return true;
            }
        }
        return false;
    }

    /** ASCII hexadecimal digits only: {@link Character#digit} would also accept other scripts' digits. */
    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
    }
}
