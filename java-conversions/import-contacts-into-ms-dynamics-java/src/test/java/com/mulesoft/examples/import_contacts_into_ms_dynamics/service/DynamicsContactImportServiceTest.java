/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_ms_dynamics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.mulesoft.examples.import_contacts_into_ms_dynamics.client.DynamicsWebApiClient;
import com.mulesoft.examples.import_contacts_into_ms_dynamics.client.UpstreamSystem;
import com.mulesoft.examples.import_contacts_into_ms_dynamics.exception.UpstreamUnavailableException;
import com.mulesoft.examples.import_contacts_into_ms_dynamics.mapper.DwCsvReader;
import com.mulesoft.examples.import_contacts_into_ms_dynamics.mapper.DynamicsContactMapper;

/**
 * Unit test of {@link DynamicsContactImportService}, the body of the Mule flow {@code crm-create-account} of
 * {@code import-contacts-into-ms-dynamics.xml} after its file inbound endpoint, with the real {@link DwCsvReader} and
 * {@link DynamicsContactMapper} and a Mockito-mocked {@link DynamicsWebApiClient}; no Spring context, network or
 * credentials.
 *
 * <p>The original {@code ImportContactsIntoMSDynamicsIT} checked one field of each created contact ({@code firstname}
 * {@code John}, {@code lastname} {@code Doe}); this test fixes the complete attribute set of every
 * {@code dynamicscrm:create logicalName="contact"} the {@code For Each} scope made, the file order of those creates,
 * and the unchanged propagation of every client failure.
 */
@ExtendWith(MockitoExtension.class)
class DynamicsContactImportServiceTest {

    /** Byte length of the original example's {@code contacts.csv}, so a modified copy fails loudly. */
    private static final int ORIGINAL_CONTACTS_CSV_LENGTH = 114;

    private static final List<String> ATTRIBUTE_ORDER =
            List.of("firstname", "lastname", "emailaddress1", "telephone1");

    @Mock
    DynamicsWebApiClient client;

    @Captor
    ArgumentCaptor<Map<String, Object>> attributesCaptor;

    private DynamicsContactImportService service;

    @BeforeEach
    void setUp() {
        service = new DynamicsContactImportService(new DwCsvReader(), new DynamicsContactMapper(), client);
    }

    @Test
    void importsEachCsvRecordAsOneContactInFileOrder() throws Exception {
        int created = service.importContacts(classpathContactsCsv());

        assertThat(created).isEqualTo(2);
        verify(client, times(2)).createContact(attributesCaptor.capture());
        List<Map<String, Object>> sent = attributesCaptor.getAllValues();
        assertThat(sent).hasSize(2);
        // Keys follow the declaration order of the dynamicscrm:attribute elements of the original Create Contact.
        assertThat(new ArrayList<>(sent.get(0).keySet())).isEqualTo(ATTRIBUTE_ORDER);
        assertThat(new ArrayList<>(sent.get(1).keySet())).isEqualTo(ATTRIBUTE_ORDER);
        assertThat(sent.get(0)).containsExactly(
                entry("firstname", "John"),
                entry("lastname", "Doe"),
                entry("emailaddress1", "john.doe@texasComp.com"),
                entry("telephone1", "096548763"));
        assertThat(sent.get(1)).containsExactly(
                entry("firstname", "Jane"),
                entry("lastname", "Doe"),
                entry("emailaddress1", "jane.doe@texasComp.com"),
                entry("telephone1", "091558780"));
        // DataWeave 1.0 CSV values are strings: the phone is never coerced, so its leading zero survives.
        assertThat(sent.get(0).get("telephone1")).isInstanceOf(String.class);
        assertThat(sent.get(1).get("telephone1")).isInstanceOf(String.class);

        InOrder inOrder = inOrder(client);
        inOrder.verify(client).createContact(argThat(m -> "john.doe@texasComp.com".equals(m.get("emailaddress1"))));
        inOrder.verify(client).createContact(argThat(m -> "jane.doe@texasComp.com".equals(m.get("emailaddress1"))));
        verifyNoMoreInteractions(client);
    }

    @Test
    void headerOnlyCsvCreatesNothing() throws Exception {
        int created = service.importContacts("firstname,surname,phone,email\n".getBytes(StandardCharsets.UTF_8));

        // A foreach over an empty collection runs no Create Contact.
        assertThat(created).isZero();
        verifyNoInteractions(client);
    }

    // Timeouts and connectivity failures are never retried, because the contact create may already have reached
    // Dataverse; the SC-9 exception must leave the service as the same instance and end the loop.
    @Test
    void unavailableUpstreamPropagatesWithoutRetry() throws Exception {
        UpstreamUnavailableException failure = new UpstreamUnavailableException(
                UpstreamSystem.MICROSOFT_DYNAMICS_365, "Connection refused", new ConnectException("Connection refused"));
        when(client.createContact(any())).thenThrow(failure);
        byte[] csv = classpathContactsCsv();

        assertThatThrownBy(() -> service.importContacts(csv)).isSameAs(failure);

        verify(client, times(1)).createContact(attributesCaptor.capture());
        assertThat(attributesCaptor.getValue()).containsEntry("firstname", "John");
        verifyNoMoreInteractions(client);
    }

    // An unclassified vendor failure is rethrown unchanged, so the file poller's handled-failure branch sees the
    // vendor type itself, logs it at ERROR and still disposes of the file.
    @Test
    void vendorErrorPropagatesAsSameInstance() throws Exception {
        WebClientResponseException badRequest =
                WebClientResponseException.create(400, "Bad Request", HttpHeaders.EMPTY, new byte[0], null);
        when(client.createContact(any())).thenThrow(badRequest);
        byte[] csv = classpathContactsCsv();

        assertThatThrownBy(() -> service.importContacts(csv))
                .isSameAs(badRequest)
                .isInstanceOf(WebClientResponseException.class);

        verify(client, times(1)).createContact(any());
        verifyNoMoreInteractions(client);
    }

    private byte[] classpathContactsCsv() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/contacts.csv")) {
            assertThat(in).as("classpath resource /contacts.csv").isNotNull();
            byte[] content = in.readAllBytes();
            assertThat(content).as("original contacts.csv").hasSize(ORIGINAL_CONTACTS_CSV_LENGTH);
            return content;
        }
    }
}
