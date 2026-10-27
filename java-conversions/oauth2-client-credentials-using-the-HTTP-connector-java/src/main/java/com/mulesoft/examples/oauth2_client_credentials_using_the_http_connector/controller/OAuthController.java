/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
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
 *       routing logic and returns the service's response as it is.</li>
 * </ul>
 * A failure the service rethrows reaches SC-1 {@code GlobalExceptionHandler}, which answers the platform-default
 * 500 {@code { "message": "<exception message>" }}.
 *
 * <p>A CORS pre-flight ({@code OPTIONS} with {@code Origin} and {@code Access-Control-Request-Method}) is dispatched
 * by {@code PreflightDispatchConfig} as the plain {@code OPTIONS} request it is, so it runs the flow through the
 * companion mapping like any other {@code OPTIONS} request, as the Mule listener, which applied no CORS policy,
 * did.
 *
 * <p>Tier 2A verification points, where the Spring MVC request handling may differ from the Mule listener:
 * <ol>
 *   <li>For a form-encoded POST without a query string, Spring's {@code ServletServerHttpRequest} rebuilds the
 *       body from the request parameters, re-encoded and in parameter-map order, so the logged payload may not be
 *       byte-identical to the inbound bytes. Form-encoded bodies of the other methods arrive unchanged, because
 *       {@code application.yml} disables Boot's {@code FormContentFilter}.</li>
 *   <li>TRACE runs the flow like every other method, because {@code application.yml} sets
 *       {@code spring.mvc.dispatch-trace-request}; whether the Mule listener ran the flow for TRACE is
 *       unverified.</li>
 *   <li>A Content-Type that cannot be parsed ends in the 500 of {@code GlobalExceptionHandler} while the body is
 *       read, before the flow's logger runs.</li>
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
     * @param body the inbound body, or {@code null} when the request carried none
     * @return the service's response, unchanged
     */
    @RequestMapping("/oauth")
    public ResponseEntity<byte[]> oauth(@RequestBody(required = false) byte[] body) {
        return boxClientCredentialsService.authorize(body);
    }

    /**
     * Runs {@code OPTIONS /oauth} like every other method. A mapping without methods deliberately does not match
     * OPTIONS, which Spring MVC would otherwise answer itself with an {@code Allow} header; the Mule listener ran
     * the flow for OPTIONS too.
     *
     * @param body the inbound body, or {@code null} when the request carried none
     * @return the service's response, unchanged
     */
    @RequestMapping(path = "/oauth", method = RequestMethod.OPTIONS)
    public ResponseEntity<byte[]> oauthOptions(@RequestBody(required = false) byte[] body) {
        return oauth(body);
    }
}
