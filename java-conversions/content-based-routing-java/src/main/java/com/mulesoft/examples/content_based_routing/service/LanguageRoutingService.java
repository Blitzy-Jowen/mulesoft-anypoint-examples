/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.content_based_routing.service;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reproduces flow {@code content-based-routingFlow} and sub-flow {@code replyInDefaultLanguage} of
 * {@code content-based-routing/src/main/app/content-based-routing.xml}, processor for statement:
 *
 * <ul>
 *   <li>{@code expression-filter #[payload != '/favicon.ico']} (line 7) is the guard clause that ends the
 *       call with {@link Optional#empty()}; the filter declares no {@code throwOnUnaccepted}, so a
 *       rejection is not an error and the caller answers it with an empty 200;</li>
 *   <li>{@code set-variable language} (line 8) is the local variable holding the decoded {@code language}
 *       query parameter;</li>
 *   <li>{@code choice} (lines 9-19) is the {@code if / else if / else} below, with the original exact,
 *       case-sensitive comparisons, so every value other than {@value #SPANISH} or {@value #FRENCH},
 *       {@code null} included, takes the default branch;</li>
 *   <li>{@code flow-ref replyInDefaultLanguage} (line 17) is the private method of the same name;</li>
 *   <li>each {@code logger} (lines 20 and 23) is an INFO statement with the original text, written in the
 *       original order.</li>
 * </ul>
 *
 * <p>The class keeps no state, so one instance serves concurrent requests.
 */
@Service
public class LanguageRoutingService {

    /** The browser request the original flow filters out before routing. */
    public static final String FAVICON_PATH = "/favicon.ico";

    /** The {@code language} value that selects the Spanish reply. */
    public static final String SPANISH = "Spanish";

    /** The {@code language} value that selects the French reply. */
    public static final String FRENCH = "French";

    /** The language the default branch assigns to the flow variable {@code language}. */
    public static final String DEFAULT_LANGUAGE = "English";

    /** Reply of the Spanish branch. */
    public static final String SPANISH_REPLY = "Hola!";

    /** Reply of the French branch. */
    public static final String FRENCH_REPLY = "Bonjour!";

    /** Reply of the default branch, sub-flow {@code replyInDefaultLanguage}. */
    public static final String DEFAULT_REPLY = "Hello!";

    private static final Logger LOG = LoggerFactory.getLogger(LanguageRoutingService.class);

    /** The sub-flow's logger message; the trailing space is the original's. */
    private static final String DEFAULT_LANGUAGE_LOG = "No language specified. Using English as a default. ";

    /** The flow's closing logger message, with the reply and the language as arguments. */
    private static final String REPLY_LOG = "The reply \"{}\" means \"hello\" in {}.";

    /**
     * Routes one request on its {@code language} query parameter.
     *
     * @param payload  the request body as text; {@code null} or empty when the request has none, which
     *                 passes the favicon filter as the original's null payload does
     * @param language the decoded {@code language} query parameter, or {@code null} when it is absent
     * @return {@link Optional#empty()} when the favicon filter rejects the payload (nothing is logged),
     *         otherwise the reply: {@value #SPANISH_REPLY}, {@value #FRENCH_REPLY} or
     *         {@value #DEFAULT_REPLY}
     */
    public Optional<String> reply(String payload, String language) {
        if (FAVICON_PATH.equals(payload)) {
            return Optional.empty();
        }

        String currentLanguage = language;
        String reply;
        if (SPANISH.equals(currentLanguage)) {
            reply = SPANISH_REPLY;
        } else if (FRENCH.equals(currentLanguage)) {
            reply = FRENCH_REPLY;
        } else {
            Routed routed = replyInDefaultLanguage();
            currentLanguage = routed.language();
            reply = routed.reply();
        }

        LOG.info(REPLY_LOG, reply, currentLanguage);
        return Optional.of(reply);
    }

    /** Sub-flow {@code replyInDefaultLanguage}: logs, then sets the language and the reply. */
    private Routed replyInDefaultLanguage() {
        LOG.info(DEFAULT_LANGUAGE_LOG);
        return new Routed(DEFAULT_REPLY, DEFAULT_LANGUAGE);
    }

    /** The two values the sub-flow sets on the message: the payload and the flow variable {@code language}. */
    private record Routed(String reply, String language) {
    }
}
