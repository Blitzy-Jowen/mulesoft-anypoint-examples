/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.dataweave_with_flowreflookup.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reproduces flow {@code LookUpSalesRegionFlow} of
 * {@code dataweave-with-flowreflookup/src/main/app/dataweave-with-flowref.xml} (lines 26-54), which the
 * {@code CSV to Maps} DataWeave script calls once per CSV record through
 * {@code lookup("LookUpSalesRegionFlow", $).region} (line 18). Its two processors run in the original order:
 *
 * <ul>
 *   <li>the Groovy {@code scripting:component} "Get Region by State" (lines 27-52) is reimplemented in plain
 *       Java, with no script engine (the "Groovy region table" of the conversion plan, AAP 0.3.2, 0.5.5 and
 *       0.7.4): the record's {@code company_state} is upper-cased when present, under the JVM default locale
 *       as the script's {@code toUpperCase()} is, printed to standard output as Groovy's {@code println} did,
 *       with any line terminator escaped in the printed copy only so each call writes one console line, and
 *       the unescaped value is matched against the five state lists of the script's
 *       {@code switch}; a state no list contains, an empty state and a missing state all keep the script's
 *       initial {@value #UNKNOWN};</li>
 *   <li>the {@code logger} "Log the Region" (line 53) is the INFO statement {@code Region is : <region>}.</li>
 * </ul>
 *
 * <p>The class keeps no state beyond the immutable region table, so one instance serves concurrent
 * callers. Nothing is memoised: as in the original, every call prints and logs.
 */
@Service
public class SalesRegionLookupService {

    /** Region of the script's first state list. */
    public static final String NORTH_EAST = "North East";

    /** Region of the script's second state list. */
    public static final String SOUTH_EAST = "South East";

    /** Region of the script's third state list. */
    public static final String MID_WEST = "Mid West";

    /** Region of the script's fourth state list. */
    public static final String SOUTH_WEST = "South West";

    /** Region of the script's fifth state list. */
    public static final String WEST_COAST = "West Coast";

    /** The script's initial region, kept when no state list matches. */
    public static final String UNKNOWN = "UNKNOWN";

    /** Key of the single entry in the returned map, read by the DataWeave caller as {@code .region}. */
    public static final String REGION_KEY = "region";

    /** CSV column the script reads as {@code payload.company_state}. */
    public static final String STATE_FIELD = "company_state";

    private static final Logger LOG = LoggerFactory.getLogger(SalesRegionLookupService.class);

    /**
     * Upper-case state code to region, built from the script's case lists in their original order. The first
     * list holding a state wins, as the first matching Groovy {@code case} does; the North East list names
     * {@code NH} twice, which {@code putIfAbsent} absorbs.
     */
    private static final Map<String, String> REGION_BY_STATE = buildRegionTable();

    /**
     * Looks up the sales region of one company record.
     *
     * @param companyRecord one CSV record keyed by its header columns; must not be {@code null}, as the
     *                      original fails on a {@code null} payload
     * @return a mutable one-entry map, {@value #REGION_KEY} to the region name, like the script's
     *         {@code ["region":region]}
     */
    public Map<String, String> lookUpSalesRegion(Map<String, ?> companyRecord) {
        Object raw = companyRecord.get(STATE_FIELD);
        // Not trimmed: the script upper-cases the value as read, so " fl" stays " FL" and matches no list.
        // Casing follows the JVM default locale exactly as the script's toUpperCase() does, so under a Turkish
        // default "il" becomes "İL" and resolves to UNKNOWN, as in the original.
        String state = raw == null ? null : String.valueOf(raw).toUpperCase(Locale.getDefault());
        // Reproduces Groovy println of the original script; line terminators are escaped in the printed copy only,
        // so one call writes one console line, while the lookup below uses the unescaped state.
        System.out.println("State to lookup is: " + escapeLineTerminators(state));
        String region = state == null ? UNKNOWN : REGION_BY_STATE.getOrDefault(state, UNKNOWN);

        Map<String, String> result = new LinkedHashMap<>();
        result.put(REGION_KEY, region);
        LOG.info("Region is : {}", result.get(REGION_KEY));
        return result;
    }

    /**
     * Renders the state for the console line only. CR and LF become the two-character escapes {@code \r} and
     * {@code \n}; the other line terminators, U+000B, U+000C, U+0085, U+2028 and U+2029, become a backslash, a
     * {@code u} and the code point's four upper-case hex digits. Every other character, the backslash included,
     * is kept, so a state without line terminators prints exactly as read.
     *
     * @param state the upper-cased state, or {@code null}
     * @return the printable copy, or {@code null} for a missing state, so the line still ends in {@code null}
     */
    private static String escapeLineTerminators(String state) {
        if (state == null) {
            return null;
        }
        StringBuilder printed = new StringBuilder(state.length());
        for (int i = 0; i < state.length(); i++) {
            char c = state.charAt(i);
            switch (c) {
                case '\r' -> printed.append("\\r");
                case '\n' -> printed.append("\\n");
                case '\u000B', '\u000C', '\u0085', '\u2028', '\u2029' ->
                        printed.append(String.format(Locale.ROOT, "\\u%04X", (int) c));
                default -> printed.append(c);
            }
        }
        return printed.toString();
    }

    private static Map<String, String> buildRegionTable() {
        Map<String, String> table = new LinkedHashMap<>();
        addRegion(table, NORTH_EAST, List.of("CT", "ME", "MA", "NH", "VT", "RI", "NY", "NJ", "DE", "DC", "MD", "NH"));
        addRegion(table, SOUTH_EAST, List.of("AL", "AR", "FL", "GA", "LA", "SC", "NC", "TN", "TX"));
        addRegion(table, MID_WEST, List.of("ID", "IL", "IA", "KS", "MT", "WY", "ND", "SD", "OH"));
        addRegion(table, SOUTH_WEST, List.of("AZ", "CO", "OK", "NM", "NV"));
        addRegion(table, WEST_COAST, List.of("CA", "HI", "WA", "OR", "AK"));
        return Collections.unmodifiableMap(table);
    }

    private static void addRegion(Map<String, String> table, String region, List<String> states) {
        for (String state : states) {
            table.putIfAbsent(state, region);
        }
    }
}
