/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.mulesoft.examples.service_orchestration_and_choice_routing.support.HttpTestClient;

/**
 * Port of the original {@code ServiceOrchestrationAndChoiceRoutingIT}: the whole application runs on Undertow, as the
 * original ran {@code fulfillment.xml} and {@code mule-config.xml}, and both original assertions are kept with their
 * comparison semantics. The SOAP order reply must equal {@code reply.xml} exactly, and {@code /populate} must answer a
 * payload containing {@code db populated}. Requests go over the wire through ST-1 {@link HttpTestClient}.
 *
 * <p>The fixed original ports 1080, 8090, 8091, 9090 and 9999 are replaced by free ports. A fresh in-memory Derby
 * database and a non-persistent embedded Artemis broker with a server id of its own give the class its own state,
 * which replaces the original's deletion of {@code ./muleEmbeddedDB} and {@code ./ObjectStore} before the run.
 */
@SpringBootTest(classes = ServiceOrchestrationAndChoiceRoutingApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ServiceOrchestrationAndChoiceRoutingTest {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceOrchestrationAndChoiceRoutingTest.class);

    private static final String TEXT_XML_UTF8 = "text/xml; charset=UTF-8";

    private static String MESSAGE;

    private static String REPLY;

    /** The main listener, which serves only the SOAP {@code /orders} path (originally port 1080). */
    @Value("${server.port}")
    private int mainPort;

    /** The {@code /populate} listener (originally port 8091). */
    @Value("${ports.populate}")
    private int populatePort;

    @BeforeAll
    static void loadMessages() throws IOException {
        MESSAGE = readClasspathResource("/message.xml");
        REPLY = readClasspathResource("/reply.xml");
    }

    /**
     * Binds every listener to a free port and gives the class its own Derby database and Artemis broker.
     *
     * <p>{@code application.yml} sets each additional listener's port through a placeholder ({@code ajax.port},
     * {@code ports.populate}, {@code ports.samsung}, {@code ports.prices}), so only those keys are overridden.
     * Overriding {@code listeners.additional[i].port} instead would make Spring Boot bind the whole list from this
     * property source alone and drop each entry's name and routes.
     */
    @DynamicPropertySource
    static void listenerPortsAndIsolatedStores(DynamicPropertyRegistry registry) throws IOException {
        int main;
        int ajax;
        int populate;
        int samsung;
        int prices;
        // All five sockets are open together so the ports are distinct; they close before the server binds them.
        try (ServerSocket mainSocket = new ServerSocket(0);
                ServerSocket ajaxSocket = new ServerSocket(0);
                ServerSocket populateSocket = new ServerSocket(0);
                ServerSocket samsungSocket = new ServerSocket(0);
                ServerSocket pricesSocket = new ServerSocket(0)) {
            main = mainSocket.getLocalPort();
            ajax = ajaxSocket.getLocalPort();
            populate = populateSocket.getLocalPort();
            samsung = samsungSocket.getLocalPort();
            prices = pricesSocket.getLocalPort();
        }
        registry.add("server.port", () -> main);
        registry.add("ajax.port", () -> ajax);
        registry.add("ports.populate", () -> populate);
        registry.add("ports.samsung", () -> samsung);
        registry.add("ports.prices", () -> prices);
        registry.add("orders.samsung.url", () -> "http://localhost:" + samsung + "/samsung/orders");
        registry.add("orders.price.url", () -> "http://localhost:" + prices + "/api/prices/{productId}");
        registry.add("orders.soap.url", () -> "http://localhost:" + main + "/orders");

        String databaseName = "memory:soacr-root-" + UUID.randomUUID();
        registry.add("spring.datasource.xa.properties.databaseName", () -> databaseName);
        registry.add("spring.datasource.xa.properties.createDatabase", () -> "create");

        // Boot numbers embedded Artemis servers from 0 upwards per JVM; an id from 1000 up stays clear of them, so
        // this class's in-VM acceptor never meets another cached context's broker.
        int artemisServerId = ThreadLocalRandom.current().nextInt(1_000, Integer.MAX_VALUE);
        registry.add("spring.artemis.embedded.persistent", () -> "false");
        registry.add("spring.artemis.embedded.server-id", () -> artemisServerId);
    }

    @Test
    void orderTest() {
        // The order holds one Samsung item (s-1 / AX02, quantity 1): the flow purchases it from the in-process Samsung
        // endpoint on the samsung listener (receipt id 1, ACCEPTED, totalPrice 2550.0) and makes no in-house JMS or
        // price call. The @Async audit of totalValue "02550" runs after the reply is built and cannot change it.
        HttpTestClient.Response response = HttpTestClient.localhost(mainPort).post("/orders", TEXT_XML_UTF8, MESSAGE);
        LOG.info("res: {}", response.bodyAsString());

        assertEquals(200, response.status(), response::toString);
        assertEquals(REPLY, response.bodyAsString());
    }

    @Test
    void populateDatabase() {
        HttpTestClient.Response response = HttpTestClient.localhost(populatePort).get("/populate");

        assertNotNull(response);
        assertEquals(200, response.status(), response::toString);
        String payload = response.bodyAsString();
        assertFalse(payload.isEmpty(), response::toString);
        assertTrue(payload.contains("db populated"), response::toString);
    }

    private static String readClasspathResource(String name) throws IOException {
        try (InputStream stream = ServiceOrchestrationAndChoiceRoutingTest.class.getResourceAsStream(name)) {
            assertNotNull(stream, () -> "Test resource " + name + " is missing from the classpath");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
