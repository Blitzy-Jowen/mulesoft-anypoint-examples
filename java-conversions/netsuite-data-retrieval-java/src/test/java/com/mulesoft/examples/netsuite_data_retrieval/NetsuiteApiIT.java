/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.netsuite_data_retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.netsuite_data_retrieval.client.NetsuiteRestClient;
import com.mulesoft.examples.netsuite_data_retrieval.support.HttpTestClient;
import com.mulesoft.examples.netsuite_data_retrieval.support.NetsuiteTestRecords;

/**
 * Tier 2B live-sandbox run, by failsafe ({@code mvn -B verify}), of the original MUnit suite
 * {@code netsuite-data-retrieval/src/test/munit/netsuite-api-test-suite.xml}: its three tests, one method each under
 * the MUnit test name, with the same requests and assertions (status 200, payload not null, and for customers the
 * substring {@code "companyName": "<companyName>"}), sent over HTTP to the application on a random port, which
 * reaches the real NetSuite account configured in the git-ignored {@code src/test/resources/application-it.yml}
 * (template {@code application-it.yml.example}). The surefire {@code NetsuiteApiTest} runs the same assertions
 * against a mocked {@link NetsuiteRestClient}.
 *
 * <p>The customers test is bracketed as the original's was: {@link NetsuiteTestRecords#createCustomerInNetsuite}
 * creates the test customer before the request and {@link NetsuiteTestRecords#deleteCustomerInNetsuite} deletes it in
 * {@code finally}, so it never stays in the account. The create is never retried: {@link NetsuiteRestClient} repeats a
 * request only after a 401, which NetSuite answers before processing it. The request is repeated until the new
 * customer appears, because SuiteQL may list a record shortly after it is created. The opportunities and items
 * searches are read-only.
 *
 * <p>Every select custom field a response carries ({@code SelectCustomFieldRef__*},
 * {@code MultiSelectCustomFieldRef__*}) must hold a {@code typeId} that is a NetSuite internal ID, the Tier 2B evidence
 * that the {@code customfield} source lookup resolves the SuiteTalk {@code typeId}, which the application never writes
 * null or guesses.
 *
 * <p>The class is skipped, never failed, while {@code application-it.yml} or one of its eight keys is missing, blank,
 * {@code TODO} or an unresolvable placeholder ({@link #missingCredential()}); the Spring context then never starts and
 * NetSuite is never called. A skipped run is not Tier 2B evidence. Failure messages carry the status line and a
 * truncated body only, and nothing here logs or reports a credential value.
 */
