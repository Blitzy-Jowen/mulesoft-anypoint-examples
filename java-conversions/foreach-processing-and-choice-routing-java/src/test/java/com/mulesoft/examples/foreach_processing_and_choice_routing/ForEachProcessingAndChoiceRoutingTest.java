/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.foreach_processing_and_choice_routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;

import com.mulesoft.examples.foreach_processing_and_choice_routing.support.HttpTestClient;

/**
 * Port of the original example's {@code ForEachProcessingAndChoiceRoutingIT} functional test, keeping its request and
 * its assertions. The test runs end to end through the in-process SOAP services: the loan broker on the random main
 * port asks the credit agency, then Banks 1 and 2 (amount 20000 selects them), and answers the lowest quote as
 * {@code Bank #<n>, rate: <rate>}. Credit profiles and rates are random, so, as in the original, only the presence of
 * {@code Bank} and {@code rate} is asserted.
 *
 * <p>The credit agency and the five banks listen on additional Undertow listeners whose ports must be concrete and
 * unique, so this class allocates six free ports once and registers them in place of the fixed defaults 18080 and
 * 10080 to 50080; the outbound calls reach them through {@code localhost}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ForEachProcessingAndChoiceRoutingTest {

    private static final String MESSAGE = "name=Muley&amount=20000&term=48&ssn=1234";

    /** Property keys of the credit agency's and the five banks' listeners, in {@code application.yml} order. */
    private static final List<String> SOAP_PORT_KEYS = List.of("ports.credit-agency", "ports.bank1", "ports.bank2",
            "ports.bank3", "ports.bank4", "ports.bank5");

    /**
     * One distinct free port per key, allocated once for the class: a cached context keeps its listeners bound, so a
     * second allocation for the same context would point the clients at ports nothing listens on.
     */
    private static final List<Integer> SOAP_PORTS = allocateDistinctPorts(SOAP_PORT_KEYS.size());

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void soapListenerPorts(DynamicPropertyRegistry registry) {
        for (int i = 0; i < SOAP_PORT_KEYS.size(); i++) {
            int soapPort = SOAP_PORTS.get(i);
            registry.add(SOAP_PORT_KEYS.get(i), () -> soapPort);
        }
        // The listeners bind 0.0.0.0; the clients connect to them through the loopback name.
        registry.add("loanbroker.outbound-host", () -> "localhost");
    }

    @Test
    void httpGetToFlowUrlSentMessage() {
        // The original sent an empty body with http.method=GET.
        HttpTestClient.Response result = HttpTestClient.localhost(port).get("/?" + MESSAGE);
        assertNotNull(result);
        assertEquals(200, result.status(), result::toString);
        String payload = result.bodyAsString();
        assertFalse(payload.isEmpty(), "payload must not be empty (was NullPayload in Mule)");
        assertTrue(payload.contains("Bank"), () -> "payload must name the bank: " + payload);
        assertTrue(payload.contains("rate"), () -> "payload must carry the rate: " + payload);
    }

    /**
     * Free TCP ports, pairwise distinct: {@link TestSocketUtils} checks each port on its own, so two calls may return
     * the same one, which the additional listeners reject.
     */
    private static List<Integer> allocateDistinctPorts(int count) {
        Set<Integer> ports = new LinkedHashSet<>();
        while (ports.size() < count) {
            ports.add(TestSocketUtils.findAvailableTcpPort());
        }
        return List.copyOf(ports);
    }
}
