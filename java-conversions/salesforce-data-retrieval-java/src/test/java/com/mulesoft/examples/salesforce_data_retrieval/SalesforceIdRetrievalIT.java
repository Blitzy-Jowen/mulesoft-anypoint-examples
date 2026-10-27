/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_data_retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;

import com.mulesoft.examples.salesforce_data_retrieval.support.HttpTestClient;

/**
 * Tier 2B live-sandbox test, run by failsafe ({@code mvn -B verify}): the two assertions of the original
 * {@code SalesforceIdRetrievalIT}, sent over HTTP to the running application, which calls the real Salesforce org
 * configured in the git-ignored {@code src/test/resources/application-it.yml} (template
 * {@code application-it.yml.example}).
 *
 * <p>Read-only, as the original: it describes the org's objects and queries existing {@code User} records, and creates
 * or deletes nothing. The profile {@code it} makes Spring Boot load {@code application-it.yml} over the empty
 * {@code sfdc.*} defaults of {@code application.yml}; the original read the same three keys from
 * {@code mule.test.properties}.
 *
 * <p>The class is skipped, never failed, while {@code application-it.yml} or one of {@code sfdc.username},
 * {@code sfdc.password}, {@code sfdc.securityToken} is missing, blank, still {@code TODO} or an unresolvable
 * placeholder ({@link LiveCredentialsGate}). A skipped run is not Tier 2B evidence.
 */
@ExtendWith(SalesforceIdRetrievalIT.LiveCredentialsGate.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
class SalesforceIdRetrievalIT {

    private static final String OPTION_REGEX = "<option\\svalue=\\\".*\\\">.*<\\/option>";

    private static final String CREDENTIALS_FILE = "application-it.yml";

    private static final List<String> REQUIRED_KEYS = List.of("sfdc.username", "sfdc.password", "sfdc.securityToken");

    private static final String FORM_URLENCODED = "application/x-www-form-urlencoded";

    /**
     * Wait for the response to start. One request makes the lazy {@code login} and up to three Partner calls in
     * sequence (POST: {@code describeGlobal}, {@code describeSObject}, {@code queryAll}), each bounded by the
     * application's own 10 s Salesforce timeouts, so ST-1's 10 s default could expire before the application answers.
     */
    private static final int LIVE_READ_TIMEOUT_MILLIS = 60_000;

    @LocalServerPort
    private int port;

    @Test
    void testDisplayData() throws Exception {
        HttpTestClient.Response result = client().get("/");

        assertThat(result.status()).as(result::toString).isEqualTo(200);
        assertThat(Pattern.compile(OPTION_REGEX).matcher(result.bodyAsString()).find())
                .as("Payload should contain at least one option tag")
                .isTrue();
    }

    /*
     * The reply.txt values (kicks+a@mulesoft.com, Mule orgA, ...) are User records of the original example authors'
     * Salesforce org, as in the original test; in another org the assertion holds only where equivalent User records
     * exist. The test mirrors the original unchanged.
     */
    @Test
    void testGetData() throws Exception {
        HttpTestClient.Response result = client().post("/", FORM_URLENCODED,
                "object=user&field=email&searchKey=name&searchValue=mule");

        assertThat(result.status()).as(result::toString).isEqualTo(200);
        String payload = result.bodyAsString();
        for (String line : replyLines()) {
            assertThat(payload).contains(line);
        }
    }

    private HttpTestClient client() {
        return HttpTestClient.localhost(port).withReadTimeout(LIVE_READ_TIMEOUT_MILLIS);
    }

    /**
     * Why the class cannot run live, or {@code null} when {@code application-it.yml} supplies every required key with
     * a value that is neither blank nor {@code TODO}.
     */
    static String missingCredential() {
        ClassPathResource credentials = new ClassPathResource(CREDENTIALS_FILE);
        if (!credentials.exists()) {
            return "src/test/resources/" + CREDENTIALS_FILE
                    + " not supplied (copy application-it.yml.example); live Salesforce test skipped";
        }
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(credentials);
        Properties properties = yaml.getObject();
        StandardEnvironment environment = new StandardEnvironment();
        for (String key : REQUIRED_KEYS) {
            String value = resolve(environment, properties == null ? null : properties.getProperty(key));
            if (value == null || value.isBlank() || value.trim().startsWith("TODO")) {
                return key + " missing in " + CREDENTIALS_FILE + "; live Salesforce test skipped";
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

    private static List<String> replyLines() throws IOException {
        try (InputStream in = new ClassPathResource("reply.txt").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }

    /**
     * Skips the class unless every live credential is supplied ({@link #missingCredential()}), deciding at class
     * level, before the test instance exists. The Spring context is loaded when the instance is prepared, so a
     * disabled class starts no application and attempts no Salesforce login.
     *
     * <p>A class condition rather than an assumption in {@code @BeforeAll}: failsafe 3.1.2 counts a class whose
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
                    ? ConditionEvaluationResult.enabled("live Salesforce credentials supplied")
                    : ConditionEvaluationResult.disabled(missing);
        }
    }
}
