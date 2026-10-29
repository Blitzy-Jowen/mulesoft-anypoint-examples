/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.querying_a_mysql_database.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import com.mulesoft.examples.querying_a_mysql_database.support.HttpTestClient;
import com.zaxxer.hikari.HikariDataSource;

import org.h2.tools.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Wire-level test of the application while its database becomes unreachable: once the pool holds connections, the
 * database server stops, and every following request to {@code /} must fail promptly with the SC-1 500
 * {@code application/json} {@code { "message": ... }}, as the original's non-pooled {@code db:generic-config} failed a
 * request as soon as its connection was refused, rather than after HikariCP's 30000 ms default connection timeout.
 *
 * <p>The database is an H2 TCP server (MySQL mode) on a free port, seeded from {@code import.sql} by profile
 * {@code test}, so it can be stopped under the running application. A stopped MySQL server's pooled connections fail
 * HikariCP's validation and are discarded, so each request waits for a new connection, the wait
 * {@code spring.datasource.hikari.connection-timeout} bounds. HikariCP does not treat H2's broken-connection errors
 * (SQL states 90067 and 90121) as fatal and keeps lending its dead connections, which fail at once; the test therefore
 * evicts the pooled connections after stopping the server, so that each request waits as it would against MySQL.
 * Requests go through the project's ST-1 {@link HttpTestClient}, with a read timeout above HikariCP's default, so a
 * lost setting fails the duration assertion instead of the read. The context is closed after the class, which closes
 * its pool.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext
class DatabaseOutageListenerTest {

    private static final String PUCKETT_REQUEST = "/?lastname=Puckett";

    /** The reply the original {@code QueryingMysqlDatabaseIT} asserts for {@code lastname=Puckett}. */
    private static final String PUCKETT_JSON = "[{\"first_name\":\"Chava\"},{\"first_name\":\"Quentin\"}]";

    /** Requests sent after the database stops, each waiting on an empty pool. */
    private static final int OUTAGE_REQUESTS = 2;

    /** Longest accepted outage reply: well above the configured 1000 ms, far below HikariCP's 30000 ms default. */
    private static final Duration FAILURE_BOUND = Duration.ofSeconds(5);

    /** Read budget of each request: above HikariCP's 30000 ms default, so its wait is measured, not cut off. */
    private static final int READ_TIMEOUT_MILLIS = 40_000;

    /** SC-1's unhandled-exception body carrying a message. */
    private static final Pattern ERROR_BODY = Pattern.compile("\\{ \"message\": \".+\" \\}");

    private static Server database;

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    @BeforeAll
    static void startDatabase() throws SQLException {
        database = Server.createTcpServer("-tcpPort", "0", "-ifNotExists").start();
    }

    @AfterAll
    static void stopDatabase() {
        if (database != null) {
            database.stop();
        }
    }

    /**
     * Points {@code jdbc.url} at a fresh in-memory database of the TCP server, with the URL settings of profile
     * {@code test}.
     *
     * @param registry the registry of the test context's dynamic properties
     */
    @DynamicPropertySource
    static void databaseUrl(DynamicPropertyRegistry registry) {
        String name = "outage-" + UUID.randomUUID();
        registry.add("jdbc.url", () -> "jdbc:h2:tcp://localhost:" + database.getPort() + "/mem:" + name
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;IGNORECASE=TRUE;DB_CLOSE_DELAY=-1");
    }

    @Test
    void requestsFailPromptlyWithJson500WhileDatabaseIsUnreachable() {
        HttpTestClient client = HttpTestClient.localhost(port).withReadTimeout(READ_TIMEOUT_MILLIS);

        HttpTestClient.Response established = client.get(PUCKETT_REQUEST);
        assertEquals("HTTP/1.1 200 OK", established.statusLine(), established::toString);
        assertEquals(PUCKETT_JSON, established.bodyAsString(), established::toString);

        database.stop();
        HikariDataSource pool = assertInstanceOf(HikariDataSource.class, dataSource);
        pool.getHikariPoolMXBean().softEvictConnections();

        for (int request = 1; request <= OUTAGE_REQUESTS; request++) {
            long start = System.nanoTime();
            HttpTestClient.Response failed = client.get(PUCKETT_REQUEST);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            String attempt = "outage request " + request + " answered after " + elapsed.toMillis() + " ms: " + failed;
            assertEquals("HTTP/1.1 500 Internal Server Error", failed.statusLine(), attempt);
            String contentType = failed.header("Content-Type");
            assertNotNull(contentType, attempt);
            assertTrue(contentType.startsWith(MediaType.APPLICATION_JSON_VALUE), attempt);
            assertTrue(ERROR_BODY.matcher(failed.bodyAsString()).matches(), attempt);
            assertTrue(elapsed.compareTo(FAILURE_BOUND) < 0, attempt);
        }
    }
}
