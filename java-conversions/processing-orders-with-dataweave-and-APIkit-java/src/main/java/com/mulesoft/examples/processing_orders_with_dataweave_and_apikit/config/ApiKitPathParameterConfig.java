/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.config;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.server.RequestPath;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * Matches a {@code ;} in the request path as content of the path segment that carries it, as the Mule 3.8 listener
 * and the APIkit 3.8.0 router did, so a RAML resource is served only to a request whose path names it exactly.
 *
 * <p>The Mule {@code <base path>/*} listener compared the raw request path, query removed, with its own path
 * segment by segment and literally, so a path such as {@code /api;x=1/teams} reached no listener and got the
 * listener's no-listener 404. Below the base path, the APIkit router took the resource path from the request path
 * relative to the base path, percent-decoded ({@code HttpRestRequest.getResourcePath}, {@code URICoder.decode}). Its
 * {@code URIResolver} encoded that path again, {@code ;} as {@code %3B} and {@code =} as {@code %3D}, and matched it
 * with the RAML URI templates: a literal segment character for character, a {@code {parameter}} segment by
 * {@code TokenVariable}'s value pattern {@code (?:[\w.~-]|(?:%[0-9A-F]{2}))+}, the bound value decoded again. A
 * {@code ;} was therefore ordinary segment content. {@code /teams;x=1} matched no template and got the APIkit 404,
 * and {@code /teams/BAR;x=1} matched {@code /teams/{teamId}} with {@code teamId} {@code BAR;x=1}, which the RAML's
 * URI parameter validation, run after the method check, rejected with the APIkit 400.
 *
 * <p>Spring MVC's {@code PathPattern} compares and binds each segment of the parsed request path without its
 * {@code ;} parameters instead, so {@code /api/teams;x=1} runs the handlers of {@code /api/teams} and
 * {@code /api/teams/BAR;x=1} binds {@code teamId} {@code BAR}; only a {@code ;} sent as {@code %3B} is segment
 * content to it, as it was to APIkit. No {@code PathPatternParser} option keeps the parameters. A handler
 * interceptor cannot reproduce the router either: it runs only once a handler has been selected, after the request
 * mappings have answered a method, body media type or {@code Accept} header they do not serve with their 405, 415
 * or 406, and a segment empty before its {@code ;} never selects a URI variable.
 *
 * <p>The handler mapping registered here is the first the {@code DispatcherServlet} consults, and it selects no
 * handler. When the raw request URI relative to the context path contains {@code ;}, it replaces the request path
 * the {@code DispatcherServlet} parsed for the dispatch with the same path in which every {@code ;} is written
 * {@code %3B}, so every handler mapping after it, the two lookups of a CORS pre-flight in
 * {@code PreflightDispatchConfig} included, matches and binds the path as APIkit did:
 * <ul>
 *   <li>a literal segment carrying {@code ;}, such as {@code /api/teams;x=1} or {@code /api/teams;}, names no
 *       resource and gets APIkit's 404 {@code { "message": "Resource not found" }} for every method token, a
 *       lower-case one included, with any {@code Content-Type} or {@code Accept} header, and for a CORS
 *       pre-flight;</li>
 *   <li>a URI parameter segment is bound whole and decoded, so {@code /api/teams/BAR;x=1} binds {@code BAR;x=1} and
 *       {@code /api/teams/;x=1} binds {@code ;x=1}, which the RAML's validation judges as any other value, while a
 *       method the resource does not declare still gets APIkit's 405;</li>
 *   <li>outside the base path, a path such as {@code /api;x=1/teams} is served by no listener and gets the
 *       no-listener 404, {@code text/plain} {@code No listener for endpoint: <path>}.</li>
 * </ul>
 * The request URI itself is left unchanged, so every response and log line reports the path as sent, {@code ;}
 * included. A {@code ;} in the query string is not part of the URI and is unaffected, and a path without {@code ;}
 * is matched exactly as before. The {@code DispatcherServlet} discards the replaced path with its own once the
 * dispatch ends.
 *
 * <p>Like the listener's other platform defaults, the answers to these paths are Tier 2A verification points: when
 * a captured fixture shows the original answering such a path differently, the fixture governs and this class
 * changes to match.
 *
 * <p>The RAML-backed projects carry copies of this class that differ from this one in the {@code package} line
 * alone, so the class depends only on the Servlet API and Spring.
 */
@Configuration(proxyBeanMethods = false)
public class ApiKitPathParameterConfig {

    /**
     * Registers the {@link PathParameterSegmentMapping}, which the {@code DispatcherServlet} finds among its handler
     * mapping beans and consults first, by its order.
     *
     * @return the handler mapping that makes {@code ;} segment content for every mapping after it
     */
    @Bean
    public PathParameterSegmentMapping apiKitPathParameterSegmentMapping() {
        return new PathParameterSegmentMapping();
    }

    /**
     * A {@link HandlerMapping} that selects no handler and, for a request whose path carries {@code ;}, replaces the
     * parsed request path every later mapping matches with one in which {@code ;} is segment content.
     */
    public static final class PathParameterSegmentMapping implements HandlerMapping, Ordered {

        /** Separates a path segment from its parameters for Spring MVC; segment content for APIkit. */
        private static final String PATH_PARAMETER_SEPARATOR = ";";

        /** {@link #PATH_PARAMETER_SEPARATOR} percent-encoded, the form Spring MVC matches as segment content. */
        private static final String ENCODED_SEPARATOR = "%3B";

        /** Separates path segments, and starts the path that follows the context path. */
        private static final String SEGMENT_SEPARATOR = "/";

        /**
         * Runs the mapping before every handler mapping Spring Boot configures.
         *
         * @return {@link Ordered#HIGHEST_PRECEDENCE}
         */
        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        /**
         * Tells the {@code DispatcherServlet} that the mapping reads the parsed request path, so it parses the path
         * before the handler lookup and discards it after the dispatch.
         *
         * @return {@code true}
         */
        @Override
        public boolean usesPathPatterns() {
            return true;
        }

        /**
         * Replaces the parsed request path of {@code request} when its raw URI, relative to the context path,
         * contains {@code ;}: the replacement is parsed from the same URI with every {@code ;} after the context
         * path written {@code %3B}, against the same context path, {@code null} and {@code /} meaning none, as
         * Spring MVC reads them. A request is left as it is when it has no URI, when its URI does not continue its
         * context path with a {@code /}, which Spring MVC cannot parse, or when its path holds no {@code ;}.
         *
         * @param request the current request, whose request URI is read and never changed
         * @return {@code null}, always, so the {@code DispatcherServlet} consults the next handler mapping
         */
        @Override
        public HandlerExecutionChain getHandler(HttpServletRequest request) {
            String uri = request.getRequestURI();
            String contextPath = request.getContextPath();
            if (contextPath == null || SEGMENT_SEPARATOR.equals(contextPath)) {
                contextPath = "";
            }
            if (uri == null || !uri.startsWith(contextPath)) {
                return null;
            }
            String path = uri.substring(contextPath.length());
            if (path.startsWith(SEGMENT_SEPARATOR) && path.contains(PATH_PARAMETER_SEPARATOR)) {
                String encoded = contextPath + path.replace(PATH_PARAMETER_SEPARATOR, ENCODED_SEPARATOR);
                ServletRequestPathUtils.setParsedRequestPath(RequestPath.parse(encoded, contextPath), request);
            }
            return null;
        }
    }
}
