/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.track_a_custom_business_event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.mulesoft.examples.track_a_custom_business_event.support.HttpTestClient;

/**
 * Port of the original example's {@code TrackACustomBusinessEventIT} functional test, keeping its request, the
 * committed {@code message.json} sent unchanged, and its three assertions; wire-level checks of status and headers
 * live in {@code controller/BusinessEventControllerTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TrackACustomBusinessEventTest {

    private static byte[] MESSAGE;

    @LocalServerPort
    private int port;

    @BeforeAll
    static void init() throws IOException {
        try (InputStream in = TrackACustomBusinessEventTest.class.getResourceAsStream("/message.json")) {
            assertNotNull(in, "message.json not on test classpath");
            MESSAGE = in.readAllBytes();
        }
    }

    @Test
    void calculateDiscountTest() throws Exception {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response response =
                client.send("POST", "/customBusinessEvents", Map.of("Content-Type", "application/json"), MESSAGE);
        assertNotNull(response);
        // A Mule NullPayload result corresponds to an empty HTTP body.
        assertFalse(response.body().length == 0);
        assertEquals("8.5", response.bodyAsString());
    }
}
