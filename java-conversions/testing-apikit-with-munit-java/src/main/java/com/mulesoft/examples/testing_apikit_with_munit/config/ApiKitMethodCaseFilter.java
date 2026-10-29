/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.testing_apikit_with_munit.config;

import java.io.IOException;
import java.util.Locale;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Routes a request under the API base path whose method token is not upper case, such as {@code get},
 * {@code Get} or {@code post}, exactly as its upper-case form. The Mule {@code <base path>/*} listener declares no
 * {@code allowedMethods}, so it handed every method token to the APIkit router, and APIkit 3.8.0 lower-cased the
 * method before it routed the request to a resource action and compared it ignoring case when it recognised a
 * request for the RAML descriptor, on the API root and on the console paths alike; a token's case therefore never
 * changed the response.
 *
 * <p>Spring MVC matches method tokens case-sensitively: a request mapping declared for {@code GET} does not match
 * {@code get}, which then gets APIkit's 405 mapping, and the console, which serves the RAML to {@code GET} and
 * {@code HEAD} alone, answers with its page or its 404 instead. This filter presents such a request to everything
 * after it with the token upper-cased by {@link Locale#ROOT} rules, and with nothing else changed: request URI,
 * query string, headers, parameters, body and attributes are the original request's, so attributes set downstream
 * land on the original request.
 *
 * <p>Only requests under the base path {@code apikit.base-path} (default {@code /api}) with a token that is not
 * already upper case are changed. Upper-case tokens and requests outside the base path, which no APIkit router
 * served, reach the chain as the same request object, unwrapped, so their responses are unchanged. A token that
 * names no method a resource declares, such as {@code patch} or {@code foo}, still gets APIkit's 405 mapping,
 * exactly as its upper-case form does.
 *
 * <p>Ordered at the highest precedence, so the servlet filters that read the method, such as Spring Boot's
 * form-content and request-context filters, and the {@code DispatcherServlet}'s own dispatch by method all see
 * the upper-cased token, as they do for an upper-case request. Error and async dispatches are left alone, as
 * {@link OncePerRequestFilter} leaves them by default.
 *
 * <p>The RAML-backed projects carry copies of this class that differ from this one in the {@code package} line
 * alone, so the class depends only on the JDK, the Servlet API and Spring.
 */
@Component
public class ApiKitMethodCaseFilter extends OncePerRequestFilter implements Ordered {

    private final String basePath;

    /**
     * Creates the filter for the API served under {@code basePath}, the original listener path without its
     * {@code /*}.
     *
     * @param basePath the API base path, for example {@code /api}; surrounding blanks are ignored, a missing
     *                 leading {@code /} is added and trailing {@code /} are dropped, so {@code ""} and
     *                 {@code "/"} mean every path
     */
    public ApiKitMethodCaseFilter(@Value("${apikit.base-path:/api}") String basePath) {
        this.basePath = normaliseBasePath(basePath);
    }

    /**
     * Runs the filter before every other servlet filter that reads the method.
     *
     * @return {@link Ordered#HIGHEST_PRECEDENCE}
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /**
     * Whether {@code request} reaches the chain unchanged: when it has no method, when its method is already upper
     * case, or when its path is outside the base path.
     *
     * @param request the current request
     * @return {@code true} when the request is passed on as it is
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        return method == null || method.equals(method.toUpperCase(Locale.ROOT)) || !isUnderBasePath(path(request));
    }

    /**
     * Passes {@code request} on with its method token upper-cased.
     *
     * @param request  a request under the base path whose method is not upper case
     * @param response the current response, passed on unchanged
     * @param chain    the rest of the filter chain
     * @throws ServletException when a later filter or the servlet fails
     * @throws IOException      when reading the request or writing the response fails
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(new UpperCaseMethodRequest(request), response);
    }

    /**
     * Whether {@code path} belongs to the API: the base path itself or any path below it, so with base
     * {@code /api} the paths {@code /api}, {@code /api/} and {@code /api/teams} do and {@code /apix} does not.
     */
    private boolean isUnderBasePath(String path) {
        if ("/".equals(basePath)) {
            return true;
        }
        return path.equals(basePath) || path.startsWith(basePath + "/");
    }

    /**
     * Returns the raw request URI, without query string, relative to the context path, which is how the Mule
     * listener matched its path; {@code ""} when the container reports no URI.
     */
    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return "";
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    /**
     * Trims {@code configured}, adds a missing leading {@code /} and drops trailing {@code /}, so {@code null},
     * {@code ""} and {@code "/"} all give {@code "/"}, every path.
     */
    private static String normaliseBasePath(String configured) {
        String path = configured == null ? "" : configured.trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /**
     * A request seen with its method token upper-cased; everything else, request attributes included, is the
     * wrapped request's, so attributes set downstream land on the wrapped request.
     */
    private static final class UpperCaseMethodRequest extends HttpServletRequestWrapper {

        private final String method;

        /**
         * @param request the request whose method token is presented upper-cased
         */
        UpperCaseMethodRequest(HttpServletRequest request) {
            super(request);
            this.method = request.getMethod().toUpperCase(Locale.ROOT);
        }

        /**
         * @return the wrapped request's method token, upper-cased by {@link Locale#ROOT} rules
         */
        @Override
        public String getMethod() {
            return method;
        }
    }
}
