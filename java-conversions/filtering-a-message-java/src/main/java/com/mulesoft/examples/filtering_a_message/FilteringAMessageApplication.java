/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.filtering_a_message;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point of the converted Mule example {@code filtering-a-message} (Apache License 2.0, see the
 * project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, whose
 * {@code config.resources=filtering.xml} loaded the single flow {@code filteringFlow1} into the Mule runtime; this
 * class starts the application as a standalone process on Undertow, where that flow runs as
 * {@code controller/DiscountController} (the {@code POST /} listener) calling {@code service/DiscountService} (the
 * filters, the JSON conversion and the logger). The listener port and address are taken from
 * {@code application.yml}.
 */
@SpringBootApplication
public class FilteringAMessageApplication {

    public static void main(String[] args) {
        SpringApplication.run(FilteringAMessageApplication.class, args);
    }
}
