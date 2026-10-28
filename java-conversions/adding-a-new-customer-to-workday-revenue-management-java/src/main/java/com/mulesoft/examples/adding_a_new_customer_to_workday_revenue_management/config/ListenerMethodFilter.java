/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.config;

import java.io.IOException;
import java.util.Locale;

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
 * Matches the request method against the listener's {@code allowedMethods} the way the Mule 3.8 HTTP listener did,
 * ignoring case.
 *
 * <p>The original listener declares {@code path="/" allowedMethods="POST"}
 * (adding-a-new-customer-to-workday-revenue-management/src/main/app/add_a_new_customer.xml:8). Mule's
 * {@code MethodRequestMatcher} lower-cases the configured methods and the request method before comparing them, so
 * {@code post}, {@code Post} or {@code pOsT} ran the flow exactly as {@code POST} did. Spring MVC matches request
 * methods case-sensitively and would answer those spellings with the listener's 405.
 *
 * <p>This filter therefore hands a request to that listener's path whose method lower-cases to {@code post}, but is
 * not {@code POST} itself, down the chain as a {@code POST} request, so it reaches
 * {@code controller/WorkdayCustomerController}'s {@code POST} mapping and is answered byte for byte as a
 * {@code POST} is. It lower-cases the method as the original matcher did, with {@link Locale#ROOT} so that the JVM's
 * locale cannot change the result. The listener's path is compared with the request's path within the application,
 * the request URI without the context path, exactly and as sent: {@code /} alone, never {@code /other}, {@code //}
 * or a percent-encoded form. The path is not decoded, so a request's declared charset is never consulted here.
 *
 * <p>Every other request passes unchanged: {@code POST} itself, every request to another path, which no listener
 * serves and which gets the no-listener 404 in any letter case, and other spellings of other methods
 * ({@code get}, {@code options}, {@code trace}, unknown tokens), which keep the 405 the listener gave them. They are
 * never turned into a recognised method, so a lower-case {@code options} never becomes a CORS pre-flight.
 *
 * <p>It runs ahead of the other filters, so that each of them, and the dispatcher, sees the method the listener
 * matched.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ListenerMethodFilter extends OncePerRequestFilter {

    /** The listener's {@code path}. */
    private static final String LISTENER_PATH = "/";

    /** The listener's only allowed method, as Spring MVC's {@code POST} mapping expects it. */
    private static final String POST = "POST";

    /** {@link #POST} as the original matcher compared it, lower-cased. */
    private static final String POST_LOWER_CASE = "post";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String method = request.getMethod();
        if (method != null && !POST.equals(method) && POST_LOWER_CASE.equals(method.toLowerCase(Locale.ROOT))
                && isListenerPath(request)) {
            filterChain.doFilter(new PostRequest(request), response);
        } else {
            filterChain.doFilter(request, response);
        }
    }

    /**
     * Whether the request's path within the application, its URI as sent without the context path, is exactly the
     * listener's path.
     *
     * @param request the current request
     * @return {@code true} for {@code /} alone; {@code false} for every other path and for a request without a URI
     */
    private static boolean isListenerPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty()) {
            if (!uri.startsWith(contextPath)) {
                return false;
            }
            uri = uri.substring(contextPath.length());
        }
        return LISTENER_PATH.equals(uri);
    }

    /** A request that reports {@code POST} as its method and delegates everything else to the original. */
    private static final class PostRequest extends HttpServletRequestWrapper {

        PostRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getMethod() {
            return POST;
        }
    }
}
