/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.dataweave_with_flowreflookup.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Unit test of {@link SalesRegionLookupService}, which replaces flow {@code LookUpSalesRegionFlow} of
 * {@code dataweave-with-flowreflookup/src/main/app/dataweave-with-flowref.xml} (lines 26-54): the Groovy
 * "Get Region by State" script and the "Log the Region" logger. Every state list of the script's {@code switch}
 * and its {@code UNKNOWN} default are exercised, as the region table of AAP 0.7.4 lists them, together with the
 * script's {@code println} line and the logger's {@code Region is : <region>} message.
 *
 * <p>Expected regions and console lines are string literals rather than the service's constants, so the tests pin
 * the original script's values instead of whatever the constants hold. Plain JUnit and AssertJ: the service has no
 * collaborators, so neither Spring nor Mockito is needed. Logback's console appender writes through
 * {@code System.out}, so the service logger is detached from the root appenders while a test runs; the captured
 * standard output then holds the script's {@code println} line alone.
 */
class SalesRegionLookupServiceTest {

    private final SalesRegionLookupService service = new SalesRegionLookupService();

    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(SalesRegionLookupService.class);

    private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();

    private Level savedLevel;

    private boolean savedAdditive;

    @BeforeEach
    void captureServiceLog() {
        savedLevel = serviceLogger.getLevel();
        savedAdditive = serviceLogger.isAdditive();
        serviceLogger.setLevel(Level.INFO);
        serviceLogger.setAdditive(false);
        logEvents.start();
        serviceLogger.addAppender(logEvents);
    }

    @AfterEach
    void restoreServiceLog() {
        serviceLogger.detachAppender(logEvents);
        logEvents.stop();
        serviceLogger.setAdditive(savedAdditive);
        serviceLogger.setLevel(savedLevel);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(textBlock = """
            CT, North East
            ME, North East
            MA, North East
            NH, North East
            VT, North East
            RI, North East
            NY, North East
            NJ, North East
            DE, North East
            DC, North East
            MD, North East
            AL, South East
            AR, South East
            FL, South East
            GA, South East
            LA, South East
            SC, South East
            NC, South East
            TN, South East
            TX, South East
            ID, Mid West
            IL, Mid West
            IA, Mid West
            KS, Mid West
            MT, Mid West
            WY, Mid West
            ND, Mid West
            SD, Mid West
            OH, Mid West
            AZ, South West
            CO, South West
            OK, South West
            NM, South West
            NV, South West
            CA, West Coast
            HI, West Coast
            WA, West Coast
            OR, West Coast
            AK, West Coast
            """)
    void everyStateOfTheScriptListsResolvesToItsRegion(String state, String region) {
        assertThat(service.lookUpSalesRegion(record(state))).containsExactly(entry("region", region));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"fl, South East", "ca, West Coast", "Ny, North East"})
    void lowerAndMixedCaseStatesAreUpperCasedBeforeTheLookup(String state, String region) {
        assertThat(service.lookUpSalesRegion(record(state))).containsExactly(entry("region", region));
    }

