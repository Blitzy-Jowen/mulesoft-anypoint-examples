package com.mulesoft.examples.soap_webservice_security.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.MappingMatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Scopes every request to the routes of the listener that received it, so each port answers only for the paths its
 * Mule {@code http:listener} served.
 *
 * <p>{@link ListenerPorts} creates the instance and registers it ahead of every other filter for request dispatches
 * only; this class is deliberately not a Spring component. For each request:
 * <ol>
 *   <li>The routes are those of the additional listener whose port equals {@code ServletRequest.getLocalPort()}; a
 *       request on any other port, the main listener's included, is judged against the main routes.</li>
 *   <li>A listener without routes serves every path: the request continues down the filter chain untouched.</li>
 *   <li>The path is the raw request URI, not decoded and without the query string, minus the context path, and
 *       {@code /} when nothing is left. The first route with a pattern that matches it, by {@link AntPathMatcher},
 *       serves the request.</li>
 *   <li>No route matches: the filter writes the listener's no-listener answer itself, status 404, Content-Type
 *       {@code text/plain} without a charset parameter, body {@code No listener for endpoint: <path>} in UTF-8, the
 *       same bytes as the dispatcher's no-listener answer for an unmapped path, and ends the chain. A servlet filter
 *       runs outside every {@code @ControllerAdvice}, so the answer cannot come from an exception handler, and it
 *       never calls {@code sendError}, which would hand the response to the container's error page instead. The body
 *       is written through the output stream, never the writer, so the container adds no charset parameter.</li>
 *   <li>The route names a servlet other than the one the container mapped: the request is forwarded to that servlet
 *       through its named dispatcher, presented as if that servlet were the default servlet ({@code /}), so it sees
 *       the whole path as its servlet path. A servlet name that is not registered fails the request with an
 *       {@link IllegalStateException}.</li>
 *   <li>The route names no servlet, or the servlet the container mapped: the request continues down the chain.</li>
 * </ol>
 *
 * <p>The routes are copied when the filter is created and never change afterwards, and the one {@link AntPathMatcher}
 * is safe for concurrent use, so a single instance serves every listener concurrently. Each routing decision is
 * logged at DEBUG with the port, the servlet names and the matching route's patterns, never the request path, which
 * can carry personal or secret segments.
 */
