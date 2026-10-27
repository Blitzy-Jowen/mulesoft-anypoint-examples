/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.trigger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.service.AttachmentImportService;

import jakarta.activation.DataHandler;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

/**
 * Trigger test of {@link Pop3MailPoller}, the replacement of {@code <pop3s:connector checkFrequency="100"/>} and
 * {@code <pop3s:inbound-endpoint port="995" responseTimeout="10000"/>} of flow {@code pop-to-xmlFlow1}
 * ({@code importing-an-email-attachment-using-the-POP3-connector/src/main/app/pop-to-xml.xml}, lines 3 and 6) and of
 * the {@code attachments-list} expression that follows it. The flow has no HTTP trigger, so this class stands in for
 * the endpoint test: it delivers mail over SMTP to an in-process GreenMail POP3S server, runs the poller's single-run
 * method {@link Pop3MailPoller#pollOnce()} and asserts what reached the flow body, a Mockito mock of
 * {@link AttachmentImportService}, and what is left in the mailbox.
 *
 * <p>GreenMail runs per test method on dynamic ports, so every poller is built inside its test. POP3S presents
 * GreenMail's self-signed certificate, which the poller accepts through its {@code sslTrust} argument {@code *}.
 * Plain JUnit 5 without a Spring context; {@link OutputCaptureExtension} only captures the console log lines.
 */
@ExtendWith(OutputCaptureExtension.class)
class Pop3MailPollerTest {

    private static final String RECEIVER = "receiver@localhost";
    private static final String SENDER = "sender@localhost";
    /** GreenMail demo password of the in-process test mailbox; not a credential of any real system. */
    private static final String MAILBOX_PASSWORD = "secret";
    private static final String SUBJECT = "This is the POP Subject Line!";
    private static final int RESPONSE_TIMEOUT = 10000;
    private static final long DELIVERY_WAIT_MS = 5000;

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(
            ServerSetup.dynamicPort(new ServerSetup[] { ServerSetupTest.SMTP, ServerSetupTest.POP3S }))
            .withConfiguration(GreenMailConfiguration.aConfig().withUser(RECEIVER, MAILBOX_PASSWORD));

    private AttachmentImportService service;

    @BeforeEach
    void createService() {
        service = mock(AttachmentImportService.class);
    }

    @Test
    @DisplayName("the original mail: its single attachment reaches the flow as delivered and the mail is deleted")
    void processesTheOriginalMailAndDeletesIt() throws Exception {
        send(attachmentMail(inputCsv(), "report"), 1);

        Pop3MailPoller poller = poller(pop3Host(), pop3Port(), MAILBOX_PASSWORD);

        assertEquals(1, poller.pollOnce());
        assertEquals(0, poller.pollOnce(), "a consumed mail is not polled again");

        ArgumentCaptor<List<byte[]>> attachments = attachmentsCaptor();
        verify(service).process(attachments.capture());
        verifyNoMoreInteractions(service);
        assertEquals(1, attachments.getValue().size());
        assertArrayEquals(deliveredInputCsv(), attachments.getValue().get(0));
        assertEquals(0, pop3MessageCount());
        assertEquals(0, greenMail.getReceivedMessages().length);
    }

    @Test
    @DisplayName("a mail without attachment is handed over as an empty list and still deleted")
    void handsAMailWithoutAttachmentOverAsAnEmptyList() throws Exception {
        MimeMessage message = newMessage();
        message.setText("no attachment here");
        send(message, 1);

        assertEquals(1, poller(pop3Host(), pop3Port(), MAILBOX_PASSWORD).pollOnce());

        ArgumentCaptor<List<byte[]>> attachments = attachmentsCaptor();
        verify(service).process(attachments.capture());
        assertTrue(attachments.getValue().isEmpty());
        assertEquals(0, pop3MessageCount());
    }

