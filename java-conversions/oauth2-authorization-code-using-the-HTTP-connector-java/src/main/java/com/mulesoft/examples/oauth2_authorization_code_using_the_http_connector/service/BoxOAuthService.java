/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.service;

import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.client.BoxApiClient;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.client.BoxApiClient.RawResponse;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.config.BoxProperties;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.config.BoxProperties.ClientCredential;

/**
 * Every behaviour of {@code oauth2-authorization-code-using-the-HTTP-connector/src/main/app/http-authorization-code-web.xml}:
 * <ul>
 *   <li>flow {@code boxUserLoginFlow} (lines 27-35), as {@link #webLogin(byte[], String)};</li>
 *   <li>flow {@code userLoginDoneFlow} (lines 37-48), as {@link #loginDone()};</li>
 *   <li>the surfaces the Mule 3.8 OAuth module generated from {@code oauth2:authorization-code-grant-type},
 *       {@code oauth2:token-manager-config} and the token-response extractors (lines 4-18): the local authorization
 *       URL of {@code AuthorizationRequestUrlBuilder} and {@code StateEncoder} as {@link #authorization(String)},
 *       the redirect-URL processor of {@code AutoAuthorizationCodeTokenRequestHandler} and {@code StateDecoder} as
 *       {@link #redirectCallback(String, String, String)}, and the token manager's per-resource-owner store as
 *       {@link #storeTokens(String, TokenSet)} and {@link #tokens(String)}.</li>
 * </ul>
 *
 * <p>The controllers only bind HTTP input and write the returned {@link Reply}; nothing here touches the servlet
 * API. Every URL and the client ID come from {@link BoxProperties}; the custom parameters {@code box_device_id=1}
 * and {@code box_device_name=device1} are literals of the flow (lines 8-11). Tokens live in memory and are lost on
 * restart, as in the original's default token manager.
 *
 * <p>Refreshes are coordinated per resource owner, as the grant's per-owner refresh lock
 * ({@code ResourceOwnerOAuthContext.getRefreshUserOAuthContextLock()}) coordinates them in Mule 3.8: one request at a
 * time redeems an owner's refresh token, a request that finds a refresh in progress waits for it and then uses the
 * tokens stored for the owner without redeeming the refresh token itself, and a refresh result replaces only the
 * token set it refreshed, so tokens a newer {@code /redirectUrl} callback stored are never overwritten. Callbacks
 * store their tokens at once, without the lock.
 *
 * <p>Platform-generated statuses and texts (the redirect-URL replies, the refresh failures, the missing-token and
 * failure-status messages) reproduce Mule 3.8.0 and are Tier 2A verification points: a captured fixture of the
 * original application overrides them.
 *
 * <p>The Box client credentials are checked where they are about to be used, never at start-up: the authorization
 * redirect needs {@code box.id}, and the code exchange and the refresh need {@code box.id} and {@code box.secret}.
 * A missing one fails locally, naming its key and never a value, and Box is not contacted.
 */
@Service
public class BoxOAuthService {

    /** Resource owner the grant uses when no {@code localAuthorizationUrlResourceOwnerId} is configured. */
    public static final String DEFAULT_RESOURCE_OWNER_ID = "default";

    private static final Logger LOGGER = LoggerFactory.getLogger(BoxOAuthService.class);

    /** Reads only the {@code token_type} custom extractor, {@code #[json:token_type]} (line 15). */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Mule 3.8 default token-response extractors, applied with Matcher.matches() and no flags: a token response
    // spread over several lines therefore yields no token, as it did in Mule.
    private static final Pattern ACCESS_TOKEN = Pattern.compile(".*\"access_token\"[ ]*:[ ]*\"([^\\\"]*)\".*");
    private static final Pattern REFRESH_TOKEN = Pattern.compile(".*\"refresh_token\"[ ]*:[ ]*\"([^\\\"]*)\".*");
    private static final Pattern EXPIRES_IN = Pattern.compile(".*\"expires_in\"[ ]*:[ ]*([\\d]*).*");

