/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Answers the 409 that {@code leagues.raml} declares for POST {@code /teams} ("The team already exists") and
 * PUT {@code /fixture/{homeTeamId}/{awayTeamId}} ("Cannot update match before the match is actually played").
 *
 * <p>The original flow's {@code apikit:mapping-exception-strategy} maps only 404, 405, 415, 406 and 400, while
 * the RAML and the example's README declare a 409 strategy. The RAML contract governs, so every
 * {@link ConflictException} the league service throws becomes 409 {@code application/json} with the APIkit
 * body shape {@code { "message": "<exception message>" }}, for example
 * {@code { "message": "There is already a team with id BAR" }}.
 *
 * <p>The advice is ordered ahead of {@code ApiKitExceptionHandler} and {@link GlobalExceptionHandler}, and it
 * declares {@link ConflictException} alone: Spring picks the first advice in order that declares a matching
 * method, so any broader type here would take exceptions away from the APIkit and platform-default mappings.
 */
@RestControllerAdvice
@Order(0)
public class LeagueExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(LeagueExceptionHandler.class);

    /**
     * Answers a team or match conflict.
     *
     * <p>Logged at INFO, because a 409 is a handled business outcome. The log line names only the code
     * location that raised the conflict; the message is left out because it repeats team identifiers taken
     * from the request.
     *
     * @param ex the conflict raised by the league service
     * @return 409 {@code application/json} {@code { "message": "<exception message>" }}; the Content-Type is
     *         preset, so the body is written without consulting the request's {@code Accept} header
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<String> handleConflict(ConflictException ex) {
        LOGGER.info("Answering 409 Conflict for a ConflictException raised at {}", throwSite(ex));
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(ErrorResponse.MEDIA_TYPE)
                .body(ErrorResponse.json(ex.getMessage()));
    }

    /** Returns {@code <class>.<method>} of the frame that threw {@code ex}, or {@code (unknown)} without one. */
    private static String throwSite(Throwable ex) {
        StackTraceElement[] trace = ex.getStackTrace();
        if (trace == null || trace.length == 0 || trace[0] == null) {
            return "(unknown)";
        }
        return trace[0].getClassName() + "." + trace[0].getMethodName();
    }
}
