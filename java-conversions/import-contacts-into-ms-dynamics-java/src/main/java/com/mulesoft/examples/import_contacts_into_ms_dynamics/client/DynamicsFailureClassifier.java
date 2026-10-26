/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_ms_dynamics.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Classifies failed Microsoft Dynamics 365 calls (the Microsoft Entra client-credentials token request and the
 * Dataverse Web API v9.2 contact create, query and delete) for {@code UpstreamCallExecutor}: the Dynamics row of
 * the approved connector failure handling (SC-9).
 *
 * <p>The rules, in the order they are evaluated:
 * <ol>
 *   <li>A timeout or connectivity failure anywhere in the cause chain → {@link UpstreamFailureKind#UNAVAILABLE},
 *       never retried. Reactor Netty's connect failures and response timeouts reach the caller wrapped in a
 *       {@code WebClientRequestException}, and a timed-out token request may additionally be wrapped by Spring
 *       Security in an {@link OAuth2AuthorizationException}; {@link UpstreamFailureClassifier#isUnavailable}
 *       looks through both.</li>
 *   <li>A {@link WebClientResponseException} with HTTP 401, from Dataverse or from the Entra token endpoint →
 *       {@link UpstreamFailureKind#AUTHENTICATION}: the executor runs the re-authentication step
 *       ({@code DynamicsWebApiClient::evictToken}, which drops the cached Entra token) and retries the call once with
 *       a fresh token. The token WebClient of {@code config/DynamicsOAuthConfig} keeps a token 401 with its status,
 *       so it is an authentication failure whatever its body, an empty or non-JSON one included.</li>
 *   <li>HTTP 429, Dataverse's service-protection throttling or Entra throttling the token request → a rate limit
 *       carrying the {@code Retry-After} header value (seconds) exactly as sent, or {@code null} when none was sent;
 *       never retried. The token WebClient raises a token 429, like a token 401 or 5xx, as a
 *       {@link WebClientResponseException} that keeps the status and headers Spring Security's OAuth 2.0 body
 *       extractor would discard.</li>
 *   <li>An {@link OAuth2AuthorizationException}, including Spring Security's {@code ClientAuthorizationException},
 *       raised when Entra rejects the token request with an OAuth 2.0 error in a 400 response (for example
 *       {@code unauthorized_client}, {@code invalid_grant} or {@code invalid_scope}), or carrying any other rejection
 *       code such as {@code invalid_client} → {@link UpstreamFailureKind#AUTHENTICATION}. The codes that report a
 *       failed token exchange rather than a rejection are not authentication failures and fall to the next rule:
 *       {@code invalid_token_response} (Spring Security could not read the token response: not JSON, empty, or
 *       malformed), {@code server_error} (also Spring Security's code for a token response that carries neither an
 *       access token nor an error code) and {@code temporarily_unavailable}.</li>
 *   <li>Everything else → {@link UpstreamFailureKind#OTHER}, rethrown unchanged: a Dataverse 400, 403, 404 or 5xx,
 *       an Entra 5xx, the token-exchange failures above (an Entra 400, 403 or 404 whose body Spring Security could
 *       not read included), and non-HTTP failures such as the
 *       {@code IllegalStateException} raised for a blank {@code dynamics.*} key or the
 *       {@code IllegalArgumentException} raised for a contact ID that is not a GUID.</li>
 * </ol>
 *
 * <p>The caller-facing message is Dynamics' own error text: the {@code error.message} of a Dataverse OData error
 * body, else the description or code of the Entra OAuth 2.0 error, whether it arrived as a response body kept with
 * its HTTP status or as Spring Security's parsed error. A response whose body carries neither, such as an empty or
 * HTML token 401, falls back to the exception's own message, for example
 * {@code 401 Unauthorized from POST <token URI>}. Request headers, and with them the bearer token and the client
 * secret, never reach it.
 *
 * <p>The classifier is stateless and therefore safe for concurrent use; it never logs, because the executor writes
 * the one ERROR line of each upstream failure.
 */
@Component
public class DynamicsFailureClassifier implements UpstreamFailureClassifier {

    /** Parses error bodies only; parse failures are swallowed, so no parser setting needs to hide the input. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The error member of an error body: the OData error object of a Dataverse body,
     * {@code {"error":{"code":"0x80040217","message":"..."}}}, or the error code string of an Entra OAuth 2.0 body.
     */
    private static final String ERROR = "error";

    private static final String MESSAGE = "message";

    /** The description member of an Entra OAuth 2.0 error body, {@code {"error":"...","error_description":"..."}}. */
    private static final String ERROR_DESCRIPTION = "error_description";

    /**
     * The code Spring Security's {@code OAuth2AccessTokenResponseBodyExtractor} gives an {@link OAuth2Error} when it
     * cannot read a token response (not JSON, empty, or malformed); Spring Security keeps it private.
     */
    private static final String INVALID_TOKEN_RESPONSE = "invalid_token_response";

    /**
     * OAuth 2.0 error codes that report a failed token exchange, not a rejected client or grant: the unreadable
     * response above, and the two codes RFC 6749 gives a failing or overloaded authorization server;
     * {@code server_error} is also the code Spring Security gives a response with neither access token nor error.
     */
    private static final Set<String> TOKEN_EXCHANGE_FAILURE_CODES = Set.of(
            INVALID_TOKEN_RESPONSE, OAuth2ErrorCodes.SERVER_ERROR, OAuth2ErrorCodes.TEMPORARILY_UNAVAILABLE);

    /**
     * @return {@link UpstreamSystem#MICROSOFT_DYNAMICS_365}
     */
    @Override
    public UpstreamSystem system() {
        return UpstreamSystem.MICROSOFT_DYNAMICS_365;
    }

    /**
     * Classifies a throwable raised by a Dataverse call or by the Entra token request it makes, by the rules listed
     * on the class.
     *
     * @param failure the throwable exactly as the call raised it; {@code null} is classified as
     *                {@link UpstreamFailureKind#OTHER}
     * @return the classification; never {@code null}
     */
    @Override
    public UpstreamFailureKind classify(Throwable failure) {
        if (failure == null) {
            return UpstreamFailureKind.OTHER;
        }
        // Unavailable is decided before any HTTP status or OAuth 2.0 error, as the UpstreamFailureClassifier
        // contract and the never-retry rule for timeouts require: a timed-out or disconnected request may already
        // have created the contact, so a 401 or authorization error found in the same chain must not trigger the
        // re-authentication retry that would submit it again.
        if (UpstreamFailureClassifier.isUnavailable(failure)) {
            return UpstreamFailureKind.UNAVAILABLE;
        }
        List<Throwable> chain = causeChain(failure);
        if (firstResponse(chain, HttpStatus.UNAUTHORIZED) != null) {
            return UpstreamFailureKind.AUTHENTICATION;
        }
        WebClientResponseException throttled = firstResponse(chain, HttpStatus.TOO_MANY_REQUESTS);
        if (throttled != null) {
            return UpstreamFailureKind.rateLimit(retryAfter(throttled));
        }
        OAuth2AuthorizationException authorization = first(chain, OAuth2AuthorizationException.class);
        if (authorization != null && isAuthorizationRejection(authorization.getError())) {
            return UpstreamFailureKind.AUTHENTICATION;
        }
        return UpstreamFailureKind.OTHER;
    }

    /**
     * @param error the OAuth 2.0 error of an {@link OAuth2AuthorizationException}; may be {@code null}
     * @return {@code true} when its code reports that Entra rejected the client or grant, {@code false} for a code
     *         of {@link #TOKEN_EXCHANGE_FAILURE_CODES} or a missing code
     */
    private static boolean isAuthorizationRejection(OAuth2Error error) {
        String errorCode = error == null ? null : error.getErrorCode();
        return errorCode != null && !TOKEN_EXCHANGE_FAILURE_CODES.contains(errorCode);
    }

    /**
     * Returns Dynamics' own text for a failure, the first available of:
     * <ol>
     *   <li>from the JSON body of the first {@link WebClientResponseException} in the cause chain: the non-blank
     *       {@code error.message} of a Dataverse OData error, or, when {@code error} is a string (an Entra OAuth 2.0
     *       error kept with its token-response status), the non-blank {@code error_description}, else the non-blank
     *       {@code error} code (a blank, non-JSON or differently shaped body yields nothing);</li>
     *   <li>for the first {@link OAuth2AuthorizationException} in the chain, its error's non-blank description, else
     *       its non-blank error code;</li>
     *   <li>the inherited lookup: the first non-blank message of the chain, else the failure's class name.</li>
     * </ol>
     *
     * @param failure the classified throwable; never {@code null}
     * @return the upstream's error text; never {@code null} or blank
     * @throws NullPointerException if {@code failure} is {@code null}
     */
    @Override
    public String upstreamMessage(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        List<Throwable> chain = causeChain(failure);
        WebClientResponseException response = first(chain, WebClientResponseException.class);
        if (response != null) {
            String message = errorBodyMessage(response);
            if (message != null) {
                return message;
            }
        }
        OAuth2AuthorizationException authorization = first(chain, OAuth2AuthorizationException.class);
        if (authorization != null) {
            String message = oauth2ErrorMessage(authorization.getError());
            if (message != null) {
                return message;
            }
        }
        return UpstreamFailureClassifier.super.upstreamMessage(failure);
    }

    /**
     * Reads the message of an error body: a Dataverse OData error, for example
     * {@code {"error":{"code":"0x80040217","message":"contact With Id = ... Does Not Exist"}}}, or an Entra OAuth 2.0
     * error of a token response, for example
     * {@code {"error":"temporarily_unavailable","error_description":"AADSTS..."}}.
     *
     * @param response the response exception; never {@code null}
     * @return the non-blank {@code error.message} text of a Dataverse body; for an Entra body the non-blank
     *         {@code error_description}, else the non-blank {@code error} code; {@code null} when the body yields none
     */
    private static String errorBodyMessage(WebClientResponseException response) {
        JsonNode root;
        try {
            String body = response.getResponseBodyAsString();
            if (body == null || body.isBlank()) {
                return null;
            }
            root = JSON.readTree(body);
        } catch (JsonProcessingException | RuntimeException e) {
            // The executor asks for the message while reporting the vendor failure; a parser fault must not
            // replace that failure, so an unreadable body falls through to the next message source.
            return null;
        }
        if (root == null) {
            return null;
        }
        JsonNode error = root.path(ERROR);
        if (error.isTextual()) {
            String description = nonBlankText(root.path(ERROR_DESCRIPTION));
            return description != null ? description : nonBlankText(error);
        }
        return nonBlankText(error.path(MESSAGE));
    }

    /**
     * @param node a JSON node, possibly missing
     * @return the node's text when it is a non-blank string, otherwise {@code null}
     */
    private static String nonBlankText(JsonNode node) {
        return node.isTextual() && !node.textValue().isBlank() ? node.textValue() : null;
    }

    /**
     * @param error the OAuth 2.0 error Entra returned, as Spring Security parsed it; may be {@code null}
     * @return the non-blank description, else the non-blank error code, otherwise {@code null}
     */
    private static String oauth2ErrorMessage(OAuth2Error error) {
        if (error == null) {
            return null;
        }
        String description = error.getDescription();
        if (description != null && !description.isBlank()) {
            return description;
        }
        String errorCode = error.getErrorCode();
        return errorCode != null && !errorCode.isBlank() ? errorCode : null;
    }

    /**
     * @param response a 429 response exception
     * @return Dataverse's {@code Retry-After} header value exactly as sent, or {@code null} when absent
     */
    private static String retryAfter(WebClientResponseException response) {
        HttpHeaders headers = response.getHeaders();
        return headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
    }

    /**
     * @param chain  a cause chain as {@link #causeChain} lists it
     * @param status the HTTP status sought
     * @return the first response exception of the chain carrying {@code status}, or {@code null}
     */
    private static WebClientResponseException firstResponse(List<Throwable> chain, HttpStatus status) {
        for (Throwable current : chain) {
            if (current instanceof WebClientResponseException response
                    && response.getStatusCode().value() == status.value()) {
                return response;
            }
        }
        return null;
    }

    /**
     * @param chain a cause chain as {@link #causeChain} lists it
     * @param type  the throwable type sought, subclasses included
     * @param <T>   the type sought
     * @return the first throwable of the chain that is a {@code type}, or {@code null}
     */
    private static <T extends Throwable> T first(List<Throwable> chain, Class<T> type) {
        for (Throwable current : chain) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
        }
        return null;
    }

    /**
     * Lists a throwable and its causes in order, each once: identity decides whether a throwable was seen, so a
     * cause chain that loops back on itself ends.
     *
     * @param failure the first throwable; never {@code null}
     * @return the chain from {@code failure} to its root cause
     */
    private static List<Throwable> causeChain(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && visited.add(current); current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }
}
