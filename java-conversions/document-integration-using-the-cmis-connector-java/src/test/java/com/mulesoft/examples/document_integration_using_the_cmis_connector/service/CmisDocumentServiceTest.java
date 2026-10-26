/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.document_integration_using_the_cmis_connector.service;

import static com.mulesoft.examples.document_integration_using_the_cmis_connector.service.CmisDocumentService.FOLDER_PATH;
import static com.mulesoft.examples.document_integration_using_the_cmis_connector.service.CmisDocumentService.MIME_TYPE;
import static com.mulesoft.examples.document_integration_using_the_cmis_connector.service.CmisDocumentService.OBJECT_TYPE;
import static com.mulesoft.examples.document_integration_using_the_cmis_connector.service.CmisDocumentService.VERSIONING_STATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import com.mulesoft.examples.document_integration_using_the_cmis_connector.client.CmisAtomPubClient;

/**
 * Unit test of {@link CmisDocumentService}, which replaces the Groovy {@code Load file} component, the
 * {@code cmis:create-document-by-path-from-content} operation and the {@code json:object-to-json-transformer} of
 * flow {@code CMIS} (document-integration-using-the-cmis-connector/src/main/app/cmis-document-integration.xml:9-14),
 * with a mocked {@link CmisAtomPubClient}: no Spring context and no repository.
 *
 * <p>Mockito runs with strict stubs, so a stub the service does not call, or calls with other arguments, fails the
 * test. The {@code JsonProcessingException} branch is not exercised: serializing a single-entry map of strings
 * cannot fail.
 */
@ExtendWith(MockitoExtension.class)
class CmisDocumentServiceTest {

    /** Size in bytes of {@code mulesoft-logo.jpg}, copied unchanged from the original example. */
    private static final int LOGO_SIZE = 29_322;

    private static final String LOGO = "mulesoft-logo.jpg";

    /** The clock value of the fixed-clock cases and the document name and OpenKM object ID it yields. */
    private static final long MILLIS = 123L;

    private static final String NAME = "pic123.jpg";

    private static final String OBJECT_ID = "/okm:root/pic123.jpg";

    private static final Pattern GENERATED_NAME = Pattern.compile("pic(\\d+)\\.jpg");

    @Mock
    private CmisAtomPubClient client;

    @Captor
    private ArgumentCaptor<byte[]> content;

    private byte[] expectedLogo;

    @BeforeEach
    void readExpectedLogo() throws IOException {
        expectedLogo = new ClassPathResource(LOGO).getContentAsByteArray();
        assertEquals(LOGO_SIZE, expectedLogo.length, "size of the classpath " + LOGO);
    }

    @Test
    void createDocumentReturnsCompactIdJson() {
        when(client.createDocumentByPath(eq(FOLDER_PATH), eq(NAME), eq(MIME_TYPE), eq(OBJECT_TYPE),
                eq(VERSIONING_STATE), any(byte[].class))).thenReturn(OBJECT_ID);

        String result = fixedClockService(new ClassPathResource(LOGO)).createDocument();

        assertEquals("{\"id\":\"/okm:root/pic123.jpg\"}", result);
        // The original IT's prefix check (DocumentIntegrationUsingTheCmisConnectorIT.java:48-50).
        assertThat(result).startsWith("{\"id\":\"/okm:root/pic");
    }

    @Test
    void createDocumentCallsClientOnceWithOriginalArguments() {
        when(client.createDocumentByPath(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(byte[].class))).thenReturn(OBJECT_ID);

        fixedClockService(new ClassPathResource(LOGO)).createDocument();

        verify(client, times(1)).createDocumentByPath(eq(FOLDER_PATH), eq(NAME), eq(MIME_TYPE), eq(OBJECT_TYPE),
                eq(VERSIONING_STATE), content.capture());
        verifyNoMoreInteractions(client);
        assertEquals(LOGO_SIZE, content.getValue().length);
        assertArrayEquals(expectedLogo, content.getValue());
        // Attributes of the original operation (cmis-document-integration.xml:13).
        assertEquals("/okm:root/", FOLDER_PATH);
        assertEquals("image/jpg", MIME_TYPE);
        assertEquals("cmis:document", OBJECT_TYPE);
        assertEquals("NONE", VERSIONING_STATE);
    }

