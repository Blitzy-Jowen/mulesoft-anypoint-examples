/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.web_service_consumer.service;

import org.springframework.stereotype.Service;
import org.w3c.dom.Document;

import com.mulesoft.examples.web_service_consumer.client.TshirtServiceClient;
import com.mulesoft.examples.web_service_consumer.mapper.DwXmlToJson;
import com.mulesoft.examples.web_service_consumer.mapper.ListInventoryResponseMapper;
import com.mulesoft.examples.web_service_consumer.mapper.OrderTshirtRequestMapper;
import com.mulesoft.examples.web_service_consumer.mapper.OrderTshirtResponseMapper;

/**
 * Orchestration of the two flows of {@code web-service-consumer/src/main/app/tshirt-service-consumer.xml}:
 * {@code orderTshirt} (lines 6-33) and {@code listInventory} (lines 34-47). Each method runs the processors that
 * follow its flow's HTTP listener, in the flow's order; the controller only binds the request body and writes
 * the returned JSON.
 *
 * <p>The DataWeave scripts are owned elsewhere: script 12 ({@code ns0#OrderTshirt: payload}) by
 * {@link OrderTshirtRequestMapper}, script 17 (the {@code AuthenticationHeader} SOAP header) by
 * {@link TshirtServiceClient}, script 28 ({@code payload} as JSON) by {@link OrderTshirtResponseMapper} and
 * script 41 ({@code payload.ns0#ListInventoryResponse}) by {@link ListInventoryResponseMapper}.
 *
 * <p>Neither flow declares an exception strategy, so every failure (an unreadable body, a SOAP fault, a
 * transport error) propagates unchanged to the platform-default 500 handler and is never retried.
 *
 * <p>Stateless apart from its final collaborators, all of them thread-safe, so one instance serves every request.
 */
@Service
public class TshirtOrderService {

    private final OrderTshirtRequestMapper requestMapper;

    private final TshirtServiceClient client;

    private final OrderTshirtResponseMapper orderResponseMapper;

    private final ListInventoryResponseMapper inventoryResponseMapper;

    private final DwXmlToJson dwXmlToJson;

    /**
     * Creates the service.
     *
     * @param requestMapper builds the {@code OrderTshirt} request body from the JSON order (script 12)
     * @param client the SOAP client of {@code TshirtService}, which also adds the API-key header (script 17)
     * @param orderResponseMapper writes the {@code OrderTshirt} response as JSON (script 28)
     * @param inventoryResponseMapper writes the selected {@code ListInventoryResponse} as JSON (script 41)
     * @param dwXmlToJson the shared XML reader that parses a supplied {@code ListInventory} request body
     */
    public TshirtOrderService(OrderTshirtRequestMapper requestMapper, TshirtServiceClient client,
            OrderTshirtResponseMapper orderResponseMapper, ListInventoryResponseMapper inventoryResponseMapper,
            DwXmlToJson dwXmlToJson) {
        this.requestMapper = requestMapper;
        this.client = client;
        this.orderResponseMapper = orderResponseMapper;
        this.inventoryResponseMapper = inventoryResponseMapper;
        this.dwXmlToJson = dwXmlToJson;
    }

    /**
     * Flow {@code orderTshirt} (lines 6-33): the "JSON to XML and set API key" transform (line 10), the
     * {@code OrderTshirt} web service call (line 25) and the "XML to JSON" transform (lines 26-32).
     *
     * @param body the raw request body posted to {@code /orders}; may be {@code null}
     * @return the DataWeave JSON of the {@code OrderTshirt} response payload, or {@code null} when the service
     *         returned no payload
     * @throws IllegalArgumentException when the body is missing, empty or not a JSON order the request mapper
     *         accepts; raised before the service is called
     */
    public String orderTshirt(byte[] body) {
        // A missing body is handed to the mapper as empty, so it is rejected there with the mapper's own message.
        Document request = requestMapper.toDocument(body == null ? new byte[0] : body);
        Document response = client.orderTshirt(request);
        return orderResponseMapper.toJson(response);
    }

    /**
     * Flow {@code listInventory} (lines 34-47): the {@code ListInventory} web service call (line 38) and the
     * "XML to JSON" transform (lines 39-46). The flow has no request transform: the consumer sent the payload
     * as the SOAP body, and the operation's empty request element when there was no payload.
     *
     * @param body the raw request body sent to {@code /inventory}; {@code null} or empty sends the empty
     *        {@code ListInventory} request, anything else is sent as the SOAP body
     * @return the DataWeave JSON of the {@code ListInventoryResponse} content, never {@code null}; the JSON literal
     *         {@code null} when the service returned no payload, another root or an empty
     *         {@code ListInventoryResponse}
     * @throws IllegalArgumentException when a non-empty body is not well-formed XML or declares a DOCTYPE; raised
     *         before the service is called
     */
    public String listInventory(byte[] body) {
        Document request = body == null || body.length == 0 ? null : dwXmlToJson.parse(body);
        Document response = client.listInventory(request);
        return inventoryResponseMapper.toJson(response);
    }
}
