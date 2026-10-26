/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_json_data_to_a_jms_queue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point of the converted Mule example {@code sending-json-data-to-a-jms-queue}.
 *
 * <p>Replaces the Mule deployment of {@code json-to-jms.xml}, the only flow file its {@code mule-deploy.properties}
 * selects: flow {@code json-to-jmsFlow} takes {@code POST /sales} and sends the body to JMS queue {@code sales}.
 * The default component scan from this package wires the classes that carry the flow:
 * <ul>
 *   <li>{@code controller.SalesController}, the HTTP listener;</li>
 *   <li>{@code config.ListenerMethodFilter}, the listener's case-insensitive match of its allowed method;</li>
 *   <li>{@code service.SalesQueueService}, the transformer, the queue send and the logger;</li>
 *   <li>{@code config.ArtemisClientConfig}, the {@code jms.call-timeout} bound on each blocking call of the send;</li>
 *   <li>{@code exception.GlobalExceptionHandler}, the listener's and runtime's default error responses.</li>
 * </ul>
 *
 * <p>Nothing is configured here. The listener, the embedded broker standing in for Mule's in-VM ActiveMQ and the
 * queue name all come from {@code application.yml}, and the Undertow server is selected by the project's
 * dependencies. No scheduling, async, caching or listener annotation is enabled: the flow only sends, through the
 * {@code JmsTemplate} that Boot's JMS auto-configuration provides.
 */
@SpringBootApplication
public class SendingJsonDataToAJmsQueueApplication {

    public static void main(String[] args) {
        SpringApplication.run(SendingJsonDataToAJmsQueueApplication.class, args);
    }
}
