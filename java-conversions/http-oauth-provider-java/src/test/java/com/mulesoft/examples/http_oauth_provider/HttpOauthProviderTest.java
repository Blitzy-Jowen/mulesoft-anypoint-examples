/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_oauth_provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.HtmlElement;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.mulesoft.examples.http_oauth_provider.support.HttpTestClient;

/**
 * Port of the original {@code HttpOauthProviderIT}: a resource owner logs in through the provider's
 * {@code /authorize} page, the demonstration {@code /redirect} flow exchanges the issued code at {@code /token},
 * and the access token read from that page opens {@code /resources}, whose name and URI are asserted exactly as
 * the original asserted them.
 *
 * <p>HtmlUnit stands in for the original headless browser driver and drives only the browser part: the login form and
 * its redirect chain. The login page needs no script, so JavaScript is off. The bearer request goes through the
 * project's ST-1 {@link HttpTestClient}, as the original sent it through a Mule client rather than the browser.
 *
 * <p>Both listeners are bound to two free loopback ports reserved here, never to the defaults 8081 and 8082. The
 * provider port is the SC-10 additional Undertow listener, so it must be a concrete port rather than port 0, and
 * {@code OAuthProviderService} writes both ports into the {@code /redirect} location and the resource URI.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HttpOauthProviderTest {

    private static final String REPLY_NAME = "payroll";

    // The resource owner the test profile configures (application-test.yml, oauth.provider.owner).
    private static final String USERNAME = "mule";
    private static final String PASSWORD = "mule";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Resource listener ({@code /resources}, {@code /redirect}), the main Undertow port. */
    private static int LISTENER_PORT;

    /** Provider listener ({@code /authorize}, {@code /token}). */
    private static int PROVIDER_PORT;

    private WebClient webClient;

    @DynamicPropertySource
    static void listenerPorts(DynamicPropertyRegistry registry) {
        LISTENER_PORT = freeLoopbackPort();
        do {
            PROVIDER_PORT = freeLoopbackPort();
        } while (PROVIDER_PORT == LISTENER_PORT);
        registry.add("http.listener.port", () -> LISTENER_PORT);
        registry.add("http.provider.port", () -> PROVIDER_PORT);
    }

    @BeforeEach
    void setUp() {
        webClient = new WebClient();
        webClient.getOptions().setJavaScriptEnabled(false);
        webClient.getOptions().setCssEnabled(false);
        webClient.getOptions().setRedirectEnabled(true);
        webClient.getOptions().setThrowExceptionOnFailingStatusCode(true);
        webClient.getOptions().setThrowExceptionOnScriptError(false);
        webClient.getOptions().setTimeout(HttpTestClient.TIMEOUT_MILLIS);
    }

    @AfterEach
    void tearDown() {
        if (webClient != null) {
            webClient.close();
        }
    }

    @Test
    void oauthTest() throws Exception {
        HtmlPage login = webClient.getPage("http://localhost:" + PROVIDER_PORT
                + "/authorize?response_type=code&client_id=myclientid&scope=READ_RESOURCE"
                + "&redirect_uri=http://localhost:" + LISTENER_PORT + "/redirect");

        HtmlElement username = login.getHtmlElementById("username");
        HtmlElement password = login.getHtmlElementById("password");
        username.type(USERNAME);
        password.type(PASSWORD);
        HtmlElement submit = login.getHtmlElementById("submit");

        // POST /authorize -> 302 /redirect?code=... -> 302 /token?...&code=... -> 200 token JSON. The final page
        // may be a TextPage or an UnexpectedPage depending on its content type, so it is read as a plain Page.
        Page result = submit.click();
        String content = result.getWebResponse().getContentAsString();
        assertEquals(200, result.getWebResponse().getStatusCode(), content);
        assertEquals("/token", result.getUrl().getPath());

        // The original read the page from its first '{' to its first '}' inclusive.
        int start = content.indexOf('{');
        int end = content.indexOf('}');
        assertTrue(start >= 0 && end > start, () -> "No JSON object on the token page: " + content);
        JsonNode issued = MAPPER.readTree(content.substring(start, end + 1));
        JsonNode accessToken = issued.get("access_token");
        assertNotNull(accessToken, () -> "No access_token on the token page: " + content);
        String token = accessToken.asText();
        assertFalse(token.isBlank(), "access_token is blank");

        HttpTestClient.Response response = HttpTestClient.localhost(LISTENER_PORT)
                .get("/resources", Map.of("Authorization", "Bearer " + token));
        assertEquals(200, response.status(), response::toString);

        JsonNode resource = MAPPER.readTree(response.bodyAsString());
        assertEquals(REPLY_NAME, resource.path("name").asText());
        assertEquals("http://localhost:" + LISTENER_PORT + "/resources/payroll", resource.path("uri").asText());
    }

    /**
     * Reserves a free port on the loopback address and releases it for the application to bind.
     *
     * @throws UncheckedIOException if no port can be reserved
     */
    private static int freeLoopbackPort() {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot reserve a free loopback port", e);
        }
    }
}
