package com.mulesoft.examples.netsuite_data_retrieval.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.noContent;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.mulesoft.examples.netsuite_data_retrieval.config.NetsuiteProperties;
import com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamRateLimitException;
import com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamUnavailableException;
import io.netty.channel.ChannelOption;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.codec.CodecsAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClientConfig;

/**
 * Tests {@link NetsuiteRestClient} (SC-16) against WireMock standing in for the NetSuite REST services and the OAuth
 * 2.0 token endpoint, with the real {@link NetsuiteTokenProvider} (signing its client assertion with an RSA key
 * generated per run), the real SC-9 executor and the real NetSuite classifier, so the tests need no account and no
 * credential. They cover SuiteQL paging, the record API, the metadata catalog and the custom-field source lookup of
 * AAP 0.7.4 with their caches (concurrent first reads sharing one request included), SuiteQL literal escaping (AAP
 * 0.4.10), every failure classification of AAP 0.4.8 as the client drives it, counting the token and API requests each
 * one makes, the {@link NetsuiteProperties} defaults, and the start-up of the client with every {@code application.yml}
 * placeholder empty.
 *
 * <p>{@code netsuite-data-retrieval-java} carries this class with the client, changing only the package line and
 * the import prefix.
 */
class NetsuiteRestClientTest {

    @RegisterExtension
    static WireMockExtension netsuite = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final String SUITEQL = "/services/rest/query/v1/suiteql";

    private static final String TOKEN = "/services/rest/auth/oauth2/v1/token";

    private static final String CUSTOMER_RECORDS = "/services/rest/record/v1/customer";

    private static final String CUSTOMER_CATALOG = "/services/rest/record/v1/metadata-catalog/customer";

    private static final String CUSTOM_FIELD_QUERY_PREFIX =
            "SELECT scriptid, fieldvaluetyperecord FROM customfield WHERE UPPER(scriptid) IN ";

    /** Callers the concurrency tests release together. */
    private static final int CONCURRENT_CALLERS = 8;

    /**
     * The delay of the stub the concurrency tests' callers share: long enough that every released caller starts its
     * call while the first caller's request is still in flight, on a loaded build host too.
     */
    private static final int IN_FLIGHT_DELAY_MILLIS = 1500;

    /** The read timeout of the concurrency tests' client, well above {@link #IN_FLIGHT_DELAY_MILLIS}. */
    private static final Duration PATIENT_READ_TIMEOUT = Duration.ofSeconds(10);

    /** The longest a concurrency test waits for its callers, so a regression fails the test instead of hanging it. */
    private static final Duration CALLER_TIMEOUT = Duration.ofSeconds(30);

    /**
     * A {@code customfield} lookup page holding a row for every script ID of the concurrency tests, whatever the query
     * asked for; the overridden {@code custentity5}'s row differs from its override.
     */
    private static final String CONCURRENT_LOOKUP_PAGE = """
            {"count": 4, "hasMore": false, "items": [
             {"scriptid": "CUSTENTITY8", "fieldvaluetyperecord": 16},
             {"scriptid": "CUSTENTITY7", "fieldvaluetyperecord": 17},
             {"scriptid": "CUSTENTITY6", "fieldvaluetyperecord": 15},
             {"scriptid": "CUSTENTITY5", "fieldvaluetyperecord": 5}]}""";

    /** NetSuite's REST error format, as NetSuite answers an invalid SuiteQL query. */
    private static final String BAD_REQUEST_BODY = """
            {"type": "https://www.rfc-editor.org/rfc/rfc9110.html#section-15.5.1", "title": "Bad Request",
             "status": 400, "o:errorDetails": [{"detail": "Invalid search query.",
             "o:errorCode": "INVALID_PARAMETER"}]}""";

    private static final String UNAUTHORIZED_BODY = """
            {"type": "https://www.rfc-editor.org/rfc/rfc9110.html#section-15.5.2", "title": "Unauthorized",
             "status": 401, "o:errorDetails": [{"detail": "Invalid login attempt.",
             "o:errorCode": "INVALID_LOGIN"}]}""";

    private static final String OAUTH_ERROR_DESCRIPTION = "Invalid client assertion signature.";

    /** The OAuth 2.0 token error format (RFC 6749, section 5.2), as the token endpoint rejects a client assertion. */
    private static final String OAUTH_ERROR_BODY = "{\"error\": \"invalid_client\", \"error_description\": \""
            + OAUTH_ERROR_DESCRIPTION + "\"}";

    /** One key for the whole class: generating an RSA-2048 pair per test would dominate the run time. */
    private static final KeyPair SIGNING_KEY = rsaKeyPair();

    @TempDir
    Path tmp;

    private NetsuiteProperties props;

    private NetsuiteTokenProvider tokenProvider;

    private NetsuiteRestClient client;

    @BeforeEach
    void setUp() throws IOException {
        // Priority 1 keeps the token endpoint answering when a test stubs every POST with a delay or a fault.
        netsuite.stubFor(post(urlPathEqualTo(TOKEN)).atPriority(1).willReturn(okJson(tokenResponse("tok-1"))));
        props = configuredProperties(netsuite.baseUrl());
        tokenProvider = new NetsuiteTokenProvider(props, WebClient.builder());
        client = newClient(props);
    }

    /** A client over the given settings that shares this test's token provider and its WireMock token endpoint. */
    private NetsuiteRestClient newClient(NetsuiteProperties properties) {
        UpstreamCallExecutor executor = new UpstreamCallExecutor(List.of(new NetsuiteFailureClassifier()));
        return new NetsuiteRestClient(properties, tokenProvider, executor, WebClient.builder());
    }

    /**
     * Settings for a NetSuite reached at {@code baseUrl}, with the OAuth 2.0 M2M credentials the token provider needs.
     * The read timeout is 2 s rather than the 10 s default so that no test waits long, and long enough that a
     * loaded build host does not time out a regular WireMock answer.
     */
    private NetsuiteProperties configuredProperties(String baseUrl) throws IOException {
        NetsuiteProperties properties = new NetsuiteProperties();
        properties.setAccount("TSTDRV1234567");
        properties.setBaseUrl(baseUrl);
        properties.setReadTimeout(Duration.ofSeconds(2));
        properties.getOauth().setClientId("test-client-id");
        properties.getOauth().setCertificateId("test-certificate-id");
        properties.getOauth().setPrivateKeyPath(writePkcs8Pem(tmp).toString());
        return properties;
    }

    private static String tokenResponse(String accessToken) {
        return "{\"access_token\": \"" + accessToken + "\", \"token_type\": \"bearer\", \"expires_in\": \"3600\"}";
    }

