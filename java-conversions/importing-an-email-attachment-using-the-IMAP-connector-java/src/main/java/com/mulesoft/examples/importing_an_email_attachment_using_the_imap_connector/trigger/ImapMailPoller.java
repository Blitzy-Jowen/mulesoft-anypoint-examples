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
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.regex.Pattern;

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

import org.eclipse.angus.mail.iap.ProtocolException;
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
 * Its connection has a connect, a read and a write deadline ({@code imap.connect-timeout}, {@code imap.read-timeout},
 * {@code imap.write-timeout}, 10 s each), so a server that stops answering during the TLS handshake, the greeting,
 * LOGIN, SELECT or a FETCH ends that step with a timeout, handled as any other failure of it, instead of holding the
 * synchronized poll and every later one; no operation is retried.
 *
 * <p>A null or blank {@code imap.host}, {@code imap.user} or {@code imap.password} leaves the mailbox unconfigured:
 * every poll is skipped before a {@link Session} is opened, so no connection is attempted. The first skipped poll
 * logs one WARN naming each missing key with its environment variable, for example
 * {@code imap.host (IMAP_HOST) is not configured; IMAPS polling is skipped}, and every skipped poll logs a DEBUG
 * line; neither carries a configured value.
 *
 * <p>The poller's own diagnostics carry a fixed description of the failed operation, the folder name, host, port or
 * message number, and the class names along the failure's cause chain; never an exception message, a cause's
 * message, a stack trace, the user, the password or message content, because mail authentication, MIME parsing and
 * import failures can quote any of them in their messages and causes. The {@code Received:} INFO line of
 * {@link AttachmentImportService} is the original flow's logger, not one of these diagnostics.
 */
@Component
public class ImapMailPoller {

    private static final Logger log = LoggerFactory.getLogger(ImapMailPoller.class);

    private static final String PROTOCOL = "imaps";

    /** The key of the poll frequency, the original connector's {@code checkFrequency}. */
    static final String FREQUENCY_KEY = "imap.check-frequency";

    /** The poll frequency in ms (default 100), as both the schedule of {@link #poll()} and the constructor read it. */
    static final String FREQUENCY = "${" + FREQUENCY_KEY + ":100}";

    /** The most exception types one diagnostic names; a longer cause chain is cut off. */
    private static final int MAX_EXCEPTION_TYPES = 10;

    /** The jakarta.mail session properties through which Angus Mail applies the three socket deadlines. */
    private static final String CONNECT_TIMEOUT_PROPERTY = "mail.imaps.connectiontimeout";
    private static final String READ_TIMEOUT_PROPERTY = "mail.imaps.timeout";
    private static final String WRITE_TIMEOUT_PROPERTY = "mail.imaps.writetimeout";

    /** A deadline as accepted: ASCII decimal digits only, so no sign, blank, hexadecimal or other notation. */
    private static final Pattern DECIMAL_DIGITS = Pattern.compile("[0-9]+");

    private final AttachmentImportService attachmentImportService;
    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final String folderName;
    private final int connectTimeout;
    private final int readTimeout;
    private final int writeTimeout;
    private final Map<String, String> sessionProperties;

    /**
     * The keys of the mailbox settings that are null or blank, in the order host, user, password; empty when all three
     * are set. Fixed at construction, as the settings are.
     */
    private final List<String> missingSettings;

    /**
     * Whether the one-time WARN about missing mailbox settings has been logged. Read and written only inside
     * {@link #pollOnce()}, so this poller's monitor guards it.
     */
    private boolean missingSettingsWarned;

