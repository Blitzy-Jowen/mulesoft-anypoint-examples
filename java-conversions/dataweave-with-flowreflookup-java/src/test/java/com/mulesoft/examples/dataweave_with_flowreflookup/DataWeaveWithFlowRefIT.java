/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.dataweave_with_flowreflookup;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.mulesoft.examples.dataweave_with_flowreflookup.client.SalesforcePartnerClient;
import com.mulesoft.examples.dataweave_with_flowreflookup.trigger.CompaniesFileTrigger;
import com.sforce.soap.partner.DeleteResult;
import com.sforce.soap.partner.StatusCode;

/**
 * Tier 2B live-sandbox test, run by failsafe ({@code mvn -B verify}): the original {@code DataWeaveWithFlowRefIT}
 * against the real Salesforce org configured in the git-ignored {@code src/test/resources/application-it.yml}
 * (template {@code application-it.yml.example}).
 *
 * <p>As the original, it imports {@code companies.csv} through the application's file endpoint, which creates the
 * Accounts {@code Universal Exports} (billing state {@code fl}) and {@code Best Widgets} in the org, reads them back by
 * name, asserts that {@code Universal Exports} carries the {@code Region__c} {@code South East} the sales-region
 * lookup gave it and that {@code Best Widgets} exists, and deletes both in {@link #tearDown()}. The org's Account
 * object needs the custom field {@code Region__c}, a prerequisite of the original example. The original's pieces map
 * as follows:
 * <ul>
 *   <li>{@code client.dispatch} to {@code file://./src/main/resources/input} with {@code outputPattern}
 *       {@code companies.csv}: the file is placed, under that name, in this class's input directory and
 *       {@link CompaniesFileTrigger#pollOnce()} runs the flow; the fixed 15000 ms sleep becomes a bounded wait;</li>
 *   <li>test flow {@code selectAccountFromSalesforce} ({@code sfdc:query-single} of
 *       {@code SELECT Id,Region__c FROM Account WHERE Name = '#[payload]'}): the same SOQL through the real
 *       {@link SalesforcePartnerClient}, the first returned Account this import created standing in for
 *       {@code query-single};</li>
 *   <li>test flow {@code deleteAccountFromSalesforce} ({@code sfdc:delete} of the collected IDs) in {@code @After}:
 *       {@link SalesforcePartnerClient#delete(List)} in {@code @AfterEach}, whose failure is logged, never thrown,
 *       as the original only printed it;</li>
 *   <li>{@code sfdc.user}, {@code sfdc.password}, {@code sfdc.securityToken} of {@code mule.test.properties}: the
 *       profile {@code it} makes Spring Boot load {@code sfdc.username}, {@code sfdc.password} and
 *       {@code sfdc.securityToken} from {@code application-it.yml} over the empty defaults of
 *       {@code application.yml}.</li>
 * </ul>
 *
 * <p>The class is skipped, never failed, while {@code application-it.yml} or one of those three keys is missing,
 * blank, still {@code TODO} or an unresolvable placeholder ({@link #missingCredential()}); then no Spring context
 * starts and Salesforce is never called. A skipped run is not Tier 2B evidence. Logs name Account IDs and names of
 * the test data only, never a credential. A failed cleanup step is named by the class names of its exception and
 * causes ({@link #failureTypes(Throwable)}) and a failed delete by its Salesforce status codes; no exception, cause,
 * suppressed exception or error message is logged or attached, because vendor and transport messages can repeat
 * tokens, credentials, record values or the SOQL.
 */