    /** {@code oauth2:custom-parameter paramName="box_device_id" value="1"} (line 9). */
    private static final String BOX_DEVICE_ID = "1";
    /** {@code oauth2:custom-parameter paramName="box_device_name" value="device1"} (line 10). */
    private static final String BOX_DEVICE_NAME = "device1";

    private static final String RESOURCE_OWNER_ID_MARKER = ":resourceOwnerId=";
    private static final String ON_COMPLETE_REDIRECT_TO_MARKER = ":onCompleteRedirectTo=";

    // authorizationStatus markers of AutoAuthorizationCodeTokenRequestHandler.
    private static final int SUCCESS = 0;
    private static final int NO_AUTHORIZATION_CODE = 100;
    private static final int TOKEN_URL_CALL_FAILED = 200;
    private static final int TOKEN_NOT_FOUND = 201;
    private static final int FAILURE_PROCESSING_REDIRECT_URL_REQUEST = 300;

    private static final String SEARCH_RESULT_PREFIX =
            "The user successfully authorized access to the resource. Searching for files containing \"mule\": ";
    private static final String NO_ACCESS_TOKEN = "No access token for the default user. Verify that you have "
            + "authenticated the user before trying to execute an operation to the API.";
    private static final String TOKEN_URL_FAILURE_PREFIX = "Failure calling token url ";
    private static final String TOKEN_NOT_FOUND_TEXT =
            "Failed getting access token or refresh token from token URL response. See logs for details.";
    private static final String ACCESS_TOKEN_RETRIEVED_TEXT = "Successfully retrieved access token";
    private static final String NO_AUTHORIZATION_CODE_PREFIX =
            "Failure retrieving access token.\n OAuth Server uri from callback: ";
    private static final String REDIRECT_PROCESSING_FAILURE_TEXT =
            "Failed processing redirect URL request done from OAuth provider. See logs for details.";

    /** Number of causes {@link #describe(Throwable)} names after the outermost exception before it stops. */
    private static final int CAUSE_LOG_LIMIT = 10;
    private static final String CAUSE_SEPARATOR = " <- ";
    private static final String MORE_CAUSES_MARKER = "...";
    private static final String CIRCULAR_CAUSE_MARKER = "[circular]";

    private final BoxProperties properties;
    private final BoxApiClient client;

    /** Replaces {@code Token_Manager_Config} (line 4): tokens per resource owner, in memory only. */
    private final ConcurrentHashMap<String, TokenSet> tokenStore = new ConcurrentHashMap<>();

    /**
     * The refresh lock of each resource owner whose tokens {@link #loginDone()} has had to refresh, created on the
     * first refresh, so the map holds no more owners than the refresh path reaches.
     */
    private final ConcurrentHashMap<String, ReentrantLock> refreshLocks = new ConcurrentHashMap<>();

    /**
     * @param properties the Box OAuth configuration: URLs, client ID and the {@code /web} redirect location
     * @param client     the Box token and search endpoints
     */
    public BoxOAuthService(BoxProperties properties, BoxApiClient client) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * What a controller writes back: status, optional {@code Location} and {@code Content-Type} headers, and the
     * body bytes.
     *
     * @param status      the HTTP status code
     * @param location    the {@code Location} header value, or {@code null} when the header is absent
     * @param contentType the {@code Content-Type} header value, or {@code null} when the header is absent
     * @param body        the body bytes; never {@code null}, empty when there is no body
     */
    public record Reply(int status, String location, String contentType, byte[] body) {

        /** Normalizes a {@code null} body to an empty one, so writers never have to check. */
        public Reply {
            body = body == null ? new byte[0] : body;
        }
    }

