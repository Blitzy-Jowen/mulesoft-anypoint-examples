/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.client;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Outbound SMTP adapter that mails the employees CSV report, replacing three elements of
 * {@code querying-a-db-and-attaching-results-to-an-email/src/main/app/attachments.xml}:
 * <ul>
 *   <li>line 4, {@code smtp:gmail-connector}: its authentication and STARTTLS behaviour is configured on the
 *       {@link JavaMailSender} through {@code spring.mail.*} in {@code application.yml};</li>
 *   <li>line 35, {@code set-attachment attachmentName="employees.csv" contentType="text/plain"}: the CSV becomes the
 *       {@value #ATTACHMENT_NAME} attachment;</li>
 *   <li>line 36, {@code smtp:outbound-endpoint}: sender, recipients and subject come from {@code mail.from},
 *       {@code mail.to} and {@code mail.subject}; server, port, account and the endpoint's 10000 ms response timeout
 *       are {@code spring.mail.*} settings, so this class holds no host, address or credential.</li>
 * </ul>
 *
 * <p>The message is laid out as Mule 3.8's {@code ObjectToMimeMessage} lays out a payload with one outbound
 * attachment: a {@code multipart/mixed} whose first part is the payload (the CSV as {@code text/plain; charset=UTF-8},
 * description {@value #PAYLOAD_PART_DESCRIPTION}, no file name, no disposition) and whose second part is the
 * attachment, described and named {@value #ATTACHMENT_NAME}. The order matters: the original integration test reads
 * the first part that is neither an attachment nor named, and expects the CSV rows in it.
 *
 * <p>Send failures ({@link org.springframework.mail.MailException}) propagate unchanged and are never retried: the
 * original flow has no exception strategy, so the platform default answers them.
 */
@Component
public class ReportMailSender {

    /** Attachment name of {@code set-attachment} (attachments.xml:35). */
    public static final String ATTACHMENT_NAME = "employees.csv";

    /** Attachment content type of {@code set-attachment} (attachments.xml:35). */
    public static final String ATTACHMENT_CONTENT_TYPE = "text/plain";

    /** Description Mule gives the payload part of a message with attachments. */
    static final String PAYLOAD_PART_DESCRIPTION = "Payload";

    /** Encoding of the payload part, the subject and the CSV bytes; Mule's default output encoding. */
    static final String CHARSET = StandardCharsets.UTF_8.name();

    private final JavaMailSender mailSender;

    private final String from;

    private final String to;

    private final String subject;

    public ReportMailSender(JavaMailSender mailSender,
                            @Value("${mail.from}") String from,
                            @Value("${mail.to}") String to,
                            @Value("${mail.subject}") String subject) {
        this.mailSender = mailSender;
        this.from = from;
        this.to = to;
        this.subject = subject;
    }

    /**
     * Mails the CSV report to the configured recipients, as the body part and as the {@value #ATTACHMENT_NAME}
     * attachment.
     *
     * @param csv the rendered employees CSV
     * @throws NullPointerException when {@code csv} is null
     * @throws MailPreparationException when the message cannot be built, for example from an invalid address
     * @throws org.springframework.mail.MailException when the send fails (authentication, connection, rejection)
     */
    public void send(String csv) {
        Objects.requireNonNull(csv, "csv");
        MimeMessage message = mailSender.createMimeMessage();
        try {
            message.setFrom(new InternetAddress(from));
            // InternetAddress.parse accepts a comma-separated list, as the endpoint's "to" attribute did.
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            message.setSubject(subject, CHARSET);

            MimeBodyPart body = new MimeBodyPart();
            body.setText(csv, CHARSET);
            body.setDescription(PAYLOAD_PART_DESCRIPTION);

            MimeBodyPart attachment = new MimeBodyPart();
            attachment.setDataHandler(new DataHandler(
                    new ByteArrayDataSource(csv.getBytes(StandardCharsets.UTF_8), attachmentContentType(csv))));
            attachment.setDescription(ATTACHMENT_NAME);
            attachment.setDisposition(Part.ATTACHMENT);
            attachment.setFileName(ATTACHMENT_NAME);

            MimeMultipart multipart = new MimeMultipart("mixed");
            multipart.addBodyPart(body);
            multipart.addBodyPart(attachment);
            message.setContent(multipart);
        } catch (MessagingException e) {
            throw new MailPreparationException("Could not prepare the employees report mail", e);
        }
        mailSender.send(message);
    }

    /**
     * Content type of the attachment's UTF-8 bytes. ASCII content keeps the flow's bare {@code text/plain}, to which
     * JavaMail adds {@code charset=us-ascii} as it did for the original. Other content names UTF-8 explicitly,
     * because JavaMail would otherwise label a charset-less text part with the JVM's default charset, which on a
     * non-UTF-8 host would not match the bytes.
     */
    private static String attachmentContentType(String csv) {
        return StandardCharsets.US_ASCII.newEncoder().canEncode(csv)
                ? ATTACHMENT_CONTENT_TYPE
                : ATTACHMENT_CONTENT_TYPE + "; charset=" + CHARSET;
    }
}