@ExtendWith(DataWeaveWithFlowRefIT.LiveCredentialsGate.class)
@SpringBootTest(classes = DataweaveWithFlowreflookupApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("it")
class DataWeaveWithFlowRefIT {

    private static final Logger LOG = LoggerFactory.getLogger(DataWeaveWithFlowRefIT.class);

    private static final String COMPANY_NAME = "Universal Exports";
    private static final String COMPANY_NAME1 = "Best Widgets";

    /** Both Accounts of {@code companies.csv}, in file order. */
    private static final List<String> COMPANY_NAMES = List.of(COMPANY_NAME, COMPANY_NAME1);

    /** Classpath resource the original dispatched, and the name its {@code outputPattern} gave the file. */
    private static final String FILE_NAME = "companies.csv";

    /** The query of test flow {@code selectAccountFromSalesforce}, up to the name literal. */
    private static final String SELECT_BY_NAME = "SELECT Id,Region__c FROM Account WHERE Name = '";

    private static final String ID = "Id";

    private static final String REGION = "Region__c";

    private static final String CREDENTIALS_FILE = "application-it.yml";

    /** Exactly the keys of {@code application-it.yml.example}; {@code sfdc.auth-endpoint} is optional. */
    private static final List<String> REQUIRED_KEYS = List.of("sfdc.username", "sfdc.password", "sfdc.securityToken");

    private static final String TODO = "TODO";

    /** How long the imported Accounts may take to be returned by the query. */
    private static final Duration IMPORT_TIMEOUT = Duration.ofSeconds(60);

    private static final Duration QUERY_POLL_INTERVAL = Duration.ofSeconds(2);

    /** How long the poller may take to move the file once the Accounts exist. */
    private static final Duration MOVE_TIMEOUT = Duration.ofSeconds(10);

    private static final Duration MOVE_POLL_INTERVAL = Duration.ofMillis(200);

    /** Most exceptions {@link #failureTypes(Throwable)} names: a failure and four of its causes. */
    private static final int FAILURE_TYPES_LIMIT = 5;

    private static final String NO_ERROR_DETAIL = "no error detail";

    @TempDir
    static Path workDir;

    @Autowired
    private SalesforcePartnerClient salesforce;

    @Autowired
    private CompaniesFileTrigger trigger;

    /** IDs of Accounts with either name that the org held before the import; never asserted on, never deleted. */
    private final Set<String> preExisting = new HashSet<>();

    /** Every Account ID read back for either name and not in {@link #preExisting}, deleted in {@link #tearDown()}. */
    private final List<String> createdIds = new ArrayList<>();

    /** Whether {@code companies.csv} reached the input directory, so the import may have created Accounts. */
    private boolean fileDropped;

    /*
     * The same check LiveCredentialsGate makes. The gate is what reports the class as skipped; this assumption still
     * stops the class before any test instance, Spring context or Salesforce login exists when the gate does not run,
     * for example when junit.jupiter.conditions.deactivate switches conditions off.
     */
    @BeforeAll
    static void requireLiveCredentials() {
        String missing = missingCredential();
        Assumptions.assumeTrue(missing == null, missing);
    }

    /**
     * Points the file endpoint at this class's directories, over the failsafe system properties of the pom. The
     * suppliers are read while the context starts, after JUnit has injected {@link #workDir}. The polling frequency
     * of one hour leaves the scheduled startup poll, which finds the input directory empty, as the only scheduled one.
     */
    @DynamicPropertySource
    static void companyDirectories(DynamicPropertyRegistry registry) {
        registry.add("companies.input-dir", () -> inputDir().toString());
        registry.add("companies.move-to-dir", () -> outputDir().toString());
        registry.add("companies.polling-frequency", () -> "3600000");
    }

    @Test
    void importAccounts() throws Exception {
        // Departs from the original, which read back whatever Account matched: Accounts the org already holds under
        // these names (another user's data, or an earlier run's) are recorded first, so only an Account this import
        // created can satisfy the assertions, and the pre-existing ones are left untouched by the cleanup.
        for (String name : COMPANY_NAMES) {
            Set<String> existing = ids(salesforce.query(selectByName(name)));
            if (!existing.isEmpty()) {
                LOG.warn("Accounts named {} already in the org, neither asserted on nor deleted: {}", name, existing);
            }
            preExisting.addAll(existing);
        }

        byte[] csv = new ClassPathResource(FILE_NAME).getContentAsByteArray();
        Path inputDir = Files.createDirectories(inputDir());
        // Staged beside the input directory and moved in atomically: the scheduled startup poll runs on its own
        // thread and must never list a half-written file.
        Path staged = Files.write(Files.createDirectories(workDir.resolve("staging")).resolve(FILE_NAME), csv);
        fileDropped = true;
        Files.move(staged, inputDir.resolve(FILE_NAME), StandardCopyOption.ATOMIC_MOVE);

        // Not asserted: the scheduled startup poll may take the file first, and the poller's in-process mark keeps
        // either run from importing it twice.
        trigger.pollOnce();

        // Both are read back before either is asserted, so a failed assertion still leaves both IDs for tearDown.
        Map<String, Map<String, Object>> imported = awaitImportedAccounts();

        assertEquals("South East", imported.get(COMPANY_NAME).get(REGION));
        assertNotNull(imported.get(COMPANY_NAME1).get(ID), COMPANY_NAME1 + " has no Id");

        // SC-5 disposition: the processed file is moved, unchanged, to the move directory under its own name.
        Path moved = outputDir().resolve(FILE_NAME);
        await(FILE_NAME + " moved to " + moved)
                .atMost(MOVE_TIMEOUT)
                .pollInterval(MOVE_POLL_INTERVAL)
                .until(() -> Files.exists(moved));
        assertArrayEquals(csv, Files.readAllBytes(moved));
        assertFalse(Files.exists(inputDir.resolve(FILE_NAME)), FILE_NAME + " still in the input directory");
    }

    /**
     * Deletes every Account this import created. Once the file was dropped, both names are queried once more first,
     * so an Account the test never got to read back (a wait that timed out) is deleted too. Before that nothing was
     * created, and querying again would only repeat a failed login, which counts towards the user's lockout. A
     * failure is logged at WARN and never thrown, as the original only printed it, and a failed query still leaves
     * the delete of the IDs already collected; the log names the Account names or IDs and only the failure's types
     * or status codes.
     */
    @AfterEach
    void tearDown() {
        if (fileDropped) {
            for (String name : COMPANY_NAMES) {
                try {
                    selectAccounts(name);
                } catch (Exception e) {
                    LOG.warn("Could not query the Salesforce Accounts named {} for deletion: {}", name,
                            failureTypes(e));
                }
            }
        }
        if (createdIds.isEmpty()) {
            return;
        }
        List<String> ids = List.copyOf(createdIds);
        createdIds.clear();
        try {
            List<DeleteResult> results = salesforce.delete(ids);
            for (int i = 0; i < results.size(); i++) {
                DeleteResult result = results.get(i);
                String id = i < ids.size() ? ids.get(i) : result.getId();
                if (result.isSuccess()) {
                    LOG.info("Deleted Salesforce Account {}", id);
                } else {
                    LOG.warn("Could not delete Salesforce Account {}: {}", id, errors(result));
                }
            }
        } catch (Exception e) {
            LOG.warn("Could not delete Salesforce Accounts {}: {}", ids, failureTypes(e));
        }
    }

    /**
     * Waits until the query for each name returns an Account whose ID is not in {@link #preExisting}. Each round
     * queries both names; a failing query is retried at the next round until the timeout.
     *
     * <p>Polled on the test thread: each round adds to {@link #createdIds}, which {@link #tearDown()} reads, and a
     * round still running on Awaitility's own thread after a timeout could change that list during the cleanup. Each
     * query is bounded by the client's 10 s timeouts, so a round can overrun the timeout by that much at most.
     *
     * @return per name, in {@link #COMPANY_NAMES} order, the first such Account, standing in for the original's
     *         {@code query-single}
     */
    private Map<String, Map<String, Object>> awaitImportedAccounts() {
        return await("Accounts " + COMPANY_NAMES + " imported into Salesforce")
                .atMost(IMPORT_TIMEOUT)
                .pollInSameThread()
                .pollDelay(Duration.ZERO)
                .pollInterval(QUERY_POLL_INTERVAL)
                .ignoreExceptions()
                .until(this::firstImportedAccounts, imported -> imported.size() == COMPANY_NAMES.size());
    }

    /**
     * Runs one round of {@link #awaitImportedAccounts()}.
     *
     * @return per name that already has one, the first returned Account whose ID is not in {@link #preExisting}
     */
    private Map<String, Map<String, Object>> firstImportedAccounts() throws Exception {
        Map<String, Map<String, Object>> imported = new LinkedHashMap<>();
        for (String name : COMPANY_NAMES) {
            selectAccounts(name).stream()
                    .filter(account -> isCreated(account.get(ID)))
                    .findFirst()
                    .ifPresent(account -> imported.put(name, account));
        }
        return imported;
    }

    /**
     * Runs the query of test flow {@code selectAccountFromSalesforce} and adds the ID of every returned Account that
     * is not in {@link #preExisting} to {@link #createdIds} as soon as the query returns.
     */
    private List<Map<String, Object>> selectAccounts(String name) throws Exception {
        List<Map<String, Object>> accounts = salesforce.query(selectByName(name));
        for (Map<String, Object> account : accounts) {
            Object id = account.get(ID);
            if (isCreated(id) && !createdIds.contains(id.toString())) {
                createdIds.add(id.toString());
            }
        }
        return accounts;
    }

    /** Whether {@code id} names an Account this import created: present and not held by the org beforehand. */
    private boolean isCreated(Object id) {
        return id != null && !preExisting.contains(id.toString());
    }

    private static String selectByName(String name) {
        return SELECT_BY_NAME + soqlLiteral(name) + "'";
    }

    private static Set<String> ids(List<Map<String, Object>> accounts) {
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> account : accounts) {
            Object id = account.get(ID);
            if (id != null) {
                ids.add(id.toString());
            }
        }
        return ids;
    }

    /** Escapes a value for a SOQL string literal: the backslash first, then the single quote. */
    static String soqlLiteral(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    /**
     * Names the Salesforce status code of each error of a failed delete, separated by {@code "; "}, or
     * {@code no error detail} when the result names none. The error message and fields are never rendered: they are
     * vendor free text that can repeat record values.
     */
    private static String errors(DeleteResult result) {
        if (result.getErrors() == null) {
            return NO_ERROR_DETAIL;
        }
        String statusCodes = Arrays.stream(result.getErrors())
                .filter(Objects::nonNull)
                .map(error -> error.getStatusCode())
                .filter(Objects::nonNull)
                .map(StatusCode::name)
                .collect(Collectors.joining("; "));
        return statusCodes.isEmpty() ? NO_ERROR_DETAIL : statusCodes;
    }

    /**
     * Names {@code failure} by type alone: the class name of the failure and of each exception in its
     * {@link Throwable#getCause()} chain, separated by {@code " <- "}. No message, cause message or suppressed
     * exception is read, because Salesforce and transport failures can repeat credentials, tokens, record values or
     * the SOQL. The walk stops after {@link #FAILURE_TYPES_LIMIT} names, marked {@code ...}, and at a cause already
     * named, marked {@code [circular]}; {@code null} reads {@code (null)}.
     */
    private static String failureTypes(Throwable failure) {
        if (failure == null) {
            return "(null)";
        }
        StringJoiner types = new StringJoiner(" <- ");
        Set<Throwable> named = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (named.size() == FAILURE_TYPES_LIMIT) {
                types.add("...");
                break;
            }
            if (!named.add(current)) {
                types.add("[circular]");
                break;
            }
            types.add(current.getClass().getName());
        }
        return types.toString();
    }

    private static Path inputDir() {
        return workDir.resolve("input");
    }

    private static Path outputDir() {
        return workDir.resolve("output");
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
                    + " not supplied (copy application-it.yml.example); live Salesforce test skipped";
        }
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(credentials);
        Properties properties = yaml.getObject();
        StandardEnvironment environment = new StandardEnvironment();
        for (String key : REQUIRED_KEYS) {
            String value = resolve(environment, properties == null ? null : properties.getProperty(key));
            if (value == null || value.isBlank() || value.trim().toUpperCase(Locale.ROOT).startsWith(TODO)) {
                return key + " missing, blank or TODO in " + CREDENTIALS_FILE + "; live Salesforce test skipped";
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
     * disabled class starts no application and attempts no Salesforce login.
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
                    ? ConditionEvaluationResult.enabled("live Salesforce credentials supplied")
                    : ConditionEvaluationResult.disabled(missing);
        }
    }
}
