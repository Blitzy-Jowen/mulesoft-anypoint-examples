/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.client;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Stream;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Client test of {@link ReportMailSender}, the {@code set-attachment} and {@code smtp:outbound-endpoint} of the
 * original {@code attachmentsFlow1} (attachments.xml:35-36), against an in-process GreenMail SMTP server standing in
 * for the Gmail account the original integration test read back over IMAPS. No Spring context is started: the sender
 * and the {@code JavaMailSender} are built here as the test profile configures them.
 */
class ReportMailSenderTest {

    /** GreenMail account; the original test sent from and to its own SMTP account. */
    private static final String ACCOUNT = "test@localhost";

    private static final String PASSWORD = "secret";

    /** {@code mail.subject} of the original test (QueryingDbAndAttachingResultsToAnEmailIT:121). */
    private static final String SUBJECT = "Mule flow completed!";

    /** {@code EmployeeCsvMapper} output for the three employees of the original {@code message.xml}. */
    private static final String CSV = "first_name,last_name,gender,dob,hire_date\n"
            + "Chava,Puckett,F,1985-09-02,2008-10-12\n"
            + "Quentin,Puckett,F,1971-10-21,2008-09-15\n"
            + "Mona,Sosa,M,1950-09-26,2007-11-27\n";

    /** The two rows the original test expected in the mail (QueryingDbAndAttachingResultsToAnEmailIT:52-53). */
    private static final String REPLY_1 = "Chava,Puckett,F,1985-09-02,2008-10-12";

    private static final String REPLY_2 = "Mona,Sosa,M,1950-09-26,2007-11-27";

    private static final long DELIVERY_TIMEOUT_MS = 5000;

