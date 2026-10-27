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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLNonTransientConnectionException;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Tests {@link DataSourceConfig} together with the two Spring Boot auto-configurations it cooperates with,
 * {@link DataSourceAutoConfiguration} and {@link JdbcTemplateAutoConfiguration}, in an
 * {@link ApplicationContextRunner}.
 *
 * <p>A blank {@code spring.datasource.url} (absent, empty or whitespace only) starts the context with
 * {@link UnconfiguredDataSource} as the only {@code DataSource}, logs exactly one WARN naming {@code jdbc.url} and
 * {@code JDBC_URL} without any value, and makes the {@code JdbcTemplate}'s first query fail with Spring's
 * {@link CannotGetJdbcConnectionException} around the data source's {@link SQLNonTransientConnectionException}. A
 * configured URL leaves Spring Boot's Hikari pool in place and logs nothing.
 *
 * <p>The URL is set through a {@link MapPropertySource} rather than {@code withPropertyValues}, which trims values,
 * so whitespace-only URLs reach the condition unchanged. The log oracle is a Logback {@link ListAppender} on
 * {@link DataSourceConfig}'s logger, attached for each test and detached afterwards.
 */
class DataSourceConfigTest {

    private static final String NOT_CONFIGURED_MESSAGE =
            "MySQL JDBC URL (jdbc.url, environment variable JDBC_URL) is not configured";

    private static final String NOT_CONFIGURED_WARNING = "jdbc.url (environment variable JDBC_URL) is not "
            + "configured: the application starts without a database connection, and every database call fails "
            + "until it is supplied";

    /** An in-memory H2 database private to this test class, standing in for a configured MySQL URL. */
    private static final String H2_URL = "jdbc:h2:mem:data-source-config-test;MODE=MySQL";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                    JdbcTemplateAutoConfiguration.class))
            .withUserConfiguration(DataSourceConfig.class);

    private Logger logger;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(DataSourceConfig.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

    /**
     * @param url the value of {@code spring.datasource.url}; {@code null} leaves the property absent
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t \n"})
    void blankUrlStartsWithUnconfiguredDataSourceThatFailsAtUse(String url) {
        withDataSourceUrl(url).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeanNamesForType(DataSource.class).length);
            assertInstanceOf(UnconfiguredDataSource.class, context.getBean(DataSource.class));

            JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
            CannotGetJdbcConnectionException failure = assertThrows(CannotGetJdbcConnectionException.class,
                    () -> jdbcTemplate.queryForList("select 1"));
            SQLNonTransientConnectionException cause =
                    assertInstanceOf(SQLNonTransientConnectionException.class, failure.getCause());
            assertEquals(NOT_CONFIGURED_MESSAGE, cause.getMessage());
            assertEquals("08001", cause.getSQLState());
        });

        List<ILoggingEvent> events = appender.list;
        assertEquals(1, events.size(), events::toString);
        ILoggingEvent warning = events.get(0);
        assertEquals(Level.WARN, warning.getLevel());
        assertEquals(NOT_CONFIGURED_WARNING, warning.getFormattedMessage());
        assertTrue(warning.getFormattedMessage().contains("jdbc.url"));
        assertTrue(warning.getFormattedMessage().contains("JDBC_URL"));
        Object[] arguments = warning.getArgumentArray();
        assertTrue(arguments == null || arguments.length == 0, warning::toString);
        assertNull(warning.getThrowableProxy());
    }

    @Test
    void configuredUrlKeepsBootsHikariPoolAndLogsNothing() {
        withDataSourceUrl(H2_URL).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeanNamesForType(DataSource.class).length);
            HikariDataSource dataSource = assertInstanceOf(HikariDataSource.class, context.getBean(DataSource.class));
            assertEquals(H2_URL, dataSource.getJdbcUrl());
            assertEquals(1, context.getBean(JdbcTemplate.class).queryForObject("select 1", Integer.class));
        });

        assertTrue(appender.list.isEmpty(), appender.list::toString);
    }

    private ApplicationContextRunner withDataSourceUrl(String url) {
        if (url == null) {
            return contextRunner;
        }
        return contextRunner.withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("dataSourceUrl", Map.of("spring.datasource.url", url))));
    }
}