    /** Neither trimmed nor otherwise normalised: the script upper-cases the value as read. */
    @ParameterizedTest(name = "[{index}] \"{0}\" -> UNKNOWN")
    @ValueSource(strings = {"PR", " FL", "FL ", "", "XX"})
    void statesOutsideEveryListKeepTheInitialUnknownRegion(String state) {
        assertThat(service.lookUpSalesRegion(record(state))).containsExactly(entry("region", "UNKNOWN"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("recordsWithoutState")
    void aMissingStateKeepsTheInitialUnknownRegion(String label, Map<String, Object> companyRecord) {
        assertThat(service.lookUpSalesRegion(companyRecord)).containsExactly(entry("region", "UNKNOWN"));
    }

    static Stream<Arguments> recordsWithoutState() {
        Map<String, Object> withoutKey = record("FL");
        withoutKey.remove("company_state");
        return Stream.of(
                Arguments.of("null company_state value", record(null)),
                Arguments.of("no company_state key", withoutKey));
    }

    @Test
    void theResultIsAMutableMapHoldingOnlyTheRegion() {
        Map<String, String> result = service.lookUpSalesRegion(record("FL"));

        assertThat(result).containsOnlyKeys("region").containsEntry("region", "South East");
        result.put("region", "changed");
        assertThat(result).containsExactly(entry("region", "changed"));
    }

    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("printedStates")
    void printsTheStateToLookUpOnOneStandardOutputLine(String state, String printed) {
        String out = captureStdout(() -> service.lookUpSalesRegion(record(state)));

        assertThat(out).isEqualTo("State to lookup is: " + printed + System.lineSeparator());
    }

    static Stream<Arguments> printedStates() {
        return Stream.of(
                Arguments.of("FL", "FL"),
                Arguments.of("fl", "FL"),
                Arguments.of(null, "null"),
                // Characters other than line terminators, the backslash and the tab included, print as read.
                Arguments.of("a\\b\tc", "A\\B\tC"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"FL, Region is : South East", "XX, Region is : UNKNOWN"})
    void logsTheRegionOnceAtInfo(String state, String message) {
        service.lookUpSalesRegion(record(state));

        assertThat(logEvents.list).hasSize(1);
        assertThat(logEvents.list.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(logEvents.list.get(0).getFormattedMessage()).isEqualTo(message);
    }

    /**
     * Groovy's {@code toUpperCase()} cases under the JVM default locale, so a Turkish default turns a dotted
     * {@code i} into {@code İ} and the state matches no list, as it did in the original; the {@code en-US} row
     * shows that the default locale is what decides.
     */
    @ParameterizedTest(name = "{0}: {1} -> {2} -> {3}")
    @CsvSource({
            "tr-TR, il, İL, UNKNOWN",
            "tr-TR, hi, Hİ, UNKNOWN",
            "tr-TR, ri, Rİ, UNKNOWN",
            "tr-TR, id, İD, UNKNOWN",
            "tr-TR, fl, FL, South East",
            "en-US, il, IL, Mid West"})
    void upperCasesUnderTheJvmDefaultLocale(String languageTag, String state, String printed, String region) {
        Locale savedDefault = Locale.getDefault();
        Locale savedDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        Locale savedFormat = Locale.getDefault(Locale.Category.FORMAT);
        AtomicReference<Map<String, String>> result = new AtomicReference<>();
        String out;
        try {
            Locale.setDefault(Locale.forLanguageTag(languageTag));
            out = captureStdout(() -> result.set(service.lookUpSalesRegion(record(state))));
        } finally {
            Locale.setDefault(savedDefault);
            Locale.setDefault(Locale.Category.DISPLAY, savedDisplay);
            Locale.setDefault(Locale.Category.FORMAT, savedFormat);
        }

        assertThat(out).isEqualTo("State to lookup is: " + printed + System.lineSeparator());
        assertThat(result.get()).containsExactly(entry("region", region));
        assertThat(logEvents.list).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("Region is : " + region);
    }

    /**
     * A quoted CSV field may carry line terminators. They are escaped in the printed copy only, so one call still
     * writes one console line, and the lookup sees the raw value, which matches no list.
     */
    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("statesWithLineTerminators")
    void lineTerminatorsInTheStateAreEscapedOnTheConsoleLineOnly(String state, String printed) {
        AtomicReference<Map<String, String>> result = new AtomicReference<>();

        String out = captureStdout(() -> result.set(service.lookUpSalesRegion(record(state))));

        assertThat(out).isEqualTo("State to lookup is: " + printed + System.lineSeparator());
        assertThat(out.substring(0, out.length() - System.lineSeparator().length()))
                .doesNotContain("\r", "\n", "\u000B", "\u000C", "\u0085", "\u2028", "\u2029");
        assertThat(result.get()).containsExactly(entry("region", "UNKNOWN"));
        assertThat(logEvents.list).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("Region is : UNKNOWN");
    }

    static Stream<Arguments> statesWithLineTerminators() {
        return Stream.of(
                Arguments.of("FL\nINFO FORGED", "FL\\nINFO FORGED"),
                Arguments.of("fl\r\nINFO FORGED", "FL\\r\\nINFO FORGED"),
                Arguments.of("FL\rX", "FL\\rX"),
                Arguments.of("FL\u2028X", "FL\\u2028X"),
                Arguments.of("FL\u2029X", "FL\\u2029X"),
                Arguments.of("FL\u0085X", "FL\\u0085X"),
                Arguments.of("FL\u000BX", "FL\\u000BX"),
                Arguments.of("FL\u000CX", "FL\\u000CX"));
    }

    /** A company record keyed by the header columns of {@code companies.csv}, with the given state. */
    private static Map<String, Object> record(Object state) {
        Map<String, Object> companyRecord = new LinkedHashMap<>();
        companyRecord.put("company_name", "Universal Exports");
        companyRecord.put("company_address", "55 Main St");
        companyRecord.put("company_city", "Miami");
        companyRecord.put("company_state", state);
        companyRecord.put("company_zip", "33126");
        companyRecord.put("has_given_contact_permission", "");
        return companyRecord;
    }

    /** Runs the action with {@code System.out} redirected to a UTF-8 buffer and returns what it printed. */
    private static String captureStdout(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        System.setOut(capture);
        try {
            action.run();
        } finally {
            System.setOut(original);
            capture.close();
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
