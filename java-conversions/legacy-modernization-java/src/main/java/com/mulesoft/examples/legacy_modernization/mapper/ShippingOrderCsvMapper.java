/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.legacy_modernization.mapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.mulesoft.examples.legacy_modernization.model.Address;
import com.mulesoft.examples.legacy_modernization.model.Order;
import com.mulesoft.examples.legacy_modernization.model.OrderItem;
import com.mulesoft.examples.legacy_modernization.model.ShippingOrder;
import com.mulesoft.examples.legacy_modernization.model.ShippingOrderConfirmation;

/**
 * Hand-written port of the "Map Fullfilment order to CSV" DataWeave 1.0 script
 * ({@code %output application/csv header=true}) of the original {@code FufillmentWebService.xml}, which runs in the
 * flow's {@code async} scope over the {@link ShippingOrderConfirmation} returned by the fulfillment component.
 *
 * <p>One row per {@code shippingOrder.order.orderItemList} element, in list order, with the script's fifteen
 * columns in the script's order; the billing, shipping and shipping-ID columns repeat the order-level values on
 * every row. The bytes are rendered by {@link DwCsvWriter}, so a {@code null} value becomes an empty field.
 * The column order is the script's, not that of the illustrative {@code LegacySystemFulfillmentOrder_Example.csv}.
 *
 * <p>Instances are stateless and safe to share between threads, as the asynchronous export requires.
 */
@Component
public class ShippingOrderCsvMapper {

    private static final String MSKU = "MSKU";
    private static final String QTY = "QTY";
    private static final String BILLING_ADDRESS_NAME = "BillingAddressName";
    private static final String BILLING_ADDRESS_STREET = "BillingAddressStreet";
    private static final String BILLING_ADDR_CITY = "BillingAddrCity";
    private static final String BILLING_ADDR_STATE = "BillingAddrState";
    private static final String BILLING_ADDR_COUNTRY = "BillingAddrCountry";
    private static final String BILLING_ADDR_ZIP_CODE = "BillingAddrZipCode";
    private static final String SHIPPING_ADDR_NAME = "ShippingAddrName";
    private static final String SHIPPING_ADDR_STREET = "ShippingAddrStreet";
    private static final String SHIPPING_ADDR_CITY = "ShippingAddrCity";
    private static final String SHIPPING_ADDR_STATE = "ShippingAddrState";
    private static final String SHIPPING_ADDR_COUNTRY = "ShippingAddrCountry";
    private static final String SHIPPING_ADDR_ZIP_CODE = "ShippingAddrZipCode";
    private static final String SHIPPING_ID = "ShippingId";

    private static final String BILLING_ADDRESS = "billingAddress";
    private static final String SHIPPING_ADDRESS = "shippingAddress";

    private final DwCsvWriter dwCsvWriter;

    /**
     * Creates the mapper.
     *
     * @param dwCsvWriter the writer that renders the rows as DataWeave 1.0 CSV bytes
     */
    public ShippingOrderCsvMapper(DwCsvWriter dwCsvWriter) {
        this.dwCsvWriter = dwCsvWriter;
    }

    /**
     * Renders the confirmation as the script's CSV output: a header row and one LF-terminated line per order item.
     *
     * @param confirmation the confirmation the fulfillment service returned
     * @return the CSV text; empty when the order has no items
     * @throws IllegalArgumentException when a part the script navigates is {@code null}, as described at
     *                                  {@link #map(ShippingOrderConfirmation)}
     */
    public String toCsv(ShippingOrderConfirmation confirmation) {
        return dwCsvWriter.write(map(confirmation), true);
    }

