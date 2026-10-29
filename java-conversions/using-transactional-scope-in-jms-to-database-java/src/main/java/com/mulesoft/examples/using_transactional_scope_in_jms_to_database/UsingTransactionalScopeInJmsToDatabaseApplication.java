/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.using_transactional_scope_in_jms_to_database;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point of the converted Mule example {@code using-transactional-scope-in-jms-to-database}
 * (Apache License 2.0, see the project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, whose
 * {@code config.resources=transactions.xml} loaded the single flow {@code transactionsFlow1} into the Mule runtime.
 * This class starts the application as a standalone process without a web server: the original has no HTTP
 * listener, and {@code application.yml} sets {@code spring.main.web-application-type: none}.
 *
 * <p>The default component scan from this package wires the classes that carry the flow:
 * <ul>
 *   <li>{@code config.TransactionConfig}, the transaction manager, the session-transacted listener container
 *       factory and the {@code JmsTemplate};</li>
 *   <li>{@code trigger.OrderQueueListener}, the JMS inbound endpoint on queue {@code in};</li>
 *   <li>{@code mapper.OrderXmlMapper}, the XML-to-{@code Order} transformer;</li>
 *   <li>{@code service.OrderTransactionService}, the transactional scope, with {@code service.TestComponent} and
 *       {@code client.OrderRepository} inside it.</li>
 * </ul>
 *
 * <p>No JMS, transaction, scheduling, async or caching annotation is declared here: Boot's JMS and transaction
 * auto-configuration already process {@code @JmsListener} and {@code @Transactional}, and the flow uses nothing else.
 */
@SpringBootApplication
public class UsingTransactionalScopeInJmsToDatabaseApplication {

    public static void main(String[] args) {
        SpringApplication.run(UsingTransactionalScopeInJmsToDatabaseApplication.class, args);
    }
}
