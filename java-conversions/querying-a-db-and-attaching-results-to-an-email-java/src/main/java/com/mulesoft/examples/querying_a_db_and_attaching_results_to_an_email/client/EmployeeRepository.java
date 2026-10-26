/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.client;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The project's single access path to the {@code employees} table.
 *
 * <p>Replaces two elements of the original {@code attachments.xml}: the {@code db:generic-config} of line 5, now
 * Boot's auto-configured {@code DataSource} and {@link JdbcTemplate} over
 * {@code spring.datasource.url: ${jdbc.url}}, and the {@code db:select} of lines 11-15, which the flow runs once per
 * split {@code <employee>} element. Splitting the employee text into first and last name belongs to the caller.
 *
 * <p>The original built its query by interpolating both names into double-quoted literals, which MySQL's default
 * {@code sql_mode} reads as string literals. Here each name is a bind parameter: the comparison runs under the same
 * column collation, so every legitimate input returns the same rows, while input that would have broken out of a
 * literal now matches nothing. This is the only intentional difference from the original.
 *
 * <p>Failures are not caught: the original flow declares no exception strategy, so every
 * {@link org.springframework.dao.DataAccessException} reaches the platform-default 500 of the global exception
 * handler through the calling service.
 */
@Repository
public class EmployeeRepository {

    /**
     * The original query text with its two MEL-interpolated literals replaced by bind parameters. The column list
     * and its order are the original's; there is no {@code ORDER BY}, so rows come back in database order.
     */
    public static final String SELECT_BY_FIRST_AND_LAST_NAME =
            "select first_name,last_name,gender,dob,hire_date from employees where first_name = ? and last_name = ?";

    /** Row key and column label of the employee's first name. */
    public static final String FIRST_NAME = "first_name";

    /** Row key and column label of the employee's last name. */
    public static final String LAST_NAME = "last_name";

    /** Row key and column label of the employee's gender ({@code M} or {@code F}). */
    public static final String GENDER = "gender";

    /** Row key and column label of the employee's date of birth. */
    public static final String DOB = "dob";

    /** Row key and column label of the employee's hire date. */
    public static final String HIRE_DATE = "hire_date";

    /*
     * Keys are inserted in the select list's order, so the row's iteration order is the column order the Mule
     * database connector returned. Dates are read as LocalDate rather than java.sql.Date or Timestamp, so no JVM or
     * session time zone can shift a stored DATE by a day before the CSV mapper renders it as yyyy-MM-dd. Columns are
     * read by label, which JDBC resolves case-insensitively, so upper- or lower-case identifiers both work.
     */
    private static final RowMapper<Map<String, Object>> ROW_MAPPER = (rs, rowNum) -> {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(FIRST_NAME, rs.getString(FIRST_NAME));
        row.put(LAST_NAME, rs.getString(LAST_NAME));
        // An ENUM('M','F') column on MySQL and H2 alike; getString is the portable read on both.
        row.put(GENDER, rs.getString(GENDER));
        row.put(DOB, rs.getObject(DOB, LocalDate.class));
        row.put(HIRE_DATE, rs.getObject(HIRE_DATE, LocalDate.class));
        return row;
    };

    private final JdbcTemplate jdbcTemplate;

    public EmployeeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Selects the employees whose first and last names equal the given values.
     *
     * <p>Both values are bind parameters, never concatenated into the SQL, so no input can change the query. The
     * comparison follows the database collation: MySQL's default collation is case-insensitive, exactly as the
     * original query's literals were compared.
     *
     * @param firstName the value compared with {@code first_name}, passed to the database unchanged
     * @param lastName  the value compared with {@code last_name}, passed to the database unchanged
     * @return one row per matching employee in database order, each a {@link LinkedHashMap} keyed, in this order,
     *         {@link #FIRST_NAME}, {@link #LAST_NAME}, {@link #GENDER} (each a {@link String}), {@link #DOB} and
     *         {@link #HIRE_DATE} (each a {@link LocalDate}), holding the values as stored; an empty list, never
     *         {@code null}, when none match
     * @throws org.springframework.dao.DataAccessException when the query fails (bad SQL, missing table, lost
     *         connection); it is propagated unchanged
     */
    public List<Map<String, Object>> findByFirstNameAndLastName(String firstName, String lastName) {
        return jdbcTemplate.query(SELECT_BY_FIRST_AND_LAST_NAME, ROW_MAPPER, firstName, lastName);
    }
}
