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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.mulesoft.examples.xml_only_soap_webservice.mapper.DwXmlWriter;
import com.mulesoft.examples.xml_only_soap_webservice.mapper.EhrMockResponseMapper;

/**
 * Branch test of {@link EhrMockService}, the {@code choice} of the mock {@code EHRService} flow
 * ({@code xml-only-soap-webservice/src/main/app/mocks.xml:45-84}): {@code operation} =
 * {@code xpath('fn:local-name(/*)')} ({@code mocks.xml:48}); {@code when flowVars.operation == 'createEpisode'}
 * ({@code mocks.xml:50}) runs the {@code createEpisodeResponse} script ({@code mocks.xml:52}), {@code otherwise}
 * ({@code mocks.xml:69}) the {@code findEpisodesResponse} script ({@code mocks.xml:71}).
 *
 * <p>The selection tests run against a mocked {@link EhrMockResponseMapper} and identify the branch by the sentinel
 * element it returns; both scripts read the echoed {@code PatientId} from the request, so each test also verifies
 * that the mapper receives the request instance unchanged. Requests are built with {@code createElementNS} on a
 * namespace-aware document, as the endpoint receives them, so each carries the local name {@code fn:local-name}
 * reads. The real-mapper tests check one response per branch by element names and text values only, on a fixed
 * clock; the exact bytes and the mapper's handling of an empty Body belong to
 * {@code mapper/EhrMockResponseMapperTest}.
 */
@ExtendWith(MockitoExtension.class)
class EhrMockServiceTest {

    private static final String NS0 = "http://www.mule-health.com/SOA/message/1.0";
    private static final String NS1 = "http://www.mule-health.com/SOA/model/1.0";

    /** 2015-01-23T04:10:20.123Z seen from Los Angeles, whose January offset is -08:00. */
    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2015-01-23T04:10:20.123Z"), ZoneId.of("America/Los_Angeles"));

    /** The text every {@code now} takes on {@link #FIXED}. */
    private static final String NOW = "2015-01-22T20:10:20.123-08:00";

    private static final String PATIENT_ID = "P-42";

    @Mock
    private EhrMockResponseMapper mapper;

    private EhrMockService service;

    private Document document;

    private Element createEpisodeSentinel;

    private Element findEpisodesSentinel;

    @BeforeEach
    void setUp() throws ParserConfigurationException {
        document = newDocument(true);
        service = new EhrMockService(mapper);
        createEpisodeSentinel = document.createElementNS(NS0, "ns0:createEpisodeResponse");
        findEpisodesSentinel = document.createElementNS(NS0, "ns0:findEpisodesResponse");
    }

    @Test
    void createEpisodeSelectsCreateEpisodeResponse() {
        assertSelectsCreateEpisodeResponse(request("createEpisode", PATIENT_ID));
    }

    /** {@code fn:local-name(/*)} ignores the namespace and the prefix of the root. */
    @ParameterizedTest(name = "'{'{0}'}'{1}")
    @MethodSource("createEpisodeRootsInAnyNamespace")
    void selectionUsesLocalNameOnly(String namespace, String qualifiedName) {
        assertSelectsCreateEpisodeResponse(document.createElementNS(namespace, qualifiedName));
    }

    static Stream<Arguments> createEpisodeRootsInAnyNamespace() {
        return Stream.of(
                arguments("urn:other", "x:createEpisode"),
                arguments(NS0, "createEpisode"),
                arguments(null, "createEpisode"));
    }

    /**
     * A root built without namespace awareness has no local name, only its qualified node name; the service strips
     * the prefix from it, so the operation is still the name {@code fn:local-name} would report.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ns0:createEpisode", "createEpisode"})
    void namespaceUnawareRootIsReadByTheNameAfterItsPrefix(String nodeName) throws ParserConfigurationException {
        Element request = newDocument(false).createElement(nodeName);
        assertThat(request.getLocalName()).isNull();

        assertSelectsCreateEpisodeResponse(request);
    }

    @Test
    void findEpisodesSelectsFindEpisodesResponse() {
        assertSelectsFindEpisodesResponse(request("findEpisodes", PATIENT_ID));
    }

    /** An empty Body leaves no root, for which {@code fn:local-name} yields the empty string. */
    @Test
    void nullRequestSelectsFindEpisodesResponse() {
        assertSelectsFindEpisodesResponse(null);
    }

