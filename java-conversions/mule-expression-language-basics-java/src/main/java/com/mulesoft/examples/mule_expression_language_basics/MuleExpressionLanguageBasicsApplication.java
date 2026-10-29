/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_expression_language_basics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point of the converted Mule example {@code mule-expression-language-basics} (Apache License 2.0,
 * see the project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, which loaded
 * {@code greeting.xml} and its six flows, the HTTP listeners on {@code /greet1} to {@code /greet6}, into the Mule
 * runtime; this class starts the application as a standalone process on Undertow, with the listener port and address
 * taken from {@code application.yml}.
 */
@SpringBootApplication
public class MuleExpressionLanguageBasicsApplication {

    public static void main(String[] args) {
        SpringApplication.run(MuleExpressionLanguageBasicsApplication.class, args);
    }
}
