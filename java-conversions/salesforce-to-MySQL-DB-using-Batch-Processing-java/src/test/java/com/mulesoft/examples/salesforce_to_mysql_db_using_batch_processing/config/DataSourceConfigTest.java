/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLNonTransientConnectionException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
 * <p>Most cases set the datasource keys exactly as {@code application.yml} does, as placeholders over
 * {@code mysql.host}, {@code mysql.port}, {@code mysql.database}, {@code mysql.user} and {@code mysql.password}, and
 * vary the {@code mysql.*} values; the others override {@code spring.datasource.*} directly. A blank URL, or a
 * {@code jdbc:mysql:} URL without a host, user or password, starts the context with {@link UnconfiguredDataSource}
 * as the only {@code DataSource}, logs exactly one WARN naming every missing key and its environment variable and
 * no value, and makes the {@code JdbcTemplate}'s first query fail with Spring's
 * {@link CannotGetJdbcConnectionException} around the data source's {@link SQLNonTransientConnectionException},
 * whose message is the WARN's list. Complete MySQL settings leave Spring Boot's Hikari pool in place, unstarted, so
 * no connection is attempted, and log nothing; so does the H2 database of the tests, whose password is empty.
 *
 * <p>Values are set through a {@link MapPropertySource} rather than {@code withPropertyValues}, which trims values,
 * so whitespace-only settings reach the condition unchanged. The log oracle is a Logback {@link ListAppender} on
 * {@link DataSourceConfig}'s logger, attached for each test and detached afterwards.
 */
class DataSourceConfigTest {

    private static final String NOT_CONFIGURED_PREFIX = "MySQL connection settings are not configured: ";

    private static final String STARTUP_CONSEQUENCE = "; the application starts without a database connection, "
            + "and every database call fails until they are supplied";

    private static final String MISSING_HOST = "mysql.host (environment variable MYSQL_HOST)";

    private static final String MISSING_USER = "mysql.user (environment variable MYSQL_USER)";

    private static final String MISSING_PASSWORD = "mysql.password (environment variable MYSQL_PASSWORD)";

    /** Values that must never appear in a log line. */
    private static final String HOST = "db.example.test";

    private static final String USER = "contact-sync-user";

    private static final String PASSWORD = "Pa55-word-value";

    /** An in-memory H2 database in MySQL mode private to this class, as the {@code test} profile configures one. */
    private static final String H2_URL = "jdbc:h2:mem:data-source-config-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE";

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

    static Stream<Arguments> incompleteMySqlSettings() {
        return Stream.of(
                Arguments.of(Named.of("mysql.host empty", shipped("", USER, PASSWORD)), List.of(MISSING_HOST)),
                Arguments.of(Named.of("mysql.host whitespace", shipped(" \t", USER, PASSWORD)), List.of(MISSING_HOST)),
                Arguments.of(Named.of("mysql.user empty", shipped(HOST, "", PASSWORD)), List.of(MISSING_USER)),
                Arguments.of(Named.of("mysql.user whitespace", shipped(HOST, " ", PASSWORD)), List.of(MISSING_USER)),
                Arguments.of(Named.of("mysql.password empty", shipped(HOST, USER, "")), List.of(MISSING_PASSWORD)),
                Arguments.of(Named.of("mysql.host and mysql.user empty", shipped("", "", PASSWORD)),
                        List.of(MISSING_HOST, MISSING_USER)),
                Arguments.of(Named.of("mysql.user and mysql.password empty", shipped(HOST, "", "")),
                        List.of(MISSING_USER, MISSING_PASSWORD)),
                Arguments.of(Named.of("every mysql.* key empty, as shipped", shipped("", "", "")),
                        List.of(MISSING_HOST, MISSING_USER, MISSING_PASSWORD)),
                Arguments.of(Named.of("spring.datasource.url absent",
                        account(Map.of())), List.of(MISSING_HOST)),
                Arguments.of(Named.of("spring.datasource.* empty",
                        Map.of("spring.datasource.url", "", "spring.datasource.username", "",
                                "spring.datasource.password", "")),
                        List.of(MISSING_HOST, MISSING_USER, MISSING_PASSWORD)),
                Arguments.of(Named.of("spring.datasource.url whitespace",
                        account(Map.of("spring.datasource.url", " \n"))), List.of(MISSING_HOST)),
                Arguments.of(Named.of("upper-case URL without host",
                        account(Map.of("spring.datasource.url", "JDBC:MYSQL://:3306/company"))),
                        List.of(MISSING_HOST)),
                Arguments.of(Named.of("URL without authority",
                        account(Map.of("spring.datasource.url", "jdbc:mysql:///company"))), List.of(MISSING_HOST)),
                Arguments.of(Named.of("load-balanced URL without host",
                        account(Map.of("spring.datasource.url", "jdbc:mysql:loadbalance://:3306/company"))),
                        List.of(MISSING_HOST)),
                Arguments.of(Named.of("URL with user information but no host",
                        account(Map.of("spring.datasource.url", "jdbc:mysql://" + USER + "@:3306/company"))),
                        List.of(MISSING_HOST)),
                Arguments.of(Named.of("complete URL with blank spring.datasource.username",
                        Map.of("spring.datasource.url", "jdbc:mysql://" + HOST + "/company",
                                "spring.datasource.username", "", "spring.datasource.password", PASSWORD)),
                        List.of(MISSING_USER)));
    }