    /**
     * @param attachmentImportService the rest of the original flow, run once per received message
     * @param environment             source of {@code imap.properties}, extra jakarta.mail session properties such as
     *                                trust settings; an entry for {@code mail.imaps.connectiontimeout},
     *                                {@code mail.imaps.timeout} or {@code mail.imaps.writetimeout} replaces the
     *                                matching deadline below
     * @param host                    the original endpoint's {@code host}, from {@code imap.host}; null or blank skips
     *                                every poll
     * @param port                    the original endpoint's {@code port} (993, implicit TLS), from {@code imap.port}
     * @param user                    the original endpoint's {@code user}, from {@code imap.user}; null or blank skips
     *                                every poll
     * @param password                the original endpoint's {@code password}, from {@code imap.password}; null or
     *                                blank skips every poll; never logged
     * @param folderName              the polled folder, from {@code imap.folder}; Mule's default {@code mailboxFolder}
     * @param connectTimeout          ms allowed for opening the TCP connection, from {@code imap.connect-timeout};
     *                                10000 by default, the original endpoint's {@code responseTimeout}
     * @param readTimeout             ms allowed for each socket read (TLS handshake, greeting and every command
     *                                reply), from {@code imap.read-timeout}; 10000 by default
     * @param writeTimeout            ms allowed for each socket write, from {@code imap.write-timeout}; 10000 by
     *                                default
     * @param checkFrequency          the poll frequency the schedule of {@link #poll()} reads, from
     *                                {@code imap.check-frequency}; checked here only, never stored
     * @throws IllegalArgumentException naming the offending key, when one of the three deadlines or an
     *                                  {@code imap.properties} entry replacing one is not a positive decimal whole
     *                                  number within int range (0 or a negative value would mean no deadline), and
     *                                  naming the key and the value when {@code imap.check-frequency} is not a
     *                                  positive frequency ({@link #requirePositiveFrequency(String, String)}); the
     *                                  poller is then never created, so it never polls
     */
    public ImapMailPoller(AttachmentImportService attachmentImportService, Environment environment,
            @Value("${imap.host:}") String host, @Value("${imap.port:993}") int port,
            @Value("${imap.user:}") String user, @Value("${imap.password:}") String password,
            @Value("${imap.folder:INBOX}") String folderName,
            @Value("${imap.connect-timeout:10000}") String connectTimeout,
            @Value("${imap.read-timeout:10000}") String readTimeout,
            @Value("${imap.write-timeout:10000}") String writeTimeout,
            @Value(FREQUENCY) String checkFrequency) {
        requirePositiveFrequency(FREQUENCY_KEY, checkFrequency);
        this.attachmentImportService = attachmentImportService;
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.missingSettings = missingSettings(host, user, password);
        this.folderName = folderName;
        this.connectTimeout = positiveMillis("imap.connect-timeout", connectTimeout);
        this.readTimeout = positiveMillis("imap.read-timeout", readTimeout);
        this.writeTimeout = positiveMillis("imap.write-timeout", writeTimeout);
        // An empty YAML map (imap.properties: {}) contributes no property, so it binds as absent.
        this.sessionProperties = Map.copyOf(Binder.get(environment)
                .bind("imap.properties", Bindable.mapOf(String.class, String.class))
                .orElseGet(Map::of));
        for (String deadline : List.of(CONNECT_TIMEOUT_PROPERTY, READ_TIMEOUT_PROPERTY, WRITE_TIMEOUT_PROPERTY)) {
            String replacement = this.sessionProperties.get(deadline);
            if (replacement != null) {
                positiveMillis("imap.properties[" + deadline + "]", replacement);
            }
        }
    }

    /**
     * The scheduled poll: a fixed delay between the end of one poll and the start of the next, as the original
     * connector's {@code checkFrequency} gave its polling receiver.
     */
    @Scheduled(fixedDelayString = FREQUENCY, initialDelayString = "${imap.initial-delay:0}")
    public void poll() {
        pollOnce();
    }

    /**
     * Runs one poll of the mailbox. Synchronized so the scheduled poll and a direct call never overlap. Never
     * throws: connection, folder and message failures are logged.
     *
     * <p>A null or blank {@code imap.host}, {@code imap.user} or {@code imap.password} skips the poll without opening
     * a session. The first skipped poll of this poller logs one WARN naming every missing key with its environment
     * variable, so the misconfiguration shows at the default level; every skipped poll, the first included, also logs
     * a DEBUG line.
     *
     * @return the number of messages handed to {@link AttachmentImportService#importAttachments(Map)}, whether or
     *         not the import succeeded; {@code 0} when {@code imap.host}, {@code imap.user} or {@code imap.password}
     *         is null or blank, or the folder cannot be opened or listed
     */
    public synchronized int pollOnce() {
        if (!missingSettings.isEmpty()) {
            if (!missingSettingsWarned) {
                missingSettingsWarned = true;
                log.warn("{}; IMAPS polling is skipped", notConfigured(missingSettings, true));
            }
            if (log.isDebugEnabled()) {
                log.debug("{}; IMAPS poll skipped", notConfigured(missingSettings, false));
            }
            return 0;
        }

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
                log.error("Failed to connect to IMAP folder {} on {}:{} {}", folderName, host, port,
                        exceptionTypes(e));
                return 0;
            }

