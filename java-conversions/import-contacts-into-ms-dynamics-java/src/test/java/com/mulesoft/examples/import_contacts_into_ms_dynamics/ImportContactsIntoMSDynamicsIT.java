/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_ms_dynamics;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
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

import com.mulesoft.examples.import_contacts_into_ms_dynamics.client.DynamicsWebApiClient;
import com.mulesoft.examples.import_contacts_into_ms_dynamics.trigger.ContactsFileTrigger;

/**
 * Tier 2B live-sandbox test, run by failsafe ({@code mvn -B verify}): the original
 * {@code ImportContactsIntoMSDynamicsIT} against the real Dataverse environment configured in the git-ignored
 * {@code src/test/resources/application-it.yml} (template {@code application-it.yml.example}).
 *
 * <p>As the original, it imports {@code contacts.csv} through the application's file endpoint, which creates the
 * contacts John Doe ({@code john.doe@texasComp.com}) and Jane Doe ({@code jane.doe@texasComp.com}) in Microsoft
 * Dynamics 365, reads them back by e-mail, asserts {@code firstname} {@code John} and {@code lastname} {@code Doe},
 * and deletes them in {@link #tearDown()}. The original's pieces map as follows:
 * <ul>
 *   <li>{@code client.dispatch} to {@code file://./src/main/resources/input} with {@code outputPattern}
 *       {@code contacts.csv}: the file is placed, under that name, in the trigger's input directory and
 *       {@link ContactsFileTrigger#pollOnce()} runs the flow; the fixed 40000 ms sleep becomes a wait of at most
 *       40 s that ends as soon as both contacts are found;</li>
 *   <li>test flow {@code selectContactFromDynamics}
 *       ({@code dsql:SELECT contactid,firstname,lastname FROM contact where emailaddress1 = '#[payload]'}):
 *       {@link DynamicsWebApiClient#findContactsByEmail(String)}, the Web API query
 *       {@code contacts?$select=contactid,firstname,lastname&$filter=emailaddress1 eq '<e-mail>'}, whose first record
 *       is asserted as the original asserted the first element of its list;</li>
 *   <li>test flow {@code deleteContactFromDynamics} ({@code dynamicscrm:delete logicalName="contact"}) in
 *       {@code @After}: {@link DynamicsWebApiClient#deleteContact(String)}, {@code DELETE contacts(<guid>)}, in
 *       {@code @AfterEach}, each failure logged and never thrown, as the original only printed it;</li>
 *   <li>{@code crm.username}, {@code crm.password}, {@code crm.service-url}, {@code crm.auth-type} of
 *       {@code mule.test.properties}: the profile {@code it} makes Spring Boot load {@code dynamics.service-url},
 *       {@code dynamics.oauth.tenant-id}, {@code dynamics.oauth.client-id} and {@code dynamics.oauth.client-secret}
 *       from {@code application-it.yml} over the empty defaults of {@code application.yml}; the token endpoint and
 *       scope are derived from them there.</li>
 * </ul>
 *
 * <p>Nothing is mocked or stubbed: the real {@link DynamicsWebApiClient}, its Microsoft Entra client-credentials
 * token flow and its SC-9 failure handling run against the environment, and this class retries nothing itself.
 *
 * <p>The class is skipped, never failed, while {@code application-it.yml} or one of those four keys is missing,
 * blank, still {@code TODO} or an unresolvable placeholder ({@link #missingCredential()}); then no Spring context
 * starts and neither Entra nor Dataverse is called. A skipped run is not Tier 2B evidence. Logs name contact IDs and
 * e-mail addresses of the test data only, never a credential.
 */
