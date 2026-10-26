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
 *       0.7.4): the record's {@code company_state} is upper-cased when present, printed to standard output
 *       as Groovy's {@code println} did, and matched against the five state lists of the script's
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
        String state = raw == null ? null : String.valueOf(raw).toUpperCase(Locale.ROOT);
        // Reproduces Groovy println of the original script
        System.out.println("State to lookup is: " + state);
        String region = state == null ? UNKNOWN : REGION_BY_STATE.getOrDefault(state, UNKNOWN);

        Map<String, String> result = new LinkedHashMap<>();
        result.put(REGION_KEY, region);
        LOG.info("Region is : {}", result.get(REGION_KEY));
        return result;
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
