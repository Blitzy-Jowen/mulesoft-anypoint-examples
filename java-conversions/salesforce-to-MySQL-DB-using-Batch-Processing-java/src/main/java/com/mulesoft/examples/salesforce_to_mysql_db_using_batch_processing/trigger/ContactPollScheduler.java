package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.trigger;

import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.client.UpstreamSystem;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.exception.UpstreamRateLimitException;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.exception.UpstreamUnavailableException;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.model.BatchJobResult;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.service.ContactToDatabaseBatchService;
import com.sforce.soap.partner.fault.ApiFault;
import com.sforce.ws.SoapFaultException;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import javax.xml.namespace.QName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The poll of the original flow {@code triggerFlow} ({@code salesforce-to-database.xml}, l.60-65):
 * {@code <poll><fixed-frequency-scheduler frequency="60" timeUnit="SECONDS"/>}, which fires from start-up.
 *
 * <p>Each poll is one {@link ContactToDatabaseBatchService#synchronize()} call; the watermark, the Salesforce query
 * and the batch job {@code salesforce-to-database-Batch} belong to that service. The flow's
 * {@code processingStrategy="synchronous"} is kept by Spring's single-threaded scheduler, so two scheduled polls never
 * overlap.
 */
@Component
public class ContactPollScheduler {

    /** The configuration key of the polling frequency. */
    static final String FREQUENCY_KEY = "poll.frequency";

    /** The polling frequency placeholder: {@link #FREQUENCY_KEY}, defaulting to the original's 60000 ms. */
    static final String FREQUENCY = "${" + FREQUENCY_KEY + ":60000}";

    private static final Logger LOGGER = LoggerFactory.getLogger(ContactPollScheduler.class);

    /** Longest logged class, method or file name, in characters after escaping. */
    private static final int NAME_LOG_LIMIT = 256;

    /** Longest logged Salesforce fault code, in characters after escaping. */
    private static final int FAULT_CODE_LOG_LIMIT = 64;

    /** Number of causes named after the failed poll's exception before the chain is cut short. */
    private static final int CAUSE_LOG_LIMIT = 10;

    private static final String NULL_LOG_TOKEN = "(null)";

    private static final String TRUNCATION_LOG_MARKER = "...[truncated]";

    private static final String CAUSE_SEPARATOR = " <- ";

    private static final String MORE_CAUSES_MARKER = "...";

    private static final String CIRCULAR_CAUSE_MARKER = "[circular]";

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private final ContactToDatabaseBatchService contactToDatabaseBatchService;

    /**
     * Creates the trigger.
     *
     * @param contactToDatabaseBatchService the service that performs one poll of {@code triggerFlow}
     * @param frequency                     the resolved {@link #FREQUENCY} the scheduled poll runs at
     * @throws IllegalArgumentException if {@code frequency} is unreadable, zero or negative, so that start-up fails
     *                                  with a message naming {@link #FREQUENCY_KEY} and the value
     */
    public ContactPollScheduler(ContactToDatabaseBatchService contactToDatabaseBatchService,
            @Value(FREQUENCY) String frequency) {
        requirePositiveFrequency(FREQUENCY_KEY, frequency);
        // The service is unchecked by design: constructor injection never passes null, and the shape matches the other
        // watermark poll trigger (salesforce-data-synchronization project), whose only differences are the service and
        // the default frequency.
        this.contactToDatabaseBatchService = contactToDatabaseBatchService;
    }

    /**
     * Scheduled poll. A fixed rate keeps the polling frequency as the period between poll starts, as the original
     * {@code fixed-frequency-scheduler} did.
     *
     * <p>A failed poll is logged at ERROR and swallowed, as Mule's default exception strategy handled a failed poll,
     * so the next poll still fires. Upstream failures were already classified and logged by the SC-9 executor; they
     * are neither retried nor mapped here.
     *
     * <p>The ERROR line names the failure by its type identifiers alone: its class, the frame that threw it and the
     * classes of its causes, each class followed, where it has one, by the Salesforce fault code of a WSC fault or
     * the upstream of an SC-9 exception, for example {@code Poll of triggerFlow failed:
     * com.sforce.soap.partner.fault.UnexpectedErrorFault [MALFORMED_QUERY] at <frame>}. No exception message, fault
     * message, suppressed exception or stack trace is logged.
     */
    @Scheduled(fixedRateString = FREQUENCY, initialDelayString = "${poll.initial-delay:0}")
    public void poll() {
        try {
            pollOnce();
        } catch (Exception e) {
            // Log safety: exception text, cause messages and stack traces can repeat the query, session data,
            // credentials or contact data and can carry line breaks, so neither the message nor the exception is
            // handed to the logger; only type identifiers, the Salesforce fault code and the upstream name are logged.
            LOGGER.error("Poll of triggerFlow failed: {}", describe(e));
        }
    }

    /**
     * Runs one poll on demand, in place of the original test's {@code runSchedulersOnce("triggerFlow")}.
     *
     * @return the result of the batch job run, as {@link ContactToDatabaseBatchService#synchronize()} returned it
     * @throws Exception the failure of the poll, unchanged, with the watermark left as it was
     */
    public BatchJobResult pollOnce() throws Exception {
        return contactToDatabaseBatchService.synchronize();
    }

    /**
     * Checks that a polling frequency is one {@link Scheduled#fixedRateString()} can schedule. The check mirrors
     * how Spring 6.1 reads that string: an ISO-8601 duration when its first or second character is {@code P} or
     * {@code p}, otherwise a whole number of milliseconds, with no trimming. Spring 6.1.15 schedules
     * {@code "PT0.0001S"}, {@code "1"}, {@code "P1D"} and {@code "pt1s"}, but hands {@code "0"}, {@code "-5"},
     * {@code "PT0S"} and {@code "PT-1S"} to the executor, which rejects them with an
     * {@link IllegalArgumentException} that carries no message. Checking first makes start-up fail with a message
     * naming the key and the value.
     *
     * @param key   the configuration key the value was read from
     * @param value the resolved value
     * @throws IllegalArgumentException if the value is unreadable, zero or negative
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

    private static boolean isDurationString(String value) {
        return value != null && value.length() > 1 && (isP(value.charAt(0)) || isP(value.charAt(1)));
    }

    private static boolean isP(char c) {
        return c == 'P' || c == 'p';
    }

    private static IllegalArgumentException invalidFrequency(String key, String value) {
        return new IllegalArgumentException("Invalid " + key + " value \"" + value + "\": the polling frequency"
                + " must be a positive number of milliseconds or a positive ISO-8601 duration");
    }

    /**
     * Returns the type identifiers of {@code failure} without any of its text: its class name and tag, its
     * throw-site frame, then the class name and tag of each cause, separated by {@code " <- "}. The walk stops at a
     * cause already named, marked {@code [circular]}, and after {@link #CAUSE_LOG_LIMIT} causes, marked
     * {@code ...}, so cyclic and very deep chains terminate. Suppressed exceptions are not named.
     */
    private static String describe(Throwable failure) {
        StringBuilder out = new StringBuilder(128);
        out.append(sanitize(failure.getClass().getName(), NAME_LOG_LIMIT));
        appendTag(out, failure);
        appendThrowSite(out, failure);
        Set<Throwable> named = Collections.newSetFromMap(new IdentityHashMap<>());
        named.add(failure);
        int causes = 0;
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            out.append(CAUSE_SEPARATOR);
            if (causes == CAUSE_LOG_LIMIT) {
                out.append(MORE_CAUSES_MARKER);
                break;
            }
            if (!named.add(cause)) {
                out.append(CIRCULAR_CAUSE_MARKER);
                break;
            }
            out.append(sanitize(cause.getClass().getName(), NAME_LOG_LIMIT));
            appendTag(out, cause);
            causes++;
        }
        return out.toString();
    }

    /**
     * Appends {@code " [<tag>]"} when {@code failure} carries an identifier of its cause that is safe to log: the
     * {@code ExceptionCode} of a WSC {@link ApiFault} ({@code LoginFault} and {@code UnexpectedErrorFault}
     * included), for example {@code MALFORMED_QUERY}; otherwise the local part of the fault code of a WSC
     * {@link SoapFaultException}; or {@code upstream <name>} for an SC-9 upstream exception. Nothing is appended for
     * any other failure. The vendor's free-text fault message is never read.
     */
    private static void appendTag(StringBuilder out, Throwable failure) {
        String tag = tag(failure);
        if (tag != null) {
            out.append(" [").append(tag).append(']');
        }
    }

    private static String tag(Throwable failure) {
        if (failure instanceof ApiFault apiFault && apiFault.getExceptionCode() != null) {
            return sanitize(apiFault.getExceptionCode().name(), FAULT_CODE_LOG_LIMIT);
        }
        if (failure instanceof SoapFaultException soapFault) {
            QName faultCode = soapFault.getFaultCode();
            if (faultCode != null && faultCode.getLocalPart() != null && !faultCode.getLocalPart().isEmpty()) {
                return sanitize(faultCode.getLocalPart(), FAULT_CODE_LOG_LIMIT);
            }
        }
        if (failure instanceof UpstreamAuthenticationException authentication) {
            return upstreamTag(authentication.getSystem());
        }
        if (failure instanceof UpstreamRateLimitException rateLimit) {
            return upstreamTag(rateLimit.getSystem());
        }
        if (failure instanceof UpstreamUnavailableException unavailable) {
            return upstreamTag(unavailable.getSystem());
        }
        return null;
    }

    private static String upstreamTag(UpstreamSystem system) {
        return "upstream " + system.displayName();
    }

    /**
     * Appends {@code " at <class>.<method>(<file>:<line>)"} for the frame that threw {@code failure}, in the form
     * {@link StackTraceElement#toString()} uses but without its class loader and module prefix, or nothing when
     * the failure has no stack trace.
     */
    private static void appendThrowSite(StringBuilder out, Throwable failure) {
        StackTraceElement[] trace = failure.getStackTrace();
        if (trace == null || trace.length == 0 || trace[0] == null) {
            return;
        }
        StackTraceElement frame = trace[0];
        out.append(" at ")
                .append(sanitize(frame.getClassName(), NAME_LOG_LIMIT))
                .append('.')
                .append(sanitize(frame.getMethodName(), NAME_LOG_LIMIT))
                .append('(');
        if (frame.isNativeMethod()) {
            out.append("Native Method");
        } else if (frame.getFileName() == null) {
            out.append("Unknown Source");
        } else {
            out.append(sanitize(frame.getFileName(), NAME_LOG_LIMIT));
            if (frame.getLineNumber() >= 0) {
                out.append(':').append(frame.getLineNumber());
            }
        }
        out.append(')');
    }

    /**
     * Makes {@code value} safe to log on one line, as the SC-9 executor does for its own ERROR lines.
     *
     * <p>ISO control characters (C0, DEL and C1, so CR, LF, TAB, NUL and NEL included), line and paragraph
     * separators, format characters (bidirectional overrides and isolates, zero-width characters, the byte order
     * mark), unpaired surrogates and the reverse solidus are each written as {@code \}{@code u} and four
     * lower-case hex digits per UTF-16 unit; escaping the reverse solidus keeps an escape in the log
     * distinguishable from the same six characters in the input. Every other character, a valid surrogate pair
     * included, is copied unchanged.
     *
     * @param value the value to log; {@code null} is written as {@code (null)}
     * @param limit the most characters the escaped value may occupy; a longer value is cut before the first
     *              character or escape that would exceed it, never inside a surrogate pair or an escape, and
     *              {@code ...[truncated]} is appended
     */
    private static String sanitize(String value, int limit) {
        if (value == null) {
            return NULL_LOG_TOKEN;
        }
        StringBuilder out = new StringBuilder(Math.min(value.length(), limit) + TRUNCATION_LOG_MARKER.length());
        int i = 0;
        while (i < value.length()) {
            int codePoint = value.codePointAt(i);
            int units = Character.charCount(codePoint);
            boolean escape = mustEscape(codePoint);
            if (out.length() + (escape ? 6 * units : units) > limit) {
                return out.append(TRUNCATION_LOG_MARKER).toString();
            }
            for (int unit = i; unit < i + units; unit++) {
                if (escape) {
                    appendUnicodeEscape(out, value.charAt(unit));
                } else {
                    out.append(value.charAt(unit));
                }
            }
            i += units;
        }
        return out.toString();
    }

    private static boolean mustEscape(int codePoint) {
        if (codePoint == '\\' || Character.isISOControl(codePoint)) {
            return true;
        }
        int type = Character.getType(codePoint);
        return type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.FORMAT
                || type == Character.SURROGATE;
    }

    private static void appendUnicodeEscape(StringBuilder out, char c) {
        out.append("\\u")
                .append(HEX_DIGITS[(c >> 12) & 0xF])
                .append(HEX_DIGITS[(c >> 8) & 0xF])
                .append(HEX_DIGITS[(c >> 4) & 0xF])
                .append(HEX_DIGITS[c & 0xF]);
    }
}
