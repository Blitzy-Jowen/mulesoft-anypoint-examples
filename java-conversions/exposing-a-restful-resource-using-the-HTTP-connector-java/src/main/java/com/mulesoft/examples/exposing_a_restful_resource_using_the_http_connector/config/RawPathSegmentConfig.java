/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.config;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.UriUtils;

import com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.controller.PersonController;

/**
 * Matches the person listeners' paths against the raw request path, {@code ;} segment parameters included, as the
 * Mule 3.8 HTTP listener did: a literal segment followed by parameters, such as {@code /person;x=1},
 * {@code /person;jsessionid=ABC}, {@code /person;} or {@code /person;x=1/1}, gets the no-listener 404 of
 * {@code GlobalExceptionHandler} before any flow runs, while the {@code {personId}} segment receives the whole raw
 * segment, so {@code /person/1;x=1} and {@code /person/;x=1} reach {@code retrievePersonFlow} with the ids
 * {@code 1;x=1} and {@code ;x=1}.
 *
 * <p>The Mule listener took the path from the request line with {@code HttpParser.extractPath}, which removes the
 * {@code ?} query and nothing else. Its {@code HttpListenerRegistry} compared each segment of that path literally with
 * the listener's and let a {@code {param}} segment match any segment, and {@code HttpParser.decodeUriParams} bound the
 * parameter to that raw segment, decoded. A {@code person;x=1} segment therefore matched neither listener path, for
 * any method, and {@code 1;x=1} was the {@code personId} that {@code Integer.valueOf} rejected with the 500 of
 * {@code GlobalExceptionHandler}. Spring MVC's {@code PathPattern} compares and binds each segment without its
 * {@code ;} parameters instead, so {@code /person;x=1} would run {@code storePersonFlow} or
 * {@code retrieveAllPersonsFlow} and {@code /person/1;x=1} would return person {@code 1}. No
 * {@code PathPatternParser} option keeps the parameters.
 *
 * <p>The interceptor registered here runs after a handler has been selected and before it runs. It splits the
 * request URI, which Undertow reports with every {@code ;} parameter and without the query, and the pattern the
 * handler matched ({@link HandlerMapping#BEST_MATCHING_PATTERN_ATTRIBUTE}) into segments:
 * <ul>
 *   <li>a segment carrying {@code ;} at a literal position of the pattern raises the {@link NoHandlerFoundException}
 *       the {@code DispatcherServlet} raises for a path no handler serves, with the same arguments, so it is answered
 *       inside the dispatcher with 404 {@code text/plain} {@code No listener for endpoint: <path>}, the path
 *       including its parameters;</li>
 *   <li>a segment carrying {@code ;} at the position of a {@code {name}} variable replaces that variable's value with
 *       the raw segment, decoded as Spring decodes a path segment; every variable whose segment carries no {@code ;}
 *       keeps the value Spring bound;</li>
 *   <li>when no matched pattern is available, when the pattern and the path differ in their number of segments, or
 *       when the segment carrying {@code ;} sits at a pattern position other than a single {@code {name}} variable,
 *       the literal rule applies and the request gets the same 404.</li>
 * </ul>
 * The interceptor sees only requests for which a handler was selected. {@code controller/PersonController} therefore
 * maps every method on both listener paths, so that {@code PUT /person;x=1} gets the 404 here instead of Spring's
 * 405 from method matching, as the Mule listener matched the path before the method. A Spring URI variable never
 * captures an empty value, so an id segment that is empty before its {@code ;} ({@code /person/;x=1},
 * {@code /person/;}) cannot match {@code /person/{personId}}, where the Mule listener's {@code {personId}} matched it
 * as any other segment. Every handler of that listener therefore also maps the alias {@code /person/*}, which Spring
 * selects only when {@code {personId}} does not match, and the interceptor holds a request matched by an alias to the
 * listener pattern it stands for, whether or not its URI carries {@code ;}:
 * <ul>
 *   <li>every variable of the listener pattern is bound to its raw segment, decoded, so {@code personId} is
 *       {@code ;x=1}: {@code GET} reaches the 500 of {@code Integer.valueOf} and every other method the 405;</li>
 *   <li>a variable whose segment is empty, as in {@code /person/}, which the alias also matches, and a literal
 *       segment carrying {@code ;} get the same 404, the answer the dispatcher gives {@code /person/} without the
 *       alias;</li>
 *   <li>the request's matched pattern becomes the listener pattern, the route {@code GlobalExceptionHandler}
 *       logs.</li>
 * </ul>
 * Every handler mapping Spring Boot configures applies the interceptor, so a CORS pre-flight, which
 * {@code PreflightDispatchConfig} dispatches as a plain {@code OPTIONS} request, gets the same answer. A {@code ;} in
 * the query string is not part of the URI and is unaffected: {@code /person?a=1;b} still reaches the flows of
 * {@code /person}.
 *
 * <p>The listener projects without URI parameters carry {@code config/ListenerPathConfig}, which refuses every path
 * carrying {@code ;} and is copied between them with only its {@code package} line changed. That rule would refuse
 * {@code /person/1;x=1}, which the original {@code retrievePersonFlow} accepted, so this project holds this class,
 * under its own name, instead.
 *
 * <p>Like the listener's other platform defaults, the answer to a path carrying {@code ;} segment parameters is a
 * Tier 2A verification point: when a captured fixture shows the original answering it differently, the fixture
 * governs and this class changes to match.
 */
