/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_request_response_with_logger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point of the converted Mule example {@code http-request-response-with-logger} (Apache License
 * 2.0, see the project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, which loaded flow
 * {@code EchoFlow} from {@code echo.xml} into the Mule runtime; this class starts the application as a standalone
 * process on Undertow, with the listener port and address taken from {@code application.yml}.
 */
@SpringBootApplication
public class HttpRequestResponseWithLoggerApplication {

    public static void main(String[] args) {
        SpringApplication.run(HttpRequestResponseWithLoggerApplication.class, args);
    }
}
