/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Spring Boot entry point of the converted Mule 3.8 EE example {@code service-orchestration-and-choice-routing}
 * (Apache License 2.0, see the project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, whose
 * {@code config.resources=fulfillment.xml,mule-config.xml} loaded both flow files into the Mule runtime. This class
 * starts the application as one standalone Undertow process serving every original listener, with ports and
 * addresses taken from {@code application.yml}:
 * <ul>
 *   <li>SOAP {@code IProcessOrder} on 1080 {@code /orders} (POST only);</li>
 *   <li>the Mule Ajax pages on 8090 {@code /orders/} and their Bayeux endpoint {@code /ajax/cometd};</li>
 *   <li>HTTP database initialisation on 8091 {@code /populate} (GET only);</li>
 *   <li>SOAP {@code SamsungService} on 9090 {@code /samsung/orders} (POST only);</li>
 *   <li>HTTP price service on 9999 {@code /api/prices/{productId}};</li>
 * </ul>
 * together with the XA-transacted listener of the JMS queue {@code inhouseOrder} on the embedded Artemis broker.
 *
 * <p>{@link EnableAsync} replaces the one-way VM endpoint {@code audit}: {@code service.AuditService} inserts the
 * audit row on Boot's {@code applicationTaskExecutor} in its own JTA transaction, so it commits independently of the
 * order write. {@link EnableCaching} replaces the {@code ee:cache} scope and its in-memory
 * {@code ee:object-store-caching-strategy}: {@code service.PriceCacheService} caches prices by product ID in the
 * Caffeine cache {@code prices} that Boot builds from {@code spring.cache.*}.
 *
 * <p>JMS listeners, transaction management (Narayana JTA) and Spring WS are left to Boot's auto-configuration. Spring
 * WS is configured by the project's {@code WsConfigurer} ({@code config.WebServiceConfig}) alone, never through Spring
 * WS's own enabling annotation: that annotation switches off Boot's web-services auto-configuration and with it the
 * {@code MessageDispatcherServlet} that serves {@code /orders} and {@code /samsung/orders}.
 */
@SpringBootApplication
@EnableAsync
@EnableCaching
public class ServiceOrchestrationAndChoiceRoutingApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServiceOrchestrationAndChoiceRoutingApplication.class, args);
    }
}
