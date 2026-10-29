/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.jms_message_rollback_and_redelivery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Spring Boot entry point of the converted Mule example {@code jms-message-rollback-and-redelivery}
 * (Apache License 2.0, see the project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, which loaded
 * {@code jms-redelivery.xml} and its single flow {@code JMSRedeliver} into the Mule runtime; this class
 * starts the application as a standalone process without a web server, as {@code application.yml} sets.
 *
 * <p>{@code @ConfigurationPropertiesScan} registers {@code config.RedeliveryProperties}, which is not a
 * component and on which {@code trigger.RedeliveryDelay} depends. Boot's JMS auto-configuration already
 * processes {@code @JmsListener}, so no JMS-enabling annotation is declared here.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class JmsMessageRollbackAndRedeliveryApplication {

    public static void main(String[] args) {
        SpringApplication.run(JmsMessageRollbackAndRedeliveryApplication.class, args);
    }
}
