/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_oauth_provider.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

import com.mulesoft.examples.http_oauth_provider.service.OAuthProviderService;
import com.mulesoft.examples.http_oauth_provider.service.OAuthProviderService.AuthorizationRequest;
import com.mulesoft.examples.http_oauth_provider.service.OAuthProviderService.OAuthError;
import com.mulesoft.examples.http_oauth_provider.service.ResourceOwnerAuthenticator;

/**
 * The provider's {@code /authorize} endpoint and its login page, which the Mule OAuth2 provider module generated
 * from {@code oauth2-provider:config} (original {@code http-oauth-provider.xml} lines 26-41) with the resource
 * owner checked by the {@code ss:authentication-manager} of lines 13-24.
 *
 * <ul>
 *   <li>{@code GET /authorize?response_type=&client_id=&redirect_uri=&scope=&state=}: a valid authorization
 *       request renders the {@code login} view ({@code templates/login.html}), 200 {@code text/html}.</li>
 *   <li>{@code POST /authorize} (the login form, its hidden fields carrying the five request parameters, plus
 *       {@code username} and {@code password}): the request is validated again, so a tampered hidden field gets
 *       the OAuth error rather than a login attempt; an accepted owner is answered 302 {@code Found} to the
 *       location {@link OAuthProviderService#issueAuthorizationCode} returns, unchanged; a rejected owner gets
 *       the login page again, 401, with the error message and the hidden fields kept.</li>
 *   <li>An invalid authorization request on either method: the {@link OAuthError}'s status, {@code
 *       application/json} {@code {"error":...,"error_description":...}} and its {@code WWW-Authenticate} value
 *       when it has one.</li>
 *   <li>{@code OPTIONS /authorize}, a CORS pre-flight included: the 405 {@code Method not allowed for endpoint:
 *       /authorize} that every method other than GET, HEAD and POST gets, raised by {@link #rejectOptions} because
 *       Spring MVC would otherwise answer {@code OPTIONS} itself with 200 and an {@code Allow} header.</li>
 * </ul>
 *
 * <p>The class only binds request input and shapes the response: client, redirect-URI, scope and code decisions
 * belong to {@link OAuthProviderService}, the owner check to {@link ResourceOwnerAuthenticator}, so no client or
 * owner credential appears here. No authentication layer is added beyond that owner check. The local handler
 * declares {@link OAuthError} alone, so unsupported methods (405) and every other exception still reach the
 * project's {@code GlobalExceptionHandler}; restricting {@code /authorize} to the provider port is the job of
 * {@code PortRoutingFilter}. No {@code consumes} or {@code produces} is declared, so the parameters bind from the
 * query string and from an {@code application/x-www-form-urlencoded} body alike.
 *
 * <p>The error statuses and bodies, the rejected-login status and page and the login markup are Tier 2A
 * verification points: a fixture captured from the original provider governs.
 *
 * <p>The instance holds no mutable state and is safe for concurrent use.
 */
