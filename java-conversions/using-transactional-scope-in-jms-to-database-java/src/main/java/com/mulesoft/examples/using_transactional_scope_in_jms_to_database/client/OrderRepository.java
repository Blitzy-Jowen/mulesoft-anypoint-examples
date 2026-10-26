/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.using_transactional_scope_in_jms_to_database.client;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.model.Order;

/**
 * The project's only database access path, replacing the "Insert into Database" {@code db:insert} of
 * {@code transactions.xml} (lines 13-16) on the {@code orders} table of {@code order.sql}.
 * <p>
 * It opens no transaction of its own: {@link JdbcTemplate} takes the connection bound to the caller's
 * transaction, so the row is rolled back with it, and a {@code DataAccessException} propagates unchanged so that
 * the transaction and the JMS session roll back, as the Mule transactional scope did.
 */
@Repository
public class OrderRepository {

    /** The original parameterized query, with the three values passed as bind parameters in column order. */
    public static final String INSERT_SQL = "insert into orders(item_id,item_units,customer_id) values (?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;

    public OrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Inserts one order row.
     *
     * @param order the order read from queue {@code in}
     * @return the number of rows inserted
     */
    public int insert(Order order) {
        return jdbcTemplate.update(INSERT_SQL, order.getItemId(), order.getItemUnits(), order.getCustomerId());
    }
}
