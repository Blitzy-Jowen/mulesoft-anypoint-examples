/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.using_transactional_scope_in_jms_to_database.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.exception.MyException;
import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.model.Order;
import org.junit.jupiter.api.Test;

class TestComponentTest {

    private static final String CUSTOMIZED_ERROR = "exception customized";

    @Test
    void onCallAlwaysThrowsMyExceptionForAnOrder() {
        // The order carried by message.xml.
        Order order = new Order();
        order.setItemId(1);
        order.setItemUnits(2);
        order.setCustomerId(1);

        MyException ex = assertThrows(MyException.class, () -> new TestComponent().onCall(order));

        assertEquals(CUSTOMIZED_ERROR, ex.getMessage());
        assertEquals(CUSTOMIZED_ERROR, ex.getError());
    }

    @Test
    void onCallThrowsMyExceptionForNullPayload() {
        MyException ex = assertThrows(MyException.class, () -> new TestComponent().onCall(null));

        assertEquals(CUSTOMIZED_ERROR, ex.getMessage());
        assertEquals(CUSTOMIZED_ERROR, ex.getError());
    }

    @Test
    void defaultMyExceptionErrorIsTest() {
        assertEquals("test", new MyException().getError());
    }

    @Test
    void messageConstructorSetsMessageAndError() {
        MyException ex = new MyException("x");

        assertEquals("x", ex.getMessage());
        assertEquals("x", ex.getError());
    }

}
