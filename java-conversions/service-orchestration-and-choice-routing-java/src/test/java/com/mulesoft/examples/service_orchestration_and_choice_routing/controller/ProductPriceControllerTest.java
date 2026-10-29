/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.mulesoft.examples.service_orchestration_and_choice_routing.ServiceOrchestrationAndChoiceRoutingApplication;
import com.mulesoft.examples.service_orchestration_and_choice_routing.service.ProductPriceService;
import com.mulesoft.examples.service_orchestration_and_choice_routing.support.HttpTestClient;
import com.mulesoft.examples.service_orchestration_and_choice_routing.support.PortInUseRetryingContextLoader;

/**
 * Wire-level controller test of {@link ProductPriceController}, the price service that replaces the
 * {@code priceService} flow: an {@code http:listener allowedMethods="POST" path="api"} on port 9999 in front of the
 * Jersey resource {@code ProductPrice}, which answers {@code /prices/{productId}} with {@code text/plain} {@code 159}
 * (service-orchestration-and-choice-routing/src/main/app/fulfillment.xml:144-149).
 *
 * <p>The whole application runs on Undertow with the real controller and service, and every request goes through
 * ST-1 {@link HttpTestClient}, so the raw status line, headers and body bytes are asserted as the listeners write
 * them. Each original listener gets a free port of its own, because SC-10's port routing confines
 * {@code /api/**}, {@code /api} included, to the prices listener and answers it with the no-listener 404 on every
 * other port.
 *
 * <p>The listener's {@code allowedMethods} are compared ignoring case, as Mule 3.8's {@code MethodRequestMatcher}
 * did, on the price path and on the listener path {@code /api} itself, where a {@code POST} reaches Jersey and finds
 * no resource.
 *
 * <p>The application context is isolated from the other Boot tests of this module: the listener ports, the embedded
 * Artemis server id and the in-memory Derby database are unique to this class, and the context is closed after it.
 * The ports are drawn for each load attempt of the context: a drawn port is free when drawn, not when bound, so when
 * another process takes one in between, {@link PortInUseRetryingContextLoader} loads the context again on newly drawn
 * ports, and the tests read the ports of the loaded context from it.
 */
