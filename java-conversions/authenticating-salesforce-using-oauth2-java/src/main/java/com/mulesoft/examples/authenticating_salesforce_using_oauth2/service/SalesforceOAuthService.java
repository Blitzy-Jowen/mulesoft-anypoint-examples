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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseCookie;
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
 *       the {@code client_id} and {@code redirect_uri} of {@link #authorizationUrl(String)};</li>
 *   <li>{@code <sfdc:authorize ... display="PAGE"/>} (line 12): {@link #authorize()} sends a browser without a token to
 *       Salesforce, and {@link #completeAuthorization(String, String, String, String, String)} exchanges the code
 *       Salesforce returns to {@code /oauth2callback}, stores the token and resumes the flow with the contact query of
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
 * <p>A callback completes only an authorization this application started in the same browser, a hardening for hostile
 * input the conversion adds; the original's DevKit callback handling is not established either way. Each redirect
 * carries a fresh, unpredictable {@code state}, which Salesforce returns to the callback, and sets the same value in
 * the {@link #STATE_COOKIE} cookie of the browser. The callback exchanges its code only when the {@code state} it
 * carries equals that cookie and names a pending authorization that has not expired, and it consumes that
 * authorization first, so each {@code state} completes at most once. Any other callback with a code fails before a
 * token request is made, so an unsolicited code never replaces the application's grant. A legitimate login is
 * answered exactly as before.
 *
 * <p>The HTTP token requests live in {@link SalesforceTokenClient}; the redirect, its cookie and the 200 answer are
 * written by the controller from an {@link AuthorizeOutcome}. Neither tokens, the authorization code, the
 * {@code state} nor the consumer key ever reach a log line.
 *
 * <p>Thread-safe: the token slot is an {@link AtomicReference}, the pending authorizations are guarded by their own
 * lock, and {@link #refresh()} is serialized so that concurrent session rejections spend the single-use refresh
 * exchange once at a time.
 */
@Service
public class SalesforceOAuthService implements SalesforcePartnerClient.SessionSource {

    private static final Logger LOG = LoggerFactory.getLogger(SalesforceOAuthService.class);

    /**
     * Path of the SOAP Partner API endpoint on the user's instance, at the API version of {@code force-partner-api}
     * 65.0.0. Appended to the token response's {@code instance_url} to form the session's service endpoint.
     */
    public static final String SOAP_ENDPOINT_PATH = "/services/Soap/u/65.0";

    /**
     * Name of the cookie that binds an authorization started at {@code /} to the browser that started it: the
     * redirect sets it to the authorization's {@code state}, and the callback must present it beside that same
     * {@code state}.
     */
    public static final String STATE_COOKIE = "sfdc_oauth_state";

    /**
     * The most authorizations pending at once. Every anonymous {@code /} request without a stored token starts one,
     * so once this many are pending the oldest is forgotten, and its callback is refused.
     */
    static final int MAX_PENDING_AUTHORIZATIONS = 1000;

    /**
     * The longest {@code sfdc.oauth.state-ttl} accepted: 400 days, the longest cookie lifetime browsers honour
     * (RFC 6265bis, the {@code Max-Age} and {@code Expires} limits), which also keeps every expiry instant and the
     * cookie's {@code Expires} date representable.
     */
    static final Duration MAX_STATE_TTL = Duration.ofDays(400);

    /** Random bytes per {@code state}: 256 bits, sent as 43 base64url characters without padding. */
    private static final int STATE_BYTES = 32;

    private static final String NOT_AUTHORIZED = "Salesforce is not authorized";

    private static final String NO_REFRESH_TOKEN = "no refresh token";

    private static final String AUTHORIZATION_FAILED = "Salesforce authorization failed: ";

    private static final String NO_STATE = "no state parameter";

    private static final String NO_STATE_COOKIE = "no state cookie";

    private static final String STATE_MISMATCH = "state does not match this browser's authorization request";

    private static final String UNKNOWN_STATE = "unknown or already used state";

    private static final String EXPIRED_STATE = "authorization request expired";

    private final SalesforceOAuthProperties properties;

    private final SalesforceTokenClient tokenClient;

    private final ContactQueryService contactQueryService;

    private final Clock clock;

    private final SecureRandom random = new SecureRandom();

    /**
     * The one token of the application, shared by every browser and caller: the DevKit connector kept a single
     * default access-token id, so whoever authorizes first authorizes the application. Held in memory only and lost on
     * restart, as in the original. The grant stays shared and no per-browser session or persistence is added; only an
     * authorization still pending is bound to the browser that started it, by the short-lived {@link #STATE_COOKIE}.
     */
    private final AtomicReference<StoredToken> token = new AtomicReference<>();

    /**
     * The authorizations started at {@code /} whose callback has not arrived: each {@code state} with the instant it
     * expires, in the order they were issued (a {@link LinkedHashMap}). Guarded by itself, held in memory only, and
     * bounded: expired entries are dropped each time a state is issued, and at most
     * {@link #MAX_PENDING_AUTHORIZATIONS} are kept, the oldest forgotten first.
     */
    private final Map<String, Instant> pendingStates = new LinkedHashMap<>();

    /**
     * Creates the service on the system clock. There is no construction cycle with {@link SalesforcePartnerClient}:
     * the client receives this {@link SalesforcePartnerClient.SessionSource} through an {@code ObjectProvider},
     * resolved at each of its calls rather than at construction.
     *
     * @param properties          the consumer key, the authorization URL, the callback settings and the state lifetime
     * @param tokenClient         the client of the Salesforce token endpoint
     * @param contactQueryService the part of the flow that runs once the user has authorized
     * @throws NullPointerException if any argument is {@code null}
     */
    @Autowired
    public SalesforceOAuthService(SalesforceOAuthProperties properties, SalesforceTokenClient tokenClient,
            ContactQueryService contactQueryService) {
        this(properties, tokenClient, contactQueryService, Clock.systemUTC());
    }

    /**
     * Creates the service on {@code clock}, which times each pending authorization, so that a test can let one
     * expire without waiting.
     *
     * @param properties          the consumer key, the authorization URL, the callback settings and the state lifetime
     * @param tokenClient         the client of the Salesforce token endpoint
     * @param contactQueryService the part of the flow that runs once the user has authorized
     * @param clock               the clock a pending authorization's issue and expiry are read from
     * @throws NullPointerException if any argument is {@code null}
     */
    SalesforceOAuthService(SalesforceOAuthProperties properties, SalesforceTokenClient tokenClient,
            ContactQueryService contactQueryService, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.tokenClient = Objects.requireNonNull(tokenClient, "tokenClient");
        this.contactQueryService = Objects.requireNonNull(contactQueryService, "contactQueryService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * What the {@code /} listener answers: a redirect to Salesforce while no token is stored, otherwise the result of
     * the flow. The controller turns a redirect into 302 with {@code Location} and the {@code Set-Cookie} of
     * {@code stateCookie}, and a completed outcome into 200 with the body and no cookie.
     *
     * @param redirectLocation where to send the browser, or {@code null} when the flow completed
     * @param stateCookie      the {@link #STATE_COOKIE} that binds the redirect's {@code state} to the browser, or
     *                         {@code null} when the flow completed
     * @param body             the flow's response text, or {@code null} for a redirect
     */
    public record AuthorizeOutcome(URI redirectLocation, ResponseCookie stateCookie, String body) {

        /**
         * @param location    the Salesforce authorization URL, carrying the {@code state} of a pending authorization
         * @param stateCookie the cookie carrying the same {@code state}
         * @return an outcome redirecting the browser to {@code location} with {@code stateCookie}
         * @throws NullPointerException if an argument is {@code null}
         */
        public static AuthorizeOutcome redirect(URI location, ResponseCookie stateCookie) {
            return new AuthorizeOutcome(Objects.requireNonNull(location, "location"),
                    Objects.requireNonNull(stateCookie, "stateCookie"), null);
        }

        /**
         * @param body the flow's response text
         * @return an outcome answering with {@code body}
         */
        public static AuthorizeOutcome completed(String body) {
            return new AuthorizeOutcome(null, null, body);
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
     * followed by {@code response_type=code}, {@code client_id}, {@code redirect_uri}, {@code display=page} and
     * {@code state}, in that order and each value form-encoded in UTF-8. {@code display=page} is the
     * {@code display="PAGE"} of the original {@code sfdc:authorize}; an unconfigured consumer key is sent empty, so
     * Salesforce answers with its own error page. Salesforce returns {@code state} unchanged to the callback.
     *
     * @param state the {@code state} of the authorization, as {@link #authorize()} issues it
     * @return the authorization URL
     * @throws NullPointerException  if {@code state} is {@code null}
     * @throws IllegalStateException if {@code sfdc.oauth.authorization-url} is not configured
     */
    public URI authorizationUrl(String state) {
        Objects.requireNonNull(state, "state");
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
        url.append("&state=").append(encode(state));
        return URI.create(url.toString());
    }

    /**
     * Runs the {@code /} flow: without a stored token the browser is redirected to Salesforce, as
     * {@code sfdc:authorize} did; with one the flow continues with the contact query.
     *
     * <p>Each redirect starts a pending authorization: a fresh {@code state} of 32 {@link SecureRandom} bytes,
     * base64url-encoded without padding, sent in the {@link #authorizationUrl(String) authorization URL} and in the
     * {@link #STATE_COOKIE} cookie, and remembered until {@code sfdc.oauth.state-ttl} has passed or its callback
     * arrives. The cookie is {@code HttpOnly}, {@code SameSite=Lax} (Salesforce's redirect back to the callback is a
     * cross-site top-level navigation, on which a {@code Strict} cookie is not sent), scoped to the callback's path,
     * lives for the state lifetime rounded up to whole seconds, and is {@code Secure} only when the
     * {@code redirect_uri} is {@code https}. Nothing is remembered when the URL or the cookie cannot be built.
     *
     * @return a redirect to the authorization URL with its state cookie, or the completed query text
     * @throws IllegalStateException if {@code sfdc.oauth.authorization-url} is not configured, or
     *                               {@code sfdc.oauth.state-ttl} is not a positive duration of at most 400 days
     * @throws Exception             every failure of the query, unchanged
     */
    public AuthorizeOutcome authorize() throws Exception {
        if (token.get() == null) {
            LOG.info("No Salesforce token stored; redirecting the browser to Salesforce authorization");
            String state = newState();
            URI location = authorizationUrl(state);
            Duration ttl = stateTtl();
            ResponseCookie cookie = stateCookie(state, ttl);
            Instant now = clock.instant();
            remember(state, now.plus(ttl), now);
            return AuthorizeOutcome.redirect(location, cookie);
        }
        return AuthorizeOutcome.completed(contactQueryService.queryContacts());
    }

    /**
     * Handles the {@code /oauth2callback} request Salesforce redirects the browser to: checks that it completes an
     * authorization this browser started, then exchanges the code for tokens, stores them and resumes the flow after
     * {@code sfdc:authorize} with the contact query.
     *
     * <p>The checks run in this order, all before any token request, and only when a code is present:
     * <ol>
     *   <li>{@code state} and {@code stateCookie} are both present;</li>
     *   <li>they are equal, compared in constant time;</li>
     *   <li>{@code state} names a pending authorization, which is removed at once, so no state completes twice;</li>
     *   <li>that authorization has not expired.</li>
     * </ol>
     * A callback without a code fails as before, whatever its state; when its {@code state} and cookie match a
     * pending authorization, that authorization is discarded, so a refused login cannot be completed later.
     *
     * @param code             the {@code code} query parameter
     * @param error            the {@code error} query parameter Salesforce sends when authorization failed, or
     *                         {@code null}
     * @param errorDescription the {@code error_description} query parameter, or {@code null}
     * @param state            the {@code state} query parameter Salesforce returns, or {@code null}
     * @param stateCookie      the value of the browser's {@link #STATE_COOKIE} cookie, or {@code null}
     * @return the flow's response text, {@code Salesforce query returned <n> contacts.}
     * @throws IllegalStateException if no code was passed ({@code Salesforce authorization failed: <error>[: <error
     *                               description>]}, or {@code ...: no authorization code} without an error), a
     *                               check fails ({@code Salesforce authorization failed: no state parameter},
     *                               {@code ...: no state cookie},
     *                               {@code ...: state does not match this browser's authorization request},
     *                               {@code ...: unknown or already used state} or
     *                               {@code ...: authorization request expired}), or the token response lacks
     *                               {@code access_token} or {@code instance_url}; nothing is stored
     * @throws Exception             a failed exchange ({@code TokenEndpointException}, the SC-9 upstream exceptions)
     *                               or query, unchanged; a failed exchange stores nothing
     */
    public String completeAuthorization(String code, String error, String errorDescription, String state,
            String stateCookie) throws Exception {
        if (isBlank(code)) {
            discardPending(state, stateCookie);
            String reason = isBlank(error) ? "no authorization code"
                    : isBlank(errorDescription) ? error : error + ": " + errorDescription;
            throw new IllegalStateException(AUTHORIZATION_FAILED + reason);
        }
        String refusal = consumePending(state, stateCookie);
        if (refusal != null) {
            LOG.warn("Salesforce callback refused before any token request: {}", refusal);
            throw new IllegalStateException(AUTHORIZATION_FAILED + refusal);
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
     * returns no refresh token on this grant). A grant that
     * {@link #completeAuthorization(String, String, String, String, String)} stored while the exchange was in flight
     * is never overwritten: the renewed token is discarded and the session of that newer grant is returned, so the
     * retry runs under it.
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

    /** A fresh {@code state}: {@link #STATE_BYTES} random bytes, base64url-encoded without padding. */
    private String newState() {
        byte[] bytes = new byte[STATE_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * @return {@code sfdc.oauth.state-ttl}
     * @throws IllegalStateException if it is unset, zero, negative or longer than {@link #MAX_STATE_TTL}
     */
    private Duration stateTtl() {
        Duration ttl = properties.getOauth().getStateTtl();
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(MAX_STATE_TTL) > 0) {
            throw new IllegalStateException("sfdc.oauth.state-ttl must be a positive duration of at most 400 days");
        }
        return ttl;
    }

    /**
     * The {@link #STATE_COOKIE} for {@code state}, with the attributes {@link #authorize()} documents. Its
     * {@code Max-Age} is {@code ttl} rounded up to whole seconds, so the cookie never expires before the state does.
     */
    private ResponseCookie stateCookie(String state, Duration ttl) {
        long maxAgeSeconds = ttl.getSeconds() + (ttl.getNano() > 0 ? 1 : 0);
        return ResponseCookie.from(STATE_COOKIE, state)
                .path(properties.getOauth().getCallback().requestPath())
                .maxAge(maxAgeSeconds)
                .secure(redirectUri().regionMatches(true, 0, "https:", 0, "https:".length()))
                .httpOnly(true)
                .sameSite("Lax")
                .build();
    }

    /**
     * Remembers {@code state} as pending until {@code expiry}. Expired entries are dropped first, then the oldest
     * ones until fewer than {@link #MAX_PENDING_AUTHORIZATIONS} remain, so the store never grows beyond that bound.
     */
    private void remember(String state, Instant expiry, Instant now) {
        synchronized (pendingStates) {
            pendingStates.values().removeIf(expires -> !now.isBefore(expires));
            Iterator<String> oldest = pendingStates.keySet().iterator();
            while (pendingStates.size() >= MAX_PENDING_AUTHORIZATIONS) {
                oldest.next();
                oldest.remove();
            }
            pendingStates.put(state, expiry);
        }
    }

    /**
     * Consumes the pending authorization a callback with a code completes, in the order
     * {@link #completeAuthorization(String, String, String, String, String)} documents.
     *
     * @return why the callback is refused, or {@code null} once its unexpired pending authorization is consumed
     */
    private String consumePending(String state, String stateCookie) {
        if (isBlank(state)) {
            return NO_STATE;
        }
        if (isBlank(stateCookie)) {
            return NO_STATE_COOKIE;
        }
        if (!sameValue(state, stateCookie)) {
            return STATE_MISMATCH;
        }
        Instant expiry;
        synchronized (pendingStates) {
            expiry = pendingStates.remove(state);
        }
        if (expiry == null) {
            return UNKNOWN_STATE;
        }
        return clock.instant().isBefore(expiry) ? null : EXPIRED_STATE;
    }

    /**
     * Discards the pending authorization of a callback without a code, when its {@code state} and cookie are both
     * present and equal; any other state is left as it is.
     */
    private void discardPending(String state, String stateCookie) {
        if (isBlank(state) || isBlank(stateCookie) || !sameValue(state, stateCookie)) {
            return;
        }
        synchronized (pendingStates) {
            pendingStates.remove(state);
        }
    }

    /** Compares two values in time independent of where they differ, so the comparison reveals no prefix. */
    private static boolean sameValue(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
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
