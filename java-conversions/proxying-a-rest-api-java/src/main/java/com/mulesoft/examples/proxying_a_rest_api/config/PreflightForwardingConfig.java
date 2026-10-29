/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_rest_api.config;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.web.servlet.WebMvcRegistrations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Lets a CORS pre-flight reach the proxy like every other {@code OPTIONS} request. The original listener
 * ({@code http:listener path="/*"} without {@code allowedMethods}, {@code proxying-a-rest-api.xml:12}) forwarded
 * every method to the upstream and applied no CORS policy, so an {@code OPTIONS} carrying {@code Origin} and
 * {@code Access-Control-Request-Method} was forwarded with its headers and the caller received the upstream's
 * answer.
 *
 * <p>Spring MVC answers a pre-flight itself instead: once a handler matches, the handler mapping replaces it with
 * its own pre-flight handler, which rejects the request with 403 {@code Invalid CORS request} when no CORS
 * configuration applies. That happens before any handler or advice runs, so the controller cannot prevent it. The
 * mapping registered here keeps the matched handler whenever no CORS configuration applies, and this application
 * declares none, so {@code RestProxyController} forwards the pre-flight. A CORS configuration, were one declared,
 * would still be processed by Spring as usual. Nothing here checks an origin or adds an {@code Access-Control-*}
 * header: the caller receives the headers the upstream sent, filtered as for every other response.
 */
@Configuration(proxyBeanMethods = false)
public class PreflightForwardingConfig {

    /**
     * Hands Spring Boot's MVC configuration a {@link PreflightForwardingHandlerMapping} in place of the default
     * {@link RequestMappingHandlerMapping}; Boot still applies its order, interceptors, path matching and content
     * negotiation to it.
     *
     * @return the registrations Spring Boot's MVC auto-configuration uses when exactly one such bean exists
     */
    @Bean
    public WebMvcRegistrations preflightForwardingRegistrations() {
        return new WebMvcRegistrations() {
            @Override
            public RequestMappingHandlerMapping getRequestMappingHandlerMapping() {
                return new PreflightForwardingHandlerMapping();
            }
        };
    }

    /**
     * A {@link RequestMappingHandlerMapping} that leaves a request without CORS configuration to the handler it
     * matched, a pre-flight included.
     */
    public static class PreflightForwardingHandlerMapping extends RequestMappingHandlerMapping {

        /**
         * Returns {@code chain} unchanged when {@code config} is {@code null}, so a pre-flight runs the matched
         * handler instead of being rejected and a request without CORS configuration gets no CORS processing;
         * otherwise applies Spring's CORS processing with {@code config}.
         *
         * @param request the current request
         * @param chain   the handler chain of the matched handler
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
    }
}
