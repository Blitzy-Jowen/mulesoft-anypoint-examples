/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.authenticating_salesforce_using_oauth2.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serial;
import java.net.URI;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.mulesoft.examples.authenticating_salesforce_using_oauth2.config.SalesforceOAuthProperties;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.config.SalesforceProperties;

/**
 * Calls the Salesforce OAuth 2.0 token endpoint: the token half of
 * {@code <sfdc:authorize accessTokenUrl="https://login.salesforce.com/services/oauth2/token" .../>} together with the
 * {@code consumerKey}/{@code consumerSecret} of {@code <sfdc:config-with-oauth>}, which the Mule connector performed
 * internally.
 *
 * <ul>
 *   <li>{@link #exchangeCode(String, String)} posts the {@code authorization_code} grant for the code Salesforce
 *       returned to {@code /oauth2callback};</li>
 *   <li>{@link #refresh(String)} posts the {@code refresh_token} grant that renews an expired session.</li>
 * </ul>
 * The authorize redirect, token storage, the {@code /oauth2callback} handling and the derivation of the Partner API
 * endpoint from {@link TokenResponse#instance_url()} belong to {@code service/SalesforceOAuthService}.
 *
 * <p>Both grants are posted as {@code application/x-www-form-urlencoded} to {@code sfdc.oauth.access-token-url},
 * read on every call, with {@code client_id} = {@code sfdc.key} and {@code client_secret} = {@code sfdc.secret}.
 * Every request runs through {@link UpstreamCallExecutor} for {@link UpstreamSystem#SALESFORCE}, so a timeout or
 * connectivity failure becomes {@code UpstreamUnavailableException} (503) and is never retried, while a rejected
 * grant ({@link TokenEndpointException}) is rethrown unchanged. The connect and read timeouts are
 * {@code sfdc.connect-timeout} and {@code sfdc.read-timeout} (10 s, the Mule default response timeout).
 *
 * <p>Nothing about a request is logged except its grant type: the consumer secret, the authorization code and the
 * tokens never reach a log line.
 *
 * <p>The class holds no mutable state; one instance serves concurrent requests.
 */
@Component
public class SalesforceTokenClient {

    private static final Logger LOG = LoggerFactory.getLogger(SalesforceTokenClient.class);

    /** Reads token-endpoint error bodies only; independent of the converters the RestClient was built with. */
    private static final ObjectMapper ERROR_READER = new ObjectMapper();

    /** Most bytes of an error body that are read; a Salesforce OAuth error object is far smaller. */
    private static final int ERROR_BODY_LIMIT = 64 * 1024;

    private static final String GRANT_AUTHORIZATION_CODE = "authorization_code";

    private static final String GRANT_REFRESH_TOKEN = "refresh_token";

    private final RestClient restClient;

    private final SalesforceOAuthProperties oauthProperties;

    private final UpstreamCallExecutor executor;

    /**
     * Builds the HTTP client once, over {@link SimpleClientHttpRequestFactory}, so that a read timeout surfaces as a
     * {@code java.net.SocketTimeoutException} under Spring's {@code ResourceAccessException}, which the Salesforce
     * classifier reads as unavailable.
     *
     * @param restClientBuilder    the auto-configured prototype builder, carrying Boot's message converters
     * @param oauthProperties      the consumer key and secret and the token endpoint URL
     * @param salesforceProperties the connect and read timeouts
     * @param executor             the SC-9 executor every token request runs through
     */
    public SalesforceTokenClient(RestClient.Builder restClientBuilder, SalesforceOAuthProperties oauthProperties,
            SalesforceProperties salesforceProperties, UpstreamCallExecutor executor) {
        Objects.requireNonNull(restClientBuilder, "restClientBuilder");
        Objects.requireNonNull(salesforceProperties, "salesforceProperties");
        this.oauthProperties = Objects.requireNonNull(oauthProperties, "oauthProperties");
        this.executor = Objects.requireNonNull(executor, "executor");
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(salesforceProperties.getConnectTimeout());
        requestFactory.setReadTimeout(salesforceProperties.getReadTimeout());
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
    }

