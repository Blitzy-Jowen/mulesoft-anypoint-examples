/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.support.HttpTestClient;
import com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.support.ImportOrdersSeeder;

/**
 * Port of the original example's {@code QueryingDbAndAttachingResultsToAnEmailIT} functional test, keeping its
 * request and its three assertions on the mailed CSV, which are compared by containment as in the original.
 *
 * <p>Profile {@code test} and this class replace the original's external systems, so the real controller, service,
 * repository, CSV mapper and mail sender run end to end without credentials or network:
 * <ul>
 *   <li>the live MySQL server and its {@code MySQLDbCreator} set-up become an in-memory H2 database in MySQL mode,
 *       loaded from the project's {@code import_orders.sql} by {@link ImportOrdersSeeder} before each test;</li>
 *   <li>the Gmail account the original sent through over SMTP and read back over IMAPS becomes an in-process
 *       GreenMail SMTP server, whose received messages are read directly. Waiting for the delivery replaces the
 *       original's fixed 15-second sleep, and purging the mailbox replaces flagging the read message deleted.</li>
 * </ul>
 *
 * <p>The HTTP response is checked as well, because the converted listener answers with the flow's final message:
 * the CSV as the {@code employees.csv} part of a {@code multipart/form-data} entity.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class QueryingDbAndAttachingResultsToAnEmailTest {

    private static final String REPLY_1 = "Chava,Puckett,F,1985-09-02,2008-10-12";

    private static final String REPLY_2 = "Mona,Sosa,M,1950-09-26,2007-11-27";

    /** The CSV the flow renders for the three employees of {@code message.xml}: header row, LF line ends. */
    private static final String CSV = "first_name,last_name,gender,dob,hire_date\n"
            + "Chava,Puckett,F,1985-09-02,2008-10-12\n"
            + "Quentin,Puckett,F,1971-10-21,2008-09-15\n"
            + "Mona,Sosa,M,1950-09-26,2007-11-27\n";

    /** The original's limit on the wait for the mail, here an upper bound rather than a fixed sleep. */
    private static final long MAIL_TIMEOUT_MILLIS = 15000;

    // A dynamic port instead of GreenMail's fixed 3025, so concurrent builds and the project's other GreenMail
    // tests never contend for one port. One server lives for the whole class, so the port the cached Spring
    // context was configured with stays valid for every test of the class.
    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort())
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("test@localhost", "secret"))
            .withPerMethodLifecycle(false);

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbcTemplate;

    /** Points the mail sender at GreenMail; the suppliers run at context load, after GreenMail has started. */
    @DynamicPropertySource
    static void mailProperties(DynamicPropertyRegistry registry) {
        registry.add("smtp.port", () -> greenMail.getSmtp().getPort());
        registry.add("spring.mail.port", () -> greenMail.getSmtp().getPort());
    }

    @BeforeEach
    void setUp() throws Exception {
        greenMail.purgeEmailFromAllMailboxes();
        ImportOrdersSeeder.seed(jdbcTemplate);
    }

    @Test
    void attachmentsTest() throws Exception {
        byte[] message;
        try (InputStream in = getClass().getResourceAsStream("/message.xml")) {
            assertNotNull(in, "classpath resource message.xml");
            message = in.readAllBytes();
        }

        // The original dispatched the message as a String payload, which Mule sent as text/plain in UTF-8.
        HttpTestClient.Response response = HttpTestClient.localhost(port)
                .send("POST", "/", Map.of("Content-Type", "text/plain; charset=UTF-8"), message);

        assertEquals(200, response.status(), response::toString);
        String contentType = response.header("Content-Type");
        assertNotNull(contentType, response::toString);
        assertTrue(contentType.startsWith("multipart/form-data"), response::toString);
        MimeMultipart reply = new MimeMultipart(new ByteArrayDataSource(response.body(), contentType));
        assertEquals(1, reply.getCount(), response::toString);
        BodyPart part = reply.getBodyPart(0);
        String[] disposition = part.getHeader("Content-Disposition");
        assertNotNull(disposition, response::toString);
        assertTrue(disposition[0].contains("name=\"employees.csv\""), disposition[0]);
        assertTrue(part.getContentType().startsWith("text/plain"), part.getContentType());
        try (InputStream csv = part.getInputStream()) {
            assertEquals(CSV, new String(csv.readAllBytes(), UTF_8));
        }

        assertTrue(greenMail.waitForIncomingEmail(MAIL_TIMEOUT_MILLIS, 1), "no mail received");
        MimeMessage[] received = greenMail.getReceivedMessages();
        String attachmentContent = getAttachment(received[received.length - 1]);
        assertNotNull(attachmentContent);
        assertTrue(attachmentContent.contains(REPLY_1));
        assertTrue(attachmentContent.contains(REPLY_2));
    }

    /**
     * The content of the message's first part that is neither disposed as an attachment nor named, as the original
     * read it: Mule mailed the flow payload, the CSV, as that unnamed body part ahead of the attachment.
     */
    private static String getAttachment(Message message) throws IOException, MessagingException {
        Multipart multipart = (Multipart) message.getContent();

        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart bodyPart = multipart.getBodyPart(i);
            String fileName = bodyPart.getFileName();
            if (!Part.ATTACHMENT.equalsIgnoreCase(bodyPart.getDisposition())
                    && (fileName == null || fileName.isBlank())) {
                return bodyPart.getContent().toString();
            }
        }
        return null;
    }
}
