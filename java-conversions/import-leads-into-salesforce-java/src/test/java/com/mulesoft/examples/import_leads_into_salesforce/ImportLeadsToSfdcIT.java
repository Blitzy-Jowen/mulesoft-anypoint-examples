/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_leads_into_salesforce;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
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

import com.mulesoft.examples.import_leads_into_salesforce.client.SalesforcePartnerClient;
import com.mulesoft.examples.import_leads_into_salesforce.trigger.LeadsFileTrigger;
import com.sforce.soap.partner.DeleteResult;

/**
 * Tier 2B live-sandbox test, run by failsafe ({@code mvn -B verify}): the original {@code ImportLeadsToSfdcIT} against
 * the real Salesforce org configured in the git-ignored {@code src/test/resources/application-it.yml} (template
 * {@code application-it.yml.example}).
 *
 * <p>As the original, it imports {@code leads.csv} through the application's file endpoint and batch job
 * {@code CreateLeadsBatch}, which creates the Leads Ishmael Alexander ({@code faucibus@egetmetus.org}) and Cole Burks
 * ({@code sem.egestas@mollis.org}) unless Leads with those e-mails already exist, reads them back by e-mail, asserts
 * {@code FirstName} {@code Ishmael} and {@code LastName} {@code Burks}, and deletes them in {@link #tearDown()}. The
 * original's pieces map as follows:
 * <ul>
 *   <li>{@code client.dispatch} to {@code file://./src/main/resources/input} with {@code outputPattern}
 *       {@code leads.csv}: the file is placed, under that name, in this class's input directory and
 *       {@link LeadsFileTrigger#pollOnce()} runs the job; the fixed 8000 ms sleep becomes a bounded wait for the job
 *       to finish and for the Leads to be returned;</li>
 *   <li>test flow {@code selectLeadFromSalesforce} ({@code sfdc:query-single} of
 *       {@code SELECT FirstName,Id,LastName FROM Lead WHERE Email = '#[payload]'}): the same SOQL through the real
 *       {@link SalesforcePartnerClient}, its first matching record standing in for {@code query-single};</li>
 *   <li>test flow {@code deleteLeadFromSalesforce} ({@code sfdc:delete} of the collected IDs) in {@code @After}:
 *       {@link SalesforcePartnerClient#delete(List)} in {@code @AfterEach}, whose failure is logged, never thrown, as
 *       the original only printed it;</li>
 *   <li>{@code sfdc.user}, {@code sfdc.password}, {@code sfdc.securityToken} of {@code mule.test.properties}: the
 *       profile {@code it} makes Spring Boot load {@code sfdc.username}, {@code sfdc.password} and
 *       {@code sfdc.securityToken} from {@code application-it.yml} over the empty defaults of
 *       {@code application.yml}.</li>
 * </ul>
 *
 * <p>A Lead an earlier failed run left with one of the two e-mails is not duplicated by the job, whose
 * {@code LeadExistsStep} finds it; it is read back, asserted and deleted, as the original did.
 *
 * <p>The class is skipped, never failed, while {@code application-it.yml} or one of those three keys is missing,
 * blank, still {@code TODO} or an unresolvable placeholder ({@link #missingCredential()}); then no Spring context
 * starts and Salesforce is never called. A skipped run is not Tier 2B evidence. Logs name the record IDs and e-mail
 * addresses of the test data, the status codes of a failed delete and the class names of a failure and its causes
 * ({@link #failureTypes(Throwable)}), never a credential, and never attach the failure or repeat vendor text: an
 * exception's message, its causes' and suppressed exceptions' messages and an error's message or fields can carry
 * record fields, SOQL values or tokens.
 */