    private static KeyPair rsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RSA key generation is unavailable", e);
        }
    }

    /** Writes {@link #SIGNING_KEY}'s private key as the unencrypted PKCS#8 PEM file the token provider reads. */
    private static Path writePkcs8Pem(Path directory) throws IOException {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(SIGNING_KEY.getPrivate().getEncoded());
        Path file = directory.resolve("netsuite-test-key.pem");
        Files.writeString(file, "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n",
                StandardCharsets.US_ASCII);
        return file;
    }

    /**
     * Asserts that exactly one token request and exactly one NetSuite API request were made: the single attempt of a
     * failure that must never be retried.
     */
    private static void assertOneTokenRequestAndOneCall() {
        netsuite.verify(1, postRequestedFor(urlPathEqualTo(TOKEN)));
        assertThat(netsuite.findAll(anyRequestedFor(anyUrl())))
                .filteredOn(request -> !request.getUrl().startsWith(TOKEN))
                .hasSize(1);
    }

    /** The result or the failure of one call made by {@link #callTogether(List)}. */
    private record Outcome<T>(T value, Throwable failure) { }

    /**
     * Makes every call on its own thread of a bounded pool, releasing all of them at once after each thread has
     * started, and returns their outcomes in call order. Every wait is bounded by {@link #CALLER_TIMEOUT}.
     */
    private static <T> List<Outcome<T>> callTogether(List<Callable<T>> calls) throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch started = new CountDownLatch(calls.size());
            CountDownLatch release = new CountDownLatch(1);
            List<Future<T>> pending = new ArrayList<>();
            for (Callable<T> call : calls) {
                pending.add(callers.submit(() -> {
                    started.countDown();
                    if (!release.await(CALLER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                        throw new IllegalStateException("the callers were never released");
                    }
                    return call.call();
                }));
            }
            assertThat(started.await(CALLER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).as("every caller started")
                    .isTrue();
            release.countDown();
            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<T> call : pending) {
                try {
                    outcomes.add(new Outcome<>(call.get(CALLER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), null));
                } catch (ExecutionException e) {
                    outcomes.add(new Outcome<>(null, e.getCause()));
                }
            }
            return outcomes;
        } finally {
            callers.shutdownNow();
        }
    }

    // ---------------------------------------------------------------------------------------------- SuiteQL

    @Test
    void suiteQlPagesUntilHasMoreIsFalseAndStripsLinks() {
        String query = "SELECT email, firstname, lastname FROM customer WHERE lastname LIKE 'O''Br%'\nORDER BY lastname";
        netsuite.stubFor(post(urlPathEqualTo(SUITEQL)).withQueryParam("offset", equalTo("0"))
                .willReturn(okJson("""
                        {"links": [{"rel": "next", "href": "x"}], "count": 2, "hasMore": true, "offset": 0,
                         "items": [
                           {"links": [], "email": "ob@example.com", "firstname": "Ann", "lastname": "OBrien",
                            "balance": 50.0, "id": 107},
                           {"links": [], "email": "oc@example.com", "firstname": "Bob", "lastname": "OBryan"}
                         ]}""")));
        netsuite.stubFor(post(urlPathEqualTo(SUITEQL)).withQueryParam("offset", equalTo("2"))
                .willReturn(okJson("""
                        {"links": [], "count": 1, "hasMore": false, "offset": 2,
                         "items": [{"links": [], "email": "od@example.com", "firstname": "Cy", "lastname": "OBryce"}]}
                        """)));

        List<Map<String, Object>> rows = client.suiteQl(query);

        assertThat(rows).extracting(row -> row.get("lastname")).containsExactly("OBrien", "OBryan", "OBryce");
        assertThat(rows).allSatisfy(row -> assertThat(row).doesNotContainKey("links"));
        assertThat(new ArrayList<>(rows.get(0).keySet()))
                .containsExactly("email", "firstname", "lastname", "balance", "id");
        assertThat(rows.get(0).get("balance")).isEqualTo(new BigDecimal("50.0"));
        assertThat(rows.get(0).get("id")).isEqualTo(107);
        assertThatThrownBy(() -> rows.add(Map.of())).isInstanceOf(UnsupportedOperationException.class);

        for (String offset : List.of("0", "2")) {
            netsuite.verify(postRequestedFor(urlEqualTo(SUITEQL + "?limit=1000&offset=" + offset))
                    .withHeader("Prefer", equalTo("transient"))
                    .withHeader("Content-Type", equalTo("application/json"))
                    .withHeader("Accept", equalTo("application/json"))
                    .withHeader("Authorization", equalTo("Bearer tok-1"))
                    .withRequestBody(equalToJson("{\"q\": \"SELECT email, firstname, lastname FROM customer"
                            + " WHERE lastname LIKE 'O''Br%'\\nORDER BY lastname\"}")));
        }
        netsuite.verify(2, postRequestedFor(urlPathEqualTo(SUITEQL)));
        // Both pages carry the token of the single token request: it is cached until shortly before it expires.
        netsuite.verify(1, postRequestedFor(urlPathEqualTo(TOKEN)));
    }

    @Test
    void suiteQlAdvancesByItemCountWhenCountIsAbsent() {
        netsuite.stubFor(post(urlPathEqualTo(SUITEQL)).withQueryParam("offset", equalTo("0"))
                .willReturn(okJson("{\"hasMore\": true, \"items\": [{\"id\": 1}, {\"id\": 2}, {\"id\": 3}]}")));
        netsuite.stubFor(post(urlPathEqualTo(SUITEQL)).withQueryParam("offset", equalTo("3"))
                .willReturn(okJson("{\"hasMore\": false, \"items\": [{\"id\": 4}]}")));

        assertThat(client.suiteQl("SELECT id FROM customer")).extracting(row -> row.get("id"))
                .containsExactly(1, 2, 3, 4);
        netsuite.verify(2, postRequestedFor(urlPathEqualTo(SUITEQL)));
    }

    @ParameterizedTest
    @MethodSource("pagesEndingTheRead")
    void suiteQlStopsAfterAPageThatEndsTheRead(String pageBody) {
        netsuite.stubFor(post(urlPathEqualTo(SUITEQL)).willReturn(okJson(pageBody)));

        assertThat(client.suiteQl("SELECT id FROM customer")).isEmpty();
        netsuite.verify(1, postRequestedFor(urlPathEqualTo(SUITEQL)));
    }

    static Stream<String> pagesEndingTheRead() {
        return Stream.of(
                "{\"count\": 0, \"hasMore\": false, \"items\": []}",
                // The minimal empty result: count, offset, totalResults and links are optional.
                "{\"items\": [], \"hasMore\": false}",
                // A server that reports more but delivers nothing must not keep the client looping.
                "{\"count\": 0, \"hasMore\": true, \"items\": []}");
    }

    @ParameterizedTest
    @MethodSource("malformedPages")
    void pagedReadsRejectAMalformedPageAfterExactlyOneRequest(String pageBody, String message) {
        netsuite.stubFor(post(urlPathEqualTo(SUITEQL)).willReturn(okJson(pageBody)));
        netsuite.stubFor(get(urlPathEqualTo(CUSTOMER_RECORDS)).willReturn(okJson(pageBody)));

        // A malformed page is a failure, never an empty result: the caller would otherwise show "No customers found".
        assertThatThrownBy(() -> client.suiteQl("SELECT id FROM customer"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(message);
        assertThatThrownBy(() -> client.listRecordIds("customer"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(message);
        netsuite.verify(1, postRequestedFor(urlPathEqualTo(SUITEQL)));
        netsuite.verify(1, getRequestedFor(urlPathEqualTo(CUSTOMER_RECORDS)));
    }

    static Stream<Arguments> malformedPages() {
        return Stream.of(
                Arguments.of("", "NetSuite returned an empty response body"),
                Arguments.of("   ", "NetSuite returned an empty response body"),
                Arguments.of("null", "NetSuite response is not a JSON object"),
                Arguments.of("[]", "Unparsable NetSuite response"),
                Arguments.of("{\"hasMore\": false}", "Unexpected NetSuite response: items is missing"),
                Arguments.of("{\"items\": []}", "Unexpected NetSuite response: hasMore is missing"),
                // Rows without hasMore are not taken for the last page.
                Arguments.of("{\"count\": 1, \"items\": [{\"id\": \"1\"}]}",
                        "Unexpected NetSuite response: hasMore is missing"),
                Arguments.of("{\"items\": {}, \"hasMore\": false}",
                        "Unexpected NetSuite response: items is not an array"),
                Arguments.of("{\"items\": [], \"hasMore\": \"false\"}",
                        "Unexpected NetSuite response: hasMore is not a boolean"));
    }

    @Test
    void suiteQlRejectsANullQueryWithoutARequest() {
        assertThatThrownBy(() -> client.suiteQl(null)).isInstanceOf(IllegalArgumentException.class);
        netsuite.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void suiteQlReportsAnUnparsableBody() {
        netsuite.stubFor(post(urlPathEqualTo(SUITEQL)).willReturn(okJson("{\"items\": [ {\"email\": ")));

        assertThatThrownBy(() -> client.suiteQl("SELECT id FROM customer"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unparsable NetSuite response")
                .hasCauseInstanceOf(JsonProcessingException.class);
    }

    @Test
    void suiteQlRejectsItemsThatAreNotAnArrayOfObjects() {
        netsuite.stubFor(post(urlPathEqualTo(SUITEQL)).willReturn(okJson("{\"hasMore\": false, \"items\": [1]}")));

        assertThatThrownBy(() -> client.suiteQl("SELECT id FROM customer"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not an object");
    }

    @ParameterizedTest
    @MethodSource("literals")
    void suiteQlLiteralDoublesQuotesOnly(String value, String expected) {
        assertThat(NetsuiteRestClient.suiteQlLiteral(value)).isEqualTo(expected);
    }

    /** The result carries no surrounding quotes; backslash, {@code %} and {@code _} pass unchanged. */
    static Stream<Arguments> literals() {
        return Stream.of(
                Arguments.of("O'Brien", "O''Brien"),
                Arguments.of("O'Brien\\%", "O''Brien\\%"),
                Arguments.of("''", "''''"),
                Arguments.of("a\\b", "a\\b"),
                Arguments.of("50%_off", "50%_off"),
                Arguments.of("a_b%", "a_b%"),
                Arguments.of("", ""));
    }

    @Test
    void suiteQlLiteralRejectsNull() {
        assertThatThrownBy(() -> NetsuiteRestClient.suiteQlLiteral(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------------------------------- Record API

    @Test
    void getRecordExpandsSubResourcesAndReturnsTheRecordUnchanged() {
        netsuite.stubFor(get(urlPathEqualTo(CUSTOMER_RECORDS + "/107"))
                .withQueryParam("expandSubResources", equalTo("true"))
                .willReturn(okJson("""
                        {"links": [{"rel": "self", "href": "x"}], "id": "107", "companyName": "Acme",
                         "balance": 12.50, "custentity8": {"id": "3", "refName": "Gold"}}""")));

        Map<String, Object> record = client.getRecord("customer", "107");

        assertThat(new ArrayList<>(record.keySet()))
                .containsExactly("links", "id", "companyName", "balance", "custentity8");
        assertThat(record.get("balance")).isEqualTo(new BigDecimal("12.50"));
        assertThat(record.get("custentity8")).isInstanceOf(LinkedHashMap.class)
                .isEqualTo(Map.of("id", "3", "refName", "Gold"));
        netsuite.verify(getRequestedFor(urlEqualTo(CUSTOMER_RECORDS + "/107?expandSubResources=true"))
                .withHeader("Accept", equalTo("application/json"))
                .withHeader("Authorization", equalTo("Bearer tok-1")));
    }

    @Test
    void recordPathVariablesAreEncodedSoTheyCannotChangeTheRequest() {
        netsuite.stubFor(get(anyUrl()).willReturn(okJson("{}")));

        client.getRecord("customer", "1?expandSubResources=false&x=y");

        netsuite.verify(getRequestedFor(urlEqualTo(
                CUSTOMER_RECORDS + "/1%3FexpandSubResources%3Dfalse%26x%3Dy?expandSubResources=true")));
    }

    @ParameterizedTest
    @MethodSource("blankArguments")
    void recordMethodsRejectBlankTypesAndIdsWithoutARequest(String type, String id) {
        assertThatThrownBy(() -> client.getRecord(type, id)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.deleteRecord(type, id)).isInstanceOf(IllegalArgumentException.class);
        netsuite.verify(0, anyRequestedFor(anyUrl()));
    }

    static Stream<Arguments> blankArguments() {
        return Stream.of(
                Arguments.of(null, "1"),
                Arguments.of(" ", "1"),
                Arguments.of("customer", null),
                Arguments.of("customer", ""));
    }

    @ParameterizedTest
    @MethodSource("bodiesThatAreNotARecord")
    void getRecordRejectsABodyThatIsNotAJsonObject(String body, String message) {
        netsuite.stubFor(get(urlPathEqualTo(CUSTOMER_RECORDS + "/107")).willReturn(okJson(body)));

        assertThatThrownBy(() -> client.getRecord("customer", "107"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(message);
        netsuite.verify(1, getRequestedFor(urlPathEqualTo(CUSTOMER_RECORDS + "/107")));
    }

    static Stream<Arguments> bodiesThatAreNotARecord() {
        return Stream.of(
                Arguments.of("", "NetSuite returned an empty response body"),
                Arguments.of(" \n ", "NetSuite returned an empty response body"),
                Arguments.of("null", "NetSuite response is not a JSON object"));
    }

    @Test
    void listRecordIdsPagesAndSkipsItemsWithoutAnId() {
        netsuite.stubFor(get(urlPathEqualTo(CUSTOMER_RECORDS)).withQueryParam("offset", equalTo("0"))
                .willReturn(okJson("""
                        {"count": 2, "hasMore": true,
                         "items": [{"links": [], "id": "11"}, {"links": [], "id": 12}]}""")));
        netsuite.stubFor(get(urlPathEqualTo(CUSTOMER_RECORDS)).withQueryParam("offset", equalTo("2"))
                .willReturn(okJson("{\"count\": 2, \"hasMore\": false, \"items\": [{\"id\": \"13\"}, {\"links\": []}]}")));

        assertThat(client.listRecordIds("customer")).containsExactly("11", "12", "13");
        netsuite.verify(getRequestedFor(urlEqualTo(CUSTOMER_RECORDS + "?limit=1000&offset=0"))
                .withHeader("Authorization", equalTo("Bearer tok-1")));
        netsuite.verify(getRequestedFor(urlEqualTo(CUSTOMER_RECORDS + "?limit=1000&offset=2")));
        netsuite.verify(2, getRequestedFor(urlPathEqualTo(CUSTOMER_RECORDS)));
    }

    @ParameterizedTest
    @MethodSource("locations")
    void createRecordReturnsTheIdFromLocation(String location) {
        netsuite.stubFor(post(urlEqualTo(CUSTOMER_RECORDS)).willReturn(noContent().withHeader("Location", location)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("companyName", "O'Brien & Co");
        body.put("subsidiary", Map.of("id", "1"));

        assertThat(client.createRecord("customer", body)).isEqualTo("4711");

        netsuite.verify(1, postRequestedFor(urlEqualTo(CUSTOMER_RECORDS))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("Authorization", equalTo("Bearer tok-1"))
                .withRequestBody(equalToJson("{\"companyName\": \"O'Brien & Co\", \"subsidiary\": {\"id\": \"1\"}}")));
    }

    static Stream<String> locations() {
        return Stream.of(
                "https://tstdrv1234567.suitetalk.api.netsuite.com/services/rest/record/v1/customer/4711",
                "https://tstdrv1234567.suitetalk.api.netsuite.com/services/rest/record/v1/customer/4711/",
                "/services/rest/record/v1/customer/4711");
    }

    @Test
    void createRecordWithoutLocationFails() {
        netsuite.stubFor(post(urlEqualTo(CUSTOMER_RECORDS)).willReturn(noContent()));

        assertThatThrownBy(() -> client.createRecord("customer", Map.of("companyName", "Acme")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("NetSuite create customer returned no Location header");
    }

    @Test
    void createRecordRejectsANullBodyWithoutARequest() {
        assertThatThrownBy(() -> client.createRecord("customer", null)).isInstanceOf(IllegalArgumentException.class);
        netsuite.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void deleteRecordSendsDelete() {
        netsuite.stubFor(delete(urlEqualTo(CUSTOMER_RECORDS + "/4711")).willReturn(noContent()));

        client.deleteRecord("customer", "4711");

        netsuite.verify(1, deleteRequestedFor(urlEqualTo(CUSTOMER_RECORDS + "/4711"))
                .withHeader("Authorization", equalTo("Bearer tok-1")));
    }

    @Test
    void deleteRecordPassesNetsuiteErrorsThroughUnwrapped() {
        netsuite.stubFor(delete(urlEqualTo(CUSTOMER_RECORDS + "/4711")).willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"title\": \"Not Found\", \"status\": 404}")));

        assertThatThrownBy(() -> client.deleteRecord("customer", "4711"))
                .isInstanceOf(WebClientResponseException.NotFound.class);
        netsuite.verify(1, deleteRequestedFor(urlEqualTo(CUSTOMER_RECORDS + "/4711")));
    }

    // ------------------------------------------------------------------------------------- Metadata catalog

    @Test
    void metadataCatalogAsksForJsonSchemaAndIsCached() {
        netsuite.stubFor(get(urlEqualTo(CUSTOMER_CATALOG)).willReturn(aResponse()
                .withHeader("Content-Type", "application/schema+json")
                .withBody("{\"type\": \"object\", \"properties\": {\"custentity8\": {\"title\": \"Tier\","
                        + " \"x-ns-custom-field\": true}}}")));

        Map<String, Object> first = client.metadataCatalog("customer");
        Map<String, Object> second = client.metadataCatalog("customer");

        assertThat(second).isSameAs(first);
        assertThat(first).containsEntry("type", "object");
        netsuite.verify(1, getRequestedFor(urlEqualTo(CUSTOMER_CATALOG))
                .withHeader("Accept", equalTo("application/schema+json"))
                .withHeader("Authorization", equalTo("Bearer tok-1")));
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) first.get("properties");
        assertThatThrownBy(() -> first.put("type", "array")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> properties.remove("custentity8")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void metadataCatalogDoesNotCacheAFailure() {
        netsuite.stubFor(get(urlEqualTo(CUSTOMER_CATALOG)).inScenario("catalog").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(500)).willSetStateTo("recovered"));
        netsuite.stubFor(get(urlEqualTo(CUSTOMER_CATALOG)).inScenario("catalog").whenScenarioStateIs("recovered")
                .willReturn(okJson("{\"type\": \"object\", \"properties\": {}}")));

        assertThatThrownBy(() -> client.metadataCatalog("customer"))
                .isInstanceOf(WebClientResponseException.InternalServerError.class);
        assertThat(client.metadataCatalog("customer")).containsEntry("type", "object");
        assertThat(client.metadataCatalog("customer")).containsEntry("type", "object");
        netsuite.verify(2, getRequestedFor(urlEqualTo(CUSTOMER_CATALOG)));
    }

    @ParameterizedTest
    @MethodSource("malformedCatalogs")
    void metadataCatalogRejectsAndDoesNotCacheACatalogWithoutAPropertiesObject(String body, String message) {
        netsuite.stubFor(get(urlEqualTo(CUSTOMER_CATALOG)).inScenario("catalog").whenScenarioStateIs(STARTED)
                .willReturn(okJson(body)).willSetStateTo("recovered"));
        // Only the properties object is required; no other schema keyword.
        netsuite.stubFor(get(urlEqualTo(CUSTOMER_CATALOG)).inScenario("catalog").whenScenarioStateIs("recovered")
                .willReturn(okJson("{\"properties\": {\"custentity8\": {\"type\": \"boolean\"}}}")));

        assertThatThrownBy(() -> client.metadataCatalog("customer"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(message);
        Map<String, Object> recovered = client.metadataCatalog("customer");

        assertThat(recovered).containsOnlyKeys("properties");
        assertThat(client.metadataCatalog("customer")).isSameAs(recovered);
        netsuite.verify(2, getRequestedFor(urlEqualTo(CUSTOMER_CATALOG)));
    }

    static Stream<Arguments> malformedCatalogs() {
        String noProperties =
                "Unexpected NetSuite metadata catalog for record type customer: properties is missing or not an object";
        return Stream.of(
                Arguments.of("", "NetSuite returned an empty response body"),
                Arguments.of("null", "NetSuite response is not a JSON object"),
                Arguments.of("{\"type\": \"object\"}", noProperties),
                Arguments.of("{\"type\": \"object\", \"properties\": null}", noProperties),
                Arguments.of("{\"type\": \"object\", \"properties\": []}", noProperties),
                Arguments.of("{\"type\": \"object\", \"properties\": \"custentity8\"}", noProperties));
    }

    @Test
    void concurrentFirstReadsOfAMetadataCatalogShareOneRequest() throws Exception {
        props.setReadTimeout(PATIENT_READ_TIMEOUT);
        NetsuiteRestClient patient = newClient(props);
        netsuite.stubFor(get(urlEqualTo(CUSTOMER_CATALOG))
                .willReturn(okJson("{\"type\": \"object\", \"properties\": {}}")
                        .withFixedDelay(IN_FLIGHT_DELAY_MILLIS)));
        Callable<Map<String, Object>> read = () -> patient.metadataCatalog("customer");

        List<Outcome<Map<String, Object>>> outcomes = callTogether(Collections.nCopies(CONCURRENT_CALLERS, read));

        assertThat(outcomes).extracting(Outcome::failure).containsOnlyNulls();
        Map<String, Object> catalog = outcomes.get(0).value();
        assertThat(catalog).containsEntry("type", "object");
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.value()).isSameAs(catalog));
        netsuite.verify(1, getRequestedFor(urlEqualTo(CUSTOMER_CATALOG)));
        assertOneTokenRequestAndOneCall();
    }

    @Test
    void concurrentFirstReadsOfAMetadataCatalogShareOneFailureWhichIsNotCached() throws Exception {
        props.setReadTimeout(PATIENT_READ_TIMEOUT);
        NetsuiteRestClient patient = newClient(props);
        netsuite.stubFor(get(urlEqualTo(CUSTOMER_CATALOG)).willReturn(aResponse().withStatus(500)
                .withFixedDelay(IN_FLIGHT_DELAY_MILLIS)));
        Callable<Map<String, Object>> read = () -> patient.metadataCatalog("customer");

        List<Outcome<Map<String, Object>>> outcomes = callTogether(Collections.nCopies(CONCURRENT_CALLERS, read));

        // Every caller fails as a single caller does, from the one request, which no caller repeats.
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.failure())
                .isInstanceOf(WebClientResponseException.InternalServerError.class));
        netsuite.verify(1, getRequestedFor(urlEqualTo(CUSTOMER_CATALOG)));
        assertOneTokenRequestAndOneCall();

        // Newer than the failing stub, so it answers the later read, which the failure did not stop.
        netsuite.stubFor(get(urlEqualTo(CUSTOMER_CATALOG))
                .willReturn(okJson("{\"type\": \"object\", \"properties\": {}}")));
        assertThat(patient.metadataCatalog("customer")).containsEntry("type", "object");
        netsuite.verify(2, getRequestedFor(urlEqualTo(CUSTOMER_CATALOG)));
    }


    // -------------------------------------------------------------------------- Custom-field source lookup

    @Test
    void customFieldSourceIdsSendsOneUpperCasedEscapedQueryAndRendersTheRawSources() {
        stubCustomFieldLookup("'CUSTENTITY8', 'CUSTENTITY7', 'CUSTENTITY_FMT_CUR_SALES_ORDER', 'CUSTENTITY6',"
                + " 'CUST''X', 'CUSTENTITY_MISSING', 'CUSTENTITY_DECIMAL'", """
                {"scriptid": "CUSTENTITY8", "fieldvaluetyperecord": 16},
                {"scriptid": "CUSTENTITY7", "fieldvaluetyperecord": " 17 "},
                {"SCRIPTID": "custentity_fmt_cur_sales_order", "FieldValueTypeRecord": -30},
                {"scriptid": "CUSTENTITY6", "fieldvaluetyperecord": 15.0},
                {"scriptid": "CUST'X", "fieldvaluetyperecord": "-112"},
                {"scriptid": "CUSTENTITY_MISSING", "fieldvaluetyperecord": null},
                {"scriptid": "CUSTENTITY_DECIMAL", "fieldvaluetyperecord": 1.50}""");

        Map<String, String> sources = client.customFieldSourceIds(Arrays.asList("custentity8", "CUSTENTITY7",
                "custentity_fmt_cur_sales_order", "custentity6", "cust'x", "custentity_missing", "CustEntity8", " ",
                null, "custentity_decimal"));

        // The committed customers-response.json values (custentity8 16, custentity7 17, custentity6 15,
        // custentity_fmt_cur_sales_order -30), in first-seen order; the unresolved ID is absent.
        assertThat(new ArrayList<>(sources.entrySet())).containsExactly(
                Map.entry("custentity8", "16"),
                Map.entry("custentity7", "17"),
                Map.entry("custentity_fmt_cur_sales_order", "-30"),
                Map.entry("custentity6", "15"),
                Map.entry("cust'x", "-112"),
                Map.entry("custentity_decimal", "1.50"));
        assertThatThrownBy(() -> sources.put("custentity9", "1")).isInstanceOf(UnsupportedOperationException.class);
        netsuite.verify(1, postRequestedFor(urlPathEqualTo(SUITEQL))
                .withHeader("Prefer", equalTo("transient")));
    }

    @Test
    void customFieldSourceIdsServesResolvedIdsFromTheCacheAndLooksUnresolvedOnesUpAgain() {
        stubCustomFieldLookup("'CUSTENTITY8', 'CUSTENTITY_MISSING'",
                "{\"scriptid\": \"CUSTENTITY8\", \"fieldvaluetyperecord\": 16}");
        stubCustomFieldLookup("'CUSTENTITY_MISSING'", "");

        assertThat(client.customFieldSourceIds(List.of("custentity8", "custentity_missing")))
                .containsExactly(Map.entry("custentity8", "16"));
        assertThat(client.customFieldSourceIds(List.of("CUSTENTITY8"))).containsExactly(Map.entry("custentity8", "16"));
        netsuite.verify(1, postRequestedFor(urlPathEqualTo(SUITEQL)));

        assertThat(client.customFieldSourceIds(List.of("custentity8", "custentity_missing")))
                .containsExactly(Map.entry("custentity8", "16"));
        netsuite.verify(2, postRequestedFor(urlPathEqualTo(SUITEQL)));
        netsuite.verify(1, postRequestedFor(urlPathEqualTo(SUITEQL))
                .withRequestBody(equalToJson(customFieldQueryJson("'CUSTENTITY_MISSING'"))));
    }

    @Test
    void customFieldSourceIdsLetsOverridesWinOverLookupAndCache() {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("CustEntity8", " 99 ");
        overrides.put("custentity7", "  ");
        props.setCustomFieldTypeIds(overrides);
        stubCustomFieldLookup("'CUSTENTITY7'", "{\"scriptid\": \"CUSTENTITY7\", \"fieldvaluetyperecord\": 17}");

        assertThat(client.customFieldSourceIds(List.of("custentity8", "custentity7")))
                .containsExactly(Map.entry("custentity8", "99"), Map.entry("custentity7", "17"));
        netsuite.verify(1, postRequestedFor(urlPathEqualTo(SUITEQL)));

        // custentity7 is now cached as 17; a later override still takes precedence.
        props.setCustomFieldTypeIds(Map.of("CUSTENTITY7", "42"));
        assertThat(client.customFieldSourceIds(List.of("custentity7"))).containsExactly(Map.entry("custentity7", "42"));
        netsuite.verify(1, postRequestedFor(urlPathEqualTo(SUITEQL)));
    }

    @ParameterizedTest
    @MethodSource("scriptIdsNeedingNoLookup")
    void customFieldSourceIdsSendsNoRequestWhenNothingIsLeftToLookUp(List<String> scriptIds) {
        props.setCustomFieldTypeIds(Map.of("custentity8", "16"));

        Map<String, String> sources = client.customFieldSourceIds(scriptIds);

        assertThat(sources).allSatisfy((id, source) -> assertThat(id).isEqualTo("custentity8"));
        netsuite.verify(0, anyRequestedFor(anyUrl()));
    }

    static Stream<List<String>> scriptIdsNeedingNoLookup() {
        return Stream.of(null, List.of(), Arrays.asList(" ", null, ""), List.of("custentity8"));
    }

    /**
     * Concurrent calls over shared script IDs query each ID once between them, with no call waiting on another
     * forever (the reversed sets make each call claim an ID the other needs first), and the overridden
     * {@code custentity5} is never queried; every call gets its own IDs' sources in its own order.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("concurrentScriptIdSets")
    void concurrentLookupsQueryEachScriptIdOnceAndKeepOverridesFirst(String name, List<String> firstHalf,
            List<String> secondHalf) throws Exception {
        props.setCustomFieldTypeIds(Map.of("custentity5", "55"));
        props.setReadTimeout(PATIENT_READ_TIMEOUT);
        NetsuiteRestClient patient = newClient(props);
        stubEveryCustomFieldLookup(okJson(CONCURRENT_LOOKUP_PAGE).withFixedDelay(IN_FLIGHT_DELAY_MILLIS));
        List<List<String>> callerIds = new ArrayList<>(Collections.nCopies(CONCURRENT_CALLERS / 2, firstHalf));
        callerIds.addAll(Collections.nCopies(CONCURRENT_CALLERS / 2, secondHalf));
        List<Callable<Map<String, String>>> calls = new ArrayList<>();
        callerIds.forEach(ids -> calls.add(() -> patient.customFieldSourceIds(ids)));

        List<Outcome<Map<String, String>>> outcomes = callTogether(calls);

        Map<String, String> expected = Map.of("custentity8", "16", "custentity7", "17", "custentity6", "15",
                "custentity5", "55");
        for (int caller = 0; caller < outcomes.size(); caller++) {
            Outcome<Map<String, String>> outcome = outcomes.get(caller);
            assertThat(outcome.failure()).isNull();
            assertThat(new ArrayList<>(outcome.value().entrySet())).containsExactlyElementsOf(callerIds.get(caller)
                    .stream()
                    .map(id -> id.toLowerCase(Locale.ROOT))
                    .map(id -> Map.entry(id, expected.get(id)))
                    .toList());
        }
        // Each ID the callers need is queried once between them; the overridden one never.
        Map<String, Integer> lookups = new LinkedHashMap<>();
        callerIds.forEach(ids -> ids.forEach(id -> lookups.put(id.toUpperCase(Locale.ROOT), 1)));
        lookups.put("CUSTENTITY5", 0);
        assertLookupsPerScriptId(lookups);
    }

    static Stream<Arguments> concurrentScriptIdSets() {
        return Stream.of(
                Arguments.of("identical", List.of("custentity8", "custentity7", "custentity5"),
                        List.of("CustEntity8", "custentity7", "custentity5")),
                Arguments.of("overlapping", List.of("custentity8", "custentity7", "custentity5"),
                        List.of("CUSTENTITY7", "custentity6", "custentity5")),
                Arguments.of("reversed", List.of("custentity8", "custentity7", "custentity6", "custentity5"),
                        List.of("custentity5", "CUSTENTITY6", "custentity7", "custentity8")));
    }

    @Test
    void concurrentLookupsShareOneFailureAndLeaveItsScriptIdsToALaterLookup() throws Exception {
        props.setReadTimeout(PATIENT_READ_TIMEOUT);
        NetsuiteRestClient patient = newClient(props);
        stubEveryCustomFieldLookup(aResponse().withStatus(500).withFixedDelay(IN_FLIGHT_DELAY_MILLIS));
        List<Callable<Map<String, String>>> calls = new ArrayList<>();
        for (int caller = 0; caller < CONCURRENT_CALLERS; caller++) {
            List<String> ids = caller % 2 == 0
                    ? List.of("custentity8", "custentity7")
                    : List.of("CUSTENTITY7", "custentity6");
            calls.add(() -> patient.customFieldSourceIds(ids));
        }

        List<Outcome<Map<String, String>>> outcomes = callTogether(calls);

        // Every caller fails as a single caller does: those waiting on another's lookup with that lookup's failure.
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.failure())
                .isInstanceOf(WebClientResponseException.InternalServerError.class));
        assertLookupsPerScriptId(Map.of("CUSTENTITY8", 1, "CUSTENTITY7", 1, "CUSTENTITY6", 1));

        // Newer than the failing stub, so it answers the later lookup, which the failure left every ID to.
        stubEveryCustomFieldLookup(okJson(CONCURRENT_LOOKUP_PAGE));
        assertThat(patient.customFieldSourceIds(List.of("custentity8", "custentity7", "custentity6"))).containsExactly(
                Map.entry("custentity8", "16"), Map.entry("custentity7", "17"), Map.entry("custentity6", "15"));
        assertLookupsPerScriptId(Map.of("CUSTENTITY8", 2, "CUSTENTITY7", 2, "CUSTENTITY6", 2));
    }

    @Test
    void customFieldSourceIdsLooksUpAtMostOneThousandScriptIdsPerQuery() {
        List<String> scriptIds = IntStream.rangeClosed(0, 1000).mapToObj(i -> "custentity_" + i).toList();
        // Every query is answered with both rows; each batch takes only the rows of the IDs it asked for.
        stubEveryCustomFieldLookup(okJson("""
                {"count": 2, "hasMore": false, "items": [
                 {"scriptid": "CUSTENTITY_0", "fieldvaluetyperecord": 10},
                 {"scriptid": "CUSTENTITY_1000", "fieldvaluetyperecord": 11}]}"""));

        assertThat(client.customFieldSourceIds(scriptIds))
                .containsExactly(Map.entry("custentity_0", "10"), Map.entry("custentity_1000", "11"));

        // Oracle's IN-list limit: the first 1000 IDs in one query, the last one in a second.
        List<String> queries = netsuite.findAll(postRequestedFor(urlPathEqualTo(SUITEQL))).stream()
                .map(request -> request.getBodyAsString())
                .toList();
        assertThat(queries).extracting(query -> query.split("'CUSTENTITY_", -1).length - 1)
                .containsExactlyInAnyOrder(1000, 1);
        assertThat(queries).filteredOn(query -> query.contains("('CUSTENTITY_1000')")).hasSize(1);
    }

    private static void stubEveryCustomFieldLookup(ResponseDefinitionBuilder response) {
        netsuite.stubFor(post(urlPathEqualTo(SUITEQL)).withRequestBody(containing(CUSTOM_FIELD_QUERY_PREFIX))
                .willReturn(response));
    }

    /** Asserts how many of the logged SuiteQL requests asked for each upper-cased script ID. */
    private static void assertLookupsPerScriptId(Map<String, Integer> expected) {
        List<String> queries = netsuite.findAll(postRequestedFor(urlPathEqualTo(SUITEQL))).stream()
                .map(request -> request.getBodyAsString())
                .toList();
        expected.forEach((scriptId, lookups) -> assertThat(queries)
                .as("lookups of %s", scriptId)
                .filteredOn(query -> query.contains("'" + scriptId + "'"))
                .hasSize(lookups));
    }

    private void stubCustomFieldLookup(String inList, String rows) {
        netsuite.stubFor(post(urlPathEqualTo(SUITEQL))
                .withRequestBody(equalToJson(customFieldQueryJson(inList)))
                .willReturn(okJson("{\"count\": 1, \"hasMore\": false, \"items\": [" + rows + "]}")));
    }

    private static String customFieldQueryJson(String inList) {
        return "{\"q\": \"" + CUSTOM_FIELD_QUERY_PREFIX + "(" + inList + ")\"}";
    }

    // ------------------------------------------------------------------------------------ Failure handling

    @Test
    void unauthorizedEvictsTheTokenAndRetriesOnceWithAFreshOne() {
        // Newer than the fixture's token stub at the same priority, so these answer first: tok-1, then tok-2.
        netsuite.stubFor(post(urlPathEqualTo(TOKEN)).atPriority(1).inScenario("token").whenScenarioStateIs(STARTED)
                .willReturn(okJson(tokenResponse("tok-1"))).willSetStateTo("renewed"));
        netsuite.stubFor(post(urlPathEqualTo(TOKEN)).atPriority(1).inScenario("token").whenScenarioStateIs("renewed")
                .willReturn(okJson(tokenResponse("tok-2"))));
        netsuite.stubFor(post(urlPathEqualTo(SUITEQL)).inScenario("auth").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(401).withHeader("Content-Type", "application/json")
                        .withBody(UNAUTHORIZED_BODY))
                .willSetStateTo("renewed"));
        netsuite.stubFor(post(urlPathEqualTo(SUITEQL)).inScenario("auth").whenScenarioStateIs("renewed")
                .willReturn(okJson("{\"hasMore\": false, \"items\": [{\"links\": [], \"lastname\": \"Abbott\"}]}")));

        assertThat(client.suiteQl("SELECT lastname FROM customer")).containsExactly(Map.of("lastname", "Abbott"));

        // The 401 evicted the cached token, so the single retry asked the token endpoint again.
        netsuite.verify(2, postRequestedFor(urlPathEqualTo(TOKEN)));
        netsuite.verify(2, postRequestedFor(urlPathEqualTo(SUITEQL)));
        netsuite.verify(1, postRequestedFor(urlPathEqualTo(SUITEQL))
                .withHeader("Authorization", equalTo("Bearer tok-1")));
        netsuite.verify(1, postRequestedFor(urlPathEqualTo(SUITEQL))
                .withHeader("Authorization", equalTo("Bearer tok-2")));
    }

    @Test
    void unauthorizedRetryThatIsRejectedAgainEndsTheAttempt() {
        netsuite.stubFor(post(urlPathEqualTo(SUITEQL)).willReturn(aResponse().withStatus(401)
                .withHeader("Content-Type", "application/json").withBody(UNAUTHORIZED_BODY)));

        assertThatThrownBy(() -> client.suiteQl("SELECT id FROM customer"))
                .isInstanceOfSatisfying(UpstreamAuthenticationException.class,
                        e -> assertThat(e.getSystem()).isEqualTo(UpstreamSystem.NETSUITE));
        netsuite.verify(2, postRequestedFor(urlPathEqualTo(TOKEN)));
        netsuite.verify(2, postRequestedFor(urlPathEqualTo(SUITEQL)));
    }

    static Stream<Arguments> tokenRejections() {
        return Stream.of(
                Arguments.of("OAuth 2.0 error body", aResponse().withStatus(401)
                        .withHeader("Content-Type", "application/json").withBody(OAUTH_ERROR_BODY),
                        OAUTH_ERROR_DESCRIPTION),
                Arguments.of("empty body", aResponse().withStatus(401), "NetSuite answered HTTP 401 Unauthorized"));
    }

    /**
     * A token endpoint that rejects the client is an authentication failure whose message is NetSuite's text, or its
     * status, and never {@code WebClient}'s exception message, which names the token URL.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("tokenRejections")
    void tokenEndpointRejectionIsAuthenticationWithoutTheTokenUrl(String name, ResponseDefinitionBuilder rejection,
            String expected) {
        // Newer than the fixture's token stub at the same priority, so this answers every token request.
        netsuite.stubFor(post(urlPathEqualTo(TOKEN)).atPriority(1).willReturn(rejection));

        assertThatThrownBy(() -> client.suiteQl("SELECT id FROM customer"))
                .isInstanceOfSatisfying(UpstreamAuthenticationException.class, e -> {
                    assertThat(e.getSystem()).isEqualTo(UpstreamSystem.NETSUITE);
                    assertThat(e.getCause()).hasMessageContaining(netsuite.baseUrl() + TOKEN);
                    assertThat(e).hasMessage(expected);
                    assertThat(e.getMessage()).doesNotContain(netsuite.baseUrl()).doesNotContain(TOKEN);
                });
        // The eviction and the one retry ask the token endpoint again; SuiteQL is never reached.
        netsuite.verify(2, postRequestedFor(urlPathEqualTo(TOKEN)));
        netsuite.verify(0, postRequestedFor(urlPathEqualTo(SUITEQL)));
    }

    @Test
    void rateLimitIsNotRetriedAndCarriesRetryAfter() {
        netsuite.stubFor(post(urlPathEqualTo(SUITEQL)).willReturn(aResponse().withStatus(429)
                .withHeader("Retry-After", "17")));

        assertThatThrownBy(() -> client.suiteQl("SELECT id FROM customer"))
                .isInstanceOfSatisfying(UpstreamRateLimitException.class, e -> {
                    assertThat(e.getRetryAfter()).isEqualTo("17");
                    assertThat(e.getSystem()).isEqualTo(UpstreamSystem.NETSUITE);
                });
        netsuite.verify(1, postRequestedFor(urlPathEqualTo(SUITEQL)));
        netsuite.verify(1, postRequestedFor(urlPathEqualTo(TOKEN)));
    }

    @Test
    void badRequestIsRethrownUnchangedWithoutReauthentication() {
        netsuite.stubFor(post(urlPathEqualTo(SUITEQL)).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json").withBody(BAD_REQUEST_BODY)));

        // OTHER: NetSuite's own exception reaches the example's exception branch, not an upstream exception.
        assertThatThrownBy(() -> client.suiteQl("SELECT nonsense FROM customer"))
                .isInstanceOfSatisfying(WebClientResponseException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        netsuite.verify(1, postRequestedFor(urlPathEqualTo(SUITEQL)));
        netsuite.verify(1, postRequestedFor(urlPathEqualTo(TOKEN)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("timedOutCalls")
    void readTimeoutIsUnavailableAndIsNeverRetried(String name, TimedOutCall call) {
        Duration stubDelay = Duration.ofMillis(1500);
        props.setReadTimeout(Duration.ofMillis(300));
        // The fixture's token provider keeps its own 2 s transport, so only the NetSuite API call times out.
        NetsuiteRestClient timingOut = newClient(props);
        netsuite.stubFor(post(anyUrl()).willReturn(okJson("{}").withFixedDelay((int) stubDelay.toMillis())));

        // The real WebClient chain carries no message, so the caller sees the neutral description, never a class.
        assertThatThrownBy(() -> call.invoke(timingOut)).isInstanceOf(UpstreamUnavailableException.class)
                .hasMessage("NetSuite did not respond in time");

        // Wait out the stub delay, so a retry sent after the timeout would have been recorded by now.
        await().pollDelay(stubDelay).atMost(stubDelay.plusSeconds(5))
                .untilAsserted(NetsuiteRestClientTest::assertOneTokenRequestAndOneCall);
    }

    static Stream<Arguments> timedOutCalls() {
        return Stream.of(
                Arguments.of("suiteQl", (TimedOutCall) c -> c.suiteQl("SELECT id FROM customer")),
                // A write is never repeated after a timeout: NetSuite may already have created the record.
                Arguments.of("createRecord", (TimedOutCall) c -> c.createRecord("customer", Map.of("companyName", "A"))));
    }

    @FunctionalInterface
    interface TimedOutCall {
        void invoke(NetsuiteRestClient client);
    }

    /**
     * NetSuite's side closing or resetting the connection, before or during the response, is unavailable after
     * exactly one request, with no token eviction: the failure reaches the classifier as the real {@code WebClient}
     * chain (for example Reactor Netty's {@code PrematureCloseException}, or a connection reset reported by the
     * native or the JDK transport), not as a hand-built cause.
     */
    @ParameterizedTest(name = "{0}: {2}")
    @MethodSource("droppedConnections")
    void droppedConnectionIsUnavailableAfterExactlyOneRequest(String name, TimedOutCall call, Fault fault) {
        netsuite.stubFor(post(anyUrl()).willReturn(aResponse().withFault(fault)));

        assertThatThrownBy(() -> call.invoke(client)).isInstanceOfSatisfying(UpstreamUnavailableException.class,
                e -> assertThat(e.getSystem()).isEqualTo(UpstreamSystem.NETSUITE));

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(NetsuiteRestClientTest::assertOneTokenRequestAndOneCall);
        assertOneTokenRequestAndOneCall();
    }

    /** Each call of {@link #timedOutCalls()} against each way WireMock drops the connection. */
    static Stream<Arguments> droppedConnections() {
        return timedOutCalls().flatMap(call -> Stream.of(Fault.EMPTY_RESPONSE, Fault.CONNECTION_RESET_BY_PEER,
                        Fault.RANDOM_DATA_THEN_CLOSE, Fault.MALFORMED_RESPONSE_CHUNK)
                .map(fault -> Arguments.of(call.get()[0], call.get()[1], fault)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("timedOutCalls")
    void connectionResetBeforeTheRequestIsSentIsNotRetried(String name, TimedOutCall call) throws IOException {
        // Holding the body back lets the reset arrive before the request headers are sent: the case in which Reactor
        // Netty would otherwise send the request again on a new connection, unseen by the executor.
        ExchangeFilterFunction holdBackBody = (request, next) -> next.exchange(ClientRequest.from(request)
                .body((message, context) -> Mono.delay(Duration.ofMillis(500))
                        .then(Mono.defer(() -> request.body().insert(message, context))))
                .build());
        // The token is fetched from WireMock and cached first, so the resetting server sees only the API request.
        assertThat(tokenProvider.accessToken()).isEqualTo("tok-1");
        try (ResettingServer server = new ResettingServer()) {
            props.setBaseUrl(server.baseUrl());
            NetsuiteRestClient resetting = new NetsuiteRestClient(props, tokenProvider,
                    new UpstreamCallExecutor(List.of(new NetsuiteFailureClassifier())),
                    WebClient.builder().filter(holdBackBody));

            assertThatThrownBy(() -> call.invoke(resetting)).isInstanceOf(RuntimeException.class);

            // A transport retry or a re-authentication (whose token request now targets the resetting server too)
            // would show as a second connection.
            await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
                    .until(server.connections::get, count -> count == 1);
            netsuite.verify(1, postRequestedFor(urlPathEqualTo(TOKEN)));
        }
    }

    @Test
    void closedPortIsUnavailableWithAConnectExceptionAndIsNeverRetried() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        NetsuiteProperties refusing = configuredProperties("http://localhost:" + port);
        NetsuiteRestClient unreachable = new NetsuiteRestClient(refusing,
                new NetsuiteTokenProvider(refusing, WebClient.builder()),
                new UpstreamCallExecutor(List.of(new NetsuiteFailureClassifier())), WebClient.builder());

        // The token request is the first to meet the refused connection; it runs inside the executor's attempt.
        assertThatThrownBy(() -> unreachable.suiteQl("SELECT id FROM customer"))
                .isInstanceOfSatisfying(UpstreamUnavailableException.class, e -> {
                    assertThat(e.getSystem()).isEqualTo(UpstreamSystem.NETSUITE);
                    // Netty's AnnotatedConnectException and the native transport's exception both extend it.
                    assertThat(causeChain(e)).anyMatch(ConnectException.class::isInstance);
                    // The transport's message names the refused address; the caller's message names neither part.
                    assertThat(e).hasMessage("NetSuite is unreachable");
                    assertThat(e.getMessage()).doesNotContain("localhost").doesNotContain(String.valueOf(port));
                });
        netsuite.verify(0, anyRequestedFor(anyUrl()));
    }

    /** The throwable and its causes, each once, so a cyclic chain ends. */
    private static List<Throwable> causeChain(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }

    /**
     * A server on a free loopback port that accepts every connection and resets it at once (linger 0, then close),
     * counting the connections it accepted.
     */
    private static final class ResettingServer implements AutoCloseable {

        private final ServerSocket socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));

        private final AtomicInteger connections = new AtomicInteger();

        private final AtomicReference<IOException> failure = new AtomicReference<>();

        ResettingServer() throws IOException {
            Thread acceptor = new Thread(this::resetEveryConnection, "netsuite-resetting-server");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }

        private void resetEveryConnection() {
            try {
                while (true) {
                    try (Socket connection = socket.accept()) {
                        connections.incrementAndGet();
                        connection.setSoLinger(true, 0);
                    }
                }
            } catch (IOException e) {
                // accept() fails this way once close() has closed the socket; any other failure is the test's.
                if (!socket.isClosed()) {
                    failure.set(e);
                }
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
            IOException failed = failure.get();
            if (failed != null) {
                throw failed;
            }
        }
    }

    // -------------------------------------------------------------------------------------- Configuration

    @Test
    void missingAccountFailsTheFirstCallNamingTheKeyBeforeAnyRequest() {
        NetsuiteRestClient unconfigured = newClient(new NetsuiteProperties());

        assertThatThrownBy(() -> unconfigured.suiteQl("SELECT id FROM customer"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("netsuite.account");
        // Not even the token request is made: WireMock serves the token endpoint too.
        netsuite.verify(0, anyRequestedFor(anyUrl()));
    }

    @ParameterizedTest(name = "{0} = {2}")
    @MethodSource("invalidTimeouts")
    void invalidTimeoutFailsStartUpNamingTheKeyBeforeAnyRequest(String key,
            BiConsumer<NetsuiteProperties, Duration> setting, Duration timeout, String message) {
        setting.accept(props, timeout);

        assertThatThrownBy(() -> newClient(props)).isInstanceOf(IllegalStateException.class).hasMessage(message);
        assertThatThrownBy(() -> NetsuiteRestClient.connector(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(message);
        // The token request runs on the same transport, so the token provider fails start-up the same way.
        assertThatThrownBy(() -> new NetsuiteTokenProvider(props, WebClient.builder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(message);
        assertThat(message).startsWith(key + " ");
        netsuite.verify(0, anyRequestedFor(anyUrl()));
    }

    static Stream<Arguments> invalidTimeouts() {
        String connect = "netsuite.connect-timeout";
        String read = "netsuite.read-timeout";
        String tooShort = " must be a positive duration of at least 1 ms";
        BiConsumer<NetsuiteProperties, Duration> setConnect = NetsuiteProperties::setConnectTimeout;
        BiConsumer<NetsuiteProperties, Duration> setRead = NetsuiteProperties::setReadTimeout;
        // Zero is Netty's "no timeout"; a positive sub-millisecond value would be truncated to it.
        return Stream.of(
                Arguments.of(connect, setConnect, Duration.ZERO, connect + tooShort),
                Arguments.of(connect, setConnect, Duration.ofMillis(-1), connect + tooShort),
                Arguments.of(connect, setConnect, Duration.ofNanos(999_999), connect + tooShort),
                Arguments.of(connect, setConnect, Duration.ofMillis(Integer.MAX_VALUE + 1L),
                        connect + " must not exceed 2147483647 ms"),
                Arguments.of(read, setRead, Duration.ZERO, read + tooShort),
                Arguments.of(read, setRead, Duration.ofMillis(-1), read + tooShort),
                Arguments.of(read, setRead, Duration.ofNanos(999_999), read + tooShort),
                Arguments.of(read, setRead, Duration.ofSeconds(Long.MAX_VALUE),
                        read + " must not exceed 9223372036854775807 ms"));
    }

    @ParameterizedTest(name = "connect {0}, read {1}")
    @MethodSource("acceptedTimeouts")
    void acceptedTimeoutsReachTheTransportWhichNeverRetries(Duration connect, Duration read, int connectMillis,
            Duration responseTimeout) {
        NetsuiteProperties properties = mock(NetsuiteProperties.class);
        when(properties.getConnectTimeout()).thenReturn(connect);
        when(properties.getReadTimeout()).thenReturn(read);

        HttpClientConfig transport = NetsuiteRestClient.httpClient(properties).configuration();

        assertThat(transport.options().get(ChannelOption.CONNECT_TIMEOUT_MILLIS)).isEqualTo(connectMillis);
        assertThat(transport.responseTimeout()).isEqualTo(responseTimeout);
        assertThat(transport.isRetryDisabled()).isTrue();
        assertThatCode(() -> newClient(properties)).doesNotThrowAnyException();
    }

    static Stream<Arguments> acceptedTimeouts() {
        return Stream.of(
                // A setting that returns null gets the 10 s default, the Mule default response timeout.
                Arguments.of(null, null, 10_000, Duration.ofSeconds(10)),
                Arguments.of(Duration.ofMillis(1), Duration.ofMillis(1), 1, Duration.ofMillis(1)),
                Arguments.of(Duration.ofMillis(Integer.MAX_VALUE), Duration.ofMillis(Long.MAX_VALUE),
                        Integer.MAX_VALUE, Duration.ofMillis(Long.MAX_VALUE)));
    }

    @Test
    void propertiesDefaultToTheMuleTimeoutsTheAccountTimeZoneAndNoOverrides() {
        NetsuiteProperties defaults = new NetsuiteProperties();

        assertThat(defaults.getConnectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(defaults.getReadTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(defaults.getTimeZone()).isEqualTo(ZoneId.of("America/Los_Angeles"));
        assertThat(defaults.getCustomFieldTypeIds()).isNotNull().isEmpty();

        // null restores each default, so no setting is ever left null.
        defaults.setConnectTimeout(null);
        defaults.setReadTimeout(null);
        defaults.setTimeZone(null);
        defaults.setCustomFieldTypeIds(null);
        assertThat(defaults.getConnectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(defaults.getReadTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(defaults.getTimeZone()).isEqualTo(ZoneId.of("America/Los_Angeles"));
        assertThat(defaults.getCustomFieldTypeIds()).isNotNull().isEmpty();
    }

    @Test
    void contextStartsWithTheEmptyPlaceholdersOfApplicationYml() {
        contextRunner().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(NetsuiteRestClient.class);
            assertThatThrownBy(() -> context.getBean(NetsuiteRestClient.class).suiteQl("SELECT id FROM customer"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("netsuite.account");
        });
    }

    @Test
    void contextWithABaseUrlButNoOauthCredentialsFailsInTheTokenProviderNamingTheKey() {
        contextRunner().withPropertyValues("netsuite.base-url=" + netsuite.baseUrl()).run(context -> {
            assertThatThrownBy(() -> context.getBean(NetsuiteRestClient.class).suiteQl("SELECT id FROM customer"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("netsuite.oauth.");
        });
        netsuite.verify(0, anyRequestedFor(anyUrl()));
    }

    /*
     * The only Spring context and the only classpath read of this class: just binding the project's own
     * application.yml and registering the beans as component scan does shows that the client starts with every
     * credential placeholder empty and that Spring selects the token provider's @Autowired constructor. Both SC-16
     * consumers carry the same empty netsuite.* placeholders, so the copy stays project-neutral.
     */
    private static ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                        CodecsAutoConfiguration.class, WebClientAutoConfiguration.class))
                .withUserConfiguration(ClientConfiguration.class);
    }

    /** The SC-16 and SC-9 beans as the application's component scan registers them. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties
    @Import({NetsuiteProperties.class, NetsuiteTokenProvider.class, NetsuiteFailureClassifier.class,
            UpstreamCallExecutor.class, NetsuiteRestClient.class})
    static class ClientConfiguration {
    }
}

