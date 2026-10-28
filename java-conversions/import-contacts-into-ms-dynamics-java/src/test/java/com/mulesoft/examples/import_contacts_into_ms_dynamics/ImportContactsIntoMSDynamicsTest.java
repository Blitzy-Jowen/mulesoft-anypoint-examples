/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_ms_dynamics;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.noContent;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.mulesoft.examples.import_contacts_into_ms_dynamics.client.DynamicsWebApiClient;
import com.mulesoft.examples.import_contacts_into_ms_dynamics.trigger.ContactsFileTrigger;

/**
 * Port of the original example's {@code ImportContactsIntoMSDynamicsIT} functional test, keeping its input and its
 * assertions: after {@code contacts.csv} is imported, the contact read back by e-mail {@code john.doe@texasComp.com}
 * has {@code firstname} {@code John}, the contact read back by e-mail {@code jane.doe@texasComp.com} has
 * {@code lastname} {@code Doe}, and both are deleted afterwards.
 *
 * <p>The original ran flow {@code crm-create-account} in an embedded Mule runtime against a live CRM Online
 * organization. Here the whole application context runs without a web server, and one WireMock server stands in for
 * both the Microsoft Entra token endpoint and the Dataverse Web API. Everything between the polled file and the HTTP
 * requests (the trigger, the SC-5 file poller, the CSV reader, the {@code CSV To Map} mapper, the import service, the
 * Web API client and its client-credentials token) is the production code, and every tenant, client, secret and token
 * is a fake test value. The stub keeps each contact the way Dataverse would: the read-back query of an e-mail finds
 * nothing until the contact with that e-mail has been created, so the ported assertions fail when the import did not
 * create it. The same assertions run against a live environment in the failsafe {@code ImportContactsIntoMSDynamicsIT}.
 *
 * <p>Parts of the original that change form:
 * <ul>
 *   <li>{@code client.dispatch} of the file to {@code file://./src/main/resources/input} becomes the file placed in
 *       this class's input directory and {@link ContactsFileTrigger#pollOnce()}; the fixed 40000 ms sleep becomes a
 *       bounded wait on the file's move to the output directory, which the poller performs only after the import;</li>
 *   <li>the test flows {@code selectContactFromDynamics} ({@code dsql:SELECT contactid,firstname,lastname FROM contact
 *       where emailaddress1 = '#[payload]'}) and {@code deleteContactFromDynamics} ({@code dynamicscrm:delete
 *       logicalName="contact"}) of {@code testflows/test-flows.xml} become
 *       {@link DynamicsWebApiClient#findContactsByEmail(String)} and
 *       {@link DynamicsWebApiClient#deleteContact(String)}; a failed delete fails the test here, because the stub is
 *       deterministic, where the original only printed it;</li>
 *   <li>loading {@code mule.test.properties} into the {@code crm.*} system properties and pointing the Mule
 *       application home at {@code ./mappings}: no Mule runtime runs and no live credential is read.</li>
 * </ul>
 * Beyond the original assertions, the exact request bodies of both creates, in file order, the bearer token they
 * carry, and the file endpoint's {@code moveToDirectory} disposition are checked.
 */
