/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.authenticating_salesforce_using_oauth2.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;

import com.mulesoft.examples.authenticating_salesforce_using_oauth2.service.SalesforceOAuthService;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.service.SalesforceOAuthService.AuthorizeOutcome;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry points of flow {@code salesforce-oauthFlow1}
 * (authenticating-salesforce-using-oauth2/src/main/app/salesforce-oauth.xml):
 * <ul>
 *   <li>{@code /}, the {@code http:listener} of the flow (line 10) on the {@code 0.0.0.0:${http.port}} listener
 *       configuration (line 6): {@code sfdc:authorize} (line 12) redirects a browser to Salesforce while no token is
 *       held, otherwise the flow answers with its {@code set-payload} text (line 17);</li>
 *   <li>{@code /oauth2callback}, the callback the connector's {@code sfdc:oauth-callback-config} served on
 *       {@code localhost:8081} (line 4): the code is exchanged, the token stored and the flow resumed, so the callback
 *       answers with the same text.</li>
 * </ul>
 * Both paths share the one listener on {@code ${http.port:8081}}, which keeps
 * {@code http://localhost:8081/oauth2callback} valid with the defaults. The authorize decision, the code exchange, the
 * token and the query belong to {@link SalesforceOAuthService}; this class only binds the request and renders the
 * outcome.
 *
 * <p>Neither listener declares {@code allowedMethods}, so every method is accepted and answered alike: each path has a
 * method-less mapping plus an explicit {@code OPTIONS} mapping, and no mapping declares a media-type condition, so no
 * {@code Accept} or {@code Content-Type} a caller sends turns into a 406 or 415.
 *
 * <p>No exception is handled here, as the flow declares no exception strategy: the SC-9
 * {@code UpstreamExceptionHandler} answers the approved Salesforce failure modes (502, 429, 503) and the SC-1
 * {@code GlobalExceptionHandler} every other failure with its 500, an unknown path with its no-listener 404. The
 * service is always called before the response is touched, so a failure finds an uncommitted response.
 */
@RestController
public class SalesforceOAuthController {

    /**
     * Mapping of the OAuth callback, the path {@code Callback#redirectUri()} sends as {@code redirect_uri}. Written
     * without a leading {@code /}, which Spring MVC prepends when a pattern lacks it: that callback key is documented
     * as accepted with or without its leading {@code /} (both build the same {@code redirect_uri}), and a literal
     * {@code "/${...}"} would map {@code //oauth2callback} for the slash form, a path no request matches.
     */
    static final String CALLBACK_PATH = "${sfdc.oauth.callback.path:oauth2callback}";

    /** Content-Type of the flow's text answer; renders as {@code text/plain;charset=UTF-8}. */
    private static final MediaType TEXT_PLAIN_UTF8 = new MediaType("text", "plain", StandardCharsets.UTF_8);

    private final SalesforceOAuthService service;

    /**
     * @param service the authorization-code flow and the contact query of {@code salesforce-oauthFlow1}
     */
    public SalesforceOAuthController(SalesforceOAuthService service) {
        this.service = service;
    }

    /**
     * Answers {@code /} for every method except {@code OPTIONS}: 302 to the Salesforce authorization page while no
     * token is held, otherwise 200 with {@code Salesforce query returned <n> contacts.}.
     *
     * @param response the response the redirect is committed to
     * @return the 200 text answer, or {@code null} once the redirect has been committed
     * @throws Exception every failure of the authorize step or the query, unchanged, for the advices
     */
    @RequestMapping("/")
    public ResponseEntity<String> root(HttpServletResponse response) throws Exception {
        return render(service.authorize(), response);
    }

    /**
     * Answers {@code OPTIONS /} like every other method. A mapping without methods deliberately does not match
     * {@code OPTIONS}, which Spring MVC would otherwise answer itself with an {@code Allow} header and no body; the
     * Mule listener ran the flow for {@code OPTIONS} too.
     *
     * @param response the response the redirect is committed to
     * @return the answer of {@link #root(HttpServletResponse)}
     * @throws Exception every failure of the authorize step or the query, unchanged
     */
    @RequestMapping(path = "/", method = RequestMethod.OPTIONS)
    public ResponseEntity<String> rootOptions(HttpServletResponse response) throws Exception {
        return root(response);
    }

    /**
     * Answers the callback Salesforce redirects the browser to, for every method except {@code OPTIONS}: 200 with
     * {@code Salesforce query returned <n> contacts.} once the code has been exchanged.
     *
     * @param code             the {@code code} parameter Salesforce issues, or {@code null}
     * @param error            the {@code error} parameter of a refused authorization, or {@code null}
     * @param errorDescription the {@code error_description} parameter of a refused authorization, or {@code null}
     * @return the 200 text answer
     * @throws Exception a refused authorization ({@code IllegalStateException}), a failed exchange or a failed query,
     *                   unchanged, for the advices
     */
    @RequestMapping(CALLBACK_PATH)
    public ResponseEntity<String> callback(@RequestParam(name = "code", required = false) String code,
                                           @RequestParam(name = "error", required = false) String error,
                                           @RequestParam(name = "error_description", required = false)
                                           String errorDescription) throws Exception {
        return text(service.completeAuthorization(code, error, errorDescription));
    }

    /**
     * Answers {@code OPTIONS} on the callback like every other method, for the reason given at
     * {@link #rootOptions(HttpServletResponse)}.
     *
     * @param code             the {@code code} parameter Salesforce issues, or {@code null}
     * @param error            the {@code error} parameter of a refused authorization, or {@code null}
     * @param errorDescription the {@code error_description} parameter of a refused authorization, or {@code null}
     * @return the answer of {@link #callback(String, String, String)}
     * @throws Exception every failure of the callback, unchanged
     */
    @RequestMapping(path = CALLBACK_PATH, method = RequestMethod.OPTIONS)
    public ResponseEntity<String> callbackOptions(@RequestParam(name = "code", required = false) String code,
                                                  @RequestParam(name = "error", required = false) String error,
                                                  @RequestParam(name = "error_description", required = false)
                                                  String errorDescription) throws Exception {
        return callback(code, error, errorDescription);
    }

    /**
     * Renders the outcome of the authorize step. A redirect is 302 with {@code Location} and an empty body, no
     * Content-Type.
     *
     * <p>The redirect is committed here with {@code Content-Length: 0} rather than returned as a bodiless
     * {@link ResponseEntity}: Spring MVC leaves such an entity uncommitted, and {@code FrameworkServlet} then
     * completes it with an {@code Allow} header for {@code OPTIONS} and, because
     * {@code spring.mvc.dispatch-trace-request} routes {@code TRACE} here, with the servlet's {@code message/http} echo
     * for {@code TRACE}, so those methods would not get the 302 every other method gets. {@code Location} is set as a
     * plain header, not through {@code sendRedirect}, which may rewrite the value.
     */
    private static ResponseEntity<String> render(AuthorizeOutcome outcome, HttpServletResponse response)
            throws IOException {
        if (outcome.isRedirect()) {
            response.setStatus(HttpServletResponse.SC_FOUND);
            response.setHeader(HttpHeaders.LOCATION, outcome.redirectLocation().toASCIIString());
            response.setContentLength(0);
            response.flushBuffer();
            return null;
        }
        return text(outcome.body());
    }

    /**
     * 200 with {@code body} as {@code text/plain;charset=UTF-8}. The preset Content-Type makes Spring write the body
     * without consulting {@code Accept}, and the string converter flushes it with its {@code Content-Length}, which
     * commits the reply.
     */
    private static ResponseEntity<String> text(String body) {
        return ResponseEntity.ok().contentType(TEXT_PLAIN_UTF8).body(body);
    }
}
