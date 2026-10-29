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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.client.SalesforcePartnerClient;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.config.SalesforceOAuthProperties;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.service.ContactQueryService;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.service.SalesforceOAuthService;
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
 * approves at once by redirecting to the callback with a code and, as Salesforce does, the {@code state} the
 * authorization URL carried, and its token endpoint answers the code exchange with a Salesforce-shaped token
 * response. The browser is replaced by walking the redirect chain hop by hop with ST-1 {@link HttpTestClient}, which
 * never follows redirects, so every {@code Location} is asserted on the wire, and by carrying the state cookie the
 * application set on its redirect to the callback, as a browser does. A Mockito {@link SalesforcePartnerClient}
 * returns three contacts for the original SOQL, recorded from the original README's log lines. Everything between
 * the HTTP requests and those two stand-ins (the controller, the authorize decision, the state and its checks, the
 * token client, the stored token and the contact count) is the production code.
 *
 * <p>Beside the original assertion, a callback with a code that this browser never started at {@code /} is refused
 * on the wire with SC-1's 500 before any token request reaches the stand-in.
 *
 * <p>The listener binds a random port. The production code reads the {@code redirect_uri} from
 * {@link SalesforceOAuthProperties} at each authorize redirect and code exchange, so once the listener is bound each
 * test points the callback's {@code sfdc.oauth.callback.local-port} at that port and registers the WireMock authorize
 * stub against the same callback. No port is chosen before the bind, so no other process can take it in between.
 *
 * <p>The same login and assertion run against a live org in the failsafe {@code SalesforceOauthIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// The context's SalesforceOAuthProperties carry the callback port this class sets, and its token slot is left
// authorized; closing the context after this class keeps both out of the context cache, so no other test class
// inherits a redirect_uri aimed at this listener or an already-authorized application.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SalesforceOauthTest {

    private static final String MESSAGE = "Salesforce query returned";

    private static final String AUTHORIZE_PATH = "/services/oauth2/authorize";

    private static final String TOKEN_PATH = "/services/oauth2/token";

    private static final String CONSUMER_KEY = "test-consumer-key";

    private static final String CONSUMER_SECRET = "test-consumer-secret";

    private static final String CODE = "test-code";

    /** The start of the state cookie's {@code Set-Cookie} and {@code Cookie} forms. */
    private static final String STATE_COOKIE_PREFIX = SalesforceOAuthService.STATE_COOKIE + "=";

    /**
     * Stand-in for {@code login.salesforce.com}: the authorize and token endpoints of the authorization-code flow.
     * Started before the context so that {@link #properties} can point both URLs at it; the token stub is registered
     * here, the authorize stub once the listener port is known ({@link #bindCallbackToListener()}).
     */
    private static final WireMockServer WIREMOCK;

    static {
        WIREMOCK = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        WIREMOCK.start();
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

    /** The port the listener bound, which the callback and the authorize stub are aimed at. */
    @LocalServerPort
    private int port;

    /** The application's OAuth settings, whose callback supplies the {@code redirect_uri} at each use. */
    @Autowired
    private SalesforceOAuthProperties oauthProperties;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("sfdc.key", () -> CONSUMER_KEY);
        registry.add("sfdc.secret", () -> CONSUMER_SECRET);
        registry.add("sfdc.oauth.authorization-url", () -> WIREMOCK.baseUrl() + AUTHORIZE_PATH);
        registry.add("sfdc.oauth.access-token-url", () -> WIREMOCK.baseUrl() + TOKEN_PATH);
    }

    @AfterAll
    static void stopWireMock() {
        WIREMOCK.stop();
    }

    /**
     * Aims the callback at the bound listener, then has the authorize stub approve by redirecting the browser there
     * with a code and the {@code state} of the authorization URL, returned unchanged through WireMock's response
     * templating as Salesforce returns it. The production code reads the callback's port each time it builds the
     * {@code redirect_uri}, so the authorize redirect and the code exchange of this test both carry the listener port.
     */
    @BeforeEach
    void bindCallbackToListener() {
        oauthProperties.getOauth().getCallback().setLocalPort(port);
        WIREMOCK.stubFor(get(urlPathEqualTo(AUTHORIZE_PATH))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", callbackUri() + "?code=" + CODE + "&state={{{request.query.state}}}")
                        .withTransformers("response-template")));
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
        HttpTestClient app = HttpTestClient.localhost(port);

        // No token yet: / redirects the browser to the Salesforce authorization page, with the state of the
        // authorization it starts in the URL and in the browser's state cookie.
        Response first = app.get("/");
        assertEquals(302, first.status(), () -> "unexpected response: " + first);
        assertEquals(0, first.body().length, () -> "redirect must have an empty body: " + first);
        String stateCookie = stateCookieOf(first);
        String state = stateCookie.substring(STATE_COOKIE_PREFIX.length());
        assertTrue(state.matches("[A-Za-z0-9_-]{43}"), () -> "unexpected state: " + first);
        String location = first.header("Location");
        assertEquals(WIREMOCK.baseUrl() + AUTHORIZE_PATH
                        + "?response_type=code&client_id=" + CONSUMER_KEY
                        + "&redirect_uri=" + URLEncoder.encode(callbackUri(), StandardCharsets.UTF_8)
                        + "&display=page&state=" + state,
                location);

        // The login: the authorization server sends the browser back to the callback with a code and the state.
        URI authorize = URI.create(location);
        Response login = HttpTestClient.localhost(WIREMOCK.port())
                .get(authorize.getRawPath() + "?" + authorize.getRawQuery());
        assertEquals(302, login.status(), () -> "unexpected response: " + login);
        assertEquals(callbackUri() + "?code=" + CODE + "&state=" + state, login.header("Location"));

        // The callback, sent with the browser's state cookie, exchanges the code, stores the token and resumes the
        // flow with the contact query.
        URI callback = URI.create(login.header("Location"));
        Response done = app.get(callback.getRawPath() + "?" + callback.getRawQuery(), Map.of("Cookie", stateCookie));
        assertEquals(200, done.status(), () -> "unexpected response: " + done);
        assertUntypedText(done);
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
        assertUntypedText(again);
        assertTrue(again.bodyAsString().contains(MESSAGE), () -> "unexpected body: " + again);
        assertEquals("Salesforce query returned 3 contacts.", again.bodyAsString());

        WIREMOCK.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
        Mockito.verify(partnerClient, times(2)).query(ContactQueryService.CONTACT_QUERY);
    }

    /**
     * Callbacks with a valid-looking code that this browser never started at {@code /}, as a page of another site can
     * make a browser send: without any state or cookie, and with a state and matching cookie the application never
     * issued. Each gets SC-1's 500 with the refusal, and no token request reaches the stand-in, so no grant can be
     * replaced by a code the user did not ask for.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "'no state, no cookie', , , Salesforce authorization failed: no state parameter",
            "'forged state and cookie', forged-state, sfdc_oauth_state=forged-state, "
                    + "Salesforce authorization failed: unknown or already used state"})
    void unsolicitedCallbackIsRefusedBeforeAnyTokenRequest(String label, String state, String cookie,
            String message) throws Exception {
        WIREMOCK.resetRequests();
        HttpTestClient app = HttpTestClient.localhost(port);

        Response refused = app.get("/oauth2callback?code=" + CODE + (state == null ? "" : "&state=" + state),
                cookie == null ? Map.of() : Map.of("Cookie", cookie));

        assertEquals("HTTP/1.1 500 Internal Server Error", refused.statusLine(),
                () -> "unexpected response: " + refused);
        assertEquals("application/json", refused.header("Content-Type"), () -> "unexpected response: " + refused);
        assertEquals("{ \"message\": \"" + message + "\" }", refused.bodyAsString());
        assertNull(refused.header("Set-Cookie"), () -> "unexpected cookie: " + refused);
        WIREMOCK.verify(0, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
        Mockito.verify(partnerClient, never()).query(ContactQueryService.CONTACT_QUERY);
    }

    /**
     * The flow's text as the Mule listener sent its untyped {@code set-payload}: no {@code Content-Type}, the
     * {@code Content-Length} of the body bytes, and no cookie.
     */
    private static void assertUntypedText(Response response) {
        assertNull(response.header("Content-Type"), () -> "unexpected Content-Type: " + response);
        assertEquals(String.valueOf(response.body().length), response.header("Content-Length"),
                () -> "unexpected Content-Length: " + response);
        assertNull(response.header("Set-Cookie"), () -> "unexpected Set-Cookie: " + response);
    }

    /**
     * The state cookie a browser keeps from the redirect, {@code sfdc_oauth_state=<state>}, as it sends it back in its
     * {@code Cookie} header: the {@code name=value} pair before the attributes of the one {@code Set-Cookie}, which
     * also scopes it to the callback path and marks it {@code HttpOnly} and {@code SameSite=Lax}.
     */
    private static String stateCookieOf(Response redirect) {
        List<String> setCookies = redirect.headers("Set-Cookie");
        assertEquals(1, setCookies.size(), () -> "expected one Set-Cookie: " + redirect);
        String setCookie = setCookies.get(0);
        assertTrue(setCookie.startsWith(STATE_COOKIE_PREFIX), () -> "unexpected Set-Cookie: " + redirect);
        assertTrue(setCookie.contains("; Path=/oauth2callback;") && setCookie.contains("; HttpOnly")
                && setCookie.endsWith("; SameSite=Lax"), () -> "unexpected Set-Cookie attributes: " + redirect);
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    /** The {@code redirect_uri} the application registers: the callback on this test's listener port. */
    private String callbackUri() {
        return "http://localhost:" + port + "/oauth2callback";
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
