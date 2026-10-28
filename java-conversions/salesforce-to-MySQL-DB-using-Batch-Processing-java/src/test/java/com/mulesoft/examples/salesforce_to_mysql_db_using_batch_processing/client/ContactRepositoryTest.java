/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Tests {@link ContactRepository}, the bind-parameter replacement of the three {@code db:} operations of the batch job
 * {@code salesforce-to-database-Batch} in the original {@code salesforce-to-database.xml}: the enricher's
 * {@code SELECT ... WHERE email=?} (line 28), the {@code INSERT} (line 40) and the {@code UPDATE ... WHERE email = ?}
 * (line 46).
 *
 * <p>The live MySQL {@code company} database and the {@code MySQLDbCreator} of the original integration test are
 * replaced by an in-memory H2 database in MySQL mode, which profile {@code test} creates from the unchanged
 * {@code contact.sql}; each Spring context gets its own database. {@code @JdbcTest} runs every test in a transaction
 * rolled back afterwards, so each test starts from the empty {@code contact} table.
 */
@JdbcTest
// Keeps the profile's MySQL-mode H2 URL; the default replacement would swap in a plain embedded H2.
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
// The @JdbcTest slice does not scan @Repository components.
@Import(ContactRepository.class)
class ContactRepositoryTest {

    /** Concatenated into a quoted SQL literal, this value would turn {@code WHERE email='...'} into a tautology. */
    private static final String HOSTILE_EMAIL = "' OR '1'='1";

    /** The columns of {@code contact.sql}, which every statement of the original flow names. */
    private static final List<String> CONTACT_COLUMNS = List.of("first_name", "last_name", "email");

