/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.client;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Client test of {@link CsvMailSender}, the {@code smtp:outbound-endpoint} of the original {@code csv-to-smtpFlow}
 * (csv-to-smtp.xml:20), against an in-process GreenMail SMTP server standing in for the hosted mail account the
 * original {@code SendingACsvFileThroughEmailUsingSmtpIT} read back over IMAPS. No Spring context is started: the
 * sender and its {@code JavaMailSender} are built here with the demo values of {@code application-test.yml}.
 *
 * <p>Received bodies are compared after {@code trim()}, the comparison the original test applied to the mail it read.
 */
class CsvMailSenderTest {

    /** {@code mail.from} of the test profile; the original test sent from its own SMTP account. */
    private static final String FROM = "test@localhost";

    /** {@code mail.to} of the test profile; the original test sent to its own SMTP account. */
    private static final String TO = "test@localhost";

    /** GreenMail password of the {@link #FROM} account ({@code smtp.password} of the test profile). */
    private static final String PASSWORD = "secret";

    /** {@code mail.subject} of the original test (SendingACsvFileThroughEmailUsingSmtpIT:100). */
    private static final String SUBJECT = "Mule flow completed!";

    /** The committed {@code email.txt}, the body the original test expected for the original {@code input.csv}. */
    private static final String EMAIL_BODY = "[{name=aaa, orderId=1, pricePerUnit=10, units=2, totalPrice=20}, "
            + "{name=bbb, orderId=2, pricePerUnit=5, units=4.15, totalPrice=20.75}]";

    private static final long DELIVERY_TIMEOUT_MS = 5000;

