/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.rest_api_with_apikit.support.HttpTestClient;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

/**
 * The original {@code RestApiWithApiKitTest} of {@code rest-api-with-apikit}, ported to the Spring Boot
 * application with its assertions and comparison semantics unchanged: GET {@code /api/teams/BAR} must return a
 * JSON tree equal to {@code reply.json} (key order and whitespace ignored), then DELETE of the same team must
 * answer 204.
 *
 * <p>The context is discarded after the class because the DELETE removes {@code BAR} from the in-memory league,
 * which a cached context would otherwise hand to later test classes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RestApiWithApiKitTest {

    private static final String MESSAGE = "teams/BAR";

    private static String REPLY;

    @LocalServerPort
    private int port;

    @BeforeAll
    static void init() throws IOException {
        try (InputStream in = RestApiWithApiKitTest.class.getResourceAsStream("/reply.json")) {
            assertNotNull(in, "test resource /reply.json not found on the classpath");
            REPLY = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void httpToGetRestApi() throws Exception {
        HttpTestClient client = HttpTestClient.localhost(port);

        HttpTestClient.Response result = client.get("/api/" + MESSAGE);
        assertNotNull(result);
        assertTrue(result.body().length > 0, () -> "GET returned no payload: " + result);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode tree1 = mapper.readTree(REPLY);
        JsonNode tree2 = mapper.readTree(result.bodyAsString());
        assertEquals(tree1, tree2, () -> "GET reply differs from reply.json: " + result);

        HttpTestClient.Response deleted = client.delete("/api/" + MESSAGE);
        assertNotNull(deleted);
        assertEquals(204, deleted.status(), () -> "DELETE did not answer 204: " + deleted);
    }
}
