/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.get_customer_list_from_netsuite.mapper;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Hand-written reimplementation of the {@code %dw 1.0} script "Customer List to XML"
 * ({@code get-customer-list-from-netsuite/src/main/app/get-customer-list-from-netsuite.xml}, header line 13),
 * of which this class is the sole owner:
 * <pre>
 * %dw 1.0
 * %output application/xml
 * ---
 * div: {
 *   ( payload map {
 *     line: {
 *       tr: {
 *         td: $.lastName,
 *         td: $.firstName,
 *         td: $.email
 *       }
 *     }
 *   })
 * } unless (payload is :empty) otherwise {
 *   line: {
 *     tr: {
 *       td: 'No customers found'
 *     }
 *   }
 * }
 * </pre>
 * The {@code div} key binds the whole {@code unless ... otherwise} expression, so both branches have the root
 * {@code div}:
 * <ul>
 *   <li>a {@code null} or empty row list gives one {@code line/tr/td} holding {@link #NO_CUSTOMERS};</li>
 *   <li>otherwise each row, in the caller's order, gives one {@code line/tr} with three {@code td} children:
 *       last name, first name, email. The rows are SuiteQL results, keyed by the lower-case column labels
 *       {@link #LAST_NAME}, {@link #FIRST_NAME} and {@link #EMAIL};</li>
 *   <li>a {@link String} value is written as it is, any other value through {@code String.valueOf}, and a
 *       {@code null} or absent value as {@link DwXmlWriter}'s element without text ({@code <td/>}), as
 *       {@code $.x} yields {@code null}; a {@code null} row reads like an empty one.</li>
 * </ul>
 * Bytes and layout are {@link DwXmlWriter}'s: the declaration line, tab indentation, LF line ends, nothing after
 * the root end tag, and {@code &}, {@code <}, {@code >} escaped in text. The declaration is kept here; the
 * template expression that drops it before the page is rendered belongs to the service.
 *
 * <p>Transformation only: no I/O, no configuration, no logging. Stateless and thread-safe.
 */
@Component
public class CustomerHtmlMapper {

    /** SuiteQL column label of the customer's last name, the script's {@code $.lastName}. */
    public static final String LAST_NAME = "lastname";

    /** SuiteQL column label of the customer's first name, the script's {@code $.firstName}. */
    public static final String FIRST_NAME = "firstname";

    /** SuiteQL column label of the customer's email address, the script's {@code $.email}. */
    public static final String EMAIL = "email";

    /** The single cell the script writes when the query returns no customers. */
    public static final String NO_CUSTOMERS = "No customers found";

    private static final String DIV = "div";
    private static final String LINE = "line";
    private static final String TR = "tr";
    private static final String TD = "td";

    private final DwXmlWriter writer;

    /**
     * Creates the mapper.
     *
     * @param writer the DataWeave XML writer that renders the element tree
     */
    public CustomerHtmlMapper(DwXmlWriter writer) {
        this.writer = writer;
    }

    /**
     * Converts the customer rows to the {@code div/line/tr/td} XML document the script produced.
     *
     * @param rows the customers in display order, each keyed by {@link #LAST_NAME}, {@link #FIRST_NAME} and
     *        {@link #EMAIL}; {@code null}, an empty list, {@code null} rows and missing keys are accepted
     * @return the DataWeave XML document, declaration included, without a trailing line feed
     */
    public String toXml(List<? extends Map<String, ?>> rows) {
        DwXmlWriter.Element div = DwXmlWriter.element(DIV);
        if (rows == null || rows.isEmpty()) {
            div.child(line(DwXmlWriter.element(TD).text(NO_CUSTOMERS)));
        } else {
            for (Map<String, ?> row : rows) {
                Map<String, ?> fields = row == null ? Map.of() : row;
                div.child(line(
                        cell(fields.get(LAST_NAME)),
                        cell(fields.get(FIRST_NAME)),
                        cell(fields.get(EMAIL))));
            }
        }
        return writer.write(div);
    }

    private static DwXmlWriter.Element line(DwXmlWriter.Element... cells) {
        return DwXmlWriter.element(LINE).child(DwXmlWriter.element(TR).children(cells));
    }

    private static DwXmlWriter.Element cell(Object value) {
        DwXmlWriter.Element td = DwXmlWriter.element(TD);
        if (value != null) {
            td.text(value instanceof String text ? text : String.valueOf(value));
        }
        return td;
    }
}
