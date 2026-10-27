/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.trigger;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.service.AttachmentImportService;

import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;

/**
 * The inbound side of the original flow {@code pop-to-xmlFlow1}
 * ({@code importing-an-email-attachment-using-the-POP3-connector/src/main/app/pop-to-xml.xml}):
 * <ul>
 *   <li>l.3, {@code <pop3s:connector checkFrequency="100" validateConnections="true"/>}: the scheduled poll, with
 *       {@code pop3.check-frequency} milliseconds between the end of one poll and the start of the next;</li>
 *   <li>l.6, {@code <pop3s:inbound-endpoint host="${pop3.host}" port="995" user="${pop3.user}"
 *       password="${pop3.password}" responseTimeout="10000"/>}: a Jakarta Mail {@code pop3s} {@link Store}, opened
 *       for each poll on {@code pop3.host}:{@code pop3.port} with {@code pop3.response-timeout} as its connect and
 *       read timeout;</li>
 *   <li>l.7-9, {@code <expression-transformer><return-argument evaluator="attachments-list" expression="*"/>}: the
 *       decoded bytes of every attachment part of the message, in MIME order, as a {@code List<byte[]>}.</li>
 * </ul>
 * Everything after l.9 belongs to {@link AttachmentImportService#process(List)}; this class only binds the mail to
 * that input.
 *
 * <p>Disposition: the flow declares no exception strategy, so Mule's default strategy applied. A message whose
 * processing fails is logged at ERROR with its stack trace and is consumed like a processed one: every polled message
 * is flagged {@link Flags.Flag#DELETED} (the POP3 connector's {@code deleteReadMessages} default {@code true}) and
 * removed when the folder is closed with expunge. A mailbox that cannot be reached or authenticated is logged at WARN
 * and retried by the next poll.
 *
 * <p>Tier 2A verification points: attachment order and the failure disposition. Mule 3's
 * {@code MailUtils.getAttachments} treated every leaf part as an attachment and keyed it by description, file name or
 * disposition in a sorted map, so for mails with several parts the order of {@code payload[0]} and which part it
 * selects are confirmed only by Tier 2A capture against the running original. The single-attachment mail of the
 * original test yields the same one entry either way.
 */
@Component
public class Pop3MailPoller {

    private static final Logger log = LoggerFactory.getLogger(Pop3MailPoller.class);

    private static final String PROTOCOL = "pop3s";

    private final AttachmentImportService attachmentImportService;
    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final String folderName;
    private final int responseTimeout;
    private final String sslTrust;

    /**
     * Creates the poller.
     *
     * @param attachmentImportService the flow body each polled message's attachments are handed to
     * @param host                    the endpoint's {@code host}; blank disables polling
     * @param port                    the endpoint's {@code port}
     * @param user                    the mailbox user
     * @param password                the mailbox password; never logged
     * @param folderName              the POP3 folder to read, always {@code INBOX} on POP3
     * @param responseTimeout         the endpoint's {@code responseTimeout}, used as connect and read timeout in ms
     * @param sslTrust                hosts whose certificate is trusted without verification ({@code *} for the
     *                                self-signed test server); blank keeps default TLS verification
     */
    public Pop3MailPoller(AttachmentImportService attachmentImportService,
                          @Value("${pop3.host:}") String host,
                          @Value("${pop3.port:995}") int port,
                          @Value("${pop3.user:}") String user,
                          @Value("${pop3.password:}") String password,
                          @Value("${pop3.folder:INBOX}") String folderName,
                          @Value("${pop3.response-timeout:10000}") int responseTimeout,
                          @Value("${pop3.ssl-trust:}") String sslTrust) {
        this.attachmentImportService = attachmentImportService;
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.folderName = folderName;
        this.responseTimeout = responseTimeout;
        this.sslTrust = sslTrust;
    }

    /**
     * Scheduled poll. A fixed delay keeps {@code checkFrequency} as the pause between the end of one poll and the
     * start of the next, so polls never overlap. The initial delay reads the same key so that test runs, which set
     * {@code pop3.check-frequency} very high, never have a background poll consume a mail a test has just delivered.
     */
    @Scheduled(fixedDelayString = "${pop3.check-frequency:100}", initialDelayString = "${pop3.check-frequency:100}")
    public void poll() {
        pollOnce();
    }

