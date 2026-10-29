/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.dataweave_with_flowreflookup.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mulesoft.examples.dataweave_with_flowreflookup.client.SalesforcePartnerClient;
import com.mulesoft.examples.dataweave_with_flowreflookup.client.UpstreamSystem;
import com.mulesoft.examples.dataweave_with_flowreflookup.exception.UpstreamUnavailableException;
import com.mulesoft.examples.dataweave_with_flowreflookup.mapper.AccountMapper;
import com.mulesoft.examples.dataweave_with_flowreflookup.mapper.DwCsvReader;
import com.sforce.soap.partner.SaveResult;
import com.sforce.ws.ConnectionException;

/**
 * Unit test of {@link AccountImportService}, the body of the Mule flow {@code CreateNewSalesforceAccountFlow} of
 * {@code dataweave-with-flowref.xml} (lines 6-24) after its file inbound endpoint, with the real {@link DwCsvReader},
 * {@link AccountMapper} and {@link SalesRegionLookupService} and a Mockito-mocked {@link SalesforcePartnerClient}
 * under strict stubs; no Spring context, network or credentials (AAP 0.5.5).
 *
 * <p>The original {@code DataWeaveWithFlowRefIT} checked one field of the created Account, {@code Region__c}
 * {@code South East} for {@code Universal Exports}; this test fixes the complete records the service hands to the
 * single {@code create} of type {@code Account}, and the unchanged, unretried propagation of every client and reader
 * failure (AAP 0.4.8).
 */
@ExtendWith(MockitoExtension.class)
class AccountImportServiceTest {

    /** Size of {@code companies.csv}, a byte copy of the original: LF line ends and no trailing LF. */
    private static final int COMPANIES_CSV_LENGTH = 196;

    @Mock
    private SalesforcePartnerClient client;

    private AccountImportService service;

    @BeforeEach
    void setUp() {
        service = new AccountImportService(new DwCsvReader(), new AccountMapper(new SalesRegionLookupService()),
                client);
    }

    @Test
    void importsCompaniesCsvAsOneAccountCreate() throws Exception {
        List<SaveResult> sentinel = List.of(new SaveResult(), new SaveResult());
        when(client.create(anyString(), anyList())).thenReturn(sentinel);

        List<SaveResult> result = service.importAccounts(classpathCompaniesCsv());

        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<Map<String, Object>>> records = recordsCaptor();
        verify(client, times(1)).create(type.capture(), records.capture());
        verifyNoMoreInteractions(client);

        assertThat(type.getValue()).isEqualTo("Account");
        // Entries are compared in order and as Strings, so the postal codes are not coerced. The lower-case "fl" is
        // upper-cased into BillingState and resolves to South East, the region the original IT asserted.
        assertThat(records.getValue()).hasSize(2);
        assertThat(records.getValue().get(0)).containsExactly(
                entry("Name", "Universal Exports"),
                entry("BillingStreet", "55 Main St"),
                entry("BillingCity", "Miami"),
                entry("BillingState", "FL"),
                entry("BillingPostalCode", "33126"),
                entry("Region__c", "South East"));
        assertThat(records.getValue().get(1)).containsExactly(
                entry("Name", "Best Widgets"),
                entry("BillingStreet", "1966 Latrobe Rd"),
                entry("BillingCity", "El Dorado Hills"),
                entry("BillingState", "CA"),
                entry("BillingPostalCode", "95762"),
                entry("Region__c", "West Coast"));
        // The script names six fields only; the CSV's last column never reaches Salesforce.
        assertThat(records.getValue())
                .allSatisfy(record -> assertThat(record).doesNotContainKey("has_given_contact_permission"));
        assertSame(sentinel, result);
    }

    @Test
    void headerOnlyCsvStillCallsCreateOnceWithNoRecords() throws Exception {
        List<SaveResult> sentinel = new ArrayList<>();
        when(client.create(anyString(), anyList())).thenReturn(sentinel);

        List<SaveResult> result = service.importAccounts(
                "company_name,company_address,company_city,company_state,company_zip,has_given_contact_permission\n"
                        .getBytes(StandardCharsets.UTF_8));

        // Mirrors the connector behaviour fixed by ContactImportServiceTest: the original sfdc:create received the
        // empty #[payload] list. What Salesforce or the connector then did with it is a Tier 2A verification point
        // (AAP 0.8.4), so the service must not skip the call.
        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<Map<String, Object>>> records = recordsCaptor();
        verify(client, times(1)).create(type.capture(), records.capture());
        verifyNoMoreInteractions(client);

        assertThat(type.getValue()).isEqualTo("Account");
        assertThat(records.getValue()).isEmpty();
        assertSame(sentinel, result);
    }

    // The service neither retries nor wraps (AAP 0.4.8): failures are classified only inside the client's SC-9
    // UpstreamCallExecutor, and whatever leaves the client must reach the file poller as the very same instance.
    @ParameterizedTest(name = "{0}")
    @MethodSource("vendorFailures")
    void vendorFailurePropagatesUnchangedWithoutRetry(String description, Exception failure) throws Exception {
        when(client.create(anyString(), anyList())).thenThrow(failure);
        byte[] csv = classpathCompaniesCsv();

        Exception thrown = assertThrows(Exception.class, () -> service.importAccounts(csv));

        assertSame(failure, thrown);
        verify(client, times(1)).create(anyString(), anyList());
        verifyNoMoreInteractions(client);
    }

    static Stream<Arguments> vendorFailures() {
        return Stream.of(
                Arguments.of("upstream unavailable, classified by SC-9, propagates unchanged without retry",
                        new UpstreamUnavailableException(UpstreamSystem.SALESFORCE, "Read timed out",
                                new SocketTimeoutException("Read timed out"))),
                Arguments.of("other vendor failure, the checked WSC ConnectionException, propagates unchanged",
                        new ConnectionException("connection reset")));
    }

    @Test
    void readerFailurePropagatesWithoutCreate() {
        // The DataWeave CSV reader tolerates short and long rows and rejects no input, so a mocked reader stands in for
        // a read failure; the mapper is mocked too, to show that nothing after the read runs.
        DwCsvReader failingReader = mock(DwCsvReader.class);
        AccountMapper mapper = mock(AccountMapper.class);
        IllegalArgumentException failure = new IllegalArgumentException("malformed CSV");
        when(failingReader.read(any(byte[].class))).thenThrow(failure);
        AccountImportService failingService = new AccountImportService(failingReader, mapper, client);
        byte[] csv = "company_name\n\"unterminated".getBytes(StandardCharsets.UTF_8);

        Exception thrown = assertThrows(IllegalArgumentException.class, () -> failingService.importAccounts(csv));

        assertSame(failure, thrown);
        verify(failingReader, times(1)).read(csv);
        verifyNoInteractions(mapper, client);
    }

    private static byte[] classpathCompaniesCsv() throws IOException {
        try (InputStream in = AccountImportServiceTest.class.getResourceAsStream("/companies.csv")) {
            assertThat(in).as("classpath resource /companies.csv").isNotNull();
            byte[] csv = in.readAllBytes();
            assertThat(csv).as("byte length of /companies.csv").hasSize(COMPANIES_CSV_LENGTH);
            return csv;
        }
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<Map<String, Object>>> recordsCaptor() {
        return ArgumentCaptor.forClass((Class<List<Map<String, Object>>>) (Class<?>) List.class);
    }
}
