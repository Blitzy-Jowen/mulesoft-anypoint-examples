/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.client;

import static com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.client.EmployeeRepository.DOB;
import static com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.client.EmployeeRepository.FIRST_NAME;
import static com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.client.EmployeeRepository.GENDER;
import static com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.client.EmployeeRepository.HIRE_DATE;
import static com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.client.EmployeeRepository.LAST_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.support.ImportOrdersSeeder;

/**
 * Client test of {@link EmployeeRepository}, the bind-parameter replacement of the original {@code attachments.xml}
 * {@code db:generic-config} (line 5) and its concatenated {@code db:select} (lines 11-15).
 *
 * <p>The live MySQL server and the original test's {@code MySQLDbCreator} are replaced by an in-memory H2 database in
 * MySQL mode (profile {@code test}), loaded before every test with the whole copied {@code import_orders.sql} through
 * {@link ImportOrdersSeeder}, a superset of the three employees the original test inserted.
 */
@JdbcTest
// Keeps the profile's MySQL-mode H2 URL; the default replacement would swap in a plain embedded H2.
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
// The @JdbcTest slice does not scan @Repository components.
@Import(EmployeeRepository.class)
// The seeder's DROP, CREATE and INSERT statements run outside a test-managed transaction; every test re-seeds.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EmployeeRepositoryTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EmployeeRepository repository;

    @BeforeEach
    void seed() {
        ImportOrdersSeeder.seed(jdbcTemplate);
    }

    @Test
    void chavaPuckettRowHasOriginalColumnsInOrder() {
        List<Map<String, Object>> rows = repository.findByFirstNameAndLastName("Chava", "Puckett");

        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.keySet()).containsExactly("first_name", "last_name", "gender", "dob", "hire_date");
        assertThat(row).containsExactly(
                entry(FIRST_NAME, "Chava"),
                entry(LAST_NAME, "Puckett"),
                entry(GENDER, "F"),
                entry(DOB, LocalDate.of(1985, 9, 2)),
                entry(HIRE_DATE, LocalDate.of(2008, 10, 12)));
        assertThat(row.get(DOB)).isInstanceOf(LocalDate.class);
        assertThat(row.get(HIRE_DATE)).isInstanceOf(LocalDate.class);
    }

    @Test
    void twoDigitYearHireDatesAreNormalized() {
        // import_orders.sql lines 83-84 store '08-09-15' and '07-11-27', which MySQL reads as 2008 and 2007; the
        // original attachment assertion expects Mona,Sosa,M,1950-09-26,2007-11-27.
        List<Map<String, Object>> quentin = repository.findByFirstNameAndLastName("Quentin", "Puckett");
        assertThat(quentin).hasSize(1);
        assertThat(quentin.get(0)).containsExactly(
                entry(FIRST_NAME, "Quentin"),
                entry(LAST_NAME, "Puckett"),
                entry(GENDER, "F"),
                entry(DOB, LocalDate.of(1971, 10, 21)),
                entry(HIRE_DATE, LocalDate.of(2008, 9, 15)));

        List<Map<String, Object>> mona = repository.findByFirstNameAndLastName("Mona", "Sosa");
        assertThat(mona).hasSize(1);
        assertThat(mona.get(0)).containsExactly(
                entry(FIRST_NAME, "Mona"),
                entry(LAST_NAME, "Sosa"),
                entry(GENDER, "M"),
                entry(DOB, LocalDate.of(1950, 9, 26)),
                entry(HIRE_DATE, LocalDate.of(2007, 11, 27)));
    }

    @Test
    void seedLoadsWholeScript() {
        assertThat(jdbcTemplate.queryForObject("select count(*) from employees", Long.class)).isEqualTo(102L);
        assertThat(jdbcTemplate.queryForObject("select count(*) from roles", Long.class)).isEqualTo(100L);
    }

    @Test
    void unknownNameReturnsEmptyList() {
        List<Map<String, Object>> rows = repository.findByFirstNameAndLastName("No", "Body");

        assertThat(rows).isNotNull().isEmpty();
    }

    @Test
    void injectionStringIsTreatedAsData() {
        // Concatenated as in the original, this first name produced
        // where first_name = "x" or "1"="1" and last_name = "Puckett", which matched both Puckett rows.
        // As a bind parameter the whole string is one literal first name.
        List<Map<String, Object>> rows = repository.findByFirstNameAndLastName("x\" or \"1\"=\"1", "Puckett");

        assertThat(rows).isEmpty();
        assertThat(EmployeeRepository.SELECT_BY_FIRST_AND_LAST_NAME)
                .endsWith("where first_name = ? and last_name = ?");
    }

    @Test
    void lookupIsCaseInsensitiveLikeMySql() {
        // MySQL's default collation compares case-insensitively; the profile's H2 URL sets IGNORECASE=TRUE.
        List<Map<String, Object>> rows = repository.findByFirstNameAndLastName("chava", "puckett");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry(FIRST_NAME, "Chava").containsEntry(LAST_NAME, "Puckett");
    }
}