    /** MEL {@code ==} compares the whole string exactly: a different case or a longer name is another operation. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"CreateEpisode", "createepisode", "CREATEEPISODE", "createEpisodeResponse",
        "doCreateEpisode"})
    void localNameComparisonIsCaseSensitive(String localName) {
        assertSelectsFindEpisodesResponse(request(localName, PATIENT_ID));
    }

    /** The prefix is not part of the local name, so a prefix spelled {@code createEpisode} selects nothing. */
    @Test
    void prefixNamedCreateEpisodeIsNotTheOperation() {
        assertSelectsFindEpisodesResponse(document.createElementNS(NS0, "createEpisode:findEpisodes"));
    }

    @Test
    void realMapperAnswersCreateEpisodeWithTheEpisodeForThePatient() {
        Element response = realService().handle(request("createEpisode", PATIENT_ID));

        assertThat(response.getLocalName()).isEqualTo("createEpisodeResponse");
        assertThat(response.getNamespaceURI()).isEqualTo(NS0);
        Element episode = only(response, NS1, "Episode");
        assertThat(episode.getParentNode()).isSameAs(response);
        Element patientId = only(episode, NS1, "PatientId");
        assertThat(patientId.getParentNode()).isSameAs(episode);
        assertThat(patientId.getTextContent()).isEqualTo(PATIENT_ID);
        assertThat(only(episode, "*", "episodeId").getTextContent()).isEqualTo(NOW);
        assertThat(only(episode, "*", "admission").getTextContent()).isEqualTo("Elective");
        assertThat(only(episode, "*", "startDate").getTextContent()).isEqualTo(NOW);
        assertThat(only(episode, "*", "endDate").getTextContent()).isEqualTo(NOW);
        assertThat(only(episode, "*", "care").getTextContent()).isEqualTo("Private");
    }

    @Test
    void realMapperAnswersFindEpisodesWithThePatientId() {
        Element response = realService().handle(request("findEpisodes", PATIENT_ID));

        assertThat(response.getLocalName()).isEqualTo("findEpisodesResponse");
        assertThat(response.getNamespaceURI()).isEqualTo(NS0);
        Element episode = only(response, NS1, "Episode");
        assertThat(episode.getParentNode()).isSameAs(response);
        Element patientId = only(episode, NS1, "PatientId");
        assertThat(patientId.getParentNode()).isSameAs(episode);
        assertThat(patientId.getTextContent()).isEqualTo(PATIENT_ID);
        assertThat(episode.getElementsByTagNameNS("*", "episodeId").getLength()).isZero();
    }

    private void assertSelectsCreateEpisodeResponse(Element request) {
        when(mapper.createEpisodeResponse(sameRequest(request))).thenReturn(createEpisodeSentinel);

        assertThat(service.handle(request)).isSameAs(createEpisodeSentinel);

        verify(mapper).createEpisodeResponse(sameRequest(request));
        verify(mapper, never()).findEpisodesResponse(any());
        verifyNoMoreInteractions(mapper);
    }

    private void assertSelectsFindEpisodesResponse(Element request) {
        when(mapper.findEpisodesResponse(sameRequest(request))).thenReturn(findEpisodesSentinel);

        assertThat(service.handle(request)).isSameAs(findEpisodesSentinel);

        verify(mapper).findEpisodesResponse(sameRequest(request));
        verify(mapper, never()).createEpisodeResponse(any());
        verifyNoMoreInteractions(mapper);
    }

    /**
     * Matches the request the service was given: that very instance, or {@code null} for the empty Body. Exactly one
     * Mockito matcher is registered per call, so it may stand in for the single mapper argument.
     */
    private static Element sameRequest(Element request) {
        return request == null ? isNull() : same(request);
    }

    /** Builds {@code ns0:<localName>} holding one {@code ns1:PatientId} with the given text. */
    private Element request(String localName, String patientId) {
        Element request = document.createElementNS(NS0, "ns0:" + localName);
        request.appendChild(document.createElementNS(NS1, "ns1:PatientId")).setTextContent(patientId);
        return request;
    }

    private static EhrMockService realService() {
        return new EhrMockService(new EhrMockResponseMapper(new DwXmlWriter(), FIXED));
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
