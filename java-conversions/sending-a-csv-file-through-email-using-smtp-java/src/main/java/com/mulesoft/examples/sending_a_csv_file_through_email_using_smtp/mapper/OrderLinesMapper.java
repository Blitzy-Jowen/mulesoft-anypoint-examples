/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.mapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

/**
 * Hand-written owner of the {@code Transform CSV to Map} script of {@code csv-to-smtp.xml} (header at line 8 of the
 * original flow file):
 * <pre>
 * %dw 1.0
 * %output application/java
 * ---
 * payload map {
 *     name: $.name,
 *     orderId: $.orderId,
 *     pricePerUnit: $.pricePerUnit as :number,
 *     units: $.units as :number,
 *     totalPrice: ($.units * $.pricePerUnit ) as :number
 * }
 * </pre>
 *
 * <p>{@code %output application/java} produced a {@code java.util} list of maps whose {@code toString()} is the mail
 * body the original test compares with {@code email.txt}, so the same rendering is reproduced exactly:
 * <ul>
 *   <li>each record is a {@link LinkedHashMap} keyed {@code name}, {@code orderId}, {@code pricePerUnit},
 *       {@code units}, {@code totalPrice}, in the script's order rather than the CSV column order;</li>
 *   <li>{@code name} and {@code orderId} are the CSV text unchanged;</li>
 *   <li>the three numbers are {@link BigDecimal}s, the product computed exactly from the parsed operands. A value
 *       equal to an integer is written with scale 0 ({@code 2.0} as {@code 2}, {@code 10} as {@code 10}, never
 *       {@code 1E+1}); any other value keeps the scale it was read or computed with ({@code 4.15}, {@code 20.75}).</li>
 * </ul>
 *
 * <p>Values are never trimmed, so a number with surrounding spaces is not a number. A missing numeric column maps to
 * {@code null}, as DataWeave's null-safe selector does, but then leaves {@code totalPrice} without an operand and the
 * record fails.
 *
 * <p>The class is stateless and therefore thread-safe.
 */
@Component
public class OrderLinesMapper {

    /** Record key and CSV column of the item name. */
    public static final String NAME = "name";

    /** Record key and CSV column of the order identifier. */
    public static final String ORDER_ID = "orderId";

    /** Record key and CSV column of the unit price. */
    public static final String PRICE_PER_UNIT = "pricePerUnit";

    /** Record key and CSV column of the number of units. */
    public static final String UNITS = "units";

    /** Record key of {@code units * pricePerUnit}. */
    public static final String TOTAL_PRICE = "totalPrice";

    /**
     * Maps CSV rows to order lines, one line per row in input order. The rows are never modified.
     *
     * @param rows the CSV rows keyed by header name, as {@link DwCsvReader} reads them
     * @return a new mutable {@link ArrayList} of new {@link LinkedHashMap} order lines; empty for an empty input
     * @throws IllegalArgumentException when {@code units} or {@code pricePerUnit} is not a number, or either is missing
     *         so {@code totalPrice} cannot be computed
     * @throws NullPointerException when {@code rows} or one of its rows is {@code null}
     */
    public List<Map<String, Object>> map(List<Map<String, String>> rows) {
        Objects.requireNonNull(rows, "rows");
        List<Map<String, Object>> lines = new ArrayList<>(rows.size());
        for (Map<String, String> row : rows) {
            lines.add(toLine(row));
        }
        return lines;
    }

    private static Map<String, Object> toLine(Map<String, String> row) {
        BigDecimal pricePerUnit = toNumber(PRICE_PER_UNIT, row.get(PRICE_PER_UNIT));
        BigDecimal units = toNumber(UNITS, row.get(UNITS));
        Map<String, Object> line = new LinkedHashMap<>();
        line.put(NAME, row.get(NAME));
        line.put(ORDER_ID, row.get(ORDER_ID));
        line.put(PRICE_PER_UNIT, normalize(pricePerUnit));
        line.put(UNITS, normalize(units));
        line.put(TOTAL_PRICE, normalize(totalPrice(units, pricePerUnit)));
        return line;
    }

    /** {@code as :number}: the text read as an exact decimal; a missing value stays {@code null}. */
    private static BigDecimal toNumber(String field, String value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " is not a number: " + value, e);
        }
    }

    // How DataWeave 1.0 multiplied a null operand is unconfirmed (a Tier 2A verification point), so a missing
    // operand fails the record visibly instead of producing a guessed totalPrice.
    private static BigDecimal totalPrice(BigDecimal units, BigDecimal pricePerUnit) {
        if (units == null || pricePerUnit == null) {
            throw new IllegalArgumentException(
                    "totalPrice cannot be computed: units=" + units + ", pricePerUnit=" + pricePerUnit);
        }
        return units.multiply(pricePerUnit);
    }

    /**
     * Writes an integral value with scale 0. {@code stripTrailingZeros()} alone is not enough: it leaves a negative
     * scale on values such as {@code 10}, whose {@code toString()} would then be {@code 1E+1}.
     */
    private static BigDecimal normalize(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() <= 0 ? stripped.setScale(0) : value;
    }
}