@ExtendWith(NetsuiteApiIT.LiveCredentialsGate.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
class NetsuiteApiIT {

    private static final Logger LOG = LoggerFactory.getLogger(NetsuiteApiIT.class);

    private static final String CREDENTIALS_FILE = "application-it.yml";

    private static final String CREDENTIALS_PATH = "src/test/resources/" + CREDENTIALS_FILE;

    /** The title prefix of the read-only opportunity search, under the original MUnit property name. */
    private static final String OPPORTUNITY_TITLE_KEY = "nets.opportunity.title";

    /** The quantity of the read-only item search, under the original MUnit property name. */
    private static final String ITEM_QUANTITY_KEY = "nets.item.quantity";

    /** Exactly the keys of {@code application-it.yml.example}. */
    private static final List<String> REQUIRED_KEYS = List.of(
            "netsuite.account",
            "netsuite.oauth.client-id",
            "netsuite.oauth.certificate-id",
            "netsuite.oauth.private-key-path",
            NetsuiteTestRecords.COMPANY_NAME_KEY,
            NetsuiteTestRecords.SUBSIDIARY_INTERNAL_ID_KEY,
            OPPORTUNITY_TITLE_KEY,
            ITEM_QUANTITY_KEY);

    private static final String TODO = "TODO";

    /** The {@code /api/*} listener path of {@code netsuite-api.xml}, the RAML base of every request. */
    private static final String API = "/api";

    private static final List<String> SELECT_FIELD_PREFIXES =
            List.of("SelectCustomFieldRef__", "MultiSelectCustomFieldRef__");

    /** A NetSuite internal ID as text; standard record-type sources are negative, such as {@code -30}. */
    private static final Pattern INTERNAL_ID = Pattern.compile("-?\\d+");

    /** How long a created customer may take to appear in SuiteQL results. */
    private static final Duration LISTING_TIMEOUT = Duration.ofSeconds(60);

    private static final Duration LISTING_POLL_INTERVAL = Duration.ofSeconds(2);

    /**
     * Wait for the customers response to start. One request makes the token request, the SuiteQL search, a record
     * read per matching customer and the metadata and custom-field lookups in sequence, each bounded by the
     * application's own 10 s NetSuite timeouts, so ST-1's 10 s default could expire before the application answers.
     */
    private static final int CUSTOMERS_READ_TIMEOUT_MILLIS = 60_000;

    /**
     * Wait for the opportunities and items responses to start: the original suite's {@code responseTimeout="1000000"}
     * on both requests, because the item search reads every item supply plan of the account.
     */
    private static final int SEARCH_READ_TIMEOUT_MILLIS = 1_000_000;

    private static final int BODY_EXCERPT_LIMIT = 2_000;

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private NetsuiteRestClient client;

    @Autowired
    private Environment env;

    /*
     * The same check LiveCredentialsGate makes. The gate is what reports the tests as skipped; this assumption still
     * stops the class before any test instance, Spring context or NetSuite call exists when the gate does not run,
     * for example when junit.jupiter.conditions.deactivate switches conditions off.
     */
    @BeforeAll
    static void requireLiveCredentials() {
        String missing = missingCredential();
        Assumptions.assumeTrue(missing == null, missing);
    }

    @Test
    @DisplayName("netsuite-api-test-suite-get:/customers:netsuite-api-configTest")
    void getCustomers() {
        String companyName = env.getRequiredProperty(NetsuiteTestRecords.COMPANY_NAME_KEY);
        String path = API + "/customers?name=" + queryValue(companyName);
        String companyNamePair = "\"companyName\": \"" + companyName + "\"";

        // Created before the try: when the create fails there is no customer to delete.
        String id = NetsuiteTestRecords.createCustomerInNetsuite(client, env);
        Throwable failure = null;
        try {
            await("GET " + API + "/customers lists the test customer")
                    .atMost(LISTING_TIMEOUT)
                    .pollDelay(Duration.ZERO)
                    .pollInterval(LISTING_POLL_INTERVAL)
                    .ignoreExceptions()
                    .untilAsserted(() -> {
                        HttpTestClient.Response response = http(CUSTOMERS_READ_TIMEOUT_MILLIS).get(path);
                        assertOk(response);
                        assertThat(response.bodyAsString().contains(companyNamePair))
                                .as(() -> "The testing company name should be included in the response! "
                                        + describe(response))
                                .isTrue();
                        assertResolvedTypeIds(response);
                    });
        } catch (RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            deleteTestCustomer(id, failure);
        }
    }

    @Test
    @DisplayName("netsuite-api-test-suite-get:/opportunities:netsuite-api-configTest")
    void getOpportunities() throws IOException {
        String title = env.getRequiredProperty(OPPORTUNITY_TITLE_KEY);
        HttpTestClient.Response response =
                http(SEARCH_READ_TIMEOUT_MILLIS).get(API + "/opportunities?title=" + queryValue(title));
        assertOk(response);
        assertResolvedTypeIds(response);
    }

    @Test
    @DisplayName("netsuite-api-test-suite-get:/items:netsuite-api-configTest")
    void getItems() throws IOException {
        String quantity = env.getRequiredProperty(ITEM_QUANTITY_KEY);
        HttpTestClient.Response response =
                http(SEARCH_READ_TIMEOUT_MILLIS).get(API + "/items?quantity=" + queryValue(quantity));
        assertOk(response);
        assertResolvedTypeIds(response);
    }

    /** The original's two common assertions: {@code http.status} 200 and a payload that is not null. */
    private static void assertOk(HttpTestClient.Response response) {
        assertThat(response.status())
                .as(() -> "The HTTP Status code is not correct! " + describe(response))
                .isEqualTo(200);
        assertThat(response.bodyAsString())
                .as(() -> "Payload is null! " + describe(response))
                .isNotNull();
    }

    /*
     * Applied to all three responses, not only the customers one: every select field the live run reads must carry a
     * resolved numeric typeId, and opportunities carry custbody* select fields too. The items response holds no
     * custom fields, so the check finds nothing to fail there.
     */
    private static void assertResolvedTypeIds(HttpTestClient.Response response) throws IOException {
        List<String> violations = new ArrayList<>();
        int checked = collectTypeIdViolations(JSON.readTree(response.bodyAsString()), violations);
        assertThat(violations)
                .as("select custom field values without a textual NetSuite internal ID as typeId")
                .isEmpty();
        LOG.info("{} select custom field value(s) carry a resolved numeric typeId", checked);
    }

    /**
     * Walks every object and array under {@code node} and checks each value of a field whose name starts with
     * {@code SelectCustomFieldRef__} or {@code MultiSelectCustomFieldRef__}: the value, or each element when it is an
     * array, must be an object whose {@code typeId} is text matching {@code -?\d+}. Each violation names the field and
     * the offending {@code typeId} or value type, never other record data.
     *
     * @param node       the parsed response, or any part of it
     * @param violations receives one entry per failing select value
     * @return the number of select values checked
     */
    static int collectTypeIdViolations(JsonNode node, List<String> violations) {
        int checked = 0;
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isSelectField(field.getKey())) {
                    checked += checkSelectValues(field.getKey(), field.getValue(), violations);
                }
                checked += collectTypeIdViolations(field.getValue(), violations);
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                checked += collectTypeIdViolations(element, violations);
            }
        }
        return checked;
    }

    private static boolean isSelectField(String name) {
        for (String prefix : SELECT_FIELD_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static int checkSelectValues(String field, JsonNode value, List<String> violations) {
        if (!value.isArray()) {
            checkSelectValue(field, value, violations);
            return 1;
        }
        int checked = 0;
        for (JsonNode element : value) {
            checkSelectValue(field, element, violations);
            checked++;
        }
        return checked;
    }

    private static void checkSelectValue(String field, JsonNode value, List<String> violations) {
        if (!value.isObject()) {
            violations.add(field + ": select value is " + value.getNodeType() + ", not an object");
            return;
        }
        JsonNode typeId = value.get("typeId");
        if (typeId == null) {
            violations.add(field + ": typeId absent");
        } else if (!typeId.isTextual() || !INTERNAL_ID.matcher(typeId.textValue()).matches()) {
            violations.add(field + ": typeId " + typeId + " is not a textual NetSuite internal ID");
        }
    }

    /**
     * Deletes the test customer. A cleanup failure fails the test, because the customer may be left in the account;
     * when the test already failed, it is added to that failure as suppressed rather than replacing it.
     */
    private void deleteTestCustomer(String id, Throwable failure) {
        try {
            NetsuiteTestRecords.deleteCustomerInNetsuite(client, id);
        } catch (RuntimeException cleanupFailure) {
            if (failure == null) {
                throw cleanupFailure;
            }
            failure.addSuppressed(cleanupFailure);
        }
    }

    private HttpTestClient http(int readTimeoutMillis) {
        return HttpTestClient.localhost(port).withReadTimeout(readTimeoutMillis);
    }

    /** A query parameter value percent-encoded as a URI component: a space is {@code %20}, never {@code +}. */
    private static String queryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** The status line and the body cut to {@value #BODY_EXCERPT_LIMIT} characters, all a failure reports. */
    private static String describe(HttpTestClient.Response response) {
        String body = response.bodyAsString();
        String excerpt = body.length() <= BODY_EXCERPT_LIMIT
                ? body
                : body.substring(0, BODY_EXCERPT_LIMIT) + "... [truncated, " + body.length() + " characters in total]";
        return response.statusLine() + "\n" + excerpt;
    }

    /**
     * Why the class cannot run live, or {@code null} when {@code application-it.yml} supplies every one of
     * {@link #REQUIRED_KEYS} with a value that is neither blank nor {@code TODO} (trimmed, any case). The file is read
     * from the test classpath, where Spring Boot reads it for the profile {@code it}, so the check and the context see
     * the same file. The reason names a key, never a value.
     */
    static String missingCredential() {
        ClassPathResource credentials = new ClassPathResource(CREDENTIALS_FILE);
        if (!credentials.exists()) {
            return CREDENTIALS_PATH + " not found; copy application-it.yml.example and fill it in";
        }
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(credentials);
        Properties properties = yaml.getObject();
        StandardEnvironment environment = new StandardEnvironment();
        for (String key : REQUIRED_KEYS) {
            String value = resolve(environment, properties == null ? null : properties.getProperty(key));
            if (value == null || value.isBlank() || TODO.equalsIgnoreCase(value.trim())) {
                return key + " missing, blank or TODO in " + CREDENTIALS_PATH + "; live NetSuite test skipped";
            }
        }
        return null;
    }

    /**
     * Resolves {@code ${...}} placeholders against system properties and environment variables, as Spring Boot will
     * when it loads the file; a placeholder without a value or default yields {@code null}, because the context would
     * fail to start on it.
     */
    private static String resolve(StandardEnvironment environment, String value) {
        if (value == null) {
            return null;
        }
        try {
            return environment.resolveRequiredPlaceholders(value);
        } catch (IllegalArgumentException unresolvable) {
            return null;
        }
    }

    /**
     * Skips the class unless every live credential is supplied ({@link #missingCredential()}), deciding at class
     * level, before the test instance exists. The Spring context is loaded when the instance is prepared, so a
     * disabled class starts no application and makes no NetSuite call.
     *
     * <p>A class condition rather than only the assumption in {@code @BeforeAll}: failsafe 3.1.2 counts a class whose
     * {@code @BeforeAll} aborts as neither run nor skipped, while each test of a class disabled here is reported
     * skipped with the reason, so the report shows that the live tier was skipped.
     */
    static final class LiveCredentialsGate implements ExecutionCondition {

        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            if (context.getTestMethod().isPresent()) {
                return ConditionEvaluationResult.enabled("decided for the class");
            }
            String missing = missingCredential();
            return missing == null
                    ? ConditionEvaluationResult.enabled("live NetSuite credentials supplied")
                    : ConditionEvaluationResult.disabled(missing);
        }
    }
}
