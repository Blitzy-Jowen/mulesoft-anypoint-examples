/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.get_customer_list_from_netsuite;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;

import com.mulesoft.examples.get_customer_list_from_netsuite.client.NetsuiteRestClient;
import com.mulesoft.examples.get_customer_list_from_netsuite.support.HttpTestClient;

/**
 * Tier 2B live-sandbox test, run by failsafe ({@code mvn -B verify}): the original
 * {@code GetCustomersListFromNetsuiteIT} assertion, {@code GET /customers?lastName=a} contains
 * {@code <h1>Netsuite Customer List</h1>}, sent over HTTP to the running application, which queries the real NetSuite
 * account configured in the git-ignored
 * {@code src/test/resources/application-it.yml} (template {@code application-it.yml.example}).
 *
 * <p>The original relied on whatever customers the account held. This test brings its own: it creates a customer
 * with a unique last name through the NetSuite REST record API, waits until {@code GET /customers} lists it as a row,
 * runs the original assertion, and deletes the customer in {@code finally}, so no test customer stays in the account.
 * The create is never retried: {@link NetsuiteRestClient} retries a request only after a 401, which NetSuite answers
 * before processing it. The profile {@code it} makes Spring Boot load {@code application-it.yml} over the empty
 * {@code netsuite.*} placeholders of {@code application.yml}; the original read its SuiteTalk login from
 * {@code mule.test.properties}.
 *
 * <p>The class is skipped, never failed, while {@code application-it.yml} or one of its five keys is missing, blank,
 * still {@code TODO} or an unresolvable placeholder ({@link #missingCredential()}); then the Spring context never
 * starts and NetSuite is never called. A skipped run is not Tier 2B evidence. Logs name only the generated last name
 * and the record ID, never a credential.
 */
