/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.client.ReportMailSender;
import com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.exception.GlobalExceptionHandler;
import com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.support.HttpTestClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Wire-level test of the whole application started as it is shipped, from the main {@code application.yml} alone
 * with {@code jdbc.url} empty, as an unset {@code JDBC_URL} leaves it: the context starts with
 * {@link UnconfiguredDataSource}, and the original test's {@code message.xml} request fails at the first employee
 * lookup with the SC-1 500 {@code application/json} {@code { "message": ... }} carrying the message that names
 * {@code jdbc.url} and {@code JDBC_URL}. The flow stops there, so no report mail is attempted.
 *
 * <p>No profile is active, so neither the database URL nor the GreenMail account of profile {@code test} applies:
 * every {@code smtp.*} and {@code mail.*} secret stays empty, as in the shipped configuration, and is never used.
 * The request goes through the project's ST-1 {@link HttpTestClient}, so the status line, {@code Content-Type} and
 * body bytes are asserted as the listener writes them; the listener declares no {@code allowedMethods}, so
 * {@code POST} and {@code GET} with the same body are answered alike. The log oracle is a Logback
 * {@link ListAppender} on {@link GlobalExceptionHandler}'s logger, attached for each test and detached afterwards;
 * the handler's single ERROR line must carry the SQL state, error code and message of the data source's failure.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "jdbc.url=")
class UnconfiguredDataSourceListenerTest {

    private static final String NOT_CONFIGURED_MESSAGE =
            "MySQL JDBC URL (jdbc.url, environment variable JDBC_URL) is not configured";

    private static final Map<String, String> XML = Map.of("Content-Type", "application/xml");

    /** The original test's request body: Chava Puckett, Quentin Puckett and Mona Sosa. */
    private static final byte[] MESSAGE = classpathBytes("message.xml");

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    @SpyBean
    private ReportMailSender reportMailSender;

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

    /**
     * @param method the request method, sent with {@code message.xml} as its body
     */
    @ParameterizedTest
    @ValueSource(strings = {"POST", "GET"})
    void reportRequestFailsWithJson500NamingTheMissingKey(String method) {
        HttpTestClient.Response response = HttpTestClient.localhost(port).send(method, "/", XML, MESSAGE);

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

        verifyNoInteractions(reportMailSender);
    }

    private static byte[] classpathBytes(String location) {
        try {
            return new ClassPathResource(location).getContentAsByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read classpath resource " + location, e);
        }
    }
}
