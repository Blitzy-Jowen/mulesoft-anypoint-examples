/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_leads_into_salesforce;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.mulesoft.examples.import_leads_into_salesforce.client.SalesforcePartnerClient;
import com.mulesoft.examples.import_leads_into_salesforce.trigger.LeadsFileTrigger;

/**
 * Port of the original example's {@code ImportLeadsToSfdcIT} functional test, keeping its input and its two
 * assertions: after {@code leads.csv} is imported, the Lead with e-mail {@code faucibus@egetmetus.org} has
 * {@code FirstName} {@code Ishmael}, and the Lead with e-mail {@code sem.egestas@mollis.org} has {@code LastName}
 * {@code Burks}.
 *
 * <p>The original ran {@code import-leads-into-salesforce.xml} in an embedded Mule runtime against a live
 * organization. Here the whole application context runs without a web server, and a Mockito
 * {@link SalesforcePartnerClient} stands in for Salesforce: it reports neither address as an existing Lead, so
 * {@code LeadExistsStep} sets {@code exists} to false for both records, {@code LeadInsertStep} accepts both, and the
 * Leads the original read back from the organization are the records its single commit group of at most 200 handed to
 * the client's {@code create} of type {@code Lead}. Everything between the polled file and that call (the trigger, the
 * SC-5 file poller, the CSV reader, the {@code Transform CSV to Maps} mapper, the SC-7 batch runner and the batch job)
 * is the production code, so no credential is needed and nothing leaves the JVM. The same assertions run against a
 * live sandbox, with the query and the delete, in the failsafe {@code ImportLeadsToSfdcIT}.
 *
 * <p>Parts of the original that are not ported, because the mocked client and the on-demand poll make them
 * unnecessary:
 * <ul>
 *   <li>{@code client.dispatch} of the file to {@code file://./src/main/resources/input} with {@code outputPattern}
 *       {@code leads.csv} becomes the file placed under that name in this class's input directory and
 *       {@link LeadsFileTrigger#pollOnce()}; the fixed 8000 ms sleep that waited for the 10000 ms poll and the batch
 *       job becomes bounded waits on the effects;</li>
 *   <li>loading {@code mule.test.properties} into the {@code sfdc.user}, {@code sfdc.password} and
 *       {@code sfdc.securityToken} system properties: the mocked client never logs in;</li>
 *   <li>the test flows {@code selectLeadFromSalesforce} (query by e-mail) and {@code deleteLeadFromSalesforce} of
 *       {@code testflows/test-flows.xml}: nothing is created in Salesforce, so the Leads are looked up by e-mail among
 *       the captured records and nothing needs deleting.</li>
 * </ul>
 * The {@code Find Lead} query of each record and the file endpoint's {@code moveToDirectory} disposition are checked
 * as well.
 */
@SpringBootTest(classes = ImportLeadsIntoSalesforceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
public class ImportLeadsToSfdcTest {

    private static final String EMAIL1 = "faucibus@egetmetus.org";
    private static final String EMAIL2 = "sem.egestas@mollis.org";

    /** Classpath resource the original dispatched, and the name its {@code outputPattern} gave the file. */
    private static final String FILE_NAME = "leads.csv";

    /** The {@code type} of the flow's {@code sfdc:create} (line 34). */
    private static final String LEAD_TYPE = "Lead";

    private static final Duration AWAIT_LIMIT = Duration.ofSeconds(10);

    @TempDir
    static Path workDir;

    @MockBean
    SalesforcePartnerClient salesforcePartnerClient;

    @Autowired
    LeadsFileTrigger leadsFileTrigger;

    /**
     * Points the file endpoint at this class's directories; these registrations outrank the surefire system
     * properties of {@code pom.xml}. The suppliers are read while the context starts, after JUnit has injected
     * {@link #workDir}. The polling frequency of one hour leaves the scheduled startup poll as the only scheduled one.
     */
    @DynamicPropertySource
    static void leadDirectories(DynamicPropertyRegistry registry) {
        registry.add("leads.input-dir", () -> inputDir().toString());
        registry.add("leads.move-to-dir", () -> outputDir().toString());
        registry.add("leads.polling-frequency", () -> "3600000");
    }

    @Test
    void testImport() throws Exception {
        // Neither address is a Lead yet, as in the original, whose teardown deleted the Leads it created.
        when(salesforcePartnerClient.query(anyString())).thenReturn(List.of());
        // The commit ignores the save results, as the original's sfdc:create did.
        when(salesforcePartnerClient.create(eq(LEAD_TYPE), anyList())).thenReturn(List.of());

        byte[] csv = new ClassPathResource(FILE_NAME).getContentAsByteArray();
        Path inputDir = Files.createDirectories(inputDir());
        // Staged beside the input directory and moved in atomically: the scheduled startup poll runs on its own
        // thread and must never list a half-written file.
        Path staged = Files.write(Files.createDirectories(workDir.resolve("staging")).resolve(FILE_NAME), csv);
        Files.move(staged, inputDir.resolve(FILE_NAME), StandardCopyOption.ATOMIC_MOVE);

        // Not asserted: the scheduled startup poll may take the file first, and the poller's in-process mark keeps
        // either run from importing it twice.
        leadsFileTrigger.pollOnce();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> created = ArgumentCaptor.forClass(List.class);
        verify(salesforcePartnerClient, timeout(AWAIT_LIMIT.toMillis()).times(1))
                .create(eq(LEAD_TYPE), created.capture());
        List<Map<String, Object>> leads = created.getValue();
        assertEquals(2, leads.size(), () -> "created records: " + leads);

        Map<Object, Map<String, Object>> byEmail = new HashMap<>();
        leads.forEach(lead -> byEmail.put(lead.get("Email"), lead));

        Map<String, Object> lead = byEmail.get(EMAIL1);
        assertNotNull(lead, () -> "no Lead created for " + EMAIL1 + " in " + leads);
        assertEquals("Ishmael", lead.get("FirstName"));
        lead = byEmail.get(EMAIL2);
        assertNotNull(lead, () -> "no Lead created for " + EMAIL2 + " in " + leads);
        assertEquals("Burks", lead.get("LastName"));

        // Every field the script maps, of both records in file order; Birthday is not mapped.
        assertEquals(List.of(
                Map.of("Company", "Aliquam Tincidunt Nunc LLC", "Email", EMAIL1,
                        "FirstName", "Ishmael", "LastName", "Alexander"),
                Map.of("Company", "Eu Odio PC", "Email", EMAIL2, "FirstName", "Cole", "LastName", "Burks")),
                leads);

        // LeadExistsStep: one Find Lead query per record, by its e-mail address.
        verify(salesforcePartnerClient).query("SELECT Id FROM Lead WHERE Email = '" + EMAIL1 + "'");
        verify(salesforcePartnerClient).query("SELECT Id FROM Lead WHERE Email = '" + EMAIL2 + "'");

        // The endpoint's moveToDirectory: the polled file ends up, unchanged, in the output directory.
        Path moved = outputDir().resolve(FILE_NAME);
        await().atMost(AWAIT_LIMIT).until(() -> Files.isRegularFile(moved) && !hasRegularFiles(inputDir));
        assertArrayEquals(csv, Files.readAllBytes(moved), "The polled file should be moved unchanged");

        // One import of the file and no other Salesforce call: neither poll handed the file over twice.
        verifyNoMoreInteractions(salesforcePartnerClient);
    }

    private static Path inputDir() {
        return workDir.resolve("input");
    }

    private static Path outputDir() {
        return workDir.resolve("output");
    }

    private static boolean hasRegularFiles(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.anyMatch(Files::isRegularFile);
        }
    }
}