    /**
     * Tokens the grant holds for one resource owner, as the token manager's resource-owner context held them.
     *
     * @param accessToken  the access token sent as {@code Authorization: Bearer}
     * @param refreshToken the refresh token used when Box rejects the access token
     * @param expiresIn    the {@code expires_in} digits of the token response, or {@code null}
     * @param tokenType    the {@code token_type} custom extractor value, or {@code null}
     */
    public record TokenSet(String accessToken, String refreshToken, String expiresIn, String tokenType) {

        /** Leaves both tokens out, because they are credentials and a record's default text would carry them. */
        @Override
        public String toString() {
            return "TokenSet[accessToken=" + mask(accessToken) + ", refreshToken=" + mask(refreshToken)
                    + ", expiresIn=" + expiresIn + ", tokenType=" + tokenType + "]";
        }

        private static String mask(String token) {
            return token == null ? "null" : "****";
        }
    }

    /**
     * Stores the tokens of a resource owner, replacing any it already had, as the token manager's
     * {@code ResourceOwnerOAuthContext} does after a token response.
     *
     * @param resourceOwnerId the resource owner, {@link #DEFAULT_RESOURCE_OWNER_ID} when {@code null}
     * @param tokens          the tokens to store
     * @throws NullPointerException when {@code tokens} is {@code null}
     */
    public void storeTokens(String resourceOwnerId, TokenSet tokens) {
        Objects.requireNonNull(tokens, "tokens");
        tokenStore.put(owner(resourceOwnerId), tokens);
    }

    /**
     * Returns the tokens stored for a resource owner, as the token manager's {@code getContextForResourceOwner}.
     *
     * @param resourceOwnerId the resource owner, {@link #DEFAULT_RESOURCE_OWNER_ID} when {@code null}
     * @return the stored tokens, empty when the owner has not authorized yet
     */
    public Optional<TokenSet> tokens(String resourceOwnerId) {
        return Optional.ofNullable(tokenStore.get(owner(resourceOwnerId)));
    }

    /**
     * Flow {@code boxUserLoginFlow} (lines 27-35): its {@code http:response-builder statusCode="302"} with the
     * fixed {@code Location} header, then {@code logger} INFO {@code Redirecting user to Box login page}. The flow
     * leaves the payload unchanged, so the request body is echoed with its content type.
     *
     * @param requestBody        the request body, {@code null} or empty when there is none
     * @param requestContentType the request {@code Content-Type}, used only when a body is echoed
     * @return 302 to {@link BoxProperties#getLoginRedirectLocation()}
     */
    public Reply webLogin(byte[] requestBody, String requestContentType) {
        LOGGER.info("Redirecting user to Box login page");
        byte[] body = requestBody == null ? new byte[0] : requestBody;
        String contentType = body.length > 0 ? requestContentType : null;
        return new Reply(302, properties.getLoginRedirectLocation(), contentType, body);
    }