@Configuration(proxyBeanMethods = false)
public class RawPathSegmentConfig implements WebMvcConfigurer {

    /**
     * Registers {@link RawPathSegmentInterceptor} without path patterns, so it applies to every handler, with the
     * item listener's alias {@link PersonController#EMPTY_ID_ITEM_PATH} standing for
     * {@link PersonController#ITEM_PATH}.
     *
     * @param registry the interceptors every handler mapping Spring Boot configures applies
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RawPathSegmentInterceptor(
                Map.of(PersonController.EMPTY_ID_ITEM_PATH, PersonController.ITEM_PATH)));
    }

    /**
     * Holds the raw segments of a request to the listener pattern its handler serves: refuses the request when a
     * literal segment carries {@code ;} parameters, and binds a variable whose segment carries them, or every variable
     * of a request matched by an alias, to the whole raw segment.
     */
    static final class RawPathSegmentInterceptor implements HandlerInterceptor {

        /** Separates a path segment from its parameters; no literal segment of the listener paths contains it. */
        private static final char PATH_PARAMETER_SEPARATOR = ';';

        /** Separates the segments of a request path and of a mapping pattern. */
        private static final String SEGMENT_SEPARATOR = "/";

        /** Each alias pattern a handler maps beside its listener pattern, mapped to that listener pattern. */
        private final Map<String, String> listenerPatterns;

        /**
         * Creates the interceptor.
         *
         * @param listenerPatterns each pattern a handler maps beside a listener pattern only so that a segment Spring
         *                         reads as empty selects it, mapped to that listener pattern, whose variables a
         *                         request matched by the alias receives
         */
        RawPathSegmentInterceptor(Map<String, String> listenerPatterns) {
            this.listenerPatterns = Map.copyOf(listenerPatterns);
        }

        /**
         * Lets the selected handler run unless a literal segment of the request URI carries {@code ;} parameters,
         * rebinding first each variable whose segment carries them, or, for a request matched by an alias, every
         * variable of the listener pattern unless one of their segments is empty.
         *
         * @param request  the current request, whose raw URI, without the query, is checked
         * @param response the current response, left untouched
         * @param handler  the selected handler, which runs only when {@code true} is returned
         * @return {@code true} when the request matched no alias and its URI is absent, contains no {@code ;}, or
         *         carries {@code ;} only in segments bound to a variable; or when it matched an alias and its URI pairs
         *         with the listener pattern, every variable on a non-empty segment and no literal segment carrying
         *         {@code ;}
         * @throws NoHandlerFoundException otherwise, carrying the request's method, URI and headers as the
         *                                 {@code DispatcherServlet} reports a path no handler serves
         */
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                throws NoHandlerFoundException {
            String uri = request.getRequestURI();
            Object matched = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String listenerPattern = matched instanceof String alias ? listenerPatterns.get(alias) : null;
            if (listenerPattern == null && (uri == null || uri.indexOf(PATH_PARAMETER_SEPARATOR) < 0)) {
                return true;
            }
            String pattern = listenerPattern != null ? listenerPattern
                    : matched instanceof String handlerPattern ? handlerPattern : null;
            Map<String, String> rawVariables = uri != null && pattern != null
                    ? rawVariableSegments(pathWithinApplication(request, uri), pattern, listenerPattern != null)
                    : null;
            if (rawVariables == null) {
                throw new NoHandlerFoundException(request.getMethod(), uri,
                        new ServletServerHttpRequest(request).getHeaders());
            }
            if (listenerPattern != null) {
                request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, listenerPattern);
            }
            rebind(request, rawVariables);
            return true;
        }

