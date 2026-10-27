/**
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

import java.util.List;

import javax.sql.DataSource;

import com.mulesoft.examples.querying_a_mysql_database.exception.GlobalExceptionHandler;
import com.mulesoft.examples.querying_a_mysql_database.support.HttpTestClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Wire-level test of the whole application started as it is shipped, from the main {@code application.yml} alone
 * with {@code jdbc.url} empty, as an unset {@code JDBC_URL} leaves it: the context starts with
 * {@link UnconfiguredDataSource}, and the original test's request {@code GET /?lastname=Puckett} fails at use with
 * the SC-1 500 {@code application/json} {@code { "message": ... }} carrying the message that names {@code jdbc.url}
 * and {@code JDBC_URL}.
 *
 * <p>No profile is active, so no database URL or SQL initialization of profile {@code test} applies;
 * {@code spring.sql.init.mode} stays {@code never}, as the main configuration sets it. The request goes through the
 * project's ST-1 {@link HttpTestClient}, so the status line, {@code Content-Type} and body bytes are asserted as the
 * listener writes them. The log oracle is a Logback {@link ListAppender} on {@link GlobalExceptionHandler}'s logger,
 * attached for each test and detached afterwards; the handler's single ERROR line must carry the SQL state, error
 * code and message of the data source's failure.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "jdbc.url=")
class UnconfiguredDataSourceListenerTest {

    private static final String NOT_CONFIGURED_MESSAGE =
            "MySQL JDBC URL (jdbc.url, environment variable JDBC_URL) is not configured";

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    private Logger logger;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void contextStartsWithUnconfiguredDataSource() {
        assertInstanceOf(UnconfiguredDataSource.class, dataSource);
    }

    @Test
    void queryFailsWithJson500NamingTheMissingKey() {
        HttpTestClient.Response response = HttpTestClient.localhost(port).get("/?lastname=Puckett");

        assertEquals("HTTP/1.1 500 Internal Server Error", response.statusLine(), response::toString);
        String contentType = response.header("Content-Type");
        assertNotNull(contentType, response::toString);
        assertTrue(contentType.startsWith(MediaType.APPLICATION_JSON_VALUE), response::toString);
        assertEquals("{ \"message\": \"" + NOT_CONFIGURED_MESSAGE + "\" }", response.bodyAsString(),
                response::toString);

        List<ILoggingEvent> events = appender.list;
        assertEquals(1, events.size(), events::toString);
        ILoggingEvent error = events.get(0);
        assertEquals(Level.ERROR, error.getLevel());
        String line = error.getFormattedMessage();
        assertTrue(line.contains("SQL state 08001"), line);
        assertTrue(line.endsWith("; SQL state 08001, error code 0: " + NOT_CONFIGURED_MESSAGE), line);
    }
}
