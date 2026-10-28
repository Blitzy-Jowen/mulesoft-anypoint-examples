/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_salesforce;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
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

import com.mulesoft.examples.import_contacts_into_salesforce.client.SalesforcePartnerClient;
import com.mulesoft.examples.import_contacts_into_salesforce.trigger.ContactsFileTrigger;
import com.sforce.soap.partner.DeleteResult;

/**
 * Tier 2B live-sandbox test, run by failsafe ({@code mvn -B verify}): the original {@code ImportContactsToSfdcIT}
 * against the real Salesforce org configured in the git-ignored {@code src/test/resources/application-it.yml}
 * (template {@code application-it.yml.example}).
 *
 * <p>As the original, it imports {@code contacts.csv} through the application's file endpoint, which creates the
 * Contacts John Doe ({@code john.doe@texasComp.com}) and Jane Doe ({@code jane.doe@texasComp.com}) in the org, reads
 * them back by e-mail, asserts {@code FirstName} {@code John} and {@code LastName} {@code Doe}, and deletes them in
 * {@link #tearDown()}. The original's pieces map as follows:
 * <ul>
 *   <li>{@code client.dispatch} to {@code file://./src/main/resources/input} with {@code outputPattern}
 *       {@code contacts.csv}: the file is placed, under that name, in this class's input directory and
 *       {@link ContactsFileTrigger#pollOnce()} runs the flow; the fixed 8000 ms sleep becomes a bounded wait;</li>
 *   <li>test flow {@code selectContactFromSalesforce} ({@code sfdc:query-single} of
 *       {@code SELECT FirstName,Id,LastName FROM Contact WHERE Email = '#[payload]'}): the same SOQL through the real
 *       {@link SalesforcePartnerClient}, its first matching record standing in for {@code query-single};</li>
 *   <li>test flow {@code deleteContactFromSalesforce} ({@code sfdc:delete} of the collected IDs) in {@code @After}:
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
 * starts and Salesforce is never called. A skipped run is not Tier 2B evidence. Logs name record IDs and e-mail
 * addresses of the test data only, never a credential.
 */
