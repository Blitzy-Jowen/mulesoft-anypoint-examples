/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.legacy_modernization.controller;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;

import com.mulesoft.examples.legacy_modernization.model.ShippingOrderConfirmation;
import com.mulesoft.examples.legacy_modernization.model.soap.PutShippingOrder;
import com.mulesoft.examples.legacy_modernization.model.soap.PutShippingOrderResponse;
import com.mulesoft.examples.legacy_modernization.service.IFulfillment;
import com.mulesoft.examples.legacy_modernization.service.ShippingOrderExportService;

/**
 * The inbound side of the original {@code Fulfillment_LegacySystemModernization} flow
 * ({@code FufillmentWebService.xml}), which answers the document/literal-wrapped {@code IFulfillment.putShippingOrder}
 * operation in {@value #NAMESPACE_URI}:
 * <ul>
 *   <li>l.5 {@code <http:listener path="OrderFulfillment" allowedMethods="POST"/>}: the path and the POST-only rule
 *       belong to {@code config/WebServiceConfig}, which maps the {@code MessageDispatcherServlet} to exactly
 *       {@code /OrderFulfillment}, and to {@code config/SoapListenerBoundaryAdapter}, which answers every other method
 *       with the listener's 405; the port is {@code server.port} in {@code application.yml}.</li>
 *   <li>l.7 {@code <cxf:jaxws-service serviceClass="org.ordermgmt.IFulfillment"/>}: this class, mapped by
 *       {@code @PayloadRoot} to the {@link PutShippingOrder} request wrapper and answering with the
 *       {@link PutShippingOrderResponse} wrapper, the bindings that carry the JAX-WS wire shape.</li>
 *   <li>l.8 {@code <component class="org.ordermgmt.FulfillmentImpl"/>}: Mule created a new component instance per
 *       call, and the ported {@code service/FulfillmentService} keeps per-call state in public fields, so it is
 *       prototype-scoped and fetched from the {@link ObjectProvider} on every request rather than injected once.</li>
 *   <li>l.9-36 {@code <async>} (CSV mapping, string conversion, {@code file:outbound-endpoint}):
 *       {@link ShippingOrderExportService#export(ShippingOrderConfirmation)}, which runs on the task executor and
 *       never affects the SOAP response.</li>
 * </ul>
 * Envelope style, the {@code ns2} payload prefix and SOAP faults belong to the project's Spring WS configuration
 * ({@code config/SoapEnvelopeStyleInterceptor}, {@code config/WebServiceConfig}, {@code config/SoapFaultConfig}), not to
 * this class: an exception escaping the handler becomes a {@code soap:Server} fault, and a request with any other body
 * root becomes a {@code soap:Client} fault. The class holds no mutable state, so one instance serves all requests.
 */
@Endpoint
public class FulfillmentEndpoint {

    /** Target namespace of the original service, the one JAX-WS derived from the {@code org.ordermgmt} package. */
    public static final String NAMESPACE_URI = "http://ordermgmt.org/";

    /** Local name of the operation's request wrapper. */
    public static final String LOCAL_PART = "putShippingOrder";

    private final ObjectProvider<IFulfillment> fulfillmentProvider;
    private final ShippingOrderExportService shippingOrderExportService;

    /**
     * @param fulfillmentProvider        source of a fresh fulfillment component for each request
     * @param shippingOrderExportService the asynchronous legacy fulfillment export
     */
    public FulfillmentEndpoint(ObjectProvider<IFulfillment> fulfillmentProvider,
            ShippingOrderExportService shippingOrderExportService) {
        this.fulfillmentProvider = fulfillmentProvider;
        this.shippingOrderExportService = shippingOrderExportService;
    }

    /**
     * Processes the shipping order in the original flow's processor order: the fulfillment component builds the
     * confirmation, the asynchronous export is started with it, and the confirmation is returned as the operation's
     * result.
     *
     * <p>A {@code null} confirmation, which the component returns when building it fails, is exported and returned as
     * it is, so the reply is the empty {@code putShippingOrderResponse} CXF wrote for a {@code null} result.
     *
     * @param request the unmarshalled {@code putShippingOrder} wrapper
     * @return the {@code putShippingOrderResponse} wrapper around the component's confirmation
     */
    @PayloadRoot(namespace = NAMESPACE_URI, localPart = LOCAL_PART)
    @ResponsePayload
    public PutShippingOrderResponse putShippingOrder(@RequestPayload PutShippingOrder request) {
        IFulfillment fulfillment = fulfillmentProvider.getObject();
        ShippingOrderConfirmation confirmation = fulfillment.putShippingOrder(request.getShippingId(),
                request.getBillingAddress(), request.getShippingAddress(), request.getOrder());
        shippingOrderExportService.export(confirmation);
        return new PutShippingOrderResponse(confirmation);
    }
}
