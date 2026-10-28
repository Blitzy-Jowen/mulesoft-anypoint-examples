/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_ms_dynamics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point of the converted Mule example {@code import-contacts-into-ms-dynamics} (Apache License 2.0,
 * see the project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, whose
 * {@code config.resources=import-contacts-into-ms-dynamics.xml} loaded the single flow {@code crm-create-account}
 * into the Mule runtime; this class starts the application as a standalone process without a web server, as
 * {@code application.yml} sets, because the flow has no HTTP listener. Microsoft Entra and the Dataverse Web API are
 * contacted only when a polled file is imported, so the application starts with the {@code dynamics.oauth.*}
 * credentials and {@code dynamics.service-url} unset.
 *
 * <p>{@code @EnableScheduling} drives {@code trigger.ContactsFileTrigger.poll()}, the replacement of the flow's
 * {@code file:inbound-endpoint}, with {@code contacts.polling-frequency} milliseconds between polls (default 10000,
 * the original {@code pollingFrequency}).
 */
@SpringBootApplication
@EnableScheduling
public class ImportContactsIntoMsDynamicsApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImportContactsIntoMsDynamicsApplication.class, args);
    }
}
