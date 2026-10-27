/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.get_customer_list_from_netsuite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point of the converted Mule example {@code get-customer-list-from-netsuite} (Apache License 2.0,
 * see the project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, which loaded
 * {@code get-customer-list-from-netsuite.xml} into the Mule runtime; this class starts the application as a
 * standalone process on Undertow, with the listener port and address taken from {@code application.yml}.
 *
 * <p>The web application type is left to Spring Boot's detection: Spring MVC and WebFlux are both on the classpath,
 * so Boot starts a servlet application, which the {@code /customers} controller and the exception handlers need;
 * WebFlux only supplies the {@code WebClient} the NetSuite clients call through. The NetSuite credentials are not
 * checked at start-up; the clients reject a missing value on the first call that needs it.
 */
@SpringBootApplication
public class GetCustomerListFromNetsuiteApplication {

    public static void main(String[] args) {
        SpringApplication.run(GetCustomerListFromNetsuiteApplication.class, args);
    }
}
