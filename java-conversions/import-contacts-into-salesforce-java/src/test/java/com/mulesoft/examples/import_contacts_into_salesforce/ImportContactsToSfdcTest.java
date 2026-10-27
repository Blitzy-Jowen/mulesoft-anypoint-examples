/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_salesforce;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

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

import com.mulesoft.examples.import_contacts_into_salesforce.client.SalesforcePartnerClient;
import com.mulesoft.examples.import_contacts_into_salesforce.trigger.ContactsFileTrigger;

/**
 * Port of the original example's {@code ImportContactsToSfdcIT} functional test, keeping its input and its two
 * assertions: after {@code contacts.csv} is imported, the Contact with e-mail {@code john.doe@texasComp.com} has
 * {@code FirstName} {@code John}, and the Contact with e-mail {@code jane.doe@texasComp.com} has {@code LastName}
 * {@code Doe}.
 *
 * <p>The original ran {@code contacts-to-SFDC.xml} in an embedded Mule runtime against a live organization. Here the
 * whole application context runs without a web server, and a Mockito {@link SalesforcePartnerClient} stands in for
 * Salesforce: the Contacts the original read back from the organization are the records the flow handed to the
 * client's single {@code create} of type {@code Contact}. Everything between the polled file and that call (the
 * trigger, the SC-5 file poller, the CSV reader, the {@code Map CSV to Contacts} mapper and the import service) is the
 * production code, so no credential is needed and nothing leaves the JVM. The same assertions run against a live
 * sandbox, with the query and the delete, in the failsafe {@code ImportContactsToSfdcIT}.
 *
 * <p>Parts of the original that are not ported, because the mocked client and the on-demand poll make them
 * unnecessary:
 * <ul>
 *   <li>{@code client.dispatch} of the file to {@code file://./src/main/resources/input} becomes the file placed in
 *       this class's input directory and {@link ContactsFileTrigger#pollOnce()}; the fixed 8000 ms sleep that waited
 *       for the 10000 ms poll becomes bounded waits on the effects;</li>
 *   <li>loading {@code mule.test.properties} into the {@code sfdc.user}, {@code sfdc.password} and
 *       {@code sfdc.securityToken} system properties: the mocked client never logs in;</li>
 *   <li>{@code getStartUpProperties}, which pointed the Mule application home at {@code ./mappings}: no Mule runtime
 *       runs;</li>
 *   <li>the test flows {@code selectContactFromSalesforce} ({@code sfdc:query-single} by e-mail) and
 *       {@code deleteContactFromSalesforce} of {@code testflows/test-flows.xml}: nothing is created in Salesforce,
 *       so the Contacts are looked up by e-mail among the captured records and nothing needs deleting.</li>
 * </ul>
 * The file endpoint's {@code moveToDirectory} disposition is checked as well.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ImportContactsToSfdcTest {

    private static final String EMAIL1 = "john.doe@texasComp.com";
    private static final String EMAIL2 = "jane.doe@texasComp.com";

    /** Classpath resource the original dispatched, and the name its {@code outputPattern} gave the file. */
    private static final String FILE_NAME = "contacts.csv";

    /** The {@code type} of the flow's {@code sfdc:create}. */
    private static final String CONTACT_TYPE = "Contact";

    private static final Duration AWAIT_LIMIT = Duration.ofSeconds(10);

    @TempDir
    static Path workDir;

    @MockBean
    SalesforcePartnerClient salesforce;

    @Autowired
    ContactsFileTrigger trigger;

    /**
     * Points the file endpoint at this class's directories. The suppliers are read while the context starts, after
     * JUnit has injected {@link #workDir}. The polling frequency of one hour leaves the scheduled startup poll as the
     * only scheduled one.
     */
    @DynamicPropertySource
    static void contactDirectories(DynamicPropertyRegistry registry) {
        registry.add("contacts.input-dir", () -> inputDir().toString());
        registry.add("contacts.move-to-dir", () -> outputDir().toString());
        registry.add("contacts.polling-frequency", () -> "3600000");
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

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> created = ArgumentCaptor.forClass(List.class);
        verify(salesforce, timeout(AWAIT_LIMIT.toMillis()).times(1)).create(eq(CONTACT_TYPE), created.capture());
        List<Map<String, Object>> contacts = created.getValue();
        assertEquals(2, contacts.size(), () -> "created records: " + contacts);

        Map<Object, Map<String, Object>> byEmail = new HashMap<>();
        contacts.forEach(contact -> byEmail.put(contact.get("Email"), contact));

        Map<String, Object> contact = byEmail.get(EMAIL1);
        assertNotNull(contact, () -> "no Contact created for " + EMAIL1 + " in " + contacts);
        assertEquals("John", contact.get("FirstName"));
        contact = byEmail.get(EMAIL2);
        assertNotNull(contact, () -> "no Contact created for " + EMAIL2 + " in " + contacts);
        assertEquals("Doe", contact.get("LastName"));

        // Every field of both records, in file order; the phone numbers stay strings with their leading zero.
        assertEquals(List.of(
                Map.of("FirstName", "John", "LastName", "Doe", "Email", EMAIL1, "Phone", "096548763"),
                Map.of("FirstName", "Jane", "LastName", "Doe", "Email", EMAIL2, "Phone", "091558780")),
                contacts);

        // The flow's moveToDirectory: the polled file ends up, unchanged, in the output directory.
        Path moved = outputDir().resolve(FILE_NAME);
        await().atMost(AWAIT_LIMIT).until(() -> Files.isRegularFile(moved) && !hasRegularFiles(inputDir));
        assertArrayEquals(csv, Files.readAllBytes(moved), "The polled file should be moved unchanged");

        // One import of the file and no other Salesforce call: neither poll handed the file over twice.
        verifyNoMoreInteractions(salesforce);
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
