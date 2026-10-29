/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_data_retrieval.config;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

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
 * Matches the request method against the {@code /} listeners' {@code allowedMethods} the way the Mule 3.8 HTTP
 * listener did, ignoring case.
 *
 * <p>The original declares two listeners on path {@code /}: {@code showFormFlow} with {@code allowedMethods="GET"}
 * and {@code salesforceDataRetrievalFlow} with {@code allowedMethods="POST"}
 * (salesforce-data-retrieval/src/main/app/salesforce-id-retrieval.xml:8,26). Mule's {@code MethodRequestMatcher}
 * lower-cases the configured methods and the request method before comparing them, so {@code get}, {@code Post} or
 * {@code gEt} ran a flow exactly as {@code GET} and {@code POST} did. Spring MVC matches request methods
 * case-sensitively and would answer those spellings with the listener's 405.
 *
 * <p>This filter therefore hands a request for {@code /} whose method lower-cases to {@code get} or {@code post},
 * but is not {@code GET} or {@code POST} itself, down the chain as a {@code GET} or {@code POST} request. It
 * lower-cases the method as the original matcher did, with {@link Locale#ROOT} so that the JVM's locale cannot change
 * the result. The path is the request URI without the context path, compared exactly, so the query string plays no
 * part and every other path, which no listener served, passes unchanged to its no-listener 404. Every other request,
 * {@code GET} and {@code POST} included, passes unchanged.
 *
 * <p>Only the method token is canonicalized; the request then reaches the existing {@code GET} and {@code POST}
 * mappings of {@code controller/SalesforceRetrievalController} and is answered byte for byte as the upper-case request
 * is. Other spellings of other methods ({@code head}, {@code options}, {@code put}, {@code trace}, unknown tokens)
 * keep the 405 the listeners gave them. They are never turned into a recognised method, so a lower-case
 * {@code options} never becomes a CORS pre-flight.
 *
 * <p>It runs ahead of the other filters, so that each of them, and the dispatcher, sees the method the listener
 * matched.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ListenerMethodFilter extends OncePerRequestFilter {

    /** The path both listeners serve. */
    private static final String LISTENER_PATH = "/";

    /**
     * The listeners' allowed methods, keyed by the lower-case form the original matcher compared and mapped to the
     * spelling Spring MVC's {@code GET} and {@code POST} mappings expect.
     */
    private static final Map<String, String> ALLOWED_METHODS = Map.of("get", "GET", "post", "POST");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String method = request.getMethod();
        String allowed = method == null ? null : ALLOWED_METHODS.get(method.toLowerCase(Locale.ROOT));
        if (allowed != null && !allowed.equals(method) && isListenerPath(request)) {
            filterChain.doFilter(new AllowedMethodRequest(request, allowed), response);
        } else {
            filterChain.doFilter(request, response);
        }
    }

    /**
     * Whether {@code request} targets {@link #LISTENER_PATH}: its request URI, without the context path when it
     * starts with one, is exactly {@code /}.
     */
    private static boolean isListenerPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        String contextPath = request.getContextPath();
        String path = contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)
                ? uri.substring(contextPath.length())
                : uri;
        return LISTENER_PATH.equals(path);
    }

    /** A request that reports an allowed method in its canonical spelling and delegates everything else. */
    private static final class AllowedMethodRequest extends HttpServletRequestWrapper {

        private final String method;

        AllowedMethodRequest(HttpServletRequest request, String method) {
            super(request);
            this.method = method;
        }

        @Override
        public String getMethod() {
            return method;
        }
    }
}
