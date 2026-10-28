/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point of the converted Mule example {@code salesforce-to-MySQL-DB-using-Batch-Processing}
 * (Apache License 2.0, see the project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, whose
 * {@code config.resources=salesforce-to-database.xml} loaded the poll-triggered flow {@code triggerFlow} and the
 * batch job {@code salesforce-to-database-Batch} it executes into the Mule runtime; this class starts the application
 * as a standalone process without a web server, as {@code application.yml} sets
 * ({@code spring.main.web-application-type: none}), because the flow has no HTTP listener. Salesforce is contacted
 * only when a poll runs, so the application starts with the {@code sfdc.*} credentials unset; the MySQL
 * {@code contact} table is reached through Boot's auto-configured {@code DataSource} and {@code JdbcTemplate}.
 *
 * <p>{@code @EnableScheduling} drives {@code trigger.ContactPollScheduler.poll()}, the replacement of the flow's
 * {@code poll} with its {@code fixed-frequency-scheduler}, every {@code poll.frequency} milliseconds (default 60000,
 * the original 60 seconds). Spring's default single-threaded scheduler is kept on purpose: it never starts a poll
 * while the previous one is still running, as the flow's {@code processingStrategy="synchronous"} did.
 */
@SpringBootApplication
@EnableScheduling
public class SalesforceToMySQLDBUsingBatchProcessingApplication {

    public static void main(String[] args) {
        SpringApplication.run(SalesforceToMySQLDBUsingBatchProcessingApplication.class, args);
    }
}