@SpringBootTest(classes = ImportContactsIntoMsDynamicsApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ImportContactsIntoMSDynamicsTest {

    private static final String EMAIL1 = "john.doe@texasComp.com";
    private static final String EMAIL2 = "jane.doe@texasComp.com";

    /** Classpath resource the original dispatched, and the name its {@code outputPattern} gave the file. */
    private static final String FILE_NAME = "contacts.csv";

    private static final String TENANT_ID = "test-tenant";

    private static final String TOKEN_PATH = "/" + TENANT_ID + "/oauth2/v2.0/token";

    private static final String TOKEN_JSON =
            "{\"access_token\":\"test-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}";

    private static final String BEARER = "Bearer test-token";

    private static final String CONTACTS_PATH = "/api/data/v9.2/contacts";

    private static final String JOHN_ID = "00000000-0000-0000-0000-000000000001";
    private static final String JANE_ID = "00000000-0000-0000-0000-000000000002";

    /**
     * The {@code dynamicscrm:create} attributes of each CSV record as the client sends them: {@code firstname},
     * {@code lastname} from {@code surname}, {@code emailaddress1} from {@code email}, {@code telephone1} from
     * {@code phone}, in that order, the phone numbers kept as strings with their leading zero.
     */
    private static final String JOHN_BODY = "{\"firstname\":\"John\",\"lastname\":\"Doe\","
            + "\"emailaddress1\":\"john.doe@texasComp.com\",\"telephone1\":\"096548763\"}";
    private static final String JANE_BODY = "{\"firstname\":\"Jane\",\"lastname\":\"Doe\","
            + "\"emailaddress1\":\"jane.doe@texasComp.com\",\"telephone1\":\"091558780\"}";

    /** Scenario state of a stubbed contact once its create has been received. */
    private static final String CREATED = "created";

    private static final Duration AWAIT_LIMIT = Duration.ofSeconds(20);

    /** Started on class initialization: the {@link DynamicPropertySource} suppliers read its port. */
    private static final WireMockServer WIREMOCK = new WireMockServer(options().dynamicPort());

    static {
        WIREMOCK.start();
    }

    @TempDir
    static Path workDir;

    @Autowired
    ContactsFileTrigger trigger;

    @Autowired
    DynamicsWebApiClient client;

    private final List<String> contactIds = new ArrayList<>();

    /**
     * Points Entra and Dataverse at the WireMock server and the file endpoint at this class's directories. The
     * suppliers are read while the context starts, after JUnit has injected {@link #workDir}, and outrank both the
     * {@code test} profile and surefire's {@code contacts.*} system properties. The polling frequency of one hour
     * leaves the scheduled startup poll as the only scheduled one.
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("dynamics.service-url", WIREMOCK::baseUrl);
        registry.add("dynamics.oauth.token-uri", () -> WIREMOCK.baseUrl() + TOKEN_PATH);
        registry.add("dynamics.oauth.tenant-id", () -> TENANT_ID);
        registry.add("dynamics.oauth.client-id", () -> "test-client-id");
        registry.add("dynamics.oauth.client-secret", () -> "test-client-secret");
        registry.add("contacts.input-dir", () -> inputDir().toString());
        registry.add("contacts.move-to-dir", () -> outputDir().toString());
        registry.add("contacts.polling-frequency", () -> "3600000");
    }

    @AfterAll
    static void stopWireMock() {
        WIREMOCK.stop();
    }

    @BeforeEach
    void stubEntraAndDataverse() {
        WIREMOCK.resetAll();
        WIREMOCK.stubFor(post(urlPathEqualTo(TOKEN_PATH)).willReturn(okJson(TOKEN_JSON)));
        stubContact(EMAIL1, JOHN_ID, "John", "Doe");
        stubContact(EMAIL2, JANE_ID, "Jane", "Doe");
        WIREMOCK.stubFor(delete(urlPathMatching("/api/data/v9\\.2/contacts\\([0-9a-f-]{36}\\)"))
                .withHeader("Authorization", equalTo(BEARER))
                .willReturn(noContent()));
    }

    @Test
    void testImport() throws Exception {
        byte[] csv = new ClassPathResource(FILE_NAME).getContentAsByteArray();
        Path inputDir = Files.createDirectories(inputDir());
        // Staged beside the input directory and moved in atomically: the scheduled startup poll runs on its own
        // thread and must never list a half-written file.
        Path staged = Files.write(Files.createDirectories(workDir.resolve("staging")).resolve(FILE_NAME), csv);
        Files.move(staged, inputDir.resolve(FILE_NAME), StandardCopyOption.ATOMIC_MOVE);

        // Not asserted: the scheduled startup poll may take the file first, and the poller's in-process mark keeps
        // either run from importing it twice.
        trigger.pollOnce();

        // The poller moves the file only after the import returned, so every create has completed once it is moved.
        Path moved = outputDir().resolve(FILE_NAME);
        await().atMost(AWAIT_LIMIT)
                .until(() -> Files.isRegularFile(moved) && Files.notExists(inputDir.resolve(FILE_NAME)));
        assertArrayEquals(csv, Files.readAllBytes(moved), "The polled file should be moved unchanged");

        // One create per record, in file order, with the attributes in the order the flow listed them. Raw bodies
        // are compared because a JSON-equality match would ignore the attribute order.
        List<LoggedRequest> creates = WIREMOCK.findAll(postRequestedFor(urlPathEqualTo(CONTACTS_PATH)));
        assertEquals(2, creates.size(), () -> "contact creates: " + creates);
        assertEquals(JOHN_BODY, creates.get(0).getBodyAsString());
        assertEquals(JANE_BODY, creates.get(1).getBodyAsString());
        for (LoggedRequest create : creates) {
            assertEquals(BEARER, create.getHeader("Authorization"));
        }

        // The original assertions, on the contacts read back from Dataverse by e-mail.
        List<Map<String, Object>> contacts = client.findContactsByEmail(EMAIL1);
        assertTrue(contacts.size() > 0);
        Map<String, Object> contact = contacts.get(0);
        assertEquals("John", contact.get("firstname"));
        contactIds.add(contact.get("contactid").toString());

        contacts = client.findContactsByEmail(EMAIL2);
        assertTrue(contacts.size() > 0);
        contact = contacts.get(0);
        assertEquals("Doe", contact.get("lastname"));
        contactIds.add(contact.get("contactid").toString());

        // One client-credentials token served the creates and the read-backs.
        WIREMOCK.verify(exactly(1), postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
    }

    /** Deletes every contact the test read back, as the original's {@code deleteContactFromDynamics} did. */
    @AfterEach
    void tearDown() throws Exception {
        try {
            for (String id : contactIds) {
                client.deleteContact(id);
                WIREMOCK.verify(exactly(1), deleteRequestedFor(urlPathEqualTo(CONTACTS_PATH + "(" + id + ")")));
            }
        } finally {
            contactIds.clear();
        }
    }

    /**
     * Stubs one contact's lifecycle: its create, answered {@code 204} with its {@code OData-EntityId}, and the
     * read-back query by its e-mail, which finds nothing before the create and the contact after it.
     */
    private static void stubContact(String email, String id, String firstName, String lastName) {
        String scenario = "contact " + email;
        WIREMOCK.stubFor(post(urlPathEqualTo(CONTACTS_PATH))
                .withHeader("Authorization", equalTo(BEARER))
                .withRequestBody(containing("\"emailaddress1\":\"" + email + "\""))
                .inScenario(scenario)
                .willSetStateTo(CREATED)
                .willReturn(noContent()
                        .withHeader("OData-EntityId", WIREMOCK.baseUrl() + CONTACTS_PATH + "(" + id + ")")));
        WIREMOCK.stubFor(findByEmail(email)
                .inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{\"value\":[]}")));
        WIREMOCK.stubFor(findByEmail(email)
                .inScenario(scenario)
                .whenScenarioStateIs(CREATED)
                .willReturn(okJson("{\"value\":[{\"contactid\":\"" + id + "\",\"firstname\":\"" + firstName
                        + "\",\"lastname\":\"" + lastName + "\"}]}")));
    }

    /** The read-back query of {@code selectContactFromDynamics}, as the Web API receives it. */
    private static MappingBuilder findByEmail(String email) {
        return get(urlPathEqualTo(CONTACTS_PATH))
                .withHeader("Authorization", equalTo(BEARER))
                .withQueryParam("$select", equalTo("contactid,firstname,lastname"))
                .withQueryParam("$filter", equalTo("emailaddress1 eq '" + email + "'"));
    }

    private static Path inputDir() {
        return workDir.resolve("input");
    }

    private static Path outputDir() {
        return workDir.resolve("output");
    }
}
