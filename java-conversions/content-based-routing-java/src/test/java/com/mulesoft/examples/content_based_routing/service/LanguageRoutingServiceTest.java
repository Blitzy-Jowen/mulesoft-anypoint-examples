/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.content_based_routing.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Unit test of {@link LanguageRoutingService}, which replaces flow {@code content-based-routingFlow} and sub-flow
 * {@code replyInDefaultLanguage} of content-based-routing/src/main/app/content-based-routing.xml: the favicon
 * {@code expression-filter}, the {@code choice} on the {@code language} query parameter and both INFO loggers.
 *
 * <p>Expected values are string literals copied from the flow rather than the service's constants, so the tests pin
 * the original payload and log bytes instead of whatever the constants hold.
 *
 * <p>Log assertions read only the output written after the call's mark, so output another test wrote into the same
 * capture can never satisfy or break an assertion.
 */
@ExtendWith(OutputCaptureExtension.class)
class LanguageRoutingServiceTest {

    /** The sub-flow's logger message; the trailing space is the original's. */
    private static final String DEFAULT_LANGUAGE_LOG = "No language specified. Using English as a default. ";

    /** Start of the flow's closing logger message, written whenever the filter accepts the payload. */
    private static final String REPLY_LOG_START = "The reply ";

    private final LanguageRoutingService service = new LanguageRoutingService();

    @Test
    void spanishLanguageRepliesHolaAndLogsTheReply(CapturedOutput output) {
        int mark = output.getOut().length();

        Optional<String> result = service.reply("", "Spanish");

        String logged = output.getOut().substring(mark);
        assertEquals(Optional.of("Hola!"), result);
        assertTrue(logged.contains("The reply \"Hola!\" means \"hello\" in Spanish."), logged);
        assertFalse(logged.contains("No language specified."), logged);
    }

    @Test
    void frenchLanguageRepliesBonjourAndLogsTheReply(CapturedOutput output) {
        int mark = output.getOut().length();

        Optional<String> result = service.reply("", "French");

        String logged = output.getOut().substring(mark);
        assertEquals(Optional.of("Bonjour!"), result);
        assertTrue(logged.contains("The reply \"Bonjour!\" means \"hello\" in French."), logged);
        assertFalse(logged.contains("No language specified."), logged);
    }

    /**
     * The {@code otherwise} branch: an absent, empty or unknown language, and the case-sensitive near misses, which
     * MEL's string {@code ==} does not match either.
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"German", "spanish", "FRENCH"})
    void otherLanguageRepliesHelloInEnglishAfterLoggingTheDefault(String language, CapturedOutput output) {
        int mark = output.getOut().length();

        Optional<String> result = service.reply("", language);

        String logged = output.getOut().substring(mark);
        String replyLine = "The reply \"Hello!\" means \"hello\" in English.";
        int defaultIndex = logged.indexOf(DEFAULT_LANGUAGE_LOG);
        int replyIndex = logged.indexOf(replyLine);
        assertEquals(Optional.of("Hello!"), result);
        assertTrue(defaultIndex >= 0, logged);
        assertTrue(replyIndex >= 0, logged);
        // The sub-flow's logger runs inside the choice, before the flow's closing logger.
        assertTrue(defaultIndex < replyIndex, logged);
    }

    @Test
    void faviconPayloadIsFilteredWithoutReplyOrLog(CapturedOutput output) {
        int mark = output.getOut().length();

        Optional<String> withLanguage = service.reply("/favicon.ico", "Spanish");
        Optional<String> withoutLanguage = service.reply("/favicon.ico", null);

        String logged = output.getOut().substring(mark);
        assertEquals(Optional.empty(), withLanguage);
        assertEquals(Optional.empty(), withoutLanguage);
        assertFalse(logged.contains(REPLY_LOG_START), logged);
        assertFalse(logged.contains("No language specified."), logged);
    }

    /** Only the exact string {@code /favicon.ico} fails {@code #[payload != '/favicon.ico']}. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"hello", "/favicon.ico/", "/FAVICON.ICO", "/"})
    void otherPayloadPassesTheFilter(String payload) {
        assertEquals(Optional.of("Hola!"), service.reply(payload, "Spanish"));
    }

    @Test
    void constantsMatchTheOriginalFlow() {
        assertEquals("/favicon.ico", LanguageRoutingService.FAVICON_PATH);
        assertEquals("Spanish", LanguageRoutingService.SPANISH);
        assertEquals("French", LanguageRoutingService.FRENCH);
        assertEquals("English", LanguageRoutingService.DEFAULT_LANGUAGE);
        assertEquals("Hola!", LanguageRoutingService.SPANISH_REPLY);
        assertEquals("Bonjour!", LanguageRoutingService.FRENCH_REPLY);
        assertEquals("Hello!", LanguageRoutingService.DEFAULT_REPLY);
    }

    @Test
    void nullPayloadAndLanguageNeverThrowAndReplyHello() {
        // A null payload is not /favicon.ico, so the filter accepts it and the default branch replies.
        Optional<String> result = assertDoesNotThrow(() -> service.reply(null, null));

        assertEquals(Optional.of("Hello!"), result);
    }
}