        /**
         * Pairs the segments of {@code path} with those of {@code pattern} and collects the variables bound to their
         * raw segment: each variable whose segment carries {@code ;} parameters, or every variable when
         * {@code everyVariable} is set.
         *
         * @param path          the request path within the application, raw
         * @param pattern       the pattern the request is held to
         * @param everyVariable whether every variable is collected, as for a request matched by an alias, to which
         *                      Spring bound none of the listener pattern's variables
         * @return each collected variable mapped to its raw segment, in path order; or {@code null} when the segments
         *         do not pair up, a segment carrying {@code ;} is not a variable's, or a collected variable's segment
         *         is empty
         */
        private static Map<String, String> rawVariableSegments(String path, String pattern, boolean everyVariable) {
            String[] segments = path.split(SEGMENT_SEPARATOR, -1);
            String[] patternSegments = pattern.split(SEGMENT_SEPARATOR, -1);
            if (segments.length != patternSegments.length) {
                return null;
            }
            Map<String, String> rawVariables = new LinkedHashMap<>();
            for (int i = 0; i < segments.length; i++) {
                boolean carriesParameters = segments[i].indexOf(PATH_PARAMETER_SEPARATOR) >= 0;
                if (!carriesParameters && !everyVariable) {
                    continue;
                }
                String name = variableName(patternSegments[i]);
                if (name == null) {
                    if (carriesParameters) {
                        return null;
                    }
                    continue;
                }
                if (segments[i].isEmpty()) {
                    return null;
                }
                rawVariables.put(name, segments[i]);
            }
            return rawVariables;
        }

        /**
         * Returns the variable name of a pattern segment that is a single {@code {name}} variable, the form of the
         * listener's {@code {personId}}.
         *
         * @param patternSegment one segment of the matched pattern
         * @return the name, or {@code null} for a literal segment and for any other pattern syntax (wildcards,
         *         regular expressions, capture of the remaining path, text around the variable)
         */
        private static String variableName(String patternSegment) {
            int last = patternSegment.length() - 1;
            if (last < 2 || patternSegment.charAt(0) != '{' || patternSegment.charAt(last) != '}') {
                return null;
            }
            String name = patternSegment.substring(1, last);
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (c == '{' || c == '}' || c == ':' || c == '*') {
                    return null;
                }
            }
            return name;
        }

        /**
         * Replaces the value of each variable in {@code rawVariables} with its raw segment, decoded as Spring decodes
         * a path segment (UTF-8), in a copy of the URI template variables Spring bound; the other variables keep
         * Spring's values. Nothing changes when {@code rawVariables} is empty.
         *
         * @param request      the current request, whose URI template variables attribute is replaced
         * @param rawVariables each variable to rebind, mapped to its raw segment
         */
        private static void rebind(HttpServletRequest request, Map<String, String> rawVariables) {
            if (rawVariables.isEmpty()) {
                return;
            }
            Map<String, String> variables = new LinkedHashMap<>();
            if (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> bound) {
                bound.forEach((name, value) -> {
                    if (name instanceof String key && value instanceof String text) {
                        variables.put(key, text);
                    }
                });
            }
            rawVariables.forEach((name, segment) ->
                    variables.put(name, UriUtils.decode(segment, StandardCharsets.UTF_8)));
            request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                    Collections.unmodifiableMap(variables));
        }

        /**
         * Returns {@code uri} without the application's context path, the path the mapping patterns describe.
         *
         * @param request the current request
         * @param uri     its raw request URI
         * @return the path within the application
         */
        private static String pathWithinApplication(HttpServletRequest request, String uri) {
            String contextPath = request.getContextPath();
            if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
                return uri.substring(contextPath.length());
            }
            return uri;
        }
    }
}
