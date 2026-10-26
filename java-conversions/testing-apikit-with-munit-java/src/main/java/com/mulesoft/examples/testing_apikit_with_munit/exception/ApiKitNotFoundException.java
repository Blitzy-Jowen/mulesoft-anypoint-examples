/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.testing_apikit_with_munit.exception;

/**
 * Signals that a requested resource does not exist, in every place the original application threw
 * APIkit's {@code NotFoundException}.
 *
 * <p>Shared component SC-3: {@code ApiKitExceptionHandler} answers it with 404 and the body
 * {@code { "message": "Resource not found" }}, so the exception message never reaches the
 * response body, as with APIkit's mapping. The class depends on the JDK only and is copied
 * unchanged, apart from its {@code package} line, into every RAML-backed project.
 *
 * <p>It is unchecked so that callers need no {@code throws} clause in place of the original
 * {@code throws MuleRestException}.
 */
public class ApiKitNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ApiKitNotFoundException(String message) {
        super(message);
    }
}
