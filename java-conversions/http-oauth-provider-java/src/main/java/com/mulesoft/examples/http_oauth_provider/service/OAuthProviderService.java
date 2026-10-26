/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_oauth_provider.service;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.http_oauth_provider.config.OAuthProviderProperties;

/**
 * The OAuth 2.0 authorization-code provider of the original {@code http-oauth-provider.xml}, with the
 * grant handled by hand.
 *
 * <p>It replaces:
 * <ul>
 *   <li>{@code oauth2-provider:config} (xml:26-41): the single registered client with its redirect-URI
 *       patterns, authorized grant types and scopes, the authorization codes it issues on
 *       {@code /authorize} and the access tokens it issues on {@code /token};</li>
 *   <li>{@code oauth2-provider:validate scopes="READ_RESOURCE"} (xml:46), the bearer-token check in front
 *       of {@code /resources};</li>
 *   <li>the resource map of {@code /resources} (xml:47-48);</li>
 *   <li>the {@code Location} of the demonstration {@code /redirect} flow (xml:55-56).</li>
 * </ul>
 *
 * <p>Every OAuth decision is taken here; the controllers only bind request input and shape the response
 * from {@link OAuthError}, {@link IssuedToken} and the returned locations. The resource-owner login is not
 * checked here: {@code ResourceOwnerAuthenticator} owns it. Client identity, secret, provider name, scopes
 * and lifetimes come only from {@link OAuthProviderProperties}; a blank configured client ID or secret
 * makes the matching check fail, so the provider fails closed until they are supplied.
 *
 * <p>Codes and tokens live in memory in this singleton and are lost on restart, as the provider module's
 * default in-memory stores were. An entry is valid while the clock is before its expiry instant; expired
 * entries are purged from a store before each insert into it and are treated as absent, and removed, when
 * looked up.
 *
 * <p>The error codes, descriptions, statuses and {@code WWW-Authenticate} values thrown here are Tier 2A
 * verification points: a fixture captured from the original provider governs, and this class changes to
 * match it.
 */
@Service
public class OAuthProviderService {

    private static final String RESPONSE_TYPE_CODE = "code";
    private static final String GRANT_AUTHORIZATION_CODE = "authorization_code";
    private static final String CONFIGURED_GRANT_AUTHORIZATION_CODE = "AUTHORIZATION_CODE";
    private static final String CLIENT_TYPE_PUBLIC = "PUBLIC";
    private static final String TOKEN_TYPE = "bearer";
    private static final String BASIC_SCHEME = "Basic";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final int SECRET_BYTES = 32;

    private final OAuthProviderProperties props;
    private final int providerPort;
    private final int listenerPort;
    private final Clock clock;
    private final RandomGenerator random;
    private final List<Pattern> redirectUriPatterns;
    private final ConcurrentHashMap<String, StoredCode> codes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AccessToken> tokens = new ConcurrentHashMap<>();

    /**
     * Creates the provider on the system UTC clock, issuing codes and tokens from a {@link SecureRandom}.
     *
     * @param props        the provider, client and lifetime configuration ({@code oauth.provider.*})
     * @param providerPort the provider listener port ({@code http.provider.port}), used in the
     *                     {@code /redirect} location
     * @param listenerPort the resource listener port ({@code http.listener.port}), used in the resource
     *                     URI and the {@code /redirect} location
     */
    @Autowired
    public OAuthProviderService(OAuthProviderProperties props,
                                @Value("${http.provider.port}") int providerPort,
                                @Value("${http.listener.port}") int listenerPort) {
        this(props, providerPort, listenerPort, Clock.systemUTC(), new SecureRandom());
    }

    OAuthProviderService(OAuthProviderProperties props, int providerPort, int listenerPort, Clock clock,
                         RandomGenerator random) {
        this.props = Objects.requireNonNull(props, "props");
        this.providerPort = providerPort;
        this.listenerPort = listenerPort;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
        this.redirectUriPatterns = props.redirectUris().stream()
                .map(OAuthProviderService::compileRedirectUriPattern)
                .toList();
    }

