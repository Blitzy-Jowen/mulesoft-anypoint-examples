/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.mapper;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

import com.mulesoft.examples.service_orchestration_and_choice_routing.mapper.DwXmlToJson.DwObject;

/**
 * Hand-written reimplementation of the {@code %dw 1.0} script "XML to JSON"
 * ({@code service-orchestration-and-choice-routing/src/main/app/mule-config.xml}, flow {@code orderRequest},
 * header line 27), of which this class is the sole owner:
 * <pre>
 * %dw 1.0
 * %output application/json
 * ---
 * {
 *     orderId:  payload.summary.orderId,
 *     customer: payload.summary.customer,
 *     orderItems: payload.summary.orderItems
 * }
 * </pre>
 *
 * <p>The input is the text {@code SoapEnvelopeToBodyChild.xslt} produces from the 1080 {@code processOrderResponse}
 * ({@code SoapEnvelopeTransformer.toBodyChild}): the {@code summary} element, possibly preceded by an XML
 * declaration and carrying in-scope namespace declarations. DataWeave read it with its XML reader
 * ({@link DwXmlToJson}: local-name keys, namespaces and attributes dropped, string leaves, a repeated element a
 * repeated key) and wrote the object with its JSON writer ({@link DwJsonWriter}: two-space indentation,
 * {@code "key": value}, LF line ends, no trailing line feed). The output is the RPC reply of the
 * {@code /orders/request} Ajax channel. For {@code sample_data/summary_1.xml} it is:
 * <pre>{@code
 * {
 *   "orderId": "12",
 *   "customer": {
 *     "address": "Main Street 123",
 *     "firstName": "John",
 *     "lastName": "Doe"
 *   },
 *   "orderItems": {
 *     "item": {
 *       "manufacturer": "Samsung",
 *       "name": "s-1",
 *       "productId": "AX02",
 *       "purchaseReceipt": {
 *         "id": "1",
 *         "status": "ACCEPTED",
 *         "totalPrice": "2550.0"
 *       },
 *       "quantity": "1"
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>Selection follows DataWeave's single-value selector: each {@code .key} takes the first entry with that local
 * name, and a selector that misses, or that is applied to a value that is not an object, yields {@code null},
 * which the writer prints as {@code null}. A document whose root is not {@code summary} therefore gives
 * {@code null} for all three keys. A selected element with neither text nor element children is written
 * {@code ""}, the empty string DataWeave 1.0's XML reader read it as: the {@code <orderItems/>} the 1080 service
 * answers for an order whose items it did not read gives {@code "orderItems": ""}, and an empty
 * {@code <firstName/>} gives {@code "firstName": ""}. The selected {@code customer} and {@code orderItems}
 * objects are written as read: an order with several items keeps one {@code "item"} key per {@code <item>}
 * element, in document order, rather than an array, and every leaf stays the string the reader produced.
 *
 * <p>Transformation only: no I/O, no configuration, no logging. Stateless and thread-safe.
 */
@Component
public class OrderSummaryJsonMapper {

    private static final String SUMMARY = "summary";

    private static final String ORDER_ID = "orderId";

    private static final String CUSTOMER = "customer";

    private static final String ORDER_ITEMS = "orderItems";

    private final DwXmlToJson dwXmlToJson;

    private final DwJsonWriter dwJsonWriter;

    /**
     * Creates the mapper.
     *
     * @param dwXmlToJson the DataWeave XML reader that parses the summary document into its object tree
     * @param dwJsonWriter the DataWeave JSON writer that renders the selected values
     */
    public OrderSummaryJsonMapper(DwXmlToJson dwXmlToJson, DwJsonWriter dwJsonWriter) {
        this.dwXmlToJson = dwXmlToJson;
        this.dwJsonWriter = dwJsonWriter;
    }

    /**
     * Writes the order summary as the JSON the script produced.
     *
     * @param summaryXml the {@code summary} document extracted from the {@code processOrderResponse} envelope
     * @return the DataWeave JSON text holding {@code orderId}, {@code customer} and {@code orderItems} in that
     *         order, without a trailing line feed
     * @throws IllegalArgumentException when {@code summaryXml} is {@code null}, empty, not well-formed or declares
     *         a DOCTYPE, as the bare text a SOAP fault leaves after the XSLT is; DataWeave's read failed there too
     */
    public String toJson(String summaryXml) {
        Document document = dwXmlToJson.parse(summaryXml);
        Object summary = select(dwXmlToJson.convert(document), SUMMARY);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(ORDER_ID, select(summary, ORDER_ID));
        result.put(CUSTOMER, select(summary, CUSTOMER));
        result.put(ORDER_ITEMS, select(summary, ORDER_ITEMS));
        return dwJsonWriter.write(result);
    }

    private static Object select(Object value, String key) {
        return value instanceof DwObject object ? object.get(key) : null;
    }
}
