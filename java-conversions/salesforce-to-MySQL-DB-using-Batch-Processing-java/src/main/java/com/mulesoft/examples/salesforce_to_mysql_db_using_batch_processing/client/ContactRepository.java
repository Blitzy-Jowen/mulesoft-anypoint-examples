/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.client;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The project's single access path to the {@code contact} table of {@code contact.sql}.
 *
 * <p>Replaces the three database operations of the batch job {@code salesforce-to-database-Batch} in the original
 * {@code salesforce-to-database.xml}: the {@code db:select} of the enricher in step {@code queryExistingContactInDbStep}
 * (line 28) and the {@code db:insert} and {@code db:update} of step {@code upsertContactInDbStep} (lines 40 and 46).
 * The {@code db:mysql-config} of line 7 is Boot's auto-configured {@code DataSource} and {@link JdbcTemplate} over
 * {@code spring.datasource.*}; this class reads no configuration itself. While {@code mysql.host},
 * {@code mysql.user} or {@code mysql.password} is missing, {@code config.DataSourceConfig} supplies
 * {@code config.UnconfiguredDataSource} instead, so every call fails with a
 * {@code CannotGetJdbcConnectionException} naming the missing keys.
 *
 * <p>Each statement is the original parameterized query with every MEL expression replaced by a bind parameter, so
 * no record value is ever concatenated into SQL. A {@code null} value is bound as SQL {@code NULL}.
 *
 * <p>Nothing is caught or logged here: a {@code DataAccessException}, such as a {@code null} email violating
 * {@code email NOT NULL} or a duplicate primary key on insert, propagates to the batch step, where the batch runner
 * records the record as failed, as Mule batch did. No transaction is opened, as the original declared none.
 */
@Repository
public class ContactRepository {

    /** Line 28, with {@code #[payload.email]} as a bind parameter. */
    public static final String SELECT_BY_EMAIL = "SELECT first_name,last_name,email FROM contact WHERE email=?";

    /** Line 40, binding the payload's {@code first_name}, {@code last_name} and {@code email} in that order. */
    public static final String INSERT_CONTACT = "INSERT INTO contact (first_name, last_name, email) VALUES (?,?,?)";

    /** Line 46, binding the payload's {@code first_name}, {@code last_name} and {@code email} in that order. */
    public static final String UPDATE_CONTACT = "UPDATE contact SET first_name=?,last_name=? WHERE email = ?";

    private final JdbcTemplate jdbcTemplate;

    public ContactRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Selects the stored contact with the given email (line 28). The returned list is what the original enricher
     * stored as {@code recordVars['dbRecord']}, and its {@code size() > 0} is {@code recordVars['exists']}.
     *
     * @param email the email compared with the primary key {@code email}
     * @return the matching rows, each a map keyed by the JDBC column labels {@code first_name}, {@code last_name} and
     *         {@code email}; an empty list, never {@code null}, when none matches
     */
    public List<Map<String, Object>> findByEmail(String email) {
        return jdbcTemplate.queryForList(SELECT_BY_EMAIL, email);
    }

    /**
     * Inserts one contact row (line 40).
     *
     * @param firstName the value of {@code first_name}, nullable
     * @param lastName the value of {@code last_name}, nullable
     * @param email the value of the primary key {@code email}
     * @return the number of rows inserted
     */
    public int insert(String firstName, String lastName, String email) {
        return jdbcTemplate.update(INSERT_CONTACT, firstName, lastName, email);
    }

    /**
     * Updates the first and last name of the contact with the given email (line 46).
     *
     * @param firstName the new value of {@code first_name}, nullable
     * @param lastName the new value of {@code last_name}, nullable
     * @param email the email of the row to update, compared in the {@code WHERE} clause
     * @return the number of rows updated; 0 when no row has that email
     */
    public int update(String firstName, String lastName, String email) {
        return jdbcTemplate.update(UPDATE_CONTACT, firstName, lastName, email);
    }
}