    /**
     * Runs one poll: connects to the mailbox, hands the attachments of every message, in mailbox order, to
     * {@link AttachmentImportService#process(List)} and consumes each message. This is the single-run method that
     * tests and fixture replay call on demand; it is {@code synchronized}, so an on-demand run and a scheduled run
     * never process the same mailbox at the same time.
     *
     * @return the number of messages consumed (flagged deleted), counting those whose processing failed with a
     *         handled exception; {@code 0} when {@code pop3.host} is blank or the mailbox cannot be opened
     */
    public synchronized int pollOnce() {
        if (host == null || host.isBlank()) {
            log.debug("pop3.host is not configured; POP3S poll skipped");
            return 0;
        }

        Store store = null;
        Folder folder = null;
        int consumed = 0;
        try {
            Session session = Session.getInstance(sessionProperties());
            store = session.getStore(PROTOCOL);
            store.connect(host, port, user, password);
            folder = store.getFolder(folderName);
            folder.open(Folder.READ_WRITE);
            Message[] messages = folder.getMessages();

            for (int i = 0; i < messages.length; i++) {
                Message message = messages[i];
                int number = i + 1;
                try {
                    List<byte[]> attachments = collectAttachments(message);
                    attachmentImportService.process(attachments);
                } catch (Exception e) {
                    log.error("Error processing POP3 message {} (subject '{}'): {}",
                            number, subjectOf(message), e.getMessage(), e);
                }
                try {
                    message.setFlag(Flags.Flag.DELETED, true);
                    consumed++;
                } catch (MessagingException e) {
                    log.warn("Cannot mark POP3 message {} for deletion: {}", number, e.getMessage());
                }
            }
        } catch (MessagingException e) {
            log.warn("Cannot poll POP3S mailbox {}:{}: {}", host, port, e.getMessage());
        } finally {
            closeFolder(folder);
            closeStore(store);
        }
        return consumed;
    }

    private Properties sessionProperties() {
        Properties props = new Properties();
        props.setProperty("mail.store.protocol", PROTOCOL);
        props.setProperty("mail.pop3s.host", host);
        props.setProperty("mail.pop3s.port", String.valueOf(port));
        props.setProperty("mail.pop3s.connectiontimeout", String.valueOf(responseTimeout));
        props.setProperty("mail.pop3s.timeout", String.valueOf(responseTimeout));
        if (sslTrust != null && !sslTrust.isBlank()) {
            props.setProperty("mail.pop3s.ssl.trust", sslTrust);
            props.setProperty("mail.pop3s.ssl.checkserveridentity", "false");
        }
        return props;
    }

    /**
     * Collects the decoded content of every attachment under {@code part}, in MIME order. A multipart is walked
     * depth-first; a leaf counts as an attachment when it names a file or declares an {@code attachment} disposition,
     * so body text parts are skipped.
     */
    private List<byte[]> collectAttachments(Part part) throws MessagingException, IOException {
        List<byte[]> attachments = new ArrayList<>();
        if (part.isMimeType("multipart/*") && part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                attachments.addAll(collectAttachments(multipart.getBodyPart(i)));
            }
            return attachments;
        }
        String fileName = part.getFileName();
        if ((fileName != null && !fileName.isBlank()) || Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
            try (InputStream content = part.getInputStream()) {
                attachments.add(content.readAllBytes());
            }
        }
        return attachments;
    }

    private static String subjectOf(Message message) {
        try {
            String subject = message.getSubject();
            return subject == null ? "" : subject;
        } catch (MessagingException e) {
            return "<unknown>";
        }
    }

    private void closeFolder(Folder folder) {
        if (folder != null && folder.isOpen()) {
            try {
                // Expunge: on POP3 the messages flagged DELETED are removed at QUIT.
                folder.close(true);
            } catch (MessagingException e) {
                log.warn("Cannot close POP3 folder {}: {}", folderName, e.getMessage());
            }
        }
    }

    private void closeStore(Store store) {
        if (store != null) {
            try {
                store.close();
            } catch (MessagingException e) {
                log.warn("Cannot close POP3S store {}:{}: {}", host, port, e.getMessage());
            }
        }
    }
}
