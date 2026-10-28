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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Unit test of {@link AggregationReportMailer}, the {@code smtp:outbound-endpoint} of the original {@code outboundFlow}
 * (scatter-gather.xml:61-66), against an in-process GreenMail SMTP server standing in for the Gmail account the
 * original test read. Every collaborator is built here, except in the {@code mail.subject} test, which starts a
 * minimal Spring context over the project's {@code application.yml} and profile {@code test}.
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

    /**
     * The subject is {@code mail.subject} as the application resolves it: profile {@code test} over the main
     * {@code application.yml}. The profile sets no subject, so the flow's literal default arrives unless the key is
     * overridden; an empty override is sent as given.
     *
     * @param override the {@code mail.subject} value added to the context, or {@code null} for none
     * @param expected the subject the received message must carry
     */
    @ParameterizedTest(name = "mail.subject override {0} -> \"{1}\"")
    @MethodSource("subjectConfigurations")
    void subjectComesFromMailSubjectDefaultingToTheFlowLiteral(String override, String expected) throws Exception {
        List<String> properties = new ArrayList<>(List.of(
                "spring.profiles.active=test", "smtp.port=" + greenMailSmtpPort()));
        if (override != null) {
            properties.add("mail.subject=" + override);
        }

        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(
                        PropertyPlaceholderAutoConfiguration.class, MailSenderAutoConfiguration.class))
                .withUserConfiguration(AggregationReportMailer.class)
                .withPropertyValues(properties.toArray(String[]::new))
                .run(context -> assertEquals(AggregationReportMailer.BODY_TEXT,
                        context.getBean(AggregationReportMailer.class).send(CSV)));

        assertEquals(expected, singleReceivedMessage().getSubject());
    }

    static Stream<Arguments> subjectConfigurations() {
        return Stream.of(
                Arguments.of(null, "Scatter gather example - aggregation"),
                Arguments.of("Weekly contacts report", "Weekly contacts report"),
                Arguments.of("", ""));
    }

    @Test
    void constantsMatchOriginalFlow() {
        assertEquals("Scatter gather example - aggregation", AggregationReportMailer.DEFAULT_SUBJECT);
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
        // A bound socket that never listens refuses connections and keeps the port from being taken meanwhile.
        try (Socket reserved = reserveRefusingPort()) {
            JavaMailSenderImpl sender = spy(senderOn(reserved.getLocalPort()));
            AggregationReportMailer mailer = mailer(sender, FROM, TO);

            assertThrows(MailException.class, () -> mailer.send(CSV));

            // any(MimeMessage.class) binds the single-message overload the mailer calls; the varargs overload it
            // delegates to is a separate method on the spy and is not counted here.
            verify(sender, times(1)).send(any(MimeMessage.class));
        }
        assertEquals(0, greenMail.getReceivedMessages().length);
    }

    /**
     * A required setting left blank, as an unset TODO key leaves it, refuses the send before a message is built or a
     * connection opened: the {@link MailPreparationException} names each missing key with its environment variable,
     * in a fixed order, and no value. The loopback listener the sender is aimed at must have accepted nothing.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unconfiguredSettings")
    void unconfiguredSettingRefusesTheSendBeforeAnyConnection(String description, String host, String user,
                                                              String password, String from, String to,
                                                              String expectedMessage) throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            JavaMailSenderImpl configured = senderOn(listener.getLocalPort());
            configured.setHost(host);
            configured.setUsername(user);
            configured.setPassword(password);
            JavaMailSenderImpl sender = spy(configured);
            AggregationReportMailer mailer = mailer(sender, from, to);

            MailPreparationException failure = assertThrows(MailPreparationException.class, () -> mailer.send(CSV));

            assertEquals(expectedMessage, failure.getMessage());
            assertNull(failure.getCause());
            for (String value : List.of("localhost", PASSWORD, Integer.toString(listener.getLocalPort()))) {
                assertFalse(failure.getMessage().contains(value), value);
            }
            verify(sender, never()).createMimeMessage();
            verify(sender, never()).send(any(MimeMessage.class));
            listener.setSoTimeout(200);
            assertThrows(SocketTimeoutException.class, listener::accept, "the sender opened a connection");
        }
    }

    static Stream<Arguments> unconfiguredSettings() {
        String prefix = "SMTP mail settings are not configured: ";
        return Stream.of(
                Arguments.of("empty smtp.host", "", FROM, PASSWORD, FROM, TO, prefix + "smtp.host (SMTP_HOST)"),
                Arguments.of("absent smtp.host", null, FROM, PASSWORD, FROM, TO, prefix + "smtp.host (SMTP_HOST)"),
                Arguments.of("empty smtp.user", "localhost", "", PASSWORD, FROM, TO, prefix + "smtp.user (SMTP_USER)"),
                Arguments.of("blank smtp.password", "localhost", FROM, "  ", FROM, TO,
                        prefix + "smtp.password (SMTP_PASSWORD)"),
                Arguments.of("empty mail.from", "localhost", FROM, PASSWORD, "", TO, prefix + "mail.from (MAIL_FROM)"),
                Arguments.of("blank mail.to", "localhost", FROM, PASSWORD, FROM, " ", prefix + "mail.to (MAIL_TO)"),
                Arguments.of("absent mail.to", "localhost", FROM, PASSWORD, FROM, null, prefix + "mail.to (MAIL_TO)"),
                Arguments.of("smtp.host and mail.to", "", FROM, PASSWORD, FROM, "",
                        prefix + "smtp.host (SMTP_HOST), mail.to (MAIL_TO)"),
                Arguments.of("every TODO key unset", "", "", "", "", "",
                        prefix + "smtp.host (SMTP_HOST), smtp.user (SMTP_USER), smtp.password (SMTP_PASSWORD), "
                                + "mail.from (MAIL_FROM), mail.to (MAIL_TO)"));
    }

    /**
     * With SMTP authentication off, as an unauthenticated server is used, the account is not required: a blank
     * {@code smtp.user} and {@code smtp.password} are accepted and the report is delivered.
     *
     * @param auth the {@code mail.smtp.auth} value, or {@code null} for none
     */
    @ParameterizedTest(name = "mail.smtp.auth={0}")
    @MethodSource("authenticationOff")
    void authenticationOffSendsWithoutAccount(Object auth) throws Exception {
        JavaMailSenderImpl sender = senderOn(greenMailSmtpPort());
        sender.setUsername("");
        sender.setPassword("");
        if (auth == null) {
            sender.getJavaMailProperties().remove("mail.smtp.auth");
        } else {
            sender.getJavaMailProperties().put("mail.smtp.auth", auth);
        }

        assertEquals(AggregationReportMailer.BODY_TEXT, mailer(sender, FROM, TO).send(CSV));

        MimeMessage message = singleReceivedMessage();
        assertEquals(AggregationReportMailer.DEFAULT_SUBJECT, message.getSubject());
        assertEquals(List.of(TO), addresses(message.getRecipients(Message.RecipientType.TO)));
    }

    static Stream<Arguments> authenticationOff() {
        return Stream.of(Arguments.of("false"), Arguments.of(Boolean.FALSE), Arguments.of((Object) null));
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

    /**
     * A socket bound to a free port on every local address that never listens or connects: a connection to its port
     * is refused while it is open, and with SO_REUSEADDR off no other socket can bind that port meanwhile.
     */
    private static Socket reserveRefusingPort() throws IOException {
        Socket socket = new Socket();
        try {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(0));
            return socket;
        } catch (IOException | RuntimeException ex) {
            try {
                socket.close();
            } catch (IOException closeFailure) {
                ex.addSuppressed(closeFailure);
            }
            throw ex;
        }
    }

    private static AggregationReportMailer mailer(JavaMailSender sender, String from, String to) {
        return new AggregationReportMailer(sender, from, to, AggregationReportMailer.DEFAULT_SUBJECT);
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
