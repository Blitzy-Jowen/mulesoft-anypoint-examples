/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.hello_world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.mulesoft.examples.hello_world.support.HttpTestClient;

/**
 * Port of the original example's {@code HelloWorldIT} functional test, keeping its request and its three
 * assertions; wire-level checks of status and headers live in {@code controller/HelloWorldControllerTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HelloWorldTest {

    @LocalServerPort
    private int port;

    @Test
    void httpGetToFlowUrlEchoesSentMessage() {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response response = client.send("GET", "/helloWorld", Map.of(), new byte[0]);
        assertNotNull(response);
        // A Mule NullPayload result corresponds to an empty HTTP body.
        assertFalse(response.body().length == 0);
        assertEquals("Hello World", response.bodyAsString());
    }
}