    /**
     * @param properties     the properties of the case
     * @param missingSettings the descriptions the message must list, in order
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("incompleteMySqlSettings")
    void incompleteSettingsStartWithUnconfiguredDataSourceThatFailsAtUse(Map<String, String> properties,
            List<String> missingSettings) {
        String expectedMessage = NOT_CONFIGURED_PREFIX + String.join(", ", missingSettings);

        withProperties(properties).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeanNamesForType(DataSource.class).length);
            assertInstanceOf(UnconfiguredDataSource.class, context.getBean(DataSource.class));

            JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
            CannotGetJdbcConnectionException failure = assertThrows(CannotGetJdbcConnectionException.class,
                    () -> jdbcTemplate.queryForList("SELECT first_name,last_name,email FROM contact WHERE email=?",
                            "john.doe@example.com"));
            SQLNonTransientConnectionException cause =
                    assertInstanceOf(SQLNonTransientConnectionException.class, failure.getCause());
            assertEquals(expectedMessage, cause.getMessage());
            assertEquals("08001", cause.getSQLState());
        });

        List<ILoggingEvent> events = appender.list;
        assertEquals(1, events.size(), events::toString);
        ILoggingEvent warning = events.get(0);
        assertEquals(Level.WARN, warning.getLevel());
        String line = warning.getFormattedMessage();
        assertEquals(expectedMessage + STARTUP_CONSEQUENCE, line);
        for (String value : List.of(HOST, USER, PASSWORD)) {
            assertFalse(line.contains(value), line);
        }
        Object[] arguments = warning.getArgumentArray();
        assertTrue(arguments == null || arguments.length == 0, warning::toString);
        assertNull(warning.getThrowableProxy());
    }

    static Stream<Arguments> completeMySqlSettings() {
        return Stream.of(
                Arguments.of(Named.of("every mysql.* key supplied", shipped(HOST, USER, PASSWORD)),
                        "jdbc:mysql://" + HOST + ":3306/company"),
                Arguments.of(Named.of("URL with an IPv6 host",
                        account(Map.of("spring.datasource.url", "jdbc:mysql://[::1]:3306/company"))),
                        "jdbc:mysql://[::1]:3306/company"),
                Arguments.of(Named.of("load-balanced URL",
                        account(Map.of("spring.datasource.url",
                                "jdbc:mysql:loadbalance://db1.example.test,db2.example.test/company"))),
                        "jdbc:mysql:loadbalance://db1.example.test,db2.example.test/company"),
                Arguments.of(Named.of("host in address form",
                        account(Map.of("spring.datasource.url",
                                "jdbc:mysql://address=(host=" + HOST + ")(port=3306)/company"))),
                        "jdbc:mysql://address=(host=" + HOST + ")(port=3306)/company"));
    }

    /**
     * @param properties the properties of the case
     * @param jdbcUrl    the resolved URL Boot's pool must carry
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("completeMySqlSettings")
    void completeSettingsKeepBootsHikariPoolWithoutConnecting(Map<String, String> properties, String jdbcUrl) {
        withProperties(properties).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeanNamesForType(DataSource.class).length);
            HikariDataSource dataSource = assertInstanceOf(HikariDataSource.class, context.getBean(DataSource.class));
            assertEquals(jdbcUrl, dataSource.getJdbcUrl());
            assertEquals(USER, dataSource.getUsername());
            // Boot's pool starts on its first connection request; none was made.
            assertFalse(dataSource.isRunning());
        });

        assertTrue(appender.list.isEmpty(), appender.list::toString);
    }

    static Stream<Arguments> nonMySqlUrls() {
        return Stream.of(
                Arguments.of(Named.of("H2 with sa and an empty password, as profile test sets it",
                        Map.of("spring.datasource.url", H2_URL, "spring.datasource.username", "sa",
                                "spring.datasource.password", ""))),
                Arguments.of(Named.of("H2 without an account",
                        Map.of("spring.datasource.url", H2_URL))));
    }

    /**
     * @param properties the properties of the case
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("nonMySqlUrls")
    void nonMySqlUrlKeepsBootsHikariPoolAndLogsNothing(Map<String, String> properties) {
        withProperties(properties).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeanNamesForType(DataSource.class).length);
            HikariDataSource dataSource = assertInstanceOf(HikariDataSource.class, context.getBean(DataSource.class));
            assertEquals(H2_URL, dataSource.getJdbcUrl());
            assertEquals(1, context.getBean(JdbcTemplate.class).queryForObject("SELECT 1", Integer.class));
        });

        assertTrue(appender.list.isEmpty(), appender.list::toString);
    }

    @Test
    void messageNeedsAtLeastOneMissingSetting() {
        assertThrows(IllegalArgumentException.class, () -> DataSourceConfig.notConfiguredMessage(List.of()));
    }

    /**
     * The datasource keys as {@code application.yml} writes them, over the given {@code mysql.*} values and the
     * original port and database.
     */
    private static Map<String, String> shipped(String host, String user, String password) {
        Map<String, String> properties = new HashMap<>();
        properties.put("spring.datasource.url", "jdbc:mysql://${mysql.host}:${mysql.port}/${mysql.database}");
        properties.put("spring.datasource.username", "${mysql.user}");
        properties.put("spring.datasource.password", "${mysql.password}");
        properties.put("mysql.host", host);
        properties.put("mysql.port", "3306");
        properties.put("mysql.database", "company");
        properties.put("mysql.user", user);
        properties.put("mysql.password", password);
        return Map.copyOf(properties);
    }

    /** {@code url} with a complete account set directly on {@code spring.datasource.*}. */
    private static Map<String, String> account(Map<String, String> url) {
        Map<String, String> properties = new HashMap<>(url);
        properties.put("spring.datasource.username", USER);
        properties.put("spring.datasource.password", PASSWORD);
        return Map.copyOf(properties);
    }

    private ApplicationContextRunner withProperties(Map<String, String> properties) {
        return contextRunner.withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("dataSourceSettings", Map.<String, Object>copyOf(properties))));
    }
}
