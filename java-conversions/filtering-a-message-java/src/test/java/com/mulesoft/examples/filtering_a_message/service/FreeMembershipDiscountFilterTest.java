/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.filtering_a_message.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit test of {@link FreeMembershipDiscountFilter}, the port of the original {@code FreeMembershipDiscountFilter.java}
 * (lines 18-30) with the filter interface removed and the body unchanged.
 *
 * <p>The boundary table and the failure cases are derived from the original rule, not from the port, so a drift in
 * the port fails here. The two example payloads are the inputs of {@code FilteringMessageIT.java} (lines 62-67):
 * {@code message.json} is granted the discount and {@code message1.json} is rejected. Plain JUnit 5 with no Spring
 * context: the filter is stateless and has no collaborators.
 */
class FreeMembershipDiscountFilterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final FreeMembershipDiscountFilter filter = new FreeMembershipDiscountFilter();

    @ParameterizedTest
    @CsvSource(useHeadersInDisplayName = true, textBlock = """
            months, purchases, membership, expected
                 5,       999,    premium,    false
                 5,      1000,    premium,    false
                 6,       999,    premium,    false
                 6,      1000,    premium,    true
                 6,      2499,       free,    false
                 6,      2500,       free,    true
                 7,      1999,       free,    false
                 7,      2000,       free,    true
                11,      1999,       free,    false
                11,      2000,       free,    true
                12,      1499,       free,    false
                12,      1500,       free,    true
                 6,      1000,    PREMIUM,    false
            """)
    void discountBoundaries(int months, int purchases, String membership, boolean expected) {
        // PREMIUM is not "premium": the membership comparison is case-sensitive, so that row takes the free path.
        assertEquals(expected, filter.accept(map(months, purchases, membership)));
    }

    @Test
    void numericStringValuesAreParsed() {
        assertTrue(filter.accept(map("12", "2000", "free")));
    }

    // An empty column leaves its key out of the payload map; the other two keys stay present, so the
    // NullPointerException can only come from the missing key.
    @ParameterizedTest
    @CsvSource(useHeadersInDisplayName = true, textBlock = """
            months, purchases, membership
                12,      2000,
                  ,      2000,       free
                12,          ,       free
            """)
    void missingKeyThrowsNullPointerException(Integer months, Integer purchases, String membership) {
        Map<String, Object> payload = map(months, purchases, membership);

        assertThrows(NullPointerException.class, () -> filter.accept(payload));
    }

    @Test
    void nonIntegerMonthsThrowsNumberFormatException() {
        Map<String, Object> payload = map("12.5", 2000, "free");

        assertThrows(NumberFormatException.class, () -> filter.accept(payload));
    }

    @ParameterizedTest
    @CsvSource({
            "/message.json,  true",
            "/message1.json, false"
    })
    void originalExamplePayloads(String resource, boolean expected) throws Exception {
        try (InputStream in = FreeMembershipDiscountFilterTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, () -> "test resource " + resource + " is missing from the classpath");
            // The same HashMap the JSON-to-object step hands the filter in production.
            HashMap<String, Object> payload = JSON.readValue(in, new TypeReference<HashMap<String, Object>>() { });

            assertEquals(expected, filter.accept(payload));
        }
    }

    /** Builds a payload map holding only the keys whose argument is non-null. */
    private static Map<String, Object> map(Object months, Object purchases, Object membership) {
        Map<String, Object> payload = new HashMap<>();
        if (months != null) {
            payload.put("months", months);
        }
        if (purchases != null) {
            payload.put("purchases", purchases);
        }
        if (membership != null) {
            payload.put("membership", membership);
        }
        return payload;
    }
}
