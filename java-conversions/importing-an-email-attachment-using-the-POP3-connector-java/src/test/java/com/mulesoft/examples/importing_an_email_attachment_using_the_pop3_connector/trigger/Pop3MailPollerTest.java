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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.InternetHeaders;
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
 * GreenMail's self-signed certificate, which the poller accepts through its {@code sslTrust} argument {@code *}
 * everywhere except in the test of the untrusted certificate. Plain JUnit 5 without a Spring context;
 * {@link OutputCaptureExtension} only captures the console log lines.
 *
 * <p>Expected attachment orders are fixed literals worked out once from Mule 3.8's naming rules and the bucket order
 * of a default-capacity {@link java.util.HashMap}; when the poller hands over another order, the poller is wrong, not
 * the literal.
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

        ArgumentCaptor<List<DataHandler>> attachments = attachmentsCaptor();
        verify(service).process(attachments.capture());
        verifyNoMoreInteractions(service);
        assertEquals(1, attachments.getValue().size());
        assertArrayEquals(deliveredInputCsv(), bytesOf(attachments.getValue().get(0)));
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

        ArgumentCaptor<List<DataHandler>> attachments = attachmentsCaptor();
        verify(service).process(attachments.capture());
        assertTrue(attachments.getValue().isEmpty());
        assertEquals(0, pop3MessageCount());
    }

    @Test
    @DisplayName("a processing failure is logged at ERROR by type, the mail is still deleted and the next is processed")
    void logsAProcessingFailureAndCarriesOn(CapturedOutput output) throws Exception {
        send(attachmentMail("first".getBytes(UTF_8), "report"), 1);
        send(attachmentMail("second".getBytes(UTF_8), "report"), 2);
        when(service.process(anyList())).thenThrow(new IOException("simulated failure")).thenReturn(null);

        Pop3MailPoller poller = poller(pop3Host(), pop3Port(), MAILBOX_PASSWORD);
        int consumed = assertDoesNotThrow(poller::pollOnce);

        assertEquals(2, consumed);
        ArgumentCaptor<List<DataHandler>> attachments = attachmentsCaptor();
        verify(service, times(2)).process(attachments.capture());
        List<List<DataHandler>> calls = attachments.getAllValues();
        assertArrayEquals("first".getBytes(UTF_8), bytesOf(calls.get(0).get(0)));
        assertArrayEquals("second".getBytes(UTF_8), bytesOf(calls.get(1).get(0)));
        assertTrue(output.getAll().lines().anyMatch(line -> line.contains("ERROR")
                        && line.contains("Pop3MailPoller")
                        && line.contains("Error processing POP3 message 1: java.io.IOException")),
                "the failure of message 1 is logged at ERROR with its exception type");
        assertFalse(output.getAll().contains("simulated failure"), "the exception message is not logged");
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
                && line.contains("Cannot poll POP3S mailbox")
                && line.contains("jakarta.mail.AuthenticationFailedException")));
        // GreenMail's rejection text is server-controlled, so only its exception type is logged.
        assertFalse(output.getAll().contains("Invalid password"), "the server's rejection text is not logged");

        assertEquals(1, poller(pop3Host(), pop3Port(), MAILBOX_PASSWORD).pollOnce());
        ArgumentCaptor<List<DataHandler>> attachments = attachmentsCaptor();
        verify(service).process(attachments.capture());
        assertArrayEquals(deliveredInputCsv(), bytesOf(attachments.getValue().get(0)));
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
                && line.contains("Cannot poll POP3S mailbox " + pop3Host() + ":" + closedPort)
                && line.contains("java.net.ConnectException")));
        assertFalse(output.getAll().contains("Couldn't connect"), "the exception message is not logged");
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("muleAttachmentOrderCases")
    @DisplayName("the attachments are handed over in the order Mule 3.8's attachment map gave them")
    void handsOverTheAttachmentsInMuleOrder(String mail, MimeMultipart content, List<String> expectedContents)
            throws Exception {
        MimeMessage message = newMessage();
        message.setContent(content);
        send(message, 1);

        assertEquals(1, poller(pop3Host(), pop3Port(), MAILBOX_PASSWORD).pollOnce());

        ArgumentCaptor<List<DataHandler>> attachments = attachmentsCaptor();
        verify(service).process(attachments.capture());
        assertEquals(expectedContents, contentsOf(attachments.getValue()));
        assertEquals(0, pop3MessageCount());
    }

    /**
     * Mails whose attachment order tells Mule 3.8's rules apart from MIME order and from sorted name order. Each
     * expected order was derived once: name every part as {@code MailUtils.getAttachments} and
     * {@code getAttachmentName} do, then list the names by the bucket of a 16-bucket {@link java.util.HashMap}
     * ({@code (h ^ (h >>> 16)) & 15} of {@link String#hashCode()}), names sharing a bucket in their sorted insertion
     * order.
     */
    static Stream<Arguments> muleAttachmentOrderCases() throws MessagingException {
        // MIME order and the name each part gets:
        //   body text without name, description or disposition   -> "0" (index 0 of the outer level)
        //   multipart/mixed: a.csv                                -> "a.csv"
        //                    inner body text                      -> "0" (index 0 of the inner level), taken -> "0_0"
        //   b.csv                                                 -> "b.csv"
        //   described.csv with description "Summary"              -> "Summary" (the description wins)
        //   disposition inline, no file name                      -> "inline"
        //   file name "=?UTF-8?Q?z=C3=BCrich.csv?="               -> "zürich.csv" (decoded)
        // Sorted: 0, 0_0, Summary, a.csv, b.csv, inline, zürich.csv. Buckets: 0 -> 0; 0_0 -> 1; b.csv -> 1;
        // a.csv -> 4; inline -> 4; zürich.csv -> 7; Summary -> 9. So the unnamed body text is the first attachment.
        MimeMultipart inner = new MimeMultipart("mixed");
        inner.addBodyPart(attachmentPart("a-content".getBytes(UTF_8), "a.csv"));
        inner.addBodyPart(textPart("inner text"));
        MimeBodyPart nested = new MimeBodyPart();
        nested.setContent(inner);
        MimeBodyPart described = attachmentPart("described-content".getBytes(UTF_8), "described.csv");
        described.setDescription("Summary");
        MimeBodyPart inline = new MimeBodyPart();
        inline.setDataHandler(new DataHandler(
                new ByteArrayDataSource("inline-content".getBytes(UTF_8), "application/octet-stream")));
        inline.setDisposition(Part.INLINE);
        MimeMultipart mixed = new MimeMultipart("mixed");
        mixed.addBodyPart(textPart("body text"));
        mixed.addBodyPart(nested);
        mixed.addBodyPart(attachmentPart("b-content".getBytes(UTF_8), "b.csv"));
        mixed.addBodyPart(described);
        mixed.addBodyPart(inline);
        mixed.addBodyPart(attachmentPart("zurich-content".getBytes(UTF_8), "=?UTF-8?Q?z=C3=BCrich.csv?="));

        // Three parts named "report" -> "report", "0_report", "1_report" (Mule 3.8.0 takes the first free prefix).
        // Sorted: 0_report, 1_report, report. Buckets: 0_report -> 7; 1_report -> 7; report -> 8. So the first
        // "report" in MIME order is the last attachment.
        MimeMultipart duplicates = new MimeMultipart();
        duplicates.addBodyPart(attachmentPart("first-report".getBytes(UTF_8), "report"));
        duplicates.addBodyPart(attachmentPart("second-report".getBytes(UTF_8), "report"));
        duplicates.addBodyPart(attachmentPart("third-report".getBytes(UTF_8), "report"));

        return Stream.of(
                Arguments.of("body text, nested multipart/mixed, description, disposition and encoded name", mixed,
                        List.of("body text", "inner text", "b-content", "a-content", "inline-content",
                                "zurich-content", "described-content")),
                Arguments.of("three attachments with the same file name", duplicates,
                        List.of("second-report", "third-report", "first-report")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("undecodablePartCases")
    @DisplayName("no part content is read, so a later part that cannot be decoded does not fail the mail")
    void readsNoPartContent(String undecodable, MimeBodyPart unusedPart) throws Exception {
        // Names: orders.csv -> bucket 2, undecodable.csv -> bucket 3, so orders.csv is the first attachment.
        MimeMultipart content = new MimeMultipart();
        content.addBodyPart(attachmentPart("orders-content".getBytes(UTF_8), "orders.csv"));
        content.addBodyPart(unusedPart);
        MimeMessage message = newMessage();
        message.setContent(content);
        send(message, 1);

        assertEquals(1, poller(pop3Host(), pop3Port(), MAILBOX_PASSWORD).pollOnce());

        ArgumentCaptor<List<DataHandler>> attachments = attachmentsCaptor();
        verify(service).process(attachments.capture());
        List<DataHandler> received = attachments.getValue();
        assertEquals(2, received.size());
        assertArrayEquals("orders-content".getBytes(UTF_8), bytesOf(received.get(0)));
        // The second part really cannot be decoded: reading it eagerly would have failed the mail.
        assertThrows(IOException.class, () -> decodedContentOf(received.get(1)));
        assertEquals(0, pop3MessageCount());
    }

    static Stream<Arguments> undecodablePartCases() throws MessagingException {
        MimeBodyPart unknownCharset = new MimeBodyPart();
        unknownCharset.setDataHandler(new DataHandler(
                new ByteArrayDataSource("unused".getBytes(UTF_8), "text/plain; charset=x-unknown")));
        unknownCharset.setFileName("undecodable.csv");

        InternetHeaders headers = new InternetHeaders();
        headers.setHeader("Content-Type", "application/octet-stream");
        headers.setHeader("Content-Transfer-Encoding", "base64");
        headers.setHeader("Content-Disposition", "attachment; filename=undecodable.csv");
        // Four valid characters and a fifth without its group: Angus Mail's strict base64 decoder rejects it.
        MimeBodyPart brokenBase64 = new MimeBodyPart(headers, "QUJDQ".getBytes(UTF_8));

        return Stream.of(
                Arguments.of("text/plain with an unknown charset", unknownCharset),
                Arguments.of("truncated base64", brokenBase64));
    }

    @Test
    @DisplayName("a mail whose MIME structure cannot be parsed stays in the mailbox and the next mail is processed")
    void keepsAnUnparseableMailAndProcessesTheNext(CapturedOutput output) throws Exception {
        // The body declares a boundary that no line carries, so parsing the multipart fails with
        // "Missing start boundary" while the copy and its attachment list are built, before any flag is set.
        send(rawMail("Subject: unparseable\n"
                + "MIME-Version: 1.0\n"
                + "Content-Type: multipart/mixed; boundary=\"never-used\"\n"
                + "\n"
                + "this body has no boundary line\n"), 1);
        send(attachmentMail(inputCsv(), "report"), 2);

        Pop3MailPoller poller = poller(pop3Host(), pop3Port(), MAILBOX_PASSWORD);
        assertEquals(1, assertDoesNotThrow(poller::pollOnce));

        ArgumentCaptor<List<DataHandler>> attachments = attachmentsCaptor();
        verify(service).process(attachments.capture());
        assertEquals(1, attachments.getValue().size());
        assertArrayEquals(deliveredInputCsv(), bytesOf(attachments.getValue().get(0)));
        assertTrue(output.getAll().lines().anyMatch(line -> line.contains("ERROR")
                        && line.contains("Pop3MailPoller")
                        && line.contains("Cannot read POP3 message 1; it stays in the mailbox: "
                                + "jakarta.mail.internet.ParseException")),
                "the unparseable message 1 is logged at ERROR with its exception type");
        assertFalse(output.getAll().contains("Missing start boundary"), "the parser's message is not logged");
        assertEquals(1, pop3MessageCount(), "the unparseable mail is not deleted");

        // The next poll reads it again and keeps it again.
        assertEquals(0, poller.pollOnce());
        verifyNoMoreInteractions(service);
        assertEquals(1, pop3MessageCount());
    }

    @Test
    @DisplayName("diagnostics carry neither the Subject nor exception text, so a hostile Subject cannot forge a line")
    void logsNeitherTheSubjectNorTheExceptionText(CapturedOutput output) throws Exception {
        String subjectSentinel = "SUBJECT-SENTINEL-4d1e";
        String exceptionSentinel = "EXCEPTION-SENTINEL-91c2";
        String forgedSubject = "Quarterly orders\r\n"
                + "2024-01-01T00:00:00.000Z ERROR 1 --- [main] c.m.e.i.trigger.Pop3MailPoller : forged line "
                + subjectSentinel;
        int half = forgedSubject.length() / 2;
        // RFC 2047 encoded words on a folded header line; the decoded Subject holds CR, LF and the forged line.
        send(rawMail("Subject: " + encodedWord(forgedSubject.substring(0, half)) + "\n "
                + encodedWord(forgedSubject.substring(half)) + "\n"
                + "MIME-Version: 1.0\n"
                + "Content-Type: multipart/mixed; boundary=\"b1\"\n"
                + "\n"
                + "--b1\n"
                + "Content-Type: application/octet-stream\n"
                + "Content-Disposition: attachment; filename=report\n"
                + "\n"
                + "orderId\n"
                + "--b1--\n"), 1);
        assertEquals(forgedSubject, greenMail.getReceivedMessages()[0].getSubject(), "the hostile Subject arrived");
        when(service.process(anyList())).thenThrow(new IOException(exceptionSentinel));

        assertEquals(1, assertDoesNotThrow(poller(pop3Host(), pop3Port(), MAILBOX_PASSWORD)::pollOnce));

        verify(service).process(anyList());
        String all = output.getAll();
        assertTrue(all.lines().anyMatch(line -> line.contains("ERROR")
                        && line.contains("Pop3MailPoller")
                        && line.endsWith("Error processing POP3 message 1: java.io.IOException")),
                "the failure is logged at ERROR with the ordinal and the exception type only");
        assertFalse(all.contains(subjectSentinel), "the Subject is not logged");
        assertFalse(all.contains("Quarterly orders"), "the Subject is not logged");
        assertFalse(all.contains("forged line"), "no forged line is logged");
        assertFalse(all.contains(exceptionSentinel), "the exception message is not logged");
        assertFalse(all.contains("Pop3MailPoller.java:"), "no stack frame of the exception is logged");
        assertFalse(all.contains("Pop3MailPollerTest.java:"), "no stack frame of the exception is logged");
        assertEquals(0, pop3MessageCount());
    }

    @Test
    @DisplayName("an untrusted server certificate is logged at WARN with the exception chain's types")
    void logsTheExceptionChainOfAnUntrustedCertificate(CapturedOutput output) throws Exception {
        send(attachmentMail(inputCsv(), "report"), 1);

        // No sslTrust: GreenMail's self-signed certificate fails default certificate path validation. GreenMail's own
        // connection thread then prints the aborted handshake to stderr; that trace is the server's, not the poller's.
        Pop3MailPoller untrusting = new Pop3MailPoller(service, pop3Host(), pop3Port(), RECEIVER, MAILBOX_PASSWORD,
                "INBOX", RESPONSE_TIMEOUT, "");
        assertEquals(0, assertDoesNotThrow(untrusting::pollOnce));

        verifyNoInteractions(service);
        assertEquals(1, pop3MessageCount());
        String chain = "jakarta.mail.MessagingException caused by javax.net.ssl.SSLHandshakeException";
        assertTrue(output.getAll().lines().anyMatch(line -> line.contains("WARN")
                        && line.contains("Cannot poll POP3S mailbox " + pop3Host() + ":" + pop3Port() + ": " + chain)),
                "the TLS failure is logged at WARN with the cause chain's types");
        assertFalse(output.getAll().contains("Connect failed"), "the exception message is not logged");
        assertFalse(output.getAll().contains("PKIX path building failed"), "the cause's message is not logged");
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

    /** A body text part: {@code text/plain} without description, file name or disposition. */
    private static MimeBodyPart textPart(String text) throws MessagingException {
        MimeBodyPart part = new MimeBodyPart();
        part.setText(text);
        return part;
    }

    /**
     * A mail from {@link #SENDER} to {@link #RECEIVER} sent exactly as {@code headersAndBody} spells it: sending
     * neither rewrites its headers nor parses or re-encodes its body, so malformed or hostile MIME reaches the server
     * unchanged.
     */
    private static MimeMessage rawMail(String headersAndBody) throws MessagingException {
        String raw = "From: " + SENDER + "\nTo: " + RECEIVER + "\n" + headersAndBody;
        return new MimeMessage(newMessage().getSession(), new ByteArrayInputStream(raw.getBytes(UTF_8))) {
            @Override
            protected void updateHeaders() {
                // Keep the message as parsed.
            }
        };
    }

    /** {@code text} as one RFC 2047 encoded word in UTF-8 and base64. */
    private static String encodedWord(String text) {
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(text.getBytes(UTF_8)) + "?=";
    }

    /** The part's bytes with only the transfer encoding undone, without any content handler. */
    private static byte[] bytesOf(DataHandler attachment) throws IOException {
        try (InputStream in = attachment.getInputStream()) {
            return in.readAllBytes();
        }
    }

    /** The part's content as the service reads it: {@link DataHandler#getContent()}, a stream read to its end. */
    private static Object decodedContentOf(DataHandler attachment) throws IOException {
        Object content = attachment.getContent();
        if (content instanceof InputStream stream) {
            try (stream) {
                return stream.readAllBytes();
            }
        }
        return content;
    }

    private static List<String> contentsOf(List<DataHandler> attachments) throws IOException {
        List<String> contents = new ArrayList<>();
        for (DataHandler attachment : attachments) {
            contents.add(new String(bytesOf(attachment), UTF_8));
        }
        return contents;
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
    private static ArgumentCaptor<List<DataHandler>> attachmentsCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }
}
