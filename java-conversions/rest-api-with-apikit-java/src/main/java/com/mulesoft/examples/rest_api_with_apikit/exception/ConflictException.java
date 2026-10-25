/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit.exception;

public class ConflictException extends RuntimeException {

    private static final long serialVersionUID = 3387516993124229969L;

    public ConflictException(String message) {
        super(message);
    }
}