@ExtendWith(ImportContactsToSfdcIT.LiveCredentialsGate.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("it")
class ImportContactsToSfdcIT {

    private static final Logger LOG = LoggerFactory.getLogger(ImportContactsToSfdcIT.class);

    private static final String EMAIL1 = "john.doe@texasComp.com";
    private static final String EMAIL2 = "jane.doe@texasComp.com";

    /** Classpath resource the original dispatched, and the name its {@code outputPattern} gave the file. */
    private static final String FILE_NAME = "contacts.csv";

    /** The query of test flow {@code selectContactFromSalesforce}, up to the e-mail literal. */
    private static final String SELECT_BY_EMAIL = "SELECT FirstName,Id,LastName FROM Contact WHERE Email = '";

    private static final String ID = "Id";

    private static final String CREDENTIALS_FILE = "application-it.yml";

    /** Exactly the keys of {@code application-it.yml.example}; {@code sfdc.auth-endpoint} is optional. */
    private static final List<String> REQUIRED_KEYS = List.of("sfdc.username", "sfdc.password", "sfdc.securityToken");

    private static final String TODO = "TODO";

    /** How long the imported Contacts may take to be returned by the query. */
    private static final Duration IMPORT_TIMEOUT = Duration.ofSeconds(60);

    private static final Duration QUERY_POLL_INTERVAL = Duration.ofSeconds(2);

    @TempDir
    static Path workDir;

    @Autowired
    private SalesforcePartnerClient salesforce;

    @Autowired
    private ContactsFileTrigger trigger;

    /** Every Contact ID read back for {@link #EMAIL1} or {@link #EMAIL2}, deleted in {@link #tearDown()}. */
    private final List<String> contactIds = new ArrayList<>();

    /** Whether {@code contacts.csv} reached the input directory, so the import may have created Contacts. */
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
     * Points the file endpoint at this class's directories. The suppliers are read while the context starts, after
     * JUnit has injected {@link #workDir}. The polling frequency of one hour leaves the scheduled startup poll, which
     * finds the input directory empty, as the only scheduled one.
     */
    @DynamicPropertySource
    static void contactDirectories(DynamicPropertyRegistry registry) {
        registry.add("contacts.input-dir", () -> inputDir().toString());
        registry.add("contacts.move-to-dir", () -> workDir.resolve("output").toString());
        registry.add("contacts.polling-frequency", () -> "3600000");
    }

    @Test
    void testImport() throws Exception {
        // Departs from the original, which read back whatever Contact matched: Contacts an earlier failed run left
        // with these e-mails are recorded first, so only a Contact this import created can satisfy the assertions.
        // They are deleted with the new ones.
        Set<String> existing1 = ids(selectContacts(EMAIL1));
        Set<String> existing2 = ids(selectContacts(EMAIL2));
        if (!existing1.isEmpty() || !existing2.isEmpty()) {
            LOG.warn("Contacts left by an earlier run, deleted after this test: {} {}, {} {}",
                    EMAIL1, existing1, EMAIL2, existing2);
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
        Map<String, Object> john = awaitImportedContact(EMAIL1, existing1);
        Map<String, Object> jane = awaitImportedContact(EMAIL2, existing2);

        assertEquals("John", john.get("FirstName"));
        assertEquals("Doe", jane.get("LastName"));
    }

    /**
     * Deletes every Contact read back for the two e-mails. Once the file was dropped, both are queried once more
     * first, so a Contact the test never got to read back (a wait that timed out on the other e-mail) is deleted too.
     * Before that nothing was created, and querying again would only repeat a failed login, which counts towards the
     * user's lockout. A failure is logged and never thrown, as the original only printed it.
     */
    @AfterEach
    void tearDown() {
        if (fileDropped) {
            for (String email : List.of(EMAIL1, EMAIL2)) {
                try {
                    selectContacts(email);
                } catch (Exception e) {
                    LOG.warn("Could not query the Salesforce Contacts with e-mail {} for deletion", email, e);
                }
            }
        }
        if (contactIds.isEmpty()) {
            return;
        }
        List<String> ids = List.copyOf(contactIds);
        contactIds.clear();
        try {
            List<DeleteResult> results = salesforce.delete(ids);
            for (int i = 0; i < results.size(); i++) {
                DeleteResult result = results.get(i);
                String id = i < ids.size() ? ids.get(i) : result.getId();
                if (result.isSuccess()) {
                    LOG.info("Deleted Salesforce Contact {}", id);
                } else {
                    LOG.warn("Could not delete Salesforce Contact {}: {}", id, errors(result));
                }
            }
        } catch (Exception e) {
            LOG.warn("Could not delete Salesforce Contacts {}", ids, e);
        }
    }

    /**
     * Waits until the query for {@code email} returns a Contact whose ID is not in {@code existing}.
     *
     * @return the first such Contact, standing in for the original's {@code query-single}
     */
    private Map<String, Object> awaitImportedContact(String email, Set<String> existing) {
        List<Map<String, Object>> imported = await("Contact " + email + " imported into Salesforce")
                .atMost(IMPORT_TIMEOUT)
                .pollDelay(Duration.ZERO)
                .pollInterval(QUERY_POLL_INTERVAL)
                .until(() -> selectContacts(email).stream()
                                .filter(contact -> !existing.contains(String.valueOf(contact.get(ID))))
                                .toList(),
                        contacts -> !contacts.isEmpty());
        return imported.get(0);
    }

    /**
     * Runs the query of test flow {@code selectContactFromSalesforce} and adds the ID of every returned Contact to
     * {@link #contactIds} as soon as the query returns.
     */
    private List<Map<String, Object>> selectContacts(String email) throws Exception {
        List<Map<String, Object>> contacts = salesforce.query(SELECT_BY_EMAIL + soqlLiteral(email) + "'");
        for (Map<String, Object> contact : contacts) {
            Object id = contact.get(ID);
            if (id != null && !contactIds.contains(id.toString())) {
                contactIds.add(id.toString());
            }
        }
        return contacts;
    }

    private static Set<String> ids(List<Map<String, Object>> contacts) {
        return contacts.stream()
                .map(contact -> contact.get(ID))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Escapes a value for a SOQL string literal: the backslash first, then the single quote. */
    static String soqlLiteral(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String errors(DeleteResult result) {
        if (result.getErrors() == null || result.getErrors().length == 0) {
            return "no error detail";
        }
        return Arrays.stream(result.getErrors())
                .map(error -> error.getStatusCode() + " " + error.getMessage())
                .collect(Collectors.joining("; "));
    }

    private static Path inputDir() {
        return workDir.resolve("input");
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