    @Test
    @DisplayName("a processing failure is logged at ERROR, the mail is still deleted and the next mail is processed")
    void logsAProcessingFailureAndCarriesOn(CapturedOutput output) throws Exception {
        send(attachmentMail("first".getBytes(UTF_8), "report"), 1);
        send(attachmentMail("second".getBytes(UTF_8), "report"), 2);
        when(service.process(anyList())).thenThrow(new IOException("simulated failure")).thenReturn(null);

        Pop3MailPoller poller = poller(pop3Host(), pop3Port(), MAILBOX_PASSWORD);
        int consumed = assertDoesNotThrow(poller::pollOnce);

        assertEquals(2, consumed);
        ArgumentCaptor<List<byte[]>> attachments = attachmentsCaptor();
        verify(service, times(2)).process(attachments.capture());
        List<List<byte[]>> calls = attachments.getAllValues();
        assertArrayEquals("first".getBytes(UTF_8), calls.get(0).get(0));
        assertArrayEquals("second".getBytes(UTF_8), calls.get(1).get(0));
        assertTrue(output.getAll().lines().anyMatch(line -> line.contains("ERROR")
                        && line.contains("Pop3MailPoller")
                        && line.contains("Error processing POP3 message 1")
                        && line.contains("simulated failure")),
                "the failure of message 1 is logged at ERROR");
        assertEquals(0, pop3MessageCount());
    }

    @Test
    @DisplayName("a rejected login is logged at WARN and leaves the mailbox untouched for the next poll")
    void leavesTheMailboxUntouchedWhenTheLoginIsRejected(CapturedOutput output) throws Exception {
        send(attachmentMail(inputCsv(), "report"), 1);

        int consumed = assertDoesNotThrow(poller(pop3Host(), pop3Port(), "wrong")::pollOnce);

        assertEquals(0, consumed);
        verifyNoInteractions(service);
        assertEquals(1, pop3MessageCount());
        assertTrue(output.getAll().lines().anyMatch(line -> line.contains("WARN")
                && line.contains("Cannot poll POP3S mailbox")));

        assertEquals(1, poller(pop3Host(), pop3Port(), MAILBOX_PASSWORD).pollOnce());
        ArgumentCaptor<List<byte[]>> attachments = attachmentsCaptor();
        verify(service).process(attachments.capture());
        assertArrayEquals(deliveredInputCsv(), attachments.getValue().get(0));
        assertEquals(0, pop3MessageCount());
    }

    @Test
    @Timeout(30)
    @DisplayName("an unreachable server is logged at WARN and the poll returns 0 without throwing")
    void returnsZeroWhenTheServerIsUnreachable(CapturedOutput output) throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        int consumed = assertDoesNotThrow(poller(pop3Host(), closedPort, MAILBOX_PASSWORD)::pollOnce);

        assertEquals(0, consumed);
        verifyNoInteractions(service);
        assertTrue(output.getAll().lines().anyMatch(line -> line.contains("WARN")
                && line.contains("Cannot poll POP3S mailbox " + pop3Host() + ":" + closedPort)));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   " })
    @DisplayName("a blank pop3.host disables polling: nothing is read and the mailbox stays as it is")
    void skipsThePollWhenTheHostIsBlank(String host) throws Exception {
        send(attachmentMail(inputCsv(), "report"), 1);

        assertEquals(0, poller(host, pop3Port(), MAILBOX_PASSWORD).pollOnce());

        verifyNoInteractions(service);
        assertEquals(1, pop3MessageCount());
    }

    @Test
    @DisplayName("attachments nested in multipart/mixed are handed over in MIME order, body text excluded")
    void collectsNestedAttachmentsInMimeOrder() throws Exception {
        MimeBodyPart text = new MimeBodyPart();
        text.setText("see attachments");

        MimeMultipart inner = new MimeMultipart("mixed");
        inner.addBodyPart(attachmentPart("a-content".getBytes(UTF_8), "a.csv"));
        MimeBodyPart nested = new MimeBodyPart();
        nested.setContent(inner);

        MimeMultipart outer = new MimeMultipart("mixed");
        outer.addBodyPart(text);
        outer.addBodyPart(nested);
        outer.addBodyPart(attachmentPart("b-content".getBytes(UTF_8), "b.csv"));

        MimeMessage message = newMessage();
        message.setContent(outer);
        send(message, 1);

        assertEquals(1, poller(pop3Host(), pop3Port(), MAILBOX_PASSWORD).pollOnce());

        // Pop3MailPoller walks multiparts depth-first and keeps only leaves that name a file or declare an
        // attachment disposition, so the leading body text is not an attachment and a.csv precedes b.csv.
        ArgumentCaptor<List<byte[]>> attachments = attachmentsCaptor();
        verify(service).process(attachments.capture());
        List<byte[]> received = attachments.getValue();
        assertEquals(2, received.size());
        assertArrayEquals("a-content".getBytes(UTF_8), received.get(0));
        assertArrayEquals("b-content".getBytes(UTF_8), received.get(1));
    }

