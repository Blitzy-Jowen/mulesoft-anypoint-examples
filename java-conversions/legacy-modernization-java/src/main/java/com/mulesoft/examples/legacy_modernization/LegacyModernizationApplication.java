/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.legacy_modernization;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Spring Boot entry point of the converted Mule example {@code legacy-modernization} (Apache License 2.0, see the
 * project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, whose
 * {@code config.resources=FufillmentWebService.xml} loaded the flow {@code Fulfillment_LegacySystemModernization}
 * into the Mule runtime; this class starts the application as a standalone process on Undertow, with the listener
 * port and address (1080 on {@code 0.0.0.0}) and the CSV output directory taken from {@code application.yml}.
 *
 * <p>{@link EnableAsync} replaces the flow's {@code async} scope: {@code service.ShippingOrderExportService} writes
 * the legacy fulfillment CSV on Boot's {@code applicationTaskExecutor} (threads named by
 * {@code spring.task.execution.thread-name-prefix}), so the SOAP response never waits for the file.
 *
 * <p>Spring WS is configured by the project's {@code WsConfigurer} ({@code config.WebServiceConfig}) alone, never
 * through Spring WS's own enabling annotation: that annotation switches off Boot's web-services auto-configuration
 * and with it the {@code MessageDispatcherServlet} that serves {@code /OrderFulfillment}.
 */
@SpringBootApplication
@EnableAsync
public class LegacyModernizationApplication {

    public static void main(String[] args) {
        SpringApplication.run(LegacyModernizationApplication.class, args);
    }
}
