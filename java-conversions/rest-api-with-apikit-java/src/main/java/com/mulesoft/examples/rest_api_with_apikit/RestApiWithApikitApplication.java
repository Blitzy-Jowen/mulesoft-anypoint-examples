package com.mulesoft.examples.rest_api_with_apikit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point of the converted Mule example {@code rest-api-with-apikit}.
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor: the Mule runtime
 * loaded the application from that descriptor, while this class starts it as a standalone process.
 */
@SpringBootApplication
public class RestApiWithApikitApplication {

    public static void main(String[] args) {
        SpringApplication.run(RestApiWithApikitApplication.class, args);
    }
}
