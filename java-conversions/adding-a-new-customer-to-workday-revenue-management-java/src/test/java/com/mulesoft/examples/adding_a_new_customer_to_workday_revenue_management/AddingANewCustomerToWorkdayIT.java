/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
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
import org.w3c.dom.Document;

import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.client.WorkdayRevenueManagementClient;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.support.HttpTestClient;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.support.WorkdayTestRequests;

/**
 * Tier 2B live-sandbox test, run by failsafe ({@code mvn -B verify}): the MUnit suite
 * {@code adding-a-new-customer-to-workday-revenue-management/src/test/munit/integration-test.xml} against the real
 * Workday tenant configured in the git-ignored {@code src/test/resources/application-it.yml} (template
 * {@code application-it.yml.example}).
 *
 * <p>Scenario, as the original:
 * <ol>
 *   <li>before-test (line 32): the customer is named {@code "John" + System.currentTimeMillis()};</li>
 *   <li>"Input data" and {@code flow-ref add-customer-flow} (lines 36-52): the {@code root/Account} customer XML is
 *       posted over HTTP to the running application's {@code POST /}, which sends {@code Put_Customer} to Workday
 *       Revenue_Management v25.0;</li>
 *   <li>{@code retrieveAccountFromWdayFlow} and {@code assert-on-equals expectedValue="1"} (lines 55-57):
 *       {@code Get_Customers} by {@code Customer_Reference_ID} returns {@code Total_Results} 1;</li>
 *   <li>{@code inactivateCustomerInWorkdayFlow} (lines 84-122): the customer is inactivated with
 *       {@code wday.status.id} and {@code wday.reason.id}. Workday exposes no customer delete, so the customer is
 *       inactivated, not deleted; this runs in {@link #inactivate()} so it also happens when an assertion fails.</li>
 * </ol>
 * The three MUnit request scripts and the {@code Total_Results} read are {@link WorkdayTestRequests}; both Workday
 * requests go through the application's own {@link WorkdayRevenueManagementClient#sendRaw}, with the same
 * UsernameToken, 10 s timeouts and failure handling as {@code Put_Customer}.
 *
 * <p>The profile {@code it} makes Spring Boot load {@code application-it.yml} over the empty {@code wday.*} defaults
 * of {@code application.yml}, from which {@code wday.endpoint} derives; the original read the same keys from
 * {@code mule.test.properties}. The existing tenant and integration user are reused.
 *
 * <p>The class is skipped, never failed, while {@code application-it.yml} or one of {@code wday.hostname},
 * {@code wday.tenant}, {@code wday.user}, {@code wday.password}, {@code wday.status.id}, {@code wday.reason.id} is
 * missing, blank, still {@code TODO} or an unresolvable placeholder ({@link LiveCredentialsGate}). A skipped run is
 * not Tier 2B evidence.
 */
