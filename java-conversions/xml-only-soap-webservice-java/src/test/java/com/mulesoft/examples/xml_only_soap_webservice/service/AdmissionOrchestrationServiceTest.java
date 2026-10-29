/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.xml_only_soap_webservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.client.WebServiceTransportException;
import org.springframework.ws.soap.client.SoapFaultClientException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.mulesoft.examples.xml_only_soap_webservice.client.EhrServiceClient;
import com.mulesoft.examples.xml_only_soap_webservice.client.PatientServiceClient;
import com.mulesoft.examples.xml_only_soap_webservice.mapper.AdmitSubjectResponseMapper;
import com.mulesoft.examples.xml_only_soap_webservice.mapper.CreateEpisodeRequestMapper;
import com.mulesoft.examples.xml_only_soap_webservice.mapper.UpsertPatientRequestMapper;

/**
 * Sequence test of {@link AdmissionOrchestrationService}, flow {@code admitPatientService}
 * ({@code xml-only-soap-webservice/src/main/app/Hospital_Admissions_SOA.xml:19-48}) with its sub-flows
 * {@code upsertPatient}, {@code invokePatientService}, {@code createEpisode} and {@code invokeEHRService}
 * ({@code :50-88}): the script of {@code :53}, the PatientService POST, the script of {@code :73}, the EHRService
 * POST and the script of {@code :26}, in that order, each exactly once.
 *
 * <p>Every collaborator is a mock and every stub matches its argument with {@code same(...)}, so a passing test
 * proves that each step receives the very element the previous step returned. The flow declares no exception
 * strategy, so the failure tests assert that each exception reaches the caller as the same instance, after a single
 * attempt, and that no later step runs. Turning those exceptions into {@code soap:Server} faults belongs to SC-11
 * {@code config/SoapFaultConfig}; the mappers' output and the clients' transport have their own tests.
 */
@ExtendWith(MockitoExtension.class)
class AdmissionOrchestrationServiceTest {

    private static final String NS0 = "http://www.mule-health.com/SOA/message/1.0";
    private static final String NS1 = "http://www.mule-health.com/SOA/model/1.0";

    @Mock
    private UpsertPatientRequestMapper upsertPatientRequestMapper;

    @Mock
    private PatientServiceClient patientServiceClient;

    @Mock
    private CreateEpisodeRequestMapper createEpisodeRequestMapper;

    @Mock
    private EhrServiceClient ehrServiceClient;

    @Mock
    private AdmitSubjectResponseMapper admitSubjectResponseMapper;

    private AdmissionOrchestrationService service;

    private Element admitSubject;
    private Element upsertRequest;
    private Element patientResponse;
    private Element episodeRequest;
    private Element ehrResponse;
    private Element admitResponse;

    @BeforeEach
    void setUp() throws ParserConfigurationException {
        // Built by hand rather than with @InjectMocks, so a change in constructor order fails to compile here.
        service = new AdmissionOrchestrationService(upsertPatientRequestMapper, patientServiceClient,
                createEpisodeRequestMapper, ehrServiceClient, admitSubjectResponseMapper);

        Document document = newDocument();
        admitSubject = admitSubject(document);
        upsertRequest = document.createElementNS(NS0, "ns0:upsertPatient");
        patientResponse = document.createElementNS(NS0, "ns0:upsertPatientResponse");
        episodeRequest = document.createElementNS(NS0, "ns0:createEpisode");
        ehrResponse = document.createElementNS(NS0, "ns0:createEpisodeResponse");
        admitResponse = document.createElementNS(NS0, "ns0:admitSubjectResponse");
    }

    @Test
    void admitRunsUpsertThenEpisodeThenResponseInOrder() {
        when(upsertPatientRequestMapper.toElement(same(admitSubject))).thenReturn(upsertRequest);
        when(patientServiceClient.send(same(upsertRequest))).thenReturn(patientResponse);
        when(createEpisodeRequestMapper.toElement(same(patientResponse))).thenReturn(episodeRequest);
        when(ehrServiceClient.send(same(episodeRequest))).thenReturn(ehrResponse);
        when(admitSubjectResponseMapper.toElement(same(ehrResponse))).thenReturn(admitResponse);

        assertThat(service.admit(admitSubject)).isSameAs(admitResponse);

        InOrder inOrder = inOrder(upsertPatientRequestMapper, patientServiceClient, createEpisodeRequestMapper,
                ehrServiceClient, admitSubjectResponseMapper);
        inOrder.verify(upsertPatientRequestMapper).toElement(same(admitSubject));
        inOrder.verify(patientServiceClient).send(same(upsertRequest));
        inOrder.verify(createEpisodeRequestMapper).toElement(same(patientResponse));
        inOrder.verify(ehrServiceClient).send(same(episodeRequest));
        inOrder.verify(admitSubjectResponseMapper).toElement(same(ehrResponse));
        verifyNoMoreCollaboratorInteractions();
    }

