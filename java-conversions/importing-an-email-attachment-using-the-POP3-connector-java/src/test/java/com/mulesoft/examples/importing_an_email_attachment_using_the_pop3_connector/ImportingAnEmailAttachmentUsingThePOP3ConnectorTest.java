/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Properties;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.icegreen.greenmail.util.ServerSetupTest;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

/**
 * Port of the original {@code ImportingAnEmailAttachmentUsingThePOP3ConnectorIT}
 * ({@code importing-an-email-attachment-using-the-POP3-connector/src/test/java/org/mule/examples}): a mail whose only
 * attachment is {@code input.csv} is sent to the polled mailbox, and once the running application has polled it the
 * output directory must hold a file. The original assertion is kept and complemented by the produced document's
 * exact bytes and by the deletion of the read mail ({@code deleteReadMessages}).
 *
 * <p>The whole application runs, with its scheduled {@code Pop3MailPoller} at the original
 * {@code checkFrequency="100"}. An in-process GreenMail server replaces the Gmail account of the original test: plain
 * SMTP to deliver the mail and POP3S, the protocol of the original {@code pop3s:inbound-endpoint}, for the poller.
 * GreenMail presents a self-signed certificate, which {@code pop3.ssl-trust=*} accepts. No credential, licence or
 * external server is needed.
 *
 * <p>{@code @DirtiesContext} closes the context after this class, which stops the live 100 ms poller rather than
 * leaving it cached and polling a stopped GreenMail while later test classes run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ImportingAnEmailAttachmentUsingThePOP3ConnectorTest {

    private static final Logger log = LoggerFactory.getLogger(ImportingAnEmailAttachmentUsingThePOP3ConnectorTest.class);

    private static final String HOST = "localhost";
    private static final String RECEIVER = "receiver@localhost";
    private static final String SENDER = "sender@localhost";
    /** GreenMail demo password of the in-process test mailbox; not a credential of any real system. */
    private static final String MAILBOX_PASSWORD = "secret";
    private static final String SUBJECT = "This is the POP Subject Line!";
    /** The original {@code <pop3s:connector checkFrequency="100"/>}. */
    private static final String CHECK_FREQUENCY_MS = "100";
    private static final String OUTPUT_FILE_NAME = "orders.xml";
    private static final long DELIVERY_WAIT_MS = 5000;
    private static final int MAIL_TIMEOUT_MS = 10000;

    /**
     * The document the original flow wrote for {@code input.csv}: the DataWeave 1.0 XML writer's declaration, tab
     * indentation, LF line ends and no trailing newline (289 bytes in UTF-8).
     */
    private static final String EXPECTED_ORDERS_XML = "<?xml version='1.0' encoding='UTF-8'?>\n"
            + "<orders>\n"
            + "\t<order>\n"
            + "\t\t<orderId>1</orderId>\n"
            + "\t\t<name>aaa</name>\n"
            + "\t\t<units>2.0</units>\n"
            + "\t\t<pricePerUnit>10</pricePerUnit>\n"
            + "\t</order>\n"
            + "\t<order>\n"
            + "\t\t<orderId>2</orderId>\n"
            + "\t\t<name>bbb</name>\n"
            + "\t\t<units>4.15</units>\n"
            + "\t\t<pricePerUnit>5</pricePerUnit>\n"
            + "\t</order>\n"
            + "</orders>";

    /**
     * One server for the whole class on dynamic ports. GreenMail 2.1.14 has no SMTP + POP3S preset, so the two
     * setups are combined here; plain SMTP accepts unauthenticated delivery, so no STARTTLS is needed.
     */
    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(
            ServerSetup.dynamicPort(new ServerSetup[] { ServerSetupTest.SMTP, ServerSetupTest.POP3S }))
            .withConfiguration(GreenMailConfiguration.aConfig().withUser(RECEIVER, MAILBOX_PASSWORD))
            .withPerMethodLifecycle(false);

    /** Replaces the original's {@code ./src/test/resources/output}, the {@code file:outbound-endpoint} path. */
    @TempDir
    static Path outputDir;

    /**
     * Replaces the {@code mule.test.properties} values the original loaded. These outrank {@code application-test.yml}
     * and the surefire system properties, which otherwise keep the poller idle; the suppliers are read when the
     * context loads, after GreenMail has started.
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("pop3.host", () -> HOST);
        registry.add("pop3.port", () -> GREEN_MAIL.getPop3s().getPort());
        registry.add("pop3.user", () -> RECEIVER);
        registry.add("pop3.password", () -> MAILBOX_PASSWORD);
        registry.add("pop3.ssl-trust", () -> "*");
        registry.add("pop3.check-frequency", () -> CHECK_FREQUENCY_MS);
        registry.add("orders.output-dir", () -> outputDir.toString());
    }

    /**
     * The original {@code @BeforeClass prepareTest}: sends the mail with {@code input.csv} attached.
     *
     * <p>Ordering: the extensions' {@code beforeAll} callbacks run first, so GreenMail is started and
     * {@link #outputDir} is created; this method runs next. The Spring context, and with it the scheduled poller, is
     * loaded only when the test instance is post-processed, after this method, so the mail is in the mailbox before
     * the first poll.
     */
    @BeforeAll
    static void sendMail() throws Exception {
        byte[] csv;
        try (InputStream in = ImportingAnEmailAttachmentUsingThePOP3ConnectorTest.class.getResourceAsStream("/input.csv")) {
            assertNotNull(in, "input.csv is on the classpath");
            csv = in.readAllBytes();
        }

        Properties properties = new Properties();
        properties.setProperty("mail.smtp.host", HOST);
        properties.setProperty("mail.smtp.port", String.valueOf(GREEN_MAIL.getSmtp().getPort()));
        properties.setProperty("mail.smtp.connectiontimeout", String.valueOf(MAIL_TIMEOUT_MS));
        properties.setProperty("mail.smtp.timeout", String.valueOf(MAIL_TIMEOUT_MS));
        Session session = Session.getInstance(properties);

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(SENDER));
        message.addRecipient(Message.RecipientType.TO, new InternetAddress(RECEIVER));
        message.setSubject(SUBJECT);
        MimeBodyPart attachmentPart = new MimeBodyPart();
        // The original overrode FileDataSource.getContentType() to report application/octet-stream.
        DataSource dataSource = new ByteArrayDataSource(csv, "application/octet-stream");
        attachmentPart.setDataHandler(new DataHandler(dataSource));
        attachmentPart.setFileName("report");
        Multipart multipart = new MimeMultipart();
        multipart.addBodyPart(attachmentPart);
        message.setContent(multipart);

        Transport.send(message);
        assertTrue(GREEN_MAIL.waitForIncomingEmail(DELIVERY_WAIT_MS, 1), "GreenMail received the mail");
        log.info("Sent message successfully....");
    }

    @Test
    void recievePOPMessage() throws Exception {
        byte[] expected = EXPECTED_ORDERS_XML.getBytes(StandardCharsets.UTF_8);

        // Replaces the original's fixed 10 s sleep: waits as long at most, until the document is complete. Hidden
        // files are skipped because FileOutboundWriter writes to a ".<uuid>.tmp" sibling before renaming it.
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                .until(() -> Arrays.stream(outputFiles())
                        .anyMatch(file -> file.isFile() && !file.getName().startsWith(".")
                                && file.length() == expected.length));

        File[] files = outputFiles();
        assertTrue(files.length > 0, "the output directory holds a file");
        assertEquals(1, files.length, "exactly one file is written: " + Arrays.toString(files));
        assertEquals(OUTPUT_FILE_NAME, files[0].getName());
        assertArrayEquals(expected, Files.readAllBytes(files[0].toPath()));

        // Mule's POP3 inbound endpoint deleted every message it read (deleteReadMessages).
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
                .until(() -> GREEN_MAIL.getReceivedMessages().length == 0);
    }

    /**
     * The original {@code @AfterClass tearDown}: empties the output directory, which JUnit also removes. The
     * original's IMAP deletion of the sent mail from the real mailbox has no counterpart: GreenMail keeps the mailbox
     * in memory and stops with the extension. This method runs before GreenMail stops and the context closes; a poll
     * in between only logs an ERROR for the unreachable server.
     */
    @AfterAll
    static void tearDown() throws IOException {
        for (File file : outputFiles()) {
            Files.deleteIfExists(file.toPath());
        }
    }

    private static File[] outputFiles() {
        File[] files = outputDir.toFile().listFiles();
        return files == null ? new File[0] : files;
    }
}
