/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.using_transactional_scope_in_jms_to_database.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import javax.sql.DataSource;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test of {@link DataSourceConfig} and {@link UnconfiguredDataSource}, which let the application start while
 * {@code jdbc.url} (environment variable {@code JDBC_URL}) is blank.
 *
 * <p>The context cases run Boot's own {@code DataSource} and {@code JdbcTemplate} auto-configurations around
 * {@link DataSourceConfig}, as the application context does, and set {@code spring.datasource.url} directly, the key
 * {@code application.yml} binds to {@code jdbc.url}. The warning is read from a Logback list appender on
 * {@link DataSourceConfig}'s logger.
 */
class DataSourceConfigTest {

    /** The single startup warning: the key and the environment variable, never a value. */
    private static final String WARNING = "jdbc.url (environment variable JDBC_URL) is not configured: the application "
            + "starts without a database connection, and every database call fails until it is supplied";

    /** Message of every connection request {@link UnconfiguredDataSource} refuses. */
    private static final String NOT_CONFIGURED = "MySQL JDBC URL (jdbc.url, environment variable JDBC_URL) is not "
            + "configured";

    /** SQL state "SQL client unable to establish SQL connection". */
    private static final String SQL_STATE = "08001";

    /** A password passed to {@code getConnection(username, password)}; it must never reach the failure. */
    private static final String PASSWORD = "SENTINEL_password_5c1e";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                    JdbcTemplateAutoConfiguration.class))
            .withUserConfiguration(DataSourceConfig.class);

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(DataSourceConfig.class);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

    /**
     * A blank URL starts the context on {@link UnconfiguredDataSource} alone, with one warning, and the first
     * database call fails naming the key. The value is set through a property source of its own, because
     * {@code withPropertyValues} trims values and would turn the whitespace cases into the empty one.
     */
    @ParameterizedTest(name = "spring.datasource.url=\"{0}\"")
    @ValueSource(strings = {"", "   ", "\t"})
    void blankUrlStartsOnUnconfiguredDataSourceAndFailsFirstUse(String url) {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("blank-url", Map.of("spring.datasource.url", url))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DataSource.class);
                    assertThat(context).getBean(DataSource.class).isInstanceOf(UnconfiguredDataSource.class);
                    assertThat(context).doesNotHaveBean(HikariDataSource.class);

                    JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
                    assertThatThrownBy(() -> jdbcTemplate.queryForList("select 1"))
                            .isInstanceOf(CannotGetJdbcConnectionException.class)
                            .cause()
                            .isInstanceOfSatisfying(SQLNonTransientConnectionException.class, failure -> {
                                assertThat(failure.getMessage()).isEqualTo(NOT_CONFIGURED);
                                assertThat(failure.getSQLState()).isEqualTo(SQL_STATE);
                            });
                });

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).isEqualTo(WARNING).contains("jdbc.url", "JDBC_URL");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    /**
     * A configured URL leaves Boot's pooled {@code DataSource} in place, built from that URL, with no bean or
     * warning from {@link DataSourceConfig}.
     */
    @Test
    void configuredUrlKeepsBootPooledDataSource() {
        String url = "jdbc:h2:mem:datasource-config-" + UUID.randomUUID() + ";MODE=MySQL";

        runner.withPropertyValues("spring.datasource.url=" + url).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DataSource.class);
            assertThat(context.getBeanNamesForType(DataSource.class)).containsExactly("dataSource");
            assertThat(context).getBean(DataSource.class).isInstanceOfSatisfying(HikariDataSource.class,
                    dataSource -> assertThat(dataSource.getJdbcUrl()).isEqualTo(url));
            assertThat(context).doesNotHaveBean(UnconfiguredDataSource.class);
            assertThat(context.getBean(JdbcTemplate.class).queryForObject("select 1", Integer.class)).isEqualTo(1);
        });

        assertThat(appender.list).isEmpty();
    }

    /** Both connection overloads refuse with the same non-transient failure, which never echoes the credentials. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("connectionRequests")
    void everyConnectionRequestIsRefusedNamingTheKey(ConnectionRequest request) {
        assertThatThrownBy(() -> request.open(new UnconfiguredDataSource()))
                .isInstanceOfSatisfying(SQLNonTransientConnectionException.class, failure -> {
                    assertThat(failure.getMessage()).isEqualTo(NOT_CONFIGURED).doesNotContain(PASSWORD);
                    assertThat(failure.getCause()).isNull();
                    assertThat(failure.getSQLState()).isEqualTo(SQL_STATE);
                });
    }

    static Stream<Named<ConnectionRequest>> connectionRequests() {
        return Stream.of(
                Named.<ConnectionRequest>of("getConnection()", dataSource -> dataSource.getConnection()),
                Named.<ConnectionRequest>of("getConnection(username, password)",
                        dataSource -> dataSource.getConnection("mule", PASSWORD)));
    }

    /** One way of asking a {@code DataSource} for a connection. */
    @FunctionalInterface
    interface ConnectionRequest {

        Connection open(DataSource dataSource) throws SQLException;
    }
}