    /**
     * Flow {@code userLoginDoneFlow} (lines 37-48): {@code http:request GET /search?query=mule} through
     * {@code HTTP_Request_Configuration_HTTPS} with the default user's access token, {@code object-to-string}, then
     * {@code set-payload}.
     *
     * <p>As the grant type does: without an access token the request is refused; a 401 or 403 answer (the default
     * {@code refreshTokenWhen}) refreshes the tokens and repeats the search exactly once; a final status of 400 or
     * more fails with the success-status validator's message. Exceptions from the Box client propagate unchanged.
     *
     * <p>The refresh is coordinated per resource owner ({@link #tokensAfterRejection(String, TokenSet)}), so
     * concurrent rejected searches redeem the refresh token once, and the repeated search uses the tokens stored for
     * the owner once the refresh, or the wait for another request's refresh, is over.
     *
     * @return 200 with no {@code Content-Type}, since {@code set-payload} sets none, and the UTF-8 text
     *         {@code The user successfully authorized access to the resource. Searching for files containing "mule": <body>}
     * @throws IllegalStateException when no access token is stored, before the search or for its repetition, when the
     *                               refresh fails, or when the final search status is 400 or more
     */
    public Reply loginDone() {
        TokenSet current = withAccessToken(tokenStore.get(DEFAULT_RESOURCE_OWNER_ID));

        RawResponse response = client.search(current.accessToken());
        if (response.status() == 401 || response.status() == 403) {
            TokenSet retry = tokensAfterRejection(DEFAULT_RESOURCE_OWNER_ID, current);
            response = client.search(retry.accessToken());
        }
        if (response.status() >= 400) {
            throw new IllegalStateException("Response code " + response.status() + " mapped as failure.");
        }
        String text = SEARCH_RESULT_PREFIX + decode(response);
        return new Reply(200, null, null, text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The tokens to repeat a search with after Box rejected {@code rejected}, the exact token set the search sent,
     * decided under the owner's refresh lock on the model of the grant type's {@code refreshToken} in Mule 3.8:
     * <ul>
     *   <li>a request that finds the lock held waits until it is released and then uses what is stored for the
     *       owner, whether the holder's refresh succeeded or failed, without redeeming the refresh token itself;</li>
     *   <li>a request that takes the lock reads the store again: when it no longer holds {@code rejected}
     *       itself, because another request refreshed or a callback stored newer tokens meanwhile, the stored tokens
     *       are used without a refresh;</li>
     *   <li>otherwise it refreshes, and the result replaces the stored tokens only while they are still
     *       {@code rejected}, so tokens a callback stored during the refresh are kept and used instead.</li>
     * </ul>
     *
     * @throws IllegalStateException with the missing-token message when the tokens to use have no access token, and
     *                               as {@link #refresh(TokenSet)} throws when the refresh fails
     */
    private TokenSet tokensAfterRejection(String owner, TokenSet rejected) {
        ReentrantLock refreshLock = refreshLocks.computeIfAbsent(owner, key -> new ReentrantLock());
        if (!refreshLock.tryLock()) {
            refreshLock.lock();
            refreshLock.unlock();
            return withAccessToken(tokenStore.get(owner));
        }
        try {
            TokenSet stored = tokenStore.get(owner);
            if (stored != rejected) {
                return withAccessToken(stored);
            }
            TokenSet refreshed = refresh(rejected);
            return withAccessToken(tokenStore.compute(owner, (key, latest) -> latest == rejected ? refreshed : latest));
        } finally {
            refreshLock.unlock();
        }
    }

    /** Returns {@code tokens} when it holds an access token; otherwise refuses the request as a missing token. */
    private static TokenSet withAccessToken(TokenSet tokens) {
        if (tokens == null || tokens.accessToken() == null) {
            throw new IllegalStateException(NO_ACCESS_TOKEN);
        }
        return tokens;
    }

    /**
     * Refresh-token request of the grant type: the stored refresh token is exchanged at the token URL, and a
     * response without a new refresh token keeps the old one. The failure messages are Tier 2A verification points.
     * Token response bodies are never logged, because they carry the tokens, and a failed token call is logged by
     * its exception types only ({@link #describe(Throwable)}) before it is rethrown unchanged.
     *
     * <p>Without a {@code box.id} or {@code box.secret} value no token request is made: the missing key, the
     * client ID checked first, is logged at ERROR and the refresh fails with its
     * {@link ClientCredential#notConfiguredMessage()}.
     */
    private TokenSet refresh(TokenSet current) {
        Optional<ClientCredential> missing = properties.firstMissing(ClientCredential.ID, ClientCredential.SECRET);
        if (missing.isPresent()) {
            LOGGER.error("The access token cannot be refreshed: {} is not configured", missing.get().key());
            throw new IllegalStateException(missing.get().notConfiguredMessage());
        }
        RawResponse response;
        try {
            response = client.refreshToken(current.refreshToken());
        } catch (RuntimeException e) {
            LOGGER.error("Failure calling the token url to refresh the access token: {}", describe(e));
            throw e;
        }
        if (response.status() >= 400) {
            LOGGER.error("The token url answered the refresh request with status {}", response.status());
            throw new IllegalStateException(TOKEN_URL_FAILURE_PREFIX + properties.getTokenUrl());
        }
        String body = decode(response);
        String accessToken = extract(ACCESS_TOKEN, body);
        if (accessToken == null) {
            LOGGER.error("No access token could be extracted from the token url's refresh response");
            throw new IllegalStateException(TOKEN_NOT_FOUND_TEXT);
        }
        String refreshToken = extract(REFRESH_TOKEN, body);
        return new TokenSet(accessToken,
                refreshToken != null ? refreshToken : current.refreshToken(),
                extract(EXPIRES_IN, body),
                tokenType(body));
    }

    /**
     * The local authorization URL ({@code localAuthorizationUrl}, line 7): redirects the browser to Box's
     * authorization endpoint with the query {@code AuthorizationRequestUrlBuilder} builds, in its order:
     * {@code response_type=code}, {@code client_id} (appended unencoded, as Mule does), {@code state} carrying
     * {@code :onCompleteRedirectTo=<url>} as {@code StateEncoder} encodes it (only when the URL is given), the custom
     * parameters, and {@code redirect_uri}. No {@code scope} and no resource owner are sent, since the original
     * configures neither.
     *
     * @param onCompleteRedirectTo where {@code /redirectUrl} sends the browser afterwards; {@code null} or empty for
     *                             none
     * @return 302 to the Box authorization URL, with no body
     * @throws IllegalStateException {@code Box client ID (box.id) is not configured} when {@code box.id} has no
     *                               value, before any URL is built, so no browser is sent to Box without a client ID
     */
    public Reply authorization(String onCompleteRedirectTo) {
        Optional<ClientCredential> missing = properties.firstMissing(ClientCredential.ID);
        if (missing.isPresent()) {
            throw new IllegalStateException(missing.get().notConfiguredMessage());
        }
        StringBuilder url = new StringBuilder(properties.getAuthorizationUrl())
                .append("?response_type=code&client_id=").append(properties.getId());
        if (onCompleteRedirectTo != null && !onCompleteRedirectTo.isEmpty()) {
            url.append("&state=").append(encode(ON_COMPLETE_REDIRECT_TO_MARKER + onCompleteRedirectTo));
        }
        url.append("&box_device_id=").append(encode(BOX_DEVICE_ID))
                .append("&box_device_name=").append(encode(BOX_DEVICE_NAME))
                .append("&redirect_uri=").append(encode(properties.getRedirectUrl()));
        return new Reply(302, url.toString(), null, new byte[0]);
    }

    /**
     * The redirect URL ({@code redirectionUrl}, line 6), as the redirect-URL processor of
     * {@code AutoAuthorizationCodeTokenRequestHandler}: decodes the state, exchanges the authorization code at the
     * token URL, extracts the tokens and stores them for the state's resource owner, or {@code default}.
     *
     * <p>The outcome is an {@code authorizationStatus} marker: 0 tokens stored; 100 no code; 200 the token call
     * threw or answered 400 or more, or could not be made because {@code box.id} or {@code box.secret} has no value,
     * in which case Box is not called and the log names the missing key; 201 no access or refresh token in the
     * response; 300 any other failure. When the state names an {@code onCompleteRedirectTo} URL the browser is sent
     * there with the marker; otherwise the platform text is returned. Every status and text here is
     * platform-generated and a Tier 2A verification point. A failure marker is logged at ERROR with its detail and,
     * when an exception caused it, that exception's types only ({@link #describe(Throwable)}).
     *
     * @param code       the {@code code} query parameter, {@code null} when absent
     * @param state      the {@code state} query parameter, URL-decoded, {@code null} when absent
     * @param requestUri the request path plus {@code ?} and the raw query when there is one, quoted in the 100 text
     * @return 302 to {@code <onCompleteRedirectTo>?authorizationStatus=<marker>}, or 200/400/500 with the platform
     *         text in UTF-8 and no {@code Content-Type}
     */
    public Reply redirectCallback(String code, String state, String requestUri) {
        String owner = Objects.requireNonNullElse(decodeState(state, RESOURCE_OWNER_ID_MARKER),
                DEFAULT_RESOURCE_OWNER_ID);
        String redirectTo = decodeState(state, ON_COMPLETE_REDIRECT_TO_MARKER);

        Outcome outcome;
        try {
            outcome = exchangeCode(code, owner);
        } catch (RuntimeException e) {
            outcome = new Outcome(FAILURE_PROCESSING_REDIRECT_URL_REQUEST, "processing failed", e);
        }
        int marker = outcome.marker();
        if (marker != SUCCESS) {
            if (outcome.failure() != null) {
                LOGGER.error("Redirect URL request failed, authorizationStatus {}: {}: {}", marker, outcome.detail(),
                        describe(outcome.failure()));
            } else {
                LOGGER.error("Redirect URL request failed, authorizationStatus {}: {}", marker, outcome.detail());
            }
        }

        if (redirectTo != null && !redirectTo.isEmpty()) {
            String location = redirectTo + (redirectTo.contains("?") ? "&" : "?") + "authorizationStatus=" + marker;
            return new Reply(302, location, null, new byte[0]);
        }
        return switch (marker) {
            case SUCCESS -> text(200, ACCESS_TOKEN_RETRIEVED_TEXT);
            case NO_AUTHORIZATION_CODE -> text(400, NO_AUTHORIZATION_CODE_PREFIX + requestUri);
            case TOKEN_URL_CALL_FAILED -> text(500,
                    TOKEN_URL_FAILURE_PREFIX + properties.getTokenUrl() + ". Exception message is null");
            case TOKEN_NOT_FOUND -> text(500, TOKEN_NOT_FOUND_TEXT);
            default -> text(500, REDIRECT_PROCESSING_FAILURE_TEXT);
        };
    }

    /**
     * Steps of the redirect-URL processor that decide the marker. Only the token call itself maps an exception to
     * 200; any other runtime failure, a {@code null} client response included, reaches the caller's 300 branch.
     * A token call that cannot be made because a client credential has no value is the token call failing, 200,
     * with the missing key as the detail the caller logs.
     */
    private Outcome exchangeCode(String code, String owner) {
        if (code == null || code.isEmpty()) {
            return new Outcome(NO_AUTHORIZATION_CODE, "no authorization code in the request", null);
        }
        Optional<ClientCredential> missing = properties.firstMissing(ClientCredential.ID, ClientCredential.SECRET);
        if (missing.isPresent()) {
            return new Outcome(TOKEN_URL_CALL_FAILED, missing.get().key() + " is not configured", null);
        }
        RawResponse response;
        try {
            response = client.requestToken(code);
        } catch (Exception e) {
            return new Outcome(TOKEN_URL_CALL_FAILED, "the token url call failed", e);
        }
        if (response.status() >= 400) {
            return new Outcome(TOKEN_URL_CALL_FAILED, "the token url answered status " + response.status(), null);
        }
        String body = decode(response);
        String accessToken = extract(ACCESS_TOKEN, body);
        String refreshToken = extract(REFRESH_TOKEN, body);
        if (accessToken == null || refreshToken == null) {
            // The body is not logged: it may hold the token that was found.
            return new Outcome(TOKEN_NOT_FOUND, (accessToken == null ? "access token" : "refresh token")
                    + " not found in the token url response", null);
        }
        storeTokens(owner, new TokenSet(accessToken, refreshToken, extract(EXPIRES_IN, body), tokenType(body)));
        return new Outcome(SUCCESS, "tokens stored", null);
    }

    /** Marker of one redirect-URL request, with what caused it for the log. */
    private record Outcome(int marker, String detail, Exception failure) {
    }

    /**
     * {@code StateDecoder}: the value after the first occurrence of {@code marker}, up to the earliest later
     * occurrence of either marker or the end; {@code null} when the state or the marker is absent. Text before the
     * first marker, the caller's original state, is ignored.
     */
    private static String decodeState(String state, String marker) {
        if (state == null) {
            return null;
        }
        int markerIndex = state.indexOf(marker);
        if (markerIndex < 0) {
            return null;
        }
        int valueStart = markerIndex + marker.length();
        int valueEnd = state.length();
        for (String next : new String[] {RESOURCE_OWNER_ID_MARKER, ON_COMPLETE_REDIRECT_TO_MARKER}) {
            int nextIndex = state.indexOf(next, valueStart);
            if (nextIndex >= 0 && nextIndex < valueEnd) {
                valueEnd = nextIndex;
            }
        }
        return state.substring(valueStart, valueEnd);
    }

    /** Applies one default extractor: its group when the whole body matches, otherwise {@code null}. */
    private static String extract(Pattern pattern, String body) {
        Matcher matcher = pattern.matcher(body);
        return matcher.matches() ? matcher.group(1) : null;
    }

    /**
     * The custom extractor {@code #[json:token_type]} (line 15): the field's text for a value, its JSON for an
     * object or array, and {@code null} when the body is not a JSON object or the field is missing or JSON null.
     */
    private static String tokenType(String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            return null;
        }
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode value = root.get("token_type");
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isValueNode() ? value.asText() : value.toString();
    }

    /** Body text in the charset the response's {@code Content-Type} declares, UTF-8 otherwise. */
    private static String decode(RawResponse response) {
        byte[] body = response.body();
        if (body == null) {
            return "";
        }
        return new String(body, charsetOf(response.headers()));
    }

    private static Charset charsetOf(HttpHeaders headers) {
        if (headers == null) {
            return StandardCharsets.UTF_8;
        }
        try {
            MediaType contentType = headers.getContentType();
            if (contentType != null && contentType.getCharset() != null) {
                return contentType.getCharset();
            }
            return StandardCharsets.UTF_8;
        } catch (IllegalArgumentException e) {
            // An unparseable Content-Type or an unknown or illegal charset name: the body is read as UTF-8.
            return StandardCharsets.UTF_8;
        }
    }

    /**
     * Returns the type identifiers of a token-call failure without any of its text: its class name, then the class
     * name of each cause, separated by {@code " <- "}. Messages, {@code toString()} and suppressed exceptions are
     * never read, because a transport's diagnostics may quote the token URL, the request or the response. The walk
     * stops at a cause already named, marked {@code [circular]}, and after {@link #CAUSE_LOG_LIMIT} causes, marked
     * {@code ...}, so cyclic and very deep chains terminate.
     */
    private static String describe(Throwable failure) {
        StringBuilder out = new StringBuilder(128).append(failure.getClass().getName());
        Set<Throwable> named = Collections.newSetFromMap(new IdentityHashMap<>());
        named.add(failure);
        int causes = 0;
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            out.append(CAUSE_SEPARATOR);
            if (causes == CAUSE_LOG_LIMIT) {
                out.append(MORE_CAUSES_MARKER);
                break;
            }
            if (!named.add(cause)) {
                out.append(CIRCULAR_CAUSE_MARKER);
                break;
            }
            out.append(cause.getClass().getName());
            causes++;
        }
        return out.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String owner(String resourceOwnerId) {
        return resourceOwnerId == null ? DEFAULT_RESOURCE_OWNER_ID : resourceOwnerId;
    }

    private static Reply text(int status, String text) {
        return new Reply(status, null, null, text.getBytes(StandardCharsets.UTF_8));
    }
}
