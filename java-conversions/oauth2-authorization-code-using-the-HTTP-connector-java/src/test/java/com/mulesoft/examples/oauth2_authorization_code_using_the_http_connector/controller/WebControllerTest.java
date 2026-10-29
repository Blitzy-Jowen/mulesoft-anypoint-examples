/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.config.BoxProperties;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.service.BoxOAuthService;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.support.HttpTestClient;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.support.PortInUseRetryingContextLoader;

/**
 * Wire-level test of {@link WebController}, the main listener {@code HTTP_Listener_Configuration} of
 * {@code oauth2-authorization-code-using-the-HTTP-connector/src/main/app/http-authorization-code-web.xml}:
 * <ul>
 *   <li>{@code /web}, flow {@code boxUserLoginFlow} (lines 27-35): 302 to the local authorization URL, the request
 *       body echoed, every method accepted because the listener declares no {@code allowedMethods};</li>
 *   <li>{@code /web/loginDone}, flow {@code userLoginDoneFlow} (lines 37-48): {@code GET /2.0/search?query=mule} on
 *       Box with the default resource owner's access token, wrapped in the {@code set-payload} text of line 47.</li>
 * </ul>
 *
 * <p>The whole application runs on Undertow: the real controller, {@link BoxOAuthService}, the Box client, SC-1
 * {@code GlobalExceptionHandler} and SC-10 {@code ListenerPorts} with its {@code PortRoutingFilter}. Box is a
 * WireMock stub on a dynamic port, so no credential, external network or Mule runtime is needed. The main listener
 * takes a random port; the HTTPS listener takes a free port drawn for each load of the context, because SC-10
 * identifies it by its concrete local port, and {@link PortInUseRetryingContextLoader} loads the context again on a
 * newly drawn port when another process binds the drawn one first. Requests go through ST-1 {@link HttpTestClient},
 * so status lines with their reason phrases, headers and body bytes are asserted as Undertow writes them.
 *
 * <p>The token store is a field of the singleton {@link BoxOAuthService}, which outlives a test method in Spring's
 * context cache; every method that stores tokens therefore dirties the context after it runs, and the method that
 * needs an empty store runs first and also passes alone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(loader = PortInUseRetryingContextLoader.class)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebControllerTest {

    /** The {@code set-payload} text of {@code userLoginDoneFlow} (line 47) before {@code #[payload]}. */
    private static final String PREFIX =
            "The user successfully authorized access to the resource. Searching for files containing \"mule\": ";

    /** SC-1's 500 body for the grant's missing-token failure, in APIkit spacing. */
    private static final String NO_TOKEN_BODY = "{ \"message\": \"No access token for the default user. Verify that "
            + "you have authenticated the user before trying to execute an operation to the API.\" }";

    /** The {@code Location} header of {@code boxUserLoginFlow}'s response builder (line 30). */
    private static final String ORIGINAL_LOGIN_LOCATION =
            "https://localhost:8082/authorization?onCompleteRedirectTo=http://localhost:8081/web/loginDone";

    private static final String SEARCH_PATH = "/2.0/search";
    private static final String TOKEN_PATH = "/api/oauth2/token";

    /** The complete Content-Type of SC-1's 500 and of the echoed JSON request, with no charset parameter. */
    private static final String JSON = "application/json";

    /** The complete Content-Type of the no-listener 404, with no charset parameter. */
    private static final String TEXT_PLAIN = "text/plain";

    /** The Box OAuth client of {@code application-test.yml}. */
    private static final String TEST_CLIENT_ID = "test-box-client-id";
    private static final String TEST_CLIENT_SECRET = "test-box-client-secret";

    @RegisterExtension
    static WireMockExtension box = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @LocalServerPort
    int port;

    @Autowired
    BoxOAuthService oauth;

    @Autowired
    BoxProperties props;

    /**
     * Points the Box client at the WireMock stub and the HTTPS {@code box-oauth} listener at a free port, never the
     * original 8082, drawn for this load attempt: Spring invokes this method again when the loader retries a lost
     * port. The suppliers run when the context resolves them, after the static WireMock extension has started.
     *
     * <p>The HTTPS port is set through {@code ports.box-oauth}, the scalar the {@code listeners.additional} entry
     * reads: overriding {@code listeners.additional[0].port} itself would replace the whole list from
     * {@code application.yml} and drop the listener's TLS settings and routes.
     *
     * <p>{@code box.login-redirect-location} is pinned to the flow's literal: its {@code application.yml} default is
     * derived from {@code server.port}, which a random-port test sets to 0, so without the override the {@code /web}
     * redirect would point at port 0 instead of the location the original answered with.
     */
    @DynamicPropertySource
    static void boxProperties(DynamicPropertyRegistry r) {
        int drawnHttpsPort = freePort();
        r.add("ports.box-oauth", () -> drawnHttpsPort);
        r.add("box.api-base-url", () -> box.baseUrl() + "/2.0");
        r.add("box.token-url", () -> box.baseUrl() + TOKEN_PATH);
        r.add("box.login-redirect-location", () -> ORIGINAL_LOGIN_LOCATION);
    }

    private HttpTestClient client() {
        return HttpTestClient.localhost(port);
    }

    private void seedDefaultTokens(String accessToken) {
        oauth.storeTokens(BoxOAuthService.DEFAULT_RESOURCE_OWNER_ID,
                new BoxOAuthService.TokenSet(accessToken, "refresh-1", "3600", "bearer"));
    }

    @Test
    void webGetRedirectsToLocalAuthorization() {
        HttpTestClient.Response response = client().get("/web");

        assertThat(response.statusLine()).as(response.toString()).isEqualTo("HTTP/1.1 302 Found");
        assertThat(response.header("Location"))
                .isEqualTo(props.getLoginRedirectLocation())
                .isEqualTo(ORIGINAL_LOGIN_LOCATION);
        assertThat(response.header("Content-Type")).isNull();
        assertThat(response.body()).isEmpty();
    }

    @Test
    void webPostEchoesBodyAndContentType() {
        byte[] sent = "{\"user\":\"mule\"}".getBytes(StandardCharsets.UTF_8);

        HttpTestClient.Response post = client().send("POST", "/web", Map.of("Content-Type", JSON), sent);

        assertThat(post.statusLine()).as(post.toString()).isEqualTo("HTTP/1.1 302 Found");
        assertThat(post.header("Location")).isEqualTo(ORIGINAL_LOGIN_LOCATION);
        assertThat(post.headers("Content-Type")).as(post.toString()).containsExactly(JSON);
        assertThat(post.body()).isEqualTo(sent);

        HttpTestClient.Response delete = client().delete("/web");

        assertThat(delete.statusLine()).as(delete.toString()).isEqualTo("HTTP/1.1 302 Found");
        assertThat(delete.header("Location")).isEqualTo(ORIGINAL_LOGIN_LOCATION);
        assertThat(delete.body()).isEmpty();

        HttpTestClient.Response put = client().send("PUT", "/web", null, null);

        assertThat(put.statusLine()).as(put.toString()).isEqualTo("HTTP/1.1 302 Found");
        assertThat(put.header("Location")).isEqualTo(ORIGINAL_LOGIN_LOCATION);
        assertThat(put.body()).isEmpty();
    }

    @Test
    @Order(1)
    void loginDoneWithoutTokenReturns500() {
        assertThat(oauth.tokens(BoxOAuthService.DEFAULT_RESOURCE_OWNER_ID)).isEmpty();

        HttpTestClient.Response response = client().get("/web/loginDone");

        assertThat(response.statusLine()).as(response.toString()).isEqualTo("HTTP/1.1 500 Internal Server Error");
        assertThat(response.headers("Content-Type")).as(response.toString()).containsExactly(JSON);
        assertThat(response.bodyAsString()).isEqualTo(NO_TOKEN_BODY);
        box.verify(0, getRequestedFor(urlPathEqualTo(SEARCH_PATH)));
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void loginDoneSearchesBoxWithStoredToken() {
        String stubBody = "{\"total_count\":1,\"entries\":[{\"type\":\"file\",\"name\":\"mule.txt\"}]}";
        seedDefaultTokens("access-1");
        box.stubFor(get(urlPathEqualTo(SEARCH_PATH))
                .withQueryParam("query", equalTo("mule"))
                .withHeader("Authorization", equalTo("Bearer access-1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", JSON)
                        .withBody(stubBody)));

        HttpTestClient.Response response = client().get("/web/loginDone");

        assertThat(response.statusLine()).as(response.toString()).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.header("Content-Type")).isNull();
        assertThat(response.bodyAsString()).isEqualTo(PREFIX + stubBody);
        box.verify(1, getRequestedFor(urlPathEqualTo(SEARCH_PATH)));
        box.verify(1, getRequestedFor(urlPathEqualTo(SEARCH_PATH))
                .withQueryParam("query", equalTo("mule"))
                .withHeader("Authorization", equalTo("Bearer access-1")));
        List<LoggedRequest> searches = box.findAll(getRequestedFor(urlPathEqualTo(SEARCH_PATH)));
        assertThat(searches).singleElement()
                .satisfies(search -> assertThat(search.getUrl()).isEqualTo(SEARCH_PATH + "?query=mule"));
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void loginDoneMapsBoxErrorStatusTo500() {
        seedDefaultTokens("access-1");
        box.stubFor(get(urlPathEqualTo(SEARCH_PATH))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", JSON)
                        .withBody("{\"type\":\"error\"}")));

        HttpTestClient.Response response = client().get("/web/loginDone");

        assertThat(response.statusLine()).as(response.toString()).isEqualTo("HTTP/1.1 500 Internal Server Error");
        assertThat(response.headers("Content-Type")).as(response.toString()).containsExactly(JSON);
        assertThat(response.bodyAsString()).isEqualTo("{ \"message\": \"Response code 404 mapped as failure.\" }");
        box.verify(1, getRequestedFor(urlPathEqualTo(SEARCH_PATH)));
        box.verify(0, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void loginDoneUpstreamConnectionFailureReturns500() {
        seedDefaultTokens("access-1");
        box.stubFor(get(urlPathEqualTo(SEARCH_PATH))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        HttpTestClient.Response response = client().get("/web/loginDone");

        assertThat(response.statusLine()).as(response.toString()).isEqualTo("HTTP/1.1 500 Internal Server Error");
        assertThat(response.headers("Content-Type")).as(response.toString()).containsExactly(JSON);
        assertThat(response.bodyAsString()).startsWith("{ \"message\": ").endsWith(" }");
        box.verify(1, getRequestedFor(urlPathEqualTo(SEARCH_PATH)));
        box.verify(0, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void loginDoneRefreshesTokenOn401AndRetries() {
        seedDefaultTokens("expired-token");
        box.stubFor(get(urlPathEqualTo(SEARCH_PATH))
                .inScenario("refresh")
                .whenScenarioStateIs(Scenario.STARTED)
                .withHeader("Authorization", equalTo("Bearer expired-token"))
                .willReturn(aResponse().withStatus(401)));
        box.stubFor(post(urlPathEqualTo(TOKEN_PATH))
                .inScenario("refresh")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", JSON)
                        .withBody("{\"access_token\":\"fresh-token\",\"refresh_token\":\"refresh-2\","
                                + "\"expires_in\":3600,\"token_type\":\"bearer\"}"))
                .willSetStateTo("refreshed"));
        box.stubFor(get(urlPathEqualTo(SEARCH_PATH))
                .inScenario("refresh")
                .whenScenarioStateIs("refreshed")
                .withHeader("Authorization", equalTo("Bearer fresh-token"))
                .willReturn(aResponse().withStatus(200).withBody("found")));

        HttpTestClient.Response response = client().get("/web/loginDone");

        assertThat(response.statusLine()).as(response.toString()).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.bodyAsString()).isEqualTo(PREFIX + "found");

        List<LoggedRequest> tokenRequests = box.findAll(postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
        assertThat(tokenRequests).hasSize(1);
        Map<String, String> form = formFields(tokenRequests.get(0).getBodyAsString());
        assertThat(form.keySet())
                .containsExactly("refresh_token", "grant_type", "client_secret", "redirect_uri", "client_id");
        assertThat(form)
                .containsEntry("refresh_token", "refresh-1")
                .containsEntry("grant_type", "refresh_token")
                .containsEntry("client_secret", TEST_CLIENT_SECRET)
                .containsEntry("redirect_uri", props.getRedirectUrl())
                .containsEntry("client_id", TEST_CLIENT_ID);

        assertThat(oauth.tokens(BoxOAuthService.DEFAULT_RESOURCE_OWNER_ID))
                .hasValueSatisfying(tokens -> assertThat(tokens.accessToken()).isEqualTo("fresh-token"));
        box.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
        box.verify(2, getRequestedFor(urlPathEqualTo(SEARCH_PATH)));
        box.verify(1, getRequestedFor(urlPathEqualTo(SEARCH_PATH))
                .withHeader("Authorization", equalTo("Bearer expired-token")));
        box.verify(1, getRequestedFor(urlPathEqualTo(SEARCH_PATH))
                .withHeader("Authorization", equalTo("Bearer fresh-token")));
    }

    @Test
    void optionsOnWebReachesController() {
        HttpTestClient.Response response = client().send("OPTIONS", "/web", Map.of(), null);

        assertThat(response.statusLine()).as(response.toString()).isEqualTo("HTTP/1.1 302 Found");
        assertThat(response.header("Location")).isEqualTo(ORIGINAL_LOGIN_LOCATION);
        assertThat(response.header("Allow")).isNull();
        assertThat(response.body()).isEmpty();
    }

    @Test
    void unknownPathOnMainPortReturnsNoListener404() {
        HttpTestClient.Response response = client().get("/unknown");

        assertNoListener(response, "/unknown");
    }

    @Test
    void oauthPathsOnMainPortReturnNoListener404() {
        for (String path : List.of("/authorization", "/redirectUrl")) {
            assertNoListener(client().get(path), path);
        }
        box.verify(0, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
    }

    /**
     * The no-listener answer for {@code path}: 404, exactly one {@code Content-Type} header equal to the bare
     * {@code text/plain} with no parameter, and the listener's text.
     */
    private static void assertNoListener(HttpTestClient.Response response, String path) {
        assertThat(response.statusLine()).as(response.toString()).isEqualTo("HTTP/1.1 404 Not Found");
        assertThat(response.headers("Content-Type")).as(response.toString()).containsExactly(TEXT_PLAIN);
        assertThat(response.bodyAsString()).isEqualTo("No listener for endpoint: " + path);
    }

    /** The fields of a form-urlencoded body in their sending order, values URL-decoded as UTF-8. */
    private static Map<String, String> formFields(String body) {
        Map<String, String> fields = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        for (String pair : body.split("&")) {
            int separator = pair.indexOf('=');
            String name = separator < 0 ? pair : pair.substring(0, separator);
            String value = separator < 0 ? "" : pair.substring(separator + 1);
            names.add(name);
            fields.put(name, URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        assertThat(names).as("form field names in %s", body).doesNotHaveDuplicates();
        return fields;
    }

    /**
     * A port free when drawn; the probe socket is closed before the context binds the port, so another process can
     * take it in between, which {@link PortInUseRetryingContextLoader} answers by loading the context again.
     */
    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("No free port for the HTTPS listener", e);
        }
    }
}
