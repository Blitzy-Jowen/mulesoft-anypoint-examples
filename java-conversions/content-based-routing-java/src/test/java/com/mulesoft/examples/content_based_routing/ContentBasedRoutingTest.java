/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.content_based_routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.mulesoft.examples.content_based_routing.support.HttpTestClient;

/**
 * Port of the original example's {@code ContentBasedRoutingIT} functional test, keeping its two requests, their
 * order and their assertions, plus one request for the choice's default branch, which the original leaves
 * untested; wire-level checks of status and headers live in {@code controller/GreetingControllerTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContentBasedRoutingTest {

    private static final String MESSAGE = "Spanish";
    private static final String MESSAGE1 = "French";

    @LocalServerPort
    private int port;

    @Test
    void httpGetToFlowUrlSentMessage() {
        HttpTestClient client = HttpTestClient.localhost(port);

        HttpTestClient.Response response = client.send("GET", "/?language=" + MESSAGE, Map.of(), new byte[0]);
        assertNotNull(response);
        // A Mule NullPayload result corresponds to an empty HTTP body.
        assertFalse(response.body().length == 0);
        assertEquals("Hola!", response.bodyAsString());

        response = client.send("GET", "/?language=" + MESSAGE1, Map.of(), new byte[0]);
        assertNotNull(response);
        assertFalse(response.body().length == 0);
        assertEquals("Bonjour!", response.bodyAsString());
    }

    @Test
    void httpGetWithoutLanguageRepliesInDefaultLanguage() {
        HttpTestClient client = HttpTestClient.localhost(port);

        HttpTestClient.Response response = client.send("GET", "/", Map.of(), new byte[0]);
        assertNotNull(response);
        assertFalse(response.body().length == 0);
        assertEquals("Hello!", response.bodyAsString());
    }
}
