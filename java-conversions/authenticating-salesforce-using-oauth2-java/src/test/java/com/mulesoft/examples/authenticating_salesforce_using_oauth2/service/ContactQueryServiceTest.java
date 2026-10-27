/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.authenticating_salesforce_using_oauth2.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.mulesoft.examples.authenticating_salesforce_using_oauth2.client.SalesforcePartnerClient;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.client.UpstreamSystem;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.exception.UpstreamUnavailableException;
import com.sforce.ws.ConnectionException;

/**
 * Unit test of {@link ContactQueryService}, lines 13-17 of {@code salesforce-oauth.xml} once the user has authorized,
 * against a Mockito-mocked {@link SalesforcePartnerClient}; no Spring context, network or credentials.
 *
 * <p>It fixes the flow contract: the original SOQL sent once, one INFO {@code contact: <record>} line per record in
 * the order Salesforce returned them, the {@code Salesforce query returned N contacts.} text, and the unchanged
 * propagation of client failures. The records are {@code HashMap}s carrying the sObject {@code type}, as
 * {@code SalesforcePartnerClient.toMap} builds them, and every expected log text is taken from the record's own
 * {@code toString()} so the assertions never depend on {@code HashMap} iteration order.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ContactQueryServiceTest {

    private static final String CONTACT_LOG_PREFIX = "contact: ";

    @Mock
    private SalesforcePartnerClient partnerClient;

    private ContactQueryService service;

    @BeforeEach
    void setUp() {
        service = new ContactQueryService(partnerClient);
    }

    @Test
    void queryRunsTheOriginalSoqlExactlyOnce() throws Exception {
        when(partnerClient.query(anyString())).thenReturn(List.of());

        service.queryContacts();

        ArgumentCaptor<String> soql = ArgumentCaptor.forClass(String.class);
        verify(partnerClient, times(1)).query(soql.capture());
        assertThat(soql.getValue())
                .isEqualTo("SELECT id,lastname,lastmodifieddate from contact limit 10")
                .isEqualTo(ContactQueryService.CONTACT_QUERY);
        verifyNoMoreInteractions(partnerClient);
    }

    @Test
    void threeContactsAreCountedAndEachIsLoggedAtInfoInRecordOrder(CapturedOutput output) throws Exception {
        List<Map<String, Object>> records = List.of(
                contact("003000000000001", "Doe", "2024-01-15T10:30:00.000Z"),
                contact("003000000000002", "Smith", "2024-02-20T08:05:12.000Z"),
                contact("003000000000003", "Burks", "2024-03-25T17:45:59.000Z"));
        when(partnerClient.query(anyString())).thenReturn(records);

        String text = service.queryContacts();

        assertThat(text).isEqualTo("Salesforce query returned 3 contacts.");
        String out = output.getOut();
        List<String> contactLines = out.lines().filter(line -> line.contains(CONTACT_LOG_PREFIX)).toList();
        assertThat(contactLines).hasSize(3);
        int previousIndex = -1;
        for (int i = 0; i < records.size(); i++) {
            String expected = CONTACT_LOG_PREFIX + records.get(i).toString();
            assertThat(contactLines.get(i)).contains(expected).contains("INFO");
            int index = out.indexOf(expected);
            assertThat(index).isGreaterThan(previousIndex);
            previousIndex = index;
        }
    }

    @Test
    void noContactsGiveACountOfZeroAndNoContactLine(CapturedOutput output) throws Exception {
        when(partnerClient.query(anyString())).thenReturn(List.of());

        String text = service.queryContacts();

        assertThat(text).isEqualTo("Salesforce query returned 0 contacts.");
        assertThat(output.getOut()).doesNotContain(CONTACT_LOG_PREFIX);
    }

    @Test
    void upstreamUnavailableReachesTheCallerAsTheSameInstance(CapturedOutput output) throws Exception {
        UpstreamUnavailableException failure = new UpstreamUnavailableException(UpstreamSystem.SALESFORCE,
                "Read timed out", new SocketTimeoutException("Read timed out"));
        when(partnerClient.query(anyString())).thenThrow(failure);

        Exception thrown = assertThrows(Exception.class, service::queryContacts);

        assertSame(failure, thrown);
        assertThat(output.getOut()).doesNotContain(CONTACT_LOG_PREFIX);
    }

    @Test
    void partnerApiFailureReachesTheCallerAsTheSameInstance() throws Exception {
        ConnectionException failure = new ConnectionException("boom");
        when(partnerClient.query(anyString())).thenThrow(failure);

        Exception thrown = assertThrows(Exception.class, service::queryContacts);

        assertSame(failure, thrown);
    }

    /** A Contact record as {@code SalesforcePartnerClient.toMap} builds it: a {@code HashMap} with its sObject type. */
    private static Map<String, Object> contact(String id, String lastName, String lastModifiedDate) {
        Map<String, Object> record = new HashMap<>();
        record.put("type", "Contact");
        record.put("Id", id);
        record.put("LastName", lastName);
        record.put("LastModifiedDate", lastModifiedDate);
        return record;
    }
}