@ExtendWith(ImportLeadsToSfdcIT.LiveCredentialsGate.class)
@SpringBootTest(classes = ImportLeadsIntoSalesforceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("it")
class ImportLeadsToSfdcIT {

    private static final Logger LOG = LoggerFactory.getLogger(ImportLeadsToSfdcIT.class);

    private static final String EMAIL1 = "faucibus@egetmetus.org";
    private static final String EMAIL2 = "sem.egestas@mollis.org";

    /** Classpath resource the original dispatched, and the name its {@code outputPattern} gave the file. */
    private static final String FILE_NAME = "leads.csv";

    /** The query of test flow {@code selectLeadFromSalesforce}, up to the e-mail literal. */
    private static final String SELECT_BY_EMAIL = "SELECT FirstName,Id,LastName FROM Lead WHERE Email = '";

    private static final String ID = "Id";

    private static final String CREDENTIALS_FILE = "application-it.yml";

    /** Exactly the keys of {@code application-it.yml.example}; {@code sfdc.auth-endpoint} is optional. */
    private static final List<String> REQUIRED_KEYS = List.of("sfdc.username", "sfdc.password", "sfdc.securityToken");

    private static final String TODO = "TODO";

    /** Number of classes {@link #failureTypes(Throwable)} names, the failure's own included, before it stops. */
    private static final int FAILURE_TYPE_LIMIT = 5;

    private static final String CAUSE_SEPARATOR = " <- ";

    private static final String MORE_CAUSES_MARKER = "...";

    private static final String CIRCULAR_CAUSE_MARKER = "[circular]";

    private static final String NULL_LOG_TOKEN = "(null)";

    /**
     * How long the job, and then each Lead query, may take. One job makes the lazy {@code login}, one
     * {@code LeadExistsStep} query per record and one {@code create}, each bounded by the application's 10 s
     * Salesforce timeouts.
     */
    private static final Duration IMPORT_TIMEOUT = Duration.ofSeconds(90);

    /** How often the move directory is checked for the processed file; a local file check only. */
    private static final Duration FILE_POLL_INTERVAL = Duration.ofMillis(200);

    /** How often the Lead query is repeated while it returns nothing. */
    private static final Duration QUERY_POLL_INTERVAL = Duration.ofSeconds(3);

    /**
     * Every Lead ID read back for {@link #EMAIL1} or {@link #EMAIL2}, deleted in {@link #tearDown()}; static, as the
     * original's list. Touched only from the test thread, because {@link #awaitLead(String, Duration)} polls in it.
     */
    private static final List<String> leadIds = new ArrayList<>();

    @TempDir
    static Path tempDir;

    @Autowired
    private LeadsFileTrigger leadsFileTrigger;

    @Autowired
    private SalesforcePartnerClient salesforcePartnerClient;

    /** Whether {@code leads.csv} reached the input directory, so the job may have created Leads. */
    private boolean fileDropped;

    /** Whether the latest Lead query threw; written by {@link #findLead(String)}, read in {@link #tearDown()}. */
    private boolean lastQueryFailed;

    /*
     * The same check LiveCredentialsGate makes. The gate is what reports the class as skipped; this assumption still
     * stops the class before any test instance, Spring context or Salesforce login exists when the gate does not run,
     * for example when junit.jupiter.conditions.deactivate switches conditions off. The directories exist before the
     * context starts, so the scheduled startup poll lists an empty input directory.
     */
    @BeforeAll
    static void requireLiveCredentials() throws IOException {
        String missing = missingCredential();
        Assumptions.assumeTrue(missing == null, missing);
        Files.createDirectories(inputDir());
        Files.createDirectories(outputDir());
    }

    /**
     * Points the file endpoint at this class's directories, over the failsafe system properties. The suppliers are
     * read while the context starts, after JUnit has injected {@link #tempDir}. The polling frequency of one hour
     * leaves the scheduled startup poll as the only scheduled one. No {@code sfdc.*} value is registered: those come
     * from {@code application-it.yml} alone.
     */
    @DynamicPropertySource
    static void leadDirectories(DynamicPropertyRegistry registry) {
        registry.add("leads.input-dir", () -> inputDir().toString());
        registry.add("leads.move-to-dir", () -> outputDir().toString());
        registry.add("leads.polling-frequency", () -> "3600000");
    }

    @Test
    void testImport() throws Exception {
        byte[] csv = new ClassPathResource(FILE_NAME).getContentAsByteArray();
        // Staged beside the input directory and moved in atomically: the scheduled startup poll runs on its own
        // thread and must never list a half-written file.
        Path staged = Files.write(Files.createDirectories(tempDir.resolve("staging")).resolve(FILE_NAME), csv);
        fileDropped = true;
        Files.move(staged, inputDir().resolve(FILE_NAME), StandardCopyOption.ATOMIC_MOVE);

        // Not asserted: the scheduled startup poll may take the file first, and the poller's in-process mark keeps
        // either run from importing it twice.
        leadsFileTrigger.pollOnce();

        // The poller moves the file only once the batch job has returned, so the Leads are not queried while the job
        // can still create them; a Lead left by an earlier run would otherwise satisfy the wait mid-import.
        Path processed = outputDir().resolve(FILE_NAME);
        await("leads.csv processed and moved to the move directory")
                .atMost(IMPORT_TIMEOUT)
                .pollDelay(Duration.ZERO)
                .pollInterval(FILE_POLL_INTERVAL)
                .until(() -> Files.exists(processed));

        // Both are read back before either is asserted, so a failed assertion still leaves both IDs for tearDown.
        Map<String, Object> ishmael = awaitLead(EMAIL1);
        Map<String, Object> burks = awaitLead(EMAIL2);

        assertEquals("Ishmael", Objects.toString(ishmael.get("FirstName"), null));
        assertEquals("Burks", Objects.toString(burks.get("LastName"), null));
    }

    /**
     * Deletes every Lead read back for the two e-mails. Once the file was dropped, both are queried once more first,
     * so a Lead the test never got to read back (a wait that timed out on the other e-mail) is deleted too. The
     * re-query is left out before the drop, when nothing was created, and after a query that threw, when it would
     * only repeat a failed login, which counts towards the reused sandbox user's lockout; a Lead left behind then is
     * found, asserted and deleted by the next run, because the job does not create a second one. A failure is logged
     * by its type identifiers alone and never thrown, as the original only printed it; the ID list is always cleared.
     */
    @AfterEach
    void tearDown() {
        if (fileDropped && !lastQueryFailed) {
            for (String email : List.of(EMAIL1, EMAIL2)) {
                try {
                    findLead(email);
                } catch (Exception e) {
                    LOG.warn("Could not query the Salesforce Leads with e-mail {} for deletion: {}", email,
                            failureTypes(e));
                    break;
                }
            }
        }
        if (leadIds.isEmpty()) {
            return;
        }
        List<String> ids = List.copyOf(leadIds);
        leadIds.clear();
        try {
            List<DeleteResult> results = salesforcePartnerClient.delete(ids);
            for (int i = 0; i < results.size(); i++) {
                DeleteResult result = results.get(i);
                String id = i < ids.size() ? ids.get(i) : result.getId();
                if (result.isSuccess()) {
                    LOG.info("Deleted Salesforce Lead {}", id);
                } else {
                    LOG.warn("Could not delete Salesforce Lead {}: {}", id, errors(result));
                }
            }
        } catch (Exception e) {
            LOG.warn("Could not delete Salesforce Leads {}: {}", ids, failureTypes(e));
        }
    }

    /**
     * Waits up to {@link #IMPORT_TIMEOUT} until the query for {@code email} returns a Lead, as
     * {@link #awaitLead(String, Duration)} does.
     *
     * @return the first matching Lead, standing in for the original's {@code query-single}
     */
    private Map<String, Object> awaitLead(String email) {
        return awaitLead(email, IMPORT_TIMEOUT);
    }

    /**
     * Waits up to {@code timeout} until the query for {@code email} returns a Lead.
     *
     * <p>Polled on the test thread: each query adds to {@link #leadIds}, which {@link #tearDown()} deletes and
     * clears, and a query still running on Awaitility's own thread after a timeout could add an ID once the cleanup
     * is done, leaving that Lead behind. On the test thread no query outlives the wait, so the wait returns or throws
     * only once every ID its queries read back is in the list. Every Salesforce call a query makes is bounded by the
     * client's 10 s connect and read timeouts, so a round can overrun the timeout by no more than those allow.
     *
     * <p>Exceptions are deliberately not ignored: a failing query (for example a rejected login) fails the test at
     * once with the vendor's exception, because repeating it every few seconds would repeat failed logins against
     * the reused sandbox user and count towards its lockout. The client's executor already re-logs in once after an
     * expired session.
     *
     * @param email   the e-mail the Lead is queried by
     * @param timeout how long the wait polls; a query still running when it passes completes, and its answer counts
     * @return the first matching Lead, standing in for the original's {@code query-single}
     */
    Map<String, Object> awaitLead(String email, Duration timeout) {
        List<Map<String, Object>> leads = await("Lead " + email + " imported into Salesforce")
                .atMost(timeout)
                .pollInSameThread()
                .pollDelay(Duration.ZERO)
                .pollInterval(QUERY_POLL_INTERVAL)
                .until(() -> findLead(email), found -> !found.isEmpty());
        return leads.get(0);
    }

    /**
     * Runs the query of test flow {@code selectLeadFromSalesforce} and adds the ID of every returned Lead to
     * {@link #leadIds} as soon as the query returns.
     */
    private List<Map<String, Object>> findLead(String email) throws Exception {
        List<Map<String, Object>> leads;
        try {
            leads = salesforcePartnerClient.query(SELECT_BY_EMAIL + escapeSoql(email) + "'");
        } catch (Exception e) {
            lastQueryFailed = true;
            throw e;
        }
        lastQueryFailed = false;
        for (Map<String, Object> lead : leads) {
            Object id = lead.get(ID);
            if (id != null && !leadIds.contains(id.toString())) {
                leadIds.add(id.toString());
            }
        }
        return leads;
    }

    /** Escapes a value for a SOQL string literal: the backslash first, then the single quote. */
    static String escapeSoql(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    /**
     * Names the Salesforce status code of each error of a failed delete, separated by {@code "; "}, or
     * {@code no error detail} when the result carries none; an error without a status code is named {@code (null)}.
     * The error's message and fields are vendor text that can repeat record values, so they are never read.
     */
    private static String errors(DeleteResult result) {
        if (result.getErrors() == null || result.getErrors().length == 0) {
            return "no error detail";
        }
        return Arrays.stream(result.getErrors())
                .map(error -> error == null || error.getStatusCode() == null
                        ? NULL_LOG_TOKEN : error.getStatusCode().name())
                .collect(Collectors.joining("; "));
    }

    /**
     * Names {@code failure} by type identifiers only: the class name of the failure and of each cause, separated by
     * {@code " <- "}, for example {@code com.sforce.ws.ConnectionException <- java.net.SocketTimeoutException}. At
     * most {@link #FAILURE_TYPE_LIMIT} classes are named, then {@code ...}; a cause already named ends the walk with
     * {@code [circular]}; a {@code null} failure is {@code (null)}. Messages, suppressed exceptions and stack traces
     * are never read, because vendor text can repeat record fields, SOQL values or tokens.
     */
    private static String failureTypes(Throwable failure) {
        if (failure == null) {
            return NULL_LOG_TOKEN;
        }
        StringBuilder out = new StringBuilder(failure.getClass().getName());
        Set<Throwable> named = Collections.newSetFromMap(new IdentityHashMap<>());
        named.add(failure);
        int count = 1;
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            out.append(CAUSE_SEPARATOR);
            if (count == FAILURE_TYPE_LIMIT) {
                out.append(MORE_CAUSES_MARKER);
                break;
            }
            if (!named.add(cause)) {
                out.append(CIRCULAR_CAUSE_MARKER);
                break;
            }
            out.append(cause.getClass().getName());
            count++;
        }
        return out.toString();
    }

    private static Path inputDir() {
        return tempDir.resolve("input");
    }

    private static Path outputDir() {
        return tempDir.resolve("output");
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
