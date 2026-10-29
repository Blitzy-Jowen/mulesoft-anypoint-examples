/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.service.BoxClientCredentialsService;
import com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.support.HttpTestClient;

/**
 * The {@code logging.level} pins of {@code application.yml} under {@code logging.level.root=TRACE}: both flows run
 * end to end against WireMock (the {@code /oauth} client-credentials grant, its Bearer GET and the 401 refresh, then
 * the {@code /FIM/*} proxy with its substituted {@code client_id}, answered once and failed once with an I/O error),
 * and the whole captured console output must hold none of the Box client credentials, the Basic value they form,
 * either access token, the refresh token, the FIM {@code client.id} or any {@code Authorization:} header line.
 *
 * <p>The case is not vacuous: WireMock confirms every one of those values crossed the wire, the output holds TRACE
 * lines of the request mapping for both controllers, and the flow's own INFO {@code #[payload]} logger
 * (http-client-credentials.xml:33) still writes its line, so the pins silence only the loggers they name.
 *
 * <p>The credentials reach the context through {@link DynamicPropertySource} rather than inlined test properties:
 * the Spring test framework logs inlined properties with its context cache key at TRACE, which would put them in the
 * output this case scans.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "logging.level.root=TRACE")
@ActiveProfiles("test")
// Logger levels are JVM-wide: the context is closed after the class and every level restored, so no later test class
// runs under root TRACE.
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class TraceLogExcludesCredentialsTest {

    private static final String OAUTH_PATH = "/oauth";

    private static final String TOKEN = "/api/oauth2/token";

    /** The flow's GET {@code path="/"} appended to the request configuration's basePath (line 12). */
    private static final String AUTHORIZE = "/api/oauth2/authorize/";

    private static final String BOX_CLIENT_ID = "g4-box-client-id-Q7";

    private static final String BOX_CLIENT_SECRET = "g4-box-client-secret-Z9";

    private static final String FIM_CLIENT_ID = "g4-fim-client-id-K3";

    /** The value of the token request's {@code Authorization: Basic} header. */
    private static final String BASIC_CREDENTIALS =
            Base64.getEncoder().encodeToString((BOX_CLIENT_ID + ":" + BOX_CLIENT_SECRET).getBytes(UTF_8));

    private static final String FIRST_ACCESS_TOKEN = jwtShapedToken("g4-first-access-signature");

    private static final String SECOND_ACCESS_TOKEN = jwtShapedToken("g4-second-access-signature");

    private static final String REFRESH_TOKEN = "g4-refresh-token-W5";

    /** The {@code /oauth} request body, which the flow's INFO logger writes. */
    private static final String PAYLOAD = "g4-trace-payload";

    /** A FIM request target holding the literal uri-param placeholder the flow substitutes (lines 41-43). */
    private static final String FIM_TARGET = "/FIM/{client_id}/accounts?view=full";

    private static final String FIM_FAILING_TARGET = "/FIM/{client_id}/fault";

    /** Every value that crossed the wire and must not reach the log, plus the header-line prefix of any of them. */
    private static final List<String> FORBIDDEN = List.of(BOX_CLIENT_ID, BOX_CLIENT_SECRET, BASIC_CREDENTIALS,
            FIRST_ACCESS_TOKEN, SECOND_ACCESS_TOKEN, REFRESH_TOKEN, FIM_CLIENT_ID, "Authorization:");

    /** How long the output must stay free of every forbidden value, for lines written after the last reply. */
    private static final Duration SETTLE = Duration.ofMillis(300);

    private static final String TOKEN_SCENARIO = "token-refresh";

    private static final String SECOND_TOKEN = "second-token";

    private static final String AUTHORIZE_SCENARIO = "token-expiry";

    private static final String EXPIRED = "expired";

    private static final String RENEWED = "renewed";

    /** The logger levels set before this class's context started, by logger name. */
    private static final Map<String, Level> LEVELS_BEFORE = new HashMap<>();

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    /** Suppliers, not values: WireMock's port is known only once the extension has started the server. */
    @DynamicPropertySource
    static void upstreamsAndCredentials(DynamicPropertyRegistry registry) {
        registry.add("box.oauth.token-url", () -> wm.baseUrl() + TOKEN);
        registry.add("box.api.base-url", wm::baseUrl);
        registry.add("fim.base-url", wm::baseUrl);
        registry.add("oauth.client.id", () -> BOX_CLIENT_ID);
        registry.add("oauth.client.secret", () -> BOX_CLIENT_SECRET);
        registry.add("client.id", () -> FIM_CLIENT_ID);
    }

    @LocalServerPort
    private int port;

    @BeforeAll
    static void recordLevels() {
        LEVELS_BEFORE.clear();
        for (Logger logger : loggerContext().getLoggerList()) {
            if (logger.getLevel() != null) {
                LEVELS_BEFORE.put(logger.getName(), logger.getLevel());
            }
        }
    }

    @AfterAll
    static void restoreLevels() {
        for (Logger logger : loggerContext().getLoggerList()) {
            Level before = LEVELS_BEFORE.get(logger.getName());
            // The root logger always has a level and cannot be given none.
            if (before != null || !org.slf4j.Logger.ROOT_LOGGER_NAME.equals(logger.getName())) {
                logger.setLevel(before);
            }
        }
    }

    @Test
    void neitherFlowLogsCredentialsTokensOrClientIdUnderRootTrace(CapturedOutput output) {
        stubBox();
        wm.stubFor(get(urlEqualTo("/FIM/" + FIM_CLIENT_ID + "/accounts?view=full")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", MediaType.TEXT_PLAIN_VALUE)
                .withBody("accounts")));
        wm.stubFor(get(urlEqualTo("/FIM/" + FIM_CLIENT_ID + "/fault"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        HttpTestClient client = HttpTestClient.localhost(port);

        HttpTestClient.Response granted = client.post(OAUTH_PATH, MediaType.TEXT_PLAIN_VALUE, PAYLOAD);
        HttpTestClient.Response refreshed = client.get(OAUTH_PATH);
        HttpTestClient.Response proxied = client.get(FIM_TARGET);
        HttpTestClient.Response failed = client.get(FIM_FAILING_TARGET);

        assertEquals(200, granted.status(), granted::toString);
        assertEquals(200, refreshed.status(), refreshed::toString);
        assertEquals(200, proxied.status(), proxied::toString);
        assertEquals(500, failed.status(), failed::toString);
        wm.verify(2, postRequestedFor(urlEqualTo(TOKEN))
                .withHeader("Authorization", equalTo("Basic " + BASIC_CREDENTIALS)));
        wm.verify(2, getRequestedFor(urlEqualTo(AUTHORIZE))
                .withHeader("Authorization", equalTo("Bearer " + FIRST_ACCESS_TOKEN)));
        wm.verify(1, getRequestedFor(urlEqualTo(AUTHORIZE))
                .withHeader("Authorization", equalTo("Bearer " + SECOND_ACCESS_TOKEN)));
        wm.verify(1, getRequestedFor(urlEqualTo("/FIM/" + FIM_CLIENT_ID + "/accounts?view=full")));
        wm.verify(1, getRequestedFor(urlEqualTo("/FIM/" + FIM_CLIENT_ID + "/fault")));

        await().during(SETTLE).atMost(SETTLE.multipliedBy(4)).untilAsserted(() -> {
            String log = output.getAll();
            List<String> leaks = log.lines()
                    .filter(line -> FORBIDDEN.stream().anyMatch(line::contains))
                    .toList();
            assertTrue(leaks.isEmpty(), () -> leaks.size() + " log line(s) hold a credential or token:\n"
                    + String.join("\n", leaks));
        });
        String log = output.getAll();
        assertTrue(hasLine(log, " TRACE ", "Mapped to " + OAuthController.class.getName()),
                "no TRACE request-mapping line for /oauth: root TRACE was not in effect");
        assertTrue(hasLine(log, " TRACE ", "Mapped to " + FimProxyController.class.getName()),
                "no TRACE request-mapping line for /FIM/*: root TRACE was not in effect");
        assertTrue(hasLine(log, " INFO ", BoxClientCredentialsService.class.getSimpleName(), PAYLOAD),
                "the flow's INFO #[payload] line is missing");
    }

    /**
     * Box issues the first token, then a second one; the authorize GET succeeds, then rejects the first token once
     * with a 401, which re-runs the grant, and succeeds for the second token.
     */
    private static void stubBox() {
        wm.stubFor(post(urlEqualTo(TOKEN)).inScenario(TOKEN_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(tokenResponse(FIRST_ACCESS_TOKEN))
                .willSetStateTo(SECOND_TOKEN));
        wm.stubFor(post(urlEqualTo(TOKEN)).inScenario(TOKEN_SCENARIO)
                .whenScenarioStateIs(SECOND_TOKEN)
                .willReturn(tokenResponse(SECOND_ACCESS_TOKEN)));
        wm.stubFor(get(urlEqualTo(AUTHORIZE)).inScenario(AUTHORIZE_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(authorized())
                .willSetStateTo(EXPIRED));
        wm.stubFor(get(urlEqualTo(AUTHORIZE)).inScenario(AUTHORIZE_SCENARIO)
                .whenScenarioStateIs(EXPIRED)
                .willReturn(aResponse().withStatus(401))
                .willSetStateTo(RENEWED));
        wm.stubFor(get(urlEqualTo(AUTHORIZE)).inScenario(AUTHORIZE_SCENARIO)
                .whenScenarioStateIs(RENEWED)
                .willReturn(authorized()));
    }

    private static ResponseDefinitionBuilder authorized() {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .withBody("{\"authorized\":true}");
    }

    private static ResponseDefinitionBuilder tokenResponse(String accessToken) {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .withBody("{\"access_token\":\"" + accessToken + "\",\"token_type\":\"bearer\",\"refresh_token\":\""
                        + REFRESH_TOKEN + "\",\"expires_in\":3600}");
    }

    /**
     * A token shaped like the JWT access tokens Box issues: Base64URL JSON header and claims, then the signature.
     * Encoded at run time, so no token-shaped literal sits in the source.
     */
    private static String jwtShapedToken(String signature) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"HS256\"}".getBytes(UTF_8)) + "."
                + encoder.encodeToString(("{\"sub\":\"" + signature + "\"}").getBytes(UTF_8)) + "." + signature;
    }

    /** Whether one line of {@code log} holds every fragment. */
    private static boolean hasLine(String log, String... fragments) {
        return log.lines().anyMatch(line -> {
            for (String fragment : fragments) {
                if (!line.contains(fragment)) {
                    return false;
                }
            }
            return true;
        });
    }

    private static LoggerContext loggerContext() {
        return (LoggerContext) LoggerFactory.getILoggerFactory();
    }
}
