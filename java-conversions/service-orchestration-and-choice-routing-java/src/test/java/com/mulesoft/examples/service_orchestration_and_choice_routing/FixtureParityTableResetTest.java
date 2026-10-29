/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.mulesoft.examples.service_orchestration_and_choice_routing.service.OrderOrchestrationService;

/**
 * The table handling of the fixture replay (ST-2) against a real database. {@code get-populate-created} starts from a
 * database where neither {@code orders} nor {@code order_audits} exists and its own {@code GET /populate} creates
 * both, while its {@code response.tables} names them, so the reset before it must leave absent tables absent.
 * {@link FixtureParityTest.Replay#emptyExistingTables}, which the reset runs on every table a fixture names, empties
 * only the tables that exist, and {@link FixtureParityTest.Replay#requireTable}, which seeding runs on each table of
 * {@code state.tables}, rejects one that does not exist.
 *
 * <p>The helpers are called alone, on a connection to an in-memory Derby database of each test's own, created with
 * the original DDL of {@link OrderOrchestrationService} and dropped afterwards, in schema {@code APP} as the
 * application's. A decoy table {@code ORDERXAUDITS}, which the unescaped search pattern {@code ORDER_AUDITS} also
 * matches, keeps its row throughout. The application's XA {@code DataSource} hands out Narayana JTA connections, which
 * cannot report their schema, so one case runs through a wrapper that cannot either. This is an engine check, not
 * parity evidence: no fixture is read or written.
 */
class FixtureParityTableResetTest {

    private static final List<String> POPULATE_TABLES = List.of("orders", "order_audits");

    private static final String DECOY_TABLE = "ORDERXAUDITS";

    /** Derby's SQLState for a table that does not exist. */
    private static final String TABLE_NOT_FOUND = "42X05";

    private String url;

    private Connection connection;

    @BeforeEach
    void createDatabaseWithDecoy() throws SQLException {
        url = "jdbc:derby:memory:fixtureParityTableResetTest-" + UUID.randomUUID();
        connection = DriverManager.getConnection(url + ";create=true");
        execute("CREATE TABLE " + DECOY_TABLE + " (i int)");
        execute("INSERT INTO " + DECOY_TABLE + " (i) VALUES (1)");
    }

    @AfterEach
    void dropDatabase() throws SQLException {
        connection.close();
        SQLException dropped = assertThrows(SQLException.class, () -> DriverManager.getConnection(url + ";drop=true"));
        assertEquals("08006", dropped.getSQLState(), "Derby reports a successful drop as SQLState 08006");
    }

