/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.filtering_a_message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.mulesoft.examples.filtering_a_message.support.HttpTestClient;

/**
 * Port of the original example's {@code FilteringMessageIT} functional test, keeping its two {@code POST /}
 * requests and their assertions with the original comparison semantics: the granted text compared exactly, the
 * rejected message's reply checked for an empty body. Wire-level checks of status, reason phrase and headers live
 * in {@code controller/DiscountControllerTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FilteringMessageTest {

    @LocalServerPort
    private int port;

    /** Free member, 12 months, 2000 in purchases: accepted by the discount filter. */
    private String message;

    /** Free member, 6 months, 100 in purchases: rejected by the discount filter. */
    private String message1;

    // FilteringMessageIT.java lines 44-52, reading the fixtures from the test classpath.
    @BeforeEach
    void init() throws IOException {
        message = resource("/message.json");
        message1 = resource("/message1.json");
    }

    // FilteringMessageIT.java lines 54-68.
    @Test
    void testFiltering() {
        HttpTestClient client = HttpTestClient.localhost(port);

        HttpTestClient.Response response = client.post("/", "application/json", message);
        assertNotNull(response);
        assertEquals("the discount was granted.", response.bodyAsString());

        response = client.post("/", "application/json", message1);
        assertNotNull(response);
        // A filter rejection ends the flow with no payload, which reaches the caller as an empty body.
        assertTrue(response.bodyAsString().isEmpty());
    }

    private static String resource(String name) throws IOException {
        try (InputStream stream = FilteringMessageTest.class.getResourceAsStream(name)) {
            assertNotNull(stream, "test resource " + name + " is missing from the classpath");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
