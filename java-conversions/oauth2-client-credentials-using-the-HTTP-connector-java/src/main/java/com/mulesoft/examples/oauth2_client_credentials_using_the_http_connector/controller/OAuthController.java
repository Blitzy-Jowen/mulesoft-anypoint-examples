/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.controller;

import java.io.IOException;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.service.BoxClientCredentialsService;

/**
 * HTTP trigger of flow {@code httpclientcredentialsFlow}
 * (oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml:31-38).
 *
 * <ul>
 *   <li>Line 32, {@code http:listener path="oauth"} on {@code HTTP_Listener_Configuration}, {@code 0.0.0.0:8081}
 *       (line 9): the listener declares no {@code allowedMethods}, so every method is accepted, {@code OPTIONS}
 *       through the companion mapping, and no mapping here declares {@code produces}, {@code consumes},
 *       parameters or headers, so no request is rejected with 405, 406 or 415. The match is exact: {@code /oauth/}
 *       and any other path reach the no-listener 404 of {@code GlobalExceptionHandler}.</li>
 *   <li>Lines 33-37, the {@code #[payload]} logger, the Box {@code http:request} and the logging
 *       {@code rollback-exception-strategy}: owned by {@link BoxClientCredentialsService}, which receives the
 *       request body, or {@code null} when the request carried none. This class holds no transformation or
 *       routing logic and writes the service's response as it is.</li>
 * </ul>
 * A failure the service rethrows reaches SC-1 {@code GlobalExceptionHandler}, which answers the platform-default
 * 500 {@code { "message": "<exception message>" }}.
 *
 * <p>The body is read from the servlet input stream, not through {@code @RequestBody}, whose
 * {@code ServletServerHttpRequest} rebuilds a form-urlencoded POST body without a query string from the parsed
 * request parameters, re-sorted and re-encoded; the flow therefore receives, and logs, the caller's bytes for a
 * form body of any method too. Nothing consumes the request stream before this class: {@code application.yml} disables Boot's
 * {@code FormContentFilter}, which would read PUT, PATCH and DELETE form bodies before dispatch and leave the
 * stream empty here. The Content-Type is parsed first, as request-body binding parsed it, so one that cannot be
 * parsed fails before the body is read.
 *
 * <p>The service's response is written to the servlet response directly, never through a message converter: its
 * status, its headers, which hold the Box Content-Type as received and nothing else, and its body with a
 * {@code Content-Length}. When Box sent no Content-Type the reply carries none, where converter negotiation would
 * add {@code application/octet-stream}. Every response is complete when the handler returns: a body is written to
 * exactly its declared length, and an empty one is declared with length 0 and flushed. {@code FrameworkServlet}
 * afterwards still runs the servlet's own TRACE echo, which repeats the request headers, {@code Authorization}
 * included, and after an OPTIONS handler still adds an {@code Allow} header; a complete response takes neither. A
 * HEAD response carries the headers without the body. The service's result is obtained before the response is
 * touched, so a failure still finds an uncommitted response for the 500.
 *
 * <p>A CORS pre-flight ({@code OPTIONS} with {@code Origin} and {@code Access-Control-Request-Method}) is dispatched
 * by {@code PreflightDispatchConfig} as the plain {@code OPTIONS} request it is, so it runs the flow through the
 * companion mapping like any other {@code OPTIONS} request, as the Mule listener, which applied no CORS policy,
 * did.
 *
 * <p>Tier 2A verification points, where the Spring MVC request handling may differ from the Mule listener:
 * <ol>
 *   <li>Form-encoded bodies of every method, POST included, are logged as the caller's text. The Mule listener
 *       parsed a form-urlencoded body into a {@code ParameterMap}, so the text its {@code #[payload]} logger wrote
 *       may differ until a fixture settles it.</li>
 *   <li>A Box reply without a Content-Type is answered without one, because the Mule message's MIME type stayed
 *       <code>*&#47;*</code>, for which the Mule 3.8 listener sends no Content-Type.</li>
 *   <li>TRACE runs the flow like every other method, because {@code application.yml} sets
 *       {@code spring.mvc.dispatch-trace-request}; whether the Mule listener ran the flow for TRACE is
 *       unverified.</li>
 *   <li>A Content-Type that cannot be parsed ends in the 500 of {@code GlobalExceptionHandler} before the body is
 *       read and the flow's logger runs.</li>
 * </ol>
 */
@RestController
public class OAuthController {

    private final BoxClientCredentialsService boxClientCredentialsService;

    /**
     * Creates the controller.
     *
     * @param boxClientCredentialsService the flow of lines 33-37 the listener runs
     */
    public OAuthController(BoxClientCredentialsService boxClientCredentialsService) {
        this.boxClientCredentialsService = boxClientCredentialsService;
    }

    /**
     * Runs a {@code /oauth} request of any method but OPTIONS through {@link BoxClientCredentialsService}.
     *
     * @param request  the inbound request, whose body the flow logs
     * @param response the response the service's result is written to
     * @throws org.springframework.http.InvalidMediaTypeException when the Content-Type cannot be parsed; the body is
     *         not read and the flow does not run
     * @throws IOException when the request body cannot be read or the response cannot be written
     */
    @RequestMapping("/oauth")
    public void oauth(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String contentType = request.getContentType();
        if (StringUtils.hasLength(contentType)) {
            // Only the failure matters: the flow does not use the media type.
            MediaType.parseMediaType(contentType);
        }
        byte[] body = readBody(request);
        write(boxClientCredentialsService.authorize(body), response);
    }

    /**
     * Runs {@code OPTIONS /oauth} like every other method. A mapping without methods deliberately does not match
     * OPTIONS, which Spring MVC would otherwise answer itself with an {@code Allow} header; the Mule listener ran
     * the flow for OPTIONS too.
     *
     * @param request  the inbound request
     * @param response the response the service's result is written to
     * @throws IOException when the request body cannot be read or the response cannot be written
     */
    @RequestMapping(path = "/oauth", method = RequestMethod.OPTIONS)
    public void oauthOptions(HttpServletRequest request, HttpServletResponse response) throws IOException {
        oauth(request, response);
    }

    /**
     * Reads the request body as the caller sent it.
     *
     * @return the body bytes, or {@code null} when the request carried none, as {@code @RequestBody(required =
     *         false)} binds an absent body
     */
    private static byte[] readBody(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readAllBytes();
        return body.length == 0 ? null : body;
    }

    /**
     * Writes {@code result} to {@code response} and completes it (see the class comment): the status, every header
     * of {@code result} as given, and the body with its {@code Content-Length}, an absent body as an empty one.
     */
    private static void write(ResponseEntity<byte[]> result, HttpServletResponse response) throws IOException {
        response.setStatus(result.getStatusCode().value());
        result.getHeaders().forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        byte[] body = result.getBody() == null ? new byte[0] : result.getBody();
        response.setContentLength(body.length);
        if (body.length == 0) {
            response.flushBuffer();
            return;
        }
        ServletOutputStream stream = response.getOutputStream();
        stream.write(body);
        stream.flush();
    }
}
