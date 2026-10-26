/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_data_retrieval.exception;

import com.mulesoft.examples.salesforce_data_retrieval.client.UpstreamSystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Answers the approved connector failure modes to the HTTP caller (shared component SC-9, AAP 0.4.8).
 *
 * <p>{@code client/UpstreamCallExecutor} turns a classified vendor failure into one of three exceptions, and
 * this advice maps each to its response:
 * <ul>
 *   <li>{@link UpstreamAuthenticationException}: the authentication was still rejected after the single
 *       re-authentication and retry → 502;</li>
 *   <li>{@link UpstreamRateLimitException}: the vendor throttled the call, which is never retried → 429, with
 *       the vendor's {@code Retry-After} copied when it sent one;</li>
 *   <li>{@link UpstreamUnavailableException}: a timeout or connectivity failure, never retried → 503.</li>
 * </ul>
 * Every body is {@code { "message": "<upstream message>", "upstream": "<system>" }} with APIkit's spacing and
 * Content-Type {@code application/json}. The {@code upstream} value is {@link UpstreamSystem#displayName()}:
 * {@code Salesforce}, {@code NetSuite}, {@code Workday} or {@code Microsoft Dynamics 365}.
 *
 * <p>The advice runs first ({@link Ordered#HIGHEST_PRECEDENCE}). Spring uses the first advice in order that
 * declares a matching method, so these three failures reach the caller ahead of a project catch-all such as
 * {@code Invalid Salesforce query.} and of the SC-1 500. It declares nothing else: every other exception falls
 * through to the project's original exception branch. Nothing here retries or calls a vendor; the retry
 * policy lives in the executor.
 *
 * <p>The five HTTP-triggered connector projects carry copies that differ from this one in the
 * {@code package} line alone, so the class depends only on the JDK, Spring Web, SLF4J, the three upstream
 * exceptions and {@link UpstreamSystem}. The body is written by hand rather than with Jackson or SC-1's
 * error-body builder, which renders a body without the {@code upstream} field.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UpstreamExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(UpstreamExceptionHandler.class);

    /** Content-Type of every failure body; no charset parameter, as SC-1's {@code application/json} 500. */
    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    /**
     * Answers an authentication rejection that survived the single re-authentication and retry.
     *
     * @param ex the failure raised by the executor
     * @return 502 {@code { "message": "<upstream message>", "upstream": "<system>" }}
     */
    @ExceptionHandler(UpstreamAuthenticationException.class)
    public ResponseEntity<String> handleAuthentication(UpstreamAuthenticationException ex) {
        return respond(HttpStatus.BAD_GATEWAY, ex, ex.getSystem(), null);
    }

    /**
     * Answers a throttled call.
     *
     * @param ex the failure raised by the executor
     * @return 429 {@code { "message": "<upstream message>", "upstream": "<system>" }}, with {@code Retry-After}
     *         set to the vendor's value exactly as received when that value is present and not blank
     */
    @ExceptionHandler(UpstreamRateLimitException.class)
    public ResponseEntity<String> handleRateLimit(UpstreamRateLimitException ex) {
        return respond(HttpStatus.TOO_MANY_REQUESTS, ex, ex.getSystem(), ex.getRetryAfter());
    }

    /**
     * Answers a vendor that could not be reached in time.
     *
     * @param ex the failure raised by the executor
     * @return 503 {@code { "message": "<upstream message>", "upstream": "<system>" }}
     */
    @ExceptionHandler(UpstreamUnavailableException.class)
    public ResponseEntity<String> handleUnavailable(UpstreamUnavailableException ex) {
        return respond(HttpStatus.SERVICE_UNAVAILABLE, ex, ex.getSystem(), null);
    }

    /**
     * Logs the failure once at ERROR and builds its response; {@code retryAfter} becomes the
     * {@code Retry-After} header only when it is non-null and not blank.
     */
    private static ResponseEntity<String> respond(HttpStatus status, RuntimeException ex, UpstreamSystem system,
                                                  String retryAfter) {
        log.error("Upstream {} failure ({}): {}", system.displayName(), status.value(), ex.getMessage(), ex);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status).contentType(JSON);
        if (retryAfter != null && !retryAfter.isBlank()) {
            response.header(HttpHeaders.RETRY_AFTER, retryAfter);
        }
        return response.body(body(ex.getMessage(), system));
    }

    /**
     * Returns {@code { "message": "<escaped message>", "upstream": "<escaped display name>" }}, with
     * {@code "message": null} unquoted when the exception carries no message.
     */
    private static String body(String message, UpstreamSystem system) {
        String renderedMessage = message == null ? "null" : "\"" + escape(message) + "\"";
        return "{ \"message\": " + renderedMessage + ", \"upstream\": \"" + escape(system.displayName()) + "\" }";
    }

    /**
     * Escapes {@code s} for a JSON string literal: quotation mark and reverse solidus are backslash-escaped,
     * newline, carriage return and tab use their two-character forms, every other character below U+0020 is
     * written as a backslash, {@code u} and four lower-case hex digits, and every remaining character,
     * non-ASCII text included, is copied unchanged.
     */
    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
