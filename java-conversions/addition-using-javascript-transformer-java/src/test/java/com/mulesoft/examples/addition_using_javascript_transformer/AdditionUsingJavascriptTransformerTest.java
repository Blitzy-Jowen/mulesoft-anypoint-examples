/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.addition_using_javascript_transformer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.mulesoft.examples.addition_using_javascript_transformer.support.HttpTestClient;

/**
 * Port of the original example's {@code AdditionUsingJavascriptTransformerIT} functional test, keeping its request
 * and its three assertions; wire-level checks of status and headers live in
 * {@code controller/CalculatorControllerTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdditionUsingJavascriptTransformerTest {

    private static final String MESSAGE = "{ \"a\" : 1, \"b\": 2 }";

    @LocalServerPort
    private int port;

    @Test
    void httpGetToFlowUrlSentMessage() {
        HttpTestClient client = HttpTestClient.localhost(port);
        // MuleClient sent the String payload as a UTF-8 text body.
        HttpTestClient.Response response = client.post("/", "text/plain;charset=UTF-8", MESSAGE);
        assertNotNull(response);
        // A Mule NullPayload result corresponds to an empty HTTP body.
        assertFalse(response.body().length == 0);
        assertEquals("Sum is: 3.0.", response.bodyAsString());
    }
}
