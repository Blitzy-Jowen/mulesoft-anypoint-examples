/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_ms_dynamics.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.noContent;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.netty.handler.timeout.ReadTimeoutException;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.codec.CodecsAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.function.ThrowingConsumer;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.mulesoft.examples.import_contacts_into_ms_dynamics.config.DynamicsOAuthConfig;
import com.mulesoft.examples.import_contacts_into_ms_dynamics.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.import_contacts_into_ms_dynamics.exception.UpstreamRateLimitException;
import com.mulesoft.examples.import_contacts_into_ms_dynamics.exception.UpstreamUnavailableException;

/**
 * Tests {@link DynamicsWebApiClient}, the Dataverse Web API v9.2 replacement of the Mule {@code dynamicscrm}
 * connector, through the application's real beans: {@link DynamicsOAuthConfig} (client-credentials token, timeouts,
 * OData headers), SC-9's {@link UpstreamCallExecutor} and the {@link DynamicsFailureClassifier}, wired by an
 * {@link ApplicationContextRunner}. One WireMock server stands in for both the Microsoft Entra token endpoint and
 * the Dataverse environment.
 *
 * <p>Covered: the client-credentials token request and its reuse; the contact create of the {@code crm-create-account}
 * flow (attribute order and bytes, OData headers, the GUID read from {@code OData-EntityId}); every failure mode of
 * the approved connector failure handling, counting the token and Dataverse requests each one makes (a 401 evicts the
 * cached token and retries once, a 429 carries {@code Retry-After} and is not retried, a timeout or refused connection
 * is never retried or re-authenticated, any other failure is rethrown unchanged); the query and delete that replace the
 * original test sub-flows {@code selectContactFromDynamics} and {@code deleteContactFromDynamics}, including the
 * doubling of {@code '} inside the OData string literal; and the late failure of a blank {@code dynamics.*} key.
 *
 * <p>Every tenant, client, secret and token is a fake test value, and no request leaves the loopback interface.
 */
class DynamicsWebApiClientTest {

    private static final String CONTACT_GUID = "00000000-0000-0000-0000-000000000001";

    private static final String CONTACTS = "/api/data/v9.2/contacts";

    private static final String CONTACT_PATH = CONTACTS + "(" + CONTACT_GUID + ")";

    private static final String TOKEN_PATH = "/test-tenant/oauth2/v2.0/token";

    private static final String TOKEN_JSON =
            "{\"access_token\":\"test-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}";

    private static final String BEARER = "Bearer test-token";

    /** The first {@code contacts.csv} record, as {@code DynamicsContactMapper} hands it to the client. */
    private static final String CONTACT_JSON = "{\"firstname\":\"John\",\"lastname\":\"Doe\","
            + "\"emailaddress1\":\"john.doe@texasComp.com\",\"telephone1\":\"096548763\"}";

    private static final String JOHN_DOE_QUERY_JSON = "{\"@odata.context\":\"x\",\"value\":[{\"contactid\":\""
            + CONTACT_GUID + "\",\"firstname\":\"John\",\"lastname\":\"Doe\"}]}";

    private static final String ODATA_ERROR_JSON = "{\"error\":{\"code\":\"0x80040217\",\"message\":\"bad\"}}";

    private static final String ENTRA_INVALID_CLIENT_JSON =
            "{\"error\":\"invalid_client\",\"error_description\":\"AADSTS7000215\"}";

    private static final String ODATA_ENTITY_ID = "OData-EntityId";

    private static WireMockServer wireMock;

    private static Level rootLogLevel;

    @BeforeAll
    static void startWireMock() {
        // Without a logback-test.xml logback logs at DEBUG, and Netty and WireMock would flood the build output.
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        rootLogLevel = root.getLevel();
        root.setLevel(Level.INFO);
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(rootLogLevel);
    }

