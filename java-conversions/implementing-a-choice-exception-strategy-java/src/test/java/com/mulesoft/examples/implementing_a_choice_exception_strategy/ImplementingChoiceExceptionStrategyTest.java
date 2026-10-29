/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.implementing_a_choice_exception_strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.mulesoft.examples.implementing_a_choice_exception_strategy.support.HttpTestClient;

/**
 * Port of the original example's {@code ImplementingChoiceExceptionStrategyIT} functional test, keeping its two
 * requests and its assertions with their comparison semantics: exact equality for the success reply, a string
 * comparison of the status code and containment for each fragment of the "Missing input data" reply. Wire-level checks
 * of reason phrases, headers and exact error bodies live in {@code controller/ValidationControllerTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class ImplementingChoiceExceptionStrategyTest {

    private static final String REPLY = "Input data validation passed.";
    private static final String[] REPLY_WRONG =
            {"Missing input data:", "item price per unit=1", "membership=free", "item name=aa", "item units=10"};

    @LocalServerPort
    private int port;

    private byte[] message;
    private byte[] messageWrong;

    @BeforeEach
    void init() {
        message = readResource("/message.json");
        messageWrong = readResource("/message_wrong.json");
    }

    @Test
    void inputValidationTest() {
        HttpTestClient client = HttpTestClient.localhost(port);
        // The original MuleClient sent the file content with http.method=POST.
        HttpTestClient.Response result =
                client.send("POST", "/", Map.of("Content-Type", "application/json"), message);
        assertNotNull(result);
        // A Mule NullPayload result corresponds to an empty HTTP body.
        assertFalse(result.bodyAsString().isEmpty());
        assertEquals(REPLY, result.bodyAsString());

        result = client.send("POST", "/", Map.of("Content-Type", "application/json"), messageWrong);
        assertNotNull(result);
        assertEquals("400", String.valueOf(result.status()));
        for (String item : REPLY_WRONG) {
            assertTrue(result.bodyAsString().contains(item), item);
        }
    }

    /**
     * Reads a test resource from the classpath as unchanged bytes, the files' tabs and trailing spaces included; a
     * missing or unreadable resource fails the test instead of leaving the request body unset.
     */
    private byte[] readResource(String name) {
        try (InputStream in = getClass().getResourceAsStream(name)) {
            assertNotNull(in, "missing test resource " + name);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read test resource " + name, e);
        }
    }
}
