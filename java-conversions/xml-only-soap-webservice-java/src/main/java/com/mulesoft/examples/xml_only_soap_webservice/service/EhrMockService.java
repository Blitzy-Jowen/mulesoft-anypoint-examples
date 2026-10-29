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

import com.mulesoft.examples.xml_only_soap_webservice.mapper.EhrMockResponseMapper;

/**
 * The routing of the mock {@code EHRService} flow ({@code xml-only-soap-webservice/src/main/app/mocks.xml:45-84}),
 * which the hospital {@code AdmissionService} orchestration calls to create a patient episode.
 *
 * <p>The original listener's {@code cxf:proxy-service payload="body"} ({@code mocks.xml:47}) hands the flow the
 * SOAP Body content; {@code controller/EhrServiceMockEndpoint} unwraps the same Body root and passes it to
 * {@link #handle(Element)}. The flow then stores {@code xpath('fn:local-name(/*)')} as {@code operation}
 * ({@code mocks.xml:48}) and chooses between two scripts ({@code mocks.xml:49-83}), both owned by
 * {@link EhrMockResponseMapper}, which reads the echoed {@code PatientId} from the request:
 * <ul>
 *   <li>{@code when flowVars.operation == 'createEpisode'} ({@code mocks.xml:50}) → {@code createEpisodeResponse}
 *       ({@code mocks.xml:52});</li>
 *   <li>{@code otherwise} ({@code mocks.xml:69}) → {@code findEpisodesResponse} ({@code mocks.xml:71}),
 *       for {@code findEpisodes}, every other operation, an unknown root name and an empty Body alike.</li>
 * </ul>
 * The flow declares no logger and no exception strategy, so nothing is caught here: a mapper failure reaches
 * {@code config/SoapFaultConfig}, which answers it with the {@code soap:Server} fault.
 *
 * <p>Stateless and thread-safe; the mapper is too.
 */
@Service
public class EhrMockService {

    /** The operation literal of {@code mocks.xml:50}, compared case-sensitively. */
    public static final String CREATE_EPISODE = "createEpisode";

    private final EhrMockResponseMapper ehrMockResponseMapper;

    /**
     * Creates the service.
     *
     * @param ehrMockResponseMapper owner of the two response scripts of {@code mocks.xml:52} and {@code :71}
     */
    public EhrMockService(EhrMockResponseMapper ehrMockResponseMapper) {
        this.ehrMockResponseMapper = ehrMockResponseMapper;
    }

    /**
     * Runs the {@code choice} of {@code mocks.xml:49-83} on one request.
     *
     * @param request the SOAP Body's root element, or {@code null} for an empty Body; passed to the mapper unchanged
     * @return {@code ns0:createEpisodeResponse} ({@code mocks.xml:52}) when the root's local name is
     *         {@value #CREATE_EPISODE} ({@code mocks.xml:50}); otherwise ({@code mocks.xml:69})
     *         {@code ns0:findEpisodesResponse} ({@code mocks.xml:71})
     */
    public Element handle(Element request) {
        String operation = operationOf(request);
        if (CREATE_EPISODE.equals(operation)) {
            return ehrMockResponseMapper.createEpisodeResponse(request);
        }
        return ehrMockResponseMapper.findEpisodesResponse(request);
    }

    /**
     * The flow variable {@code operation} of {@code mocks.xml:48}: XPath {@code fn:local-name(/*)} over the payload,
     * the root element's name without its prefix, neither trimmed nor case-normalized.
     *
     * @param request the SOAP Body's root element, or {@code null}
     * @return the root's local name; the empty string when there is no root, as {@code fn:local-name} yields for an
     *         empty node-set
     */
    static String operationOf(Element request) {
        if (request == null) {
            return "";
        }
        String localName = request.getLocalName();
        if (localName != null) {
            return localName;
        }
        // DOM Level 1 nodes (built without namespace awareness) carry no local name; their node name is the
        // qualified name, so the prefix is stripped to keep local-name() semantics.
        String nodeName = request.getNodeName();
        int colon = nodeName.indexOf(':');
        return colon < 0 ? nodeName : nodeName.substring(colon + 1);
    }
}
