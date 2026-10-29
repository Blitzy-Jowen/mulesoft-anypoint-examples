/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_oauth_provider.config;

import jakarta.servlet.Filter;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;

/**
 * Registers {@link RequestBodyDrainFilter} as the outermost servlet filter, ahead of {@link PortRoutingFilter}, so
 * that every request pipelined on an HTTP/1.1 connection is answered on both listeners, including a request the
 * routing filter answers itself.
 *
 * <p>The drain only takes effect for the requests it wraps. {@link PortRoutingFilter} writes the no-listener 404 for
 * a path the receiving listener does not serve, {@code /unknown} or {@code /token} on the resource listener,
 * {@code /resources} on the provider listener, and ends the chain there without calling the filters behind it. A
 * drain inside the routing filter would therefore never run for those requests, and Undertow would close a
 * pipelined connection with neither response flushed, which the Mule listener never did.
 *
 * <p>Both filters ask for {@link Ordered#HIGHEST_PRECEDENCE}, and the order value alone cannot decide between them:
 * Spring Boot lists registration beans, such as the one {@link ListenerPorts} declares for the routing filter, ahead
 * of filters it adapts from components and keeps that order for equal values. The registration declared here
 * implements {@link PriorityOrdered}, which Spring's order comparator places ahead of every plain {@link Ordered}
 * object whatever its value, so the drain filter comes first however the beans are named, scanned or defined.
 * Spring Boot then registers the component only through this bean, once, for {@code /*} and for every dispatcher
 * type, as it registers any {@code OncePerRequestFilter}. The registration names the filter after its component,
 * {@code requestBodyDrainFilter}, the name it carries in every conversion that registers it as a plain component,
 * instead of after this bean.
 */
@Configuration(proxyBeanMethods = false)
public class RequestBodyDrainRegistration {

    /** Servlet filter name of the drain filter: the bean name of its component. */
    public static final String FILTER_NAME = "requestBodyDrainFilter";

    /**
     * Registers the {@link RequestBodyDrainFilter} component ahead of every other filter.
     *
     * @param requestBodyDrainFilter the filter component
     * @return its registration as {@value #FILTER_NAME}, at {@link Ordered#HIGHEST_PRECEDENCE} and of
     *         {@link PriorityOrdered} rank
     */
    @Bean
    public FilterRegistrationBean<RequestBodyDrainFilter> requestBodyDrainFilterRegistration(
            RequestBodyDrainFilter requestBodyDrainFilter) {
        FilterRegistrationBean<RequestBodyDrainFilter> registration =
                new PriorityFilterRegistrationBean<>(requestBodyDrainFilter);
        registration.setName(FILTER_NAME);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * A filter registration that sorts ahead of every registration that is only {@link Ordered}, and among
     * {@link PriorityOrdered} ones by its order value.
     *
     * @param <T> type of the registered filter
     */
    static final class PriorityFilterRegistrationBean<T extends Filter> extends FilterRegistrationBean<T>
            implements PriorityOrdered {

        PriorityFilterRegistrationBean(T filter) {
            super(filter);
        }
    }
}
