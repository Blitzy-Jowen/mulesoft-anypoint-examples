/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.hello_world.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves a listener path only to a request whose raw path is that path, as the Mule 3.8 HTTP listener did: a request
 * whose path carries {@code ;} segment parameters, such as a listener path followed by {@code ;x=1},
 * {@code ;jsessionid=ABC} or a bare {@code ;}, gets the no-listener 404 of {@code GlobalExceptionHandler} before its
 * handler runs.
 *
 * <p>The Mule listener looked a request up in its {@code HttpListenerRegistry} by the path
 * {@code HttpParser.extractPath} took from the request line, which removes the {@code ?} query and nothing else, and
 * compared each segment of that path literally with the listener's. A segment carrying {@code ;} parameters therefore
 * matched no listener of this project, none of whose paths contains a {@code ;}. Spring MVC's {@code PathPattern}
 * compares each segment without its {@code ;} parameters instead, so {@code /path;x=1} selects the handler of
 * {@code /path}. No {@code PathPatternParser} option keeps them, and {@code UrlPathHelper} removes
 * {@code ;jsessionid} however it is configured, so the request mappings cannot be made to refuse such a path.
 *
 * <p>The interceptor registered here therefore checks the raw request URI after a handler has been selected and
 * before it runs. Undertow reports that URI with every {@code ;} parameter and without the query. A URI containing
 * {@code ;} raises the {@link NoHandlerFoundException} the {@code DispatcherServlet} raises for a path no handler
 * serves, with the same arguments, so {@code GlobalExceptionHandler} answers it inside the dispatcher with 404
 * {@code text/plain} {@code No listener for endpoint: <path>}, the path including its {@code ;} parameters, and every
 * servlet filter sees the request end as any other unserved path does. A {@code ;} in the query string is not part
 * of the URI and is unaffected: {@code /path?a=1;b} still reaches the flow of {@code /path}. Every handler mapping
 * Spring Boot configures applies the interceptor, so a CORS pre-flight, which {@code PreflightDispatchConfig}
 * dispatches as a plain {@code OPTIONS} request, gets the same 404.
 *
 * <p>Like the listener's other platform defaults, the answer to such a path is a Tier 2A verification point: when a
 * captured fixture shows the original serving it, the fixture governs and this class changes to match.
 *
 * <p>The class names no project value, so every conversion that carries it holds a copy that differs from this one
 * in the {@code package} line alone.
 */
@Configuration(proxyBeanMethods = false)
public class ListenerPathConfig implements WebMvcConfigurer {

    /**
     * Registers {@link PathParameterInterceptor} without path patterns, so it applies to every handler.
     *
     * @param registry the interceptors every handler mapping Spring Boot configures applies
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PathParameterInterceptor());
    }

    /**
     * Refuses a request whose raw URI carries {@code ;} segment parameters as a path no listener serves.
     */
    static final class PathParameterInterceptor implements HandlerInterceptor {

        /** Separates a path segment from its parameters; no listener path of this project contains it. */
        private static final char PATH_PARAMETER_SEPARATOR = ';';

        /**
         * Lets the selected handler run unless the request URI contains {@code ;}.
         *
         * @param request  the current request, whose raw URI, without the query, is checked
         * @param response the current response, left untouched
         * @param handler  the selected handler, which runs only when {@code true} is returned
         * @return {@code true} when the request URI is absent or contains no {@code ;}
         * @throws NoHandlerFoundException when the request URI contains {@code ;}, carrying the request's method, URI
         *                                 and headers as the {@code DispatcherServlet} reports a path no handler
         *                                 serves
         */
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                throws NoHandlerFoundException {
            String uri = request.getRequestURI();
            if (uri != null && uri.indexOf(PATH_PARAMETER_SEPARATOR) >= 0) {
                throw new NoHandlerFoundException(request.getMethod(), uri,
                        new ServletServerHttpRequest(request).getHeaders());
            }
            return true;
        }
    }
}
