/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.dataweave_with_flowreflookup;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point of the converted Mule example {@code dataweave-with-flowreflookup} (Apache License 2.0,
 * see the project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, whose
 * {@code config.resources=dataweave-with-flowref.xml} loaded two flows into the Mule runtime:
 * {@code CreateNewSalesforceAccountFlow} (polled CSV file, DataWeave mapping to Account maps, {@code sfdc:create}
 * of type {@code Account}) and {@code LookUpSalesRegionFlow} (the Groovy lookup of a sales region by billing state,
 * reached through DataWeave's {@code lookup}). This class starts the application as a standalone process without a
 * web server, as {@code application.yml} sets, because neither flow has an HTTP listener. Salesforce is contacted
 * only when a polled file is imported, so the application starts with the {@code sfdc.*} credentials unset.
 *
 * <p>{@code @EnableScheduling} drives {@code trigger.CompaniesFileTrigger.poll()}, the replacement of the flow's
 * {@code file:inbound-endpoint}, with {@code companies.polling-frequency} milliseconds between polls (default 10000,
 * the original {@code pollingFrequency}).
 */
@SpringBootApplication
@EnableScheduling
public class DataweaveWithFlowreflookupApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataweaveWithFlowreflookupApplication.class, args);
    }
}