    @ParameterizedTest(name = "existing [{0}] -> emptied [{0}]")
    @CsvSource({
            "''",
            "orders",
            "order_audits",
            "orders;order_audits",
    })
    void emptiesTheExistingTablesAndLeavesAbsentOnesAbsent(String existing) throws SQLException {
        List<String> created = names(existing);
        for (String table : created) {
            createWithRow(table);
        }

        List<String> emptied = FixtureParityTest.Replay.emptyExistingTables(connection, POPULATE_TABLES);

        assertEquals(created, emptied, "the existing tables, in the order the fixture names them");
        for (String table : POPULATE_TABLES) {
            if (created.contains(table)) {
                assertEquals(0, count(table), table + " is emptied but kept");
            } else {
                assertAbsent(table);
            }
        }
        assertEquals(1, count(DECOY_TABLE), "the decoy is neither emptied nor taken for order_audits");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"orders", "ORDERS", "Orders"})
    void findsATableByItsUnquotedNameInAnyLetterCase(String fixtureName) throws SQLException {
        createWithRow("orders");

        assertEquals(List.of(fixtureName),
                FixtureParityTest.Replay.emptyExistingTables(connection, List.of(fixtureName)));
        assertEquals(0, count("orders"));
    }

    @Test
    void leavesAQuotedLowerCaseTableThatTheUnquotedNameDoesNotResolveTo() throws SQLException {
        execute("CREATE TABLE \"orders\" (i int)");
        execute("INSERT INTO \"orders\" (i) VALUES (1)");

        assertEquals(List.of(), FixtureParityTest.Replay.emptyExistingTables(connection, List.of("orders")),
                "an unquoted orders resolves to ORDERS, which does not exist");
        assertEquals(1, count("\"orders\""));
        assertAbsent("orders");
    }

    @Test
    void rejectsAnInvalidNameBeforeAnyStatementRuns() throws SQLException {
        createWithRow("orders");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> FixtureParityTest.Replay.emptyExistingTables(connection,
                        List.of("orders", "orders; DROP TABLE " + DECOY_TABLE)));

        assertTrue(failure.getMessage().contains("[A-Za-z0-9_]+"), failure.getMessage());
        assertEquals(1, count("orders"), "no table is emptied once a name is rejected");
        assertEquals(1, count(DECOY_TABLE));
    }

    @Test
    void asksTheDatabaseConnectionForTheSchemaWhenAWrapperCannotReportIt() throws SQLException {
        execute("CREATE SCHEMA OTHER");
        execute("CREATE TABLE OTHER.orders (i int)");
        execute("INSERT INTO OTHER.orders (i) VALUES (1)");
        createWithRow("order_audits");

        assertEquals(List.of("order_audits"),
                FixtureParityTest.Replay.emptyExistingTables(withoutSchema(connection), POPULATE_TABLES),
                "orders exists only in schema OTHER, not in the session's schema APP");
        assertEquals(0, count("order_audits"));
        assertEquals(1, count("OTHER.orders"));
        assertAbsent("orders");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"orders", "order_audits"})
    void seedingRejectsATableThatDoesNotExist(String table) throws SQLException {
        String what = "get-populate-already-populated: state.tables." + table;

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> FixtureParityTest.Replay.requireTable(connection, table, what));

        assertTrue(failure.getMessage().startsWith(what + " seeds rows into table " + table
                + ", which does not exist in the replay database"), failure.getMessage());
        assertTrue(failure.getMessage().contains("the replay creates no table"), failure.getMessage());
        assertAbsent(table);
        assertEquals(1, count(DECOY_TABLE));
    }

    @Test
    void seedingAcceptsTheTablesTheApplicationCreated() throws SQLException {
        execute(OrderOrchestrationService.ORDERS_DDL);
        execute(OrderOrchestrationService.ORDER_AUDITS_DDL);

        for (String table : POPULATE_TABLES) {
            assertDoesNotThrow(() -> FixtureParityTest.Replay.requireTable(connection, table, "state.tables." + table));
            assertTrue(FixtureParityTest.Replay.tableExists(connection, table), table);
        }
        assertFalse(FixtureParityTest.Replay.tableExists(connection, "order_audit"), "a prefix is not a match");
    }

    @ParameterizedTest(name = "[{0}] with escape [{1}] -> [{2}]")
    @CsvSource(nullValues = "NULL", value = {
            "order_audits,  \\,   order\\_audits",
            "a%b_c,         \\,   a\\%b\\_c",
            "a\\b,          \\,   a\\\\b",
            "orders,        \\,   orders",
            "order_audits,  '',   order_audits",
            "order_audits,  NULL, order_audits",
            "NULL,          \\,   NULL",
    })
    void escapesWildcardsAndTheEscapeItselfInASearchPattern(String name, String escape, String pattern) {
        assertEquals(pattern, FixtureParityTest.Replay.searchPattern(name, escape));
    }

    /** The table names written with {@code ;} between them; an empty list is {@code ''}. */
    private static List<String> names(String list) {
        return list == null || list.isEmpty() ? List.of() : Arrays.asList(list.split(";"));
    }

    /** Creates {@code orders} or {@code order_audits} with its original DDL and one row. */
    private void createWithRow(String table) throws SQLException {
        if (table.equals("orders")) {
            execute(OrderOrchestrationService.ORDERS_DDL);
            execute("INSERT INTO orders (product_id, name, manufacturer, quantity, price)"
                    + " VALUES ('AX02', 's-1', 'Philips', 1, 159)");
        } else {
            execute(OrderOrchestrationService.ORDER_AUDITS_DDL);
            execute("INSERT INTO order_audits (order_id, total_value) VALUES ('12', 2550)");
        }
    }

    /**
     * {@code delegate} behind a wrapper whose {@code getSchema()} throws an {@link SQLException} without a message, as
     * the JTA connection Narayana hands out from the application's XA {@code DataSource} does.
     */
    private static Connection withoutSchema(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(FixtureParityTableResetTest.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getSchema")) {
                        throw new SQLException();
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private int count(String table) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    /** Asserted through the database itself, independently of the helper's metadata lookup. */
    private void assertAbsent(String table) {
        SQLException missing = assertThrows(SQLException.class, () -> count(table), table + " stays absent");
        assertEquals(TABLE_NOT_FOUND, missing.getSQLState(), missing.getMessage());
    }
}
