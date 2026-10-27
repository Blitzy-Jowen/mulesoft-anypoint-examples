/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.controller;

import jakarta.xml.bind.JAXBElement;

import com.mulesoft.examples.service_orchestration_and_choice_routing.model.samsung.ObjectFactory;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.samsung.OrderResponse;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.samsung.Purchase;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.samsung.PurchaseResponse;
import com.mulesoft.examples.service_orchestration_and_choice_routing.service.SamsungServiceImpl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;

/**
 * The mock Samsung SOAP service the original exposed as the {@code samsungService} flow
 * ({@code fulfillment.xml:150-155}): an {@code http:listener} with {@code allowedMethods="POST"} and
 * {@code path="samsung/orders"} on 9090 (line 151), a {@code logger} at INFO with the message {@code aaaa} (line 152),
 * a {@code cxf:jaxws-service} over {@code com.mulesoft.se.samsung.SamsungService} (line 153) and the component
 * {@code com.mulesoft.se.samsung.SamsungServiceImpl} (line 154).
 *
 * <p>It answers the document/literal-wrapped {@code SamsungService.purchase} operation: request element
 * {@code purchase} and response element {@code purchaseResponse}, both in {@code http://samsung.se.mulesoft.com/},
 * each carrying one unqualified child, {@code orderRequest} and {@code orderResponse}, as the original's
 * {@code targetNamespace = ""} parameter and result declared. The wrappers are the generated {@code model.samsung} classes, which have no
 * {@code XmlRootElement}; their root elements come from {@link ObjectFactory}, so the method takes and returns
 * {@link JAXBElement}s. Their type arguments must stay visible in the signature: the marshaller bound to the
 * {@code model.samsung} package accepts a {@code JAXBElement} only through its type argument, and the order
 * service's marshaller rejects these types.
 *
 * <p>What the flow's listener and CXF did around the operation is carried by the project's shared components rather
 * than by this class:
 * <ul>
 *   <li>Port 9090: SC-10's {@code PortRoutingFilter} forwards {@code /samsung/orders} on the {@code samsung} listener
 *       port to {@code messageDispatcherServlet}, and answers the path on any other port with the no-listener
 *       404.</li>
 *   <li>POST only, and the {@code soap:Client} fault for a body that is not a SOAP 1.1 envelope: SC-11's
 *       {@code SoapListenerBoundaryAdapter}, before any endpoint is resolved.</li>
 *   <li>A {@code purchase} posted to another listener path, and an exception escaping this endpoint: the project's
 *       listener-path guard and SC-11's fault resolver, which answer with CXF's {@code soap:Client} and
 *       {@code soap:Server} faults over HTTP 500.</li>
 * </ul>
 *
 * <p>One ordering difference remains, a Tier 2A verification point: the original logged {@code aaaa} before CXF
 * parsed the body, so a malformed envelope was logged too. Here the boundary fault is produced before endpoint
 * dispatch, and {@code aaaa} is logged only for a {@code purchase} request that reaches this endpoint.
 *
 * <p>The class holds no per-request state, so one instance serves concurrent requests.
 */
@Endpoint
public class SamsungServiceEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger(SamsungServiceEndpoint.class);

    private static final ObjectFactory OBJECT_FACTORY = new ObjectFactory();

    /** Target namespace of {@code SamsungService} and of its request and response wrappers. */
    private static final String NAMESPACE_URI = "http://samsung.se.mulesoft.com/";

    /** Local name of the {@code purchase} request wrapper, the body root this endpoint is mapped to. */
    private static final String LOCAL_PART = "purchase";

    private final SamsungServiceImpl samsungServiceImpl;

    /**
     * @param samsungServiceImpl the service behind the operation, the original flow's component
     */
    public SamsungServiceEndpoint(SamsungServiceImpl samsungServiceImpl) {
        this.samsungServiceImpl = samsungServiceImpl;
    }

    /**
     * Logs the flow's {@code aaaa} line at INFO, then purchases the request's order through
     * {@link SamsungServiceImpl#purchase} and wraps its result in {@code purchaseResponse}.
     *
     * <p>No failure is handled here, as the original flow handled none: a request without {@code orderRequest} makes
     * the service fail, and that exception, like any other, becomes a {@code soap:Server} fault carrying its message
     * (CXF's {@code Fault occurred while processing.} when it has none) over HTTP 500. The order orchestration turns
     * such a fault into a {@code REJECTED} receipt.
     *
     * @param request the {@code purchase} request wrapper
     * @return the {@code purchaseResponse} wrapper holding the order response
     */
    @PayloadRoot(namespace = NAMESPACE_URI, localPart = LOCAL_PART)
    @ResponsePayload
    public JAXBElement<PurchaseResponse> purchase(@RequestPayload JAXBElement<Purchase> request) {
        LOG.info("aaaa");
        OrderResponse result = samsungServiceImpl.purchase(request.getValue().getOrderRequest());
        PurchaseResponse response = OBJECT_FACTORY.createPurchaseResponse();
        response.setOrderResponse(result);
        return OBJECT_FACTORY.createPurchaseResponse(response);
    }
}
