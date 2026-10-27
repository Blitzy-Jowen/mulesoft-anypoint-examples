/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point of the converted Mule example {@code exposing-a-restful-resource-using-the-HTTP-connector}
 * (Apache License 2.0, see the project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, whose
 * {@code config.resources=http-restful-resource.xml} loaded the flows {@code retrieveAllPersonsFlow},
 * {@code storePersonFlow} and {@code retrievePersonFlow} into the Mule runtime; this class starts the application as
 * a standalone process on Undertow, so the reason phrase {@code Not Found} reaches the wire. The listener port and
 * address are taken from {@code application.yml}. The flow file's {@code http:request-config HttpRequestConfig}
 * (line 5) is not carried, because no flow references it.
 *
 * <p>Component scanning from this package root picks up the flows' parts: {@code controller.PersonController} (the
 * three listeners on {@code /person} and {@code /person/{personId}}), {@code service.PersonService} (the
 * {@code personDataStore} and {@code personId} beans of lines 6-9, held as its own state, and every flow step),
 * {@code exception.PersonExceptionHandler} (the choice exception strategy of {@code retrievePersonFlow}),
 * {@code config.ListenerMethodFilter} (the listener's case-insensitive method matching),
 * {@code config.PreflightDispatchConfig} and the SC-1 {@code exception.GlobalExceptionHandler} (the platform-default
 * error responses).
 */
@SpringBootApplication
public class ExposingARestfulResourceUsingTheHTTPConnectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExposingARestfulResourceUsingTheHTTPConnectorApplication.class, args);
    }
}
