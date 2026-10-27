/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.using_transactional_scope_in_jms_to_database.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.model.Order;

/**
 * JDBC slice test of {@link OrderRepository}, the replacement of the "Insert into Database" {@code db:insert} of
 * {@code transactions.xml} (lines 13-16), against the {@code orders} table that profile {@code test} creates from the
 * unchanged {@code order.sql} in an in-memory H2 database in MySQL mode, in place of the live MySQL database the
 * original test set up through {@code MySQLDbCreator}.
 *
 * <p>{@code Replace.NONE} keeps the profile's {@code jdbc.url}; Boot's default replacement would be a plain-mode H2
 * that rejects {@code order.sql}. The slice runs every method in a transaction rolled back afterwards, so each method
 * starts with an empty table. Rows are read back only through the context's {@link JdbcTemplate}, because resolving
 * {@code jdbc.url} again yields a different {@code ${random.uuid}} database. The generated {@code id} is never
 * asserted: it starts at {@code AUTO_INCREMENT=109} and rolled-back inserts consume sequence values.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(OrderRepository.class)
@ActiveProfiles("test")
class OrderRepositoryTest {

    private static final String SELECT_COLUMNS = "select item_id, item_units, customer_id from orders";

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("db:insert writes one orders row with the values of message.xml")
    void insertWritesOneRowWithBoundValues() {
        assertThat(orderRepository.insert(order(1, 2, 1))).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject("select count(*) from orders", Integer.class)).isEqualTo(1);
        assertRow(jdbcTemplate.queryForMap(SELECT_COLUMNS), 1, 2, 1);
    }

    @Test
    @DisplayName("db:insert binds each value to its own column and passes int boundaries unchanged")
    void insertRoundTripsBoundaryValues() {
        assertThat(orderRepository.insert(order(Integer.MAX_VALUE, 0, Integer.MAX_VALUE))).isEqualTo(1);
        assertThat(orderRepository.insert(order(0, Integer.MAX_VALUE, 0))).isEqualTo(1);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(SELECT_COLUMNS + " order by item_units");

        assertThat(rows).hasSize(2);
        assertRow(rows.get(0), Integer.MAX_VALUE, 0, Integer.MAX_VALUE);
        assertRow(rows.get(1), 0, Integer.MAX_VALUE, 0);
    }

    @Test
    @DisplayName("db:insert keeps the original parameterized query's column list")
    void insertSqlKeepsOriginalColumnList() {
        assertThat(OrderRepository.INSERT_SQL)
                .isEqualTo("insert into orders(item_id,item_units,customer_id) values (?, ?, ?)");
    }

    private static Order order(int itemId, int itemUnits, int customerId) {
        Order order = new Order();
        order.setItemId(itemId);
        order.setItemUnits(itemUnits);
        order.setCustomerId(customerId);
        return order;
    }

    /**
     * Compares the three columns as numbers: the driver chooses the boxed type, and the result map's keys are
     * case-insensitive while H2 reports upper-case labels.
     */
    private static void assertRow(Map<String, Object> row, int itemId, int itemUnits, int customerId) {
        assertThat(((Number) row.get("item_id")).intValue()).as("item_id").isEqualTo(itemId);
        assertThat(((Number) row.get("item_units")).intValue()).as("item_units").isEqualTo(itemUnits);
        assertThat(((Number) row.get("customer_id")).intValue()).as("customer_id").isEqualTo(customerId);
    }
}