    @BeforeEach
    void resetWireMock() {
        wireMock.resetAll();
        wireMock.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(okJson(TOKEN_JSON)));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Token and create
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void createContactPostsTheAttributesWithAClientCredentialsTokenAndReturnsTheEntityGuid() {
        stubCreated();

        withClient(client -> assertThat(client.createContact(johnDoe())).isEqualTo(CONTACT_GUID));

        List<LoggedRequest> tokens = tokenRequests();
        assertThat(tokens).hasSize(1);
        LoggedRequest token = tokens.get(0);
        assertThat(token.getHeader("Content-Type")).startsWith("application/x-www-form-urlencoded");
        // DynamicsOAuthConfig registers client_secret_post: the credentials travel in the form, not in a Basic header.
        assertThat(token.containsHeader("Authorization")).isFalse();
        assertThat(formFields(token))
                .containsEntry("grant_type", "client_credentials")
                .containsEntry("scope", wireMock.baseUrl() + "/.default")
                .containsEntry("client_id", "test-client")
                .containsEntry("client_secret", "test-secret");

        List<LoggedRequest> creates = createRequests();
        assertThat(creates).hasSize(1);
        LoggedRequest create = creates.get(0);
        assertDataverseHeaders(create);
        assertThat(create.getHeader("Content-Type")).startsWith("application/json");
        // Compared as a string: equalToJson would accept the attributes in any order.
        assertThat(create.getBodyAsString()).isEqualTo(CONTACT_JSON);
        assertThat(dataverseRequests()).hasSize(1);
    }

    @Test
    void createdContactWithoutAnEntityIdHeaderYieldsNull() {
        wireMock.stubFor(post(urlEqualTo(CONTACTS)).willReturn(noContent()));

        withClient(client -> assertThat(client.createContact(johnDoe())).isNull());

        assertThat(createRequests()).hasSize(1);
    }

    @Test
    void consecutiveCreatesReuseTheCachedToken() {
        stubCreated();

        withClient(client -> {
            assertThat(client.createContact(johnDoe())).isEqualTo(CONTACT_GUID);
            assertThat(client.createContact(johnDoe())).isEqualTo(CONTACT_GUID);
        });

        assertThat(tokenRequests()).hasSize(1);
        assertThat(createRequests()).hasSize(2)
                .allSatisfy(request -> assertThat(request.getHeader("Authorization")).isEqualTo(BEARER));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Approved connector failure modes (SC-9)
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void expiredTokenIsEvictedAndTheCreateRetriedOnceWithANewToken() {
        wireMock.stubFor(post(urlEqualTo(CONTACTS))
                .inScenario("expired")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(401))
                .willSetStateTo("renewed"));
        wireMock.stubFor(post(urlEqualTo(CONTACTS))
                .inScenario("expired")
                .whenScenarioStateIs("renewed")
                .willReturn(noContent().withHeader(ODATA_ENTITY_ID, wireMock.baseUrl() + CONTACT_PATH)));

        withClient(client -> assertThat(client.createContact(johnDoe())).isEqualTo(CONTACT_GUID));

        // A second token request proves evictToken removed the cached authorized client before the retry.
        assertThat(tokenRequests()).hasSize(2);
        assertThat(createRequests()).hasSize(2).allSatisfy(request -> {
            assertThat(request.getHeader("Authorization")).isEqualTo(BEARER);
            assertThat(request.getBodyAsString()).isEqualTo(CONTACT_JSON);
        });
    }

    @Test
    void createRejectedAgainAfterReauthenticationIsAnAuthenticationFailure() {
        wireMock.stubFor(post(urlEqualTo(CONTACTS)).willReturn(aResponse().withStatus(401)));

        withClient(client -> assertThatThrownBy(() -> client.createContact(johnDoe()))
                .isInstanceOfSatisfying(UpstreamAuthenticationException.class, failure ->
                        assertThat(failure.getSystem()).isEqualTo(UpstreamSystem.MICROSOFT_DYNAMICS_365)));

        assertThat(createRequests()).hasSize(2);
        assertThat(tokenRequests()).hasSize(2);
    }

    @Test
    void throttledCreateIsARateLimitCarryingRetryAfterAndIsNotRetried() {
        wireMock.stubFor(post(urlEqualTo(CONTACTS)).willReturn(aResponse()
                .withStatus(429)
                .withHeader("Retry-After", "7")
                .withHeader("Content-Type", "application/json")
                .withBody(ODATA_ERROR_JSON)));

        withClient(client -> assertThatThrownBy(() -> client.createContact(johnDoe()))
                .isInstanceOfSatisfying(UpstreamRateLimitException.class, failure -> {
                    assertThat(failure.getSystem()).isEqualTo(UpstreamSystem.MICROSOFT_DYNAMICS_365);
                    assertThat(failure.getRetryAfter()).isEqualTo("7");
                }));

        assertThat(createRequests()).hasSize(1);
        assertThat(tokenRequests()).hasSize(1);
    }

