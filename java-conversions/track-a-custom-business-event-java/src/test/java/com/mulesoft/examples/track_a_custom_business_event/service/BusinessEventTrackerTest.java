package com.mulesoft.examples.track_a_custom_business_event.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Unit test of {@link BusinessEventTracker}, which replaces the Mule EE tracking elements of flow
 * {@code custom-business-eventsFlow1} in {@code track-a-custom-business-event/src/main/app/custom-business-events.xml}
 * (lines 4-24): the per-message transaction id of {@code tracking:enable-default-events="true"}, the custom event
 * {@code Price} with its {@code item name}, {@code item units} and {@code item price per unit} metadata, and
 * {@code tracking:transaction id="custom-business-event-example-transaction"}.
 *
 * <p>Plain JUnit 5 with a Logback appender on the tracker's own logger: the tracker has no collaborators, so no
 * Spring context or mock is involved. Expected log lines are literals, so the tests pin the rendering rather than
 * whatever the tracker computes.
 */
class BusinessEventTrackerTest {

    private static final String EXAMPLE_TRANSACTION_ID = "custom-business-event-example-transaction";

    /** The line terminators of the regex {@code \R}, the set the tracker escapes. */
    private static final Pattern LINE_TERMINATOR = Pattern.compile("\\R");

    private final Logger trackerLogger = (Logger) LoggerFactory.getLogger(BusinessEventTracker.class);

    private final CapturingAppender appender = new CapturingAppender();

    private BusinessEventTracker tracker;

    private Level savedLevel;

    private boolean savedAdditive;

    @BeforeEach
    void captureTrackerLog() {
        // MDC is thread-local and surefire reuses threads, so no test may see another test's keys.
        MDC.clear();
        tracker = new BusinessEventTracker();
        savedLevel = trackerLogger.getLevel();
        savedAdditive = trackerLogger.isAdditive();
        trackerLogger.setLevel(Level.INFO);
        // Inherited output-stream appenders also snapshot each event's MDC; detaching them keeps the MDC
        // assertions dependent on CapturingAppender alone, whatever logging configuration the classpath holds.
        trackerLogger.setAdditive(false);
        appender.setContext(trackerLogger.getLoggerContext());
        trackerLogger.addAppender(appender);
        appender.start();
    }

    @AfterEach
    void restoreTrackerLog() {
        trackerLogger.detachAppender(appender);
        appender.stop();
        trackerLogger.setAdditive(savedAdditive);
        trackerLogger.setLevel(savedLevel);
        MDC.clear();
    }

    @Test
    void mdcKeysAreTheTrackingNames() {
        assertEquals("transactionId", BusinessEventTracker.TRANSACTION_ID_KEY);
        assertEquals("businessEvent", BusinessEventTracker.BUSINESS_EVENT_KEY);
    }

    @Test
    void startTransactionPutsARandomUuidUnderTransactionId() {
        tracker.startTransaction();

        assertRandomUuid(MDC.get("transactionId"));
        assertTrue(appender.list.isEmpty(), "opening the transaction logs nothing");
    }

    @Test
    void trackCustomEventLogsThePriceEventInTheTransactionContext() {
        tracker.startTransaction();
        String transactionId = MDC.get("transactionId");
        appender.list.clear();
        Map<String, String> metadata = priceMetadata();

        tracker.trackCustomEvent("Price", metadata);

        ILoggingEvent event = singleEvent();
        assertEquals(Level.INFO, event.getLevel());
        assertEquals(BusinessEventTracker.class.getName(), event.getLoggerName());
        assertEquals("Business event {} {}", event.getMessage());
        assertTrue(event.getFormattedMessage().startsWith("Business event Price"));
        assertEquals("Business event Price " + metadata, event.getFormattedMessage());
        assertEquals("Business event Price {item name=shoes, item units=2, item price per unit=10}",
                event.getFormattedMessage());
        assertEquals(transactionId, event.getMDCPropertyMap().get("transactionId"));
        assertRandomUuid(event.getMDCPropertyMap().get("transactionId"));
        assertEquals("Price", event.getMDCPropertyMap().get("businessEvent"));

        assertNull(MDC.get(BusinessEventTracker.BUSINESS_EVENT_KEY), "the event name is scoped to its log call");
        assertEquals(transactionId, MDC.get("transactionId"), "the transaction outlives the event");
        assertEquals(priceMetadata(), metadata, "the caller's metadata is not modified");
    }