    /**
     * Validates an authorization request, as {@code /authorize} receives it both when the login page is
     * rendered and when the login form is posted.
     *
     * <p>Checks, in this order; the first failure throws a 400 {@link OAuthError} without
     * {@code WWW-Authenticate} (the error is answered to the caller, never redirected):
     * <ol>
     *   <li>{@code response_type} is {@code code} and the client is authorized for
     *       {@code AUTHORIZATION_CODE}, else {@code unsupported_response_type};</li>
     *   <li>{@code client_id} equals the configured, non-blank client ID, else {@code invalid_client};</li>
     *   <li>{@code redirect_uri} is non-blank and fully matches a configured pattern, where {@code *}
     *       matches any characters, else {@code invalid_request};</li>
     *   <li>every whitespace-separated {@code scope} token is a configured scope, else
     *       {@code invalid_scope}; an absent or blank scope requests no scope.</li>
     * </ol>
     *
     * @param responseType the {@code response_type} parameter, nullable
     * @param clientId     the {@code client_id} parameter, nullable
     * @param redirectUri  the {@code redirect_uri} parameter, nullable
     * @param scope        the raw {@code scope} parameter, nullable
     * @param state        the {@code state} parameter, nullable
     * @return the validated request, with its scopes in request order
     * @throws OAuthError when a check fails
     */
    public AuthorizationRequest validateAuthorizationRequest(String responseType, String clientId,
                                                             String redirectUri, String scope, String state) {
        if (!RESPONSE_TYPE_CODE.equals(responseType) || !authorizationCodeGrantAllowed()) {
            throw badRequest("unsupported_response_type", "Unsupported response_type");
        }
        String configuredId = props.clientId();
        if (configuredId.isBlank() || clientId == null || !clientId.equals(configuredId)) {
            throw badRequest("invalid_client", "Invalid client id");
        }
        if (isBlank(redirectUri) || !redirectUriAllowed(redirectUri)) {
            throw badRequest("invalid_request", "Invalid redirect_uri");
        }
        Set<String> scopes = parseScopes(scope);
        if (!props.scopes().containsAll(scopes)) {
            throw badRequest("invalid_scope", "Invalid scope");
        }
        return new AuthorizationRequest(responseType, clientId, redirectUri, scope, scopes, state);
    }

    /**
     * Issues a single-use authorization code for a validated request whose resource owner has logged in,
     * and returns the location the browser is redirected to.
     *
     * <p>The code is valid for {@code authorization-code-ttl-seconds}. The location is the request's
     * redirect URI with {@code code=<code>} appended ({@code ?} or {@code &} as the URI requires), then
     * {@code &state=<state>} form-encoded when the request carried a non-empty state.
     *
     * @param request the request {@link #validateAuthorizationRequest} returned
     * @param owner   the authenticated resource owner's username
     * @return the redirect location carrying the code
     */
    public String issueAuthorizationCode(AuthorizationRequest request, String owner) {
        Objects.requireNonNull(request, "request");
        Instant now = Instant.now(clock);
        codes.values().removeIf(stored -> !now.isBefore(stored.expiresAt()));
        String code = newSecret();
        codes.put(code, new StoredCode(request.clientId(), request.redirectUri(), request.scopes(), owner,
                now.plusSeconds(props.authorizationCodeTtlSeconds())));

        StringBuilder location = new StringBuilder(request.redirectUri())
                .append(request.redirectUri().contains("?") ? '&' : '?')
                .append("code=").append(code);
        if (request.state() != null && !request.state().isEmpty()) {
            location.append("&state=").append(URLEncoder.encode(request.state(), StandardCharsets.UTF_8));
        }
        return location.toString();
    }

