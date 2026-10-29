/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.netsuite_data_retrieval.exception;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import com.mulesoft.examples.netsuite_data_retrieval.config.JsonSchemaValidator;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The APIkit {@code apikit:mapping-exception-strategy} of the original application: shared component SC-3
 * of the conversion plan.
 *
 * <p>Each APIkit mapping answered with its status, {@code Content-Type: application/json} and a literal
 * body, never with the exception's message:
 * <ul>
 *   <li>404 {@code { "message": "Resource not found" }} for {@link ApiKitNotFoundException} (APIkit's
 *       {@code NotFoundException}) and for a path no controller serves under the API base path;</li>
 *   <li>405 {@code { "message": "Method not allowed" }} for a method a resource does not declare;</li>
 *   <li>415 {@code { "message": "Unsupported media type" }} for a request body media type no method
 *       consumes;</li>
 *   <li>406 {@code { "message": "Not acceptable" }} for an {@code Accept} header no method can satisfy;</li>
 *   <li>400 {@code { "message": "Bad request" }} for every request APIkit's RAML validation rejected: a body
 *       that fails its JSON schema or cannot be read, and a URI or query parameter that is missing, of the
 *       wrong type or outside its constraints.</li>
 * </ul>
 *
 * <p>The Mule listener only routed {@code <base path>/*} to the APIkit router, so outside the base path
 * the listener's own defaults applied. This advice declares the 404 and 405 exception types for every path,
 * and Spring never consults a later advice once one declares a matching method, so outside the base path
 * it writes the listener-default responses of {@code GlobalExceptionHandler} (SC-1) itself: 404 or 405,
 * {@code text/plain}, {@code No listener for endpoint: <path>} or
 * {@code Method not allowed for endpoint: <path>}.
 *
 * <p>Precedence ({@link Order}): the project's own handlers first, then this advice, then SC-1 last. The
 * advice declares no {@link Exception}, {@link RuntimeException} or {@code ServletException} method and does
 * not extend {@code ResponseEntityExceptionHandler}, so every exception outside the APIkit mappings still
 * reaches SC-1's generic 500.
 *
 * <p>Every response is written straight to the {@link HttpServletResponse}, with no message converter and so
 * no content negotiation: a request whose {@code Accept} header already failed still receives the JSON 406
 * body, and no error response can be turned into a 406. Taking the response as a handler argument marks the
 * request handled, so no view is rendered. Only Content-Type and Content-Length are set; the APIkit mappings
 * sent no {@code Allow} or other header.
 *
 * <p>The RAML-backed projects carry copies of this class that differ from this one in the {@code package}
 * line and the package root of the {@link JsonSchemaValidator} import alone, so the class depends only on the
 * JDK, Spring Web, the Servlet API, Jakarta Validation, SLF4J and the other two SC-3 classes.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 1)
public class ApiKitExceptionHandler {

    /** Body of APIkit's 404 mapping. */
    public static final String RESOURCE_NOT_FOUND = "{ \"message\": \"Resource not found\" }";

    /** Body of APIkit's 405 mapping. */
    public static final String METHOD_NOT_ALLOWED = "{ \"message\": \"Method not allowed\" }";

    /** Body of APIkit's 415 mapping. */
    public static final String UNSUPPORTED_MEDIA_TYPE = "{ \"message\": \"Unsupported media type\" }";

    /** Body of APIkit's 406 mapping. */
    public static final String NOT_ACCEPTABLE = "{ \"message\": \"Not acceptable\" }";

    /** Body of APIkit's 400 mapping. */
    public static final String BAD_REQUEST = "{ \"message\": \"Bad request\" }";

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiKitExceptionHandler.class);

    // The listener-default texts of SC-1, duplicated because SC-3 copies may not depend on SC-1 classes.
    private static final String NO_LISTENER_PREFIX = "No listener for endpoint: ";

    private static final String METHOD_NOT_ALLOWED_PREFIX = "Method not allowed for endpoint: ";

    /** Longest logged request path, in characters after escaping. */
    private static final int PATH_LOG_LIMIT = 256;

    private final String basePath;

    /**
     * Creates the handler for the API served under {@code basePath}, the original listener path without
     * its {@code /*}.
     *
     * @param basePath the API base path, for example {@code /api}; surrounding blanks are ignored, a missing
     *                 leading {@code /} is added and trailing {@code /} are dropped, so {@code ""} and
     *                 {@code "/"} mean every path
     */
    public ApiKitExceptionHandler(@Value("${apikit.base-path:/api}") String basePath) {
        this.basePath = normaliseBasePath(basePath);
    }

    /**
     * APIkit's {@code NotFoundException} mapping: a resource the API serves reports that the requested
     * entity does not exist. Applies on every path, because only API code throws it.
     */
    @ExceptionHandler(ApiKitNotFoundException.class)
    public void handleApiKitNotFound(ApiKitNotFoundException ex, HttpServletRequest request,
                                     HttpServletResponse response) throws IOException {
        answer(ex, request, response, HttpServletResponse.SC_NOT_FOUND, MediaType.APPLICATION_JSON_VALUE,
                RESOURCE_NOT_FOUND);
    }

    /**
     * A path no controller serves: APIkit's 404 mapping under the base path, the listener's no-listener
     * default elsewhere.
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public void handleNoHandler(Exception ex, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String path = path(request);
        if (isUnderBasePath(path)) {
            answer(ex, request, response, HttpServletResponse.SC_NOT_FOUND, MediaType.APPLICATION_JSON_VALUE,
                    RESOURCE_NOT_FOUND);
        } else {
            answer(ex, request, response, HttpServletResponse.SC_NOT_FOUND, MediaType.TEXT_PLAIN_VALUE,
                    NO_LISTENER_PREFIX + path);
        }
    }

    /**
     * A method the mapped resource does not declare: APIkit's 405 mapping under the base path, the
     * listener's method-not-allowed default elsewhere.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public void handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex, HttpServletRequest request,
                                       HttpServletResponse response) throws IOException {
        String path = path(request);
        if (isUnderBasePath(path)) {
            answer(ex, request, response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                    MediaType.APPLICATION_JSON_VALUE, METHOD_NOT_ALLOWED);
        } else {
            answer(ex, request, response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, MediaType.TEXT_PLAIN_VALUE,
                    METHOD_NOT_ALLOWED_PREFIX + path);
        }
    }

    /** APIkit's {@code UnsupportedMediaTypeException} mapping. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public void handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest request,
                                           HttpServletResponse response) throws IOException {
        answer(ex, request, response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
                MediaType.APPLICATION_JSON_VALUE, UNSUPPORTED_MEDIA_TYPE);
    }

    /**
     * APIkit's {@code NotAcceptableException} mapping. The JSON body is written although the request's
     * {@code Accept} header excludes it, as APIkit's mapping wrote it.
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public void handleNotAcceptable(HttpMediaTypeNotAcceptableException ex, HttpServletRequest request,
                                    HttpServletResponse response) throws IOException {
        answer(ex, request, response, HttpServletResponse.SC_NOT_ACCEPTABLE, MediaType.APPLICATION_JSON_VALUE,
                NOT_ACCEPTABLE);
    }

    /**
     * APIkit's {@code BadRequestException} mapping, the single answer to every request its RAML validation
     * rejected:
     * <ul>
     *   <li>{@link JsonSchemaValidator.SchemaValidationException}: a blank body, a body that is not one JSON
     *       document, or one its draft-03 schema rejects;</li>
     *   <li>{@link HttpMessageNotReadableException}: a body that cannot be bound to the method's type;</li>
     *   <li>{@link HandlerMethodValidationException}, {@link ConstraintViolationException},
     *       {@link MethodArgumentNotValidException}: a URI parameter, query parameter or bound body outside
     *       its declared constraints, such as a team ID that is not three characters long;</li>
     *   <li>{@link MissingServletRequestParameterException}: a required query parameter is absent;</li>
     *   <li>{@link MethodArgumentTypeMismatchException}: a parameter that is not of its declared type or not
     *       one of its declared enumeration values.</li>
     * </ul>
     */
    @ExceptionHandler({
            JsonSchemaValidator.SchemaValidationException.class,
            HttpMessageNotReadableException.class,
            HandlerMethodValidationException.class,
            ConstraintViolationException.class,
            MethodArgumentNotValidException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public void handleBadRequest(Exception ex, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        answer(ex, request, response, HttpServletResponse.SC_BAD_REQUEST, MediaType.APPLICATION_JSON_VALUE,
                BAD_REQUEST);
    }

    /**
     * Whether {@code path} belongs to the API: the base path itself or any path below it, so with base
     * {@code /api} the paths {@code /api}, {@code /api/} and {@code /api/teams} do and {@code /apix} does not.
     */
    boolean isUnderBasePath(String path) {
        if ("/".equals(basePath)) {
            return true;
        }
        return path.equals(basePath) || path.startsWith(basePath + "/");
    }

    /**
     * Returns the raw request URI, without query string, relative to the context path, which is how the
     * Mule listener reported the endpoint.
     */
    static String path(HttpServletRequest request) {
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

    private static void answer(Exception ex, HttpServletRequest request, HttpServletResponse response,
                               int status, String contentType, String body) throws IOException {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Answering {} for {} on {}", status, ex.getClass().getName(),
                    loggable(path(request)));
        }
        write(response, status, contentType, body);
    }

    private static void write(HttpServletResponse response, int status, String contentType, String body)
            throws IOException {
        if (response.isCommitted()) {
            LOGGER.warn("Response already committed; the APIkit {} answer was not written", status);
            return;
        }
        response.resetBuffer();
        response.setStatus(status);
        // No charset parameter: the APIkit mappings set the bare media type.
        response.setContentType(contentType);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        response.setContentLength(bytes.length);
        OutputStream out = response.getOutputStream();
        out.write(bytes);
        response.flushBuffer();
    }

    private static String normaliseBasePath(String configured) {
        String path = configured == null ? "" : configured.trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /**
     * Escapes ISO control characters, line and paragraph separators and the reverse solidus as
     * {@code \}{@code uXXXX} and bounds the length, so a request path cannot forge or flood log lines.
     */
    private static String loggable(String value) {
        StringBuilder out = new StringBuilder(Math.min(value.length(), PATH_LOG_LIMIT));
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean escape = Character.isISOControl(c) || c == '\u2028' || c == '\u2029' || c == '\\';
            if (out.length() + (escape ? 6 : 1) > PATH_LOG_LIMIT) {
                return out.append("...[truncated]").toString();
            }
            if (escape) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
