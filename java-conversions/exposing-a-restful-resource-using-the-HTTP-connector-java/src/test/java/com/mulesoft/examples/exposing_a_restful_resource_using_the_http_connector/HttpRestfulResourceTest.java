/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

import com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.support.HttpTestClient;

/**
 * Port of the original example's {@code HttpRestfulResourceIT} functional test, keeping its setup request and the
 * comparison semantics of its three assertions (exact for {@code getPerson} and {@code postPerson}, contains for
 * {@code getPersons}), plus the not-found branch of {@code retrievePersonFlow} that no original test covered.
 *
 * <p>The context is rebuilt before every test method: Mule's {@code FunctionalTestCase} started a fresh Mule context
 * per test, so the in-memory {@code HashMap} store and {@code AtomicInteger} counter were empty before each
 * {@code createPerson} and id 1 always existed after setup. A fresh Spring context per method reproduces that
 * whatever the JUnit 5 method order; a shared one would let {@code postPerson}'s id 2 reach {@code getPersons}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class HttpRestfulResourceTest {

    private static final String REPLY = "{ \"status\": \"success\", \"statusDescription\": \"person created successfully\"}";

    private static final String PERSON_RESOURCE = "/person.json";

    private static String PERSON_JSON;

    @LocalServerPort
    private int port;

    @BeforeAll
    static void init() throws IOException {
        try (InputStream in = Objects.requireNonNull(
                HttpRestfulResourceTest.class.getResourceAsStream(PERSON_RESOURCE),
                "test resource " + PERSON_RESOURCE + " is missing from the classpath")) {
            PERSON_JSON = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @BeforeEach
    void createPerson() {
        HttpTestClient.Response response =
                HttpTestClient.localhost(port).post("/person", "application/json", PERSON_JSON);
        assertEquals(201, response.status(), () -> "setup POST /person did not store the person: " + response);
    }

    @Test
    void getPerson() {
        HttpTestClient.Response response = HttpTestClient.localhost(port).get("/person/1");
        assertEquals(200, response.status(), response::toString);
        assertEquals(PERSON_JSON, response.bodyAsString());
    }

    @Test
    void postPerson() {
        HttpTestClient.Response response =
                HttpTestClient.localhost(port).post("/person", "application/json", PERSON_JSON);
        assertEquals(201, response.status(), response::toString);
        assertEquals(REPLY, response.bodyAsString());
    }

    @Test
    void getPersons() {
        HttpTestClient.Response response = HttpTestClient.localhost(port).get("/person");
        assertEquals(200, response.status(), response::toString);
        String body = response.bodyAsString();
        assertTrue(body.contains(PERSON_JSON), () -> "GET /person does not list the stored person: " + body);
    }

    @Test
    void getPersonNotFound() {
        HttpTestClient.Response response = HttpTestClient.localhost(port).get("/person/999");
        assertEquals("HTTP/1.1 404 Not Found", response.statusLine(), response::toString);
        assertEquals(404, response.status());
        assertEquals("Not Found", response.reason());
        assertEquals(0, response.body().length, response::toString);
    }
}
