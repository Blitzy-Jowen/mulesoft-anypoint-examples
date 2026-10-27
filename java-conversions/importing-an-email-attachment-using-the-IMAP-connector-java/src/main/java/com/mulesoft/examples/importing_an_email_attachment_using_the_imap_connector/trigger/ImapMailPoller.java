/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector.trigger;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import jakarta.activation.DataHandler;
import jakarta.mail.BodyPart;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector.service.AttachmentImportService;

/**
 * Replaces the IMAPS inbound endpoint of the original flow {@code imap-to-csvFlow1} ({@code imap-to-xml.xml}): the
 * {@code imaps:connector} with {@code checkFrequency="100"} and the {@code imaps:inbound-endpoint} on port 993 with
 * the {@code imap.host}, {@code imap.user} and {@code imap.password} placeholders.
 *
 * <p>Each poll follows Mule 3.8's {@code RetrieveMessageReceiver} with the connector defaults the original keeps:
 * folder {@code INBOX} opened read-write; every message that is neither {@code \Deleted} nor {@code \Seen} is copied
 * out of the folder, turned into its attachment map, flagged {@code \Deleted} ({@code deleteReadMessages=true}) and
 * handed to {@link AttachmentImportService}; a failure is logged at ERROR and the next message is processed, as
 * Mule's default exception strategy did; closing the folder expunges the flagged messages. Everything the flow does
 * after the endpoint belongs to the service.
 *
 * <p>An unreachable or rejecting mailbox does not stop the application: each failed poll is logged at ERROR and the
 * next scheduled poll connects again. Every poll opens its own {@link Session}, never the JVM-global default one.
 */
@Component
public class ImapMailPoller {

    private static final Logger log = LoggerFactory.getLogger(ImapMailPoller.class);

    private static final String PROTOCOL = "imaps";

    private final AttachmentImportService attachmentImportService;
    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final String folderName;
    private final Map<String, String> sessionProperties;

    /**
     * @param attachmentImportService the rest of the original flow, run once per received message
     * @param environment             source of {@code imap.properties}, extra jakarta.mail session properties such as
     *                                trust or timeout settings
     * @param host                    the original endpoint's {@code host}, from {@code imap.host}
     * @param port                    the original endpoint's {@code port} (993, implicit TLS), from {@code imap.port}
     * @param user                    the original endpoint's {@code user}, from {@code imap.user}
     * @param password                the original endpoint's {@code password}, from {@code imap.password}
     * @param folderName              the polled folder, from {@code imap.folder}; Mule's default {@code mailboxFolder}
     */
    public ImapMailPoller(AttachmentImportService attachmentImportService, Environment environment,
            @Value("${imap.host:}") String host, @Value("${imap.port:993}") int port,
            @Value("${imap.user:}") String user, @Value("${imap.password:}") String password,
            @Value("${imap.folder:INBOX}") String folderName) {
        this.attachmentImportService = attachmentImportService;
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.folderName = folderName;
        // An empty YAML map (imap.properties: {}) contributes no property, so it binds as absent.
        this.sessionProperties = Map.copyOf(Binder.get(environment)
                .bind("imap.properties", Bindable.mapOf(String.class, String.class))
                .orElseGet(Map::of));
    }

    /**
     * The scheduled poll: a fixed delay between the end of one poll and the start of the next, as the original
     * connector's {@code checkFrequency} gave its polling receiver.
     */
    @Scheduled(fixedDelayString = "${imap.check-frequency:100}", initialDelayString = "${imap.initial-delay:0}")
    public void poll() {
        pollOnce();
    }

    /**
     * Runs one poll of the mailbox. Synchronized so the scheduled poll and a direct call never overlap. Never
     * throws: connection, folder and message failures are logged.
     *
     * @return the number of messages handed to {@link AttachmentImportService#importAttachments(Map)}, whether or
     *         not the import succeeded
     */
    public synchronized int pollOnce() {
        Store store = null;
        Folder folder = null;
        try {
            try {
                Session session = Session.getInstance(sessionProperties());
                store = session.getStore(PROTOCOL);
                store.connect(host, port, user, password);
                folder = store.getFolder(folderName);
                folder.open(Folder.READ_WRITE);
            } catch (MessagingException | RuntimeException e) {
                log.error("Failed to connect to IMAP folder {} on {}:{}", folderName, host, port, e);
                return 0;
            }

            Message[] messages;
            try {
                messages = folder.getMessages();
            } catch (MessagingException | RuntimeException e) {
                log.error("Failed to read the messages of IMAP folder {} on {}:{}", folderName, host, port, e);
                return 0;
            }

            int processed = 0;
            for (Message message : messages) {
                if (process(message)) {
                    processed++;
                }
            }
            return processed;
        } finally {
            close(folder, store);
        }
    }

