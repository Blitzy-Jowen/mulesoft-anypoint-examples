/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit.exception;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Generic Spring MVC error handling: shared component SC-1 of the conversion plan.
 *
 * <p>Answers what the Mule HTTP listener and runtime answered by default when no flow shaped the error:
 * <ul>
 *   <li>a path no handler serves: 404, {@code text/plain}, {@code No listener for endpoint: <path>};</li>
 *   <li>a method the handler does not accept: 405, {@code text/plain},
 *       {@code Method not allowed for endpoint: <path>};</li>
 *   <li>any other exception: 500, {@code application/json}, {@code { "message": "<exception message>" }}.</li>
 * </ul>
 * These bytes are platform defaults rather than flow output, so a captured fixture of the original
 * application overrides them.
 *
 * <p>The advice runs last ({@link Ordered#LOWEST_PRECEDENCE}): Spring uses the first advice in order that
 * declares a matching method, and the {@link Exception} method here matches everything, so every
 * project-specific, APIkit or upstream handler must be able to claim an exception first. It deliberately
 * does not extend {@code ResponseEntityExceptionHandler}, whose handlers for the other MVC exceptions would
 * answer with ProblemDetail bodies instead of the shapes above.
 *
 * <p>Every project with a Spring MVC controller carries a copy that differs from this one in the
 * {@code package} line alone, so the class depends only on the JDK, Spring Web, the Servlet API, SLF4J and
 * {@link ErrorResponse}. Each response presets its Content-Type, which makes Spring write the body without
 * consulting the request's {@code Accept} header, so an error response can never become a 406.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String NO_LISTENER_PREFIX = "No listener for endpoint: ";

    private static final String METHOD_NOT_ALLOWED_PREFIX = "Method not allowed for endpoint: ";

    /**
     * Answers a request for a path no controller or resource handler serves.
     *
     * @param ex the {@link NoHandlerFoundException} or {@link NoResourceFoundException} raised by the dispatcher
     * @param request the rejected request, whose path is reported
     * @return 404 {@code text/plain} {@code No listener for endpoint: <path>}
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<String> handleNoListener(Exception ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.TEXT_PLAIN)
                .body(NO_LISTENER_PREFIX + path(request));
    }

    /**
     * Answers a request whose method the mapped handler does not accept. No {@code Allow} header is sent,
     * because the Mule listener sent none.
     *
     * @param ex the exception raised by the handler mapping
     * @param request the rejected request, whose path is reported
     * @return 405 {@code text/plain} {@code Method not allowed for endpoint: <path>}
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<String> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex,
                                                         HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .contentType(MediaType.TEXT_PLAIN)
                .body(METHOD_NOT_ALLOWED_PREFIX + path(request));
    }

    /**
     * Answers any exception no other advice claims, logging it at ERROR as the Mule default exception
     * strategy logged unhandled exceptions.
     *
     * @param ex the unhandled exception
     * @param request the failed request, whose method and path are logged
     * @return 500 {@code application/json} {@code { "message": "<exception message>" }}, with
     *         {@code { "message": null }} when the exception carries no message
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleUnhandled(Exception ex, HttpServletRequest request) {
        LOGGER.error("Unhandled exception processing {} {}", request.getMethod(), path(request), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(ErrorResponse.MEDIA_TYPE)
                .body(ErrorResponse.json(ex.getMessage()));
    }

    /**
     * Returns the raw request URI, without query string, relative to the context path, which is how the
     * Mule listener reported the endpoint.
     */
    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return "";
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }
}
