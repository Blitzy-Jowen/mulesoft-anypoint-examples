/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.xml_only_soap_webservice.controller;

import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;
import org.w3c.dom.Element;

import com.mulesoft.examples.xml_only_soap_webservice.config.WebServiceConfig;
import com.mulesoft.examples.xml_only_soap_webservice.service.EhrMockService;

/**
 * The entry point of the mock {@code EHRService} flow
 * ({@code xml-only-soap-webservice/src/main/app/mocks.xml:45-47}): the {@code http:listener path="EHRService"} on
 * {@code 0.0.0.0:${http.port}}, which declares no {@code allowedMethods}, and the
 * {@code cxf:proxy-service payload="body"} for service {@code EHRService}, port {@code EHRPort}, that hands the flow
 * the SOAP Body content.
 * <ul>
 *   <li>Dispatch is by listener path, not by payload root: a CXF proxy service accepted any Body root, so this class
 *       declares no payload-root or SOAP-action mapping. {@link WebServiceConfig}'s
 *       {@code ListenerPathEndpointMapping} hands every request on {@value WebServiceConfig#EHR_PATH} to
 *       {@link #handle(Element)} of the bean named {@value WebServiceConfig#EHR_ENDPOINT}, which is this class.</li>
 *   <li>The operation choice of {@code mocks.xml:48-83} and its two response scripts belong to
 *       {@link EhrMockService} and {@code mapper/EhrMockResponseMapper}.</li>
 *   <li>The CXF envelope style of the response is applied by SC-11 {@code config/SoapEnvelopeStyleInterceptor}; an
 *       exception escaping the handler becomes a {@code soap:Server} fault through SC-11
 *       {@code config/SoapFaultConfig}, and an unreadable request a {@code soap:Client} fault through SC-11
 *       {@code config/SoapListenerBoundaryAdapter}.</li>
 *   <li>{@code GET /EHRService?wsdl} serves the carried {@code service/EHRService.wsdl} (the proxy's
 *       {@code wsdlLocation}) through {@link WebServiceConfig} and SC-11 {@code config/WsdlQueryPublisher}.</li>
 * </ul>
 * The class holds no mutable state, so one instance serves all requests concurrently.
 */
@Endpoint(WebServiceConfig.EHR_ENDPOINT)
public class EhrServiceMockEndpoint {

    private final EhrMockService service;

    /**
     * @param service the routing of the {@code EHRService} flow
     */
    public EhrServiceMockEndpoint(EhrMockService service) {
        this.service = service;
    }

    /**
     * Answers one {@code EHRService} request with the flow's response Body content.
     *
     * @param request the SOAP Body's root element, or {@code null} for an empty Body
     * @return the response Body's root element, {@code createEpisodeResponse} or {@code findEpisodesResponse}
     */
    public @ResponsePayload Element handle(@RequestPayload Element request) {
        return service.handle(request);
    }
}
