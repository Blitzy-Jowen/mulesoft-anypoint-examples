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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.HtmlElement;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.mulesoft.examples.http_oauth_provider.support.HttpTestClient;
import com.mulesoft.examples.http_oauth_provider.support.PortInUseRetryingContextLoader;

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
 * <p>Both listeners are bound to two free loopback ports drawn here, never to the defaults 8081 and 8082. The
 * provider port is the SC-10 additional Undertow listener, so it must be a concrete port rather than port 0, and
 * {@code OAuthProviderService} writes both ports into the {@code /redirect} location and the resource URI. The ports
 * are drawn for every attempt to load the context, and an attempt that loses one to another process before Undertow
 * binds it is repeated on newly drawn ports ({@link PortInUseRetryingContextLoader}), so the test reads both ports
 * from the loaded context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ContextConfiguration(loader = PortInUseRetryingContextLoader.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HttpOauthProviderTest {

    private static final String REPLY_NAME = "payroll";

    // The resource owner the test profile configures (application-test.yml, oauth.provider.owner).
    private static final String USERNAME = "mule";
    private static final String PASSWORD = "mule";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Resource listener ({@code /resources}, {@code /redirect}), the main Undertow port of the loaded context. */
    @LocalServerPort
    private int listenerPort;

    /** Provider listener ({@code /authorize}, {@code /token}) of the loaded context. */
    @Value("${http.provider.port}")
    private int providerPort;

    private WebClient webClient;

    /**
     * Draws two distinct free loopback ports, both sockets open at once, for the resource and provider listeners.
     * Spring calls this method again for every attempt to load the context, so each attempt runs on ports drawn for
     * it.
     *
     * @throws UncheckedIOException if no port can be drawn
     */
    @DynamicPropertySource
    static void listenerPorts(DynamicPropertyRegistry registry) {
        int drawnListenerPort;
        int drawnProviderPort;
        try (ServerSocket listenerSocket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
             ServerSocket providerSocket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            drawnListenerPort = listenerSocket.getLocalPort();
            drawnProviderPort = providerSocket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot draw free loopback ports for the two listeners", e);
        }
        registry.add("http.listener.port", () -> drawnListenerPort);
        registry.add("http.provider.port", () -> drawnProviderPort);
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
        HtmlPage login = webClient.getPage("http://localhost:" + providerPort
                + "/authorize?response_type=code&client_id=myclientid&scope=READ_RESOURCE"
                + "&redirect_uri=http://localhost:" + listenerPort + "/redirect");

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

        HttpTestClient.Response response = HttpTestClient.localhost(listenerPort)
                .get("/resources", Map.of("Authorization", "Bearer " + token));
        assertEquals(200, response.status(), response::toString);

        JsonNode resource = MAPPER.readTree(response.bodyAsString());
        assertEquals(REPLY_NAME, resource.path("name").asText());
        assertEquals("http://localhost:" + listenerPort + "/resources/payroll", resource.path("uri").asText());
    }
}