    @Test
    void trackCustomEventWithoutAnOpenTransactionCarriesNoTransactionId() {
        tracker.trackCustomEvent("Price", priceMetadata());

        ILoggingEvent event = singleEvent();
        assertEquals("Business event Price {item name=shoes, item units=2, item price per unit=10}",
                event.getFormattedMessage());
        assertNull(event.getMDCPropertyMap().get("transactionId"));
        assertEquals("Price", event.getMDCPropertyMap().get("businessEvent"));
        assertNull(MDC.get("businessEvent"));
        assertNull(MDC.get("transactionId"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("metadataRenderings")
    void trackCustomEventRendersMetadataAsMapToStringDoes(String description, Map<String, String> metadata,
            String expectedRendering) {
        tracker.trackCustomEvent("Price", metadata);

        String logged = singleEvent().getFormattedMessage();
        assertEquals("Business event Price " + expectedRendering, logged);
        assertEquals("Business event Price " + metadata, logged);
    }

    static Stream<Arguments> metadataRenderings() {
        Map<String, String> single = new LinkedHashMap<>();
        single.put("item name", "jeans");
        Map<String, String> nulls = new LinkedHashMap<>();
        nulls.put(null, "shoes");
        nulls.put("item units", null);
        return Stream.of(
                Arguments.of("null metadata", null, "null"),
                Arguments.of("empty metadata", Collections.emptyMap(), "{}"),
                Arguments.of("one entry", single, "{item name=jeans}"),
                Arguments.of("null key and null value", nulls, "{null=shoes, item units=null}"));
    }

    @Test
    void trackCustomEventWithoutAnEventNameLogsNull() {
        tracker.startTransaction();
        appender.list.clear();

        tracker.trackCustomEvent(null, priceMetadata());

        ILoggingEvent event = singleEvent();
        assertEquals("Business event null {item name=shoes, item units=2, item price per unit=10}",
                event.getFormattedMessage());
        assertNull(event.getMDCPropertyMap().get("businessEvent"));
        assertNull(MDC.get("businessEvent"));
    }

    @Test
    void setTransactionIdReplacesTheIdAndLogsIt() {
        tracker.startTransaction();
        appender.list.clear();

        tracker.setTransactionId(EXAMPLE_TRANSACTION_ID);

        assertEquals(EXAMPLE_TRANSACTION_ID, MDC.get("transactionId"));
        ILoggingEvent event = singleEvent();
        assertEquals(Level.INFO, event.getLevel());
        assertEquals("Transaction id set to custom-business-event-example-transaction", event.getFormattedMessage());
        assertEquals(EXAMPLE_TRANSACTION_ID, event.getMDCPropertyMap().get("transactionId"));

        appender.list.clear();
        tracker.trackCustomEvent("Price", priceMetadata());
        assertEquals(EXAMPLE_TRANSACTION_ID, singleEvent().getMDCPropertyMap().get("transactionId"),
                "later events carry the business transaction id");
    }

    @Test
    void setTransactionIdToNullClearsTheIdValue() {
        tracker.startTransaction();
        appender.list.clear();

        tracker.setTransactionId(null);

        assertNull(MDC.get("transactionId"));
        assertEquals("Transaction id set to null", singleEvent().getFormattedMessage());
    }

    @Test
    void endTransactionRemovesTheTransactionId() {
        tracker.startTransaction();
        tracker.setTransactionId(EXAMPLE_TRANSACTION_ID);
        appender.list.clear();

        tracker.endTransaction();

        assertNull(MDC.get("transactionId"));
        assertTrue(appender.list.isEmpty(), "closing the transaction logs nothing");
    }

    @Test
    void eachTransactionGetsADistinctId() {
        tracker.startTransaction();
        String first = MDC.get("transactionId");
        tracker.endTransaction();
        tracker.startTransaction();
        String second = MDC.get("transactionId");

        assertRandomUuid(first);
        assertRandomUuid(second);
        assertNotEquals(first, second);
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("lineTerminators")
    void lineTerminatorsAreEscapedInEveryLoggedValue(String codePoint, char terminator, String escape) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("item" + terminator + "name", terminator + "shoes");
        Map<String, String> original = new LinkedHashMap<>(metadata);

        tracker.trackCustomEvent("Pri" + terminator + "ce", metadata);
        tracker.setTransactionId("tx" + terminator);

        assertEquals(2, appender.list.size());
        ILoggingEvent businessEvent = appender.list.get(0);
        assertEquals("Business event Pri" + escape + "ce {item" + escape + "name=" + escape + "shoes}",
                businessEvent.getFormattedMessage());
        assertEquals("Pri" + escape + "ce", businessEvent.getMDCPropertyMap().get("businessEvent"));
        ILoggingEvent transactionEvent = appender.list.get(1);
        assertEquals("Transaction id set to tx" + escape, transactionEvent.getFormattedMessage());
        assertEquals("tx" + escape, MDC.get("transactionId"));
        for (ILoggingEvent event : appender.list) {
            assertFalse(LINE_TERMINATOR.matcher(event.getFormattedMessage()).find(),
                    () -> "one physical line: " + event.getFormattedMessage());
        }
        assertEquals(original, metadata, "the caller's metadata is not modified");
    }

    static Stream<Arguments> lineTerminators() {
        return Stream.of(
                Arguments.of("U+000D", '\r', "\\r"),
                Arguments.of("U+000A", '\n', "\\n"),
                Arguments.of("U+000B", (char) 0x000B, "\\u000B"),
                Arguments.of("U+000C", (char) 0x000C, "\\u000C"),
                Arguments.of("U+0085", (char) 0x0085, "\\u0085"),
                Arguments.of("U+2028", (char) 0x2028, "\\u2028"),
                Arguments.of("U+2029", (char) 0x2029, "\\u2029"));
    }

    @Test
    void consecutiveLineTerminatorsAreEachEscaped() {
        tracker.trackCustomEvent("Price", Map.of("item name", "shoes\r\n\r\nInjected: entry"));

        assertEquals("Business event Price {item name=shoes\\r\\n\\r\\nInjected: entry}",
                singleEvent().getFormattedMessage());
    }

    @Test
    void textWithoutLineTerminatorsIsLoggedUnchanged() {
        String text = "C:\\shoes\\u2028\ttab \u00e9t\u00e9 \u00b1";

        tracker.trackCustomEvent(text, Map.of(text, text));
        tracker.setTransactionId(text);

        assertEquals("Business event " + text + " {" + text + "=" + text + "}",
                appender.list.get(0).getFormattedMessage());
        assertEquals(text, appender.list.get(0).getMDCPropertyMap().get("businessEvent"));
        assertEquals("Transaction id set to " + text, appender.list.get(1).getFormattedMessage());
        assertEquals(text, MDC.get("transactionId"));
    }

    private ILoggingEvent singleEvent() {
        assertEquals(1, appender.list.size(), () -> "logged events: " + appender.list);
        return appender.list.get(0);
    }

    /** The metadata the price service passes for {@code message.json}, in the flow's meta-data order. */
    private static Map<String, String> priceMetadata() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("item name", "shoes");
        metadata.put("item units", "2");
        metadata.put("item price per unit", "10");
        return metadata;
    }

    private static void assertRandomUuid(String value) {
        assertNotNull(value);
        UUID uuid = UUID.fromString(value);
        assertEquals(value, uuid.toString(), "canonical form");
        assertEquals(4, uuid.version(), "random (type 4) UUID");
    }

    /**
     * Snapshots each event's MDC, message and thread name as it is appended. Logback reads an event's MDC lazily,
     * so a plain {@link ListAppender} would show the MDC as it stands when the test reads it, after the tracker has
     * removed the per-call {@code businessEvent} key.
     */
    static final class CapturingAppender extends ListAppender<ILoggingEvent> {

        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    }
}