    @Autowired
    private ContactRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void contactTableIsCreatedFromContactSql() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                "SELECT column_name, is_nullable FROM information_schema.columns"
                        + " WHERE LOWER(table_name) = 'contact' ORDER BY ordinal_position");

        assertThat(columns).extracting(column -> lowerCase(column.get("column_name")))
                .containsExactlyElementsOf(CONTACT_COLUMNS);
        assertThat(columns).extracting(column -> String.valueOf(column.get("is_nullable")))
                .containsExactly("YES", "YES", "NO");
        assertThat(countContacts()).isZero();
    }

    @Test
    void findByEmailReturnsNoRowsForUnknownEmail() {
        seed("John", "Doe", "john.doe@test.com");

        // An empty list is what makes the original enricher's payload.size() > 0, recordVars['exists'], false.
        assertThat(repository.findByEmail("unknown@test.com")).isNotNull().isEmpty();
    }

    @Test
    void findByEmailReturnsTheInsertedRowWithTheOriginalSelectColumns() {
        repository.insert("John", "Doe", "john.doe@test.com");

        List<Map<String, Object>> rows = repository.findByEmail("john.doe@test.com");

        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.keySet()).map(ContactRepositoryTest::lowerCase).containsExactlyElementsOf(CONTACT_COLUMNS);
        // Read as the original IT read its select result: payload.get(0).get("last_name").
        assertThat(row.get("first_name")).isEqualTo("John");
        assertThat(row.get("last_name")).isEqualTo("Doe");
        assertThat(row.get("email")).isEqualTo("john.doe@test.com");
    }

    @Test
    void findByEmailMatchesEmailCaseInsensitively() {
        // The original table's utf8_slovak_ci collation compares the email key case-insensitively;
        // the test profile's IGNORECASE=TRUE reproduces it.
        seed("John", "Doe", "john.doe@test.com");

        List<Map<String, Object>> rows = repository.findByEmail("John.Doe@TEST.com");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("email")).isEqualTo("john.doe@test.com");
    }

    @Test
    void insertStoresOneRow() {
        assertThat(repository.insert("John", "Doe", "john.doe@test.com")).isEqualTo(1);

        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT first_name, last_name FROM contact WHERE email = ?", "john.doe@test.com");
        assertThat(stored.get("first_name")).isEqualTo("John");
        assertThat(stored.get("last_name")).isEqualTo("Doe");
        assertThat(countContacts()).isEqualTo(1);
    }

    @Test
    void insertBindsNullFirstNameAsSqlNull() {
        // A Salesforce Contact without FirstName maps to first_name null; the column is DEFAULT NULL.
        assertThat(repository.insert(null, "Doe", "doe@test.com")).isEqualTo(1);

        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT first_name, last_name FROM contact WHERE email = ?", "doe@test.com");
        assertThat(stored).containsKey("first_name");
        assertThat(stored.get("first_name")).isNull();
        assertThat(stored.get("last_name")).isEqualTo("Doe");
    }

    @Test
    void updateChangesOnlyTheRowWithTheGivenEmail() {
        seed("John", "Doe", "john.doe@test.com");
        seed("Other", "Person", "other@test.com");

        assertThat(repository.update("Jane", "Roe", "john.doe@test.com")).isEqualTo(1);

        assertThat(namesOf("john.doe@test.com")).containsExactly("Jane", "Roe");
        assertThat(namesOf("other@test.com")).containsExactly("Other", "Person");
        assertThat(countContacts()).isEqualTo(2);
    }

    @Test
    void updateOfUnknownEmailChangesNothingAndInsertsNothing() {
        seed("John", "Doe", "john.doe@test.com");
        List<Map<String, Object>> before = allContacts();

        assertThat(repository.update("X", "Y", "unknown@test.com")).isZero();

        assertThat(allContacts()).isEqualTo(before);
    }

    @Test
    void insertWithNullEmailPropagatesDataIntegrityViolation() {
        // email is NOT NULL; the repository catches nothing, so the batch step records the record as failed.
        assertThatThrownBy(() -> repository.insert("A", "B", null))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(countContacts()).isZero();
    }

    @Test
    void insertOfExistingEmailPropagatesDuplicateKey() {
        repository.insert("John", "Doe", "john.doe@test.com");

        assertThatThrownBy(() -> repository.insert("Jane", "Roe", "john.doe@test.com"))
                .isInstanceOf(DuplicateKeyException.class);

        assertThat(namesOf("john.doe@test.com")).containsExactly("John", "Doe");
        assertThat(countContacts()).isEqualTo(1);
    }

    @Test
    void quotesInValuesAreBoundLiterally() {
        assertThat(repository.insert("Pat", "O'Brien", "o'brien@test.com")).isEqualTo(1);

        List<Map<String, Object>> rows = repository.findByEmail("o'brien@test.com");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("first_name")).isEqualTo("Pat");
        assertThat(rows.get(0).get("last_name")).isEqualTo("O'Brien");
        assertThat(rows.get(0).get("email")).isEqualTo("o'brien@test.com");
    }

    @Test
    void hostileEmailIsOneLiteralValueInSelect() {
        // Concatenated into "... WHERE email='<value>'" this would match every row; bound, it matches none.
        seed("John", "Doe", "john.doe@test.com");
        seed("Other", "Person", "other@test.com");

        assertThat(repository.findByEmail(HOSTILE_EMAIL)).isEmpty();
    }

    @Test
    void hostileEmailIsOneLiteralValueInUpdate() {
        seed("John", "Doe", "john.doe@test.com");
        seed("Other", "Person", "other@test.com");
        List<Map<String, Object>> before = allContacts();

        assertThat(repository.update("X", "Y", HOSTILE_EMAIL)).isZero();

        assertThat(allContacts()).isEqualTo(before);
    }

    static Stream<Arguments> statements() {
        return Stream.of(
                Arguments.of(ContactRepository.SELECT_BY_EMAIL, 1L),
                Arguments.of(ContactRepository.INSERT_CONTACT, 3L),
                Arguments.of(ContactRepository.UPDATE_CONTACT, 3L));
    }

    @ParameterizedTest
    @MethodSource("statements")
    void statementsUseBindParametersForEveryValue(String sql, long parameters) {
        assertThat(sql.chars().filter(c -> c == '?').count()).isEqualTo(parameters);
        // No MEL expression and no quoted literal: every value reaches the database as a bind parameter.
        assertThat(sql).containsIgnoringCase(" contact ").doesNotContain("#[").doesNotContain("'");
        for (String column : CONTACT_COLUMNS) {
            assertThat(sql).containsIgnoringCase(column);
        }
    }

    private void seed(String firstName, String lastName, String email) {
        jdbcTemplate.update("INSERT INTO contact (first_name, last_name, email) VALUES (?, ?, ?)",
                firstName, lastName, email);
    }

    private List<Object> namesOf(String email) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT first_name, last_name FROM contact WHERE email = ?", email);
        return List.of(row.get("first_name"), row.get("last_name"));
    }

    private List<Map<String, Object>> allContacts() {
        return jdbcTemplate.queryForList("SELECT first_name, last_name, email FROM contact ORDER BY email");
    }

    private long countContacts() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM contact", Long.class);
        return count == null ? 0L : count;
    }

    private static String lowerCase(Object identifier) {
        return String.valueOf(identifier).toLowerCase(Locale.ROOT);
    }
}
