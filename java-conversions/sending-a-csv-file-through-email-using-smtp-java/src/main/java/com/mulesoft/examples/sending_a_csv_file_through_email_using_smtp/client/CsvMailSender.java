/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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
 *
 * <p>Before a message is built or a connection opened, every setting the send needs must have a non-blank value:
 * {@code smtp.host}, {@code smtp.user} and {@code smtp.password}, the account only while SMTP authentication is on,
 * then {@code mail.from} and {@code mail.to}. Otherwise the send is refused with a {@link MailPreparationException}
 * whose message names each missing key with its environment variable, in that order and never with a value, for
 * example {@code SMTP mail settings are not configured: smtp.host (SMTP_HOST), mail.from (MAIL_FROM)}, and the same
 * text is logged at ERROR on one line, because the file poller logs only the exception's class and throw site.
 * {@code mail.subject} is never required. The host, account and password are read from the injected
 * {@link JavaMailSender} when it is a {@link JavaMailSenderImpl}, which Spring Boot builds from {@code spring.mail.*}
 * bound to {@code smtp.*}: they are the values the transport would connect with, however they were supplied.
 * Authentication is on when that sender's JavaMail session sets {@code mail.<protocol>.auth} to {@code true}, as
 * Angus Mail reads it, so a server used without authentication needs no account. Any other {@code JavaMailSender}
 * exposes no server settings, so only {@code mail.from} and {@code mail.to} are checked for it.
 */
@Component
public class CsvMailSender {

    private static final Logger LOG = LoggerFactory.getLogger(CsvMailSender.class);

    /** Encoding of the subject and the text body; Mule's default output encoding. */
    private static final String CHARSET = "UTF-8";

    /** Start of the message a send is refused with when settings are missing; the missing keys follow it. */
    private static final String NOT_CONFIGURED_MESSAGE = "SMTP mail settings are not configured: ";

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
     * @throws MailPreparationException before any connection when a required SMTP setting is blank, naming the
     *         missing keys; or when the message cannot be built, for example from a malformed {@code mail.from} or
     *         {@code mail.to}
     * @throws MailException when the send fails (authentication, connection, timeout or rejection)
     */
    public void send(Object payload) {
        requireConfiguredSettings();
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

    /**
     * Refuses the send when a required setting is blank (see the class description), naming every missing key and
     * its environment variable in a fixed order and no value, in the exception and in one ERROR line.
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
            String message = NOT_CONFIGURED_MESSAGE + String.join(", ", missing);
            LOG.error("Order lines mail not sent. {}", message);
            throw new MailPreparationException(message);
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
}
