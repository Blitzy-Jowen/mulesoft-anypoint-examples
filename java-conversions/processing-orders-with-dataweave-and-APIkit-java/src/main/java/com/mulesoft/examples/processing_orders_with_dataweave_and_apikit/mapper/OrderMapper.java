/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.mapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Hand-written owner of the {@code set-payload} script of the {@code Process Orders and Create Report}
 * transform in {@code processing-orders-with-dataweave-and-APIkit/src/main/app/books.xml} (lines 14-29):
 * <pre>{@code
 * %dw 1.0
 * %output application/json
 * ---
 * {
 *   orders: payload.orders.*item filter $.properties.year > 2004 map
 *     using (item = $) {
 *     title: $.properties.title,
 *     prices: flowVars.currencies.USD map {
 *       price: $.ratio * item.price,
 *       currency: $.currency
 *     },
 *     authors: $.properties.authors distinctBy $ map {
 *       author: $
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>{@link #map(Document, JsonNode)} builds the script's result as {@link LinkedHashMap}s and
 * {@link ArrayList}s in the script's key order ({@code orders}; {@code title}, {@code prices}, {@code authors};
 * {@code price}, {@code currency}; {@code author}), and {@link #toJson(Document, JsonNode)} writes it through
 * SC-4 {@link DwJsonWriter}, so the bundled {@code orders.xml} and {@code currency.json} produce the original
 * {@code orders.json} byte for byte.
 *
 * <p>Selection and values:
 * <ul>
 *   <li>Element names match by local name, as a DataWeave selector without a namespace prefix does, so a
 *       namespace-aware and a namespace-unaware DOM select the same elements.</li>
 *   <li>The items are the {@code item} element children of the {@code orders} root, in document order. A root
 *       of any other name selects nothing and yields {@code {"orders": []}}.</li>
 *   <li>An item is kept when {@code properties/year} is numerically greater than 2004; an item without that
 *       element is dropped, and a year that is not a number raises {@link NumberFormatException}.</li>
 *   <li>{@code price} is {@code ratio * price} in exact {@link BigDecimal} arithmetic, so its scale is the sum
 *       of the operand scales, as DataWeave's ({@code 0.92 * 30 = 27.60}, {@code 0.92 * 29.99 = 27.5908}). The
 *       ratio is read from the currency entry, a JSON number or numeric text; the price is the item's direct
 *       {@code price} child, read only when {@code USD} has at least one entry. A textual ratio or a price that
 *       is not a number raises {@link NumberFormatException}; a missing ratio, a ratio that is neither a number
 *       nor text, and a missing price raise {@link IllegalArgumentException}. Callers parse
 *       {@code currency.json} with exact decimals so that {@code 0.92} keeps scale 2.</li>
 *   <li>{@code currency} is the entry's value as DataWeave carried it: a string, a {@link BigDecimal}, a
 *       {@link Boolean}, or {@code null} when absent or JSON null; an object or array raises
 *       {@link IllegalArgumentException}.</li>
 *   <li>{@code authors} lists the text of each child of {@code properties/authors}, whatever its name, once per
 *       distinct text, in order of first occurrence.</li>
 * </ul>
 *
 * <p>Plan defaults, which no committed output of the original settles and which are Tier 2A verification points:
 * a {@code currencies} tree without {@code USD}, or with a JSON-null {@code USD}, gives {@code "prices": null};
 * an item without {@code properties/authors} gives {@code "authors": null}; a {@code USD} value that is not an
 * array raises {@link IllegalArgumentException}.
 *
 * <p>The class is stateless and thread-safe.
 */
@Component
public class OrderMapper {

    private static final String ORDERS = "orders";
    private static final String ITEM = "item";
    private static final String PROPERTIES = "properties";
    private static final String YEAR = "year";
    private static final String TITLE = "title";
    private static final String PRICE = "price";
    private static final String AUTHORS = "authors";
    private static final String AUTHOR = "author";
    private static final String PRICES = "prices";
    private static final String CURRENCY = "currency";
    private static final String RATIO = "ratio";
    private static final String USD = "USD";

    private static final BigDecimal MIN_EXCLUSIVE_YEAR = BigDecimal.valueOf(2004);

    private final DwJsonWriter dwJsonWriter;

    /**
     * Creates the mapper.
     *
     * @param dwJsonWriter the SC-4 writer producing DataWeave 1.0 JSON bytes
     */
    public OrderMapper(DwJsonWriter dwJsonWriter) {
        this.dwJsonWriter = Objects.requireNonNull(dwJsonWriter, "dwJsonWriter");
    }

    /**
     * Applies the script to an orders document.
     *
     * @param orders the parsed orders XML, the script's {@code payload}
     * @param currencies the currencies tree, the script's {@code flowVars.currencies}; {@code null} is treated as
     *        a tree without {@code USD}
     * @return a {@link LinkedHashMap} holding {@code orders}: a {@link List} of one {@link LinkedHashMap} per kept
     *         item with {@code title} ({@link String} or {@code null}), {@code prices} ({@link List} of
     *         {@code {price: BigDecimal, currency}} maps, or {@code null}) and {@code authors} ({@link List} of
     *         {@code {author: String}} maps, or {@code null})
     * @throws NumberFormatException when a kept item's year, price or a ratio is not a number
     * @throws IllegalArgumentException when {@code USD} is not an array, an entry has no usable ratio or an
     *         object or array currency, or a kept item has no price while {@code USD} has entries
     */
    public Map<String, Object> map(Document orders, JsonNode currencies) {
        Objects.requireNonNull(orders, "orders");
        List<Object> kept = new ArrayList<>();
        Element root = orders.getDocumentElement();
        if (root != null && ORDERS.equals(localName(root))) {
            JsonNode usd = currencies == null ? null : currencies.get(USD);
            for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element item && ITEM.equals(localName(item)) && isAfter2004(item)) {
                    kept.add(mapItem(item, usd));
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(ORDERS, kept);
        return result;
    }

    /**
     * Applies the script and writes its result as DataWeave 1.0 JSON.
     *
     * @param orders the parsed orders XML, the script's {@code payload}
     * @param currencies the currencies tree, the script's {@code flowVars.currencies}
     * @return the JSON text, without a trailing line feed
     * @throws NumberFormatException as {@link #map(Document, JsonNode)}
     * @throws IllegalArgumentException as {@link #map(Document, JsonNode)}
     */
    public String toJson(Document orders, JsonNode currencies) {
        return dwJsonWriter.write(map(orders, currencies));
    }

    private static boolean isAfter2004(Element item) {
        Element year = firstChild(firstChild(item, PROPERTIES), YEAR);
        return year != null && new BigDecimal(year.getTextContent().trim()).compareTo(MIN_EXCLUSIVE_YEAR) > 0;
    }

    private static Map<String, Object> mapItem(Element item, JsonNode usd) {
        Element properties = firstChild(item, PROPERTIES);
        Element title = firstChild(properties, TITLE);
        Map<String, Object> mapped = new LinkedHashMap<>();
        mapped.put(TITLE, title == null ? null : title.getTextContent());
        mapped.put(PRICES, prices(item, usd));
        mapped.put(AUTHORS, authors(firstChild(properties, AUTHORS)));
        return mapped;
    }

    private static List<Object> prices(Element item, JsonNode usd) {
        if (usd == null || usd.isNull()) {
            return null;
        }
        if (!usd.isArray()) {
            throw new IllegalArgumentException("currencies.USD is not an array");
        }
        List<Object> prices = new ArrayList<>(usd.size());
        BigDecimal itemPrice = null;
        for (int index = 0; index < usd.size(); index++) {
            JsonNode entry = usd.get(index);
            BigDecimal ratio = ratio(entry, index);
            if (itemPrice == null) {
                itemPrice = itemPrice(item);
            }
            Map<String, Object> price = new LinkedHashMap<>();
            price.put(PRICE, ratio.multiply(itemPrice));
            price.put(CURRENCY, currency(entry, index));
            prices.add(price);
        }
        return prices;
    }

    private static BigDecimal ratio(JsonNode entry, int index) {
        JsonNode ratio = entry.get(RATIO);
        if (ratio != null && ratio.isNumber()) {
            return ratio.decimalValue();
        }
        if (ratio != null && ratio.isTextual()) {
            return new BigDecimal(ratio.textValue().trim());
        }
        throw new IllegalArgumentException("currencies.USD[" + index + "] has no numeric ratio");
    }

    private static BigDecimal itemPrice(Element item) {
        Element price = firstChild(item, PRICE);
        if (price == null) {
            throw new IllegalArgumentException("item has no price");
        }
        return new BigDecimal(price.getTextContent().trim());
    }

    private static Object currency(JsonNode entry, int index) {
        JsonNode currency = entry.get(CURRENCY);
        if (currency == null || currency.isNull()) {
            return null;
        }
        if (currency.isTextual()) {
            return currency.asText();
        }
        if (currency.isNumber()) {
            return currency.decimalValue();
        }
        if (currency.isBoolean()) {
            return currency.booleanValue();
        }
        throw new IllegalArgumentException("currencies.USD[" + index + "].currency is not a scalar value");
    }

    private static List<Object> authors(Element authors) {
        if (authors == null) {
            return null;
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (Node child = authors.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element author) {
                distinct.add(author.getTextContent());
            }
        }
        List<Object> mapped = new ArrayList<>(distinct.size());
        for (String name : distinct) {
            Map<String, Object> author = new LinkedHashMap<>();
            author.put(AUTHOR, name);
            mapped.add(author);
        }
        return mapped;
    }

    /** First direct element child with the given local name; {@code null} when there is none or no parent. */
    private static Element firstChild(Element parent, String name) {
        if (parent == null) {
            return null;
        }
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
