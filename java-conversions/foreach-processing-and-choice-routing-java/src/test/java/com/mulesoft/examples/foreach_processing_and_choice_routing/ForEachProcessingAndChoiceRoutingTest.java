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
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;

import com.mulesoft.examples.foreach_processing_and_choice_routing.support.HttpTestClient;
import com.mulesoft.examples.foreach_processing_and_choice_routing.support.PortInUseRetryingContextLoader;

/**
 * Port of the original example's {@code ForEachProcessingAndChoiceRoutingIT} functional test, keeping its request and
 * its assertions. The test runs end to end through the in-process SOAP services: the loan broker on the random main
 * port asks the credit agency, then Banks 1 and 2 (amount 20000 selects them), and answers the lowest quote as
 * {@code Bank #<n>, rate: <rate>}. Credit profiles and rates are random, so, as in the original, only the presence of
 * {@code Bank} and {@code rate} is asserted.
 *
 * <p>The credit agency and the five banks listen on additional Undertow listeners whose ports must be concrete and
 * unique, so this class draws six free ports for each load of its context and registers them in place of the fixed
 * defaults 18080 and 10080 to 50080; the outbound calls reach them through {@code localhost}. A port another process
 * takes between its draw and its bind fails that load, which {@link PortInUseRetryingContextLoader} repeats on newly
 * drawn ports.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(loader = PortInUseRetryingContextLoader.class)
class ForEachProcessingAndChoiceRoutingTest {

    private static final String MESSAGE = "name=Muley&amount=20000&term=48&ssn=1234";

    /** Property keys of the credit agency's and the five banks' listeners, in {@code application.yml} order. */
    private static final List<String> SOAP_PORT_KEYS = List.of("ports.credit-agency", "ports.bank1", "ports.bank2",
            "ports.bank3", "ports.bank4", "ports.bank5");

    /** The original's fixed listener ports, which this class never binds. */
    private static final Set<Integer> ORIGINAL_PORTS = Set.of(11081, 18080, 10080, 20080, 30080, 40080, 50080);

    @LocalServerPort
    private int port;

    /**
     * Registers one distinct free port per key, drawn for this load of the context: Spring invokes this method once
     * per load, and the clients and listeners of that context both read the registered values.
     */
    @DynamicPropertySource
    static void soapListenerPorts(DynamicPropertyRegistry registry) {
        List<Integer> soapPorts = allocateDistinctPorts(SOAP_PORT_KEYS.size());
        for (int i = 0; i < SOAP_PORT_KEYS.size(); i++) {
            int soapPort = soapPorts.get(i);
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
     * Free TCP ports, pairwise distinct and never an original port: {@link TestSocketUtils} checks each port on its
     * own, so two calls may return the same one, which the additional listeners reject.
     */
    private static List<Integer> allocateDistinctPorts(int count) {
        Set<Integer> ports = new LinkedHashSet<>();
        while (ports.size() < count) {
            int candidate = TestSocketUtils.findAvailableTcpPort();
            if (!ORIGINAL_PORTS.contains(candidate)) {
                ports.add(candidate);
            }
        }
        return List.copyOf(ports);
    }
}