    /**
     * Builds the script's records: one insertion-ordered map per order item, keyed by the fifteen column names.
     *
     * <p>{@code QTY} is the item's quantity as an {@link Integer}; every other value is the model's {@code String},
     * {@code null} included. The street columns join {@code line1} and {@code line2} with a single space.
     *
     * <p>The confirmation, its shipping order, the order and the item list are checked before any row is built,
     * because the script maps over that path; the addresses and their {@code line1}/{@code line2} are checked while
     * each row is built, as the map body evaluates them, so an empty item list yields no rows whatever the
     * addresses hold.
     *
     * @param confirmation the confirmation the fulfillment service returned
     * @return a new mutable list of the rows, empty when the order has no items
     * @throws IllegalArgumentException when the confirmation, {@code shippingOrder}, {@code shippingOrder.order} or
     *                                  {@code shippingOrder.order.orderItemList} is {@code null}, or, for a
     *                                  non-empty item list, when an address or one of its two street lines is
     *                                  {@code null}; the message names the {@code null} part
     */
    public List<Map<String, Object>> map(ShippingOrderConfirmation confirmation) {
        // The script fails at runtime on these nulls (map over null, ++ on null); the exact DataWeave 1.0 error
        // text is a Tier 2A verification point, so each failure names the null part of the navigated path.
        if (confirmation == null) {
            throw new IllegalArgumentException("ShippingOrderConfirmation is null");
        }
        ShippingOrder shippingOrder = confirmation.getShippingOrder();
        if (shippingOrder == null) {
            throw new IllegalArgumentException("shippingOrder is null");
        }
        Order order = shippingOrder.getOrder();
        if (order == null) {
            throw new IllegalArgumentException("shippingOrder.order is null");
        }
        List<OrderItem> items = order.getOrderItemList();
        if (items == null) {
            throw new IllegalArgumentException("shippingOrder.order.orderItemList is null");
        }

        Address billing = shippingOrder.getBillingAddress();
        Address shipping = shippingOrder.getShippingAddress();
        List<Map<String, Object>> rows = new ArrayList<>(items.size());
        for (OrderItem item : items) {
            // Checked per row, billing before shipping, as the script's map body meets them; street() also
            // guarantees both addresses are non-null for the reads below.
            String billingStreet = street(billing, BILLING_ADDRESS);
            String shippingStreet = street(shipping, SHIPPING_ADDRESS);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(MSKU, item.getMerchantSKU());
            row.put(QTY, Integer.valueOf(item.getQuantity()));
            row.put(BILLING_ADDRESS_NAME, billing.getName());
            row.put(BILLING_ADDRESS_STREET, billingStreet);
            row.put(BILLING_ADDR_CITY, billing.getCity());
            row.put(BILLING_ADDR_STATE, billing.getStateOrProvinceCode());
            row.put(BILLING_ADDR_COUNTRY, billing.getCountryCode());
            row.put(BILLING_ADDR_ZIP_CODE, billing.getPostalCode());
            row.put(SHIPPING_ADDR_NAME, shipping.getName());
            row.put(SHIPPING_ADDR_STREET, shippingStreet);
            row.put(SHIPPING_ADDR_CITY, shipping.getCity());
            row.put(SHIPPING_ADDR_STATE, shipping.getStateOrProvinceCode());
            row.put(SHIPPING_ADDR_COUNTRY, shipping.getCountryCode());
            row.put(SHIPPING_ADDR_ZIP_CODE, shipping.getPostalCode());
            row.put(SHIPPING_ID, shippingOrder.getShippingId());
            rows.add(row);
        }
        return rows;
    }

    // DataWeave's ++ fails on a null operand, so a missing address or street line fails the row instead of
    // producing "null" text; no trimming, exactly line1 + ' ' + line2.
    private static String street(Address address, String part) {
        if (address == null) {
            throw new IllegalArgumentException("shippingOrder." + part + " is null");
        }
        if (address.getLine1() == null) {
            throw new IllegalArgumentException("shippingOrder." + part + ".line1 is null");
        }
        if (address.getLine2() == null) {
            throw new IllegalArgumentException("shippingOrder." + part + ".line2 is null");
        }
        return address.getLine1() + " " + address.getLine2();
    }
}
