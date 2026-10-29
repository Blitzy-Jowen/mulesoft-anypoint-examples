/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.xml_only_soap_webservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.mulesoft.examples.xml_only_soap_webservice.mapper.DwXmlWriter;
import com.mulesoft.examples.xml_only_soap_webservice.mapper.PatientMockResponseMapper;

/**
 * Branch test of {@link PatientMockService}, the {@code choice} of the mock {@code PatientService} flow
 * ({@code xml-only-soap-webservice/src/main/app/mocks.xml:4-44}): {@code operation} =
 * {@code xpath('fn:local-name(/*)')} ({@code mocks.xml:7}); {@code when flowVars.operation == 'upsertPatient'}
 * ({@code mocks.xml:10}) runs the {@code upsertPatientResponse} script ({@code mocks.xml:12}), {@code otherwise}
 * ({@code mocks.xml:22}) the {@code getPatientResponse} script ({@code mocks.xml:24}).
 *
 * <p>The selection tests run against a mocked {@link PatientMockResponseMapper} and identify the branch by the
 * sentinel element it returns. Requests are built with {@code createElementNS} on a namespace-aware document, as the
 * endpoint receives them, so each carries the local name {@code fn:local-name} reads. The real-mapper tests check
 * one response per branch by element names and text values only, on a fixed clock; the exact bytes belong to
 * {@code mapper/PatientMockResponseMapperTest}.
 */
@ExtendWith(MockitoExtension.class)
class PatientMockServiceTest {

    private static final String NS0 = "http://www.mule-health.com/SOA/message/1.0";
    private static final String NS1 = "http://www.mule-health.com/SOA/model/1.0";

    /** 2015-01-23T04:10:20.123Z seen from Los Angeles, whose January offset is -08:00. */
    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2015-01-23T04:10:20.123Z"), ZoneId.of("America/Los_Angeles"));

    /** The text every {@code now} takes on {@link #FIXED}. */
    private static final String NOW = "2015-01-22T20:10:20.123-08:00";

    @Mock
    private PatientMockResponseMapper mapper;

    private PatientMockService service;

    private Document document;

    private Element upsertPatientSentinel;

    private Element getPatientSentinel;

    @BeforeEach
    void setUp() throws ParserConfigurationException {
        document = newDocument(true);
        service = new PatientMockService(mapper);
        upsertPatientSentinel = document.createElementNS(NS0, "ns0:upsertPatientResponse");
        getPatientSentinel = document.createElementNS(NS0, "ns0:getPatientResponse");
    }

    @Test
    void upsertPatientSelectsUpsertPatientResponse() {
        Element request = document.createElementNS(NS0, "ns0:upsertPatient");
        Element patient = (Element) request.appendChild(document.createElementNS(NS1, "ns1:Patient"));
        patient.appendChild(document.createElementNS(null, "firstName")).setTextContent("Mickey");

        assertSelectsUpsertPatientResponse(request);
    }

    /** {@code fn:local-name(/*)} ignores the namespace and the prefix of the root. */
    @ParameterizedTest(name = "'{'{0}'}'{1}")
    @MethodSource("upsertPatientRootsInAnyNamespace")
    void selectionUsesLocalNameOnly(String namespace, String qualifiedName) {
        assertSelectsUpsertPatientResponse(document.createElementNS(namespace, qualifiedName));
    }

    static Stream<Arguments> upsertPatientRootsInAnyNamespace() {
        return Stream.of(
                arguments("urn:other", "x:upsertPatient"),
                arguments(NS0, "upsertPatient"),
                arguments(null, "upsertPatient"));
    }

    /**
     * A root built without namespace awareness has no local name, only its qualified node name; the service strips
     * the prefix from it, so the operation is still the name {@code fn:local-name} would report.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ns0:upsertPatient", "upsertPatient"})
    void namespaceUnawareRootIsReadByTheNameAfterItsPrefix(String nodeName) throws ParserConfigurationException {
        Element request = newDocument(false).createElement(nodeName);
        assertThat(request.getLocalName()).isNull();

        assertSelectsUpsertPatientResponse(request);
    }

    @Test
    void otherRootSelectsGetPatientResponse() {
        Element request = document.createElementNS(NS0, "ns0:getPatient");
        request.appendChild(document.createElementNS(NS1, "ns1:PatientId")).setTextContent("42");

        assertSelectsGetPatientResponse(request);
    }

    /** An empty Body leaves no root, for which {@code fn:local-name} yields the empty string. */
    @Test
    void nullRequestSelectsGetPatientResponse() {
        assertSelectsGetPatientResponse(null);
    }