    /**
     * Exchanges an authorization code for an access token, as {@code /token} receives it by GET or POST.
     *
     * <p>Checks, in this order; the first failure throws an {@link OAuthError}:
     * <ol>
     *   <li>{@code grant_type} absent or blank: 400 {@code invalid_request};</li>
     *   <li>{@code grant_type} other than {@code authorization_code}: 400 {@code unsupported_grant_type};</li>
     *   <li>an {@code Authorization: Basic} header together with a non-blank {@code client_secret}
     *       parameter: 400 {@code invalid_request};</li>
     *   <li>client authentication, from the Basic header (whose ID must equal any non-blank
     *       {@code client_id} parameter) or else from the {@code client_id} and {@code client_secret}
     *       parameters, against the configured, non-blank client ID and, unless the client type is
     *       {@code PUBLIC}, the configured, non-blank secret: 401 {@code invalid_client}, with
     *       {@code WWW-Authenticate: Basic realm="<provider name>"} when the client used Basic;</li>
     *   <li>{@code code} absent or blank: 400 {@code invalid_request};</li>
     *   <li>the code unknown, expired, already used or issued to another client: 400
     *       {@code invalid_grant};</li>
     *   <li>{@code redirect_uri} absent or different from the one the code was issued for: 400
     *       {@code invalid_grant}.</li>
     * </ol>
     * A code that reaches check 6 is consumed whatever the outcome of checks 6 and 7, so it can never be
     * exchanged twice. The token is valid for {@code token-ttl-seconds}.
     *
     * @param grantType           the {@code grant_type} parameter, nullable
     * @param code                the {@code code} parameter, nullable
     * @param redirectUri         the {@code redirect_uri} parameter, nullable
     * @param clientId            the {@code client_id} parameter, nullable
     * @param clientSecret        the {@code client_secret} parameter, nullable
     * @param authorizationHeader the raw {@code Authorization} header, nullable
     * @return the issued token
     * @throws OAuthError when a check fails
     */
    public IssuedToken exchangeAuthorizationCode(String grantType, String code, String redirectUri,
                                                 String clientId, String clientSecret,
                                                 String authorizationHeader) {
        if (isBlank(grantType)) {
            throw badRequest("invalid_request", "Missing parameter: grant_type");
        }
        if (!GRANT_AUTHORIZATION_CODE.equals(grantType)) {
            throw badRequest("unsupported_grant_type", "Unsupported grant_type");
        }
        boolean basic = isBasicScheme(authorizationHeader);
        if (basic && !isBlank(clientSecret)) {
            throw badRequest("invalid_request", "Multiple client authentication methods");
        }
        String authenticatedId = authenticateClient(basic, authorizationHeader, clientId, clientSecret);
        if (isBlank(code)) {
            throw badRequest("invalid_request", "Missing parameter: code");
        }
        StoredCode stored = codes.remove(code);
        Instant now = Instant.now(clock);
        if (stored == null || !now.isBefore(stored.expiresAt()) || !stored.clientId().equals(authenticatedId)) {
            throw badRequest("invalid_grant", "Invalid authorization code");
        }
        if (isBlank(redirectUri) || !redirectUri.equals(stored.redirectUri())) {
            throw badRequest("invalid_grant", "redirect_uri mismatch");
        }

        tokens.values().removeIf(token -> !now.isBefore(token.expiresAt()));
        String value = newSecret();
        tokens.put(value, new AccessToken(value, authenticatedId, stored.owner(), stored.scopes(),
                now.plusSeconds(props.tokenTtlSeconds())));
        return new IssuedToken(value, TOKEN_TYPE, props.tokenTtlSeconds(), String.join(" ", stored.scopes()));
    }

