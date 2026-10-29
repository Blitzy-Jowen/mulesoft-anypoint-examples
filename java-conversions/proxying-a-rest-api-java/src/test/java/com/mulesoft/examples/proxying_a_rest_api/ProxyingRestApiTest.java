/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_rest_api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import com.mulesoft.examples.proxying_a_rest_api.support.HttpTestClient;

/**
 * Port of the original example's {@code ProxyingRestApiIT} functional test, keeping its three calls through the proxy
 * and its four assertions on the folder response.
 *
 * <p>The original logged in to Box with PhantomJS through the proxy, read the authorization code from the consent
 * redirect, exchanged it for a token through the proxy and read folder {@code 0} with that token. Here a WireMock
 * server stands in for {@code https://api.box.com:443}, so the three calls still travel through the whole proxy
 * (listener, {@code copy-headers} on both legs, upstream request) while no browser, Box account or network is needed:
 * the login page is fetched instead of rendered, and the code is read from the consent button's redirect URL by the
 * original's own rule. The {@code box.*} values come from {@code application-test.yml} in place of the uncommitted
 * {@code mule.test.properties}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class ProxyingRestApiTest {

    private static final String AUTHORIZE_PATH = "/oauth2/authorize?response_type=code&client_id=";

    private static final String TOKEN_PATH = "/oauth2/token";

    private static final String FOLDER_PATH = "/2.0/folders/0";

    /** What the original's {@code UrlEncodedFormEntity(formparams, "utf-8")} declared as the token request type. */
    private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded; charset=UTF-8";

    /** Authorization code the stubbed consent page redirects with; a URL-safe test value. */
    private static final String AUTH_CODE = "test-authorization-code";

    /** Access token the stubbed token endpoint issues; a URL-safe test value. */
    private static final String ACCESS_TOKEN = "test-access-token";

    /** The post-consent redirect URL carried by the consent button of the stubbed Box page. */
    private static final Pattern CONSENT_REDIRECT =
            Pattern.compile("id=\"consent_accept_button\"[^>]*\\sdata-redirect-url=\"([^\"]*)\"");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Static, so its URL exists before {@link #upstream} is read while the application context starts. */
    @RegisterExtension
    static WireMockExtension box = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry registry) {
        registry.add("proxy.upstream-url", box::baseUrl);
    }

    @LocalServerPort
    int port;

    @Value("${box.user}")
    String username;

    @Value("${box.id}")
    String clientId;

    @Value("${box.secret}")
    String clientSecret;

    @BeforeEach
    void stubBox() throws JsonProcessingException {
        box.stubFor(get(urlEqualTo(AUTHORIZE_PATH + clientId))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/html; charset=utf-8")
                        .withBody(authorizePage())));
        box.stubFor(post(urlEqualTo(TOKEN_PATH))
                .withHeader("Content-Type", equalTo(FORM_CONTENT_TYPE))
                .withRequestBody(equalTo(tokenForm(AUTH_CODE)))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(tokenResponse())));
        box.stubFor(get(urlEqualTo(FOLDER_PATH))
                .withHeader("Authorization", equalTo("Bearer " + ACCESS_TOKEN))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(folderResponse(username))));
    }

    @Test
    void restAuthenticationTest() throws Exception {
        HttpTestClient client = HttpTestClient.localhost(port);

        // The Box login and consent screens the original's browser opened through the proxy.
        HttpTestClient.Response page = client.get(AUTHORIZE_PATH + clientId);
        assertEquals(200, page.status(), page::toString);
        String html = page.bodyAsString();
        assertTrue(html.contains("id=\"login\""), html);
        assertTrue(html.contains("id=\"password\""), html);
        Matcher consent = CONSENT_REDIRECT.matcher(html);
        assertTrue(consent.find(), html);
        String url = consent.group(1);
        String code = url.substring(url.indexOf("code=") + 5);
        assertEquals(AUTH_CODE, code);

        String token = getAccessToken(client, code);

        HttpTestClient.Response result = client.get(FOLDER_PATH, Map.of("Authorization", "Bearer " + token));

        assertNotNull(result);
        assertEquals(200, result.status(), result::toString);
        // A Mule NullPayload result corresponds to an empty HTTP body.
        assertTrue(result.body().length > 0, result::toString);

        JsonNode jo = MAPPER.readTree(result.body());
        assertEquals("folder", jo.path("type").asText());
        assertEquals(username, jo.path("owned_by").path("login").asText());

        // The caller's Authorization header reached the upstream unchanged.
        box.verify(getRequestedFor(urlEqualTo(FOLDER_PATH))
                .withHeader("Authorization", equalTo("Bearer " + token)));
    }

    /**
     * Exchanges {@code code} for an access token through the proxy and checks that the form reached the upstream
     * byte for byte.
     */
    private String getAccessToken(HttpTestClient client, String code) throws Exception {
        String form = tokenForm(code);
        HttpTestClient.Response response = client.post(TOKEN_PATH, FORM_CONTENT_TYPE, form);
        assertEquals(200, response.status(), response::toString);
        box.verify(postRequestedFor(urlEqualTo(TOKEN_PATH))
                .withHeader("Content-Type", equalTo(FORM_CONTENT_TYPE))
                .withRequestBody(equalTo(form)));
        return MAPPER.readTree(response.body()).path("access_token").asText();
    }

    /** The token request form, in the parameter order and UTF-8 form encoding of the original's request. */
    private String tokenForm(String code) {
        return "grant_type=" + encode("authorization_code")
                + "&code=" + encode(code)
                + "&client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** One page holding the login fields and the consent button; the redirect URL needs no HTML unescaping. */
    private static String authorizePage() {
        return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="utf-8"><title>Box | Log In</title></head>
                <body>
                <form id="login_form" method="post" action="/api/oauth2/authorize">
                <input id="login" name="login" type="text">
                <input id="password" name="password" type="password">
                <button id="consent_accept_button" type="submit"
                        data-redirect-url="https://www.google.com/?code=%s">Grant access to Box</button>
                </form>
                </body>
                </html>
                """.formatted(AUTH_CODE);
    }

    private static String tokenResponse() throws JsonProcessingException {
        ObjectNode token = MAPPER.createObjectNode();
        token.put("access_token", ACCESS_TOKEN);
        token.put("expires_in", 3600);
        token.putArray("restricted_to");
        token.put("refresh_token", "test-refresh-token");
        token.put("token_type", "bearer");
        return MAPPER.writeValueAsString(token);
    }

    private static String folderResponse(String login) throws JsonProcessingException {
        ObjectNode folder = MAPPER.createObjectNode();
        folder.put("type", "folder");
        folder.put("id", "0");
        folder.put("name", "All Files");
        ObjectNode owner = folder.putObject("owned_by");
        owner.put("type", "user");
        owner.put("id", "1");
        owner.put("name", "Mule Tester");
        owner.put("login", login);
        return MAPPER.writeValueAsString(folder);
    }
}
