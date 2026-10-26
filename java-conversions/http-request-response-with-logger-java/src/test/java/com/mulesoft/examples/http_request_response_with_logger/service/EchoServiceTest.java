/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_request_response_with_logger.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Unit test of {@link EchoService}, which replaces the processors of flow {@code EchoFlow}
 * (http-request-response-with-logger/src/main/app/echo.xml): {@code set-payload} to the request path (line 6) and
 * {@code logger level="INFO" message="About to echo #[message.payload]"} (line 7).
 *
 * <p>Expected payloads and log lines are string literals, so the tests pin the original flow's bytes. The service
 * has no collaborators, so neither Spring nor Mockito is involved; the log line is captured by a Logback
 * {@link ListAppender} attached to the service's logger for the duration of each test.
 */
class EchoServiceTest {

    private EchoService service;

    private Logger logger;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        service = new EchoService();
        logger = (Logger) LoggerFactory.getLogger(EchoService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/message", "/", "/a/b"})
    void echoReturnsRequestPathUnchanged(String path) {
        // "/message" is the original HttpRequestResponseWithLoggerTest case: GET /message echoed "/message".
        assertThat(service.echo(path)).isEqualTo(path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/message", "/", "/a/b"})
    void echoLogsAboutToEchoAtInfo(String path) {
        service.echo(path);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage()).isEqualTo("About to echo " + path);
    }

    @Test
    void echoLogsThroughLoggerNamedAfterService() {
        service.echo("/message");

        assertThat(appender.list).singleElement()
                .extracting(ILoggingEvent::getLoggerName)
                .isEqualTo(EchoService.class.getName());
    }
}
