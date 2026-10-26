/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.querying_a_mysql_database.client;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The project's single access path to the {@code employees} table.
 *
 * <p>Replaces two elements of the original {@code database-to-json.xml}: the
 * {@code db:generic-config} of line 3, now Boot's auto-configured {@code DataSource} and
 * {@link JdbcTemplate} over {@code spring.datasource.url: ${jdbc.url}}, and the {@code db:select}
 * of lines 7-8.
 *
 * <p>The original built its query by interpolating the {@code lastname} query parameter into a
 * double-quoted literal, which MySQL reads as a string literal. Here the value is a bind parameter:
 * the comparison runs under the same column collation, so every legitimate input returns the same
 * rows, while input that would have broken out of the literal now matches nothing. This is the only
 * intentional difference from the original.
 *
 * <p>Failures are not caught: the original flow declares no exception strategy, so they reach the
 * platform-default 500 of the global exception handler.
 */
@Repository
public class EmployeeRepository {

    /**
     * The original query text with its MEL-interpolated literal replaced by one bind parameter.
     * No {@code ORDER BY}, so rows come back in database order, as in the original.
     */
    static final String SELECT_FIRST_NAMES_BY_LAST_NAME =
            "select first_name from employees where last_name = ?";

    private final JdbcTemplate jdbcTemplate;

    public EmployeeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Selects the first names of the employees whose last name equals {@code lastName}.
     *
     * @param lastName the value compared with {@code last_name}, passed to the database unchanged
     * @return one row per matching employee in database order, each a map keyed by the JDBC column
     *         label {@code first_name}, as the Mule database connector returned them; empty when
     *         none match
     */
    public List<Map<String, Object>> findFirstNamesByLastName(String lastName) {
        return jdbcTemplate.queryForList(SELECT_FIRST_NAMES_BY_LAST_NAME, lastName);
    }
}
