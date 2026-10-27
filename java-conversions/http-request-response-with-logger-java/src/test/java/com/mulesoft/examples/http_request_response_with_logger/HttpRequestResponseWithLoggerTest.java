/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_request_response_with_logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.mulesoft.examples.http_request_response_with_logger.support.HttpTestClient;

/**
 * Port of the original example's {@code HttpRequestResponseWithLoggerTest} functional test over flow
 * {@code EchoFlow}, keeping its request and its three assertions; wire-level checks of status and headers live in
 * {@code controller/EchoControllerTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpRequestResponseWithLoggerTest {

    private static final String MESSAGE = "message";

    @LocalServerPort
    private int port;

    @Test
    void httpGetToFlowUrlEchoesSentMessage() {
        HttpTestClient.Response result =
                HttpTestClient.localhost(port).send("GET", "/" + MESSAGE, Map.of(), new byte[0]);
        assertNotNull(result);
        // A Mule NullPayload result corresponds to an empty HTTP body.
        assertFalse(result.bodyAsString().isEmpty());
        assertEquals("/" + MESSAGE, result.bodyAsString());
    }
}
