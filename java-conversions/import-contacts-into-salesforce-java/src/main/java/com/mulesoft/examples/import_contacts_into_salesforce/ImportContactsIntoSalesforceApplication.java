/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_salesforce;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point of the converted Mule example {@code import-contacts-into-salesforce} (Apache License 2.0,
 * see the project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, whose
 * {@code config.resources=contacts-to-SFDC.xml} loaded the single flow {@code Contacts_to_SFDC} into the Mule
 * runtime; this class starts the application as a standalone process without a web server, as
 * {@code application.yml} sets, because the flow has no HTTP listener. Salesforce is contacted only when a polled
 * file is imported, so the application starts with the {@code sfdc.*} credentials unset.
 *
 * <p>{@code @EnableScheduling} drives {@code trigger.ContactsFileTrigger.poll()}, the replacement of the flow's
 * {@code file:inbound-endpoint}, with {@code contacts.polling-frequency} milliseconds between polls (default 10000,
 * the original {@code pollingFrequency}).
 */
@SpringBootApplication
@EnableScheduling
public class ImportContactsIntoSalesforceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImportContactsIntoSalesforceApplication.class, args);
    }
}
