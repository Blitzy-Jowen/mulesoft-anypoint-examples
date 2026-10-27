package com.mulesoft.examples.testing_apikit_with_munit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point of the converted Mule example {@code testing-apikit-with-munit}.
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor: the Mule runtime
 * loaded the application from that descriptor's {@code api.xml} configuration, while this class starts
 * it as a standalone process.
 */
@SpringBootApplication
public class TestingApikitWithMunitApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestingApikitWithMunitApplication.class, args);
    }
}
