/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.trigger;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.service.AttachmentImportService;

import jakarta.activation.DataHandler;
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
 *       message's inbound attachments as a {@code List<DataHandler>}, built as Mule 3.8 built them
 *       ({@code attachmentsOf}).</li>
 * </ul>
 * Everything after l.9 belongs to {@link AttachmentImportService#process(List)}; this class only binds the mail to
 * that input.
 *
 * <p>Attachments: Mule 3.8's {@code MailMuleMessageFactory.addAttachments} took every leaf part of a multipart
 * message as an attachment, body text included, recursing only into {@code multipart/mixed} parts. It named each
 * part by {@code MailUtils.getAttachments} (description, else decoded file name, else disposition, else a running
 * index local to its multipart level), made a repeated name unique with {@code MailUtils.getAttachmentName} (the first
 * free {@code <n>_<name>} for n = 0, 1, 2, ...), collected the parts in a {@link TreeMap} and added them in its key
 * order to the message's {@link HashMap} of inbound attachments ({@code DefaultMuleMessage}). The wildcard
 * {@code attachments-list} returned them in that {@link HashMap}'s key iteration order, so {@code payload[0]} is the
 * first entry of that order, which neither MIME order nor sorted name order predicts. This class reproduces that
 * naming and both maps exactly. No part content is read here, only the structure of nested {@code multipart/mixed}
 * parts: the service reads the content of the first attachment alone, so a later part that cannot be decoded never
 * fails the message.
 *
 * <p>Disposition, after Mule 3.8's {@code RetrieveMessageReceiver.messagesAdded} with the POP3 connector's
 * {@code deleteReadMessages} default {@code true}: each message is first copied out of the folder and its attachment
 * list built; only then is it flagged {@link Flags.Flag#DELETED}, and only a flagged message reaches the service.
 * A message that cannot be copied or whose attachment list cannot be built (malformed MIME, for example) is logged at
 * ERROR, left unflagged and so kept in the mailbox for the next poll. A message whose flag cannot be set is logged
 * at WARN and not processed. The flow declares no exception strategy, so a failure of the service is logged at ERROR
 * by Mule's default strategy and the message stays consumed. Flagged messages are removed when the folder is closed
 * with expunge. Mule 3.8.0 abandoned the rest of a poll's messages after a construction failure; here every message
 * is handled on its own, so one unreadable mail never holds back the mails behind it. A mailbox that cannot be
 * reached or authenticated is logged at WARN and retried by the next poll.
 *
 * <p>Diagnostics carry a fixed operation text, the message's ordinal in the poll, the configured mailbox or folder
 * where relevant, and the class names of the exception and its causes ({@code exceptionTypes}). They never
 * carry mail content such as the Subject, an exception message or a stack trace, because those hold sender- or
 * server-controlled text; class names hold no line break, so a log line cannot be forged through them. The only line
 * with mail content is the flow's own {@code Received:} logger in the service.
 */
@Component
public class Pop3MailPoller {

    private static final Logger log = LoggerFactory.getLogger(Pop3MailPoller.class);

    private static final String PROTOCOL = "pop3s";

    /** The content type whose parts Mule's {@code MailUtils.getAttachments} descended into instead of naming. */
    private static final String MULTIPART_MIXED = "multipart/mixed";

    /** The most exceptions of a cause chain {@link #exceptionTypes(Throwable)} names. */
    private static final int MAX_CAUSE_DEPTH = 16;

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
     * Runs one poll: connects to the mailbox and processes every message, in mailbox order, as
     * {@code process(Message, int)} describes. This is the single-run method that tests and fixture replay call on
     * demand; it is {@code synchronized}, so an on-demand run and a scheduled run never process the same mailbox at
     * the same time.
     *
     * @return the number of messages consumed (flagged deleted), counting those whose processing failed with a
     *         handled exception but not those whose copy or attachment list could not be built, which stay in the
     *         mailbox; {@code 0} when {@code pop3.host} is blank or the mailbox cannot be opened
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
                if (process(messages[i], i + 1)) {
                    consumed++;
                }
            }
        } catch (MessagingException e) {
            log.warn("Cannot poll POP3S mailbox {}:{}: {}", host, port, exceptionTypes(e));
        } finally {
            closeFolder(folder);
            closeStore(store);
        }
        return consumed;
    }

    /**
     * Processes one folder message in the order of Mule 3.8's {@code RetrieveMessageReceiver.messagesAdded}: copy the
     * message out of the folder and build its attachment list, flag the original {@link Flags.Flag#DELETED}, then run
     * the flow. Each step's failure is logged and ends the message's processing without affecting the next message.
     *
     * @param message the folder message
     * @param number  the message's ordinal in this poll, the only message detail the diagnostics carry
     * @return whether the message was flagged deleted
     */
    private boolean process(Message message, int number) {
        List<DataHandler> attachments;
        try {
            MimeMessage copy = new MimeMessage((MimeMessage) message);
            attachments = attachmentsOf(copy);
        } catch (Exception e) {
            log.error("Cannot read POP3 message {}; it stays in the mailbox: {}", number, exceptionTypes(e));
            return false;
        }

        try {
            message.setFlag(Flags.Flag.DELETED, true);
        } catch (MessagingException e) {
            log.warn("Cannot mark POP3 message {} for deletion; it is not processed: {}", number, exceptionTypes(e));
            return false;
        }

        try {
            attachmentImportService.process(attachments);
        } catch (Exception e) {
            log.error("Error processing POP3 message {}: {}", number, exceptionTypes(e));
        }
        return true;
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
     * Builds a received message's attachment list as Mule 3.8's {@code MailMuleMessageFactory.addAttachments} and the
     * wildcard {@code attachments-list} expression did: the parts named by {@link #collect(Multipart, Map)} in a
     * {@link TreeMap}, added in its key order to a {@link HashMap} of {@link DataHandler}s, Mule's inbound-attachment
     * map type, whose values are returned in that map's iteration order. Reads the message content only to learn
     * whether it is multipart; part content is never read.
     *
     * @param message the received message, detached from its folder
     * @return the attachments in Mule's attachment-list order; empty for a message that is not multipart
     * @throws MessagingException when the message structure cannot be parsed
     * @throws IOException        when the message content cannot be read
     * @throws ClassCastException when a {@code multipart/mixed} part does not hold a multipart, as Mule's cast failed
     */
    private static List<DataHandler> attachmentsOf(MimeMessage message) throws MessagingException, IOException {
        if (!(message.getContent() instanceof Multipart multipart)) {
            return new ArrayList<>();
        }
        TreeMap<String, Part> parts = new TreeMap<>();
        collect(multipart, parts);
        Map<String, DataHandler> attachments = new HashMap<>();
        for (Map.Entry<String, Part> entry : parts.entrySet()) {
            attachments.put(entry.getKey(), entry.getValue().getDataHandler());
        }
        return new ArrayList<>(attachments.values());
    }

    /**
     * Mule 3.8's {@code MailUtils.getAttachments}: descends into every part whose content type contains
     * {@code multipart/mixed} and names every other part by its description, else its decoded file name, else its
     * disposition, else a running index that is local to each multipart level, then makes the name unique as
     * {@link #uniqueName(String, Map)} does.
     */
    private static void collect(Multipart multipart, Map<String, Part> attachments)
            throws MessagingException, IOException {
        int index = 0;
        for (int i = 0; i < multipart.getCount(); i++) {
            Part part = multipart.getBodyPart(i);
            if (part.getContentType().contains(MULTIPART_MIXED)) {
                collect((Multipart) part.getContent(), attachments);
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
            attachments.put(uniqueName(key, attachments), part);
        }
    }

    /**
     * Mule 3.8.0's {@code MailUtils.getAttachmentName}: a name already taken becomes the first {@code <n>_<name>},
     * for n = 0, 1, 2, ..., that is still free.
     */
    private static String uniqueName(String key, Map<String, Part> attachments) {
        if (!attachments.containsKey(key)) {
            return key;
        }
        int n = 0;
        while (attachments.containsKey(n + "_" + key)) {
            n++;
        }
        return n + "_" + key;
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

    /**
     * Names an exception for a diagnostic: the fully qualified class names of {@code e} and of each of its causes,
     * joined with {@code " caused by "}. A Jakarta Mail {@link MessagingException} reports its next exception as its
     * cause, so a failed TLS connect reads, for example, {@code jakarta.mail.MessagingException caused by
     * javax.net.ssl.SSLHandshakeException caused by ...}. Messages are left out, since they can carry mail or server
     * text. A cause already named, or one beyond {@value #MAX_CAUSE_DEPTH} names, ends the chain.
     */
    private static String exceptionTypes(Throwable e) {
        StringJoiner types = new StringJoiner(" caused by ");
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = e; t != null && seen.size() < MAX_CAUSE_DEPTH && seen.add(t); t = t.getCause()) {
            types.add(t.getClass().getName());
        }
        return types.toString();
    }

    private void closeFolder(Folder folder) {
        if (folder != null && folder.isOpen()) {
            try {
                // Expunge: on POP3 the messages flagged DELETED are removed at QUIT.
                folder.close(true);
            } catch (MessagingException e) {
                log.warn("Cannot close POP3 folder {}: {}", folderName, exceptionTypes(e));
            }
        }
    }

    private void closeStore(Store store) {
        if (store != null) {
            try {
                store.close();
            } catch (MessagingException e) {
                log.warn("Cannot close POP3S store {}:{}: {}", host, port, exceptionTypes(e));
            }
        }
    }
}
