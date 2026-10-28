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
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
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
 * Wire-level controller test of {@link ProductPriceController}, the price service that replaces the
 * {@code priceService} flow: an {@code http:listener allowedMethods="POST" path="api"} on port 9999 in front of the
 * Jersey resource {@code ProductPrice}, which answers {@code /prices/{productId}} with {@code text/plain} {@code 159}
 * (service-orchestration-and-choice-routing/src/main/app/fulfillment.xml:144-149).
 *
 * <p>The whole application runs on Undertow with the real controller and service, and every request goes through
 * ST-1 {@link HttpTestClient}, so the raw status line, headers and body bytes are asserted as the listeners write
 * them. Each original listener gets a free port of its own, because SC-10's port routing confines
 * {@code /api/**} to the prices listener and answers it with the no-listener 404 on every other port.
 *
 * <p>The application context is isolated from the other Boot tests of this module: the listener ports, the embedded
 * Artemis server id and the in-memory Derby database are unique to this class, and the context is closed after it.
 */
@SpringBootTest(classes = ServiceOrchestrationAndChoiceRoutingApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProductPriceControllerTest {

    private static final int MAIN_PORT;
    private static final int AJAX_PORT;
    private static final int POPULATE_PORT;
    private static final int SAMSUNG_PORT;
    private static final int PRICES_PORT;

    /** In-VM acceptor id of this context's embedded Artemis broker, distinct from any other context in the JVM. */
    private static final int ARTEMIS_SERVER_ID = ThreadLocalRandom.current().nextInt(10_000, Integer.MAX_VALUE);

    /** In-memory Derby database of this context, so no state is shared with another test class. */
    private static final String DERBY_DATABASE = "memory:ProductPriceControllerTest_"
            + UUID.randomUUID().toString().replace("-", "");

    private static final String PRICE_PATH = "/api/prices/AX02";

    private static final String PRICE = "159";

    static {
        int[] ports = freePorts(5);
        MAIN_PORT = ports[0];
        AJAX_PORT = ports[1];
        POPULATE_PORT = ports[2];
        SAMSUNG_PORT = ports[3];
        PRICES_PORT = ports[4];
    }

    /**
     * The additional listeners' ports in {@code application.yml} are placeholders ({@code ajax.port},
     * {@code ports.populate}, {@code ports.samsung}, {@code ports.prices}), so overriding those keys moves every
     * {@code listeners.additional[]} entry without re-registering the list, which Boot binds from one property source.
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> MAIN_PORT);
        registry.add("ajax.port", () -> AJAX_PORT);
        registry.add("ports.populate", () -> POPULATE_PORT);
        registry.add("ports.samsung", () -> SAMSUNG_PORT);
        registry.add("ports.prices", () -> PRICES_PORT);
        registry.add("orders.samsung.url", () -> "http://localhost:" + SAMSUNG_PORT + "/samsung/orders");
        registry.add("orders.price.url", () -> "http://localhost:" + PRICES_PORT + "/api/prices/{productId}");
        registry.add("orders.soap.url", () -> "http://localhost:" + MAIN_PORT + "/orders");
        registry.add("spring.datasource.xa.properties.databaseName", () -> DERBY_DATABASE);
        registry.add("spring.datasource.xa.properties.createDatabase", () -> "create");
        registry.add("spring.artemis.mode", () -> "embedded");
        registry.add("spring.artemis.embedded.persistent", () -> "false");
        registry.add("spring.artemis.embedded.server-id", () -> ARTEMIS_SERVER_ID);
    }

    @Test
    void postReturnsPrice() {
        HttpTestClient.Response response = HttpTestClient.localhost(PRICES_PORT)
                .send("POST", PRICE_PATH, null, new byte[0]);

        assertThat(response.statusLine()).as(response::toString).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.header("Content-Type")).as(response::toString).startsWith("text/plain");
        assertThat(response.body()).as(response::toString).isEqualTo(PRICE.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void priceIsTheSameForEveryProduct() {
        HttpTestClient.Response response = HttpTestClient.localhost(PRICES_PORT)
                .send("POST", "/api/prices/ZZ99", null, new byte[0]);

        assertThat(response.statusLine()).as(response::toString).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.header("Content-Type")).as(response::toString).startsWith("text/plain");
        assertThat(response.body()).as(response::toString).isEqualTo(PRICE.getBytes(StandardCharsets.UTF_8));
    }

    // Tier 2A verification point: the POST-only listener fronts a JAX-RS @GET; the original GET answer is unconfirmed.
    @Test
    void getIsMethodNotAllowed() {
        HttpTestClient.Response response = HttpTestClient.localhost(PRICES_PORT).get(PRICE_PATH);

        assertThat(response.statusLine()).as(response::toString).isEqualTo("HTTP/1.1 405 Method Not Allowed");
        assertThat(response.header("Content-Type")).as(response::toString).startsWith("text/plain");
        assertThat(response.body()).as(response::toString)
                .isEqualTo(("Method not allowed for endpoint: " + PRICE_PATH).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void pricePathOnMainPortIsNoListener() {
        HttpTestClient.Response response = HttpTestClient.localhost(MAIN_PORT)
                .send("POST", PRICE_PATH, null, new byte[0]);

        assertThat(response.statusLine()).as(response::toString).isEqualTo("HTTP/1.1 404 Not Found");
        assertThat(response.header("Content-Type")).as(response::toString).startsWith("text/plain");
        assertThat(response.body()).as(response::toString)
                .isEqualTo(("No listener for endpoint: " + PRICE_PATH).getBytes(StandardCharsets.UTF_8));
    }

    /** Returns {@code count} distinct ports that were free when probed. */
    private static int[] freePorts(int count) {
        Set<Integer> ports = new LinkedHashSet<>();
        while (ports.size() < count) {
            try (ServerSocket socket = new ServerSocket()) {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(0));
                ports.add(socket.getLocalPort());
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot probe a free port", e);
            }
        }
        return ports.stream().mapToInt(Integer::intValue).toArray();
    }
}
