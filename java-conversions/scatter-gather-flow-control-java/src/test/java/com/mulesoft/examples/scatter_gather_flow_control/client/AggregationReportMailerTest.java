/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.scatter_gather_flow_control.client;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import java.io.InputStream;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Unit test of {@link AggregationReportMailer}, the {@code smtp:outbound-endpoint} of the original {@code outboundFlow}
 * (scatter-gather.xml:61-66), against an in-process GreenMail SMTP server standing in for the Gmail account the
 * original test read. Every collaborator is built here; no Spring context is started.
 */
class AggregationReportMailerTest {

    private static final String FROM = "scatter@localhost";

    private static final String TO = "receiver@localhost";

    private static final String PASSWORD = "secret";

    /** {@code ContactsCsvMapper} output for the merged contacts; merged maps carry no {@code Id}, so it is blank. */
    private static final String CSV =
            "Id,Name,Email\n,vlado,vlado@email.com\n,michal,michal@email.com\n,peter,peter@email.com\n";

    private static final long DELIVERY_TIMEOUT_MS = 5000;

    // GreenMail binds a free port for each test method instead of its fixed 3025: concurrent builds share this host,
    // so a fixed port may already be taken. Senders read the bound port through greenMailSmtpPort().
    @RegisterExtension
    static final GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort())
            .withConfiguration(GreenMailConfiguration.aConfig().withUser(FROM, PASSWORD));

    @Test
    void sendReturnsBodyTextAndDeliversOneMessage() throws Exception {
        String result = mailer(senderOn(greenMailSmtpPort()), FROM, TO).send(CSV);

        assertEquals(AggregationReportMailer.BODY_TEXT, result);
        assertEquals("Please find attached your Aggregation Report", result);

        MimeMessage message = singleReceivedMessage();
        assertEquals("Scatter gather example - aggregation", message.getSubject());
        Address[] from = message.getFrom();
        assertEquals(1, from.length);
        assertEquals(FROM, ((InternetAddress) from[0]).getAddress());
        assertEquals(List.of(TO), addresses(message.getRecipients(Message.RecipientType.TO)));
    }

    @Test
    void constantsMatchOriginalFlow() {
        assertEquals("Scatter gather example - aggregation", AggregationReportMailer.SUBJECT);
        assertEquals("aggregation-report", AggregationReportMailer.ATTACHMENT_NAME);
        assertEquals("text/plain", AggregationReportMailer.ATTACHMENT_CONTENT_TYPE);
        assertEquals("Please find attached your Aggregation Report", AggregationReportMailer.BODY_TEXT);
    }

    @Test
    void messageIsMultipartMixedWithPayloadAndAttachment() throws Exception {
        mailer(senderOn(greenMailSmtpPort()), FROM, TO).send(CSV);

        MimeMessage message = singleReceivedMessage();
        Object content = message.getContent();
        Multipart multipart = assertInstanceOf(Multipart.class, content);
        assertTrue(message.getContentType().toLowerCase(Locale.ROOT).startsWith("multipart/mixed"),
                message.getContentType());
        assertEquals(2, multipart.getCount());

        BodyPart payload = multipart.getBodyPart(0);
        assertEquals("text/plain;charset=utf-8", normalize(payload.getContentType()));
        assertEquals("Payload", payload.getDescription());
        assertEquals(AggregationReportMailer.BODY_TEXT, payload.getContent());

        BodyPart attachment = multipart.getBodyPart(1);
        assertEquals("aggregation-report", attachment.getFileName());
        assertEquals("aggregation-report", attachment.getDescription());
        assertTrue(attachment.getContentType().toLowerCase(Locale.ROOT).startsWith("text/plain"),
                attachment.getContentType());
        assertAttachmentBytes(assertInstanceOf(MimeBodyPart.class, attachment));
    }

    @Test
    void commaSeparatedRecipientsReceiveOneMessageEach() throws Exception {
        List<String> recipients = List.of("a@localhost", "b@localhost");

        mailer(senderOn(greenMailSmtpPort()), FROM, String.join(",", recipients)).send(CSV);

        assertTrue(greenMail.waitForIncomingEmail(DELIVERY_TIMEOUT_MS, 2));
        MimeMessage[] received = greenMail.getReceivedMessages();
        assertEquals(2, received.length);
        for (String recipient : recipients) {
            GreenMailUser user = greenMail.getUserManager().getUserByEmail(recipient);
            assertNotNull(user, recipient);
            assertEquals(1, greenMail.getManagers().getImapHostManager().getInbox(user).getMessageCount(), recipient);
        }
        assertEquals(recipients, addresses(received[0].getRecipients(Message.RecipientType.TO)));
    }

    @Test
    void closedPortRaisesMailExceptionWithoutRetry() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        JavaMailSenderImpl sender = spy(senderOn(closedPort));
        AggregationReportMailer mailer = mailer(sender, FROM, TO);

        assertThrows(MailException.class, () -> mailer.send(CSV));

        // any(MimeMessage.class) binds the single-message overload the mailer calls; the varargs overload it
        // delegates to is a separate method on the spy and is not counted here.
        verify(sender, times(1)).send(any(MimeMessage.class));
        assertEquals(0, greenMail.getReceivedMessages().length);
    }

    @Test
    void invalidFromRaisesMailException() {
        AggregationReportMailer mailer = mailer(senderOn(greenMailSmtpPort()), "not an address", TO);

        assertThrows(MailException.class, () -> mailer.send(CSV));

        assertEquals(0, greenMail.getReceivedMessages().length);
    }

    private static int greenMailSmtpPort() {
        return greenMail.getSmtp().getPort();
    }

    /** A sender set up as the test profile's {@code spring.mail.*} keys set it up, aimed at the given port. */
    private static JavaMailSenderImpl senderOn(int port) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("localhost");
        sender.setPort(port);
        sender.setUsername(FROM);
        sender.setPassword(PASSWORD);
        sender.setDefaultEncoding("UTF-8");
        Properties properties = new Properties();
        properties.setProperty("mail.smtp.auth", "true");
        properties.setProperty("mail.smtp.starttls.enable", "false");
        // responseTimeout="10000" of the original endpoint; also keeps a failing send from hanging the build.
        properties.setProperty("mail.smtp.connectiontimeout", "10000");
        properties.setProperty("mail.smtp.timeout", "10000");
        properties.setProperty("mail.smtp.writetimeout", "10000");
        sender.setJavaMailProperties(properties);
        return sender;
    }

    private static AggregationReportMailer mailer(JavaMailSender sender, String from, String to) {
        return new AggregationReportMailer(sender, from, to);
    }

    private static MimeMessage singleReceivedMessage() {
        assertTrue(greenMail.waitForIncomingEmail(DELIVERY_TIMEOUT_MS, 1));
        MimeMessage[] received = greenMail.getReceivedMessages();
        assertEquals(1, received.length);
        return received[0];
    }

    /**
     * Base64 content must arrive byte for byte. A 7bit or quoted-printable text part has its line ends canonicalized
     * to CRLF by the SMTP transport, as the original Mule/JavaMail stack did, so it is compared with CRLF read as LF.
     */
    private static void assertAttachmentBytes(MimeBodyPart attachment) throws Exception {
        byte[] bytes;
        try (InputStream in = attachment.getInputStream()) {
            bytes = in.readAllBytes();
        }
        if ("base64".equalsIgnoreCase(attachment.getEncoding())) {
            assertArrayEquals(CSV.getBytes(UTF_8), bytes);
        } else {
            assertEquals(CSV, new String(bytes, UTF_8).replace("\r\n", "\n"));
        }
    }

    private static List<String> addresses(Address[] addresses) {
        assertNotNull(addresses);
        return Arrays.stream(addresses).map(address -> ((InternetAddress) address).getAddress()).toList();
    }

    private static String normalize(String contentType) {
        return contentType.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