@Controller
@RequestMapping("/authorize")
public class AuthorizationController {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationController.class);

    /** The Thymeleaf view {@code templates/login.html}. */
    private static final String VIEW = "login";

    /** The message the login page shows after a rejected login: a UI text, not a credential. */
    private static final String INVALID_CREDENTIALS = "Invalid username or password";

    /** The methods {@link #loginPage} and {@link #login} serve, named by the 405 {@link #rejectOptions} raises. */
    private static final List<String> SUPPORTED_METHODS =
            List.of(RequestMethod.GET.name(), RequestMethod.POST.name());

    private final OAuthProviderService service;

    private final ResourceOwnerAuthenticator authenticator;

    /**
     * @param service       the provider that validates authorization requests and issues codes
     * @param authenticator the resource-owner check of the login form
     * @throws NullPointerException if either argument is {@code null}
     */
    public AuthorizationController(OAuthProviderService service, ResourceOwnerAuthenticator authenticator) {
        this.service = Objects.requireNonNull(service, "service");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
    }

    /**
     * Renders the login page for a valid authorization request.
     *
     * @param responseType the {@code response_type} parameter, nullable
     * @param clientId     the {@code client_id} parameter, nullable
     * @param redirectUri  the {@code redirect_uri} parameter, nullable
     * @param scope        the {@code scope} parameter, nullable
     * @param state        the {@code state} parameter, nullable
     * @return the {@code login} view, 200, without an error message
     * @throws OAuthError when the request is invalid, answered by {@link #oauthError}
     */
    @GetMapping
    public ModelAndView loginPage(
            @RequestParam(name = "response_type", required = false) String responseType,
            @RequestParam(name = "client_id", required = false) String clientId,
            @RequestParam(name = "redirect_uri", required = false) String redirectUri,
            @RequestParam(name = "scope", required = false) String scope,
            @RequestParam(name = "state", required = false) String state) {
        service.validateAuthorizationRequest(responseType, clientId, redirectUri, scope, state);
        return new ModelAndView(VIEW, loginModel(responseType, clientId, redirectUri, scope, state, null));
    }

    /**
     * Handles the submitted login form.
     *
     * @param responseType the {@code response_type} hidden field, nullable
     * @param clientId     the {@code client_id} hidden field, nullable
     * @param redirectUri  the {@code redirect_uri} hidden field, nullable
     * @param scope        the {@code scope} hidden field, nullable
     * @param state        the {@code state} hidden field, nullable
     * @param username     the submitted username, nullable
     * @param password     the submitted password, nullable; never logged
     * @return a 302 {@code Found} to the redirect URI carrying the issued code when the owner is accepted,
     *         otherwise the {@code login} view, 401, with the error message
     * @throws OAuthError when the authorization request is invalid, answered by {@link #oauthError}
     */
    @PostMapping
    public ModelAndView login(
            @RequestParam(name = "response_type", required = false) String responseType,
            @RequestParam(name = "client_id", required = false) String clientId,
            @RequestParam(name = "redirect_uri", required = false) String redirectUri,
            @RequestParam(name = "scope", required = false) String scope,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "username", required = false) String username,
            @RequestParam(name = "password", required = false) String password) {
        AuthorizationRequest request =
                service.validateAuthorizationRequest(responseType, clientId, redirectUri, scope, state);

        if (!authenticator.authenticate(username, password)) {
            log.debug("Resource owner login rejected; login page rendered again");
            // The repository records no rejected-login answer of the generated page; 401 with the page rendered
            // again stands until a captured Tier 2A fixture shows the original's status and markup.
            return new ModelAndView(VIEW,
                    loginModel(responseType, clientId, redirectUri, scope, state, INVALID_CREDENTIALS),
                    HttpStatus.UNAUTHORIZED);
        }

        String location = service.issueAuthorizationCode(request, username);
        log.debug("Resource owner authenticated; redirecting to the redirect URI with an authorization code");
        return new ModelAndView(redirectTo(location));
    }

    /**
     * Rejects {@code OPTIONS /authorize} with the 405 of {@code GlobalExceptionHandler} that every method other than
     * GET, HEAD and POST receives. Method-restricted mappings do not match {@code OPTIONS}, so without this one Spring
     * MVC would answer it itself, with 200 and an {@code Allow} header.
     *
     * @throws HttpRequestMethodNotSupportedException always, answered with 405 {@code text/plain}
     *                                                {@code Method not allowed for endpoint: /authorize}
     */
    @RequestMapping(method = RequestMethod.OPTIONS)
    public void rejectOptions() throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(RequestMethod.OPTIONS.name(), SUPPORTED_METHODS);
    }

    /**
     * Answers an invalid authorization request raised by {@link #loginPage} or {@link #login}.
     *
     * @param err the error {@link OAuthProviderService} threw
     * @return the error's status, {@code application/json} body {@code {"error":...,"error_description":...}}
     *         and, when the error carries one, its {@code WWW-Authenticate} header
     */
    @ExceptionHandler(OAuthError.class)
    public ResponseEntity<Map<String, String>> oauthError(OAuthError err) {
        log.debug("Authorization request rejected: {} {}", err.status().value(), err.error());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(err.status())
                .contentType(MediaType.APPLICATION_JSON);
        if (err.wwwAuthenticate() != null) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, err.wwwAuthenticate());
        }
        return response.body(err.toJsonBody());
    }

    /**
     * The model {@code templates/login.html} reads: the configured provider and client texts, the five request
     * parameters echoed into the hidden fields as received, and the error message. A {@link LinkedHashMap}
     * because absent parameters and the error are {@code null}, which {@code Map.of} rejects.
     */
    private Map<String, Object> loginModel(String responseType, String clientId, String redirectUri, String scope,
                                           String state, String error) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("providerName", service.providerName());
        model.put("clientName", service.clientName());
        model.put("clientDescription", service.clientDescription());
        model.put("responseType", responseType);
        model.put("clientId", clientId);
        model.put("redirectUri", redirectUri);
        model.put("scope", scope);
        model.put("state", state);
        model.put("error", error);
        return model;
    }

    /**
     * A 302 {@code Found} whose {@code Location} is {@code location} byte for byte: written as a header rather
     * than through {@code sendRedirect}, with no model attribute appended and no URI template expanded.
     */
    private static RedirectView redirectTo(String location) {
        RedirectView view = new RedirectView(location);
        view.setStatusCode(HttpStatus.FOUND);
        view.setHttp10Compatible(false);
        view.setExposeModelAttributes(false);
        view.setExpandUriTemplateVariables(false);
        return view;
    }
}