    private Properties sessionProperties() {
        Properties properties = new Properties();
        properties.setProperty("mail.store.protocol", PROTOCOL);
        properties.setProperty("mail.imaps.host", host);
        properties.setProperty("mail.imaps.port", String.valueOf(port));
        properties.putAll(sessionProperties);
        return properties;
    }

    /**
     * Processes one folder message the way {@code RetrieveMessageReceiver.messagesAdded} did. The original is
     * flagged deleted after its attachments are read and before the service runs, so a message whose import fails
     * is deleted all the same; a message whose copy or attachment map fails is left unflagged, and the server's
     * {@code \Seen} flag, set by fetching its body, keeps the next poll from taking it again.
     *
     * @return whether the service was invoked for the message
     */
    private boolean process(Message message) {
        boolean invoked = false;
        try {
            Flags flags = message.getFlags();
            if (flags.contains(Flags.Flag.DELETED) || flags.contains(Flags.Flag.SEEN)) {
                return false;
            }
            MimeMessage copy = new MimeMessage((MimeMessage) message);
            Map<String, DataHandler> attachments = attachmentsOf(copy);
            message.setFlag(Flags.Flag.DELETED, true);
            invoked = true;
            attachmentImportService.importAttachments(attachments);
        } catch (Exception e) {
            log.error("Error processing IMAP message {}", message.getMessageNumber(), e);
        }
        return invoked;
    }

    /**
     * Builds a received message's inbound attachments as Mule 3.8's {@code MailMuleMessageFactory.addAttachments}
     * did: every leaf part of a multipart message (body text parts included) collected by name in a sorted map, then
     * added in that order to the attachment map.
     *
     * @param message the received message, detached from its folder
     * @return the attachments by name; empty for a message that is not multipart. A {@link HashMap}, as Mule's
     *         inbound-attachment map was, so its iteration order is the order the original's attachment list had
     * @throws MessagingException when the message structure cannot be read
     * @throws IOException        when the message content cannot be read
     */
    static Map<String, DataHandler> attachmentsOf(MimeMessage message) throws MessagingException, IOException {
        Map<String, DataHandler> attachments = new HashMap<>();
        if (!(message.getContent() instanceof Multipart multipart)) {
            return attachments;
        }
        TreeMap<String, Part> parts = new TreeMap<>();
        collect(multipart, parts);
        for (Map.Entry<String, Part> entry : parts.entrySet()) {
            attachments.put(entry.getKey(), entry.getValue().getDataHandler());
        }
        return attachments;
    }

    /**
     * Mule 3.8's {@code MailUtils.getAttachments}: recurses into {@code multipart/mixed} parts and names every other
     * part by its description, decoded file name, disposition or running index, in that order of preference. The
     * index counter is local to each multipart level, as in the original.
     */
    private static void collect(Multipart multipart, Map<String, Part> result) throws MessagingException, IOException {
        int index = 0;
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            if (part.getContentType().contains("multipart/mixed")) {
                collect((Multipart) part.getContent(), result);
                continue;
            }
            String key;
            if (isNotEmpty(part.getDescription())) {
                key = part.getDescription();
            } else if (isNotEmpty(part.getFileName())) {
                key = decodedFileName(part.getFileName());
            } else if (isNotEmpty(part.getDisposition())) {
                key = part.getDisposition();
            } else {
                key = String.valueOf(index++);
            }
            // Duplicate names get Mule's "<index>_" prefix; the exact keys Mule produced here are a Tier 2A
            // verification point.
            if (result.containsKey(key)) {
                key = String.valueOf(index++) + "_" + key;
            }
            result.put(key, part);
        }
    }

    private static String decodedFileName(String fileName) {
        try {
            return MimeUtility.decodeText(fileName);
        } catch (UnsupportedEncodingException e) {
            return fileName;
        }
    }

    private static boolean isNotEmpty(String value) {
        return value != null && !value.isEmpty();
    }

    /** Closes the folder with expunge, removing the messages flagged deleted, then the store. */
    private void close(Folder folder, Store store) {
        if (folder != null && folder.isOpen()) {
            try {
                folder.close(true);
            } catch (MessagingException | RuntimeException e) {
                log.warn("Failed to close IMAP folder {} on {}:{}", folderName, host, port, e);
            }
        }
        if (store != null && store.isConnected()) {
            try {
                store.close();
            } catch (MessagingException | RuntimeException e) {
                log.warn("Failed to close the IMAP store for {}:{}", host, port, e);
            }
        }
    }
}