    @Test
    void publicConstructorUsesCurrentTimeAndClasspathLogo() {
        when(client.createDocumentByPath(eq(FOLDER_PATH), anyString(), eq(MIME_TYPE), eq(OBJECT_TYPE),
                eq(VERSIONING_STATE), any(byte[].class)))
                .thenAnswer(invocation -> "/okm:root/" + invocation.getArgument(1));
        CmisDocumentService service = new CmisDocumentService(client);
        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);

        long before = System.currentTimeMillis();
        String result = service.createDocument();
        long after = System.currentTimeMillis();

        verify(client).createDocumentByPath(eq(FOLDER_PATH), name.capture(), eq(MIME_TYPE), eq(OBJECT_TYPE),
                eq(VERSIONING_STATE), content.capture());
        Matcher generated = GENERATED_NAME.matcher(name.getValue());
        assertThat(generated.matches()).as("document name %s matches %s", name.getValue(), GENERATED_NAME).isTrue();
        assertThat(Long.parseLong(generated.group(1))).isBetween(before, after);
        assertEquals("{\"id\":\"/okm:root/" + name.getValue() + "\"}", result);
        assertArrayEquals(expectedLogo, content.getValue());
    }

    /** The three failure types the AtomPub client documents: HTTP status, transport, and protocol content. */
    static Stream<RuntimeException> clientFailures() {
        return Stream.of(
                new RestClientResponseException("Unauthorized", HttpStatus.UNAUTHORIZED, "Unauthorized", null, null,
                        null),
                new ResourceAccessException("I/O error on GET request: Connection refused",
                        new ConnectException("Connection refused")),
                new IllegalStateException("CMIS create-document response has no cmis:objectId"));
    }

    @ParameterizedTest
    @MethodSource("clientFailures")
    void clientFailurePropagatesUnchanged(RuntimeException failure) {
        when(client.createDocumentByPath(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(byte[].class))).thenThrow(failure);
        CmisDocumentService service = fixedClockService(new ClassPathResource(LOGO));

        RuntimeException thrown = assertThrows(RuntimeException.class, service::createDocument);

        assertSame(failure, thrown);
        verify(client, times(1)).createDocumentByPath(eq(FOLDER_PATH), eq(NAME), eq(MIME_TYPE), eq(OBJECT_TYPE),
                eq(VERSIONING_STATE), any(byte[].class));
        verifyNoMoreInteractions(client);
    }

    @Test
    void missingLogoRaisesUncheckedIoAndSkipsClient() {
        UncheckedIOException thrown = assertThrows(UncheckedIOException.class,
                () -> fixedClockService(new ClassPathResource("missing.jpg")).createDocument());

        assertThat(thrown.getCause()).isInstanceOf(IOException.class);
        assertEquals("Unable to read document content class path resource [missing.jpg]", thrown.getMessage());
        verifyNoInteractions(client);
    }

    @Test
    void failingLogoStreamRaisesUncheckedIoAndSkipsClient() throws IOException {
        IOException boom = new IOException("boom");
        Resource logo = mock(Resource.class);
        when(logo.getInputStream()).thenThrow(boom);
        when(logo.getDescription()).thenReturn("failing logo");
        CmisDocumentService service = fixedClockService(logo);

        UncheckedIOException thrown = assertThrows(UncheckedIOException.class, service::createDocument);

        assertSame(boom, thrown.getCause());
        assertEquals("Unable to read document content failing logo", thrown.getMessage());
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"client", "currentTimeMillis", "content"})
    void constructorRejectsMissingCollaborator(String missing) {
        CmisAtomPubClient givenClient = "client".equals(missing) ? null : client;
        LongSupplier givenClock = "currentTimeMillis".equals(missing) ? null : () -> MILLIS;
        Resource givenContent = "content".equals(missing) ? null : new ClassPathResource(LOGO);

        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> new CmisDocumentService(givenClient, givenClock, givenContent));

        assertEquals(missing, thrown.getMessage());
        verifyNoInteractions(client);
    }

    private CmisDocumentService fixedClockService(Resource logo) {
        return new CmisDocumentService(client, () -> MILLIS, logo);
    }
}