    /** An empty request Body reaches the endpoint as {@code null}; the service hands it on without a check. */
    @Test
    void admitPassesNullAdmitSubjectThrough() {
        when(upsertPatientRequestMapper.toElement(isNull())).thenReturn(upsertRequest);
        when(patientServiceClient.send(same(upsertRequest))).thenReturn(patientResponse);
        when(createEpisodeRequestMapper.toElement(same(patientResponse))).thenReturn(episodeRequest);
        when(ehrServiceClient.send(same(episodeRequest))).thenReturn(ehrResponse);
        when(admitSubjectResponseMapper.toElement(same(ehrResponse))).thenReturn(admitResponse);

        assertThat(service.admit(null)).isSameAs(admitResponse);

        InOrder inOrder = inOrder(upsertPatientRequestMapper, patientServiceClient, createEpisodeRequestMapper,
                ehrServiceClient, admitSubjectResponseMapper);
        inOrder.verify(upsertPatientRequestMapper).toElement(isNull());
        inOrder.verify(patientServiceClient).send(same(upsertRequest));
        inOrder.verify(createEpisodeRequestMapper).toElement(same(patientResponse));
        inOrder.verify(ehrServiceClient).send(same(episodeRequest));
        inOrder.verify(admitSubjectResponseMapper).toElement(same(ehrResponse));
        verifyNoMoreCollaboratorInteractions();
    }

    /**
     * A client returns {@code null} for an empty response Body; the next mapper receives it unchanged, as the
     * original's next script received the null payload.
     */
    @Test
    void admitPassesEmptyUpstreamBodiesThrough() {
        when(upsertPatientRequestMapper.toElement(same(admitSubject))).thenReturn(upsertRequest);
        when(patientServiceClient.send(same(upsertRequest))).thenReturn(null);
        when(createEpisodeRequestMapper.toElement(isNull())).thenReturn(episodeRequest);
        when(ehrServiceClient.send(same(episodeRequest))).thenReturn(null);
        when(admitSubjectResponseMapper.toElement(isNull())).thenReturn(admitResponse);

        assertThat(service.admit(admitSubject)).isSameAs(admitResponse);

        InOrder inOrder = inOrder(upsertPatientRequestMapper, patientServiceClient, createEpisodeRequestMapper,
                ehrServiceClient, admitSubjectResponseMapper);
        inOrder.verify(upsertPatientRequestMapper).toElement(same(admitSubject));
        inOrder.verify(patientServiceClient).send(same(upsertRequest));
        inOrder.verify(createEpisodeRequestMapper).toElement(isNull());
        inOrder.verify(ehrServiceClient).send(same(episodeRequest));
        inOrder.verify(admitSubjectResponseMapper).toElement(isNull());
        verifyNoMoreCollaboratorInteractions();
    }

    @Test
    void upsertMapperFailurePropagatesBeforeAnyCall() {
        IllegalStateException failure =
                new IllegalStateException("upsertPatient XML could not be parsed: Premature end of file.");
        when(upsertPatientRequestMapper.toElement(same(admitSubject))).thenThrow(failure);

        assertThatThrownBy(() -> service.admit(admitSubject)).isSameAs(failure);

        verify(upsertPatientRequestMapper, times(1)).toElement(same(admitSubject));
        verifyNoInteractions(patientServiceClient, createEpisodeRequestMapper, ehrServiceClient,
                admitSubjectResponseMapper);
        verifyNoMoreInteractions(upsertPatientRequestMapper);
    }

