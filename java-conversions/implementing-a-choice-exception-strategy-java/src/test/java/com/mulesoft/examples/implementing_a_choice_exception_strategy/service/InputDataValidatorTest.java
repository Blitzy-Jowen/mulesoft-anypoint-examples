/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.implementing_a_choice_exception_strategy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Unit test of {@link InputDataValidator}, the port of
 * {@code implementing-a-choice-exception-strategy/src/main/java/org/mule/examples/InputDataValidator.java}
 * with the Mule {@code Filter} interface removed and the body unchanged.
 *
 * <p>The exception type and message are the contract, because they select the branch of the choice exception
 * strategy: {@link NullPointerException} selects "Missing input data" and {@link IllegalArgumentException}
 * selects "Invalid input data". Plain JUnit 5 with no Spring context: the validator has no collaborators.
 */
class InputDataValidatorTest {

    private final InputDataValidator validator = new InputDataValidator();

    @Test
    void validOrderIsAccepted() {
        assertThat(validator.accept(validMap())).isTrue();
    }

    @Test
    void emailIsCheckedBeforeItemUnits() {
        Map<String, Object> order = validMap();
        order.remove("email");
        order.put("item units", 0);

        NullPointerException ex = assertThrows(NullPointerException.class, () -> validator.accept(order));
        assertEquals("email is missing", ex.getMessage());
    }

    @Test
    void messageWrongJsonWithoutEmailThrowsNullPointerException() {
        // The original's message_wrong.json is message.json without its email key.
        Map<String, Object> order = validMap();
        order.remove("email");

        NullPointerException ex = assertThrows(NullPointerException.class, () -> validator.accept(order));
        assertEquals("email is missing", ex.getMessage());
    }

    @Test
    void zeroItemUnitsThrowsIllegalArgumentException() {
        Map<String, Object> order = validMap();
        order.put("item units", 0);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> validator.accept(order));
        // The exact class: assertThrows also accepts a subclass such as NumberFormatException.
        assertEquals(IllegalArgumentException.class, ex.getClass());
        assertEquals("item units is negative", ex.getMessage());
    }

    @Test
    void oneItemUnitAndZeroPricePerUnitAreAccepted() {
        Map<String, Object> order = validMap();
        order.put("item units", 1);
        order.put("item price per unit", 0);

        assertThat(validator.accept(order)).isTrue();
    }

    @Test
    void negativePricePerUnitThrowsIllegalArgumentException() {
        Map<String, Object> order = validMap();
        order.put("item price per unit", -1);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> validator.accept(order));
        // The exact class: assertThrows also accepts a subclass such as NumberFormatException.
        assertEquals(IllegalArgumentException.class, ex.getClass());
        assertEquals("item price per unit is negative", ex.getMessage());
    }

    @Test
    void numericStringItemUnitsAreAccepted() {
        // The value is read through toString() and Integer.parseInt, so its Java type does not matter.
        Map<String, Object> order = validMap();
        order.put("item units", "10");

        assertThat(validator.accept(order)).isTrue();
    }

    @Test
    void decimalItemUnitsThrowNumberFormatException() {
        // NumberFormatException is an IllegalArgumentException, so it reaches the "Invalid input data" branch.
        Map<String, Object> order = validMap();
        order.put("item units", Double.valueOf(1.5));

        NumberFormatException ex = assertThrows(NumberFormatException.class, () -> validator.accept(order));
        assertInstanceOf(IllegalArgumentException.class, ex);
    }

    @Test
    void nonNumericItemUnitsThrowNumberFormatException() {
        // NumberFormatException is an IllegalArgumentException, so it reaches the "Invalid input data" branch.
        Map<String, Object> order = validMap();
        order.put("item units", "abc");

        NumberFormatException ex = assertThrows(NumberFormatException.class, () -> validator.accept(order));
        assertInstanceOf(IllegalArgumentException.class, ex);
    }

    @Test
    void missingItemUnitsThrowsNullPointerException() {
        // No null check is added: the absent value fails on toString(), which the "Missing input data" branch catches.
        // The message is the JVM's helpful NullPointerException text, so it is not asserted.
        Map<String, Object> order = validMap();
        order.remove("item units");

        assertThrows(NullPointerException.class, () -> validator.accept(order));
    }

    @Test
    void missingPricePerUnitThrowsNullPointerException() {
        // The message is the JVM's helpful NullPointerException text, so it is not asserted.
        Map<String, Object> order = validMap();
        order.remove("item price per unit");

        assertThrows(NullPointerException.class, () -> validator.accept(order));
    }

    /**
     * The order of the original's {@code message.json}, as {@code json-to-object-transformer} produced it: a new
     * map on every call, so each test mutates its own copy.
     */
    private static Map<String, Object> validMap() {
        Map<String, Object> order = new HashMap<>();
        order.put("email", "aaa@aaa.aa");
        order.put("item name", "aa");
        order.put("item units", 10);
        order.put("item price per unit", 1);
        order.put("membership", "free");
        return order;
    }
}