    /** MEL {@code ==} compares the whole string exactly: a different case or a longer name is another operation. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"UpsertPatient", "upsertpatient", "UPSERTPATIENT", "upsertPatientResponse",
        "doUpsertPatient"})
    void localNameComparisonIsCaseSensitive(String localName) {
        assertSelectsGetPatientResponse(document.createElementNS(NS0, "ns0:" + localName));
    }

    /** The prefix is not part of the local name, so a prefix spelled {@code upsertPatient} selects nothing. */
    @Test
    void prefixNamedUpsertPatientIsNotTheOperation() {
        assertSelectsGetPatientResponse(document.createElementNS(NS0, "upsertPatient:getPatient"));
    }

    @Test
    void realMapperAnswersUpsertPatientWithPatientIdNow() {
        Element response = realService().handle(document.createElementNS(NS0, "ns0:upsertPatient"));

        assertThat(response.getLocalName()).isEqualTo("upsertPatientResponse");
        assertThat(response.getNamespaceURI()).isEqualTo(NS0);
        Element patientId = only(response, NS1, "PatientId");
        assertThat(patientId.getParentNode()).isSameAs(response);
        assertThat(patientId.getTextContent()).isEqualTo(NOW);
    }

    /** {@code null} stands for the empty Body; {@code getPatient} for the operation the WSDL declares beside upsert. */
    @ParameterizedTest(name = "root {0}")
    @NullSource
    @ValueSource(strings = "getPatient")
    void realMapperAnswersOtherwiseWithTheFixedPatient(String localName) {
        Element request = localName == null ? null : document.createElementNS(NS0, "ns0:" + localName);

        Element response = realService().handle(request);

        assertThat(response.getLocalName()).isEqualTo("getPatientResponse");
        assertThat(response.getNamespaceURI()).isEqualTo(NS0);
        Element patient = only(response, NS1, "Patient");
        assertThat(only(patient, "*", "firstName").getTextContent()).isEqualTo("Donald");
        assertThat(only(patient, "*", "lastName").getTextContent()).isEqualTo("Duck");
        assertThat(only(patient, "*", "dateOfBirth").getTextContent()).isEqualTo("1930-01-01");
        assertThat(only(patient, "*", "patientId").getTextContent()).isEqualTo(NOW);
        assertThat(only(patient, "*", "nationalId").getTextContent()).isEqualTo(NOW);
    }

    private void assertSelectsUpsertPatientResponse(Element request) {
        when(mapper.upsertPatientResponse()).thenReturn(upsertPatientSentinel);

        assertThat(service.handle(request)).isSameAs(upsertPatientSentinel);

        verify(mapper).upsertPatientResponse();
        verify(mapper, never()).getPatientResponse();
        verifyNoMoreInteractions(mapper);
    }

    private void assertSelectsGetPatientResponse(Element request) {
        when(mapper.getPatientResponse()).thenReturn(getPatientSentinel);

        assertThat(service.handle(request)).isSameAs(getPatientSentinel);

        verify(mapper).getPatientResponse();
        verify(mapper, never()).upsertPatientResponse();
        verifyNoMoreInteractions(mapper);
    }

    private static PatientMockService realService() {
        return new PatientMockService(new PatientMockResponseMapper(new DwXmlWriter(), FIXED));
    }

    private static Element only(Element parent, String namespace, String localName) {
        NodeList matches = parent.getElementsByTagNameNS(namespace, localName);
        assertThat(matches.getLength()).as("{%s}%s under %s", namespace, localName, parent.getLocalName())
                .isEqualTo(1);
        return (Element) matches.item(0);
    }

    private static Document newDocument(boolean namespaceAware) throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setNamespaceAware(namespaceAware);
        return factory.newDocumentBuilder().newDocument();
    }
}