    /**
     * Validates the bearer token presented to {@code /resources}, replacing
     * {@code oauth2-provider:validate scopes="READ_RESOURCE" throwExceptionOnUnaccepted="true"} (xml:46).
     *
     * <p>Only an {@code Authorization: Bearer <token>} header is read. Checks, in this order:
     * <ol>
     *   <li>no header, another scheme or a blank token: 401 {@code invalid_request},
     *       {@code WWW-Authenticate: Bearer realm="<provider name>"};</li>
     *   <li>a token never issued or expired: 401 {@code invalid_token},
     *       {@code WWW-Authenticate: Bearer realm="<provider name>", error="invalid_token"};</li>
     *   <li>a token without the configured resource scope: 403 {@code insufficient_scope},
     *       {@code WWW-Authenticate: Bearer realm="<provider name>", error="insufficient_scope",
     *       scope="<resource scope>"}.</li>
     * </ol>
     * A valid token is not consumed and may be presented again until it expires.
     *
     * @param authorizationHeader the raw {@code Authorization} header, nullable
     * @return the validated token
     * @throws OAuthError when a check fails
     */
    public AccessToken validateAccessToken(String authorizationHeader) {
        String realm = props.providerName();
        String resourceScope = props.resourceScope();
        String value = bearerToken(authorizationHeader);
        if (value == null) {
            throw new OAuthError(HttpStatus.UNAUTHORIZED, "invalid_request", "Access token missing",
                    "Bearer realm=\"" + realm + "\"");
        }
        AccessToken token = tokens.get(value);
        if (token == null || !Instant.now(clock).isBefore(token.expiresAt())) {
            if (token != null) {
                tokens.remove(value, token);
            }
            throw new OAuthError(HttpStatus.UNAUTHORIZED, "invalid_token", "Invalid or expired access token",
                    "Bearer realm=\"" + realm + "\", error=\"invalid_token\"");
        }
        if (!token.scopes().contains(resourceScope)) {
            throw new OAuthError(HttpStatus.FORBIDDEN, "insufficient_scope",
                    "Insufficient scope: " + resourceScope + " required",
                    "Bearer realm=\"" + realm + "\", error=\"insufficient_scope\", scope=\""
                            + resourceScope + "\"");
        }
        return token;
    }

    /**
     * The resource {@code /resources} serves once its token is validated (xml:47-48).
     *
     * @return a new map holding {@code name} {@code payroll} and
     *         {@code uri} {@code http://localhost:<listener port>/resources/payroll}
     */
    public Map<String, String> protectedResource() {
        // A HashMap, the type the MEL map literal produced: it iterates name before uri, so the JSON keeps
        // the original key order. Do not replace it with a LinkedHashMap or Map.of.
        Map<String, String> resource = new HashMap<>();
        resource.put("name", "payroll");
        resource.put("uri", "http://localhost:" + listenerPort + "/resources/payroll");
        return resource;
    }

    /**
     * The {@code Location} the demonstration {@code /redirect} flow answers with its 302 (xml:55-56): the
     * provider's {@code /token} URL carrying the configured client credentials, the received code and the
     * {@code /redirect} URI.
     *
     * <p>The code is the first {@code code} query parameter, form-decoded; when the query has none it is
     * written as {@code null}, as MEL rendered a missing value. A malformed percent escape anywhere in the
     * query throws {@link IllegalArgumentException}.
     *
     * @param rawQueryString the request's raw query string, nullable
     * @return the location, unencoded
     */
    public String redirectLocation(String rawQueryString) {
        String code = String.valueOf((Object) firstQueryValue(rawQueryString, "code"));
        // The template is the original's verbatim, including the empty parameter between "&&".
        return "http://localhost:" + providerPort
                + "/token?grant_type=authorization_code&&client_id=" + props.clientId()
                + "&client_secret=" + props.clientSecret()
                + "&code=" + code
                + "&redirect_uri=http://localhost:" + listenerPort + "/redirect";
    }

    /**
     * @return the configured provider name, shown on the login page
     */
    public String providerName() {
        return props.providerName();
    }

    /**
     * @return the configured client name, shown on the login page
     */
    public String clientName() {
        return props.clientName();
    }

