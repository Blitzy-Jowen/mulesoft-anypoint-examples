package com.mulesoft.examples.scatter_gather_flow_control.client;

import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Outbound SMTP adapter replacing the {@code outboundFlow} sub-flow of
 * {@code scatter-gather-flow-control/src/main/app/scatter-gather.xml} (lines 61-66): the CSV report is attached as
 * {@code aggregation-report} ({@code text/plain}), the payload becomes the fixed body text, the mail is sent through
 * the Gmail SMTP connector, and the attachment is discarded with the message.
 *
 * <p>The message is laid out as Mule 3.8's {@code ObjectToMimeMessage} lays out a payload with one outbound
 * attachment: a {@code multipart/mixed} whose first part is the payload (description {@code Payload}) and whose
 * second part is the attachment, named and described by its attachment name.
 *
 * <p>SMTP host, port, account and the connector's STARTTLS, authentication and timeout settings reach the
 * {@link JavaMailSender} through {@code spring.mail.*}; the endpoint's {@code from}, {@code to} and {@code subject} are
 * read here from {@code mail.from}, {@code mail.to} and {@code mail.subject}, whose default is the flow's subject
 * literal {@link #DEFAULT_SUBJECT}. The subject is sent as configured, an empty one included. Send failures
 * ({@link org.springframework.mail.MailException}) propagate unchanged, because the original flow has no exception
 * strategy and the platform default answers them.
 *
 * <p>Before a message is built or a connection opened, every setting the send needs must have a non-blank value:
 * {@code smtp.host}, {@code smtp.user} and {@code smtp.password}, the account only while SMTP authentication is on,
 * then {@code mail.from} and {@code mail.to}. Otherwise the send is refused with a {@link MailPreparationException}
 * whose message names each missing key with its environment variable, in that order and never with a value, for
 * example {@code SMTP mail settings are not configured: smtp.host (SMTP_HOST), mail.from (MAIL_FROM)}.
 * {@code mail.subject} is never required. The host, account and password are read from the injected
 * {@link JavaMailSender} when it is a {@link JavaMailSenderImpl}, which Spring Boot builds from {@code spring.mail.*}
 * bound to {@code smtp.*}: they are the values the transport would connect with, however they were supplied.
 * Authentication is on when that sender's JavaMail session sets {@code mail.<protocol>.auth} to {@code true}, as
 * Angus Mail reads it, so a server used without authentication needs no account. Any other {@code JavaMailSender}
 * exposes no server settings, so only {@code mail.from} and {@code mail.to} are checked for it.
 */
@Component
public class AggregationReportMailer {

    /** Attachment name of {@code set-attachment} (scatter-gather.xml:62). */
    public static final String ATTACHMENT_NAME = "aggregation-report";

    /** Attachment content type of {@code set-attachment} (scatter-gather.xml:62). */
    public static final String ATTACHMENT_CONTENT_TYPE = "text/plain";

    /** Payload set before the send (scatter-gather.xml:63); the mail body and the flow's resulting payload. */
    public static final String BODY_TEXT = "Please find attached your Aggregation Report";

    /**
     * Subject literal of {@code smtp:outbound-endpoint} (scatter-gather.xml:64), the default of {@code mail.subject}
     * in {@code application.yml}.
     */
    public static final String DEFAULT_SUBJECT = "Scatter gather example - aggregation";

    private static final String PAYLOAD_PART_DESCRIPTION = "Payload";

    private static final String BODY_CONTENT_TYPE = "text/plain; charset=UTF-8";

    /** Start of the message a send is refused with when settings are missing; the missing keys follow it. */
    private static final String NOT_CONFIGURED_MESSAGE = "SMTP mail settings are not configured: ";

    private final JavaMailSender mailSender;

    private final String from;

    private final String to;

    private final String subject;

    /**
     * @param mailSender the sender {@code spring.mail.*} configures
     * @param from {@code mail.from}, the sender address
     * @param to {@code mail.to}, the recipient address or a comma-separated list
     * @param subject {@code mail.subject}, by default {@link #DEFAULT_SUBJECT}; sent as given, an empty one included
     */
    public AggregationReportMailer(JavaMailSender mailSender,
                                   @Value("${mail.from}") String from,
                                   @Value("${mail.to}") String to,
                                   @Value("${mail.subject}") String subject) {
        this.mailSender = mailSender;
        this.from = from;
        this.to = to;
        this.subject = subject;
    }

    /**
     * Mails the report as the {@code aggregation-report} attachment and returns the payload the original flow
     * carried after the send.
     *
     * @param report the CSV aggregation report
     * @return {@link #BODY_TEXT}
     * @throws MailPreparationException before any connection when a required SMTP setting is blank, naming the
     *         missing keys; or when the message cannot be built, for example from an invalid address
     * @throws org.springframework.mail.MailException when the SMTP send fails
     */
    public String send(String report) {
        requireConfiguredSettings();
        MimeMessage message = mailSender.createMimeMessage();
        try {
            message.setFrom(new InternetAddress(from));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            message.setSubject(subject);
            message.setSentDate(new Date());

            MimeBodyPart payloadPart = new MimeBodyPart();
            payloadPart.setDataHandler(new DataHandler(
                    new ByteArrayDataSource(BODY_TEXT.getBytes(StandardCharsets.UTF_8), BODY_CONTENT_TYPE)));
            payloadPart.setDescription(PAYLOAD_PART_DESCRIPTION);

            ByteArrayDataSource reportSource = new ByteArrayDataSource(
                    String.valueOf(report).getBytes(StandardCharsets.UTF_8), ATTACHMENT_CONTENT_TYPE);
            reportSource.setName(ATTACHMENT_NAME);
            MimeBodyPart attachmentPart = new MimeBodyPart();
            attachmentPart.setDataHandler(new DataHandler(reportSource));
            attachmentPart.setDescription(ATTACHMENT_NAME);
            attachmentPart.setFileName(ATTACHMENT_NAME);

            MimeMultipart multipart = new MimeMultipart("mixed");
            multipart.addBodyPart(payloadPart);
            multipart.addBodyPart(attachmentPart);
            message.setContent(multipart);
            message.saveChanges();
        } catch (MessagingException e) {
            throw new MailPreparationException("Could not build the aggregation report mail: " + e.getMessage(), e);
        }
        mailSender.send(message);
        return BODY_TEXT;
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
}