    /**
     * Each kind of failure the client reports is a single attempt that reaches the caller unchanged; the episode is
     * never requested.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("upstreamFailures")
    void patientServiceFailurePropagatesWithoutRetryAndStopsTheChain(RuntimeException failure) {
        when(upsertPatientRequestMapper.toElement(same(admitSubject))).thenReturn(upsertRequest);
        when(patientServiceClient.send(same(upsertRequest))).thenThrow(failure);

        assertThatThrownBy(() -> service.admit(admitSubject)).isSameAs(failure);

        verify(upsertPatientRequestMapper, times(1)).toElement(same(admitSubject));
        verify(patientServiceClient, times(1)).send(same(upsertRequest));
        verifyNoInteractions(createEpisodeRequestMapper, ehrServiceClient, admitSubjectResponseMapper);
        verifyNoMoreInteractions(upsertPatientRequestMapper, patientServiceClient);
    }

    /** As for the PatientService: one attempt, the same exception, and no {@code admitSubjectResponse} built. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("upstreamFailures")
    void ehrServiceFailurePropagatesWithoutRetryAndSkipsTheResponseMapper(RuntimeException failure) {
        when(upsertPatientRequestMapper.toElement(same(admitSubject))).thenReturn(upsertRequest);
        when(patientServiceClient.send(same(upsertRequest))).thenReturn(patientResponse);
        when(createEpisodeRequestMapper.toElement(same(patientResponse))).thenReturn(episodeRequest);
        when(ehrServiceClient.send(same(episodeRequest))).thenThrow(failure);

        assertThatThrownBy(() -> service.admit(admitSubject)).isSameAs(failure);

        verify(upsertPatientRequestMapper, times(1)).toElement(same(admitSubject));
        verify(patientServiceClient, times(1)).send(same(upsertRequest));
        verify(createEpisodeRequestMapper, times(1)).toElement(same(patientResponse));
        verify(ehrServiceClient, times(1)).send(same(episodeRequest));
        verifyNoInteractions(admitSubjectResponseMapper);
        verifyNoMoreCollaboratorInteractions();
    }

    /** The response mapper's failure for an upstream date it cannot coerce, raised after both calls completed. */
    @Test
    void responseMapperFailurePropagates() {
        IllegalArgumentException failure = new IllegalArgumentException("Cannot coerce endDate value 'x' to :date");
        when(upsertPatientRequestMapper.toElement(same(admitSubject))).thenReturn(upsertRequest);
        when(patientServiceClient.send(same(upsertRequest))).thenReturn(patientResponse);
        when(createEpisodeRequestMapper.toElement(same(patientResponse))).thenReturn(episodeRequest);
        when(ehrServiceClient.send(same(episodeRequest))).thenReturn(ehrResponse);
        when(admitSubjectResponseMapper.toElement(same(ehrResponse))).thenThrow(failure);

        assertThatThrownBy(() -> service.admit(admitSubject)).isSameAs(failure);

        verify(upsertPatientRequestMapper, times(1)).toElement(same(admitSubject));
        verify(patientServiceClient, times(1)).send(same(upsertRequest));
        verify(createEpisodeRequestMapper, times(1)).toElement(same(patientResponse));
        verify(ehrServiceClient, times(1)).send(same(episodeRequest));
        verify(admitSubjectResponseMapper, times(1)).toElement(same(ehrResponse));
        verifyNoMoreCollaboratorInteractions();
    }

    /**
     * The three failures the clients document: a SOAP fault, an HTTP error without a fault, and a refused
     * connection. These cases differ only in the exception thrown, so each client gets one parameterized test over
     * all three rather than one test per kind. The fault is a mock because a real one needs a fault-bearing
     * {@code SoapMessage}.
     */
    static Stream<Arguments> upstreamFailures() {
        return Stream.of(
                arguments(named("SOAP fault", mock(SoapFaultClientException.class))),
                arguments(named("HTTP 500 without fault",
                        new WebServiceTransportException("Internal Server Error [500]"))),
                arguments(named("connection refused",
                        new WebServiceIOException("I/O error: Connection refused",
                                new ConnectException("Connection refused")))));
    }

    private void verifyNoMoreCollaboratorInteractions() {
        verifyNoMoreInteractions(upsertPatientRequestMapper, patientServiceClient, createEpisodeRequestMapper,
                ehrServiceClient, admitSubjectResponseMapper);
    }

    private static Document newDocument() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().newDocument();
    }

    /** The {@code ns0:admitSubject} Body child of {@code src/test/resources/message.xml}, reduced to its subject. */
    private static Element admitSubject(Document document) {
        Element subject = document.createElementNS(NS1, "ns1:Subject");
        subject.appendChild(textElement(document, "firstName", "Nial"));
        subject.appendChild(textElement(document, "lastName", "Darbey"));
        Element request = document.createElementNS(NS0, "ns0:admitSubject");
        request.appendChild(subject);
        return request;
    }

    private static Element textElement(Document document, String localName, String text) {
        Element element = document.createElementNS(null, localName);
        element.setTextContent(text);
        return element;
    }
}
