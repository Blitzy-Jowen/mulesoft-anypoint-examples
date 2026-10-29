/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point of the converted Mule example
 * {@code salesforce-data-synchronization-using-watermarking-and-batch-processing} (Apache License 2.0, see the
 * project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, whose
 * {@code config.resources=watermarking.xml} loaded the poll-triggered flow {@code triggerFlow} and the batch job
 * {@code watermarkingBatch} it executes into the Mule runtime; this class starts the application as a standalone
 * process without a web server, as {@code application.yml} sets, because the flow has no HTTP listener. Salesforce
 * is contacted only when a poll runs, so the application starts with the {@code sfdc.*} credentials unset.
 *
 * <p>{@code @EnableScheduling} drives {@code trigger.ContactPollScheduler.poll()}, the replacement of the flow's
 * {@code poll} with its {@code fixed-frequency-scheduler}, every {@code poll.frequency} milliseconds (default 10000,
 * the original 10 seconds). Spring's default single-threaded scheduler is kept on purpose: it never starts a poll
 * while the previous one is still running, as the flow's {@code processingStrategy="synchronous"} did.
 */
@SpringBootApplication
@EnableScheduling
public class SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingApplication {

    public static void main(String[] args) {
        SpringApplication.run(SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingApplication.class, args);
    }
}
