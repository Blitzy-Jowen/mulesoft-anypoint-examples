/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.exception;

import com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.config.ReasonPhrase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The {@code choice-exception-strategy} of flow {@code retrievePersonFlow}
 * ({@code exposing-a-restful-resource-using-the-HTTP-connector/src/main/app/http-restful-resource.xml:37-42}).
 *
 * <p>Its single branch, a {@code rollback-exception-strategy} selected when the exception was caused by the
 * {@code FilterUnacceptedException} of the throwing {@code message-filter} (lines 33-35), set the flow variables
 * {@code errorStatusCode} = {@code 404} and {@code errorReasonPhrase} = {@code Not Found} (lines 39-40). The
 * listener's {@code <http:error-response-builder reasonPhrase="#[errorReasonPhrase]"
 * statusCode="#[errorStatusCode]"/>} (line 29) put both on the status line. Here {@link PersonNotFoundException}
 * stands for the filter rejection, the two variables are the constants {@link #ERROR_STATUS_CODE} and
 * {@link #ERROR_REASON_PHRASE}, and the reason phrase is written through SC-2 {@link ReasonPhrase}.
 *
 * <p>The advice runs at {@code @Order(0)}, ahead of the SC-1 {@link GlobalExceptionHandler}, and declares only the
 * exception type the original branch names. Every other failure of the flow, such as the
 * {@link NumberFormatException} of a non-integer {@code personId}, matched no branch in the original and still
 * reaches the platform-default 500 of {@link GlobalExceptionHandler}.
 *
 * <p>Tier 2A verification point: the 404 carries an empty body, because the error-response builder sets only a
 * status and a reason phrase and a GET carries no payload. The original README (lines 97-99) says the filter text
 * {@link PersonNotFoundException#MESSAGE} is returned instead. A captured fixture of the original governs; should it
 * show that text as the body, this handler writes {@link PersonNotFoundException#MESSAGE}, the single source of it.
 */
// Not restricted with assignableTypes, unlike the choice-exception-strategy handler of the sibling project: naming
// the controller here would make the exception package depend on the controller package and close a package cycle.
// Only service/PersonService throws PersonNotFoundException, so the global scope selects the same requests.
@RestControllerAdvice
@Order(0)
public class PersonExceptionHandler {

    /** {@code errorStatusCode} of the rollback branch ({@code http-restful-resource.xml:39}). */
    public static final int ERROR_STATUS_CODE = 404;

    /** {@code errorReasonPhrase} of the rollback branch ({@code http-restful-resource.xml:40}). */
    public static final String ERROR_REASON_PHRASE = "Not Found";

    private static final Logger LOGGER = LoggerFactory.getLogger(PersonExceptionHandler.class);

    /**
     * The rollback branch ({@code when="#[exception.causedBy(org.mule.api.routing.filter.FilterUnacceptedException)]"},
     * lines 38-41). It logs the rejection at ERROR, as the Mule rollback strategy logged the exception it handled,
     * sets the reason phrase and answers with the branch's status.
     *
     * <p>The response is built through {@link ResponseEntity}, never {@code sendError}, so no error dispatch runs
     * and no body or Content-Type is added.
     *
     * @param ex the rejection of a {@code personId} the person store does not hold
     * @return 404 with reason phrase {@code Not Found}, no body and no Content-Type
     */
    @ExceptionHandler(PersonNotFoundException.class)
    public ResponseEntity<Void> handlePersonNotFound(PersonNotFoundException ex) {
        LOGGER.error("Rollback exception strategy: person {} not found: {}", ex.getPersonId(), ex.getMessage());
        ReasonPhrase.set(ERROR_REASON_PHRASE);
        return ResponseEntity.status(ERROR_STATUS_CODE).build();
    }
}
