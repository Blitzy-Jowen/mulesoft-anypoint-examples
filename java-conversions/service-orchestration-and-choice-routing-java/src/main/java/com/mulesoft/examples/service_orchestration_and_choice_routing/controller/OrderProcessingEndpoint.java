/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.controller;

import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.Order;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.soap.ProcessOrder;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.soap.ProcessOrderResponse;
import com.mulesoft.examples.service_orchestration_and_choice_routing.service.OrderOrchestrationService;

import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;

/**
 * The inbound half of the original {@code orderService} flow ({@code fulfillment.xml:28-30}): the
 * {@code http:listener} with {@code allowedMethods="POST"} and {@code path="orders"} on port 1080
 * ({@code HTTP_Listener_Configuration3}, line 29) and the {@code cxf:jaxws-service} over
 * {@code com.mulesoft.se.orders.IProcessOrder} (line 30).
 *
 * <p>It answers the document/literal-wrapped {@code IProcessOrder.processOrder} operation, whose interface declared
 * {@code @WebResult(name = "summary") Order processOrder(@WebParam(name = "order") Order order)}: request element
 * {@code processOrder} and response element {@code processOrderResponse}, both in {@value ProcessOrder#NAMESPACE_URI},
 * each carrying one unqualified child, {@code order} and {@code summary}. The wrappers are {@link ProcessOrder} and
 * {@link ProcessOrderResponse}, which now carry the wire shape the JAX-WS runtime used to derive.
 *
 * <p>The rest of the flow, the item loop with its Samsung or in-house choice, the enrichers, the audit dispatch and the
 * catch strategy that runs {@code defaultErrorHandler}, belongs to {@link OrderOrchestrationService#processOrder}.
 * This class only binds the request and returns the result; it carries no port, path, routing or error logic. What the
 * listener and CXF did around the operation is carried by the project's shared components:
 * <ul>
 *   <li>Port 1080: SC-10's {@code PortRoutingFilter}, whose {@code main} route forwards {@code /orders} to
 *       {@code messageDispatcherServlet} and answers the path on any other port with the no-listener 404.</li>
 *   <li>POST only, and the {@code soap:Client} fault for a body that is not a SOAP 1.1 envelope: SC-11's
 *       {@code SoapListenerBoundaryAdapter}, before any endpoint is resolved. No WSDL is published, so
 *       {@code GET /orders?wsdl} receives the listener's 405 as the original's did.</li>
 *   <li>A {@code processOrder} posted to another listener path: the project's listener-path guard, which answers with
 *       CXF's {@code soap:Client} unknown-part fault.</li>
 *   <li>An exception escaping {@link OrderOrchestrationService#processOrder}, which has already logged
 *       {@code Error occurred: <payload>}: SC-11's {@code SoapFaultConfig}, which answers with a {@code soap:Server}
 *       fault carrying the exception message over HTTP 500.</li>
 *   <li>The reply bytes of {@code reply.xml}: the {@code ordersMarshaller}, bound to {@code model.soap} and
 *       {@code model.orders} only, writes the single {@code ns2} declaration, and SC-11's
 *       {@code SoapEnvelopeStyleInterceptor} gives the envelope CXF's {@code soap:} prefix, no {@code Header} and
 *       compact form.</li>
 * </ul>
 *
 * <p>The class holds no per-request state, so one instance serves concurrent requests.
 */
@Endpoint
public class OrderProcessingEndpoint {

    private final OrderOrchestrationService orderOrchestrationService;

    /**
     * @param orderOrchestrationService the {@code orderService} flow behind the operation
     */
    public OrderProcessingEndpoint(OrderOrchestrationService orderOrchestrationService) {
        this.orderOrchestrationService = orderOrchestrationService;
    }

    /**
     * Processes the request's order through {@link OrderOrchestrationService#processOrder(Order)} and wraps the
     * processed order, each item now carrying its purchase receipt, in {@code processOrderResponse} as its
     * {@code summary}.
     *
     * <p>No failure is handled here: every exception propagates unchanged and becomes a {@code soap:Server} fault.
     *
     * @param request the {@code processOrder} request wrapper
     * @return the {@code processOrderResponse} wrapper holding the processed order
     */
    @PayloadRoot(namespace = ProcessOrder.NAMESPACE_URI, localPart = ProcessOrder.LOCAL_PART)
    @ResponsePayload
    public ProcessOrderResponse processOrder(@RequestPayload ProcessOrder request) {
        Order summary = orderOrchestrationService.processOrder(request.getOrder());
        return new ProcessOrderResponse(summary);
    }
}
