/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_expression_language_basics.mapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Hand-written replacement for the DataWeave 1.0 script of flow {@code docs-greetingFlow4}
 * ({@code greeting.xml:47}), and the only class that reimplements it:
 * <pre>
 * %dw 1.0
 * %output application/csv header=false
 * ---
 * [{
 *     username: payload.username,
 *     age: payload.age,
 *     age_of: (payload.age &gt; 18) as :boolean
 * }]
 * </pre>
 * The payload is the map the flow builds from the {@code username} and {@code age} query parameters. Both values
 * are written unchanged, so they keep the types the caller gave them; {@code age_of} is a {@link Boolean}. The
 * single record is written through {@link DwCsvWriter} without a header row, so {@code username=Mule&age=1}
 * produces {@code Mule,1,false} followed by a line feed.
 *
 * <p>The {@code age_of} rule follows DataWeave 1.0, not MEL: {@code >} coerces its right operand to the type of
 * its left one. The query parameters are Strings, so {@code payload.age > 18} compares the text of the age with
 * {@code "18"} lexicographically: {@code "22"}, {@code "5"} and {@code "abc"} are over 18, while {@code "1"},
 * {@code "18"} and {@code "100"} are not. A numeric age is compared with 18 as a number; a missing age is never
 * over 18. No version-matched DataWeave 1.x source was available to confirm this coercion, so the rule is a
 * Tier 2A verification point: a fixture captured from the running original that shows other bytes governs.
 *
 * <p>Instances hold no state beyond the shared writer and are safe to share between threads.
 */
@Component
public class GreetingCsvMapper {

    private static final BigDecimal THRESHOLD = BigDecimal.valueOf(18);
    private static final String THRESHOLD_TEXT = "18";

    private final DwCsvWriter dwCsvWriter;

    /**
     * Creates the mapper.
     *
     * @param dwCsvWriter the writer that produces the DataWeave 1.0 CSV bytes
     */
    public GreetingCsvMapper(DwCsvWriter dwCsvWriter) {
        this.dwCsvWriter = dwCsvWriter;
    }

    /**
     * Builds the script's single output record.
     *
     * @param payload the flow payload, holding {@code username} and {@code age}; neither changed nor retained
     * @return a new map with the columns {@code username}, {@code age} and {@code age_of}, in that order; an
     *         absent {@code username} or {@code age} stays {@code null}
     * @throws NullPointerException when {@code payload} is {@code null}
     */
    public Map<String, Object> map(Map<String, ?> payload) {
        Object age = payload.get("age");
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("username", payload.get("username"));
        record.put("age", age);
        record.put("age_of", Boolean.valueOf(ageOver18(age)));
        return record;
    }

    /**
     * Writes the script's output: one header-less CSV line ending with a line feed.
     *
     * @param payload the flow payload, holding {@code username} and {@code age}
     * @return the CSV text, for example {@code Mule,1,false\n}; a {@code null} value becomes an empty field
     * @throws NullPointerException when {@code payload} is {@code null}
     */
    public String toCsv(Map<String, ?> payload) {
        return dwCsvWriter.write(List.of(map(payload)), false);
    }

    /**
     * Evaluates {@code payload.age > 18} as DataWeave 1.0 does.
     *
     * @param age the age as the payload holds it
     * @return {@code false} for {@code null}; for a {@link Number}, whether it exceeds 18 numerically; for a
     *         String, and for any other type by its {@code String.valueOf} text, whether that text sorts after
     *         {@code "18"}
     */
    static boolean ageOver18(Object age) {
        if (age == null) {
            return false;
        }
        if (age instanceof Number number) {
            BigDecimal value;
            if (number instanceof BigDecimal decimal) {
                value = decimal;
            } else {
                try {
                    value = new BigDecimal(number.toString());
                } catch (NumberFormatException noDecimalForm) {
                    // NaN and the infinities have no decimal form.
                    return number.doubleValue() > THRESHOLD.doubleValue();
                }
            }
            return value.compareTo(THRESHOLD) > 0;
        }
        // 18 is coerced to the left operand's type: a String age is compared as text, never parsed as a number.
        return String.valueOf(age).compareTo(THRESHOLD_TEXT) > 0;
    }
}
