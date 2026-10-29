/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.support.HttpTestClient;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.support.PortInUseRetryingContextLoader;

/**
 * Port of the original {@code HttpAuthorizationCodeWebIT}: a browser starts at {@code /web}, completes Box's
 * authorization-code grant through the local {@code /authorization} and {@code /redirectUrl} endpoints, and lands on
 * {@code /web/loginDone}, whose page must contain {@link #REPLY}, the original assertion with its contains semantics.
 *
 * <p>WireMock stands in for Box's authorize, token and search endpoints, so no Box account is needed; it grants
 * consent at once by redirecting to {@code /redirectUrl} with a fixed code and the state it received. The project's
 * ST-1 {@link HttpTestClient} stands in for the original headless browser: it follows every redirect itself, over
 * plain HTTP to the main listener and to WireMock, and over HTTPS, presenting the {@code TLS_Context} client
 * certificate the listener requires, to the {@code box-oauth} listener.
 *
 * <p>Both listeners run on free ports drawn for each load of the context, never on the original 8081 and 8082, and
 * WireMock on a port of its own choosing. The HTTPS listener is an additional Undertow listener that needs a concrete
 * port, so the main one runs on a defined port too. A drawn port is only free when it is drawn: when another process
 * binds one first, {@link PortInUseRetryingContextLoader} loads the context again on newly drawn ports, so the test
 * reads both ports from the loaded context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ContextConfiguration(loader = PortInUseRetryingContextLoader.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class HttpAuthorizationCodeWebTest {

    private static final CharSequence REPLY = "The user successfully authorized";

    private static final int ORIGINAL_HTTP_PORT = 8081;
    private static final int ORIGINAL_HTTPS_PORT = 8082;

    /** Redirects a browser follows before the test gives up, so a redirect loop fails instead of hanging. */
    private static final int MAX_REDIRECTS = 10;

    private static final String CODE = "test-code";
    private static final String ACCESS_TOKEN = "test-access-token";
    // One line: the grant's token extractors match the whole body without DOTALL, as Mule 3.8's did.
    private static final String TOKEN_RESPONSE = "{\"access_token\":\"" + ACCESS_TOKEN + "\",\"expires_in\":3600,"
            + "\"refresh_token\":\"test-refresh-token\",\"token_type\":\"bearer\"}";
    private static final String SEARCH_RESPONSE = "{\"total_count\":0,\"entries\":[]}";

    private static final String AUTHORIZE_PATH = "/api/oauth2/authorize";
    private static final String TOKEN_PATH = "/api/oauth2/token";
    private static final String SEARCH_PATH = "/2.0/search";

    /** The WireMock stand-in for Box, started before the context on a port it binds itself. */
    @RegisterExtension
    static final WireMockExtension BOX = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    /** Main listener ({@code /web}, {@code /web/loginDone}), plain HTTP. */
    @LocalServerPort
    private int mainPort;

    /** {@code box-oauth} listener ({@code /authorization}, {@code /redirectUrl}), HTTPS with client certificate. */
    @Value("${ports.box-oauth}")
    private int httpsPort;

    @Value("${box.id}")
    private String boxClientId;

    @Value("${truststore.path}")
    private String trustStorePath;

    @Value("${truststore.password}")
    private String trustStorePassword;

    @Value("${keystore.path}")
    private String keyStorePath;

    @Value("${keystore.password}")
    private String keyStorePassword;

    /**
     * Moves both listeners to ports drawn for this load attempt, Spring invoking this method again for each attempt,
     * and every Box URL to WireMock, whose base URL the suppliers read once the extension has started. The HTTPS
     * port is set through {@code ports.box-oauth} rather than {@code listeners.additional[0].port}: overriding one
     * element of that list here would replace the whole list and drop the listener's TLS settings and routes. The
     * local authorization, redirect and login-redirect URLs follow both ports through {@code application.yml}.
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        int[] ports = drawFreePorts(2);
        registry.add("server.port", () -> ports[0]);
        registry.add("ports.box-oauth", () -> ports[1]);
        registry.add("box.authorization-url", () -> BOX.baseUrl() + AUTHORIZE_PATH);
        registry.add("box.token-url", () -> BOX.baseUrl() + TOKEN_PATH);
        registry.add("box.api-base-url", () -> BOX.baseUrl() + "/2.0");
    }

    @Test
    public void oauthTest() {
        String redirectUrl = "https://localhost:" + httpsPort + "/redirectUrl";
        String loginDoneUrl = "http://localhost:" + mainPort + "/web/loginDone";

        // Box's consent step, granted at once: back to the redirect URL with a code and the state as received.
        BOX.stubFor(get(urlPathEqualTo(AUTHORIZE_PATH))
                .withQueryParam("response_type", equalTo("code"))
                .withQueryParam("client_id", equalTo(boxClientId))
                .withQueryParam("box_device_id", equalTo("1"))
                .withQueryParam("box_device_name", equalTo("device1"))
                .withQueryParam("redirect_uri", equalTo(redirectUrl))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", redirectUrl + "?code=" + CODE
                                + "&state={{urlEncode request.query.state}}")
                        .withTransformers("response-template")));
        BOX.stubFor(post(urlPathEqualTo(TOKEN_PATH))
                .withRequestBody(containing("code=" + CODE))
                .withRequestBody(containing("grant_type=authorization_code"))
                .willReturn(okJson(TOKEN_RESPONSE)));
        BOX.stubFor(get(urlPathEqualTo(SEARCH_PATH))
                .withQueryParam("query", equalTo("mule"))
                .withHeader("Authorization", equalTo("Bearer " + ACCESS_TOKEN))
                .willReturn(okJson(SEARCH_RESPONSE)));

        URI target = URI.create("http://localhost:" + mainPort + "/web");
        List<String> paths = new ArrayList<>();
        List<String> hops = new ArrayList<>();
        HttpTestClient.Response response = null;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            response = clientFor(target).get(requestTarget(target));
            paths.add(target.getRawPath());
            hops.add("GET " + target + " -> " + response.statusLine());
            if (!isRedirect(response.status())) {
                break;
            }
            String location = response.header("Location");
            assertNotNull(location, "Redirect without Location: " + hops);
            if (redirects == MAX_REDIRECTS) {
                fail("More than " + MAX_REDIRECTS + " redirects: " + hops);
            }
            target = URI.create(location);
        }

        assertEquals(List.of("/web", "/authorization", AUTHORIZE_PATH, "/redirectUrl", "/web/loginDone"), paths,
                hops::toString);
        assertEquals(URI.create(loginDoneUrl + "?authorizationStatus=0"), target, hops::toString);
        assertEquals(200, response.status(), response::toString);
        assertTrue(response.bodyAsString().contains(REPLY), response::toString);
        assertTrue(response.bodyAsString().endsWith(SEARCH_RESPONSE), response::toString);

        BOX.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
        BOX.verify(getRequestedFor(urlPathEqualTo(SEARCH_PATH))
                .withHeader("Authorization", equalTo("Bearer " + ACCESS_TOKEN)));
    }

    /**
     * The client a browser would use for {@code target}: plain HTTP for the main listener and WireMock, and HTTPS
     * with the {@code TLS_Context} stores for the {@code box-oauth} listener, whose trust store makes it require a
     * client certificate. Any other target fails the test, so nothing beyond these local servers is contacted.
     */
    private HttpTestClient clientFor(URI target) {
        if (!"localhost".equals(target.getHost())) {
            fail("Redirect to a host other than localhost: " + target);
        }
        int port = target.getPort();
        if ("http".equals(target.getScheme()) && (port == mainPort || port == BOX.getPort())) {
            return HttpTestClient.localhost(port);
        }
        if ("https".equals(target.getScheme()) && port == httpsPort) {
            return HttpTestClient.https("localhost", port, trustStorePath, "JKS", trustStorePassword,
                    keyStorePath, "JKS", keyStorePassword);
        }
        return fail("Redirect to an unexpected listener: " + target);
    }

    /** The raw path and query of {@code target}, so the request carries the Location's encoding byte for byte. */
    private static String requestTarget(URI target) {
        String path = target.getRawPath() == null || target.getRawPath().isEmpty() ? "/" : target.getRawPath();
        return target.getRawQuery() == null ? path : path + "?" + target.getRawQuery();
    }

    private static boolean isRedirect(int status) {
        return status >= 300 && status < 400;
    }

    /**
     * Draws {@code count} distinct free ports, none of them an original port, by holding every probe socket open
     * until all are found, then closes the probes for the application to bind the ports. They are free when drawn,
     * not reserved: a port another process binds in between fails the load with a port in use, which
     * {@link PortInUseRetryingContextLoader} answers by loading the context again on newly drawn ports.
     *
     * @throws UncheckedIOException if a probe socket cannot be opened or closed
     */
    private static int[] drawFreePorts(int count) {
        List<ServerSocket> held = new ArrayList<>();
        int[] ports = new int[count];
        try {
            int found = 0;
            while (found < count) {
                ServerSocket socket = new ServerSocket(0);
                held.add(socket);
                int port = socket.getLocalPort();
                if (port != ORIGINAL_HTTP_PORT && port != ORIGINAL_HTTPS_PORT) {
                    ports[found++] = port;
                }
            }
            return ports;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot draw free ports", e);
        } finally {
            held.forEach(HttpAuthorizationCodeWebTest::release);
        }
    }

    private static void release(ServerSocket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot close the probe socket of port " + socket.getLocalPort(), e);
        }
    }
}
