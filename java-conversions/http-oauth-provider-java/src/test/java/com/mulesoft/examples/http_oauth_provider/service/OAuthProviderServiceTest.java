/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_oauth_provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.mulesoft.examples.http_oauth_provider.config.OAuthProviderProperties;
import com.mulesoft.examples.http_oauth_provider.service.OAuthProviderService.AccessToken;
import com.mulesoft.examples.http_oauth_provider.service.OAuthProviderService.AuthorizationRequest;
import com.mulesoft.examples.http_oauth_provider.service.OAuthProviderService.IssuedToken;
import com.mulesoft.examples.http_oauth_provider.service.OAuthProviderService.OAuthError;

/**
 * Unit test of {@link OAuthProviderService}, which replaces the {@code oauth2-provider:config} of
 * {@code http-oauth-provider.xml} (lines 26-41: provider {@code SampleAPI}, the confidential client
 * {@code myclientid}/{@code myclientsecret} with redirect URI {@code http://localhost*}, grant
 * {@code AUTHORIZATION_CODE} and scopes {@code READ_RESOURCE} and {@code POST_RESOURCE}), the
 * {@code oauth2-provider:validate scopes="READ_RESOURCE"} in front of {@code /resources} (line 46), the
 * payroll resource map (lines 47-48) and the {@code Location} of the {@code /redirect} flow (line 56).
 *
 * <p>It drives the authorization-code grant end to end and every rejection the service defines: response
 * type, grant type, client identity and authentication (parameters and {@code Basic}), redirect-URI
 * wildcard matching, scopes, code and token lifetimes on their exact boundaries, single-use codes, bearer
 * header parsing and the fail-closed rejection while the configured client credentials are blank. Time is a
 * {@link MutableClock} and codes and tokens come from a seeded generator, so every run is deterministic; the
 * original demo values are built here in code, so no Spring context, profile or network is involved.
 */
class OAuthProviderServiceTest {

    private static final int PROVIDER_PORT = 9081;
    private static final int LISTENER_PORT = 9082;
    private static final long SEED = 42L;
    private static final Instant START = Instant.parse("2024-01-01T00:00:00Z");

    private static final String CLIENT_ID = "myclientid";
    private static final String CLIENT_SECRET = "myclientsecret";
    private static final String OWNER = "mule";
    private static final String REDIRECT_URI = "http://localhost:" + LISTENER_PORT + "/redirect";
    private static final String GRANT_TYPE = "authorization_code";

    private static final String BASIC_CHALLENGE = "Basic realm=\"SampleAPI\"";
    private static final String BEARER_CHALLENGE = "Bearer realm=\"SampleAPI\"";
    private static final String INVALID_TOKEN_CHALLENGE = "Bearer realm=\"SampleAPI\", error=\"invalid_token\"";
    private static final String INSUFFICIENT_SCOPE_CHALLENGE =
            "Bearer realm=\"SampleAPI\", error=\"insufficient_scope\", scope=\"READ_RESOURCE\"";

    /** A {@code code} argument the token-request table replaces with a code freshly issued to the client. */
    private static final String ISSUED_CODE = "<code issued to myclientid>";

    /** 32 random bytes in URL-safe Base64 without padding, the form of every code and token. */
    private static final String SECRET_REGEX = "[A-Za-z0-9_-]{43}";

