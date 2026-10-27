/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_data_retrieval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point of the converted Mule example {@code salesforce-data-retrieval} (Apache License 2.0, see
 * the project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces {@code salesforce-data-retrieval/src/main/app/mule-deploy.properties}
 * ({@code config.resources=salesforce-id-retrieval.xml}), which loaded the flows {@code showFormFlow} and
 * {@code salesforceDataRetrievalFlow} into the Mule runtime; this class starts the application as a standalone
 * process on Undertow, with the listener port and address and the {@code sfdc.*} connection settings taken from
 * {@code application.yml}. Salesforce is contacted only when a request arrives, so the application starts with the
 * credentials unset.
 */
@SpringBootApplication
public class SalesforceDataRetrievalApplication {

    public static void main(String[] args) {
        SpringApplication.run(SalesforceDataRetrievalApplication.class, args);
    }
}
