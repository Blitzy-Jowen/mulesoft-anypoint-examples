/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Seeds the test database with the example's {@code import_orders.sql}, replacing the original test's
 * {@code MySQLDbCreator}, which created the {@code company} schema on a live MySQL server.
 *
 * <p>The unit tier runs on H2 in MySQL mode (profile {@code test}). {@link #seed(JdbcTemplate)} loads the complete
 * copied script from the classpath, a superset of the three employees {@code MySQLDbCreator} inserted, so every
 * per-employee query returns the rows it returned against MySQL. The script file itself is never changed; it is
 * adapted in memory, statement by statement:
 * <ul>
 *   <li>The MySQL administration statements {@code CREATE DATABASE}, {@code CREATE USER}, {@code GRANT},
 *       {@code FLUSH PRIVILEGES} and {@code USE} are skipped: the in-memory database the test context opened is
 *       already the target schema, and H2 has no MySQL accounts to create or grant.</li>
 *   <li>Every quoted two-digit date {@code 'YY-MM-DD'} is rewritten to {@code 'YYYY-MM-DD'} by MySQL's two-digit-year
 *       rule ({@code 00}-{@code 69} become {@code 2000}-{@code 2069}, {@code 70}-{@code 99} become
 *       {@code 1970}-{@code 1999}). H2 has no such pivot and would store {@code '07-11-27'} as year 7 instead of
 *       2007-11-27, the hire date the original assertion {@code Mona,Sosa,M,1950-09-26,2007-11-27} expects.</li>
 *   <li>Every other statement, the two {@code CREATE TABLE} statements included, runs unchanged in file order; H2's
 *       MySQL mode accepts their {@code ENUM}, {@code mediumint(8) unsigned ... auto_increment} and
 *       {@code AUTO_INCREMENT=1} syntax. A failing statement propagates its exception: nothing is skipped or ignored
 *       beyond the administration statements above.</li>
 * </ul>
 *
 * <p>Each call first drops the {@code roles} and {@code employees} tables, so it can run before every test of one
 * context and always leaves exactly the script's rows (102 employees, 100 roles).
 */
public final class ImportOrdersSeeder {

    /** Classpath location of the byte-identical copy of the original {@code import_orders.sql}. */
    private static final String SCRIPT = "import_orders.sql";

    /** A statement ends at a semicolon that closes its line or the script; no literal in the script holds one. */
    private static final Pattern STATEMENT_SEPARATOR = Pattern.compile(";\\s*(\\R|$)");

    /** A quoted two-digit-year date; the leading quote keeps it from matching inside a four-digit-year literal. */
    private static final Pattern TWO_DIGIT_DATE = Pattern.compile("'(\\d{2})-(\\d{2})-(\\d{2})'");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Leading keywords of the MySQL administration statements that are skipped. A statement matches when its
     * upper-cased, whitespace-normalized text equals a prefix or continues it with a space, so {@code USE company}
     * is skipped while a statement merely starting with the letters {@code USE} (such as {@code USER...}) is not.
     */
    private static final List<String> SKIPPED_PREFIXES =
            List.of("CREATE DATABASE", "CREATE USER", "GRANT", "FLUSH PRIVILEGES", "USE");

    private ImportOrdersSeeder() {
    }

    /**
     * Recreates the {@code employees} and {@code roles} tables and loads every row of {@code import_orders.sql}.
     *
     * @param jdbc the template over the test context's H2 database (MySQL mode)
     * @throws UncheckedIOException when {@code import_orders.sql} cannot be read from the classpath
     * @throws org.springframework.dao.DataAccessException when a statement fails; it is propagated unchanged
     */
    public static void seed(JdbcTemplate jdbc) {
        Objects.requireNonNull(jdbc, "jdbc");
        jdbc.execute("DROP TABLE IF EXISTS roles");
        jdbc.execute("DROP TABLE IF EXISTS employees");
        for (String statement : statements(readScript())) {
            if (!isAdministration(statement)) {
                jdbc.execute(expandTwoDigitYears(statement));
            }
        }
    }

    private static String readScript() {
        try (InputStream in = new ClassPathResource(SCRIPT).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read classpath resource " + SCRIPT, e);
        }
    }

    private static List<String> statements(String script) {
        List<String> statements = new ArrayList<>();
        for (String piece : STATEMENT_SEPARATOR.split(script)) {
            String statement = piece.trim();
            if (!statement.isEmpty()) {
                statements.add(statement);
            }
        }
        return statements;
    }

    private static boolean isAdministration(String statement) {
        String normalized = WHITESPACE.matcher(statement).replaceAll(" ").toUpperCase(Locale.ROOT);
        for (String prefix : SKIPPED_PREFIXES) {
            if (normalized.equals(prefix) || normalized.startsWith(prefix + " ")) {
                return true;
            }
        }
        return false;
    }

    private static String expandTwoDigitYears(String statement) {
        return TWO_DIGIT_DATE.matcher(statement).replaceAll(match -> {
            String century = Integer.parseInt(match.group(1)) <= 69 ? "20" : "19";
            return Matcher.quoteReplacement(
                    "'" + century + match.group(1) + "-" + match.group(2) + "-" + match.group(3) + "'");
        });
    }
}