public class PortRoutingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PortRoutingFilter.class);

    private static final String NO_LISTENER_PREFIX = "No listener for endpoint: ";
    private static final String CONTENT_TYPE = "text/plain";
    private static final String ROOT_PATH = "/";

    private final List<ListenerPorts.Route> mainRoutes;
    private final Map<Integer, List<ListenerPorts.Route>> additionalRoutesByPort;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * Creates the filter over the routes of every listener.
     *
     * @param mainRoutes             routes of the main listener, which also judge requests on every port that has no
     *                               entry below; {@code null} or empty serves every path
     * @param additionalRoutesByPort routes of each additional listener, keyed by its local port; {@code null} means
     *                               no additional listener, and a {@code null} or empty route list serves every path
     */
    public PortRoutingFilter(List<ListenerPorts.Route> mainRoutes,
            Map<Integer, List<ListenerPorts.Route>> additionalRoutesByPort) {
        this.mainRoutes = immutable(mainRoutes);
        Map<Integer, List<ListenerPorts.Route>> byPort = new LinkedHashMap<>();
        if (additionalRoutesByPort != null) {
            additionalRoutesByPort.forEach((port, routes) -> {
                // A null key cannot equal any local port, so it would never select its routes.
                if (port != null) {
                    byPort.put(port, immutable(routes));
                }
            });
        }
        this.additionalRoutesByPort = Collections.unmodifiableMap(byPort);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        int port = request.getLocalPort();
        List<ListenerPorts.Route> routes = additionalRoutesByPort.getOrDefault(port, mainRoutes);
        if (routes.isEmpty()) {
            log.debug("Port {} serves every path; the request continues unrouted", port);
            chain.doFilter(request, response);
            return;
        }

        String path = pathWithinApplication(request);
        ListenerPorts.Route route = matchingRoute(routes, path);
        if (route == null) {
            log.debug("No route on port {} matches the request path; answering 404", port);
            writeNoListener(response, path);
            return;
        }

        String mappedServlet = mappedServletName(request);
        String targetServlet = StringUtils.hasText(route.servlet()) ? route.servlet().trim() : null;
        if (targetServlet == null || targetServlet.equals(mappedServlet)) {
            log.debug("Port {} serves the request through {} as mapped, by route {}", port, mappedServlet,
                    route.patterns());
            chain.doFilter(request, response);
            return;
        }

        RequestDispatcher dispatcher = getServletContext().getNamedDispatcher(targetServlet);
        if (dispatcher == null) {
            throw new IllegalStateException("No servlet named '" + targetServlet + "' is registered to serve " + path
                    + " on port " + port + " (route " + route.patterns() + ")");
        }
        log.debug("Port {} forwards the request from {} to {}, by route {}", port, mappedServlet, targetServlet,
                route.patterns());
        dispatcher.forward(new DefaultMappingRequest(request, targetServlet, path), response);
    }

    /** The first route with a pattern matching {@code path}, or {@code null} when none matches. */
    private ListenerPorts.Route matchingRoute(List<ListenerPorts.Route> routes, String path) {
        for (ListenerPorts.Route route : routes) {
            for (String pattern : route.patterns()) {
                if (pathMatcher.match(pattern, path)) {
                    return route;
                }
            }
        }
        return null;
    }

    /**
     * The raw request URI relative to the context path, as the Mule listener reported the endpoint: not decoded,
     * without the query string, and {@code /} when empty.
     */
    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            uri = "";
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return uri.isEmpty() ? ROOT_PATH : uri;
    }

    /** Name of the servlet the container mapped the request to, or {@code null} when the request reports none. */
    private static String mappedServletName(HttpServletRequest request) {
        HttpServletMapping mapping = request.getHttpServletMapping();
        return mapping == null ? null : mapping.getServletName();
    }

    /** Writes the 404 the listener answered for a path it does not serve, and completes the response body. */
    private static void writeNoListener(HttpServletResponse response, String path) throws IOException {
        byte[] body = (NO_LISTENER_PREFIX + path).getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType(CONTENT_TYPE);
        response.setContentLength(body.length);
        ServletOutputStream out = response.getOutputStream();
        out.write(body);
        out.flush();
    }

    /** Null-tolerant unmodifiable copy: a missing list is empty and missing elements are dropped. */
    private static List<ListenerPorts.Route> immutable(List<ListenerPorts.Route> routes) {
        return routes == null ? List.of() : routes.stream().filter(Objects::nonNull).toList();
    }

    /**
     * The request as the target servlet of a forward sees it: mapped as the default servlet, so the servlet path is
     * the whole path within the application and there is no path info.
     */
    private static final class DefaultMappingRequest extends HttpServletRequestWrapper {

        private final HttpServletMapping mapping;
        private final String servletPath;

        DefaultMappingRequest(HttpServletRequest request, String servletName, String servletPath) {
            super(request);
            this.mapping = new DefaultServletMapping(servletName);
            this.servletPath = servletPath;
        }

        @Override
        public HttpServletMapping getHttpServletMapping() {
            return mapping;
        }

        @Override
        public String getServletPath() {
            return servletPath;
        }

        @Override
        public String getPathInfo() {
            return null;
        }

        /** Always {@code null}, since a request without path info has no translated path. */
        @Override
        public String getPathTranslated() {
            return null;
        }
    }

    /**
     * The mapping of the default servlet ({@code /}) for the named servlet.
     *
     * @param servletName registration name of the servlet the request is forwarded to
     */
    private record DefaultServletMapping(String servletName) implements HttpServletMapping {

        @Override
        public String getMatchValue() {
            return "";
        }

        @Override
        public String getPattern() {
            return ROOT_PATH;
        }

        @Override
        public String getServletName() {
            return servletName;
        }

        @Override
        public MappingMatch getMappingMatch() {
            return MappingMatch.DEFAULT;
        }
    }
}
