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
 * Hand-written reimplementation of the {@code %dw 1.0} script "XML to JSON"
 * ({@code web-service-consumer/src/main/app/tshirt-service-consumer.xml}, flow {@code orderTshirt}, header line 28),
 * of which this class is the sole owner:
 * <pre>
 * %dw 1.0
 * %output application/json
 * ---
 * payload
 * </pre>
 * The input is the SOAP body payload the {@code OrderTshirt} operation returned. DataWeave read it with its XML
 * reader ({@link DwXmlToJson}: local-name keys, namespace prefixes and URIs dropped, string leaves, an element with
 * no text {@code null}) and wrote the whole document with its JSON writer ({@link DwJsonWriter}: two-space
 * indentation, {@code "key": value}, LF line ends, no trailing line feed). For the response
 * {@code <ns2:OrderTshirtResponse xmlns:ns2="http://mulesoft.org/tshirt-service"><orderId>1</orderId></ns2:OrderTshirtResponse>}
 * the result is:
 * <pre>{@code
 * {
 *   "OrderTshirtResponse": {
 *     "orderId": "1"
 *   }
 * }
 * }</pre>
 *
 * <p>Transformation only: no I/O, no configuration, no logging. Stateless and thread-safe.
 */
@Component
public class OrderTshirtResponseMapper {

    private final DwXmlToJson dwXmlToJson;

    private final DwJsonWriter dwJsonWriter;

    /**
     * Creates the mapper.
     *
     * @param dwXmlToJson the DataWeave XML reader that turns the response document into its object tree
     * @param dwJsonWriter the DataWeave JSON writer that renders the tree
     */
    public OrderTshirtResponseMapper(DwXmlToJson dwXmlToJson, DwJsonWriter dwJsonWriter) {
        this.dwXmlToJson = dwXmlToJson;
        this.dwJsonWriter = dwJsonWriter;
    }

    /**
     * Writes the {@code OrderTshirt} response payload as the JSON the script produced.
     *
     * @param doc the SOAP body payload returned by the operation; may be {@code null}
     * @return the DataWeave JSON text of the whole document, without a trailing line feed; {@code null} when
     *         {@code doc} is {@code null}, since there is no payload to write
     */
    public String toJson(Document doc) {
        if (doc == null) {
            return null;
        }
        return dwJsonWriter.write(dwXmlToJson.convert(doc));
    }
}
