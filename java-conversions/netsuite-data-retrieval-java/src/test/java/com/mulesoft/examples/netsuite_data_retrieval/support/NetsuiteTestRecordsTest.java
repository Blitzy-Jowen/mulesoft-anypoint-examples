package com.mulesoft.examples.netsuite_data_retrieval.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.noContent;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.mulesoft.examples.netsuite_data_retrieval.client.NetsuiteFailureClassifier;
import com.mulesoft.examples.netsuite_data_retrieval.client.NetsuiteRestClient;
import com.mulesoft.examples.netsuite_data_retrieval.client.NetsuiteTokenProvider;
import com.mulesoft.examples.netsuite_data_retrieval.client.UpstreamCallExecutor;
import com.mulesoft.examples.netsuite_data_retrieval.config.NetsuiteProperties;
import com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamUnavailableException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Tests {@link NetsuiteTestRecords#withTestCustomer}, the create → assert → delete bracket {@code NetsuiteApiIT} runs
 * against the live account, so its cleanup is proved under {@code mvn test} without an account or a credential.
 * WireMock stands in for the NetSuite REST services and the OAuth 2.0 token endpoint, behind the real
 * {@link NetsuiteRestClient}, {@link NetsuiteTokenProvider} (signing its client assertion with an RSA key generated per
 * run), SC-9 executor and NetSuite classifier, built as {@code client/NetsuiteRestClientTest} builds them.
 *
 * <p>The decisive case is a create whose outcome is unknown: NetSuite receives the create and its answer is lost, or
 * the answer carries no record ID. The bracket must then send the create once and never again, look the customer up
 * by the run's external ID with SuiteQL, and delete what that lookup finds, and nothing else. A create answered with
 * an ID is deleted by that ID without the lookup, whether the assertion passes or fails. A cleanup failure fails a
 * passing test and is suppressed under a failing one, and the log lines name record IDs and the external ID only.
 */
class NetsuiteTestRecordsTest {

    @RegisterExtension
    static WireMockExtension netsuite = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final String TOKEN = "/services/rest/auth/oauth2/v1/token";

    private static final String SUITEQL = "/services/rest/query/v1/suiteql";

    private static final String CUSTOMER_RECORDS = "/services/rest/record/v1/customer";

    /** The internal ID NetSuite assigns the test customer, in the {@code Location} header or the lookup's row. */
    private static final String CUSTOMER_ID = "4711";

    private static final String CUSTOMER_RECORD = CUSTOMER_RECORDS + "/" + CUSTOMER_ID;

    private static final String COMPANY_NAME = "Ramsey Inc. Wholesale Distribution V2011.1";

    private static final String SUBSIDIARY_ID = "1";

    /** NetSuite's REST error detail of the failing lookup, which no log line may carry. */
    private static final String LOOKUP_ERROR_DETAIL = "Invalid search query.";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One key for the whole class: generating an RSA-2048 pair per test would dominate the run time. */
    private static final KeyPair SIGNING_KEY = rsaKeyPair();

    @TempDir
    Path tmp;

    private NetsuiteRestClient client;

    private MockEnvironment env;

    private String externalId;

    private Logger recordsLogger;

    private Level previousLevel;

    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() throws IOException {
        netsuite.stubFor(post(urlPathEqualTo(TOKEN)).willReturn(okJson(
                "{\"access_token\": \"tok-1\", \"token_type\": \"bearer\", \"expires_in\": \"3600\"}")));
        NetsuiteProperties props = new NetsuiteProperties();
        props.setAccount("TSTDRV1234567");
        props.setBaseUrl(netsuite.baseUrl());
        props.setReadTimeout(Duration.ofSeconds(2));
        props.getOauth().setClientId("test-client-id");
        props.getOauth().setCertificateId("test-certificate-id");
        props.getOauth().setPrivateKeyPath(writePkcs8Pem(tmp).toString());
        client = new NetsuiteRestClient(props, new NetsuiteTokenProvider(props, WebClient.builder()),
                new UpstreamCallExecutor(List.of(new NetsuiteFailureClassifier())), WebClient.builder());
        env = new MockEnvironment()
                .withProperty(NetsuiteTestRecords.COMPANY_NAME_KEY, COMPANY_NAME)
                .withProperty(NetsuiteTestRecords.SUBSIDIARY_INTERNAL_ID_KEY, SUBSIDIARY_ID);
        externalId = NetsuiteTestRecords.newExternalId();

        recordsLogger = (Logger) LoggerFactory.getLogger(NetsuiteTestRecords.class);
        previousLevel = recordsLogger.getLevel();
        logs = new ListAppender<>();
        logs.start();
        recordsLogger.addAppender(logs);
        recordsLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void detachLogs() {
        recordsLogger.detachAppender(logs);
        logs.stop();
        recordsLogger.setLevel(previousLevel);
    }

    /**
     * The confirmation case and its kin: NetSuite receives the create, whose answer is then dropped or carries no
     * record ID. The assertion never runs, the create's own failure is what the test reports, and the cleanup
     * deletes the customer the external-ID lookup finds, after exactly one create.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unknownCreateOutcomes")
    void unknownCreateOutcomeDeletesTheCustomerFoundByExternalIdWithoutResubmitting(String name,
            ResponseDefinitionBuilder createAnswer, Class<? extends Throwable> createFailure) throws IOException {
        netsuite.stubFor(post(urlEqualTo(CUSTOMER_RECORDS)).willReturn(createAnswer));
        stubLookup(okJson(page(CUSTOMER_ID)));
        netsuite.stubFor(delete(urlEqualTo(CUSTOMER_RECORD)).willReturn(noContent()));
        AtomicInteger assertionRuns = new AtomicInteger();

        assertThatThrownBy(() -> NetsuiteTestRecords.withTestCustomer(client, env, externalId,
                assertionRuns::incrementAndGet))
                .isInstanceOf(createFailure)
                .hasNoSuppressedExceptions();

        assertThat(assertionRuns).hasValue(0);
        assertThat(netsuiteCalls()).containsExactlyInAnyOrder(
                "POST " + CUSTOMER_RECORDS, "POST " + SUITEQL, "DELETE " + CUSTOMER_RECORD);
        netsuite.verify(1, postRequestedFor(urlEqualTo(CUSTOMER_RECORDS)).withRequestBody(equalToJson(createBody())));
        netsuite.verify(1, postRequestedFor(urlPathEqualTo(SUITEQL)).withRequestBody(equalToJson(lookupBody())));
        assertThat(logLines()).containsExactly(
                "WARN The test customer's ID is unknown; deleting the customer with external ID " + externalId,
                "INFO Test customer with ID " + CUSTOMER_ID + " was deleted.");
        assertLogsNameNoTestDataOrErrorText();
    }

    static Stream<Arguments> unknownCreateOutcomes() {
        return Stream.of(
                Arguments.of("create received, connection reset before the answer",
                        aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER), UpstreamUnavailableException.class),
                Arguments.of("create received, connection closed with no answer",
                        aResponse().withFault(Fault.EMPTY_RESPONSE), UpstreamUnavailableException.class),
                Arguments.of("create answered without a Location header",
                        noContent(), IllegalStateException.class),
                Arguments.of("create answered with a Location header without a record ID",
                        noContent().withHeader("Location", "https://tstdrv1234567.suitetalk.api.netsuite.com/"),
                        IllegalStateException.class));
    }

    /** A create answered with the record's ID: the cleanup deletes that ID and never looks the customer up. */
    @ParameterizedTest(name = "assertion fails: {0}")
    @ValueSource(booleans = {false, true})
    void knownIdIsDeletedByIdWithoutTheLookup(boolean assertionFails) throws IOException {
        netsuite.stubFor(post(urlEqualTo(CUSTOMER_RECORDS))
                .willReturn(noContent().withHeader("Location", netsuite.baseUrl() + CUSTOMER_RECORD)));
        netsuite.stubFor(delete(urlEqualTo(CUSTOMER_RECORD)).willReturn(noContent()));
        AtomicInteger assertionRuns = new AtomicInteger();
        AssertionError assertionFailure = new AssertionError("The testing company name should be included!");

        Throwable thrown = catchThrowable(() -> NetsuiteTestRecords.withTestCustomer(client, env, externalId, () -> {
            assertionRuns.incrementAndGet();
            if (assertionFails) {
                throw assertionFailure;
            }
        }));

        assertThat(thrown).isSameAs(assertionFails ? assertionFailure : null);
        assertThat(assertionFailure).hasNoSuppressedExceptions();
        assertThat(assertionRuns).hasValue(1);
        assertThat(netsuiteCalls()).containsExactlyInAnyOrder("POST " + CUSTOMER_RECORDS, "DELETE " + CUSTOMER_RECORD);
        netsuite.verify(1, postRequestedFor(urlEqualTo(CUSTOMER_RECORDS)).withRequestBody(equalToJson(createBody())));
        assertThat(logLines()).containsExactly(
                "INFO Test customer created with ID: " + CUSTOMER_ID,
                "INFO Test customer with ID " + CUSTOMER_ID + " was deleted.");
        assertLogsNameNoTestDataOrErrorText();
    }

    /** The create's answer was lost and NetSuite holds no customer with the external ID: nothing is deleted. */
    @Test
    void unknownCreateOutcomeWithNoCustomerFoundDeletesNothing() throws IOException {
        netsuite.stubFor(post(urlEqualTo(CUSTOMER_RECORDS)).willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));
        stubLookup(okJson(page()));

        assertThatThrownBy(() -> NetsuiteTestRecords.withTestCustomer(client, env, externalId, () -> {
            throw new AssertionError("the assertion must not run when the create failed");
        })).isInstanceOf(UpstreamUnavailableException.class).hasNoSuppressedExceptions();

        assertThat(netsuiteCalls()).containsExactlyInAnyOrder("POST " + CUSTOMER_RECORDS, "POST " + SUITEQL);
        netsuite.verify(1, postRequestedFor(urlPathEqualTo(SUITEQL)).withRequestBody(equalToJson(lookupBody())));
        assertThat(logLines()).containsExactly(
                "WARN The test customer's ID is unknown; deleting the customer with external ID " + externalId,
                "WARN No NetSuite customer has external ID " + externalId + "; there is no test customer to delete");
        assertLogsNameNoTestDataOrErrorText();
    }

    /** A cleanup failure under a passing test is the test's failure, because the customer may be left behind. */
    @Test
    void deleteFailureAfterAPassingAssertionFailsTheTest() {
        netsuite.stubFor(post(urlEqualTo(CUSTOMER_RECORDS))
                .willReturn(noContent().withHeader("Location", netsuite.baseUrl() + CUSTOMER_RECORD)));
        netsuite.stubFor(delete(urlEqualTo(CUSTOMER_RECORD)).willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"title\": \"Not Found\", \"status\": 404}")));
        AtomicInteger assertionRuns = new AtomicInteger();

        assertThatThrownBy(() -> NetsuiteTestRecords.withTestCustomer(client, env, externalId,
                assertionRuns::incrementAndGet))
                .isInstanceOf(WebClientResponseException.NotFound.class);

        assertThat(assertionRuns).hasValue(1);
        assertThat(netsuiteCalls()).containsExactlyInAnyOrder("POST " + CUSTOMER_RECORDS, "DELETE " + CUSTOMER_RECORD);
        assertThat(logLines()).containsExactly("INFO Test customer created with ID: " + CUSTOMER_ID);
        assertLogsNameNoTestDataOrErrorText();
    }

    /**
     * A cleanup failure under a failed create is suppressed in the create's failure, which stays the reported one;
     * the create is not sent again and no log line carries the lookup's error text.
     */
    @Test
    void lookupFailureAfterAFailedCreateIsSuppressedInTheCreateFailure() throws IOException {
        netsuite.stubFor(post(urlEqualTo(CUSTOMER_RECORDS))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        stubLookup(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"title\": \"Bad Request\", \"status\": 400, \"o:errorDetails\": [{\"detail\": \""
                        + LOOKUP_ERROR_DETAIL + "\", \"o:errorCode\": \"INVALID_PARAMETER\"}]}"));

        assertThatThrownBy(() -> NetsuiteTestRecords.withTestCustomer(client, env, externalId, () -> {
            throw new AssertionError("the assertion must not run when the create failed");
        })).isInstanceOfSatisfying(UpstreamUnavailableException.class, failure ->
                assertThat(failure.getSuppressed()).singleElement()
                        .isInstanceOf(WebClientResponseException.BadRequest.class));

        assertThat(netsuiteCalls()).containsExactlyInAnyOrder("POST " + CUSTOMER_RECORDS, "POST " + SUITEQL);
        netsuite.verify(1, postRequestedFor(urlPathEqualTo(SUITEQL)).withRequestBody(equalToJson(lookupBody())));
        assertThat(logLines()).containsExactly(
                "WARN The test customer's ID is unknown; deleting the customer with external ID " + externalId);
        assertLogsNameNoTestDataOrErrorText();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void missingExternalIdIsRejectedBeforeAnyRequest(String missing) {
        AtomicInteger assertionRuns = new AtomicInteger();

        assertThatThrownBy(() -> NetsuiteTestRecords.withTestCustomer(client, env, missing,
                assertionRuns::incrementAndGet))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("test customer externalId is missing");

        assertThat(assertionRuns).hasValue(0);
        netsuite.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void externalIdsArePrefixedUniqueAndQuotedAsSuiteQlLiterals() {
        String other = NetsuiteTestRecords.newExternalId();

        assertThat(externalId)
                .matches(NetsuiteTestRecords.EXTERNAL_ID_PREFIX + "\\p{XDigit}{8}(-\\p{XDigit}{4}){3}-\\p{XDigit}{12}")
                .isNotEqualTo(other);
        assertThat(NetsuiteTestRecords.externalIdQuery("mule-it-O'Brien"))
                .isEqualTo("SELECT id FROM customer WHERE externalid = 'mule-it-O''Brien'");
    }

    private void stubLookup(ResponseDefinitionBuilder answer) throws IOException {
        netsuite.stubFor(post(urlPathEqualTo(SUITEQL)).withRequestBody(equalToJson(lookupBody())).willReturn(answer));
    }

    /** The create body NetSuite must receive: the configured customer plus the run's external ID, nothing else. */
    private String createBody() throws JsonProcessingException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("companyName", COMPANY_NAME);
        body.put("subsidiary", Map.of("id", SUBSIDIARY_ID));
        body.put("externalId", externalId);
        return JSON.writeValueAsString(body);
    }

    /** The SuiteQL lookup by the run's external ID, written out rather than taken from the code under test. */
    private String lookupBody() throws JsonProcessingException {
        return JSON.writeValueAsString(Map.of("q", "SELECT id FROM customer WHERE externalid = '" + externalId + "'"));
    }

    /** A last SuiteQL page holding one row per ID, in NetSuite's format. */
    private static String page(String... ids) throws JsonProcessingException {
        List<Map<String, Object>> items = Stream.of(ids)
                .<Map<String, Object>>map(id -> Map.of("links", List.of(), "id", id))
                .toList();
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("links", List.of());
        page.put("count", ids.length);
        page.put("hasMore", false);
        page.put("items", items);
        page.put("offset", 0);
        page.put("totalResults", ids.length);
        return JSON.writeValueAsString(page);
    }

    /** Every NetSuite API request WireMock received, token requests aside, as {@code METHOD path}. */
    private static List<String> netsuiteCalls() {
        return netsuite.findAll(anyRequestedFor(anyUrl())).stream()
                .filter(request -> !request.getUrl().startsWith(TOKEN))
                .map(request -> request.getMethod() + " " + URI.create(request.getUrl()).getPath())
                .toList();
    }

    private List<String> logLines() {
        return logs.list.stream().map(event -> event.getLevel() + " " + event.getFormattedMessage()).toList();
    }

    /** No log line carries a throwable, the configured company name or NetSuite's error text. */
    private void assertLogsNameNoTestDataOrErrorText() {
        assertThat(logs.list).allSatisfy(event -> {
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage()).doesNotContain(COMPANY_NAME).doesNotContain(LOOKUP_ERROR_DETAIL);
        });
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
}
