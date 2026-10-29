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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.mulesoft.examples.dataweave_with_flowreflookup.client.SalesforcePartnerClient;
import com.mulesoft.examples.dataweave_with_flowreflookup.trigger.CompaniesFileTrigger;

/**
 * Port of the original example's {@code DataWeaveWithFlowRefIT} functional test, keeping its input and its
 * assertions: after {@code companies.csv} is imported, the Account named {@code Universal Exports} has
 * {@code Region__c} {@code South East}, and an Account named {@code Best Widgets} exists (the original fetched it
 * without asserting any field, so only its presence is checked here).
 *
 * <p>The original ran {@code dataweave-with-flowref.xml} in an embedded Mule runtime against a live organization.
 * Here the whole application context runs without a web server, and a Mockito {@link SalesforcePartnerClient} stands
 * in for Salesforce: the Accounts the original read back from the organization are the records the flow handed to
 * the client's single {@code create} of type {@code Account}. Everything between the polled file and that call (the
 * trigger, the SC-5 file poller, the CSV reader, the {@code CSV to Maps} mapper with its {@code LookUpSalesRegionFlow}
 * region lookup, and the import service) is the production code, so no credential is needed and nothing leaves the
 * JVM. The same assertions run against a live sandbox, with the query and the delete, in the failsafe
 * {@code DataWeaveWithFlowRefIT}.
 *
 * <p>Parts of the original that are not ported, because the mocked client and the on-demand poll make them
 * unnecessary:
 * <ul>
 *   <li>{@code client.dispatch} of the file to {@code file://./src/main/resources/input} becomes the file placed in
 *       this class's input directory and {@link CompaniesFileTrigger#pollOnce()}; the fixed 15000 ms sleep that
 *       waited for the 10000 ms poll becomes bounded waits on the effects;</li>
 *   <li>loading {@code mule.test.properties} into the {@code sfdc.user}, {@code sfdc.password} and
 *       {@code sfdc.securityToken} system properties: the mocked client never logs in;</li>
 *   <li>{@code getStartUpProperties}, which pointed the Mule application home at {@code ./mappings}: no Mule runtime
 *       runs;</li>
 *   <li>the test flows {@code selectAccountFromSalesforce} ({@code sfdc:query-single} by name) and
 *       {@code deleteAccountFromSalesforce} of {@code testflows/test-flows.xml}: nothing is created in Salesforce,
 *       so the Accounts are looked up by name among the captured records and nothing needs deleting.</li>
 * </ul>
 * The file endpoint's {@code moveToDirectory} disposition is checked as well.
 */
@SpringBootTest(classes = DataweaveWithFlowreflookupApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DataWeaveWithFlowRefTest {

    private static final String COMPANY_NAME = "Universal Exports";
    private static final String COMPANY_NAME1 = "Best Widgets";

    /** Classpath resource the original dispatched, and the name its {@code outputPattern} gave the file. */
    private static final String FILE_NAME = "companies.csv";

    /** The {@code type} of the flow's {@code sfdc:create}. */
    private static final String ACCOUNT_TYPE = "Account";

    private static final Duration AWAIT_LIMIT = Duration.ofSeconds(10);

    @TempDir
    static Path workDir;

    @MockBean
    SalesforcePartnerClient client;

    @Autowired
    CompaniesFileTrigger trigger;

    /**
     * Points the file endpoint at this class's directories; these registrations outrank the surefire system
     * properties of the pom. The suppliers are read while the context starts, after JUnit has injected
     * {@link #workDir}. The polling frequency of one hour leaves the scheduled startup poll as the only scheduled one.
     */
    @DynamicPropertySource
    static void companyDirectories(DynamicPropertyRegistry registry) {
        registry.add("companies.input-dir", () -> inputDir().toString());
        registry.add("companies.move-to-dir", () -> outputDir().toString());
        registry.add("companies.polling-frequency", () -> "3600000");
    }

    @Test
    void importAccounts() throws Exception {
        byte[] csv = new ClassPathResource(FILE_NAME).getContentAsByteArray();
        Path inputDir = Files.createDirectories(inputDir());
        Path input = inputDir.resolve(FILE_NAME);
        // Staged beside the input directory and moved in atomically: the scheduled startup poll runs on its own
        // thread and must never list a half-written file.
        Path staged = Files.write(Files.createDirectories(workDir.resolve("staging")).resolve(FILE_NAME), csv);
        try {
            Files.move(staged, input, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(staged, input, StandardCopyOption.REPLACE_EXISTING);
        }

        // Not asserted: the scheduled startup poll may take the file first, and the poller's in-process mark keeps
        // either run from importing it twice.
        trigger.pollOnce();

        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> created = ArgumentCaptor.forClass(List.class);
        verify(client, timeout(AWAIT_LIMIT.toMillis()).times(1)).create(type.capture(), created.capture());
        assertEquals(ACCOUNT_TYPE, type.getValue());
        List<Map<String, Object>> accounts = created.getValue();

        // One create carrying every CSV record, in file order, as sfdc:create sent the whole mapped payload.
        List<Object> names = accounts.stream().map(record -> record.get("Name")).toList();
        assertEquals(List.of(COMPANY_NAME, COMPANY_NAME1), names, () -> "created records: " + accounts);

        Map<Object, Map<String, Object>> byName = new HashMap<>();
        accounts.forEach(account -> byName.put(account.get("Name"), account));

        Map<String, Object> account = byName.get(COMPANY_NAME);
        assertNotNull(account, () -> "no Account created for " + COMPANY_NAME + " in " + accounts);
        assertEquals("South East", account.get("Region__c"));
        assertNotNull(byName.get(COMPANY_NAME1), () -> "no Account created for " + COMPANY_NAME1 + " in " + accounts);

        // The flow's moveToDirectory: the polled file ends up, unchanged and under its own name, in the output
        // directory.
        Path moved = outputDir().resolve(FILE_NAME);
        await().atMost(AWAIT_LIMIT).until(() -> Files.isRegularFile(moved) && !Files.exists(input));
        assertArrayEquals(csv, Files.readAllBytes(moved), "The polled file should be moved unchanged");

        // Still one import of the file once it is consumed, and no other Salesforce call: neither poll handed the
        // file over twice, nothing was retried and nothing was deleted.
        verify(client, times(1)).create(eq(ACCOUNT_TYPE), anyList());
        verifyNoMoreInteractions(client);
    }

    private static Path inputDir() {
        return workDir.resolve("input");
    }

    private static Path outputDir() {
        return workDir.resolve("output");
    }
}
