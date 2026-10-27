/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.authenticating_salesforce_using_oauth2.service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.authenticating_salesforce_using_oauth2.client.SalesforcePartnerClient;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.client.SalesforcePartnerClient.Session;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.client.SalesforceTokenClient;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.client.SalesforceTokenClient.TokenEndpointException;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.client.SalesforceTokenClient.TokenResponse;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.client.UpstreamSystem;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.config.SalesforceOAuthProperties;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.exception.UpstreamRateLimitException;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.exception.UpstreamUnavailableException;

/**
 * The Salesforce OAuth 2.0 authorization-code flow of {@code salesforce-oauth.xml}, which the Mule DevKit connector
 * performed internally:
 * <ul>
 *   <li>{@code <sfdc:config-with-oauth consumerKey="${sfdc.key}" ...>} (line 3) and
 *       {@code <sfdc:oauth-callback-config domain="localhost" localPort="8081" path="oauth2callback"/>} (line 4):
 *       the {@code client_id} and {@code redirect_uri} of {@link #authorizationUrl()};</li>
 *   <li>{@code <sfdc:authorize ... display="PAGE"/>} (line 12): {@link #authorize()} sends a browser without a token to
 *       Salesforce, and {@link #completeAuthorization(String, String, String)} exchanges the code Salesforce returns to
 *       {@code /oauth2callback}, stores the token and resumes the flow with the contact query of
 *       {@link ContactQueryService}, so the callback answers with {@code Salesforce query returned <n> contacts.} as
 *       the original IT expects;</li>
 *   <li>the session the connector then used for {@code sfdc:query}: this class is the
 *       {@link SalesforcePartnerClient.SessionSource} that switches the Partner API client to session mode, and
 *       {@link #refresh()} renews an expired session with the {@code refresh_token} grant, the re-authentication step
 *       the upstream executor runs once after Salesforce rejects the session. The client reads {@link #current()} on
 *       every Partner API call, so a newly completed grant takes effect on the next call, the callback's own query
 *       included, without a refresh.</li>
 * </ul>
 *
 * <p>The HTTP token requests live in {@link SalesforceTokenClient}; the redirect and the 200 answer are written by the
 * controller from an {@link AuthorizeOutcome}. Neither tokens, the authorization code nor the consumer key ever reach a
 * log line.
 *
 * <p>Thread-safe: the token slot is an {@link AtomicReference}, and {@link #refresh()} is serialized so that concurrent
 * session rejections spend the single-use refresh exchange once at a time.
 */
@Service
public class SalesforceOAuthService implements SalesforcePartnerClient.SessionSource {

    private static final Logger LOG = LoggerFactory.getLogger(SalesforceOAuthService.class);

    /**
     * Path of the SOAP Partner API endpoint on the user's instance, at the API version of {@code force-partner-api}
     * 65.0.0. Appended to the token response's {@code instance_url} to form the session's service endpoint.
     */
    public static final String SOAP_ENDPOINT_PATH = "/services/Soap/u/65.0";

    private static final String NOT_AUTHORIZED = "Salesforce is not authorized";

    private static final String NO_REFRESH_TOKEN = "no refresh token";

    private final SalesforceOAuthProperties properties;

    private final SalesforceTokenClient tokenClient;

    private final ContactQueryService contactQueryService;

    /**
     * The one token of the application, shared by every browser and caller: the DevKit connector kept a single
     * default access-token id, so whoever authorizes first authorizes the application. Held in memory only and lost on
     * restart, as in the original; no per-browser session, cookie or persistence is added.
     */
    private final AtomicReference<StoredToken> token = new AtomicReference<>();

