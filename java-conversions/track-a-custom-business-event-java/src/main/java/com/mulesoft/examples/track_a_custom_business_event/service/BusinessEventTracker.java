package com.mulesoft.examples.track_a_custom_business_event.service;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Business-event tracking of flow {@code custom-business-eventsFlow1}, replacing the Mule EE elements
 * {@code tracking:enable-default-events="true"} (a per-message transaction id),
 * {@code tracking:custom-event} with its {@code tracking:meta-data} entries, and
 * {@code tracking:transaction}.
 *
 * <p>Each event is a structured SLF4J INFO entry; the transaction id travels in the MDC under
 * {@value #TRANSACTION_ID_KEY} and the event name under {@value #BUSINESS_EVENT_KEY} for the duration
 * of the event's log call. The MDC is thread-local, so callers open and close the transaction on the
 * request thread with {@link #startTransaction()} and {@link #endTransaction()}.
 *
 * <p>Line terminators in caller-supplied text (CR, LF, U+000B, U+000C, U+0085, U+2028 and U+2029) are
 * escaped in the logged rendering and in the MDC values, so each event stays one physical log line;
 * every other character is kept, so ordinary values render exactly as {@code Map.toString()} renders
 * them.
 */
@Component
public class BusinessEventTracker {

    private static final Logger log = LoggerFactory.getLogger(BusinessEventTracker.class);

    /** MDC key holding the current message's transaction id. */
    public static final String TRANSACTION_ID_KEY = "transactionId";

    /** MDC key holding the custom business event name while that event is logged. */
    public static final String BUSINESS_EVENT_KEY = "businessEvent";

    /**
     * Opens the tracking transaction with a random id, as Mule's default events assign one to every
     * message before any custom transaction id is set.
     */
    public void startTransaction() {
        MDC.put(TRANSACTION_ID_KEY, UUID.randomUUID().toString());
    }

    /**
     * Records a custom business event with its metadata, logged in the caller's iteration order.
     *
     * @param eventName the event name, e.g. {@code Price}; line terminators in it are escaped in the
     *                  log entry and in the MDC value
     * @param metaData  the event's metadata entries, rendered as {@code {key=value, ...}} like
     *                  {@code Map.toString()}, with line terminators in keys and values escaped; the map
     *                  itself is not modified
     */
    public void trackCustomEvent(String eventName, Map<String, String> metaData) {
        String loggedName = escapeLineDelimiters(eventName);
        MDC.put(BUSINESS_EVENT_KEY, loggedName);
        try {
            log.info("Business event {} {}", loggedName, renderMetaData(metaData));
        } finally {
            MDC.remove(BUSINESS_EVENT_KEY);
        }
    }

    /**
     * Replaces the current transaction id, as {@code tracking:transaction} does for the rest of the
     * message's processing.
     *
     * @param id the business transaction id; line terminators in it are escaped in the MDC value and
     *           in the log entry
     */
    public void setTransactionId(String id) {
        String loggedId = escapeLineDelimiters(id);
        MDC.put(TRANSACTION_ID_KEY, loggedId);
        log.info("Transaction id set to {}", loggedId);
    }

    /** Closes the tracking transaction, removing its id from the current thread's MDC. */
    public void endTransaction() {
        MDC.remove(TRANSACTION_ID_KEY);
    }

    /**
     * Renders metadata in {@code Map.toString()} form, {@code {k1=v1, k2=v2}} ({@code {}} when empty), in
     * the map's iteration order, with line terminators in every key and value escaped.
     *
     * @param metaData the event's metadata, possibly {@code null}; it is neither copied nor modified
     * @return the rendering, or {@code null} when {@code metaData} is {@code null}
     */
    private static String renderMetaData(Map<String, String> metaData) {
        if (metaData == null) {
            return null;
        }
        StringBuilder rendered = new StringBuilder("{");
        String separator = "";
        for (Map.Entry<String, String> entry : metaData.entrySet()) {
            rendered.append(separator)
                    .append(escapeLineDelimiters(entry.getKey()))
                    .append('=')
                    .append(escapeLineDelimiters(entry.getValue()));
            separator = ", ";
        }
        return rendered.append('}').toString();
    }

    /**
     * Replaces each line terminator of the regex {@code \R} set with escape text, so caller-supplied
     * text cannot start a new physical log line: CR becomes {@code \r}, LF becomes {@code \n}, and
     * U+000B, U+000C, U+0085, U+2028 and U+2029 become a backslash, {@code u} and the four upper-case
     * hex digits of the character. Every other character, the backslash included, is kept.
     *
     * @param text the caller-supplied text, possibly {@code null}
     * @return the escaped text; {@code text} itself when it holds no line terminator, {@code null} when
     *         it is {@code null}
     */
    private static String escapeLineDelimiters(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder escaped = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String replacement = switch (c) {
                case '\r' -> "\\r";
                case '\n' -> "\\n";
                case 0x000B, 0x000C, 0x0085, 0x2028, 0x2029 -> String.format("\\u%04X", (int) c);
                default -> null;
            };
            if (replacement != null) {
                if (escaped == null) {
                    escaped = new StringBuilder(text.length() + 16).append(text, 0, i);
                }
                escaped.append(replacement);
            } else if (escaped != null) {
                escaped.append(c);
            }
        }
        return escaped == null ? text : escaped.toString();
    }
}