@ExtendWith(ImportContactsIntoMSDynamicsIT.LiveCredentialsGate.class)
@SpringBootTest(classes = ImportContactsIntoMsDynamicsApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("it")
class ImportContactsIntoMSDynamicsIT {

    private static final Logger LOG = LoggerFactory.getLogger(ImportContactsIntoMSDynamicsIT.class);

    private static final String EMAIL1 = "john.doe@texasComp.com";
    private static final String EMAIL2 = "jane.doe@texasComp.com";

    /** Classpath resource the original dispatched, and the name its {@code outputPattern} gave the file. */
    private static final String FILE_NAME = "contacts.csv";

    private static final String CONTACT_ID = "contactid";

    private static final String CREDENTIALS_FILE = "application-it.yml";

    /** Exactly the keys of {@code application-it.yml.example}. */
    private static final List<String> REQUIRED_KEYS = List.of(
            "dynamics.service-url",
            "dynamics.oauth.tenant-id",
            "dynamics.oauth.client-id",
            "dynamics.oauth.client-secret");

    /** The marker {@code application-it.yml.example} gives every value to be supplied. */
    private static final String TODO = "TODO";

    /** The original's wait between dispatching the file and querying the contacts. */
    private static final Duration IMPORT_TIMEOUT = Duration.ofSeconds(40);

    private static final Duration QUERY_POLL_INTERVAL = Duration.ofSeconds(2);

    @TempDir
    static Path workDir;

    @Autowired
    private ContactsFileTrigger trigger;

    @Autowired
    private DynamicsWebApiClient client;

    /** Every contact ID read back for {@link #EMAIL1} or {@link #EMAIL2}, once each, deleted in {@link #tearDown()}. */
    private final List<String> contactIds = new ArrayList<>();

    /** Whether {@code contacts.csv} reached the input directory, so the import may have created contacts. */
    private boolean fileDropped;

    /*
     * The same check LiveCredentialsGate makes. The gate is what reports the class as skipped, because failsafe 3.1.2
     * counts a class whose @BeforeAll aborts as neither run nor skipped; this assumption still stops the class before
     * any test instance, Spring context or token request exists when the gate does not run, for example when
     * junit.jupiter.conditions.deactivate switches conditions off.
     */
    @BeforeAll
    static void requireCredentials() {
        String missing = missingCredential();
        Assumptions.assumeTrue(missing == null, missing);
    }

    /**
     * Points the file endpoint at this class's directories, over the {@code target/it-contacts} system properties of
     * failsafe. The suppliers are read while the context starts, after JUnit has injected {@link #workDir}. The
     * polling frequency of one hour leaves the scheduled startup poll, which finds the input directory empty, as the
     * only scheduled one. Nothing under {@code dynamics.*} is registered: those values come only from
     * {@code application-it.yml}.
     */
    @DynamicPropertySource
    static void contactDirectories(DynamicPropertyRegistry registry) {
        registry.add("contacts.input-dir", () -> workDir.resolve("input").toString());
        registry.add("contacts.move-to-dir", () -> workDir.resolve("output").toString());
        registry.add("contacts.polling-frequency", () -> "3600000");
    }

    @Test
    void testImport() throws Exception {
        // Departs from the original, which asserted whatever contact matched the e-mail: contacts an earlier failed
        // run left with these e-mails are recorded first, so only a contact this import created can satisfy the
        // assertions. They are deleted with the new ones.
        Set<String> existing1 = ids(selectContacts(EMAIL1));
        Set<String> existing2 = ids(selectContacts(EMAIL2));
        if (!existing1.isEmpty() || !existing2.isEmpty()) {
            LOG.warn("Dataverse contacts left by an earlier run, deleted after this test: {} {}, {} {}",
                    EMAIL1, existing1, EMAIL2, existing2);
        }

        dropContactsCsv();

        // Not asserted: the scheduled startup poll may take the file first, and the poller's in-process mark keeps
        // either run from importing it twice.
        trigger.pollOnce();

        // Both e-mails are read back before either is asserted, so a failed assertion still leaves both IDs for
        // tearDown. A query failure (an Entra or Dataverse rejection) ends the wait at once with its own exception.
        ImportedContacts imported = await("contacts.csv imported into Dataverse")
                .atMost(IMPORT_TIMEOUT)
                .pollDelay(Duration.ZERO)
                .pollInterval(QUERY_POLL_INTERVAL)
                .pollInSameThread()
                .until(() -> new ImportedContacts(newContacts(EMAIL1, existing1), newContacts(EMAIL2, existing2)),
                        ImportedContacts::bothFound);

        List<Map<String, Object>> john = imported.john();
        assertTrue(john.size() > 0);
        assertEquals("John", john.get(0).get("firstname"));

        List<Map<String, Object>> jane = imported.jane();
        assertTrue(jane.size() > 0);
        assertEquals("Doe", jane.get(0).get("lastname"));
    }

    /**
     * Deletes every contact read back for the two e-mails. Once the file was dropped, both are queried once more
     * first, so a contact the test never got to read back (a wait that timed out) is deleted too. Each failure is
     * logged and never thrown, as the original only printed it, so one failed delete does not keep the others from
     * running.
     */
    @AfterEach
    void tearDown() {
        if (fileDropped) {
            for (String email : List.of(EMAIL1, EMAIL2)) {
                try {
                    selectContacts(email);
                } catch (Exception e) {
                    LOG.warn("Could not query the Dataverse contacts with e-mail {} for deletion", email, e);
                }
            }
        }
        for (String id : List.copyOf(contactIds)) {
            try {
                client.deleteContact(id);
                LOG.info("Deleted Dataverse contact {}", id);
            } catch (Exception e) {
                LOG.error("Failed to delete Dataverse contact {}", id, e);
            }
        }
        contactIds.clear();
    }

    /**
     * Places the original {@code contacts.csv} in the directory the trigger polls. The file is written beside that
     * directory and moved in atomically, because the scheduled startup poll runs on its own thread and must never
     * list a half-written file.
     */
    private void dropContactsCsv() throws Exception {
        byte[] csv = new ClassPathResource(FILE_NAME).getContentAsByteArray();
        Path inputDir = Files.createDirectories(trigger.inputDirectory());
        Path staged = Files.write(Files.createDirectories(workDir.resolve("staging")).resolve(FILE_NAME), csv);
        fileDropped = true;
        Files.move(staged, inputDir.resolve(FILE_NAME), StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * @return the contacts the query for {@code email} returns whose ID is not in {@code existing}, in Dataverse's
     *         order
     */
    private List<Map<String, Object>> newContacts(String email, Set<String> existing) throws Exception {
        return selectContacts(email).stream()
                .filter(contact -> !existing.contains(String.valueOf(contact.get(CONTACT_ID))))
                .toList();
    }

    /**
     * Runs the query of test flow {@code selectContactFromDynamics} and adds the ID of every returned contact to
     * {@link #contactIds} as soon as the query returns.
     */
    private List<Map<String, Object>> selectContacts(String email) throws Exception {
        List<Map<String, Object>> contacts = client.findContactsByEmail(email);
        for (Map<String, Object> contact : contacts) {
            Object id = contact.get(CONTACT_ID);
            if (id != null && !contactIds.contains(id.toString())) {
                contactIds.add(id.toString());
            }
        }
        return contacts;
    }

    private static Set<String> ids(List<Map<String, Object>> contacts) {
        return contacts.stream()
                .map(contact -> contact.get(CONTACT_ID))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Why the class cannot run live, or {@code null} when {@code application-it.yml} supplies every one of
     * {@link #REQUIRED_KEYS} with a value that is neither blank nor {@code TODO} (in any letter case). The file is
     * read from the test classpath, where Spring Boot reads it for the profile {@code it}, so the check and the
     * context see the same file: the copy {@code process-test-resources} makes of
     * {@code src/test/resources/application-it.yml}. Nested keys are flattened to their dotted names, and a key
     * without a value, as the template leaves each, counts as blank. The reason names a key, never a value.
     */
    static String missingCredential() {
        ClassPathResource credentials = new ClassPathResource(CREDENTIALS_FILE);
        if (!credentials.exists()) {
            return "src/test/resources/" + CREDENTIALS_FILE + " not present; copy application-it.yml.example and "
                    + "supply Dataverse sandbox credentials; live Dynamics test skipped";
        }
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(credentials);
        Properties properties = yaml.getObject();
        StandardEnvironment environment = new StandardEnvironment();
        for (String key : REQUIRED_KEYS) {
            String value = resolve(environment, properties == null ? null : properties.getProperty(key));
            if (value == null || value.isBlank() || value.trim().toUpperCase(Locale.ROOT).startsWith(TODO)) {
                return key + " missing or TODO in " + CREDENTIALS_FILE + "; live Dynamics test skipped";
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

    /** The contacts this import created, per e-mail, as one poll of the wait read them back. */
    private record ImportedContacts(List<Map<String, Object>> john, List<Map<String, Object>> jane) {

        boolean bothFound() {
            return !john.isEmpty() && !jane.isEmpty();
        }
    }

    /**
     * Skips the class unless every live credential is supplied ({@link #missingCredential()}), deciding at class
     * level, before the test instance exists. The Spring context is loaded when the instance is prepared, so a
     * disabled class starts no application and requests no token.
     *
     * <p>A class condition beside the assumption in {@code @BeforeAll}: failsafe 3.1.2 counts a class whose
     * {@code @BeforeAll} aborts as neither run nor skipped, while the test of a class disabled here is reported
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
                    ? ConditionEvaluationResult.enabled("live Dataverse credentials supplied")
                    : ConditionEvaluationResult.disabled(missing);
        }
    }
}