@SpringBootTest(classes = ServiceOrchestrationAndChoiceRoutingApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ContextConfiguration(loader = PortInUseRetryingContextLoader.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProductPriceControllerTest {

    /** The listeners {@link #pricesPathOnOtherPortIsNoListener} sends the price paths to, by name. */
    private static final String MAIN_LISTENER = "main";
    private static final String POPULATE_LISTENER = "populate";

    /** In-VM acceptor id of this context's embedded Artemis broker, distinct from any other context in the JVM. */
    private static final int ARTEMIS_SERVER_ID = ThreadLocalRandom.current().nextInt(10_000, Integer.MAX_VALUE);

    /** In-memory Derby database of this context, so no state is shared with another test class. */
    private static final String DERBY_DATABASE = "memory:ProductPriceControllerTest_"
            + UUID.randomUUID().toString().replace("-", "");

    private static final String PRICE_PATH = "/api/prices/AX02";

    /** The listener's own {@code path}, on which no JAX-RS resource sits. */
    private static final String LISTENER_PATH = "/api";

    private static final String PRICE = "159";

    /** The real price service, spied so that a rejected request is shown never to reach it. */
    @SpyBean
    private ProductPriceService productPriceService;

    /** The main listener of the loaded context. */
    @LocalServerPort
    private int mainPort;

    /** The populate listener of the loaded context. */
    @Value("${ports.populate}")
    private int populatePort;

    /** The prices listener of the loaded context. */
    @Value("${ports.prices}")
    private int pricesPort;

    /**
     * Moves every listener onto distinct free ports drawn for this load attempt. The additional listeners' ports in
     * {@code application.yml} are placeholders ({@code ajax.port}, {@code ports.populate}, {@code ports.samsung},
     * {@code ports.prices}), so overriding those keys moves every {@code listeners.additional[]} entry without
     * re-registering the list, which Boot binds from one property source.
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        int[] ports = freePorts(5);
        int main = ports[0];
        int ajax = ports[1];
        int populate = ports[2];
        int samsung = ports[3];
        int prices = ports[4];
        registry.add("server.port", () -> main);
        registry.add("ajax.port", () -> ajax);
        registry.add("ports.populate", () -> populate);
        registry.add("ports.samsung", () -> samsung);
        registry.add("ports.prices", () -> prices);
        registry.add("orders.samsung.url", () -> "http://localhost:" + samsung + "/samsung/orders");
        registry.add("orders.price.url", () -> "http://localhost:" + prices + "/api/prices/{productId}");
        registry.add("orders.soap.url", () -> "http://localhost:" + main + "/orders");
        registry.add("spring.datasource.xa.properties.databaseName", () -> DERBY_DATABASE);
        registry.add("spring.datasource.xa.properties.createDatabase", () -> "create");
        registry.add("spring.artemis.mode", () -> "embedded");
        registry.add("spring.artemis.embedded.persistent", () -> "false");
        registry.add("spring.artemis.embedded.server-id", () -> ARTEMIS_SERVER_ID);
    }

    /**
     * The listener's {@code allowedMethods="POST"} was compared ignoring case, as Mule 3.8's
     * {@code MethodRequestMatcher} did, so every letter case of {@code POST} gets the price.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"POST", "post", "Post", "pOsT"})
    void postInAnyLetterCaseReturnsPrice(String method) {
        HttpTestClient.Response response = HttpTestClient.localhost(pricesPort)
                .send(method, PRICE_PATH, null, new byte[0]);

        assertThat(response.statusLine()).as(response::toString).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.header("Content-Type")).as(response::toString).isEqualTo("text/plain");
        assertThat(response.body()).as(response::toString).isEqualTo(PRICE.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void priceIsTheSameForEveryProduct() {
        HttpTestClient.Response response = HttpTestClient.localhost(pricesPort)
                .send("POST", "/api/prices/ZZ99", null, new byte[0]);

        assertThat(response.statusLine()).as(response::toString).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.header("Content-Type")).as(response::toString).startsWith("text/plain");
        assertThat(response.body()).as(response::toString).isEqualTo(PRICE.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Every method but {@code POST}, in upper or lower case, gets the listener's 405 on the price path and on the
     * listener path {@code /api} itself, with no {@code Allow} header; {@code HEAD} gets the same status and headers
     * without the body. {@code GET} on the price path is the in-house price lookup's request (Tier 2A verification
     * point: the POST-only listener fronts a JAX-RS {@code @GET}, and the original GET answer is unconfirmed).
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("methodsOutsideAllowedMethods")
    void methodOutsideAllowedMethodsIsMethodNotAllowed(String method, String path) {
        HttpTestClient.Response response = HttpTestClient.localhost(pricesPort).send(method, path, null, null);

        assertThat(response.statusLine()).as(response::toString).isEqualTo("HTTP/1.1 405 Method Not Allowed");
        assertThat(response.header("Content-Type")).as(response::toString).isEqualTo("text/plain");
        assertThat(response.header("Allow")).as(response::toString).isNull();
        byte[] expectedBody = "HEAD".equals(method)
                ? new byte[0]
                : ("Method not allowed for endpoint: " + path).getBytes(StandardCharsets.UTF_8);
        assertThat(response.body()).as(response::toString).isEqualTo(expectedBody);
        verifyNoInteractions(productPriceService);
    }

    private static Stream<Arguments> methodsOutsideAllowedMethods() {
        return Stream.of(PRICE_PATH, LISTENER_PATH).flatMap(path -> Stream
                .of("GET", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE", "PROPFIND", "get", "options")
                .map(method -> Arguments.of(method, path)));
    }

    /**
     * {@code POST /api}, in any letter case, passed the listener and reached Jersey, where no resource serves the
     * root: Jersey's 404 for an unmatched request, with no entity, so no Content-Type and a zero Content-Length
     * (Tier 2A verification point).
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"POST", "post"})
    void postToListenerPathIsJerseyNotFound(String method) {
        HttpTestClient.Response response = HttpTestClient.localhost(pricesPort)
                .send(method, LISTENER_PATH, null, new byte[0]);

        assertThat(response.statusLine()).as(response::toString).isEqualTo("HTTP/1.1 404 Not Found");
        assertThat(response.header("Content-Type")).as(response::toString).isNull();
        assertThat(response.header("Content-Length")).as(response::toString).isEqualTo("0");
        assertThat(response.body()).as(response::toString).isEmpty();
        verifyNoInteractions(productPriceService);
    }

    /**
     * The {@code /api} mapping is exact: {@code /api/} and other paths under {@code /api} that no mapping serves get
     * SC-1's no-listener 404 on the prices listener, even for {@code POST}.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/api/", "/api/other", "/api/prices"})
    void unmappedPathUnderListenerPathIsNoListener(String path) {
        HttpTestClient.Response response = HttpTestClient.localhost(pricesPort)
                .send("POST", path, null, new byte[0]);

        assertThat(response.statusLine()).as(response::toString).isEqualTo("HTTP/1.1 404 Not Found");
        assertThat(response.header("Content-Type")).as(response::toString).isEqualTo("text/plain");
        assertThat(response.body()).as(response::toString)
                .isEqualTo(("No listener for endpoint: " + path).getBytes(StandardCharsets.UTF_8));
        verifyNoInteractions(productPriceService);
    }

    /**
     * A CORS pre-flight on either path gets exactly what a plain {@code OPTIONS} request gets, the listener's 405,
     * whatever method it asks for, because the original listener applied no CORS policy; Spring's 403
     * {@code Invalid CORS request} never appears.
     */
    @ParameterizedTest(name = "{0}, Access-Control-Request-Method {1}")
    @MethodSource("preflights")
    void preflightIsAnsweredLikePlainOptions(String path, String requestedMethod) {
        HttpTestClient client = HttpTestClient.localhost(pricesPort);
        HttpTestClient.Response plain = client.send("OPTIONS", path, null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", path,
                Map.of("Origin", "https://example.invalid", "Access-Control-Request-Method", requestedMethod), null);

        assertThat(preflight.statusLine()).as(preflight::toString).isEqualTo("HTTP/1.1 405 Method Not Allowed");
        assertThat(preflight.statusLine()).as(preflight::toString).isEqualTo(plain.statusLine());
        assertThat(headerLines(preflight)).as(preflight::toString).isEqualTo(headerLines(plain));
        assertThat(preflight.body()).as(preflight::toString)
                .isEqualTo(("Method not allowed for endpoint: " + path).getBytes(StandardCharsets.UTF_8))
                .isEqualTo(plain.body());
        assertThat(preflight.headers()).as(preflight::toString).noneMatch(header ->
                header.getKey().regionMatches(true, 0, "Access-Control-", 0, "Access-Control-".length())
                        || header.getKey().equalsIgnoreCase("Vary"));
        verifyNoInteractions(productPriceService);
    }

    private static Stream<Arguments> preflights() {
        return Stream.of(PRICE_PATH, LISTENER_PATH)
                .flatMap(path -> Stream.of("POST", "GET").map(method -> Arguments.of(path, method)));
    }

    /**
     * The price path and {@code /api} belong to the prices listener only: on the main and populate listeners each
     * is an unserved path, answered by SC-10's port routing with the no-listener 404 before any controller runs.
     */
    @ParameterizedTest(name = "{0} listener {1}")
    @MethodSource("pricesPathsOnOtherPorts")
    void pricesPathOnOtherPortIsNoListener(String listener, String path) {
        HttpTestClient.Response response = HttpTestClient.localhost(portOf(listener))
                .send("POST", path, null, new byte[0]);

        assertThat(response.statusLine()).as(response::toString).isEqualTo("HTTP/1.1 404 Not Found");
        assertThat(response.header("Content-Type")).as(response::toString).isEqualTo("text/plain");
        assertThat(response.body()).as(response::toString)
                .isEqualTo(("No listener for endpoint: " + path).getBytes(StandardCharsets.UTF_8));
        verifyNoInteractions(productPriceService);
    }

    /**
     * The listeners by name, never by port: the arguments are built before the context draws its ports, and a
     * repeated load attempt draws them again.
     */
    private static Stream<Arguments> pricesPathsOnOtherPorts() {
        return Stream.of(MAIN_LISTENER, POPULATE_LISTENER)
                .flatMap(listener -> Stream.of(PRICE_PATH, LISTENER_PATH).map(path -> Arguments.of(listener, path)));
    }

    /** The port of the loaded context's listener named {@code listener}. */
    private int portOf(String listener) {
        return switch (listener) {
            case MAIN_LISTENER -> mainPort;
            case POPULATE_LISTENER -> populatePort;
            default -> throw new IllegalArgumentException("No listener named " + listener);
        };
    }

    /** Every header but {@code Date} as {@code name: value} with the name lower-cased, sorted, duplicates kept. */
    private static List<String> headerLines(HttpTestClient.Response response) {
        return response.headers().stream()
                .filter(header -> !header.getKey().equalsIgnoreCase("Date"))
                .map(header -> header.getKey().toLowerCase(Locale.ROOT) + ": " + header.getValue())
                .sorted()
                .toList();
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
