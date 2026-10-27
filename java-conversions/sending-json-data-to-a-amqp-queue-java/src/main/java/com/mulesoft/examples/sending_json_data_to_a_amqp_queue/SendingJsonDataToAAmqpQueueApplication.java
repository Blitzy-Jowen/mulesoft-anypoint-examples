/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_json_data_to_a_amqp_queue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point of the converted Mule example {@code sending-json-data-to-a-amqp-queue}.
 *
 * <p>Replaces the Mule deployment of {@code json-to-rabbitmq.xml}, the only flow file its
 * {@code mule-deploy.properties} selects: flow {@code json-to-rabbitmqFlow} takes {@code POST /}, logs the body and
 * publishes it request-response to AMQP exchange {@code sales_exchange} (queue {@code sales_queue}). The default
 * component scan from this package wires the classes that carry the flow:
 * <ul>
 *   <li>{@code controller.SalesController}, the HTTP listener;</li>
 *   <li>{@code service.SalesPublisherService}, the logger and the request-response AMQP send;</li>
 *   <li>{@code config.AmqpConfig}, the endpoint's destination and reply timeout, applied to the
 *       {@code RabbitTemplate} it declares, a {@code config.CorrelatedReplyRabbitTemplate} that returns only the
 *       reply correlated with its request;</li>
 *   <li>{@code exception.GlobalExceptionHandler}, the listener's and runtime's default error responses.</li>
 * </ul>
 *
 * <p>Nothing is configured here. The listener address and port, the broker host and the destination all come
 * from {@code application.yml}, and the Undertow server is selected by the project's dependencies. No scheduling,
 * async, caching or listener-container annotation is enabled: the flow only sends, and Boot's RabbitMQ
 * auto-configuration opens the broker connection lazily on the first publish, so the application starts without
 * a broker.
 */
@SpringBootApplication
public class SendingJsonDataToAAmqpQueueApplication {

    public static void main(String[] args) {
        SpringApplication.run(SendingJsonDataToAAmqpQueueApplication.class, args);
    }
}