    /**
     * Creates the service. There is no construction cycle with {@link SalesforcePartnerClient}: the client receives
     * this {@link SalesforcePartnerClient.SessionSource} through an {@code ObjectProvider}, resolved at each of its
     * calls rather than at construction.
     *
     * @param properties          the consumer key, the authorization URL and the callback settings
     * @param tokenClient         the client of the Salesforce token endpoint
     * @param contactQueryService the part of the flow that runs once the user has authorized
     * @throws NullPointerException if any argument is {@code null}
     */
    public SalesforceOAuthService(SalesforceOAuthProperties properties, SalesforceTokenClient tokenClient,
            ContactQueryService contactQueryService) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.tokenClient = Objects.requireNonNull(tokenClient, "tokenClient");
        this.contactQueryService = Objects.requireNonNull(contactQueryService, "contactQueryService");
    }

    /**
     * What the {@code /} listener answers: a redirect to Salesforce while no token is stored, otherwise the result of
     * the flow. The controller turns a redirect into 302 with {@code Location}, and a completed outcome into 200 with
     * the body.
     *
     * @param redirectLocation where to send the browser, or {@code null} when the flow completed
     * @param body             the flow's response text, or {@code null} for a redirect
     */
    public record AuthorizeOutcome(URI redirectLocation, String body) {

        /**
         * @param location the Salesforce authorization URL
         * @return an outcome redirecting the browser to {@code location}
         * @throws NullPointerException if {@code location} is {@code null}
         */
        public static AuthorizeOutcome redirect(URI location) {
            return new AuthorizeOutcome(Objects.requireNonNull(location, "location"), null);
        }

        /**
         * @param body the flow's response text
         * @return an outcome answering with {@code body}
         */
        public static AuthorizeOutcome completed(String body) {
            return new AuthorizeOutcome(null, body);
        }

        /**
         * @return {@code true} when the browser must be redirected to {@link #redirectLocation()}
         */
        public boolean isRedirect() {
            return redirectLocation != null;
        }
    }

    /**
     * The token of an authorization-code grant, as renewed by later refresh grants.
     */
    private record StoredToken(String accessToken, String refreshToken, String instanceUrl) {

        /** Masks both tokens, which are bearer credentials, so a logged or debugged slot never reveals them. */
        @Override
        public String toString() {
            return "StoredToken[accessToken=****, refreshToken=" + (refreshToken == null ? null : "****")
                    + ", instanceUrl=" + instanceUrl + "]";
        }
    }

    /**
     * @return the {@code redirect_uri} of the callback, {@code http://<domain>:<localPort>/<path>}
     *         ({@code http://localhost:8081/oauth2callback} with the original settings)
     */
    public String redirectUri() {
        return properties.getOauth().getCallback().redirectUri();
    }

    /**
     * Builds the Salesforce authorization URL the browser is sent to: the configured {@code sfdc.oauth.authorization-url}
     * followed by {@code response_type=code}, {@code client_id}, {@code redirect_uri} and {@code display=page}, in that
     * order and each value form-encoded in UTF-8. {@code display=page} is the {@code display="PAGE"} of the original
     * {@code sfdc:authorize}; an unconfigured consumer key is sent empty, so Salesforce answers with its own error page.
     *
     * @return the authorization URL
     * @throws IllegalStateException if {@code sfdc.oauth.authorization-url} is not configured
     */
    public URI authorizationUrl() {
        String base = properties.getOauth().getAuthorizationUrl();
        if (isBlank(base)) {
            throw new IllegalStateException("sfdc.oauth.authorization-url is not configured");
        }
        base = base.trim();
        StringBuilder url = new StringBuilder(base).append(base.indexOf('?') < 0 ? '?' : '&');
        url.append("response_type=").append(encode("code"));
        url.append("&client_id=").append(encode(Objects.toString(properties.getKey(), "")));
        url.append("&redirect_uri=").append(encode(redirectUri()));
        url.append("&display=").append(encode("page"));
        return URI.create(url.toString());
    }

    /**
     * Runs the {@code /} flow: without a stored token the browser is redirected to Salesforce, as
     * {@code sfdc:authorize} did; with one the flow continues with the contact query.
     *
     * @return a redirect to {@link #authorizationUrl()}, or the completed query text
     * @throws Exception every failure of the query, unchanged
     */
    public AuthorizeOutcome authorize() throws Exception {
        if (token.get() == null) {
            LOG.info("No Salesforce token stored; redirecting the browser to Salesforce authorization");
            return AuthorizeOutcome.redirect(authorizationUrl());
        }
        return AuthorizeOutcome.completed(contactQueryService.queryContacts());
    }

    /**
     * Handles the {@code /oauth2callback} request Salesforce redirects the browser to: exchanges the code for tokens,
     * stores them and resumes the flow after {@code sfdc:authorize} with the contact query.
     *
     * @param code             the {@code code} query parameter
     * @param error            the {@code error} query parameter Salesforce sends when authorization failed, or
     *                         {@code null}
     * @param errorDescription the {@code error_description} query parameter, or {@code null}
     * @return the flow's response text, {@code Salesforce query returned <n> contacts.}
     * @throws IllegalStateException if no code was passed ({@code Salesforce authorization failed: <error>[: <error
     *                               description>]}, or {@code ...: no authorization code} without an error), or the
     *                               token response lacks {@code access_token} or {@code instance_url}; nothing is
     *                               stored
     * @throws Exception             a failed exchange ({@code TokenEndpointException}, the SC-9 upstream exceptions)
     *                               or query, unchanged; a failed exchange stores nothing
     */
    public String completeAuthorization(String code, String error, String errorDescription) throws Exception {
        if (isBlank(code)) {
            String reason = isBlank(error) ? "no authorization code"
                    : isBlank(errorDescription) ? error : error + ": " + errorDescription;
            throw new IllegalStateException("Salesforce authorization failed: " + reason);
        }
        TokenResponse response = tokenClient.exchangeCode(code, redirectUri());
        String accessToken = requireField(response == null ? null : response.access_token(), "access_token");
        String instanceUrl = requireField(response.instance_url(), "instance_url");
        token.set(new StoredToken(accessToken, response.refresh_token(), instanceUrl));
        LOG.info("Salesforce authorization completed; token stored");
        return contactQueryService.queryContacts();
    }

    /**
     * @return {@code true} once a token is stored, until a refused refresh clears it
     */
    public boolean isAuthorized() {
        return token.get() != null;
    }

    /**
     * Supplies the stored session to the Partner API client.
     *
     * @return the access token as session ID and the SOAP Partner endpoint on the user's instance
     * @throws UpstreamAuthenticationException if no user has authorized yet
     */
    @Override
    public Session current() {
        StoredToken stored = token.get();
        if (stored == null) {
            throw new UpstreamAuthenticationException(UpstreamSystem.SALESFORCE, NOT_AUTHORIZED, null);
        }
        return toSession(stored);
    }

    /**
     * Renews the session with the {@code refresh_token} grant after Salesforce rejected it. The new access token is
     * stored; the refresh token and instance URL are kept unless the response supplies new ones (Salesforce normally
     * returns no refresh token on this grant). A grant that {@link #completeAuthorization(String, String, String)}
     * stored while the exchange was in flight is never overwritten: the renewed token is discarded and the session of
     * that newer grant is returned, so the retry runs under it.
     *
     * <p>Failures:
     * <ul>
     *   <li>no stored token or no refresh token, or Salesforce refuses the grant (for example {@code invalid_grant}):
     *       the slot is cleared, so the next {@code /} request redirects to Salesforce again, and an
     *       {@link UpstreamAuthenticationException} is raised (with the refused grant as its cause);</li>
     *   <li>{@link UpstreamUnavailableException}, {@link UpstreamRateLimitException} or any other unchecked failure:
     *       rethrown unchanged and the token kept; a timeout is never retried here;</li>
     *   <li>a checked failure: wrapped in an {@link IllegalStateException}, the token kept.</li>
     * </ul>
     *
     * @return the renewed session, or the session of a grant completed during the exchange
     * @throws UpstreamAuthenticationException if the session cannot be renewed by refresh
     */
    @Override
    public synchronized Session refresh() {
        StoredToken stored = token.get();
        if (stored == null || isBlank(stored.refreshToken())) {
            token.compareAndSet(stored, null);
            throw new UpstreamAuthenticationException(UpstreamSystem.SALESFORCE, NO_REFRESH_TOKEN, null);
        }
        TokenResponse response;
        try {
            response = tokenClient.refresh(stored.refreshToken());
        } catch (TokenEndpointException refused) {
            token.compareAndSet(stored, null);
            LOG.warn("Salesforce refused the token refresh (HTTP {}); authorization is required again",
                    refused.getStatus());
            String message = isBlank(refused.getErrorDescription()) ? refused.getMessage()
                    : refused.getErrorDescription();
            throw new UpstreamAuthenticationException(UpstreamSystem.SALESFORCE, message, refused);
        } catch (RuntimeException unchecked) {
            // UpstreamUnavailableException and UpstreamRateLimitException included: the SC-9 handler answers them,
            // and the token stays usable once Salesforce is reachable again.
            throw unchecked;
        } catch (Exception checked) {
            throw new IllegalStateException("Salesforce token refresh failed: "
                    + Objects.toString(checked.getMessage(), checked.getClass().getName()), checked);
        }
        String accessToken = requireField(response == null ? null : response.access_token(), "access_token");
        StoredToken renewed = new StoredToken(accessToken,
                isBlank(response.refresh_token()) ? stored.refreshToken() : response.refresh_token(),
                isBlank(response.instance_url()) ? stored.instanceUrl() : response.instance_url());
        if (!token.compareAndSet(stored, renewed)) {
            LOG.info("Salesforce authorization completed during the token refresh; the newer token is kept");
            return current();
        }
        LOG.info("Salesforce access token refreshed");
        return toSession(renewed);
    }

    private static Session toSession(StoredToken stored) {
        return new Session(stored.accessToken(), endpoint(stored.instanceUrl()));
    }

    /**
     * The SOAP Partner endpoint on an instance: one trailing {@code /} of the instance URL is dropped, then
     * {@link #SOAP_ENDPOINT_PATH} appended ({@code https://na1.salesforce.com} &rarr;
     * {@code https://na1.salesforce.com/services/Soap/u/65.0}).
     */
    private static String endpoint(String instanceUrl) {
        String base = instanceUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + SOAP_ENDPOINT_PATH;
    }

    private static String requireField(String value, String field) {
        if (isBlank(value)) {
            throw new IllegalStateException("Salesforce token response is missing " + field);
        }
        return value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