    // A dynamic port instead of GreenMail's fixed 3025: concurrent builds on this host and the project's other
    // GreenMail tests would otherwise contend for one port. One server serves the class and is purged after each test.
    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort())
            .withConfiguration(GreenMailConfiguration.aConfig().withUser(FROM, PASSWORD))
            .withPerMethodLifecycle(false);

    @AfterEach
    void purgeMailboxes() throws Exception {
        GREEN_MAIL.purgeEmailFromAllMailboxes();
    }

    @Test
    @DisplayName("sends one single-part text/plain UTF-8 mail with the configured sender, recipient and subject")
    void sendsTextPlainUtf8MailWithConfiguredHeaders() throws Exception {
        List<Map<String, String>> payload = List.of(Map.of("name", "aaa"));

        sender(TO).send(payload);

        MimeMessage message = singleReceivedMessage();
        assertThat(addresses(message.getFrom())).containsExactly(FROM);
        assertThat(addresses(message.getRecipients(Message.RecipientType.TO))).containsExactly(TO);
        assertThat(message.getRecipients(Message.RecipientType.CC)).isNull();
        assertThat(message.getRecipients(Message.RecipientType.BCC)).isNull();
        assertThat(message.getSubject()).isEqualTo(SUBJECT);
        assertThat(message.isMimeType("text/plain")).as(message.getContentType()).isTrue();
        assertThat(new ContentType(message.getContentType()).getParameter("charset")).isEqualToIgnoringCase("UTF-8");
        assertThat(message.getContent()).isInstanceOf(String.class).isNotInstanceOf(Multipart.class);
        assertThat(body(message)).isEqualTo(String.valueOf(payload));
    }

    @Test
    @DisplayName("a string payload equal to the committed email.txt arrives as exactly that text")
    void bodyIsGoldenEmailTxtString() throws Exception {
        // Keeps the constant and the committed golden file from drifting apart.
        assertThat(classpathText("/email.txt")).isEqualTo(EMAIL_BODY);

        sender(TO).send(EMAIL_BODY);

        assertThat(body(singleReceivedMessage())).isEqualTo(EMAIL_BODY);
    }

    @Test
    @DisplayName("a non-string payload is mailed as its toString(), with no formatting added by the sender")
    void bodyIsStringValueOfNonStringPayload() throws Exception {
        // The shape OrderLinesMapper produces for the original input.csv: rendering it is the mapper's concern, so the
        // sender must pass its toString() through unchanged.
        List<Map<String, Object>> orderLines = new ArrayList<>();
        orderLines.add(orderLine("aaa", "1", "10", "2", "20"));
        orderLines.add(orderLine("bbb", "2", "5", "4.15", "20.75"));
        CsvMailSender sender = sender(TO);

        sender.send(orderLines);

        assertThat(body(singleReceivedMessage())).isEqualTo(orderLines.toString()).isEqualTo(EMAIL_BODY);

        GREEN_MAIL.purgeEmailFromAllMailboxes();
        // Non-ASCII text also proves the UTF-8 body survives the transfer encoding JavaMail picks for it.
        String marker = "custom toString – café ✓";
        Object custom = new Object() {
            @Override
            public String toString() {
                return marker;
            }
        };

        sender.send(custom);

        assertThat(body(singleReceivedMessage())).isEqualTo(marker);
    }

    @Test
    @DisplayName("a null payload is mailed as the text \"null\", as String.valueOf renders it")
    void nullPayloadSendsStringNull() throws Exception {
        sender(TO).send(null);

        assertThat(body(singleReceivedMessage())).isEqualTo("null");
    }

    @Test
    @DisplayName("a comma-separated mail.to delivers one copy to each listed recipient")
    void commaSeparatedToProducesOneCopyPerRecipient() throws Exception {
        // GreenMail creates a mailbox for each unknown local recipient on delivery.
        List<String> recipients = List.of("a@localhost", "b@localhost");

        sender(String.join(",", recipients)).send(EMAIL_BODY);

        assertThat(GREEN_MAIL.waitForIncomingEmail(DELIVERY_TIMEOUT_MS, recipients.size())).isTrue();
        MimeMessage[] received = GREEN_MAIL.getReceivedMessages();
        assertThat(received).hasSize(recipients.size());
        for (MimeMessage message : received) {
            assertThat(addresses(message.getRecipients(Message.RecipientType.TO)))
                    .containsExactlyElementsOf(recipients);
            assertThat(body(message)).isEqualTo(EMAIL_BODY);
        }
    }

    @Test
    @DisplayName("an unreachable SMTP server fails the send with a MailException carrying the connection refusal")
    void unreachableServerThrowsMailExceptionWithoutRetry() throws Exception {
        // A bound socket that never listens refuses connections and keeps the port from being reused meanwhile, which a
        // closed ServerSocket(0) cannot guarantee on a host where many builds open ports concurrently.
        try (Socket reserved = reserveRefusingPort()) {
            CsvMailSender failingSender = new CsvMailSender(mailSender(reserved.getLocalPort()), FROM, TO, SUBJECT);

            // A MessagingException returns its nested exception from getCause(), so the refusal is on the chain.
            assertThatThrownBy(() -> failingSender.send(EMAIL_BODY))
                    .isInstanceOf(MailException.class)
                    .satisfies(failure -> assertThat(causeChain(failure)).anyMatch(ConnectException.class::isInstance));
        }

        assertThat(GREEN_MAIL.getReceivedMessages()).isEmpty();
    }

    @Test
    @DisplayName("a server that drops the connection is contacted exactly once and the failure propagates")
    void droppedConnectionFailsAfterExactlyOneAttempt() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        try (ServerSocket dropping = new ServerSocket(0)) {
            Thread acceptor = new Thread(() -> acceptAndDrop(dropping, connections), "dropping-smtp-server");
            acceptor.setDaemon(true);
            acceptor.start();
            CsvMailSender failingSender = new CsvMailSender(mailSender(dropping.getLocalPort()), FROM, TO, SUBJECT);

            assertThatThrownBy(() -> failingSender.send(EMAIL_BODY)).isInstanceOf(MailException.class);

            // The client only sees the drop after the server counted and closed the connection, and send() is
            // synchronous, so any retry would already have been counted here.
            assertThat(connections.get()).isEqualTo(1);
        }

        assertThat(GREEN_MAIL.getReceivedMessages()).isEmpty();
    }

    @ParameterizedTest(name = "mail.from=''{0}'', mail.to=''{1}'' -> {2}")
    @MethodSource("unusableAddressConfigurations")
    @DisplayName("an unusable mail.from or mail.to fails with a MailException and delivers nothing")
    void unusableAddressConfigurationFailsWithoutDelivery(String from, String to,
                                                          Class<? extends MailException> expected) {
        CsvMailSender misconfigured = new CsvMailSender(mailSender(GREEN_MAIL.getSmtp().getPort()), from, to, SUBJECT);

        assertThatThrownBy(() -> misconfigured.send(EMAIL_BODY)).isInstanceOf(expected);

        assertThat(GREEN_MAIL.getReceivedMessages()).isEmpty();
    }

    /**
     * Address values the message cannot be sent with: a malformed sender, or one left at the empty default of
     * {@code application.yml}, fails while the message is built; an empty recipient list leaves no recipient, which
     * the SMTP send rejects.
     */
    static Stream<Arguments> unusableAddressConfigurations() {
        return Stream.of(
                Arguments.of("not an address", TO, MailPreparationException.class),
                Arguments.of("", TO, MailPreparationException.class),
                Arguments.of(FROM, "", MailSendException.class));
    }

    /** The sender as the test profile configures it, mailing to the given {@code mail.to} value. */
    private static CsvMailSender sender(String to) {
        return new CsvMailSender(mailSender(GREEN_MAIL.getSmtp().getPort()), FROM, to, SUBJECT);
    }

    /** A {@code JavaMailSender} configured as the test profile's {@code spring.mail.*} keys configure it. */
    private static JavaMailSenderImpl mailSender(int port) {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost("localhost");
        mailSender.setPort(port);
        mailSender.setUsername(FROM);
        mailSender.setPassword(PASSWORD);
        mailSender.setDefaultEncoding(UTF_8.name());
        Properties properties = new Properties();
        properties.setProperty("mail.smtp.auth", "true");
        properties.setProperty("mail.smtp.starttls.enable", "false");
        // Short timeouts keep a failing send from hanging the build.
        properties.setProperty("mail.smtp.connectiontimeout", "5000");
        properties.setProperty("mail.smtp.timeout", "5000");
        properties.setProperty("mail.smtp.writetimeout", "5000");
        mailSender.setJavaMailProperties(properties);
        return mailSender;
    }

    /** One order line keyed in the order of the original script, with its numbers as {@link BigDecimal}s. */
    private static Map<String, Object> orderLine(String name, String orderId, String pricePerUnit, String units,
                                                 String totalPrice) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("name", name);
        line.put("orderId", orderId);
        line.put("pricePerUnit", new BigDecimal(pricePerUnit));
        line.put("units", new BigDecimal(units));
        line.put("totalPrice", new BigDecimal(totalPrice));
        return line;
    }

    /** Accepts connections until the server socket closes, counting each one and closing it without a greeting. */
    private static void acceptAndDrop(ServerSocket server, AtomicInteger connections) {
        while (!server.isClosed()) {
            try (Socket accepted = server.accept()) {
                connections.incrementAndGet();
            } catch (IOException closed) {
                return;
            }
        }
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
        assertThat(GREEN_MAIL.waitForIncomingEmail(DELIVERY_TIMEOUT_MS, 1)).isTrue();
        MimeMessage[] received = GREEN_MAIL.getReceivedMessages();
        assertThat(received).hasSize(1);
        return received[0];
    }

    /** The decoded single-part body, trimmed as the original test trimmed the mail it read. */
    private static String body(MimeMessage message) throws Exception {
        Object content = message.getContent();
        assertThat(content).as("single-part text body").isInstanceOf(String.class);
        return ((String) content).trim();
    }

    private static String classpathText(String resource) throws IOException {
        try (InputStream stream = CsvMailSenderTest.class.getResourceAsStream(resource)) {
            assertThat(stream).as(resource).isNotNull();
            return new String(stream.readAllBytes(), UTF_8);
        }
    }

    private static List<String> addresses(Address[] addresses) {
        assertThat(addresses).isNotNull();
        return Arrays.stream(addresses).map(address -> ((InternetAddress) address).getAddress()).toList();
    }

    private static Stream<Throwable> causeChain(Throwable failure) {
        return Stream.iterate(failure, Objects::nonNull, Throwable::getCause);
    }
}
