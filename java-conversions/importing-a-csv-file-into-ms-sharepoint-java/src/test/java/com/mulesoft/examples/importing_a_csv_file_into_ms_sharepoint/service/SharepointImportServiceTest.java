/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.client.SharepointRestClient;
import com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.config.SharepointProperties;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Tests {@link SharepointImportService}, the body of the original flow
 * {@code importing-a-csv-file-into-ms-sharepointFlow}: sign in, create {@code /Shared Documents/<csvImportFolder>},
 * add the polled bytes there as {@code contacts.csv} with overwrite, then log the success line, in that order.
 *
 * <p>{@link SharepointRestClient} is a Mockito mock, so no request leaves the JVM and no credential is needed. The
 * original declares no exception strategy, so a failure at any step must reach the caller unchanged, skip every
 * later step and suppress the success line.
 */
@ExtendWith(MockitoExtension.class)
class SharepointImportServiceTest {

    private static final String FOLDER = "/Shared Documents/test-folder";

    private static final String FILE = FOLDER + "/contacts.csv";

    private static final String SUCCESS_LINE =
            "Input file stored in the specified folder under the 'Shared Documents' folder";

    private static final SharepointRestClient.SharepointSession SESSION =
            new SharepointRestClient.SharepointSession("rtFa=a; FedAuth=b", "digest");

    @Mock
    SharepointRestClient client;

    private SharepointImportService service;

    private byte[] bytes;

    private byte[] expectedBytes;

    private Logger serviceLogger;

    private Level savedLevel;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        service = serviceFor("test-folder");
        bytes = "firstname;surname;phone;email\nJohn;Doe;096548763;john.doe@texasComp.com\n".getBytes(UTF_8);
        expectedBytes = bytes.clone();

        serviceLogger = (Logger) LoggerFactory.getLogger(SharepointImportService.class);
        savedLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(appender);
        appender.stop();
        serviceLogger.setLevel(savedLevel);
    }

    @Test
    void importFileSignsInCreatesFolderThenAddsFileWithOverwrite() {
        givenSignInSucceeds();

        service.importFile(bytes);

        InOrder order = inOrder(client);
        order.verify(client).signIn();
        order.verify(client).createFolder(SESSION, FOLDER);
        order.verify(client).addFile(eq(SESSION), eq(FILE), aryEq(expectedBytes), eq(true));

        ArgumentCaptor<byte[]> content = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<Boolean> overwrite = ArgumentCaptor.forClass(Boolean.class);
        verify(client).addFile(any(), anyString(), content.capture(), overwrite.capture());
        assertThat(overwrite.getValue()).isTrue();
        assertThat(content.getValue()).containsExactly(expectedBytes);
        verifyNoMoreInteractions(client);
    }

    @Test
    void importFileLogsTheOriginalInfoLineOnce() {
        givenSignInSucceeds();

        service.importFile(bytes);

        assertThat(infoEvents())
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly(SUCCESS_LINE);
    }

    @Test
    void folderNameIsUsedVerbatim() {
        service = serviceFor("My Folder 'Q'");
        givenSignInSucceeds();

        service.importFile(bytes);

        InOrder order = inOrder(client);
        order.verify(client).signIn();
        order.verify(client).createFolder(SESSION, "/Shared Documents/My Folder 'Q'");
        order.verify(client).addFile(eq(SESSION), eq("/Shared Documents/My Folder 'Q'/contacts.csv"),
                aryEq(expectedBytes), eq(true));
        verifyNoMoreInteractions(client);
    }

    @Test
    void signInFailurePropagatesAndSkipsEveryLaterStep() {
        IllegalStateException failure = new IllegalStateException("sign-in failed");
        when(client.signIn()).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.importFile(bytes)));

        verify(client).signIn();
        verify(client, never()).createFolder(any(), anyString());
        verify(client, never()).addFile(any(), anyString(), any(), anyBoolean());
        assertThat(infoEvents()).isEmpty();
        verifyNoMoreInteractions(client);
    }

    @Test
    void createFolderFailurePropagatesAndSkipsAddFile() {
        HttpClientErrorException failure =
                HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", new HttpHeaders(), null, null);
        givenSignInSucceeds();
        doThrow(failure).when(client).createFolder(SESSION, FOLDER);

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.importFile(bytes)));

        InOrder order = inOrder(client);
        order.verify(client).signIn();
        order.verify(client).createFolder(SESSION, FOLDER);
        verify(client, never()).addFile(any(), anyString(), any(), anyBoolean());
        assertThat(infoEvents()).isEmpty();
        verifyNoMoreInteractions(client);
    }

    @Test
    void addFileFailurePropagatesWithoutTheSuccessLine() {
        ResourceAccessException failure = new ResourceAccessException("I/O error on POST request");
        givenSignInSucceeds();
        doThrow(failure).when(client).addFile(eq(SESSION), eq(FILE), aryEq(expectedBytes), eq(true));

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.importFile(bytes)));

        InOrder order = inOrder(client);
        order.verify(client).signIn();
        order.verify(client).createFolder(SESSION, FOLDER);
        order.verify(client).addFile(eq(SESSION), eq(FILE), aryEq(expectedBytes), eq(true));
        assertThat(infoEvents()).isEmpty();
        verifyNoMoreInteractions(client);
    }

    @Test
    void urlPartsMatchTheOriginalFlow() {
        assertThat(SharepointImportService.SHARED_DOCUMENTS).isEqualTo("/Shared Documents/");
        assertThat(SharepointImportService.TARGET_FILE_NAME).isEqualTo("contacts.csv");
    }

    private SharepointImportService serviceFor(String csvImportFolder) {
        SharepointProperties props = new SharepointProperties("user@example.onmicrosoft.com", "pw",
                "http://127.0.0.1:9", csvImportFolder, "http://127.0.0.1:9/extSTS.srf");
        return new SharepointImportService(client, props);
    }

    private void givenSignInSucceeds() {
        when(client.signIn()).thenReturn(SESSION);
    }

    private List<ILoggingEvent> infoEvents() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .toList();
    }
}
