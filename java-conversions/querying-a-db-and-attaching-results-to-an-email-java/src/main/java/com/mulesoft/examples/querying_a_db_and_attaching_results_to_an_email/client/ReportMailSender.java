/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.client;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

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
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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
 * <p>Send failures are never retried: the original flow has no exception strategy, so the platform default answers
 * them with the exception's message. A {@link MailSendException} (connection refused or timed out, message
 * rejected) is therefore rethrown as a {@code MailSendException} whose message is only {@value #SEND_FAILURE_MESSAGE},
 * with the original as its cause: Spring composes the original's message from the failed messages' exceptions,
 * naming mail and network classes and the SMTP host, port and timeout. An authentication rejection
 * ({@link MailAuthenticationException}, whose message is Spring's fixed {@code Authentication failed}) propagates
 * unchanged.
 *
 * <p>Before a message is built or a connection opened, every setting the send needs must have a non-blank value:
 * {@code smtp.host}, {@code smtp.user} and {@code smtp.password}, the account only while SMTP authentication is on,
 * then {@code mail.from} and {@code mail.to}. Otherwise the send is refused with a {@link MailPreparationException}
 * whose message names each missing key with its environment variable, in that order and never with a value, for
 * example {@code SMTP mail settings are not configured: smtp.host (SMTP_HOST), mail.from (MAIL_FROM)}; being raised
 * before the send, it reaches the caller unchanged. {@code mail.subject} is never required. The host, account and
 * password are read from the injected {@link JavaMailSender} when it is a {@link JavaMailSenderImpl}, which Spring
 * Boot builds from {@code spring.mail.*} bound to {@code smtp.*}: they are the values the transport would connect
 * with, however they were supplied. Authentication is on when that sender's JavaMail session sets
 * {@code mail.<protocol>.auth} to {@code true}, as Angus Mail reads it, so a server used without authentication needs
 * no account. Any other {@code JavaMailSender} exposes no server settings, so only {@code mail.from} and
 * {@code mail.to} are checked for it.
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

    /** Message of the {@link MailSendException} a failed send is rethrown as, the text the caller receives. */
    public static final String SEND_FAILURE_MESSAGE = "Could not send the employees report mail";

    /** Start of the message a send is refused with when settings are missing; the missing keys follow it. */
    private static final String NOT_CONFIGURED_MESSAGE = "SMTP mail settings are not configured: ";

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
     * @throws MailPreparationException before any connection when a required SMTP setting is blank, naming the
     *         missing keys; or when the message cannot be built, for example from an invalid address
     * @throws MailAuthenticationException unchanged, when the server rejects the configured account
     * @throws MailSendException with the message {@value #SEND_FAILURE_MESSAGE} and the original failure as its
     *         cause, when the server cannot be reached or does not accept the message; never retried
     */
    public void send(String csv) {
        Objects.requireNonNull(csv, "csv");
        requireConfiguredSettings();
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
        try {
            mailSender.send(message);
        } catch (MailSendException e) {
            // Spring appends each failed message's exception text to the message, which the platform-default 500
            // reports to the caller; only the fixed text goes on, and the failure stays on the cause chain.
            throw new MailSendException(SEND_FAILURE_MESSAGE, e);
        }
    }

    /**
     * Refuses the send when a required setting is blank (see the class description), naming every missing key and
     * its environment variable in a fixed order and no value.
     *
     * @throws MailPreparationException when at least one required setting is blank
     */
    private void requireConfiguredSettings() {
        List<String> missing = new ArrayList<>();
        if (mailSender instanceof JavaMailSenderImpl sender) {
            if (!StringUtils.hasText(sender.getHost())) {
                missing.add("smtp.host (SMTP_HOST)");
            }
            if (authenticationEnabled(sender)) {
                if (!StringUtils.hasText(sender.getUsername())) {
                    missing.add("smtp.user (SMTP_USER)");
                }
                if (!StringUtils.hasText(sender.getPassword())) {
                    missing.add("smtp.password (SMTP_PASSWORD)");
                }
            }
        }
        if (!StringUtils.hasText(from)) {
            missing.add("mail.from (MAIL_FROM)");
        }
        if (!StringUtils.hasText(to)) {
            missing.add("mail.to (MAIL_TO)");
        }
        if (!missing.isEmpty()) {
            throw new MailPreparationException(NOT_CONFIGURED_MESSAGE + String.join(", ", missing));
        }
    }

    /**
     * Whether the sender's transport authenticates: {@code mail.<protocol>.auth} of its JavaMail session, for the
     * protocol it connects with ({@code mail.transport.protocol}, else {@code smtp}, when it names none), read as
     * Angus Mail reads it, a {@link Boolean} or the text {@code true} in any case.
     */
    private static boolean authenticationEnabled(JavaMailSenderImpl sender) {
        Properties properties = sender.getSession().getProperties();
        String protocol = sender.getProtocol();
        if (protocol == null) {
            protocol = properties.getProperty("mail.transport.protocol", JavaMailSenderImpl.DEFAULT_PROTOCOL);
        }
        String key = "mail." + protocol + ".auth";
        Object value = properties.get(key);
        return value instanceof Boolean flag ? flag : Boolean.parseBoolean(properties.getProperty(key));
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
