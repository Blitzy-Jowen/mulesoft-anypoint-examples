/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector.mapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

/**
 * Reimplements the {@code Map CSV to XML} script of the original {@code imap-to-xml.xml} flow:
 *
 * <pre>{@code
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
 * }</pre>
 *
 * <p>The input is the first mail attachment as the rows {@code DwCsvReader} reads from it (header names as
 * keys, every value a string). Each row becomes one {@code order} element, in row order, holding the four
 * keys in the script's order whatever the CSV column order; other columns are ignored. Values are written as
 * the CSV strings, because DataWeave 1.0 reads CSV values as strings and the script coerces nothing
 * ({@code 2.0} stays {@code 2.0}). A column the row lacks is {@code null} in the script and is written through
 * {@link DwXmlWriter} as an empty element. Serialization, declaration and tab layout are {@link DwXmlWriter}'s.
 *
 * <p>The {@code order} elements repeat once per row, as the script writes them. The carried test schema
 * {@code orders.xsd} declares a single {@code order}, so a document of several rows does not validate against
 * it; the original runtime wrote the same repeated siblings, and the original test only counts the written
 * files, so the script's output governs over the schema.
 *
 * <p>Stateless and thread-safe.
 */
@Component
public class OrdersXmlMapper {

    /** Root element name. */
    public static final String ORDERS = "orders";
    /** Element written once per CSV row. */
    public static final String ORDER = "order";
    /** Order child element, read from the CSV column of the same name. */
    public static final String ORDER_ID = "orderId";
    /** Order child element, read from the CSV column of the same name. */
    public static final String NAME = "name";
    /** Order child element, read from the CSV column of the same name. */
    public static final String UNITS = "units";
    /** Order child element, read from the CSV column of the same name. */
    public static final String PRICE_PER_UNIT = "pricePerUnit";

    /** The script's {@code order} keys in the order it writes them; each names the CSV column it reads. */
    private static final List<String> ORDER_FIELDS = List.of(ORDER_ID, NAME, UNITS, PRICE_PER_UNIT);

    private final DwXmlWriter dwXmlWriter;

    /**
     * @param dwXmlWriter the DataWeave 1.0 XML writer every document is serialized with
     * @throws NullPointerException when {@code dwXmlWriter} is {@code null}
     */
    public OrdersXmlMapper(DwXmlWriter dwXmlWriter) {
        this.dwXmlWriter = Objects.requireNonNull(dwXmlWriter, "dwXmlWriter must not be null");
    }

    /**
     * Maps the attachment's CSV rows to the {@code orders} document.
     *
     * @param rows the CSV rows in file order, each keyed by header name; an empty list yields an empty
     *        {@code orders} element. Rows must not be {@code null}
     * @return the document as {@link DwXmlWriter#write(DwXmlWriter.Element)} renders it: the declaration
     *         {@code <?xml version='1.0' encoding='UTF-8'?>}, tab-indented elements, no trailing line feed
     * @throws IllegalArgumentException when {@code rows} is {@code null}, which no attachment read produces,
     *         so it is rejected rather than written as an empty document
     */
    public String toXml(List<Map<String, String>> rows) {
        if (rows == null) {
            throw new IllegalArgumentException("CSV rows to map to orders XML must not be null");
        }
        DwXmlWriter.Element orders = DwXmlWriter.element(ORDERS);
        for (Map<String, String> row : rows) {
            DwXmlWriter.Element order = DwXmlWriter.element(ORDER);
            for (String field : ORDER_FIELDS) {
                order.child(DwXmlWriter.element(field).text(row.get(field)));
            }
            orders.child(order);
        }
        return dwXmlWriter.write(orders);
    }
}
