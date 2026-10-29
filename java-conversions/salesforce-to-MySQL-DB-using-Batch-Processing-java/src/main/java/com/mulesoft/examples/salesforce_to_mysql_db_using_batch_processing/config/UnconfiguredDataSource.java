/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.util.Objects;

import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * The {@code DataSource} {@link DataSourceConfig} supplies while {@code mysql.host}, {@code mysql.user} or
 * {@code mysql.password} (environment variables {@code MYSQL_HOST}, {@code MYSQL_USER}, {@code MYSQL_PASSWORD}) is
 * missing.
 *
 * <p>Without it, Boot's Hikari pool would receive the incomplete settings and fail inside Connector/J, or connect to
 * the driver's default host, without naming the application's key. With it, the context starts and holds no
 * connection, and every connection request fails with the message {@link DataSourceConfig} computed, which names
 * each missing key and its environment variable, so each database call reports the missing settings wherever its
 * failure is handled or kept: {@code JdbcTemplate} wraps it in a {@code CannotGetJdbcConnectionException}, and the
 * batch runner keeps that exception as the record's failure.
 *
 * <p>Each request throws a new {@link SQLNonTransientConnectionException} with SQL state {@code 08001} (the client
 * cannot establish the connection): retrying cannot succeed until the settings are supplied and the application
 * restarted. No MySQL connection is attempted. The class never logs. The startup warning belongs to
 * {@link DataSourceConfig}, and each failed call is logged by the code that handles it.
 */
final class UnconfiguredDataSource extends AbstractDataSource {

    /** The SQL state of every connection failure: the client cannot establish the connection. */
    static final String SQL_STATE = "08001";

    private final String notConfiguredMessage;

    /**
     * @param notConfiguredMessage the message of every connection failure: the missing settings, by key and
     *                             environment variable, never a value
     */
    UnconfiguredDataSource(String notConfiguredMessage) {
        this.notConfiguredMessage = Objects.requireNonNull(notConfiguredMessage, "notConfiguredMessage");
    }

    /**
     * Refuses the connection, since a MySQL connection setting is missing.
     *
     * @return never
     * @throws SQLNonTransientConnectionException always, with the not-configured message and SQL state
     *         {@code 08001}
     */
    @Override
    public Connection getConnection() throws SQLException {
        throw new SQLNonTransientConnectionException(notConfiguredMessage, SQL_STATE);
    }

    /**
     * Refuses the connection, since a MySQL connection setting is missing; the credentials are neither used nor
     * reported.
     *
     * @param username ignored
     * @param password ignored
     * @return never
     * @throws SQLNonTransientConnectionException always, with the not-configured message and SQL state
     *         {@code 08001}
     */
    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLNonTransientConnectionException(notConfiguredMessage, SQL_STATE);
    }
}