@ExtendWith(AddingANewCustomerToWorkdayIT.LiveCredentialsGate.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
class AddingANewCustomerToWorkdayIT {

    private static final Logger log = LoggerFactory.getLogger(AddingANewCustomerToWorkdayIT.class);

    private static final String CREDENTIALS_FILE = "application-it.yml";

    private static final List<String> REQUIRED_KEYS = List.of("wday.hostname", "wday.tenant", "wday.user",
            "wday.password", "wday.status.id", "wday.reason.id");

    /** The MUnit before-test's name prefix: {@code "John" + System.currentTimeMillis()}. */
    private static final String CUSTOMER_NAME_PREFIX = "John";

    /** Literal part of the flow's {@code set-payload value="Added customer: #[payload]"}. */
    private static final String RESPONSE_PREFIX = "Added customer: ";

    /** {@code assert-on-equals expectedValue="1"} on {@code Total_Results}. */
    private static final String EXPECTED_TOTAL_RESULTS = "1";

    /**
     * Wait for the response to start. {@code POST /} makes one {@code Put_Customer} call and, after an
     * authentication failure, its one retry, each bounded by the application's own 10 s Workday connect and read
     * timeouts, so ST-1's 10 s default could expire before the application answers.
     */
    private static final int LIVE_READ_TIMEOUT_MILLIS = 60_000;

    @LocalServerPort
    private int port;

    @Autowired
    private WorkdayRevenueManagementClient client;

    @Value("${wday.status.id}")
    private String statusId;

    @Value("${wday.reason.id}")
    private String reasonId;

    /** Name of the customer this test posted, or {@code null} before the post; cleared by {@link #inactivate()}. */
    private String customerName;

    /** Whether {@code POST /} answered 200, so the customer was added to the tenant. */
    private boolean created;

    @Test
    @DisplayName("Live: add, retrieve and inactivate Workday customer")
    void test() throws Exception {
        customerName = CUSTOMER_NAME_PREFIX + System.currentTimeMillis();

        log.info("Calling main flow...");
        HttpTestClient.Response result = HttpTestClient.localhost(port).withReadTimeout(LIVE_READ_TIMEOUT_MILLIS)
                .post("/", "application/xml", WorkdayTestRequests.customerXml(customerName));
        created = result.status() == 200;

        assertThat(result.status()).as(result::toString).isEqualTo(200);
        // The descriptor after the prefix is assigned by the tenant, so only the literal part is compared.
        assertThat(result.bodyAsString()).startsWith(RESPONSE_PREFIX);

        log.info("Retrieving Customer from Workday..");
        Document response = client.sendRaw(WorkdayTestRequests.getCustomersRequest(customerName));

        assertThat(WorkdayTestRequests.totalResults(response))
                .as("One customer should be retrieved")
                .isEqualTo(EXPECTED_TOTAL_RESULTS);
    }

    /**
     * The original {@code inactivateCustomerInWorkdayFlow}: reads the customer again, because the inactivating
     * {@code Put_Customer_Request} copies its {@code Customer_Category_ID} from the {@code Get_Customers} response,
     * and sends that request. It runs when {@code POST /} answered 200, and also when the post failed in a way that
     * leaves its outcome unknown (for example a read timeout after Workday stored the customer) but the re-read finds
     * the customer. Failures propagate, so a customer left active in the tenant shows in the failsafe report.
     */
    @AfterEach
    void inactivate() throws Exception {
        if (customerName == null) {
            return;
        }
        try {
            Document current = client.sendRaw(WorkdayTestRequests.getCustomersRequest(customerName));
            if (created || EXPECTED_TOTAL_RESULTS.equals(WorkdayTestRequests.totalResults(current))) {
                log.info("Invalidating customer... {}", customerName);
                client.sendRaw(WorkdayTestRequests.inactivateRequest(customerName, current, statusId, reasonId));
                log.info("Customer invalidation flow finished..");
            }
        } finally {
            customerName = null;
            created = false;
        }
    }

    /**
     * Why the class cannot run live, or {@code null} when {@code application-it.yml} supplies every required key with
     * a value that is neither blank nor {@code TODO}.
     */
    static String missingCredential() {
        ClassPathResource credentials = new ClassPathResource(CREDENTIALS_FILE);
        if (!credentials.exists()) {
            return "src/test/resources/" + CREDENTIALS_FILE
                    + " not supplied (copy application-it.yml.example); live Workday test skipped";
        }
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(credentials);
        Properties properties = yaml.getObject();
        StandardEnvironment environment = new StandardEnvironment();
        for (String key : REQUIRED_KEYS) {
            String value = resolve(environment, properties == null ? null : properties.getProperty(key));
            if (value == null || value.isBlank() || value.trim().startsWith("TODO")) {
                return key + " missing in " + CREDENTIALS_FILE + "; live Workday test skipped";
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
     * disabled class starts no application, never resolves the {@code @Value} placeholders and calls no Workday
     * operation.
     *
     * <p>A class condition rather than an {@code Assumptions.assumeTrue} in {@code @BeforeAll}: failsafe 3.1.2 counts
     * a class whose {@code @BeforeAll} aborts as neither run nor skipped, while each test of a class disabled here is
     * reported skipped with the reason, so the report shows that the live tier was skipped. The outcome the plan
     * requires, skipped and never failed without credentials, is the same.
     */
    static final class LiveCredentialsGate implements ExecutionCondition {

        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            if (context.getTestMethod().isPresent()) {
                return ConditionEvaluationResult.enabled("decided for the class");
            }
            String missing = missingCredential();
            return missing == null
                    ? ConditionEvaluationResult.enabled("live Workday credentials supplied")
                    : ConditionEvaluationResult.disabled(missing);
        }
    }
}