    /**
     * @return the configured client description, shown on the login page
     */
    public String clientDescription() {
        return props.clientDescription();
    }


    private boolean authorizationCodeGrantAllowed() {
        return props.authorizedGrantTypes().stream()
                .anyMatch(CONFIGURED_GRANT_AUTHORIZATION_CODE::equalsIgnoreCase);
    }

    private boolean redirectUriAllowed(String redirectUri) {
        return redirectUriPatterns.stream().anyMatch(pattern -> pattern.matcher(redirectUri).matches());
    }

    /**
     * Compiles a configured redirect-URI pattern: each {@code *} matches any characters and everything
     * else matches literally, so {@code http://localhost*} is a prefix match and a pattern without
     * {@code *} an exact one.
     */
    private static Pattern compileRedirectUriPattern(String pattern) {
        return Pattern.compile(Arrays.stream(pattern.split("\\*", -1))
                .map(Pattern::quote)
                .collect(Collectors.joining(".*")));
    }

    private static Set<String> parseScopes(String scope) {
        Set<String> scopes = new LinkedHashSet<>();
        if (!isBlank(scope)) {
            Collections.addAll(scopes, scope.trim().split("\\s+"));
        }
        return scopes;
    }

    /**
     * Authenticates the OAuth client of a token request and returns its ID.
     *
     * @throws OAuthError 401 {@code invalid_client} when the credentials are malformed or do not match
     */
    private String authenticateClient(boolean basic, String authorizationHeader, String clientIdParam,
                                      String clientSecretParam) {
        String id;
        String secret;
        if (basic) {
            int space = authorizationHeader.indexOf(' ');
            String encoded = space < 0 ? "" : authorizationHeader.substring(space + 1).trim();
            String decoded;
            try {
                decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException malformed) {
                throw invalidClient(true);
            }
            int colon = decoded.indexOf(':');
            if (colon < 0) {
                throw invalidClient(true);
            }
            id = decoded.substring(0, colon);
            secret = decoded.substring(colon + 1);
            if (!isBlank(clientIdParam) && !clientIdParam.equals(id)) {
                throw invalidClient(true);
            }
        } else {
            id = clientIdParam;
            secret = clientSecretParam;
        }

        String configuredId = props.clientId();
        if (id == null || configuredId.isBlank() || !id.equals(configuredId)) {
            throw invalidClient(basic);
        }
        if (!CLIENT_TYPE_PUBLIC.equalsIgnoreCase(props.clientType())) {
            String configuredSecret = props.clientSecret();
            if (isBlank(secret) || configuredSecret.isBlank()
                    || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),
                            configuredSecret.getBytes(StandardCharsets.UTF_8))) {
                throw invalidClient(basic);
            }
        }
        return id;
    }

    private OAuthError invalidClient(boolean basic) {
        return new OAuthError(HttpStatus.UNAUTHORIZED, "invalid_client", "Invalid client credentials",
                basic ? "Basic realm=\"" + props.providerName() + "\"" : null);
    }

    /** Whether the header's scheme, the text before its first space, is {@code Basic} in any case. */
    private static boolean isBasicScheme(String authorizationHeader) {
        if (authorizationHeader == null) {
            return false;
        }
        int space = authorizationHeader.indexOf(' ');
        String scheme = space < 0 ? authorizationHeader : authorizationHeader.substring(0, space);
        return BASIC_SCHEME.equalsIgnoreCase(scheme);
    }

    /** The token of an {@code Authorization: Bearer} header, or {@code null} when there is none. */
    private static String bearerToken(String authorizationHeader) {
        if (authorizationHeader == null
                || !authorizationHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * The first value of a query parameter, decoding every pair as Mule's {@code http.query.params} did, so
     * a malformed escape in any pair fails; a pair without {@code =} has the value {@code ""}.
     */
    private static String firstQueryValue(String rawQueryString, String name) {
        if (rawQueryString == null) {
            return null;
        }
        String found = null;
        for (String pair : rawQueryString.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String rawName = equals < 0 ? pair : pair.substring(0, equals);
            String rawValue = equals < 0 ? "" : pair.substring(equals + 1);
            String pairName = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
            String pairValue = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
            if (found == null && name.equals(pairName)) {
                found = pairValue;
            }
        }
        return found;
    }

    /** 32 random bytes, URL-safe Base64 without padding, for codes and tokens. */
    private String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static OAuthError badRequest(String error, String description) {
        return new OAuthError(HttpStatus.BAD_REQUEST, error, description, null);
    }

    /** An issued, not yet exchanged authorization code. */
    private record StoredCode(String clientId, String redirectUri, Set<String> scopes, String owner,
                              Instant expiresAt) {
    }

    /**
     * A validated authorization request.
     *
     * @param responseType the {@code response_type} parameter
     * @param clientId     the {@code client_id} parameter
     * @param redirectUri  the {@code redirect_uri} parameter
     * @param scope        the raw {@code scope} parameter, nullable
     * @param scopes       the requested scopes in request order, unmodifiable
     * @param state        the {@code state} parameter, nullable
     */
    public record AuthorizationRequest(String responseType, String clientId, String redirectUri, String scope,
                                       Set<String> scopes, String state) {

        public AuthorizationRequest {
            scopes = scopes == null
                    ? Collections.emptySet()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(scopes));
        }
    }

    /**
     * The token answered by {@code /token}.
     *
     * @param accessToken the access token
     * @param tokenType   the token type, {@code bearer}
     * @param expiresIn   the lifetime in seconds
     * @param scope       the granted scopes, space-separated
     */
    public record IssuedToken(String accessToken, String tokenType, long expiresIn, String scope) {

        /**
         * The JSON body of the token response. It stays one flat object without a refresh token or any
         * nested value, because the original test parses the page from its first opening brace to its
         * first closing brace.
         *
         * @return a new map of {@code access_token}, {@code token_type}, {@code expires_in} (a number) and
         *         {@code scope}, in that order
         */
        public Map<String, Object> toJsonBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("access_token", accessToken);
            body.put("token_type", tokenType);
            body.put("expires_in", Long.valueOf(expiresIn));
            body.put("scope", scope);
            return body;
        }
    }

    /**
     * An issued access token.
     *
     * @param value     the token value
     * @param clientId  the client it was issued to
     * @param owner     the resource owner who authorized it
     * @param scopes    the granted scopes, unmodifiable
     * @param expiresAt the instant from which it is expired
     */
    public record AccessToken(String value, String clientId, String owner, Set<String> scopes, Instant expiresAt) {

        public AccessToken {
            scopes = scopes == null
                    ? Collections.emptySet()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(scopes));
        }
    }

    /**
     * An OAuth error: the HTTP status, the RFC 6749 / RFC 6750 error code and description, and the
     * {@code WWW-Authenticate} value the response carries, if any.
     */
    public static final class OAuthError extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final HttpStatus status;
        private final String error;
        private final String description;
        private final String wwwAuthenticate;

        /**
         * @param status          the response status
         * @param error           the OAuth error code
         * @param description     the error description, also the exception message
         * @param wwwAuthenticate the {@code WWW-Authenticate} value, or {@code null} for none
         */
        public OAuthError(HttpStatus status, String error, String description, String wwwAuthenticate) {
            super(description);
            this.status = status;
            this.error = error;
            this.description = description;
            this.wwwAuthenticate = wwwAuthenticate;
        }

        public HttpStatus status() {
            return status;
        }

        public String error() {
            return error;
        }

        public String description() {
            return description;
        }

        public String wwwAuthenticate() {
            return wwwAuthenticate;
        }

        /**
         * @return a new map of {@code error} and {@code error_description}, in that order
         */
        public Map<String, String> toJsonBody() {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("error", error);
            body.put("error_description", description);
            return body;
        }
    }
}

