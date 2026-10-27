/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.querying_a_mysql_database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import com.mulesoft.examples.querying_a_mysql_database.support.HttpTestClient;

/**
 * Port of the original example's {@code QueryingMysqlDatabaseIT} functional test, keeping its request and its
 * three assertions, the reply compared as an exact string.
 *
 * <p>Profile {@code test} replaces the original's live MySQL server and its {@code MySQLDbCreator} set-up with an
 * in-memory H2 database in MySQL mode, seeded from the project's {@code import.sql}, so the test exercises the real
 * controller, service, repository and database chain without credentials or an external server.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class QueryingMysqlDatabaseTest {

    private static final String MESSAGE = "lastname=Puckett";

    private static final String REPLY = "[{\"first_name\":\"Chava\"},{\"first_name\":\"Quentin\"}]";

    @LocalServerPort
    private int port;

    @Test
    void httpGetToFlowUrlSentMessage() {
        HttpTestClient client = HttpTestClient.localhost(port);
        // The original sent an empty payload with http.method GET.
        HttpTestClient.Response response = client.send("GET", "/?" + MESSAGE, Map.of(), new byte[0]);
        assertNotNull(response);
        assertEquals(200, response.status(), response::toString);
        // A Mule NullPayload result corresponds to an empty HTTP body.
        assertTrue(response.body().length > 0, response::toString);
        assertEquals(REPLY, response.bodyAsString());
    }
}
