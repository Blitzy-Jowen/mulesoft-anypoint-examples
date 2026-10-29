/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.implementing_a_choice_exception_strategy.service;

import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Validates the order map of {@code choice-error-handlingFlow1}, which ran this class as a
 * {@code custom-filter} after {@code json-to-object-transformer returnClass="java.util.HashMap"}.
 *
 * <p>The thrown exceptions are the contract, because they select the choice exception strategy's
 * branches: a {@link NullPointerException} (no {@code email} key, or a missing or null
 * {@code item units} or {@code item price per unit}) selects "Missing input data", and an
 * {@link IllegalArgumentException}, including the {@link NumberFormatException} a non-integer value
 * raises, selects "Invalid input data". The method never returns {@code false}. No null or type
 * checks are added: each one would move an input from the branch the original sent it to.
 */
@Component
public class InputDataValidator {

	public boolean accept(Map<String, Object> payloadMap) {
		if (!payloadMap.containsKey("email"))
			throw new NullPointerException("email is missing");
		if (Integer.parseInt(payloadMap.get("item units").toString()) < 1)
			throw new IllegalArgumentException("item units is negative");
		if (Integer.parseInt(payloadMap.get("item price per unit").toString()) < 0)
			throw new IllegalArgumentException("item price per unit is negative");

		return true;
	}

}
