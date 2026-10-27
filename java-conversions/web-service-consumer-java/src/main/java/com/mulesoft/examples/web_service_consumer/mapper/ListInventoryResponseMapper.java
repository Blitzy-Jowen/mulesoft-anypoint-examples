/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.web_service_consumer.mapper;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

/**
 * Hand-written reimplementation of the {@code %dw 1.0} script "XML to JSON" of flow {@code listInventory}
 * ({@code web-service-consumer/src/main/app/tshirt-service-consumer.xml}, header line 41), of which this class is
 * the sole owner:
 * <pre>
 * %dw 1.0
 * %output application/json
 * %namespace ns0 http://mulesoft.org/tshirt-service
 * ---
 * payload.ns0#ListInventoryResponse
 * </pre>
 * The payload is the SOAP Body child the {@code ListInventory} operation returned. The selector picks the
 * {@code ListInventoryResponse} element in the T-shirt service namespace and the JSON writer serializes its
 * content, so the wrapper element itself does not appear in the output.
 *
 * <p>Output rules, as DataWeave 1.0 read the XML ({@link DwXmlToJson}) and wrote the JSON ({@link DwJsonWriter}):
 * <ul>
 *   <li>each {@code inventory} element is its own {@code "inventory"} key, repeated once per item rather than
 *       gathered into an array, in document order;</li>
 *   <li>leaves stay strings ({@code "count": "5"}) although {@code tshirt.wsdl} types {@code count} as
 *       {@code xsd:integer}, because the XML reader yields text;</li>
 *   <li>namespace prefixes and attributes are dropped; layout is two-space indentation, LF line ends and no
 *       trailing line feed;</li>
 *   <li>a selector that misses (no payload, another root, or {@code ListInventoryResponse} outside the
 *       {@code ns0} namespace) or selects an empty element yields null, which the JSON writer writes as the
 *       four characters {@code null}.</li>
 * </ul>
 * For the original sample {@code sample_data/ListInventoryResponse.xml} (five items) the result is:
 * <pre>{@code
 * {
 *   "inventory": {
 *     "productCode": "4102",
 *     "size": "L",
 *     "description": "Prueba",
 *     "count": "2"
 *   },
 *   ... one "inventory" entry each for 1412, 5656, 5657 and 1411 ...
 * }
 * }</pre>
 *
 * <p>Transformation only: no I/O, no configuration, no logging. Stateless and thread-safe.
 */
@Component
public class ListInventoryResponseMapper {

    /** Namespace of the T-shirt service messages, the script's {@code ns0}. */
    public static final String NAMESPACE = "http://mulesoft.org/tshirt-service";

    /** Local name of the response element of the {@code ListInventory} operation, the script's selector. */
    public static final String ROOT = "ListInventoryResponse";

    private final DwXmlToJson dwXmlToJson;

    private final DwJsonWriter dwJsonWriter;

    /**
     * Creates the mapper.
     *
     * @param dwXmlToJson the DataWeave XML reader that selects the response element and converts its content
     * @param dwJsonWriter the DataWeave JSON writer that renders the converted content
     */
    public ListInventoryResponseMapper(DwXmlToJson dwXmlToJson, DwJsonWriter dwJsonWriter) {
        this.dwXmlToJson = dwXmlToJson;
        this.dwJsonWriter = dwJsonWriter;
    }

    /**
     * Applies {@code payload.ns0#ListInventoryResponse} to the {@code ListInventory} response and writes the
     * selected content as DataWeave 1.0 JSON.
     *
     * @param doc the SOAP Body child returned by the {@code ListInventory} operation; may be {@code null}
     * @return the JSON text without a trailing line feed, never {@code null}; the JSON literal {@code null} when
     *         the document is {@code null}, its root is not {@code ListInventoryResponse} in {@link #NAMESPACE}, or
     *         that root is empty, because the selector then yields null and the JSON writer writes it as such
     */
    public String toJson(Document doc) {
        return dwJsonWriter.write(dwXmlToJson.select(doc, NAMESPACE, ROOT));
    }
}
