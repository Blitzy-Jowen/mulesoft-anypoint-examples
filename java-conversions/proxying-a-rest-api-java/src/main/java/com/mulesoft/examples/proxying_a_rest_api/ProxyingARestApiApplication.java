/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_rest_api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point of the converted Mule example {@code proxying-a-rest-api}, replacing its
 * {@code mule-deploy.properties}. It boots the transparent REST proxy on Undertow:
 * {@code controller.RestProxyController} on {@code /**} → {@code service.RestProxyService} →
 * {@code client.BoxProxyClient} to {@code proxy.upstream-url}.
 */
@SpringBootApplication
public class ProxyingARestApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProxyingARestApiApplication.class, args);
    }
}
