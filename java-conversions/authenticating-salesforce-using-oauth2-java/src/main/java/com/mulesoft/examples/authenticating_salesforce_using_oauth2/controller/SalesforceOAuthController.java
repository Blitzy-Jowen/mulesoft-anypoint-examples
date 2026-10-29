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
import org.springframework.web.bind.annotation.CookieValue;
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
 *       held, with the {@code state} of the authorization it starts in the URL and in the browser's
 *       {@link SalesforceOAuthService#STATE_COOKIE} cookie, otherwise the flow answers with its {@code set-payload}
 *       text (line 17);</li>
 *   <li>{@code /oauth2callback}, the callback the connector's {@code sfdc:oauth-callback-config} served on
 *       {@code localhost:8081} (line 4): when the {@code state} Salesforce returns matches that cookie and an
 *       authorization still pending, the code is exchanged, the token stored and the flow resumed, so the callback
 *       answers with the same text.</li>
 * </ul>
 * Both paths share the one listener on {@code ${http.port:8081}}, which keeps
 * {@code http://localhost:8081/oauth2callback} valid with the defaults. The authorize decision, the {@code state}
 * and its cookie, the callback's checks, the code exchange, the token and the query belong to
 * {@link SalesforceOAuthService}; this class only binds the request and renders the outcome.
 *
 * <p>Neither listener declares {@code allowedMethods}, so every method is accepted and answered alike: each path has a
 * method-less mapping plus an explicit {@code OPTIONS} mapping, and no mapping declares a media-type condition, so no
 * {@code Accept} or {@code Content-Type} a caller sends turns into a 406 or 415. The listener applied no CORS policy,
 * so {@code config.PreflightDispatchConfig} dispatches a CORS pre-flight to the {@code OPTIONS} mapping as the plain
 * {@code OPTIONS} request it is, and it runs the flow too.
 *
 * <p>No exception is handled here, as the flow declares no exception strategy: the SC-9
 * {@code UpstreamExceptionHandler} answers the approved Salesforce failure modes (502, 429, 503) and the SC-1
 * {@code GlobalExceptionHandler} every other failure with its 500, an unknown path with its no-listener 404. The
 * service is always called before the response is touched, so a failure finds an uncommitted response.
 *
 * <p>The 200 text goes out with its {@code Content-Length} and no {@code Content-Type}, written to the servlet
 * response directly so that no message converter adds one: the flow's {@code set-payload} (line 17) declares no MIME
 * type, and the Mule listener sent no Content-Type for such a payload. The absent Content-Type is a Tier 2A
 * verification point, where a captured fixture of the original governs.
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

    private final SalesforceOAuthService service;

    /**
     * @param service the authorization-code flow and the contact query of {@code salesforce-oauthFlow1}
     */
    public SalesforceOAuthController(SalesforceOAuthService service) {
        this.service = service;
    }

    /**
     * Answers {@code /} for every method except {@code OPTIONS}: 302 to the Salesforce authorization page, with the
     * state cookie of the authorization it starts, while no token is held, otherwise 200 with
     * {@code Salesforce query returned <n> contacts.} and no cookie.
     *
     * @param response the response the redirect or the text is committed to
     * @throws Exception every failure of the authorize step or the query, unchanged, for the advices
     */
    @RequestMapping("/")
    public void root(HttpServletResponse response) throws Exception {
        render(service.authorize(), response);
    }

    /**
     * Answers {@code OPTIONS /} like every other method. A mapping without methods deliberately does not match
     * {@code OPTIONS}, which Spring MVC would otherwise answer itself with an {@code Allow} header and no body; the
     * Mule listener ran the flow for {@code OPTIONS} too.
     *
     * @param response the response the redirect or the text is committed to
     * @throws Exception every failure of the authorize step or the query, unchanged
     */
    @RequestMapping(path = "/", method = RequestMethod.OPTIONS)
    public void rootOptions(HttpServletResponse response) throws Exception {
        root(response);
    }

    /**
     * Answers the callback Salesforce redirects the browser to, for every method except {@code OPTIONS}: 200 with
     * {@code Salesforce query returned <n> contacts.} once the code has been exchanged. The service exchanges a code
     * only for the browser that started the authorization at {@code /}: {@code state} must equal its state cookie and
     * name an authorization still pending, otherwise the callback fails before any token request.
     *
     * @param code             the {@code code} parameter Salesforce issues, or {@code null}
     * @param error            the {@code error} parameter of a refused authorization, or {@code null}
     * @param errorDescription the {@code error_description} parameter of a refused authorization, or {@code null}
     * @param state            the {@code state} parameter Salesforce returns from the authorization URL, or
     *                         {@code null}
     * @param stateCookie      the browser's {@link SalesforceOAuthService#STATE_COOKIE} cookie, or {@code null}
     * @param response         the response the text is committed to
     * @throws Exception a refused authorization or a callback the browser did not start
     *                   ({@code IllegalStateException}), a failed exchange or a failed query, unchanged, for the
     *                   advices
     */
    @RequestMapping(CALLBACK_PATH)
    public void callback(@RequestParam(name = "code", required = false) String code,
                         @RequestParam(name = "error", required = false) String error,
                         @RequestParam(name = "error_description", required = false) String errorDescription,
                         @RequestParam(name = "state", required = false) String state,
                         @CookieValue(name = SalesforceOAuthService.STATE_COOKIE, required = false) String stateCookie,
                         HttpServletResponse response) throws Exception {
        text(service.completeAuthorization(code, error, errorDescription, state, stateCookie), response);
    }

    /**
     * Answers {@code OPTIONS} on the callback like every other method, for the reason given at
     * {@link #rootOptions(HttpServletResponse)}.
     *
     * @param code             the {@code code} parameter Salesforce issues, or {@code null}
     * @param error            the {@code error} parameter of a refused authorization, or {@code null}
     * @param errorDescription the {@code error_description} parameter of a refused authorization, or {@code null}
     * @param state            the {@code state} parameter Salesforce returns from the authorization URL, or
     *                         {@code null}
     * @param stateCookie      the browser's {@link SalesforceOAuthService#STATE_COOKIE} cookie, or {@code null}
     * @param response         the response the text is committed to
     * @throws Exception every failure of the callback, unchanged
     */
    @RequestMapping(path = CALLBACK_PATH, method = RequestMethod.OPTIONS)
    public void callbackOptions(@RequestParam(name = "code", required = false) String code,
                                @RequestParam(name = "error", required = false) String error,
                                @RequestParam(name = "error_description", required = false) String errorDescription,
                                @RequestParam(name = "state", required = false) String state,
                                @CookieValue(name = SalesforceOAuthService.STATE_COOKIE, required = false)
                                String stateCookie,
                                HttpServletResponse response) throws Exception {
        callback(code, error, errorDescription, state, stateCookie, response);
    }

    /**
     * Renders the outcome of the authorize step: a redirect as 302 with {@code Location}, the {@code Set-Cookie} of
     * its state cookie and an empty body, no Content-Type; a completed query as the text of
     * {@link #text(String, HttpServletResponse)}, which sets no cookie.
     *
     * <p>Every reply is committed here with its {@code Content-Length} rather than returned as a
     * {@code ResponseEntity}: Spring MVC leaves a bodiless entity uncommitted, and {@code FrameworkServlet} then
     * completes an uncommitted reply with an {@code Allow} header for {@code OPTIONS} and, because
     * {@code spring.mvc.dispatch-trace-request} routes {@code TRACE} here, with the servlet's {@code message/http} echo
     * for {@code TRACE}, so those methods would not get the answer every other method gets. {@code Location} is set as
     * a plain header, not through {@code sendRedirect}, which may rewrite the value, and the state cookie as the
     * {@code Set-Cookie} header its {@code ResponseCookie} renders, with every attribute the service gave it.
     *
     * @param outcome  the authorize step's outcome: the redirect location and its state cookie, or the completed
     *                 query's text
     * @param response the response the reply is committed to
     * @throws IOException when committing the reply fails
     */
    private static void render(AuthorizeOutcome outcome, HttpServletResponse response) throws IOException {
        if (outcome.isRedirect()) {
            response.setStatus(HttpServletResponse.SC_FOUND);
            response.setHeader(HttpHeaders.LOCATION, outcome.redirectLocation().toASCIIString());
            response.addHeader(HttpHeaders.SET_COOKIE, outcome.stateCookie().toString());
            response.setContentLength(0);
            response.flushBuffer();
            return;
        }
        text(outcome.body(), response);
    }

    /**
     * Commits 200 with the UTF-8 bytes of {@code body}, their {@code Content-Length} and no {@code Content-Type}, the
     * reply the Mule listener sent for the flow's untyped {@code set-payload}. Writing to the servlet response keeps
     * every message converter, and so every Content-Type and {@code Accept} negotiation, out of the reply; a
     * {@code HEAD} reply carries the same headers without the body.
     *
     * @param body     the flow's {@code set-payload} text
     * @param response the response the text is committed to
     * @throws IOException when writing or committing the reply fails
     */
    private static void text(String body, HttpServletResponse response) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
        response.flushBuffer();
    }
}