@ExtendWith(GetCustomersListFromNetsuiteIT.LiveCredentialsGate.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
class GetCustomersListFromNetsuiteIT {

    private static final Logger LOG = LoggerFactory.getLogger(GetCustomersListFromNetsuiteIT.class);

    private static final String LAST_NAME_VALUE = "a";

    private static final String TEST_HTML = "<h1>Netsuite Customer List</h1>";

    private static final String CREDENTIALS_FILE = "application-it.yml";

    /** Exactly the keys of {@code application-it.yml.example}. */
    private static final List<String> REQUIRED_KEYS = List.of(
            "netsuite.account",
            "netsuite.oauth.client-id",
            "netsuite.oauth.certificate-id",
            "netsuite.oauth.private-key-path",
            "nets.customer.subsidiary.internalId");

    private static final String TODO = "TODO";

    private static final String CUSTOMER_RECORD = "customer";

    /**
     * Starts with {@code A}, so while the test customer exists the original's {@code lastName=a} query, which matches
     * last names case-insensitively, has at least one customer to list.
     */
    private static final String LAST_NAME_PREFIX = "Amuleit";

    /** How long a created customer may take to appear in SuiteQL results. */
    private static final Duration LISTING_TIMEOUT = Duration.ofSeconds(60);

    private static final Duration LISTING_POLL_INTERVAL = Duration.ofSeconds(2);

    /**
     * Wait for the response to start. One request may make the token request and several SuiteQL pages in sequence,
     * each bounded by the application's own 10 s NetSuite timeouts, so ST-1's 10 s default could expire before the
     * application answers.
     */
    private static final int LIVE_READ_TIMEOUT_MILLIS = 60_000;

    @LocalServerPort
    private int port;

    @Autowired
    private NetsuiteRestClient client;

    @Value("${nets.customer.subsidiary.internalId}")
    private String subsidiaryId;

    /*
     * The same check LiveCredentialsGate makes. The gate is what reports the class as skipped; this assumption still
     * stops the class before any test instance, Spring context or NetSuite call exists when the gate does not run,
     * for example when junit.jupiter.conditions.deactivate switches conditions off.
     */
    @BeforeAll
    static void requireLiveCredentials() {
        String missing = missingCredential();
        Assumptions.assumeTrue(missing == null, missing);
    }

    @Test
    void createdCustomerIsListed() {
        long millis = System.currentTimeMillis();
        String lastName = LAST_NAME_PREFIX + millis;
        String id = null;
        Throwable failure = null;
        try {
            id = client.createRecord(CUSTOMER_RECORD, customer(lastName, millis));
            LOG.info("Created NetSuite customer {} with last name {}", id, lastName);

            String listingPath = "/customers?lastName=" + URLEncoder.encode(lastName, StandardCharsets.UTF_8);
            String row = "<td>" + lastName + "</td>";
            await("customer " + lastName + " listed by GET /customers")
                    .atMost(LISTING_TIMEOUT)
                    .pollDelay(Duration.ZERO)
                    .pollInterval(LISTING_POLL_INTERVAL)
                    .untilAsserted(() -> {
                        HttpTestClient.Response listing = http().get(listingPath);
                        assertEquals(200, listing.status(), listing::toString);
                        String body = listing.bodyAsString();
                        assertTrue(body.contains(TEST_HTML), listing::toString);
                        assertTrue(body.contains(row), listing::toString);
                    });

            // The original assertion. Only the status line goes into a failure message: this page lists the
            // account's other customers too.
            HttpTestClient.Response response = http().get("/customers?lastName=" + LAST_NAME_VALUE);
            assertEquals(200, response.status(), response::statusLine);
            assertTrue(response.bodyAsString().contains(TEST_HTML), response::statusLine);
        } catch (RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            deleteCustomer(id, lastName, failure);
        }
    }

    private Map<String, Object> customer(String lastName, long millis) {
        return Map.of(
                "isPerson", true,
                "firstName", "Mule",
                "lastName", lastName,
                "email", "amuleit" + millis + "@example.com",
                "subsidiary", Map.of("id", subsidiaryId));
    }

    /**
     * Deletes the test customer. When the create failed without returning an ID, NetSuite may still have created the
     * customer (a timeout or a connection lost after the request was sent), so every customer with the test's unique
     * last name is deleted instead.
     *
     * <p>A cleanup failure fails the test, because a customer may be left in the account; when the test already
     * failed, it is added to that failure as suppressed rather than replacing it.
     */
    private void deleteCustomer(String id, String lastName, Throwable failure) {
        try {
            List<String> ids;
            if (id != null) {
                ids = List.of(id);
            } else {
                LOG.warn("NetSuite returned no customer ID; deleting any customer with last name {}", lastName);
                ids = customerIdsWithLastName(lastName);
            }
            for (String each : ids) {
                client.deleteRecord(CUSTOMER_RECORD, each);
                LOG.info("Deleted NetSuite customer {} with last name {}", each, lastName);
            }
        } catch (RuntimeException cleanupFailure) {
            if (failure == null) {
                throw cleanupFailure;
            }
            failure.addSuppressed(cleanupFailure);
        }
    }

    private List<String> customerIdsWithLastName(String lastName) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> row : client.suiteQl("SELECT id FROM customer WHERE lastname = '"
                + NetsuiteRestClient.suiteQlLiteral(lastName) + "'")) {
            Object value = row.get("id");
            if (value != null) {
                ids.add(String.valueOf(value));
            }
        }
        return ids;
    }

    private HttpTestClient http() {
        return HttpTestClient.localhost(port).withReadTimeout(LIVE_READ_TIMEOUT_MILLIS);
    }

    /**
     * Why the class cannot run live, or {@code null} when {@code application-it.yml} supplies every one of
     * {@link #REQUIRED_KEYS} with a value that is neither blank nor {@code TODO}. The file is read from the test
     * classpath, where Spring Boot reads it for the profile {@code it}, so the check and the context see the same
     * file. The reason names a key, never a value.
     */
    static String missingCredential() {
        ClassPathResource credentials = new ClassPathResource(CREDENTIALS_FILE);
        if (!credentials.exists()) {
            return "src/test/resources/" + CREDENTIALS_FILE
                    + " not present (copy application-it.yml.example); live NetSuite test skipped";
        }
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(credentials);
        Properties properties = yaml.getObject();
        StandardEnvironment environment = new StandardEnvironment();
        for (String key : REQUIRED_KEYS) {
            String value = resolve(environment, properties == null ? null : properties.getProperty(key));
            if (value == null || value.isBlank() || value.trim().toUpperCase(Locale.ROOT).startsWith(TODO)) {
                return key + " missing, blank or TODO in " + CREDENTIALS_FILE + "; live NetSuite test skipped";
            }
        }
        return null;
    }

    /**
     * Resolves {@code ${...}} placeholders against system properties and environment variables, as Spring Boot will
     * when it loads the file; a placeholder without a value or default yields {@code null}, because the context
     * would fail to start on it.
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
