/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.authenticating_salesforce_using_oauth2;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.client.SalesforcePartnerClient;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.service.ContactQueryService;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.support.HttpTestClient;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.support.HttpTestClient.Response;

/**
 * Credential-free port of the original example's {@code SalesforceOauthIT} functional test, keeping its test method
 * {@code testAuthentication} and its assertion: once the user has logged in at Salesforce, {@code GET /} answers with
 * a page that contains {@code Salesforce query returned}.
 *
 * <p>The original drove a PhantomJS browser through the live Salesforce login page with the credentials of the
 * never-committed {@code mule.test.properties}, then requested {@code /}. Here the whole application runs on its
 * Undertow listener and a WireMock server stands in for {@code login.salesforce.com}: its authorize endpoint
 * approves at once by redirecting to the callback with a code, and its token endpoint answers the code exchange with
 * a Salesforce-shaped token response. The browser is replaced by walking the redirect chain hop by hop with ST-1
 * {@link HttpTestClient}, which never follows redirects, so every {@code Location} is asserted on the wire. A
 * Mockito {@link SalesforcePartnerClient} returns three contacts for the original SOQL, recorded from the original
 * README's log lines. Everything between the HTTP requests and those two stand-ins (the controller, the authorize
 * decision, the token client, the stored token and the contact count) is the production code.
 *
 * <p>The same login and assertion run against a live org in the failsafe {@code SalesforceOauthIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
// The context binds PORT and holds the authorized token slot; closing it after this class keeps both out of the
// context cache, so no other test class inherits the port or an already-authorized application.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SalesforceOauthTest {

    private static final String MESSAGE = "Salesforce query returned";

    private static final String AUTHORIZE_PATH = "/services/oauth2/authorize";

    private static final String TOKEN_PATH = "/services/oauth2/token";

    private static final String CONSUMER_KEY = "test-consumer-key";

    private static final String CONSUMER_SECRET = "test-consumer-secret";

    private static final String CODE = "test-code";

    /**
     * The listener port, fixed before the context starts rather than {@code RANDOM_PORT}: the callback's
     * {@code redirect_uri} is built from {@code sfdc.oauth.callback.local-port}, not from the bound port, and the
     * WireMock authorize stub must redirect to that same callback, so both need the port in advance.
     */
    private static final int PORT;

    /** Stand-in for {@code login.salesforce.com}: the authorize and token endpoints of the authorization-code flow. */
    private static final WireMockServer WIREMOCK;

    static {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            PORT = socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not reserve a port for the listener", e);
        }

        WIREMOCK = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        WIREMOCK.start();
        WIREMOCK.stubFor(get(urlPathEqualTo(AUTHORIZE_PATH))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", callbackUri() + "?code=" + CODE)));
        WIREMOCK.stubFor(post(urlPathEqualTo(TOKEN_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "access_token": "00D000000000001!test-access-token",
                                  "refresh_token": "test-refresh-token",
                                  "instance_url": "%s",
                                  "id": "https://login.salesforce.com/id/00D000000000001/005000000000001",
                                  "issued_at": "1700000000000",
                                  "token_type": "Bearer",
                                  "scope": "api refresh_token"
                                }
                                """.formatted(WIREMOCK.baseUrl()))));
    }

    @MockBean
    private SalesforcePartnerClient partnerClient;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // server.port resolves ${http.port}, so DEFINED_PORT binds PORT.
        registry.add("http.port", () -> PORT);
        registry.add("sfdc.oauth.callback.local-port", () -> PORT);
        registry.add("sfdc.key", () -> CONSUMER_KEY);
        registry.add("sfdc.secret", () -> CONSUMER_SECRET);
        registry.add("sfdc.oauth.authorization-url", () -> WIREMOCK.baseUrl() + AUTHORIZE_PATH);
        registry.add("sfdc.oauth.access-token-url", () -> WIREMOCK.baseUrl() + TOKEN_PATH);
    }

    @AfterAll
    static void stopWireMock() {
        WIREMOCK.stop();
    }

    @BeforeEach
    void stubContactQuery() throws Exception {
        given(partnerClient.query(ContactQueryService.CONTACT_QUERY)).willReturn(List.of(
                contact("0032000001INNfoAAH", "Pickwick", "2014-08-25T13:21:00.000Z"),
                contact("0032000001INGnuAAH", "GaultThe", "2014-08-25T13:21:00.000Z"),
                contact("0032000001IOBe4AAH", "Hobbit", "2014-08-25T13:21:00.000Z")));
    }

    @Test
    void testAuthentication() throws Exception {
        HttpTestClient app = HttpTestClient.localhost(PORT);

        // No token yet: / redirects the browser to the Salesforce authorization page.
        Response first = app.get("/");
        assertEquals(302, first.status(), () -> "unexpected response: " + first);
        assertEquals(0, first.body().length, () -> "redirect must have an empty body: " + first);
        String location = first.header("Location");
        assertEquals(WIREMOCK.baseUrl() + AUTHORIZE_PATH
                        + "?response_type=code&client_id=" + CONSUMER_KEY
                        + "&redirect_uri=" + URLEncoder.encode(callbackUri(), StandardCharsets.UTF_8)
                        + "&display=page",
                location);

        // The login: the authorization server sends the browser back to the callback with a code.
        URI authorize = URI.create(location);
        Response login = HttpTestClient.localhost(WIREMOCK.port())
                .get(authorize.getRawPath() + "?" + authorize.getRawQuery());
        assertEquals(302, login.status(), () -> "unexpected response: " + login);
        assertEquals(callbackUri() + "?code=" + CODE, login.header("Location"));

        // The callback exchanges the code, stores the token and resumes the flow with the contact query.
        URI callback = URI.create(login.header("Location"));
        Response done = app.get(callback.getRawPath() + "?" + callback.getRawQuery());
        assertEquals(200, done.status(), () -> "unexpected response: " + done);
        String contentType = done.header("Content-Type");
        assertNotNull(contentType, () -> "missing Content-Type: " + done);
        assertTrue(contentType.startsWith("text/plain"), () -> "unexpected Content-Type: " + contentType);
        assertTrue(done.bodyAsString().contains(MESSAGE), () -> "unexpected body: " + done);
        assertEquals("Salesforce query returned 3 contacts.", done.bodyAsString());

        WIREMOCK.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withFormParam("grant_type", equalTo("authorization_code"))
                .withFormParam("code", equalTo(CODE))
                .withFormParam("client_id", equalTo(CONSUMER_KEY))
                .withFormParam("client_secret", equalTo(CONSUMER_SECRET))
                .withFormParam("redirect_uri", equalTo(callbackUri())));

        // The original's own request: with the token stored, / answers the query result without a redirect.
        Response again = app.get("/");
        assertEquals(200, again.status(), () -> "unexpected response: " + again);
        assertNull(again.header("Location"), () -> "unexpected redirect: " + again);
        assertTrue(again.bodyAsString().contains(MESSAGE), () -> "unexpected body: " + again);
        assertEquals("Salesforce query returned 3 contacts.", again.bodyAsString());

        WIREMOCK.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
        Mockito.verify(partnerClient, times(2)).query(ContactQueryService.CONTACT_QUERY);
    }

    /** The {@code redirect_uri} the application registers: the callback on this test's listener port. */
    private static String callbackUri() {
        return "http://localhost:" + PORT + "/oauth2callback";
    }

    /** A Contact record as the Partner client returns it: a {@code HashMap} carrying the sObject {@code type}. */
    private static Map<String, Object> contact(String id, String lastName, String lastModifiedDate) {
        Map<String, Object> record = new HashMap<>();
        record.put("type", "Contact");
        record.put("Id", id);
        record.put("LastName", lastName);
        record.put("LastModifiedDate", lastModifiedDate);
        return record;
    }
}
