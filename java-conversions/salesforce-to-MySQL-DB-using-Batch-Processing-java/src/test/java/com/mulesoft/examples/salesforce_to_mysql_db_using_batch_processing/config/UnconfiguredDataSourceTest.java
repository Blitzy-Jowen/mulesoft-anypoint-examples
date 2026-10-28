/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLNonTransientConnectionException;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests {@link UnconfiguredDataSource}: both {@code getConnection} overloads fail with a
 * {@link SQLNonTransientConnectionException} carrying the message it was created with, SQL state {@code 08001}, error
 * code 0 and no cause; a {@code null} message is rejected when the data source is created.
 */
class UnconfiguredDataSourceTest {

    private static final String NOT_CONFIGURED_MESSAGE = "MySQL connection settings are not configured: "
            + "mysql.host (environment variable MYSQL_HOST), mysql.user (environment variable MYSQL_USER)";

    private static final UnconfiguredDataSource DATA_SOURCE = new UnconfiguredDataSource(NOT_CONFIGURED_MESSAGE);

    static Stream<Arguments> connectionAttempts() {
        return Stream.of(
                Arguments.of("getConnection()", (Executable) DATA_SOURCE::getConnection),
                Arguments.of("getConnection(username, password)",
                        (Executable) () -> DATA_SOURCE.getConnection("contact-sync-user", "Pa55-word-value")));
    }

    /**
     * @param overload the overload called, naming the case in the report
     * @param attempt  the call of that overload
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("connectionAttempts")
    void everyConnectionAttemptFailsNamingTheMissingKeys(String overload, Executable attempt) {
        SQLNonTransientConnectionException failure =
                assertThrows(SQLNonTransientConnectionException.class, attempt, overload);

        assertEquals(NOT_CONFIGURED_MESSAGE, failure.getMessage());
        assertEquals("08001", failure.getSQLState());
        assertEquals(0, failure.getErrorCode());
        assertNull(failure.getCause());
    }

    @Test
    void nullMessageIsRejected() {
        NullPointerException failure = assertThrows(NullPointerException.class, () -> new UnconfiguredDataSource(null));

        assertEquals("notConfiguredMessage", failure.getMessage());
    }
}
