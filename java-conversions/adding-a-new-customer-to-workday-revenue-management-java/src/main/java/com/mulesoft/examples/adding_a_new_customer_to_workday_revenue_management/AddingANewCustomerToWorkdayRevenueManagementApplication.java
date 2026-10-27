/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.webservices.WebServicesAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Spring Boot entry point of the converted Mule example {@code adding-a-new-customer-to-workday-revenue-management}
 * (Apache License 2.0, see the project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, which loaded
 * {@code add_a_new_customer.xml} and its single flow {@code add-customer-flow} ({@code POST /}, which adds the
 * posted customer to Workday Revenue Management v25.0 with {@code Put_Customer}) into the Mule runtime. This class
 * starts the application as a standalone process on Undertow, with the listener port and address taken from
 * {@code application.yml}.
 *
 * <p>The project only consumes SOAP, so {@link WebServicesAutoConfiguration} is excluded: because
 * {@code spring-ws-core} carries {@code MessageDispatcherServlet}, it would register that servlet at
 * {@code /services/*}, a path the original listener never served. Requests there reach the Spring MVC dispatcher and
 * get the no-listener 404 like any other unserved path.
 *
 * <p>{@code @ConfigurationPropertiesScan} registers {@code config.WorkdayProperties}, the {@code wday.*} settings,
 * which is deliberately not a component.
 */
@SpringBootApplication(exclude = WebServicesAutoConfiguration.class)
@ConfigurationPropertiesScan
public class AddingANewCustomerToWorkdayRevenueManagementApplication {

    public static void main(String[] args) {
        SpringApplication.run(AddingANewCustomerToWorkdayRevenueManagementApplication.class, args);
    }
}