    @Test
    void timedOutCreateIsUnavailableAndNeitherRetriedNorReauthenticated() {
        wireMock.stubFor(post(urlEqualTo(CONTACTS)).willReturn(noContent()
                .withHeader(ODATA_ENTITY_ID, wireMock.baseUrl() + CONTACT_PATH)
                .withFixedDelay(2000)));

        assertTimeoutPreemptively(Duration.ofSeconds(15), () ->
                withClient(overrides("dynamics.read-timeout", "500ms"), client ->
                        assertThatThrownBy(() -> client.createContact(johnDoe()))
                                .isInstanceOfSatisfying(UpstreamUnavailableException.class, failure ->
                                        assertThat(failure.getSystem())
                                                .isEqualTo(UpstreamSystem.MICROSOFT_DYNAMICS_365))
                                .hasRootCauseInstanceOf(ReadTimeoutException.class)));

        assertThat(createRequests()).hasSize(1);
        assertThat(tokenRequests()).hasSize(1);
    }

    @Test
    void refusedConnectionIsUnavailableAndNeitherRetriedNorReauthenticated() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        String serviceUrl = "http://localhost:" + closedPort;

        withClient(overrides("dynamics.service-url", serviceUrl), client ->
                assertThatThrownBy(() -> client.createContact(johnDoe()))
                        .isInstanceOfSatisfying(UpstreamUnavailableException.class, failure -> {
                            assertThat(failure.getSystem()).isEqualTo(UpstreamSystem.MICROSOFT_DYNAMICS_365);
                            assertThat(causeChain(failure)).anySatisfy(cause ->
                                    assertThat(cause).isInstanceOf(ConnectException.class));
                        }));

