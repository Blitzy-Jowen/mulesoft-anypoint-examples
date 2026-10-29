/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_json_data_to_a_jms_queue.config;

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
 * Matches the request method against the listener's {@code allowedMethods} the way the Mule 3.8 HTTP listener
 * did, ignoring case.
 *
 * <p>The original listener declares {@code allowedMethods="POST"}
 * (sending-json-data-to-a-jms-queue/src/main/app/json-to-jms.xml:6). Mule's {@code MethodRequestMatcher}
 * lower-cases the configured methods and the request method before comparing them, so {@code post},
 * {@code Post} or {@code pOsT} ran the flow exactly as {@code POST} did. Spring MVC matches request methods
 * case-sensitively and would answer those spellings with the listener's 405.
 *
 * <p>This filter therefore hands a request whose method lower-cases to {@code post}, but is not {@code POST}
 * itself, down the chain as a {@code POST} request. It lower-cases the method as the original matcher did, with
 * {@link Locale#ROOT} so that the JVM's locale cannot change the result. Every other request, {@code POST}
 * included, passes unchanged. Other spellings of other methods ({@code get}, {@code options}, {@code trace},
 * unknown tokens) keep the 405 the listener gave them. They are never turned into a recognised method, so a
 * lower-case {@code options} never becomes a CORS preflight.
 *
 * <p>It runs ahead of the other filters, so that each of them, and the dispatcher, sees the method the listener
 * matched.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ListenerMethodFilter extends OncePerRequestFilter {

    /** The listener's only allowed method, as Spring MVC's {@code POST} mapping expects it. */
    private static final String POST = "POST";

    /** {@link #POST} as the original matcher compared it, lower-cased. */
    private static final String POST_LOWER_CASE = "post";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String method = request.getMethod();
        if (method != null && !POST.equals(method) && POST_LOWER_CASE.equals(method.toLowerCase(Locale.ROOT))) {
            filterChain.doFilter(new PostRequest(request), response);
        } else {
            filterChain.doFilter(request, response);
        }
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
