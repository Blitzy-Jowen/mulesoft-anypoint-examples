/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.querying_a_mysql_database.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLNonTransientConnectionException;
import java.util.stream.Stream;

import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests {@link UnconfiguredDataSource}: both {@code getConnection} overloads fail with the same
 * {@link SQLNonTransientConnectionException}, whose message names {@code jdbc.url} and {@code JDBC_URL}, whose SQL
 * state is {@code 08001} and whose error code is 0, the values the global exception handler reports.
 */
class UnconfiguredDataSourceTest {

    private static final UnconfiguredDataSource DATA_SOURCE = new UnconfiguredDataSource();

    static Stream<Arguments> connectionAttempts() {
        return Stream.of(
                Arguments.of("getConnection()", (Executable) DATA_SOURCE::getConnection),
                Arguments.of("getConnection(username, password)",
                        (Executable) () -> DATA_SOURCE.getConnection("mule", "mule")));
    }

    /**
     * @param overload the overload called, naming the case in the report
     * @param attempt  the call of that overload
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("connectionAttempts")
    void everyConnectionAttemptFailsNamingTheMissingKey(String overload, Executable attempt) {
        SQLNonTransientConnectionException failure =
                assertThrows(SQLNonTransientConnectionException.class, attempt, overload);

        assertEquals("MySQL JDBC URL (jdbc.url, environment variable JDBC_URL) is not configured",
                failure.getMessage());
        assertEquals("08001", failure.getSQLState());
        assertEquals(0, failure.getErrorCode());
        assertNull(failure.getCause());
    }
}