    private MutableClock clock;
    private OAuthProviderService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        service = service(properties(CLIENT_ID, CLIENT_SECRET));
    }

    // Fixtures and helpers

    /** The original provider configuration (xml lines 16, 26-37, 46) with the given client credentials. */
    static OAuthProviderProperties properties(String clientId, String clientSecret) {
        return properties(clientId, clientSecret, "CONFIDENTIAL", List.of("http://localhost*"),
                List.of("AUTHORIZATION_CODE"));
    }

    /** The original provider configuration with the client's type, redirect URIs and grant types replaced. */
    static OAuthProviderProperties properties(String clientId, String clientSecret, String clientType,
                                              List<String> redirectUris, List<String> grantTypes) {
        return new OAuthProviderProperties(
                "SampleAPI",
                clientId,
                clientSecret,
                "Mule Bookstore",
                "Mule-powered On-line Bookstore",
                clientType,
                redirectUris,
                grantTypes,
                List.of("READ_RESOURCE", "POST_RESOURCE"),
                "READ_RESOURCE",
                86400L,
                600L,
                new OAuthProviderProperties.Owner("mule", "mule", List.of("RESOURCE_OWNER")));
    }

    /** A provider on this test's clock, drawing codes and tokens from a generator seeded with {@link #SEED}. */
    private OAuthProviderService service(OAuthProviderProperties props) {
        return new OAuthProviderService(props, PROVIDER_PORT, LISTENER_PORT, clock, new SplittableRandom(SEED));
    }

    /** Validates an authorization request for {@link #REDIRECT_URI} and issues its code to {@link #OWNER}. */
    private String obtainCode(String scope) {
        AuthorizationRequest request =
                service.validateAuthorizationRequest("code", CLIENT_ID, REDIRECT_URI, scope, null);
        return codeIn(service.issueAuthorizationCode(request, OWNER), REDIRECT_URI + "?code=", "");
    }

    /** Exchanges a code as the configured client authenticating with parameters. */
    private IssuedToken exchange(String code) {
        return service.exchangeAuthorizationCode(GRANT_TYPE, code, REDIRECT_URI, CLIENT_ID, CLIENT_SECRET, null);
    }

    private static OAuthError expectError(Executable executable) {
        return assertThrows(OAuthProviderService.OAuthError.class, executable);
    }

    private static void assertError(OAuthError error, int status, String code, String description,
                                    String wwwAuthenticate) {
        assertThat(error.status().value()).isEqualTo(status);
        assertThat(error.error()).isEqualTo(code);
        assertThat(error.description()).isEqualTo(description);
        assertThat(error.wwwAuthenticate()).isEqualTo(wwwAuthenticate);
    }

    /** The code of an issued location of the form {@code <prefix><code><suffix>}. */
    private static String codeIn(String location, String prefix, String suffix) {
        Matcher matcher = Pattern.compile(Pattern.quote(prefix) + "(" + SECRET_REGEX + ")" + Pattern.quote(suffix))
                .matcher(location);
        assertThat(matcher.matches()).as("issued location %s", location).isTrue();
        return matcher.group(1);
    }

    private static String basic(String credentials) {
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    /** The {@code /redirect} location of xml line 56 for the demo client and this test's ports. */
    private static String redirectLocationWithCode(String code) {
        return "http://localhost:9081/token?grant_type=authorization_code&&client_id=myclientid"
                + "&client_secret=myclientsecret&code=" + code + "&redirect_uri=http://localhost:9082/redirect";
    }

    // Authorization-code grant

    @Test
    void authorizationCodeFlowGrantsAReusableTokenForTheProtectedResource() {
        AuthorizationRequest request =
                service.validateAuthorizationRequest("code", CLIENT_ID, REDIRECT_URI, "READ_RESOURCE", "xyz");
        assertThat(request).isEqualTo(new AuthorizationRequest("code", CLIENT_ID, REDIRECT_URI, "READ_RESOURCE",
                Set.of("READ_RESOURCE"), "xyz"));

        String code = codeIn(service.issueAuthorizationCode(request, OWNER), REDIRECT_URI + "?code=", "&state=xyz");
        IssuedToken issued = exchange(code);

        assertThat(issued.accessToken()).matches(SECRET_REGEX).isNotEqualTo(code);
        assertThat(issued.tokenType()).isEqualTo("bearer");
        assertThat(issued.expiresIn()).isEqualTo(86400L);
        assertThat(issued.scope()).isEqualTo("READ_RESOURCE");

        AccessToken token = service.validateAccessToken("Bearer " + issued.accessToken());
        assertThat(token).isEqualTo(new AccessToken(issued.accessToken(), CLIENT_ID, OWNER,
                Set.of("READ_RESOURCE"), START.plusSeconds(86400)));
        assertThat(service.validateAccessToken("Bearer " + issued.accessToken())).isEqualTo(token);
    }

    static Stream<Arguments> issuedLocations() {
        return Stream.of(
                Arguments.of("no state", REDIRECT_URI, null, REDIRECT_URI + "?code=", ""),
                Arguments.of("empty state", REDIRECT_URI, "", REDIRECT_URI + "?code=", ""),
                Arguments.of("plain state", REDIRECT_URI, "xyz", REDIRECT_URI + "?code=", "&state=xyz"),
                Arguments.of("state form-encoded", REDIRECT_URI, "a b&c=d/\u00e9", REDIRECT_URI + "?code=",
                        "&state=a+b%26c%3Dd%2F%C3%A9"),
                Arguments.of("redirect URI already carrying a query", "http://localhost:1234/x?app=1", "s",
                        "http://localhost:1234/x?app=1&code=", "&state=s"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("issuedLocations")
    void issuedLocationTargetsTheRedirectUriWithTheCodeAndState(String scenario, String redirectUri, String state,
                                                               String prefix, String suffix) {
        AuthorizationRequest request =
                service.validateAuthorizationRequest("code", CLIENT_ID, redirectUri, "READ_RESOURCE", state);

        String code = codeIn(service.issueAuthorizationCode(request, OWNER), prefix, suffix);

        IssuedToken issued = service.exchangeAuthorizationCode(GRANT_TYPE, code, redirectUri, CLIENT_ID,
                CLIENT_SECRET, null);
        assertThat(service.validateAccessToken("Bearer " + issued.accessToken()).owner()).isEqualTo(OWNER);
    }

    @ParameterizedTest(name = "exchanged {0} s after issue: accepted = {1}")
    @MethodSource
    void codeIsExchangeableOnlyBeforeItsTtlElapses(long secondsAfterIssue, boolean accepted) {
        String code = obtainCode("READ_RESOURCE");
        clock.advance(Duration.ofSeconds(secondsAfterIssue));

        if (accepted) {
            assertThat(exchange(code).scope()).isEqualTo("READ_RESOURCE");
        } else {
            assertError(expectError(() -> exchange(code)), 400, "invalid_grant", "Invalid authorization code", null);
        }
    }

    static Stream<Arguments> codeIsExchangeableOnlyBeforeItsTtlElapses() {
        return Stream.of(Arguments.of(599L, true), Arguments.of(600L, false), Arguments.of(601L, false));
    }

    @ParameterizedTest(name = "presented {0} s after issue: accepted = {1}")
    @MethodSource
    void tokenIsValidOnlyBeforeItsTtlElapses(long secondsAfterIssue, boolean accepted) {
        String header = "Bearer " + exchange(obtainCode("READ_RESOURCE")).accessToken();
        clock.advance(Duration.ofSeconds(secondsAfterIssue));

        if (accepted) {
            assertThat(service.validateAccessToken(header).expiresAt()).isEqualTo(START.plusSeconds(86400));
        } else {
            assertError(expectError(() -> service.validateAccessToken(header)), 401, "invalid_token",
                    "Invalid or expired access token", INVALID_TOKEN_CHALLENGE);
            // The expired token was dropped from the store; presenting it again is still rejected.
            assertError(expectError(() -> service.validateAccessToken(header)), 401, "invalid_token",
                    "Invalid or expired access token", INVALID_TOKEN_CHALLENGE);
        }
    }

    static Stream<Arguments> tokenIsValidOnlyBeforeItsTtlElapses() {
        return Stream.of(Arguments.of(86399L, true), Arguments.of(86400L, false), Arguments.of(86401L, false));
    }

    @Test
    void codeCanBeExchangedOnlyOnce() {
        String code = obtainCode("READ_RESOURCE");
        exchange(code);

        assertError(expectError(() -> exchange(code)), 400, "invalid_grant", "Invalid authorization code", null);
    }

    @Test
    void codeIsConsumedByAnExchangeWithADifferentRedirectUri() {
        String code = obtainCode("READ_RESOURCE");

        assertError(expectError(() -> service.exchangeAuthorizationCode(GRANT_TYPE, code,
                "http://localhost:9082/other", CLIENT_ID, CLIENT_SECRET, null)),
                400, "invalid_grant", "redirect_uri mismatch", null);
        assertError(expectError(() -> exchange(code)), 400, "invalid_grant", "Invalid authorization code", null);
    }

    @Test
    void failedClientAuthenticationLeavesTheCodeExchangeable() {
        String code = obtainCode("READ_RESOURCE");

        assertError(expectError(() -> service.exchangeAuthorizationCode(GRANT_TYPE, code, REDIRECT_URI, CLIENT_ID,
                "wrong-secret", null)), 401, "invalid_client", "Invalid client credentials", null);
        assertThat(exchange(code).scope()).isEqualTo("READ_RESOURCE");
    }

    @Test
    void codeIssuedToAnotherClientIsRejected() {
        AuthorizationRequest foreign = new AuthorizationRequest("code", "otherclient", REDIRECT_URI, "READ_RESOURCE",
                Set.of("READ_RESOURCE"), null);
        String code = codeIn(service.issueAuthorizationCode(foreign, OWNER), REDIRECT_URI + "?code=", "");

        assertError(expectError(() -> exchange(code)), 400, "invalid_grant", "Invalid authorization code", null);
    }

    @Test
    void codesAndTokensAreDistinctUrlSafeValuesFromTheInjectedGenerator() {
        String first = obtainCode("READ_RESOURCE");
        String second = obtainCode("READ_RESOURCE");
        String token = exchange(first).accessToken();

        assertThat(List.of(first, second, token))
                .allMatch(value -> value.matches(SECRET_REGEX))
                .doesNotHaveDuplicates();

        clock = new MutableClock();
        service = service(properties(CLIENT_ID, CLIENT_SECRET));
        assertThat(obtainCode("READ_RESOURCE")).as("same seed, same first code").isEqualTo(first);
    }

    @Test
    void issuingPurgesExpiredCodesAndTokensAndKeepsLiveOnes() {
        String expiredToken = exchange(obtainCode("READ_RESOURCE")).accessToken();
        String expiredCode = obtainCode("READ_RESOURCE");
        clock.advance(Duration.ofSeconds(86400));

        String liveCode = obtainCode("READ_RESOURCE");
        String liveToken = exchange(obtainCode("READ_RESOURCE")).accessToken();
        String laterToken = exchange(liveCode).accessToken();

        assertError(expectError(() -> exchange(expiredCode)), 400, "invalid_grant", "Invalid authorization code",
                null);
        assertError(expectError(() -> service.validateAccessToken("Bearer " + expiredToken)), 401, "invalid_token",
                "Invalid or expired access token", INVALID_TOKEN_CHALLENGE);
        assertThat(service.validateAccessToken("Bearer " + liveToken).value()).isEqualTo(liveToken);
        assertThat(service.validateAccessToken("Bearer " + laterToken).value()).isEqualTo(laterToken);
    }

    // Authorization requests: response type, client, redirect URI, scopes

    static Stream<Arguments> rejectedAuthorizationRequests() {
        return Stream.of(
                Arguments.of("response_type token", "token", CLIENT_ID, "READ_RESOURCE",
                        "unsupported_response_type", "Unsupported response_type"),
                Arguments.of("response_type missing", null, CLIENT_ID, "READ_RESOURCE",
                        "unsupported_response_type", "Unsupported response_type"),
                Arguments.of("response_type differing in case", "CODE", CLIENT_ID, "READ_RESOURCE",
                        "unsupported_response_type", "Unsupported response_type"),
                Arguments.of("response_type checked before client_id", "token", "otherclient", "READ_RESOURCE",
                        "unsupported_response_type", "Unsupported response_type"),
                Arguments.of("unknown client_id", "code", "otherclient", "READ_RESOURCE",
                        "invalid_client", "Invalid client id"),
                Arguments.of("client_id missing", "code", null, "READ_RESOURCE",
                        "invalid_client", "Invalid client id"),
                Arguments.of("client_id empty", "code", "", "READ_RESOURCE",
                        "invalid_client", "Invalid client id"),
                Arguments.of("client_id differing in case", "code", "MyClientId", "READ_RESOURCE",
                        "invalid_client", "Invalid client id"),
                Arguments.of("unknown scope", "code", CLIENT_ID, "DELETE_RESOURCE",
                        "invalid_scope", "Invalid scope"),
                Arguments.of("unknown scope among known ones", "code", CLIENT_ID, "READ_RESOURCE DELETE_RESOURCE",
                        "invalid_scope", "Invalid scope"),
                Arguments.of("scope differing in case", "code", CLIENT_ID, "read_resource",
                        "invalid_scope", "Invalid scope"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedAuthorizationRequests")
    void invalidAuthorizationRequestIsRejectedWithABadRequest(String scenario, String responseType, String clientId,
                                                              String scope, String error, String description) {
        assertError(expectError(() -> service.validateAuthorizationRequest(responseType, clientId, REDIRECT_URI,
                scope, "xyz")), 400, error, description, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://localhost",
        "http://localhost:1234/x",
        "http://localhost:9082/redirect",
        "http://localhost/cb?x=1&y=*"
    })
    void redirectUriMatchingTheConfiguredWildcardIsAccepted(String redirectUri) {
        assertThat(service.validateAuthorizationRequest("code", CLIENT_ID, redirectUri, "READ_RESOURCE", null)
                .redirectUri()).isEqualTo(redirectUri);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "   ",
        "http://evil.example/cb",
        "https://localhost/cb",
        "HTTP://LOCALHOST/cb",
        " http://localhost/cb",
        "http://127.0.0.1/cb",
        "localhost"
    })
    void redirectUriOutsideTheConfiguredWildcardIsRejected(String redirectUri) {
        assertError(expectError(() -> service.validateAuthorizationRequest("code", CLIENT_ID, redirectUri,
                "READ_RESOURCE", null)), 400, "invalid_request", "Invalid redirect_uri", null);
    }

    @ParameterizedTest(name = "{0}: accepted = {1}")
    @MethodSource
    void redirectUriPatternWithoutWildcardMatchesLiterallyAndInFull(String redirectUri, boolean accepted) {
        service = service(properties(CLIENT_ID, CLIENT_SECRET, "CONFIDENTIAL", List.of("http://app.example/cb"),
                List.of("AUTHORIZATION_CODE")));
        Executable validation =
                () -> service.validateAuthorizationRequest("code", CLIENT_ID, redirectUri, "READ_RESOURCE", null);

        if (accepted) {
            assertDoesNotThrow(validation);
        } else {
            assertError(expectError(validation), 400, "invalid_request", "Invalid redirect_uri", null);
        }
    }

    static Stream<Arguments> redirectUriPatternWithoutWildcardMatchesLiterallyAndInFull() {
        return Stream.of(
                Arguments.of("http://app.example/cb", true),
                Arguments.of("http://appXexample/cb", false),
                Arguments.of("http://app.example/cb/x", false),
                Arguments.of("http://app.example/c", false));
    }

    static Stream<Arguments> grantedScopes() {
        return Stream.of(
                Arguments.of("READ_RESOURCE", List.of("READ_RESOURCE"), "READ_RESOURCE", true),
                Arguments.of("READ_RESOURCE POST_RESOURCE", List.of("READ_RESOURCE", "POST_RESOURCE"),
                        "READ_RESOURCE POST_RESOURCE", true),
                Arguments.of("  POST_RESOURCE \t READ_RESOURCE  ", List.of("POST_RESOURCE", "READ_RESOURCE"),
                        "POST_RESOURCE READ_RESOURCE", true),
                Arguments.of("READ_RESOURCE READ_RESOURCE", List.of("READ_RESOURCE"), "READ_RESOURCE", true),
                Arguments.of("POST_RESOURCE", List.of("POST_RESOURCE"), "POST_RESOURCE", false),
                Arguments.of(null, List.of(), "", false),
                Arguments.of("", List.of(), "", false),
                Arguments.of("   ", List.of(), "", false));
    }

    @ParameterizedTest(name = "scope [{0}] grants [{2}]: resource accessible = {3}")
    @MethodSource("grantedScopes")
    void tokenCarriesTheRequestedScopesAndReachesTheResourceOnlyWithReadResource(String rawScope,
                                                                                List<String> scopes,
                                                                                String grantedScope,
                                                                                boolean resourceAccessible) {
        AuthorizationRequest request =
                service.validateAuthorizationRequest("code", CLIENT_ID, REDIRECT_URI, rawScope, null);
        assertThat(request.scope()).isEqualTo(rawScope);
        assertThat(request.scopes()).containsExactlyElementsOf(scopes);

        String code = codeIn(service.issueAuthorizationCode(request, OWNER), REDIRECT_URI + "?code=", "");
        IssuedToken issued = exchange(code);
        assertThat(issued.scope()).isEqualTo(grantedScope);

        String header = "Bearer " + issued.accessToken();
        if (resourceAccessible) {
            assertThat(service.validateAccessToken(header).scopes()).containsExactlyElementsOf(scopes);
        } else {
            assertError(expectError(() -> service.validateAccessToken(header)), 403, "insufficient_scope",
                    "Insufficient scope: READ_RESOURCE required", INSUFFICIENT_SCOPE_CHALLENGE);
        }
    }

    @ParameterizedTest(name = "grant types {0}: code flow allowed = {1}")
    @MethodSource
    void codeResponseTypeRequiresTheAuthorizationCodeGrant(List<String> grantTypes, boolean allowed) {
        service = service(properties(CLIENT_ID, CLIENT_SECRET, "CONFIDENTIAL", List.of("http://localhost*"),
                grantTypes));
        Executable validation =
                () -> service.validateAuthorizationRequest("code", CLIENT_ID, REDIRECT_URI, "READ_RESOURCE", null);

        if (allowed) {
            assertDoesNotThrow(validation);
        } else {
            assertError(expectError(validation), 400, "unsupported_response_type", "Unsupported response_type",
                    null);
        }
    }

    static Stream<Arguments> codeResponseTypeRequiresTheAuthorizationCodeGrant() {
        return Stream.of(
                Arguments.of(List.of("AUTHORIZATION_CODE"), true),
                Arguments.of(List.of("authorization_code"), true),
                Arguments.of(List.of("IMPLICIT", "AUTHORIZATION_CODE"), true),
                Arguments.of(List.of("IMPLICIT"), false),
                Arguments.of(List.of(), false));
    }

    // Token requests: grant type, client authentication, code, redirect URI

    static Stream<Arguments> rejectedTokenRequests() {
        String demoBasic = basic(CLIENT_ID + ":" + CLIENT_SECRET);
        return Stream.of(
                Arguments.of("grant_type missing", null, ISSUED_CODE, REDIRECT_URI, CLIENT_ID, CLIENT_SECRET, null,
                        400, "invalid_request", "Missing parameter: grant_type", null),
                Arguments.of("grant_type empty", "", ISSUED_CODE, REDIRECT_URI, CLIENT_ID, CLIENT_SECRET, null,
                        400, "invalid_request", "Missing parameter: grant_type", null),
                Arguments.of("grant_type blank", "  ", ISSUED_CODE, REDIRECT_URI, CLIENT_ID, CLIENT_SECRET, null,
                        400, "invalid_request", "Missing parameter: grant_type", null),
                Arguments.of("grant_type checked before the client", null, ISSUED_CODE, REDIRECT_URI, "otherclient",
                        "wrong-secret", null, 400, "invalid_request", "Missing parameter: grant_type", null),
                Arguments.of("grant_type password", "password", ISSUED_CODE, REDIRECT_URI, CLIENT_ID, CLIENT_SECRET,
                        null, 400, "unsupported_grant_type", "Unsupported grant_type", null),
                Arguments.of("grant_type differing in case", "AUTHORIZATION_CODE", ISSUED_CODE, REDIRECT_URI,
                        CLIENT_ID, CLIENT_SECRET, null, 400, "unsupported_grant_type", "Unsupported grant_type", null),
                Arguments.of("Basic header together with client_secret parameter", GRANT_TYPE, ISSUED_CODE,
                        REDIRECT_URI, CLIENT_ID, CLIENT_SECRET, demoBasic,
                        400, "invalid_request", "Multiple client authentication methods", null),
                Arguments.of("unknown client_id", GRANT_TYPE, ISSUED_CODE, REDIRECT_URI, "otherclient",
                        CLIENT_SECRET, null, 401, "invalid_client", "Invalid client credentials", null),
                Arguments.of("client_id differing in case", GRANT_TYPE, ISSUED_CODE, REDIRECT_URI, "MyClientId",
                        CLIENT_SECRET, null, 401, "invalid_client", "Invalid client credentials", null),
                Arguments.of("client_id missing", GRANT_TYPE, ISSUED_CODE, REDIRECT_URI, null, CLIENT_SECRET, null,
                        401, "invalid_client", "Invalid client credentials", null),
                Arguments.of("wrong client_secret", GRANT_TYPE, ISSUED_CODE, REDIRECT_URI, CLIENT_ID,
                        "wrong-secret", null, 401, "invalid_client", "Invalid client credentials", null),
                Arguments.of("client_secret missing", GRANT_TYPE, ISSUED_CODE, REDIRECT_URI, CLIENT_ID, null, null,
                        401, "invalid_client", "Invalid client credentials", null),
                Arguments.of("client_secret empty", GRANT_TYPE, ISSUED_CODE, REDIRECT_URI, CLIENT_ID, "", null,
                        401, "invalid_client", "Invalid client credentials", null),
                Arguments.of("Basic credentials not Base64", GRANT_TYPE, ISSUED_CODE, REDIRECT_URI, null, null,
                        "Basic !!!not-base64", 401, "invalid_client", "Invalid client credentials", BASIC_CHALLENGE),
                Arguments.of("Basic credentials without a colon", GRANT_TYPE, ISSUED_CODE, REDIRECT_URI, null, null,
                        basic(CLIENT_ID), 401, "invalid_client", "Invalid client credentials", BASIC_CHALLENGE),
                Arguments.of("Basic credentials empty", GRANT_TYPE, ISSUED_CODE, REDIRECT_URI, null, null,
                        "Basic ", 401, "invalid_client", "Invalid client credentials", BASIC_CHALLENGE),
                Arguments.of("Basic scheme without credentials", GRANT_TYPE, ISSUED_CODE, REDIRECT_URI, null, null,
                        "Basic", 401, "invalid_client", "Invalid client credentials", BASIC_CHALLENGE),
                Arguments.of("Basic with wrong secret", GRANT_TYPE, ISSUED_CODE, REDIRECT_URI, null, null,
                        basic(CLIENT_ID + ":wrong-secret"), 401, "invalid_client", "Invalid client credentials",
                        BASIC_CHALLENGE),
                Arguments.of("Basic with empty secret", GRANT_TYPE, ISSUED_CODE, REDIRECT_URI, null, null,
                        basic(CLIENT_ID + ":"), 401, "invalid_client", "Invalid client credentials", BASIC_CHALLENGE),
                Arguments.of("Basic with unknown client", GRANT_TYPE, ISSUED_CODE, REDIRECT_URI, null, null,
                        basic("otherclient:" + CLIENT_SECRET), 401, "invalid_client", "Invalid client credentials",
                        BASIC_CHALLENGE),
                Arguments.of("Basic client differing from client_id parameter", GRANT_TYPE, ISSUED_CODE,
                        REDIRECT_URI, "otherclient", null, demoBasic, 401, "invalid_client",
                        "Invalid client credentials", BASIC_CHALLENGE),
                Arguments.of("code missing", GRANT_TYPE, null, REDIRECT_URI, CLIENT_ID, CLIENT_SECRET, null,
                        400, "invalid_request", "Missing parameter: code", null),
                Arguments.of("code empty", GRANT_TYPE, "", REDIRECT_URI, CLIENT_ID, CLIENT_SECRET, null,
                        400, "invalid_request", "Missing parameter: code", null),
                Arguments.of("code blank", GRANT_TYPE, "  ", REDIRECT_URI, CLIENT_ID, CLIENT_SECRET, null,
                        400, "invalid_request", "Missing parameter: code", null),
                Arguments.of("client checked before the code", GRANT_TYPE, null, REDIRECT_URI, CLIENT_ID,
                        "wrong-secret", null, 401, "invalid_client", "Invalid client credentials", null),
                Arguments.of("code never issued", GRANT_TYPE, "not-a-code", REDIRECT_URI, CLIENT_ID, CLIENT_SECRET,
                        null, 400, "invalid_grant", "Invalid authorization code", null),
                Arguments.of("redirect_uri missing", GRANT_TYPE, ISSUED_CODE, null, CLIENT_ID, CLIENT_SECRET, null,
                        400, "invalid_grant", "redirect_uri mismatch", null),
                Arguments.of("redirect_uri empty", GRANT_TYPE, ISSUED_CODE, "", CLIENT_ID, CLIENT_SECRET, null,
                        400, "invalid_grant", "redirect_uri mismatch", null),
                Arguments.of("redirect_uri different from the authorization request", GRANT_TYPE, ISSUED_CODE,
                        "http://localhost:9082/other", CLIENT_ID, CLIENT_SECRET, null,
                        400, "invalid_grant", "redirect_uri mismatch", null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedTokenRequests")
    void invalidTokenRequestIsRejected(String scenario, String grantType, String code, String redirectUri,
                                       String clientId, String clientSecret, String authorization, int status,
                                       String error, String description, String wwwAuthenticate) {
        String presentedCode = ISSUED_CODE.equals(code) ? obtainCode("READ_RESOURCE") : code;

        assertError(expectError(() -> service.exchangeAuthorizationCode(grantType, presentedCode, redirectUri,
                clientId, clientSecret, authorization)), status, error, description, wwwAuthenticate);
    }

    static Stream<Arguments> acceptedClientAuthentications() {
        String credentials = Base64.getEncoder()
                .encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
        return Stream.of(
                Arguments.of("client_id and client_secret parameters", CLIENT_ID, CLIENT_SECRET, null),
                Arguments.of("Basic header, no parameters", null, null, "Basic " + credentials),
                Arguments.of("Basic header, empty parameters", "", "", "Basic " + credentials),
                Arguments.of("Basic header, matching client_id and blank client_secret", CLIENT_ID, "  ",
                        "Basic " + credentials),
                Arguments.of("lower-case basic scheme", null, null, "basic " + credentials),
                Arguments.of("upper-case scheme, padded credentials", null, null, "BASIC   " + credentials + "  "),
                Arguments.of("non-Basic header ignored, parameters used", CLIENT_ID, CLIENT_SECRET, "Bearer abc"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedClientAuthentications")
    void configuredClientIsAuthenticatedByParametersOrBasicHeader(String scenario, String clientId,
                                                                 String clientSecret, String authorization) {
        String code = obtainCode("READ_RESOURCE");

        IssuedToken issued = service.exchangeAuthorizationCode(GRANT_TYPE, code, REDIRECT_URI, clientId,
                clientSecret, authorization);

        assertThat(service.validateAccessToken("Bearer " + issued.accessToken()).clientId()).isEqualTo(CLIENT_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUBLIC", "public"})
    void publicClientIsAuthenticatedByItsIdAlone(String clientType) {
        service = service(properties(CLIENT_ID, "", clientType, List.of("http://localhost*"),
                List.of("AUTHORIZATION_CODE")));

        IssuedToken issued = service.exchangeAuthorizationCode(GRANT_TYPE, obtainCode("READ_RESOURCE"), REDIRECT_URI,
                CLIENT_ID, null, null);
        assertThat(service.validateAccessToken("Bearer " + issued.accessToken()).clientId()).isEqualTo(CLIENT_ID);

        assertError(expectError(() -> service.exchangeAuthorizationCode(GRANT_TYPE, obtainCode("READ_RESOURCE"),
                REDIRECT_URI, "otherclient", null, null)), 401, "invalid_client", "Invalid client credentials", null);
    }

    // Fail-closed while the configured client credentials are blank

    static Stream<Arguments> blankConfiguredClientIds() {
        return Stream.of(
                Arguments.of("", ""),
                Arguments.of("", null),
                Arguments.of("   ", "   "),
                Arguments.of("", CLIENT_ID));
    }

    @ParameterizedTest(name = "configured [{0}], submitted [{1}]")
    @MethodSource("blankConfiguredClientIds")
    void blankConfiguredClientIdRejectsEveryAuthorizationRequest(String configuredId, String submittedId) {
        service = service(properties(configuredId, CLIENT_SECRET));

        assertError(expectError(() -> service.validateAuthorizationRequest("code", submittedId, REDIRECT_URI,
                "READ_RESOURCE", null)), 400, "invalid_client", "Invalid client id", null);
    }

    static Stream<Arguments> blankConfiguredCredentials() {
        return Stream.of(
                Arguments.of("both blank, empty parameters", "", "", "", "", null, null),
                Arguments.of("both blank, no parameters", "", "", null, null, null, null),
                Arguments.of("both whitespace, same whitespace submitted", " ", " ", " ", " ", null, null),
                Arguments.of("both blank, Basic with empty credentials", "", "", null, null, basic(":"),
                        BASIC_CHALLENGE),
                Arguments.of("blank secret, empty secret submitted", CLIENT_ID, "", CLIENT_ID, "", null, null),
                Arguments.of("blank secret, any secret submitted", CLIENT_ID, "", CLIENT_ID, "anything", null, null),
                Arguments.of("whitespace secret, same whitespace submitted", CLIENT_ID, "  ", CLIENT_ID, "  ", null,
                        null),
                Arguments.of("blank secret, Basic with empty secret", CLIENT_ID, "", null, null,
                        basic(CLIENT_ID + ":"), BASIC_CHALLENGE),
                Arguments.of("blank client id, secret submitted", "", CLIENT_SECRET, "", CLIENT_SECRET, null, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("blankConfiguredCredentials")
    void blankConfiguredClientCredentialsRejectEveryTokenRequest(String scenario, String configuredId,
                                                                String configuredSecret, String clientId,
                                                                String clientSecret, String authorization,
                                                                String wwwAuthenticate) {
        service = service(properties(configuredId, configuredSecret));

        assertError(expectError(() -> service.exchangeAuthorizationCode(GRANT_TYPE, "any-code", REDIRECT_URI,
                clientId, clientSecret, authorization)), 401, "invalid_client", "Invalid client credentials",
                wwwAuthenticate);
    }

    // Bearer token validation

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Bearer", "Bearer ", "Bearer    ", "Bearerabc", "Basic abc", "Token abc", "abc"})
    void authorizationHeaderWithoutABearerTokenIsRejected(String authorization) {
        assertError(expectError(() -> service.validateAccessToken(authorization)), 401, "invalid_request",
                "Access token missing", BEARER_CHALLENGE);
    }

    @Test
    void bearerTokenNeverIssuedIsRejected() {
        String code = obtainCode("READ_RESOURCE");

        assertError(expectError(() -> service.validateAccessToken("Bearer unknown-token")), 401, "invalid_token",
                "Invalid or expired access token", INVALID_TOKEN_CHALLENGE);
        assertError(expectError(() -> service.validateAccessToken("Bearer " + code)), 401, "invalid_token",
                "Invalid or expired access token", INVALID_TOKEN_CHALLENGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer %s", "bearer %s", "BEARER %s", "Bearer   %s  "})
    void bearerSchemeIsCaseInsensitiveAndTheTokenIsTrimmed(String headerFormat) {
        String token = exchange(obtainCode("READ_RESOURCE")).accessToken();

        assertThat(service.validateAccessToken(String.format(headerFormat, token)).value()).isEqualTo(token);
    }

    // Output shapes

    @Test
    void issuedTokenJsonBodyIsTheFlatTokenResponse() throws Exception {
        IssuedToken issued = exchange(obtainCode("READ_RESOURCE"));

        Map<String, Object> body = issued.toJsonBody();

        assertThat(List.copyOf(body.keySet())).isEqualTo(List.of("access_token", "token_type", "expires_in", "scope"));
        assertThat(body.get("access_token")).isEqualTo(issued.accessToken());
        assertThat(body.get("token_type")).isEqualTo("bearer");
        assertThat(body.get("expires_in")).isEqualTo(86400L);
        assertThat(body.get("scope")).isEqualTo("READ_RESOURCE");
        assertThat(new ObjectMapper().writeValueAsString(body)).isEqualTo("{\"access_token\":\"" + issued.accessToken()
                + "\",\"token_type\":\"bearer\",\"expires_in\":86400,\"scope\":\"READ_RESOURCE\"}");
    }

    @Test
    void protectedResourceIsAFreshPayrollMapOnTheListenerPort() throws Exception {
        Map<String, String> resource = service.protectedResource();

        assertThat(new ObjectMapper().writeValueAsString(resource))
                .isEqualTo("{\"name\":\"payroll\",\"uri\":\"http://localhost:9082/resources/payroll\"}");

        resource.put("name", "changed");
        assertThat(service.protectedResource().get("name")).isEqualTo("payroll");
    }

    static Stream<Arguments> redirectQueries() {
        return Stream.of(
                Arguments.of("code=abc", "abc"),
                Arguments.of("code=ab%2Fcd%3D", "ab/cd="),
                Arguments.of("code=a+b", "a b"),
                Arguments.of("state=1&code=abc", "abc"),
                Arguments.of("code=first&code=second", "first"),
                Arguments.of("&&code=abc&&", "abc"),
                Arguments.of("c%6Fde=abc", "abc"),
                Arguments.of("code", ""),
                Arguments.of("code=", ""),
                Arguments.of(null, "null"),
                Arguments.of("", "null"),
                Arguments.of("state=1", "null"));
    }

    @ParameterizedTest(name = "query [{0}] -> code={1}")
    @MethodSource("redirectQueries")
    void redirectLocationCarriesTheFirstDecodedCode(String rawQuery, String code) {
        assertThat(service.redirectLocation(rawQuery)).isEqualTo(redirectLocationWithCode(code));
    }

    @Test
    void redirectLocationIsTheOriginalTemplateWithItsDoubleAmpersand() {
        assertThat(service.redirectLocation("code=abc")).isEqualTo("http://localhost:9081/token?"
                + "grant_type=authorization_code&&client_id=myclientid&client_secret=myclientsecret&code=abc"
                + "&redirect_uri=http://localhost:9082/redirect");
    }

    @ParameterizedTest
    @ValueSource(strings = {"code=%zz", "x=%G1&code=abc", "code=abc&x=%", "%=abc"})
    void redirectLocationRejectsAMalformedEscapeAnywhereInTheQuery(String rawQuery) {
        assertThatThrownBy(() -> service.redirectLocation(rawQuery)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void redirectLocationCarriesTheConfiguredClientCredentials() {
        service = service(properties("", ""));

        assertThat(service.redirectLocation("code=abc")).isEqualTo("http://localhost:9081/token?"
                + "grant_type=authorization_code&&client_id=&client_secret=&code=abc"
                + "&redirect_uri=http://localhost:9082/redirect");
    }

    @Test
    void oauthErrorCarriesStatusCodeDescriptionAndChallenge() throws Exception {
        OAuthError unauthorized = expectError(() -> service.validateAccessToken(null));

        assertError(unauthorized, 401, "invalid_request", "Access token missing", BEARER_CHALLENGE);
        assertThat(unauthorized.getMessage()).isEqualTo("Access token missing");
        assertThat(List.copyOf(unauthorized.toJsonBody().keySet())).isEqualTo(List.of("error", "error_description"));
        assertThat(new ObjectMapper().writeValueAsString(unauthorized.toJsonBody()))
                .isEqualTo("{\"error\":\"invalid_request\",\"error_description\":\"Access token missing\"}");

        OAuthError badRequest = expectError(() -> service.validateAuthorizationRequest("token", CLIENT_ID,
                REDIRECT_URI, "READ_RESOURCE", null));

        assertThat(badRequest.wwwAuthenticate()).isNull();
        assertThat(badRequest.toJsonBody()).isEqualTo(Map.of("error", "unsupported_response_type",
                "error_description", "Unsupported response_type"));
    }

    @Test
    void loginPageDetailsComeFromTheConfiguration() {
        assertThat(service.providerName()).isEqualTo("SampleAPI");
        assertThat(service.clientName()).isEqualTo("Mule Bookstore");
        assertThat(service.clientDescription()).isEqualTo("Mule-powered On-line Bookstore");
    }

    @Test
    void recordsHoldAnUnmodifiableCopyOfTheirScopes() {
        Set<String> scopes = new LinkedHashSet<>(List.of("READ_RESOURCE"));
        AuthorizationRequest request = new AuthorizationRequest("code", CLIENT_ID, REDIRECT_URI, "READ_RESOURCE",
                scopes, null);
        AccessToken token = new AccessToken("value", CLIENT_ID, OWNER, scopes, START);
        scopes.add("POST_RESOURCE");

        assertThat(request.scopes()).containsExactly("READ_RESOURCE");
        assertThat(token.scopes()).containsExactly("READ_RESOURCE");
        assertThatThrownBy(() -> request.scopes().add("POST_RESOURCE"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> token.scopes().add("POST_RESOURCE"))
                .isInstanceOf(UnsupportedOperationException.class);

        assertThat(new AuthorizationRequest("code", CLIENT_ID, REDIRECT_URI, null, null, null).scopes()).isEmpty();
        assertThat(new AccessToken("value", CLIENT_ID, OWNER, null, START).scopes()).isEmpty();
    }

    @Test
    void missingCollaboratorsAndRequestsAreRefused() {
        OAuthProviderProperties props = properties(CLIENT_ID, CLIENT_SECRET);
        SplittableRandom random = new SplittableRandom(SEED);

        assertThatNullPointerException()
                .isThrownBy(() -> new OAuthProviderService(null, PROVIDER_PORT, LISTENER_PORT, clock, random))
                .withMessage("props");
        assertThatNullPointerException()
                .isThrownBy(() -> new OAuthProviderService(props, PROVIDER_PORT, LISTENER_PORT, null, random))
                .withMessage("clock");
        assertThatNullPointerException()
                .isThrownBy(() -> new OAuthProviderService(props, PROVIDER_PORT, LISTENER_PORT, clock, null))
                .withMessage("random");
        assertThatNullPointerException()
                .isThrownBy(() -> service.issueAuthorizationCode(null, OWNER))
                .withMessage("request");
    }

    @Test
    void springConstructorUsesTheConfiguredPorts() {
        OAuthProviderService wired = new OAuthProviderService(properties(CLIENT_ID, CLIENT_SECRET), PROVIDER_PORT,
                LISTENER_PORT);

        assertThat(wired.redirectLocation("code=abc")).isEqualTo(redirectLocationWithCode("abc"));
        assertThat(wired.protectedResource().get("uri")).isEqualTo("http://localhost:9082/resources/payroll");
    }

    /**
     * A clock that stands still until advanced, starting at {@link #START} in UTC. Zone-shifted views share
     * its instant, so advancing one advances all.
     */
    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> now;
        private final ZoneId zone;

        MutableClock() {
            this(new AtomicReference<>(START), ZoneOffset.UTC);
        }

        private MutableClock(AtomicReference<Instant> now, ZoneId zone) {
            this.now = now;
            this.zone = zone;
        }

        void advance(Duration amount) {
            now.updateAndGet(instant -> instant.plus(amount));
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId newZone) {
            return zone.equals(newZone) ? this : new MutableClock(now, newZone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
