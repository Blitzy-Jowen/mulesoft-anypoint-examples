/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.login_form_using_the_http_connector.exception;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.mulesoft.examples.login_form_using_the_http_connector.mapper.ParseTemplateRenderer;
import com.mulesoft.examples.login_form_using_the_http_connector.service.LoginService;

/**
 * The {@code catch-exception-strategy} of flow {@code DoLoginFlow}
 * ({@code login-form-using-the-http-connector.xml}:27-30): a rejected or failed {@code POST /login} is
 * answered with status 403 ({@code set-property http.status=403}, xml:28) and {@code login/loginFailure.html}
 * rendered with the posted username ({@code parse-template}, xml:29).
 *
 * <p>The advice runs ahead of {@link GlobalExceptionHandler} and claims {@link LoginService.LoginFailedException}
 * alone, the only failure the original branch caught; every other exception, such as a {@code /requesterLogin}
 * upstream failure, still reaches the generic 500.
 */
@RestControllerAdvice
@Order(0)
public class LoginExceptionHandler {

    /** Template of the catch branch (xml:29). */
    static final String FAILURE_TEMPLATE = "login/loginFailure.html";

    /** The only expression of {@code loginFailure.html} (line 13). */
    static final String USERNAME_EXPRESSION = "#[payload.username]";

    /** Form field holding the username; the payload was still the posted form map in the catch branch. */
    static final String USERNAME_FIELD = "username";

    private final ParseTemplateRenderer renderer;

    /**
     * @param renderer SC-14 template renderer
     */
    public LoginExceptionHandler(ParseTemplateRenderer renderer) {
        this.renderer = renderer;
    }

    /**
     * Writes 403 with the rendered failure page. A missing username renders as the text {@code null}, as MEL
     * rendered a null {@code payload.username}.
     *
     * <p>The page is rendered before the response is touched, so a rendering failure leaves the response
     * uncommitted for the next resolver.
     *
     * @param ex       the rejected or failed login, carrying the posted form
     * @param response the response the page is written to
     * @throws IOException if the body cannot be written
     */
    @ExceptionHandler(LoginService.LoginFailedException.class)
    public void handleLoginFailed(LoginService.LoginFailedException ex, HttpServletResponse response)
            throws IOException {
        Map<String, String> form = ex.form();
        Object username = form == null ? null : form.get(USERNAME_FIELD);
        Map<String, Object> values = new HashMap<>();
        values.put(USERNAME_EXPRESSION, String.valueOf(username));
        String body = renderer.render(FAILURE_TEMPLATE, values);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        // The catch branch set no Content-Type (only the success path does, xml:25), so none is set here and
        // the body goes through the output stream, never getWriter(), which could imply a charset. setStatus,
        // not sendError, so the container's error page cannot replace the template.
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentLength(bytes.length);
        ServletOutputStream out = response.getOutputStream();
        out.write(bytes);
        out.flush();
    }
}
