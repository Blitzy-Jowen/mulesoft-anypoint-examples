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
     * @param eventName the event name, e.g. {@code Price}
     * @param metaData  the event's metadata entries, rendered through their {@code toString()}
     */
    public void trackCustomEvent(String eventName, Map<String, String> metaData) {
        MDC.put(BUSINESS_EVENT_KEY, eventName);
        try {
            log.info("Business event {} {}", eventName, metaData);
        } finally {
            MDC.remove(BUSINESS_EVENT_KEY);
        }
    }

    /**
     * Replaces the current transaction id, as {@code tracking:transaction} does for the rest of the
     * message's processing.
     *
     * @param id the business transaction id
     */
    public void setTransactionId(String id) {
        MDC.put(TRANSACTION_ID_KEY, id);
        log.info("Transaction id set to {}", id);
    }

    /** Closes the tracking transaction, removing its id from the current thread's MDC. */
    public void endTransaction() {
        MDC.remove(TRANSACTION_ID_KEY);
    }
}
