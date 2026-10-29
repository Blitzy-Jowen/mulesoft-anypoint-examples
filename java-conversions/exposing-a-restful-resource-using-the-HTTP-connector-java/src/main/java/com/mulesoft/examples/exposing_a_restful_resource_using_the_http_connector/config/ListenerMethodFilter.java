/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.config;

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
 * Matches the request method against the listeners' {@code allowedMethods} the way the Mule 3.8 HTTP listener did,
 * ignoring case.
 *
 * <p>The original declares three listeners: {@code GET /person}, {@code POST /person} and
 * {@code GET /person/{personId}}
 * (exposing-a-restful-resource-using-the-HTTP-connector/src/main/app/http-restful-resource.xml:11,16,28). Mule's
 * {@code MethodRequestMatcher} lower-cases the configured methods and the request method before comparing them, so
 * {@code get}, {@code Post} or {@code gEt} ran a flow exactly as {@code GET} and {@code POST} did. Spring MVC matches
 * request methods case-sensitively and would answer those spellings with the listener's 405.
 *
 * <p>This filter therefore hands a request whose method lower-cases to {@code get} or {@code post}, but is not
 * {@code GET} or {@code POST} itself, down the chain as a {@code GET} or {@code POST} request. It lower-cases the
 * method as the original matcher did, with {@link Locale#ROOT} so that the JVM's locale cannot change the result.
 * Every other request, {@code GET} and {@code POST} included, passes unchanged.
 *
 * <p>Only the method token is canonicalized; the request then reaches {@code controller/PersonController}'s existing
 * mappings, so the exact allowed set ({@code GET} and {@code POST} on {@code /person}, {@code GET} on
 * {@code /person/{personId}}) and the explicit {@code HEAD} and {@code OPTIONS} rejection are unchanged: a
 * {@code post} on {@code /person/{personId}} still gets the listener's 405, and a path no listener serves still the
 * no-listener 404. Other spellings of other methods ({@code head}, {@code options}, {@code put}, {@code trace},
 * unknown tokens) keep the 405 the listeners gave them. They are never turned into a recognised method, so a
 * lower-case {@code options} never becomes a CORS pre-flight.
 *
 * <p>The controller keeps its method-specific mappings rather than mapping without methods and checking the method
 * itself, because {@code /person} carries two listeners and {@code storePerson} binds
 * {@code @RequestBody(required = false) byte[]}, whose Spring semantics (a form-urlencoded body rebuilt from the
 * parsed parameters, an unparseable {@code Content-Type} answered with the 500, an empty body bound as
 * {@code null}) only a canonical {@code POST} reproduces; through this filter a {@code Post} is answered byte for
 * byte as a {@code POST} is.
 *
 * <p>It runs ahead of the other filters, so that each of them, and the dispatcher, sees the method the listener
 * matched.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ListenerMethodFilter extends OncePerRequestFilter {

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
        if (allowed != null && !allowed.equals(method)) {
            filterChain.doFilter(new AllowedMethodRequest(request, allowed), response);
        } else {
            filterChain.doFilter(request, response);
        }
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
