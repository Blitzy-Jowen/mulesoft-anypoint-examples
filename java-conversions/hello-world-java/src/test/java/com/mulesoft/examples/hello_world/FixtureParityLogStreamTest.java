package com.mulesoft.examples.hello_world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Exactness of the fixture replay's log comparison (ST-2): {@link FixtureParityTest.Replay#logStreamDifference}
 * compares {@code response.logs} entry for entry with the stream of every event the loggers it names logged
 * ({@link FixtureParityTest.Replay#logStream}), so a delivery sequence recorded only as per-delivery log events is
 * compared in full. The helpers are called alone, on Logback events built here, so none of the replay state
 * {@link FixtureParityTest} keeps for its own run is touched.
 *
 * <p>The recording is the JMS rollback scenario's: four rollback events of the flow's logger, then its success
 * event. {@link #FRAMEWORK} stands for the framework loggers that log around the flow, which no entry names.
 */
class FixtureParityLogStreamTest {

    private static final String WHAT = "jms-in-redelivered-until-fifth-delivery: response.logs";
    private static final String FLOW = "org.mule.api.processor.LoggerMessageProcessor";
    private static final String FRAMEWORK = "org.springframework.jms.listener.DefaultMessageListenerContainer";
    private static final String ROLLBACK =
            "Entered rollback exception strategy. The message rolls back to its original state for reprocessing.";
    private static final String SUCCESS = "Transaction without errors";

    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest(name = "{0}")
    @MethodSource("matchingStreams")
    void matchesTheStreamThatHoldsExactlyTheRecordedEvents(String description, List<ILoggingEvent> logged) {
        assertNull(FixtureParityTest.Replay.logStreamDifference(recording(), logged, true));
        assertNull(FixtureParityTest.Replay.logStreamDifference(recording(), logged, false));
    }

    static Stream<Arguments> matchingStreams() {
        List<ILoggingEvent> interleaved = new ArrayList<>();
        interleaved.add(event(FRAMEWORK, Level.INFO, "Listener container started"));
        for (int delivery = 1; delivery <= 4; delivery++) {
            interleaved.add(event(FLOW, Level.INFO, ROLLBACK));
            interleaved.add(event(FRAMEWORK, Level.WARN, "Execution of JMS message listener failed"));
            interleaved.add(event(FRAMEWORK, Level.DEBUG, ""));
        }
        interleaved.add(event(FLOW, Level.INFO, SUCCESS));
        interleaved.add(event(FRAMEWORK, Level.ERROR, "Setup of JMS message listener invoker failed"));
        return Stream.of(
                arguments("four rollback events, then the success event", deliveries(4, true)),
                arguments("the same, with framework events before, between and after them", interleaved));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("differingStreams")
    void reportsEveryStreamThatDiffersFromTheRecording(String description, List<ILoggingEvent> logged,
            boolean deterministic, String expectedDifference) {
        String difference = FixtureParityTest.Replay.logStreamDifference(recording(), logged, deterministic);

        assertNotNull(difference, "a stream that differs from the recording never matches");
        assertTrue(difference.startsWith("the log stream of [" + FLOW + "] " + expectedDifference), difference);
        assertTrue(difference.contains("\n  expected: ") && difference.contains("\n  logged:   "),
                "the report names both streams: " + difference);
    }

    static Stream<Arguments> differingStreams() {
        List<ILoggingEvent> trailingError = deliveries(4, true);
        trailingError.add(event(FLOW, Level.ERROR, "Catch Exception Strategy"));
        List<ILoggingEvent> wrongLevel = deliveries(4, false);
        wrongLevel.add(event(FLOW, Level.WARN, SUCCESS));
        List<ILoggingEvent> wrongMessage = deliveries(4, false);
        wrongMessage.add(event(FLOW, Level.INFO, "Transaction without error"));
        List<ILoggingEvent> otherLogger = deliveries(4, false);
        otherLogger.add(event(FRAMEWORK, Level.INFO, SUCCESS));
        List<ILoggingEvent> reordered = deliveries(4, false);
        reordered.add(0, event(FLOW, Level.INFO, SUCCESS));
        return Stream.of(
                arguments("one rollback event more before the success", deliveries(5, true), true,
                        "differs at event 5: expected INFO " + FLOW + " - " + SUCCESS + ", logged INFO " + FLOW
                                + " - " + ROLLBACK),
                arguments("one rollback event more, nondeterministic", deliveries(5, true), false,
                        "holds a further event after the 5 the original logged"),
                arguments("one rollback event fewer before the success", deliveries(3, true), true,
                        "differs at event 4: expected INFO " + FLOW + " - " + ROLLBACK + ", logged INFO " + FLOW
                                + " - " + SUCCESS),
                arguments("one rollback event fewer, nondeterministic", deliveries(3, true), false,
                        "holds 4 of the 5 events the original logged"),
                arguments("a further rollback event after the full recording", deliveries(4, true, 1), true,
                        "holds a further event after the 5 the original logged: INFO " + FLOW + " - " + ROLLBACK),
                arguments("a further event of the flow logger at another level", trailingError, true,
                        "holds a further event after the 5 the original logged: ERROR " + FLOW),
                arguments("a further event, nondeterministic", deliveries(4, true, 1), false,
                        "holds a further event after the 5"),
                arguments("the success event at another level", wrongLevel, true,
                        "differs at event 5: expected INFO " + FLOW + " - " + SUCCESS + ", logged WARN"),
                arguments("the success event at another level, nondeterministic", wrongLevel, false,
                        "differs at event 5"),
                arguments("the success event with another message", wrongMessage, true,
                        "differs at event 5: expected INFO " + FLOW + " - " + SUCCESS + ", logged INFO " + FLOW
                                + " - Transaction without error"),
                arguments("the success event under another logger", otherLogger, true,
                        "holds 4 of the 5 events the original logged; the next is INFO " + FLOW + " - " + SUCCESS),
                arguments("the success event before the rollback events", reordered, true, "differs at event 1"),
                arguments("the rollback events without the success event yet", deliveries(4, false), true,
                        "holds 4 of the 5 events the original logged"),
                arguments("nothing logged by the flow logger", List.of(event(FRAMEWORK, Level.INFO, SUCCESS)),
                        true, "holds 0 of the 5 events the original logged"));
    }

    @ParameterizedTest(name = "recorded {0}, logged {1}: deterministic {2}, nondeterministic {3}")
    @CsvSource(nullValues = "NULL", value = {
            "'" + SUCCESS + "', '" + SUCCESS + "', true,  true",
            "'" + SUCCESS + "', Transaction 42,     false, true",
            "'" + SUCCESS + "', '',                 false, false",
            "'" + SUCCESS + "', NULL,               false, false",
            "'',                Transaction 42,     false, false",
            "NULL,              Transaction 42,     false, false",
            "NULL,              '',                 true,  true",
            "NULL,              NULL,               true,  true",
    })
    void comparesTheMessageExactlyOrInNondeterministicScenariosOnlyByEmptiness(String recorded, String logged,
            boolean deterministicMatch, boolean nondeterministicMatch) throws Exception {
        String message = recorded == null ? "" : ", \"message\": " + JSON.writeValueAsString(recorded);
        List<FixtureParityTest.Replay.LogEntry> entries = FixtureParityTest.Replay.logEntries(
                JSON.readTree("[{\"logger\": \"" + FLOW + "\", \"level\": \"INFO\"" + message + "}]"), WHAT);
        List<ILoggingEvent> stream = List.of(event(FLOW, Level.INFO, logged));

        assertEquals(deterministicMatch, FixtureParityTest.Replay.logStreamDifference(entries, stream, true) == null);
        assertEquals(nondeterministicMatch,
                FixtureParityTest.Replay.logStreamDifference(entries, stream, false) == null);
    }

    @Test
    void comparesTheStreamOfEveryNamedLoggerInLoggedOrder() {
        String other = "com.mulesoft.examples.hello_world.service.HelloWorldService";
        List<FixtureParityTest.Replay.LogEntry> entries = List.of(entry(FLOW, "INFO", ROLLBACK),
                entry(other, "INFO", "Hello"), entry(FLOW, "INFO", SUCCESS));
        ILoggingEvent rollback = event(FLOW, Level.INFO, ROLLBACK);
        ILoggingEvent hello = event(other, Level.INFO, "Hello");
        ILoggingEvent success = event(FLOW, Level.INFO, SUCCESS);
        List<ILoggingEvent> logged = List.of(event(FRAMEWORK, Level.INFO, "started"), rollback, hello,
                event(FRAMEWORK, Level.INFO, "committed"), success);

        assertEquals(List.of(rollback, hello, success), FixtureParityTest.Replay.logStream(entries, logged));
        assertNull(FixtureParityTest.Replay.logStreamDifference(entries, logged, true));
        String swapped = FixtureParityTest.Replay.logStreamDifference(entries, List.of(hello, rollback, success), true);
        assertNotNull(swapped, "events of two named loggers compare in the order they were logged");
        assertTrue(swapped.startsWith("the log stream of [" + FLOW + ", " + other + "] differs at event 1"), swapped);
    }

    @Test
    void anEmptyRecordingNamesNoLoggerAndConstrainsNothing() throws Exception {
        List<FixtureParityTest.Replay.LogEntry> entries =
                FixtureParityTest.Replay.logEntries(JSON.readTree("[]"), WHAT);

        assertEquals(List.of(), entries);
        assertEquals(List.of(), FixtureParityTest.Replay.logStream(entries, deliveries(4, true)));
        assertNull(FixtureParityTest.Replay.logStreamDifference(entries, deliveries(4, true), true));
    }

    @Test
    void readsTheEntriesInOrderWithAnAbsentOrNullMessageAsEmpty() throws Exception {
        List<FixtureParityTest.Replay.LogEntry> entries = FixtureParityTest.Replay.logEntries(JSON.readTree(
                "[{\"logger\": \"" + FLOW + "\", \"level\": \"INFO\", \"message\": \"" + ROLLBACK + "\"},"
                        + " {\"logger\": \"" + FLOW + "\", \"level\": \"INFO\"},"
                        + " {\"logger\": \"" + FLOW + "\", \"level\": \"ERROR\", \"message\": null}]"), WHAT);

        assertEquals(List.of(entry(FLOW, "INFO", ROLLBACK), entry(FLOW, "INFO", ""), entry(FLOW, "ERROR", "")),
                entries);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "{\"logger\": \"a\", \"level\": \"INFO\"}",
            "[\"INFO a - message\"]",
            "[{\"level\": \"INFO\", \"message\": \"m\"}]",
            "[{\"logger\": \"a\", \"message\": \"m\"}]",
            "[{\"logger\": \"a\", \"level\": 20000}]",
            "[{\"logger\": null, \"level\": \"INFO\"}]",
            "[{\"logger\": \"a\", \"level\": \"INFO\", \"message\": 7}]",
    })
    void failsTheFixtureOnAMalformedEntry(String logs) throws Exception {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> FixtureParityTest.Replay.logEntries(JSON.readTree(logs), WHAT));

        assertTrue(failure.getMessage().startsWith(WHAT), failure.getMessage());
    }

    /** The recording: four rollback events of the flow's logger, then its success event. */
    private static List<FixtureParityTest.Replay.LogEntry> recording() {
        List<FixtureParityTest.Replay.LogEntry> entries =
                new ArrayList<>(Collections.nCopies(4, entry(FLOW, "INFO", ROLLBACK)));
        entries.add(entry(FLOW, "INFO", SUCCESS));
        return entries;
    }

    /** {@code rollbacks} rollback events, then the success event when {@code success}, then {@code trailing} more. */
    private static List<ILoggingEvent> deliveries(int rollbacks, boolean success, int trailing) {
        List<ILoggingEvent> events = deliveries(rollbacks, success);
        for (int i = 0; i < trailing; i++) {
            events.add(event(FLOW, Level.INFO, ROLLBACK));
        }
        return events;
    }

    private static List<ILoggingEvent> deliveries(int rollbacks, boolean success) {
        List<ILoggingEvent> events = new ArrayList<>();
        for (int i = 0; i < rollbacks; i++) {
            events.add(event(FLOW, Level.INFO, ROLLBACK));
        }
        if (success) {
            events.add(event(FLOW, Level.INFO, SUCCESS));
        }
        return events;
    }

    private static FixtureParityTest.Replay.LogEntry entry(String logger, String level, String message) {
        return new FixtureParityTest.Replay.LogEntry(logger, level, message);
    }

    private static ILoggingEvent event(String logger, Level level, String message) {
        LoggingEvent event = new LoggingEvent();
        event.setLoggerName(logger);
        event.setLevel(level);
        event.setMessage(message);
        return event;
    }
}
