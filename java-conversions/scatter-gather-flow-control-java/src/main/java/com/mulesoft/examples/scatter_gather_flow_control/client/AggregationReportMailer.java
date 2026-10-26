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
import java.util.Date;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

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
 * {@link JavaMailSender} through {@code spring.mail.*}; only the endpoint's {@code from} and {@code to} are read here.
 * Send failures ({@link org.springframework.mail.MailException}) propagate unchanged, because the original flow has
 * no exception strategy and the platform default answers them.
 */
@Component
public class AggregationReportMailer {

    /** Attachment name of {@code set-attachment} (scatter-gather.xml:62). */
    public static final String ATTACHMENT_NAME = "aggregation-report";

    /** Attachment content type of {@code set-attachment} (scatter-gather.xml:62). */
    public static final String ATTACHMENT_CONTENT_TYPE = "text/plain";

    /** Payload set before the send (scatter-gather.xml:63); the mail body and the flow's resulting payload. */
    public static final String BODY_TEXT = "Please find attached your Aggregation Report";

    /** Subject literal of {@code smtp:outbound-endpoint} (scatter-gather.xml:64); the flow reads no subject key. */
    public static final String SUBJECT = "Scatter gather example - aggregation";

    private static final String PAYLOAD_PART_DESCRIPTION = "Payload";

    private static final String BODY_CONTENT_TYPE = "text/plain; charset=UTF-8";

    private final JavaMailSender mailSender;

    private final String from;

    private final String to;

    public AggregationReportMailer(JavaMailSender mailSender,
                                   @Value("${mail.from}") String from,
                                   @Value("${mail.to}") String to) {
        this.mailSender = mailSender;
        this.from = from;
        this.to = to;
    }

    /**
     * Mails the report as the {@code aggregation-report} attachment and returns the payload the original flow
     * carried after the send.
     *
     * @param report the CSV aggregation report
     * @return {@link #BODY_TEXT}
     * @throws MailPreparationException when the message cannot be built, for example from an invalid address
     * @throws org.springframework.mail.MailException when the SMTP send fails
     */
    public String send(String report) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            message.setFrom(new InternetAddress(from));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            message.setSubject(SUBJECT);
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
}