    // A dynamic port instead of GreenMail's fixed 3025: concurrent builds on this host and the project's other
    // GreenMail tests would otherwise contend for one port. One server serves the class and is purged per test.
    @RegisterExtension
    static final GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort())
            .withConfiguration(GreenMailConfiguration.aConfig().withUser(ACCOUNT, PASSWORD))
            .withPerMethodLifecycle(false);

    private ReportMailSender sender;

    @BeforeEach
    void setUp() throws Exception {
        greenMail.purgeEmailFromAllMailboxes();
        sender = new ReportMailSender(mailSenderOn(greenMail.getSmtp().getPort()), ACCOUNT, ACCOUNT, SUBJECT);
    }

    @Test
    void sendsOneMessageWithConfiguredEnvelope() throws Exception {
        sender.send(CSV);

        MimeMessage message = singleReceivedMessage();
        assertThat(addresses(message.getFrom())).containsExactly(ACCOUNT);
        assertThat(addresses(message.getRecipients(Message.RecipientType.TO))).containsExactly(ACCOUNT);
        assertThat(message.getSubject()).isEqualTo(SUBJECT);
    }

    @Test
    void messageIsMultipartMixedWithBodyThenAttachment() throws Exception {
        sender.send(CSV);

        MimeMessage message = singleReceivedMessage();
        assertThat(message.isMimeType("multipart/mixed")).as(message.getContentType()).isTrue();
        assertThat(message.getContent()).isInstanceOf(MimeMultipart.class);
        MimeMultipart multipart = (MimeMultipart) message.getContent();
        assertThat(multipart.getCount()).isEqualTo(2);

        // The part the original test's getAttachment read: neither an attachment nor named.
        BodyPart body = multipart.getBodyPart(0);
        assertThat(body.isMimeType("text/plain")).as(body.getContentType()).isTrue();
        assertThat(contentTypeOf(body)).contains("charset=utf-8");
        assertThat(body.getDisposition()).isNull();
        assertThat(body.getFileName()).isNull();
        assertThat(body.getDescription()).isEqualTo("Payload");
        assertThat(text(body)).isEqualTo(CSV).contains(REPLY_1, REPLY_2);

        BodyPart attachment = multipart.getBodyPart(1);
        assertThat(Part.ATTACHMENT.equalsIgnoreCase(attachment.getDisposition()))
                .as(attachment.getDisposition()).isTrue();
        assertThat(attachment.getFileName()).isEqualTo("employees.csv");
        assertThat(attachment.getDescription()).isEqualTo("employees.csv");
        assertThat(attachment.isMimeType("text/plain")).as(attachment.getContentType()).isTrue();
        // ASCII content keeps the flow's bare text/plain, which JavaMail labels us-ascii as it did for the original.
        assertThat(contentTypeOf(attachment)).contains("charset=us-ascii");
        assertThat(text(attachment)).isEqualTo(CSV);
    }

    @Test
    void nonAsciiReportArrivesIntactAndLabelledUtf8() throws Exception {
        String csv = "first_name,last_name,gender,dob,hire_date\nJosé,Núñez,M,1980-01-02,2010-03-04\n";
        String subject = "Informe de empleados – año 2008";
        ReportMailSender utf8Sender =
                new ReportMailSender(mailSenderOn(greenMail.getSmtp().getPort()), ACCOUNT, ACCOUNT, subject);

        utf8Sender.send(csv);

        MimeMessage message = singleReceivedMessage();
        assertThat(message.getSubject()).isEqualTo(subject);
        MimeMultipart multipart = (MimeMultipart) message.getContent();
        BodyPart body = multipart.getBodyPart(0);
        assertThat(contentTypeOf(body)).contains("charset=utf-8");
        assertThat(text(body)).isEqualTo(csv);
        BodyPart attachment = multipart.getBodyPart(1);
        assertThat(contentTypeOf(attachment)).contains("charset=utf-8");
        assertThat(attachment.getFileName()).isEqualTo("employees.csv");
        assertThat(text(attachment)).isEqualTo(csv);
    }

    @Test
    void commaSeparatedRecipientsEachReceiveTheReport() throws Exception {
        List<String> recipients = List.of("hr@localhost", "payroll@localhost");
        ReportMailSender listSender = new ReportMailSender(
                mailSenderOn(greenMail.getSmtp().getPort()), ACCOUNT, String.join(",", recipients), SUBJECT);

        listSender.send(CSV);

        assertThat(greenMail.waitForIncomingEmail(DELIVERY_TIMEOUT_MS, recipients.size())).isTrue();
        MimeMessage[] received = greenMail.getReceivedMessages();
        assertThat(received).hasSize(recipients.size());
        for (MimeMessage message : received) {
            assertThat(addresses(message.getRecipients(Message.RecipientType.TO)))
                    .containsExactlyElementsOf(recipients);
        }
    }

    /**
     * Spring's own message for a refused connection names the mail and network exception classes and the SMTP host,
     * port and timeout; the caller-facing message is the fixed text alone, and the refusal stays on the cause chain.
     */
    @Test
    void unreachableSmtpServerFailsWithTheFixedSendFailureMessage() throws Exception {
        try (Socket reserved = reserveRefusingPort()) {
            JavaMailSenderImpl unreachable = mailSenderOn(reserved.getLocalPort());
            unreachable.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", "2000");
            unreachable.getJavaMailProperties().setProperty("mail.smtp.timeout", "2000");
            ReportMailSender failingSender = new ReportMailSender(unreachable, ACCOUNT, ACCOUNT, SUBJECT);

            // A MessagingException returns its nested exception from getCause(), so the refusal is on the chain.
            assertThatThrownBy(() -> failingSender.send(CSV))
                    .isInstanceOf(MailSendException.class)
                    .hasMessage(ReportMailSender.SEND_FAILURE_MESSAGE)
                    .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain("org.", "java.",
                            "Exception", Integer.toString(reserved.getLocalPort()), "localhost", "127.0.0.1"))
                    .satisfies(failure -> assertThat(Stream.iterate(failure, Objects::nonNull, Throwable::getCause))
                            .anyMatch(ConnectException.class::isInstance));

            assertThat(greenMail.getReceivedMessages()).isEmpty();
        }
    }

    /** A rejected account fails with Spring's fixed message, which names no class, host or port, and is not retried. */
    @Test
    void rejectedCredentialsFailWithAuthenticationFailedUnchanged() {
        JavaMailSenderImpl rejected = mailSenderOn(greenMail.getSmtp().getPort());
        rejected.setPassword("not-" + PASSWORD);
        ReportMailSender rejectedSender = new ReportMailSender(rejected, ACCOUNT, ACCOUNT, SUBJECT);

        assertThatThrownBy(() -> rejectedSender.send(CSV))
                .isInstanceOf(MailAuthenticationException.class)
                .hasMessage("Authentication failed")
                .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain("org.", "java.",
                        Integer.toString(greenMail.getSmtp().getPort()), "localhost", "127.0.0.1"));

        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    void invalidSenderAddressFailsBeforeSending() {
        ReportMailSender invalidSender = new ReportMailSender(
                mailSenderOn(greenMail.getSmtp().getPort()), "not an address", ACCOUNT, SUBJECT);

        assertThatThrownBy(() -> invalidSender.send(CSV)).isInstanceOf(MailPreparationException.class);

        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    /**
     * A required setting left blank, as an unset TODO key leaves it, refuses the send before a message is built or a
     * connection opened: the {@link MailPreparationException}, not the rewrapped {@link MailSendException}, names each
     * missing key with its environment variable, in a fixed order, and no value. The loopback listener the sender is
     * aimed at must have accepted nothing.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unconfiguredSettings")
    void unconfiguredSettingRefusesTheSendBeforeAnyConnection(String description, String host, String user,
                                                              String password, String from, String to,
                                                              String expectedMessage) throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            JavaMailSenderImpl configured = mailSenderOn(listener.getLocalPort());
            configured.setHost(host);
            configured.setUsername(user);
            configured.setPassword(password);
            JavaMailSenderImpl mailSender = spy(configured);
            ReportMailSender unconfigured = new ReportMailSender(mailSender, from, to, SUBJECT);

            assertThatThrownBy(() -> unconfigured.send(CSV))
                    .isExactlyInstanceOf(MailPreparationException.class)
                    .hasMessage(expectedMessage)
                    .hasNoCause()
                    .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(
                            "localhost", PASSWORD, Integer.toString(listener.getLocalPort())));

            verify(mailSender, never()).createMimeMessage();
            verify(mailSender, never()).send(any(MimeMessage.class));
            listener.setSoTimeout(200);
            assertThatThrownBy(listener::accept).as("the sender opened a connection")
                    .isInstanceOf(SocketTimeoutException.class);
        }
    }

    static Stream<Arguments> unconfiguredSettings() {
        String prefix = "SMTP mail settings are not configured: ";
        return Stream.of(
                Arguments.of("empty smtp.host", "", ACCOUNT, PASSWORD, ACCOUNT, ACCOUNT,
                        prefix + "smtp.host (SMTP_HOST)"),
                Arguments.of("absent smtp.host", null, ACCOUNT, PASSWORD, ACCOUNT, ACCOUNT,
                        prefix + "smtp.host (SMTP_HOST)"),
                Arguments.of("empty smtp.user", "localhost", "", PASSWORD, ACCOUNT, ACCOUNT,
                        prefix + "smtp.user (SMTP_USER)"),
                Arguments.of("blank smtp.password", "localhost", ACCOUNT, "  ", ACCOUNT, ACCOUNT,
                        prefix + "smtp.password (SMTP_PASSWORD)"),
                Arguments.of("empty mail.from", "localhost", ACCOUNT, PASSWORD, "", ACCOUNT,
                        prefix + "mail.from (MAIL_FROM)"),
                Arguments.of("blank mail.to", "localhost", ACCOUNT, PASSWORD, ACCOUNT, " ",
                        prefix + "mail.to (MAIL_TO)"),
                Arguments.of("absent mail.to", "localhost", ACCOUNT, PASSWORD, ACCOUNT, null,
                        prefix + "mail.to (MAIL_TO)"),
                Arguments.of("smtp.host and mail.to", "", ACCOUNT, PASSWORD, ACCOUNT, "",
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
        JavaMailSenderImpl unauthenticated = mailSenderOn(greenMail.getSmtp().getPort());
        unauthenticated.setUsername("");
        unauthenticated.setPassword("");
        if (auth == null) {
            unauthenticated.getJavaMailProperties().remove("mail.smtp.auth");
        } else {
            unauthenticated.getJavaMailProperties().put("mail.smtp.auth", auth);
        }

        new ReportMailSender(unauthenticated, ACCOUNT, ACCOUNT, SUBJECT).send(CSV);

        MimeMessage message = singleReceivedMessage();
        assertThat(addresses(message.getRecipients(Message.RecipientType.TO))).containsExactly(ACCOUNT);
        assertThat(message.getSubject()).isEqualTo(SUBJECT);
    }

    static Stream<Arguments> authenticationOff() {
        return Stream.of(Arguments.of("false"), Arguments.of(Boolean.FALSE), Arguments.of((Object) null));
    }

    /** {@code mail.subject} is not a required setting: an empty one is sent as given. */
    @Test
    void emptySubjectIsSentAsGiven() throws Exception {
        new ReportMailSender(mailSenderOn(greenMail.getSmtp().getPort()), ACCOUNT, ACCOUNT, "").send(CSV);

        assertThat(singleReceivedMessage().getSubject()).isEmpty();
    }

    @Test
    void nullReportIsRejectedWithoutSending() {
        assertThatThrownBy(() -> sender.send(null)).isInstanceOf(NullPointerException.class).hasMessage("csv");

        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    void attachmentConstantsMatchOriginalFlow() {
        assertThat(ReportMailSender.ATTACHMENT_NAME).isEqualTo("employees.csv");
        assertThat(ReportMailSender.ATTACHMENT_CONTENT_TYPE).isEqualTo("text/plain");
    }

    /** A sender set up as the test profile's {@code spring.mail.*} keys set it up, aimed at the given port. */
    private static JavaMailSenderImpl mailSenderOn(int port) {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost("localhost");
        mailSender.setPort(port);
        mailSender.setUsername(ACCOUNT);
        mailSender.setPassword(PASSWORD);
        mailSender.setDefaultEncoding(UTF_8.name());
        Properties properties = new Properties();
        properties.setProperty("mail.smtp.auth", "true");
        properties.setProperty("mail.smtp.starttls.enable", "false");
        // responseTimeout="10000" of the original endpoint; also keeps a failing send from hanging the build.
        properties.setProperty("mail.smtp.connectiontimeout", "10000");
        properties.setProperty("mail.smtp.timeout", "10000");
        properties.setProperty("mail.smtp.writetimeout", "10000");
        mailSender.setJavaMailProperties(properties);
        return mailSender;
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

    private static MimeMessage singleReceivedMessage() {
        assertThat(greenMail.waitForIncomingEmail(DELIVERY_TIMEOUT_MS, 1)).isTrue();
        MimeMessage[] received = greenMail.getReceivedMessages();
        assertThat(received).hasSize(1);
        return received[0];
    }

    /** Decoded text of a part, whether JavaMail hands it back as a string or as a stream. */
    private static String text(Part part) throws Exception {
        Object content = part.getContent();
        if (content instanceof InputStream stream) {
            try (stream) {
                return normalize(new String(stream.readAllBytes(), UTF_8));
            }
        }
        return normalize(content);
    }

    /**
     * SMTP DATA canonicalizes bare LF line ends to CRLF in 7bit and quoted-printable text parts, as the original
     * Mule/JavaMail stack did, so received text is compared with CRLF read as LF. Base64 content is unaffected.
     */
    static String normalize(Object content) {
        return content.toString().replace("\r\n", "\n");
    }

    private static String contentTypeOf(Part part) throws Exception {
        return part.getContentType().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static List<String> addresses(Address[] addresses) {
        assertThat(addresses).isNotNull();
        return Arrays.stream(addresses).map(address -> ((InternetAddress) address).getAddress()).toList();
    }
}
