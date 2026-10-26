/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.client;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Outbound SMTP adapter that mails the processed order lines, replacing two elements of
 * {@code sending-a-csv-file-through-email-using-smtp/src/main/app/csv-to-smtp.xml}:
 * <ul>
 *   <li>line 3, {@code smtp:gmail-connector}: its authenticated STARTTLS transport is configured on the
 *       {@link JavaMailSender} through {@code spring.mail.*} in {@code application.yml};</li>
 *   <li>line 20, {@code smtp:outbound-endpoint}: sender, recipients and subject come from {@code mail.from},
 *       {@code mail.to} and {@code mail.subject}; the server host, the endpoint's fixed port, the account, its
 *       password and the endpoint's 10000 ms response timeout are {@code spring.mail.*} settings bound from
 *       {@code smtp.*}, so this class holds no host, port, address or credential and no caller can override them.</li>
 * </ul>
 *
 * <p>The message is a single-part {@code text/plain; charset=UTF-8} body holding {@link String#valueOf(Object)} of the
 * payload, which is how the Mule SMTP transport rendered a non-string payload: the order lines arrive as a
 * {@code java.util} list of maps, and their {@code toString()} rendering is the mail body the original test compares
 * with {@code email.txt}. It is deliberately not multipart, has no attachment and no extra header, so a reader's
 * {@code getContent()} returns that text as a {@code String}.
 *
 * <p>Failures are never retried or swallowed: the original flow has no exception strategy, so they propagate to the
 * caller and the file poller's default handling.
 */
@Component
public class CsvMailSender {

    /** Encoding of the subject and the text body; Mule's default output encoding. */
    private static final String CHARSET = "UTF-8";

    private final JavaMailSender mailSender;

    private final String from;

    private final String to;

    private final String subject;

    public CsvMailSender(JavaMailSender mailSender,
                         @Value("${mail.from}") String from,
                         @Value("${mail.to}") String to,
                         @Value("${mail.subject}") String subject) {
        this.mailSender = mailSender;
        this.from = from;
        this.to = to;
        this.subject = subject;
    }

    /**
     * Mails {@code String.valueOf(payload)} as the plain-text body to the configured recipients.
     *
     * @param payload the flow payload, the order lines produced by the CSV mapper
     * @throws MailPreparationException when the message cannot be built, for example from an empty or invalid
     *         {@code mail.from} or a malformed {@code mail.to}
     * @throws MailException when the send fails (authentication, connection, timeout, rejection, or no recipient
     *         because {@code mail.to} is empty)
     */
    public void send(Object payload) {
        String body = String.valueOf(payload);
        MimeMessage message;
        try {
            message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, CHARSET);
            helper.setFrom(from);
            // InternetAddress.parse accepts a comma-separated list, as the endpoint's "to" attribute did.
            helper.setTo(InternetAddress.parse(to));
            helper.setSubject(subject);
            helper.setText(body, false);
        } catch (MessagingException e) {
            throw new MailPreparationException("Failed to prepare mail message", e);
        }
        mailSender.send(message);
    }
}
