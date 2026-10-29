/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point of the converted Mule example {@code processing-orders-with-dataweave-and-APIkit}
 * (Apache License 2.0, see the project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, whose
 * {@code config.resources=currency.xml,books.xml} loaded both flow files into one Mule application: the APIkit
 * Currency API of {@code currency.xml} on the listener path {@code /api/*}, and the file-triggered flow
 * {@code OrderFlow} of {@code books.xml}, which reads the currencies from that API and writes {@code orders.json}
 * and {@code report.csv}. This class starts both as one standalone process.
 *
 * <p>{@code @EnableScheduling} drives {@code trigger.OrderFileTrigger.poll()}, the replacement of the flow's
 * {@code file:inbound-endpoint}, with {@code orders.polling-frequency} milliseconds between polls (default 1000,
 * Mule's default, since the endpoint declares no {@code pollingFrequency}).
 */
@SpringBootApplication
@EnableScheduling
public class ProcessingOrdersWithDataweaveAndAPIkitApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProcessingOrdersWithDataweaveAndAPIkitApplication.class, args);
    }
}
