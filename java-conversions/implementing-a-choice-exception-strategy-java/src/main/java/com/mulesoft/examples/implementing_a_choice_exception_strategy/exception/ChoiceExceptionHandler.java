/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.implementing_a_choice_exception_strategy.exception;

import com.mulesoft.examples.implementing_a_choice_exception_strategy.config.ReasonPhrase;
import com.mulesoft.examples.implementing_a_choice_exception_strategy.controller.ValidationController;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The {@code choice-exception-strategy} of flow {@code choice-error-handlingFlow1}
 * ({@code implementing-a-choice-exception-strategy/src/main/app/choice-error-handling.xml:15-47}).
 *
 * <p>Each catch branch set the flow variables {@code statusCode} and {@code reason}, which the listener's
 * {@code http:response-builder} put on the status line (lines 5-6), and set the payload to
 * {@code "<reason>: #[payload]"}. Here each branch is its own handler method, in the original declaration order:
 * {@link IllegalArgumentException} ("Catch Invalid Data", line 16) and {@link NullPointerException} ("Catch Missing
 * Data", line 31). The status code comes from the branch constant, the reason phrase is written through
 * {@link ReasonPhrase}, and the body renders the parsed {@code HashMap} payload that {@link ValidationController}
 * stored under {@link #PAYLOAD_ATTRIBUTE}. The class owns the four constant DataWeave scripts of the branches (lines
 * 20, 24, 35, 39) as constants.
 *
 * <p>The advice applies only to {@link ValidationController} and runs at {@code @Order(0)}, ahead of the SC-1
 * {@link GlobalExceptionHandler}. It declares only the two exception types the original branches name, so every other
 * failure, unreadable JSON included, still reaches the platform-default 500 of {@link GlobalExceptionHandler}, as an
 * exception no branch matched did in the original.
 */
@RestControllerAdvice(assignableTypes = ValidationController.class)
@Order(0)
public class ChoiceExceptionHandler {

    /**
     * Request attribute holding the {@code HashMap} payload that {@link ValidationController} stores before
     * validating; it stands for the Mule message payload the catch branches rendered as {@code #[payload]}.
     */
    public static final String PAYLOAD_ATTRIBUTE = ChoiceExceptionHandler.class.getName() + ".payload";

    /** {@code statusCode} of the "Catch Invalid Data" branch (line 20). */
    public static final String INVALID_DATA_STATUS_CODE = "400";

    /** {@code reason} of the "Catch Invalid Data" branch (line 24). */
    public static final String INVALID_DATA_REASON = "Invalid input data";

    /** {@code statusCode} of the "Catch Missing Data" branch (line 35). */
    public static final String MISSING_DATA_STATUS_CODE = "400";

    /** {@code reason} of the "Catch Missing Data" branch (line 39). */
    public static final String MISSING_DATA_REASON = "Missing input data";

    private static final Logger LOGGER = LoggerFactory.getLogger(ChoiceExceptionHandler.class);

    /**
     * Branch "Catch Invalid Data" ({@code when="#[exception.causedBy(java.lang.IllegalArgumentException)]"},
     * lines 16-30), which also catches the {@link NumberFormatException} of a non-integer item value.
     *
     * @param ex      the validator's exception
     * @param request the failed request, carrying the payload under {@link #PAYLOAD_ATTRIBUTE}
     * @return 400 with reason {@code Invalid input data}, {@code text/plain;charset=UTF-8},
     *         {@code Invalid input data: <payload>}
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleInvalidInputData(IllegalArgumentException ex, HttpServletRequest request) {
        LOGGER.error("Catch Invalid Data", ex);
        ReasonPhrase.set(INVALID_DATA_REASON);
        return ResponseEntity.status(Integer.parseInt(INVALID_DATA_STATUS_CODE))
                .contentType(ValidationController.RESPONSE_CONTENT_TYPE)
                .body(INVALID_DATA_REASON + ": " + String.valueOf(request.getAttribute(PAYLOAD_ATTRIBUTE)));
    }

    /**
     * Branch "Catch Missing Data" ({@code when="#[exception.causedBy(java.lang.NullPointerException)]"},
     * lines 31-45).
     *
     * @param ex      the validator's exception
     * @param request the failed request, carrying the payload under {@link #PAYLOAD_ATTRIBUTE}
     * @return 400 with reason {@code Missing input data}, {@code text/plain;charset=UTF-8},
     *         {@code Missing input data: <payload>}
     */
    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<String> handleMissingInputData(NullPointerException ex, HttpServletRequest request) {
        LOGGER.error("Catch Missing Data", ex);
        ReasonPhrase.set(MISSING_DATA_REASON);
        return ResponseEntity.status(Integer.parseInt(MISSING_DATA_STATUS_CODE))
                .contentType(ValidationController.RESPONSE_CONTENT_TYPE)
                .body(MISSING_DATA_REASON + ": " + String.valueOf(request.getAttribute(PAYLOAD_ATTRIBUTE)));
    }
}
