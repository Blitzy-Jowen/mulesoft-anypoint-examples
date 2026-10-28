/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Properties;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import jakarta.activation.DataHandler;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

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
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.icegreen.greenmail.util.ServerSetupTest;

/**
 * Port of the original {@code ImportingAnEmailAttachmentUsingTheIMAPConnectorIT}
 * ({@code importing-an-email-attachment-using-the-IMAP-connector/src/test/java/org/mule/examples}): a mail whose only
 * attachment is {@code input.csv} is sent to the polled mailbox, and once the running application has polled it the
 * output directory must hold exactly one file. The original assertion is kept and complemented by the produced
 * document's name, exact bytes and {@code orders.xsd} validity, and by the expunge of the read mail.
 *
 * <p>The whole application runs, with its scheduled {@code ImapMailPoller} at the original
 * {@code checkFrequency="100"}. An in-process GreenMail server replaces the Gmail account of the original test: plain
 * SMTP to deliver the mail and IMAPS, the protocol of the original {@code imaps:inbound-endpoint}, for the poller.
 * The {@code test} profile trusts GreenMail's self-signed certificate ({@code imap.properties}). No credential,
 * licence or external server is needed.
 *
 * <p>Lifecycle: the extensions' {@code beforeAll} callbacks start GreenMail and create {@link #outputDir}; then
 * {@link #prepareTest()} sends the mail; the Spring context, and with it the poller, loads only when the test instance
 * is post-processed, after that. After the class, {@link #tearDown()} runs, GreenMail stops, and
 * {@code @DirtiesContext} closes the context, which stops the live poller instead of leaving it cached and polling
 * while later test classes run; a poll in the short gap between the last two steps only logs an ERROR for the
 * unreachable server.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ImportingAnEmailAttachmentUsingTheIMAPConnectorTest {

    private static final Logger log =
            LoggerFactory.getLogger(ImportingAnEmailAttachmentUsingTheIMAPConnectorTest.class);

    private static final String HOST = "127.0.0.1";
    // GreenMail mailboxes of the test profile (application-test.yml); test-only values, not credentials of any real
    // system. GreenMail's login is the address itself.
    private static final String RECEIVER = "receiver@localhost";
    private static final String RECEIVER_PASSWORD = "receiver-test-pw";
    private static final String SENDER = "sender@localhost";
    private static final String SENDER_PASSWORD = "sender-test-pw";

    private static final String SUBJECT = "This is the IMAP Subject Line!";
    private static final String INBOX = "INBOX";
    private static final String OUTPUT_FILE_NAME = "orders.xml";
    private static final long DELIVERY_WAIT_MS = 5000;
    private static final int MAIL_TIMEOUT_MS = 10000;
    /** The original test's fixed sleep before its assertion, now the longest wait. */
    private static final Duration ORIGINAL_WAIT = Duration.ofSeconds(10);
    /** The original {@code <imaps:connector checkFrequency="100"/>}, also the await poll interval. */
    private static final Duration CHECK_FREQUENCY = Duration.ofMillis(100);

    /**
     * The document the original flow wrote for {@code input.csv}: the DataWeave 1.0 XML writer's declaration, tab
     * indentation, LF line ends and no trailing newline; every CSV value is kept as text ({@code 2.0} stays
     * {@code 2.0}).
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
    private static final int EXPECTED_ORDERS_XML_BYTES = 289;
    private static final String EXPECTED_ORDERS_XML_SHA256 =
            "c5d9c000e8caa4640d880c08c0f9b75ab2010991b8cf3a93840760af55a906ee";
    private static final int EXPECTED_ORDER_COUNT = 2;
    /** GreenMail's 2 s default start-up wait is too short for the IMAPS TLS context on shared, loaded build hosts. */
    private static final long SERVER_STARTUP_TIMEOUT_MS = 30_000;

    /**
     * One server for the whole class. Its ports are allocated dynamically, never GreenMail's fixed test ports,
     * because concurrent builds share the host; GreenMail 2.1.14 has no SMTP + IMAPS preset, so the two setups are
     * combined here. Plain SMTP accepts unauthenticated delivery, so no STARTTLS or login is needed to send.
     */
    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(smtpAndImaps())
            .withConfiguration(GreenMailConfiguration.aConfig()
                    .withUser(RECEIVER, RECEIVER, RECEIVER_PASSWORD)
                    .withUser(SENDER, SENDER, SENDER_PASSWORD))
            .withPerMethodLifecycle(false);

    /** Replaces the original's {@code ./src/test/resources/output}, the {@code file:outbound-endpoint} path. */
    @TempDir
    static Path outputDir;

    /**
     * Replaces the uncommitted test properties file and the {@code System.setProperty} calls of the original. The
     * suppliers are read when the context loads, after GreenMail has bound its ports and {@link #outputDir} exists.
     * {@code imap.initial-delay} 0 releases the scheduled poll the {@code test} profile holds back, so the poller runs
     * every 100 ms from start-up as the original connector did.
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("imap.host", () -> HOST);
        registry.add("imap.port", () -> GREEN_MAIL.getImaps().getPort());
        registry.add("imap.user", () -> RECEIVER);
        registry.add("imap.password", () -> RECEIVER_PASSWORD);
        registry.add("imap.initial-delay", () -> "0");
        registry.add("orders.output-dir", () -> outputDir.toString());
    }

    /**
     * The original {@code @BeforeClass prepareTest}: sends the mail with {@code input.csv} attached as
     * {@code report}.
     */
    @BeforeAll
    static void prepareTest() throws Exception {
        byte[] csv;
        try (InputStream in = ImportingAnEmailAttachmentUsingTheIMAPConnectorTest.class
                .getResourceAsStream("/input.csv")) {
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
        attachmentPart.setDataHandler(new DataHandler(new ByteArrayDataSource(csv, "application/octet-stream")));
        attachmentPart.setFileName("report");
        Multipart multipart = new MimeMultipart();
        multipart.addBodyPart(attachmentPart);
        message.setContent(multipart);

        Transport.send(message);
        assertTrue(GREEN_MAIL.waitForIncomingEmail(DELIVERY_WAIT_MS, 1), "GreenMail received the mail");
        log.info("Sent message successfully....");
    }

    @Test
    void recieveImapMessage() throws Exception {
        // Replaces the original's fixed 10 s sleep: waits as long at most, until the document is in place. The
        // writer first fills a hidden ".<uuid>.tmp" sibling and renames it, so a single entry is awaited by name.
        await().atMost(ORIGINAL_WAIT).pollInterval(CHECK_FREQUENCY).untilAsserted(() -> {
            File[] files = outputFiles();
            assertEquals(1, files.length, "entries of the output directory: " + Arrays.toString(files));
            assertEquals(OUTPUT_FILE_NAME, files[0].getName());
            assertEquals(EXPECTED_ORDERS_XML_BYTES, files[0].length(), "size of " + OUTPUT_FILE_NAME);
        });

        // The original assertion.
        assertTrue(outputDir.toFile().listFiles().length == 1);

        File written = outputFiles()[0];
        assertEquals(OUTPUT_FILE_NAME, written.getName());
        byte[] produced = Files.readAllBytes(written.toPath());
        assertEquals(EXPECTED_ORDERS_XML_BYTES, produced.length);
        assertEquals(EXPECTED_ORDERS_XML, new String(produced, UTF_8));
        assertEquals(EXPECTED_ORDERS_XML_SHA256, HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(produced)));
        assertEquals(EXPECTED_ORDER_COUNT, validateEachOrder(produced), "orders validated against orders.xsd");

        // The read mail was flagged deleted and expunged when the poller closed the folder.
        await().atMost(ORIGINAL_WAIT).pollInterval(CHECK_FREQUENCY).until(() -> inboxMessageCount() == 0);
    }

    /**
     * The original {@code @AfterClass tearDown}: deletes every file of the output directory, which JUnit then
     * removes. Runs before GreenMail stops and the context closes.
     */
    @AfterAll
    static void tearDown() throws IOException {
        for (File file : outputFiles()) {
            Files.deleteIfExists(file.toPath());
        }
    }

    /**
     * GreenMail's SMTP and IMAPS test setups on dynamic ports, each allowed {@link #SERVER_STARTUP_TIMEOUT_MS} to
     * start. {@code dynamicPort} returns copies, so the shared {@link ServerSetupTest} presets stay unchanged.
     */
    private static ServerSetup[] smtpAndImaps() {
        ServerSetup[] setups =
                ServerSetup.dynamicPort(new ServerSetup[] {ServerSetupTest.SMTP, ServerSetupTest.IMAPS});
        for (ServerSetup setup : setups) {
            setup.setServerStartupTimeout(SERVER_STARTUP_TIMEOUT_MS);
        }
        return setups;
    }

    /** The entries of {@link #outputDir}; none when the directory is absent. */
    private static File[] outputFiles() {
        File[] files = outputDir == null ? null : outputDir.toFile().listFiles();
        return files == null ? new File[0] : files;
    }

    /**
     * Validates every {@code order} of the produced document against the original test resource
     * {@code orders.xsd}. Its {@code ordersType} admits exactly one {@code order}, so each order is validated in a
     * document of its own under an {@code orders} root.
     *
     * @return the number of orders validated
     */
    private static int validateEachOrder(byte[] document) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Element root = builder.parse(new ByteArrayInputStream(document)).getDocumentElement();
        assertEquals("orders", root.getLocalName());

        Validator validator = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                .newSchema(ImportingAnEmailAttachmentUsingTheIMAPConnectorTest.class.getResource("/orders.xsd"))
                .newValidator();
        int validated = 0;
        for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element order && "order".equals(order.getLocalName())) {
                Document single = builder.newDocument();
                Element singleRoot = single.createElementNS(null, "orders");
                single.appendChild(singleRoot);
                singleRoot.appendChild(single.importNode(order, true));
                validator.validate(new DOMSource(single));
                validated++;
            }
        }
        return validated;
    }

    /** The receiver's {@code INBOX} size, read over GreenMail's IMAPS server without changing any flag. */
    private static int inboxMessageCount() throws MessagingException {
        Store store = GREEN_MAIL.getImaps().createStore();
        try {
            store.connect(RECEIVER, RECEIVER_PASSWORD);
            Folder inbox = store.getFolder(INBOX);
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
}
