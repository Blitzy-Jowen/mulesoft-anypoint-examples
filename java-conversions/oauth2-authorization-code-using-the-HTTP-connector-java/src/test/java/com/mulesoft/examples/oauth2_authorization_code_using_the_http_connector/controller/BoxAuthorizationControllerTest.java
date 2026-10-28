/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.config.BoxProperties;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.service.BoxOAuthService;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.service.BoxOAuthService.TokenSet;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.support.HttpTestClient;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.support.PortInUseRetryingContextLoader;

/**
 * Wire-level test of {@link BoxAuthorizationController}: the {@code /authorization} and {@code /redirectUrl}
 * endpoints the Mule 3.8 OAuth module generated from {@code oauth2:authorization-code-grant-type} in
 * {@code oauth2-authorization-code-using-the-HTTP-connector/src/main/app/http-authorization-code-web.xml}
 * (lines 6-18), served on the HTTPS listener behind {@code localAuthorizationUrl} and {@code redirectionUrl} with
 * the {@code TLS_Context} of lines 21-24.
 *
 * <p>The whole application runs: the controller, {@code BoxOAuthService}, {@code BoxApiClient}, SC-10's HTTPS
 * listener with its {@code PortRoutingFilter}, and SC-1. The main listener takes a random port; the HTTPS listener
 * takes a free port drawn for each load of the context, because SC-10 identifies it by its concrete local port, and
 * {@link PortInUseRetryingContextLoader} loads the context again on a newly drawn port when another process binds
 * the drawn one first. Box's authorization and token endpoints are a WireMock stub, so nothing leaves the loopback
 * interface. Requests go through ST-1 {@link HttpTestClient} in its TLS mode with the carried stores, presenting the
 * client key the listener requires, so status line, headers and body bytes are asserted as the listener writes them.
 *
 * <p>A successful callback stores tokens in the singleton service, so the methods that store tokens dirty the
 * context after they run; every other method therefore starts without tokens and asserts that none were stored.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(loader = PortInUseRetryingContextLoader.class)
@ActiveProfiles("test")
class BoxAuthorizationControllerTest {

    @RegisterExtension
    static WireMockExtension box = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    /** The carried {@code TLS_Context} stores as copied to {@code target/classes}, the files the listener reads. */
    private static final String TRUST_STORE_PATH = classpathFile("/trust-store");
    private static final String KEY_STORE_PATH = classpathFile("/keystore.jks");
    private static final String STORE_TYPE = "JKS";

    /** The {@code onCompleteRedirectTo} of {@code boxUserLoginFlow}'s {@code Location} (line 30), decoded. */
    private static final String ON_COMPLETE = "http://localhost:8081/web/loginDone";

    /** {@code :onCompleteRedirectTo=<ON_COMPLETE>} as {@code StateEncoder} puts it into the {@code state} parameter. */
    private static final String ENC_STATE =
            "%3AonCompleteRedirectTo%3Dhttp%3A%2F%2Flocalhost%3A8081%2Fweb%2FloginDone";

    /** {@code box.id} and {@code box.secret} of {@code application-test.yml}. */
    private static final String CLIENT_ID = "test-box-client-id";
    private static final String CLIENT_SECRET = "test-box-client-secret";

    private static final String AUTHORIZE_PATH = "/api/oauth2/authorize";
    private static final String TOKEN_PATH = "/api/oauth2/token";

    private static final String TOKEN_RESPONSE =
            "{\"access_token\":\"at-1\",\"refresh_token\":\"rt-1\",\"expires_in\":3600,\"token_type\":\"bearer\"}";
    private static final String INVALID_GRANT_RESPONSE = "{\"error\":\"invalid_grant\","
            + "\"error_description\":\"Auth code doesn't exist or is invalid for the client\"}";

    /** The complete Content-Type of the no-listener 404, with no charset parameter. */
    private static final String NO_LISTENER_CONTENT_TYPE = "text/plain";

    @Autowired
    BoxProperties props;

    @Autowired
    BoxOAuthService oauth;

    @LocalServerPort
    int mainPort;

    /** Local port of the HTTPS listener; SC-10 keys the listener's routes by it, so it cannot be 0. */
    @Value("${ports.box-oauth}")
    int httpsPort;

    @Value("${truststore.password}")
    String trustStorePassword;

    @Value("${keystore.password}")
    String keyStorePassword;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        // Drawn for each load attempt: Spring invokes this method again when the loader retries a lost port.
        int drawnHttpsPort = freePort();
        // ports.box-oauth rather than listeners.additional[0].port: setting one element of that list in a
        // higher-priority source replaces the whole list and drops the listener's ssl and routes (application.yml).
        r.add("ports.box-oauth", () -> drawnHttpsPort);
        r.add("box.authorization-url", () -> box.baseUrl() + AUTHORIZE_PATH);
        r.add("box.token-url", () -> box.baseUrl() + TOKEN_PATH);
        // Not called by these endpoints; pointed at the stub so that no request can reach api.box.com.
        r.add("box.api-base-url", () -> box.baseUrl() + "/2.0");
    }

    // ---------------------------------------------------------------------------------------------------------
    // /authorization
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void authorizationWithOnCompleteRedirectToRedirectsToBoxWithState() {
        // Unencoded, exactly as boxUserLoginFlow's Location sends the browser here.
        HttpTestClient.Response raw = tls().get("/authorization?onCompleteRedirectTo=" + ON_COMPLETE);
        HttpTestClient.Response encoded = tls().get("/authorization?onCompleteRedirectTo=" + enc(ON_COMPLETE));

        assertRedirect(raw, expectedAuthorizeLocation(true));
        assertRedirect(encoded, expectedAuthorizeLocation(true));
        // Box's authorization URL is only redirected to, never called by the application.
        assertThat(box.getAllServeEvents()).isEmpty();
        assertNoTokensStored();
    }

    @Test
    void authorizationWithoutParameterOmitsState() {
        HttpTestClient.Response response = tls().get("/authorization");

        assertRedirect(response, expectedAuthorizeLocation(false));
        // The redirect_uri sent to Box names the HTTPS listener that serves /redirectUrl, as redirectionUrl did.
        assertThat(props.getRedirectUrl()).isEqualTo("https://localhost:" + httpsPort + "/redirectUrl");
        assertThat(box.getAllServeEvents()).isEmpty();
    }

    @Test
    void authorizationQueryParsingFollowsMuleParameterMap() {
        HttpTestClient.Response repeated = tls().get("/authorization?onCompleteRedirectTo=http://first.example/ignored"
                + "&onCompleteRedirectTo=" + ON_COMPLETE);
        HttpTestClient.Response withoutValue = tls().get("/authorization?onCompleteRedirectTo");
        HttpTestClient.Response emptyValue = tls().get("/authorization?onCompleteRedirectTo=");

        // ParameterMap.get yields the last occurrence; a name without '=' has a null value.
        assertRedirect(repeated, expectedAuthorizeLocation(true));
        assertRedirect(withoutValue, expectedAuthorizeLocation(false));
        // An empty URL carries no state either, as StateEncoder skips an empty onCompleteRedirectTo.
        assertRedirect(emptyValue, expectedAuthorizeLocation(false));
    }

    @Test
    void authorizationAcceptsEveryMethod() {
        // The listener declares no allowedMethods.
        HttpTestClient.Response response = tls().post("/authorization", null, "");

        assertRedirect(response, expectedAuthorizeLocation(false));
    }

    // ---------------------------------------------------------------------------------------------------------
    // /redirectUrl with an onCompleteRedirectTo state: 302 to it with the authorizationStatus marker
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void redirectWithStateAndCodeExchangesTokenAndReportsStatus0() {
        stubTokenOk(TOKEN_RESPONSE);

        HttpTestClient.Response response = tls().get("/redirectUrl?code=auth-code-1&state=" + ENC_STATE);

        assertRedirect(response, ON_COMPLETE + "?authorizationStatus=0");

        List<LoggedRequest> tokenRequests = tokenRequests();
        assertThat(tokenRequests).hasSize(1);
        LoggedRequest tokenRequest = tokenRequests.get(0);
        assertThat(tokenRequest.getHeader("Content-Type")).startsWith("application/x-www-form-urlencoded");
        // Field order is the one Mule 3.8's token request form had.
        assertThat(decodedFormFields(tokenRequest.getBodyAsString())).containsExactly(
                "code=auth-code-1",
                "grant_type=authorization_code",
                "client_secret=" + CLIENT_SECRET,
                "redirect_uri=" + props.getRedirectUrl(),
                "client_id=" + CLIENT_ID);

        assertThat(oauth.tokens(BoxOAuthService.DEFAULT_RESOURCE_OWNER_ID)).hasValueSatisfying(tokens -> {
            assertThat(tokens.accessToken()).isEqualTo("at-1");
            assertThat(tokens.refreshToken()).isEqualTo("rt-1");
            assertThat(tokens.expiresIn()).isEqualTo("3600");
            // The custom extractor token_type = #[json:token_type] (line 15).
            assertThat(tokens.tokenType()).isEqualTo("bearer");
        });
    }

    @Test
    void redirectWithStateWithoutCodeReportsStatus100() {
        HttpTestClient.Response noCode = tls().get("/redirectUrl?state=" + ENC_STATE);
        // 'code' without '=' is null under ParameterMap, so it counts as no code.
        HttpTestClient.Response codeWithoutValue = tls().get("/redirectUrl?code&state=" + ENC_STATE);

        assertRedirect(noCode, ON_COMPLETE + "?authorizationStatus=100");
        assertRedirect(codeWithoutValue, ON_COMPLETE + "?authorizationStatus=100");
        assertThat(tokenRequests()).isEmpty();
        assertNoTokensStored();
    }

    @Test
    void redirectWithStateTokenEndpointErrorReportsStatus200() {
        stubToken(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"server_error\"}"));

        HttpTestClient.Response response = tls().get("/redirectUrl?code=auth-code-1&state=" + ENC_STATE);

        assertRedirect(response, ON_COMPLETE + "?authorizationStatus=200");
        assertThat(tokenRequests()).hasSize(1);
        assertNoTokensStored();
    }

    @Test
    void redirectWithStateTokenConnectionFailureReportsStatus200() {
        stubToken(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER));

        HttpTestClient.Response response = tls().get("/redirectUrl?code=auth-code-1&state=" + ENC_STATE);

        assertRedirect(response, ON_COMPLETE + "?authorizationStatus=200");
        // A failed token call is never repeated.
        assertThat(tokenRequests()).hasSize(1);
        assertNoTokensStored();
    }

    @Test
    void redirectWithStateTokenWithoutAccessTokenReportsStatus201() {
        stubTokenOk(INVALID_GRANT_RESPONSE);

        HttpTestClient.Response response = tls().get("/redirectUrl?code=auth-code-1&state=" + ENC_STATE);

        assertRedirect(response, ON_COMPLETE + "?authorizationStatus=201");
        assertThat(tokenRequests()).hasSize(1);
        assertNoTokensStored();
    }

    @Test
    void redirectWithStatePrettyPrintedTokenJsonReportsStatus201() {
        // Mule's default access-token extractor regex has no DOTALL flag, so it never matches a multi-line body.
        stubTokenOk("{\n  \"access_token\": \"at-2\",\n  \"refresh_token\": \"rt-2\",\n"
                + "  \"token_type\": \"bearer\"\n}");

        HttpTestClient.Response response = tls().get("/redirectUrl?code=auth-code-1&state=" + ENC_STATE);

        assertRedirect(response, ON_COMPLETE + "?authorizationStatus=201");
        assertNoTokensStored();
    }

    // ---------------------------------------------------------------------------------------------------------
    // /redirectUrl without a state: the platform texts, with no Content-Type
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void redirectWithoutStateSuccessReturns200() {
        stubTokenOk(TOKEN_RESPONSE);

        HttpTestClient.Response response = tls().get("/redirectUrl?code=auth-code-2");

        assertText(response, "HTTP/1.1 200 OK", "Successfully retrieved access token");
        assertThat(tokenRequests()).hasSize(1);
        assertThat(oauth.tokens(BoxOAuthService.DEFAULT_RESOURCE_OWNER_ID))
                .map(TokenSet::accessToken)
                .hasValue("at-1");
    }

    @Test
    void redirectWithoutStateWithoutCodeReturns400() {
        HttpTestClient.Response response = tls().get("/redirectUrl?error=access_denied");

        // The text quotes the path and the raw query of the callback.
        assertText(response, "HTTP/1.1 400 Bad Request",
                "Failure retrieving access token.\n OAuth Server uri from callback: /redirectUrl?error=access_denied");
        assertThat(tokenRequests()).isEmpty();
        assertNoTokensStored();
    }

    @Test
    void redirectWithoutStateTokenCallFailureReturns500() {
        stubToken(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"server_error\"}"));

        HttpTestClient.Response response = tls().get("/redirectUrl?code=auth-code-2");

        assertText(response, "HTTP/1.1 500 Internal Server Error",
                "Failure calling token url " + props.getTokenUrl() + ". Exception message is null");
        assertNoTokensStored();
    }

    @Test
    void redirectWithoutStateTokenNotFoundReturns500() {
        stubTokenOk(INVALID_GRANT_RESPONSE);

        HttpTestClient.Response response = tls().get("/redirectUrl?code=auth-code-2");

        assertText(response, "HTTP/1.1 500 Internal Server Error",
                "Failed getting access token or refresh token from token URL response. See logs for details.");
        assertNoTokensStored();
    }

    // ---------------------------------------------------------------------------------------------------------
    // SC-10 listeners
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void httpsListenerRequiresClientCertificate() {
        HttpTestClient withoutClientKey =
                HttpTestClient.https("localhost", httpsPort, TRUST_STORE_PATH, STORE_TYPE, trustStorePassword);

        // Under TLS 1.3 the server's certificate_required alert may surface on the first read rather than in the
        // handshake, and a reset may arrive instead of the alert; ST-1 wraps each of them.
        assertThatThrownBy(() -> withoutClientKey.get("/authorization"))
                .isInstanceOf(UncheckedIOException.class)
                .cause()
                .isInstanceOfAny(SSLException.class, SocketException.class);
        // The same request presenting the client key is served, so the certificate alone decided the rejection.
        assertRedirect(tls().get("/authorization"), expectedAuthorizeLocation(false));
    }

    @Test
    void webPathsOnHttpsPortReturnNoListener404() {
        for (String path : List.of("/web", "/web/loginDone", "/unknown")) {
            assertNoListener(tls().get(path), path);
        }
        assertThat(box.getAllServeEvents()).isEmpty();
    }

    @Test
    void oauthPathsOnMainPortReturnNoListener404() {
        HttpTestClient main = HttpTestClient.localhost(mainPort);

        assertNoListener(main.get("/authorization?onCompleteRedirectTo=" + ON_COMPLETE), "/authorization");
        assertNoListener(main.get("/redirectUrl?code=auth-code-1&state=" + ENC_STATE), "/redirectUrl");
        // A callback on the wrong listener exchanges no code.
        assertThat(box.getAllServeEvents()).isEmpty();
        assertNoTokensStored();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    /** ST-1 client for the HTTPS listener, trusting only the carried trust store and presenting its client key. */
    private HttpTestClient tls() {
        return HttpTestClient.https("localhost", httpsPort, TRUST_STORE_PATH, STORE_TYPE, trustStorePassword,
                KEY_STORE_PATH, STORE_TYPE, keyStorePassword);
    }

    /**
     * Box's authorization URL with the query {@code AuthorizationRequestUrlBuilder} builds, in its order:
     * {@code response_type, client_id, [state,] box_device_id, box_device_name, redirect_uri}.
     */
    private String expectedAuthorizeLocation(boolean withState) {
        return props.getAuthorizationUrl() + "?response_type=code&client_id=" + CLIENT_ID
                + (withState ? "&state=" + ENC_STATE : "")
                + "&box_device_id=1&box_device_name=device1&redirect_uri=" + enc(props.getRedirectUrl());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static void stubTokenOk(String json) {
        stubToken(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(json));
    }

    private static void stubToken(ResponseDefinitionBuilder response) {
        box.stubFor(post(urlPathEqualTo(TOKEN_PATH)).willReturn(response));
    }

    private static List<LoggedRequest> tokenRequests() {
        return box.findAll(postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
    }

    /** Each {@code name=value} pair of a form body in sending order, its value URL-decoded. */
    private static List<String> decodedFormFields(String body) {
        List<String> fields = new ArrayList<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            assertThat(eq).as("form field %s has no '='", pair).isPositive();
            fields.add(pair.substring(0, eq) + "=" + URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return fields;
    }

    private void assertNoTokensStored() {
        assertThat(oauth.tokens(BoxOAuthService.DEFAULT_RESOURCE_OWNER_ID)).isEmpty();
    }

    /** A redirect reply: 302 with exactly {@code location}, no {@code Content-Type} and an empty body. */
    private static void assertRedirect(HttpTestClient.Response response, String location) {
        assertThat(response.statusLine()).as(response::toString).isEqualTo("HTTP/1.1 302 Found");
        assertThat(response.header("Location")).as(response::toString).isEqualTo(location);
        assertThat(response.header("Content-Type")).as(response::toString).isNull();
        assertThat(response.body()).as(response::toString).isEmpty();
    }

    /** A platform text reply: status line and UTF-8 body, with neither {@code Location} nor {@code Content-Type}. */
    private static void assertText(HttpTestClient.Response response, String statusLine, String body) {
        assertThat(response.statusLine()).as(response::toString).isEqualTo(statusLine);
        assertThat(response.header("Location")).as(response::toString).isNull();
        assertThat(response.header("Content-Type")).as(response::toString).isNull();
        assertThat(response.bodyAsString()).as(response::toString).isEqualTo(body);
    }

    /**
     * SC-10's no-listener answer for {@code path}, written by {@code PortRoutingFilter}: 404, exactly one
     * {@code Content-Type} header equal to the bare {@link #NO_LISTENER_CONTENT_TYPE}, and the listener's text.
     */
    private static void assertNoListener(HttpTestClient.Response response, String path) {
        assertThat(response.statusLine()).as(response::toString).isEqualTo("HTTP/1.1 404 Not Found");
        assertThat(response.headers("Content-Type")).as(response::toString)
                .containsExactly(NO_LISTENER_CONTENT_TYPE);
        assertThat(response.bodyAsString()).as(response::toString).isEqualTo("No listener for endpoint: " + path);
    }

    /**
     * A port free when drawn; the probe socket is closed again before the context binds it, so another process can
     * take it in between, which {@link PortInUseRetryingContextLoader} answers by loading the context again.
     */
    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("No free port for the HTTPS listener", e);
        }
    }

    /** The file-system path of a classpath resource, which ST-1 opens as a file. */
    private static String classpathFile(String resource) {
        URL url = BoxAuthorizationControllerTest.class.getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Carried store " + resource + " is not on the test classpath");
        }
        try {
            return Path.of(url.toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Carried store " + resource + " has no file-system path: " + url, e);
        }
    }
}
