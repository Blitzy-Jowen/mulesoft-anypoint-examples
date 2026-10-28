/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.xml_only_soap_webservice.service;

import org.springframework.stereotype.Service;
import org.w3c.dom.Element;

import com.mulesoft.examples.xml_only_soap_webservice.client.EhrServiceClient;
import com.mulesoft.examples.xml_only_soap_webservice.client.PatientServiceClient;
import com.mulesoft.examples.xml_only_soap_webservice.mapper.AdmitSubjectResponseMapper;
import com.mulesoft.examples.xml_only_soap_webservice.mapper.CreateEpisodeRequestMapper;
import com.mulesoft.examples.xml_only_soap_webservice.mapper.UpsertPatientRequestMapper;

/**
 * The hospital pre-admission orchestration of flow {@code admitPatientService}
 * ({@code xml-only-soap-webservice/src/main/app/Hospital_Admissions_SOA.xml:19-48}) and the sub-flows it reaches:
 * {@code upsertPatient} ({@code :50-64}), {@code invokePatientService} ({@code :66-70}), {@code createEpisode}
 * ({@code :71-83}) and {@code invokeEHRService} ({@code :85-88}). Each {@code flow-ref} is a method call and the
 * processors run as statements in their original order, so the two outbound POSTs happen in the original sequence:
 * <ol>
 *   <li>{@code flow-ref upsertPatient} ({@code :23}): the script of {@code :53} builds {@code ns0:upsertPatient}
 *       from the request's {@code Subject} ({@link UpsertPatientRequestMapper}), and {@code invokePatientService}
 *       POSTs it to the PatientService and yields the response Body root {@code upsertPatientResponse}
 *       ({@link PatientServiceClient});</li>
 *   <li>{@code flow-ref createEpisode} ({@code :24}): the script of {@code :73} builds {@code ns0:createEpisode}
 *       from the returned {@code PatientId} ({@link CreateEpisodeRequestMapper}), and {@code invokeEHRService} POSTs
 *       it to the EHRService and yields {@code createEpisodeResponse} ({@link EhrServiceClient});</li>
 *   <li>the script of {@code :26} turns the returned Episode into {@code admitSubjectResponse}
 *       ({@link AdmitSubjectResponseMapper}), the flow's final payload, which the SOAP layer wraps in the response
 *       envelope.</li>
 * </ol>
 * The original listener's {@code cxf:proxy-service payload="envelope"} ({@code :21}) handed the first script the
 * whole envelope; {@code controller/AdmissionServiceEndpoint} passes the Body child {@code admitSubject}, the node
 * that script reaches through {@code Envelope.Body}.
 *
 * <p>The flow declares no logger, choice or exception strategy, so this class catches, logs and retries nothing and
 * adds no null check: an empty response Body ({@code null} from a client) reaches the next mapper as the original's
 * null payload did, and every exception (a {@code SoapFaultClientException} from a mock's fault, a
 * {@code WebServiceTransportException} or {@code WebServiceIOException} from the transport, an
 * {@link IllegalArgumentException} for an upstream date that cannot be read) propagates unchanged to SC-11
 * {@code config/SoapFaultConfig}, which answers it with the {@code soap:Server} fault. Data shaping belongs to the
 * mappers and transport to the clients; no XML is built here.
 *
 * <p>Stateless and thread-safe: every field is a final reference to a thread-safe collaborator.
 */
@Service
public class AdmissionOrchestrationService {

    private final UpsertPatientRequestMapper upsertPatientRequestMapper;
    private final PatientServiceClient patientServiceClient;
    private final CreateEpisodeRequestMapper createEpisodeRequestMapper;
    private final EhrServiceClient ehrServiceClient;
    private final AdmitSubjectResponseMapper admitSubjectResponseMapper;

    /**
     * Creates the service.
     *
     * @param upsertPatientRequestMapper owner of the {@code upsertPatient} script ({@code :53})
     * @param patientServiceClient       the {@code invokePatientService} call ({@code :66-70})
     * @param createEpisodeRequestMapper owner of the {@code createEpisode} script ({@code :73})
     * @param ehrServiceClient           the {@code invokeEHRService} call ({@code :85-88})
     * @param admitSubjectResponseMapper owner of the {@code admitSubjectResponse} script ({@code :26})
     */
    public AdmissionOrchestrationService(UpsertPatientRequestMapper upsertPatientRequestMapper,
            PatientServiceClient patientServiceClient,
            CreateEpisodeRequestMapper createEpisodeRequestMapper,
            EhrServiceClient ehrServiceClient,
            AdmitSubjectResponseMapper admitSubjectResponseMapper) {
        this.upsertPatientRequestMapper = upsertPatientRequestMapper;
        this.patientServiceClient = patientServiceClient;
        this.createEpisodeRequestMapper = createEpisodeRequestMapper;
        this.ehrServiceClient = ehrServiceClient;
        this.admitSubjectResponseMapper = admitSubjectResponseMapper;
    }

    /**
     * Runs flow {@code admitPatientService} on one AdmissionService request.
     *
     * @param admitSubject the SOAP Body child the endpoint received, {@code admitSubject}, or {@code null} for an
     *        empty Body
     * @return the {@code ns0:admitSubjectResponse} element, the response Body content
     */
    public Element admit(Element admitSubject) {
        Element upsertPatientResponse = upsertPatient(admitSubject);
        Element createEpisodeResponse = createEpisode(upsertPatientResponse);
        return admitSubjectResponseMapper.toElement(createEpisodeResponse);
    }

    /**
     * Sub-flow {@code upsertPatient} ({@code :50-64}): the script of {@code :53}, then {@code invokePatientService}.
     */
    private Element upsertPatient(Element admitSubject) {
        Element upsertPatient = upsertPatientRequestMapper.toElement(admitSubject);
        return patientServiceClient.send(upsertPatient);
    }

    /**
     * Sub-flow {@code createEpisode} ({@code :71-83}): the script of {@code :73}, then {@code invokeEHRService}.
     */
    private Element createEpisode(Element upsertPatientResponse) {
        Element createEpisode = createEpisodeRequestMapper.toElement(upsertPatientResponse);
        return ehrServiceClient.send(createEpisode);
    }
}
