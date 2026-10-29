/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.websphere_mq;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point of the converted Mule example {@code websphere-mq} (Apache License 2.0, see the project's
 * {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor
 * ({@code config.resources=websphere-mq.xml}), which loaded the flows {@code Input}, {@code MessageProcessor} and
 * {@code Output} into the Mule runtime; this class starts the application as a standalone process on Undertow, with
 * the Ajax listener's port and address, the {@code wmq.*} connection keys and the {@code ajax.*} channel settings
 * taken from {@code application.yml}.
 *
 * <p>JMS listener processing is switched on by {@code config.IbmMqConfig}, next to the listener container factories
 * it defines, so no JMS-enabling annotation is declared here. Without {@code wmq.host} start-up fails with a message
 * naming {@code wmq.host} and {@code WMQ_HOST}, and while the configured queue manager cannot be reached or rejects the
 * credentials it fails in that class's connection check, the counterpart of {@code validateConnections="true"}; no
 * other broker stands in for IBM MQ outside tests.
 */
@SpringBootApplication
public class WebsphereMqApplication {

    /**
     * Starts the application.
     *
     * @param args command-line arguments; {@code --key=value} entries override {@code application.yml} keys
     */
    public static void main(String[] args) {
        SpringApplication.run(WebsphereMqApplication.class, args);
    }
}
