/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.querying_a_mysql_database.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Tests {@link EmployeeRepository}, the bind-parameter query that replaces the concatenated
 * {@code db:select} of the original {@code database-to-json.xml} (lines 7-8).
 *
 * <p>The live MySQL server and the database-creator helper of the original test are replaced by an
 * in-memory H2 database in MySQL mode, which profile {@code test} seeds from the unchanged
 * {@code import.sql}; each Spring context gets its own database.
 */
@JdbcTest
// Keeps the profile's MySQL-mode H2 URL; the default replacement would swap in a plain embedded H2.
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
// The @JdbcTest slice does not scan @Repository components.
@Import(EmployeeRepository.class)
class EmployeeRepositoryTest {

    @Autowired
    private EmployeeRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void seedsAllEmployeesFromImportSql() {
        assertEquals(102L, jdbcTemplate.queryForObject("select count(*) from employees", Long.class));
    }

    @Test
    void seedsAllRolesFromImportSql() {
        assertEquals(100L, jdbcTemplate.queryForObject("select count(*) from roles", Long.class));
    }

    @Test
    void findsPuckettFirstNamesInTableOrder() {
        List<Map<String, Object>> rows = repository.findFirstNamesByLastName("Puckett");

        assertEquals(2, rows.size());
        assertEquals("Chava", rows.get(0).get("first_name"));
        assertEquals("Quentin", rows.get(1).get("first_name"));
        for (Map<String, Object> row : rows) {
            assertEquals(Set.of("first_name"), row.keySet());
        }
    }

    @Test
    void returnsNoRowsForUnknownLastName() {
        List<Map<String, Object>> rows = repository.findFirstNamesByLastName("Nobody");

        assertNotNull(rows);
        assertTrue(rows.isEmpty());
    }

    @Test
    void returnsNoRowsForMissingParameterRendering() {
        // "null" is what EmployeeQueryService passes (MISSING_VALUE) when the request has no lastname,
        // the text MEL rendered for the absent parameter inside the original query.
        assertTrue(repository.findFirstNamesByLastName("null").isEmpty());
    }

    @Test
    void treatsHostileInputAsLiteralValue() {
        // Concatenated as in the original, this input became ... where last_name = "Puckett" or "1"="1",
        // which matches all 102 rows. As a bind parameter the whole string is one literal last name.
        assertTrue(repository.findFirstNamesByLastName("Puckett\" or \"1\"=\"1").isEmpty());
    }

    @Test
    void matchesLastNameCaseInsensitively() {
        List<Map<String, Object>> rows = repository.findFirstNamesByLastName("puckett");

        assertEquals(2, rows.size());
        assertEquals("Chava", rows.get(0).get("first_name"));
        assertEquals("Quentin", rows.get(1).get("first_name"));
    }

    @Test
    void usesBindParameterQuery() {
        assertEquals("select first_name from employees where last_name = ?",
                EmployeeRepository.SELECT_FIRST_NAMES_BY_LAST_NAME);
    }
}
