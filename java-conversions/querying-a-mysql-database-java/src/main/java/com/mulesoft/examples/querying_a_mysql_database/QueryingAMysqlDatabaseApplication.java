/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.querying_a_mysql_database;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point of the converted Mule example {@code querying-a-mysql-database} (Apache License 2.0, see
 * the project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, which loaded
 * {@code database-to-json.xml} and its single flow {@code database-to-jsonFlow} into the Mule runtime. This class
 * starts the application as a standalone process on Undertow; the package scan wires the flow's replacements:
 * {@code controller.EmployeeQueryController} for the HTTP listener on {@code /},
 * {@code service.EmployeeQueryService} for the query rendering and {@code object-to-json},
 * {@code client.EmployeeRepository} for {@code db:select}, and {@code exception.GlobalExceptionHandler} for the
 * platform-default errors. The listener port and address and the database URL ({@code jdbc.url}) come from
 * {@code application.yml}.
 */
@SpringBootApplication
public class QueryingAMysqlDatabaseApplication {

    public static void main(String[] args) {
        SpringApplication.run(QueryingAMysqlDatabaseApplication.class, args);
    }
}
