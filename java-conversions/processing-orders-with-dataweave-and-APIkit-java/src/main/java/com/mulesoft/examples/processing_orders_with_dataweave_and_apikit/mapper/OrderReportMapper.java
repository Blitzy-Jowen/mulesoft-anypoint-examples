/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.mapper;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Hand-written owner of the {@code set-variable report} script of the {@code Process Orders and Create Report}
 * transform in {@code processing-orders-with-dataweave-and-APIkit/src/main/app/books.xml} (lines 30-37):
 * <pre>{@code
 * %dw 1.0
 * %output application/csv
 * ---
 * [{
 *   totalNumberOfItems: sizeOf payload.orders.*item,
 *   totalAmount: sum payload.orders.*item.price,
 *   averageSellingPrice: avg payload.orders.*item.price
 * }]
 * }</pre>
 *
 * <p>The script reads the transform's input payload, so the report covers every item of the orders document,
 * whatever its year; the year filter belongs to the sibling {@code set-payload} script ({@link OrderMapper}) only.
 * {@link #map(Document)} builds the script's one-record result and {@link #toCsv(Document)} writes it through SC-4
 * {@link DwCsvWriter}, so the bundled {@code orders.xml} produces the original {@code report.csv} byte for byte:
 * {@code totalNumberOfItems,totalAmount,averageSellingPrice\n4,149.93,37.4825\n}.
 *
 * <p>Selection and values:
 * <ul>
 *   <li>Element names match by local name, as a DataWeave selector without a namespace prefix does, so a
 *       namespace-aware and a namespace-unaware DOM select the same elements.</li>
 *   <li>The items are the {@code item} element children of the {@code orders} root, in document order. A
 *       document without a root, or with a root of any other name, has no items.</li>
 *   <li>An item's price is the trimmed text of its first direct {@code price} child, read as an exact
 *       {@link BigDecimal}.</li>
 *   <li>{@code totalNumberOfItems} is the item count as an {@link Integer}; {@code totalAmount} is the exact sum of
 *       the prices, its scale the largest operand scale ({@code 30 + 29.99 + 49.99 + 39.95 = 149.93});
 *       {@code averageSellingPrice} is {@code totalAmount / count} rounded to {@link MathContext#DECIMAL128}, which
 *       leaves a terminating quotient exact ({@code 37.4825}) and gives a non-terminating one 34 significant
 *       digits. That precision is a plan default and a Tier 2A verification point: a fixture captured from the
 *       original runtime that shows other digits governs.</li>
 * </ul>
 *
 * <p>The class is stateless and thread-safe.
 */
@Component
public class OrderReportMapper {

    private static final String ORDERS = "orders";
    private static final String ITEM = "item";
    private static final String PRICE = "price";

    private static final String TOTAL_NUMBER_OF_ITEMS = "totalNumberOfItems";
    private static final String TOTAL_AMOUNT = "totalAmount";
    private static final String AVERAGE_SELLING_PRICE = "averageSellingPrice";

    private final DwCsvWriter dwCsvWriter;

    /**
     * Creates the mapper.
     *
     * @param dwCsvWriter the SC-4 writer producing DataWeave 1.0 CSV bytes
     */
    public OrderReportMapper(DwCsvWriter dwCsvWriter) {
        this.dwCsvWriter = Objects.requireNonNull(dwCsvWriter, "dwCsvWriter");
    }

    /**
     * Applies the script to an orders document.
     *
     * @param orders the parsed orders XML, the script's {@code payload}
     * @return a single-element list holding one {@link LinkedHashMap} with, in this order,
     *         {@code totalNumberOfItems} ({@link Integer}), {@code totalAmount} ({@link BigDecimal}) and
     *         {@code averageSellingPrice} ({@link BigDecimal})
     * @throws ArithmeticException when the document has no items, whose average is undefined, as DataWeave's
     *         {@code avg} of an empty array is
     * @throws IllegalArgumentException when an item has no {@code price} child
     * @throws NumberFormatException when an item's price is not a number
     * @throws NullPointerException when {@code orders} is {@code null}
     */
    public List<Map<String, Object>> map(Document orders) {
        Objects.requireNonNull(orders, "orders");
        int count = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        Element root = orders.getDocumentElement();
        if (root != null && ORDERS.equals(localName(root))) {
            for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element item && ITEM.equals(localName(item))) {
                    totalAmount = totalAmount.add(itemPrice(item));
                    count++;
                }
            }
        }
        BigDecimal averageSellingPrice = totalAmount.divide(BigDecimal.valueOf(count), MathContext.DECIMAL128);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(TOTAL_NUMBER_OF_ITEMS, Integer.valueOf(count));
        row.put(TOTAL_AMOUNT, totalAmount);
        row.put(AVERAGE_SELLING_PRICE, averageSellingPrice);
        return List.of(row);
    }

    /**
     * Applies the script and writes its result as DataWeave 1.0 CSV, with a header row.
     *
     * @param orders the parsed orders XML, the script's {@code payload}
     * @return the CSV text, every line ending with a line feed
     * @throws ArithmeticException as {@link #map(Document)}
     * @throws IllegalArgumentException as {@link #map(Document)}
     * @throws NumberFormatException as {@link #map(Document)}
     * @throws NullPointerException as {@link #map(Document)}
     */
    public String toCsv(Document orders) {
        return dwCsvWriter.write(map(orders));
    }

    private static BigDecimal itemPrice(Element item) {
        Element price = firstChild(item, PRICE);
        if (price == null) {
            throw new IllegalArgumentException("item has no price");
        }
        return new BigDecimal(price.getTextContent().trim());
    }

    /** First direct element child with the given local name; {@code null} when there is none. */
    private static Element firstChild(Element parent, String name) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && name.equals(localName(element))) {
                return element;
            }
        }
        return null;
    }

    /** Local name of an element, also for a DOM built without namespace awareness, where it is {@code null}. */
    private static String localName(Element element) {
        String localName = element.getLocalName();
        if (localName != null) {
            return localName;
        }
        String nodeName = element.getNodeName();
        return nodeName.substring(nodeName.indexOf(':') + 1);
    }
}