        List<LoggedRequest> tokens = tokenRequests();
        assertThat(tokens).hasSize(1);
        assertThat(formFields(tokens.get(0))).containsEntry("scope", serviceUrl + "/.default");
        assertThat(wireMock.getAllServeEvents()).hasSize(1);
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {400, 500})
    void otherDataverseFailureIsRethrownUnchangedAndNotRetried(int status) {
        wireMock.stubFor(post(urlEqualTo(CONTACTS)).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(ODATA_ERROR_JSON)));

        withClient(client -> assertThatThrownBy(() -> client.createContact(johnDoe()))
                .isInstanceOfSatisfying(WebClientResponseException.class, failure -> {
                    assertThat(failure.getStatusCode().value()).isEqualTo(status);
                    assertThat(failure.getResponseBodyAsString()).isEqualTo(ODATA_ERROR_JSON);
                }));

        assertThat(createRequests()).hasSize(1);
        assertThat(tokenRequests()).hasSize(1);
    }

    @Test
    void rejectedTokenRequestIsAnAuthenticationFailureAndNoContactIsPosted() {
        wireMock.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse()
                .withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody(ENTRA_INVALID_CLIENT_JSON)));

        withClient(client -> assertThatThrownBy(() -> client.createContact(johnDoe()))
                .isInstanceOfSatisfying(UpstreamAuthenticationException.class, failure -> {
                    assertThat(failure.getSystem()).isEqualTo(UpstreamSystem.MICROSOFT_DYNAMICS_365);
                    assertThat(failure.getMessage()).isEqualTo("AADSTS7000215");
                }));

        assertThat(tokenRequests()).hasSizeBetween(1, 2);
        assertThat(dataverseRequests()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Query and delete: the original test sub-flows selectContactFromDynamics and deleteContactFromDynamics
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void findContactsByEmailSelectsTheOriginalColumnsWithAnEncodedODataFilter() {
        wireMock.stubFor(get(urlPathEqualTo(CONTACTS))
                .withQueryParam("$select", equalTo("contactid,firstname,lastname"))
                .withQueryParam("$filter", equalTo("emailaddress1 eq 'john.doe@texasComp.com'"))
                .willReturn(okJson(JOHN_DOE_QUERY_JSON)));

        withClient(client -> {
            List<Map<String, Object>> contacts = client.findContactsByEmail("john.doe@texasComp.com");
            assertThat(contacts).singleElement().satisfies(contact -> assertThat(contact)
                    .containsEntry("contactid", CONTACT_GUID)
                    .containsEntry("firstname", "John")
                    .containsEntry("lastname", "Doe"));
        });

        List<LoggedRequest> queries = wireMock.findAll(getRequestedFor(urlPathEqualTo(CONTACTS)));
        assertThat(queries).hasSize(1);
        LoggedRequest query = queries.get(0);
        assertDataverseHeaders(query);
        assertThat(query.getUrl()).doesNotContain(" ").contains("%20");
        assertThat(tokenRequests()).hasSize(1);
    }

    @Test
    void findContactsByEmailDoublesSingleQuotesInsideTheODataLiteral() {
        wireMock.stubFor(get(urlPathEqualTo(CONTACTS))
                .withQueryParam("$filter", equalTo("emailaddress1 eq 'o''neil@x.com'"))
                .willReturn(okJson("{\"@odata.context\":\"x\",\"value\":[]}")));

        withClient(client -> assertThat(client.findContactsByEmail("o'neil@x.com")).isEmpty());

        List<LoggedRequest> queries = wireMock.findAll(getRequestedFor(urlPathEqualTo(CONTACTS)));
        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).queryParameter("$filter").firstValue())
                .isEqualTo("emailaddress1 eq 'o''neil@x.com'");
        assertThat(queries.get(0).getUrl()).doesNotContain(" ").doesNotContain("'");
    }

    @Test
    void deleteContactDeletesTheContactByItsGuid() {
        wireMock.stubFor(delete(urlEqualTo(CONTACT_PATH)).willReturn(noContent()));

        withClient(client -> client.deleteContact(CONTACT_GUID));

        List<LoggedRequest> deletes = wireMock.findAll(deleteRequestedFor(urlEqualTo(CONTACT_PATH)));
        assertThat(deletes).hasSize(1);
        assertDataverseHeaders(deletes.get(0));
        assertThat(dataverseRequests()).hasSize(1);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"not-a-guid", "1) or (x"})
    void deleteContactRejectsAnIdThatIsNotAGuidBeforeAnyRequest(String contactId) {
        withClient(client -> assertThatThrownBy(() -> client.deleteContact(contactId))
                .isInstanceOf(IllegalArgumentException.class));

        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Missing configuration: the context starts, the first call fails naming the key
    // ---------------------------------------------------------------------------------------------------------------

    static Stream<Arguments> missingSettings() {
        return Stream.of(
                Arguments.of("dynamics.oauth.client-id", overrides("dynamics.oauth.client-id", "")),
                Arguments.of("dynamics.oauth.client-secret", overrides("dynamics.oauth.client-secret", "")),
                Arguments.of("dynamics.oauth.tenant-id",
                        overrides("dynamics.oauth.tenant-id", "", "dynamics.oauth.token-uri", null)),
                Arguments.of("dynamics.service-url", overrides("dynamics.service-url", "")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("missingSettings")
    void blankSettingFailsTheFirstCallNamingItsKeyBeforeAnyRequest(String key, Map<String, String> overrides) {
        // withClient asserts first that the context started: the dynamics.* settings are read only per call.
        withClient(overrides, client -> assertThatThrownBy(() -> client.createContact(johnDoe()))
                .satisfies(failure -> assertThat(causeChain(failure)).anySatisfy(cause -> {
                    assertThat(cause).isInstanceOf(IllegalStateException.class);
                    assertThat(cause.getMessage()).contains(key);
                })));

        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Fixture helpers
    // ---------------------------------------------------------------------------------------------------------------

    /** The settings every test starts from: Entra and Dataverse both on the WireMock server. */
    private static Map<String, String> defaultSettings() {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("dynamics.service-url", wireMock.baseUrl());
        settings.put("dynamics.oauth.tenant-id", "test-tenant");
        settings.put("dynamics.oauth.client-id", "test-client");
        settings.put("dynamics.oauth.client-secret", "test-secret");
        settings.put("dynamics.oauth.token-uri", wireMock.baseUrl() + TOKEN_PATH);
        // Short enough that no test waits long, long enough that a loaded build host does not time out WireMock.
        settings.put("dynamics.connect-timeout", "2s");
        settings.put("dynamics.read-timeout", "2s");
        return settings;
    }

    /**
     * @param keysAndValues alternating keys and values; a {@code null} value removes the key from the defaults
     * @return the overrides, in a map that admits {@code null} values
     */
    private static Map<String, String> overrides(String... keysAndValues) {
        Map<String, String> overrides = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            overrides.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return overrides;
    }

    private static void withClient(ThrowingConsumer<DynamicsWebApiClient> test) {
        withClient(Map.of(), test);
    }

    /**
     * Starts the application's Dynamics beans over {@link #defaultSettings()} changed by {@code overrides} and hands
     * the context's {@link DynamicsWebApiClient} to {@code test}. Boot's Jackson, codecs and WebClient
     * auto-configuration supply the {@code WebClient.Builder} the Dataverse WebClient bean is built from.
     */
    private static void withClient(Map<String, String> overrides, ThrowingConsumer<DynamicsWebApiClient> test) {
        Map<String, String> settings = defaultSettings();
        overrides.forEach((key, value) -> {
            if (value == null) {
                settings.remove(key);
            } else {
                settings.put(key, value);
            }
        });
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                        CodecsAutoConfiguration.class, WebClientAutoConfiguration.class))
                .withUserConfiguration(DynamicsOAuthConfig.class, UpstreamCallExecutor.class,
                        DynamicsFailureClassifier.class, DynamicsWebApiClient.class)
                .withPropertyValues(settings.entrySet().stream()
                        .map(setting -> setting.getKey() + "=" + setting.getValue())
                        .toArray(String[]::new))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    test.acceptWithException(context.getBean(DynamicsWebApiClient.class));
                });
    }

    /** The attributes of the first {@code contacts.csv} record, in the order the flow sets them. */
    private static Map<String, Object> johnDoe() {
        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("firstname", "John");
        contact.put("lastname", "Doe");
        contact.put("emailaddress1", "john.doe@texasComp.com");
        contact.put("telephone1", "096548763");
        return contact;
    }

    private static void stubCreated() {
        wireMock.stubFor(post(urlEqualTo(CONTACTS))
                .willReturn(noContent().withHeader(ODATA_ENTITY_ID, wireMock.baseUrl() + CONTACT_PATH)));
    }

    private static List<LoggedRequest> tokenRequests() {
        return wireMock.findAll(postRequestedFor(urlEqualTo(TOKEN_PATH)));
    }

    private static List<LoggedRequest> createRequests() {
        return wireMock.findAll(postRequestedFor(urlEqualTo(CONTACTS)));
    }

    /** Every request to the contacts entity set, whatever its method, query or key. */
    private static List<LoggedRequest> dataverseRequests() {
        return wireMock.findAll(anyRequestedFor(urlPathMatching("/api/data/v9\\.2/contacts.*")));
    }

    /** Asserts the bearer token and the single OData version headers every Dataverse request carries. */
    private static void assertDataverseHeaders(LoggedRequest request) {
        assertThat(request.getHeader("Authorization")).isEqualTo(BEARER);
        assertThat(request.getHeaders().getHeader("OData-MaxVersion").values()).containsExactly("4.0");
        assertThat(request.getHeaders().getHeader("OData-Version").values()).containsExactly("4.0");
    }

    /** Decodes an {@code application/x-www-form-urlencoded} request body. */
    private static Map<String, String> formFields(LoggedRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : request.getBodyAsString().split("&")) {
            int separator = pair.indexOf('=');
            String name = separator < 0 ? pair : pair.substring(0, separator);
            String value = separator < 0 ? "" : pair.substring(separator + 1);
            fields.put(URLDecoder.decode(name, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return fields;
    }

    /** The throwable and its causes, stopping at a cause already visited. */
    private static List<Throwable> causeChain(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && visited.add(current); current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }
}