            Message[] messages;
            try {
                messages = folder.getMessages();
            } catch (MessagingException | RuntimeException e) {
                log.error("Failed to read the messages of IMAP folder {} on {}:{} {}", folderName, host, port,
                        exceptionTypes(e));
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

    /**
     * The properties of one poll's session: protocol, host, port and the three socket deadlines, then every
     * {@code imap.properties} entry, which therefore replaces a deadline it names (with a value the constructor has
     * checked is positive).
     */
    Properties sessionProperties() {
        Properties properties = new Properties();
        properties.setProperty("mail.store.protocol", PROTOCOL);
        properties.setProperty("mail.imaps.host", host);
        properties.setProperty("mail.imaps.port", String.valueOf(port));
        properties.setProperty(CONNECT_TIMEOUT_PROPERTY, String.valueOf(connectTimeout));
        properties.setProperty(READ_TIMEOUT_PROPERTY, String.valueOf(readTimeout));
        properties.setProperty(WRITE_TIMEOUT_PROPERTY, String.valueOf(writeTimeout));
        properties.putAll(sessionProperties);
        return properties;
    }

    /**
     * The keys of the mailbox settings a poll cannot do without that are null or blank, in the order host, user,
     * password. Angus Mail would otherwise connect to the loopback host for a blank host, and log in with a blank
     * user or password, or with a Jakarta Mail default name such as the JVM user's for a null user, only to be
     * refused.
     */
    private static List<String> missingSettings(String host, String user, String password) {
        List<String> missing = new ArrayList<>();
        if (host == null || host.isBlank()) {
            missing.add("imap.host");
        }
        if (user == null || user.isBlank()) {
            missing.add("imap.user");
        }
        if (password == null || password.isBlank()) {
            missing.add("imap.password");
        }
        return List.copyOf(missing);
    }

    /**
     * States which settings are missing without any of their values, for example
     * {@code imap.user (IMAP_USER), imap.password (IMAP_PASSWORD) are not configured}: every key, followed by its
     * environment variable when {@code withVariables}, joined with {@code ", "}. The variable is the key upper-cased
     * with dots and hyphens as underscores, the name its {@code application.yml} placeholder reads.
     */
    private static String notConfigured(List<String> keys, boolean withVariables) {
        StringJoiner names = new StringJoiner(", ");
        for (String key : keys) {
            names.add(withVariables
                    ? key + " (" + key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_') + ")"
                    : key);
        }
        return names + (keys.size() == 1 ? " is not configured" : " are not configured");
    }

    /**
     * Reads a socket deadline the way Angus Mail will apply it, refusing every value it would apply as no deadline
     * (0, a negative value, or text it cannot parse, which it silently reads as -1) or read in another notation.
     *
     * @param key   the configuration key the value came from, named in the refusal
     * @param value the configured value
     * @return the deadline in ms, at least 1
     * @throws IllegalArgumentException when the value is not a decimal whole number from 1 to
     *                                  {@link Integer#MAX_VALUE}
     */
    private static int positiveMillis(String key, String value) {
        String refusal = key + " must be a positive number of milliseconds (a decimal whole number from 1 to "
                + Integer.MAX_VALUE + ")";
        if (value == null || !DECIMAL_DIGITS.matcher(value).matches()) {
            throw new IllegalArgumentException(refusal);
        }
        int millis;
        try {
            millis = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            // Only reachable with more digits than an int holds.
            throw new IllegalArgumentException(refusal, e);
        }
        if (millis <= 0) {
            throw new IllegalArgumentException(refusal);
        }
        return millis;
    }

    /**
     * Refuses a poll frequency the schedule of {@link #poll()} cannot run, so that start-up fails with a message
     * naming the key and the value rather than with the message-less {@link IllegalArgumentException} that
     * {@link java.util.concurrent.ScheduledThreadPoolExecutor} throws for a period that is not positive. The value is
     * read as Spring 6.1's {@code @Scheduled} reads {@code fixedDelayString}: an ISO-8601 duration when its first or
     * second character is {@code P} or {@code p}, otherwise a whole number of milliseconds, without trimming. On
     * Spring 6.1.15 {@code PT0.0001S}, {@code 1}, {@code P1D} and {@code pt1s} are scheduled, while {@code 0},
     * {@code -5}, {@code PT0S} and {@code PT-1S} are refused by the executor with that message-less exception.
     *
     * @param key   the configuration key the value came from, named in the refusal
     * @param value the configured frequency
     * @throws IllegalArgumentException naming the key and the value, when the value is neither a positive number of
     *                                  milliseconds nor a positive ISO-8601 duration
     */
    static void requirePositiveFrequency(String key, String value) {
        Duration period;
        try {
            period = isDurationString(value) ? Duration.parse(value) : Duration.ofMillis(Long.parseLong(value));
        } catch (NumberFormatException | DateTimeParseException e) {
            throw invalidFrequency(key, value);
        }
        if (period.isZero() || period.isNegative()) {
            throw invalidFrequency(key, value);
        }
    }

    /** Whether Spring 6.1's {@code @Scheduled} reads the value as an ISO-8601 duration rather than as milliseconds. */
    private static boolean isDurationString(String value) {
        return value != null && value.length() > 1 && (isP(value.charAt(0)) || isP(value.charAt(1)));
    }

    private static boolean isP(char c) {
        return c == 'P' || c == 'p';
    }

    private static IllegalArgumentException invalidFrequency(String key, String value) {
        return new IllegalArgumentException("Invalid " + key + " value \"" + value + "\": the polling frequency must "
                + "be a positive number of milliseconds or a positive ISO-8601 duration");
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
            log.error("Error processing IMAP message {} {}", message.getMessageNumber(), exceptionTypes(e));
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
     * part by its description, decoded file name, disposition or running index, in that order of preference, then
     * makes the name unique as {@link #uniqueName(String, Map)} does. The index counter is local to each multipart
     * level and advances only when a part is named by it, as in the original.
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
            result.put(uniqueName(key, result), part);
        }
    }

    /**
     * Mule 3.8.0's {@code MailUtils.getAttachmentName}: a name already taken becomes the first {@code <n>_<name>},
     * for n = 0, 1, 2, ..., that is still free. Its counter is its own, not the part index of {@code collect}.
     */
    private static String uniqueName(String key, Map<String, Part> taken) {
        if (!taken.containsKey(key)) {
            return key;
        }
        int n = 0;
        while (taken.containsKey(n + "_" + key)) {
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

    /** Closes the folder with expunge, removing the messages flagged deleted, then the store. */
    private void close(Folder folder, Store store) {
        if (folder != null && folder.isOpen()) {
            try {
                folder.close(true);
            } catch (MessagingException | RuntimeException e) {
                log.warn("Failed to close IMAP folder {} on {}:{} {}", folderName, host, port, exceptionTypes(e));
            }
        }
        if (store != null && store.isConnected()) {
            try {
                store.close();
            } catch (MessagingException | RuntimeException e) {
                log.warn("Failed to close the IMAP store for {}:{} {}", host, port, exceptionTypes(e));
            }
        }
    }

    /**
     * Identifies a failure for a diagnostic by its exception types alone, outermost first, for example
     * {@code (jakarta.mail.MessagingException <- java.net.SocketTimeoutException)}: the class name of every
     * throwable along the {@link Throwable#getCause()} chain. Where that chain ends at an Angus Mail IMAP
     * {@link ProtocolException}, which keeps the I/O failure behind a lost connection in its synthetic response
     * rather than as its cause, the walk continues with that failure. A throwable already listed ends the walk, and a
     * chain of more than {@value #MAX_EXCEPTION_TYPES} types is cut off with {@code ...}. No message is read.
     */
    private static String exceptionTypes(Throwable failure) {
        StringJoiner types = new StringJoiner(" <- ", "(", ")");
        Set<Throwable> listed = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && listed.add(current)) {
            if (listed.size() > MAX_EXCEPTION_TYPES) {
                types.add("...");
                break;
            }
            types.add(current.getClass().getName());
            Throwable cause = current.getCause();
            if (cause == null && current instanceof ProtocolException protocolFailure
                    && protocolFailure.getResponse() != null) {
                cause = protocolFailure.getResponse().getException();
            }
            current = cause;
        }
        return types.toString();
    }
}
