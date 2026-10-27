/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.querying_a_mysql_database.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;

import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * The {@code DataSource} {@link DataSourceConfig} supplies while {@code jdbc.url} (environment variable
 * {@code JDBC_URL}) is blank.
 *
 * <p>Without it, Boot's {@code DataSourceAutoConfiguration} stops the application at startup with
 * "Failed to configure a DataSource: 'url' attribute is not specified", which names neither the application's key
 * nor its environment variable. With it, the context starts and holds no connection, and every connection request
 * fails with {@link #NOT_CONFIGURED_MESSAGE}, which names both, so each database call reports the missing setting
 * wherever its failure is handled or logged.
 *
 * <p>Each request throws a new {@link SQLNonTransientConnectionException} with SQL state {@code 08001} (the client
 * cannot establish the connection): retrying cannot succeed until the URL is supplied and the application restarted.
 * The class never logs. The startup warning belongs to {@link DataSourceConfig}, and each failed call is logged by
 * the code that handles it.
 */
final class UnconfiguredDataSource extends AbstractDataSource {

    /** Message of every connection failure: the missing setting, by key and environment variable, never a value. */
    static final String NOT_CONFIGURED_MESSAGE = "MySQL JDBC URL (jdbc.url, environment variable JDBC_URL) is not configured";

    /**
     * Refuses the connection, since no database URL is configured.
     *
     * @return never
     * @throws SQLNonTransientConnectionException always, with {@link #NOT_CONFIGURED_MESSAGE} and SQL state
     *         {@code 08001}
     */
    @Override
    public Connection getConnection() throws SQLException {
        throw new SQLNonTransientConnectionException(NOT_CONFIGURED_MESSAGE, "08001");
    }

    /**
     * Refuses the connection, since no database URL is configured; the credentials are neither used nor reported.
     *
     * @param username ignored
     * @param password ignored
     * @return never
     * @throws SQLNonTransientConnectionException always, with {@link #NOT_CONFIGURED_MESSAGE} and SQL state
     *         {@code 08001}
     */
    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLNonTransientConnectionException(NOT_CONFIGURED_MESSAGE, "08001");
    }
}
