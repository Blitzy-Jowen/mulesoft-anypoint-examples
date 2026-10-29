/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_salesforce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.mulesoft.examples.import_contacts_into_salesforce.client.SalesforcePartnerClient;
import com.mulesoft.examples.import_contacts_into_salesforce.client.UpstreamSystem;
import com.mulesoft.examples.import_contacts_into_salesforce.exception.UpstreamUnavailableException;
import com.mulesoft.examples.import_contacts_into_salesforce.mapper.ContactMapper;
import com.mulesoft.examples.import_contacts_into_salesforce.mapper.DwCsvReader;
import com.sforce.soap.partner.SaveResult;
import com.sforce.ws.ConnectionException;

/**
 * Unit test of {@link ContactImportService}, the body of the Mule flow {@code Contacts_to_SFDC} of
 * {@code contacts-to-SFDC.xml} after its file inbound endpoint, with the real {@link DwCsvReader} and
 * {@link ContactMapper} and a Mockito-mocked {@link SalesforcePartnerClient}; no Spring context, network or
 * credentials.
 *
 * <p>The original {@code ImportContactsToSfdcIT} checked one field of each created Contact ({@code FirstName}
 * {@code John}, {@code LastName} {@code Doe}); this test fixes the complete records the service hands to the single
 * {@code create} of type {@code Contact}, and the unchanged propagation of every client failure.
 */
class ContactImportServiceTest {

    private final SalesforcePartnerClient client = Mockito.mock(SalesforcePartnerClient.class);

    private ContactImportService service;

    @BeforeEach
    void setUp() {
        service = new ContactImportService(new DwCsvReader(), new ContactMapper(), client);
    }

    @Test
    void importsEveryContactOfTheClasspathFileInOneCreateCall() throws Exception {
        List<SaveResult> sentinel = List.of(new SaveResult(), new SaveResult());
        when(client.create(anyString(), anyList())).thenReturn(sentinel);

        List<SaveResult> result = service.importContacts(classpathContactsCsv());

        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<Map<String, Object>>> records = recordsCaptor();
        verify(client, times(1)).create(type.capture(), records.capture());
        verifyNoMoreInteractions(client);

        assertThat(type.getValue()).isEqualTo("Contact");
        // Entries are compared in order and as Strings, so Phone keeps its leading zero and nothing is coerced.
        assertThat(records.getValue()).hasSize(2);
        assertThat(records.getValue().get(0)).containsExactly(
                entry("FirstName", "John"),
                entry("LastName", "Doe"),
                entry("Email", "john.doe@texasComp.com"),
                entry("Phone", "096548763"));
        assertThat(records.getValue().get(1)).containsExactly(
                entry("FirstName", "Jane"),
                entry("LastName", "Doe"),
                entry("Email", "jane.doe@texasComp.com"),
                entry("Phone", "091558780"));
        assertSame(sentinel, result);
    }

    @Test
    void headerOnlyFileStillCallsCreateOnceWithNoRecords() throws Exception {
        List<SaveResult> sentinel = new ArrayList<>();
        when(client.create(anyString(), anyList())).thenReturn(sentinel);

        List<SaveResult> result = service.importContacts(
                "firstname,surname,phone,email\n".getBytes(StandardCharsets.UTF_8));

        // Mirrors the connector: the original sfdc:create received the empty #[payload] list. What Salesforce or the
        // connector then did with it is a Tier 2A verification point (AAP 0.8.4), so the service must not skip it.
        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<Map<String, Object>>> records = recordsCaptor();
        verify(client, times(1)).create(type.capture(), records.capture());
        verifyNoMoreInteractions(client);

        assertThat(type.getValue()).isEqualTo("Contact");
        assertThat(records.getValue()).isEmpty();
        assertSame(sentinel, result);
    }

    // The service neither retries nor wraps (AAP 0.4.8): failures are classified only inside the client's SC-9
    // UpstreamCallExecutor, and whatever leaves the client must reach the file poller as the very same instance.
    @ParameterizedTest(name = "{0}")
    @MethodSource("vendorFailures")
    void vendorFailurePropagatesUnchanged(String description, Exception failure) throws Exception {
        when(client.create(anyString(), anyList())).thenThrow(failure);
        byte[] csv = classpathContactsCsv();

        Exception thrown = assertThrows(Exception.class, () -> service.importContacts(csv));

        assertSame(failure, thrown);
        verify(client, times(1)).create(anyString(), anyList());
        verifyNoMoreInteractions(client);
    }

    static Stream<Arguments> vendorFailures() {
        return Stream.of(
                Arguments.of("upstream unavailable, classified by SC-9",
                        new UpstreamUnavailableException(UpstreamSystem.SALESFORCE, "Read timed out",
                                new SocketTimeoutException("Read timed out"))),
                Arguments.of("other vendor failure, the checked WSC exception",
                        new ConnectionException("INVALID_FIELD: No such column")));
    }

    private static byte[] classpathContactsCsv() throws IOException {
        try (InputStream in = ContactImportServiceTest.class.getResourceAsStream("/contacts.csv")) {
            if (in == null) {
                throw new IllegalStateException("Classpath resource /contacts.csv is missing");
            }
            return in.readAllBytes();
        }
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<Map<String, Object>>> recordsCaptor() {
        return ArgumentCaptor.forClass((Class<List<Map<String, Object>>>) (Class<?>) List.class);
    }
}