    /**
     * Exchanges an authorization code for tokens: posts {@code grant_type=authorization_code}, {@code code},
     * {@code client_id}, {@code client_secret} and {@code redirect_uri}, in that order.
     *
     * @param code        the code Salesforce passed to the callback
     * @param redirectUri the redirect URI sent to {@code /authorize}, which Salesforce requires again here
     *                    ({@code http://localhost:8081/oauth2callback} with the original callback settings)
     * @return the issued tokens; {@link TokenResponse#access_token()} is never blank
     * @throws TokenEndpointException       Salesforce rejected the grant, or answered without an access token
     * @throws com.mulesoft.examples.authenticating_salesforce_using_oauth2.exception.UpstreamUnavailableException
     *                                      the token endpoint timed out or could not be reached
     * @throws NullPointerException         if {@code code} or {@code redirectUri} is {@code null}
     * @throws IllegalStateException        if {@code sfdc.oauth.access-token-url} is not configured
     */
    public TokenResponse exchangeCode(String code, String redirectUri) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(redirectUri, "redirectUri");
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", GRANT_AUTHORIZATION_CODE);
        form.add("code", code);
        addClientCredentials(form);
        form.add("redirect_uri", redirectUri);
        return post(form, GRANT_AUTHORIZATION_CODE);
    }

    /**
     * Renews the access token: posts {@code grant_type=refresh_token}, {@code refresh_token}, {@code client_id} and
     * {@code client_secret}, in that order.
     *
     * @param refreshToken the refresh token of an earlier authorization-code grant
     * @return the renewed tokens; Salesforce returns no {@code refresh_token} on this grant, so that component is
     *         {@code null}
     * @throws TokenEndpointException       Salesforce rejected the grant, or answered without an access token
     * @throws com.mulesoft.examples.authenticating_salesforce_using_oauth2.exception.UpstreamUnavailableException
     *                                      the token endpoint timed out or could not be reached
     * @throws NullPointerException         if {@code refreshToken} is {@code null}
     * @throws IllegalStateException        if {@code sfdc.oauth.access-token-url} is not configured
     */
    public TokenResponse refresh(String refreshToken) {
        Objects.requireNonNull(refreshToken, "refreshToken");
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", GRANT_REFRESH_TOKEN);
        form.add("refresh_token", refreshToken);
        addClientCredentials(form);
        return post(form, GRANT_REFRESH_TOKEN);
    }

    /**
     * Adds the connected app's credentials. An unconfigured key or secret is sent empty, so the request stays a
     * well-formed form and Salesforce answers with its own {@code invalid_client_id} or {@code invalid_client}
     * error.
     */
    private void addClientCredentials(MultiValueMap<String, String> form) {
        form.add("client_id", Objects.toString(oauthProperties.getKey(), ""));
        form.add("client_secret", Objects.toString(oauthProperties.getSecret(), ""));
    }

    private TokenResponse post(MultiValueMap<String, String> form, String grantType) {
        URI tokenUri = tokenUri();
        LOG.debug("Requesting Salesforce access token (grant_type={})", grantType);
        try {
            // No re-authentication step (null rather than a no-op): a rejected grant is not an expired session,
            // and a single-use code or refused refresh token cannot succeed on a retry, so the executor treats an
            // authentication failure as final.
            return executor.call(UpstreamSystem.SALESFORCE, () -> requestToken(tokenUri, form), null);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * Performs one token request. Runs inside the executor, so both the {@link TokenEndpointException} and any
     * transport {@code ResourceAccessException} are raised within the executed call.
     */
    private TokenResponse requestToken(URI tokenUri, MultiValueMap<String, String> form) {
        ResponseEntity<TokenResponse> response = restClient.post()
                .uri(tokenUri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(form)
                .retrieve()
                // Every non-2xx answer is a grant failure, a 3xx included: redirects of a POST are not followed.
                .onStatus(status -> !status.is2xxSuccessful(), (request, errorResponse) -> {
                    throw tokenError(errorResponse.getStatusCode().value(), readBody(errorResponse));
                })
                .toEntity(TokenResponse.class);
        TokenResponse token = response.getBody();
        if (token == null || token.access_token() == null || token.access_token().isBlank()) {
            throw new TokenEndpointException(response.getStatusCode().value(), null, null);
        }
        return token;
    }

    private URI tokenUri() {
        SalesforceOAuthProperties.Oauth oauth = oauthProperties.getOauth();
        String url = oauth == null ? null : oauth.getAccessTokenUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("sfdc.oauth.access-token-url is not configured");
        }
        // A URI, not a template string, so nothing in the configured URL is expanded.
        return URI.create(url.trim());
    }

    /**
     * Reads an error body. {@code HttpURLConnection} may expose no error stream, in which case reading fails; that
     * and any other read failure yield an empty body, which leaves the status-only error.
     */
    private static byte[] readBody(ClientHttpResponse response) {
        try (InputStream body = response.getBody()) {
            return body == null ? new byte[0] : body.readNBytes(ERROR_BODY_LIMIT);
        } catch (IOException unreadable) {
            return new byte[0];
        }
    }

    /**
     * Builds the exception for a rejected grant from Salesforce's error object
     * ({@code {"error":"invalid_grant","error_description":"expired authorization code"}}). A body that is not a JSON
     * object with a textual {@code error} yields an exception carrying the status alone.
     */
    private static TokenEndpointException tokenError(int status, byte[] body) {
        JsonNode root;
        try {
            root = ERROR_READER.readTree(body);
        } catch (IOException notJson) {
            return new TokenEndpointException(status, null, null);
        }
        if (root == null || !root.isObject()) {
            return new TokenEndpointException(status, null, null);
        }
        JsonNode error = root.get("error");
        if (error == null || !error.isTextual()) {
            return new TokenEndpointException(status, null, null);
        }
        JsonNode description = root.get("error_description");
        return new TokenEndpointException(status, error.textValue(),
                description != null && description.isTextual() ? description.textValue() : null);
    }

    /**
     * The token endpoint's JSON answer. Component names equal Salesforce's keys, so Jackson binds them without a
     * naming strategy; keys not listed, such as {@code signature}, are ignored.
     *
     * @param access_token  the session ID the Partner API is called with
     * @param refresh_token the refresh token; {@code null} on refresh-grant responses and when the connected app
     *                      lacks the {@code refresh_token} scope
     * @param instance_url  the org's base URL, from which {@code service/SalesforceOAuthService} derives the SC-15
     *                      Partner API service endpoint
     * @param id            the identity URL of the authorized user
     * @param issued_at     the issue time, in milliseconds since the epoch, as Salesforce's string
     * @param token_type    the token type, {@code Bearer}
     * @param scope         the granted scopes, space-separated
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TokenResponse(String access_token, String refresh_token, String instance_url, String id,
            String issued_at, String token_type, String scope) {

        /**
         * Describes the response with both tokens masked, so logging the record never writes a token.
         */
        @Override
        public String toString() {
            return "TokenResponse{access_token=" + mask(access_token)
                    + ", refresh_token=" + mask(refresh_token)
                    + ", instance_url=" + instance_url
                    + ", id=" + id
                    + ", issued_at=" + issued_at
                    + ", token_type=" + token_type
                    + ", scope=" + scope
                    + "}";
        }

        private static String mask(String token) {
            return token == null ? null : "****";
        }
    }

    /**
     * Salesforce refused a grant, or answered it without an access token. The executor classifies it as
     * {@code OTHER} and rethrows it unchanged, so the generic handler answers 500 with its message.
     */
    public static class TokenEndpointException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final int status;

        private final String error;

        private final String errorDescription;

        /**
         * @param status           the HTTP status of the token endpoint's answer
         * @param error            Salesforce's {@code error} code, or {@code null} when the answer carried none
         * @param errorDescription Salesforce's {@code error_description}, or {@code null}
         */
        public TokenEndpointException(int status, String error, String errorDescription) {
            super(message(status, error, errorDescription));
            this.status = status;
            this.error = error;
            this.errorDescription = errorDescription;
        }

        /**
         * @return the HTTP status of the token endpoint's answer
         */
        public int getStatus() {
            return status;
        }

        /**
         * @return Salesforce's {@code error} code, such as {@code invalid_grant}, or {@code null}
         */
        public String getError() {
            return error;
        }

        /**
         * @return Salesforce's {@code error_description}, or {@code null}
         */
        public String getErrorDescription() {
            return errorDescription;
        }

        /**
         * {@code <error>: <error_description>}; {@code <error>} alone when the description is blank;
         * {@code HTTP <status>} when the error code is blank.
         */
        private static String message(int status, String error, String errorDescription) {
            if (error == null || error.isBlank()) {
                return "HTTP " + status;
            }
            if (errorDescription == null || errorDescription.isBlank()) {
                return error;
            }
            return error + ": " + errorDescription;
        }
    }
}
