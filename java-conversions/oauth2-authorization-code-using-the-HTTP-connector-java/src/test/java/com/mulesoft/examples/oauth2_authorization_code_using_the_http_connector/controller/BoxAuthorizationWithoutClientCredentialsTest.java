/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.controller;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.config.BoxProperties;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.service.BoxOAuthService;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.support.HttpTestClient;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.support.PortInUseRetryingContextLoader;

/**
 * Wire-level test of the application with {@code box.id} and {@code box.secret} left empty, as the empty defaults of
 * {@code application.yml} leave them: the application starts and {@code /web} still answers its 302, while every
 * endpoint that would send a Box client credential fails locally instead of sending Box an empty one.
 * {@code /authorization} answers SC-1's 500 naming {@code box.id} and redirects nowhere, and {@code /redirectUrl}
 * answers the token-call-failed marker 200 without a token request.
 *
 * <p>The store passwords come from the {@code test} profile, as in {@link BoxAuthorizationControllerTest}, and Box is
 * a WireMock stub that must receive nothing. The HTTPS listener takes a port of its own, drawn for each load of the
 * context, so this context and the other controller tests' contexts can be cached side by side;
 * {@link PortInUseRetryingContextLoader} loads the context again on a newly drawn port when another process binds
 * the drawn one first.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"box.id=", "box.secret="})
@ContextConfiguration(loader = PortInUseRetryingContextLoader.class)
@ActiveProfiles("test")
class BoxAuthorizationWithoutClientCredentialsTest {

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
        // ports.box-oauth rather than listeners.additional[0].port, for the reason application.yml gives.
        r.add("ports.box-oauth", () -> drawnHttpsPort);
        r.add("box.authorization-url", () -> box.baseUrl() + "/api/oauth2/authorize");
        r.add("box.token-url", () -> box.baseUrl() + "/api/oauth2/token");
        r.add("box.api-base-url", () -> box.baseUrl() + "/2.0");
    }

    @Test
    void webStillRedirectsToTheLocalAuthorizationUrl() {
        HttpTestClient.Response response = HttpTestClient.localhost(mainPort).get("/web");

        assertThat(response.statusLine()).as(response::toString).isEqualTo("HTTP/1.1 302 Found");
        assertThat(response.header("Location")).as(response::toString).isEqualTo(props.getLoginRedirectLocation());
        assertThat(box.getAllServeEvents()).isEmpty();
    }

    @Test
    void authorizationFailsNamingTheClientIdWithoutRedirectingToBox() {
        HttpTestClient.Response response = tls().get("/authorization?onCompleteRedirectTo=" + ON_COMPLETE);

        assertThat(response.statusLine()).as(response::toString).isEqualTo("HTTP/1.1 500 Internal Server Error");
        assertThat(response.header("Location")).as(response::toString).isNull();
        assertThat(response.header("Content-Type")).as(response::toString).startsWith("application/json");
        assertThat(response.bodyAsString()).as(response::toString)
                .isEqualTo("{ \"message\": \"Box client ID (box.id) is not configured\" }");
        assertThat(box.getAllServeEvents()).isEmpty();
    }

    @Test
    void redirectUrlWithACodeReportsStatus200WithoutATokenRequest() {
        HttpTestClient.Response withState = tls().get("/redirectUrl?code=abc&state=" + ENC_STATE);
        HttpTestClient.Response withoutState = tls().get("/redirectUrl?code=abc");

        assertThat(withState.statusLine()).as(withState::toString).isEqualTo("HTTP/1.1 302 Found");
        assertThat(withState.header("Location")).as(withState::toString)
                .isEqualTo(ON_COMPLETE + "?authorizationStatus=200");
        assertThat(withState.header("Content-Type")).as(withState::toString).isNull();
        assertThat(withState.body()).as(withState::toString).isEmpty();

        assertThat(withoutState.statusLine()).as(withoutState::toString)
                .isEqualTo("HTTP/1.1 500 Internal Server Error");
        assertThat(withoutState.header("Location")).as(withoutState::toString).isNull();
        assertThat(withoutState.header("Content-Type")).as(withoutState::toString).isNull();
        assertThat(withoutState.bodyAsString()).as(withoutState::toString)
                .isEqualTo("Failure calling token url " + props.getTokenUrl() + ". Exception message is null");

        assertThat(box.getAllServeEvents()).isEmpty();
        assertThat(oauth.tokens(BoxOAuthService.DEFAULT_RESOURCE_OWNER_ID)).isEmpty();
    }

    /** ST-1 client for the HTTPS listener, trusting only the carried trust store and presenting its client key. */
    private HttpTestClient tls() {
        return HttpTestClient.https("localhost", httpsPort, TRUST_STORE_PATH, STORE_TYPE, trustStorePassword,
                KEY_STORE_PATH, STORE_TYPE, keyStorePassword);
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
        URL url = BoxAuthorizationWithoutClientCredentialsTest.class.getResource(resource);
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
