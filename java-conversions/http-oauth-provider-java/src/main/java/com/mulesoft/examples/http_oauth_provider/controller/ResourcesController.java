/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_oauth_provider.controller;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.http_oauth_provider.exception.ErrorResponse;
import com.mulesoft.examples.http_oauth_provider.service.OAuthProviderService;
import com.mulesoft.examples.http_oauth_provider.service.OAuthProviderService.OAuthError;

/**
 * HTTP trigger of the two flows on {@code HTTP_Listener_Configuration} (host {@code localhost}, port
 * {@code http.listener.port}) of {@code http-oauth-provider/src/main/app/http-oauth-provider.xml}:
 * <ul>
 *   <li>{@code protectedAuthcodeFlow} (xml:44-49), {@code /resources}: the bearer token must carry
 *       {@code READ_RESOURCE}, then the resource map is answered as JSON;</li>
 *   <li>{@code redirectFlow} (xml:53-57), {@code /redirect}: the demonstration 302 to the provider's
 *       {@code /token} with the received authorization code.</li>
 * </ul>
 *
 * <p>Token validation, the resource map and the redirect location are all {@link OAuthProviderService}'s;
 * this class only binds the request and shapes the response. Neither listener declares
 * {@code allowedMethods}, so each path answers every method alike: a method-less mapping serves all of
 * them except {@code OPTIONS}, which Spring MVC would otherwise answer itself with an {@code Allow} header,
 * so each path also carries an explicit {@code OPTIONS} mapping running the same logic. No handler declares
 * {@code consumes} or {@code produces}, so a request's {@code Content-Type} or {@code Accept} never turns
 * into a 415 or 406. The paths match exactly; confining them to the resource listener's port is
 * {@code config.PortRoutingFilter}'s job.
 *
 * <p>Every response is complete when its handler returns: it declares its {@code Content-Length} and has
 * written exactly that many bytes, which ends it. {@code application.yml} dispatches {@code TRACE} to these
 * handlers, as the listeners ran their flows for it, yet {@code FrameworkServlet} afterwards still runs the
 * servlet's own {@code TRACE} echo, and after an {@code OPTIONS} handler that set no {@code Allow} it still
 * adds one. Undertow does not commit a response on a flush, so a response left open would take the echo of
 * the request, its {@code Authorization} header included, or the {@code Allow} header; a complete one
 * takes neither.
 */
@RestController
public class ResourcesController {

    private final OAuthProviderService oauthProviderService;

    private final ObjectMapper objectMapper;

    /**
     * @param oauthProviderService the provider that validates tokens and supplies the resource and location
     * @param objectMapper         the application's JSON mapper, the one Spring's JSON converter writes with
     */
    public ResourcesController(OAuthProviderService oauthProviderService, ObjectMapper objectMapper) {
        this.oauthProviderService = oauthProviderService;
        this.objectMapper = objectMapper;
    }

    /**
     * Answers {@code /resources} for every method except {@code OPTIONS}; a {@code HEAD} response carries
     * the headers without the body.
     *
     * @param authorization the raw {@code Authorization} header, nullable
     * @return 200 {@code application/json}
     *         {@code {"name":"payroll","uri":"http://localhost:<listener port>/resources/payroll"}}
     * @throws OAuthError             when the token is missing, invalid, expired or lacks the resource scope,
     *                                 answered by {@link #oauthError}
     * @throws JsonProcessingException when the resource cannot be serialized
     */
    @RequestMapping("/resources")
    public ResponseEntity<byte[]> resources(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization)
            throws JsonProcessingException {
        return resourcesResponse(authorization);
    }

    /**
     * Answers {@code OPTIONS /resources} like every other method, as the Mule listener ran the flow for it.
     *
     * @param authorization the raw {@code Authorization} header, nullable
     * @return the response of {@link #resources}
     * @throws JsonProcessingException when the resource cannot be serialized
     */
    @RequestMapping(path = "/resources", method = RequestMethod.OPTIONS)
    public ResponseEntity<byte[]> resourcesOptions(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization)
            throws JsonProcessingException {
        return resourcesResponse(authorization);
    }

    /**
     * Answers {@code /redirect} for every method except {@code OPTIONS} with the flow's 302: the
     * {@code Location} of {@link OAuthProviderService#redirectLocation} and an empty body.
     *
     * @param request  the request whose raw query string carries the {@code code}
     * @param response the response the 302 is written to
     * @throws IOException when writing the response fails
     */
    @RequestMapping("/redirect")
    public void redirect(HttpServletRequest request, HttpServletResponse response) throws IOException {
        redirectResponse(request, response);
    }

    /**
     * Answers {@code OPTIONS /redirect} like every other method, as the Mule listener ran the flow for it.
     *
     * @param request  the request whose raw query string carries the {@code code}
     * @param response the response the 302 is written to
     * @throws IOException when writing the response fails
     */
    @RequestMapping(path = "/redirect", method = RequestMethod.OPTIONS)
    public void redirectOptions(HttpServletRequest request, HttpServletResponse response) throws IOException {
        redirectResponse(request, response);
    }

    /**
     * Answers a rejected bearer token, the {@code throwExceptionOnUnaccepted="true"} outcome of the original
     * {@code oauth2-provider:validate}. Only {@link OAuthError} is declared, so every other exception still
     * reaches {@code GlobalExceptionHandler}'s 500.
     *
     * @param err the rejection {@link OAuthProviderService#validateAccessToken} threw
     * @return the error's status, {@code application/json} {@code { "message": "<description>" }}, and its
     *         {@code WWW-Authenticate} value when it has one
     */
    @ExceptionHandler(OAuthError.class)
    public ResponseEntity<String> oauthError(OAuthError err) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(err.status())
                .contentType(MediaType.APPLICATION_JSON);
        if (err.wwwAuthenticate() != null) {
            builder.header(HttpHeaders.WWW_AUTHENTICATE, err.wwwAuthenticate());
        }
        return builder.body(ErrorResponse.json(err.description()));
    }

    /**
     * Validates the token, then answers the resource as compact JSON. The map is serialized as the service
     * built it, a {@code HashMap} iterating {@code name} before {@code uri} as the MEL map literal did, so
     * the original's key order is kept. It is serialized here rather than by Spring's JSON converter, which
     * declares no length (see the class comment); the preset Content-Type keeps the request's
     * {@code Accept} out of the choice.
     */
    private ResponseEntity<byte[]> resourcesResponse(String authorization) throws JsonProcessingException {
        oauthProviderService.validateAccessToken(authorization);
        Map<String, String> resource = oauthProviderService.protectedResource();
        byte[] body = objectMapper.writeValueAsBytes(resource);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(body.length)
                .body(body);
    }

    /**
     * Writes the 302. The location goes out as a raw header string, never through {@code URI} or
     * {@code sendRedirect}, so the original's {@code &&} and unencoded {@code redirect_uri} reach the wire
     * byte for byte. The flow set no payload; the response carries no body, which is what the bodiless
     * browser GET of the original got. It is written to the servlet response, without a Content-Type, and
     * closed, which completes it (see the class comment); an empty {@code ResponseEntity} would only be
     * flushed. The location is computed before the response is touched, so a failure still finds an
     * uncommitted response for {@code GlobalExceptionHandler}'s 500.
     */
    private void redirectResponse(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String location = oauthProviderService.redirectLocation(request.getQueryString());
        response.setStatus(HttpStatus.FOUND.value());
        response.setHeader(HttpHeaders.LOCATION, location);
        response.setContentLength(0);
        response.getOutputStream().close();
    }
}