    private Pop3MailPoller poller(String host, int port, String password) {
        return new Pop3MailPoller(service, host, port, RECEIVER, password, "INBOX", RESPONSE_TIMEOUT, "*");
    }

    private static String pop3Host() {
        return greenMail.getPop3s().getServerSetup().getBindAddress();
    }

    private static int pop3Port() {
        return greenMail.getPop3s().getPort();
    }

    /** The project's copy of the original {@code src/main/resources/input.csv}, the attachment the original test sent. */
    private static byte[] inputCsv() throws IOException {
        try (InputStream in = Pop3MailPollerTest.class.getResourceAsStream("/input.csv")) {
            assertNotNull(in, "input.csv is on the classpath");
            return in.readAllBytes();
        }
    }

    /**
     * {@code input.csv} as the mailbox holds it. Jakarta Mail sends all-ASCII {@code application/octet-stream}
     * content with transfer encoding {@code 7bit}, as it did for the original test's {@code FileDataSource}, and SMTP
     * carries 7bit lines with CRLF ends, so the attachment arrives with each LF turned into CRLF (57 → 59 bytes). The
     * poller must hand over exactly these decoded bytes; the CSV reader downstream accepts either line end.
     */
    private static byte[] deliveredInputCsv() throws IOException {
        byte[] delivered = new String(inputCsv(), UTF_8).replace("\n", "\r\n").getBytes(UTF_8);
        assertEquals(59, delivered.length);
        return delivered;
    }

    private static MimeMessage newMessage() throws MessagingException {
        Properties props = new Properties();
        props.setProperty("mail.smtp.host", greenMail.getSmtp().getServerSetup().getBindAddress());
        props.setProperty("mail.smtp.port", String.valueOf(greenMail.getSmtp().getPort()));
        MimeMessage message = new MimeMessage(Session.getInstance(props));
        message.setFrom(new InternetAddress(SENDER));
        message.addRecipient(Message.RecipientType.TO, new InternetAddress(RECEIVER));
        message.setSubject(SUBJECT);
        return message;
    }

    /** A mail shaped like the original test's: a multipart holding one octet-stream attachment. */
    private static MimeMessage attachmentMail(byte[] content, String fileName) throws MessagingException {
        MimeMultipart multipart = new MimeMultipart();
        multipart.addBodyPart(attachmentPart(content, fileName));
        MimeMessage message = newMessage();
        message.setContent(multipart);
        return message;
    }

    private static MimeBodyPart attachmentPart(byte[] content, String fileName) throws MessagingException {
        MimeBodyPart part = new MimeBodyPart();
        part.setDataHandler(new DataHandler(new ByteArrayDataSource(content, "application/octet-stream")));
        part.setFileName(fileName);
        return part;
    }

    private static void send(MimeMessage message, int expectedTotal) throws MessagingException {
        Transport.send(message);
        assertTrue(greenMail.waitForIncomingEmail(DELIVERY_WAIT_MS, expectedTotal),
                "GreenMail received " + expectedTotal + " mail(s)");
    }

    /** Counts the mails left in the POP3 mailbox over a read-only session that flags nothing for deletion. */
    private static int pop3MessageCount() throws MessagingException {
        Properties props = new Properties();
        props.setProperty("mail.pop3s.ssl.trust", "*");
        props.setProperty("mail.pop3s.ssl.checkserveridentity", "false");
        props.setProperty("mail.pop3s.connectiontimeout", String.valueOf(RESPONSE_TIMEOUT));
        props.setProperty("mail.pop3s.timeout", String.valueOf(RESPONSE_TIMEOUT));
        Store store = Session.getInstance(props).getStore("pop3s");
        store.connect(pop3Host(), pop3Port(), RECEIVER, MAILBOX_PASSWORD);
        try {
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            try {
                return inbox.getMessageCount();
            } finally {
                inbox.close(false);
            }
        } finally {
            store.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<byte[]>> attachmentsCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }
}
