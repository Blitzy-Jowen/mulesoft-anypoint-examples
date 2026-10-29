/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.web_service_consumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.webservices.WebServicesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.mulesoft.examples.web_service_consumer.config.TshirtServiceProperties;

/**
 * Spring Boot entry point of the converted Mule example {@code web-service-consumer} (Apache License 2.0, see the
 * project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, which loaded
 * {@code tshirt-service-consumer.xml} into the Mule runtime: flows {@code orderTshirt} ({@code /orders}) and
 * {@code listInventory} ({@code /inventory}), which call {@code TshirtService} through {@code ws:consumer}. This
 * class starts the application as a standalone process on Undertow, with the listener port and address taken from
 * {@code application.yml}, and binds the {@code tshirt.*} keys to {@link TshirtServiceProperties}.
 *
 * <p>The project only consumes SOAP, so {@link WebServicesAutoConfiguration} is excluded: it would register a
 * {@code MessageDispatcherServlet} at {@code /services/*}, a path the original listener never served. Requests
 * there reach the Spring MVC dispatcher and get the no-listener 404 like any other unserved path.
 */
@SpringBootApplication(exclude = WebServicesAutoConfiguration.class)
@EnableConfigurationProperties(TshirtServiceProperties.class)
public class WebServiceConsumerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebServiceConsumerApplication.class, args);
    }
}
