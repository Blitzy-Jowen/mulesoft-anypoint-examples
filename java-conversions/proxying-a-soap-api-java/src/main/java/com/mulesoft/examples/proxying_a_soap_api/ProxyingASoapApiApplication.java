/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_soap_api;

import jakarta.servlet.DispatcherType;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

import com.mulesoft.examples.proxying_a_soap_api.client.ShopServiceClient;
import com.mulesoft.examples.proxying_a_soap_api.config.WsdlQueryPublisher;

/**
 * Spring Boot entry point of the converted Mule example {@code proxying-a-soap-api}, replacing its
 * {@code mule-deploy.properties} ({@code config.resources=soap-api-proxy.xml}). It boots the transparent SOAP proxy on
 * Undertow: {@code controller.SoapProxyController} on {@code /} → {@code service.SoapProxyService} →
 * {@code client.ShopServiceClient} to {@code proxy.upstream-url}.
 *
 * <p>The {@code cxf:proxy-service} of {@code soap-api-proxy.xml:7} read its {@code wsdlLocation} at deployment and
 * answered {@code GET /?wsdl}; this class registers the SC-11 {@link WsdlQueryPublisher} that does the same here.
 */
@SpringBootApplication
public class ProxyingASoapApiApplication {

    /** The {@code path} of the original {@code http:listener} ({@code soap-api-proxy.xml:6}). */
    private static final String LISTENER_PATH = "/";

    public static void main(String[] args) {
        SpringApplication.run(ProxyingASoapApiApplication.class, args);
    }

    /**
     * Publishes the upstream ShopService WSDL from {@code proxy.wsdl-location} at {@code GET /?wsdl}, and each schema
     * it imports at {@code GET /?xsd=<location>}, with {@code soap:address} rewritten to the request URL. Every other
     * request, a {@code POST} to {@code /?wsdl} included, continues down the chain to {@code SoapProxyController} or
     * the generic 404 advice. The WSDL and its schema closure are read here, at start-up, as CXF read them at
     * deployment.
     *
     * @param shopServiceClient supplies the WSDL resource, read under {@code proxy.response-timeout-ms}
     * @return the filter registration, on {@code /*} for request dispatches only
     * @throws IllegalStateException when the WSDL or a schema it references cannot be read or parsed, which fails
     *                               start-up
     */
    @Bean
    public FilterRegistrationBean<WsdlQueryPublisher> wsdlQueryPublisher(ShopServiceClient shopServiceClient) {
        WsdlQueryPublisher publisher = new WsdlQueryPublisher()
                .register(shopServiceClient.wsdlResource(), LISTENER_PATH);

        FilterRegistrationBean<WsdlQueryPublisher> registration = new FilterRegistrationBean<>(publisher);
        registration.setName("wsdlQueryPublisher");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/*");
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        return registration;
    }
}
