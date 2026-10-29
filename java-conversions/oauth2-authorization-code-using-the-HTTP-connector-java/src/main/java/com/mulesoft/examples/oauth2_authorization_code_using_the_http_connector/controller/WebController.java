/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.controller;

import java.io.IOException;

import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.service.BoxOAuthService;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP triggers of {@code oauth2-authorization-code-using-the-HTTP-connector/src/main/app/http-authorization-code-web.xml}
 * on {@code HTTP_Listener_Configuration} (line 26):
 * <ul>
 *   <li>{@code /web}, the listener of flow {@code boxUserLoginFlow} (lines 27-35), answered by
 *       {@link BoxOAuthService#webLogin(byte[], String)};</li>
 *   <li>{@code /web/loginDone}, the listener of flow {@code userLoginDoneFlow} (lines 37-48), answered by
 *       {@link BoxOAuthService#loginDone()}.</li>
 * </ul>
 *
 * <p>Neither listener declares {@code allowedMethods}, so both paths accept every HTTP method. The matches are
 * exact: any other path, {@code /web/} and {@code /web/<id>} included, falls through to the no-listener 404 of
 * {@code GlobalExceptionHandler}. Which port serves these paths is decided by {@code PortRoutingFilter} before a
 * request reaches this class.
 *
 * <p>The controller only binds the request and writes the {@link BoxOAuthService.Reply} as it is: status, the
 * {@code Location} and {@code Content-Type} headers only when the reply carries them, {@code Content-Length} and the
 * body bytes. The reply is written to the servlet response directly, so no message converter adds a
 * {@code Content-Type}. Every exception propagates to {@code GlobalExceptionHandler}, and since the service returns
 * the reply before the response is touched, a failure still finds an uncommitted response for its 500.
 */
@RestController
public class WebController {

    private final BoxOAuthService service;

    /**
     * @param service the flows' behaviour: redirect location, body echo, log line, Box search and response text
     */
    public WebController(BoxOAuthService service) {
        this.service = service;
    }

    /**
     * Answers {@code /web} for every method except {@code OPTIONS}: 302 to the configured Box login location, with
     * the request body echoed, because the flow leaves the payload unchanged.
     *
     * @param request  the request whose body and {@code Content-Type} are echoed
     * @param response the response the reply is written to
     * @throws IOException when reading the request or writing the response fails
     */
    @RequestMapping("/web")
    public void web(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // The body is read from the stream before anything else touches the request: on Undertow a parameter
        // lookup first would consume a form-urlencoded body and leave nothing to echo.
        byte[] body = request.getInputStream().readAllBytes();
        String contentType = request.getContentType();
        writeReply(response, service.webLogin(body, contentType));
    }

    /**
     * Answers {@code OPTIONS /web} like every other method. A mapping without methods deliberately does not match
     * {@code OPTIONS}, which Spring MVC would otherwise answer itself with an {@code Allow} header; the Mule
     * listener ran the flow for {@code OPTIONS} too.
     *
     * @param request  the request whose body and {@code Content-Type} are echoed
     * @param response the response the reply is written to
     * @throws IOException when reading the request or writing the response fails
     */
    @RequestMapping(value = "/web", method = RequestMethod.OPTIONS)
    public void webOptions(HttpServletRequest request, HttpServletResponse response) throws IOException {
        web(request, response);
    }

    /**
     * Answers {@code /web/loginDone} for every method except {@code OPTIONS}: 200 with the Box search result text
     * and no {@code Content-Type}. The flow reads neither the request body nor its query, so neither is bound.
     *
     * @param response the response the reply is written to
     * @throws IOException when writing the response fails
     */
    @RequestMapping("/web/loginDone")
    public void loginDone(HttpServletResponse response) throws IOException {
        writeReply(response, service.loginDone());
    }

    /**
     * Answers {@code OPTIONS /web/loginDone} like every other method, for the reason given at
     * {@link #webOptions(HttpServletRequest, HttpServletResponse)}.
     *
     * @param response the response the reply is written to
     * @throws IOException when writing the response fails
     */
    @RequestMapping(value = "/web/loginDone", method = RequestMethod.OPTIONS)
    public void loginDoneOptions(HttpServletResponse response) throws IOException {
        loginDone(response);
    }

    /**
     * Writes {@code reply} unchanged. {@code Location} is set as a plain header rather than through
     * {@code sendRedirect}, which would rewrite the value and add a body of its own.
     */
    private static void writeReply(HttpServletResponse response, BoxOAuthService.Reply reply) throws IOException {
        response.setStatus(reply.status());
        if (reply.location() != null) {
            response.setHeader("Location", reply.location());
        }
        if (reply.contentType() != null) {
            response.setContentType(reply.contentType());
        }
        byte[] body = reply.body();
        response.setContentLength(body.length);
        ServletOutputStream out = response.getOutputStream();
        out.write(body);
        out.flush();
    }
}
