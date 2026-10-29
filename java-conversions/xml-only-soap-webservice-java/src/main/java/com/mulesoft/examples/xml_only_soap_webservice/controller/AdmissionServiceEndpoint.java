/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.xml_only_soap_webservice.controller;

import org.springframework.stereotype.Component;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;
import org.w3c.dom.Element;

import com.mulesoft.examples.xml_only_soap_webservice.config.WebServiceConfig;
import com.mulesoft.examples.xml_only_soap_webservice.service.AdmissionOrchestrationService;

/**
 * The entry point of flow {@code admitPatientService}
 * ({@code xml-only-soap-webservice/src/main/app/Hospital_Admissions_SOA.xml:16,19-21}): the
 * {@code http:listener path="AdmissionService"} on {@code 0.0.0.0:${http.port}}, which declares no
 * {@code allowedMethods}, and the {@code cxf:proxy-service payload="envelope"} for service {@code AdmissionService},
 * port {@code AdmissionPort}, in namespace {@code http://www.mule-health.com/SOA/service/admission/1.0}.
 * <ul>
 *   <li>{@code payload="envelope"} handed the flow the whole envelope, and its scripts reached the request as
 *       {@code payload.Envelope.Body.admitSubject}; this class receives that node, the Body child
 *       {@code admitSubject}, directly.</li>
 *   <li>Dispatch is by listener path, not by payload root: a CXF proxy service accepted any Body root, so this class
 *       declares no payload-root or SOAP-action mapping. {@link WebServiceConfig}'s
 *       {@code ListenerPathEndpointMapping} hands every request on {@value WebServiceConfig#ADMISSION_PATH} to
 *       {@link #handle(Element)} of the bean named {@value WebServiceConfig#ADMISSION_ENDPOINT}, which is this
 *       class.</li>
 *   <li>The flow's sequence ({@code flow-ref upsertPatient}, {@code flow-ref createEpisode}, the
 *       {@code admitSubjectResponse} script) belongs to {@link AdmissionOrchestrationService}.</li>
 *   <li>The CXF envelope style of the response ({@code soap:} prefix, no empty {@code Header}) is applied by SC-11
 *       {@code config/SoapEnvelopeStyleInterceptor}; an exception escaping the handler becomes a {@code soap:Server}
 *       fault through SC-11 {@code config/SoapFaultConfig}, and an unreadable request a {@code soap:Client} fault
 *       through SC-11 {@code config/SoapListenerBoundaryAdapter}.</li>
 *   <li>{@code GET /AdmissionService?wsdl} serves the carried {@code service/AdmissionService.wsdl} (the proxy's
 *       {@code wsdlLocation}) and {@code ?xsd=} its {@code service/xsd/*.xsd} closure through {@link WebServiceConfig}
 *       and SC-11 {@code config/WsdlQueryPublisher}.</li>
 * </ul>
 * The class holds no mutable state, so one instance serves all requests concurrently.
 */
@Endpoint
@Component(WebServiceConfig.ADMISSION_ENDPOINT)
public class AdmissionServiceEndpoint {

    private final AdmissionOrchestrationService service;

    /**
     * @param service the orchestration of the {@code admitPatientService} flow
     */
    public AdmissionServiceEndpoint(AdmissionOrchestrationService service) {
        this.service = service;
    }

    /**
     * Answers one {@code AdmissionService} request with the flow's response Body content.
     *
     * @param request the SOAP Body's root element, {@code admitSubject}, or {@code null} for an empty Body
     * @return the response Body's root element, {@code admitSubjectResponse}
     */
    public @ResponsePayload Element handle(@RequestPayload Element request) {
        return service.admit(request);
    }
}
