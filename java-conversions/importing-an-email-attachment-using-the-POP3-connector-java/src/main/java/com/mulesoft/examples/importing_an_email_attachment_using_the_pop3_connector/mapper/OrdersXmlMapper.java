/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.mapper;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Hand-written reimplementation of the {@code %dw 1.0} script "Map CSV to XML"
 * ({@code importing-an-email-attachment-using-the-POP3-connector/src/main/app/pop-to-xml.xml}, flow
 * {@code pop-to-xmlFlow1}, header line 13), of which this class is the sole owner:
 * <pre>
 * %dw 1.0
 * %output application/xml
 * ---
 * orders: {
 *     (payload map
 *     order: {
 *         orderId : $.orderId,
 *         name : $.name,
 *         units : $.units,
 *         pricePerUnit : $.pricePerUnit
 *     })
 * }
 * </pre>
 * The payload is the first attachment of the polled email read as {@code application/csv}: the records the SC-4
 * {@code DwCsvReader} yields, one map per data record keyed by the header names, every value the field's text.
 *
 * <p>Output rules, as DataWeave applied them:
 * <ul>
 *   <li>the root is {@code orders}; it holds one {@code order} per record, in record order;</li>
 *   <li>each {@code order} holds exactly {@code orderId}, {@code name}, {@code units} and {@code pricePerUnit}, in
 *       that order, whatever order or extra columns the record has; extra columns are not written;</li>
 *   <li>values are the CSV strings unchanged: never trimmed, never coerced, so {@code 2.0} stays {@code 2.0};</li>
 *   <li>a column the record lacks is {@code null}, written as the empty element {@code <units/>}; an empty value
 *       is {@code <units></units>} ({@link DwXmlWriter.Element#text(Object)} rules);</li>
 *   <li>a {@code null} record reads every field as {@code null}, as {@code $.orderId} on {@code null} does, so it
 *       still yields an {@code order} with four empty elements;</li>
 *   <li>no records ({@code null} or an empty list) leave the root empty: {@code <orders/>}.</li>
 * </ul>
 * Bytes and layout are {@link DwXmlWriter}'s. For the original {@code input.csv} the result is:
 * <pre>{@code
 * <?xml version='1.0' encoding='UTF-8'?>
 * <orders>
 *     <order>
 *         <orderId>1</orderId>
 *         <name>aaa</name>
 *         <units>2.0</units>
 *         <pricePerUnit>10</pricePerUnit>
 *     </order>
 *     <order>
 *         <orderId>2</orderId>
 *         <name>bbb</name>
 *         <units>4.15</units>
 *         <pricePerUnit>5</pricePerUnit>
 *     </order>
 * </orders>
 * }</pre>
 * (indentation shown as spaces; the writer emits tabs, and nothing follows the root end tag).
 *
 * <p>Transformation only: no I/O, no configuration, no logging. Stateless and thread-safe.
 */
@Component
public class OrdersXmlMapper {

    /** The document element. */
    public static final String ROOT = "orders";

    /** The element written once per CSV record. */
    public static final String ORDER = "order";

    /** CSV column and child element: the order identifier. */
    public static final String ORDER_ID = "orderId";

    /** CSV column and child element: the item name. */
    public static final String NAME = "name";

    /** CSV column and child element: the ordered units. */
    public static final String UNITS = "units";

    /** CSV column and child element: the price of one unit. */
    public static final String PRICE_PER_UNIT = "pricePerUnit";

    /** The children of every {@code order}, in the order the script writes them. */
    public static final List<String> FIELDS = List.of(ORDER_ID, NAME, UNITS, PRICE_PER_UNIT);

    private final DwXmlWriter dwXmlWriter;

    /**
     * Creates the mapper.
     *
     * @param dwXmlWriter the DataWeave XML writer that renders the element tree
     */
    public OrdersXmlMapper(DwXmlWriter dwXmlWriter) {
        this.dwXmlWriter = dwXmlWriter;
    }

    /**
     * Converts the CSV records to the {@code orders} XML document the script produced.
     *
     * @param rows the CSV records in input order, each keyed by header name; {@code null} reads as no records
     * @return the DataWeave XML document, declaration included, without a trailing line feed
     */
    public String toXml(List<Map<String, String>> rows) {
        return dwXmlWriter.write(toElement(rows));
    }

    /**
     * Converts the CSV records to the {@code orders} XML document, encoded in UTF-8, as the file
     * {@code orders.xml} receives it.
     *
     * @param rows the CSV records in input order, each keyed by header name; {@code null} reads as no records
     * @return the UTF-8 bytes of {@link #toXml(List)}
     */
    public byte[] toXmlBytes(List<Map<String, String>> rows) {
        return dwXmlWriter.writeBytes(toElement(rows));
    }

    private static DwXmlWriter.Element toElement(List<Map<String, String>> rows) {
        DwXmlWriter.Element root = DwXmlWriter.element(ROOT);
        if (rows == null) {
            return root;
        }
        for (Map<String, String> row : rows) {
            Map<String, String> record = row == null ? Collections.emptyMap() : row;
            DwXmlWriter.Element order = DwXmlWriter.element(ORDER);
            for (String field : FIELDS) {
                order.child(DwXmlWriter.element(field).text(record.get(field)));
            }
            root.child(order);
        }
        return root;
    }
}
