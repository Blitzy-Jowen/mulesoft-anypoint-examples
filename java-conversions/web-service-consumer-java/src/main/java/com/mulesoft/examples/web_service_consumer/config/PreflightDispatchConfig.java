/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.web_service_consumer.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import org.springframework.boot.autoconfigure.web.servlet.WebMvcRegistrations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Dispatches a CORS pre-flight like every other {@code OPTIONS} request. The Mule HTTP listener applied no CORS
 * policy: an {@code OPTIONS} request carrying {@code Origin} and {@code Access-Control-Request-Method} ran the flow of
 * a listener that accepts {@code OPTIONS}, and a listener whose {@code allowedMethods} exclude it answered it with
 * the listener's 405, exactly as each answered an {@code OPTIONS} request without those headers.
 *
 * <p>Spring MVC treats such a request as a pre-flight and departs from that in two ways, both before any handler or
 * advice runs, so no controller can prevent them. Its request mappings match a pre-flight by the method it asks for
 * instead of by {@code OPTIONS}, so a pre-flight asking for {@code POST} selects the {@code POST} handler. And once a
 * handler is selected, the handler mapping replaces it with its own pre-flight handler, which answers 403
 * {@code Invalid CORS request} with three {@code Vary} headers when no CORS configuration applies.
 *
 * <p>The mapping registered here looks a pre-flight to which no CORS configuration applies up as the plain
 * {@code OPTIONS} request it is, and runs the handler that lookup selects: an explicit {@code OPTIONS} mapping,
 * Spring's {@code OPTIONS} handler where the path is mapped for other methods only, or none. The pre-flight therefore
 * gets the response any {@code OPTIONS} request to that path gets: the flow's response, the listener's 405 or the
 * no-listener 404. The handler for the requested method is never run, and the selected handler receives the request
 * unchanged, every header included. Nothing checks an origin or adds an {@code Access-Control-*} or {@code Vary}
 * header. A CORS configuration, were one declared through {@code @CrossOrigin} or globally, would still be processed
 * by Spring as usual for the handlers it applies to.
 */
@Configuration(proxyBeanMethods = false)
public class PreflightDispatchConfig {

    /**
     * Hands Spring Boot's MVC configuration a {@link PreflightDispatchHandlerMapping} in place of the default
     * {@link RequestMappingHandlerMapping}; Boot still applies its order, interceptors, path matching and content
     * negotiation to it, and registers it under the usual bean name {@code requestMappingHandlerMapping}.
     *
     * @return the registrations Spring Boot's MVC auto-configuration uses when exactly one such bean exists
     */
    @Bean
    public WebMvcRegistrations preflightDispatchRegistrations() {
        return new WebMvcRegistrations() {
            @Override
            public RequestMappingHandlerMapping getRequestMappingHandlerMapping() {
                return new PreflightDispatchHandlerMapping();
            }
        };
    }

    /**
     * A {@link RequestMappingHandlerMapping} that dispatches a CORS pre-flight to which no CORS configuration applies
     * as a plain {@code OPTIONS} request, and every other request as Spring does.
     */
    public static class PreflightDispatchHandlerMapping extends RequestMappingHandlerMapping {

        /**
         * Selects the handler for {@code request}. A request that is not a CORS pre-flight is looked up as Spring
         * looks it up.
         *
         * <p>A pre-flight is first looked up as Spring looks it up, by the method it asks for. When that selects a
         * handler to which a CORS configuration applies, that handler is returned and Spring answers the pre-flight
         * with that configuration. Otherwise the pre-flight is looked up again as a {@link PlainOptionsRequest},
         * which mappings match by {@code OPTIONS}, and the handler of that lookup is returned. The second lookup
         * runs last, so the request attributes it sets, such as URI template variables, are those of the handler
         * that runs.
         *
         * @param request the current request
         * @return the handler method for {@code request}, or {@code null} when no mapping serves its path
         * @throws Exception when the lookup rejects the request, as it rejects any {@code OPTIONS} request to that
         *                   path with the same headers
         */
        @Override
        protected HandlerMethod getHandlerInternal(HttpServletRequest request) throws Exception {
            if (CorsUtils.isPreFlightRequest(request)) {
                HandlerMethod preFlightHandler = preFlightHandler(request);
                if (preFlightHandler != null && corsConfiguration(preFlightHandler, request) != null) {
                    return preFlightHandler;
                }
                return super.getHandlerInternal(new PlainOptionsRequest(request));
            }
            return super.getHandlerInternal(request);
        }

