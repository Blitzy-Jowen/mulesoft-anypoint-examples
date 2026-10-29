/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.munit_short_tutorial;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point of the converted Mule example {@code munit-short-tutorial} (Apache License 2.0, see the
 * project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, which loaded
 * {@code production-code.xml} into the Mule runtime; this class starts the application as a standalone process on
 * Undertow, with the listener port and address taken from {@code application.yml}.
 */
@SpringBootApplication
public class MunitShortTutorialApplication {

    public static void main(String[] args) {
        SpringApplication.run(MunitShortTutorialApplication.class, args);
    }
}
