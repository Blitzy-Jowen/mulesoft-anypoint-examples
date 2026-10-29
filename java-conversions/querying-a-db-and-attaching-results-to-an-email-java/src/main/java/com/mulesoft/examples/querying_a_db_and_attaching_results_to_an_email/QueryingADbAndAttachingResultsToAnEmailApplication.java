/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point of the converted Mule example {@code querying-a-db-and-attaching-results-to-an-email}
 * (Apache License 2.0, see the project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, which loaded
 * {@code attachments.xml} and its single flow {@code attachmentsFlow1} into the Mule runtime. This class starts the
 * application as a standalone process on Undertow; the package scan wires the flow's replacements:
 * {@code controller.EmployeeReportController} for the HTTP listener on {@code /},
 * {@code service.EmployeeReportService} for the splitter, the aggregation and the logger,
 * {@code client.EmployeeRepository} for {@code db:select}, {@code mapper.EmployeeCsvMapper} for the DataWeave CSV
 * transform, {@code client.ReportMailSender} for the {@code employees.csv} attachment and the SMTP send, and
 * {@code exception.GlobalExceptionHandler} for the platform-default errors. The listener port and address, the
 * database URL ({@code jdbc.url}) and the mail settings ({@code smtp.*}, {@code mail.*}) come from
 * {@code application.yml}.
 */
@SpringBootApplication
public class QueryingADbAndAttachingResultsToAnEmailApplication {

    public static void main(String[] args) {
        SpringApplication.run(QueryingADbAndAttachingResultsToAnEmailApplication.class, args);
    }
}