        /**
         * Returns {@code chain} unchanged when {@code config} is {@code null}, so a pre-flight runs the handler
         * {@link #getHandlerInternal} selected instead of being rejected, and a request without CORS configuration
         * gets no CORS processing; otherwise applies Spring's CORS processing with {@code config}.
         *
         * @param request the current request
         * @param chain   the handler chain of the selected handler
         * @param config  the CORS configuration that applies to the request, or {@code null} when none does
         * @return {@code chain} when {@code config} is {@code null}, otherwise the chain Spring's CORS processing
         *         builds
         */
        @Override
        protected HandlerExecutionChain getCorsHandlerExecutionChain(HttpServletRequest request,
                                                                     HandlerExecutionChain chain,
                                                                     CorsConfiguration config) {
            if (config == null) {
                return chain;
            }
            return super.getCorsHandlerExecutionChain(request, chain, config);
        }

        /**
         * Spring's own lookup of the pre-flight {@code request}, which matches it by the method it asks for.
         *
         * @param request the current pre-flight
         * @return the handler that lookup selects, or {@code null} when it selects none or rejects the request
         * @throws Exception when the lookup fails for a reason other than rejecting the request
         */
        private HandlerMethod preFlightHandler(HttpServletRequest request) throws Exception {
            try {
                return super.getHandlerInternal(request);
            } catch (ServletException rejected) {
                // The lookup rejects a request, for example for a missing parameter its matching mapping requires,
                // only when it selects no handler, so no CORS configuration applies; the lookup as a plain OPTIONS
                // request that follows produces that request's own result.
                return null;
            }
        }

        /**
         * The CORS configuration that applies to {@code handler} for {@code request}: the handler's own, combined
         * with the global configuration when one is set, as {@link #getHandler} computes it.
         *
         * @param handler the handler Spring's lookup selected for the pre-flight
         * @param request the current pre-flight
         * @return the configuration, or {@code null} when none applies
         */
        private CorsConfiguration corsConfiguration(HandlerMethod handler, HttpServletRequest request) {
            CorsConfiguration config = getCorsConfiguration(handler, request);
            CorsConfigurationSource globalSource = getCorsConfigurationSource();
            CorsConfiguration global = globalSource != null ? globalSource.getCorsConfiguration(request) : null;
            return global != null ? global.combine(config) : config;
        }
    }

    /**
     * A CORS pre-flight seen as the plain {@code OPTIONS} request it is: the {@code Access-Control-Request-Method}
     * header, its name compared ignoring case, is absent from every header accessor, and everything else, request
     * attributes included, is the wrapped request's, so attributes set during a lookup land on the wrapped request.
     */
    static final class PlainOptionsRequest extends HttpServletRequestWrapper {

        /**
         * @param request the pre-flight to present without its {@code Access-Control-Request-Method} header
         */
        PlainOptionsRequest(HttpServletRequest request) {
            super(request);
        }

        /**
         * @return {@code null} for {@code Access-Control-Request-Method}, otherwise the wrapped request's value
         */
        @Override
        public String getHeader(String name) {
            return isHidden(name) ? null : super.getHeader(name);
        }

        /**
         * @return an empty enumeration for {@code Access-Control-Request-Method}, otherwise the wrapped request's
         *         values
         */
        @Override
        public Enumeration<String> getHeaders(String name) {
            return isHidden(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        /**
         * @return the wrapped request's header names without {@code Access-Control-Request-Method}, in their order,
         *         or {@code null} when the container does not expose header names
         */
        @Override
        public Enumeration<String> getHeaderNames() {
            Enumeration<String> names = super.getHeaderNames();
            if (names == null) {
                return null;
            }
            List<String> visible = new ArrayList<>();
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                if (!isHidden(name)) {
                    visible.add(name);
                }
            }
            return Collections.enumeration(visible);
        }

        /**
         * @return {@code -1}, the value for an absent header, for {@code Access-Control-Request-Method}, otherwise
         *         the wrapped request's value
         */
        @Override
        public int getIntHeader(String name) {
            return isHidden(name) ? -1 : super.getIntHeader(name);
        }

        /**
         * @return {@code -1}, the value for an absent header, for {@code Access-Control-Request-Method}, otherwise
         *         the wrapped request's value
         */
        @Override
        public long getDateHeader(String name) {
            return isHidden(name) ? -1L : super.getDateHeader(name);
        }

        /** Whether {@code name} is {@code Access-Control-Request-Method}, ignoring case; {@code null} is not. */
        private static boolean isHidden(String name) {
            return HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD.equalsIgnoreCase(name);
        }
    }
}
