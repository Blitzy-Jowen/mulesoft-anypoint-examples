/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_oauth_provider.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mulesoft.examples.http_oauth_provider.service.OAuthProviderService;
import com.mulesoft.examples.http_oauth_provider.service.OAuthProviderService.IssuedToken;
import com.mulesoft.examples.http_oauth_provider.service.OAuthProviderService.OAuthError;

/**
 * The token endpoint of the original OAuth 2.0 provider module ({@code oauth2-provider:config}, xml:26-41),
 * served on the provider listener ({@code http.provider.port}).
 *
 * <p>It accepts GET as well as POST because the demonstration {@code /redirect} flow (xml:53-57) answers the
 * browser with a 302 to {@code /token?grant_type=authorization_code&&client_id=...&client_secret=...&code=...
 * &redirect_uri=...}, which the browser follows with a GET. Parameters bind from the query string or from an
 * {@code application/x-www-form-urlencoded} body, so no {@code consumes} or {@code produces} is declared; the
 * empty parameter between the two ampersands is simply never bound.
 *
 * <p>This class only binds the request and shapes the response. Grant-type checks, client authentication (from
 * the parameters or an HTTP Basic {@code Authorization} header), single-use code consumption, redirect-URI
 * matching and token issuance all belong to {@link OAuthProviderService#exchangeAuthorizationCode}.
 *
 * <p>Error precedence: an {@link OAuthError} is answered by {@link #oauthError}, which Spring resolves before any
 * {@code @ControllerAdvice}. No other exception type is claimed here, so a method other than GET, HEAD or POST
 * still reaches the generic handler's 405 {@code Method not allowed for endpoint: /token}, and any other failure
 * its 500. {@code OPTIONS}, which Spring MVC would otherwise answer itself with 200 and an {@code Allow} header, is
 * mapped explicitly to {@link #rejectOptions} so that it gets the same 405, a CORS pre-flight included. Which port
 * serves {@code /token} is decided by the port-routing filter, not here.
 *
 * <p>The token body's keys and order, the error statuses and bodies, and the cache headers are Tier 2A
 * verification points: a fixture captured from the original provider governs.
 */
@RestController
public class TokenController {

    private static final String PRAGMA_NO_CACHE = "no-cache";

    /** The methods {@link #token} serves, named by the 405 {@link #rejectOptions} raises. */
    private static final List<String> SUPPORTED_METHODS =
            List.of(RequestMethod.GET.name(), RequestMethod.POST.name());

    private final OAuthProviderService service;

    /**
     * @param service the provider that validates the exchange and issues the token
     */
    public TokenController(OAuthProviderService service) {
        this.service = service;
    }

    /**
     * Exchanges an authorization code for an access token.
     *
     * @param grantType     the {@code grant_type} parameter, nullable
     * @param code          the {@code code} parameter, nullable
     * @param redirectUri   the {@code redirect_uri} parameter, nullable
     * @param clientId      the {@code client_id} parameter, nullable
     * @param clientSecret  the {@code client_secret} parameter, nullable
     * @param authorization the raw {@code Authorization} header, nullable
     * @return 200 {@code application/json} with {@code Cache-Control: no-store} and {@code Pragma: no-cache}; the
     *         body is the token as one flat object ({@code access_token}, {@code token_type}, {@code expires_in},
     *         {@code scope}), because the original test reads the page from its first opening brace to its first
     *         closing brace
     * @throws OAuthError when the service rejects the exchange, answered by {@link #oauthError}
     */
    @RequestMapping(path = "/token", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Map<String, Object>> token(
            @RequestParam(name = "grant_type", required = false) String grantType,
            @RequestParam(name = "code", required = false) String code,
            @RequestParam(name = "redirect_uri", required = false) String redirectUri,
            @RequestParam(name = "client_id", required = false) String clientId,
            @RequestParam(name = "client_secret", required = false) String clientSecret,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        IssuedToken token = service.exchangeAuthorizationCode(grantType, code, redirectUri, clientId, clientSecret,
                authorization);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, PRAGMA_NO_CACHE)
                .body(token.toJsonBody());
    }

    /**
     * Rejects {@code OPTIONS /token} with the 405 of {@code GlobalExceptionHandler} that every method other than GET,
     * HEAD and POST receives. A method-restricted mapping does not match {@code OPTIONS}, so without this one Spring
     * MVC would answer it itself, with 200 and an {@code Allow} header.
     *
     * @throws HttpRequestMethodNotSupportedException always, answered with 405 {@code text/plain}
     *                                                {@code Method not allowed for endpoint: /token}
     */
    @RequestMapping(path = "/token", method = RequestMethod.OPTIONS)
    public void rejectOptions() throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(RequestMethod.OPTIONS.name(), SUPPORTED_METHODS);
    }

    /**
     * Answers an OAuth error raised by the exchange, as the authorization endpoint answers its own.
     *
     * @param err the error the service threw
     * @return {@code err.status()}, {@code application/json}, {@code WWW-Authenticate} when the error carries one
     *         (an {@code invalid_client} of a Basic-authenticated client), and the body {@code err.toJsonBody()}
     */
    @ExceptionHandler(OAuthError.class)
    public ResponseEntity<Map<String, String>> oauthError(OAuthError err) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(err.status())
                .contentType(MediaType.APPLICATION_JSON);
        if (err.wwwAuthenticate() != null) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, err.wwwAuthenticate());
        }
        return response.body(err.toJsonBody());
    }
}
