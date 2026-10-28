/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.mulesoft.examples.service_orchestration_and_choice_routing.ServiceOrchestrationAndChoiceRoutingApplication;
import com.mulesoft.examples.service_orchestration_and_choice_routing.support.HttpTestClient;

/**
 * Wire-level test of {@link PopulateController}, the {@code databaseInitialisation} flow's
 * {@code http:listener allowedMethods="GET" path="populate"} on its own port ({@code fulfillment.xml:159-172}).
 *
 * <p>The whole application runs, so a request travels the same path as in production: the populate listener of
 * SC-10 {@code config/ListenerPorts}, {@code config/PortRoutingFilter}, the controller, and
 * {@code service/OrderOrchestrationService#populateDatabase()} executing both DDL statements against the embedded
 * Derby XA database. Every response is read through ST-1 {@link HttpTestClient}, so the raw status line, headers and
 * body bytes are asserted as sent on the wire.
 *
 * <p>Every listener port is a free port chosen here rather than an original port, so concurrent builds on one host
 * never collide. Each run gets its own in-memory Derby database, which starts without tables, and its own embedded
 * Artemis server ID, so the first {@code GET /populate} always creates the tables and no other context in the same
 * JVM shares either.
 */
@SpringBootTest(classes = ServiceOrchestrationAndChoiceRoutingApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PopulateControllerTest {

    private static final String POPULATE_PATH = "/populate";

    private static final int MAIN_PORT;

    private static final int AJAX_PORT;

    private static final int POPULATE_PORT;

    private static final int SAMSUNG_PORT;

    private static final int PRICES_PORT;

    private static final int ARTEMIS_SERVER_ID = ThreadLocalRandom.current().nextInt(1_000_000, Integer.MAX_VALUE);

    private static final String DERBY_DATABASE_NAME =
            "memory:PopulateControllerTest_" + UUID.randomUUID().toString().replace("-", "");

    static {
        // All five sockets stay open together, so the ports are distinct from one another.
        try (ServerSocket main = freeSocket();
                ServerSocket ajax = freeSocket();
                ServerSocket populate = freeSocket();
                ServerSocket samsung = freeSocket();
                ServerSocket prices = freeSocket()) {
            MAIN_PORT = main.getLocalPort();
            AJAX_PORT = ajax.getLocalPort();
            POPULATE_PORT = populate.getLocalPort();
            SAMSUNG_PORT = samsung.getLocalPort();
            PRICES_PORT = prices.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot reserve free listener ports", e);
        }
    }

    private static ServerSocket freeSocket() throws IOException {
        ServerSocket socket = new ServerSocket(0);
        socket.setReuseAddress(true);
        return socket;
    }

    /**
     * Moves every listener of the application to the ports chosen above and isolates the embedded stores.
     *
     * <p>The additional listener entries of {@code application.yml} read their ports from the placeholders
     * {@code ajax.port} and {@code ports.*}; those keys are overridden rather than {@code listeners.additional[i]}
     * itself, because Spring Boot binds an indexed list from a single property source and a partial override would
     * drop every other field of the entries.
     */
    @DynamicPropertySource
    static void listenerAndStoreProperties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> MAIN_PORT);
        registry.add("ajax.port", () -> AJAX_PORT);
        registry.add("ports.populate", () -> POPULATE_PORT);
        registry.add("ports.samsung", () -> SAMSUNG_PORT);
        registry.add("ports.prices", () -> PRICES_PORT);
        registry.add("orders.samsung.url", () -> "http://localhost:" + SAMSUNG_PORT + "/samsung/orders");
        registry.add("orders.price.url", () -> "http://localhost:" + PRICES_PORT + "/api/prices/{productId}");
        registry.add("orders.soap.url", () -> "http://localhost:" + MAIN_PORT + "/orders");
        registry.add("spring.datasource.xa.properties.databaseName", () -> DERBY_DATABASE_NAME);
        registry.add("spring.datasource.xa.properties.createDatabase", () -> "create");
        registry.add("spring.artemis.mode", () -> "embedded");
        registry.add("spring.artemis.embedded.persistent", () -> "false");
        registry.add("spring.artemis.embedded.server-id", () -> ARTEMIS_SERVER_ID);
    }

    /**
     * Both outcomes of the flow in one method, in the order the database state dictates: the first call creates
     * {@code orders} and {@code order_audits} and answers {@code db populated}, as the original
     * {@code populateDatabase} test asserts; the second finds them present, so the catch branch answers
     * {@code table already populated}, still with 200.
     *
     * <p>{@code PopulateController} sends no {@code Content-Type}, because the flow's {@code set-payload} declares no
     * MIME type and the Mule listener sent none for such a payload; the absent header is asserted, so a converter
     * adding one is caught.
     */
    @Test
    void populateCreatesTablesThenReportsAlreadyPopulated() {
        HttpTestClient populateListener = HttpTestClient.localhost(POPULATE_PORT);

        HttpTestClient.Response created = populateListener.get(POPULATE_PATH);

        assertThat(created.statusLine()).as(created::toString).isEqualTo("HTTP/1.1 200 OK");
        assertThat(created.header("Content-Type")).as(created::toString).isNull();
        assertThat(created.header("Content-Length")).as(created::toString).isEqualTo("12");
        assertThat(created.bodyAsString()).isEqualTo("db populated");

        HttpTestClient.Response repeated = populateListener.get(POPULATE_PATH);

        assertThat(repeated.statusLine()).as(repeated::toString).isEqualTo("HTTP/1.1 200 OK");
        assertThat(repeated.header("Content-Type")).as(repeated::toString).isNull();
        assertThat(repeated.bodyAsString()).isEqualTo("table already populated");
    }

    /**
     * The listener accepts {@code GET} alone: a {@code POST} gets the listener's 405, without an {@code Allow}
     * header, and runs no DDL.
     */
    @Test
    void postIsMethodNotAllowed() {
        HttpTestClient.Response response = HttpTestClient.localhost(POPULATE_PORT).post(POPULATE_PATH, null, "");

        assertThat(response.statusLine()).as(response::toString).isEqualTo("HTTP/1.1 405 Method Not Allowed");
        assertThat(response.header("Content-Type")).as(response::toString).startsWith("text/plain");
        assertThat(response.header("Allow")).as(response::toString).isNull();
        assertThat(response.bodyAsString()).isEqualTo("Method not allowed for endpoint: /populate");
    }

    /**
     * {@code /populate} belongs to the populate listener only: on the main listener, which serves the SOAP
     * {@code /orders} path alone, it is an unserved path and gets the no-listener 404 before any controller runs.
     */
    @Test
    void populatePathOnMainPortIsNoListener() {
        HttpTestClient.Response response = HttpTestClient.localhost(MAIN_PORT).get(POPULATE_PATH);

        assertThat(response.statusLine()).as(response::toString).isEqualTo("HTTP/1.1 404 Not Found");
        assertThat(response.header("Content-Type")).as(response::toString).startsWith("text/plain");
        assertThat(response.bodyAsString()).isEqualTo("No listener for endpoint: /populate");
    }
}
